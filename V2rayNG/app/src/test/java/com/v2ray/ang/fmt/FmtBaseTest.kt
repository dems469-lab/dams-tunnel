package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FmtBaseTest {
    @Test
    fun `query values split at first equals and decode encoded grpc parameters`() {
        val query = FmtBase().getQueryParam(java.net.URI("vless://id@example.com:443?serviceName=a%3Db%26c&host=ex%3Dample"))

        assertEquals("a=b&c", query["serviceName"])
        assertEquals("ex=ample", query["host"])
    }

    @Test
    fun `VMess standard URI with a single query parameter is not treated as base64`() {
        val profile = VmessFmt.parse("vmess://id@example.com:443?type=ws")

        assertEquals("ws", profile?.network)
        assertEquals("example.com", profile?.server)
    }

    @Test
    fun `VMess TLS websocket fields and emoji remark are decoded`() {
        val profile = VmessFmt.parse(
            "vmess://id@example.com:443?type=ws&security=tls&sni=sni.example&host=cdn.example&path=%2Fws#Test%20%F0%9F%9A%80"
        )

        assertEquals("ws", profile?.network)
        assertEquals("tls", profile?.security)
        assertEquals("sni.example", profile?.sni)
        assertEquals("cdn.example", profile?.host)
        assertEquals("/ws", profile?.path)
        assertEquals("Test 🚀", profile?.remarks)
    }

    @Test
    fun `VMess gRPC maps service name authority and TLS SNI`() {
        val profile = VmessFmt.parse(
            "vmess://id@example.com:443?type=grpc&serviceName=grpc%3Dservice&authority=cdn.example&security=tls&sni=sni.example#VMess"
        )

        assertEquals("grpc", profile?.network)
        assertEquals("grpc=service", profile?.serviceName)
        assertEquals("cdn.example", profile?.authority)
        assertEquals("sni.example", profile?.sni)
    }

    @Test
    fun `Trojan websocket preserves path host TLS and encoded emoji name`() {
        val profile = TrojanFmt.parse(
            "trojan://secret@example.com:443?type=ws&path=%2Fsocket&host=cdn.example&security=tls&sni=sni.example#Trojan%20%E2%9C%A8"
        )

        assertEquals("ws", profile?.network)
        assertEquals("/socket", profile?.path)
        assertEquals("cdn.example", profile?.host)
        assertEquals("tls", profile?.security)
        assertEquals("sni.example", profile?.sni)
        assertEquals("Trojan ✨", profile?.remarks)
    }

    @Test
    fun `Trojan gRPC preserves equals signs in encoded service name and TLS SNI`() {
        val profile = TrojanFmt.parse(
            "trojan://secret@example.com:443?type=grpc&serviceName=svc%3Da%3Db&security=tls&sni=sni.example#Serveur%20🚀"
        )

        assertEquals("grpc", profile.network)
        assertEquals("svc=a=b", profile.serviceName)
        assertEquals("tls", profile.security)
        assertEquals("sni.example", profile.sni)
        assertEquals("Serveur 🚀", profile.remarks)
    }

    @Test
    fun `missing insecure query defaults to certificate verification`() {
        val profile = ProfileItem.create(EConfigType.VLESS)

        FmtBase().getItemFormQuery(profile, mapOf("security" to AppConfig.TLS))

        assertFalse(profile.insecure == true)
    }

    @Test
    fun `explicit insecure query remains enabled`() {
        val profile = ProfileItem.create(EConfigType.VLESS)

        FmtBase().getItemFormQuery(profile, mapOf("security" to AppConfig.TLS, "insecure" to "1"))

        assertTrue(profile.insecure == true)
    }

    @Test
    fun `vmess URI without insecure parameter verifies certificates`() {
        val profile = VmessFmt.parseVmessStd("vmess://user@example.com:443?type=ws&security=tls")

        assertFalse(profile?.insecure == true)
    }

    @Test
    fun `trojan URI without query verifies certificates`() {
        val profile = TrojanFmt.parse("trojan://secret@example.com:443")

        assertFalse(profile.insecure == true)
    }
}
