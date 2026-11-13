package com.v2ray.ang.core

import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil

/** Compteurs persistés d'un serveur (fenêtre glissante). */
internal data class ServerHealthRecord(
    var samples: Int = 0,
    var failures: Int = 0,
    var latencySum: Long = 0L,
    var lastLatency: Long = -1L,
    var lastTestedAt: Long = 0L,
    var protocol: String = "",
)

/** Événement de bascule, affiché dans l'historique. */
internal data class FailoverEvent(
    var atMillis: Long = 0L,
    var fromName: String = "",
    var toName: String = "",
    var reason: String = "",
    var result: String = "",
)

/**
 * Mémoire des mesures réelles par serveur.
 *
 * Chaque serveur testé réellement (test de tunnel, sonde de santé du démon,
 * bascule vérifiée) alimente une fenêtre de [WINDOW] mesures. Quand la fenêtre
 * est pleine, elle est réduite de moitié : les mesures anciennes pèsent moins
 * sans être oubliées brutalement.
 *
 * Aucun chiffre n'est inventé : un serveur sans mesure a `samples == 0` et
 * n'est jamais candidat à une bascule.
 */
internal object ServerHealthStore {

    const val WINDOW = 10
    private const val MAX_HISTORY = 20
    private const val TAG = "AutoFailover"

    fun snapshot(guid: String): ServerHealthSnapshot {
        val record = load(guid)
        val measured = record.samples.coerceAtLeast(0)
        val failures = record.failures.coerceIn(0, measured)
        val successes = (measured - failures).coerceAtLeast(0)
        return ServerHealthSnapshot(
            guid = guid,
            protocol = record.protocol,
            latencyMillis = if (successes > 0) record.latencySum / successes else record.lastLatency,
            samples = measured,
            failures = failures,
            lastTestedAtMillis = record.lastTestedAt,
        )
    }

    fun recordSuccess(guid: String, protocol: String, latencyMillis: Long) {
        if (guid.isBlank() || latencyMillis < 0L) return
        update(guid) { record ->
            record.samples += 1
            record.latencySum += latencyMillis
            record.lastLatency = latencyMillis
            record.lastTestedAt = System.currentTimeMillis()
            if (protocol.isNotBlank()) record.protocol = protocol
        }
    }

    fun recordFailure(guid: String, protocol: String) {
        if (guid.isBlank()) return
        update(guid) { record ->
            record.samples += 1
            record.failures += 1
            record.lastLatency = -1L
            record.lastTestedAt = System.currentTimeMillis()
            if (protocol.isNotBlank()) record.protocol = protocol
        }
    }

    /** Toutes les mesures connues, pour le classement. */
    fun snapshots(guids: Collection<String>): List<ServerHealthSnapshot> =
        guids.distinct().map(::snapshot).filter { it.samples > 0 }

    fun history(): List<FailoverEvent> {
        val raw = MmkvManager.decodeFailoverHistory() ?: return emptyList()
        return runCatching {
            JsonUtil.fromJsonSafe(raw, Array<FailoverEvent>::class.java)?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    fun recordEvent(fromName: String, toName: String, reason: String, result: String) {
        val event = FailoverEvent(
            atMillis = System.currentTimeMillis(),
            fromName = fromName,
            toName = toName,
            reason = reason,
            result = result,
        )
        val history = (listOf(event) + history()).take(MAX_HISTORY)
        runCatching {
            MmkvManager.encodeFailoverHistory(JsonUtil.toJson(history))
        }.onFailure { LogUtil.w(TAG, "failover history not persisted", it) }
    }

    private fun update(guid: String, block: (ServerHealthRecord) -> Unit) {
        runCatching {
            val record = load(guid)
            block(record)
            decay(record)
            MmkvManager.encodeServerHealth(guid, JsonUtil.toJson(record))
        }.onFailure { LogUtil.w(TAG, "health update failed", it) }
    }

    private fun load(guid: String): ServerHealthRecord {
        val raw = MmkvManager.decodeServerHealth(guid) ?: return ServerHealthRecord()
        return runCatching {
            JsonUtil.fromJsonSafe(raw, ServerHealthRecord::class.java) ?: ServerHealthRecord()
        }.getOrDefault(ServerHealthRecord())
    }

    /** Fenêtre glissante : au-delà de [WINDOW], on conserve la moitié. */
    private fun decay(record: ServerHealthRecord) {
        if (record.samples <= WINDOW) return
        record.samples = (record.samples / 2).coerceAtLeast(1)
        record.failures = (record.failures / 2).coerceAtMost(record.samples)
        record.latencySum = record.latencySum / 2
    }
}
