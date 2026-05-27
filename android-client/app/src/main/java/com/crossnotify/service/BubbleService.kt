package com.crossnotify.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.crossnotify.R
import com.crossnotify.BuildConfig
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BubbleService : Service() {

    companion object { private const val TAG = "BubbleService" }
    private lateinit var wm: WindowManager
    private var bubbleView: View? = null
    private var ws: WebSocket? = null
    private var isConnected = false
    private var isFocused = false

    private var initialX = 0; private var initialY = 0
    private var initialTouchX = 0f; private var initialTouchY = 0f

    private val client = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .pingInterval(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()

    override fun onCreate() { super.onCreate(); wm = getSystemService(WINDOW_SERVICE) as WindowManager }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "HIDE") { hideBubble(); stopSelf(); return START_NOT_STICKY }
        try { showBubble(); connectWs() } catch (e: Exception) { Log.e(TAG, "Start failed", e); stopSelf() }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { ws?.close(1000, "done"); ws = null; hideBubble(); super.onDestroy() }

    private fun connectWs() {
        val req = Request.Builder().url(BuildConfig.WS_URL).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                // 注册 HMS token
                WebSocketService.pendingFcmToken?.let { token ->
                    val msg = JSONObject().apply { put("type", "register_fcm"); put("token", token) }
                    ws.send(msg.toString())
                }
            }
            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    when (msg.optString("type")) {
                        "reminder" -> {
                            val title = msg.optString("title", "新提醒")
                            val body = msg.optString("body", "")
                            val from = msg.optString("from", "pc")
                            bubbleView?.findViewById<TextView>(R.id.bubbleMsg)?.text = "📩 ${if (from == "pc") "PC" else "手机"}: ${title}\n$body"
                            bubbleView?.findViewById<TextView>(R.id.bubbleMsg)?.visibility = View.VISIBLE
                            // 点击消息跳转到主界面（先启动Activity，再停止Service）
                            bubbleView?.findViewById<TextView>(R.id.bubbleMsg)?.setOnClickListener {
                                val i = Intent(this@BubbleService, com.crossnotify.ui.MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                }
                                startActivity(i)
                                stopSelf()
                            }
                            android.os.Handler(mainLooper).postDelayed({
                                bubbleView?.findViewById<TextView>(R.id.bubbleMsg)?.visibility = View.GONE
                            }, 5000)
                        }
                        "photo" -> {
                            val data = msg.optString("data", "")
                            val name = msg.optString("name", "photo.jpg")
                            val from = msg.optString("from", "pc")
                            // 显示图片预览
                            if (data.isNotEmpty()) {
                                try {
                                    val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    val imgView = bubbleView?.findViewById<android.widget.ImageView>(R.id.bubblePhoto)
                                    imgView?.setImageBitmap(bmp)
                                    imgView?.visibility = View.VISIBLE
                                    imgView?.setOnClickListener {
                                        val i = Intent(this@BubbleService, com.crossnotify.ui.MainActivity::class.java).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        }
                                        startActivity(i)
                                        stopSelf()
                                    }
                                } catch (_: Exception) {}
                            }
                            bubbleView?.findViewById<TextView>(R.id.bubbleMsg)?.text = "📷 来自${if (from == "pc") "PC" else "手机"}的照片"
                            bubbleView?.findViewById<TextView>(R.id.bubbleMsg)?.visibility = View.VISIBLE
                            android.os.Handler(mainLooper).postDelayed({
                                bubbleView?.findViewById<TextView>(R.id.bubbleMsg)?.visibility = View.GONE
                                bubbleView?.findViewById<android.widget.ImageView>(R.id.bubblePhoto)?.visibility = View.GONE
                            }, 8000)
                        }
                    }
                } catch (_: Exception) {}
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { isConnected = false; scheduleReconnect() }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { isConnected = false; scheduleReconnect() }
        })
    }
    private fun scheduleReconnect() { android.os.Handler(mainLooper).postDelayed({ connectWs() }, 5000) }

    private fun showBubble() {
        if (bubbleView != null) return
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        bubbleView = inflater.inflate(R.layout.bubble_overlay, null) ?: return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.START; params.x = 100; params.y = 200

        // 拖拽 + 点击切换聚焦模式（使输入框可用）
        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        params.x = initialX + dx; params.y = initialY + dy
                        wm.updateViewLayout(bubbleView!!, params)
                    }
                    true
                }
                else -> false
            }
        }

        // 点击输入框时获取焦点
        bubbleView?.findViewById<EditText>(R.id.bubbleTitle)?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                wm.updateViewLayout(bubbleView!!, params)
                isFocused = true
            }
        }
        bubbleView?.findViewById<EditText>(R.id.bubbleBody)?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                wm.updateViewLayout(bubbleView!!, params)
                isFocused = true
            }
        }

        // 点击空白区域失去焦点
        bubbleView?.setOnClickListener { }

        // 发送
        bubbleView?.findViewById<Button>(R.id.btnBubbleSend)?.setOnClickListener {
            val title = bubbleView?.findViewById<EditText>(R.id.bubbleTitle)?.text?.toString()?.trim() ?: ""
            val body = bubbleView?.findViewById<EditText>(R.id.bubbleBody)?.text?.toString()?.trim() ?: ""
            if (title.isEmpty() && body.isEmpty()) { Toast.makeText(this, "请输入内容", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val msg = JSONObject().apply { put("type", "reminder"); put("target", "pc"); put("title", title); put("body", body) }
            ws?.send(msg.toString())
            bubbleView?.findViewById<EditText>(R.id.bubbleTitle)?.text?.clear()
            bubbleView?.findViewById<EditText>(R.id.bubbleBody)?.text?.clear()
            // 发送后释放焦点
            params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            wm.updateViewLayout(bubbleView!!, params)
            Toast.makeText(this, "已发送", Toast.LENGTH_SHORT).show()
        }
        // 关闭
        bubbleView?.findViewById<View>(R.id.btnBubbleClose)?.setOnClickListener { stopSelf() }
        // 通知主Activity进入后台
        sendBroadcast(Intent("com.crossnotify.BACKGROUND"))
        wm.addView(bubbleView, params)
    }
    private fun hideBubble() { bubbleView?.let { wm.removeView(it) }; bubbleView = null }
}
