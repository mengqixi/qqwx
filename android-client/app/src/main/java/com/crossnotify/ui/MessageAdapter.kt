package com.crossnotify.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.crossnotify.R

class MessageAdapter(
    private var messages: List<MainActivity.MessageItem>,
    private val onSavePhoto: ((String) -> Unit)? = null
) : RecyclerView.Adapter<MessageAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val titleText: TextView = view.findViewById(R.id.msgTitle)
        val bodyText: TextView = view.findViewById(R.id.msgBody)
        val timeText: TextView = view.findViewById(R.id.msgTime)
        val photoPreview: ImageView = view.findViewById(R.id.msgPhoto)
        val btnSave: Button = view.findViewById(R.id.btnSavePhoto)
        val pinBadge: TextView = view.findViewById(R.id.msgPinBadge)
    }

    fun updateMessages(newMessages: List<MainActivity.MessageItem>) {
        messages = newMessages
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.message_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = messages[position]

        // 置顶标记
        val isPinned = msg.pinned && msg.pinExpiry > System.currentTimeMillis()
        if (isPinned && holder.pinBadge != null) {
            holder.pinBadge.visibility = View.VISIBLE
            holder.pinBadge.text = "📌 置顶"
            val remaining = msg.pinExpiry - System.currentTimeMillis()
            val hoursLeft = remaining / 3600000
            val minsLeft = (remaining % 3600000) / 60000
            if (hoursLeft > 0) {
                holder.pinBadge.text = "📌 置顶 ${hoursLeft}h${minsLeft}m"
            } else {
                holder.pinBadge.text = "📌 置顶 ${minsLeft}m"
            }
        } else if (holder.pinBadge != null) {
            holder.pinBadge.visibility = View.GONE
        }

        // 标题显示
        when (msg.type) {
            "sent" -> holder.titleText.text = if (isPinned) "📌 ${msg.title}" else "📤 ${msg.title}"
            "received" -> holder.titleText.text = if (isPinned) "📌 ${msg.title}" else "📩 ${msg.title}"
            "photo_sent" -> holder.titleText.text = "📤 ${msg.title}"
            "photo_received" -> holder.titleText.text = "📩 ${msg.title}"
            else -> holder.titleText.text = msg.title
        }

        if (isPinned) {
            holder.titleText.setTypeface(null, Typeface.BOLD)
            holder.titleText.setTextColor(0xFFE89B3C.toInt())
        } else {
            holder.titleText.setTypeface(null, Typeface.NORMAL)
            holder.titleText.setTextColor(0xFF1A1A2E.toInt())
        }

        if (msg.body.isNotEmpty() && !msg.type.startsWith("photo")) {
            holder.bodyText.text = msg.body
            holder.bodyText.visibility = View.VISIBLE
        } else {
            holder.bodyText.visibility = View.GONE
        }

        // 照片预览
        if (msg.photoBase64.isNotEmpty()) {
            try {
                val bytes = android.util.Base64.decode(msg.photoBase64, android.util.Base64.DEFAULT)
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                holder.photoPreview.setImageBitmap(bmp)
                holder.photoPreview.visibility = View.VISIBLE
                holder.bodyText.visibility = View.GONE
            } catch (_: Exception) { holder.photoPreview.visibility = View.GONE }
        } else {
            holder.photoPreview.visibility = View.GONE
        }

        // 保存按钮
        if (msg.type == "photo_received" && msg.photoBase64.isNotEmpty()) {
            holder.btnSave.visibility = View.VISIBLE
            holder.btnSave.setOnClickListener { onSavePhoto?.invoke(msg.photoBase64) }
        } else {
            holder.btnSave.visibility = View.GONE
        }

        holder.timeText.text = msg.time
    }

    override fun getItemCount() = messages.size
}
