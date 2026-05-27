package com.crossnotify.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.crossnotify.R

class MessageAdapter(private val messages: MutableList<MainActivity.MessageItem>) :
    RecyclerView.Adapter<MessageAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val titleText: TextView = view.findViewById(R.id.msgTitle)
        val bodyText: TextView = view.findViewById(R.id.msgBody)
        val timeText: TextView = view.findViewById(R.id.msgTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.message_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = messages[position]
        when (msg.type) {
            "sent" -> holder.titleText.text = "📤 ${msg.title}"
            "received" -> holder.titleText.text = "📩 ${msg.title}"
            else -> holder.titleText.text = msg.title
        }

        if (msg.body.isNotEmpty()) {
            holder.bodyText.text = msg.body
            holder.bodyText.visibility = View.VISIBLE
        } else {
            holder.bodyText.visibility = View.GONE
        }
        holder.timeText.text = msg.time
    }

    override fun getItemCount() = messages.size
}
