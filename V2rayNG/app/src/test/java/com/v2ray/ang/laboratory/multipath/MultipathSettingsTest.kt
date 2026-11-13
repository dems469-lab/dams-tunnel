package com.v2ray.ang.laboratory.multipath

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MultipathSettingsTest {

    @Test
    fun `defaults are already normalized`() {
        val settings = MultipathSettings()
        assertEquals(settings, settings.normalized())
        assertTrue(settings.hybridEnabled)
        assertTrue(settings.autoFailoverEnabled)
        assertEquals(MultipathSettings.DEFAULT_PROBE_URL, settings.probeUrl)
    }

    @Test
    fun `probe interval is clamped`() {
        assertEquals(
            MultipathSettings.MIN_PROBE_INTERVAL,
            MultipathSettings(probeIntervalSeconds = 1).normalized().probeIntervalSeconds,
        )
        assertEquals(
            MultipathSettings.MAX_PROBE_INTERVAL,
            MultipathSettings(probeIntervalSeconds = 10_000).normalized().probeIntervalSeconds,
        )
    }

    @Test
    fun `failover cooldown is clamped`() {
        assertEquals(
            MultipathSettings.MIN_COOLDOWN,
            MultipathSettings(failoverCooldownSeconds = 0).normalized().failoverCooldownSeconds,
        )
        assertEquals(
            MultipathSettings.MAX_COOLDOWN,
            MultipathSettings(failoverCooldownSeconds = 5_000).normalized().failoverCooldownSeconds,
        )
    }

    @Test
    fun `invalid probe url falls back to the default`() {
        assertEquals(
            MultipathSettings.DEFAULT_PROBE_URL,
            MultipathSettings(probeUrl = "ftp://example.com").normalized().probeUrl,
        )
        assertEquals(
            MultipathSettings.DEFAULT_PROBE_URL,
            MultipathSettings(probeUrl = "   ").normalized().probeUrl,
        )
    }

    @Test
    fun `cooldown is exposed in milliseconds`() {
        assertEquals(45_000L, MultipathSettings(failoverCooldownSeconds = 45).failoverCooldownMillis())
        assertEquals(
            MultipathSettings.OBSERVATORY_INTERVAL_SECONDS,
            MultipathSettings().observatoryIntervalSeconds(),
        )
    }

    @Test
    fun `monitoring cadence is fast enough for an anticipatory failover`() {
        assertEquals(3, MultipathSettings.DEFAULT_PROBE_INTERVAL)
        assertEquals(1, MultipathSettings.MIN_PROBE_INTERVAL)
        assertEquals(1, MultipathSettings.DEGRADED_PROBE_INTERVAL_SECONDS)
        // L'observatoire du démon reste actif écran éteint, mais à 5 s : Xray
        // ne garantit pas une sonde par seconde.
        assertEquals(5, MultipathSettings.OBSERVATORY_INTERVAL_SECONDS)
        assertEquals(
            3,
            MultipathSettings(probeIntervalSeconds = 3).normalized().probeIntervalSeconds,
        )
    }
}
