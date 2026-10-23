package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultServerFailoverPolicyTest {
    private val defaultGroup = "__default_subscription__"

    @Test
    fun `disabled option never changes the selected server`() {
        assertFalse(shouldTryDefaultServerFailover(false, defaultGroup, defaultGroup))
    }

    @Test
    fun `enabled option may fail over only inside default group`() {
        assertTrue(shouldTryDefaultServerFailover(true, defaultGroup, defaultGroup))
        assertFalse(shouldTryDefaultServerFailover(true, "custom-subscription", defaultGroup))
        assertFalse(shouldTryDefaultServerFailover(true, null, defaultGroup))
    }
}
