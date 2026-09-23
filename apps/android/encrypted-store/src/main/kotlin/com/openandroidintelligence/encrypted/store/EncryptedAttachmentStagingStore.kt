package com.openandroidintelligence.encrypted.store

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.conversation.ports.LocalAttachmentStagingStore
import com.openandroidintelligence.conversation.ports.StagedAttachmentContent
import java.util.concurrent.CancellationException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StagedEncryptedAttachment(
    val id: String,
    val sizeBytes: Long,
    val sha256Hex: String,
)

class EncryptedAttachmentCorrupted : IllegalStateException("ENCRYPTED_ATTACHMENT_CORRUPTED")

class AttachmentStorageUnavailable(cause: Throwable? = null) :
    IOException("ATTACHMENT_STORAGE_UNAVAILABLE", cause)

private class AttachmentSourceReadFailed(cause: Throwable) : IOException("ATTACHMENT_READ_FAILED", cause)

private data class AttachmentStageOperations(
    val syncFileDescriptor: (FileDescriptor) -> Unit,
    val promotePartial: (File, File) -> Boolean,
)

private const val ATTACHMENT_STAGE_MAGIC = "OPEN_ANDROID_INTELLIGENCE_ATTACHMENT_STAGE_V1"

private fun chunkAad(scope: String, id: String, index: Long, length: Int): ByteArray =
    "$ATTACHMENT_STAGE_MAGIC\u0000$scope\u0000$id\u0000$index\u0000$length".encodeToByteArray()

private fun footerAad(scope: String, id: String, chunks: Long): ByteArray =
    "$ATTACHMENT_STAGE_MAGIC\u0000$scope\u0000$id\u0000footer\u0000$chunks".encodeToByteArray()

/**
 * Authenticated, bounded-memory staging for a single attachment.
 *
 * Each plaintext chunk is authenticated before it can be read back. The scope
 * string is included in every GCM AAD value, so a stage copied between Gateway
 * or account stores cannot be opened even when a caller reuses the same key.
 */
