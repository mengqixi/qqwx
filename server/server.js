require('dotenv').config();
const WebSocket = require('ws');
const admin = require('firebase-admin');
const fs = require('fs');
const path = require('path');

// ─── 配置 ───────────────────────────────────────────────────────────
const PORT = parseInt(process.env.WS_PORT, 10) || 9527;
const FCM_KEY_PATH = process.env.FCM_KEY_PATH;
const PING_INTERVAL = 30000;  // 心跳间隔 30s
const MAX_PAYLOAD_SIZE = 4096;

// 连接存储
const clients = new Map();  // Map<clientId, { ws, deviceType, fcmToken, connectedAt }>

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
        console.log('[FCM] FCM push will be disabled. WebSocket-only mode.');
    }
} else {
    console.log('[FCM] No FCM key found at:', FCM_KEY_PATH);
    console.log('[FCM] FCM push will be disabled. WebSocket-only mode.');
}

// ─── WebSocket 服务器 ───────────────────────────────────────────────
const wss = new WebSocket.Server({
    port: PORT,
    maxPayload: MAX_PAYLOAD_SIZE,
    clientTracking: false,
});

// ─── 连接管理 ───────────────────────────────────────────────────────
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
    try {
        msg = JSON.parse(raw);
    } catch {
        senderWs.send(JSON.stringify({ type: 'error', message: 'Invalid JSON' }));
        return;
    }

    const sender = clients.get(senderId);
    if (!sender) return;

    switch (msg.type) {
        case 'register_fcm': {
            if (sender.deviceType === 'android' && msg.token) {
                sender.fcmToken = msg.token;
                console.log(`[FCM] Token registered for ${senderId}`);
                senderWs.send(JSON.stringify({ type: 'fcm_registered' }));
            }
            break;
        }

        case 'reminder': {
            const target = msg.target; // 'pc' | 'android'
            const payload = {
                type: 'reminder',
                from: sender.deviceType,
                fromId: senderId,
                title: msg.title || '新提醒',
                body: msg.body || (sender.deviceType === 'pc' ? 'PC 端发来提醒' : '手机端发来提醒'),
                timestamp: Date.now(),
            };

            // 1) WebSocket 直推
            const sentDirect = broadcast(target, payload);

            // 2) 若目标是 android 且无 WS 在线 → FCM 推送
            if (target === 'android' && sentDirect === 0 && fcmAvailable) {
                let fcmSent = 0;
                for (const [id, client] of clients) {
                    if (client.deviceType === 'android' && client.fcmToken) {
                        sendFCM(client.fcmToken, payload);
                        fcmSent++;
                    }
                }
                console.log(`[Reminder] ${senderId} → android (FCM:${fcmSent})`);
            } else {
                console.log(`[Reminder] ${senderId} → ${target} (WS:${sentDirect})`);
            }

            // 回复发送者投递状态
            senderWs.send(JSON.stringify({
                type: 'delivery_status',
                target,
                sentDirect,
                fcmTriggered: target === 'android' && sentDirect === 0 && fcmAvailable,
            }));
            break;
        }

        case 'ping': {
            senderWs.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
            break;
        }

        default:
            senderWs.send(JSON.stringify({ type: 'error', message: `Unknown message type: ${msg.type}` }));
    }
}

// ─── FCM 推送 ───────────────────────────────────────────────────────
function sendFCM(token, payload) {
    const message = {
        token,
        data: {
            type: 'reminder',
            from: payload.from || 'pc',
            title: payload.title || '新提醒',
            body: payload.body || '',
            timestamp: String(payload.timestamp || Date.now()),
        },
        android: {
            priority: 'high',
            ttl: 86400000, // 24h
        },
    };
    admin.messaging().send(message)
        .then(result => console.log(`[FCM] Sent: ${result}`))
        .catch(err => {
            if (err.code === 'messaging/registration-token-not-registered') {
                // Token 过期，清理
                for (const [id, client] of clients) {
                    if (client.fcmToken === token) {
                        client.fcmToken = null;
                        console.log(`[FCM] Removed stale token for ${id}`);
                    }
                }
            }
            console.error('[FCM] Send error:', err.message);
        });
}

