package com.crossnotify.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Build

/**
 * 开机自启广播接收器
 * 在系统启动后自动拉起 WebSocket 后台服务
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            Log.i(TAG, "Boot completed, starting WebSocketService...")
            val serviceIntent = Intent(context, WebSocketService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
