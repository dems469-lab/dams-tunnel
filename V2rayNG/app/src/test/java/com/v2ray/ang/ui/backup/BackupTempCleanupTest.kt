package com.v2ray.ang.ui.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class BackupTempCleanupTest {
    @Test
    fun `cleanup removes abandoned local import zip but preserves recent files`() {
        val cache = Files.createTempDirectory("dams-temp-cleanup").toFile()
        try {
            val expired = File(cache, "123456789.zip").apply {
                writeText("temporary import")
                setLastModified(1_000)
            }
            val recent = File(cache, "987654321.zip").apply {
                writeText("active import")
                setLastModified(9_000)
            }
            assertEquals(1, deleteExpiredBackupTempFiles(cache, nowMillis = 10_000, maxAgeMillis = 5_000))
            assertFalse(expired.exists())
            assertTrue(recent.exists())
        } finally {
            cache.deleteRecursively()
        }
    }
}
