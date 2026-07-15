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

    override fun nextEvent(): CalendarEvent? {
        if (!CalendarAccess.isGranted(context)) return null

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
                    // rows roughly time-ordered per-calendar, so scan for the true soonest
                    // BEGIN across all calendars rather than trusting row order.
                    var soonest: CalendarEvent? = null
                    val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                    val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                    while (cursor.moveToNext()) {
                        val begin = cursor.getLong(beginIndex)
                        if (soonest == null || begin < soonest.startAtMillis) {
                            soonest = CalendarEvent(
                                title = cursor.getString(titleIndex) ?: "Untitled event",
                                startAtMillis = begin,
                            )
                        }
                    }
                    soonest
                }
        } catch (e: SecurityException) {
            null
        }
    }

    companion object {
        private const val LOOKAHEAD_DAYS = 7L
    }
}
