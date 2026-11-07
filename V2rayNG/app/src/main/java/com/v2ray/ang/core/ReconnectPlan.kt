package com.v2ray.ang.core

/** Étapes ordonnées d'une reconnexion. */
internal enum class ReconnectStep {
    STOP_PREVIOUS_TUNNEL,
    RESOLVE_DNS,
    START_CORE,
}

internal data class ReconnectPlan(val steps: List<ReconnectStep>)

/**
 * Ordonne une reconnexion : couper le tunnel précédent, résoudre les domaines, redémarrer.
 *
 * Un démarrage déjà en cours annule la reconnexion (pas de second démarrage concurrent).
 * Le tunnel n'est arrêté que s'il tournait réellement.
 */
internal fun planReconnect(startInProgress: Boolean, tunnelRunning: Boolean): ReconnectPlan? {
    if (startInProgress) return null
    val steps = buildList {
        if (tunnelRunning) add(ReconnectStep.STOP_PREVIOUS_TUNNEL)
        add(ReconnectStep.RESOLVE_DNS)
        add(ReconnectStep.START_CORE)
    }
    return ReconnectPlan(steps)
}
