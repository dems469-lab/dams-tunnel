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
    /** La dernière mesure a échoué : le serveur n'est pas un candidat sain. */
    val lastFailed: Boolean = false,
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
 * - seuls les serveurs réellement mesurés et récents sont candidats ;
 * - un serveur actif sain n'est jamais remplacé pour un gain marginal ;
 * - un incident transitoire du serveur actif (une sonde perdue) ne déclenche
 *   pas de bascule : il faut que sa dernière mesure soit en échec ;
 * - un serveur actif dégradé est remplacé dès qu'un candidat fait mieux ;
 * - après une bascule, un délai de refroidissement empêche les aller-retours.
 */
internal object AutoFailoverPolicy {

    /** Une mesure suffit pour être candidat ; elle sera re-testée avant bascule. */
    const val MIN_SAMPLES = 1

    /**
     * Fraîcheur du serveur actif : sa sonde tourne toutes les 20 s, une mesure
     * plus vieille que 10 min signifie que la surveillance est morte.
     */
    const val MAX_SAMPLE_AGE_MILLIS = 10 * 60_000L

    /**
     * Fraîcheur d'un candidat : un test manuel ou automatique vieux de moins de
     * 24 h reste une base de classement valable. Exiger 10 min rendait la
     * bascule inopérante, car seuls les serveurs testés dans la fenêtre étaient
     * candidats (M1 de l'audit).
     */
    const val MAX_CANDIDATE_AGE_MILLIS = 24 * 60 * 60_000L

    /**
     * Gain minimal pour remplacer un serveur actif sain : une bascule coupe
     * toutes les connexions, elle doit être nettement justifiée.
     */
    const val HEALTHY_SERVER_MIN_GAIN = 25

    const val DEFAULT_COOLDOWN_MILLIS = 3 * 60_000L

    /**
     * Disponibilité minimale d'un serveur pour être retenu comme candidat, et
     * pour qu'un serveur actif soit considéré comme « encore utilisable » (donc
     * protégé par le cooldown). En dessous, c'est un sauvetage : on ne temporise
     * pas, le tunnel ne transporte quasiment plus rien.
     */
    const val MIN_USABLE_AVAILABILITY = 50

    /**
     * Nombre de passes de vérification réellement en échec avant d'autoriser
     * une bascule au démarrage : un doute (sonde ignorée, exception) ne doit
     * jamais faire changer de serveur. Chaque passe exige déjà plusieurs
     * fournisseurs en échec, donc deux passes = quatre avis indépendants.
     */
    const val REQUIRED_CONFIRMED_FAILURES = 2

    /**
     * Une bascule au démarrage n'est permise que sur une panne prouvée par
     * [REQUIRED_CONFIRMED_FAILURES] passes et avec un relais vivant : changer
     * de serveur ne répare pas un relais TUN mort.
     */
    fun shouldAttemptFailover(confirmedDownPasses: Int, relayAlive: Boolean): Boolean =
        relayAlive && confirmedDownPasses >= REQUIRED_CONFIRMED_FAILURES

    /**
     * Fenêtre pendant laquelle des octets réellement observés protègent le
     * tunnel. Couvre plusieurs échantillons de statistiques (1 s écran allumé,
     * 3 s éteint) : une courte pause dans un flux ne doit pas autoriser un
     * rechargement au milieu d'une session.
     */
    const val USER_TRAFFIC_QUIET_MILLIS = 10_000L

    /**
     * Volume minimal déplacé pendant un échantillon pour compter comme trafic
     * utilisateur. Les sondes internes (réponse 204) et les keepalives des
     * applications restent largement en dessous ; un téléchargement ou une
     * vidéo le dépassent en permanence.
     */
    const val USER_TRAFFIC_MIN_BYTES = 16L * 1024L

    /** Cet échantillon de statistiques prouve-t-il un usage réel du tunnel ? */
    fun isUserTrafficSample(movedBytes: Long): Boolean = movedBytes >= USER_TRAFFIC_MIN_BYTES

    /**
     * Du trafic réel a-t-il circulé assez récemment pour interdire une coupure ?
     *
     * `lastUserTrafficAtMillis == 0` signifie « aucune mesure » : ce n'est pas
     * une preuve de silence, mais l'absence de preuve ne doit pas bloquer une
     * récupération. Une horloge qui recule (réglage système) est traitée comme
     * une mesure récente, donc protectrice.
     */
    fun shouldProtectUserTraffic(
        lastUserTrafficAtMillis: Long,
        nowMillis: Long,
        quietMillis: Long = USER_TRAFFIC_QUIET_MILLIS,
    ): Boolean {
        if (lastUserTrafficAtMillis <= 0L) return false
        val age = nowMillis - lastUserTrafficAtMillis
        return age < 0L || age <= quietMillis
    }

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

    fun isCandidateFresh(snapshot: ServerHealthSnapshot, nowMillis: Long): Boolean =
        snapshot.samples >= MIN_SAMPLES &&
            snapshot.lastTestedAtMillis > 0L &&
            nowMillis - snapshot.lastTestedAtMillis <= MAX_CANDIDATE_AGE_MILLIS

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
                isCandidateFresh(it, nowMillis) &&
                !it.lastFailed &&
                it.availabilityPercent >= MIN_USABLE_AVAILABILITY
        }
        if (usable.isEmpty()) return FailoverDecision(null, "no_candidate")

        // À score égal, une latence inconnue ne doit jamais battre une mesure
        // réelle : `-latencyMillis` donnerait +1 pour -1 ms, soit un avantage
        // artificiel au serveur dont on ne sait rien.
        val best = usable.maxWithOrNull(
            compareBy(
                { it.score },
                { if (it.latencyMillis >= 0L) -it.latencyMillis else Long.MIN_VALUE },
            ),
        ) ?: return FailoverDecision(null, "no_candidate")

        val activeFresh = isFresh(active, nowMillis)
        // Le cooldown protège tout serveur actif encore utilisable, pas
        // seulement un serveur parfait : sinon un serveur disponible à 50-89 %
        // serait remplacé toutes les minutes, à chaque rechargement du cœur.
        // Un serveur réellement inutilisable (mesure absente/vieille ou
        // disponibilité sous le seuil) reste un sauvetage immédiat.
        val activeUsable = activeFresh && active.availabilityPercent >= MIN_USABLE_AVAILABILITY
        if (activeUsable &&
            lastSwitchAtMillis > 0L &&
            nowMillis - lastSwitchAtMillis < cooldownMillis
        ) {
            return FailoverDecision(null, "cooldown")
        }

        // Une seule sonde perdue ne rend pas le serveur « mauvais » : seule la
        // dernière mesure en échec (ou une disponibilité dégradée) le fait.
        val activeHealthy = activeFresh && active.availabilityPercent >= 90 && !active.lastFailed
        val activeScore = if (activeFresh) active.score else 0
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

        return if (gain >= HEALTHY_SERVER_MIN_GAIN) {
            FailoverDecision(best, "better_score")
        } else {
            FailoverDecision(null, "insufficient_gain")
        }
    }
}
