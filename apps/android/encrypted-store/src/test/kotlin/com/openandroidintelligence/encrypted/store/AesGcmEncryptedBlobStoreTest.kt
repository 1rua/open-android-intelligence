package com.openandroidintelligence.encrypted.store

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class AesGcmEncryptedBlobStoreTest {
    @Test
    fun current_writer_preserves_the_v1_envelope_and_accepts_existing_caller_iv_ciphertext() {
        val persistence = InMemoryOutboxPersistence()
        val key = SecretKeySpec(ByteArray(32) { 21 }, "AES")
        val plain = "旧镜像仍能被当前代码读取".encodeToByteArray()
        persistence.write(envelopeWithIv(key, ByteArray(12) { 7 }, plain))
        val store = AesGcmEncryptedBlobStore(persistence, key)
        assertArrayEquals(plain, store.readPlaintext())

        store.writePlaintext(plain)
        val firstIv = storedIv(checkNotNull(persistence.bytes))
        store.writePlaintext(plain)
        val secondIv = storedIv(checkNotNull(persistence.bytes))
        assertEquals(12, firstIv.size)
        assertEquals(12, secondIv.size)
        assertFalse("每次加密必须使用新的初始化向量", firstIv.contentEquals(secondIv))
        assertArrayEquals(plain, store.readPlaintext())
    }
    @Test
    fun ciphertext_never_contains_plaintext_and_restores_with_fixed_256_bit_key() {
        val persistence = InMemoryOutboxPersistence()
        val key = SecretKeySpec(ByteArray(32) { (it + 3).toByte() }, "AES")
        val plain = "call metadata that must not persist in plaintext".encodeToByteArray()
        AesGcmEncryptedBlobStore(persistence, key).writePlaintext(plain)

        assertFalse(persistence.bytes!!.decodeToString().contains("call metadata"))
        assertArrayEquals(plain, AesGcmEncryptedBlobStore(persistence, key).readPlaintext())
    }

    @Test
    fun malformed_or_unauthenticated_envelopes_are_redacted_as_corruption() {
        val persistence = InMemoryOutboxPersistence()
        val key = SecretKeySpec(ByteArray(32) { 1 }, "AES")
        val store = AesGcmEncryptedBlobStore(persistence, key)
        store.writePlaintext(byteArrayOf(1, 2, 3))
        val good = checkNotNull(persistence.bytes)

        listOf(
            good.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() },
            good.copyOf(4),
            good.copyOf().also { it[4] = 'X'.code.toByte() },
            good + byteArrayOf(7),
        ).forEach { corrupted ->
            persistence.write(corrupted)
            val failure = assertThrows(EncryptedBlobCorrupted::class.java) { store.readPlaintext() }
            org.junit.Assert.assertEquals("ENCRYPTED_BLOB_CORRUPTED", failure.message)
            org.junit.Assert.assertNull(failure.cause)
        }

        persistence.write(good)
        val wrongKey = AesGcmEncryptedBlobStore(persistence, SecretKeySpec(ByteArray(32) { 2 }, "AES"))
        assertThrows(EncryptedBlobCorrupted::class.java) { wrongKey.readPlaintext() }
    }

    @Test
    fun non_standard_iv_length_is_rejected_even_when_gcm_can_authenticate_it() {
        val persistence = InMemoryOutboxPersistence()
        val key = SecretKeySpec(ByteArray(32) { 6 }, "AES")
        persistence.write(envelopeWithIv(key, ByteArray(11) { 3 }, byteArrayOf(7)))

        val failure = assertThrows(EncryptedBlobCorrupted::class.java) {
            AesGcmEncryptedBlobStore(persistence, key).readPlaintext()
        }
        org.junit.Assert.assertEquals("ENCRYPTED_BLOB_CORRUPTED", failure.message)
        org.junit.Assert.assertNull(failure.cause)
    }

    @Test
    fun copies_input_output_and_persistence_boundaries() {
        val persistence = InMemoryOutboxPersistence()
        val store = AesGcmEncryptedBlobStore(persistence, SecretKeySpec(ByteArray(32) { 5 }, "AES"))
        val input = byteArrayOf(3, 4, 5)
        store.writePlaintext(input)
        input[0] = 99
        val returned = checkNotNull(store.readPlaintext())
        returned[1] = 99
        assertArrayEquals(byteArrayOf(3, 4, 5), store.readPlaintext())
        store.clearCiphertext()
        assertNull(store.readPlaintext())
    }

    @Test
    fun assertion_error_from_persistence_is_not_reclassified_as_corruption() {
        val persistence = object : EncryptedOutboxPersistence {
            override fun read(): ByteArray? = throw AssertionError("test seam")
            override fun write(ciphertext: ByteArray) = Unit
            override fun clear() = Unit
        }
        val store = AesGcmEncryptedBlobStore(persistence, SecretKeySpec(ByteArray(32) { 1 }, "AES"))
        assertThrows(AssertionError::class.java) { store.readPlaintext() }
    }

    private fun envelopeWithIv(key: SecretKeySpec, iv: ByteArray, plain: ByteArray): ByteArray {
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            doFinal(plain)
        }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                val magic = "OPEN_ANDROID_INTELLIGENCE_AES_GCM_BLOB_V1".encodeToByteArray()
                output.writeInt(magic.size)
                output.write(magic)
                output.writeInt(iv.size)
                output.write(iv)
                output.writeInt(ciphertext.size)
                output.write(ciphertext)
            }
            bytes.toByteArray()
        }
    }

    private fun storedIv(envelope: ByteArray): ByteArray = DataInputStream(ByteArrayInputStream(envelope)).use { input ->
        val magic = ByteArray(input.readInt()).also(input::readFully).decodeToString()
        assertEquals("OPEN_ANDROID_INTELLIGENCE_AES_GCM_BLOB_V1", magic)
        ByteArray(input.readInt()).also(input::readFully)
    }
}
