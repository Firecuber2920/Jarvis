package com.jarvis.huddash.debug

import android.os.SystemClock

data class TelemetryEvent(val label: String, val atElapsedRealtimeMillis: Long)

/**
 * Lightweight, in-memory telemetry for wear-test sessions — doubles as the
 * evidence-collection mechanism the design doc's real-hardware wear-test needs: a
 * session log you can read off after a run instead of relying on "it felt fine."
 * Toggled on-glass (double-click in the dead zone); nothing is persisted to disk
 * or sent anywhere. [lastInputLatencyMillis] is computed against [MotionEvent.eventTime]
 * (android.view.MotionEvent), which shares [SystemClock.uptimeMillis]'s clock base —
 * both sides of the subtraction use the same clock, so this isn't vulnerable to the
 * usual wall-clock-vs-monotonic-clock skew that makes latency numbers lie.
 */
object DebugTelemetry {
    private const val MAX_EVENTS = 40
    private val _events = ArrayDeque<TelemetryEvent>()
    val events: List<TelemetryEvent> get() = _events.toList()

    var lastInputLatencyMillis: Long = 0L
        private set
    private var lastPositionEventAtMillis: Long = -1L

    var pinCount: Int = 0
        private set
    var dismissCount: Int = 0
        private set
    var flickCount: Int = 0
        private set

    /** @param eventTimeMillis the source MotionEvent's eventTime (uptimeMillis-based). */
    fun recordPosition(eventTimeMillis: Long) {
        lastInputLatencyMillis = (SystemClock.uptimeMillis() - eventTimeMillis).coerceAtLeast(0L)
        lastPositionEventAtMillis = eventTimeMillis
    }

    fun recordPin(panelLabel: String) {
        pinCount++
        log("pin:$panelLabel")
    }

    fun recordDismiss() {
        dismissCount++
        log("dismiss")
    }

    fun recordFlick(direction: String) {
        flickCount++
        log("flick:$direction")
    }

    fun summaryText(): String =
        "pins:$pinCount dismiss:$dismissCount flicks:$flickCount latency:${lastInputLatencyMillis}ms"

    private fun log(label: String) {
        _events.addLast(TelemetryEvent(label, SystemClock.elapsedRealtime()))
        while (_events.size > MAX_EVENTS) _events.removeFirst()
    }
}
