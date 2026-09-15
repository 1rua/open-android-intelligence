package com.openandroidintelligence.gateway.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Software Ed25519 device key, wrapped by a Keystore AES key at rest.
 *
 * Android Keystore cannot generate Ed25519, but the platform JCA can sign with
 * it, so the key pair lives outside Keystore and its private bytes are stored
 * only as AES-GCM ciphertext under a non-exportable Keystore wrapping key —
 * the same envelope pattern the refresh credential uses. If the platform lacks
 * Ed25519 the store fails loudly instead of downgrading to another algorithm.
 *
 * The on-disk frame is `[iv 12][ciphertextLen 4][ciphertext][publicSpki]` so
 * the two halves never have to be told apart by guessing.
 */
class Ed25519DeviceKeyStore(private val storageDir: File) {

    fun publicKeyBase64Url(profileId: String): String =
        ed25519WirePublicKey(keyPair(profileId).public)

    fun sign(profileId: String, preimage: ByteArray): ByteArray {
        val signature = Signature.getInstance("Ed25519")
        signature.initSign(keyPair(profileId).private)
        signature.update(preimage)
        return signature.sign()
    }

    fun delete(profileId: String) {
        keystore().deleteEntry(wrappingKeyAlias(profileId))
        fileFor(profileId).delete()
    }

    private fun keyPair(profileId: String): KeyPair {
        val frame = fileFor(profileId).takeIf { it.exists() }?.readBytes()
        if (frame != null) {
            return readFrame(profileId, frame)
        }
        return generateAndStore(profileId)
    }

    private fun readFrame(profileId: String, frame: ByteArray): KeyPair {
        require(frame.size > IV_BYTES + LENGTH_BYTES) { "DEVICE_KEY_MALFORMED:$profileId" }
        val iv = frame.copyOfRange(0, IV_BYTES)
        val ciphertextLen = readInt(frame, IV_BYTES)
        val ciphertextStart = IV_BYTES + LENGTH_BYTES
        val ciphertextEnd = ciphertextStart + ciphertextLen
        require(ciphertextEnd in (ciphertextStart + 1)..(frame.size - 1)) {
            "DEVICE_KEY_MALFORMED:$profileId"
        }
        val ciphertext = frame.copyOfRange(ciphertextStart, ciphertextEnd)
        val publicSpki = frame.copyOfRange(ciphertextEnd, frame.size)

        val plaintext = Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, wrappingKey(profileId), GCMParameterSpec(TAG_BITS, iv))
            doFinal(ciphertext)
        }
        val factory = KeyFactory.getInstance("Ed25519")
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(plaintext))
        val publicKey = factory.generatePublic(X509EncodedKeySpec(publicSpki))
        return KeyPair(publicKey, privateKey)
    }

    private fun generateAndStore(profileId: String): KeyPair {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(profileId))
        val ciphertext = cipher.doFinal(pair.private.encoded)
        val frame = cipher.iv + intToBytes(ciphertext.size) + ciphertext + pair.public.encoded
        storageDir.mkdirs()
        fileFor(profileId).writeBytes(frame)
        return pair
    }

    private fun wrappingKey(profileId: String): SecretKey {
        val alias = wrappingKeyAlias(profileId)
        val store = keystore()
        val existing = store.getKey(alias, null)
        if (existing != null) return existing as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keystore(): KeyStore =
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun encodedId(profileId: String): String {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(profileId.toByteArray(Charsets.UTF_8))
    }

    private fun fileFor(profileId: String): File =
        File(storageDir, "device_ed25519_${encodedId(profileId)}.bin")

    private fun wrappingKeyAlias(profileId: String): String =
        "open_android_intelligence_device_ed25519_wrap_${encodedId(profileId)}"

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_BYTES = 12
        const val LENGTH_BYTES = 4
    }
}
