package com.v2ray.ang.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XrayJsonConfigTest {

    private fun profile(type: EConfigType = EConfigType.VLESS): ProfileItem =
        ProfileItem.create(type).apply {
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

    private fun outbound(profile: ProfileItem): JsonObject {
        val json = XrayJsonConfig.buildOutboundJson(profile)
        assertNotNull(json)
        return JsonParser.parseString(json).asJsonObject
    }

    private fun stream(outbound: JsonObject): JsonObject =
        outbound.getAsJsonObject("streamSettings")

    private fun tlsServerName(outbound: JsonObject): String =
        stream(outbound).getAsJsonObject("tlsSettings").get("serverName").asString

    @Test
    fun `vless uses vnext with the uuid`() {
        val json = outbound(profile(EConfigType.VLESS))
        val settings = json.getAsJsonObject("settings")

        assertEquals("vless", json.get("protocol").asString)
        assertTrue(settings.has("vnext"))
        assertFalse(settings.has("servers"))

        val user = settings.getAsJsonArray("vnext")[0].asJsonObject
            .getAsJsonArray("users")[0].asJsonObject
        assertEquals("2d3bf640-c3f0-11f1-b838-205c6d5f5d78", user.get("id").asString)
        assertEquals("none", user.get("encryption").asString)
        assertEquals(8, user.get("level").asInt)
    }

    @Test
    fun `vmess uses vnext with alterId`() {
        val json = outbound(profile(EConfigType.VMESS))
        val settings = json.getAsJsonObject("settings")

        assertTrue(settings.has("vnext"))
        assertFalse(settings.has("servers"))
        val user = settings.getAsJsonArray("vnext")[0].asJsonObject
            .getAsJsonArray("users")[0].asJsonObject
        assertEquals(0, user.get("alterId").asInt)
        assertEquals("none", user.get("security").asString)
    }

    @Test
    fun `trojan uses servers with the password and never vnext`() {
        val json = outbound(profile(EConfigType.TROJAN))
        val settings = json.getAsJsonObject("settings")

        assertEquals("trojan", json.get("protocol").asString)
        assertTrue(settings.has("servers"))
        assertFalse(settings.has("vnext"))

        val server = settings.getAsJsonArray("servers")[0].asJsonObject
        assertEquals("maxit-mobile.orange.bf.asia1.fufo.org", server.get("address").asString)
        assertEquals(443, server.get("port").asInt)
        assertEquals("2d3bf640-c3f0-11f1-b838-205c6d5f5d78", server.get("password").asString)
        assertEquals(8, server.get("level").asInt)
    }

    @Test
    fun `tls uses allowInsecure and the dynamic sni`() {
        val json = outbound(profile())
        val tls = stream(json).getAsJsonObject("tlsSettings")

        assertTrue(tls.get("allowInsecure").asBoolean)
        assertEquals("maxit-mobile.orange.bf", tls.get("serverName").asString)
    }

    @Test
    fun `websocket writes Host in headers and not at the root`() {
        val ws = stream(outbound(profile())).getAsJsonObject("wsSettings")

        assertEquals("/trojanws", ws.get("path").asString)
        assertEquals(
            "Maxit-mobile.orange.bf",
            ws.getAsJsonObject("headers").get("Host").asString,
        )
        assertFalse(ws.has("host"))
    }

    @Test
    fun `sni falls back to host then to the base domain`() {
        val base = profile()

        assertEquals(
            "sni.example.com",
            tlsServerName(outbound(base.copy(sni = "sni.example.com", host = "host.example.com"))),
        )
        assertEquals(
            "host.example.com",
            tlsServerName(outbound(base.copy(sni = null, host = "host.example.com"))),
        )
        assertEquals(
            "maxit-mobile.orange.bf.asia1.fufo.org",
            tlsServerName(outbound(base.copy(sni = null, host = null))),
        )
    }

    @Test
    fun `grpc tcp and httpupgrade transports are rendered`() {
        val grpc = stream(outbound(profile().copy(network = "grpc", serviceName = "svc")))
        assertEquals("svc", grpc.getAsJsonObject("grpcSettings").get("serviceName").asString)

        val tcp = stream(outbound(profile().copy(network = "tcp")))
        assertTrue(tcp.getAsJsonObject("tcpSettings").entrySet().isEmpty())

        val httpUpgrade = stream(
            outbound(profile().copy(network = "httpupgrade", path = "/up", host = "host.example.com")),
        )
        assertEquals("/up", httpUpgrade.getAsJsonObject("httpupgradeSettings").get("path").asString)
        assertEquals(
            "host.example.com",
            httpUpgrade.getAsJsonObject("httpupgradeSettings").get("host").asString,
        )
    }

    @Test
    fun `reality includes the keys and the sni`() {
        val json = outbound(
            profile().copy(
                security = "reality",
                network = "grpc",
                serviceName = "svc",
                publicKey = "pbk",
                shortId = "sid",
                fingerPrint = "chrome",
            ),
        )
        val reality = stream(json).getAsJsonObject("realitySettings")

        assertEquals("pbk", reality.get("publicKey").asString)
        assertEquals("sid", reality.get("shortId").asString)
        assertEquals("maxit-mobile.orange.bf", reality.get("serverName").asString)
        assertEquals("chrome", reality.get("fingerprint").asString)
        assertFalse(stream(json).has("tlsSettings"))
    }

    @Test
    fun `flat typed settings are normalized to vnext and ws headers`() {
        val flat = """
            {
              "outbounds": [
                {
                  "protocol": "vless",
                  "settings": {
                    "address": "edge.example.com",
                    "port": 443,
                    "id": "uuid",
                    "encryption": "none",
                    "level": 8
                  },
                  "streamSettings": {
                    "network": "ws",
                    "security": "tls",
                    "wsSettings": { "path": "/ray", "host": "header.example.com" }
                  }
                },
                { "protocol": "freedom", "tag": "direct" },
                { "protocol": "blackhole", "tag": "block" }
              ]
            }
        """.trimIndent()

        val root = JsonParser.parseString(XrayJsonConfig.normalizeConfigJson(flat)).asJsonObject
        val outbounds = root.getAsJsonArray("outbounds")
        assertEquals(3, outbounds.size())

        val proxy = outbounds[0].asJsonObject
        val settings = proxy.getAsJsonObject("settings")
        assertTrue(settings.has("vnext"))
        assertFalse(settings.has("servers"))
        assertFalse(settings.has("address"))

        val ws = proxy.getAsJsonObject("streamSettings").getAsJsonObject("wsSettings")
        assertEquals("header.example.com", ws.getAsJsonObject("headers").get("Host").asString)
        assertFalse(ws.has("host"))

        assertEquals("freedom", outbounds[1].asJsonObject.get("protocol").asString)
        assertEquals("blackhole", outbounds[2].asJsonObject.get("protocol").asString)
    }

    @Test
    fun `flat settings are rejected as an xray outbound`() {
        val flat = """{"protocol":"trojan","settings":{"address":"edge.example.com","port":443,"password":"p"}}"""
        assertFalse(XrayJsonConfig.isOutboundValid(flat, EConfigType.TROJAN))
    }

    @Test
    fun `trojan normalized outbound is valid and contains no vnext`() {
        val json = XrayJsonConfig.buildOutboundJson(profile(EConfigType.TROJAN))
        assertNotNull(json)
        assertTrue(XrayJsonConfig.isOutboundValid(json, EConfigType.TROJAN))
        assertTrue(json.contains("\"servers\""))
        assertFalse(json.contains("\"vnext\""))
    }

    @Test
    fun `probe config keeps the proxy first and lets the native probe add its inbound`() {
        val outbound = XrayJsonConfig.buildOutboundJson(profile())
        assertNotNull(outbound)
        val probe = XrayJsonConfig.buildProbeConfig(outbound)
        assertNotNull(probe)

        val root = JsonParser.parseString(probe).asJsonObject
        val outbounds = root.getAsJsonArray("outbounds")
        assertEquals("vless", outbounds[0].asJsonObject.get("protocol").asString)
        assertEquals("proxy", outbounds[0].asJsonObject.get("tag").asString)
        assertFalse(root.has("inbounds"))
        assertTrue(XrayJsonConfig.isConfigValid(probe))
    }
}
