package com.v2ray.ang.core

import java.util.Base64
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShadowsocksConfigValidatorTest {

    private val decoder: (String) -> ByteArray? = { value ->
        try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            try {
                Base64.getUrlDecoder().decode(value)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    private fun key(size: Int): String = Base64.getEncoder().encodeToString(ByteArray(size) { it.toByte() })

    @Test
    fun `legacy ciphers accept any non empty password`() {
        assertDoesNotThrow {
            ShadowsocksConfigValidator.validate("chacha20-ietf-poly1305", "tunnel-ljlcf", 8444, decoder)
        }
        assertDoesNotThrow {
            ShadowsocksConfigValidator.validate("aes-256-gcm", "mot de passe libre", 8388, decoder)
        }
    }

    @Test
    fun `2022 cipher accepts a key of the right size`() {
        assertDoesNotThrow {
            ShadowsocksConfigValidator.validate("2022-blake3-aes-128-gcm", key(16), 443, decoder)
        }
        assertDoesNotThrow {
            ShadowsocksConfigValidator.validate("2022-blake3-aes-256-gcm", key(32), 443, decoder)
        }
        assertDoesNotThrow {
            ShadowsocksConfigValidator.validate("2022-blake3-chacha20-poly1305", key(32), 443, decoder)
        }
    }

    @Test
    fun `2022 cipher rejects a key of the wrong size`() {
        val error = assertThrows(ConfigValidationException::class.java) {
            ShadowsocksConfigValidator.validate("2022-blake3-aes-128-gcm", key(15), 443, decoder)
        }
        assertTrue(error.message.orEmpty().contains("16 attendus"))
    }

    @Test
    fun `2022 cipher rejects a password that is not base64`() {
        assertThrows(ConfigValidationException::class.java) {
            ShadowsocksConfigValidator.validate("2022-blake3-aes-128-gcm", "pas-du-base64!", 443, decoder)
        }
    }

    @Test
    fun `2022 multi user key pair is accepted`() {
        val password = "${key(16)}:${key(16)}"
        assertDoesNotThrow {
            ShadowsocksConfigValidator.validate("2022-blake3-aes-128-gcm", password, 443, decoder)
        }
    }

    @Test
    fun `2022 multi user key pair checks both parts`() {
        val password = "${key(16)}:${key(15)}"
        val error = assertThrows(ConfigValidationException::class.java) {
            ShadowsocksConfigValidator.validate("2022-blake3-aes-128-gcm", password, 443, decoder)
        }
        assertTrue(error.message.orEmpty().contains("clé utilisateur"))
    }

    @Test
    fun `more than two keys is rejected`() {
        val password = "${key(16)}:${key(16)}:${key(16)}"
        assertThrows(ConfigValidationException::class.java) {
            ShadowsocksConfigValidator.validate("2022-blake3-aes-128-gcm", password, 443, decoder)
        }
    }

    @Test
    fun `missing method password or port is reported`() {
        assertThrows(ConfigValidationException::class.java) {
            ShadowsocksConfigValidator.validate(null, "x", 443, decoder)
        }
        assertThrows(ConfigValidationException::class.java) {
            ShadowsocksConfigValidator.validate("aes-256-gcm", "", 443, decoder)
        }
        assertThrows(ConfigValidationException::class.java) {
            ShadowsocksConfigValidator.validate("aes-256-gcm", "x", 0, decoder)
        }
        assertThrows(ConfigValidationException::class.java) {
            ShadowsocksConfigValidator.validate("aes-256-gcm", "x", null, decoder)
        }
    }

    /** Valeurs exactes du lien fourni par l'utilisateur : les deux clés font 16 octets. */
    @Test
    fun `real user uri key pair decodes to sixteen bytes`() {
        val password = "FreeVPN/us/shadowsocks==:FreeVPN/us/shadowsocks=="
        assertEquals(16, decoder("FreeVPN/us/shadowsocks==")?.size)
        assertDoesNotThrow {
            ShadowsocksConfigValidator.validate("2022-blake3-aes-128-gcm", password, 443, decoder)
        }
    }
}
