package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CoreOutboundBuilderTest {
    @Test
    fun `websocket host is not used as implicit TLS SNI`() {
        val stream = OutboundBean.StreamSettingsBean()
        val profile = ProfileItem.create(EConfigType.VMESS).apply {
            server = "target.example"
            host = "header.example"
            network = NetworkType.WS.type
            security = AppConfig.TLS
        }

        CoreOutboundBuilder.populateTransportSettings(stream, profile)
        CoreOutboundBuilder.populateTlsSettings(stream, profile, profile.host)

        assertEquals("header.example", stream.wsSettings?.host)
        assertEquals("target.example", stream.tlsSettings?.serverName)
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
        CoreOutboundBuilder.populateTlsSettings(stream, profile, profile.host)

        assertEquals("header.example", stream.wsSettings?.host)
        assertEquals("sni.example", stream.tlsSettings?.serverName)
    }
}
