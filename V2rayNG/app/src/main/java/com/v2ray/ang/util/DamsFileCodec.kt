package com.v2ray.ang.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-protected, versioned container used by Dams Tunnel exports. */
object DamsFileCodec {
    private val magic = "DAMS1".toByteArray(StandardCharsets.US_ASCII)
    private const val saltSize = 16
    private const val nonceSize = 12
    private const val tagBits = 128
    private const val keyBits = 256
    private const val iterations = 210_000
    private const val noExpiry = 0L

    data class ExportOptions(val password: String, val expiresAtEpochMillis: Long? = null)

    class InvalidFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun encrypt(payload: ByteArray, options: ExportOptions, nowEpochMillis: Long = System.currentTimeMillis()): ByteArray {
        require(options.password.isNotEmpty()) { "A password is required for a .dams export" }
        require(options.expiresAtEpochMillis == null || options.expiresAtEpochMillis > nowEpochMillis) {
            "The expiration date must be in the future"
        }

        val salt = ByteArray(saltSize)
        val nonce = ByteArray(nonceSize)
        SecureRandom().nextBytes(salt)
        SecureRandom().nextBytes(nonce)
        val expiresAt = options.expiresAtEpochMillis ?: noExpiry
        val header = ByteBuffer.allocate(magic.size + Long.SIZE_BYTES + Long.SIZE_BYTES + saltSize + nonceSize)
            .put(magic)
            .putLong(nowEpochMillis)
            .putLong(expiresAt)
            .put(salt)
            .put(nonce)
            .array()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(options.password, salt), GCMParameterSpec(tagBits, nonce))
        cipher.updateAAD(header)
        val ciphertext = cipher.doFinal(payload)
        return ByteArrayOutputStream(header.size + ciphertext.size).apply {
            write(header)
            write(ciphertext)
        }.toByteArray()
    }

    fun decrypt(container: ByteArray, password: String, nowEpochMillis: Long = System.currentTimeMillis()): ByteArray {
        if (password.isEmpty()) throw InvalidFileException("A password is required")
        val headerSize = magic.size + Long.SIZE_BYTES + Long.SIZE_BYTES + saltSize + nonceSize
        if (container.size <= headerSize || !container.copyOfRange(0, magic.size).contentEquals(magic)) {
            throw InvalidFileException("Not a valid .dams file")
        }

        try {
            val input = ByteArrayInputStream(container)
            val header = ByteArray(headerSize)
            check(input.read(header) == headerSize)
            val buffer = ByteBuffer.wrap(header)
            val actualMagic = ByteArray(magic.size)
            buffer.get(actualMagic)
            check(actualMagic.contentEquals(magic))
            buffer.long
            val expiresAt = buffer.long
            val salt = ByteArray(saltSize)
            val nonce = ByteArray(nonceSize)
            buffer.get(salt)
            buffer.get(nonce)
            if (expiresAt != noExpiry && nowEpochMillis >= expiresAt) {
                throw InvalidFileException("This .dams file has expired")
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(tagBits, nonce))
            cipher.updateAAD(header)
            return cipher.doFinal(input.readBytes())
        } catch (e: InvalidFileException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw InvalidFileException("Incorrect password or corrupted .dams file", e)
        } catch (e: Exception) {
            throw InvalidFileException("Invalid .dams file", e)
        }
    }

    fun isDamsFile(bytes: ByteArray): Boolean = bytes.size >= magic.size && bytes.copyOfRange(0, magic.size).contentEquals(magic)

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyBits)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES"
            )
        } finally {
            spec.clearPassword()
        }
    }
}
