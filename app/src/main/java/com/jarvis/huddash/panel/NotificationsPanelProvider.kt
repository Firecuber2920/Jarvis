package com.jarvis.huddash.panel

/**
 * Mocked Notifications panel — last 3, read-only. Live NotificationListenerService
 * wiring is explicitly deferred (design doc NOT-in-scope) until the peek renderer
 * and gesture vocabulary are solid.
 */
class NotificationsPanelProvider(
    private val latest: List<Pair<String, String>> = listOf(
        "Slack" to "Team channel: demo run-through at 3pm",
        "Messages" to "Good luck today!",
        "Calendar" to "Showcase setup starts in 1 hour",
    ),
) : PanelProvider {

    override fun getContent(): PanelContent {
        val (sender, body) = latest.firstOrNull() ?: ("—" to "No notifications")
        return PanelContent(
            title = "Notifications",
            primaryText = sender,
            secondaryText = body,
            glyph = "N",
        )
    }
}
