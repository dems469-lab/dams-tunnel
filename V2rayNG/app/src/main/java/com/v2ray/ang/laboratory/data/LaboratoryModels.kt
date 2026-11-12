package com.v2ray.ang.laboratory.data

/** Ligne de session expérimentale (une activation complète du laboratoire). */
data class LaboratorySessionRow(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val primaryGuid: String,
    val primaryName: String,
    val mode: String,
    val outcome: String?,
)

/** Une mesure enregistrée pour un chemin à un instant donné. */
data class PathStatRow(
    val id: Long,
    val sessionId: Long?,
    val recordedAt: Long,
    val tag: String,
    val guid: String,
    val remarks: String,
    val latencyMillis: Long,
    val lossPercent: Int,
    val availabilityPercent: Int,
    val stabilityPercent: Int,
    val qualityScore: Int,
    val throughputIndex: Int,
    val state: String,
)

/** Un changement de chemin réellement observé/appliqué. */
data class RelayEventRow(
    val id: Long,
    val sessionId: Long?,
    val recordedAt: Long,
    val fromTag: String?,
    val toTag: String,
    val reason: String,
    val fromLatencyMillis: Long,
    val toLatencyMillis: Long,
)

/** Entrée de journal du laboratoire. */
data class LaboratoryLogRow(
    val id: Long,
    val recordedAt: Long,
    val level: String,
    val category: String,
    val message: String,
)

/** Statistiques agrégées par serveur, toutes sessions confondues. */
data class PathAggregate(
    val roleTag: String,
    val remarks: String,
    val samples: Int,
    val averageLatencyMillis: Long,
    val averageLossPercent: Int,
    val averageQuality: Int,
)

/** Compteurs globaux du laboratoire. */
data class LaboratoryCounters(
    val sessions: Int,
    val samples: Int,
    val relays: Int,
)
