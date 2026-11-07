package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProxyOutboundDiagnosticsTest {
    @Test
    fun describesTlsTransportWithoutExposingTheServerAddress() {
        val descriptor = proxyOutboundDescriptor(
            """
            {"outbounds":[
              {"tag":"proxy","protocol":"vless","settings":{"address":"203.0.113.7","port":443},
               "streamSettings":{"network":"ws","security":"tls",
                 "tlsSettings":{"serverName":"cdn.example.com","alpn":["h2","http/1.1"],"fingerprint":"chrome"},
                 "wsSettings":{"host":"cdn.example.com","path":"/ws"}}},
              {"tag":"direct","protocol":"freedom"}
            ]}
            """.trimIndent()
        )

        assertEquals("vless", descriptor?.protocol)
        assertEquals("ws", descriptor?.network)
        assertEquals("tls", descriptor?.security)
        assertEquals("cdn.example.com", descriptor?.sni)
        assertEquals("cdn.example.com", descriptor?.host)
        assertEquals("h2,http/1.1", descriptor?.alpn)
        assertEquals("chrome", descriptor?.fingerprint)
        assertEquals(443, descriptor?.port)
        assertEquals(true, descriptor?.serverIsIp)
        assertFalse(descriptor?.toLogLine().orEmpty().contains("203.0.113.7"))
    }

    @Test
    fun readsThePortAndAddressFromVnextAndKeepsDomainFlagFalse() {
        val descriptor = proxyOutboundDescriptor(
            """
            {"outbounds":[
              {"tag":"proxy","protocol":"vmess",
               "settings":{"vnext":[{"address":"example.net","port":8443}]},
               "streamSettings":{"network":"tcp"}}
            ]}
            """.trimIndent()
        )

        assertEquals(8443, descriptor?.port)
        assertEquals(false, descriptor?.serverIsIp)
        assertNull(descriptor?.sni)
        assertNull(descriptor?.host)
        assertEquals("tcp", descriptor?.network)
        assertNull(descriptor?.security)
    }

    @Test
    fun readsRealitySniAndFingerprint() {
        val descriptor = proxyOutboundDescriptor(
            """
            {"outbounds":[
              {"tag":"proxy","protocol":"vless","settings":{"address":"198.51.100.9","port":443},
               "streamSettings":{"network":"tcp","security":"reality",
                 "realitySettings":{"serverName":"www.example.org","fingerprint":"firefox"}}}
            ]}
            """.trimIndent()
        )

        assertEquals("reality", descriptor?.security)
        assertEquals("www.example.org", descriptor?.sni)
        assertEquals("firefox", descriptor?.fingerprint)
        assertEquals(true, descriptor?.serverIsIp)
    }

    @Test
    fun ignoresInvalidOrMissingConfiguration() {
        assertNull(proxyOutboundDescriptor("not-json"))
        assertNull(proxyOutboundDescriptor("""{"outbounds":[]}"""))
    }
}
