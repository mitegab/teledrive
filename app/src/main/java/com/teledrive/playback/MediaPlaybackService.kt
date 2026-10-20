package com.teledrive.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MediaPlaybackService : MediaSessionService() {

    @Inject lateinit var playbackController: PlaybackController

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Ensure controller's session is used if available
        mediaSession = playbackController.getSession() ?: run {
            val player = playbackController.getPlayer()
            MediaSession.Builder(this, player).build()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return playbackController.getSession() ?: mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val shouldKeep = playbackController.shouldKeepService()
        if (!shouldKeep) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Don't release controller here; controller is singleton and owned elsewhere.
        // Just release our fallback session if it differs
        if (mediaSession != playbackController.getSession()) {
            mediaSession?.release()
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback controls"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "playback_channel"
        const val NOTIFICATION_ID = 1001
    }
}
