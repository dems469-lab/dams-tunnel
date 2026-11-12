package com.v2ray.ang.laboratory.multipath

/**
 * Décide du sous-ensemble de chemins utilisé pour les **nouvelles** connexions.
 *
 * Le gestionnaire est volontairement conservateur :
 * - hystérésis par nombre d'échecs consécutifs (`consecutiveFailures`), déjà
 *   calculée par le moniteur ;
 * - temps de refroidissement entre deux changements pour éviter les bascules
 *   en boucle lors d'un réseau instable ;
 * - repli sur le chemin principal lorsqu'aucune mesure exploitable n'existe.
 *
 * Il ne prétend jamais migrer une session TCP existante : Xray applique le
 * sélecteur aux connexions suivantes.
 */
class InstantFailoverManager(
    private val cooldownMillis: Long = MultipathSettings.DEFAULT_COOLDOWN * 1_000L,
) {

    private var lastDecision: FailoverDecision? = null
    private var lastChangeAtMillis: Long = 0L

    fun reset() {
        lastDecision = null
        lastChangeAtMillis = 0L
    }

    fun currentDecision(): FailoverDecision? = lastDecision

    fun decide(
        health: List<PathHealth>,
        hybridEnabled: Boolean,
        nowMillis: Long,
        cooldownMillisOverride: Long = cooldownMillis,
    ): FailoverDecision {
        val byRole = health.associateBy { it.role }
        val primary = byRole[PathRole.PRIMARY]
        val primaryUsable = primary != null && primary.metrics.isUsable && primary.consecutiveFailures < 2

        val usable = health
            .filter { it.metrics.isUsable && it.consecutiveFailures < 2 }
            .sortedWith(
                compareByDescending<PathHealth> { it.metrics.qualityScore }
                    .thenBy { if (it.metrics.latencyMillis <= 0L) Long.MAX_VALUE else it.metrics.latencyMillis }
            )

        val best = usable.firstOrNull()
        val desiredSelector: List<String>
        val preferred: PathRole

        when {
            usable.isEmpty() -> {
                preferred = PathRole.PRIMARY
                desiredSelector = listOf(PathRole.PRIMARY.tag)
            }

            hybridEnabled -> {
                preferred = best?.role ?: PathRole.PRIMARY
                desiredSelector = usable.map { it.role.tag }
            }

            primaryUsable -> {
                preferred = PathRole.PRIMARY
                desiredSelector = listOf(PathRole.PRIMARY.tag)
            }

            else -> {
                // Principal dégradé : on prépare deux chemins de secours afin
                // qu'une seconde panne ne coupe pas immédiatement le trafic.
                val fallback = usable.take(2)
                preferred = fallback.first().role
                desiredSelector = fallback.map { it.role.tag }
            }
        }

        val previous = lastDecision
        val desiredChanged = previous == null || previous.selector != desiredSelector

        if (!desiredChanged) {
            val unchanged = FailoverDecision(
                selector = previous!!.selector,
                preferred = preferred,
                reason = null,
                changed = false,
            )
            lastDecision = unchanged
            return unchanged
        }

        val reason = when {
            previous == null -> "initial"
            preferred == PathRole.PRIMARY && previous.preferred != PathRole.PRIMARY -> "primary-recovered"
            preferred != PathRole.PRIMARY -> "primary-degraded"
            else -> "rebalanced"
        }

        val previousPreferredUsable = previous != null &&
            usable.any { it.role == previous.preferred }
        val withinCooldown = previous != null &&
            cooldownMillisOverride > 0L &&
            nowMillis - lastChangeAtMillis < cooldownMillisOverride

        if (withinCooldown && previousPreferredUsable) {
            // Changement réel mais trop rapproché : on conserve le routage en
            // cours tant qu'il reste au moins un chemin exploitable.
            val held = FailoverDecision(
                selector = previous!!.selector,
                preferred = previous.preferred,
                reason = null,
                changed = false,
            )
            lastDecision = held
            return held
        }

        val applied = FailoverDecision(
            selector = desiredSelector,
            preferred = preferred,
            reason = reason,
            changed = true,
        )
        lastDecision = applied
        lastChangeAtMillis = nowMillis
        return applied
    }
}
