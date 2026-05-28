package com.crossnotify.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.crossnotify.BuildConfig
import com.crossnotify.R
import com.crossnotify.storage.MessageStorage
import com.crossnotify.ui.MainActivity
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BubbleService : Service() {

    companion object {
        private const val TAG = "BubbleService"
        private const val MAX_HISTORY = 20
    }

    private lateinit var wm: WindowManager
    private var bubbleView: View? = null
    private var ws: WebSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var initialX = 0; private var initialY = 0
    private var initialTouchX = 0f; private var initialTouchY = 0f

    // 历史消息列表
    private val historyMessages = mutableListOf<MainActivity.MessageItem>()
    private lateinit var historyAdapter: BubbleHistoryAdapter
    private var historyVisible = false

    private val client = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .pingInterval(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "HIDE") { hideBubble(); stopSelf(); return START_NOT_STICKY }
        try { showBubble(); connectWs() } catch (e: Exception) { Log.e(TAG, "Start failed", e); stopSelf() }
        // 通知主Activity进入后台
        sendBroadcast(Intent("com.crossnotify.BACKGROUND"))
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { ws?.close(1000, "done"); ws = null; hideBubble(); super.onDestroy() }

    private fun connectWs() {
        val req = Request.Builder().url(BuildConfig.WS_URL).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                val token = WebSocketService.pendingFcmToken
                if (token != null) {
                    val msg = JSONObject().apply { put("type", "register_fcm"); put("token", token) }
                    ws.send(msg.toString())
                }
            }
            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    when (msg.optString("type")) {
                        "reminder" -> showInBubble(msg)
                        "photo" -> showPhotoInBubble(msg)
                        "welcome" -> Log.i(TAG, "Bubble WS connected: ${msg.optString("clientId")}")
                    }
                } catch (_: Exception) {}
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { scheduleReconnect() }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { scheduleReconnect() }
        })
    }

    private fun showInBubble(msg: JSONObject) {
        val title = msg.optString("title", "新提醒")
        val body = msg.optString("body", "")
        val from = msg.optString("from", "pc")
        val isPinned = msg.optBoolean("pin", false) || msg.optString("pin", "false") == "true"
        val pinExpiry = msg.optLong("pinExpiry", 0L)

        // 添加到历史
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())
        val item = MainActivity.MessageItem(
            type = "received",
            title = title,
            body = body,
            time = time,
            pinned = isPinned,
            pinExpiry = pinExpiry,
            timestamp = System.currentTimeMillis(),
            from = from
        )
        addToHistory(item)

        mainHandler.post {
            try {
                val tv = bubbleView?.findViewById<TextView>(R.id.bubbleMsg)
                val prefix = if (isPinned) "📌 " else "📩 "
                tv?.text = "${prefix}${if (from == "pc") "PC" else "手机"}: ${title}\n$body"
                tv?.visibility = View.VISIBLE
                tv?.setOnClickListener {
                    startActivity(Intent(this@BubbleService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    })
                }
                mainHandler.postDelayed({ tv?.visibility = View.GONE }, 5000)
            } catch (_: Exception) {}
        }
    }

    private fun showPhotoInBubble(msg: JSONObject) {
        val data = msg.optString("data", "")
        val name = msg.optString("name", "photo.jpg")
        val from = msg.optString("from", "pc")

        // 添加到历史
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())
        val item = MainActivity.MessageItem(
            type = "photo_received",
            title = "📷 照片",
            body = "来自${if (from == "pc") "PC" else "手机"}",
            time = time,
            photoBase64 = data,
            timestamp = System.currentTimeMillis(),
            from = from,
            name = name
        )
        addToHistory(item)

        mainHandler.post {
            try {
                // 显示预览图
                if (data.isNotEmpty()) {
                    val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val iv = bubbleView?.findViewById<ImageView>(R.id.bubblePhoto)
                    iv?.setImageBitmap(bmp)
                    iv?.visibility = View.VISIBLE
                    iv?.setOnClickListener {
                        startActivity(Intent(this@BubbleService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        })
                        stopSelf()
                    }
                }
                // 显示文字提示
                val tv = bubbleView?.findViewById<TextView>(R.id.bubbleMsg)
                tv?.text = "📷 来自${if (from == "pc") "PC" else "手机"}的照片"
                tv?.visibility = View.VISIBLE
                mainHandler.postDelayed({ tv?.visibility = View.GONE; bubbleView?.findViewById<ImageView>(R.id.bubblePhoto)?.visibility = View.GONE }, 8000)
            } catch (_: Exception) {}
        }
    }

    private fun addToHistory(item: MainActivity.MessageItem) {
        historyMessages.add(item)
        if (historyMessages.size > MAX_HISTORY) {
            historyMessages.removeAt(0)
        }
        // 持久化
        MessageStorage.saveMessages(this, historyMessages)
        // 更新适配器
        if (::historyAdapter.isInitialized) {
            mainHandler.post {
                historyAdapter.updateMessages(historyMessages)
                historyAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun scheduleReconnect() { mainHandler.postDelayed({ connectWs() }, 5000) }

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

        // 拖拽
        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { initialX = params.x; initialY = params.y; initialTouchX = event.rawX; initialTouchY = event.rawY; false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt(); val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) { params.x = initialX + dx; params.y = initialY + dy; wm.updateViewLayout(bubbleView!!, params) }
                    true
                }
                else -> false
            }
        }

        // 输入框获取焦点
        bubbleView?.findViewById<EditText>(R.id.bubbleBody)?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                wm.updateViewLayout(bubbleView!!, params)
            }
        }

        // ── 历史消息列表 ──
        val historyPanel = bubbleView?.findViewById<View>(R.id.bubbleHistoryPanel)!!
        val historyRecycler = bubbleView?.findViewById<RecyclerView>(R.id.bubbleHistoryList)!!

        // 从本地加载历史消息
        val savedMessages = MessageStorage.loadMessages(this)
        historyMessages.clear()
        historyMessages.addAll(savedMessages.takeLast(MAX_HISTORY))

        historyAdapter = BubbleHistoryAdapter(historyMessages) { msg ->
            // 点击消息打开 MainActivity
            startActivity(Intent(this@BubbleService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
        historyRecycler.layoutManager = LinearLayoutManager(this)
        historyRecycler.adapter = historyAdapter

        bubbleView?.findViewById<View>(R.id.btnBubbleHistory)?.setOnClickListener {
            historyVisible = !historyVisible
            historyPanel.visibility = if (historyVisible) View.VISIBLE else View.GONE
            if (historyVisible) {
                historyAdapter.updateMessages(historyMessages)
                historyAdapter.notifyDataSetChanged()
            }
        }

        // 发送
        bubbleView?.findViewById<Button>(R.id.btnBubbleSend)?.setOnClickListener {
            val body = bubbleView?.findViewById<EditText>(R.id.bubbleBody)?.text?.toString()?.trim() ?: ""
            if (body.isEmpty()) { Toast.makeText(this, "请输入内容", Toast.LENGTH_SHORT).show(); return@setOnClickListener }

            // 发送（不带置顶，悬浮窗保持简洁；如需置顶可后续添加开关）
            val msg = JSONObject().apply {
                put("type", "reminder")
                put("target", "pc")
                put("title", "悬浮窗")
                put("body", body)
            }
            ws?.send(msg.toString())

            // 添加到本地历史
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())
            val item = MainActivity.MessageItem(
                type = "sent",
                title = "已发送",
                body = body,
                time = time,
                timestamp = System.currentTimeMillis()
            )
            addToHistory(item)

            bubbleView?.findViewById<EditText>(R.id.bubbleBody)?.text?.clear()
            Toast.makeText(this, "已发送", Toast.LENGTH_SHORT).show()
        }

        // 关闭
        bubbleView?.findViewById<View>(R.id.btnBubbleClose)?.setOnClickListener {
            stopSelf()
        }

        wm.addView(bubbleView, params)
    }

    private fun hideBubble() { bubbleView?.let { wm.removeView(it) }; bubbleView = null }
}

