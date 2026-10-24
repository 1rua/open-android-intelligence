package com.openandroidintelligence.tailnet.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore AES-GCM encryption over an app-private no-backup file. The
 * Go state callback only ever observes the ciphertext envelope.
 */
class KeystoreEncryptedNoBackupState(
    private val file: File,
    private val alias: String = DEFAULT_ALIAS,
) : EncryptedNoBackupState {

    override fun read(): ByteArray? {
        if (!file.isFile) return null
        val envelope = file.readBytes()
        require(envelope.size > IV_SIZE + TAG_BYTES) { "node state envelope is truncated" }
        val iv = envelope.copyOfRange(0, IV_SIZE)
        val ciphertext = envelope.copyOfRange(IV_SIZE, envelope.size)
        return decrypt(ciphertext, iv)
    }

    override fun write(value: ByteArray) {
        require(value.isNotEmpty()) { "node state must not be empty" }
        // 方案②（用户已确认）：Keystore 在 randomized-encryption 下自行生成 IV，
        // 加密后从 cipher.iv 取回并写进信封；信封格式不变（iv(12B)||ciphertext）。
        val iv = ByteArray(IV_SIZE)
        val ciphertext = encrypt(value, iv)
        val envelope = ByteArray(IV_SIZE + ciphertext.size)
        iv.copyInto(envelope, 0)
        ciphertext.copyInto(envelope, IV_SIZE)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile ?: file.absoluteFile.parentFile!!, "${file.name}.tmp")
        temporary.writeBytes(envelope)
        check(temporary.renameTo(file)) { "unable to atomically persist node state" }
    }

    override fun clear() {
        file.delete()
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        } catch (_: Exception) {
            // Absent alias is equivalent to a cleared node state.
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(alias, null)
        if (existing is SecretKey) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
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

    private fun encrypt(plaintext: ByteArray, ivOut: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // 不传 GCMParameterSpec：Keystore 在 RandomizedEncryptionRequired(true)
        // 下手动生成随机 IV，随后从 cipher.iv 读回写入信封。
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val generated = checkNotNull(cipher.iv) { "keystore must provide a generated IV" }
        require(generated.size == IV_SIZE) { "keystore IV size unexpected: ${generated.size}" }
        generated.copyInto(ivOut, 0)
        return cipher.doFinal(plaintext)
    }

    private fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        const val DEFAULT_ALIAS = "open_android_intelligence_tailnet_node_state_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
    }
}
