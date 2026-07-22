package com.jarvis.huddash.input

import com.jarvis.huddash.panel.PanelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the InputController state machine, per the design doc's Test Plan
 * plus later revisions: dead-zone boundary, pin threshold boundary, instant dismiss
 * on dead-space touch, instant switch to a different panel, stale-input timeout,
 * reconnect, and flick-gesture detection for pinned-panel controls. No Android
 * instrumentation needed — pure logic.
 */
class InputControllerTest {

    // Exact unit vectors toward each panel's clock-angle center (30/90/150/210/270/330°, magnitude 1.0).
    private val towardTime = 0.5f to -0.8660254f              // 30°
    private val towardAppWindow = 1f to 0f                    // 90°
    private val towardMedia = 0.5f to 0.8660254f               // 150°
    private val towardNav = -0.5f to 0.8660254f                // 210°
    private val towardStatus = -1f to 0f                       // 270°
    private val towardNotifications = -0.5f to -0.8660254f     // 330°

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

    private fun pin(controller: InputController, vector: Pair<Float, Float>, startMillis: Long = 1000L): Long {
        val (dx, dy) = vector
        controller.onPositionChanged(dx, dy, startMillis)
        controller.onPositionChanged(dx, dy, startMillis + 400L)
        return startMillis + 400L
    }

    @Test
    fun dismiss_touchingDeadSpaceWhilePinned_unpinsImmediately_noDwellRequired() {
        val controller = InputController()
        val pinnedAt = pin(controller, towardTime)
        assertEquals(PanelId.TIME, controller.pinnedPanel())

        // A single center touch — no sustained dwell needed — dismisses right away.
        controller.onPositionChanged(0f, 0f, pinnedAt + 50L)
        assertNull(controller.pinnedPanel())
    }

    @Test
    fun switch_nudgingTowardAnotherPanelLongEnough_switchesThePinDirectly() {
        val controller = InputController()
        val pinnedAt = pin(controller, towardTime)
        assertEquals(PanelId.TIME, controller.pinnedPanel())

        // Nudge toward Media and hold for the same pin duration — switches directly,
        // no separate dismiss step needed first.
        val (mediaDx, mediaDy) = towardMedia
        controller.onPositionChanged(mediaDx, mediaDy, pinnedAt + 100L)
        controller.onPositionChanged(mediaDx, mediaDy, pinnedAt + 100L + 400L)

        assertEquals(PanelId.MEDIA, controller.pinnedPanel())
    }

    @Test
    fun switch_brieflyNudgingTowardAnotherPanel_doesNotSwitchUntilHoldCompletes() {
        val controller = InputController()
        val pinnedAt = pin(controller, towardTime)

        // Only a brief nudge toward Media — well under the 400ms hold.
        val (mediaDx, mediaDy) = towardMedia
        controller.onPositionChanged(mediaDx, mediaDy, pinnedAt + 100L)
        controller.onPositionChanged(mediaDx, mediaDy, pinnedAt + 100L + 100L)

        assertEquals(
            "should not switch before the hold duration completes",
            PanelId.TIME,
            controller.pinnedPanel(),
        )
    }

    @Test
    fun pin_towardStatusPanel_pinsAfterHoldDuration() {
        val controller = InputController()
        val (dx, dy) = towardStatus

        controller.onPositionChanged(dx, dy, 1000L)
        controller.onPositionChanged(dx, dy, 1000L + 400L)

        assertEquals(PanelId.STATUS, controller.pinnedPanel())
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

    @Test
    fun flick_quickOutAndBackWhilePinned_reportsDirectionAndSurvivesThePin() {
        val controller = InputController()
        val pinnedAt = pin(controller, towardMedia)
        assertEquals(PanelId.MEDIA, controller.pinnedPanel())

        // Flick right: out past the flick threshold, then back to center, well within 350ms.
        controller.onPositionChanged(0.8f, 0f, pinnedAt + 50L)
        controller.onPositionChanged(0f, 0f, pinnedAt + 120L)

        assertEquals(FlickDirection.RIGHT, controller.pollFlick())
        // A completed flick is not a dead-space dismiss — the pin must survive it.
        assertEquals(PanelId.MEDIA, controller.pinnedPanel())
    }

    @Test
    fun flick_pollFlick_clearsAfterReading() {
        val controller = InputController()
        val pinnedAt = pin(controller, towardMedia)

        controller.onPositionChanged(0f, -0.8f, pinnedAt + 50L) // flick up
        controller.onPositionChanged(0f, 0f, pinnedAt + 120L)

        assertEquals(FlickDirection.UP, controller.pollFlick())
        assertNull("a second poll without a new flick should return null", controller.pollFlick())
    }

    @Test
    fun flick_tooSlowToCountAsFlick_dismissesInsteadSinceItEndsAtCenter() {
        val controller = InputController()
        val pinnedAt = pin(controller, towardMedia)

        // Displacement held out past the flick window (350ms), then released to
        // center — too slow to count as a flick, so the center touch that follows
        // is treated as a dismiss (consistent with "any non-flick center touch dismisses").
        controller.onPositionChanged(0.8f, 0f, pinnedAt + 50L)
        controller.onPositionChanged(0.8f, 0f, pinnedAt + 500L)
        controller.onPositionChanged(0f, 0f, pinnedAt + 550L)

        assertNull(controller.pollFlick())
        assertNull(controller.pinnedPanel())
    }

    @Test
    fun flick_belowThreshold_producesNoFlickAndDismissesOnReturnToCenter() {
        val controller = InputController()
        val pinnedAt = pin(controller, towardMedia)

        // Out-and-back, but peak magnitude never crosses the 0.6 flick threshold —
        // not a flick, so the return to center dismisses as normal.
        controller.onPositionChanged(0.3f, 0f, pinnedAt + 50L)
        controller.onPositionChanged(0f, 0f, pinnedAt + 100L)

        assertNull(controller.pollFlick())
        assertNull(controller.pinnedPanel())
    }
}
