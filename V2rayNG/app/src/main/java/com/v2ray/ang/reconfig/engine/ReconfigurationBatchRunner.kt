package com.v2ray.ang.reconfig.engine

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.reconfig.data.PreparedReconfiguration
import com.v2ray.ang.reconfig.data.ReconfigurationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal data class ReconfigurationBatchResult(
    val prepared: Int,
    val duplicates: Int,
    val ignored: Int,
    val failures: Map<ReconfigurationStatus, Int>,
    val cancelled: Boolean,
    /** Toujours 0 : le moteur ne modifie jamais le profil d'origine. */
    val originalsModified: Int = 0,
) {
    val processed: Int get() = prepared + duplicates + ignored
}

/**
 * Traitement d'un lot de configurations.
 *
 * - chaque configuration est traitée séparément : une erreur n'interrompt pas
 *   les suivantes ;
 * - la transformation s'exécute sur [Dispatchers.Default] et l'écriture sur le
 *   dispatcher du dépôt, jamais sur le thread principal ;
 * - chaque résultat valide est enregistré immédiatement, ce qui permet
 *   d'annuler sans perdre le travail déjà fait ;
 * - aucune opération réseau n'est effectuée.
 */
internal class ReconfigurationBatchRunner(
    private val store: ReconfigurationStore,
    private val engine: LocalReconfigurationEngine = LocalReconfigurationEngine(),
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun run(
        inputs: List<Pair<String, ProfileItem>>,
        method: ReconfigMethod,
        target: String,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): ReconfigurationBatchResult {
        var prepared = 0
        var duplicates = 0
        var ignored = 0
        val failures = mutableMapOf<ReconfigurationStatus, Int>()
        val total = inputs.size
        var cancelled = false

        for ((index, input) in inputs.withIndex()) {
            if (!currentCoroutineContext().isActive) {
                cancelled = true
                break
            }
            try {
                val outcome = withContext(computationDispatcher) {
                    engine.reconfigure(input.second, method, target)
                }
                if (outcome.isSuccess) {
                    val summary = store.savePrepared(
                        listOf(PreparedReconfiguration(input.first, outcome)),
                        System.currentTimeMillis(),
                    )
                    prepared += summary.prepared
                    duplicates += summary.duplicates
                } else {
                    ignored++
                    failures[outcome.status] = (failures[outcome.status] ?: 0) + 1
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ignored++
                failures[ReconfigurationStatus.VALIDATION_FAILED] =
                    (failures[ReconfigurationStatus.VALIDATION_FAILED] ?: 0) + 1
            }
            onProgress(index + 1, total)
        }

        return ReconfigurationBatchResult(
            prepared = prepared,
            duplicates = duplicates,
            ignored = ignored,
            failures = failures,
            cancelled = cancelled,
        )
    }
}
