// ─── DOM ────────────────────────────────────────────────────────────
const $ = (s) => document.querySelector(s);
const $$ = (s) => document.querySelectorAll(s);

const statusDot = $('#statusDot');
const statusText = $('#statusText');
const clientIdSpan = $('#clientId');
const pcCount = $('#pcCount');
const androidCount = $('#androidCount');
const btnRemind = $('#btnRemind');
const btnClearLog = $('#btnClearLog');
const reminderBody = $('#reminderBody');
const messageList = $('#messageList');
const logCount = $('#logCount');
const btnSettings = $('#btnSettings');
const settingsModal = $('#settingsModal');
const serverUrlInput = $('#serverUrl');
const btnReconnect = $('#btnReconnect');
const btnSaveSettings = $('#btnSaveSettings');
const btnCloseModal = $('#btnCloseModal');
const btnMinimize = $('#btnMinimize');
const btnClose = $('#btnClose');
const logEmpty = document.querySelector('.log-empty');
const btnAttach = $('#btnAttach');
const photoInput = $('#photoInput');
const photoStatus = $('#photoStatus');

// ─── Pin elements ─────────────────────────────────────────────────
const pinCheckbox = $('#pinCheckbox');
const pinDurationGroup = $('#pinDurationGroup');
const pinDurationBtns = $$('.pin-duration-btn');

let ws = null;
let reconnectTimer = null;
let clientId = null;
let msgCount = 0;
let messages = []; // In-memory message store: [{type, body, time, timestamp, pinned, pinExpiry, photoBase64?, name?, from?}]
let pinDurationHours = 6; // Default

// ─── 配置 ──────────────────────────────────────────────────────────
async function initConfig() {
    try {
        const cfg = await window.electronAPI.getConfig();
        serverUrlInput.value = cfg.serverUrl;
    } catch { /* Electron not available */ }
    if (!serverUrlInput.value) {
        serverUrlInput.value = 'wss://47.110.65.93:9528/ws?type=pc';
    }
}

function saveServerUrl(url) {
    try { window.electronAPI.setConfig({ serverUrl: url }); }
    catch { localStorage.setItem('serverUrl', url); }
}

// ─── 消息持久化 ──────────────────────────────────────────────────
async function loadMessages() {
    try {
        const saved = await window.electronAPI.getMessages();
        if (saved && Array.isArray(saved)) {
            messages = saved;
            return true;
        }
    } catch {}
    return false;
}

async function persistMessage(msg) {
    try {
        await window.electronAPI.saveMessage(msg);
    } catch {}
}

async function clearStoredMessages() {
    try {
        await window.electronAPI.clearMessages();
    } catch {}
}

// ─── 消息渲染 ────────────────────────────────────────────────────
function renderAllMessages() {
    // Clear existing messages (keep log-empty)
    messageList.querySelectorAll('.msg').forEach(el => el.remove());

    // Filter/expire old pins
    const now = Date.now();
    messages = messages.filter(m => {
        if (m.pinned && m.pinExpiry > 0 && now >= m.pinExpiry) {
            m.pinned = false;
            m.pinExpiry = 0;
        }
        return true;
    });

    // Sort: pinned first (by pinExpiry ascending), then by timestamp descending
    const sorted = [...messages].sort((a, b) => {
        // Active pins at top
        const aPin = a.pinned && a.pinExpiry > now ? 1 : 0;
        const bPin = b.pinned && b.pinExpiry > now ? 1 : 0;
        if (aPin !== bPin) return bPin - aPin;
        // Then chronologically
        return (a.timestamp || 0) - (b.timestamp || 0);
    });

    msgCount = sorted.length;
    logCount.textContent = msgCount;

    if (msgCount === 0) {
        if (logEmpty) logEmpty.classList.remove('hidden');
        return;
    }
    if (logEmpty) logEmpty.classList.add('hidden');

    sorted.forEach(msg => {
        const item = createMessageElement(msg);
        messageList.appendChild(item);
    });

    messageList.scrollTop = messageList.scrollHeight;
}

