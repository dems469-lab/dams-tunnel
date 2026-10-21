package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.TrafficSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionEphemeralStateTest {
    private fun connectedState() = MainUiState(
        isRunning = true,
        status = MainStatus.Connected,
        connectionDelayMillis = 161L,
        traffic = TrafficSnapshot(downlinkBytesPerSecond = 120L, uplinkBytesPerSecond = 45L),
        trafficHistory = listOf(TrafficSnapshot(downlinkBytesPerSecond = 120L)),
        diagnostic = ConnectionDiagnostic(true, true, true, 161L),
    )

    @Test
    fun homePingOnlyUsesAnActiveSessionValue() {
        assertNull(homeSessionPing(false, 161L))
        assertNull(homeSessionPing(true, null))
        assertEquals(161L, homeSessionPing(true, 161L))
    }

    @Test
    fun disconnectClearsSessionPingTrafficGraphAndDiagnostic() {
        val disconnected = clearEphemeralConnectionData(connectedState(), MainStatus.Disconnected)
        assertFalseDisconnected(disconnected)
        assertNull(disconnected.connectionDelayMillis)
        assertEquals(TrafficSnapshot(), disconnected.traffic)
        assertTrue(disconnected.trafficHistory.isEmpty())
        assertNull(disconnected.diagnostic)
    }

    @Test
    fun connectionFailureAndStopDuringConnectionLeaveNoOldSessionData() {
        val failed = clearEphemeralConnectionData(connectedState(), MainStatus.Disconnected)
        val stoppedWhileConnecting = clearEphemeralConnectionData(
            connectedState().copy(status = MainStatus.Connecting),
            MainStatus.Disconnected,
        )
        assertNull(failed.connectionDelayMillis)
        assertNull(failed.diagnostic)
        assertNull(stoppedWhileConnecting.connectionDelayMillis)
        assertNull(stoppedWhileConnecting.diagnostic)
        assertTrue(stoppedWhileConnecting.trafficHistory.isEmpty())
    }

    @Test
    fun appRestartStartsWithoutPersistedEphemeralConnectionData() {
        val state = MainUiState()
        assertEquals(MainStatus.Disconnected, state.status)
        assertNull(state.connectionDelayMillis)
        assertEquals(TrafficSnapshot(), state.traffic)
        assertTrue(state.trafficHistory.isEmpty())
        assertNull(state.diagnostic)
    }

    @Test
    fun connectingShowsPendingDiagnosticWithoutGreenChecks() {
        val connecting = clearEphemeralConnectionData(
            connectedState(), MainStatus.Connecting, showPendingDiagnostic = true,
        )
        assertEquals(ConnectionDiagnostic(), connecting.diagnostic)
        assertNull(connecting.diagnostic?.networkAvailable)
        assertNull(connecting.diagnostic?.serverReachable)
        assertNull(connecting.diagnostic?.tunnelVerified)
    }

    private fun assertFalseDisconnected(state: MainUiState) {
        assertEquals(MainStatus.Disconnected, state.status)
        assertTrue(!state.isRunning)
    }
}
