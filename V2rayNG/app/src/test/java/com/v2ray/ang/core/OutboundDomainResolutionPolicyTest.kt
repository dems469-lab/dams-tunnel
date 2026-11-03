package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutboundDomainResolutionPolicyTest {
    @Test
    fun `policy group member tags are skipped`() {
        val groups = setOf("proxy")
        assertTrue(CoreConfigManager.isPolicyGroupMemberTag("proxy-proxy-1-Paris", groups))
        assertTrue(CoreConfigManager.isPolicyGroupMemberTag("proxy-proxy-12-Tokyo", groups))
    }

    @Test
    fun `the primary outbound and unrelated tags are resolved`() {
        val groups = setOf("proxy")
        assertFalse(CoreConfigManager.isPolicyGroupMemberTag("proxy", groups))
        assertFalse(CoreConfigManager.isPolicyGroupMemberTag("proxy-chain-1", groups))
        assertFalse(CoreConfigManager.isPolicyGroupMemberTag("proxy-other-1-Paris", groups))
    }

    @Test
    fun `no group means every tag is eligible`() {
        assertFalse(CoreConfigManager.isPolicyGroupMemberTag("proxy-anything-1-Paris", emptySet()))
        assertFalse(CoreConfigManager.isPolicyGroupMemberTag(null, setOf("proxy")))
        assertFalse(CoreConfigManager.isPolicyGroupMemberTag("   ", setOf("proxy")))
    }
}
