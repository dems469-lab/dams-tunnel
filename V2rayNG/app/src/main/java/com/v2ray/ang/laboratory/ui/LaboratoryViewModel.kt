package com.v2ray.ang.laboratory.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.R
import com.v2ray.ang.core.LaboratoryTunnelBridge
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.laboratory.data.LaboratoryPreferences
import com.v2ray.ang.laboratory.data.LaboratoryRepository
import com.v2ray.ang.laboratory.multipath.EngineState
import com.v2ray.ang.laboratory.multipath.MultipathConfigRepository
import com.v2ray.ang.laboratory.multipath.MultipathController
import com.v2ray.ang.laboratory.multipath.MultipathEngine
import com.v2ray.ang.laboratory.multipath.MultipathEventLogger
import com.v2ray.ang.laboratory.multipath.MultipathSettings
import com.v2ray.ang.laboratory.multipath.MultipathStatistics
import com.v2ray.ang.laboratory.multipath.PathHealth
import com.v2ray.ang.laboratory.multipath.PathHealthMonitor
import com.v2ray.ang.laboratory.multipath.PathLinkState
import com.v2ray.ang.laboratory.multipath.PathRole
import com.v2ray.ang.laboratory.service.LaboratoryTunnelGateway
import com.v2ray.ang.ui.base.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * État et orchestration de l'écran Laboratoire.
 *
 * L'écran ne démarre rien tout seul : [requestActivation] n'est appelé que sur
 * action explicite, après confirmation si un VPN classique tourne. La
 * surveillance réelle ne s'exécute que lorsque l'écran est visible ; le tunnel,
 * lui, continue de fonctionner avec l'observatoire interne de Xray.
 */
class LaboratoryViewModel(application: Application) : BaseViewModel(application) {

    private val preferences = LaboratoryPreferences(application)
    private val configRepository = MultipathConfigRepository(application, preferences)
    private val repository = LaboratoryRepository(application)
    private val logger = MultipathEventLogger(repository)
    private val monitor = PathHealthMonitor(application)
    private val engine = MultipathEngine(application, configRepository)
    private val controller = MultipathController(
        context = application,
        repository = repository,
        configRepository = configRepository,
        engine = engine,
        monitor = monitor,
        logger = logger,
    )

    private val _uiState = MutableStateFlow(LaboratoryUiState())
    val uiState: StateFlow<LaboratoryUiState> = _uiState.asStateFlow()

    private var gateway: LaboratoryTunnelGateway? = null
    private var gatewayStateJob: Job? = null
    private var monitorJob: Job? = null

    @Volatile
    private var screenVisible = false

    init {
        bootstrap()
    }

    // ── Cycle de vie ─────────────────────────────────────────────────────────

    fun attachGateway(labGateway: LaboratoryTunnelGateway) {
        if (gateway === labGateway) return
        gatewayStateJob?.cancel()
        gateway?.unregister()
        gateway = labGateway
        labGateway.register()
        gatewayStateJob = viewModelScope.launch {
            labGateway.serviceState.collect { state -> onServiceState(state) }
        }
    }

    fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
        if (visible) {
            if (controller.isActive()) startMonitoringIfVisible()
        } else {
            stopMonitoring()
        }
    }

    override fun onCleared() {
        stopMonitoring()
        gatewayStateJob?.cancel()
        gateway?.unregister()
        gateway = null
        controller.close()
        super.onCleared()
    }

    // ── Chargement initial ──────────────────────────────────────────────────

    private fun bootstrap() {
        viewModelScope.launch {
            val settings = withContext(Dispatchers.IO) { configRepository.settings() }
            val profiles = withContext(Dispatchers.IO) { configRepository.availableProfiles() }
            val saved = withContext(Dispatchers.IO) { configRepository.selection() }
            val selection = sanitizeSelection(saved, profiles)
            withContext(Dispatchers.IO) { configRepository.saveSelection(selection) }
            val session = withContext(Dispatchers.IO) { LaboratoryTunnelBridge.read(app) }

            _uiState.update {
                it.copy(
                    loading = false,
                    profiles = profiles,
                    paths = buildPaths(selection, profiles, controller.currentHealth()),
                    hybridEnabled = settings.hybridEnabled,
                    autoFailoverEnabled = settings.autoFailoverEnabled,
                    probeIntervalSeconds = settings.probeIntervalSeconds,
                    vpnModeAvailable = SettingsManager.isVpnMode(),
                    // Une session sur disque ne prouve pas que le tunnel tourne :
                    // l'état « actif » n'est affiché qu'après confirmation du
                    // service (MSG_REGISTER_CLIENT ci-dessous).
                    engineState = EngineState.IDLE,
                )
            }

            if (session != null) {
                val adopted = withContext(Dispatchers.IO) {
                    controller.adoptActiveSession(settings, selection)
                }
                if (!adopted) {
                    withContext(Dispatchers.IO) { LaboratoryTunnelBridge.clear(app) }
                    controller.markInactiveExternally()
                    _uiState.update {
                        it.copy(
                            engineState = EngineState.IDLE,
                            notice = app.getString(R.string.laboratory_notice_orphan_session),
                        )
                    }
                }
            }
            refreshHistory()
        }
    }

    private fun sanitizeSelection(
        saved: Map<PathRole, String?>,
        profiles: List<MultipathConfigRepository.LaboratoryProfile>,
    ): Map<PathRole, String?> {
        val available = profiles.map { it.guid }.toSet()
        if (available.isEmpty()) {
            return PathRole.entries.associateWith { null }
        }
        val productionSelected = MmkvManager.getSelectServer()?.takeIf { it in available }
        val result = LinkedHashMap<PathRole, String?>()
        PathRole.entries.forEach { role ->
            result[role] = saved[role]?.takeIf { it in available }
        }
        if (result[PathRole.PRIMARY] == null) {
            result[PathRole.PRIMARY] = productionSelected ?: profiles.first().guid
        }
        val used = result.values.filterNotNull().toMutableSet()
        listOf(PathRole.SECONDARY_B, PathRole.SECONDARY_C).forEach { role ->
            if (result[role] == null) {
                val candidate = profiles.firstOrNull { it.guid !in used }?.guid
                if (candidate != null) {
                    result[role] = candidate
                    used.add(candidate)
                }
            }
        }
        val seen = mutableSetOf<String>()
        PathRole.entries.forEach { role ->
            val guid = result[role]
            if (guid != null && !seen.add(guid)) {
                result[role] = null
            }
        }
        return result
    }

    private fun buildPaths(
        selection: Map<PathRole, String?>,
        profiles: List<MultipathConfigRepository.LaboratoryProfile>,
        health: List<PathHealth>,
    ): List<LaboratoryPathUi> = PathRole.entries.map { role ->
        val guid = selection[role]
        val profile = profiles.firstOrNull { it.guid == guid }
        val pathHealth = health.firstOrNull { it.role == role }
        LaboratoryPathUi(
            role = role,
            guid = guid,
            name = profile?.remarks ?: app.getString(R.string.laboratory_path_empty),
            protocol = profile?.protocol.orEmpty(),
            state = pathHealth?.state ?: PathLinkState.IDLE,
            latencyMillis = pathHealth?.metrics?.latencyMillis ?: -1L,
            lossPercent = pathHealth?.metrics?.lossPercent ?: 0,
            availabilityPercent = pathHealth?.metrics?.availabilityPercent ?: 0,
            qualityScore = pathHealth?.metrics?.qualityScore ?: 0,
            throughputIndex = pathHealth?.metrics?.throughputIndex ?: 0,
            sampleCount = pathHealth?.metrics?.sampleCount ?: 0,
            lastUpdateMillis = pathHealth?.lastUpdateMillis ?: 0L,
        )
    }

    // ── Actions utilisateur ─────────────────────────────────────────────────

    fun selectPath(role: PathRole, guid: String?) {
        val state = _uiState.value
        if (!state.canEditSelection) {
            _uiState.update { it.copy(notice = app.getString(R.string.laboratory_notice_selection_locked)) }
            return
        }
        val selection = LinkedHashMap<PathRole, String?>()
        state.paths.forEach { selection[it.role] = it.guid }
        selection.forEach { (otherRole, otherGuid) ->
            if (otherRole != role && guid != null && otherGuid == guid) {
                selection[otherRole] = null
            }
        }
        selection[role] = guid
        configRepository.saveSelection(selection)
        _uiState.update {
            it.copy(
                paths = buildPaths(selection, state.profiles, controller.currentHealth()),
                editingRole = null,
            )
        }
    }

    fun openPathPicker(role: PathRole) {
        if (!_uiState.value.canEditSelection) {
            _uiState.update { it.copy(notice = app.getString(R.string.laboratory_notice_selection_locked)) }
            return
        }
        _uiState.update { it.copy(editingRole = role) }
    }

    fun closePathPicker() {
        _uiState.update { it.copy(editingRole = null) }
    }

    fun setHybrid(enabled: Boolean) {
        if (_uiState.value.busy) return
        val settings = configRepository.settings().copy(hybridEnabled = enabled)
        configRepository.saveSettings(settings)
        val wasActive = controller.isActive()
        _uiState.update { it.copy(hybridEnabled = enabled) }
        if (wasActive) applySettingsLive(settings)
    }

    fun setAutoFailover(enabled: Boolean) {
        if (_uiState.value.busy) return
        val settings = configRepository.settings().copy(autoFailoverEnabled = enabled)
        configRepository.saveSettings(settings)
        val wasActive = controller.isActive()
        _uiState.update { it.copy(autoFailoverEnabled = enabled) }
        if (wasActive) applySettingsLive(settings)
    }

    fun setProbeInterval(seconds: Int) {
        val settings = configRepository.settings()
            .copy(probeIntervalSeconds = seconds)
            .normalized()
        configRepository.saveSettings(settings)
        _uiState.update { it.copy(probeIntervalSeconds = settings.probeIntervalSeconds) }
    }

    private fun applySettingsLive(settings: MultipathSettings) {
        viewModelScope.launch {
            val applied = controller.applySettingsWhileActive(settings)
            if (applied) {
                _uiState.update {
                    it.copy(
                        selector = controller.currentDecision()?.selector ?: it.selector,
                        notice = app.getString(R.string.laboratory_notice_mode_applied),
                    )
                }
                refreshHistory()
            }
        }
    }

    fun requestActivation() {
        val state = _uiState.value
        if (state.isActive || state.busy || state.isTransitioning) return
        if (!state.vpnModeAvailable) {
            _uiState.update { it.copy(error = app.getString(R.string.laboratory_notice_vpn_mode_required)) }
            return
        }
        if (!state.selectionComplete) {
            _uiState.update { it.copy(notice = app.getString(R.string.laboratory_notice_select_paths)) }
            return
        }
        if (state.profiles.isEmpty()) {
            _uiState.update { it.copy(notice = app.getString(R.string.laboratory_notice_no_profile)) }
            return
        }
        if (state.serviceRunning) {
            _uiState.update { it.copy(showActivationWarning = true) }
            return
        }
        performActivation()
    }

    fun confirmActivation() {
        _uiState.update { it.copy(showActivationWarning = false) }
        performActivation()
    }

    fun cancelActivation() {
        _uiState.update { it.copy(showActivationWarning = false) }
    }

    fun performActivation() {
        val labGateway = gateway ?: run {
            _uiState.update { it.copy(error = app.getString(R.string.laboratory_error_service_unavailable)) }
            return
        }
        val state = _uiState.value
        if (state.busy) return
        val selection = state.paths.associate { it.role to it.guid }
        val primary = selection[PathRole.PRIMARY]?.takeIf { it.isNotBlank() } ?: return
        val secondary = buildMap {
            selection[PathRole.SECONDARY_B]?.takeIf { it.isNotBlank() }?.let {
                put(PathRole.SECONDARY_B, it)
            }
            selection[PathRole.SECONDARY_C]?.takeIf { it.isNotBlank() }?.let {
                put(PathRole.SECONDARY_C, it)
            }
        }
        if (secondary.size != 2) {
            _uiState.update { it.copy(notice = app.getString(R.string.laboratory_notice_select_paths)) }
            return
        }

        viewModelScope.launch {
            setBusy(true, R.string.laboratory_busy_activating)
            _uiState.update { it.copy(showActivationWarning = false, error = null) }
            val result = controller.activate(
                gateway = labGateway,
                primaryGuid = primary,
                secondaryGuids = secondary,
                settings = configRepository.settings(),
            )
            setBusy(false, null)
            val running = labGateway.isTunnelRunning()
            _uiState.update {
                it.copy(
                    engineState = controller.state,
                    serviceRunning = running,
                    sessionStats = controller.statisticsSnapshot(),
                )
            }
            if (result.success && running) {
                _uiState.update {
                    it.copy(notice = app.getString(R.string.laboratory_notice_activation_success))
                }
                startMonitoringIfVisible()
            } else if (result.success) {
                // Le moteur a répondu mais l'état courant n'est plus actif :
                // on ne prétend jamais que le VPN est connecté.
                controller.deactivate(labGateway, "verification-failed")
                _uiState.update {
                    it.copy(
                        engineState = EngineState.FAILED,
                        serviceRunning = false,
                        error = app.getString(R.string.laboratory_error_lab_start_failed),
                    )
                }
            } else {
                _uiState.update { it.copy(error = activationError(result.code)) }
            }
            refreshHistory()
        }
    }

    fun requestDeactivation() {
        val labGateway = gateway ?: return
        if (_uiState.value.busy) return
        viewModelScope.launch {
            setBusy(true, R.string.laboratory_busy_stopping)
            val result = controller.deactivate(labGateway, "user")
            stopMonitoring()
            setBusy(false, null)
            _uiState.update {
                it.copy(
                    engineState = controller.state,
                    serviceRunning = labGateway.isTunnelRunning(),
                    error = if (result.success) null else {
                        app.getString(R.string.laboratory_error_stop_unconfirmed)
                    },
                    notice = if (result.success) {
                        app.getString(R.string.laboratory_notice_stopped)
                    } else {
                        it.notice
                    },
                )
            }
            refreshHistory()
        }
    }

    fun requestBackToClassic() {
        val labGateway = gateway ?: return
        if (_uiState.value.busy) return
        viewModelScope.launch {
            setBusy(true, R.string.laboratory_busy_back)
            val result = if (controller.state == EngineState.IDLE) {
                MultipathController.OperationResult(true, "inactive")
            } else {
                controller.deactivate(labGateway, "back-to-classic")
            }
            stopMonitoring()
            setBusy(false, null)
            if (!result.success) {
                _uiState.update {
                    it.copy(
                        engineState = controller.state,
                        error = app.getString(R.string.laboratory_error_stop_unconfirmed),
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(engineState = EngineState.IDLE, serviceRunning = false) }
            if (MmkvManager.getSelectServer().isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        notice = app.getString(R.string.laboratory_notice_no_profile),
                        exitRequested = true,
                    )
                }
            } else {
                LauncherManager.startService(app)
                _uiState.update {
                    it.copy(
                        notice = app.getString(R.string.laboratory_notice_back_to_classic),
                        exitRequested = true,
                    )
                }
            }
            refreshHistory()
        }
    }

    fun probeNow() {
        if (!controller.isActive() || _uiState.value.busy) return
        viewModelScope.launch {
            setBusy(true, R.string.laboratory_busy_probing)
            val round = controller.runHealthRound(configRepository.settings())
            setBusy(false, null)
            if (round != null) {
                publishHealth(round.health, round.decision)
                if (round.reloadApplied) refreshHistory()
            }
        }
    }

    fun requestReset() {
        if (_uiState.value.isActive) {
            _uiState.update { it.copy(notice = app.getString(R.string.laboratory_notice_reset_requires_stop)) }
            return
        }
        _uiState.update { it.copy(showResetConfirmation = true) }
    }

    fun cancelReset() {
        _uiState.update { it.copy(showResetConfirmation = false) }
    }

    fun confirmReset() {
        if (_uiState.value.isActive) return
        viewModelScope.launch {
            setBusy(true, R.string.laboratory_busy_resetting)
            withContext(Dispatchers.IO) {
                LaboratoryTunnelBridge.clear(app)
                repository.reset()
                preferences.clear()
                monitor.reset()
            }
            controller.markInactiveExternally()
            setBusy(false, null)
            _uiState.update {
                LaboratoryUiState(
                    loading = false,
                    vpnModeAvailable = SettingsManager.isVpnMode(),
                    notice = app.getString(R.string.laboratory_notice_reset),
                )
            }
            bootstrap()
        }
    }

    fun consumeNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    fun consumeError() {
        _uiState.update { it.copy(error = null) }
    }

    fun consumeExit() {
        _uiState.update { it.copy(exitRequested = false) }
    }

    // ── Surveillance ────────────────────────────────────────────────────────

    private fun startMonitoringIfVisible() {
        if (!screenVisible || !controller.isActive()) return
        if (monitorJob?.isActive == true) return
        monitorJob = viewModelScope.launch {
            while (screenVisible && controller.isActive()) {
                val settings = withContext(Dispatchers.IO) { configRepository.settings() }
                val round = controller.runHealthRound(settings)
                if (round != null) {
                    publishHealth(round.health, round.decision)
                    if (round.reloadApplied) refreshHistory()
                }
                delay(settings.probeIntervalSeconds.coerceAtLeast(20) * 1_000L)
            }
        }
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun publishHealth(health: List<PathHealth>, decision: com.v2ray.ang.laboratory.multipath.FailoverDecision?) {
        val current = _uiState.value
        val byRole = health.associateBy { it.role }
        val paths = current.paths.map { path ->
            val measured = byRole[path.role] ?: return@map path
            path.copy(
                state = measured.state,
                latencyMillis = measured.metrics.latencyMillis,
                lossPercent = measured.metrics.lossPercent,
                availabilityPercent = measured.metrics.availabilityPercent,
                qualityScore = measured.metrics.qualityScore,
                throughputIndex = measured.metrics.throughputIndex,
                sampleCount = measured.metrics.sampleCount,
                lastUpdateMillis = measured.lastUpdateMillis,
            )
        }
        _uiState.update {
            it.copy(
                paths = paths,
                preferredRole = decision?.preferred ?: it.preferredRole,
                selector = decision?.selector ?: it.selector,
                sessionStats = controller.statisticsSnapshot(),
            )
        }
    }

    // ── États du service ────────────────────────────────────────────────────

    private fun onServiceState(what: Int?) {
        when (what) {
            null -> Unit
            com.v2ray.ang.AppConfig.MSG_STATE_START_SUCCESS,
            com.v2ray.ang.AppConfig.MSG_STATE_CONNECTING,
            com.v2ray.ang.AppConfig.MSG_STATE_RECONNECTING -> {
                _uiState.update {
                    it.copy(
                        serviceRunning = true,
                        serviceStateKnown = true,
                        // Session adoptée après recréation de l'interface : le
                        // moteur n'est déclaré actif que maintenant, sur preuve.
                        engineState = if (controller.isActive()) controller.state else it.engineState,
                    )
                }
                if (controller.isActive()) startMonitoringIfVisible()
            }

            com.v2ray.ang.AppConfig.MSG_STATE_NOT_RUNNING,
            com.v2ray.ang.AppConfig.MSG_STATE_STOP_SUCCESS -> {
                _uiState.update { it.copy(serviceRunning = false, serviceStateKnown = true) }
                if (controller.isActive()) onTunnelStoppedExternally()
            }

            com.v2ray.ang.AppConfig.MSG_STATE_START_FAILURE -> {
                _uiState.update { it.copy(serviceRunning = false, serviceStateKnown = true) }
            }
        }
    }

    private fun onTunnelStoppedExternally() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { LaboratoryTunnelBridge.clear(app) }
            controller.markInactiveExternally()
            stopMonitoring()
            _uiState.update {
                it.copy(
                    engineState = EngineState.IDLE,
                    sessionStats = MultipathStatistics.Snapshot(),
                    paths = buildPaths(
                        it.paths.associate { path -> path.role to path.guid },
                        it.profiles,
                        emptyList(),
                    ),
                    notice = app.getString(R.string.laboratory_notice_service_stopped),
                )
            }
            refreshHistory()
        }
    }

    // ── Données d'historique ────────────────────────────────────────────────

    private fun refreshHistory() {
        viewModelScope.launch {
            val relays = withContext(Dispatchers.IO) { repository.recentRelays() }
            val logs = withContext(Dispatchers.IO) { repository.recentLogs() }
            val aggregates = withContext(Dispatchers.IO) { repository.aggregates() }
            val counters = withContext(Dispatchers.IO) { repository.counters() }
            _uiState.update {
                it.copy(
                    relayHistory = relays,
                    logs = logs,
                    aggregates = aggregates,
                    counters = counters,
                )
            }
        }
    }

    // ── Utilitaires ─────────────────────────────────────────────────────────

    private fun setBusy(busy: Boolean, labelRes: Int?) {
        _uiState.update {
            it.copy(
                busy = busy,
                busyLabel = labelRes?.let { res -> app.getString(res) },
            )
        }
    }

    private fun activationError(code: String): String {
        val resource = when (code) {
            "primary_missing" -> R.string.laboratory_error_primary_missing
            "primary_unsupported" -> R.string.laboratory_error_primary_unsupported
            "primary_config_invalid" -> R.string.laboratory_error_primary_config_invalid
            "secondary_missing" -> R.string.laboratory_error_secondary_missing
            "secondary_unsupported" -> R.string.laboratory_error_secondary_unsupported
            "duplicate_server" -> R.string.laboratory_error_duplicate_server
            "outbound_invalid" -> R.string.laboratory_error_outbound_invalid
            "injection_failed" -> R.string.laboratory_error_injection_failed
            "config_invalid" -> R.string.laboratory_error_config_invalid
            "publish_failed" -> R.string.laboratory_error_publish_failed
            "classic_stop_failed" -> R.string.laboratory_error_classic_stop_failed
            "lab_start_failed" -> R.string.laboratory_error_lab_start_failed
            "stop_unconfirmed" -> R.string.laboratory_error_stop_unconfirmed
            else -> R.string.laboratory_error_prepare_failed
        }
        return app.getString(resource)
    }
}
