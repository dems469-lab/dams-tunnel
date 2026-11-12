package com.v2ray.ang.laboratory.multipath

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MultipathStatisticsTest {

    private fun health(
        role: PathRole,
        latency: Long,
        loss: Int,
        quality: Int,
        samples: Int = 4,
    ) = PathHealth(
        role = role,
        tag = role.tag,
        guid = "guid",
        remarks = "Server",
        protocol = "VLESS",
        state = PathLinkState.READY,
        metrics = PathMetrics(
            latencyMillis = latency,
            lossPercent = loss,
            availabilityPercent = 100 - loss,
            stabilityPercent = 90,
            qualityScore = quality,
            throughputIndex = quality,
            sampleCount = samples,
        ),
    )

    @Test
    fun `empty snapshot has no data`() {
        val statistics = MultipathStatistics()
        val snapshot = statistics.snapshot()
        assertEquals(0, snapshot.rounds)
        assertEquals(0, snapshot.samples)
        assertEquals(-1L, snapshot.averageLatencyMillis)
        assertEquals(0, snapshot.relays)
    }

    @Test
    fun `rounds aggregate only paths with data`() {
        val statistics = MultipathStatistics()
        statistics.start(nowMillis = 1_000L)
        statistics.recordRound(
            listOf(
                health(PathRole.PRIMARY, latency = 100, loss = 0, quality = 90),
                health(PathRole.SECONDARY_B, latency = 300, loss = 20, quality = 60),
                health(PathRole.SECONDARY_C, latency = -1, loss = 100, quality = 0, samples = 0),
            ),
            nowMillis = 2_000L,
        )
        val snapshot = statistics.snapshot()
        assertEquals(1, snapshot.rounds)
        assertEquals(2, snapshot.samples)
        assertEquals(200L, snapshot.averageLatencyMillis)
        assertEquals(10, snapshot.averageLossPercent)
        assertEquals(75, snapshot.averageQuality)
        assertEquals(1_000L, snapshot.startedAtMillis)
    }

    @Test
    fun `relay counter is independent from rounds`() {
        val statistics = MultipathStatistics()
        statistics.start()
        statistics.recordRelay()
        statistics.recordRelay()
        assertEquals(2, statistics.snapshot().relays)
        assertTrue(statistics.snapshot().relays > 0)
    }

    @Test
    fun `reset clears every counter`() {
        val statistics = MultipathStatistics()
        statistics.start()
        statistics.recordRound(listOf(health(PathRole.PRIMARY, 100, 0, 90)))
        statistics.recordRelay()
        statistics.reset()
        val snapshot = statistics.snapshot()
        assertEquals(0, snapshot.rounds)
        assertEquals(0, snapshot.relays)
        assertEquals(-1L, snapshot.averageLatencyMillis)
        assertEquals(0L, snapshot.startedAtMillis)
    }
}
