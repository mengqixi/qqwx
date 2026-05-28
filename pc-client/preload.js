const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
    getConfig: () => ipcRenderer.invoke('get-config'),
    setConfig: (config) => ipcRenderer.invoke('set-config', config),
    getMessages: () => ipcRenderer.invoke('get-messages'),
    saveMessage: (msg) => ipcRenderer.invoke('save-message', msg),
    clearMessages: () => ipcRenderer.invoke('clear-messages'),
    showNotification: (opts) => ipcRenderer.invoke('show-notification', opts),
    minimizeWindow: () => ipcRenderer.invoke('minimize-window'),
    hideWindow: () => ipcRenderer.invoke('hide-window'),
    onFocusReminder: (callback) => ipcRenderer.on('focus-reminder', callback),
});
