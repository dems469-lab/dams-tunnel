package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class CoreOutboundBuilderTest {
    @Test
    fun `protocol outbounds carry VLESS VMess Trojan and Shadowsocks credentials`() {
        val cases = listOf(
            ProfileItem.create(EConfigType.VLESS).apply { server = "192.0.2.10"; serverPort = "443"; password = "uuid"; method = "none" },
            ProfileItem.create(EConfigType.VMESS).apply { server = "192.0.2.10"; serverPort = "443"; password = "vmess-id"; method = "auto" },
            ProfileItem.create(EConfigType.TROJAN).apply { server = "192.0.2.10"; serverPort = "443"; password = "trojan-pass" },
            ProfileItem.create(EConfigType.SHADOWSOCKS).apply { server = "192.0.2.10"; serverPort = "8388"; password = "ss-pass"; method = "chacha20-ietf-poly1305" },
        )

        val outbounds = cases.map(CoreOutboundBuilder::buildProtocolOutbound)

        assertEquals("uuid", outbounds[0]?.settings?.id)
        assertEquals("vmess-id", outbounds[1]?.settings?.id)
        assertEquals("trojan-pass", outbounds[2]?.settings?.password)
        assertEquals("ss-pass", outbounds[3]?.settings?.password)
        assertEquals("chacha20-ietf-poly1305", outbounds[3]?.settings?.method)
    }

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
        assertEquals("header.example", stream.wsSettings?.host)
        assertEquals("target.example", CoreOutboundBuilder.resolveTlsServerName(profile, profile.host))
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
    fun `VMess gRPC and Trojan websocket settings reach generated outbounds`() {
        val vmess = ProfileItem.create(EConfigType.VMESS).apply {
            server = "vmess.example"
            serverPort = "443"
            password = "uuid"
            network = NetworkType.GRPC.type
            serviceName = "grpc-service"
            authority = "grpc.example"
            security = AppConfig.TLS
            sni = "sni.example"
        }
        val trojan = ProfileItem.create(EConfigType.TROJAN).apply {
            server = "trojan.example"
            serverPort = "443"
            password = "secret"
            network = NetworkType.WS.type
            path = "/socket"
            host = "cdn.example"
            security = AppConfig.TLS
            sni = "trojan-sni.example"
        }

        val vmessOutbound = CoreOutboundBuilder.buildProtocolOutbound(vmess)
        val trojanOutbound = CoreOutboundBuilder.buildProtocolOutbound(trojan)

        assertNotNull(vmessOutbound)
        assertEquals("grpc-service", vmessOutbound?.streamSettings?.grpcSettings?.serviceName)
        assertEquals("grpc.example", vmessOutbound?.streamSettings?.grpcSettings?.authority)
        assertEquals("sni.example", vmessOutbound?.streamSettings?.tlsSettings?.serverName)
        assertNotNull(trojanOutbound)
        assertEquals("/socket", trojanOutbound?.streamSettings?.wsSettings?.path)
        assertEquals("cdn.example", trojanOutbound?.streamSettings?.wsSettings?.host)
        assertEquals("trojan-sni.example", trojanOutbound?.streamSettings?.tlsSettings?.serverName)
    }
}
