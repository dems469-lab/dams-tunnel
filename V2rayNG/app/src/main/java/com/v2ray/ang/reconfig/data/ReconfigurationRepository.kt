package com.v2ray.ang.reconfig.data

import android.content.Context
import com.v2ray.ang.publicconfigs.redactPublicLabel
import com.v2ray.ang.reconfig.engine.ReconfigurationOutcome
import com.v2ray.ang.util.JsonUtil

internal data class PreparedReconfiguration(
    val originalId: String,
    val outcome: ReconfigurationOutcome,
)

internal data class PrepareSummary(
    val prepared: Int,
    val duplicates: Int,
)

/**
 * Contrat de sauvegarde, isolé pour permettre des tests JVM sans base Android.
 */
internal interface ReconfigurationStore {
    suspend fun savePrepared(items: List<PreparedReconfiguration>, now: Long): PrepareSummary
}

/**
 * Dépôt des serveurs préparés.
 *
 * La sauvegarde est immédiate et transactionnelle : un arrêt du processus ou un
 * redémarrage du téléphone ne perd rien, et le transfert vers Configs ne
 * supprime une ligne qu'après confirmation de l'écriture du profil.
 */
internal class ReconfigurationRepository(
    context: Context,
    private val dao: ReconfigurationDao = ReconfigurationDao(context),
) : ReconfigurationStore {
    suspend fun loadAll(): List<ReconfiguredServer> = dao.loadAll()

    suspend fun findByIds(ids: Set<Long>): List<ReconfiguredServer> = dao.findByIds(ids)

    override suspend fun savePrepared(
        items: List<PreparedReconfiguration>,
        now: Long,
    ): PrepareSummary {
        val rows = items.mapNotNull { item -> toRow(item, now) }
        if (rows.isEmpty()) return PrepareSummary(0, 0)
        val summary = dao.insertAll(rows)
        return PrepareSummary(prepared = summary.inserted, duplicates = summary.duplicates)
    }

    suspend fun updatePing(id: Long, state: ReconfigPingState, latencyMs: Long, at: Long) =
        dao.updatePing(id, state, latencyMs, at)

    suspend fun updateTest(id: Long, state: ReconfigTestState, functional: Boolean, at: Long, error: String?) =
        dao.updateTest(id, state, functional, at, error)

    suspend fun delete(ids: Set<Long>): Int = dao.delete(ids)

    private fun toRow(item: PreparedReconfiguration, now: Long): ReconfiguredServer? {
        val outcome = item.outcome
        val transformed = outcome.transformed ?: return null
        val json = JsonUtil.toJson(transformed)
        val transformedConfig = outcome.transformedConfig.orEmpty()
        // L'empreinte porte sur la forme complète, pas sur l'URI : deux URI
        // différentes du même profil ne doivent pas créer deux lignes.
        val hash = ReconfigurationFingerprint.of(json)
        return ReconfiguredServer(
            configHash = hash,
            originalConfigId = item.originalId,
            originalConfig = outcome.originalConfig ?: JsonUtil.toJson(outcome.original),
            transformedConfig = transformedConfig,
            transformedProfileJson = json,
            name = displayName(outcome),
            protocol = transformed.configType.name,
            originalAddress = outcome.originalAddress,
            transformedAddress = outcome.transformedAddress,
            originalPort = outcome.originalPort,
            transformedPort = outcome.transformedPort,
            reconfigurationMethod = outcome.method.storageKey,
            target = outcome.target,
            createdAt = now,
            updatedAt = now,
            isValid = true,
        )
    }

    private fun displayName(outcome: ReconfigurationOutcome): String {
        val raw = outcome.original.remarks.ifBlank { outcome.originalAddress }
        return redactPublicLabel(raw).ifBlank { outcome.originalAddress }
    }
}
