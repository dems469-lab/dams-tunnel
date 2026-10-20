package com.v2ray.ang.ui.backup

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class BackupTempFilesTest {
    @Test
    fun `temporary export zip is removed when export fails`() = runBlocking {
        assertTemporaryFileRemovedAfterFailure("export.zip")
    }

    @Test
    fun `temporary restore zip is removed when restore fails`() = runBlocking {
        assertTemporaryFileRemovedAfterFailure("restore_123.zip")
    }

    @Test
    fun `startup cleanup removes only expired backup artifacts`() {
        val cache = Files.createTempDirectory("dams-backup-cleanup").toFile()
        try {
            val oldZip = File(cache, "Dams Tunnel_2026-01-01-12-00-00.zip").apply {
                writeText("zip")
                setLastModified(1_000)
            }
            val oldFolder = File(cache, "Dams Tunnel_2026-01-01-12-00-00").apply {
                mkdir()
                File(this, "profile").writeText("backup")
                setLastModified(1_000)
            }
            val recent = File(cache, "restore_123.zip").apply {
                writeText("recent")
                setLastModified(9_500)
            }
            val unrelated = File(cache, "other.tmp").apply { writeText("keep") }

            assertEquals(2, deleteExpiredBackupTempFiles(cache, nowMillis = 10_000, maxAgeMillis = 5_000))
            assertFalse(oldZip.exists())
            assertFalse(oldFolder.exists())
            assertTrue(recent.exists())
            assertTrue(unrelated.exists())
        } finally {
            cache.deleteRecursively()
        }
    }

    private suspend fun assertTemporaryFileRemovedAfterFailure(name: String) {
        val cache = Files.createTempDirectory("dams-backup-temp").toFile()
        val temp = File(cache, name)
        try {
            temp.writeText("unencrypted archive")
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    withTemporaryFileCleanup(temp) {
                        assertTrue(temp.exists())
                        throw IllegalStateException("simulated failure")
                    }
                }
            }
            assertFalse(temp.exists())
        } finally {
            cache.deleteRecursively()
        }
    }
}
