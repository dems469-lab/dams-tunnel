package com.v2ray.ang.laboratory.multipath

/**
 * Évite les rechargements inutiles du moteur Xray.
 *
 * Un changement de sélecteur ne déclenche un rechargement que si le
 * sous-ensemble de chemins diffère réellement de celui déjà appliqué. En mode
 * hybride, Xray gère lui-même l'exclusion des chemins morts par l'observatoire :
 * le laboratoire ne recharge donc quasiment jamais pendant un incident.
 */
class SmartTrafficScheduler {

    private var appliedSelector: List<String>? = null

    val applied: List<String>? get() = appliedSelector

    fun shouldReload(selector: List<String>): Boolean {
        if (selector.isEmpty()) return false
        return selector != appliedSelector
    }

    /** Marque le sélecteur comme appliqué ; retourne true s'il a changé. */
    fun markAppliedIfNeeded(selector: List<String>): Boolean {
        if (!shouldReload(selector)) return false
        appliedSelector = selector.toList()
        return true
    }

    fun markApplied(selector: List<String>) {
        appliedSelector = selector.toList()
    }

    fun reset() {
        appliedSelector = null
    }
}
