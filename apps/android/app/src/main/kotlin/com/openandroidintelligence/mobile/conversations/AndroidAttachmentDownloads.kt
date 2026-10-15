package com.openandroidintelligence.mobile.conversations

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.encrypted.store.EncryptedAttachmentStagingStore
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.mobile.plugins.EncryptedDocuments
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 用户明确下载时导出普通文件；写完与完整性校验之前，系统保持文件为 pending。 */
class AndroidAttachmentDownloads(private val context: Context, private val media: HistoricalMediaPort,
    scope: ConversationScope, keyProvider: AesGcmKeyProvider? = null,
    private val resolver: ContentResolver = context.contentResolver) : AttachmentDownloadPort {
    constructor(context: Context, media: EncryptedHistoryMediaCache) : this(context, media, media.conversationScope)

    private val scopeKey = Json.canonical(Json.of(listOf(scope.profileId, scope.gatewayId, scope.accountId, scope.installId)))
    private val journal = if (keyProvider == null) EncryptedDocuments(context, "attachment-download-journal-v1:$scopeKey")
        else EncryptedDocuments(context, "attachment-download-journal-v1:$scopeKey", keyProvider)
    private val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val mutex = Mutex()
    private var initialRecoveryAttempted = false
    private var recoveryWarningCode: String? = null
    @Volatile private var closing = false
    private data class Pending(val token: String, val conversationId: String, val attachmentId: String,
        val filename: String, val mediaType: String, val sizeBytes: Long, val sha256: String,
        val uri: String? = null, val verified: Boolean = false, val cancelled: Boolean = false, val originalEpoch: String? = null)
    private data class Row(val uri: Uri, val pending: Boolean, val filename: String)

    override suspend fun download(conversationId: String, attachmentId: String): AttachmentDownloadResult = withContext(Dispatchers.IO) {
        val originalEpoch = media.originalEpoch(conversationId, attachmentId)
        mutex.withLock {
            checkOpenScope()
            if (!initialRecoveryAttempted) {
                initialRecoveryAttempted = true
                try {
                    recoverLocked()
                    recoveryWarningCode = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    // 已明确尝试并失败；旧日志继续可重试，同时允许独立的新用户意图完成。
                    recoveryWarningCode = "DOWNLOAD_RECOVERY_FAILED"
                }
            }
            val metadata = media.ensureLocal(conversationId, attachmentId)
            media.withOriginalEpoch(conversationId, attachmentId, originalEpoch) {}
            checkOpenScope()
            var pending = Pending("download_" + UUID.randomUUID().toString().replace("-", ""), conversationId, attachmentId,
                safeFilename(metadata.filename, attachmentId), metadata.mediaType.ifBlank { "application/octet-stream" },
                metadata.sizeBytes, metadata.sha256, originalEpoch = originalEpoch)
            write(pending)
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, pending.filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, pending.mediaType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, DOWNLOAD_DIRECTORY)
                    put(MediaStore.DownloadColumns.DOWNLOAD_URI, sourceUri(pending.token))
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: throw IOException("DOWNLOAD_STORAGE_UNAVAILABLE")
                pending = pending.copy(uri = uri.toString())
                write(pending)
                copyOriginal(pending, uri)
                pending = pending.copy(verified = true)
                write(pending)
                val filename = row(uri, pending)?.filename ?: pending.filename
                currentCoroutineContext().ensureActive()
                checkOpenScope()
                withOriginal(pending) { publish(uri) }
                // 文件已发布；日志退休失败只留给重启恢复处理，不把已成功的下载报告为失败。
                runCatching { journal.delete(pending.token) }
                AttachmentDownloadResult(uri.toString(), filename, recoveryWarningCode)
            } catch (cancelled: CancellationException) {
                // 主动取消不保留发布意图；真实杀进程没有执行到此处，仍按旧日志恢复。
                withContext(NonCancellable) { rollback(pending) }
                throw cancelled
            } catch (cause: Exception) {
                rollback(pending)
                throw IOException("ATTACHMENT_DOWNLOAD_FAILED", cause)
            }
        }
    }

    suspend fun recoverPendingDownloads(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkOpenScope()
            initialRecoveryAttempted = true
            try { recoverLocked().also { recoveryWarningCode = null } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                recoveryWarningCode = "DOWNLOAD_RECOVERY_FAILED"
                throw IOException("DOWNLOAD_RECOVERY_FAILED", failure)
            }
        }
    }
    suspend fun cancelPendingDownloads() {
        closing = true
        withContext(NonCancellable + Dispatchers.IO) { mutex.withLock { cancelLocked() } }
    }
    suspend fun wipePrivateDownloadJournal() {
        closing = true
        withContext(NonCancellable + Dispatchers.IO) {
            mutex.withLock {
                cancelLocked()
                journal.erase()
            }
        }
    }
    private fun cancelLocked() {
        var failure: Exception? = null
        journal.keys().filter { it.startsWith("download_") }.forEach { token ->
            try { rollback(read(token)) }
            catch (cause: Exception) { if (failure == null) failure = cause }
        }
        failure?.let { throw IOException("DOWNLOAD_CLEANUP_FAILED", it) }
    }
    private fun checkOpenScope() {
        if (closing) throw CancellationException("DOWNLOAD_SCOPE_CLOSED")
    }
    private suspend fun recoverLocked(): Int {
        var count = 0
        var failure: Exception? = null
        journal.keys().filter { it.startsWith("download_") }.forEach { token ->
            try {
                var pending = read(token)
                val row = pending.uri?.let { row(Uri.parse(it), pending) } ?: findUnjournaledRow(pending)
                if (pending.cancelled) {
                    rollback(pending)
                } else if (row == null) {
                    // 系统已回收待发布条目。
                    journal.delete(token)
                } else if (!row.pending) {
                    // 只退休已有完整性提交的已发布记录；外部文件此后属于用户，不再改写或回收。
                    check(pending.verified) { "DOWNLOAD_RECOVERY_UNVERIFIED" }
                    journal.delete(token)
                } else {
                    pending = pending.copy(uri = row.uri.toString())
                    write(pending)
                    if (!pending.verified) {
                        copyOriginal(pending, row.uri)
                        pending = pending.copy(verified = true)
                        write(pending)
                    } else verifyExport(pending, row.uri)
                    currentCoroutineContext().ensureActive()
                    checkOpenScope()
                    withOriginal(pending) { publish(row.uri) }
                    journal.delete(token)
                }
                count++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) {
                // 恢复是对原已确认意图的重试；临时失败不撤销意图或销毁可继续处理的日志。
                if (failure == null) failure = cause
            }
        }
        failure?.let { throw IOException("DOWNLOAD_RECOVERY_FAILED", it) }
        return count
    }
    private suspend fun copyOriginal(pending: Pending, uri: Uri) {
        val job = currentCoroutineContext()
        withOriginal(pending) {}
        val descriptor = resolver.openFileDescriptor(uri, "rwt") ?: throw IOException("DOWNLOAD_STORAGE_UNAVAILABLE")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            media.openOriginal(pending.conversationId, pending.attachmentId).use { input ->
                val buffer = ByteArray(EncryptedAttachmentStagingStore.DEFAULT_CHUNK_BYTES)
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                while (true) {
                    job.ensureActive()
                    checkOpenScope()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    withOriginal(pending) {
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        size = Math.addExact(size, read.toLong())
                    }
                }
                withOriginal(pending) {
                    check(size == pending.sizeBytes && sha(digest.digest()) == pending.sha256.removePrefix("sha256:")) { "MEDIA_INTEGRITY_FAILED" }
                    output.flush()
                    descriptor.fileDescriptor.sync()
                }
            }
        }
    }
    private fun verifyExport(pending: Pending, uri: Uri) {
        withOriginal(pending) {}
        val input = resolver.openInputStream(uri) ?: throw IOException("DOWNLOAD_STORAGE_UNAVAILABLE")
        input.use {
            val buffer = ByteArray(EncryptedAttachmentStagingStore.DEFAULT_CHUNK_BYTES)
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                withOriginal(pending) {
                    digest.update(buffer, 0, count)
                    size = Math.addExact(size, count.toLong())
                }
            }
            withOriginal(pending) { check(size == pending.sizeBytes && sha(digest.digest()) == pending.sha256.removePrefix("sha256:")) { "MEDIA_INTEGRITY_FAILED" } }
        }
    }
    private fun <T> withOriginal(pending: Pending, commit: () -> T): T = media.withOriginalEpoch(pending.conversationId,
        pending.attachmentId, pending.originalEpoch ?: error("DOWNLOAD_RECOVERY_VERSION_UNAVAILABLE"), commit)
    private fun publish(uri: Uri) {
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
            "DOWNLOAD_PUBLISH_FAILED"
        }
    }
    private fun rollback(pending: Pending) {
        // 先记录取消，再进入系统回收；后半段失败时下一次恢复也只能继续回收，不能发布。
        write(pending.copy(cancelled = true))
        val row = pending.uri?.let { row(Uri.parse(it), pending) } ?: findUnjournaledRow(pending)
        if (row?.pending == true) check(resolver.update(row.uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_TRASHED, 1)
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null) == 1) { "DOWNLOAD_CLEANUP_FAILED" }
        journal.delete(pending.token)
    }
    private fun row(uri: Uri, pending: Pending): Row? {
        check(uri.scheme == "content" && uri.authority == collection.authority && uri.path?.startsWith(collection.path + "/") == true) { "DOWNLOAD_URI_INVALID" }
        val options = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        (resolver.query(uri, COLUMNS, options, null) ?: throw IOException("DOWNLOAD_LOOKUP_UNAVAILABLE")).use { cursor ->
            if (!cursor.moveToFirst()) return null
            check(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)) == context.packageName &&
                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)) == DOWNLOAD_DIRECTORY &&
                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.DownloadColumns.DOWNLOAD_URI)) == sourceUri(pending.token)) { "DOWNLOAD_OWNER_MISMATCH" }
            return Row(uri, cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)) == 1,
                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)))
        }
    }
    private fun findUnjournaledRow(pending: Pending): Row? {
        val query = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.DownloadColumns.DOWNLOAD_URI}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(sourceUri(pending.token), context.packageName, DOWNLOAD_DIRECTORY))
        }
        (resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), query, null) ?: throw IOException("DOWNLOAD_LOOKUP_UNAVAILABLE")).use { cursor ->
            if (cursor.moveToFirst()) return row(ContentUris.withAppendedId(collection, cursor.getLong(0)), pending)
        }
        return null
    }
    private fun write(pending: Pending) {
        journal.write(pending.token, Json.canonical(Json.of(mapOf("token" to pending.token, "conversationId" to pending.conversationId,
            "attachmentId" to pending.attachmentId, "filename" to pending.filename, "mediaType" to pending.mediaType,
            "sizeBytes" to pending.sizeBytes, "sha256" to pending.sha256, "uri" to pending.uri, "verified" to pending.verified,
            "cancelled" to pending.cancelled, "originalEpoch" to pending.originalEpoch))).toByteArray())
    }
    private fun read(token: String): Pending {
        val o = journal.read(token)?.let { JsonFields.obj(Json.parse(it.decodeToString())) } ?: error("DOWNLOAD_JOURNAL_INVALID")
        fun field(name: String) = JsonFields.string(o, name) ?: error("DOWNLOAD_JOURNAL_INVALID")
        check(field("token") == token) { "DOWNLOAD_JOURNAL_INVALID" }
        return Pending(token, field("conversationId"), field("attachmentId"), field("filename"), field("mediaType"),
            JsonFields.long(o, "sizeBytes") ?: error("DOWNLOAD_JOURNAL_INVALID"), field("sha256"), JsonFields.string(o, "uri"),
            JsonFields.bool(o, "verified") == true, JsonFields.bool(o, "cancelled") == true, JsonFields.string(o, "originalEpoch"))
    }
    private fun sourceUri(token: String) = "oai-local:$token"
    private fun sha(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun safeFilename(name: String, id: String) = name.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
        .joinToString("").trim().takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "attachment_$id"

    companion object {
        val DOWNLOAD_DIRECTORY = Environment.DIRECTORY_DOWNLOADS + "/OpenAndroidIntelligence/"
        private val COLUMNS = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.DownloadColumns.DOWNLOAD_URI)
    }
}
