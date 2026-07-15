package com.jarvis.huddash.panel

import android.content.Context
import android.content.pm.PackageManager

private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
private const val INSTAGRAM_PACKAGE = "com.instagram.android"

/**
 * Pin ("click") reveals a real menu — same expanded-detail-view mechanism as
 * Notifications — listing both apps with their actual icons, not just a text hint.
 * Selection still happens via flick (no confirmed physical-button hardware to build
 * true per-row click-to-select on yet — design doc open question), but the menu
 * makes which flick does what visually explicit instead of memorized.
 */
class AppWindowPanelProvider(private val context: Context) : PanelProvider {

    override fun getContent(): PanelContent {
        val compact = PanelContent(
            title = "App Window",
            primaryText = "Pin to open menu",
            glyph = "▭",
        )
        return compact.copy(
            expandedItems = listOf(
                PanelContent(
                    title = "YouTube",
                    primaryText = "Landscape video",
                    secondaryText = "flick →",
                    glyph = "▶",
                    iconDrawable = appIcon(YOUTUBE_PACKAGE),
                ),
                PanelContent(
                    title = "Instagram",
                    primaryText = "Portrait Reels",
                    secondaryText = "flick ←",
                    glyph = "◆",
                    iconDrawable = appIcon(INSTAGRAM_PACKAGE),
                ),
                PanelContent(
                    title = "Dismiss",
                    primaryText = "Return to full HUD",
                    secondaryText = "flick ↑",
                    glyph = "×",
                ),
            ),
        )
    }

    private fun appIcon(packageName: String) = try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
