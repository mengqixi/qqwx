package com.crossnotify.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import com.crossnotify.R
import com.crossnotify.BuildConfig
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 悬浮窗服务 — 分屏/悬浮模式下快速发送提醒
 */
class BubbleService : Service() {

    private lateinit var wm: WindowManager
    private var bubbleView: View? = null
    private var ws: WebSocket? = null
    private var isConnected = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private val client = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "HIDE") {
            hideBubble()
            stopSelf()
            return START_NOT_STICKY
        }
        showBubble()
        connectWs()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ws?.close(1000, "Service destroyed")
        ws = null
        hideBubble()
        super.onDestroy()
    }

    private fun connectWs() {
        val request = Request.Builder().url(BuildConfig.WS_URL).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                scheduleReconnect()
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        android.os.Handler(mainLooper).postDelayed({ connectWs() }, 5000)
    }

    private fun showBubble() {
        if (bubbleView != null) return

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        bubbleView = inflater.inflate(R.layout.bubble_overlay, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 200

        // 拖拽
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleHeader)?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    wm.updateViewLayout(bubbleView!!, params)
                    true
                }
                else -> false
            }
        }

        // 发送
        bubbleView?.findViewById<Button>(R.id.btnBubbleSend)?.setOnClickListener {
            val title = bubbleView?.findViewById<EditText>(R.id.bubbleTitle)?.text?.toString()?.trim() ?: ""
            val body = bubbleView?.findViewById<EditText>(R.id.bubbleBody)?.text?.toString()?.trim() ?: ""
            if (title.isEmpty() && body.isEmpty()) return@setOnClickListener

            val msg = JSONObject().apply {
                put("type", "reminder")
                put("target", "pc")
                put("title", title)
                put("body", body)
            }
            ws?.send(msg.toString())
            bubbleView?.findViewById<EditText>(R.id.bubbleTitle)?.text?.clear()
            bubbleView?.findViewById<EditText>(R.id.bubbleBody)?.text?.clear()
        }

        // 关闭
        bubbleView?.findViewById<View>(R.id.btnBubbleClose)?.setOnClickListener {
            stopSelf()
        }

        wm.addView(bubbleView, params)
    }

    private fun hideBubble() {
        bubbleView?.let { wm.removeView(it) }
        bubbleView = null
    }
}
