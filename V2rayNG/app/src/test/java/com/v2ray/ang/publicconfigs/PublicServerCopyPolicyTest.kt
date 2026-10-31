package com.v2ray.ang.publicconfigs

import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicServerCopyPolicyTest {
    @Test
    fun `single protocols can be copied into My servers`() {
        listOf(
            EConfigType.VLESS,
            EConfigType.VMESS,
            EConfigType.TROJAN,
            EConfigType.SHADOWSOCKS,
            EConfigType.SOCKS,
            EConfigType.HTTP,
            EConfigType.WIREGUARD,
            EConfigType.HYSTERIA2,
        ).forEach { assertTrue(canCopyToMyServers(it), "expected $it to be copyable") }
    }

    @Test
    fun `policy groups cannot be copied`() {
        assertFalse(canCopyToMyServers(EConfigType.POLICYGROUP))
    }

    @Test
    fun `proxy chains cannot be copied`() {
        assertFalse(canCopyToMyServers(EConfigType.PROXYCHAIN))
    }
}
