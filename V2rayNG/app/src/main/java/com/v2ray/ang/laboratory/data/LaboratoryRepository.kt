package com.v2ray.ang.laboratory.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.v2ray.ang.laboratory.multipath.PathHealth
import com.v2ray.ang.laboratory.multipath.RelayEvent

/**
 * Accès aux données du laboratoire.
 *
 * Toutes les écritures sont sérialisées par un verrou : la base reçoit des lots
 * de trois mesures à chaque cycle, et l'historique est lu en parallèle par
 * l'interface. Aucune donnée de production n'est référencée ici.
 */
class LaboratoryRepository(context: Context) {

    private val helper = LaboratoryDatabase(context)
    private val lock = Any()

    fun startSession(primaryGuid: String, primaryName: String, mode: String): Long =
        synchronized(lock) {
            val values = ContentValues().apply {
                put("started_at", System.currentTimeMillis())
                put("primary_guid", primaryGuid)
                put("primary_name", primaryName)
                put("mode", mode)
            }
            helper.writableDatabase.insert(LaboratoryDatabase.TABLE_SESSION, null, values)
        }

    fun finishSession(sessionId: Long, outcome: String) {
        if (sessionId <= 0L) return
        synchronized(lock) {
            val values = ContentValues().apply {
                put("ended_at", System.currentTimeMillis())
                put("outcome", outcome)
            }
            helper.writableDatabase.update(
                LaboratoryDatabase.TABLE_SESSION,
                values,
                "id = ?",
                arrayOf(sessionId.toString()),
            )
        }
    }

