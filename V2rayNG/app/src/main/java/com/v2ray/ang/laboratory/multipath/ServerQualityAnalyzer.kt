package com.v2ray.ang.laboratory.multipath

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Analyse pure des mesures d'un chemin.
 *
 * Toutes les valeurs sont calculées à partir d'échantillons réels. L'indice de
 * débit est une **estimation** dérivée de la qualité observée : il n'est jamais
 * présenté comme un débit mesuré en octets par seconde.
 */
object ServerQualityAnalyzer {

    /** Latence à laquelle le score de latence tombe à zéro (ms). */
    private const val LATENCY_REFERENCE_MILLIS = 150.0

    /** Au-delà de cette latence, le chemin est considéré comme inexploitable. */
    private const val LATENCY_HARD_LIMIT_MILLIS = 2_500L

    private const val UNUSABLE_LOSS_PERCENT = 60

    fun analyze(
        samples: List<PathSample>,
        windowSize: Int = MultipathSettings.SAMPLE_WINDOW,
    ): PathMetrics {
        val window = samples.takeLast(windowSize.coerceAtLeast(1))
        if (window.isEmpty()) return PathMetrics.empty()

        val successes = window.filter { it.success }
        val availability = (successes.size * 100) / window.size

        if (successes.isEmpty()) {
            return PathMetrics(
                latencyMillis = -1L,
                lossPercent = 100,
                availabilityPercent = 0,
                stabilityPercent = 0,
                qualityScore = 0,
                throughputIndex = 0,
                sampleCount = window.size,
            )
        }

        val latencies = successes.map { it.latencyMillis.coerceAtLeast(1L) }
        val average = latencies.average()
        val latencyScore = (100.0 * min(1.0, LATENCY_REFERENCE_MILLIS / average))
            .coerceIn(0.0, 100.0)

        val variance = latencies.sumOf { (it - average) * (it - average) } / latencies.size
        val deviation = sqrt(variance)
        val jitterRatio = if (average <= 0.0) 0.0 else deviation / average
        val stability = (100.0 - jitterRatio * 200.0).coerceIn(0.0, 100.0)

        val availabilityScore = availability.toDouble()
        var score = latencyScore * 0.45 + availabilityScore * 0.35 + stability * 0.20
        if (average > LATENCY_HARD_LIMIT_MILLIS) {
            score *= 0.5
        }
        score = score.coerceIn(0.0, 100.0)

        val throughputIndex = (score * availabilityScore / 100.0).coerceIn(0.0, 100.0)

        return PathMetrics(
            latencyMillis = average.roundToInt().toLong(),
            lossPercent = (100 - availability).coerceIn(0, 100),
            availabilityPercent = availability.coerceIn(0, 100),
            stabilityPercent = stability.roundToInt().coerceIn(0, 100),
            qualityScore = score.roundToInt().coerceIn(0, 100),
            throughputIndex = throughputIndex.roundToInt().coerceIn(0, 100),
            sampleCount = window.size,
        )
    }

    /**
     * Traduit des mesures en état affichable.
     *
     * - aucune mesure : [PathLinkState.IDLE]
     * - pertes ou qualité insuffisantes : [PathLinkState.UNAVAILABLE]
     * - retenu dans le sélecteur et meilleur score : [PathLinkState.CARRYING]
     * - retenu dans le sélecteur : [PathLinkState.READY]
     * - sain mais hors sélecteur : [PathLinkState.MONITORED]
     */
    fun resolveState(
        metrics: PathMetrics,
        selected: Boolean,
        preferred: Boolean,
    ): PathLinkState = when {
        !metrics.hasData -> PathLinkState.IDLE
        metrics.lossPercent >= UNUSABLE_LOSS_PERCENT || !metrics.isUsable -> PathLinkState.UNAVAILABLE
        selected && preferred -> PathLinkState.CARRYING
        selected -> PathLinkState.READY
        else -> PathLinkState.MONITORED
    }
}
