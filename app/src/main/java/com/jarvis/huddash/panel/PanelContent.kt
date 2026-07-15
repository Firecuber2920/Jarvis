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
    /**
     * "Full investigation" detail rows shown instead of the compact card when this
     * panel is pinned — e.g. Notifications' full recent list. Empty for panels with
     * no expanded view (renderer falls back to the compact card even while pinned).
     * Items are flat rows: their own [expandedItems] is ignored (no nesting).
     */
    val expandedItems: List<PanelContent> = emptyList(),
)

/**
 * Angular position around the ring — evenly spaced (72° apart, 5 panels).
 * APP_WINDOW is pinned to exactly 90° (true right) per its "right peripheral vision"
 * requirement; the other four fall out at 72° increments from there.
 */
enum class PanelId(val clockAngleDegrees: Float) {
    TIME(18f),
    APP_WINDOW(90f),
    MEDIA(162f),
    NAV(234f),
    NOTIFICATIONS(306f),
}

fun interface PanelProvider {
    fun getContent(): PanelContent
}
