package com.v2ray.ang.laboratory.multipath

import android.content.Context
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.XrayJsonConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Surveillance réelle des trois chemins.
 *
 * Chaque mesure démarre une instance Xray temporaire pour **un seul** outbound
 * ([CoreNativeManager.measureOutboundDelay]) : c'est exactement le mécanisme
 * utilisé par le test de Reconfiguration, donc éprouvé sur l'appareil. Les
 * sondes sont séquentielles et espacées pour maîtriser la batterie ; un cycle
 * ne chevauche jamais le précédent ([Mutex]).
 *
 * Cette surveillance est indépendante de l'observatoire interne de Xray : elle
 * alimente l'interface et l'historique, tandis que Xray continue d'appliquer
 * sa propre bascule aux nouvelles connexions.
 */
class PathHealthMonitor(
    context: Context,
    private val probe: (String, String) -> Long = ::defaultProbe,
) {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    private val samples = mutableMapOf<PathRole, ArrayDeque<PathSample>>()
    private val failures = mutableMapOf<PathRole, Int>()
    private val successes = mutableMapOf<PathRole, Int>()

    @Volatile
    private var initialized = false

    fun reset() {
        samples.clear()
        failures.clear()
        successes.clear()
    }

    /** Exécute un cycle complet. Retourne une mesure par chemin. */
    suspend fun measure(
        targets: List<PathTarget>,
        settings: MultipathSettings,
    ): List<PathHealth> = mutex.withLock {
        ensureInitialized()
        val window = MultipathSettings.SAMPLE_WINDOW
        val results = ArrayList<PathHealth>(targets.size)
        targets.forEachIndexed { index, target ->
            val latency = runCatching { probe(target.outboundJson, settings.probeUrl) }
                .getOrDefault(-1L)
            val success = latency > 0L
            val now = System.currentTimeMillis()

            val history = samples.getOrPut(target.role) { ArrayDeque() }
            history.addLast(PathSample(latencyMillis = latency, success = success, atMillis = now))
            while (history.size > window) history.removeFirst()

            val failureCount = if (success) 0 else (failures[target.role] ?: 0) + 1
            val successCount = if (success) (successes[target.role] ?: 0) + 1 else 0
            failures[target.role] = failureCount
            successes[target.role] = successCount

            val metrics = ServerQualityAnalyzer.analyze(history.toList(), window)
            val state = when {
                !metrics.hasData -> PathLinkState.IDLE
                !metrics.isUsable -> PathLinkState.UNAVAILABLE
                else -> PathLinkState.READY
            }
            results.add(
                PathHealth(
                    role = target.role,
                    tag = target.role.tag,
                    guid = target.guid,
                    remarks = target.remarks,
                    protocol = target.protocol,
                    state = state,
                    metrics = metrics,
                    consecutiveFailures = failureCount,
                    consecutiveSuccesses = successCount,
                    lastUpdateMillis = now,
                )
            )

            if (index < targets.lastIndex) {
                delay(PROBE_GAP_MILLIS)
            }
        }
        results
    }

    private fun ensureInitialized() {
        if (initialized) return
        initialized = true
        runCatching { CoreNativeManager.initCoreEnv(appContext) }
    }

    companion object {
        private const val PROBE_GAP_MILLIS = 350L
    }
}

private fun defaultProbe(outboundJson: String, url: String): Long {
    val probeConfig = XrayJsonConfig.buildProbeConfig(outboundJson) ?: return -1L
    return CoreNativeManager.measureOutboundDelay(probeConfig, url)
}
