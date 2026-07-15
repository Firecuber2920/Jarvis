package com.jarvis.huddash.appwindow

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.widget.Toast

private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
private const val INSTAGRAM_PACKAGE = "com.instagram.android"

/**
 * Launches a real installed app in a bounded window docked to the right side of the
 * screen — a landscape "phone" box for YouTube video, a portrait box for Instagram
 * Reels. Uses ActivityOptions.setLaunchBounds, the standard Android multi-window
 * placement hint API.
 *
 * REAL RISK, UNCONFIRMED ON HARDWARE: setLaunchBounds is a *hint* — it only takes
 * effect when the system is in a freeform-capable window mode, and whether DeX
 * actually honors the requested bounds (rather than opening the app fullscreen,
 * ignoring the hint, or refusing to run alongside our own fullscreen-immersive
 * activity) is unknown until tested live. If it doesn't dock cleanly, the fallback
 * is dropping FLAG_ACTIVITY_LAUNCH_ADJACENT and/or the bounds hint and accepting
 * whatever window behavior DeX defaults to.
 */
class AppWindowLauncher(private val activity: Activity) {

    fun launchYouTubeHorizontal() = launch(YOUTUBE_PACKAGE, landscapeBounds())

    fun launchInstagramVertical() = launch(INSTAGRAM_PACKAGE, portraitBounds())

    private fun launch(packageName: String, bounds: Rect) {
        val intent = activity.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Toast.makeText(activity, "$packageName isn't installed", Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        val options = ActivityOptions.makeBasic().apply { setLaunchBounds(bounds) }
        activity.startActivity(intent, options.toBundle())
    }

    /** A 16:9 box docked to the right edge, vertically centered. */
    private fun landscapeBounds(): Rect {
        val metrics = activity.resources.displayMetrics
        val boxWidth = (metrics.widthPixels * 0.42f).toInt()
        val boxHeight = (boxWidth * 9f / 16f).toInt()
        return dockedToRight(metrics.widthPixels, metrics.heightPixels, boxWidth, boxHeight)
    }

    /** A 9:16 box docked to the right edge, vertically centered — true portrait, not a rotated landscape box. */
    private fun portraitBounds(): Rect {
        val metrics = activity.resources.displayMetrics
        val boxHeight = (metrics.heightPixels * 0.8f).toInt()
        val boxWidth = (boxHeight * 9f / 16f).toInt()
        return dockedToRight(metrics.widthPixels, metrics.heightPixels, boxWidth, boxHeight)
    }

    private fun dockedToRight(screenWidth: Int, screenHeight: Int, boxWidth: Int, boxHeight: Int): Rect {
        val right = (screenWidth * 0.96f).toInt()
        val left = (right - boxWidth).coerceAtLeast(0)
        val top = ((screenHeight - boxHeight) / 2f).toInt().coerceAtLeast(0)
        return Rect(left, top, left + boxWidth, top + boxHeight)
    }
}
