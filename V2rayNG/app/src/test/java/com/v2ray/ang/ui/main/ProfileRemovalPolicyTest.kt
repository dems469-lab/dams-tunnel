package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfileRemovalPolicyTest {
    @Test
    fun inactiveVpnAllowsRemovingSelectedOrOtherProfileAndSelectsRemaining() {
        assertFalse(isProtectedActiveProfileRemoval("selected", "selected", MainStatus.Disconnected))
        assertFalse(isProtectedActiveProfileRemoval("other", "selected", MainStatus.Disconnected))
        assertEquals("remaining", selectedGuidAfterRemoval("selected", "selected", listOf("remaining")))
        assertEquals("selected", selectedGuidAfterRemoval("selected", "other", listOf("selected")))
        assertEquals("remaining", selectedGuidAfterRemoval("stale", "other", listOf("remaining")))
    }

    @Test
    fun activeSessionBlocksRemovingItsSelectedProfileOnly() {
        listOf(MainStatus.Connecting, MainStatus.Reconnecting, MainStatus.Connected).forEach { status ->
            assertTrue(isProtectedActiveProfileRemoval("selected", "selected", status))
            assertFalse(isProtectedActiveProfileRemoval("other", "selected", status))
        }
    }

    @Test
    fun removingLastSelectedProfileLeavesNoSelection() {
        assertNull(selectedGuidAfterRemoval("last", "last", emptyList()))
    }

    @Test
    fun serviceCannotStartWithoutASelectedProfile() {
        assertFalse(hasSelectedProfile(null))
        assertFalse(hasSelectedProfile(" "))
        assertTrue(hasSelectedProfile("profile-guid"))
    }
}
