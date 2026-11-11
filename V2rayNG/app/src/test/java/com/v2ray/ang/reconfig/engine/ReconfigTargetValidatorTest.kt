package com.v2ray.ang.reconfig.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReconfigTargetValidatorTest {

    @Test
    fun `accepte les domaines fournis par le module`() {
        assertEquals("Maxit-mobile.orange.bf", ReconfigTargetValidator.validate("Maxit-mobile.orange.bf").target)
        assertEquals("api-connect.orange.bf", ReconfigTargetValidator.validate("api-connect.orange.bf").target)
        assertEquals("apigw-maxit2.orange.bf", ReconfigTargetValidator.validate("apigw-maxit2.orange.bf").target)
    }

    @Test
    fun `accepte une adresse ipv4`() {
        val result = ReconfigTargetValidator.validate("192.0.2.10")
        assertTrue(result.isValid)
        assertEquals("192.0.2.10", result.target)
    }

    @Test
    fun `refuse une saisie vide`() {
        assertEquals(ReconfigTargetError.EMPTY, ReconfigTargetValidator.validate("").error)
        assertEquals(ReconfigTargetError.EMPTY, ReconfigTargetValidator.validate("   ").error)
        assertNull(ReconfigTargetValidator.validate("").target)
    }

    @Test
    fun `refuse un schema un port ou un chemin`() {
        assertEquals(
            ReconfigTargetError.INVALID_CHARACTERS,
            ReconfigTargetValidator.validate("https://gateway.example.com").error,
        )
        assertEquals(
            ReconfigTargetError.IPV6_NOT_SUPPORTED,
            ReconfigTargetValidator.validate("gateway.example.com:443").error,
        )
        assertEquals(
            ReconfigTargetError.INVALID_CHARACTERS,
            ReconfigTargetValidator.validate("gateway.example.com/path").error,
        )
        assertEquals(
            ReconfigTargetError.INVALID_CHARACTERS,
            ReconfigTargetValidator.validate("user@gateway.example.com").error,
        )
    }

    @Test
    fun `refuse les domaines malformes`() {
        assertEquals(ReconfigTargetError.INVALID_DOMAIN, ReconfigTargetValidator.validate("localhost").error)
        assertEquals(ReconfigTargetError.INVALID_DOMAIN, ReconfigTargetValidator.validate("-bad.example.com").error)
        assertEquals(ReconfigTargetError.INVALID_DOMAIN, ReconfigTargetValidator.validate("bad-.example.com").error)
        assertEquals(ReconfigTargetError.INVALID_DOMAIN, ReconfigTargetValidator.validate("a..b").error)
        assertEquals(ReconfigTargetError.INVALID_DOMAIN, ReconfigTargetValidator.validate("example.123").error)
        assertEquals(
            ReconfigTargetError.INVALID_CHARACTERS,
            ReconfigTargetValidator.validate("bad_domain.example.com").error,
        )
    }

    @Test
    fun `refuse un hote trop long`() {
        val longHost = (1..40).joinToString(".") { "abcdefghij" }
        assertEquals(ReconfigTargetError.TOO_LONG, ReconfigTargetValidator.validate(longHost).error)
    }

    @Test
    fun `refuse ipv6 comme cible`() {
        assertEquals(
            ReconfigTargetError.IPV6_NOT_SUPPORTED,
            ReconfigTargetValidator.validate("2001:db8::1").error,
        )
    }

    @Test
    fun `reconnait les adresses de source`() {
        assertTrue(ReconfigTargetValidator.isAddress("edge.example.com"))
        assertTrue(ReconfigTargetValidator.isAddress("192.0.2.10"))
        assertTrue(ReconfigTargetValidator.isAddress("2001:db8::1"))
        assertTrue(ReconfigTargetValidator.isAddress("[2001:db8::1]"))
        assertFalse(ReconfigTargetValidator.isAddress(""))
        assertFalse(ReconfigTargetValidator.isAddress("bad host"))
        assertFalse(ReconfigTargetValidator.isAddress("edge.example.com:443"))
    }

    @Test
    fun `un domaine seul n est pas accepte comme cible`() {
        assertFalse(ReconfigTargetValidator.isDomain("single"))
        assertTrue(ReconfigTargetValidator.isDomain("gateway.example.com"))
    }
}
