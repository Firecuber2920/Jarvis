package com.jarvis.huddash.panel

import android.graphics.drawable.Drawable

/**
 * Shared render payload for every panel. The ring renderer has no per-panel-type
 * branching beyond this — adding a panel is a new [PanelProvider], not a renderer change.
 */
data class PanelContent(
    val title: String,
    val primaryText: String,
    val secondaryText: String? = null,
    /** Short glyph label (e.g. "T", "N") — placeholder until real iconography exists; ignored when [iconDrawable] is set. */
    val glyph: String,
    /** 0..1 progress bar under the panel when non-null (currently Media only). */
    val progressFraction: Float? = null,
    /** Real app icon (e.g. the currently-playing media app) drawn instead of [glyph] when present. */
    val iconDrawable: Drawable? = null,
)

/** Angular position around the ring — evenly spaced, N/E/S/W on a 4-panel ring. */
enum class PanelId(val clockAngleDegrees: Float) {
    TIME(0f),
    NOTIFICATIONS(90f),
    NAV(180f),
    MEDIA(270f),
}

fun interface PanelProvider {
    fun getContent(): PanelContent
}
