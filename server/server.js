require('dotenv').config();
const WebSocket = require('ws');
const admin = require('firebase-admin');
const fs = require('fs');
const path = require('path');
const https = require('https');

// ─── 配置 ───────────────────────────────────────────────────────────
const PORT = parseInt(process.env.WS_PORT, 10) || 9527;
const FCM_KEY_PATH = process.env.FCM_KEY_PATH;
const PING_INTERVAL = 30000;
const MAX_PAYLOAD_SIZE = 50 * 1024 * 1024; // 50MB (for photos)

// 连接存储
const clients = new Map();

// ─── HMS Push 配置 ─────────────────────────────────────────────────
let hmsCreds = null;
const HMS_CONFIG_PATH = path.join(path.dirname(FCM_KEY_PATH || '.'), 'agconnect-services.json');
if (fs.existsSync(HMS_CONFIG_PATH)) {
    try {
        hmsCreds = JSON.parse(fs.readFileSync(HMS_CONFIG_PATH, 'utf8'));
        console.log('[HMS] Huawei Push config loaded');
    } catch (e) {
        console.log('[HMS] Failed to load Huawei config:', e.message);
    }
}

// ├── OAuth2 token 缓存 ────────────────────────────────────────────
let hmsTokenCache = { token: null, expires: 0 };

function getHMSToken() {
    return new Promise((resolve, reject) => {
        // 缓存未过期直接返回
        if (hmsTokenCache.token && Date.now() < hmsTokenCache.expires) {
            return resolve(hmsTokenCache.token);
        }
        if (!hmsCreds) return reject('No HMS config');

        const clientId = hmsCreds.client.client_id;
        const clientSecret = hmsCreds.client.client_secret;
        const postData = `grant_type=client_credentials&client_id=${encodeURIComponent(clientId)}&client_secret=${encodeURIComponent(clientSecret)}`;

        const req = https.request({
            hostname: 'oauth-login.cloud.huawei.com',
            path: '/oauth2/v2/token',
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
                'Content-Length': Buffer.byteLength(postData)
            }
        }, (res) => {
            let data = '';
            res.on('data', c => data += c);
            res.on('end', () => {
                try {
                    const r = JSON.parse(data);
                    if (r.access_token) {
                        hmsTokenCache = { token: r.access_token, expires: Date.now() + (r.expires_in || 3600) * 1000 - 60000 };
                        resolve(r.access_token);
                    } else {
                        reject(r.error_description || 'Unknown error');
                    }
                } catch (e) { reject(e.message); }
            });
        });
        req.on('error', reject);
        req.write(postData);
        req.end();
    });
}

function sendHMS(token, payload) {
    return new Promise(async (resolve, reject) => {
        try {
            const accessToken = await getHMSToken();
            const appId = hmsCreds.client.app_id || hmsCreds.client.client_id;
            const msgBody = JSON.stringify({
                message: {
                    token: [token],
                    data: JSON.stringify({
                        type: 'reminder',
                        from: payload.from || 'pc',
                        title: payload.title || '新提醒',
                        body: payload.body || '',
                        pin: String(payload.pin || false),
                        pinExpiry: String(payload.pinExpiry || 0),
                        timestamp: String(payload.timestamp || Date.now())
                    }),
                    android: {
                        notification: {
                            title: payload.title || '新提醒',
                            body: payload.body || '收到来自 PC 端的提醒'
                        }
                    }
                }
            });

            const req = https.request({
                hostname: 'push-api.cloud.huawei.com',
                path: `/v1/${appId}/messages:send`,
                method: 'POST',
                headers: {
                    'Authorization': `Bearer ${accessToken}`,
                    'Content-Type': 'application/json',
                    'Content-Length': Buffer.byteLength(msgBody)
                }
            }, (res) => {
                let data = '';
                res.on('data', c => data += c);
                res.on('end', () => {
                    try {
                        const r = JSON.parse(data);
                        if (r.code === '80000000') {
                            console.log('[HMS] Push sent:', r.requestId);
                            resolve(r);
                        } else {
                            console.error('[HMS] Push error:', r.code, r.msg);
                            reject(r.msg);
                        }
                    } catch (e) { reject(e.message); }
                });
            });
            req.on('error', reject);
            req.write(msgBody);
            req.end();
        } catch (e) { reject(e); }
    });
}

