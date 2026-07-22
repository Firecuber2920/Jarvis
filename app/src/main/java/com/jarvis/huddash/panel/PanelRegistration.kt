package com.jarvis.huddash.panel

/**
 * How a panel participates in ambient (gesture-independent) visibility.
 * ONGOING panels stay visible for the duration of a session (e.g. an active
 * Maps route); TRANSIENT panels flash briefly on a new event (e.g. a new
 * notification) and yield to any ONGOING panel so a persistent state is never
 * interrupted by a passing alert. NONE never appears ambiently — gesture reveal only.
 */
enum class AmbientTier { NONE, ONGOING, TRANSIENT }

/**
 * One entry in the panel registry: a [PanelId] plus how to fetch its current
 * content and ambient state. Adding a panel to the ring means adding one
 * [PanelRegistration] to the list built in MainActivity — refreshPanelContents()
 * and the ambient-visibility rule below no longer need per-panel branches.
 */
data class PanelRegistration(
    val id: PanelId,
    val content: () -> PanelContent,
    val ambientTier: () -> AmbientTier = { AmbientTier.NONE },
)

/**
 * Resolves which panels should be ambiently visible right now, applying the
 * "ongoing beats transient" priority rule: if any panel reports [AmbientTier.ONGOING],
 * every [AmbientTier.TRANSIENT] panel is suppressed for that tick — losing sight of
 * an active route is worse than a delayed notification glance. Future panels slot
 * into these same two tiers rather than getting bespoke conflict rules; a genuinely
 * new conflict shape (e.g. two ONGOING panels at once) should be resolved when it's
 * actually built, not speculatively here.
 */
fun resolveAmbientPanels(registrations: List<PanelRegistration>): Set<PanelId> {
    val tiers = registrations.associate { it.id to it.ambientTier() }
    val hasOngoing = tiers.values.any { it == AmbientTier.ONGOING }
    return tiers.filterValues { tier ->
        when (tier) {
            AmbientTier.ONGOING -> true
            AmbientTier.TRANSIENT -> !hasOngoing
            AmbientTier.NONE -> false
        }
    }.keys
}
