package com.jarvis.huddash.panel

data class StatusSnapshot(
    val timeText: String,
    /** Null when no location permission yet, or a fetch hasn't completed — degrades gracefully, doesn't block time/battery. */
    val weatherText: String?,
    /** Null only if the battery sticky-intent read itself fails (shouldn't happen on real hardware). */
    val batteryPercent: Int?,
    val isCharging: Boolean = false,
)

fun interface StatusSource {
    fun currentStatus(): StatusSnapshot
}

class MockStatusSource(
    private val snapshot: StatusSnapshot = StatusSnapshot(
        timeText = "14:32",
        weatherText = "68°F Cloudy",
        batteryPercent = 82,
        isCharging = false,
    ),
) : StatusSource {
    override fun currentStatus(): StatusSnapshot = snapshot
}

/**
 * Real source is [com.jarvis.huddash.status.LiveStatusSource]. Unlike the other
 * live/mock pairs in this app, the live source doesn't need a permission gate at
 * the registration level — time and battery are always real with zero permission,
 * and weather degrades to null internally rather than losing the whole panel.
 */
class StatusPanelProvider(private val source: StatusSource) : PanelProvider {

    override fun getContent(): PanelContent {
        val status = source.currentStatus()
        val batteryLabel = status.batteryPercent?.let { "$it%${if (status.isCharging) " charging" else ""}" }
            ?: "battery unknown"
        val weatherLabel = status.weatherText ?: "weather unavailable"

        return PanelContent(
            title = "Status",
            primaryText = status.timeText,
            secondaryText = "$weatherLabel · $batteryLabel",
            glyph = "◔",
            expandedItems = listOf(
                PanelContent(title = "Time", primaryText = status.timeText, glyph = "T"),
                PanelContent(title = "Weather", primaryText = weatherLabel, glyph = "W"),
                PanelContent(title = "Battery", primaryText = batteryLabel, glyph = "B"),
            ),
        )
    }
}
