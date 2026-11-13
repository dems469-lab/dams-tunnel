package com.v2ray.ang.core

/**
 * État de santé agrégé d'un serveur, sur une fenêtre glissante de mesures.
 *
 * `samples` compte les mesures, `failures` celles qui ont échoué : les pertes
 * affichées sont donc mesurées, jamais déduites d'un simple ping.
 */
internal data class ServerHealthSnapshot(
    val guid: String,
    val protocol: String,
    val latencyMillis: Long,
    val samples: Int,
    val failures: Int,
    val lastTestedAtMillis: Long,
) {
    /** Aucune mesure : la perte vaut 0 mais ne veut pas dire « 0 % de pertes ». */
    val hasMeasure: Boolean get() = samples > 0

    val availabilityPercent: Int
        get() = if (samples <= 0) 0 else (((samples - failures).coerceAtLeast(0)) * 100) / samples

    val lossPercent: Int get() = if (samples <= 0) 0 else 100 - availabilityPercent

    val score: Int get() = AutoFailoverPolicy.score(this)
}

/** Décision de bascule : `candidate == null` signifie « ne rien changer ». */
internal data class FailoverDecision(
    val candidate: ServerHealthSnapshot?,
    val reason: String,
) {
    val switches: Boolean get() = candidate != null
}

/**
 * Classement et décision de bascule du VPN classique.
 *
 * Règles :
 * - seuls les serveurs réellement mesurés et frais sont candidats ;
 * - un serveur actif sain n'est jamais remplacé pour un gain marginal ;
 * - un serveur actif dégradé est remplacé dès qu'un candidat fait mieux ;
 * - après une bascule, un délai de refroidissement empêche les aller-retours.
 */
internal object AutoFailoverPolicy {

    /** Une mesure suffit pour être candidat ; elle sera re-testée avant bascule. */
    const val MIN_SAMPLES = 1

    /** Au-delà, la mesure est trop ancienne pour décider. */
    const val MAX_SAMPLE_AGE_MILLIS = 10 * 60_000L

    /** Gain de score minimal pour justifier une coupure de connexion. */
    const val MIN_SCORE_GAIN = 18

    /** En dessous, le serveur actif est considéré comme médiocre. */
    const val DEGRADED_SCORE = 55

    const val DEFAULT_COOLDOWN_MILLIS = 3 * 60_000L

    fun score(snapshot: ServerHealthSnapshot): Int {
        if (snapshot.samples <= 0) return 0
        val availability = snapshot.availabilityPercent
        if (availability <= 0) return 0
        val latencyScore = when {
            snapshot.latencyMillis < 0L -> 0
            snapshot.latencyMillis <= 120L -> 100
            snapshot.latencyMillis <= 250L -> 80
            snapshot.latencyMillis <= 500L -> 55
            snapshot.latencyMillis <= 900L -> 30
            snapshot.latencyMillis <= 1_500L -> 10
            else -> 0
        }
        return ((availability * 65) + (latencyScore * 35)) / 100
    }

    fun isFresh(snapshot: ServerHealthSnapshot, nowMillis: Long): Boolean =
        snapshot.samples >= MIN_SAMPLES &&
            snapshot.lastTestedAtMillis > 0L &&
            nowMillis - snapshot.lastTestedAtMillis <= MAX_SAMPLE_AGE_MILLIS

    fun decide(
        active: ServerHealthSnapshot,
        candidates: List<ServerHealthSnapshot>,
        nowMillis: Long,
        lastSwitchAtMillis: Long = 0L,
        cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    ): FailoverDecision {
        val usable = candidates.filter {
            it.guid.isNotBlank() &&
                it.guid != active.guid &&
                isFresh(it, nowMillis) &&
                it.availabilityPercent >= 50
        }
        if (usable.isEmpty()) return FailoverDecision(null, "no_candidate")

        val best = usable.maxWithOrNull(compareBy({ it.score }, { -it.latencyMillis }))
            ?: return FailoverDecision(null, "no_candidate")

        val activeHealthy = isFresh(active, nowMillis) && active.availabilityPercent >= 90
        val activeScore = if (isFresh(active, nowMillis)) active.score else 0
        val gain = best.score - activeScore

        if (!activeHealthy) {
            // Le serveur actif est tombé ou perd des paquets : bascule rapide dès
            // qu'un candidat mesuré fait mieux.
            return if (gain > 0) {
                FailoverDecision(best, "active_degraded")
            } else {
                FailoverDecision(null, "no_better_candidate")
            }
        }

        if (lastSwitchAtMillis > 0L && nowMillis - lastSwitchAtMillis < cooldownMillis) {
            return FailoverDecision(null, "cooldown")
        }
        if (activeScore >= DEGRADED_SCORE && gain < MIN_SCORE_GAIN) {
            return FailoverDecision(null, "insufficient_gain")
        }
        return if (gain >= MIN_SCORE_GAIN) {
            FailoverDecision(best, "better_score")
        } else {
            FailoverDecision(null, "insufficient_gain")
        }
    }
}
