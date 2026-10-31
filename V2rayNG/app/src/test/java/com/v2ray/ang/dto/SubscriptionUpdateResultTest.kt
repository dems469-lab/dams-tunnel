package com.v2ray.ang.dto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SubscriptionUpdateResultTest {
    @Test
    fun `combines valid ignored and reason counts across sources`() {
        val combined = SubscriptionUpdateResult(
            configCount = 4,
            ignoredConfigCount = 3,
            ignoredReasons = mapOf("duplicate" to 2, "invalid" to 1),
        ) + SubscriptionUpdateResult(
            configCount = 5,
            ignoredConfigCount = 2,
            ignoredReasons = mapOf("duplicate" to 1, "unsupported" to 1),
        )

        assertEquals(9, combined.configCount)
        assertEquals(5, combined.ignoredConfigCount)
        assertEquals(3, combined.ignoredReasons["duplicate"])
        assertEquals(1, combined.ignoredReasons["invalid"])
        assertEquals(1, combined.ignoredReasons["unsupported"])
    }
}
