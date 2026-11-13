package com.v2ray.ang.reconfig.data

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ConfigFingerprintTest {

    private fun vless(): ProfileItem = ProfileItem.create(EConfigType.VLESS).apply {
        remarks = "Maxit"
        server = "maxit-mobile.orange.bf.asia1.fufo.org"
        serverPort = "443"
        password = "2d3bf640-c3f0-11f1-b838-205c6d5f5d78"
        method = "none"
        network = "ws"
        security = "tls"
        sni = "maxit-mobile.orange.bf"
        host = "Maxit-mobile.orange.bf"
        path = "/trojanws"
    }

    @Test
    fun `label date and group do not change identity`() {
        val original = vless()
        val renamed = vless().apply {
            remarks = "Autre libellé"
            addedTime = 42L
            subscriptionId = "un-groupe"
            policyGroupSubscriptionId = "autre"
        }

        assertEquals(ConfigFingerprint.of(original), ConfigFingerprint.of(renamed))
    }

    @Test
    fun `identity is stable when the key order changes`() {
        val first = vless()
        val second = vless().apply {
            host = "Maxit-mobile.orange.bf"
            path = "/trojanws"
        }

        assertNotNull(ConfigFingerprint.of(first))
        assertEquals(ConfigFingerprint.of(first), ConfigFingerprint.of(second))
    }

    @Test
    fun `flow difference is a different configuration`() {
        val withoutFlow = vless()
        val withFlow = vless().apply { flow = "xtls-rprx-vision" }

        assertNotEquals(ConfigFingerprint.of(withoutFlow), ConfigFingerprint.of(withFlow))
    }

    @Test
    fun `transport tls and host differences are preserved`() {
        val base = ConfigFingerprint.of(vless())
        val otherSni = ConfigFingerprint.of(vless().apply { sni = "autre.example.com" })
        val otherPath = ConfigFingerprint.of(vless().apply { path = "/autre" })
        val otherNetwork = ConfigFingerprint.of(vless().apply { network = "grpc" })
        val otherAlpn = ConfigFingerprint.of(vless().apply { alpn = "h2,http/1.1" })
        val otherFingerprint = ConfigFingerprint.of(vless().apply { fingerPrint = "chrome" })

        assertNotEquals(base, otherSni)
        assertNotEquals(base, otherPath)
        assertNotEquals(base, otherNetwork)
        assertNotEquals(base, otherAlpn)
        assertNotEquals(base, otherFingerprint)
    }

    @Test
    fun `address port and credential differences are preserved`() {
        val base = ConfigFingerprint.of(vless())
        val otherServer = ConfigFingerprint.of(vless().apply { server = "api-connect.orange.bf" })
        val otherPort = ConfigFingerprint.of(vless().apply { serverPort = "8443" })
        val otherUuid = ConfigFingerprint.of(vless().apply { password = "11111111-2222-3333-4444-555555555555" })

        assertNotEquals(base, otherServer)
        assertNotEquals(base, otherPort)
        assertNotEquals(base, otherUuid)
    }

    @Test
    fun `unsupported protocol has no outbound identity and uses a distinct fallback`() {
        val shadowsocks = ProfileItem.create(EConfigType.SHADOWSOCKS).apply {
            server = "example.com"
            serverPort = "8388"
            password = "secret"
            method = "aes-128-gcm"
        }

        assertNull(ConfigFingerprint.of(shadowsocks))
        assertEquals(
            ConfigFingerprint.ofFallback(shadowsocks),
            ConfigFingerprint.ofFallback(shadowsocks.copy(remarks = "renommé")),
        )
        assertNotEquals(
            ConfigFingerprint.ofFallback(shadowsocks),
            ConfigFingerprint.ofFallback(shadowsocks.copy(server = "autre.example.com")),
        )
    }

    @Test
    fun `trojan and vmess produce their own identity`() {
        val trojan = ProfileItem.create(EConfigType.TROJAN).apply {
            server = "maxit-mobile.orange.bf.asia1.fufo.org"
            serverPort = "443"
            password = "secret-trojan"
            network = "ws"
            security = "tls"
            sni = "maxit-mobile.orange.bf"
            path = "/trojanws"
        }
        val vmess = ProfileItem.create(EConfigType.VMESS).apply {
            server = "maxit-mobile.orange.bf.asia1.fufo.org"
            serverPort = "443"
            password = "2d3bf640-c3f0-11f1-b838-205c6d5f5d78"
            method = "auto"
            network = "ws"
            security = "tls"
            sni = "maxit-mobile.orange.bf"
            path = "/trojanws"
        }

        assertNotNull(ConfigFingerprint.of(trojan))
        assertNotNull(ConfigFingerprint.of(vmess))
        assertNotEquals(ConfigFingerprint.of(trojan), ConfigFingerprint.of(vmess))
    }
}
