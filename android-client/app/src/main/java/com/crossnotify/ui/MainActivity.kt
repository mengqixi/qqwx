package com.crossnotify.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.crossnotify.R
import com.crossnotify.service.WebSocketService

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var connectionInfo: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnRemindPc: Button
    private lateinit var reminderTitle: EditText
    private lateinit var reminderBody: EditText
    private lateinit var messageList: RecyclerView

    private var wsService: WebSocketService? = null
    private var isBound = false
    private var serviceRunning = false
    private val messages = mutableListOf<MessageItem>()
    private lateinit var messageAdapter: MessageAdapter

    // 消息数据类
    data class MessageItem(
        val type: String,  // "sent" / "received" / "system"
        val title: String,
        val body: String,
        val time: String
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WebSocketService.LocalBinder
            wsService = binder.getService()
            isBound = true
            // 监听收到的提醒
            WebSocketService.onReminderReceived = { title, body ->
                runOnUiThread {
                    addMessage("received", title, body)
                }
            }
            WebSocketService.onStatusChange = { connected ->
                runOnUiThread { updateConnectionStatus(connected) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            wsService = null
            isBound = false
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            toggleService(true)
        } else {
            Toast.makeText(this, "通知权限被拒绝，将无法接收提醒", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        connectionInfo = findViewById(R.id.connectionInfo)
        btnToggle = findViewById(R.id.btnToggle)
        btnRemindPc = findViewById(R.id.btnRemindPc)
        reminderTitle = findViewById(R.id.reminderTitle)
        reminderBody = findViewById(R.id.reminderBody)
        messageList = findViewById(R.id.messageList)

        // 消息列表
        messageAdapter = MessageAdapter(messages)
        messageList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        messageList.adapter = messageAdapter

        btnToggle.setOnClickListener {
            if (serviceRunning) {
                toggleService(false)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotificationPermission()
                } else {
                    toggleService(true)
                }
            }
        }

        btnRemindPc.setOnClickListener {
            val title = reminderTitle.text.toString().trim()
            val body = reminderBody.text.toString().trim()
            if (title.isEmpty() && body.isEmpty()) {
                Toast.makeText(this, "请输入提醒内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            wsService?.sendReminderToPc(title, body)
            addMessage("sent", title.ifEmpty { "提醒" }, body)
            reminderTitle.text.clear()
            reminderBody.text.clear()
        }

        updateServiceState()
    }

    override fun onResume() {
        super.onResume()
        updateServiceState()
        Intent(this, WebSocketService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onPause() {
        super.onPause()
        if (isBound) {
            // 不清空回调，保留监听
            unbindService(connection)
            isBound = false
        }
    }

    private fun requestNotificationPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED -> toggleService(true)
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                Toast.makeText(this, "梦柒兮 需要通知权限来接收提醒", Toast.LENGTH_LONG).show()
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toggleService(start: Boolean) {
        val intent = Intent(this, WebSocketService::class.java)
        if (start) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            serviceRunning = true
            btnToggle.text = "停止服务"
            connectionInfo.text = "连接中..."
            addMessage("system", "服务已启动", "")
        } else {
            intent.action = "DISCONNECT"
            startService(intent)
            serviceRunning = false
            btnToggle.text = "启动服务"
            connectionInfo.text = "服务已停止"
            statusText.text = "○ 已停止"
            statusText.setTextColor(0xFF8E8E93.toInt())
            btnRemindPc.isEnabled = false
            addMessage("system", "服务已停止", "")
        }
    }

    private fun updateServiceState() {
        val state = WebSocketService.connectionState
        serviceRunning = state != WebSocketService.ConnectionState.DISCONNECTED

        when (state) {
            WebSocketService.ConnectionState.CONNECTED -> updateConnectionStatus(true)
            WebSocketService.ConnectionState.CONNECTING -> {
                btnToggle.text = "停止服务"
                connectionInfo.text = "连接中..."
                serviceRunning = true
            }
            WebSocketService.ConnectionState.DISCONNECTED -> updateConnectionStatus(false)
        }
    }

    private fun updateConnectionStatus(connected: Boolean) {
        statusText.text = if (connected) "● 已连接" else "○ 未连接"
        statusText.setTextColor(if (connected) 0xFF34C759.toInt() else 0xFFFF453A.toInt())
        btnRemindPc.isEnabled = connected
        connectionInfo.text = if (connected) "在线" else "离线"
        if (connected && !serviceRunning) {
            serviceRunning = true
            btnToggle.text = "停止服务"
        }
    }

    private fun addMessage(type: String, title: String, body: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())
        messages.add(MessageItem(type, title, body, time))
        messageAdapter.notifyItemInserted(messages.size - 1)
        messageList.smoothScrollToPosition(messages.size - 1)
    }
}
