package com.teledrive.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.teledrive.core.model.TeleDriveLog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground host for the singleton [PlaybackController].
 *
 * The service never creates a player or a session of its own: the controller owns exactly one
 * [MediaSession] and this service only exposes it. Two sessions bound to one ExoPlayer produce
 * duplicate notification providers and duplicate controller registrations, which Media3 rejects
 * at runtime (the process dies with an IllegalStateException from the session callback).
 */
@AndroidEntryPoint
class MediaPlaybackService : MediaSessionService() {

    @Inject lateinit var playbackController: PlaybackController

    override fun onCreate() {
        super.onCreate()
        runCatching {
            createNotificationChannel()
            setMediaNotificationProvider(DefaultMediaNotificationProvider(this))
        }.onFailure { error -> TeleDriveLog.d("playback.notification_setup_failed", "error" to error.message) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        // Nothing may connect before the controller has a player; the session is built on
        // demand so a system controller can never observe a half-initialised session.
        runCatching { playbackController.getOrCreateSession() }
            .onFailure { error -> TeleDriveLog.d("playback.session_unavailable", "error" to error.message) }
            .getOrNull()

    override fun onTaskRemoved(rootIntent: Intent?) {
        val shouldKeep = runCatching { playbackController.shouldKeepService() }.getOrDefault(false)
        if (!shouldKeep) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // The session belongs to the controller; releasing it here would leave the singleton
        // holding a released session for the rest of the process lifetime.
        TeleDriveLog.d("playback.service_destroyed")
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
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "playback_channel"
        const val NOTIFICATION_ID = 1001
    }
}
