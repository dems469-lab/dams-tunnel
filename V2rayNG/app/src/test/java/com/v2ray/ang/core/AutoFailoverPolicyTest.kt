package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoFailoverPolicyTest {

    // Horodatage réaliste (epoch) : une mesure vieille de 25 h doit rester
    // strictement positive, sinon la fraîcheur serait rejetée pour cause de
    // valeur négative et non pour son âge réel.
    private val now = 1_700_000_000_000L

    private fun snapshot(
        guid: String,
        latencyMillis: Long,
        samples: Int = 3,
        failures: Int = 0,
        ageMillis: Long = 0L,
        lastFailed: Boolean = false,
    ) = ServerHealthSnapshot(
        guid = guid,
        protocol = "VLESS",
        latencyMillis = latencyMillis,
        samples = samples,
        failures = failures,
        lastTestedAtMillis = now - ageMillis,
        lastFailed = lastFailed,
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
            candidates = listOf(snapshot("old", 50L, ageMillis = 25 * 60 * 60_000L)),
            nowMillis = now,
        )

        assertFalse(decision.switches)
    }

    @Test
    fun `a candidate whose last probe failed is not selected`() {
        val decision = AutoFailoverPolicy.decide(
            active = snapshot("active", 900L, lastFailed = true),
            candidates = listOf(snapshot("flaky", 50L, lastFailed = true)),
            nowMillis = now,
        )

        assertFalse(decision.switches)
        assertEquals("no_candidate", decision.reason)
    }

    @Test
    fun `a recent candidate outside the active probe window is still usable`() {
        val decision = AutoFailoverPolicy.decide(
            active = snapshot("active", 2_000L),
            candidates = listOf(snapshot("tested-hours-ago", 100L, ageMillis = 6 * 60 * 60_000L)),
            nowMillis = now,
        )

        assertTrue(decision.switches)
        assertEquals("tested-hours-ago", decision.candidate?.guid)
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

    // ── Lot A2 ───────────────────────────────────────────────────────────────

    @Test
    fun `A2 le cooldown protege aussi un serveur degrade mais utilisable`() {
        // 75 % de disponibilité : le serveur perd des paquets mais transporte
        // encore. Le remplacer toutes les minutes rechargerait le cœur en boucle.
        val decision = AutoFailoverPolicy.decide(
            active = snapshot("active", 300L, samples = 4, failures = 1),
            candidates = listOf(snapshot("candidate", 120L)),
            nowMillis = now,
            lastSwitchAtMillis = now - 1_000L,
        )

        assertFalse(decision.switches)
        assertEquals("cooldown", decision.reason)
    }

    @Test
    fun `A2 une latence inconnue ne gagne jamais une egalite de score`() {
        val decision = AutoFailoverPolicy.decide(
            active = snapshot("active", 900L, samples = 4, failures = 2),
            candidates = listOf(
                snapshot("unknown", -1L),
                snapshot("slow-but-measured", 4_000L),
            ),
            nowMillis = now,
        )

        assertTrue(decision.switches)
        assertEquals("slow-but-measured", decision.candidate?.guid)
    }

    @Test
    fun `A2 un candidat sous le seuil de disponibilite est ignore`() {
        val decision = AutoFailoverPolicy.decide(
            active = snapshot("active", 900L, samples = 4, failures = 2),
            candidates = listOf(snapshot("half-down", 60L, samples = 100, failures = 51)),
            nowMillis = now,
        )

        assertFalse(decision.switches)
        assertEquals("no_candidate", decision.reason)
    }

    @Test
    fun `A2 un serveur actif jamais mesure autorise un sauvetage immediat`() {
        val decision = AutoFailoverPolicy.decide(
            active = snapshot("active", -1L, samples = 0),
            candidates = listOf(snapshot("candidate", 100L)),
            nowMillis = now,
            lastSwitchAtMillis = now - 1_000L,
        )

        assertTrue(decision.switches)
        assertEquals("active_degraded", decision.reason)
    }

    @Test
    fun `A2 une mesure active perimee leve le cooldown`() {
        // Surveillance morte depuis 11 min : le serveur n'est plus prouvé
        // utilisable, donc le cooldown ne doit pas retarder le sauvetage.
        val decision = AutoFailoverPolicy.decide(
            active = snapshot("active", 300L, samples = 4, failures = 1, ageMillis = 11 * 60_000L),
            candidates = listOf(snapshot("candidate", 200L)),
            nowMillis = now,
            lastSwitchAtMillis = now - 1_000L,
        )

        assertTrue(decision.switches)
        assertEquals("active_degraded", decision.reason)
    }

    @Test
    fun `A2 la bascule au demarrage exige deux passes confirmees et un relais vivant`() {
        assertTrue(AutoFailoverPolicy.shouldAttemptFailover(2, relayAlive = true))
        assertFalse(AutoFailoverPolicy.shouldAttemptFailover(1, relayAlive = true))
        assertFalse(AutoFailoverPolicy.shouldAttemptFailover(0, relayAlive = true))
        // Un relais TUN mort ne se répare pas en changeant de serveur.
        assertFalse(AutoFailoverPolicy.shouldAttemptFailover(2, relayAlive = false))
    }
}
