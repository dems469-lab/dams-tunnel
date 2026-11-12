package com.v2ray.ang.laboratory.ui

import com.v2ray.ang.laboratory.data.LaboratoryCounters
import com.v2ray.ang.laboratory.data.LaboratoryLogRow
import com.v2ray.ang.laboratory.data.PathAggregate
import com.v2ray.ang.laboratory.data.RelayEventRow
import com.v2ray.ang.laboratory.multipath.EngineState
import com.v2ray.ang.laboratory.multipath.MultipathConfigRepository
import com.v2ray.ang.laboratory.multipath.MultipathStatistics
import com.v2ray.ang.laboratory.multipath.PathLinkState
import com.v2ray.ang.laboratory.multipath.PathRole

/** État d'affichage d'un chemin. */
data class LaboratoryPathUi(
    val role: PathRole,
    val guid: String? = null,
    val name: String = "",
    val protocol: String = "",
    val state: PathLinkState = PathLinkState.IDLE,
    val latencyMillis: Long = -1L,
    val lossPercent: Int = 0,
    val availabilityPercent: Int = 0,
    val qualityScore: Int = 0,
    val throughputIndex: Int = 0,
    val sampleCount: Int = 0,
    val lastUpdateMillis: Long = 0L,
) {
    val selected: Boolean get() = !guid.isNullOrBlank()
}

/** État complet de l'écran Laboratoire. */
data class LaboratoryUiState(
    val loading: Boolean = true,
    val engineState: EngineState = EngineState.IDLE,
    val serviceRunning: Boolean = false,
    val serviceStateKnown: Boolean = false,
    val vpnModeAvailable: Boolean = true,
    val paths: List<LaboratoryPathUi> = emptyList(),
    val hybridEnabled: Boolean = true,
    val autoFailoverEnabled: Boolean = true,
    val probeIntervalSeconds: Int = 45,
    val preferredRole: PathRole? = null,
    val selector: List<String> = emptyList(),
    val busy: Boolean = false,
    val busyLabel: String? = null,
    val notice: String? = null,
    val error: String? = null,
    val profiles: List<MultipathConfigRepository.LaboratoryProfile> = emptyList(),
    val relayHistory: List<RelayEventRow> = emptyList(),
    val logs: List<LaboratoryLogRow> = emptyList(),
    val aggregates: List<PathAggregate> = emptyList(),
    val counters: LaboratoryCounters = LaboratoryCounters(0, 0, 0),
    val sessionStats: MultipathStatistics.Snapshot = MultipathStatistics.Snapshot(),
    val showActivationWarning: Boolean = false,
    val showResetConfirmation: Boolean = false,
    val editingRole: PathRole? = null,
    val exitRequested: Boolean = false,
) {
    val isActive: Boolean get() = engineState == EngineState.ACTIVE
    val isTransitioning: Boolean
        get() = engineState == EngineState.PREPARING ||
            engineState == EngineState.STOPPING_CLASSIC ||
            engineState == EngineState.STARTING ||
            engineState == EngineState.STOPPING
    val selectionComplete: Boolean get() = paths.count { it.selected } == 3
    val canEditSelection: Boolean get() = !isActive && !busy
    val editingPath: LaboratoryPathUi?
        get() = editingRole?.let { role -> paths.firstOrNull { it.role == role } }
}
