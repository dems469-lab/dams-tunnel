package com.v2ray.ang.ui.main

import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DamsHomeStateTest {

    // ---------- état du bouton Power ----------

    @Test
    fun `power state follows the real service state`() {
        assertEquals(
            HomePowerState.Disconnected,
            homePowerState(MainStatus.Disconnected, isRunning = false, diagnostic = null),
        )
        assertEquals(
            HomePowerState.Connecting,
            homePowerState(MainStatus.Connecting, isRunning = true, diagnostic = null),
        )
        assertEquals(
            HomePowerState.Reconnecting,
            homePowerState(MainStatus.Reconnecting, isRunning = true, diagnostic = null),
        )
        assertEquals(
            HomePowerState.Connected,
            homePowerState(MainStatus.Connected, isRunning = true, diagnostic = null),
        )
        // Un test de vitesse ne doit pas faire croire à une déconnexion.
        assertEquals(
            HomePowerState.Connected,
            homePowerState(MainStatus.Testing, isRunning = true, diagnostic = null),
        )
    }

    @Test
    fun `diagnostic failure is shown as an error only when the service is stopped`() {
        val failure = ConnectionDiagnostic(networkAvailable = true, serverReachable = false)
        assertEquals(
            HomePowerState.Error,
            homePowerState(MainStatus.Disconnected, isRunning = false, diagnostic = failure),
        )
        assertEquals(
            HomePowerState.Connected,
            homePowerState(MainStatus.Connected, isRunning = true, diagnostic = failure),
        )
    }

    // ---------- mesures dérivées ----------

    @Test
    fun `quality is unavailable without a real measurement`() {
        assertEquals(ConnectionQuality.Unavailable, connectionQuality(false, 40L))
        assertEquals(ConnectionQuality.Unavailable, connectionQuality(true, null))
        assertEquals(ConnectionQuality.Unavailable, connectionQuality(true, -1L))
    }

    @Test
    fun `quality thresholds use the measured latency`() {
        assertEquals(ConnectionQuality.Excellent, connectionQuality(true, 12L))
        assertEquals(ConnectionQuality.Good, connectionQuality(true, 90L))
        assertEquals(ConnectionQuality.Fair, connectionQuality(true, 180L))
        assertEquals(ConnectionQuality.Weak, connectionQuality(true, 600L))
    }

    @Test
    fun `signal bars are zero when nothing was measured`() {
        assertEquals(0, signalBarCount(null))
        assertEquals(0, signalBarCount(-1L))
        assertEquals(4, signalBarCount(30L))
        assertEquals(3, signalBarCount(120L))
        assertEquals(2, signalBarCount(200L))
        assertEquals(1, signalBarCount(900L))
    }

    @Test
    fun `megabits per second come from the engine byte counters`() {
        assertEquals("0.0", formatMbps(0L, Locale.US))
        assertEquals("8.0", formatMbps(1_000_000L, Locale.US))
        assertEquals("48.7", formatMbps(6_087_500L, Locale.US))
        assertEquals("0.0", formatMbps(-5_000L, Locale.US))
    }

    @Test
    fun `connection duration is a real clock`() {
        assertEquals("00:00:00", formatConnectionDuration(0L))
        assertEquals("00:12:48", formatConnectionDuration(768_000L))
        assertEquals("02:00:00", formatConnectionDuration(7_200_000L))
        assertEquals("00:00:00", formatConnectionDuration(-1_000L))
    }

    @Test
    fun `sparkline stays empty instead of inventing a curve`() {
        assertTrue(sparklineFractions(emptyList()).isEmpty())
        assertEquals(listOf(1f), sparklineFractions(listOf(0L)))
        assertEquals(listOf(0.5f, 1f, 0f), sparklineFractions(listOf(50L, 100L, 0L)))
    }

    // ---------- confidentialité de la configuration ----------

    @Test
    fun `a readable alias is displayed as is`() {
        val fallback = "Aucune configuration sélectionnée"
        assertEquals("France - Paris", publicServerAlias("France - Paris", fallback))
        assertEquals("US Server 1", publicServerAlias("US Server 1", fallback))
        assertEquals("v1.7.2", publicServerAlias("v1.7.2", fallback))
        assertEquals(fallback, publicServerAlias("   ", fallback))
        assertEquals(fallback, publicServerAlias(null, fallback))
    }

    @Test
    fun `connection data is never displayed as an alias`() {
        val fallback = "Configurations masquées"
        listOf(
            "vless://uuid@example.org:443",
            "1.2.3.4:8388",
            "tr-1.stunnel.cc",
            "node.example.com:8444",
            "d0e2f8a8-1c1f-4f0a-9d3e-7c9b2a1f0e11",
            "2001:db8::1",
            "user:secret@host",
        ).forEach { raw ->
            assertEquals(fallback, publicServerAlias(raw, fallback), "fuite pour: $raw")
            assertTrue(looksLikeConnectionData(raw), "non détecté: $raw")
        }
    }

    @Test
    fun `the flag follows the public alias only`() {
        assertEquals("🇫🇷", flagForAlias("France - Paris"))
        assertEquals("🇺🇸", flagForAlias("USA - New York"))
        assertEquals("🇺🇦", flagForAlias("Ukraine - Kyiv"))
        assertEquals("🌐", flagForAlias("Mon serveur"))
        assertEquals("🌐", flagForAlias(null))
    }

    @Test
    fun `country keyword matching respects word boundaries`() {
        // « uk » ne doit pas être trouvé dans « Ukraine », ni « inde » dans « Indesign ».
        assertEquals("🇺🇦", flagForAlias("Ukraine"))
        assertEquals("🌐", flagForAlias("Indesign"))
        assertFalse(looksLikeConnectionData("France - Paris"))
    }
}
