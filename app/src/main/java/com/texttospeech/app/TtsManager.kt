package com.texttospeech.app

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Wraps Android TextToSpeech with:
 *  - Listener-based callbacks (multiple observers supported)
 *  - Sentence-level chunking — each sentence is its own TTS utterance
 *  - QUEUE_ADD bulk-queuing: all chunks are submitted to the TTS engine at
 *    once so playback is entirely engine-driven and never relies on a
 *    mainHandler.post() round-trip between chunks. This is the key fix for
 *    background playback: Android throttles the app's main thread when it is
 *    in the background, so the old speak→onDone→post→speak loop would stall.
 *    With bulk-queuing the engine runs independently of our main thread.
 *  - speak() return-value check + error signal on failure
 *  - Best-voice auto-selection per locale (neural / enhanced preferred)
 *  - Auto-language mode — LanguageDetector labels each word-group VI / EN
 *    (falls back to one-at-a-time because setLanguage() is not queued)
 */
class TtsManager(private val context: Context) {

    // ─── Listener interface ──────────────────────────────────────────────────

    interface Listener {
        fun onInitialized()                                           = Unit
        /** Fired just before the first chunk is sent to the TTS engine. */
        fun onPlaybackStarted()                                       = Unit
        fun onProgress(current: Int, total: Int, percentage: Int)   = Unit
        fun onChunkStart(text: String, index: Int)                   = Unit
        fun onFinished()                                              = Unit
        fun onError(message: String)                                  = Unit
    }

    private val listeners = mutableListOf<Listener>()
    fun addListener(l: Listener)    { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: Listener) { synchronized(listeners) { listeners.remove(l) } }
    private fun eachListener(block: Listener.() -> Unit) {
        synchronized(listeners) { listeners.toList() }.forEach { it.block() }
    }

    // ─── Constants ───────────────────────────────────────────────────────────

    companion object {
        const val MAX_CHUNK_SIZE = 3000
        const val MIN_SPEED      = 0.1f
        const val MAX_SPEED      = 4.0f
        const val DEFAULT_SPEED  = 1.0f
    }

    // ─── Voice metadata ───────────────────────────────────────────────────────

    enum class VoiceStatus {
        LOCAL, NETWORK, NOT_INSTALLED
    }

    data class VoiceItem(
        val voice: android.speech.tts.Voice,
        val status: VoiceStatus
    )

    // ─── Internal chunk type ─────────────────────────────────────────────────

    private data class Chunk(val text: String, val locale: Locale)

    // ─── State ───────────────────────────────────────────────────────────────

    private var tts: TextToSpeech? = null

    /**
     * Handler on the main looper — used to post speakChunk() calls OUT of
     * the TTS binder callback thread (onDone/onError). Calling speak() from
     * inside a TTS progress callback is technically undefined behaviour and
     * silently fails on several OEM ROMs when the app is in the background.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var isInitialized = false; private set
    @Volatile var isPlaying     = false; private set
    @Volatile var isPaused      = false; private set

    /** Raw text saved so TtsService can persist it across process restarts. */
    private var rawText = ""

    private var chunks                      = emptyList<Chunk>()
    @Volatile private var currentChunkIndex = 0

    private var speechRate             = DEFAULT_SPEED
    private var currentLocale          = Locale("vi", "VN")
    @Volatile private var autoLanguage = false

    private var activeSpeakingLocale: Locale? = null
    private val bestVoiceCache = mutableMapOf<String, android.speech.tts.Voice?>()

    // ─── Initialisation ──────────────────────────────────────────────────────

