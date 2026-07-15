package com.jarvis.huddash.panel

/**
 * Pure action panel — no live data to fetch, just a hint for the flick controls that
 * become active once this panel is pinned. Actual launching happens in
 * [com.jarvis.huddash.appwindow.AppWindowLauncher], wired from MainActivity's flick
 * routing (same pattern as Media's transport controls).
 */
class AppWindowPanelProvider : PanelProvider {
    override fun getContent(): PanelContent = PanelContent(
        title = "App Window",
        primaryText = "Pin, then flick",
        secondaryText = "→ YouTube   ← Instagram",
        glyph = "▭",
    )
}
