package com.crossnotify.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.crossnotify.R

class MessageAdapter(private val messages: MutableList<MainActivity.MessageItem>) :
    RecyclerView.Adapter<MessageAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val titleText: TextView = view.findViewById(R.id.msgTitle)
        val bodyText: TextView = view.findViewById(R.id.msgBody)
        val timeText: TextView = view.findViewById(R.id.msgTime)
        val photoPreview: ImageView = view.findViewById(R.id.msgPhoto)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.message_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = messages[position]

        when (msg.type) {
            "sent" -> holder.titleText.text = "📤 ${msg.title}"
            "received" -> holder.titleText.text = "📩 ${msg.title}"
            "photo_sent" -> holder.titleText.text = "📤 ${msg.title}"
            "photo_received" -> holder.titleText.text = "📩 ${msg.title}"
            else -> holder.titleText.text = msg.title
        }

        // 显示正文
        if (msg.body.isNotEmpty() && !msg.type.startsWith("photo")) {
            holder.bodyText.text = msg.body
            holder.bodyText.visibility = View.VISIBLE
        } else {
            holder.bodyText.visibility = View.GONE
        }

        // 显示照片预览
        if (msg.photoBase64.isNotEmpty()) {
            try {
                val bytes = android.util.Base64.decode(msg.photoBase64, android.util.Base64.DEFAULT)
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                holder.photoPreview.setImageBitmap(bmp)
                holder.photoPreview.visibility = View.VISIBLE
                holder.bodyText.visibility = View.GONE
            } catch (e: Exception) {
                holder.photoPreview.visibility = View.GONE
            }
        } else {
            holder.photoPreview.visibility = View.GONE
        }

        holder.timeText.text = msg.time
    }

    override fun getItemCount() = messages.size
}
