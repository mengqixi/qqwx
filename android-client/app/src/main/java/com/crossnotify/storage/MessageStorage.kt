package com.crossnotify.storage

import android.content.Context
import com.crossnotify.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 消息本地持久化工具
 * 使用内部存储的 JSON 文件保存消息历史
 */
object MessageStorage {

    private const val FILE_NAME = "messages.json"
    private const val MAX_MESSAGES = 500

    /**
     * 保存消息列表到本地文件
     */
    fun saveMessages(context: Context, messages: List<MainActivity.MessageItem>) {
        try {
            val jsonArray = JSONArray()
            // 只存最新的 MAX_MESSAGES 条
            val toSave = if (messages.size > MAX_MESSAGES) {
                messages.subList(messages.size - MAX_MESSAGES, messages.size)
            } else messages

            for (msg in toSave) {
                val obj = JSONObject().apply {
                    put("type", msg.type)
                    put("title", msg.title)
                    put("body", msg.body)
                    put("time", msg.time)
                    put("photoBase64", msg.photoBase64)
                    put("pinned", msg.pinned)
                    put("pinExpiry", msg.pinExpiry)
                    put("timestamp", msg.timestamp)
                    put("from", msg.from)
                    put("name", msg.name)
                }
                jsonArray.put(obj)
            }

            context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use {
                it.write(jsonArray.toString(2).toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            android.util.Log.e("MessageStorage", "Failed to save messages", e)
        }
    }

    /**
     * 从本地文件加载消息列表
     */
    fun loadMessages(context: Context): MutableList<MainActivity.MessageItem> {
        val messages = mutableListOf<MainActivity.MessageItem>()
        try {
            if (!context.getFileStreamPath(FILE_NAME).exists()) return messages

            val jsonStr = context.openFileInput(FILE_NAME).use {
                it.bufferedReader(Charsets.UTF_8).readText()
            }
            val jsonArray = JSONArray(jsonStr)

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val msg = MainActivity.MessageItem(
                    type = obj.optString("type", "received"),
                    title = obj.optString("title", ""),
                    body = obj.optString("body", ""),
                    time = obj.optString("time", ""),
                    photoBase64 = obj.optString("photoBase64", ""),
                    pinned = obj.optBoolean("pinned", false),
                    pinExpiry = obj.optLong("pinExpiry", 0L),
                    timestamp = obj.optLong("timestamp", 0L),
                    from = obj.optString("from", ""),
                    name = obj.optString("name", "")
                )
                messages.add(msg)
            }
        } catch (e: Exception) {
            android.util.Log.e("MessageStorage", "Failed to load messages", e)
        }
        return messages
    }

    /**
     * 清空所有消息
     */
    fun clearMessages(context: Context) {
        try {
            context.deleteFile(FILE_NAME)
        } catch (e: Exception) {
            android.util.Log.e("MessageStorage", "Failed to clear messages", e)
        }
    }
}
