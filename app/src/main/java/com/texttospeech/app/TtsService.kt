package com.texttospeech.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat

/**
 * Foreground Service that owns the TtsManager so playback continues
 * even when the Activity is in the background or the screen is off.
 *
 * Activity binds via TtsBinder → calls setActivityListener() to receive
 * UI callbacks; when Activity unbinds the service keeps running.
 */
class TtsService : Service() {

    // ─── Binder ──────────────────────────────────────────────────────────────

    inner class TtsBinder : Binder() {
        fun getService(): TtsService = this@TtsService
    }

    private val binder = TtsBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    // ─── Constants ───────────────────────────────────────────────────────────

    companion object {
        const val ACTION_PAUSE_RESUME = "com.texttospeech.app.PAUSE_RESUME"
        const val ACTION_STOP         = "com.texttospeech.app.STOP"
        const val CHANNEL_ID          = "tts_playback"
        const val NOTIF_ID            = 1001
    }

    // ─── State ───────────────────────────────────────────────────────────────

    lateinit var ttsManager: TtsManager
        private set

    private var activityListener: TtsManager.Listener? = null

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ttsManager = TtsManager(this)
        ttsManager.addListener(serviceListener)
        ttsManager.init()
        startForeground(NOTIF_ID, buildNotification("Sẵn sàng đọc", isPlaying = false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_RESUME -> when {
                ttsManager.isPlaying -> ttsManager.pause()
                ttsManager.isPaused  -> ttsManager.resume()
            }
            ACTION_STOP -> {
                ttsManager.stop()
                updateNotification("Đã dừng", isPlaying = false)
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App was swiped away from recents — stop if idle
        if (!ttsManager.isPlaying && !ttsManager.isPaused) stopSelf()
    }

    override fun onDestroy() {
        ttsManager.release()
        super.onDestroy()
    }

    // ─── Activity ↔ Service bridge ───────────────────────────────────────────

    /**
     * Register [listener] to receive TTS events for UI updates.
     * Pass null to unregister (call in Activity.onStop).
     */
    fun setActivityListener(listener: TtsManager.Listener?) {
        activityListener?.let { ttsManager.removeListener(it) }
        activityListener = listener
        listener?.let { ttsManager.addListener(it) }
    }

    // ─── Service-side TTS listener (updates notification) ────────────────────

    private val serviceListener = object : TtsManager.Listener {
        override fun onInitialized() {
            updateNotification("Sẵn sàng đọc", isPlaying = false)
        }
        override fun onProgress(current: Int, total: Int, percentage: Int) {
            updateNotification("Đoạn $current / $total", isPlaying = true)
        }
        override fun onFinished() {
            updateNotification("Đã đọc xong ✓", isPlaying = false)
        }
        override fun onError(message: String) {
            updateNotification("Lỗi: $message", isPlaying = false)
        }
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
            .setOngoing(isPlaying)
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
