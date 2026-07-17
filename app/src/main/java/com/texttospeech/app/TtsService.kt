package com.texttospeech.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import java.io.File

/**
 * Foreground Service that owns the TtsManager so playback continues
 * when the Activity is in the background or the screen is off.
 *
 * Reliability stack:
 *  1. PARTIAL_WAKE_LOCK          — keeps CPU awake while screen is off
 *  2. AudioFocus AUDIOFOCUS_GAIN — Android will not mute/drop speak() calls
 *     ↳ auto-resumes after transient focus loss (notification sounds etc.)
 *  3. Active MediaSessionCompat  — OEM battery managers treat us like Spotify
 *  4. Text/chunk persistence     — if the process is killed (OEM aggression)
 *     and restarted via START_STICKY, playback resumes from the saved position
 */
class TtsService : Service() {

    // ─── Binder ──────────────────────────────────────────────────────────────

    inner class TtsBinder : Binder() {
        fun getService(): TtsService = this@TtsService
    }

    private val binder = TtsBinder()

    override fun onBind(intent: Intent?): IBinder {
        isBound = true
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isBound = false
        return false
    }

    // ─── Constants ───────────────────────────────────────────────────────────

    companion object {
        const val ACTION_PAUSE_RESUME = "com.texttospeech.app.PAUSE_RESUME"
        const val ACTION_STOP         = "com.texttospeech.app.STOP"
        const val CHANNEL_ID          = "tts_playback"
        const val NOTIF_ID            = 1001
        private const val PREFS_NAME  = "tts_state"
        private const val KEY_CHUNK   = "chunk"
        private const val KEY_PLAYING = "playing"
        private const val TEXT_FILE   = "tts_last_text.txt"
    }

    // ─── State ───────────────────────────────────────────────────────────────

    lateinit var ttsManager: TtsManager
        private set

    private var activityListener: TtsManager.Listener? = null
    private var isBound = false

    // Wake lock
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * True as soon as the user starts or resumes playback, false only when
     * playback fully finishes, the user stops, or an unrecoverable error occurs.
     * Used to keep the foreground notification [setOngoing] even while paused —
     * a non-ongoing notification signals to OEM battery managers that the
     * service is idle and can be killed.
     */
    private var sessionActive = false

    // Audio focus ─────────────────────────────────────────────────────────────
    private var audioFocusRequest: AudioFocusRequest? = null   // API 26+
    private var hasAudioFocus    = false
    /**
     * True when WE called ttsManager.pause() because of a transient focus loss
     * (e.g. notification sound). On AUDIOFOCUS_GAIN we must auto-resume so
     * playback is never silently stuck in the paused state.
     */
    private var pausedByFocusLoss = false

