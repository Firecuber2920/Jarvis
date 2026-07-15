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
            ?: return PanelContent(
                title = "Next",
                primaryText = "No upcoming events",
                glyph = "T",
            )

        val remainingMinutes = TimeUnit.MILLISECONDS.toMinutes(
            (event.startAtMillis - nowProvider()).coerceAtLeast(0)
        )
        return PanelContent(
            title = "Next",
            primaryText = event.title,
            secondaryText = if (remainingMinutes > 0) "in ${remainingMinutes}m" else "now",
            glyph = "T",
        )
    }
}
