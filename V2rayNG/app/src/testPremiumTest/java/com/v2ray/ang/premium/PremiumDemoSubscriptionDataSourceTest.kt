package com.v2ray.ang.premium

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PremiumDemoSubscriptionDataSourceTest {
    @Test
    fun `test variant source simulates active expired and exhausted quota without network`() {
        val source = PremiumDemoSubscriptionDataSource(nowEpochSeconds = { 1_800_000_000L })

        assertEquals(
            PremiumSubscriptionStatus.ACTIVE,
            PremiumSubscriptionInfoParser.status(source.subscriptionInfo(), 1_800_000_000L),
        )

        source.selectScenario(PremiumDemoScenario.EXPIRED)
        assertEquals(true, source.expiryState().isExpired)
        assertEquals(
            PremiumSubscriptionStatus.EXPIRED,
            PremiumSubscriptionInfoParser.status(source.subscriptionInfo(), 1_800_000_000L),
        )

        source.selectScenario(PremiumDemoScenario.QUOTA_EXHAUSTED)
        assertEquals(
            PremiumSubscriptionStatus.QUOTA_EXHAUSTED,
            PremiumSubscriptionInfoParser.status(source.subscriptionInfo(), 1_800_000_000L),
        )
    }
}
