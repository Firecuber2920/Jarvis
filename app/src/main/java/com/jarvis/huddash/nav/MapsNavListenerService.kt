package com.jarvis.huddash.nav

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.huddash.panel.NavInstruction
import com.jarvis.huddash.panel.NavSource
import com.jarvis.huddash.panel.NotificationEntry
import com.jarvis.huddash.panel.NotificationsSource

private const val MAPS_PACKAGE = "com.google.android.apps.maps"
private const val MAX_FEED_SIZE = 8

/**
 * This is the app's one enabled NotificationListenerService, so it's dual-purpose:
 * 1. Relays Google Maps' own ongoing turn-by-turn notification (mirrors title/text,
 *    computes nothing independently).
 * 2. Tracks a rolling feed of recent notifications system-wide (for the Notifications
 *    panel), mirroring the current notification shade — entries drop out when
 *    dismissed, same as [onNotificationRemoved]. Media-playback notifications are
 *    excluded — that data already has its own dedicated Media panel; showing it again
 *    here would just be redundant noise.
 */
class MapsNavListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            activeNotifications?.forEach { updateNotificationsFeed(it, isLiveEvent = false) }
        } catch (e: SecurityException) {
            // Access can be revoked between grant and connect; feed just stays empty.
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == MAPS_PACKAGE && sbn.isOngoing) {
            updateMapsNav(sbn)
        }
        updateNotificationsFeed(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == MAPS_PACKAGE && sbn.isOngoing) {
            MapsNavState.latest = null
        }
        NotificationsFeedState.entries = NotificationsFeedState.entries.filterNot { it.key == sbn.key }
    }

    override fun onListenerDisconnected() {
        MapsNavState.latest = null
        NotificationsFeedState.entries = emptyList()
    }

    private fun updateMapsNav(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        // Maps' ongoing nav notification puts the maneuver/street in one field and
        // distance in the other; exact placement has shifted across Maps versions,
        // so this reports both without guessing which is "the" instruction vs distance.
        MapsNavState.latest = NavInstruction(
            instructionText = title?.takeIf { it.isNotBlank() } ?: text.orEmpty(),
            distanceText = text?.takeIf { it.isNotBlank() && it != title },
        )
    }

    /**
     * @param isLiveEvent false when called from [onListenerConnected]'s startup seeding —
     * catching up on notifications that already existed isn't a "new arrival" worth
     * flashing the panel for.
     */
    private fun updateNotificationsFeed(sbn: StatusBarNotification, isLiveEvent: Boolean = true) {
        if (sbn.packageName == packageName) return // never show our own notifications
        if (isMediaNotification(sbn)) return // Media panel already covers this data

        val (title, text, subText) = extractDisplayFields(sbn.notification)
        if (title.isBlank() && text.isBlank()) return

        val isNew = NotificationsFeedState.entries.none { it.key == sbn.key }

        val entry = NotificationEntry(
            key = sbn.key,
            appLabel = appLabelFor(sbn.packageName),
            appIcon = appIconFor(sbn.packageName),
            title = title,
            text = text,
            postedAtMillis = sbn.postTime,
            subText = subText,
        )

        NotificationsFeedState.entries = (
            listOf(entry) + NotificationsFeedState.entries.filterNot { it.key == sbn.key }
        ).take(MAX_FEED_SIZE)

        if (isLiveEvent && isNew) {
            NotificationsFeedState.lastNewNotificationAtMillis = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Plain android.title/android.text are only the collapsed, truncated preview — the
     * same fields the system uses before a notification is expanded. Samsung's own
     * handler pulls the richer expanded/conversation data instead, which is why it
     * reads as more informative: bigText (full expanded body) beats a truncated text
     * preview, and MessagingStyle notifications (chat apps) carry the real content —
     * who sent the latest message and what it said — in EXTRA_MESSAGES, not android.text
     * at all (that field is often just a stale "X new messages" summary).
     */
    private fun extractDisplayFields(notification: Notification): Triple<String, String, String?> {
        val extras = notification.extras
        val plainTitle = extras.getCharSequence("android.title")?.toString().orEmpty()
        val plainText = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString()
        val subText = extras.getCharSequence("android.subText")?.toString()
            ?: extras.getCharSequence("android.summaryText")?.toString()

        val latestMessage = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification)
            ?.messages
            ?.lastOrNull()

        val senderName = latestMessage?.person?.name?.toString()
        val title = senderName?.takeIf { it.isNotBlank() } ?: plainTitle
        val text = when {
            latestMessage != null -> latestMessage.text?.toString().orEmpty()
            !bigText.isNullOrBlank() -> bigText
            else -> plainText
        }
        return Triple(title, text, subText)
    }

    /** Standard Android signals for "this is a media transport notification," not a guess. */
    private fun isMediaNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true
        return notification.extras.get(Notification.EXTRA_MEDIA_SESSION) != null
    }

    private fun appLabelFor(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun appIconFor(pkg: String) = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}

/**
 * Public so MainActivity can check [isActive] and keep the Nav panel visible for the
 * whole duration of a Maps session, not just while the trackpad happens to be pointed
 * at it — [latest] itself stays internal-set so only the listener service can write it.
 */
object MapsNavState {
    @Volatile
    var latest: NavInstruction? = null
        internal set

    val isActive: Boolean get() = latest != null
}

object NotificationsFeedState {
    @Volatile
    var entries: List<NotificationEntry> = emptyList()
        internal set

    /** SystemClock.elapsedRealtime() of the last genuinely new (not just re-posted) notification. */
    @Volatile
    var lastNewNotificationAtMillis: Long = 0L
        internal set
}

class LiveMapsNavSource : NavSource {
    override fun currentInstruction(): NavInstruction? = MapsNavState.latest
}

class LiveNotificationsSource : NotificationsSource {
    override fun recentNotifications(): List<NotificationEntry> = NotificationsFeedState.entries
}

object NotificationAccess {
    fun isGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}
