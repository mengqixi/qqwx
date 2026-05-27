const { app, BrowserWindow, Tray, Menu, nativeImage, Notification, ipcMain } = require('electron');
const path = require('path');
const fs = require('fs');

// Store config in appData
const configPath = path.join(app.getPath('userData'), 'config.json');

function loadConfig() {
    try {
        if (fs.existsSync(configPath)) {
            return JSON.parse(fs.readFileSync(configPath, 'utf8'));
        }
    } catch {}
    return { serverUrl: 'wss://47.110.65.93:9528/ws?type=pc' };
}

function saveConfig(config) {
    fs.writeFileSync(configPath, JSON.stringify(config, null, 2));
}

let mainWindow = null;
let tray = null;
let config = loadConfig();

function createWindow() {
    mainWindow = new BrowserWindow({
        width: 700,
        height: 620,
        minWidth: 640,
        minHeight: 500,
        resizable: true,
        frame: false,
        transparent: false,
        icon: path.join(__dirname, 'icon.png'),
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false,
        },
    });

    mainWindow.loadFile('index.html');
    mainWindow.setTitle('梦柒兮');

    // Accept self-signed certificate for WebSocket in renderer
    mainWindow.webContents.session.setCertificateVerifyProc((req, callback) => {
        if (req.hostname === '47.110.65.93' || req.hostname.includes('crossnotify')) {
            callback(0); // 0 = accept
        } else {
            callback(-1); // -1 = use default verification
        }
    });

    // Prevent close, just hide
    mainWindow.on('close', (event) => {
        if (!app.isQuitting) {
            event.preventDefault();
            mainWindow.hide();
        }
    });
}

function createTray() {
    const iconPath = path.join(__dirname, 'icon.png');
    const icon = nativeImage.createFromPath(iconPath).resize({ width: 16, height: 16 });
    tray = new Tray(icon);
    tray.setToolTip('梦柒兮');

    const contextMenu = Menu.buildFromTemplate([
        { label: '打开窗口', click: () => mainWindow?.show() },
        { type: 'separator' },
        { label: '退出', click: () => { app.isQuitting = true; app.quit(); } },
    ]);
    tray.setContextMenu(contextMenu);

    tray.on('double-click', () => mainWindow?.show());
}

// ─── IPC Handlers ───────────────────────────────────────────────────
ipcMain.handle('get-config', () => config);

ipcMain.handle('set-config', (_, newConfig) => {
    config = { ...config, ...newConfig };
    saveConfig(config);
    return config;
});

ipcMain.handle('show-notification', (_, { title, body }) => {
    if (Notification.isSupported()) {
        const notif = new Notification({
            title,
            body,
            icon: path.join(__dirname, 'icon.png'),
        });
        notif.on('click', () => {
            if (mainWindow) {
                mainWindow.show();
                mainWindow.webContents.send('focus-reminder');
            }
        });
        notif.show();
    }
});

ipcMain.handle('minimize-window', () => {
    if (mainWindow) mainWindow.minimize();
});

ipcMain.handle('hide-window', () => {
    if (mainWindow) mainWindow.hide();
});

// ─── App Lifecycle ──────────────────────────────────────────────────
app.whenReady().then(() => {
    // 设置 Windows AppUserModelID（通知来源名称）
    app.setAppUserModelId('梦柒兮');
    createWindow();
    createTray();
});

// Accept self-signed SSL cert for our server
app.on('certificate-error', (event, webContents, url, error, certificate, callback) => {
    if (url.includes('47.110.65.93:9528') || url.includes('crossnotify')) {
        event.preventDefault();
        callback(true); // Accept
    } else {
        callback(false); // Reject
    }
});

app.on('window-all-closed', () => {
    // Don't quit on macOS
    if (process.platform !== 'darwin') {
        // Keep running in tray
    }
});

app.on('activate', () => {
    if (mainWindow) mainWindow.show();
});
