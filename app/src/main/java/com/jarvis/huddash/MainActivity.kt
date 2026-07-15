package com.jarvis.huddash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import com.jarvis.huddash.calendar.CalendarAccess
import com.jarvis.huddash.calendar.LiveCalendarSource
import com.jarvis.huddash.input.FlickDirection
import com.jarvis.huddash.input.InputController
import com.jarvis.huddash.media.LiveMediaSource
import com.jarvis.huddash.nav.LiveMapsNavSource
import com.jarvis.huddash.nav.LiveNotificationsSource
import com.jarvis.huddash.nav.MapsNavState
import com.jarvis.huddash.nav.NotificationAccess
import com.jarvis.huddash.panel.MediaPanelProvider
import com.jarvis.huddash.panel.MediaSource
import com.jarvis.huddash.panel.MockCalendarSource
import com.jarvis.huddash.panel.MockMediaSource
import com.jarvis.huddash.panel.MockNavSource
import com.jarvis.huddash.panel.MockNotificationsSource
import com.jarvis.huddash.panel.NavPanelProvider
import com.jarvis.huddash.panel.NotificationsPanelProvider
import com.jarvis.huddash.panel.PanelId
import com.jarvis.huddash.panel.PanelProvider
import com.jarvis.huddash.panel.TimePanelProvider
import com.jarvis.huddash.render.SpatialRingView

/**
 * Fullscreen-Activity DeX bootstrap (primary path per the design doc). If true
 * fullscreen proves unreliable on real DeX hardware in Derisk session 1, the
 * documented fallback chain is: Presentation API targeting the external display,
 * then direct USB-C DisplayPort passthrough, then a bordered/cropped layout —
 * none of which are implemented here yet, by design, until Day 1 confirms which
 * is actually needed.
 */
class MainActivity : Activity() {

    private lateinit var ringView: SpatialRingView
    private lateinit var inputController: InputController
    private val watchdogHandler = Handler(Looper.getMainLooper())

    private val mockNotificationsProvider: PanelProvider = NotificationsPanelProvider(MockNotificationsSource())
    private val liveNotificationsProvider: PanelProvider by lazy { NotificationsPanelProvider(LiveNotificationsSource()) }
    private val mockNavProvider: PanelProvider = NavPanelProvider(MockNavSource())
    private val liveNavProvider: PanelProvider = NavPanelProvider(LiveMapsNavSource())
    private val mockTimeProvider: PanelProvider = TimePanelProvider(MockCalendarSource())
    private val liveTimeProvider: PanelProvider by lazy { TimePanelProvider(LiveCalendarSource(this)) }
    private val mockMediaSource: MediaSource = MockMediaSource()
    private val liveMediaSource: MediaSource by lazy { LiveMediaSource(this) }
    private var activeMediaSource: MediaSource = mockMediaSource
    private var notificationAccessPrompted = false
    private var calendarAccessPrompted = false

    private val watchdogTick = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.uptimeMillis()
            inputController.onWatchdogTick(now)
            refreshPanelContents()
            pushStateToView()
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        inputController = InputController()
        ringView = SpatialRingView(this)
        setContentView(ringView)

