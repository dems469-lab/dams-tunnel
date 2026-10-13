package com.v2ray.ang.util

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DamsFileCodecTest {
    @Test
    fun `encrypted export round trips`() {
        val payload = "complete configuration".toByteArray()
        val file = DamsFileCodec.encrypt(
            payload,
            DamsFileCodec.ExportOptions("correct horse battery staple", 2_000L),
            nowEpochMillis = 1_000L,
        )

        assertArrayEquals(payload, DamsFileCodec.decrypt(file, "correct horse battery staple", 1_500L))
    }

    @Test
    fun `wrong password is rejected`() {
        val file = DamsFileCodec.encrypt("secret".toByteArray(), DamsFileCodec.ExportOptions("password"), 1_000L)

        assertThrows(DamsFileCodec.InvalidFileException::class.java) {
            DamsFileCodec.decrypt(file, "other", 1_000L)
        }
    }

    @Test
    fun `expired export is rejected`() {
        val file = DamsFileCodec.encrypt("secret".toByteArray(), DamsFileCodec.ExportOptions("password", 2_000L), 1_000L)

        assertThrows(DamsFileCodec.InvalidFileException::class.java) {
            DamsFileCodec.decrypt(file, "password", 2_000L)
        }
    }
}
