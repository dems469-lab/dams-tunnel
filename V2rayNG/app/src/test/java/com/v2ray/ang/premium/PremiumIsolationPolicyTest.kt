package com.v2ray.ang.premium

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PremiumIsolationPolicyTest {
    @Test
    fun `update all subscriptions excludes the dedicated Premium group`() {
        val subscriptions = listOf("free-a", PremiumGroup.ID, "free-b")

        val updateTargets = PremiumIsolationPolicy.filterGeneralCleanup(subscriptions) { it }

        assertEquals(listOf("free-a", "free-b"), updateTargets)
    }

    @Test
    fun `delete all configs retains Premium while deleting ordinary groups`() {
        val profiles = listOf(
            TestProfile("free", "free-subscription"),
            TestProfile("premium", PremiumGroup.ID),
        )

        val retained = PremiumIsolationPolicy.retainPremium(profiles) { it.subscriptionId }
        val generalDeletionTargets = PremiumIsolationPolicy.filterGeneralCleanup(profiles) { it.subscriptionId }

        assertEquals(listOf(TestProfile("premium", PremiumGroup.ID)), retained)
        assertEquals(listOf(TestProfile("free", "free-subscription")), generalDeletionTargets)
    }

    @Test
    fun `duplicate cleanup never includes Premium profiles`() {
        val duplicateCandidates = listOf(
            TestProfile("free-duplicate", "free-subscription"),
            TestProfile("premium-duplicate", PremiumGroup.ID),
        )

        assertEquals(
            listOf(TestProfile("free-duplicate", "free-subscription")),
            PremiumIsolationPolicy.filterGeneralCleanup(duplicateCandidates) { it.subscriptionId },
        )
    }

    @Test
    fun `expiration is confirmed only when every configured endpoint returns expired`() {
        assertEquals(
            PremiumFetchResult.ConfirmedExpired,
            PremiumFetchOutcomePolicy.afterAllEndpoints(
                listOf(PremiumEndpointOutcome.EXPIRED, PremiumEndpointOutcome.EXPIRED),
                endpointCount = 2,
            ),
        )
        assertEquals(
            PremiumFetchResult.Failed,
            PremiumFetchOutcomePolicy.afterAllEndpoints(
                listOf(PremiumEndpointOutcome.EXPIRED, PremiumEndpointOutcome.FAILED),
                endpointCount = 2,
            ),
        )
    }

    @Test
    fun `empty response never confirms expiration`() {
        assertEquals(
            PremiumFetchResult.EmptyResponse,
            PremiumFetchOutcomePolicy.afterAllEndpoints(
                listOf(PremiumEndpointOutcome.EXPIRED, PremiumEndpointOutcome.EMPTY),
                endpointCount = 2,
            ),
        )
    }

    private data class TestProfile(val id: String, val subscriptionId: String)
}
