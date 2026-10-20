package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FmtBaseTest {
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
