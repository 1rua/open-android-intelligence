package com.openandroidintelligence.gateway.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed credential store.
 *
 * Each account gets its own wrapping key, its own device key pair and its own
 * ciphertext file, all addressed by [profileId]. Material belonging to one
 * account is therefore unreachable through another account's id, and deleting
 * one account's aliases leaves the others untouched.
 *
 * The keys are generated inside Keystore and are non-exportable: the refresh
 * credential is only ever decrypted into this process.
 */
class AndroidKeystoreGatewayCredentialStore(
    private val storageDir: File,
) : GatewayCredentialStore {

    @Synchronized
    override fun saveRefresh(profileId: String, credential: ByteArray) {
        require(credential.isNotEmpty()) { "refresh credential must not be empty" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(profileId))
        val ciphertext = cipher.doFinal(credential.copyOf())
        val file = AtomicFile(fileFor(profileId))
        val output = file.startWrite()
        try {
            output.write(cipher.iv + ciphertext)
            file.finishWrite(output)
        } catch (cause: Throwable) {
            file.failWrite(output)
            throw cause
        }
    }

    @Synchronized
    override fun loadRefresh(profileId: String): ByteArray? {
        val file = AtomicFile(fileFor(profileId))
        val envelope = try { file.openRead().use { it.readBytes() } }
        catch (_: java.io.FileNotFoundException) { return null }
        if (envelope.size <= IV_BYTES) return null
        val iv = envelope.copyOfRange(0, IV_BYTES)
        val ciphertext = envelope.copyOfRange(IV_BYTES, envelope.size)
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, wrappingKey(profileId), GCMParameterSpec(TAG_BITS, iv))
            doFinal(ciphertext)
        }
    }

    override fun clearRefresh(profileId: String) {
        AtomicFile(fileFor(profileId)).delete()
    }

    override fun clearDeviceKey(profileId: String) {
        keystore().apply {
            deleteEntry(deviceKeyAlias(profileId))
            deleteEntry(wrappingKeyAlias(profileId))
        }
        AtomicFile(fileFor(profileId)).delete()
    }

    /** Device identity key pair, generated on first use and non-exportable. */
    fun deviceKey(profileId: String): java.security.KeyPair {
        val alias = deviceKeyAlias(profileId)
        val store = keystore()
        val existing = store.getKey(alias, null)
        if (existing != null) {
            val certificate = store.getCertificate(alias) ?: error("DEVICE_KEY_WITHOUT_CERTIFICATE")
            return java.security.KeyPair(certificate.publicKey, existing as java.security.PrivateKey)
        }
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER).run {
            initialize(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generateKeyPair()
        }
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

    /**
     * The id is encoded rather than interpolated: a profile id is external
     * input and must never be able to escape [storageDir] or alias namespace.
     */
    private fun encodedId(profileId: String): String {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(profileId.toByteArray(Charsets.UTF_8))
    }

    private fun fileFor(profileId: String): File = File(storageDir, "gateway_refresh_${encodedId(profileId)}.bin")

    private fun wrappingKeyAlias(profileId: String): String = "open_android_intelligence_gateway_refresh_${encodedId(profileId)}"

    private fun deviceKeyAlias(profileId: String): String = "open_android_intelligence_gateway_device_${encodedId(profileId)}"

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
