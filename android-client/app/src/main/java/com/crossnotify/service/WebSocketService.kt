package com.crossnotify.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.crossnotify.BuildConfig
import com.crossnotify.ui.MainActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketService : Service() {

    // Binder for Activity to get service reference
    inner class LocalBinder : android.os.Binder() {
        fun getService(): WebSocketService = this@WebSocketService
    }

    private val binder = LocalBinder()

    companion object {
        private const val TAG = "WebSocketService"
        private const val CHANNEL_ID = "crossnotify_ws"
        private const val REMINDER_CHANNEL_ID = "crossnotify_reminder"
        private const val NOTIFICATION_ID = 1001
        private const val RECONNECT_DELAY_MS = 5000L

        // 全局回调，供 Activity 监听连接状态
        var onStatusChange: ((Boolean) -> Unit)? = null
        var onReminderReceived: ((String, String) -> Unit)? = null
        var onPhotoReceived: ((String, String) -> Unit)? = null  // (base64, fileName)
        var connectionState: ConnectionState = ConnectionState.DISCONNECTED
            private set

        // HMS Push Token（由 HmsPushService 写入，WebSocket 连接后上报）
        @Volatile
        var pendingFcmToken: String? = null
    }

    enum class ConnectionState {
        CONNECTING, CONNECTED, DISCONNECTED
    }

    private val client = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)  // 绕过系统代理（如 Nox 的 HTTP 代理）
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        if (intent?.action == "DISCONNECT") {
            disconnectWs()
            stopSelf()
            return START_NOT_STICKY
        }

        connectWs()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = binder

    override fun onDestroy() {
        disconnectWs()
        super.onDestroy()
    }

    // ─── WebSocket ────────────────────────────────────────────────

    private fun connectWs() {
        if (ws != null) return

        updateState(ConnectionState.CONNECTING)

        val request = Request.Builder()
            .url(BuildConfig.WS_URL)
            .build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                updateState(ConnectionState.CONNECTED)
                // 上报 HMS Push Token（如果已获取）
                pendingFcmToken?.let { token ->
                    val msg = org.json.JSONObject().apply {
                        put("type", "register_fcm")
                        put("token", token)
                    }
                    ws.send(msg.toString())
                    Log.i(TAG, "HMS token registered")
                    pendingFcmToken = null
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                this@WebSocketService.ws = null
                updateState(ConnectionState.DISCONNECTED)
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                this@WebSocketService.ws = null
                updateState(ConnectionState.DISCONNECTED)
                scheduleReconnect()
            }
        })
    }

    private fun disconnectWs() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
        ws?.close(1000, "Client closing")
        ws = null
        updateState(ConnectionState.DISCONNECTED)
    }

    private fun scheduleReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = Runnable {
            Log.i(TAG, "Attempting reconnect...")
            connectWs()
        }
        handler.postDelayed(reconnectRunnable!!, RECONNECT_DELAY_MS)
    }

    // ─── 消息处理 ─────────────────────────────────────────────────

    private fun handleMessage(text: String) {
        try {
            val msg = JSONObject(text)
            when (msg.optString("type")) {
                "welcome" -> {
                    Log.i(TAG, "Server welcome: ${msg.optString("clientId")}")
                }

                "reminder" -> {
                    val from = msg.optString("from", "pc")
                    val title = msg.optString("title", "新提醒")
                    val body = msg.optString("body", "收到来自${if (from == "pc") "PC" else "手机"}端的提醒")

                    // 显示通知
                    showReminderNotification(title, body)

                    // 回调给 Activity
                    onReminderReceived?.invoke(title, body)
                }

                // ── 照片接收 ──
                "photo" -> {
                    val from = msg.optString("from", "pc")
                    val name = msg.optString("name", "photo.jpg")
                    val data = msg.optString("data", "")

                    if (data.isNotEmpty()) {
                        // 通知 UI 预览（不再自动保存）
                        onPhotoReceived?.invoke(data, name)
                        showPhotoNotification(data, name)
                    }
                }

                "peer_status" -> {
                    // 在线状态更新，忽略
                }

                "pong" -> {
                    // 心跳回复
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message", e)
        }
    }

    // ─── 发送提醒（手机 → PC） ──────────────────────────────────

    fun sendReminderToPc(title: String = "", body: String = "") {
        val msg = JSONObject().apply {
            put("type", "reminder")
            put("target", "pc")
            put("title", title)
            put("body", body)
        }
        ws?.send(msg.toString())
        Log.i(TAG, "Reminder sent to PC")
    }

    // ─── 发送照片（手机 → PC） ─────────────────────────────────

    fun sendPhotoToPc(base64: String, fileName: String) {
        val msg = JSONObject().apply {
            put("type", "photo")
            put("target", "pc")
            put("data", base64)
            put("name", fileName)
        }
        ws?.send(msg.toString())
        Log.i(TAG, "Photo sent to PC: $fileName (${base64.length} chars)")
    }

    // ─── 保存照片到系统相册 ─────────────────────────────────────

    private fun savePhotoToGallery(base64: String, fileName: String): String? {
        return try {
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            val time = System.currentTimeMillis() / 1000
            val displayName = "mqx_${time}.jpg"

            // 全部 API 级别: 使用 MediaStore 写入系统相册
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.DATE_ADDED, time)
                put(android.provider.MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                }
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                val path = "系统相册 → $displayName"
                Log.i(TAG, "MediaStore save OK: $path")
                return path
            }

            // 降级: 写入应用目录（所有设备通用）
            val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            if (dir != null && !dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, displayName)
            java.io.FileOutputStream(file).use { it.write(bytes) }
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            intent.data = android.net.Uri.fromFile(file)
            sendBroadcast(intent)
            val path = "系统相册 → Pictures/$displayName"
            Log.i(TAG, "Fallback save: ${file.absolutePath}")
            path
        } catch (e: Exception) { Log.e(TAG, "Save photo failed", e); null }
    }

    fun savePhoto(photo: String, name: String): String? {
        return savePhotoToGallery(photo, name)
    }

    // ─── 照片通知（预览+保存按钮） ─────────────────────────────

    private fun showPhotoNotification(base64: String, fileName: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channel = android.app.NotificationChannel("photo_preview", "照片预览", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)

        // 保存按钮通过 PendingIntent 启动 Activity 执行保存
        val saveIntent = Intent(this, com.crossnotify.ui.MainActivity::class.java).apply {
            putExtra("action", "save_photo")
            putExtra("photo_data", base64)
            putExtra("photo_name", fileName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val savePending = PendingIntent.getActivity(this, (System.currentTimeMillis() % 100000).toInt(),
            saveIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = android.app.Notification.Builder(this, "photo_preview")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("📷 收到照片")
            .setContentText(fileName)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_save, "💾 保存到相册", savePending)
            .setStyle(android.app.Notification.BigPictureStyle()
                .bigPicture(android.graphics.BitmapFactory.decodeByteArray(
                    android.util.Base64.decode(base64, android.util.Base64.DEFAULT), 0,
                    android.util.Base64.decode(base64, android.util.Base64.DEFAULT).size)))
            .setPriority(android.app.Notification.PRIORITY_HIGH)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }

    // ─── 通知 ─────────────────────────────────────────────────────

    private fun showReminderNotification(title: String, body: String) {
        // 确保提醒频道存在
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val reminderChannel = NotificationChannel(
            REMINDER_CHANNEL_ID, "新提醒", NotificationManager.IMPORTANCE_HIGH
        ).apply { enableVibration(true); enableLights(true) }
        nm.createNotificationChannel(reminderChannel)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), notification)
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("梦柒兮")
            .setContentText("服务运行中")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WebSocket 服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "梦柒兮 后台连接服务"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    // ─── 状态更新 ─────────────────────────────────────────────────

    private fun updateState(state: ConnectionState) {
        connectionState = state
        onStatusChange?.invoke(state == ConnectionState.CONNECTED)
    }
}