function createMessageElement(msg) {
    const item = document.createElement('div');
    const isPinned = msg.pinned && msg.pinExpiry > Date.now();

    if (msg.type === 'system') {
        item.className = 'msg msg-system';
        item.textContent = msg.body || msg.title || '';
        return item;
    }

    // Determine class
    let typeClass = 'msg-received';
    if (msg.type === 'sent' || msg.type === 'photo_sent') typeClass = 'msg-sent';
    if (isPinned) typeClass += ' msg-pinned';

    item.className = `msg ${typeClass}`;

    // Title line
    const titleEl = document.createElement('div');
    titleEl.className = 'msg-title';
    if (isPinned) titleEl.classList.add('pinned');

    if (isPinned) {
        const badge = document.createElement('span');
        badge.className = 'pin-badge';
        badge.textContent = '📌';
        titleEl.appendChild(badge);

        const remaining = msg.pinExpiry - Date.now();
        const hoursLeft = Math.floor(remaining / 3600000);
        const minsLeft = Math.floor((remaining % 3600000) / 60000);
        const timeStr = hoursLeft > 0 ? `${hoursLeft}h${minsLeft}m` : `${minsLeft}m`;

        const expiryBadge = document.createElement('span');
        expiryBadge.className = 'pin-expiry-badge';
        expiryBadge.textContent = `置顶剩余 ${timeStr}`;
        titleEl.appendChild(expiryBadge);
    }

    const displayTitle = msg.title || msg.body || '';
    if (!isPinned) {
        titleEl.textContent = displayTitle;
    } else {
        // Already added badge and expiry, now add title text after
        const textSpan = document.createElement('span');
        textSpan.textContent = displayTitle;
        titleEl.appendChild(textSpan);
    }
    item.appendChild(titleEl);

    // Body (if it's not a photo and has body)
    if (msg.body && !msg.type.startsWith('photo')) {
        const bodyEl = document.createElement('div');
        bodyEl.className = 'msg-body';
        bodyEl.textContent = msg.body;
        item.appendChild(bodyEl);
    }

    // Photo preview
    if (msg.photoBase64) {
        const img = document.createElement('img');
        img.src = 'data:image/jpeg;base64,' + msg.photoBase64;
        img.className = 'photo-preview';
        item.appendChild(img);

        const downloadBtn = document.createElement('button');
        downloadBtn.className = 'btn btn-secondary';
        downloadBtn.textContent = '💾 保存图片';
        downloadBtn.onclick = () => {
            const a = document.createElement('a');
            a.href = img.src;
            a.download = msg.name || 'photo.jpg';
            a.click();
        };
        item.appendChild(downloadBtn);
    }

    // Time
    const timeEl = document.createElement('div');
    timeEl.className = 'msg-time';
    timeEl.textContent = msg.time || '';
    item.appendChild(timeEl);

    return item;
}

// ─── 消息日志 ────────────────────────────────────────────────────
function addLog(type, title, body, opts = {}) {
    const now = Date.now();
    const time = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });

    const msg = {
        type,
        title: title || '',
        body: body || '',
        time,
        timestamp: now,
        pinned: opts.pinned || false,
        pinExpiry: opts.pinExpiry || 0,
        photoBase64: opts.photoBase64 || '',
        name: opts.name || '',
        from: opts.from || ''
    };

    messages.push(msg);
    persistMessage(msg);

    // Re-render the full list (pinned items need to be at top)
    renderAllMessages();
}