    fun init() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                activeSpeakingLocale = applyLocale(currentLocale)
                tts?.setSpeechRate(speechRate)
                tts?.setPitch(1.0f)
                attachListener()
                eachListener { onInitialized() }
            } else {
                eachListener { onError("Không thể khởi tạo Text-to-Speech. Hãy cài Google TTS.") }
            }
        }
    }

    private fun attachListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {

            override fun onStart(utteranceId: String?) {
                val idx = parseIdx(utteranceId) ?: return
                currentChunkIndex = idx
                val pct = ((idx + 1) * 100) / chunks.size.coerceAtLeast(1)
                eachListener { onProgress(idx + 1, chunks.size, pct) }
                eachListener { onChunkStart(chunks.getOrElse(idx) { Chunk("", currentLocale) }.text, idx) }
            }

            override fun onDone(utteranceId: String?) {
                if (isPaused) return
                val idx = parseIdx(utteranceId) ?: return
                mainHandler.post {
                    if (isPaused || !isPlaying) return@post
                    if (idx >= chunks.size - 1) {
                        // Last chunk finished → playback complete
                        isPlaying = false
                        currentChunkIndex = 0
                        eachListener { onFinished() }
                    } else if (autoLanguage) {
                        // Auto-language mode: each chunk may need setLanguage() which is
                        // NOT queued, so we must speak one chunk at a time.
                        val next = idx + 1
                        currentChunkIndex = next
                        speakChunk(next)
                    }
                    // Single-locale mode: all chunks were already bulk-queued via
                    // queueAllChunks(); the engine drives playback independently.
                }
            }

            @Deprecated("Deprecated in newer API")
            override fun onError(utteranceId: String?) {
                if (isPlaying) eachListener { onError("Lỗi TTS khi đọc câu.") }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (isPlaying) eachListener { onError("Lỗi TTS (code $errorCode)") }
            }
        })
    }

    // ─── Text management ─────────────────────────────────────────────────────

    fun setText(text: String) {
        rawText = text
        stop()
        chunks = buildChunks(text)
        currentChunkIndex = 0
    }

    fun getRawText()            = rawText
    fun getCurrentLocale()      = currentLocale
    fun getSpeechRate()         = speechRate
    fun getTotalChunks()        = chunks.size
    fun getCurrentChunkIndex()  = currentChunkIndex

    // ─── Playback control ────────────────────────────────────────────────────

    fun play() {
        if (!isInitialized) { eachListener { onError("TTS chưa sẵn sàng.") }; return }
        if (chunks.isEmpty()) { eachListener { onError("Chưa có nội dung để đọc.") }; return }
        isPlaying = true
        isPaused  = false
        activeSpeakingLocale = null
        eachListener { onPlaybackStarted() }
        if (autoLanguage) speakChunk(currentChunkIndex)   // one-at-a-time for locale switching
        else              queueAllChunks(currentChunkIndex) // bulk-queue for robust background play
    }

    fun pause() {
        if (!isPlaying) return
        isPaused  = true
        isPlaying = false
        tts?.stop()
    }

    fun resume() {
        if (!isPaused) return
        isPaused  = false
        isPlaying = true
        activeSpeakingLocale = null
        eachListener { onPlaybackStarted() }
        if (autoLanguage) speakChunk(currentChunkIndex)
        else              queueAllChunks(currentChunkIndex)
    }

    fun stop() {
        mainHandler.removeCallbacksAndMessages(null)   // cancel any pending speakChunk posts
        tts?.stop()
        isPlaying         = false
        isPaused          = false
        currentChunkIndex = 0
    }

    fun seekToChunk(index: Int) {
        val wasPlaying = isPlaying
        mainHandler.removeCallbacksAndMessages(null)
        tts?.stop()
        currentChunkIndex = index.coerceIn(0, (chunks.size - 1).coerceAtLeast(0))
        if (wasPlaying) {
            isPlaying = true
            isPaused  = false
            activeSpeakingLocale = null
            if (autoLanguage) speakChunk(currentChunkIndex)
            else              queueAllChunks(currentChunkIndex)
        }
    }

    // ─── Settings ────────────────────────────────────────────────────────────

    fun setSpeed(rate: Float) {
        speechRate = rate.coerceIn(MIN_SPEED, MAX_SPEED)
        tts?.setSpeechRate(speechRate)
    }

    fun setLanguage(locale: Locale) {
        currentLocale = locale
        if (isInitialized) activeSpeakingLocale = applyLocale(locale)
    }

    fun setAutoLanguage(enabled: Boolean) { autoLanguage = enabled }

    fun getVoicesForLocale(locale: Locale): List<VoiceItem> {
        val notInstalledKey = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED
        val allVoices = tts?.voices ?: return emptyList()

        // Try progressively looser locale matching so OEM TTS engines are covered
        val matched = allVoices.filter { v -> voiceMatchesLocale(v, locale) }
            .ifEmpty { allVoices.toList() }   // fallback: show ALL voices if nothing matched

        return matched
            .map { v ->
                val status = when {
                    v.features.contains(notInstalledKey) -> VoiceStatus.NOT_INSTALLED
                    v.isNetworkConnectionRequired        -> VoiceStatus.NETWORK
                    else                                 -> VoiceStatus.LOCAL
                }
                VoiceItem(v, status)
            }
            .sortedWith(
                compareBy<VoiceItem> { it.status.ordinal }
                    .thenByDescending { it.voice.quality }
            )
    }

    fun getCurrentVoice(): android.speech.tts.Voice? = tts?.voice

    fun setVoice(voice: android.speech.tts.Voice) {
        tts?.voice = voice
        bestVoiceCache[voice.locale.language] = voice
        activeSpeakingLocale = null
    }

    // ─── Internal helpers ────────────────────────────────────────────────────

    private fun speakChunk(index: Int) {
        if (index < 0 || index >= chunks.size || !isInitialized) return
        val chunk = chunks[index]
        if (chunk.locale != activeSpeakingLocale) {
            activeSpeakingLocale = applyLocale(chunk.locale)
        }
        val result = tts?.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, Bundle(), "chunk_$index")
        if (result == TextToSpeech.ERROR) {
            isPlaying = false
            eachListener { onError("TTS engine lỗi — nhấn Phát để thử lại") }
        }
    }

    /**
     * Submits all chunks from [startIndex] to the end into the TTS engine queue
     * using QUEUE_ADD. Once queued, the engine plays them sequentially with no
     * involvement from our main thread between chunks — this is what makes
     * background playback reliable. Android can throttle the app's main thread
     * when in background; with QUEUE_ADD the engine is completely self-driving.
     *
     * Only used in single-locale mode. Auto-language falls back to speakChunk()
     * because setLanguage() is not part of the TTS utterance queue.
     */
    private fun queueAllChunks(startIndex: Int) {
        if (startIndex < 0 || startIndex >= chunks.size || !isInitialized) return
        // Apply locale once for the whole batch (single-locale mode)
        val locale = chunks[startIndex].locale
        if (locale != activeSpeakingLocale) {
            activeSpeakingLocale = applyLocale(locale)
        }
        for (i in startIndex until chunks.size) {
            val mode = if (i == startIndex) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = tts?.speak(chunks[i].text, mode, Bundle(), "chunk_$i")
            if (result == TextToSpeech.ERROR) {
                isPlaying = false
                eachListener { onError("TTS engine lỗi — nhấn Phát để thử lại") }
                return
            }
        }
    }

    private fun parseIdx(id: String?) = id?.removePrefix("chunk_")?.toIntOrNull()

    private fun applyLocale(locale: Locale): Locale {
        val result = tts?.setLanguage(locale)
        return if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(Locale.ENGLISH)
            selectBestVoice(Locale.ENGLISH)
            eachListener { onError("Ngôn ngữ ${locale.displayName} chưa được hỗ trợ — dùng tiếng Anh.") }
            Locale.ENGLISH
        } else {
            selectBestVoice(locale)
            locale
        }
    }

    /**
     * Checks whether a Voice matches the target locale using multiple strategies.
     * This is necessary because OEM TTS engines (Samsung, Xiaomi, MIUI…) may
     * represent locale differently (e.g. "vi" vs "vi-VN" vs "vie").
     */
    private fun voiceMatchesLocale(v: android.speech.tts.Voice, locale: Locale): Boolean {
        val lang = locale.language
        return v.locale.language == lang ||
               v.locale.toLanguageTag().startsWith(lang) ||
               v.locale.toString().startsWith(lang)
    }

    private fun selectBestVoice(locale: Locale) {
        val engine = tts ?: return
        val key = locale.language

        // Only use cached value if it is non-null; don't permanently cache null
        // (voices list may be empty during early TTS init on some OEM engines).
        val cached = bestVoiceCache[key]
        if (cached != null) {
            engine.voice = cached
            return
        }

        val found = engine.voices
            ?.filter { v ->
                voiceMatchesLocale(v, locale) &&
                !v.isNetworkConnectionRequired &&
                !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            ?.maxByOrNull { it.quality }

        if (found != null) {
            bestVoiceCache[key] = found
            engine.voice = found
        }
        // If still null (engine not ready yet), leave engine.voice unchanged
        // and do NOT store null so the next call will retry.
    }

    // ─── Chunking ────────────────────────────────────────────────────────────

    private fun buildChunks(text: String): List<Chunk> {
        return if (autoLanguage) {
            LanguageDetector.segmentText(text, currentLocale)
                .flatMap { seg -> buildMonolingualChunks(seg.text, seg.locale) }
        } else {
            buildMonolingualChunks(text, currentLocale)
        }
    }

    private fun buildMonolingualChunks(text: String, locale: Locale): List<Chunk> {
        val result = mutableListOf<Chunk>()
        for (sent in splitSentences(text)) {
            val s = sent.trim()
            if (s.isBlank()) continue
            if (s.length > MAX_CHUNK_SIZE) {
                splitByWords(s).forEach { result += Chunk(it, locale) }
            } else {
                result += Chunk(s, locale)
            }
        }
        return result
    }

    private fun splitSentences(text: String): List<String> {
        val marked = text
            .replace(Regex("""([.!?！？。])\s+""")) { "${it.groupValues[1]}\u0000" }
            .replace(Regex("""\n{2,}"""), "\n\n\u0000")
        return marked.split("\u0000")
            .map { it.replace("\u0000", "").trim() }
            .filter { it.isNotBlank() }
    }

    private fun splitByWords(text: String): List<String> {
        val result = mutableListOf<String>()
        val buf    = StringBuilder()
        for (word in text.split(' ')) {
            if (buf.length + word.length + 1 > MAX_CHUNK_SIZE && buf.isNotBlank()) {
                result += buf.toString().trim()
                buf.clear()
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(word)
        }
        if (buf.isNotBlank()) result += buf.toString().trim()
        return result
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts           = null
        isInitialized = false
        isPlaying     = false
        isPaused      = false
        bestVoiceCache.clear()
    }
}
