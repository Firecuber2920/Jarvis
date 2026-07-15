package com.jarvis.huddash.panel

import java.util.concurrent.TimeUnit

/**
 * Real source is [com.jarvis.huddash.calendar.LiveCalendarSource] (queries
 * CalendarContract.Instances directly, populated by the Google Calendar app for a
 * synced Google account). This file only defines the swappable interface and a mock
 * for when calendar permission isn't granted — same pattern as [NavSource].
 */
data class CalendarEvent(
    val title: String,
    val startAtMillis: Long,
)

fun interface CalendarSource {
    /** Null when there's no upcoming event in the lookahead window. */
    fun nextEvent(): CalendarEvent?
}

class MockCalendarSource(
    private val eventTitle: String = "Capstone Showcase",
    minutesFromNow: Long = 45,
    nowProvider: () -> Long = System::currentTimeMillis,
) : CalendarSource {
    private val eventAtMillis: Long = nowProvider() + TimeUnit.MINUTES.toMillis(minutesFromNow)

    override fun nextEvent(): CalendarEvent = CalendarEvent(eventTitle, eventAtMillis)
}

class TimePanelProvider(
    private val calendarSource: CalendarSource,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) : PanelProvider {

    override fun getContent(): PanelContent {
        val event = calendarSource.nextEvent()
        val remainingMillis = event?.let { (it.startAtMillis - nowProvider()).coerceAtLeast(0) }

        if (event == null || remainingMillis == null || remainingMillis > LOOKAHEAD_WINDOW_MILLIS) {
            return PanelContent(
                title = "Next",
                primaryText = "Nothing in the next 24h",
                glyph = "T",
            )
        }

        return PanelContent(
            title = "Next",
            primaryText = event.title,
            secondaryText = "in ${formatRemaining(remainingMillis)}",
            glyph = "T",
        )
    }

    private fun formatRemaining(remainingMillis: Long): String {
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(remainingMillis)
        if (totalMinutes <= 0) return "now"
        if (totalMinutes < 60) return "${totalMinutes}m"

        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}m"
    }

    companion object {
        private val LOOKAHEAD_WINDOW_MILLIS = TimeUnit.HOURS.toMillis(24)
    }
}
