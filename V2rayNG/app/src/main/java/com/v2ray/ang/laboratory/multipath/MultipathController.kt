package com.v2ray.ang.laboratory.multipath

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LaboratoryTunnelBridge
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.laboratory.data.LaboratoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cœur du laboratoire : machine à états, orchestration et décisions.
 *
 * Séquence d'activation imposée :
 * 1. préparer et valider la configuration (aucun tunnel touché) ;
 * 2. arrêter le VPN classique s'il tourne, et attendre sa confirmation ;
 * 3. publier la session expérimentale ;
 * 4. démarrer l'unique [com.v2ray.ang.service.CoreVpnService] ;
 * 5. n'annoncer l'état actif qu'après `MSG_STATE_START_SUCCESS` (vérification
 *    réelle du trafic par le moteur existant).
 *
 * Toute erreur intermédiaire nettoie la session et n'annonce jamais un état
 * connecté. Le mode normal peut alors être relancé sans état incohérent.
 */
class MultipathController(
    private val context: Context,
    private val repository: LaboratoryRepository,
    private val configRepository: MultipathConfigRepository,
    private val engine: MultipathEngine,
    private val monitor: PathHealthMonitor,
    private val logger: MultipathEventLogger,
) {

    data class OperationResult(
        val success: Boolean,
        val code: String,
        val detail: String? = null,
    )

    data class HealthRound(
        val health: List<PathHealth>,
        val decision: FailoverDecision?,
        val reloadApplied: Boolean,
    )

    private val failover = InstantFailoverManager()
    private val scheduler = SmartTrafficScheduler()
    private val statistics = MultipathStatistics()

    private var prepared: MultipathEngine.Prepared? = null
    private var sessionId: Long = 0L
    private var lastStates: Map<PathRole, PathLinkState> = emptyMap()
    private var lastHealth: List<PathHealth> = emptyList()
    private var lastDecision: FailoverDecision? = null
    private var lastReloadAtMillis: Long = 0L

    @Volatile
    var state: EngineState = EngineState.IDLE
        private set

    fun preparedInfo(): MultipathEngine.Prepared? = prepared

    fun currentHealth(): List<PathHealth> = lastHealth

    fun currentDecision(): FailoverDecision? = lastDecision

    fun statisticsSnapshot(): MultipathStatistics.Snapshot = statistics.snapshot()

    fun isActive(): Boolean = state == EngineState.ACTIVE

    /**
     * Reprend une session publiée après un redémarrage du processus UI.
     *
     * Le tunnel peut très bien tourner dans le processus `:daemon` alors que
     * l'interface vient d'être recréée. On reconstruit uniquement les cibles de
     * mesure ; la configuration publiée reste la référence pour les recharges.
     */
    suspend fun adoptActiveSession(
        settings: MultipathSettings,
        selection: Map<PathRole, String?>,
    ): Boolean {
        if (state == EngineState.ACTIVE) return true
        val session = withContext(Dispatchers.IO) { LaboratoryTunnelBridge.read(context) }
            ?: return false
        val secondary = buildMap {
            selection[PathRole.SECONDARY_B]?.let { put(PathRole.SECONDARY_B, it) }
            selection[PathRole.SECONDARY_C]?.let { put(PathRole.SECONDARY_C, it) }
        }
        if (secondary.size != 2) return false

        val rebuilt = withContext(Dispatchers.IO) {
            engine.prepare(session.primaryGuid, secondary, settings)
        }.getOrNull() ?: return false

        val restored = rebuilt.copy(
            configJson = session.configJson,
            selector = session.selector.ifEmpty { rebuilt.selector },
        )
        prepared = restored
        scheduler.markApplied(restored.selector)
        statistics.start()
        lastStates = emptyMap()
        lastHealth = emptyList()
        lastDecision = null
        state = EngineState.ACTIVE
        return true
    }

    // ── Activation / arrêt ───────────────────────────────────────────────────

    suspend fun activate(
        gateway: TunnelGateway,
        primaryGuid: String,
        secondaryGuids: Map<PathRole, String>,
        settings: MultipathSettings,
    ): OperationResult {
        if (state == EngineState.ACTIVE || state == EngineState.STARTING) {
            return OperationResult(true, "already_active")
        }
        state = EngineState.PREPARING
        val normalized = settings.normalized()
        logger.info(MultipathEventLogger.CATEGORY_ENGINE, "Préparation de la configuration Multipath")

        val preparation = withContext(Dispatchers.IO) {
            engine.prepare(primaryGuid, secondaryGuids, normalized)
        }
        val ready = preparation.getOrNull()
        if (ready == null) {
            val code = (preparation.exceptionOrNull() as? MultipathEngine.PreparationException)?.code
                ?: "prepare_failed"
            logger.error(MultipathEventLogger.CATEGORY_ENGINE, "Préparation refusée : $code")
            prepared = null
            state = EngineState.FAILED
            return OperationResult(false, code)
        }

        prepared = ready
        configRepository.saveSettings(normalized)
        state = EngineState.STOPPING_CLASSIC

        val stopped = try {
            gateway.stopClassicTunnel()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            false
        }
        if (!stopped) {
            val reason = gateway.lastFailureReason()
            prepared = null
            state = EngineState.FAILED
            logger.error(
                MultipathEventLogger.CATEGORY_SERVICE,
                "Arrêt du VPN classique non confirmé (${reason ?: "sans motif"}) : activation annulée",
            )
            return OperationResult(false, "classic_stop_failed", reason)
        }

        state = EngineState.STARTING
        val published = withContext(Dispatchers.IO) {
            LaboratoryTunnelBridge.write(
                context,
                LaboratoryTunnelBridge.Session(
                    primaryGuid = ready.primaryGuid,
                    configJson = ready.configJson,
                    selector = ready.selector,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        if (!published) {
            prepared = null
            state = EngineState.FAILED
            return OperationResult(false, "publish_failed")
        }

        val started = try {
            gateway.startLaboratoryTunnel()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            false
        }
        if (!started) {
            val reason = gateway.lastFailureReason()
            withContext(Dispatchers.IO) { LaboratoryTunnelBridge.clear(context) }
            runCatching { gateway.stopTunnel() }
            prepared = null
            state = EngineState.FAILED
            logger.error(
                MultipathEventLogger.CATEGORY_SERVICE,
                "Le moteur expérimental n'a pas confirmé son démarrage (${reason ?: "sans motif"})",
            )
            return OperationResult(false, "lab_start_failed", reason)
        }

        sessionId = withContext(Dispatchers.IO) {
            repository.startSession(
                primaryGuid = ready.primaryGuid,
                primaryName = ready.primaryName,
                mode = if (normalized.hybridEnabled) "hybrid" else "primary",
            )
        }
        statistics.start()
        lastStates = emptyMap()
        lastHealth = emptyList()
        lastDecision = null
        lastReloadAtMillis = 0L
        failover.reset()
        scheduler.markApplied(ready.selector)
        state = EngineState.ACTIVE

        // Un moteur démarré ne prouve rien : chaque chemin doit avoir répondu
        // à une vraie requête avant que le laboratoire ne soit déclaré actif.
        val verification = runCatching { runHealthRound(normalized) }.getOrNull()
        val usable = verification?.health?.count { it.metrics.isUsable } ?: 0
        if (usable == 0) {
            val detail = if (verification == null) "verification_failed" else "no_path_available"
            logger.error(
                MultipathEventLogger.CATEGORY_PATH,
                "Vérification réelle des chemins échouée ($detail) : moteur arrêté",
            )
            runCatching { gateway.stopTunnel() }
            withContext(Dispatchers.IO) {
                LaboratoryTunnelBridge.clear(context)
                repository.finishSession(sessionId, detail)
            }
            sessionId = 0L
            prepared = null
            statistics.reset()
            monitor.reset()
            lastHealth = emptyList()
            lastDecision = null
            state = EngineState.FAILED
            return OperationResult(false, detail)
        }

        logger.info(
            MultipathEventLogger.CATEGORY_ENGINE,
            "Laboratoire actif · principal ${ready.primaryName} · " +
                "sélecteur ${ready.selector.joinToString()} · " +
                "$usable/3 chemins vérifiés",
        )
        return OperationResult(true, "ok")
    }

    suspend fun deactivate(gateway: TunnelGateway, outcome: String = "user"): OperationResult {
        if (state == EngineState.IDLE) {
            withContext(Dispatchers.IO) { LaboratoryTunnelBridge.clear(context) }
            return OperationResult(true, "inactive")
        }
        state = EngineState.STOPPING
        val stopped = try {
            gateway.stopTunnel()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            false
        }
        if (!stopped) {
            val reason = gateway.lastFailureReason()
            if (!gateway.isTunnelRunning()) {
                // Aucun tunnel en marche : l'absence d'accusé ne doit pas
                // laisser la session ouverte ni bloquer le retour au mode normal.
                withContext(Dispatchers.IO) {
                    LaboratoryTunnelBridge.clear(context)
                    repository.finishSession(sessionId, outcome)
                }
                sessionId = 0L
                prepared = null
                lastHealth = emptyList()
                lastDecision = null
                lastStates = emptyMap()
                monitor.reset()
                failover.reset()
                scheduler.reset()
                statistics.reset()
                state = EngineState.IDLE
                logger.warn(
                    MultipathEventLogger.CATEGORY_SERVICE,
                    "Arrêt confirmé par l'état du moteur, sans accusé du service",
                )
                return OperationResult(true, "stopped_without_ack")
            }
            // Le moteur tourne encore : état cohérent avec la réalité, la
            // surveillance continue et une nouvelle tentative reste possible.
            state = EngineState.ACTIVE
            logger.warn(
                MultipathEventLogger.CATEGORY_SERVICE,
                "Arrêt du moteur non confirmé (${reason ?: "sans motif"}) : " +
                    "session conservée pour permettre une nouvelle tentative",
            )
            return OperationResult(false, "stop_unconfirmed", reason)
        }

        withContext(Dispatchers.IO) {
            LaboratoryTunnelBridge.clear(context)
            repository.finishSession(sessionId, outcome)
        }
        sessionId = 0L
        prepared = null
        lastHealth = emptyList()
        lastDecision = null
        lastStates = emptyMap()
        monitor.reset()
        failover.reset()
        scheduler.reset()
        statistics.reset()
        state = EngineState.IDLE
        logger.info(MultipathEventLogger.CATEGORY_ENGINE, "Laboratoire arrêté · retour au mode normal")
        return OperationResult(true, "stopped")
    }

    /** Réinitialise l'état interne après un arrêt constaté hors du laboratoire. */
    fun markInactiveExternally() {
        prepared = null
        sessionId = 0L
        lastHealth = emptyList()
        lastDecision = null
        lastStates = emptyMap()
        monitor.reset()
        failover.reset()
        scheduler.reset()
        statistics.reset()
        state = EngineState.IDLE
    }

    // ── Cycle de surveillance ────────────────────────────────────────────────

    suspend fun runHealthRound(settings: MultipathSettings): HealthRound? {
        val ready = prepared ?: return null
        if (state != EngineState.ACTIVE) return null

        val normalized = settings.normalized()
        val measured = try {
            // Les sondes sont bloquantes (core natif) : jamais sur le thread UI.
            withContext(Dispatchers.IO) { monitor.measure(ready.targets, normalized) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logger.warn(MultipathEventLogger.CATEGORY_PATH, "Cycle de mesure interrompu")
            return null
        }
        if (measured.isEmpty()) return null

        val now = System.currentTimeMillis()
        val previousPreferred = lastDecision?.preferred
        val decision = if (normalized.autoFailoverEnabled) {
            failover.decide(
                health = measured,
                hybridEnabled = normalized.hybridEnabled,
                nowMillis = now,
                cooldownMillisOverride = normalized.failoverCooldownMillis(),
            )
        } else {
            FailoverDecision(
                selector = ready.selector,
                preferred = measured
                    .filter { it.metrics.isUsable }
                    .maxByOrNull { it.metrics.qualityScore }
                    ?.role
                    ?: PathRole.PRIMARY,
                reason = null,
                changed = false,
            )
        }

        val decorated = measured.map { path ->
            val selected = path.role.tag in decision.selector
            val preferred = path.role == decision.preferred
            path.copy(
                state = ServerQualityAnalyzer.resolveState(path.metrics, selected, preferred),
            )
        }
        lastHealth = decorated
        lastDecision = decision
        statistics.recordRound(decorated, now)
        withContext(Dispatchers.IO) { repository.recordHealth(sessionId, decorated) }
        logTransitions(decorated)

        var reloadApplied = false
        if (decision.changed && scheduler.markAppliedIfNeeded(decision.selector)) {
            reloadApplied = applySelector(decision, previousPreferred)
        }

        return HealthRound(decorated, decision, reloadApplied)
    }

    /**
     * Applique un changement de sélection sans redémarrer le mode normal :
     * le sélecteur est réécrit dans la session puis le moteur recharge.
     */
    private suspend fun applySelector(
        decision: FailoverDecision,
        previousPreferred: PathRole?,
    ): Boolean {
        val ready = prepared ?: return false
        val updated = XrayMultipathConfig.withSelector(ready.configJson, decision.selector)
            ?: return false
        val written = withContext(Dispatchers.IO) {
            LaboratoryTunnelBridge.write(
                context,
                LaboratoryTunnelBridge.Session(
                    primaryGuid = ready.primaryGuid,
                    configJson = updated,
                    selector = decision.selector,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        if (!written) return false

        prepared = ready.copy(configJson = updated, selector = decision.selector)
        lastReloadAtMillis = System.currentTimeMillis()
        val sentAt = lastReloadAtMillis
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_LAB_RELOAD, "")

        // Un relais n'est compté qu'après application réelle : le démon
        // republie son état RUNNING (horodaté) quand le moteur a bien redémarré
        // avec la nouvelle sélection.
        val applied = withContext(Dispatchers.IO) {
            repeat(RELOAD_CONFIRM_ATTEMPTS) {
                val status = LaboratoryTunnelBridge.readStatus(context)
                if (status != null &&
                    status.state == LaboratoryTunnelBridge.STATE_RUNNING &&
                    status.atMillis >= sentAt
                ) {
                    return@withContext true
                }
                kotlinx.coroutines.delay(RELOAD_CONFIRM_STEP_MILLIS)
            }
            false
        }
        if (!applied) {
            logger.warn(
                MultipathEventLogger.CATEGORY_ENGINE,
                "Bascule préparée mais non appliquée par le moteur : relais non compté",
            )
            return false
        }

        val toRole = decision.preferred
        val fromRole = previousPreferred?.takeIf { it != toRole }
        val toLatency = lastHealth.firstOrNull { it.role == toRole }?.metrics?.latencyMillis ?: -1L
        val fromLatency = fromRole?.let { role ->
            lastHealth.firstOrNull { it.role == role }?.metrics?.latencyMillis
        } ?: -1L
        val event = RelayEvent(
            atMillis = lastReloadAtMillis,
            fromRole = fromRole,
            toRole = toRole,
            reason = decision.reason ?: "selector-change",
            fromLatencyMillis = fromLatency,
            toLatencyMillis = toLatency,
        )
        statistics.recordRelay()
        withContext(Dispatchers.IO) { logger.relay(sessionId, event) }
        return true
    }

    /**
     * Change le mode (hybride / principal strict) pendant que le laboratoire
     * tourne. Applique immédiatement le nouveau sélecteur.
     */
    suspend fun applySettingsWhileActive(settings: MultipathSettings): Boolean {
        val ready = prepared ?: return false
        if (state != EngineState.ACTIVE) return false
        val normalized = settings.normalized()
        configRepository.saveSettings(normalized)
        prepared = ready.copy(settings = normalized)
        if (!normalized.autoFailoverEnabled) return false

        val selector = if (normalized.hybridEnabled) {
            XrayMultipathConfig.allTags
        } else {
            listOf(XrayMultipathConfig.PRIMARY_TAG)
        }
        if (!scheduler.shouldReload(selector)) return false
        scheduler.markApplied(selector)
        failover.reset()
        return applySelector(
            FailoverDecision(
                selector = selector,
                preferred = PathRole.PRIMARY,
                reason = "mode-change",
                changed = true,
            ),
            previousPreferred = null,
        )
    }

    fun lastReloadAt(): Long = lastReloadAtMillis

    fun close() {
        repository.close()
    }

    // ── Journalisation des transitions ───────────────────────────────────────

    private fun logTransitions(health: List<PathHealth>) {
        val previous = lastStates
        val current = health.associate { it.role to it.state }
        health.forEach { path ->
            val before = previous[path.role]
            if (before != null && before != path.state) {
                when (path.state) {
                    PathLinkState.UNAVAILABLE -> logger.warn(
                        MultipathEventLogger.CATEGORY_PATH,
                        "${path.role.label} indisponible (${path.metrics.lossPercent}% de pertes)",
                    )

                    PathLinkState.CARRYING, PathLinkState.READY -> if (before == PathLinkState.UNAVAILABLE) {
                        logger.info(
                            MultipathEventLogger.CATEGORY_PATH,
                            "${path.role.label} rétabli (${path.metrics.latencyMillis} ms)",
                        )
                    }

                    else -> Unit
                }
            }
        }
        lastStates = current
    }

    private companion object {
        /** Le démon redémarre le moteur (~1 s) puis republie son état. */
        const val RELOAD_CONFIRM_ATTEMPTS = 15
        const val RELOAD_CONFIRM_STEP_MILLIS = 250L
    }
}
