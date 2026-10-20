package com.v2ray.ang.util

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DamsFileCodecTest {

    @Test
    fun `DAMS2 round trip hides metadata and restores payload`() {
        val payload = "configuration sans secret en dehors du conteneur".toByteArray()
        val container = DamsFileCodec.encrypt(
            payload,
            DamsFileCodec.ExportOptions("mot-de-passe", expiresAtEpochMillis = System.currentTimeMillis() + 60_000),
        )

        assertTrue(DamsFileCodec.isDamsFile(container))
        // The clear header contains only the format marker and random crypto material.
        assertFalse(container.copyOfRange(0, container.size).contentEquals(payload))
        assertArrayEquals(payload, DamsFileCodec.decrypt(container, "mot-de-passe"))
    }

    @Test
    fun `wrong password is rejected`() {
        val container = DamsFileCodec.encrypt(
            byteArrayOf(1, 2, 3),
            DamsFileCodec.ExportOptions("correct"),
        )

        assertThrows(DamsFileCodec.IncorrectPasswordException::class.java) {
            DamsFileCodec.decrypt(container, "incorrect")
        }
    }

    @Test
    fun `expired file is rejected`() {
        val now = 2_000_000L
        val container = DamsFileCodec.encrypt(
            byteArrayOf(4, 5, 6),
            DamsFileCodec.ExportOptions("correct", expiresAtEpochMillis = now + 1_000),
            nowEpochMillis = now,
        )

        assertThrows(DamsFileCodec.InvalidFileException::class.java) {
            DamsFileCodec.decrypt(container, "correct", nowEpochMillis = now + 1_001)
        }
    }

    @Test
    fun `tampered file is rejected`() {
        val container = DamsFileCodec.encrypt(
            byteArrayOf(7, 8, 9),
            DamsFileCodec.ExportOptions("correct"),
        ).also { it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte() }

        assertThrows(DamsFileCodec.InvalidFileException::class.java) {
            DamsFileCodec.decrypt(container, "correct")
        }
    }

    @Test
    fun `rejects payload and container larger than five MiB`() {
        assertThrows(BackupFileTooLargeException::class.java) {
            DamsFileCodec.encrypt(
                ByteArray(BackupFileSize.MAX_BYTES + 1),
                DamsFileCodec.ExportOptions("long-enough-password"),
            )
        }
        assertThrows(BackupFileTooLargeException::class.java) {
            DamsFileCodec.decrypt(ByteArray(BackupFileSize.MAX_BYTES + 1), "long-enough-password")
        }
    }

    @Test
    fun `export rejects passwords shorter than twelve characters`() {
        assertThrows(IllegalArgumentException::class.java) {
            DamsFileCodec.encrypt(byteArrayOf(1), DamsFileCodec.ExportOptions("short-pass"))
        }
        assertTrue(DamsPasswordPolicy.strength("12345678901") == DamsPasswordPolicy.Strength.TOO_SHORT)
        assertTrue(DamsPasswordPolicy.strength("123456789012") == DamsPasswordPolicy.Strength.ACCEPTABLE)
        assertTrue(DamsPasswordPolicy.strength("SafePassword123456") == DamsPasswordPolicy.Strength.STRONG)
    }

    @Test
    fun `failed restore after a valid password does not consume an attempt`() {
        assertTrue(
            DamsRestoreAttemptPolicy.remainingAfterFailure(4, IllegalStateException("restore failed")) == 4
        )
        assertTrue(
            DamsRestoreAttemptPolicy.remainingAfterFailure(4, DamsFileCodec.IncorrectPasswordException()) == 3
        )
    }

}
