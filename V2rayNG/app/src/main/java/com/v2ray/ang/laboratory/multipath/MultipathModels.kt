package com.v2ray.ang.laboratory.multipath

/**
 * Rôle d'un chemin du moteur Multipath.
 *
 * Le tag est celui écrit dans la configuration Xray. Le serveur principal
 * conserve le tag historique `proxy` dans la configuration de base puis est
 * renommé en [PRIMARY] au moment de l'injection : toutes les références
 * internes (règles de routage, DNS, dialerProxy) sont réécrites en même temps.
 */
enum class PathRole(val tag: String, val label: String, val shortLabel: String) {
    PRIMARY("path-a", "PATH A", "A"),
    SECONDARY_B("path-b", "PATH B", "B"),
    SECONDARY_C("path-c", "PATH C", "C");

    companion object {
        fun fromTag(tag: String?): PathRole? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * État observé d'un chemin. Il ne s'agit jamais d'un état supposé :
 * [IDLE] tant qu'aucune mesure réelle n'a été faite, [UNAVAILABLE] seulement
 * après des échecs constatés.
 */
enum class PathLinkState {
    IDLE,
    MONITORED,
    READY,
    CARRYING,
    UNAVAILABLE,
}

/** Un échantillon de mesure réel (latence en ms, ou échec). */
data class PathSample(
    val latencyMillis: Long,
    val success: Boolean,
    val atMillis: Long,
)

/** Agrégat calculé sur la fenêtre d'échantillons d'un chemin. */
data class PathMetrics(
    val latencyMillis: Long = -1L,
    val lossPercent: Int = 0,
    val availabilityPercent: Int = 0,
    val stabilityPercent: Int = 0,
    val qualityScore: Int = 0,
    /** Indice de débit estimé (0-100) : jamais présenté comme un débit mesuré. */
    val throughputIndex: Int = 0,
    val sampleCount: Int = 0,
) {
    val hasData: Boolean get() = sampleCount > 0

    val isUsable: Boolean
        get() = hasData &&
            availabilityPercent >= MIN_AVAILABILITY_PERCENT &&
            qualityScore >= MIN_USABLE_SCORE

    companion object {
        const val MIN_USABLE_SCORE = 30
        const val MIN_AVAILABILITY_PERCENT = 40

        fun empty(): PathMetrics = PathMetrics()
    }
}

/** Photographie complète d'un chemin, exposition directe à l'interface. */
data class PathHealth(
    val role: PathRole,
    val tag: String,
    val guid: String,
    val remarks: String,
    val protocol: String,
    val state: PathLinkState = PathLinkState.IDLE,
    val metrics: PathMetrics = PathMetrics(),
    val consecutiveFailures: Int = 0,
    val consecutiveSuccesses: Int = 0,
    val lastUpdateMillis: Long = 0L,
) {
    val isCarrying: Boolean get() = state == PathLinkState.CARRYING
}

/** Cible de mesure : un outbound Xray autonome, prêt à être testé. */
data class PathTarget(
    val role: PathRole,
    val guid: String,
    val remarks: String,
    val protocol: String,
    val outboundJson: String,
)

/** État du moteur expérimental, indépendant de l'état brut du TUN. */
enum class EngineState {
    IDLE,
    PREPARING,
    STOPPING_CLASSIC,
    STARTING,
    ACTIVE,
    STOPPING,
    FAILED,
}

/** Décision de sélection produite par [InstantFailoverManager]. */
data class FailoverDecision(
    val selector: List<String>,
    val preferred: PathRole,
    val reason: String? = null,
    val changed: Boolean = false,
)

/** Événement de relais réel, enregistré dans l'historique du laboratoire. */
data class RelayEvent(
    val atMillis: Long,
    val fromRole: PathRole?,
    val toRole: PathRole,
    val reason: String,
    val fromLatencyMillis: Long,
    val toLatencyMillis: Long,
)
