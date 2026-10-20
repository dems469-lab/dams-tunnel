package com.v2ray.ang.ipfinder

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IpFinderFoundActionCoordinatorTest {
    @Test
    fun connectsVpnAfterFinalCellularAddressStillMatches() = runBlocking {
        val port = FakeFoundPort("10.63.4.5", IpFinderVpnConnectionResult.Connected)
        assertEquals(
            IpFinderFoundActionResult.Found("10.63.4.5", "10.63", true),
            coordinator(port).handle("10.63", true)
        )
        assertEquals(1, port.startCalls)
        assertEquals(IpFinderFoundActionCoordinator.VPN_CONNECT_TIMEOUT_MILLIS, port.requestedTimeout)
    }

    @Test
    fun leavesVpnDisconnectedWhenAutomaticConnectionIsDisabled() = runBlocking {
        val port = FakeFoundPort("10.63.4.5", IpFinderVpnConnectionResult.Connected)
        assertEquals(
            IpFinderFoundActionResult.Found("10.63.4.5", "10.63", false),
            coordinator(port).handle("10.63", false)
        )
        assertEquals(0, port.startCalls)
    }

    @Test
    fun retriesSearchIfCellularAddressNoLongerMatches() = runBlocking {
        val port = FakeFoundPort("10.64.4.5", IpFinderVpnConnectionResult.Connected)
        assertEquals(
            IpFinderFoundActionResult.RetrySearch("10.64.4.5"),
            coordinator(port).handle("10.63", true)
        )
        assertEquals(0, port.startCalls)
    }

    @Test
    fun reportsVpnConnectionFailureWithoutRetryingSearch() = runBlocking {
        val port = FakeFoundPort("10.63.4.5", IpFinderVpnConnectionResult.Failed("échec de vérification"))
        assertEquals(
            IpFinderFoundActionResult.VpnFailed("10.63.4.5", "échec de vérification"),
            coordinator(port).handle("10.63", true)
        )
        assertEquals(1, port.startCalls)
    }

    @Test
    fun refusesWhenSelectedProfileWasRemoved() = runBlocking {
        val port = FakeFoundPort("10.63.4.5", IpFinderVpnConnectionResult.Connected, hasProfile = false)
        assertEquals(IpFinderFoundActionResult.ProfileRequired, coordinator(port).handle("10.63", true))
        assertEquals(0, port.startCalls)
    }

    @Test
    fun aVpnDisconnectedBeforeSearchCanStillBeStartedOnSuccess() = runBlocking {
        val port = FakeFoundPort("10.63.4.5", IpFinderVpnConnectionResult.Connected)
        assertEquals(
            IpFinderFoundActionResult.Found("10.63.4.5", "10.63", true),
            coordinator(port).handle("10.63", true)
        )
        assertTrue(port.startCalls > 0)
    }

    @Test
    fun preflightRequiresProfileAndConfirmsActiveVpnBeforeSearch() {
        assertEquals(
            IpFinderPreflightDecision.PROFILE_REQUIRED,
            IpFinderPreflight.decide(IpFinderPreflightInput(false, false, false, true, false))
        )
        assertEquals(
            IpFinderPreflightDecision.CONFIRM_VPN_DISCONNECT,
            IpFinderPreflight.decide(IpFinderPreflightInput(false, true, false, true, true))
        )
        assertEquals(
            IpFinderPreflightDecision.REQUEST_VPN_PERMISSION,
            IpFinderPreflight.decide(IpFinderPreflightInput(false, true, true, false, false))
        )
        assertFalse(
            IpFinderPreflight.decide(IpFinderPreflightInput(true, true, false, true, false)) ==
                IpFinderPreflightDecision.START_SEARCH
        )
    }

    private fun coordinator(port: FakeFoundPort) = IpFinderFoundActionCoordinator(port, "10.63")

    private class FakeFoundPort(
        private val address: String?,
        private val vpnResult: IpFinderVpnConnectionResult,
        private val hasProfile: Boolean = true,
    ) : IpFinderFoundActionPort {
        var startCalls = 0
        var requestedTimeout: Long? = null

        override fun selectedProfileExists() = hasProfile
        override fun currentCellularAddress() = address
        override suspend fun startVpn() { startCalls++ }
        override suspend fun awaitVpnConnected(timeoutMillis: Long): IpFinderVpnConnectionResult {
            requestedTimeout = timeoutMillis
            return vpnResult
        }
    }
}
