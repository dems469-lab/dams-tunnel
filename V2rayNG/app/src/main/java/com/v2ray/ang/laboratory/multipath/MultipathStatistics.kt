package com.v2ray.ang.laboratory.multipath

/**
 * Statistiques de session du laboratoire, calculées sur les mesures réelles.
 *
 * Les valeurs sont des moyennes pondérées par les échantillons : un chemin
 * indisponible ne « tire » pas artificiellement la latence vers le haut, il
 * pèse sur la perte et la disponibilité.
 */
class MultipathStatistics {

    data class Snapshot(
        val rounds: Int = 0,
        val averageLatencyMillis: Long = -1L,
        val averageLossPercent: Int = 0,
        val averageQuality: Int = 0,
        val relays: Int = 0,
        val samples: Int = 0,
        val startedAtMillis: Long = 0L,
    )

    private var rounds = 0
    private var relays = 0
    private var samples = 0
    private var latencySum = 0L
    private var latencyCount = 0
    private var lossSum = 0L
    private var qualitySum = 0L
    private var startedAt = 0L

    fun start(nowMillis: Long = System.currentTimeMillis()) {
        reset()
        startedAt = nowMillis
    }

    fun recordRound(health: List<PathHealth>, nowMillis: Long = System.currentTimeMillis()) {
        if (health.isEmpty()) return
        if (startedAt == 0L) startedAt = nowMillis
        rounds++
        health.forEach { path ->
            val metrics = path.metrics
            if (!metrics.hasData) return@forEach
            samples++
            if (metrics.latencyMillis > 0L) {
                latencySum += metrics.latencyMillis
                latencyCount++
            }
            lossSum += metrics.lossPercent
            qualitySum += metrics.qualityScore
        }
    }

    fun recordRelay() {
        relays++
    }

    fun snapshot(): Snapshot {
        val sampleCount = samples.coerceAtLeast(1)
        return Snapshot(
            rounds = rounds,
            averageLatencyMillis = if (latencyCount > 0) latencySum / latencyCount else -1L,
            averageLossPercent = (lossSum / sampleCount).toInt().coerceIn(0, 100),
            averageQuality = (qualitySum / sampleCount).toInt().coerceIn(0, 100),
            relays = relays,
            samples = samples,
            startedAtMillis = startedAt,
        )
    }

    fun reset() {
        rounds = 0
        relays = 0
        samples = 0
        latencySum = 0L
        latencyCount = 0
        lossSum = 0L
        qualitySum = 0L
        startedAt = 0L
    }
}
