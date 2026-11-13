package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoFailoverPolicyTest {

    private val now = 1_000_000L

    private fun snapshot(
        guid: String,
        latencyMillis: Long,
        samples: Int = 3,
        failures: Int = 0,
        ageMillis: Long = 0L,
    ) = ServerHealthSnapshot(
        guid = guid,
        protocol = "VLESS",
        latencyMillis = latencyMillis,
        samples = samples,
        failures = failures,
        lastTestedAtMillis = now - ageMillis,
    )

    @Test
    fun `score values real measurements`() {
        assertEquals(100, snapshot("a", 100L).score)
        assertEquals(93, snapshot("a", 200L).score)
        assertEquals(65, snapshot("a", 4_000L).score)
        assertEquals(0, snapshot("a", 200L, samples = 0).score)
        assertEquals(0, snapshot("a", 200L, failures = 3).score)
    }

    @Test
    fun `no candidate means no switch`() {
        val decision = AutoFailoverPolicy.decide(snapshot("active", 900L), emptyList(), now)

        assertFalse(decision.switches)
        assertEquals("no_candidate", decision.reason)
    }

    @Test
    fun `server without measurement is never a candidate`() {
        val decision = AutoFailoverPolicy.decide(
            active = snapshot("active", 900L),
            candidates = listOf(snapshot("unknown", 50L, samples = 0)),
            nowMillis = now,
        )

        assertFalse(decision.switches)
    }

    @Test
    fun `stale measurement is ignored`() {
        val decision = AutoFailoverPolicy.decide(
            active = snapshot("active", 2_000L),
            candidates = listOf(snapshot("old", 50L, ageMillis = 11 * 60_000L)),
            nowMillis = now,
        )

        assertFalse(decision.switches)
    }

    @Test
    fun `degraded active switches to a better measured server`() {
        val decision = AutoFailoverPolicy.decide(
            active = snapshot("active", 300L, samples = 4, failures = 2),
            candidates = listOf(snapshot("candidate", 120L)),
            nowMillis = now,
        )

        assertTrue(decision.switches)
        assertEquals("candidate", decision.candidate?.guid)
        assertEquals("active_degraded", decision.reason)
    }

    @Test
    fun `healthy active is kept for a marginal gain`() {
        val decision = AutoFailoverPolicy.decide(
            active = snapshot("active", 200L),
            candidates = listOf(snapshot("candidate", 100L)),
            nowMillis = now,
            lastSwitchAtMillis = 0L,
        )

        assertFalse(decision.switches)
        assertEquals("insufficient_gain", decision.reason)
    }

    @Test
    fun `healthy but slow active switches to a much better server`() {
        val decision = AutoFailoverPolicy.decide(
            active = snapshot("active", 600L),
            candidates = listOf(snapshot("candidate", 100L)),
            nowMillis = now,
        )

        assertTrue(decision.switches)
        assertEquals("better_score", decision.reason)
    }

    @Test
    fun `cooldown blocks a comfort switch but not a rescue`() {
        val comfort = AutoFailoverPolicy.decide(
            active = snapshot("active", 600L),
            candidates = listOf(snapshot("candidate", 100L)),
            nowMillis = now,
            lastSwitchAtMillis = now - 1_000L,
        )
        assertEquals("cooldown", comfort.reason)
        assertNull(comfort.candidate)

        val rescue = AutoFailoverPolicy.decide(
            active = snapshot("active", 300L, samples = 4, failures = 3),
            candidates = listOf(snapshot("candidate", 300L)),
            nowMillis = now,
            lastSwitchAtMillis = now - 1_000L,
        )
        assertTrue(rescue.switches)
        assertEquals("active_degraded", rescue.reason)
    }

    @Test
    fun `best candidate wins on score rather than raw latency`() {
        val decision = AutoFailoverPolicy.decide(
            active = snapshot("active", 300L, samples = 4, failures = 1),
            candidates = listOf(
                snapshot("lossy", 60L, samples = 4, failures = 1),
                snapshot("stable", 140L, samples = 4, failures = 0),
            ),
            nowMillis = now,
        )

        assertTrue(decision.switches)
        assertEquals("stable", decision.candidate?.guid)
    }
}