// ─── Firebase Admin 初始化 ──────────────────────────────────────────
let fcmAvailable = false;
if (FCM_KEY_PATH && fs.existsSync(FCM_KEY_PATH)) {
    try {
        const serviceAccount = require(path.resolve(FCM_KEY_PATH));
        admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
        fcmAvailable = true;
        console.log('[FCM] Firebase Admin initialized successfully');
    } catch (err) {
        console.error('[FCM] Failed to initialize Firebase Admin:', err.message);
    }
} else {
    console.log('[FCM] No FCM key found, WebSocket-only mode');
}

// ─── WebSocket 服务器 ───────────────────────────────────────────────
const wss = new WebSocket.Server({ port: PORT, maxPayload: MAX_PAYLOAD_SIZE, clientTracking: false });

function generateId(type) {
    const ts = Date.now().toString(36);
    const rand = Math.random().toString(36).substring(2, 6);
    return `${type}_${ts}${rand}`;
}

function broadcast(type, message, excludeId) {
    let sent = 0;
    for (const [id, client] of clients) {
        if (id === excludeId) continue;
        if (client.deviceType !== type) continue;
        if (client.ws.readyState === WebSocket.OPEN) {
            client.ws.send(JSON.stringify(message));
            sent++;
        }
    }
    return sent;
}

function getStats() {
    let pc = 0, android = 0;
    for (const client of clients.values()) {
        if (client.deviceType === 'pc') pc++;
        else android++;
    }
    return { pc, android, total: pc + android };
}

// ─── 消息处理 ───────────────────────────────────────────────────────
function handleMessage(senderId, senderWs, raw) {
    let msg;
    try { msg = JSON.parse(raw); } catch { senderWs.send(JSON.stringify({ type: 'error', message: 'Invalid JSON' })); return; }

    const sender = clients.get(senderId);
    if (!sender) return;

    switch (msg.type) {
        case 'register_fcm': {
            if (sender.deviceType === 'android' && msg.token) {
                sender.fcmToken = msg.token;
                console.log(`[Push] Token registered for ${senderId}`);
                senderWs.send(JSON.stringify({ type: 'fcm_registered' }));
            }
            break;
        }

        case 'reminder': {
            const target = msg.target;
            const payload = {
                type: 'reminder',
                from: sender.deviceType,
                fromId: senderId,
                title: msg.title || '新提醒',
                body: msg.body || (sender.deviceType === 'pc' ? 'PC 端发来提醒' : '手机端发来提醒'),
                pin: msg.pin || false,
                pinExpiry: msg.pinExpiry || 0,
                timestamp: Date.now(),
            };

            const sentDirect = broadcast(target, payload);

            // 若目标是 android 且无 WS 在线 → 尝试推送到手机
            if (target === 'android' && sentDirect === 0) {
                let pushed = 0;
                for (const [id, client] of clients) {
                    if (client.deviceType === 'android' && client.fcmToken) {
                        const token = client.fcmToken;
                        // 先试 HMS（华为手机）
                        if (hmsCreds) {
                            sendHMS(token, payload).then(() => pushed++).catch(err => {
                                console.log(`[HMS] Failed for ${id}: ${err.message}`);
                                // HMS 失败后用 FCM
                                if (fcmAvailable) {
                                    sendFCM(token, payload).then(() => pushed++).catch(() => {});
                                }
                            });
                        } else if (fcmAvailable) {
                            // 没有 HMS 就用 FCM
                            sendFCM(token, payload).then(() => pushed++).catch(() => {});
                        }
                    }
                }
                console.log(`[Reminder] ${senderId} → android (HMS/FCM push)`);
            } else {
                console.log(`[Reminder] ${senderId} → ${target} (WS:${sentDirect})`);
            }

            senderWs.send(JSON.stringify({
                type: 'delivery_status', target,
                sentDirect, fcmTriggered: target === 'android' && sentDirect === 0 && (fcmAvailable || hmsCreds),
            }));
            break;
        }

        case 'ping': {
            senderWs.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
            break;
        }

        // ── 照片转发 ──
        case 'photo': {
            const target = msg.target || 'android';
            const photoPayload = {
                type: 'photo',
                data: msg.data,
                name: msg.name || 'photo.jpg',
                from: sender.deviceType,
                fromId: senderId,
                timestamp: Date.now(),
            };
            const sent = broadcast(target, photoPayload);
            senderWs.send(JSON.stringify({ type: 'delivery_status', target, sentDirect: sent }));
            console.log(`[Photo] ${senderId} → ${target} (${(msg.data || '').length} bytes)`);
            break;
        }

        default:
            senderWs.send(JSON.stringify({ type: 'error', message: `Unknown message type: ${msg.type}` }));
    }
}

