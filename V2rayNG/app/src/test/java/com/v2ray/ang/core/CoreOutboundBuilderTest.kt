package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreOutboundBuilderTest {
    @Test
    fun `websocket host is used as fallback TLS SNI`() {
        val stream = OutboundBean.StreamSettingsBean()
        val profile = ProfileItem.create(EConfigType.VMESS).apply {
            server = "target.example"
            host = "header.example"
            network = NetworkType.WS.type
            security = AppConfig.TLS
        }

        CoreOutboundBuilder.populateTransportSettings(stream, profile)
        assertEquals("header.example", stream.wsSettings?.host)
        assertEquals("header.example", CoreOutboundBuilder.resolveTlsServerName(profile, profile.host))
    }

    @Test
    fun `explicit SNI remains independent from websocket host`() {
        val stream = OutboundBean.StreamSettingsBean()
        val profile = ProfileItem.create(EConfigType.TROJAN).apply {
            server = "target.example"
            host = "header.example"
            sni = "sni.example"
            network = NetworkType.WS.type
            security = AppConfig.TLS
        }

        CoreOutboundBuilder.populateTransportSettings(stream, profile)
        assertEquals("header.example", stream.wsSettings?.host)
        assertEquals("sni.example", CoreOutboundBuilder.resolveTlsServerName(profile, profile.host))
    }

    @Test
    fun `tls never emits the removed allowInsecure option`() {
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            server = "target.example"
            security = AppConfig.TLS
            insecure = true
            pinnedCA256 = "aa:bb"
            verifyPeerCertByName = "cert.example"
            // Évite le chemin de fragmentation, qui lit les préférences MMKV
            // (indisponibles dans un test JVM).
            finalMask = "{}"
        }

        val stream = OutboundBean.StreamSettingsBean()
        CoreOutboundBuilder.populateTlsSettings(stream, profile, null)
        val json = JsonUtil.toJson(stream)

        // Xray-core >= 26.6 refuse la configuration si « allowInsecure » est émis.
        assertFalse(json.contains("allowInsecure"), json)
        assertTrue(json.contains("\"pinnedPeerCertSha256\":\"aa:bb\""), json)
        assertTrue(json.contains("\"verifyPeerCertByName\":\"cert.example\""), json)
    }

    @Test
    fun `reality never emits the removed allowInsecure option`() {
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            server = "target.example"
            security = AppConfig.REALITY
            insecure = true
            publicKey = "pbk"
            shortId = "sid"
            finalMask = "{}"
        }

        val stream = OutboundBean.StreamSettingsBean()
        CoreOutboundBuilder.populateTlsSettings(stream, profile, null)
        val json = JsonUtil.toJson(stream)

        assertFalse(json.contains("allowInsecure"), json)
    }
}
