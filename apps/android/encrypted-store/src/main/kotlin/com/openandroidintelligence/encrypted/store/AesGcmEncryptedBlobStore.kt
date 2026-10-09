package com.openandroidintelligence.encrypted.store

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface AesGcmKeyProvider {
    fun getOrCreate(): SecretKey
    fun delete()
}

class EncryptedBlobCorrupted : IllegalStateException("ENCRYPTED_BLOB_CORRUPTED")

/**
 * Small authenticated persistence primitive.  Its envelope is self-delimiting,
 * so malformed, unknown and trailing bytes all fail closed before callers see
 * plaintext or provider/crypto diagnostics.
 */
class AesGcmEncryptedBlobStore(
    private val persistence: EncryptedOutboxPersistence,
    private val key: SecretKey,
) {
    init {
        require(key.algorithm.equals("AES", ignoreCase = true)) { "encrypted blob key must use AES" }
        key.encoded?.let { require(it.size == AES_256_BYTES) { "encrypted blob key must be 256 bits" } }
    }

    fun readPlaintext(): ByteArray? {
        val envelope = persistence.read()?.copyOf() ?: return null
        return try {
            DataInputStream(ByteArrayInputStream(envelope)).use { input ->
                check(readString(input) == MAGIC)
                val iv = readField(input, IV_BYTES)
                check(iv.size == IV_BYTES)
                val ciphertext = readField(input, MAX_CIPHERTEXT_BYTES)
                check(ciphertext.size >= TAG_BYTES + 1)
                check(input.available() == 0)
                decrypt(iv, ciphertext)
            }
        } catch (_: EncryptedBlobCorrupted) {
            throw EncryptedBlobCorrupted()
        } catch (_: Exception) {
            throw EncryptedBlobCorrupted()
        }
    }

    fun writePlaintext(value: ByteArray) {
        require(value.isNotEmpty()) { "encrypted blob plaintext must not be empty" }
        val plain = value.copyOf()
        val (iv, ciphertext) = try {
            Cipher.getInstance(TRANSFORMATION).run {
                // Android Keystore 的随机化加密密钥要求提供者生成 IV，不能由调用方传入。
                init(Cipher.ENCRYPT_MODE, key)
                val generatedIv = iv?.copyOf() ?: throw EncryptedBlobCorrupted()
                if (generatedIv.size != IV_BYTES) throw EncryptedBlobCorrupted()
                generatedIv to doFinal(plain)
            }
        } catch (_: GeneralSecurityException) {
            throw EncryptedBlobCorrupted()
        } finally {
            plain.fill(0)
        }
        val envelope = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                writeString(output, MAGIC)
                writeField(output, iv)
                writeField(output, ciphertext)
            }
            bytes.toByteArray()
        }
        persistence.write(envelope.copyOf())
    }

    fun clearCiphertext() = persistence.clear()

    private fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray = try {
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            doFinal(ciphertext)
        }.copyOf()
    } catch (_: GeneralSecurityException) {
        throw EncryptedBlobCorrupted()
    }

    private fun readString(input: DataInputStream): String = readField(input, MAX_MAGIC_BYTES).decodeToString()

    private fun writeString(output: DataOutputStream, value: String) = writeField(output, value.encodeToByteArray())

    private fun readField(input: DataInputStream, maximum: Int): ByteArray {
        val length = input.readInt()
        check(length in 0..maximum)
        return ByteArray(length).also(input::readFully)
    }

    private fun writeField(output: DataOutputStream, value: ByteArray) {
        output.writeInt(value.size)
        output.write(value)
    }

    private companion object {
        const val MAGIC = "OPEN_ANDROID_INTELLIGENCE_AES_GCM_BLOB_V1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_256_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        const val MAX_MAGIC_BYTES = 128
        const val MAX_CIPHERTEXT_BYTES = 16 * 1_024 * 1_024
    }
}
