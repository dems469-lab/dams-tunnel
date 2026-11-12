package com.v2ray.ang.laboratory.multipath

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstantFailoverManagerTest {

    private fun health(
        role: PathRole,
        quality: Int,
        latency: Long,
        failures: Int = 0,
        samples: Int = 4,
    ): PathHealth = PathHealth(
        role = role,
        tag = role.tag,
        guid = "guid-${role.shortLabel}",
        remarks = "Server ${role.shortLabel}",
        protocol = "VLESS",
        state = PathLinkState.READY,
        metrics = PathMetrics(
            latencyMillis = if (samples == 0) -1L else latency,
            lossPercent = if (samples == 0) 100 else 0,
            availabilityPercent = if (samples == 0) 0 else 100,
            stabilityPercent = if (samples == 0) 0 else 90,
            qualityScore = if (samples == 0) 0 else quality,
            throughputIndex = if (samples == 0) 0 else quality,
            sampleCount = samples,
        ),
        consecutiveFailures = failures,
        consecutiveSuccesses = if (failures == 0) 3 else 0,
    )

    private fun dead(role: PathRole) = health(role, quality = 0, latency = -1, failures = 3, samples = 4)

    @Test
    fun `first decision falls back to the primary when nothing is usable`() {
        val manager = InstantFailoverManager(cooldownMillis = 45_000L)
        val decision = manager.decide(
            health = listOf(dead(PathRole.PRIMARY), dead(PathRole.SECONDARY_B), dead(PathRole.SECONDARY_C)),
            hybridEnabled = true,
            nowMillis = 1_000L,
        )
        assertEquals(listOf("path-a"), decision.selector)
        assertEquals(PathRole.PRIMARY, decision.preferred)
        assertTrue(decision.changed)
    }

    @Test
    fun `hybrid mode selects every healthy path by quality`() {
        val manager = InstantFailoverManager(cooldownMillis = 45_000L)
        val decision = manager.decide(
            health = listOf(
                health(PathRole.PRIMARY, quality = 70, latency = 120),
                health(PathRole.SECONDARY_B, quality = 92, latency = 80),
                health(PathRole.SECONDARY_C, quality = 55, latency = 200),
            ),
            hybridEnabled = true,
            nowMillis = 1_000L,
        )
        assertEquals(listOf("path-b", "path-a", "path-c"), decision.selector)
        assertEquals(PathRole.SECONDARY_B, decision.preferred)
        assertTrue(decision.changed)
    }

    @Test
    fun `cooldown keeps the current routing while the preferred path is healthy`() {
        val manager = InstantFailoverManager(cooldownMillis = 45_000L)
        val first = manager.decide(
            health = listOf(
                health(PathRole.PRIMARY, quality = 60, latency = 150),
                health(PathRole.SECONDARY_B, quality = 55, latency = 180),
                dead(PathRole.SECONDARY_C),
            ),
            hybridEnabled = true,
            nowMillis = 1_000L,
        )
        assertEquals(listOf("path-a", "path-b"), first.selector)

        val second = manager.decide(
            health = listOf(
                health(PathRole.PRIMARY, quality = 60, latency = 150),
                health(PathRole.SECONDARY_B, quality = 95, latency = 70),
                health(PathRole.SECONDARY_C, quality = 85, latency = 90),
            ),
            hybridEnabled = true,
            nowMillis = 10_000L,
        )
        assertFalse(second.changed)
        assertEquals(first.selector, second.selector)
    }

    @Test
    fun `a preferred path failure switches immediately even inside the cooldown`() {
        val manager = InstantFailoverManager(cooldownMillis = 45_000L)
        manager.decide(
            health = listOf(
                health(PathRole.PRIMARY, quality = 90, latency = 80),
                health(PathRole.SECONDARY_B, quality = 60, latency = 150),
                health(PathRole.SECONDARY_C, quality = 50, latency = 200),
            ),
            hybridEnabled = true,
            nowMillis = 1_000L,
        )

        val switched = manager.decide(
            health = listOf(
                dead(PathRole.PRIMARY),
                health(PathRole.SECONDARY_B, quality = 60, latency = 150),
                health(PathRole.SECONDARY_C, quality = 50, latency = 200),
            ),
            hybridEnabled = true,
            nowMillis = 5_000L,
        )
        assertTrue(switched.changed)
        assertEquals(listOf("path-b", "path-c"), switched.selector)
        assertEquals(PathRole.SECONDARY_B, switched.preferred)
    }

    @Test
    fun `strict mode keeps only the primary while it is healthy`() {
        val manager = InstantFailoverManager(cooldownMillis = 45_000L)
        val decision = manager.decide(
            health = listOf(
                health(PathRole.PRIMARY, quality = 40, latency = 300),
                health(PathRole.SECONDARY_B, quality = 95, latency = 70),
                health(PathRole.SECONDARY_C, quality = 90, latency = 75),
            ),
            hybridEnabled = false,
            nowMillis = 1_000L,
        )
        assertEquals(listOf("path-a"), decision.selector)
        assertEquals(PathRole.PRIMARY, decision.preferred)
    }

    @Test
    fun `strict mode fails over to the two best fallbacks when the primary dies`() {
        val manager = InstantFailoverManager(cooldownMillis = 45_000L)
        val decision = manager.decide(
            health = listOf(
                dead(PathRole.PRIMARY),
                health(PathRole.SECONDARY_B, quality = 60, latency = 150),
                health(PathRole.SECONDARY_C, quality = 90, latency = 70),
            ),
            hybridEnabled = false,
            nowMillis = 1_000L,
        )
        assertEquals(listOf("path-c", "path-b"), decision.selector)
        assertEquals(PathRole.SECONDARY_C, decision.preferred)
    }

    @Test
    fun `reset forgets the previous decision`() {
        val manager = InstantFailoverManager(cooldownMillis = 45_000L)
        manager.decide(
            health = listOf(health(PathRole.PRIMARY, quality = 90, latency = 80)),
            hybridEnabled = true,
            nowMillis = 1_000L,
        )
        manager.reset()
        val decision = manager.decide(
            health = listOf(health(PathRole.PRIMARY, quality = 90, latency = 80)),
            hybridEnabled = true,
            nowMillis = 2_000L,
        )
        assertTrue(decision.changed)
    }
}
