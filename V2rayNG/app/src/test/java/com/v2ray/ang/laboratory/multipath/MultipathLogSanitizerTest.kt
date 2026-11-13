package com.v2ray.ang.laboratory.multipath

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MultipathLogSanitizerTest {

    @Test
    fun `uuid is masked`() {
        val message = "Connexion vers 11111111-2222-3333-4444-555555555555 établie"
        val safe = MultipathEventLogger.sanitize(message)
        assertFalse(safe.contains("11111111"))
        assertTrue(safe.contains("***"))
    }

    @Test
    fun `secret assignment is masked without leaking the value`() {
        val safe = MultipathEventLogger.sanitize("password=SuperSecret123 token: abcdef")
        assertFalse(safe.contains("SuperSecret123"))
        assertFalse(safe.contains("abcdef"))
        assertTrue(safe.contains("password=***"))
        assertTrue(safe.contains("token=***"))
    }

    @Test
    fun `long hexadecimal string is masked`() {
        val safe = MultipathEventLogger.sanitize("clé 0123456789abcdef0123456789abcdef")
        assertFalse(safe.contains("0123456789abcdef"))
        assertTrue(safe.contains("***"))
    }

    @Test
    fun `line breaks are flattened and whitespace trimmed`() {
        val safe = MultipathEventLogger.sanitize("  ligne un\nligne deux\r\n  ")
        assertEquals("ligne un ligne deux", safe)
    }

    @Test
    fun `long message is truncated to the maximum length`() {
        // 'z' n'est pas hexadécimal : la chaîne n'est donc pas masquée avant troncature.
        val safe = MultipathEventLogger.sanitize("z".repeat(500))
        assertEquals(240, safe.length)
        assertTrue(safe.endsWith("…"))
    }

    @Test
    fun `ordinary message is preserved`() {
        val message = "Relais A → B (latence, 120 ms → 90 ms)"
        assertEquals(message, MultipathEventLogger.sanitize(message))
    }
}
