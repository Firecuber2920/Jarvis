package com.jarvis.huddash.panel

/**
 * Relay-only nav panel: shows whatever Google Maps' own active turn-by-turn
 * notification says, nothing more. Deliberately does not compute heading,
 * routing, or direction independently — per the explicit "no compass, relay
 * Google Maps only" call. Real source is [com.jarvis.huddash.nav.LiveMapsNavSource]
 * (reads Maps' notification via NotificationListenerService); this file only
 * defines the swappable interface and a mock for when notification access isn't
 * granted or Maps isn't actively navigating.
 */
data class NavInstruction(
    val instructionText: String,
    val distanceText: String?,
)

fun interface NavSource {
    /** Null when Maps isn't actively navigating (or, for the live source, access isn't granted). */
    fun currentInstruction(): NavInstruction?
}

class MockNavSource(
    private val instruction: NavInstruction? = NavInstruction(
        instructionText = "Turn right onto Main St",
        distanceText = "0.3 mi",
    ),
) : NavSource {
    override fun currentInstruction(): NavInstruction? = instruction
}

class NavPanelProvider(
    private val navSource: NavSource,
) : PanelProvider {

    override fun getContent(): PanelContent {
        val instruction = navSource.currentInstruction()
        return if (instruction == null) {
            PanelContent(
                title = "Nav",
                primaryText = "No active route",
                secondaryText = "Start navigation in Google Maps",
                glyph = "M",
            )
        } else {
            PanelContent(
                title = "Nav",
                primaryText = instruction.instructionText,
                secondaryText = instruction.distanceText,
                glyph = "M",
            )
        }
    }
}
