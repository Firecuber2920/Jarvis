package com.jarvis.huddash.media

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.jarvis.huddash.nav.MapsNavListenerService
import com.jarvis.huddash.panel.MediaSource
import com.jarvis.huddash.panel.MediaState

/**
 * Reads the system's active media session(s) via MediaSessionManager. This requires
 * an enabled NotificationListenerService component owned by this app — reuses
 * MapsNavListenerService's component for that authorization rather than declaring a
 * second listener service; no additional permission needed beyond what Nav already
 * requires.
 */
class LiveMediaSource(private val context: Context) : MediaSource {

    private val sessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent = ComponentName(context, MapsNavListenerService::class.java)

    private fun activeController(): MediaController? = try {
        val sessions = sessionManager.getActiveSessions(listenerComponent)
        sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
    } catch (e: SecurityException) {
        null
    }

    override fun currentMedia(): MediaState? {
        val controller = activeController() ?: return null
        val metadata = controller.metadata ?: return null
        val state = controller.playbackState ?: return null

        val icon = try {
            context.packageManager.getApplicationIcon(controller.packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

        return MediaState(
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown",
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            isPlaying = state.state == PlaybackState.STATE_PLAYING,
            positionMillis = state.position.coerceAtLeast(0),
            durationMillis = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
            sourceAppIcon = icon,
        )
    }

    override fun playPause() {
        val controller = activeController() ?: return
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    override fun play() {
        activeController()?.transportControls?.play()
    }

    override fun pause() {
        activeController()?.transportControls?.pause()
    }

    override fun skipNext() {
        activeController()?.transportControls?.skipToNext()
    }

    override fun skipPrevious() {
        activeController()?.transportControls?.skipToPrevious()
    }
}