// ─── FCM 推送（Google，备用） ──────────────────────────────────────
function sendFCM(token, payload) {
    const message = {
        token,
        data: {
            type: 'reminder',
            from: payload.from || 'pc',
            title: payload.title || '新提醒',
            body: payload.body || '',
            pin: String(payload.pin || false),
            pinExpiry: String(payload.pinExpiry || 0),
            timestamp: String(payload.timestamp || Date.now()),
        },
        android: { priority: 'high', ttl: 86400000 },
    };
    return admin.messaging().send(message);
}

// ─── 心跳 ───────────────────────────────────────────────────────────
function startHeartbeat() {
    setInterval(() => {
        const now = Date.now();
        for (const [id, client] of clients) {
            if (client.ws.readyState !== WebSocket.OPEN) { clients.delete(id); continue; }
            if (client._lastPong && now - client._lastPong > 60000) {
                console.log(`[Heartbeat] ${id} timed out`);
                client.ws.terminate();
                clients.delete(id);
                continue;
            }
            if (!client._lastPong) client._lastPong = now;
            client.ws.ping(() => {});
        }
    }, PING_INTERVAL);
}

// ─── 连接事件 ───────────────────────────────────────────────────────
wss.on('connection', (ws, req) => {
    const url = new URL(req.url, 'http://localhost');
    const clientType = url.searchParams.get('type') === 'pc' ? 'pc' : 'android';
    const clientId = generateId(clientType);

    const clientInfo = { ws, deviceType: clientType, fcmToken: null, connectedAt: new Date(), _lastPong: Date.now(), remoteAddr: req.socket.remoteAddress };
    clients.set(clientId, clientInfo);
    console.log(`[Connect] ${clientType} | ${clientId} | ${clientInfo.remoteAddr}`);

    ws.send(JSON.stringify({ type: 'welcome', clientId, deviceType: clientType, stats: getStats() }));

    broadcast(clientType === 'pc' ? 'android' : 'pc', { type: 'peer_status', online: getStats() }, clientId);
    broadcast(clientType, { type: 'peer_status', online: getStats() }, clientId);

    ws.on('message', (data) => handleMessage(clientId, ws, data));
    ws.on('pong', () => { const c = clients.get(clientId); if (c) c._lastPong = Date.now(); });
    ws.on('close', () => {
        clients.delete(clientId);
        console.log(`[Disconnect] ${clientType} | ${clientId}`);
        const stats = getStats();
        for (const client of clients.values()) {
            if (client.ws.readyState === WebSocket.OPEN) client.ws.send(JSON.stringify({ type: 'peer_status', online: stats }));
        }
    });
    ws.on('error', (err) => { console.error(`[Error] ${clientId}:`, err.message); clients.delete(clientId); });
});

// ─── 优雅关闭 ───────────────────────────────────────────────────────
function gracefulShutdown(signal) {
    console.log(`\n[Server] Received ${signal}, shutting down...`);
    const shutdownMsg = JSON.stringify({ type: 'server_shutdown' });
    for (const client of clients.values()) {
        if (client.ws.readyState === WebSocket.OPEN) client.ws.send(shutdownMsg);
    }
    wss.close(() => process.exit(0));
    setTimeout(() => process.exit(1), 5000);
}
process.on('SIGINT', () => gracefulShutdown('SIGINT'));
process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));

// ─── 启动 ───────────────────────────────────────────────────────────
startHeartbeat();
console.log('═══════════════════════════════════════════');
console.log('  梦柒兮 Signaling Server v1.0');
console.log(`  Port: ${PORT}`);
console.log(`  FCM:  ${fcmAvailable ? '✅ Enabled' : '❌ Disabled'}`);
console.log(`  HMS:  ${hmsCreds ? '✅ Enabled' : '❌ Disabled'}`);
console.log('═══════════════════════════════════════════');
