package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainDrawerItemsTest {
    @Test
    fun `premium entry is hidden when feature gate is off`() {
        assertFalse(drawerItems(showPremium = false).contains(MainDestination.PremiumSubscription))
    }

    @Test
    fun `premium entry appears immediately below subscriptions and leaves other order intact`() {
        val defaultItems = drawerItems(showPremium = false)
        val premiumItems = drawerItems(showPremium = true)

        assertEquals(MainDestination.Subscriptions, premiumItems[0])
        assertEquals(MainDestination.PremiumSubscription, premiumItems[1])
        assertTrue(premiumItems.containsAll(defaultItems))
        assertEquals(defaultItems, premiumItems.filterNot { it == MainDestination.PremiumSubscription })
    }
}
