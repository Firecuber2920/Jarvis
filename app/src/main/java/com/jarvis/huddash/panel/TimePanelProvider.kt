package com.jarvis.huddash.panel

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

interface CalendarSource {
    /** Null when there's no upcoming event in the lookahead window. */
    fun nextEvent(): CalendarEvent?
    /** Soonest-first, capped at [limit] — used for the enlarged pinned view; the compact glance only needs [nextEvent]. */
    fun upcomingEvents(limit: Int): List<CalendarEvent>
}

class MockCalendarSource(
    eventTitle: String = "Capstone Showcase",
    minutesFromNow: Long = 45,
    nowProvider: () -> Long = System::currentTimeMillis,
) : CalendarSource {
    private val events = listOf(
        CalendarEvent(eventTitle, nowProvider() + TimeUnit.MINUTES.toMillis(minutesFromNow)),
        CalendarEvent("Advisor check-in", nowProvider() + TimeUnit.MINUTES.toMillis(minutesFromNow + 90)),
        CalendarEvent("Team sync", nowProvider() + TimeUnit.HOURS.toMillis(5)),
    )

    override fun nextEvent(): CalendarEvent = events.first()
    override fun upcomingEvents(limit: Int): List<CalendarEvent> = events.take(limit)
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

        // Additional upcoming events shown inline only while pinned (see
        // PanelContent.pinnedDetailLines) — the widget itself grows to fit rather than
        // opening a separate menu box. Compact glance still only needs the next one.
        val laterEvents = calendarSource.upcomingEvents(MAX_PINNED_EVENTS).drop(1)
        val detailLines = laterEvents.map { "${timeFormat.format(Date(it.startAtMillis))}  ${it.title}" }

        return PanelContent(
            title = "Next",
            primaryText = event.title,
            secondaryText = "in ${formatRemaining(remainingMillis)}",
            glyph = "T",
            pinnedDetailLines = detailLines,
        )
    }

    private val timeFormat = SimpleDateFormat("h:mma", Locale.getDefault())

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
        private const val MAX_PINNED_EVENTS = 3
    }
}
