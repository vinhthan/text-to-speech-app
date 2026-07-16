package com.texttospeech.app

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(
    val id: String,
    val label: String,
    val chunkIndex: Int,
    val preview: String,
    val timestamp: Long = System.currentTimeMillis()
)

class BookmarkManager(context: Context) {

    private val prefs = context.getSharedPreferences("tts_bookmarks", Context.MODE_PRIVATE)

    fun getAll(): List<Bookmark> {
        val json = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Bookmark(
                    id        = o.getString("id"),
                    label     = o.getString("label"),
                    chunkIndex = o.getInt("chunkIndex"),
                    preview   = o.getString("preview"),
                    timestamp = o.getLong("timestamp")
                )
            }.sortedByDescending { it.timestamp }
        } catch (_: Exception) { emptyList() }
    }

    fun add(label: String, chunkIndex: Int, preview: String): Bookmark {
        val bm = Bookmark(
            id         = System.currentTimeMillis().toString(),
            label      = label,
            chunkIndex = chunkIndex,
            preview    = preview.take(80).replace('\n', ' ')
        )
        val list = getAll().toMutableList()
        list.add(0, bm)
        save(list.take(MAX_BOOKMARKS))
        return bm
    }

    fun remove(id: String) = save(getAll().filter { it.id != id })

    fun clear() = prefs.edit { remove(KEY) }

    private fun save(list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(JSONObject().apply {
                put("id",         b.id)
                put("label",      b.label)
                put("chunkIndex", b.chunkIndex)
                put("preview",    b.preview)
                put("timestamp",  b.timestamp)
            })
        }
        prefs.edit { putString(KEY, arr.toString()) }
    }

    companion object {
        private const val KEY = "bookmarks"
        private const val MAX_BOOKMARKS = 50
    }
}
