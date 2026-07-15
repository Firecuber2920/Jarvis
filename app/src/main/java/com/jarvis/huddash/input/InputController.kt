package com.jarvis.huddash.input

import com.jarvis.huddash.panel.PanelId
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

enum class FlickDirection { LEFT, RIGHT, UP, DOWN }

/**
 * Pure state machine driving the peek/pin/switch/dismiss mechanic, plus flick-gesture
 * detection for controlling a pinned panel (e.g. media transport controls). Takes a
 * normalized displacement vector (x, y in [-1, 1], relative to the trackpad's own
 * center) and a timestamp, and produces per-panel reveal weights, pin state, and
 * pending flick events.
 *
 * Pin lifecycle: nudge into a panel's wedge past [pinThresholdFraction] and hold for
 * [pinHoldMillis] to pin it. While pinned, nudging into a *different* panel's wedge
 * past the same threshold/hold switches the pin directly — no separate dismiss step.
 * Returning to the center dead zone dismisses the pin immediately (no dwell), unless
 * it completes a flick (a fast out-and-back), which the pin survives.
 *
 * Deliberately has no Android/MotionEvent dependency so it's unit-testable with
 * plain JUnit — the MainActivity input adapter is responsible for converting
 * whatever the trackpad's actual HID reports turn out to be (absolute position vs.
 * accumulated relative deltas — confirmed on real hardware in Derisk session 1)
 * into this normalized offset.
 */