    fun recordHealth(sessionId: Long?, health: List<PathHealth>) {
        if (health.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                health.forEach { path ->
                    val values = ContentValues().apply {
                        put("session_id", sessionId)
                        put("recorded_at", now)
                        put("tag", path.tag)
                        put("guid", path.guid)
                        put("remarks", path.remarks)
                        put("latency_ms", path.metrics.latencyMillis)
                        put("loss_pct", path.metrics.lossPercent)
                        put("availability_pct", path.metrics.availabilityPercent)
                        put("stability_pct", path.metrics.stabilityPercent)
                        put("quality_score", path.metrics.qualityScore)
                        put("throughput_index", path.metrics.throughputIndex)
                        put("state", path.state.name)
                    }
                    db.insert(LaboratoryDatabase.TABLE_PATH_STAT, null, values)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun recordRelay(sessionId: Long?, event: RelayEvent) {
        synchronized(lock) {
            val values = ContentValues().apply {
                put("session_id", sessionId)
                put("recorded_at", event.atMillis)
                put("from_tag", event.fromRole?.tag)
                put("to_tag", event.toRole.tag)
                put("reason", event.reason)
                put("from_latency_ms", event.fromLatencyMillis)
                put("to_latency_ms", event.toLatencyMillis)
            }
            helper.writableDatabase.insert(LaboratoryDatabase.TABLE_RELAY, null, values)
        }
    }

    fun appendLog(level: String, category: String, message: String) {
        synchronized(lock) {
            val values = ContentValues().apply {
                put("recorded_at", System.currentTimeMillis())
                put("level", level)
                put("category", category)
                put("message", message.take(MAX_LOG_LENGTH))
            }
            helper.writableDatabase.insert(LaboratoryDatabase.TABLE_LOG, null, values)
        }
    }

    fun recentRelays(limit: Int = DEFAULT_RELAY_LIMIT): List<RelayEventRow> = synchronized(lock) {
        query(
            "SELECT id, session_id, recorded_at, from_tag, to_tag, reason, " +
                "from_latency_ms, to_latency_ms FROM ${LaboratoryDatabase.TABLE_RELAY} " +
                "ORDER BY recorded_at DESC, id DESC LIMIT $limit"
        ) { cursor ->
            RelayEventRow(
                id = cursor.getLong(0),
                sessionId = cursor.nullableLong(1),
                recordedAt = cursor.getLong(2),
                fromTag = cursor.nullableString(3),
                toTag = cursor.getString(4),
                reason = cursor.getString(5),
                fromLatencyMillis = cursor.getLong(6),
                toLatencyMillis = cursor.getLong(7),
            )
        }
    }

    fun recentLogs(limit: Int = DEFAULT_LOG_LIMIT): List<LaboratoryLogRow> = synchronized(lock) {
        query(
            "SELECT id, recorded_at, level, category, message FROM " +
                "${LaboratoryDatabase.TABLE_LOG} ORDER BY recorded_at DESC, id DESC LIMIT $limit"
        ) { cursor ->
            LaboratoryLogRow(
                id = cursor.getLong(0),
                recordedAt = cursor.getLong(1),
                level = cursor.getString(2),
                category = cursor.getString(3),
                message = cursor.getString(4),
            )
        }
    }

    fun aggregates(): List<PathAggregate> = synchronized(lock) {
        query(
            "SELECT tag, MAX(remarks), COUNT(*), " +
                "COALESCE(AVG(CASE WHEN latency_ms > 0 THEN latency_ms END), -1), " +
                "COALESCE(AVG(loss_pct), 0), COALESCE(AVG(quality_score), 0) " +
                "FROM ${LaboratoryDatabase.TABLE_PATH_STAT} " +
                "GROUP BY tag ORDER BY tag"
        ) { cursor ->
            PathAggregate(
                roleTag = cursor.getString(0),
                remarks = cursor.getString(1) ?: "",
                samples = cursor.getInt(2),
                averageLatencyMillis = cursor.getLong(3),
                averageLossPercent = cursor.getInt(4),
                averageQuality = cursor.getInt(5),
            )
        }
    }

    fun counters(): LaboratoryCounters = synchronized(lock) {
        LaboratoryCounters(
            sessions = count(LaboratoryDatabase.TABLE_SESSION),
            samples = count(LaboratoryDatabase.TABLE_PATH_STAT),
            relays = count(LaboratoryDatabase.TABLE_RELAY),
        )
    }

    /** Purge les données anciennes ; les sessions restent consultables. */
    fun prune(maxAgeDays: Int = DEFAULT_RETENTION_DAYS) {
        val cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 60L * 60L * 1_000L
        synchronized(lock) {
            val db = helper.writableDatabase
            db.delete(LaboratoryDatabase.TABLE_PATH_STAT, "recorded_at < ?", arrayOf(cutoff.toString()))
            db.delete(LaboratoryDatabase.TABLE_LOG, "recorded_at < ?", arrayOf(cutoff.toString()))
        }
    }

    /** Réinitialise uniquement les données du laboratoire. */
    fun reset() {
        synchronized(lock) {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                db.delete(LaboratoryDatabase.TABLE_LOG, null, null)
                db.delete(LaboratoryDatabase.TABLE_RELAY, null, null)
                db.delete(LaboratoryDatabase.TABLE_PATH_STAT, null, null)
                db.delete(LaboratoryDatabase.TABLE_SESSION, null, null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun close() {
        synchronized(lock) { runCatching { helper.close() } }
    }

    // ── Utilitaires ──────────────────────────────────────────────────────────

    private fun count(table: String): Int = query("SELECT COUNT(*) FROM $table") { cursor ->
        cursor.getInt(0)
    }.firstOrNull() ?: 0

    private fun <T> query(sql: String, mapper: (Cursor) -> T): List<T> {
        val result = ArrayList<T>()
        val cursor = helper.readableDatabase.rawQuery(sql, null)
        try {
            while (cursor.moveToNext()) {
                result.add(mapper(cursor))
            }
        } finally {
            cursor.close()
        }
        return result
    }

    private fun Cursor.nullableLong(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun Cursor.nullableString(index: Int): String? =
        if (isNull(index)) null else getString(index)

    companion object {
        private const val DEFAULT_RELAY_LIMIT = 25
        private const val DEFAULT_LOG_LIMIT = 40
        private const val DEFAULT_RETENTION_DAYS = 7
        private const val MAX_LOG_LENGTH = 240
    }
}
