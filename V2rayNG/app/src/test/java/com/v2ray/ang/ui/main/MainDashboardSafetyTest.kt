package com.v2ray.ang.ui.main

import com.v2ray.ang.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainDashboardSafetyTest {
    @Test
    fun connectionSubtitleNeverClaimsPrivacyProtectionFromServiceStateAlone() {
        assertEquals(R.string.dams_tunnel_active, connectionSubtitleRes(MainStatus.Connected, false))
        assertEquals(R.string.dams_per_app_tunnel_active, connectionSubtitleRes(MainStatus.Connected, true))
        assertEquals(R.string.connection_connecting, connectionSubtitleRes(MainStatus.Connecting, true))
        assertEquals(R.string.connection_reconnecting, connectionSubtitleRes(MainStatus.Reconnecting, true))
        assertEquals(R.string.dams_ready_to_connect, connectionSubtitleRes(MainStatus.Disconnected, true))
    }

    @Test
    fun emptyNormalProxySelectionRequiresAnExplicitChoice() {
        assertTrue(shouldWarnEmptyPerAppSelection(enabled = true, bypassMode = false, selectedPackages = emptySet()))
        assertFalse(shouldWarnEmptyPerAppSelection(enabled = false, bypassMode = false, selectedPackages = emptySet()))
        assertFalse(shouldWarnEmptyPerAppSelection(enabled = true, bypassMode = true, selectedPackages = emptySet()))
        assertFalse(shouldWarnEmptyPerAppSelection(enabled = true, bypassMode = false, selectedPackages = setOf("app.example")))
    }

    @Test
    fun coreProbeDoesNotClaimApplicationTrafficWasVerified() {
        val diagnostic = diagnosticFromCoreProbe(networkAvailable = true, delayMillis = 42)
        assertEquals(true, diagnostic.networkAvailable)
        assertEquals(true, diagnostic.serverReachable)
        assertEquals(42L, diagnostic.pingMillis)
        assertNull(diagnostic.tunnelVerified)
    }

    @Test
    fun failedCoreProbeDoesNotClaimServerReachability() {
        val diagnostic = diagnosticFromCoreProbe(networkAvailable = true, delayMillis = -1)
        assertEquals(false, diagnostic.serverReachable)
        assertNull(diagnostic.tunnelVerified)
        assertNull(diagnostic.pingMillis)
    }
}
