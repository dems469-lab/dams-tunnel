package com.v2ray.ang.premium

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface PremiumTokenReadResult {
    data object Missing : PremiumTokenReadResult
    data class Available(val token: String) : PremiumTokenReadResult
    data object ReentryRequired : PremiumTokenReadResult
}

/** Stocke uniquement le token chiffré dans noBackupFilesDir avec une clé Android Keystore. */
class PremiumTokenStore(context: Context) {
    private val tokenFile = AtomicFile(
        File(File(context.noBackupFilesDir, DIRECTORY).apply { mkdirs() }, FILE_NAME),
    )

    @Synchronized
    fun saveToken(token: String) {
        require(token.isNotBlank() && token.length <= MAX_TOKEN_LENGTH) { "Code Premium invalide" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val envelope = byteArrayOf(FORMAT_VERSION, iv.size.toByte()) + iv + ciphertext

        val stream = tokenFile.startWrite()
        try {
            stream.write(envelope)
            tokenFile.finishWrite(stream)
        } catch (error: Exception) {
            tokenFile.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun readToken(): PremiumTokenReadResult {
        val envelope = try {
            tokenFile.openRead().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(256)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_ENVELOPE_SIZE) return requireReentry()
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } catch (_: java.io.FileNotFoundException) {
            return PremiumTokenReadResult.Missing
        } catch (_: Exception) {
            return requireReentry()
        }
        return try {
            require(envelope.size > 2 && envelope[0] == FORMAT_VERSION)
            val ivSize = envelope[1].toInt() and 0xff
            require(ivSize in MIN_IV_SIZE..MAX_IV_SIZE && envelope.size > 2 + ivSize)
            val iv = envelope.copyOfRange(2, 2 + ivSize)
            val ciphertext = envelope.copyOfRange(2 + ivSize, envelope.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(TAG_BITS, iv))
            val token = cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
            if (token.isBlank() || token.length > MAX_TOKEN_LENGTH) requireReentry()
            else PremiumTokenReadResult.Available(token)
        } catch (_: AEADBadTagException) {
            requireReentry()
        } catch (_: Exception) {
            requireReentry()
        }
    }

    @Synchronized
    fun clear() {
        tokenFile.delete()
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun requireReentry(): PremiumTokenReadResult.ReentryRequired {
        tokenFile.delete()
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.deleteEntry(KEY_ALIAS)
        }
        return PremiumTokenReadResult.ReentryRequired
    }

    private fun getExistingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Clé Premium absente")
    }

    private fun getOrCreateKey(): SecretKey = getExistingKeyOrNull() ?: run {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    }

    private fun getExistingKeyOrNull(): SecretKey? = runCatching { getExistingKey() }.getOrNull()

    private companion object {
        const val DIRECTORY = "premium"
        const val FILE_NAME = "token.aesgcm"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.v2ray.ang.premium.token.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
        const val TAG_BITS = 128
        const val MIN_IV_SIZE = 12
        const val MAX_IV_SIZE = 16
        const val MAX_TOKEN_LENGTH = 512
        const val MAX_ENVELOPE_SIZE = MAX_TOKEN_LENGTH * 4 + MAX_IV_SIZE + 32
    }
}
