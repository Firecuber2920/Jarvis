package com.jarvis.huddash.nav

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.jarvis.huddash.panel.NavInstruction
import com.jarvis.huddash.panel.NavSource

private const val MAPS_PACKAGE = "com.google.android.apps.maps"

/**
 * Relay only: reads Google Maps' own ongoing turn-by-turn notification and mirrors
 * its title/text. Does not compute or infer direction independently — if Maps'
 * notification format changes or isn't present, this correctly reports "no route"
 * rather than falling back to any kind of guess.
 */
class MapsNavListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != MAPS_PACKAGE || !sbn.isOngoing) return

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

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == MAPS_PACKAGE && sbn.isOngoing) {
            MapsNavState.latest = null
        }
    }

    override fun onListenerDisconnected() {
        MapsNavState.latest = null
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

class LiveMapsNavSource : NavSource {
    override fun currentInstruction(): NavInstruction? = MapsNavState.latest
}

object NotificationAccess {
    fun isGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}
