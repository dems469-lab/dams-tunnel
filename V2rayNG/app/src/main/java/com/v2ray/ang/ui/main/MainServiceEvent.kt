package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TrafficSnapshot

sealed class MainServiceEvent {
    data object StateRunning : MainServiceEvent()
    data object StateNotRunning : MainServiceEvent()
    data object StateStartSuccess : MainServiceEvent()
    data object StateConnecting : MainServiceEvent()
    data object StateReconnecting : MainServiceEvent()
    data class StateStartFailure(val message: String? = null) : MainServiceEvent()
    data object StateStopSuccess : MainServiceEvent()
    data class TrafficUpdate(val snapshot: TrafficSnapshot) : MainServiceEvent()
    data class MeasureDelayResult(val result: ConnectionTestResult, val requestId: String) : MainServiceEvent()
    data class MeasureDelayCancelled(val requestId: String) : MainServiceEvent()
    data class MeasureConfigSuccess(val result: RealPingResult, val requestId: String) : MainServiceEvent()
    data class MeasureConfigNotify(val progress: String, val requestId: String) : MainServiceEvent()
    data class MeasureConfigFinish(val requestId: String) : MainServiceEvent()
    data class MeasureConfigCancelled(val requestId: String) : MainServiceEvent()
}