// ─── WebSocket ────────────────────────────────────────────────────
function connect() {
    if (ws) { ws.onclose = null; ws.close(); ws = null; }

    let url = serverUrlInput.value.trim();
    if (!url) { setStatus('disconnected', '请配置服务器'); return; }
    if (!url.includes('type=')) {
        url += (url.includes('?') ? '&' : '?') + 'type=pc';
    }
    serverUrlInput.value = url;
    saveServerUrl(url);

    setStatus('connecting', '连接中...');

    try { ws = new WebSocket(url); }
    catch (err) { setStatus('disconnected', '连接失败'); scheduleReconnect(); return; }

    ws.onopen = () => {
        setStatus('connected', '已连接');
        btnRemind.disabled = false;
        clearReconnect();
    };

    ws.onmessage = (e) => {
        try { handleMessage(JSON.parse(e.data)); }
        catch { /* ignore parse errors */ }
    };

    ws.onclose = () => {
        setStatus('disconnected', '连接断开');
        btnRemind.disabled = true;
        ws = null;
        scheduleReconnect();
    };

    ws.onerror = () => {};
}

function handleMessage(msg) {
    switch (msg.type) {
        case 'welcome':
            clientId = msg.clientId;
            clientIdSpan.textContent = clientId;
            setStatus('connected', '已连接');
            addLog('system', `✅ 已连接为 ${msg.deviceType.toUpperCase()} 端`, '', {});
            updatePeers(msg.stats);
            break;

        case 'reminder': {
            const from = msg.from === 'android' ? '手机' : 'PC';
            const title = msg.title || `来自${from}的提醒`;
            const body = msg.body || '';
            const isPinned = msg.pin === true || msg.pin === 'true';
            const pinExpiry = parseInt(msg.pinExpiry) || 0;

            addLog('received', `📩 ${title}`, body, {
                pinned: isPinned,
                pinExpiry: pinExpiry,
                from: msg.from
            });

            if (isPinned) {
                try { window.electronAPI.showNotification({ title: `📌 [置顶] ${title}`, body: body || title }); }
                catch {}
            } else {
                try { window.electronAPI.showNotification({ title, body: body || title }); }
                catch {}
            }
            break;
        }

        case 'photo': {
            const from = msg.from === 'android' ? '手机' : 'PC';
            addLog('received', `📷 来自${from}的照片`, msg.name || '', {
                photoBase64: msg.data || '',
                name: msg.name || 'photo.jpg',
                from: msg.from
            });
            break;
        }

        case 'delivery_status':
            if (msg.sentDirect > 0) addLog('system', `✅ 已送达手机端`, '', {});
            else if (msg.fcmTriggered) addLog('system', `📡 已通过推送送达`, '', {});
            else addLog('system', `⚠️ 没有在线设备`, '', {});
            break;

        case 'peer_status':
            updatePeers(msg.online);
            break;

        case 'server_shutdown':
            addLog('system', '🔴 服务器即将关闭', '', {});
            break;

        case 'error':
            addLog('system', `❌ ${msg.message}`, '', {});
            break;
    }
}

function setStatus(state, text) {
    statusDot.className = 'connector-dot ' + state;
    statusText.textContent = text;
}

function updatePeers(stats) {
    if (stats) {
        pcCount.textContent = stats.pc || 0;
        androidCount.textContent = stats.android || 0;
    }
}

function scheduleReconnect() {
    clearReconnect();
    reconnectTimer = setTimeout(() => { addLog('system', '🔄 重连中...', '', {}); connect(); }, 3000);
}

function clearReconnect() {
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
}

// ─── 发送 ─────────────────────────────────────────────────────────
function sendReminder() {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
        addLog('system', '⚠️ 未连接', '', {});
        return;
    }

    const body = reminderBody.value.trim();
    if (!body) { addLog('system', '⚠️ 请输入提醒内容', '', {}); return; }

    const isPinned = pinCheckbox.checked;
    let pinExpiry = 0;
    if (isPinned) {
        pinExpiry = Date.now() + pinDurationHours * 3600 * 1000;
    }

    const payload = {
        type: 'reminder',
        target: 'android',
        body: body,
        pin: isPinned,
        pinExpiry: pinExpiry
    };

    ws.send(JSON.stringify(payload));

    addLog('sent', isPinned ? `📌 [置顶] 提醒手机` : `📱 提醒手机`, body, {
        pinned: isPinned,
        pinExpiry: pinExpiry
    });

    reminderBody.value = '';
}

