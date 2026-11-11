package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.SessionRttSample
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Synthèse de la qualité de la session, calculée uniquement à partir de mesures
 * réelles :
 *
 * - les aller-retours de la sonde du cœur (qui traversent le tunnel) ;
 * - les débits réels du moteur, échantillonnés une fois par seconde.
 *
 * Aucune valeur n'est simulée. Tant que la fenêtre ne contient pas assez de
 * mesures, les champs concernés restent `null` et l'interface affiche
 * « Indisponible ».
 *
 * @property probeCount nombre de sondes conservées dans la fenêtre (succès et échecs).
 * @property rttSampleCount nombre de mesures de latence exploitables.
 * @property lastRttMillis dernière latence réussie.
 * @property averageRttMillis latence moyenne de la fenêtre courte.
 * @property jitterMillis variation moyenne entre deux mesures consécutives.
 * @property lossPercent part de sondes sans réponse dans la fenêtre courte.
 * @property continuityPercent part de sondes réussies dans la fenêtre longue.
 */
data class SessionMetrics(
    val probeCount: Int = 0,
    val rttSampleCount: Int = 0,
    val lastRttMillis: Long? = null,
    val averageRttMillis: Long? = null,
    val jitterMillis: Long? = null,
    val lossPercent: Double? = null,
    val continuityPercent: Double? = null,
    val updatedAtMillis: Long = 0L,
) {
    val hasLatency: Boolean get() = averageRttMillis != null
}

/**
 * Accumulateur glissant des échantillons de latence.
 *
 * Les fenêtres sont bornées pour que la mémoire et le coût de calcul restent
 * constants, quelle que soit la durée de la session.
 *
 * @param rttWindow nombre de mesures exploitées pour la moyenne et le jitter.
 * @param lossWindow nombre de sondes utilisées pour la perte de paquets.
 * @param continuityWindow nombre de sondes utilisées pour la continuité.
 */
internal class SessionMetricsTracker(
    private val rttWindow: Int = 20,
    private val lossWindow: Int = 10,
    private val continuityWindow: Int = 60,
) {
    private val maxSamples = maxOf(rttWindow, lossWindow, continuityWindow)
    private val samples = ArrayDeque<SessionRttSample>()

    fun reset() {
        samples.clear()
    }

    fun add(sample: SessionRttSample): SessionMetrics {
        samples.addLast(sample)
        while (samples.size > maxSamples) samples.removeFirst()
        return snapshot()
    }

    fun snapshot(): SessionMetrics {
        if (samples.isEmpty()) return SessionMetrics()

        val all = samples.toList()
        val successfulRtt = all
            .asSequence()
            .filter { it.success }
            .map { it.delayMillis }
            .filter { it >= 0L }
            .toList()
        val recentRtt = successfulRtt.takeLast(rttWindow)
        val last = recentRtt.lastOrNull()
        val average = recentRtt.takeIf { it.isNotEmpty() }?.average()?.roundToLong()
        val jitter = if (recentRtt.size >= 2) {
            recentRtt.zipWithNext { previous, next -> abs(next - previous) }
                .average()
                .roundToLong()
        } else {
            null
        }

        val lossSlice = all.takeLast(lossWindow)
        val loss = if (lossSlice.size >= minSampleForLoss) {
            lossSlice.count { !it.success } * 100.0 / lossSlice.size
        } else {
            null
        }

        val continuitySlice = all.takeLast(continuityWindow)
        val continuity = if (continuitySlice.size >= minSampleForContinuity) {
            continuitySlice.count { it.success } * 100.0 / continuitySlice.size
        } else {
            null
        }

        return SessionMetrics(
            probeCount = all.size,
            rttSampleCount = recentRtt.size,
            lastRttMillis = last,
            averageRttMillis = average,
            jitterMillis = jitter,
            lossPercent = loss,
            continuityPercent = continuity,
            updatedAtMillis = all.last().measuredAtMillis,
        )
    }

    private companion object {
        const val minSampleForLoss = 5
        const val minSampleForContinuity = 10
    }
}

/**
 * Seuils de l'évaluation. Regroupés ici pour être ajustables et testables sans
 * toucher à l'interface.
 */
