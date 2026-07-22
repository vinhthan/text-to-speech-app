package com.texttospeech.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File

/**
 * Wraps sherpa-onnx [OfflineTts] with [AudioTrack] playback.
 *
 * Usage (all calls from a background coroutine):
 *   val engine = PiperTtsEngine()
 *   engine.init(modelDir)
 *   val audio = engine.synthesize("Hello", speed = 1.0f) ?: return
 *   val done  = engine.playBlocking(audio)     // blocks until done or stopped
 *   engine.release()
 *
 * Thread-safety:
 *   [stop] may be called from any thread to interrupt an in-progress [playBlocking].
 *   [synthesize] / [playBlocking] must be called from a single background thread.
 */
class PiperTtsEngine {

    private var offlineTts: OfflineTts? = null

    /** Volatile flag — set from any thread to interrupt [playBlocking]. */
    @Volatile private var shouldStop = false

    /** Current AudioTrack, kept so [stop] can halt it immediately. */
    @Volatile private var currentTrack: AudioTrack? = null

    // ─── Initialisation ──────────────────────────────────────────────────────

    /**
     * Initializes the ONNX TTS engine from the downloaded model directory.
     * @return true on success, false if init failed (e.g. model files missing)
     */
    fun init(modelDir: File): Boolean {
        return try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model   = "${modelDir.absolutePath}/${ModelManager.MODEL_NAME}",
                        tokens  = "${modelDir.absolutePath}/tokens.txt",
                        dataDir = "${modelDir.absolutePath}/espeak-ng-data",
                    ),
                    numThreads = 2,
                    debug      = false,
                    provider   = "cpu",
                )
            )
            offlineTts = OfflineTts(config = config)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Sample rate of the loaded model (typically 22 050 Hz for Piper). */
    val sampleRate: Int get() = offlineTts?.sampleRate() ?: 22_050

    // ─── Synthesis ───────────────────────────────────────────────────────────

    /**
     * Synthesizes [text] to a [GeneratedAudio] object.
     * Blocking CPU call — must run on a background (Default/IO) dispatcher.
     * Returns null if [stop] was called or the engine is not initialized.
     */
    fun synthesize(text: String, speed: Float): GeneratedAudio? {
        if (shouldStop || offlineTts == null) return null
        return try {
            offlineTts!!.generate(text = text, sid = 0, speed = speed)
        } catch (e: Exception) {
            null
        }
    }

    // ─── Playback ────────────────────────────────────────────────────────────

    /**
     * Plays [audio] via [AudioTrack] (MODE_STATIC).
     * Blocks until playback completes, is stopped, or is paused.
     *
     * @return true  → playback finished normally (all samples played)
     *         false → playback was interrupted by [stop]
     */
    fun playBlocking(audio: GeneratedAudio): Boolean {
        shouldStop = false
        val samples   = audio.samples
        val rate      = audio.sampleRate
        val bufBytes  = (samples.size * 4).coerceAtLeast(
            AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        )

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bufBytes)
            .build()

        currentTrack = track

        // Write all samples before starting playback
        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)

        if (shouldStop) {
            track.release()
            currentTrack = null
            return false
        }

        track.play()

        // Poll until done or stopped
        val totalSamples = samples.size
        while (!shouldStop) {
            when (track.playState) {
                AudioTrack.PLAYSTATE_STOPPED -> break   // stopped externally
                AudioTrack.PLAYSTATE_PAUSED  -> break   // paused externally
                else -> {
                    if (track.playbackHeadPosition >= totalSamples) break  // finished
                }
            }
            Thread.sleep(20)
        }

        try { track.stop() } catch (_: Exception) {}
        track.release()
        currentTrack = null

        return !shouldStop
    }

    // ─── Control ─────────────────────────────────────────────────────────────

    /**
     * Stops the current [playBlocking] call immediately.
     * Thread-safe — may be called from any thread.
     */
    fun stop() {
        shouldStop = true
        try { currentTrack?.stop() } catch (_: Exception) {}
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    fun release() {
        stop()
        offlineTts?.release()
        offlineTts = null
    }
}
