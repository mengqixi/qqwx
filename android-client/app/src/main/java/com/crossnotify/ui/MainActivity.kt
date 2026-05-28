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
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
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
import com.crossnotify.storage.MessageStorage

class MainActivity : AppCompatActivity() {

    companion object {
        // 用于悬浮窗/BubbleService 访问消息列表
        var activeMessages: MutableList<MessageItem> = mutableListOf()
    }

    private lateinit var statusText: TextView
    private lateinit var connectionInfo: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnRemindPc: Button
    private lateinit var btnSendPhoto: Button
    private lateinit var btnBubble: Button
    private lateinit var reminderBody: EditText
    private lateinit var messageList: RecyclerView

    // ── 置顶 UI ──
    private lateinit var pinCheckbox: CheckBox
    private lateinit var pinDuration1h: Button
    private lateinit var pinDuration6h: Button
    private lateinit var pinDuration24h: Button
    private var pinDurationHours: Int = 6 // 默认 6h

    private var wsService: WebSocketService? = null
    private var isBound = false
    private var serviceRunning = false
    private val messages = mutableListOf<MessageItem>()
    private lateinit var messageAdapter: MessageAdapter

    private val backgroundReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == "com.crossnotify.BACKGROUND") {
                moveTaskToBack(true)
            }
        }
    }

    // 消息数据类
    data class MessageItem(
        val type: String,  // "sent" / "received" / "system" / "photo_sent" / "photo_received"
        val title: String,
        val body: String,
        val time: String,
        val photoBase64: String = "",
        val pinned: Boolean = false,
        val pinExpiry: Long = 0L,
        val timestamp: Long = System.currentTimeMillis(),
        val from: String = "",
        val name: String = ""
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WebSocketService.LocalBinder
            wsService = binder.getService()
            isBound = true
            // 监听收到的提醒
            WebSocketService.onReminderReceived = { title, body ->
                runOnUiThread {
                    // body now contains pin info encoded as "PIN|expiry|actualBody"
                    val (actualBody, isPinned, pinExpiry) = parsePinBody(body)
                    addMessage("received", title, actualBody, pinned = isPinned, pinExpiry = pinExpiry)
                }
            }
            WebSocketService.onPhotoReceived = { base64, fileName ->
                runOnUiThread {
                    addMessage("photo_received", "📷 收到照片", "点击右下角保存", photoBase64 = base64, name = fileName)
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

    // 照片选择器
    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes() ?: return@registerForActivityResult
            inputStream.close()
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
            val fileName = "photo_${System.currentTimeMillis()}.jpg"

            wsService?.sendPhotoToPc(base64, fileName)
            addMessage("sent", "📷 照片已发送", fileName)
            Toast.makeText(this, "照片已发送到 PC", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "照片读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 处理通知中的保存照片请求
        handleIntent(intent)

        // 监听悬浮窗广播：进入后台
        val filter = android.content.IntentFilter("com.crossnotify.BACKGROUND")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(backgroundReceiver, filter, android.Manifest.permission.POST_NOTIFICATIONS, null)
        } else {
            registerReceiver(backgroundReceiver, filter)
        }

        statusText = findViewById(R.id.statusText)
        connectionInfo = findViewById(R.id.connectionInfo)
        btnToggle = findViewById(R.id.btnToggle)
        btnRemindPc = findViewById(R.id.btnRemindPc)
        btnSendPhoto = findViewById(R.id.btnSendPhoto)
        btnBubble = findViewById(R.id.btnBubble)
        reminderBody = findViewById(R.id.reminderBody)
        messageList = findViewById(R.id.messageList)

        // 置顶 UI
        pinCheckbox = findViewById(R.id.pinCheckbox)
        pinDuration1h = findViewById(R.id.pinDuration1h)
        pinDuration6h = findViewById(R.id.pinDuration6h)
        pinDuration24h = findViewById(R.id.pinDuration24h)

        // 加载历史消息
        val savedMessages = MessageStorage.loadMessages(this)
        messages.addAll(savedMessages)
        activeMessages = messages

        // 消息列表
        messageAdapter = MessageAdapter(messages) { base64 ->
            val result = wsService?.savePhoto(base64, "photo_${System.currentTimeMillis()}.jpg")
            val msg = if (result != null) "💾 $result" else "❌ 保存失败"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        messageList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = false // 置顶需要从顶部开始
        }
        messageList.adapter = messageAdapter

        // 置顶复选框事件
        pinCheckbox.setOnCheckedChangeListener { _, isChecked ->
            val alpha = if (isChecked) 1f else 0.4f
            pinDuration1h.alpha = alpha
            pinDuration6h.alpha = alpha
            pinDuration24h.alpha = alpha
            pinDuration1h.isEnabled = isChecked
            pinDuration6h.isEnabled = isChecked
            pinDuration24h.isEnabled = isChecked
        }

        // 置顶时长选择
        pinDuration1h.setOnClickListener { selectPinDuration(1) }
        pinDuration6h.setOnClickListener { selectPinDuration(6) }
        pinDuration24h.setOnClickListener { selectPinDuration(24) }
        selectPinDuration(6) // 默认

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

        // 发送照片
        btnSendPhoto.setOnClickListener {
            photoPickerLauncher.launch("image/*")
        }

        // 悬浮窗
        btnBubble.setOnClickListener {
            val intent = Intent(this, com.crossnotify.service.BubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (android.provider.Settings.canDrawOverlays(this)) {
                    startService(intent)
                } else {
                    val overlayIntent = Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(overlayIntent)
                    Toast.makeText(this, "请允许悬浮窗权限后重试", Toast.LENGTH_LONG).show()
                }
            } else {
                startService(intent)
            }
        }

        btnRemindPc.setOnClickListener {
            val body = reminderBody.text.toString().trim()
            if (body.isEmpty()) {
                Toast.makeText(this, "请输入提醒内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val isPinned = pinCheckbox.isChecked
            val pinExpiry = if (isPinned) System.currentTimeMillis() + pinDurationHours * 3600 * 1000L else 0L

            // 发送 pin 信息编码在 body 中（WebSocketService 原样转发）
            val sendBody = if (isPinned) {
                "PIN|$pinExpiry|$body"
            } else {
                body
            }
            wsService?.sendReminderToPc(if (isPinned) "📌 [置顶]" else "提醒", sendBody)
            addMessage("sent", if (isPinned) "📌 已置顶" else "已发送", body, pinned = isPinned, pinExpiry = pinExpiry)
            reminderBody.text.clear()
        }

        // 检查华为设备电池优化
        checkBatteryOptimization()

        // 渲染消息
        renderMessages()
        updateServiceState()
    }

    override fun onResume() {
        super.onResume()
        handleIntent(intent)
        updateServiceState()
        Intent(this, WebSocketService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(backgroundReceiver) } catch (_: Exception) {}
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra("action") == "save_photo") {
            val data = intent.getStringExtra("photo_data") ?: return
            val name = intent.getStringExtra("photo_name") ?: "photo.jpg"
            val result = wsService?.savePhoto(data, name)
            Toast.makeText(this, if (result != null) "💾 $result" else "❌ 保存失败", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    private fun selectPinDuration(hours: Int) {
        pinDurationHours = hours
        pinDuration1h.isSelected = hours == 1
        pinDuration6h.isSelected = hours == 6
        pinDuration24h.isSelected = hours == 24
        // 更新背景样式
        pinDuration1h.setBackgroundResource(if (hours == 1) R.drawable.bg_button_gradient else R.drawable.bg_glass_dark)
        pinDuration6h.setBackgroundResource(if (hours == 6) R.drawable.bg_button_gradient else R.drawable.bg_glass_dark)
        pinDuration24h.setBackgroundResource(if (hours == 24) R.drawable.bg_button_gradient else R.drawable.bg_glass_dark)
    }

    private fun parsePinBody(body: String): Triple<String, Boolean, Long> {
        if (body.startsWith("PIN|")) {
            val parts = body.split("|", limit = 3)
            if (parts.size == 3) {
                val expiry = parts[1].toLongOrNull() ?: 0L
                val actual = parts[2]
                return Triple(actual, true, expiry)
            }
        }
        return Triple(body, false, 0L)
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
            btnToggle.visibility = android.view.View.GONE
            connectionInfo.text = "连接中..."
            addMessage("system", "", "服务已启动")
        } else {
            intent.action = "DISCONNECT"
            startService(intent)
            serviceRunning = false
            btnToggle.visibility = android.view.View.VISIBLE
            btnToggle.text = "▶"
            connectionInfo.text = "已停止"
            statusText.text = "○ 已停止"
            statusText.setTextColor(0xFF8E8E93.toInt())
            btnRemindPc.isEnabled = false
            btnSendPhoto.isEnabled = false
            addMessage("system", "", "服务已停止")
        }
    }

    private fun updateServiceState() {
        val state = WebSocketService.connectionState
        serviceRunning = state != WebSocketService.ConnectionState.DISCONNECTED

        when (state) {
            WebSocketService.ConnectionState.CONNECTED -> updateConnectionStatus(true)
            WebSocketService.ConnectionState.CONNECTING -> {
                btnToggle.visibility = android.view.View.GONE
                connectionInfo.text = "连接中..."
                serviceRunning = true
            }
            WebSocketService.ConnectionState.DISCONNECTED -> updateConnectionStatus(false)
        }
    }

    private fun updateConnectionStatus(connected: Boolean) {
        statusText.text = if (connected) "● 已连接" else "○ 未连接"
        statusText.setTextColor(if (connected) 0xFF60D7A9.toInt() else 0xFFFF453A.toInt())
        btnRemindPc.isEnabled = connected
        btnSendPhoto.isEnabled = connected
        btnBubble.visibility = if (connected) android.view.View.VISIBLE else android.view.View.GONE
        connectionInfo.text = if (connected) "在线" else "离线"
        if (connected && !serviceRunning) {
            serviceRunning = true
            btnToggle.visibility = android.view.View.GONE
        }
        if (!connected) {
            btnToggle.visibility = android.view.View.VISIBLE
            btnToggle.text = "▶"
        }
    }

    private fun addMessage(type: String, title: String, body: String = "", photoBase64: String = "",
                           pinned: Boolean = false, pinExpiry: Long = 0L, name: String = "") {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())
        val msg = MessageItem(
            type = type,
            title = title,
            body = body,
            time = time,
            photoBase64 = photoBase64,
            pinned = pinned,
            pinExpiry = pinExpiry,
            timestamp = System.currentTimeMillis(),
            name = name
        )
        messages.add(msg)
        // 更新共享实例
        activeMessages = messages
        // 持久化
        MessageStorage.saveMessages(this, messages)
        // 重新渲染（置顶消息需要排序）
        renderMessages()
    }

    private fun renderMessages() {
        val now = System.currentTimeMillis()

        // 移除过期置顶（pinExpiry > 0 且已过期的取消置顶状态）
        val expiredMessages = messages.filter { it.pinned && it.pinExpiry > 0 && now >= it.pinExpiry }
        if (expiredMessages.isNotEmpty()) {
            val expiredIndices = expiredMessages.map { messages.indexOf(it) }.filter { it >= 0 }
            for (index in expiredIndices.sortedDescending()) {
                val old = messages[index]
                messages[index] = old.copy(pinned = false, pinExpiry = 0L)
            }
            // 持久化更新
            MessageStorage.saveMessages(this, messages)
        }

        // 更新共享实例
        activeMessages = messages

        // 排序：置顶优先，然后按时间
        val sorted = messages.sortedWith(Comparator { a, b ->
            val aPin = a.pinned && a.pinExpiry > now
            val bPin = b.pinned && b.pinExpiry > now
            if (aPin != bPin) {
                if (aPin) -1 else 1
            } else {
                (a.timestamp).compareTo(b.timestamp)
            }
        })

        messageAdapter.updateMessages(sorted)
        messageAdapter.notifyDataSetChanged()
    }

    /**
     * 华为/小米等厂商的电池优化检查
     */
    private fun checkBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val packageName = packageName

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                // 检查是否华为设备
                val manufacturer = Build.MANUFACTURER.lowercase()
                if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
                    Toast.makeText(this,
                        "检测到华为设备，建议：\n设置 → 应用 → 梦柒兮 → 电池 → 不允许限制\n以保证后台消息接收",
                        Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this,
                        "建议关闭电池优化以保证后台消息接收",
                        Toast.LENGTH_LONG).show()
                }

                // 提供跳转电池优化的按钮
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }
}
