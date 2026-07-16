package com.texttospeech.app

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(
    val id: String,
    val fileName: String,
    val preview: String,
    val charCount: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val uriString: String = ""
)

class HistoryManager(context: Context) {

    private val prefs = context.getSharedPreferences("tts_history", Context.MODE_PRIVATE)

    fun getAll(): List<HistoryItem> {
        val json = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryItem(
                    id        = o.getString("id"),
                    fileName  = o.getString("fileName"),
                    preview   = o.getString("preview"),
                    charCount = o.getInt("charCount"),
                    timestamp = o.getLong("timestamp"),
                    uriString = o.optString("uriString", "")
                )
            }.sortedByDescending { it.timestamp }
        } catch (_: Exception) { emptyList() }
    }

    fun add(fileName: String, text: String, uriString: String = "") {
        val item = HistoryItem(
            id        = System.currentTimeMillis().toString(),
            fileName  = fileName,
            preview   = text.take(120).replace('\n', ' '),
            charCount = text.length,
            uriString = uriString
        )
        // Deduplicate by fileName, keep most recent
        val list = getAll().filter { it.fileName != fileName }.toMutableList()
        list.add(0, item)
        save(list.take(MAX_HISTORY))
    }

    fun remove(id: String) = save(getAll().filter { it.id != id })

    fun clear() = prefs.edit { remove(KEY) }

    private fun save(list: List<HistoryItem>) {
        val arr = JSONArray()
        list.forEach { h ->
            arr.put(JSONObject().apply {
                put("id",        h.id)
                put("fileName",  h.fileName)
                put("preview",   h.preview)
                put("charCount", h.charCount)
                put("timestamp", h.timestamp)
                put("uriString", h.uriString)
            })
        }
        prefs.edit { putString(KEY, arr.toString()) }
    }

    companion object {
        private const val KEY = "history"
        private const val MAX_HISTORY = 30
    }
}
