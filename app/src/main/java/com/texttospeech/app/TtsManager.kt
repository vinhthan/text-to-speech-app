package com.texttospeech.app

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Wraps Android TextToSpeech with:
 *  - Listener-based callbacks (multiple observers supported)
 *  - Sentence-level chunking — each sentence is its own TTS utterance
 *    → better prosody / intonation per sentence, natural inter-sentence pauses
 *  - Explicit sequential triggering in onDone — no reliance on QUEUE_ADD
 *    which can be silently dropped on some Android versions / engine combinations
 *  - Best-voice auto-selection — picks the highest-quality installed voice
 *    for each locale (prefers neural / enhanced voices when available)
 *  - Auto-language mode — [LanguageDetector] labels each word-group with the
 *    correct locale (VI / EN) so the TTS engine switches voice mid-document
 */
class TtsManager(private val context: Context) {

    // ─── Listener interface ──────────────────────────────────────────────────

    interface Listener {
        fun onInitialized()                                           = Unit
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

    // ─── Internal chunk type ─────────────────────────────────────────────────

    private data class Chunk(val text: String, val locale: Locale)

    // ─── State ───────────────────────────────────────────────────────────────

    private var tts: TextToSpeech? = null

    @Volatile var isInitialized = false; private set
    @Volatile var isPlaying     = false; private set
    @Volatile var isPaused      = false; private set

    private var chunks                      = emptyList<Chunk>()
    @Volatile private var currentChunkIndex = 0

    private var speechRate             = DEFAULT_SPEED
    private var currentLocale          = Locale("vi", "VN")
    @Volatile private var autoLanguage = false

    /**
     * The locale actually loaded in the TTS engine right now.
     * Null = unknown / stale → must re-apply before the next speak() call.
     */
    private var activeSpeakingLocale: Locale? = null

    /** Cache of best voice per language code to avoid repeated voice queries. */
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
                if (idx >= chunks.size - 1) {
                    isPlaying = false
                    currentChunkIndex = 0
                    eachListener { onFinished() }
                } else {
                    val next = idx + 1
                    currentChunkIndex = next
                    speakChunk(next)
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
        stop()
        chunks = buildChunks(text)
        currentChunkIndex = 0
    }

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
        activeSpeakingLocale = null   // force locale application on first chunk
        speakChunk(currentChunkIndex)
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
        activeSpeakingLocale = null   // force locale re-application after pause
        speakChunk(currentChunkIndex)
    }

    fun stop() {
        tts?.stop()
        isPlaying         = false
        isPaused          = false
        currentChunkIndex = 0
    }

    fun seekToChunk(index: Int) {
        val wasPlaying = isPlaying
        tts?.stop()
        currentChunkIndex = index.coerceIn(0, (chunks.size - 1).coerceAtLeast(0))
        if (wasPlaying) {
            isPlaying = true
            isPaused  = false
            activeSpeakingLocale = null   // force locale re-application after seek
            speakChunk(currentChunkIndex)
        }
    }

    // ─── Settings ────────────────────────────────────────────────────────────

    fun setSpeed(rate: Float) {
        speechRate = rate.coerceIn(MIN_SPEED, MAX_SPEED)
        tts?.setSpeechRate(speechRate)
    }

    fun setLanguage(locale: Locale) {
        currentLocale = locale
        if (isInitialized) {
            activeSpeakingLocale = applyLocale(locale)
        }
    }

    fun setAutoLanguage(enabled: Boolean) {
        autoLanguage = enabled
    }

    /**
     * Returns all installed (non-network) voices for [locale], sorted by quality descending.
     * Used to populate the voice-picker dialog in the UI.
     */
    fun getVoicesForLocale(locale: Locale): List<android.speech.tts.Voice> =
        tts?.voices
            ?.filter { v ->
                v.locale.language == locale.language &&
                !v.isNetworkConnectionRequired &&
                !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            ?.sortedByDescending { it.quality }
            ?: emptyList()

    /** The voice currently active in the engine, or null if not yet initialized. */
    fun getCurrentVoice(): android.speech.tts.Voice? = tts?.voice

    /** Applies [voice] immediately and updates the best-voice cache for its language. */
    fun setVoice(voice: android.speech.tts.Voice) {
        tts?.voice = voice
        bestVoiceCache[voice.locale.language] = voice
        activeSpeakingLocale = null   // force locale/voice re-apply on next speak
    }

    /**
     * Applies [locale] to the TTS engine and selects the best available voice.
     * @return the locale that was actually applied (may fall back to English if
     *         the requested locale is not installed).
     */
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
     * Selects the highest-quality installed voice for [locale].
     * Results are cached per language code to avoid redundant queries.
     * Requires API 21+ (minSdk 24 ✓).
     */
    private fun selectBestVoice(locale: Locale) {
        val engine = tts ?: return
        val key    = locale.language

        val voice = bestVoiceCache.getOrPut(key) {
            engine.voices
                ?.filter { v ->
                    v.locale.language == key &&
                    !v.isNetworkConnectionRequired &&
                    !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                }
                ?.maxByOrNull { it.quality }
        }
        voice?.let { engine.voice = it }
    }

    // ─── Internal helpers ────────────────────────────────────────────────────

    private fun speakChunk(index: Int) {
        if (index < 0 || index >= chunks.size || !isInitialized) return
        val chunk = chunks[index]
        if (chunk.locale != activeSpeakingLocale) {
            activeSpeakingLocale = applyLocale(chunk.locale)
        }
        tts?.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, Bundle(), "chunk_$index")
    }

    private fun parseIdx(id: String?) = id?.removePrefix("chunk_")?.toIntOrNull()

    // ─── Chunking ────────────────────────────────────────────────────────────

    /**
     * Builds the ordered list of chunks to speak.
     *
     * **Auto-language mode** ([autoLanguage] = true):
     *   [LanguageDetector] splits the text into locale-labelled segments first;
     *   each segment is then sentence-split independently, enabling the TTS
     *   engine to switch voice at natural word-group boundaries.
     *
     * **Single-language mode** ([autoLanguage] = false):
     *   The full text is sentence-split using [currentLocale] only.
     */
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
        tts?.stop()
        tts?.shutdown()
        tts                  = null
        isInitialized        = false
        isPlaying            = false
        isPaused             = false
        bestVoiceCache.clear()
    }
}