class InputController(
    private val deadZoneFraction: Float = 0.15f,
    private val pinThresholdFraction: Float = 0.80f,
    private val pinHoldMillis: Long = 400L,
    private val staleTimeoutMillis: Long = 1500L,
    private val flickThreshold: Float = 0.6f,
    private val flickMaxDurationMillis: Long = 350L,
    private val panels: List<PanelId> = PanelId.entries,
    /** Half-wedge width in degrees; defaults so panels' wedges exactly tile the circle regardless of panel count. */
    private val panelHalfWidthDegrees: Float = 180f / panels.size,
) {
    sealed class RingState {
        data class Revealing(val reveals: Map<PanelId, Float>) : RingState()
        data class Pinned(val panelId: PanelId) : RingState()
    }

    private var state: RingState = RingState.Revealing(panels.associateWith { 0f })
    private var lastInputTimestamp: Long = 0L

    private var pinCandidate: PanelId? = null
    private var pinCandidateStartMillis: Long = 0L

    private var flickTrackingActive: Boolean = false
    private var flickStartMillis: Long = 0L
    private var flickPeakMagnitude: Float = 0f
    private var flickPeakX: Float = 0f
    private var flickPeakY: Float = 0f
    private var pendingFlick: FlickDirection? = null

    fun currentState(): RingState = state

    /** Reveal weight per panel — 1.0 for a pinned panel, 0.0 for every other panel while pinned. */
    fun currentReveals(): Map<PanelId, Float> = when (val s = state) {
        is RingState.Revealing -> s.reveals
        is RingState.Pinned -> panels.associateWith { if (it == s.panelId) 1f else 0f }
    }

    fun pinnedPanel(): PanelId? = (state as? RingState.Pinned)?.panelId

    /** Returns and clears any pending flick — only produced while a panel is pinned. Poll once per tick. */
    fun pollFlick(): FlickDirection? {
        val flick = pendingFlick
        pendingFlick = null
        return flick
    }

    /**
     * A real click (button press+release) — instant action, not the hover dwell/flick
     * heuristics used elsewhere in this class (those exist for continuous hover
     * position; a click is a discrete, intentional event and doesn't need the same
     * anti-accidental-trigger threshold). If unpinned and hovering within a panel's
     * wedge (any magnitude past the dead zone counts — no 80% threshold required for
     * a click), pins it instantly. If already pinned and the click isn't within that
     * same panel's wedge, dismisses instantly. Per-row menu-item clicks are handled by
     * the caller *before* reaching this (via the renderer's hit-test) — this only
     * covers ring-level pin/dismiss.
     *
     * @return the panel pinned after this click, or null if nothing ended up pinned
     */
    fun onClick(offsetX: Float, offsetY: Float, timestampMillis: Long): PanelId? {
        lastInputTimestamp = timestampMillis
        val magnitude = min(1f, hypot(offsetX, offsetY))
        val angleDegrees = clockAngleDegrees(offsetX, offsetY)
        val reveals = panels.associateWith { panel ->
            angularWeight(angleDegrees, panel.clockAngleDegrees) * magnitudeWeight(magnitude)
        }
        val hovered = reveals.maxByOrNull { it.value }?.takeIf { it.value > 0f }?.key

        val currentPin = pinnedPanel()
        if (currentPin != null) {
            if (hovered != currentPin) decayToNeutral()
            return pinnedPanel()
        }

        if (hovered != null) {
            state = RingState.Pinned(hovered)
            pinCandidate = null
            resetFlickTracking()
        }
        return pinnedPanel()
    }

    /** Force-dismiss regardless of current position — e.g. a click landing outside a pinned panel's expanded menu. */
    fun dismiss() {
        decayToNeutral()
    }

    /**
     * @param offsetX normalized displacement, [-1, 1], positive = right
     * @param offsetY normalized displacement, [-1, 1], positive = down (screen coords)
     */
    fun onPositionChanged(offsetX: Float, offsetY: Float, timestampMillis: Long) {
        lastInputTimestamp = timestampMillis
        val magnitude = min(1f, hypot(offsetX, offsetY))
        val angleDegrees = clockAngleDegrees(offsetX, offsetY)
        val reveals = panels.associateWith { panel ->
            angularWeight(angleDegrees, panel.clockAngleDegrees) * magnitudeWeight(magnitude)
        }

        if (pinnedPanel() != null) {
            handlePinnedInput(offsetX, offsetY, magnitude, reveals, timestampMillis)
            return
        }

        state = RingState.Revealing(reveals)
        evaluatePinCandidate(reveals, timestampMillis)
    }

    /**
     * Call periodically (e.g. every 250ms) so staleness is detected even without new
     * input — e.g. the trackpad going silent (disconnect, or the hand simply lifting
     * off) de-emphasizes whatever's showing, complementing the instant dead-space
     * dismiss above (that one requires an actual center touch; this one requires none).
     */
    fun onWatchdogTick(nowMillis: Long) {
        if (lastInputTimestamp == 0L) return
        val isIdle = (state as? RingState.Revealing)?.reveals?.values?.all { it == 0f } == true
        if (!isIdle && nowMillis - lastInputTimestamp > staleTimeoutMillis) {
            decayToNeutral()
        }
    }

    private fun handlePinnedInput(
        offsetX: Float,
        offsetY: Float,
        magnitude: Float,
        reveals: Map<PanelId, Float>,
        timestampMillis: Long,
    ) {
        if (magnitude <= deadZoneFraction) {
            // A quick out-and-back within the flick window completes a flick — the pin
            // survives. Anything else touching center (a deliberate release, "clicking
            // off into dead space") dismisses immediately, no dwell required.
            val isValidFlick = flickTrackingActive &&
                timestampMillis - flickStartMillis <= flickMaxDurationMillis &&
                flickPeakMagnitude >= flickThreshold

            if (isValidFlick) {
                pendingFlick = directionFromVector(flickPeakX, flickPeakY)
                resetFlickTracking()
                pinCandidate = null
                return
            }

            resetFlickTracking()
            decayToNeutral()
            return
        }

        if (!flickTrackingActive) {
            flickTrackingActive = true
            flickStartMillis = timestampMillis
            flickPeakMagnitude = 0f
        }
        if (magnitude > flickPeakMagnitude) {
            flickPeakMagnitude = magnitude
            flickPeakX = offsetX
            flickPeakY = offsetY
        }
        // Sustained displacement past the flick window without returning to center
        // isn't a flick — drop the tracking (it's not a dead-zone touch either, so no
        // dismiss happens here; only switching-panel evaluation applies below).
        if (timestampMillis - flickStartMillis > flickMaxDurationMillis) {
            resetFlickTracking()
        }

        // Nudging into a different panel's wedge switches the pin directly — same
        // hold-duration gate as the original pin, so switching isn't accidentally
        // easier (or harder) to trigger than pinning was in the first place.
        evaluatePinCandidate(reveals, timestampMillis)
    }

    private fun resetFlickTracking() {
        flickTrackingActive = false
        flickPeakMagnitude = 0f
    }

    private fun directionFromVector(x: Float, y: Float): FlickDirection = when {
        abs(x) >= abs(y) && x >= 0f -> FlickDirection.RIGHT
        abs(x) >= abs(y) -> FlickDirection.LEFT
        y < 0f -> FlickDirection.UP
        else -> FlickDirection.DOWN
    }

    private fun evaluatePinCandidate(reveals: Map<PanelId, Float>, timestampMillis: Long) {
        val topPanel = reveals.maxByOrNull { it.value }?.takeIf { it.value >= pinThresholdFraction }?.key

        if (topPanel == null || topPanel == pinnedPanel()) {
            pinCandidate = null
            return
        }

        if (pinCandidate != topPanel) {
            pinCandidate = topPanel
            pinCandidateStartMillis = timestampMillis
            return
        }

        if (timestampMillis - pinCandidateStartMillis >= pinHoldMillis) {
            state = RingState.Pinned(topPanel)
            pinCandidate = null
        }
    }

    private fun decayToNeutral() {
        state = RingState.Revealing(panels.associateWith { 0f })
        pinCandidate = null
        resetFlickTracking()
    }

    private fun magnitudeWeight(magnitude: Float): Float =
        ((magnitude - deadZoneFraction) / (1f - deadZoneFraction)).coerceIn(0f, 1f)

    private fun angularWeight(inputAngle: Float, panelAngle: Float): Float {
        val distance = circularDistanceDegrees(inputAngle, panelAngle)
        return (1f - distance / panelHalfWidthDegrees).coerceIn(0f, 1f)
    }

    private fun circularDistanceDegrees(a: Float, b: Float): Float {
        val diff = abs(a - b) % 360f
        return if (diff > 180f) 360f - diff else diff
    }

    /** 0° = up (12 o'clock), increasing clockwise — matches [PanelId.clockAngleDegrees]. */
    private fun clockAngleDegrees(offsetX: Float, offsetY: Float): Float {
        val degrees = Math.toDegrees(atan2(offsetX.toDouble(), -offsetY.toDouble())).toFloat()
        return (degrees + 360f) % 360f
    }
}
