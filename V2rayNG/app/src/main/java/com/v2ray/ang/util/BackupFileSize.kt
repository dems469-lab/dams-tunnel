package com.v2ray.ang.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

object BackupFileSize {
    const val MAX_BYTES = 5 * 1024 * 1024
}

class BackupFileTooLargeException : Exception("Backup file exceeds the 5 MiB limit")

fun checkBackupFileSize(declaredSize: Long?) {
    if (declaredSize != null && declaredSize > BackupFileSize.MAX_BYTES) {
        throw BackupFileTooLargeException()
    }
}

fun ContentResolver.getDeclaredFileSize(uri: Uri): Long? =
    query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst() && sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
            cursor.getLong(sizeIndex).takeIf { it >= 0 }
        } else {
            null
        }
    }

fun InputStream.readBytesLimited(maxBytes: Int = BackupFileSize.MAX_BYTES): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    copyToLimited(output, maxBytes)
    return output.toByteArray()
}

fun InputStream.copyToLimited(output: OutputStream, maxBytes: Int = BackupFileSize.MAX_BYTES): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) return total
        total += read
        if (total > maxBytes) throw BackupFileTooLargeException()
        output.write(buffer, 0, read)
    }
}
