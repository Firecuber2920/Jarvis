package com.jarvis.huddash.panel

import android.graphics.drawable.Drawable

/**
 * Real source is [com.jarvis.huddash.nav.LiveNotificationsSource] (reads a rolling
 * feed maintained by the app's NotificationListenerService — same access already
 * granted for Nav, no separate permission). This file only defines the swappable
 * interface and a mock, same pattern as Nav/Calendar/Media.
 */
data class NotificationEntry(
    val key: String,
    val appLabel: String,
    val appIcon: Drawable?,
    /** Sender/notification title — for chat apps, the latest message's sender name. */
    val title: String,
    /** Message body — the richest available text (expanded/big text, or latest chat message), never just a truncated preview. */
    val text: String,
    val postedAtMillis: Long,
    /** Conversation/group name, distinct from [title] — e.g. a group chat's name when [title] is the specific sender. Null when there's nothing beyond title+text. */
    val subText: String? = null,
)

fun interface NotificationsSource {
    /** Most recent first. */
    fun recentNotifications(): List<NotificationEntry>
}

class MockNotificationsSource(
    private val entries: List<NotificationEntry> = listOf(
        NotificationEntry("mock-1", "Slack", null, "Team channel", "Demo run-through at 3pm", 0L),
        NotificationEntry("mock-2", "Messages", null, "Mom", "Good luck today!", 0L),
        NotificationEntry("mock-3", "Calendar", null, "Showcase", "Setup starts in 1 hour", 0L),
    ),
) : NotificationsSource {
    override fun recentNotifications(): List<NotificationEntry> = entries
}

/**
 * Compact card shows only the single most recent notification (existing gesture
 * behavior). Pinning ("clicking") the panel reveals the full expanded list via
 * [PanelContent.expandedItems] — the "full investigation" view.
 */
class NotificationsPanelProvider(
    private val notificationsSource: NotificationsSource,
) : PanelProvider {

    override fun getContent(): PanelContent {
        val entries = notificationsSource.recentNotifications()
        val latest = entries.firstOrNull()

        val expandedItems = entries.take(MAX_EXPANDED_ITEMS).map { entry ->
            PanelContent(
                title = entry.subText?.let { "${entry.appLabel} · $it" } ?: entry.appLabel,
                primaryText = entry.title.ifBlank { "(no title)" },
                secondaryText = entry.text.ifBlank { null },
                glyph = "N",
                iconDrawable = entry.appIcon,
            )
        }

        return if (latest == null) {
            PanelContent(title = "Notifications", primaryText = "No notifications", glyph = "N")
        } else {
            // Compact card: sender/title up top (bold), the actual message body below it
            // (dim) — previously the body was dropped entirely whenever a title was also
            // present, showing only the app name and title with no indication of content.
            PanelContent(
                title = "Notifications",
                primaryText = latest.title.ifBlank { latest.appLabel },
                secondaryText = latest.text.ifBlank { null },
                glyph = "N",
                iconDrawable = latest.appIcon,
                expandedItems = expandedItems,
            )
        }
    }

    companion object {
        private const val MAX_EXPANDED_ITEMS = 5
    }
}
