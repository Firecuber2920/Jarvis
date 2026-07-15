package com.jarvis.huddash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import com.jarvis.huddash.appwindow.AppWindowLauncher
import com.jarvis.huddash.calendar.CalendarAccess
import com.jarvis.huddash.calendar.LiveCalendarSource
import com.jarvis.huddash.input.FlickDirection
import com.jarvis.huddash.input.InputController
import com.jarvis.huddash.media.LiveMediaSource
import com.jarvis.huddash.nav.LiveMapsNavSource
import com.jarvis.huddash.nav.LiveNotificationsSource
import com.jarvis.huddash.nav.MapsNavState
import com.jarvis.huddash.nav.NotificationAccess
import com.jarvis.huddash.nav.NotificationsFeedState
import com.jarvis.huddash.panel.AppWindowPanelProvider
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
import com.jarvis.huddash.render.ExpandedClickResult
import com.jarvis.huddash.render.SpatialRingView
import kotlin.math.hypot

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
    private val appWindowProvider: PanelProvider by lazy { AppWindowPanelProvider(this) }
    private val appWindowLauncher: AppWindowLauncher by lazy { AppWindowLauncher(this) }
    private var notificationAccessPrompted = false
    private var calendarAccessPrompted = false

    /**
     * True once a docked app window (YouTube/Instagram) has been launched. While true,
     * we deliberately stop re-asserting our own immersive fullscreen on resume/focus —
     * doing so re-maximizes Jarvis over the docked window and visually "puts it away"
     * the moment focus returns to us, which is exactly the bug this flag exists to
     * prevent. Cleared by the explicit flick-up dismiss gesture in [routeFlick].
     */
    private var appWindowActive = false

    // Click detection: the trackpad has full button support (confirmed — a normal
    // HID device, not just position). A click is ACTION_DOWN followed by ACTION_UP
    // within a short duration and minimal movement; anything else (held longer, or
    // moved further) is a drag, not a click, and shouldn't trigger click actions.
    private var pointerDownTimestamp: Long = -1L
    private var pointerDownX: Float = 0f
    private var pointerDownY: Float = 0f

    private val watchdogTick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
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
        if (!appWindowActive) applyImmersiveFullscreen()
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
        if (hasFocus && !appWindowActive) applyImmersiveFullscreen()
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
        if (!isPointerSource(event)) return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                handlePointerPosition(event)
                pointerDownTimestamp = event.eventTime
                pointerDownX = event.x
                pointerDownY = event.y
            }
            MotionEvent.ACTION_MOVE -> handlePointerPosition(event)
            MotionEvent.ACTION_UP -> {
                handlePointerPosition(event)
                maybeHandleClick(event)
                pointerDownTimestamp = -1L
            }
            else -> return super.onTouchEvent(event)
        }
        return true
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
        routeFlick()
    }

    /**
     * Click-vs-drag: elapsed time always gates it (a genuine drag/hold), but movement
     * tolerance is more forgiving when down and up both land on the *same* expanded
     * menu row — carefully aiming at a small menu target naturally causes more cursor
     * drift between press and release than a quick tap on a large glowing ring panel
     * does, and the flat pixel threshold was rejecting those as drags.
     */
    private fun maybeHandleClick(event: MotionEvent) {
        if (pointerDownTimestamp < 0L) return
        val elapsed = event.eventTime - pointerDownTimestamp
        if (elapsed > CLICK_MAX_DURATION_MILLIS) return // held too long — a drag, not a click

        val moved = hypot((event.x - pointerDownX).toDouble(), (event.y - pointerDownY).toDouble())
        if (moved <= CLICK_MAX_MOVEMENT_PX) {
            handleClick(event.x, event.y, event.eventTime)
            return
        }

        val downHit = ringView.hitTestExpanded(pointerDownX, pointerDownY)
        val upHit = ringView.hitTestExpanded(event.x, event.y)
        if (downHit is ExpandedClickResult.ItemClicked && downHit == upHit) {
            handleClick(event.x, event.y, event.eventTime)
        }
        // Otherwise: real drag outside any menu target — not a click, no action.
    }

    /**
     * Click routing: menu-row selection first (App Window's YouTube/Instagram/Dismiss
     * rows, checked via the renderer's hit-test in actual screen-pixel space), then
     * ring-level pin/dismiss (checked via the trackpad-normalized offset InputController
     * already works in) for everything else. A click outside a shown menu — even if
     * it's still within the pinned panel's ring wedge — dismisses, matching "click off
     * into dead space" for the menu case specifically.
     */
    private fun handleClick(screenX: Float, screenY: Float, timestampMillis: Long) {
        when (val hit = ringView.hitTestExpanded(screenX, screenY)) {
            is ExpandedClickResult.ItemClicked -> handleExpandedItemClick(hit.panel, hit.index)
            is ExpandedClickResult.ClickedOutside -> inputController.dismiss()
            null -> {
                val halfDim = (minOf(ringView.width, ringView.height) / 2f).coerceAtLeast(1f)
                val centerX = ringView.width / 2f
                val centerY = ringView.height / 2f
                val offsetX = ((screenX - centerX) / halfDim).coerceIn(-1f, 1f)
                val offsetY = ((screenY - centerY) / halfDim).coerceIn(-1f, 1f)
                inputController.onClick(offsetX, offsetY, timestampMillis)
            }
        }
        pushStateToView()
        refreshPanelContents()
    }

    private fun handleExpandedItemClick(panel: PanelId, index: Int) {
        when (panel) {
            PanelId.APP_WINDOW -> when (index) {
                0 -> {
                    appWindowLauncher.launchYouTubeHorizontal()
                    appWindowActive = true
                }
                1 -> {
                    appWindowLauncher.launchInstagramVertical()
                    appWindowActive = true
                }
                2 -> restoreFullImmersiveHud()
            }
            else -> {} // Notification rows are informational only for now — no per-item action defined.
        }
    }

    /**
     * Flick mapping while a panel is pinned. Now that the trackpad's confirmed to have
     * full button support, click is the primary interaction for pin/dismiss/menu
     * selection (see [handleClick]) — flick remains Media's only control surface
     * (transport controls have no menu UI) and stays as a redundant alternate path for
     * App Window, which does have a menu now. Media: right/left skip next/previous,
     * up/down toggle play/pause. App Window: right launches YouTube (horizontal), left
     * launches Instagram (vertical), up dismisses and restores full HUD immersive mode.
     */
    private fun routeFlick() {
        val flick = inputController.pollFlick() ?: return
        when (inputController.pinnedPanel()) {
            PanelId.MEDIA -> {
                when (flick) {
                    FlickDirection.RIGHT -> activeMediaSource.skipNext()
                    FlickDirection.LEFT -> activeMediaSource.skipPrevious()
                    FlickDirection.UP, FlickDirection.DOWN -> activeMediaSource.playPause()
                }
                refreshPanelContents() // snappier feedback than waiting for the next watchdog tick
            }
            PanelId.APP_WINDOW -> when (flick) {
                FlickDirection.RIGHT -> {
                    appWindowLauncher.launchYouTubeHorizontal()
                    appWindowActive = true
                }
                FlickDirection.LEFT -> {
                    appWindowLauncher.launchInstagramVertical()
                    appWindowActive = true
                }
                FlickDirection.UP -> restoreFullImmersiveHud()
                FlickDirection.DOWN -> {}
            }
            else -> {}
        }
    }

    /** We can't reach into the other app's task to close it — this just stops Jarvis
     * ceding the full screen back to it, which is the part actually under our control. */
    private fun restoreFullImmersiveHud() {
        appWindowActive = false
        applyImmersiveFullscreen()
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
            PanelId.APP_WINDOW to appWindowProvider.getContent(),
        )

        val ambient = mutableSetOf<PanelId>()

        // Nav stays visible for the whole active Maps session, not just while the
        // trackpad is pointed at it — only when we have a real signal to trust (live
        // source + Maps actually reporting an active route), never for the mock.
        if (navIsLive && MapsNavState.isActive) ambient += PanelId.NAV

        // Notifications flashes fully visible for a short window right after a
        // genuinely new notification arrives ("light up and show it"), then reverts
        // to normal gesture-only visibility — unlike Nav's steady ambient-for-the-
        // whole-session behavior, this is a brief alert, not a persistent state.
        if (navIsLive) {
            val sinceLastNotification = SystemClock.elapsedRealtime() - NotificationsFeedState.lastNewNotificationAtMillis
            if (sinceLastNotification in 0 until NOTIFICATION_ALERT_WINDOW_MILLIS) {
                ambient += PanelId.NOTIFICATIONS
            }
        }

        ringView.ambientPanels = ambient
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
        private const val CLICK_MAX_DURATION_MILLIS = 400L
        private const val CLICK_MAX_MOVEMENT_PX = 30f
        private const val NOTIFICATION_ALERT_WINDOW_MILLIS = 8_000L
    }
}
