package com.jarvis.huddash.panel

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelRegistrationTest {

    private fun registration(id: PanelId, tier: AmbientTier) = PanelRegistration(
        id = id,
        content = { PanelContent(title = id.name, primaryText = "", glyph = "?") },
        ambientTier = { tier },
    )

    @Test
    fun noAmbientPanels_resolvesToEmptySet() {
        val registrations = listOf(
            registration(PanelId.TIME, AmbientTier.NONE),
            registration(PanelId.MEDIA, AmbientTier.NONE),
        )
        assertEquals(emptySet<PanelId>(), resolveAmbientPanels(registrations))
    }

    @Test
    fun ongoingPanel_isAmbientWithNoConflict() {
        val registrations = listOf(
            registration(PanelId.NAV, AmbientTier.ONGOING),
            registration(PanelId.MEDIA, AmbientTier.NONE),
        )
        assertEquals(setOf(PanelId.NAV), resolveAmbientPanels(registrations))
    }

    @Test
    fun transientPanel_isAmbientWhenNoOngoingPanelActive() {
        val registrations = listOf(
            registration(PanelId.NOTIFICATIONS, AmbientTier.TRANSIENT),
            registration(PanelId.NAV, AmbientTier.NONE),
        )
        assertEquals(setOf(PanelId.NOTIFICATIONS), resolveAmbientPanels(registrations))
    }

    @Test
    fun ongoingPanel_suppressesTransientPanel() {
        val registrations = listOf(
            registration(PanelId.NAV, AmbientTier.ONGOING),
            registration(PanelId.NOTIFICATIONS, AmbientTier.TRANSIENT),
        )
        assertEquals(setOf(PanelId.NAV), resolveAmbientPanels(registrations))
    }

    @Test
    fun multipleOngoingPanels_allStayAmbient() {
        val registrations = listOf(
            registration(PanelId.NAV, AmbientTier.ONGOING),
            registration(PanelId.MEDIA, AmbientTier.ONGOING),
            registration(PanelId.NOTIFICATIONS, AmbientTier.TRANSIENT),
        )
        assertEquals(setOf(PanelId.NAV, PanelId.MEDIA), resolveAmbientPanels(registrations))
    }
}
