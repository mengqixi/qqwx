const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
    getConfig: () => ipcRenderer.invoke('get-config'),
    setConfig: (config) => ipcRenderer.invoke('set-config', config),
    showNotification: (opts) => ipcRenderer.invoke('show-notification', opts),
    minimizeWindow: () => ipcRenderer.invoke('minimize-window'),
    hideWindow: () => ipcRenderer.invoke('hide-window'),
    onFocusReminder: (callback) => ipcRenderer.on('focus-reminder', callback),
});