class EncryptedAttachmentStagingStore private constructor(
    private val directory: File,
    private val key: SecretKey,
    private val scopeId: String,
    private val chunkBytes: Int,
    private val operations: AttachmentStageOperations,
) : LocalAttachmentStagingStore {
    constructor(
        directory: File,
        key: SecretKey,
        scopeId: String,
        chunkBytes: Int = DEFAULT_CHUNK_BYTES,
    ) : this(
        directory,
        key,
        scopeId,
        chunkBytes,
        AttachmentStageOperations({ descriptor -> descriptor.sync() }, { partial, complete -> partial.renameTo(complete) }),
    )

    internal constructor(
        directory: File,
        key: SecretKey,
        scopeId: String,
        chunkBytes: Int,
        syncFileDescriptor: (FileDescriptor) -> Unit,
    ) : this(
        directory,
        key,
        scopeId,
        chunkBytes,
        AttachmentStageOperations(syncFileDescriptor, { partial, complete -> partial.renameTo(complete) }),
    )

    internal constructor(
        directory: File,
        key: SecretKey,
        scopeId: String,
        chunkBytes: Int,
        syncFileDescriptor: (FileDescriptor) -> Unit,
        promotePartial: (File, File) -> Boolean,
    ) : this(directory, key, scopeId, chunkBytes, AttachmentStageOperations(syncFileDescriptor, promotePartial))

    init {
        require(key.algorithm.equals("AES", ignoreCase = true)) { "attachment staging key must use AES" }
        key.encoded?.let { require(it.size == AES_256_BYTES) { "attachment staging key must be 256 bits" } }
        require(scopeId.isNotBlank()) { "attachment staging scope must not be blank" }
        require(chunkBytes in 1..MAX_CHUNK_BYTES) { "attachment staging chunk size is invalid" }
        ensureDirectory()
        cleanupIncomplete(System.currentTimeMillis())
        cleanupExpired(System.currentTimeMillis(), DEFAULT_STAGE_TTL_MILLIS)
    }

    /** Reads once, encrypts each chunk, and derives metadata from those exact bytes. */
    override fun stage(
        selection: LocalAttachmentSelection,
        onBytesStaged: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ): StagedAttachmentContent {
        val staged = stage(selection.contentSource.openStream(), onBytesStaged, isCancelled)
        return StagedAttachmentContent(staged.id, staged.sizeBytes, staged.sha256Hex)
    }

    fun stage(
        source: InputStream,
        onBytesStaged: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): StagedEncryptedAttachment {
        val id = "ast_" + randomBytes(16).joinToString("") { "%02x".format(it) }
        val partial = File(directory, "$id.part")
        val complete = File(directory, "$id.stage")
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        var chunkIndex = 0L
        var promoted = false
        var finalDigest = ByteArray(0)

        try {
            source.use { input ->
                val rawOutput = try {
                    FileOutputStream(partial)
                } catch (cause: IOException) {
                    throw AttachmentStorageUnavailable(cause)
                } catch (cause: SecurityException) {
                    throw AttachmentStorageUnavailable(cause)
                }
                DataOutputStream(BufferedOutputStream(rawOutput)).use { sink ->
                    try {
                        sink.writeUTF(MAGIC)
                        sink.writeUTF(id)
                        sink.writeInt(chunkBytes)
                    } catch (cause: IOException) {
                        throw AttachmentStorageUnavailable(cause)
                    }
                    val buffer = ByteArray(chunkBytes)
                    while (true) {
                        if (isCancelled()) throw CancellationException("ATTACHMENT_STAGING_CANCELLED")
                        val count = try {
                            readChunk(input, buffer)
                        } catch (cause: IOException) {
                            throw AttachmentSourceReadFailed(cause)
                        }
                        if (count == 0) break
                        digest.update(buffer, 0, count)
                        totalBytes = try {
                            Math.addExact(totalBytes, count.toLong())
                        } catch (cause: ArithmeticException) {
                            throw IOException("ATTACHMENT_SIZE_UNREPRESENTABLE", cause)
                        }
                        val nonce = randomBytes(IV_BYTES)
                        val ciphertext = encrypt(
                            plain = buffer,
                            plainLength = count,
                            nonce = nonce,
                            aad = chunkAad(scopeId, id, chunkIndex, count),
                        )
                        try {
                            sink.writeInt(count)
                            sink.write(nonce)
                            sink.writeInt(ciphertext.size)
                            sink.write(ciphertext)
                        } catch (cause: IOException) {
                            throw AttachmentStorageUnavailable(cause)
                        }
                        chunkIndex++
                        onBytesStaged(totalBytes)
                    }

                    finalDigest = digest.digest()
                    val footer = ByteBuffer.allocate(FOOTER_PLAIN_BYTES)
                        .putLong(totalBytes)
                        .put(finalDigest)
                        .array()
                    val nonce = randomBytes(IV_BYTES)
                    val ciphertext = encrypt(footer, footer.size, nonce, footerAad(scopeId, id, chunkIndex))
                    try {
                        sink.writeInt(FOOTER_MARKER)
                        sink.write(nonce)
                        sink.writeInt(ciphertext.size)
                        sink.write(ciphertext)
                        sink.flush()
                        operations.syncFileDescriptor(rawOutput.fd)
                    } catch (cause: IOException) {
                        throw AttachmentStorageUnavailable(cause)
                    }
                }
            }
            if (!operations.promotePartial(partial, complete)) throw AttachmentStorageUnavailable()
            promoted = true
            return StagedEncryptedAttachment(
                id = id,
                sizeBytes = totalBytes,
                sha256Hex = finalDigest.toHex(),
            )
        } catch (cause: AttachmentStorageUnavailable) {
            throw cause
        } catch (cause: AttachmentSourceReadFailed) {
            throw cause
        } catch (cause: IOException) {
            if (cause.message == "ATTACHMENT_SIZE_UNREPRESENTABLE") throw cause
            throw AttachmentStorageUnavailable(cause)
        } finally {
            if (!promoted && partial.exists()) partial.delete()
            if (!promoted && complete.exists()) complete.delete()
        }
    }

    override fun openStream(stagedId: String): InputStream {
        val id = stagedId
        val file = fileFor(id)
        if (!file.isFile) throw IOException("ATTACHMENT_STAGING_NOT_FOUND")
        return try {
            EncryptedChunkInputStream(file, id, key, scopeId)
        } catch (_: Exception) {
            throw EncryptedAttachmentCorrupted()
        }
    }

    override fun delete(stagedId: String) {
        val id = stagedId
        val complete = fileFor(id)
        val partial = File(directory, "$id.part")
        try {
            if (complete.exists() && !complete.delete()) throw AttachmentStorageUnavailable()
            if (partial.exists() && !partial.delete()) throw AttachmentStorageUnavailable()
        } catch (cause: SecurityException) {
            throw AttachmentStorageUnavailable(cause)
        }
    }

    override fun cleanupExpired(nowMillis: Long, maxAgeMillis: Long) {
        require(maxAgeMillis >= 0L) { "ATTACHMENT_STAGING_INVALID_TTL" }
        val cutoff = nowMillis - maxAgeMillis
        try {
            directory.listFiles()?.filter { it.extension == "stage" && it.lastModified() < cutoff }?.forEach { file ->
                if (!file.delete()) throw AttachmentStorageUnavailable()
            }
        } catch (cause: SecurityException) {
            throw AttachmentStorageUnavailable(cause)
        }
    }

    /** Clears all attachment temporaries for this scoped store. */
    override fun cleanup() {
        val files = directory.listFiles() ?: throw AttachmentStorageUnavailable()
        files.filter { it.extension == "part" || it.extension == "stage" }.forEach { file ->
            if (!file.delete()) throw AttachmentStorageUnavailable()
        }
    }

    private fun ensureDirectory() {
        try {
            if (!directory.exists() && !directory.mkdirs()) throw AttachmentStorageUnavailable()
            if (!directory.isDirectory || !directory.canWrite()) throw AttachmentStorageUnavailable()
        } catch (cause: SecurityException) {
            throw AttachmentStorageUnavailable(cause)
        }
    }

    private fun cleanupIncomplete(nowMillis: Long) {
        val cutoff = nowMillis - DEFAULT_PARTIAL_STAGE_TTL_MILLIS
        try {
            directory.listFiles()?.filter { it.extension == "part" && it.lastModified() < cutoff }?.forEach { file ->
                if (!file.delete()) throw AttachmentStorageUnavailable()
            }
        } catch (cause: SecurityException) {
            throw AttachmentStorageUnavailable(cause)
        }
    }

    private fun fileFor(id: String): File {
        require(STAGE_ID.matches(id)) { "ATTACHMENT_STAGING_INVALID_ID" }
        val file = File(directory, "$id.stage")
        if (file.parentFile?.canonicalFile != directory.canonicalFile) {
            throw IllegalArgumentException("ATTACHMENT_STAGING_INVALID_ID")
        }
        return file
    }

    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            if (read == 0) {
                val single = input.read()
                if (single < 0) break
                buffer[count++] = single.toByte()
            } else {
                count += read
            }
        }
        return count
    }

    private fun encrypt(plain: ByteArray, plainLength: Int, nonce: ByteArray, aad: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plain, 0, plainLength)

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(RANDOM::nextBytes)

    private class EncryptedChunkInputStream(
        file: File,
        private val id: String,
        private val key: SecretKey,
        private val scopeId: String,
    ) : InputStream() {
        private val input = DataInputStream(BufferedInputStream(FileInputStream(file)))
        private val chunkBytes: Int
        private val digest = MessageDigest.getInstance("SHA-256")
        private var chunkIndex = 0L
        private var totalBytes = 0L
        private var current = ByteArray(0)
        private var currentOffset = 0
        private var verifiedEof = false
        private var closed = false

        init {
            try {
                if (input.readUTF() != MAGIC || input.readUTF() != id) throw EncryptedAttachmentCorrupted()
                chunkBytes = input.readInt().takeIf { it in 1..MAX_CHUNK_BYTES }
                    ?: throw EncryptedAttachmentCorrupted()
            } catch (cause: EncryptedAttachmentCorrupted) {
                input.close()
                throw cause
            } catch (cause: Exception) {
                input.close()
                throw EncryptedAttachmentCorrupted()
            }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (closed) throw IOException("STREAM_CLOSED")
            if (offset < 0 || length < 0 || length > target.size - offset) throw IndexOutOfBoundsException()
            if (length == 0) return 0
            if (currentOffset >= current.size && !verifiedEof) loadNextChunk()
            if (verifiedEof && currentOffset >= current.size) return -1
            val copied = minOf(length, current.size - currentOffset)
            current.copyInto(target, offset, currentOffset, currentOffset + copied)
            currentOffset += copied
            return copied
        }

        private fun loadNextChunk() {
            try {
                val marker = input.readInt()
                if (marker == FOOTER_MARKER) {
                    val nonce = ByteArray(IV_BYTES).also(input::readFully)
                    val cipherLength = input.readInt()
                    if (cipherLength != FOOTER_CIPHER_BYTES) throw EncryptedAttachmentCorrupted()
                    val ciphertext = ByteArray(cipherLength).also(input::readFully)
                    val plain = decrypt(ciphertext, key, nonce, footerAad(scopeId, id, chunkIndex))
                    val footer = ByteBuffer.wrap(plain)
                    val expectedLength = footer.long
                    val expectedDigest = ByteArray(SHA256_BYTES).also(footer::get)
                    if (expectedLength != totalBytes || !expectedDigest.contentEquals(digest.digest())) {
                        throw EncryptedAttachmentCorrupted()
                    }
                    if (input.read() != -1) throw EncryptedAttachmentCorrupted()
                    verifiedEof = true
                    current = ByteArray(0)
                    currentOffset = 0
                    return
                }
                if (marker !in 1..chunkBytes) throw EncryptedAttachmentCorrupted()
                val nonce = ByteArray(IV_BYTES).also(input::readFully)
                val cipherLength = input.readInt()
                if (cipherLength != marker + TAG_BYTES) throw EncryptedAttachmentCorrupted()
                val ciphertext = ByteArray(cipherLength).also(input::readFully)
                val plain = decrypt(ciphertext, key, nonce, chunkAad(scopeId, id, chunkIndex, marker))
                if (plain.size != marker) throw EncryptedAttachmentCorrupted()
                digest.update(plain)
                totalBytes = Math.addExact(totalBytes, plain.size.toLong())
                current = plain
                currentOffset = 0
                chunkIndex++
            } catch (cause: EncryptedAttachmentCorrupted) {
                throw cause
            } catch (cause: Exception) {
                throw EncryptedAttachmentCorrupted()
            }
        }

        override fun close() {
            if (!closed) {
                closed = true
                input.close()
            }
        }
    }

    companion object {
        const val DEFAULT_CHUNK_BYTES = 256 * 1024
        const val MAX_CHUNK_BYTES = 1024 * 1024
        const val DEFAULT_STAGE_TTL_MILLIS = 24L * 60 * 60 * 1000
        private const val DEFAULT_PARTIAL_STAGE_TTL_MILLIS = 10L * 60 * 1000
        private const val MAGIC = ATTACHMENT_STAGE_MAGIC
        private const val AES_256_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val SHA256_BYTES = 32
        private const val FOOTER_MARKER = 0
        private const val FOOTER_PLAIN_BYTES = Long.SIZE_BYTES + SHA256_BYTES
        private const val FOOTER_CIPHER_BYTES = FOOTER_PLAIN_BYTES + TAG_BYTES
        private val STAGE_ID = Regex("ast_[0-9a-f]{32}")
        private val RANDOM = SecureRandom()

        /** Process-start janitor: account scopes no longer visited are still bounded by the TTL. */
        fun cleanupExpiredRoot(
            root: File,
            nowMillis: Long = System.currentTimeMillis(),
            maxAgeMillis: Long = DEFAULT_STAGE_TTL_MILLIS,
        ): Int {
            require(maxAgeMillis >= 0L) { "ATTACHMENT_STAGING_INVALID_TTL" }
            if (!root.exists()) return 0
            if (!root.isDirectory) throw AttachmentStorageUnavailable()
            val cutoff = nowMillis - maxAgeMillis
            var removed = 0
            fun visit(directory: File) {
                val files = directory.listFiles() ?: throw AttachmentStorageUnavailable()
                for (file in files) {
                    if (file.isDirectory) {
                        visit(file)
                    } else if ((file.extension == "part" || file.extension == "stage") && file.lastModified() < cutoff) {
                        if (!file.delete()) throw AttachmentStorageUnavailable()
                        removed++
                    }
                }
            }
            try {
                visit(root)
            } catch (cause: SecurityException) {
                throw AttachmentStorageUnavailable(cause)
            }
            return removed
        }

        private fun cipher(mode: Int, key: SecretKey, nonce: ByteArray, aad: ByteArray): Cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, key, GCMParameterSpec(128, nonce))
                updateAAD(aad)
            }

        private fun decrypt(ciphertext: ByteArray, key: SecretKey, nonce: ByteArray, aad: ByteArray): ByteArray =
            try {
                cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(ciphertext)
            } catch (cause: AEADBadTagException) {
                throw EncryptedAttachmentCorrupted()
            }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
