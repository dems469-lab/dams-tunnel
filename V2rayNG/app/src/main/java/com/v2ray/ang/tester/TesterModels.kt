package com.v2ray.ang.tester

import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil

/** Les deux entrées de l'écran Tester restent cloisonnées. */
internal enum class TesterScope { CONFIGS, FREE }

/** Nature réelle de la mesure : jamais confondue dans l'affichage. */
internal enum class TesterKind { TUNNEL, TCP }

/**
 * Dernières mesures d'un serveur.
 *
 * `delay < 0` avec `at > 0` signifie « test effectué, échec ». `at == 0`
 * signifie « jamais mesuré » : ce n'est pas un échec, c'est une absence.
 */
internal data class TesterMeasurement(
    var tunnelDelay: Long = -1L,
    var tunnelAt: Long = 0L,
    var tcpDelay: Long = -1L,
    var tcpAt: Long = 0L,
) {
    fun delayOf(kind: TesterKind): Long = when (kind) {
        TesterKind.TUNNEL -> tunnelDelay
        TesterKind.TCP -> tcpDelay
    }

    fun atOf(kind: TesterKind): Long = when (kind) {
        TesterKind.TUNNEL -> tunnelAt
        TesterKind.TCP -> tcpAt
    }

    fun hasMeasure(kind: TesterKind): Boolean = atOf(kind) > 0L
}

/** Persistance des mesures de l'écran Tester, une entrée par serveur. */
internal object TesterMeasurementStore {

    fun read(guid: String): TesterMeasurement {
        val raw = MmkvManager.decodeTesterMeasurement(guid) ?: return TesterMeasurement()
        return runCatching {
            JsonUtil.fromJsonSafe(raw, TesterMeasurement::class.java) ?: TesterMeasurement()
        }.getOrDefault(TesterMeasurement())
    }

    fun record(guid: String, kind: TesterKind, delayMillis: Long) {
        if (guid.isBlank()) return
        val measurement = read(guid)
        when (kind) {
            TesterKind.TUNNEL -> {
                measurement.tunnelDelay = delayMillis
                measurement.tunnelAt = System.currentTimeMillis()
            }

            TesterKind.TCP -> {
                measurement.tcpDelay = delayMillis
                measurement.tcpAt = System.currentTimeMillis()
            }
        }
        runCatching {
            MmkvManager.encodeTesterMeasurement(guid, JsonUtil.toJson(measurement))
        }
    }
}

/**
 * Ligne affichée : la mesure de la catégorie demandée, les pertes réellement
 * observées (fenêtre de santé) et l'état du test en cours.
 */
internal data class TesterRow(
    val guid: String,
    val name: String,
    val address: String,
    val scope: TesterScope,
    val measurement: TesterMeasurement,
    val lossPercent: Int?,
    val running: Boolean = false,
) {
    /** Rang de tri : rapidité pénalisée par les pertes mesurées. */
    fun rank(kind: TesterKind): Long {
        val delay = measurement.delayOf(kind)
        if (!measurement.hasMeasure(kind) || delay < 0L) return Long.MAX_VALUE
        val loss = lossPercent ?: 0
        return delay * (100 + loss) / 100
    }
}
