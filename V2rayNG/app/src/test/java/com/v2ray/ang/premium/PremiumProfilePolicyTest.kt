package com.v2ray.ang.premium

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PremiumProfilePolicyTest {
    @Test
    fun premiumGroupIsAppendedOnlyWhenGateAndProfilesExist() {
        val freeGroups = listOf("default", "sub-a", "sub-b")
        assertEquals(freeGroups, PremiumProfilePolicy.appendVisibleGroup(freeGroups, "premium", false, 2))
        assertEquals(freeGroups, PremiumProfilePolicy.appendVisibleGroup(freeGroups, "premium", true, 0))
        assertEquals(
            listOf("default", "sub-a", "sub-b", "premium"),
            PremiumProfilePolicy.appendVisibleGroup(freeGroups, "premium", true, 1),
        )
    }

    @Test
    fun expirationBlocksOnlyPremiumProfilesAndDoesNotDisableFreeSharing() {
        assertTrue(PremiumProfilePolicy.blocksConnection(PremiumGroup.ID, subscriptionExpired = true))
        assertFalse(PremiumProfilePolicy.blocksConnection("free-sub", subscriptionExpired = true))
        assertFalse(PremiumProfilePolicy.blocksConnection(PremiumGroup.ID, subscriptionExpired = false))
        assertFalse(PremiumProfilePolicy.isShareAllowed(PremiumGroup.ID))
        assertTrue(PremiumProfilePolicy.isShareAllowed("free-sub"))
        assertTrue(PremiumProfilePolicy.isShareAllowed(null))
    }

}
