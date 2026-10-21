package com.v2ray.ang.premium

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PremiumEndpointPolicyTest {
    @Test
    fun `token seul produit les domaines dans l'ordre principal puis secours`() {
        val policy = PremiumEndpointPolicy("https://primary.example, https://backup.example/")

        assertEquals(
            listOf(
                "https://primary.example/sub/abc-123",
                "https://backup.example/sub/abc-123",
            ),
            policy.subscriptionUrls(" abc-123 "),
        )
    }

    @Test
    fun `URL complète permise sur domaine configuré et domaine saisi passe en premier`() {
        val policy = PremiumEndpointPolicy("https://primary.example,https://backup.example")

        assertEquals(
            listOf(
                "https://backup.example/sub/token_1",
                "https://primary.example/sub/token_1",
            ),
            policy.subscriptionUrls("https://backup.example/sub/token_1"),
        )
        assertEquals("token_1", policy.token("https://backup.example/sub/token_1"))
    }

    @Test
    fun `URL complète étrangère ou non HTTPS est refusée`() {
        val policy = PremiumEndpointPolicy("https://primary.example,https://backup.example")

        assertTrue(policy.subscriptionUrls("https://foreign.example/sub/token").isEmpty())
        assertTrue(policy.subscriptionUrls("http://primary.example/sub/token").isEmpty())
        assertTrue(policy.subscriptionUrls("https://user@primary.example/sub/token").isEmpty())
        assertTrue(policy.subscriptionUrls("https://primary.example/other/token").isEmpty())
        assertTrue(policy.subscriptionUrls("https://primary.example/sub/token?x=1").isEmpty())
    }

    @Test
    fun `sans domaine configuré aucun code ou URL ne peut être activé`() {
        val policy = PremiumEndpointPolicy("")

        assertFalse(policy.isConfigured)
        assertTrue(policy.subscriptionUrls("token").isEmpty())
        assertTrue(policy.subscriptionUrls("https://primary.example/sub/token").isEmpty())
    }

    @Test
    fun `domaines invalides et doublons sont ignorés sans perdre les domaines valides`() {
        val policy = PremiumEndpointPolicy(
            "http://insecure.example,https://PRIMARY.example,https://primary.example/"
        )

        assertEquals(listOf("https://primary.example/sub/token"), policy.subscriptionUrls("token"))
    }
}
