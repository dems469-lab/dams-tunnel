package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerAddressResolverTest {
    @Test
    fun `literal server address is never resolved`() {
        var calls = 0
        val resolver = ServerAddressResolver(
            resolveDirect = {
                calls++
                listOf("192.0.2.1")
            },
            cache = InMemoryServerAddressCache(),
        )

        val result = resolver.resolve(listOf("203.0.113.7", "2001:db8::1"), 1_000L)

        assertEquals(0, calls)
        assertTrue(result.fresh.isEmpty())
        assertTrue(result.cached.isEmpty())
        assertTrue(result.unresolved.isEmpty())
    }

    @Test
    fun `resolution never selects a vpn network`() {
        val candidates = listOf(
            NetworkCandidate(id = 1, isVpn = true, hasInternet = true, isValidated = true),
            NetworkCandidate(id = 2, isVpn = false, hasInternet = true, isValidated = false),
            NetworkCandidate(id = 3, isVpn = false, hasInternet = true, isValidated = true),
        )

        assertEquals(3L, selectUnderlyingNetwork(candidates)?.id)
        assertNull(selectUnderlyingNetwork(listOf(NetworkCandidate(1, true, true, true))))
        assertNull(selectUnderlyingNetwork(listOf(NetworkCandidate(2, false, false, true))))
    }

    @Test
    fun `successful resolution is persisted with its date`() {
        val cache = InMemoryServerAddressCache()
        val resolver = ServerAddressResolver(
            resolveDirect = { listOf("192.0.2.20") },
            cache = cache,
            now = { 42L },
        )

        resolver.resolve(listOf("fresh.example"), 1_000L)

        assertEquals(
            CachedServerAddress(listOf("192.0.2.20"), 42L),
            cache.read("fresh.example"),
        )
    }

    @Test
    fun `failed resolution falls back to the dated cache`() {
        val cache = InMemoryServerAddressCache()
        cache.write("cached.example", listOf("192.0.2.10"), 1_600_000_000_000L)
        val resolver = ServerAddressResolver(
            resolveDirect = { null },
            cache = cache,
            now = { 1_700_000_000_000L },
        )

        val result = resolver.resolve(listOf("cached.example"), 1_000L)

        assertEquals(listOf("192.0.2.10"), result.ips("cached.example"))
        assertEquals(listOf("cached.example"), result.cached.keys.toList())
        assertTrue(result.unresolved.isEmpty())
        assertEquals(1_600_000_000_000L, cache.read("cached.example")?.resolvedAtMillis)
    }

    @Test
    fun `unresolved domain without cache is reported instead of thrown`() {
        val resolver = ServerAddressResolver(
            resolveDirect = { null },
            cache = InMemoryServerAddressCache(),
        )

        val result = resolver.resolve(listOf("unknown.example"), 1_000L)

        assertEquals(listOf("unknown.example"), result.unresolved)
        assertNull(result.ips("unknown.example"))
    }

    @Test
    fun `ipv6 preference only reorders addresses`() {
        val addresses = listOf("192.0.2.1", "2001:db8::1")

        assertEquals(listOf("2001:db8::1", "192.0.2.1"), sortResolvedAddresses(addresses, true))
        assertEquals(listOf("192.0.2.1", "2001:db8::1"), sortResolvedAddresses(addresses, false))
    }

    private class InMemoryServerAddressCache : ServerAddressCache {
        private val entries = mutableMapOf<String, CachedServerAddress>()

        override fun read(domain: String): CachedServerAddress? = entries[domain]

        override fun write(domain: String, ips: List<String>, resolvedAtMillis: Long) {
            entries[domain] = CachedServerAddress(ips, resolvedAtMillis)
        }
    }
}