    private val onAudioFocusChange = AudioManager.OnAudioFocusChangeListener { focus ->
        when (focus) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss — pause but do NOT auto-resume later
                if (ttsManager.isPlaying) {
                    ttsManager.pause()
                    updateNotification("Tạm dừng", isPlaying = false)
                }
                hasAudioFocus = false
                pausedByFocusLoss = false   // permanent loss, not transient
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Transient loss (notification, phone call start, etc.)
                // Pause and mark so we auto-resume when focus returns
                if (ttsManager.isPlaying) {
                    ttsManager.pause()
                    updateNotification("Tạm dừng", isPlaying = false)
                    pausedByFocusLoss = true
                }
                hasAudioFocus = false
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                // Auto-resume ONLY if we paused because of a transient loss
                if (pausedByFocusLoss && ttsManager.isPaused) {
                    pausedByFocusLoss = false
                    ttsManager.resume()   // onPlaybackStarted fires → re-requests focus
                    updateSessionState(PlaybackStateCompat.STATE_PLAYING)
                }
            }
        }
    }

    // MediaSession
    private lateinit var mediaSession: MediaSessionCompat

    // Restore-on-restart flags (set when START_STICKY restarts with null intent)
    private var restoreChunk = 0
    private var needsRestore = false

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannel()

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TextToSpeech:playback")

        setupMediaSession()

        ttsManager = TtsManager(this)
        ttsManager.addListener(serviceListener)
        ttsManager.init()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification("Sẵn sàng đọc", isPlaying = false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("Sẵn sàng đọc", isPlaying = false))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Service was killed and restarted by START_STICKY — attempt to restore
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_PLAYING, false)) {
                restoreChunk = prefs.getInt(KEY_CHUNK, 0)
                needsRestore = true
                // Actual restore happens in serviceListener.onInitialized()
                // once the TTS engine is ready
            }
            return START_STICKY
        }

        when (intent.action) {
            ACTION_PAUSE_RESUME -> when {
                ttsManager.isPlaying -> {
                    ttsManager.pause()
                    pausedByFocusLoss = false   // user-initiated pause, not focus loss
                    updateSessionState(PlaybackStateCompat.STATE_PAUSED)
                    updateNotification("Tạm dừng", isPlaying = false)
                }
                ttsManager.isPaused -> {
                    ttsManager.resume()   // onPlaybackStarted → requestAudioFocus
                    updateSessionState(PlaybackStateCompat.STATE_PLAYING)
                }
            }
            ACTION_STOP -> {
                sessionActive = false
                ttsManager.stop()
                abandonAudioFocus()
                clearSavedState()
                updateSessionState(PlaybackStateCompat.STATE_STOPPED)
                updateNotification("Đã dừng", isPlaying = false)
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!ttsManager.isPlaying && !ttsManager.isPaused) stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        abandonAudioFocus()
        mediaSession.release()
        ttsManager.release()
        super.onDestroy()
    }

    // ─── Activity ↔ Service bridge ───────────────────────────────────────────

    fun setActivityListener(listener: TtsManager.Listener?) {
        activityListener?.let { ttsManager.removeListener(it) }
        activityListener = listener
        listener?.let { ttsManager.addListener(it) }
    }

    // ─── Service-side TTS listener ────────────────────────────────────────────

    private val serviceListener = object : TtsManager.Listener {

        override fun onInitialized() {
            updateNotification("Sẵn sàng đọc", isPlaying = false)
            // If we were restarted by the system, restore and resume
            if (needsRestore) {
                needsRestore = false
                val text = loadTextFile()
                if (!text.isNullOrBlank()) {
                    ttsManager.setText(text)
                    ttsManager.seekToChunk(restoreChunk)
                    ttsManager.play()
                }
            }
        }

        override fun onPlaybackStarted() {
            sessionActive = true
            requestAudioFocus()
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(3 * 60 * 60 * 1000L)   // 3-hour safety cap
            }
            // Persist text so we can restore if the process is killed
            saveTextFile(ttsManager.getRawText())
            saveChunk(ttsManager.getCurrentChunkIndex(), playing = true)
            updateSessionState(PlaybackStateCompat.STATE_PLAYING)
            updateNotification("Đang phát…", isPlaying = true)
        }

        override fun onProgress(current: Int, total: Int, percentage: Int) {
            // Keep saved chunk index up to date for crash recovery
            saveChunk(ttsManager.getCurrentChunkIndex(), playing = true)
            updateNotification("Đoạn $current / $total", isPlaying = true)
        }

        override fun onFinished() {
            sessionActive = false
            releaseWakeLock()
            abandonAudioFocus()
            clearSavedState()
            updateSessionState(PlaybackStateCompat.STATE_STOPPED)
            updateNotification("Đã đọc xong ✓", isPlaying = false)
            if (!isBound) stopSelf()
        }

        override fun onError(message: String) {
            sessionActive = false
            releaseWakeLock()
            abandonAudioFocus()
            clearSavedState()
            updateSessionState(PlaybackStateCompat.STATE_ERROR)
            updateNotification("Lỗi: $message", isPlaying = false)
        }
    }

    // ─── MediaSession ─────────────────────────────────────────────────────────

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "TTS_Playback").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (ttsManager.isPaused) ttsManager.resume()
                }
                override fun onPause() {
                    ttsManager.pause()
                    pausedByFocusLoss = false
                    updateSessionState(PlaybackStateCompat.STATE_PAUSED)
                    updateNotification("Tạm dừng", isPlaying = false)
                }
                override fun onStop() {
                    sessionActive = false
                    ttsManager.stop()
                    abandonAudioFocus()
                    clearSavedState()
                    updateSessionState(PlaybackStateCompat.STATE_STOPPED)
                    updateNotification("Đã dừng", isPlaying = false)
                }
            })
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_NONE, 0, 1f)
                    .setActions(PlaybackStateCompat.ACTION_PLAY)
                    .build()
            )
            isActive = true
        }
    }

    private fun updateSessionState(state: Int) {
        val actions = when (state) {
            PlaybackStateCompat.STATE_PLAYING ->
                PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP
            PlaybackStateCompat.STATE_PAUSED ->
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_STOP
            else ->
                PlaybackStateCompat.ACTION_PLAY
        }
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, 0, ttsManager.getSpeechRate())
                .setActions(actions)
                .build()
        )
    }

    // ─── Audio focus ─────────────────────────────────────────────────────────

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(onAudioFocusChange)
                .setAcceptsDelayedFocusGain(true)   // accept if focus is delayed (call ending etc.)
                .build()
            audioFocusRequest = req
            hasAudioFocus = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            hasAudioFocus = am.requestAudioFocus(
                onAudioFocusChange,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(onAudioFocusChange)
        }
        hasAudioFocus = false
    }

    // ─── Wake lock ────────────────────────────────────────────────────────────

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    // ─── State persistence (crash / process-kill recovery) ───────────────────

    private fun saveTextFile(text: String) {
        try { File(filesDir, TEXT_FILE).writeText(text) } catch (_: Exception) { }
    }

    private fun loadTextFile(): String? {
        return try {
            val f = File(filesDir, TEXT_FILE)
            if (f.exists()) f.readText().takeIf { it.isNotBlank() } else null
        } catch (_: Exception) { null }
    }

    private fun saveChunk(chunk: Int, playing: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CHUNK, chunk)
            .putBoolean(KEY_PLAYING, playing)
            .apply()
    }

    private fun clearSavedState() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PLAYING, false)
            .apply()
    }

    // ─── Notification ────────────────────────────────────────────────────────

    private fun buildNotification(status: String, isPlaying: Boolean): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pausePi = PendingIntent.getService(
            this, 1,
            Intent(this, TtsService::class.java).setAction(ACTION_PAUSE_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 2,
            Intent(this, TtsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("🔊 Text to Speech")
            .setContentText(status)
            .setContentIntent(openPi)
            .setOngoing(sessionActive)   // ongoing = true even when paused → keeps process priority high
            .setSilent(true)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause
                else           android.R.drawable.ic_media_play,
                if (isPlaying) "⏸ Tạm dừng" else "▶ Tiếp tục",
                pausePi
            )
            .addAction(android.R.drawable.ic_delete, "⏹ Dừng", stopPi)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String, isPlaying: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(status, isPlaying))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TTS Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Điều khiển phát Text-to-Speech"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