        applyImmersiveFullscreen()
        refreshPanelContents()
        pushStateToView()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveFullscreen()
        watchdogHandler.post(watchdogTick)
        promptForNotificationAccessIfNeeded()
        promptForCalendarAccessIfNeeded()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // No explicit handling needed either way — refreshPanelContents() re-checks
        // CalendarAccess.isGranted() on every watchdog tick and swaps sources itself.
    }

    /**
     * Notification access can't be granted via a runtime permission dialog — it's a
     * one-time trip to system Settings. Prompted at most once per app run; the Nav
     * panel falls back to [MockNavSource] until it's granted, and picks up
     * [LiveMapsNavSource] automatically on the next refresh once it is (no restart
     * needed — see [refreshPanelContents]).
     */
    private fun promptForNotificationAccessIfNeeded() {
        if (notificationAccessPrompted || NotificationAccess.isGranted(this)) return
        notificationAccessPrompted = true
        Toast.makeText(
            this,
            "Grant notification access so the Nav panel can relay Google Maps directions",
            Toast.LENGTH_LONG,
        ).show()
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /** READ_CALENDAR is a normal runtime permission — a standard system dialog, not a Settings trip. */
    private fun promptForCalendarAccessIfNeeded() {
        if (calendarAccessPrompted || CalendarAccess.isGranted(this)) return
        calendarAccessPrompted = true
        requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), CALENDAR_PERMISSION_REQUEST_CODE)
    }

    override fun onPause() {
        watchdogHandler.removeCallbacks(watchdogTick)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    /**
     * Mouse/trackpad-class HID devices move the system pointer and report
     * ACTION_HOVER_MOVE (no button held) via generic motion events, with
     * absolute on-screen coordinates — Android already resolves the pointer
     * position, so no raw HID report parsing is needed here. Exact behavior of
     * this specific trackpad's HID reports is confirmed on real hardware in
     * Derisk session 1; this adapter is the seam where that gets adjusted if
     * the trackpad instead reports accumulated relative deltas.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (isPointerSource(event) && event.action == MotionEvent.ACTION_HOVER_MOVE) {
            handlePointerPosition(event)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isPointerSource(event) &&
            (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN)
        ) {
            handlePointerPosition(event)
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun isPointerSource(event: MotionEvent): Boolean =
        event.source and android.view.InputDevice.SOURCE_CLASS_POINTER != 0

    private fun handlePointerPosition(event: MotionEvent) {
        val halfDim = (minOf(ringView.width, ringView.height) / 2f).coerceAtLeast(1f)
        val centerX = ringView.width / 2f
        val centerY = ringView.height / 2f

        val offsetX = ((event.x - centerX) / halfDim).coerceIn(-1f, 1f)
        val offsetY = ((event.y - centerY) / halfDim).coerceIn(-1f, 1f)

        inputController.onPositionChanged(offsetX, offsetY, event.eventTime)
        pushStateToView()
        routeFlickToMediaControls()
    }

    /**
     * Flick mapping while the Media panel is pinned — a first pass since the
     * trackpad's physical-button capability isn't confirmed yet (design doc open
     * question). Right/left skip next/previous, up/down toggle play/pause. Retune
     * once tested on real hardware; if the trackpad does have a button, prefer that
     * for pin/unpin-adjacent controls over this flick heuristic.
     */
    private fun routeFlickToMediaControls() {
        val flick = inputController.pollFlick() ?: return
        if (inputController.pinnedPanel() != PanelId.MEDIA) return
        when (flick) {
            FlickDirection.RIGHT -> activeMediaSource.skipNext()
            FlickDirection.LEFT -> activeMediaSource.skipPrevious()
            FlickDirection.UP, FlickDirection.DOWN -> activeMediaSource.playPause()
        }
        refreshPanelContents() // snappier feedback than waiting for the next watchdog tick
    }

    private fun refreshPanelContents() {
        val navIsLive = NotificationAccess.isGranted(this)
        val navProvider = if (navIsLive) liveNavProvider else mockNavProvider
        val timeProvider = if (CalendarAccess.isGranted(this)) liveTimeProvider else mockTimeProvider
        // Media and Notifications both reuse the same notification-listener signal
        // Nav depends on — no separate permission gate for either.
        activeMediaSource = if (navIsLive) liveMediaSource else mockMediaSource
        val notificationsProvider = if (navIsLive) liveNotificationsProvider else mockNotificationsProvider

        ringView.panelContents = mapOf(
            PanelId.TIME to timeProvider.getContent(),
            PanelId.NOTIFICATIONS to notificationsProvider.getContent(),
            PanelId.NAV to navProvider.getContent(),
            PanelId.MEDIA to MediaPanelProvider(activeMediaSource).getContent(),
        )

        // Nav stays visible for the whole active Maps session, not just while the
        // trackpad is pointed at it — only when we have a real signal to trust (live
        // source + Maps actually reporting an active route), never for the mock.
        ringView.ambientPanels = if (navIsLive && MapsNavState.isActive) {
            setOf(PanelId.NAV)
        } else {
            emptySet()
        }
    }

    private fun pushStateToView() {
        ringView.reveals = inputController.currentReveals()
        ringView.pinnedPanel = inputController.pinnedPanel()
    }

    @SuppressLint("InlinedApi")
    private fun applyImmersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    companion object {
        private const val WATCHDOG_INTERVAL_MILLIS = 250L
        private const val CALENDAR_PERMISSION_REQUEST_CODE = 1001
    }
}