/**
 * 悬浮窗历史消息适配器
 */
class BubbleHistoryAdapter(
    private var messages: List<MainActivity.MessageItem>,
    private val onClick: (MainActivity.MessageItem) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<BubbleHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val textView: TextView = view as TextView
    }

    fun updateMessages(newMessages: List<MainActivity.MessageItem>) {
        messages = newMessages
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val tv = TextView(parent.context).apply {
            setPadding(8, 6, 8, 6)
            textSize = 12f
            maxLines = 2
            setTextColor(0xFF333333.toInt())
        }
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = messages[position]
        val isPinned = msg.pinned && msg.pinExpiry > System.currentTimeMillis()
        val prefix = when {
            isPinned -> "📌 "
            msg.type == "sent" || msg.type == "photo_sent" -> "📤 "
            msg.type == "received" || msg.type == "photo_received" -> "📩 "
            else -> ""
        }
        val displayBody = if (msg.body.length > 30) msg.body.take(30) + "…" else msg.body
        holder.textView.text = "${prefix}${msg.title}${if (displayBody.isNotEmpty()) ": $displayBody" else ""}"
        holder.textView.setTextColor(if (isPinned) 0xFFE89B3C.toInt() else 0xFF333333.toInt())
        holder.itemView.setOnClickListener { onClick(msg) }
    }

    override fun getItemCount() = messages.size
}
