package com.v2ray.ang.ui.backup

import java.io.File

internal suspend fun <T> withTemporaryFileCleanup(file: File, action: suspend () -> T): T =
    try {
        action()
    } finally {
        file.delete()
    }

internal fun deleteExpiredBackupTempFiles(cacheDir: File, nowMillis: Long, maxAgeMillis: Long): Int {
    val backupName = Regex("(?:.*_\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}(?:\\.zip)?|.*_\\d+\\.dams|\\d+\\.zip|(?:restore|download)_\\d+\\.zip|import_\\d+\\.dams)")
    return cacheDir.listFiles()
        ?.filter { file ->
            backupName.matches(file.name) && nowMillis - file.lastModified() >= maxAgeMillis
        }
        ?.count { if (it.isDirectory) it.deleteRecursively() else it.delete() }
        ?: 0
}
