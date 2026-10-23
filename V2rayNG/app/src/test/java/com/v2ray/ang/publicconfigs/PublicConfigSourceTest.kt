package com.v2ray.ang.publicconfigs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicConfigSourceTest {
    @Test
    fun `liste contient toutes les sources fournies`() {
        assertEquals(12, PublicConfigSources.all.size)
        assertTrue(PublicConfigSources.all.all(PublicConfigSources::isAllowed))
    }

    @Test
    fun `source externe non autorisee`() {
        val external = PublicConfigSource("x", "x", "x", "http://example.com/configs", emptySet())
        assertTrue(!PublicConfigSources.isAllowed(external))
    }

    @Test
    fun `filtre les sources par protocole sans inventer de categorie`() {
        val result = filterPublicConfigSources(PublicConfigSources.all, "", "VLESS")
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.protocols.contains("VLESS") })
        assertTrue(filterPublicConfigSources(PublicConfigSources.all, "", "SSH").isEmpty())
    }
}
