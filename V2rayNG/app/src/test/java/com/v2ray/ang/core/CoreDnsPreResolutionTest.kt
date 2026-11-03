package com.v2ray.ang.core

import com.v2ray.ang.dto.V2rayConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CoreDnsPreResolutionTest {
    @Test
    fun `policy group members are pre-resolved like any other proxy outbound`() {
        val config = configWith(
            outbound("proxy-group-1-Paris", "paris.example"),
            outbound("proxy-group-2-Tokyo", "tokyo.example"),
        )

        assertEquals(
            listOf("paris.example", "tokyo.example"),
            CoreConfigManager.collectProxyOutboundDomains(config, emptyMap()),
        )
    }

    @Test
    fun `literal addresses and existing hosts are skipped`() {
        val config = configWith(
            outbound("proxy-ip", "192.0.2.10"),
            outbound("proxy-known", "known.example"),
            outbound("proxy-new", "new.example"),
        )

        assertEquals(
            listOf("new.example"),
            CoreConfigManager.collectProxyOutboundDomains(
                config,
                mapOf("known.example" to "192.0.2.11"),
            ),
        )
    }

    private fun configWith(vararg outbounds: V2rayConfig.OutboundBean) = V2rayConfig(
        log = V2rayConfig.LogBean(),
        inbounds = arrayListOf(),
        outbounds = arrayListOf(*outbounds),
        routing = V2rayConfig.RoutingBean(domainStrategy = "AsIs", rules = arrayListOf()),
    )

    private fun outbound(tag: String, address: String) = V2rayConfig.OutboundBean(
        tag = tag,
        protocol = "vmess",
        settings = V2rayConfig.OutboundBean.OutSettingsBean(address = address),
    )
}
