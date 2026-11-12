package com.v2ray.ang.laboratory.multipath

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerQualityAnalyzerTest {

    private fun sample(latency: Long, success: Boolean = true, at: Long = 0L) =
        PathSample(latencyMillis = latency, success = success, atMillis = at)

    @Test
    fun `no sample means no data and idle state`() {
        val metrics = ServerQualityAnalyzer.analyze(emptyList())
        assertFalse(metrics.hasData)
        assertEquals(0, metrics.sampleCount)
        assertEquals(PathLinkState.IDLE, ServerQualityAnalyzer.resolveState(metrics, false, false))
    }

    @Test
    fun `stable fast path is healthy`() {
        val metrics = ServerQualityAnalyzer.analyze(List(10) { sample(80) })
        assertEquals(0, metrics.lossPercent)
        assertEquals(100, metrics.availabilityPercent)
        assertEquals(100, metrics.stabilityPercent)
        assertEquals(80, metrics.latencyMillis)
        assertTrue(metrics.isUsable)
        assertTrue(metrics.qualityScore >= 80)
        assertEquals(metrics.availabilityPercent, metrics.throughputIndex)
    }

    @Test
    fun `total failure is unusable`() {
        val metrics = ServerQualityAnalyzer.analyze(List(10) { sample(-1, success = false) })
        assertEquals(100, metrics.lossPercent)
        assertEquals(0, metrics.availabilityPercent)
        assertEquals(0, metrics.qualityScore)
        assertEquals(-1L, metrics.latencyMillis)
        assertFalse(metrics.isUsable)
        assertEquals(PathLinkState.UNAVAILABLE, ServerQualityAnalyzer.resolveState(metrics, true, true))
    }

    @Test
    fun `half loss reduces availability and quality`() {
        val samples = List(10) { index -> sample(120, success = index % 2 == 0) }
        val metrics = ServerQualityAnalyzer.analyze(samples)
        assertEquals(50, metrics.availabilityPercent)
        assertEquals(50, metrics.lossPercent)
        assertTrue(metrics.isUsable)
        assertTrue(metrics.qualityScore < 100)
    }

    @Test
    fun `high latency scores lower than fast latency`() {
        val fast = ServerQualityAnalyzer.analyze(List(10) { sample(90) })
        val slow = ServerQualityAnalyzer.analyze(List(10) { sample(1500) })
        assertTrue(slow.qualityScore < fast.qualityScore)
    }

    @Test
    fun `jitter lowers stability`() {
        val stable = ServerQualityAnalyzer.analyze(List(10) { sample(100) })
        val jittery = ServerQualityAnalyzer.analyze(
            listOf(60, 400, 70, 420, 80, 380, 90, 410, 100, 390).map { sample(it.toLong()) },
        )
        assertTrue(jittery.stabilityPercent < stable.stabilityPercent)
    }

    @Test
    fun `state reflects selection and preference`() {
        val metrics = ServerQualityAnalyzer.analyze(List(5) { sample(200) })
        assertEquals(PathLinkState.CARRYING, ServerQualityAnalyzer.resolveState(metrics, true, true))
        assertEquals(PathLinkState.READY, ServerQualityAnalyzer.resolveState(metrics, true, false))
        assertEquals(PathLinkState.MONITORED, ServerQualityAnalyzer.resolveState(metrics, false, false))
    }

    @Test
    fun `window keeps only the most recent samples`() {
        val samples = List(10) { sample(-1, success = false, at = it.toLong()) } +
            List(5) { sample(100, success = true, at = 100L + it) }
        val metrics = ServerQualityAnalyzer.analyze(samples, windowSize = 5)
        assertEquals(5, metrics.sampleCount)
        assertEquals(0, metrics.lossPercent)
        assertTrue(metrics.isUsable)
    }
}
