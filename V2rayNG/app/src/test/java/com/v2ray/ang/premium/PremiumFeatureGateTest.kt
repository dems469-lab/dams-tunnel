package com.v2ray.ang.premium

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PremiumFeatureGateTest {
    @Test
    fun `premium requires both build flag and internal setting`() {
        assertFalse(PremiumFeatureGate.isEnabled(buildEnabled = false, internalEnabled = false))
        assertFalse(PremiumFeatureGate.isEnabled(buildEnabled = false, internalEnabled = true))
        assertFalse(PremiumFeatureGate.isEnabled(buildEnabled = true, internalEnabled = false))
        assertTrue(PremiumFeatureGate.isEnabled(buildEnabled = true, internalEnabled = true))
    }
}
