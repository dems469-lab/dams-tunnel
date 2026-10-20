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
    private val legacyMagic = "DAMS1".toByteArray(StandardCharsets.US_ASCII)
    private val magic = "DAMS2".toByteArray(StandardCharsets.US_ASCII)
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
        // DAMS2 intentionally keeps only cryptographic material in clear text.  Creation
        // and expiry metadata stay inside the AES-GCM payload so a copied file does not
        // reveal its owner’s usage period before a successful password check.
        val header = ByteBuffer.allocate(magic.size + saltSize + nonceSize)
            .put(magic)
            .put(salt)
            .put(nonce)
            .array()
        val protectedPayload = ByteBuffer.allocate(Long.SIZE_BYTES + Long.SIZE_BYTES + payload.size)
            .putLong(nowEpochMillis)
            .putLong(options.expiresAtEpochMillis ?: noExpiry)
            .put(payload)
            .array()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(options.password, salt), GCMParameterSpec(tagBits, nonce))
        cipher.updateAAD(header)
        val ciphertext = cipher.doFinal(protectedPayload)
        return ByteArrayOutputStream(header.size + ciphertext.size).apply {
            write(header)
            write(ciphertext)
        }.toByteArray()
    }

    fun decrypt(container: ByteArray, password: String, nowEpochMillis: Long = System.currentTimeMillis()): ByteArray {
        if (password.isEmpty()) throw InvalidFileException("A password is required")
        return when {
            container.size >= magic.size && container.copyOfRange(0, magic.size).contentEquals(magic) ->
                decryptV2(container, password, nowEpochMillis)

            container.size >= legacyMagic.size && container.copyOfRange(0, legacyMagic.size).contentEquals(legacyMagic) ->
                decryptV1(container, password, nowEpochMillis)

            else -> throw InvalidFileException("Not a valid .dams file")
        }
    }

    private fun decryptV2(container: ByteArray, password: String, nowEpochMillis: Long): ByteArray {
        val headerSize = magic.size + saltSize + nonceSize
        if (container.size <= headerSize) throw InvalidFileException("Not a valid .dams file")

        try {
            val input = ByteArrayInputStream(container)
            val header = ByteArray(headerSize)
            check(input.read(header) == headerSize)
            val buffer = ByteBuffer.wrap(header)
            val actualMagic = ByteArray(magic.size)
            buffer.get(actualMagic)
            check(actualMagic.contentEquals(magic))
            val salt = ByteArray(saltSize)
            val nonce = ByteArray(nonceSize)
            buffer.get(salt)
            buffer.get(nonce)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(tagBits, nonce))
            cipher.updateAAD(header)
            val protectedPayload = cipher.doFinal(input.readBytes())
            if (protectedPayload.size < Long.SIZE_BYTES * 2) throw InvalidFileException("Invalid .dams file")
            val payloadBuffer = ByteBuffer.wrap(protectedPayload)
            payloadBuffer.long // createdAt; available only after successful decryption
            val expiresAt = payloadBuffer.long
            if (expiresAt != noExpiry && nowEpochMillis >= expiresAt) {
                throw InvalidFileException("This .dams file has expired")
            }
            return ByteArray(payloadBuffer.remaining()).also(payloadBuffer::get)
        } catch (e: InvalidFileException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw InvalidFileException("Incorrect password or corrupted .dams file", e)
        } catch (e: Exception) {
            throw InvalidFileException("Invalid .dams file", e)
        }
    }

    /** Reads exports created by the first Dams Tunnel builds. New exports always use DAMS2. */
    private fun decryptV1(container: ByteArray, password: String, nowEpochMillis: Long): ByteArray {
        val headerSize = legacyMagic.size + Long.SIZE_BYTES + Long.SIZE_BYTES + saltSize + nonceSize
        if (container.size <= headerSize) throw InvalidFileException("Not a valid .dams file")
        try {
            val input = ByteArrayInputStream(container)
            val header = ByteArray(headerSize)
            check(input.read(header) == headerSize)
            val buffer = ByteBuffer.wrap(header)
            val actualMagic = ByteArray(legacyMagic.size)
            buffer.get(actualMagic)
            check(actualMagic.contentEquals(legacyMagic))
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

    fun isDamsFile(bytes: ByteArray): Boolean = bytes.size >= magic.size && (
        bytes.copyOfRange(0, magic.size).contentEquals(magic) ||
            bytes.copyOfRange(0, legacyMagic.size).contentEquals(legacyMagic)
        )

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
