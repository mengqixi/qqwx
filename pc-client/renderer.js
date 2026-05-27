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
const reminderTitle = $('#reminderTitle');
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

let ws = null;
let reconnectTimer = null;
let clientId = null;
let msgCount = 0;

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

// ─── 消息日志 ────────────────────────────────────────────────────
function addLog(type, title, body) {
    // Remove empty state
    if (logEmpty && !logEmpty.classList.contains('hidden')) {
        logEmpty.classList.add('hidden');
    }

    const item = document.createElement('div');
    item.className = `msg msg-${type}`;

    const time = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });

    if (type === 'system') {
        item.textContent = title;
    } else {
        const titleEl = document.createElement('div');
        titleEl.className = 'msg-title';
        titleEl.textContent = title;
        item.appendChild(titleEl);

        if (body) {
            const bodyEl = document.createElement('div');
            bodyEl.className = 'msg-body';
            bodyEl.textContent = body;
            item.appendChild(bodyEl);
        }
    }

    const timeEl = document.createElement('div');
    timeEl.className = 'msg-time';
    timeEl.textContent = time;
    item.appendChild(timeEl);

    messageList.appendChild(item);
    messageList.scrollTop = messageList.scrollHeight;

    msgCount++;
    logCount.textContent = msgCount;
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
            addLog('system', `✅ 已连接为 ${msg.deviceType.toUpperCase()} 端`);
            updatePeers(msg.stats);
            break;

        case 'reminder': {
            const from = msg.from === 'android' ? '手机' : 'PC';
            const title = msg.title || `来自${from}的提醒`;
            const body = msg.body || '';
            addLog('received', `📩 ${title}`, body);
            try { window.electronAPI.showNotification({ title, body: body || title }); }
            catch {}
            break;
        }

        case 'delivery_status':
            if (msg.sentDirect > 0) addLog('system', `✅ 已送达手机端`);
            else if (msg.fcmTriggered) addLog('system', `📡 已通过 FCM 推送`);
            else addLog('system', `⚠️ 没有在线设备`);
            break;

        case 'peer_status':
            updatePeers(msg.online);
            break;

        case 'server_shutdown':
            addLog('system', '🔴 服务器即将关闭');
            break;

        case 'error':
            addLog('system', `❌ ${msg.message}`);
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
    reconnectTimer = setTimeout(() => { addLog('system', '🔄 重连中...'); connect(); }, 3000);
}

function clearReconnect() {
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
}

// ─── 发送 ─────────────────────────────────────────────────────────
function sendReminder() {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
        addLog('system', '⚠️ 未连接');
        return;
    }

    const title = reminderTitle.value.trim();
    const body = reminderBody.value.trim();
    if (!title && !body) { addLog('system', '⚠️ 请输入提醒内容'); return; }

    ws.send(JSON.stringify({ type: 'reminder', target: 'android', title: title || undefined, body: body || undefined }));
    addLog('sent', `📱 ${title || '提醒手机'}`, body || '');
    reminderTitle.value = '';
    reminderBody.value = '';
}

// ─── 模态框 ───────────────────────────────────────────────────────
function showModal() { settingsModal.classList.remove('hidden'); }
function hideModal() { settingsModal.classList.add('hidden'); }

// ─── 事件绑定 ─────────────────────────────────────────────────────
btnRemind.addEventListener('click', sendReminder);

reminderTitle.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); reminderBody.focus(); }
});
reminderBody.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && e.ctrlKey) { e.preventDefault(); sendReminder(); }
});

btnClearLog.addEventListener('click', () => {
    messageList.querySelectorAll('.msg').forEach(el => el.remove());
    msgCount = 0;
    logCount.textContent = '0';
    if (logEmpty) logEmpty.classList.remove('hidden');
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

// ─── 初始化 ───────────────────────────────────────────────────────
initConfig().then(connect);