// ─── Pin UI 逻辑 ──────────────────────────────────────────────────
pinCheckbox.addEventListener('change', () => {
    pinDurationGroup.style.opacity = pinCheckbox.checked ? '1' : '0.4';
    pinDurationGroup.style.pointerEvents = pinCheckbox.checked ? 'all' : 'none';
});

pinDurationBtns.forEach(btn => {
    btn.addEventListener('click', () => {
        pinDurationBtns.forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        pinDurationHours = parseInt(btn.dataset.hours) || 6;
    });
});

// ─── 模态框 ───────────────────────────────────────────────────────
function showModal() { settingsModal.classList.remove('hidden'); }
function hideModal() { settingsModal.classList.add('hidden'); }

// ─── 事件绑定 ─────────────────────────────────────────────────────
btnRemind.addEventListener('click', sendReminder);

// 照片发送
btnAttach.addEventListener('click', () => photoInput.click());
photoInput.addEventListener('change', (e) => {
    const file = e.target.files[0];
    if (!file) return;
    photoStatus.textContent = '📤 上传中...';
    btnAttach.disabled = true;
    const reader = new FileReader();
    reader.onload = () => {
        const base64 = reader.result.split(',')[1];
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({
                type: 'photo', target: 'android',
                data: base64, name: file.name
            }));
            addLog('sent', `📷 ${file.name}`, `${Math.round(file.size/1024)}KB`, {});
            photoStatus.textContent = '✅ 已发送';
        } else {
            addLog('system', '⚠️ 未连接，无法发送照片', '', {});
            photoStatus.textContent = '❌ 连接断开';
        }
        setTimeout(() => { photoStatus.textContent = ''; btnAttach.disabled = false; }, 2000);
        photoInput.value = '';
    };
    reader.onerror = () => {
        photoStatus.textContent = '❌ 读取失败';
        btnAttach.disabled = false;
    };
    reader.readAsDataURL(file);
});

reminderBody.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendReminder(); }
});

btnClearLog.addEventListener('click', async () => {
    messages = [];
    clearStoredMessages();
    renderAllMessages();
});

btnSettings.addEventListener('click', showModal);
btnCloseModal.addEventListener('click', hideModal);
settingsModal.addEventListener('click', (e) => { if (e.target === settingsModal) hideModal(); });

btnReconnect.addEventListener('click', () => { clearReconnect(); connect(); hideModal(); });
btnSaveSettings.addEventListener('click', () => { clearReconnect(); connect(); hideModal(); });

btnMinimize.addEventListener('click', () => {
    try { window.electronAPI.minimizeWindow(); } catch {}
});
btnClose.addEventListener('click', () => {
    try { window.electronAPI.hideWindow(); } catch {}
});

// ─── 周期性置顶到期检查 ──────────────────────────────────────────
setInterval(() => {
    const now = Date.now();
    let needsRerender = false;
    messages.forEach(m => {
        if (m.pinned && m.pinExpiry > 0 && now >= m.pinExpiry) {
            m.pinned = false;
            m.pinExpiry = 0;
            needsRerender = true;
        }
    });
    if (needsRerender) {
        renderAllMessages();
    }
}, 30000); // Check every 30s

// ─── 初始化 ───────────────────────────────────────────────────────
async function init() {
    await initConfig();
    const hasHistory = await loadMessages();
    if (hasHistory && messages.length > 0) {
        renderAllMessages();
        addLog('system', `💾 已加载 ${messages.length} 条历史消息`, '', {});
    } else {
        addLog('system', '📋 消息记录将自动保存', '', {});
    }
    connect();
}

init();
