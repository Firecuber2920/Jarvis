package com.jarvis.huddash.input

import com.jarvis.huddash.panel.PanelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the InputController state machine, per the design doc's Test Plan:
 * dead-zone boundary, pin threshold boundary, unpin-only-via-long-press, stale-input
 * timeout, reconnect, and flick-gesture detection for pinned-panel controls. No
 * Android instrumentation needed — pure logic.
 */
class InputControllerTest {

    // Exact unit vectors toward each panel's clock-angle center (N/E/S/W, magnitude 1.0).
    private val towardTime = 0f to -1f            // 0°
    private val towardNotifications = 1f to 0f    // 90°
    private val towardNav = 0f to 1f              // 180°
    private val towardMedia = -1f to 0f           // 270°

    @Test
    fun deadZone_belowThreshold_producesNoReveal() {
        val controller = InputController()
        // Magnitude 0.10 < default dead zone of 0.15, pointed at TIME.
        val (dx, dy) = towardTime
        controller.onPositionChanged(dx * 0.10f, dy * 0.10f, 1000L)

        controller.currentReveals().values.forEach { assertEquals(0f, it, 1e-4f) }
    }

    @Test
    fun deadZone_justAboveThreshold_producesSmallReveal() {
        val controller = InputController()
        val (dx, dy) = towardTime
        controller.onPositionChanged(dx * 0.20f, dy * 0.20f, 1000L)

        val reveal = controller.currentReveals()[PanelId.TIME] ?: 0f
        assertTrue("expected a small positive reveal just above the dead zone, got $reveal", reveal > 0f)
    }

    @Test
    fun pin_sustainedAboveThresholdForHoldDuration_pinsThePanel() {
        val controller = InputController()
        val (dx, dy) = towardTime

        controller.onPositionChanged(dx, dy, 1000L)
        assertNull("should not pin before hold duration elapses", controller.pinnedPanel())

        controller.onPositionChanged(dx, dy, 1000L + 400L)
        assertEquals(PanelId.TIME, controller.pinnedPanel())
    }

    @Test
    fun pin_releasedBeforeHoldDurationCompletes_doesNotPin() {
        val controller = InputController()
        val (dx, dy) = towardTime

        controller.onPositionChanged(dx, dy, 1000L)
        // Moves back to center before the 400ms hold elapses.
        controller.onPositionChanged(0f, 0f, 1000L + 200L)
        controller.onPositionChanged(dx, dy, 1000L + 250L)
        controller.onPositionChanged(dx, dy, 1000L + 400L) // only 150ms of sustained hold

        assertNull(controller.pinnedPanel())
    }

    @Test
    fun unpin_requiresExplicitLongPressAtCenter_releaseAloneIsNotEnough() {
        val controller = InputController()
        val (dx, dy) = towardTime

        controller.onPositionChanged(dx, dy, 1000L)
        controller.onPositionChanged(dx, dy, 1400L)
        assertEquals(PanelId.TIME, controller.pinnedPanel())

        // Single low-magnitude sample right after pinning: not yet a sustained long-press.
        controller.onPositionChanged(0f, 0f, 1450L)
        assertEquals(
            "a brief return to center should not immediately unpin",
            PanelId.TIME,
            controller.pinnedPanel(),
        )

        // Sustained center hold for the full unpin duration.
        controller.onPositionChanged(0f, 0f, 1450L + 500L)
        assertNull(controller.pinnedPanel())
    }

    @Test
    fun unpin_browsingTowardAnotherPanelWhilePinned_doesNotDismissThePin() {
        val controller = InputController()
        val (timeDx, timeDy) = towardTime
        val (notifDx, notifDy) = towardNotifications

        controller.onPositionChanged(timeDx, timeDy, 1000L)
        controller.onPositionChanged(timeDx, timeDy, 1400L)
        assertEquals(PanelId.TIME, controller.pinnedPanel())

        // Nudging toward a different panel while pinned must NOT switch or dismiss the pin —
        // this is the exact contradiction the eng review caught in the design doc.
        controller.onPositionChanged(notifDx, notifDy, 1500L)
        assertEquals(PanelId.TIME, controller.pinnedPanel())
    }