internal data class QualityThresholds(
    val excellentMaxRttMillis: Long = 60L,
    val goodMaxRttMillis: Long = 120L,
    val fairMaxRttMillis: Long = 220L,
    val weakMaxRttMillis: Long = 400L,
    val excellentMaxJitterMillis: Long = 15L,
    val goodMaxJitterMillis: Long = 40L,
    val fairMaxJitterMillis: Long = 90L,
    val minimumContinuityPercent: Double = 95.0,
    val minimumThroughputStability: Double = 0.35,
    val minimumRttSamples: Int = 3,
)

/**
 * Évalue la qualité réelle de la connexion.
 *
 * La latence est le critère principal, mais un débit élevé ne suffit jamais à
 * déclarer une connexion excellente : le jitter, la perte de paquets, la
 * stabilité des débits et la continuité de la connexion retirent des points.
 *
 * @param isConnected état réel du tunnel.
 * @param metrics mesures accumulées ; sans mesure exploitable le résultat est
 * [ConnectionQuality.Unavailable].
 * @param downlinkStability stabilité du débit descendant dans `[0..1]`, ou `null`
 * quand le trafic est trop faible pour être jugé.
 * @param uplinkStability stabilité du débit montant dans `[0..1]`.
 */
internal fun evaluateConnectionQuality(
    isConnected: Boolean,
    metrics: SessionMetrics,
    downlinkStability: Double? = null,
    uplinkStability: Double? = null,
    thresholds: QualityThresholds = QualityThresholds(),
): ConnectionQuality {
    if (!isConnected) return ConnectionQuality.Unavailable
    val average = metrics.averageRttMillis ?: return ConnectionQuality.Unavailable
    if (metrics.rttSampleCount < thresholds.minimumRttSamples) return ConnectionQuality.Unavailable

    var score = when {
        average <= thresholds.excellentMaxRttMillis -> 1.0
        average <= thresholds.goodMaxRttMillis -> 0.85
        average <= thresholds.fairMaxRttMillis -> 0.62
        average <= thresholds.weakMaxRttMillis -> 0.42
        else -> 0.20
    }

    metrics.jitterMillis?.let { jitter ->
        score -= when {
            jitter <= thresholds.excellentMaxJitterMillis -> 0.0
            jitter <= thresholds.goodMaxJitterMillis -> 0.08
            jitter <= thresholds.fairMaxJitterMillis -> 0.16
            else -> 0.28
        }
    }

    metrics.lossPercent?.let { loss ->
        if (loss > 0.0) {
            score -= (0.02 + loss / 100.0 * 1.2).coerceAtMost(0.45)
        }
    }

    metrics.continuityPercent?.let { continuity ->
        if (continuity < thresholds.minimumContinuityPercent) {
            val deficit = (thresholds.minimumContinuityPercent - continuity) / 100.0
            score -= (deficit * 1.5).coerceAtMost(0.30)
        }
    }

    val stability = listOfNotNull(downlinkStability, uplinkStability).minOrNull()
    if (stability != null && stability < thresholds.minimumThroughputStability) {
        score -= 0.25
    }

    return when {
        score >= 0.90 -> ConnectionQuality.Excellent
        score >= 0.65 -> ConnectionQuality.Good
        score >= 0.40 -> ConnectionQuality.Fair
        else -> ConnectionQuality.Weak
    }
}

/**
 * Stabilité d'une série de débits comprise dans `[0..1]` (1 = très stable).
 *
 * Seuls les échantillons non nuls sont pris en compte : un tunnel au repos ne
 * doit pas être déclaré instable. En dessous de [minimumSamples] échantillons
 * actifs, la stabilité est inconnue et renvoie `null`.
 */
internal fun throughputStability(
    rates: List<Long>,
    minimumSamples: Int = 5,
): Double? {
    val active = rates.filter { it > 0L }.map { it.toDouble() }
    if (active.size < minimumSamples) return null
    val mean = active.average()
    if (mean <= 0.0) return null
    val variance = active.sumOf { value -> (value - mean) * (value - mean) } / active.size
    val coefficient = sqrt(variance) / mean
    return (1.0 / (1.0 + 2.0 * coefficient)).coerceIn(0.0, 1.0)
}
