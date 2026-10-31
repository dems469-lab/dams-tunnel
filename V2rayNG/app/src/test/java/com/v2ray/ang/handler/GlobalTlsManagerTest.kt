package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlobalTlsManagerTest {
    private fun profile(type: EConfigType, security: String? = null) =
        ProfileItem.create(type).apply { this.security = security }

    @Test
    fun `only tls capable protocols are considered`() {
        assertTrue(GlobalTlsManager.supportsGlobalTls(EConfigType.VMESS))
        assertTrue(GlobalTlsManager.supportsGlobalTls(EConfigType.VLESS))
        assertTrue(GlobalTlsManager.supportsGlobalTls(EConfigType.TROJAN))
        assertFalse(GlobalTlsManager.supportsGlobalTls(EConfigType.SHADOWSOCKS))
        assertFalse(GlobalTlsManager.supportsGlobalTls(EConfigType.WIREGUARD))
        assertFalse(GlobalTlsManager.supportsGlobalTls(EConfigType.SOCKS))
        assertFalse(GlobalTlsManager.supportsGlobalTls(EConfigType.HTTP))
        assertFalse(GlobalTlsManager.supportsGlobalTls(EConfigType.HYSTERIA2))
        assertFalse(GlobalTlsManager.supportsGlobalTls(EConfigType.CUSTOM))
        assertFalse(GlobalTlsManager.supportsGlobalTls(EConfigType.POLICYGROUP))
        assertFalse(GlobalTlsManager.supportsGlobalTls(EConfigType.PROXYCHAIN))
    }

    @Test
    fun `enabling adds tls to a plain profile`() {
        val item = profile(EConfigType.VLESS)
        assertTrue(GlobalTlsManager.apply(item, enabled = true))
        assertEquals("tls", item.security)
    }

    @Test
    fun `enabling keeps an existing transport security`() {
        val item = profile(EConfigType.VLESS, "reality")
        assertFalse(GlobalTlsManager.apply(item, enabled = true))
        assertEquals("reality", item.security)
    }

    @Test
    fun `disabling removes plain tls`() {
        val item = profile(EConfigType.VMESS, "tls")
        assertTrue(GlobalTlsManager.apply(item, enabled = false))
        assertEquals("", item.security)
    }

    @Test
    fun `disabling leaves trojan untouched because it needs tls`() {
        val item = profile(EConfigType.TROJAN, "tls")
        assertFalse(GlobalTlsManager.apply(item, enabled = false))
        assertEquals("tls", item.security)
    }

    @Test
    fun `disabling leaves reality and xtls untouched`() {
        val reality = profile(EConfigType.VLESS, "reality")
        val xtls = profile(EConfigType.VMESS, "xtls")
        assertFalse(GlobalTlsManager.apply(reality, enabled = false))
        assertFalse(GlobalTlsManager.apply(xtls, enabled = false))
        assertEquals("reality", reality.security)
        assertEquals("xtls", xtls.security)
    }

    @Test
    fun `incompatible protocols are never modified`() {
        val item = profile(EConfigType.SHADOWSOCKS)
        assertFalse(GlobalTlsManager.apply(item, enabled = true))
        assertNull(item.security)
    }
}
