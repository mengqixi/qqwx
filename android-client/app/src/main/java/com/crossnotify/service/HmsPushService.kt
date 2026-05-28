package com.crossnotify.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import com.crossnotify.ui.MainActivity
import com.huawei.hms.push.HmsMessageService
import com.huawei.hms.push.RemoteMessage

/**
 * HMS Push Kit 推送接收服务
 * 华为手机后台推送，无论应用是否存活都能收到
 *
 * 当应用被杀死时，HMS 系统服务仍然能收到推送并显示通知。
 * 收到推送时尝试重启 WebSocketService 以便恢复实时连接。
 */
class HmsPushService : HmsMessageService() {

    companion object {
        private const val TAG = "HmsPushService"
        private const val CHANNEL_ID = "hms_push"

        // 保存最新 token，供 WebSocketService 上报服务器
        var latestToken: String? = null
    }

    override fun onNewToken(token: String) {
        Log.i(TAG, "HMS push token: $token")
        latestToken = token
        // 如果 WebSocket 已连接，立即上报
        WebSocketService.pendingFcmToken = token
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d(TAG, "HMS message received: ${message.data}")
        val data = message.dataOfMap
        val title = data["title"] ?: "新提醒"
        val body = data["body"] ?: "收到来自 PC 端的提醒"
        val isPinned = data["pin"] == "true"
        val pinExpiry = data["pinExpiry"]?.toLongOrNull() ?: 0L

        showNotification(
            if (isPinned) "📌 [置顶] $title" else title,
            body
        )

        // 收到推送时尝试重启 WebSocketService（如果已死）
        try {
            val wsIntent = Intent(this, WebSocketService::class.java)
            wsIntent.action = null // 正常启动
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(wsIntent)
            } else {
                startService(wsIntent)
            }
            Log.i(TAG, "WebSocketService restarted after HMS push")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart WebSocketService", e)
        }
    }

    private fun showNotification(title: String, body: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "推送提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(android.app.Notification.PRIORITY_HIGH)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }
}
