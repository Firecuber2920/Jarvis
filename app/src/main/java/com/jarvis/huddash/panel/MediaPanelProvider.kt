package com.jarvis.huddash.panel

import android.graphics.drawable.Drawable

/**
 * Real source is [com.jarvis.huddash.media.LiveMediaSource] (reads the system's
 * active MediaSession via MediaSessionManager — reuses the notification-listener
 * access already granted for Nav, no separate permission needed). This file only
 * defines the swappable interface and a mock, same pattern as Nav/Calendar.
 */
data class MediaState(
    val title: String,
    val artist: String?,
    val isPlaying: Boolean,
    val positionMillis: Long,
    val durationMillis: Long,
    val sourceAppIcon: Drawable?,
)

interface MediaSource {
    /** Null when nothing is actively playing/paused in any known session. */
    fun currentMedia(): MediaState?
    /** Toggle, used by the flick gesture (no menu UI to show separate Play/Pause targets). */
    fun playPause()
    /** Explicit play/pause, used by the click menu's separate Play and Pause rows. */
    fun play()
    fun pause()
    fun skipNext()
    fun skipPrevious()
}

class MockMediaSource(
    private val title: String = "Around the World",
    private val artist: String? = "Daft Punk",
    private val durationMillis: Long = 3 * 60_000L,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) : MediaSource {
    private val startedAtMillis = nowProvider()
    private var playing = true
    private var pausedAtPositionMillis = 45_000L

    override fun currentMedia(): MediaState {
        val position = if (playing) {
            ((nowProvider() - startedAtMillis) + pausedAtPositionMillis).coerceAtMost(durationMillis)
        } else {
            pausedAtPositionMillis
        }
        return MediaState(title, artist, playing, position, durationMillis, sourceAppIcon = null)
    }

    override fun playPause() {
        if (playing) pausedAtPositionMillis = currentMedia().positionMillis
        playing = !playing
    }

    override fun play() { if (!playing) playPause() }
    override fun pause() { if (playing) playPause() }

    override fun skipNext() { /* no-op for the mock — nothing to skip to */ }
    override fun skipPrevious() { /* no-op for the mock — nothing to skip to */ }
}

class MediaPanelProvider(
    private val mediaSource: MediaSource,
) : PanelProvider {

    override fun getContent(): PanelContent {
        val media = mediaSource.currentMedia()
            ?: return PanelContent(
                title = "Media",
                primaryText = "Nothing playing",
                glyph = "M",
            )

        val progress = if (media.durationMillis > 0) {
            (media.positionMillis.toFloat() / media.durationMillis).coerceIn(0f, 1f)
        } else {
            null
        }

        return PanelContent(
            title = "Media",
            primaryText = media.title,
            secondaryText = media.artist,
            glyph = if (media.isPlaying) "⏸" else "▶",
            progressFraction = progress,
            iconDrawable = media.sourceAppIcon,
            // Inline controls within the same widget (enlarged while pinned), not a
            // separate menu takeover — the live title/artist/progress stays visible
            // right above the buttons. Play/Pause collapses to one state-reflecting
            // toggle rather than two rows, since only one is ever a valid action.
            actions = listOf(
                PanelAction(glyph = "⏮", label = "Back"),
                PanelAction(glyph = if (media.isPlaying) "⏸" else "▶", label = if (media.isPlaying) "Pause" else "Play"),
                PanelAction(glyph = "⏭", label = "Skip"),
            ),
        )
    }
}