    @Test
    fun staleInput_timeoutDecaysToNeutral() {
        val controller = InputController()
        val (dx, dy) = towardNav
        controller.onPositionChanged(dx * 0.5f, dy * 0.5f, 1000L)
        assertTrue((controller.currentReveals()[PanelId.NAV] ?: 0f) > 0f)

        // No further input for longer than the 1500ms default stale timeout.
        controller.onWatchdogTick(1000L + 1600L)

        controller.currentReveals().values.forEach { assertEquals(0f, it, 1e-4f) }
    }

    @Test
    fun staleInput_withinTimeoutWindow_staysActive() {
        val controller = InputController()
        val (dx, dy) = towardNav
        controller.onPositionChanged(dx * 0.5f, dy * 0.5f, 1000L)

        controller.onWatchdogTick(1000L + 800L) // under the 1500ms timeout

        assertTrue((controller.currentReveals()[PanelId.NAV] ?: 0f) > 0f)
    }

    @Test
    fun reconnect_resumesNormallyAfterStaleDecay_noExplicitResetNeeded() {
        val controller = InputController()
        val (dx, dy) = towardNav
        controller.onPositionChanged(dx * 0.5f, dy * 0.5f, 1000L)
        controller.onWatchdogTick(1000L + 1600L) // decays to neutral

        // New input arrives after the "disconnect" — should behave exactly like fresh input.
        controller.onPositionChanged(dx * 0.5f, dy * 0.5f, 5000L)

        assertTrue((controller.currentReveals()[PanelId.NAV] ?: 0f) > 0f)
    }

    private fun pinMedia(controller: InputController, startMillis: Long = 1000L): Long {
        val (dx, dy) = towardMedia
        controller.onPositionChanged(dx, dy, startMillis)
        controller.onPositionChanged(dx, dy, startMillis + 400L)
        assertEquals(PanelId.MEDIA, controller.pinnedPanel())
        return startMillis + 400L
    }

    @Test
    fun flick_quickOutAndBackWhilePinned_reportsDirection() {
        val controller = InputController()
        val pinnedAt = pinMedia(controller)

        // Flick right: out past the flick threshold, then back to center, well within 350ms.
        controller.onPositionChanged(0.8f, 0f, pinnedAt + 50L)
        controller.onPositionChanged(0f, 0f, pinnedAt + 120L)

        assertEquals(FlickDirection.RIGHT, controller.pollFlick())
        // Pin must survive a flick — it's a control gesture, not an unpin attempt.
        assertEquals(PanelId.MEDIA, controller.pinnedPanel())
    }

    @Test
    fun flick_pollFlick_clearsAfterReading() {
        val controller = InputController()
        val pinnedAt = pinMedia(controller)

        controller.onPositionChanged(0f, -0.8f, pinnedAt + 50L) // flick up
        controller.onPositionChanged(0f, 0f, pinnedAt + 120L)

        assertEquals(FlickDirection.UP, controller.pollFlick())
        assertNull("a second poll without a new flick should return null", controller.pollFlick())
    }

    @Test
    fun flick_tooSlowToCountAsFlick_producesNoFlickAndDoesNotUnpinEither() {
        val controller = InputController()
        val pinnedAt = pinMedia(controller)

        // Displacement held out past the flick window (350ms) without returning to
        // center — not a flick (too slow) and not at center either, so no unpin.
        controller.onPositionChanged(0.8f, 0f, pinnedAt + 50L)
        controller.onPositionChanged(0.8f, 0f, pinnedAt + 500L)

        assertNull(controller.pollFlick())
        assertEquals(PanelId.MEDIA, controller.pinnedPanel())
    }

    @Test
    fun flick_belowThreshold_producesNoFlick() {
        val controller = InputController()
        val pinnedAt = pinMedia(controller)

        // Out-and-back, but peak magnitude never crosses the 0.6 flick threshold.
        controller.onPositionChanged(0.3f, 0f, pinnedAt + 50L)
        controller.onPositionChanged(0f, 0f, pinnedAt + 100L)

        assertNull(controller.pollFlick())
    }
}
