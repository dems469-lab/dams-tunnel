package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.SessionRttSample
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
        assertEquals(ConnectionQuality.Unavailable, evaluateConnectionQuality(false, metrics()))
        // Connecté mais sans aucune mesure : on n'invente pas d'évaluation.
        assertEquals(ConnectionQuality.Unavailable, evaluateConnectionQuality(true, metrics()))
        // Trop peu de mesures pour être fiable.
        assertEquals(
            ConnectionQuality.Unavailable,
            evaluateConnectionQuality(true, metrics(rtt = 20L, samples = 2)),
        )
    }

    @Test
    fun `quality thresholds use the measured latency`() {
        assertEquals(ConnectionQuality.Excellent, evaluateConnectionQuality(true, metrics(rtt = 12L)))
        assertEquals(ConnectionQuality.Excellent, evaluateConnectionQuality(true, metrics(rtt = 52L)))
        assertEquals(ConnectionQuality.Good, evaluateConnectionQuality(true, metrics(rtt = 90L)))
        assertEquals(ConnectionQuality.Fair, evaluateConnectionQuality(true, metrics(rtt = 180L)))
        assertEquals(ConnectionQuality.Weak, evaluateConnectionQuality(true, metrics(rtt = 600L)))
    }

    @Test
    fun `high throughput never hides a high latency`() {
        val highLatency = metrics(rtt = 300L, samples = 6)
        assertEquals(
            ConnectionQuality.Fair,
            evaluateConnectionQuality(true, highLatency, downlinkStability = 1.0, uplinkStability = 1.0),
        )
    }

    @Test
    fun `jitter and loss degrade the evaluation`() {
        val clean = metrics(rtt = 50L, jitter = 5L, loss = 0.0, continuity = 100.0)
        val unstable = metrics(rtt = 50L, jitter = 120L, loss = 30.0, continuity = 80.0)
        assertEquals(ConnectionQuality.Excellent, evaluateConnectionQuality(true, clean))
        assertEquals(ConnectionQuality.Weak, evaluateConnectionQuality(true, unstable))
    }

    @Test
    fun `unstable throughput degrades the evaluation`() {
        val stable = metrics(rtt = 70L)
        assertEquals(
            ConnectionQuality.Good,
            evaluateConnectionQuality(true, stable, downlinkStability = 0.9, uplinkStability = 0.8),
        )
        assertEquals(
            ConnectionQuality.Fair,
            evaluateConnectionQuality(true, stable, downlinkStability = 0.05, uplinkStability = 0.9),
        )
    }

    @Test
    fun `session tracker aggregates real samples`() {
        val tracker = SessionMetricsTracker()
        assertEquals(SessionMetrics(), tracker.snapshot())

        var last = SessionMetrics()
        listOf(40L, 60L, 80L).forEach { rtt ->
            last = tracker.add(sample(rtt))
        }
        assertEquals(3, last.rttSampleCount)
        assertEquals(80L, last.lastRttMillis)
        assertEquals(60L, last.averageRttMillis)
        // Jitter moyen : (|60-40| + |80-60|) / 2 = 20
        assertEquals(20L, last.jitterMillis)
        // Moins de 5 sondes : la perte n'est pas encore significative.
        assertEquals(null, last.lossPercent)
        assertEquals(3, last.probeCount)

        tracker.reset()
        assertEquals(SessionMetrics(), tracker.snapshot())
    }

    @Test
    fun `session tracker reports loss and continuity from probe outcomes`() {
        val tracker = SessionMetricsTracker()
        repeat(8) { tracker.add(sample(50L)) }
        tracker.add(SessionRttSample(-1L, 1L, success = false))
        tracker.add(SessionRttSample(-1L, 2L, success = false))
        val metrics = tracker.snapshot()

        assertEquals(10, metrics.probeCount)
        // Fenêtre de perte = 10 sondes, dont 2 échecs.
        assertEquals(20.0, metrics.lossPercent)
        // Continuité calculée sur les mêmes 10 sondes (< 10 exige 10 minimum).
        assertEquals(80.0, metrics.continuityPercent)
        // Les échecs n'entrent pas dans la moyenne de latence.
        assertEquals(50L, metrics.averageRttMillis)
    }

    @Test
    fun `session tracker keeps a bounded window`() {
        val tracker = SessionMetricsTracker(rttWindow = 4, lossWindow = 4, continuityWindow = 4)
        repeat(10) { index -> tracker.add(sample(50L + index)) }
        val metrics = tracker.snapshot()
        assertEquals(4, metrics.probeCount)
        assertEquals(4, metrics.rttSampleCount)
        assertEquals(59L, metrics.lastRttMillis)
        assertEquals(58L, metrics.averageRttMillis)
    }

    @Test
    fun `throughput stability needs enough active samples`() {
        assertEquals(null, throughputStability(listOf(0L, 0L, 10L)))
        val steady = List(6) { 1_000_000L }
        assertEquals(1.0, throughputStability(steady)!!, 0.001)
        val erratic = listOf(100L, 1_000_000L, 50L, 900_000L, 10L, 800_000L)
        val value = throughputStability(erratic)!!
        assertTrue(value < 0.35, "débit instable mal évalué : $value")
        // Un tunnel au repos n'est ni stable ni instable : mesure inconnue.
        assertEquals(null, throughputStability(List(10) { 0L }))
    }

    private fun sample(rtt: Long) = SessionRttSample(rtt, rtt, success = true)

    private fun metrics(
        rtt: Long? = null,
        samples: Int = 5,
        jitter: Long? = null,
        loss: Double? = null,
        continuity: Double? = null,
    ) = SessionMetrics(
        probeCount = samples,
        rttSampleCount = if (rtt == null) 0 else samples,
        lastRttMillis = rtt,
        averageRttMillis = rtt,
        jitterMillis = jitter,
        lossPercent = loss,
        continuityPercent = continuity,
        updatedAtMillis = 1L,
    )

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
        assertEquals(listOf(0f), sparklineFractions(listOf(0L)))
        assertEquals(listOf(1f), sparklineFractions(listOf(120_000L)))
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
