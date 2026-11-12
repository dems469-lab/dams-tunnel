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
}