// ─── 心跳 ───────────────────────────────────────────────────────────
function startHeartbeat() {
    const interval = setInterval(() => {
        const now = Date.now();
        for (const [id, client] of clients) {
            if (client.ws.readyState !== WebSocket.OPEN) {
                clients.delete(id);
                continue;
            }
            // 等待 pong，如果上次 ping 超过 60s 无回应则断开
            if (client._lastPong && now - client._lastPong > 60000) {
                console.log(`[Heartbeat] ${id} timed out, terminating`);
                client.ws.terminate();
                clients.delete(id);
                continue;
            }
            if (!client._lastPong) {
                client._lastPong = now;
            }
            client.ws.ping(() => {});
        }
    }, PING_INTERVAL);
    wss._heartbeatTimer = interval;
}

// ─── 连接事件 ───────────────────────────────────────────────────────
wss.on('connection', (ws, req) => {
    // 从 URL 查询参数识别客户端类型: ?type=pc 或 ?type=android
    const url = new URL(req.url, 'http://localhost');
    const clientType = url.searchParams.get('type') === 'pc' ? 'pc' : 'android';
    const clientId = generateId(clientType);

    const clientInfo = {
        ws,
        deviceType: clientType,
        fcmToken: null,
        connectedAt: new Date(),
        _lastPong: Date.now(),
        remoteAddr: req.socket.remoteAddress,
    };
    clients.set(clientId, clientInfo);

    console.log(`[Connect] ${clientType} | ${clientId} | ${clientInfo.remoteAddr}`);

    // 发送欢迎消息
    ws.send(JSON.stringify({
        type: 'welcome',
        clientId,
        deviceType: clientType,
        stats: getStats(),
    }));

    // 广播在线人数变化
    broadcast(clientType === 'pc' ? 'android' : 'pc', {
        type: 'peer_status',
        online: getStats(),
    }, clientId);
    // 同端也广播
    broadcast(clientType, { type: 'peer_status', online: getStats() }, clientId);

    // ── 消息接收 ──
    ws.on('message', (data) => {
        handleMessage(clientId, ws, data);
    });

    // ── Pong ──
    ws.on('pong', () => {
        const client = clients.get(clientId);
        if (client) client._lastPong = Date.now();
    });

    // ── 断开 ──
    ws.on('close', () => {
        clients.delete(clientId);
        console.log(`[Disconnect] ${clientType} | ${clientId}`);

        // 广播在线人数
        const stats = getStats();
        for (const client of clients.values()) {
            if (client.ws.readyState === WebSocket.OPEN) {
                client.ws.send(JSON.stringify({ type: 'peer_status', online: stats }));
            }
        }
    });

    ws.on('error', (err) => {
        console.error(`[Error] ${clientId}:`, err.message);
        clients.delete(clientId);
    });
});

// ─── 优雅关闭 ───────────────────────────────────────────────────────
function gracefulShutdown(signal) {
    console.log(`\n[Server] Received ${signal}, shutting down gracefully...`);

    if (wss._heartbeatTimer) clearInterval(wss._heartbeatTimer);

    // 通知所有客户端
    const shutdownMsg = JSON.stringify({ type: 'server_shutdown' });
    for (const client of clients.values()) {
        if (client.ws.readyState === WebSocket.OPEN) {
            client.ws.send(shutdownMsg);
        }
    }

    wss.close(() => {
        console.log('[Server] All connections closed');
        process.exit(0);
    });

    // 强制退出
    setTimeout(() => {
        console.error('[Server] Forced shutdown');
        process.exit(1);
    }, 5000);
}

process.on('SIGINT', () => gracefulShutdown('SIGINT'));
process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));

// ─── 启动 ───────────────────────────────────────────────────────────
startHeartbeat();

console.log('═══════════════════════════════════════════');
console.log('  梦柒兮 Signaling Server v1.0');
console.log(`  Port: ${PORT}`);
console.log(`  FCM:  ${fcmAvailable ? '✅ Enabled' : '❌ Disabled (WebSocket only)'}`);
console.log('═══════════════════════════════════════════');
