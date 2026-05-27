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
        private const val NOTIFICATION_ID = 1001
        private const val RECONNECT_DELAY_MS = 5000L

        // 全局回调，供 Activity 监听连接状态
        var onStatusChange: ((Boolean) -> Unit)? = null
        var onReminderReceived: ((String, String) -> Unit)? = null
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
                        savePhotoToGallery(data, name)
                    }
                    onReminderReceived?.invoke("📷 来自${if (from == "pc") "PC" else "手机"}的照片", name)
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

    // ─── 保存照片到相册 ──────────────────────────────────────────

    private fun savePhotoToGallery(base64: String, fileName: String) {
        try {
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            if (dir != null && !dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, fileName)
            java.io.FileOutputStream(file).use { it.write(bytes) }

            // 通知系统相册刷新
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            intent.data = android.net.Uri.fromFile(file)
            sendBroadcast(intent)

            Log.i(TAG, "Photo saved: ${file.absolutePath} (${bytes.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save photo", e)
        }
    }

    // ─── 通知 ─────────────────────────────────────────────────────

    private fun showReminderNotification(title: String, body: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
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
