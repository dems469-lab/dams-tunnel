package com.v2ray.ang.util

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DamsProfileFileCodecTest {
    @Test
    fun `protected profile payload contains one profile and strips subscription metadata`() {
        val profile = ProfileItem(
            configType = EConfigType.VLESS,
            subscriptionId = "subscription-secret-id",
            remarks = "one-server",
            server = "vpn.example.test",
            serverPort = "443",
        )

        val plaintext = DamsProfileFileCodec.encode(profile, "vless://secret")
        val encrypted = DamsFileCodec.encrypt(
            plaintext,
            DamsFileCodec.ExportOptions("long-enough-password"),
        )
        val decoded = DamsProfileFileCodec.decode(DamsFileCodec.decrypt(encrypted, "long-enough-password"))

        assertTrue(DamsFileCodec.isDamsFile(encrypted))
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("vpn.example.test"))
        assertEquals("one-server", decoded?.profile?.remarks)
        assertEquals("", decoded?.profile?.subscriptionId)
        assertEquals("vless://secret", decoded?.rawConfig)
    }

    @Test
    fun `full backup payload is not mistaken for a single profile`() {
        assertNull(DamsProfileFileCodec.decode("PK\u0003\u0004backup".toByteArray()))
    }

    @Test
    fun `group profiles are rejected because their dependencies cannot be shared alone`() {
        val profile = ProfileItem(configType = EConfigType.PROXYCHAIN)

        assertThrows(IllegalArgumentException::class.java) {
            DamsProfileFileCodec.encode(profile)
        }
    }
}
