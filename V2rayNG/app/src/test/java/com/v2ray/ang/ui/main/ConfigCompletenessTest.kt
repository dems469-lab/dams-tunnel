package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigCompletenessTest {
    private val uuid = "123e4567-e89b-12d3-a456-426614174000"

    @Test
    fun `VLESS websocket complet est distingue du profil partiel`() {
        val complete = ProfileItem.create(EConfigType.VLESS).apply {
            server = "example.org"
            serverPort = "443"
            password = uuid
            network = "ws"
            path = "/tunnel"
            host = "example.org"
            security = "tls"
            sni = "example.org"
        }
        val partial = complete.copy(path = null, host = null)
        assertEquals(ConfigCompletenessStatus.COMPLETE, assessConfigCompleteness(complete).status)
        assertEquals(ConfigCompletenessStatus.PARTIAL, assessConfigCompleteness(partial).status)
    }

    @Test
    fun `VLESS sans serveur ou UUID est invalide`() {
        val result = assessConfigCompleteness(ProfileItem.create(EConfigType.VLESS).apply {
            serverPort = "443"
            password = "pas-un-uuid"
        })
        assertEquals(ConfigCompletenessStatus.INVALID, result.status)
        assertTrue(result.missingFields.contains("serveur"))
        assertTrue(result.missingFields.contains("identifiant"))
    }

    @Test
    fun `Trojan ne revele pas le mot de passe`() {
        val result = assessConfigCompleteness(ProfileItem.create(EConfigType.TROJAN).apply {
            server = "example.org"
            serverPort = "443"
            password = "secret-trojan"
        })
        assertTrue(result.missingFields.none { it.contains("secret", true) })
    }

    @Test
    fun `classement separe completude et latence`() {
        val slowComplete = ProfileItem.create(EConfigType.VLESS).apply {
            server = "one.example"
            serverPort = "443"
            password = uuid
        }
        val fastInvalid = ProfileItem.create(EConfigType.VLESS).apply {
            server = null
            serverPort = "443"
            password = "bad"
        }
        val ranked = rankConfigs(listOf(
            RankedConfig("slow", slowComplete, assessConfigCompleteness(slowComplete), 900),
            RankedConfig("fast-invalid", fastInvalid, assessConfigCompleteness(fastInvalid), 10),
        ))
        assertEquals("slow", ranked.first().guid)
    }
}
