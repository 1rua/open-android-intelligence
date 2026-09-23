package com.openandroidintelligence.encrypted.store

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedAttachmentStagingStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun stagesAndReopensContentLargerThanTheOldSixteenMibBlobLimitWithoutPlaintextOnDisk() {
        val totalBytes = 16L * 1024 * 1024 + 37
        val store = store()
        val expectedDigest = patternedDigest(totalBytes)

        val staged = store.stage(PatternInputStream(totalBytes))

        assertEquals(totalBytes, staged.sizeBytes)
        assertEquals(expectedDigest, staged.sha256Hex)
        val persisted = temporaryFolder.root.listFiles()!!.single { it.extension == "stage" }.readBytes()
        assertFalse(persisted.contentEquals(ByteArray(0)))
        assertFalse(containsPatternMarker(persisted))
        assertEquals(expectedDigest, digest(store.openStream(staged.id), totalBytes))
    }

    @Test
    fun authenticatedChunksRejectModificationAndDeleteRemovesTheStagedFile() {
        val store = store()
        val staged = store.stage(ByteArrayInputStream("private attachment".toByteArray()))
        val file = temporaryFolder.root.listFiles()!!.single { it.extension == "stage" }
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)

        assertThrows(EncryptedAttachmentCorrupted::class.java) {
            store.openStream(staged.id).use { it.readBytes() }
        }

        store.delete(staged.id)
        assertEquals(0, temporaryFolder.root.listFiles()!!.count { it.extension == "stage" })
    }

    @Test
    fun stagingFailureAndExplicitCleanupDoNotLeavePartialFiles() {
        val store = store()
        assertThrows(IllegalStateException::class.java) {
            store.stage(object : InputStream() {
                override fun read(): Int = throw IllegalStateException("source failed")
            })
        }

        assertEquals(0, temporaryFolder.root.listFiles()!!.size)
        val staged = store.stage(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        store.cleanup()
        assertFalse(temporaryFolder.root.listFiles()!!.any { it.name == "${staged.id}.stage" })
    }

    @Test
    fun aSyncFailureLeavesNoPromotedStageOrPartialFile() {
        val brokenStore = EncryptedAttachmentStagingStore(
            directory = temporaryFolder.root,
            key = SecretKeySpec(ByteArray(32) { 7 }, "AES"),
            scopeId = "gateway-a/account-a",
            chunkBytes = 32 * 1024,
            syncFileDescriptor = { throw IOException("simulated disk sync failure") },
        )

        assertThrows(AttachmentStorageUnavailable::class.java) {
            brokenStore.stage(ByteArrayInputStream("must not appear as a completed stage".toByteArray()))
        }
        assertEquals("sync failure must clean both temp and final names", 0, temporaryFolder.root.listFiles()!!.size)
    }

    @Test
    fun aRenameFailureLeavesNoPartialOrUnusableStage() {
        val brokenStore = EncryptedAttachmentStagingStore(
            directory = temporaryFolder.root,
            key = SecretKeySpec(ByteArray(32) { 7 }, "AES"),
            scopeId = "gateway-a/account-a",
            chunkBytes = 32 * 1024,
            syncFileDescriptor = { it.sync() },
            promotePartial = { _, _ -> false },
        )

        assertThrows(AttachmentStorageUnavailable::class.java) {
            brokenStore.stage(ByteArrayInputStream("rename must fail cleanly".toByteArray()))
        }
        assertEquals("failed promotion must clean its partial file", 0, temporaryFolder.root.listFiles()!!.size)
    }

    @Test
    fun expiredStagesAreRemovedWithoutApplyingAByteSizeLimit() {
        val store = store()
        val staged = store.stage(ByteArrayInputStream("retry later".toByteArray()))
        val file = temporaryFolder.root.listFiles()!!.single { it.name == "${staged.id}.stage" }
        file.setLastModified(1_000L)

        store.cleanupExpired(nowMillis = 3 * 24 * 60 * 60 * 1000L, maxAgeMillis = 24 * 60 * 60 * 1000L)

        assertFalse(file.exists())
    }

    @Test
    fun processJanitorCleansExpiredFilesFromScopesThatAreNotReopened() {
        val oldScope = File(temporaryFolder.root, "gateway-a/account-hash").apply { mkdirs() }
        val newScope = File(temporaryFolder.root, "gateway-b/account-hash").apply { mkdirs() }
        val old = File(oldScope, "old.stage").apply { writeBytes(byteArrayOf(1)); setLastModified(1_000L) }
        val fresh = File(newScope, "fresh.stage").apply { writeBytes(byteArrayOf(2)); setLastModified(3 * 24 * 60 * 60 * 1000L) }

        val removed = EncryptedAttachmentStagingStore.cleanupExpiredRoot(
            temporaryFolder.root,
            nowMillis = 3 * 24 * 60 * 60 * 1000L,
            maxAgeMillis = 24 * 60 * 60 * 1000L,
        )

        assertEquals(1, removed)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun stageCannotBeOpenedWithAKeyFromAnotherAccountScope() {
        val first = store(scope = "gateway-a/account-a", keyByte = 1)
        val staged = first.stage(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        val second = store(scope = "gateway-b/account-b", keyByte = 1)

        assertThrows(EncryptedAttachmentCorrupted::class.java) {
            second.openStream(staged.id).use { it.readBytes() }
        }
    }

    private fun store(scope: String = "gateway-a/account-a", keyByte: Int = 7) =
        EncryptedAttachmentStagingStore(
            directory = temporaryFolder.root,
            key = SecretKeySpec(ByteArray(32) { keyByte.toByte() }, "AES"),
            scopeId = scope,
            chunkBytes = 32 * 1024,
        )

    private fun patternedDigest(size: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0L
        val buffer = ByteArray(8192)
        while (offset < size) {
            val length = minOf(buffer.size.toLong(), size - offset).toInt()
            for (index in 0 until length) buffer[index] = patternByte(offset + index)
            digest.update(buffer, 0, length)
            offset += length
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun digest(input: InputStream, expectedLength: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        input.use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
                length += count
            }
        }
        assertEquals(expectedLength, length)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun containsPatternMarker(bytes: ByteArray): Boolean =
        bytes.toString(Charsets.ISO_8859_1).contains("stage-plaintext-marker")

    private val patternMarker = "stage-plaintext-marker".encodeToByteArray()

    private fun patternByte(offset: Long): Byte =
        if (offset < patternMarker.size) patternMarker[offset.toInt()] else ((offset * 31 + 7) and 0xff).toByte()

    private inner class PatternInputStream(private val size: Long) : InputStream() {
        private var offset = 0L

        override fun read(): Int {
            if (offset >= size) return -1
            return patternByte(offset++).toInt() and 0xff
        }

        override fun read(target: ByteArray, start: Int, length: Int): Int {
            if (offset >= size) return -1
            val count = minOf(length.toLong(), size - offset).toInt()
            for (index in 0 until count) target[start + index] = patternByte(offset + index)
            offset += count
            return count
        }
    }
}
