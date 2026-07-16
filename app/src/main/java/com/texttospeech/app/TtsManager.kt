package com.texttospeech.app

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class TtsManager(
    private val context: Context,
    private val onInitialized: () -> Unit,
    private val onProgress: (currentChunk: Int, totalChunks: Int, percentage: Int) -> Unit,
    private val onChunkStart: (chunkText: String, chunkIndex: Int) -> Unit,
    private val onFinished: () -> Unit,
    private val onError: (message: String) -> Unit
) {

    companion object {
        const val MAX_CHUNK_SIZE = 3000
        const val MIN_SPEED = 0.1f
        const val MAX_SPEED = 4.0f
        const val DEFAULT_SPEED = 1.0f
    }

    private var tts: TextToSpeech? = null

    var isInitialized = false
        private set

    var isPlaying = false
        private set

    var isPaused = false
        private set

    private var chunks: List<String> = emptyList()
    private var currentChunkIndex = 0
    private var speechRate = DEFAULT_SPEED
    private var currentLocale = Locale("vi", "VN")

    // ─── Init ───────────────────────────────────────────────────────────────

    fun init() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                applyLanguage(currentLocale)
                tts?.setSpeechRate(speechRate)
                setupListener()
                onInitialized()
            } else {
                onError("Không thể khởi tạo Text-to-Speech. Vui lòng cài Google TTS.")
            }
        }
    }

    private fun setupListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {

            override fun onStart(utteranceId: String?) {
                val index = parseIndex(utteranceId) ?: return
                currentChunkIndex = index
                val percentage = ((index + 1) * 100) / chunks.size.coerceAtLeast(1)
                onProgress(index + 1, chunks.size, percentage)
                onChunkStart(chunks.getOrElse(index) { "" }, index)
            }

            override fun onDone(utteranceId: String?) {
                if (isPaused) return   // User paused — stay at current chunk
                val index = parseIndex(utteranceId) ?: return
                val nextIndex = index + 1
                if (nextIndex < chunks.size) {
                    currentChunkIndex = nextIndex
                    playChunk(nextIndex)
                } else {
                    isPlaying = false
                    isPaused = false
                    currentChunkIndex = 0
                    onFinished()
                }
            }

            @Deprecated("Deprecated in newer API")
            override fun onError(utteranceId: String?) {
                if (!isPaused && isPlaying) {
                    onError("Lỗi khi đọc đoạn văn bản.")
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (!isPaused && isPlaying) {
                    onError("Lỗi TTS (code $errorCode)")
                }
            }
        })
    }

    // ─── Text management ────────────────────────────────────────────────────

    fun setText(text: String) {
        stop()
        chunks = splitIntoChunks(text)
        currentChunkIndex = 0
    }

    fun getTotalChunks() = chunks.size
    fun getCurrentChunkIndex() = currentChunkIndex
    fun getSpeechRate() = speechRate

    // ─── Playback control ───────────────────────────────────────────────────

    fun play() {
        if (!isInitialized) { onError("TTS chưa sẵn sàng, vui lòng chờ."); return }
        if (chunks.isEmpty()) { onError("Không có nội dung để đọc."); return }

        isPlaying = true
        isPaused = false
        playChunk(currentChunkIndex)
    }

    fun pause() {
        if (!isPlaying) return
        isPaused = true   // Must be set BEFORE tts.stop() to suppress onDone auto-advance
        isPlaying = false
        tts?.stop()
    }

    fun resume() {
        if (!isPaused) return
        isPaused = false
        isPlaying = true
        playChunk(currentChunkIndex)   // Replay current chunk from start
    }

    fun stop() {
        tts?.stop()
        isPlaying = false
        isPaused = false
        currentChunkIndex = 0
    }

    fun seekToChunk(index: Int) {
        val target = index.coerceIn(0, (chunks.size - 1).coerceAtLeast(0))
        val wasPlaying = isPlaying
        tts?.stop()
        currentChunkIndex = target
        if (wasPlaying) {
            isPlaying = true
            isPaused = false
            playChunk(target)
        }
    }

    // ─── Settings ───────────────────────────────────────────────────────────

    fun setSpeed(rate: Float) {
        speechRate = rate.coerceIn(MIN_SPEED, MAX_SPEED)
        tts?.setSpeechRate(speechRate)
    }

    fun setLanguage(locale: Locale) {
        currentLocale = locale
        if (isInitialized) applyLanguage(locale)
    }

    private fun applyLanguage(locale: Locale) {
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Fallback to English
            tts?.setLanguage(Locale.ENGLISH)
            onError("Ngôn ngữ ${locale.displayName} chưa được hỗ trợ, dùng tiếng Anh thay thế.")
        }
    }

    // ─── Internal ───────────────────────────────────────────────────────────

    private fun playChunk(index: Int) {
        if (index >= chunks.size || !isInitialized) return
        tts?.speak(
            chunks[index],
            TextToSpeech.QUEUE_FLUSH,
            Bundle(),
            "chunk_$index"
        )
    }

    private fun parseIndex(utteranceId: String?): Int? =
        utteranceId?.removePrefix("chunk_")?.toIntOrNull()

    /**
     * Splits text into chunks at sentence boundaries to avoid the Android TTS ~4000 char limit.
     * Splits preferably at: . ! ? \n — then at spaces as fallback.
     */
    private fun splitIntoChunks(text: String): List<String> {
        if (text.length <= MAX_CHUNK_SIZE) return listOf(text).filter { it.isNotBlank() }

        val result = mutableListOf<String>()
        var start = 0

        while (start < text.length) {
            val remaining = text.length - start
            if (remaining <= MAX_CHUNK_SIZE) {
                val last = text.substring(start).trim()
                if (last.isNotBlank()) result.add(last)
                break
            }

            val end = start + MAX_CHUNK_SIZE
            val half = start + MAX_CHUNK_SIZE / 2

            // Search backwards from `end` for a good split point
            var splitAt = -1
            for (i in end downTo half) {
                when (text[i]) {
                    '.', '!', '?', '\n', '。', '！', '？' -> { splitAt = i + 1; break }
                }
            }
            if (splitAt == -1) {
                // Fallback: split at space
                for (i in end downTo half) {
                    if (text[i] == ' ') { splitAt = i + 1; break }
                }
            }
            if (splitAt == -1) splitAt = end   // Hard split

            val chunk = text.substring(start, splitAt).trim()
            if (chunk.isNotBlank()) result.add(chunk)
            start = splitAt
        }

        return result
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        isPlaying = false
        isPaused = false
    }
}
