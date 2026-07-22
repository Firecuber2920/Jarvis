package com.jarvis.huddash.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.jarvis.huddash.panel.CalendarEvent
import com.jarvis.huddash.panel.CalendarSource
import java.util.concurrent.TimeUnit

object CalendarAccess {
    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
}

/**
 * Queries CalendarContract.Instances directly — the system calendar provider that the
 * Google Calendar app populates for a synced Google account. No API key or network
 * call needed; this reads what's already on-device, same as any other calendar app.
 */
class LiveCalendarSource(private val context: Context) : CalendarSource {

    override fun nextEvent(): CalendarEvent? = upcomingEvents(1).firstOrNull()

    override fun upcomingEvents(limit: Int): List<CalendarEvent> {
        if (!CalendarAccess.isGranted(context)) return emptyList()

        val now = System.currentTimeMillis()
        val lookaheadEnd = now + TimeUnit.DAYS.toMillis(LOOKAHEAD_DAYS)
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
        )

        return try {
            CalendarContract.Instances.query(context.contentResolver, projection, now, lookaheadEnd)
                ?.use { cursor ->
                    // Instances.query doesn't accept a sort order — the provider returns
                    // rows roughly time-ordered per-calendar, so collect and sort by BEGIN
                    // across all calendars rather than trusting row order.
                    val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                    val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                    val events = mutableListOf<CalendarEvent>()
                    while (cursor.moveToNext()) {
                        events += CalendarEvent(
                            title = cursor.getString(titleIndex) ?: "Untitled event",
                            startAtMillis = cursor.getLong(beginIndex),
                        )
                    }
                    events.sortedBy { it.startAtMillis }.take(limit)
                } ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    companion object {
        private const val LOOKAHEAD_DAYS = 7L
    }
}
