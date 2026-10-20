package com.v2ray.ang.util

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupFileSizeTest {
    @Test
    fun `rejects declared size over five MiB before reading`() {
        assertThrows(BackupFileTooLargeException::class.java) {
            checkBackupFileSize(BackupFileSize.MAX_BYTES.toLong() + 1)
        }
    }

    @Test
    fun `caps actual stream when declared size is falsely small`() {
        checkBackupFileSize(8L)
        val input = ByteArrayInputStream(ByteArray(BackupFileSize.MAX_BYTES + 1))
        assertThrows(BackupFileTooLargeException::class.java) { input.readBytesLimited() }
    }

    @Test
    fun `allows exactly five MiB`() {
        val bytes = ByteArray(BackupFileSize.MAX_BYTES) { (it % 251).toByte() }
        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readBytesLimited())
    }

    @Test
    fun `limited copy stops without writing bytes beyond limit`() {
        val output = ByteArrayOutputStream()
        assertThrows(BackupFileTooLargeException::class.java) {
            ByteArrayInputStream(ByteArray(33)).copyToLimited(output, maxBytes = 32)
        }
        assertTrue(output.size() <= 32)
    }
}
