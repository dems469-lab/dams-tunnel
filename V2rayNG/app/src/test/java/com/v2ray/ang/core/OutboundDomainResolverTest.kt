package com.v2ray.ang.core

import java.util.Collections
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutboundDomainResolverTest {
    @Test
    fun `deduplicates domains and keeps only non-empty results`() {
        val calls = Collections.synchronizedSet(mutableSetOf<String>())
        val resolved = OutboundDomainResolver.resolve(
            listOf("a.example", "a.example", "b.example", " "),
        ) { domain ->
            calls += domain
            when (domain) {
                "a.example" -> listOf("192.0.2.1")
                "b.example" -> null
                else -> error("unexpected domain $domain")
            }
        }

        assertEquals(setOf("a.example", "b.example"), calls)
        assertEquals(mapOf("a.example" to listOf("192.0.2.1")), resolved)
    }

    @Test
    fun `a failing resolution does not discard the others`() {
        val resolved = OutboundDomainResolver.resolve(listOf("bad.example", "good.example")) { domain ->
            if (domain == "bad.example") error("resolver failure") else listOf("192.0.2.2")
        }

        assertEquals(mapOf("good.example" to listOf("192.0.2.2")), resolved)
    }

    @Test
    fun `empty input does not call the resolver`() {
        var called = false
        val resolved = OutboundDomainResolver.resolve(emptyList()) {
            called = true
            null
        }

        assertTrue(resolved.isEmpty())
        assertFalse(called)
    }
}
