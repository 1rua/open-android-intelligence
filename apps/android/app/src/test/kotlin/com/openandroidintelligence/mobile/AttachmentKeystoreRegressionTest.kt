package com.openandroidintelligence.mobile

import com.openandroidintelligence.encrypted.store.EncryptedAttachmentStagingStore
import com.openandroidintelligence.encrypted.store.EncryptedAttachmentCorrupted
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentKeystoreRegressionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun singleChunkStagesAndRestoresWithANonExportableRandomizedEncryptionKey() = withRandomizedKey { key ->
        val store = EncryptedAttachmentStagingStore(temporaryFolder.root, key, SCOPE, CHUNK_BYTES)
        val plain = "附件样本".encodeToByteArray()

        val staged = store.stage(ByteArrayInputStream(plain))

        assertArrayEquals(plain, store.openStream(staged.id).use { it.readBytes() })
    }

    @Test
    fun multiChunkAndEmptyAttachmentsKeepTheirExactContentAcrossStoreRecreation() = withRandomizedKey { key ->
        for (plain in listOf(ByteArray(CHUNK_BYTES * 2 + 7) { it.toByte() }, ByteArray(0))) {
            val store = EncryptedAttachmentStagingStore(temporaryFolder.root, key, SCOPE, CHUNK_BYTES)
            val staged = store.stage(ByteArrayInputStream(plain))
            val recreated = EncryptedAttachmentStagingStore(temporaryFolder.root, key, SCOPE, CHUNK_BYTES)

            assertEquals(plain.size.toLong(), staged.sizeBytes)
            assertEquals(sha256(plain).toHex(), staged.sha256Hex)
            assertArrayEquals(plain, recreated.openStream(staged.id).use { it.readBytes() })
        }
    }

    @Test
    fun everyChunkAndFooterUseFreshIvsAndDecryptWithAnIndependentV1Reader() = withRandomizedKey { key ->
        val store = EncryptedAttachmentStagingStore(temporaryFolder.root, key, SCOPE, CHUNK_BYTES)
        val plain = ByteArray(CHUNK_BYTES * 2 + 7) { (it * 7).toByte() }
        val nonces = mutableListOf<ByteArray>()

        repeat(2) {
            val staged = store.stage(ByteArrayInputStream(plain))
            nonces += inspectV1(fileFor(staged.id), key.delegate, staged.id, SCOPE, plain)
        }
        val empty = store.stage(ByteArrayInputStream(ByteArray(0)))
        nonces += inspectV1(fileFor(empty.id), key.delegate, empty.id, SCOPE, ByteArray(0))

        assertEquals(9, nonces.size)
        assertTrue(nonces.all { it.size == IV_BYTES })
        assertEquals("数据块、结尾校验块与重复暂存不能复用 IV", nonces.size, nonces.map { it.toHex() }.toSet().size)
    }

    @Test
    fun existingV1FilesWithCallerGeneratedIvsRemainReadable() = withRandomizedKey { key ->
        val store = EncryptedAttachmentStagingStore(temporaryFolder.root, key, SCOPE, CHUNK_BYTES)
        val id = "ast_0123456789abcdef0123456789abcdef"

        for (plain in listOf(ByteArray(CHUNK_BYTES * 2 + 7) { (it + 11).toByte() }, ByteArray(0))) {
            fileFor(id).writeBytes(legacyV1(key.delegate, id, SCOPE, plain))

            assertArrayEquals(plain, store.openStream(id).use { it.readBytes() })
        }
    }

    @Test
    fun changingAccountScopeRejectsTheBytesEvenWhenTheKeyIsTheSame() = withRandomizedKey { key ->
        val store = EncryptedAttachmentStagingStore(temporaryFolder.root, key, SCOPE, CHUNK_BYTES)
        val staged = store.stage(ByteArrayInputStream(ByteArray(CHUNK_BYTES + 5) { it.toByte() }))
        val anotherAccount = EncryptedAttachmentStagingStore(
            temporaryFolder.root, key, "gateway-a/account-b/install-a", CHUNK_BYTES,
        )

        assertThrows(EncryptedAttachmentCorrupted::class.java) {
            anotherAccount.openStream(staged.id).use { it.readBytes() }
        }
    }

    @Test
    fun modifiedChunksModifiedFooterTruncationAndTrailingBytesAreRejected() = withRandomizedKey { key ->
        val store = EncryptedAttachmentStagingStore(temporaryFolder.root, key, SCOPE, CHUNK_BYTES)
        val plain = ByteArray(CHUNK_BYTES * 2 + 7) { it.toByte() }
        val staged = store.stage(ByteArrayInputStream(plain))
        val file = fileFor(staged.id)
        val original = file.readBytes()
        val firstCipherByte = DataInputStream(ByteArrayInputStream(original)).use { input ->
            input.readUTF()
            input.readUTF()
            input.readInt()
            input.readInt()
            input.readFully(ByteArray(IV_BYTES))
            input.readInt()
            original.size - input.available()
        }
        val modifiedChunk = original.copyOf().also { it[firstCipherByte] = (it[firstCipherByte].toInt() xor 1).toByte() }
        val modifiedFooter = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

        for (invalid in listOf(modifiedChunk, modifiedFooter, original.copyOf(original.size - 1), original + byteArrayOf(0))) {
            file.writeBytes(invalid)

            assertThrows(EncryptedAttachmentCorrupted::class.java) {
                store.openStream(staged.id).use { it.readBytes() }
            }
        }
    }

    @Test
    fun authenticatedFooterMustMatchTheActualLengthAndDigest() = withRandomizedKey { key ->
        val store = EncryptedAttachmentStagingStore(temporaryFolder.root, key, SCOPE, CHUNK_BYTES)
        val id = "ast_0123456789abcdef0123456789abcdef"
        val plain = ByteArray(CHUNK_BYTES + 5) { it.toByte() }
        val invalidEnvelopes = listOf(
            legacyV1(key.delegate, id, SCOPE, plain, footerLength = plain.size.toLong() + 1),
            legacyV1(key.delegate, id, SCOPE, plain, footerDigest = ByteArray(SHA256_BYTES)),
        )

        for (invalid in invalidEnvelopes) {
            fileFor(id).writeBytes(invalid)

            assertThrows(EncryptedAttachmentCorrupted::class.java) {
                store.openStream(id).use { it.readBytes() }
            }
        }
    }

    // 独立解析公开的 V1 信封，并直接调用平台 Cipher，不使用生产解密或 AAD helper。
    private fun inspectV1(file: File, key: SecretKey, id: String, scope: String, expected: ByteArray): List<ByteArray> {
        val nonces = mutableListOf<ByteArray>()
        DataInputStream(file.inputStream()).use { input ->
            assertEquals(V1_MAGIC, input.readUTF())
            assertEquals(id, input.readUTF())
            assertEquals(CHUNK_BYTES, input.readInt())
            val content = ByteArrayOutputStream()
            var index = 0L
            while (true) {
                val length = input.readInt()
                val nonce = ByteArray(IV_BYTES).also(input::readFully)
                nonces += nonce
                val encryptedLength = input.readInt()
                val encrypted = ByteArray(encryptedLength).also(input::readFully)
                if (length == 0) {
                    assertEquals(Long.SIZE_BYTES + SHA256_BYTES + TAG_BYTES, encryptedLength)
                    val footer = ByteBuffer.wrap(decryptV1(key, nonce, footerAad(scope, id, index), encrypted))
                    assertEquals(expected.size.toLong(), footer.long)
                    assertArrayEquals(sha256(expected), ByteArray(SHA256_BYTES).also(footer::get))
                    assertEquals(0, footer.remaining())
                    assertEquals(-1, input.read())
                    break
                }
                assertTrue(length in 1..CHUNK_BYTES)
                assertEquals(length + TAG_BYTES, encryptedLength)
                val plain = decryptV1(key, nonce, chunkAad(scope, id, index, length), encrypted)
                assertEquals(length, plain.size)
                content.write(plain)
                index++
            }
            assertArrayEquals(expected, content.toByteArray())
        }
        return nonces
    }

    // 用软件密钥和调用方 IV 独立编码旧 V1 文件，兼容断言不依赖当前生产写入器。
    private fun legacyV1(
        key: SecretKey,
        id: String,
        scope: String,
        plain: ByteArray,
        footerLength: Long = plain.size.toLong(),
        footerDigest: ByteArray = sha256(plain),
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeUTF(V1_MAGIC)
            output.writeUTF(id)
            output.writeInt(CHUNK_BYTES)
            var index = 0L
            var offset = 0
            while (offset < plain.size) {
                val length = minOf(CHUNK_BYTES, plain.size - offset)
                val nonce = ByteArray(IV_BYTES) { (index + 1).toByte() }
                val encrypted = encryptV1(key, nonce, chunkAad(scope, id, index, length), plain.copyOfRange(offset, offset + length))
                output.writeInt(length)
                output.write(nonce)
                output.writeInt(encrypted.size)
                output.write(encrypted)
                offset += length
                index++
            }
            val footer = ByteBuffer.allocate(Long.SIZE_BYTES + SHA256_BYTES).putLong(footerLength).put(footerDigest).array()
            val nonce = ByteArray(IV_BYTES) { (index + 1).toByte() }
            val encrypted = encryptV1(key, nonce, footerAad(scope, id, index), footer)
            output.writeInt(0)
            output.write(nonce)
            output.writeInt(encrypted.size)
            output.write(encrypted)
        }
        bytes.toByteArray()
    }

    private fun decryptV1(key: SecretKey, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding", "SunJCE").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
            doFinal(ciphertext)
        }

    private fun encryptV1(key: SecretKey, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding", "SunJCE").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
            doFinal(plain)
        }

    private fun chunkAad(scope: String, id: String, index: Long, length: Int): ByteArray =
        "$V1_MAGIC\u0000$scope\u0000$id\u0000$index\u0000$length".encodeToByteArray()

    private fun footerAad(scope: String, id: String, chunks: Long): ByteArray =
        "$V1_MAGIC\u0000$scope\u0000$id\u0000footer\u0000$chunks".encodeToByteArray()

    private fun sha256(plain: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(plain)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun fileFor(id: String) = File(temporaryFolder.root, "$id.stage")

    private fun withRandomizedKey(block: (NonExportableAesKey) -> Unit) {
        check(Security.insertProviderAt(RandomizedEncryptionRequiredProvider(), 1) == 1)
        try {
            block(NonExportableAesKey())
        } finally {
            Security.removeProvider(RandomizedEncryptionRequiredProvider.NAME)
        }
    }

    private companion object {
        const val SCOPE = "gateway-a/account-a/install-a"
        const val CHUNK_BYTES = 32
        const val V1_MAGIC = "OPEN_ANDROID_INTELLIGENCE_ATTACHMENT_STAGE_V1"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        const val SHA256_BYTES = 32
    }
}
