package com.v2ray.ang.publicconfigs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
}
