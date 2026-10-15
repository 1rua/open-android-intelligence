package com.openandroidintelligence.mobile.conversations

import android.content.Context
import android.graphics.BitmapFactory
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.encrypted.store.AndroidKeystoreOutboxKeyProvider
import com.openandroidintelligence.encrypted.store.EncryptedAttachmentStagingStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.mobile.plugins.EncryptedDocuments
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 账号隔离的持久原件库；正文独立保存，原件使用有界缓冲分块格式且不按 TTL 淘汰。 */
class EncryptedHistoryMediaCache(context: Context, private val scope: ConversationScope,
    private val http: GatewayHttpClient? = null, keyProvider: AesGcmKeyProvider? = null) : HistoricalMediaPort {
    private val scopeKey = Json.canonical(Json.of(listOf(scope.profileId, scope.gatewayId, scope.accountId, scope.installId)))
    private val scopeHash = MessageDigest.getInstance("SHA-256").digest(scopeKey.toByteArray()).joinToString("") { "%02x".format(it) }
    private val directory = File(context.noBackupFilesDir, "history-media/$scopeHash")
    private val retiredRoot = File(context.noBackupFilesDir, "history-media-retired/$scopeHash")
    private val originalKey = keyProvider ?: AndroidKeystoreOutboxKeyProvider("oai_history_media_$scopeHash")
    private val legacy = if (keyProvider == null) EncryptedDocuments(context, "history-media-cache-v1:$scopeKey")
        else EncryptedDocuments(context, "history-media-cache-v1:$scopeKey", keyProvider)
    private val index = if (keyProvider == null) EncryptedDocuments(context, "history-media-index-v2:$scopeKey")
        else EncryptedDocuments(context, "history-media-index-v2:$scopeKey", keyProvider)
    private val files by lazy { EncryptedAttachmentStagingStore(directory, originalKey.getOrCreate(), scopeKey, persistent = true) }
    private val writes = Mutex()
    private data class MediaOwner(val revision: Long, val deleted: Boolean)
    private data class MediaVersion(val epoch: Long = 0L, val owners: Map<String, MediaOwner> = emptyMap(), val permanent: Boolean = false) {
        val deleted get() = permanent || (owners.isNotEmpty() && owners.values.all { it.deleted })
    }
    private val versions = ConcurrentHashMap<String, MediaVersion>()
    private val scopeEpoch by lazy {
        synchronized(index) {
            index.read("scope-epoch")?.decodeToString() ?: UUID.randomUUID().toString().also { index.write("scope-epoch", it.toByteArray()) }
        }
    }
    @Volatile private var generation = 0L
    @Volatile private var retired = false
    @Volatile private var pendingTransitionsBlocked = false
    internal val conversationScope get() = scope
    internal fun setPendingTransitionsBlocked(blocked: Boolean) { synchronized(index) { pendingTransitionsBlocked = blocked } }
    private fun checkTransitionsReady() { check(!pendingTransitionsBlocked) { "MEDIA_TRANSITIONS_PENDING" } }

    private data class Entry(val metadata: HistoricalMediaMetadata, val contentId: String, val localDraftIds: List<String> = emptyList())
    private fun entryKey(conversationId: String, id: String): String = MessageDigest.getInstance("SHA-256")
        .digest(Json.canonical(Json.of(listOf(conversationId, id))).toByteArray()).joinToString("") { "%02x".format(it) }
    private fun recordAt(key: String): Entry? = index.read(key)?.let { bytes ->
        val o = JsonFields.obj(Json.parse(bytes.decodeToString())) ?: error("LOCAL_MEDIA_INVALID")
        val id = JsonFields.string(o, "attachmentId") ?: error("LOCAL_MEDIA_INVALID")
        Entry(HistoricalMediaMetadata(JsonFields.string(o, "conversationId") ?: error("LOCAL_MEDIA_INVALID"), id,
            JsonFields.string(o, "filename").orEmpty(), JsonFields.string(o, "mediaType").orEmpty(),
            JsonFields.long(o, "sizeBytes") ?: error("LOCAL_MEDIA_INVALID"), JsonFields.string(o, "sha256") ?: error("LOCAL_MEDIA_INVALID"),
            false, JsonFields.long(o, "sizeBytes") ?: 0L), JsonFields.string(o, "contentId") ?: error("LOCAL_MEDIA_INVALID"), JsonFields.strings(o, "localDraftIds"))
    }
    private fun scopedEntry(conversationId: String, id: String): Entry? = recordAt("metadata:${entryKey(conversationId, id)}")?.also {
        check(it.metadata.conversationId == conversationId && it.metadata.attachmentId == id) { "HISTORY_MEDIA_SCOPE_MISMATCH" }
    }
    private fun version(key: String): MediaVersion = versions.getOrPut(key) {
        val record = index.read("version:$key")?.let { JsonFields.obj(Json.parse(it.decodeToString())) ?: error("LOCAL_MEDIA_INVALID") }
        val owners = record?.let { JsonFields.objects(it, "owners") }.orEmpty().associate { owner ->
            (JsonFields.string(owner, "messageId") ?: error("LOCAL_MEDIA_INVALID")) to MediaOwner(
                JsonFields.long(owner, "revision") ?: error("LOCAL_MEDIA_INVALID"), JsonFields.bool(owner, "deleted") == true)
        }
        check(owners.values.all { it.revision >= 0L }) { "LOCAL_MEDIA_INVALID" }
        // 旧删除记录没有来源，不能借新正文撤销用户曾经确认的永久删除。
        MediaVersion((record?.let { JsonFields.long(it, "epoch") } ?: 0L).also { check(it >= 0L) { "LOCAL_MEDIA_INVALID" } }, owners,
            record?.let { JsonFields.bool(it, "permanent") } == true || index.read("revoked:$key") != null)
    }
    private fun writeVersion(key: String, value: MediaVersion) {
        versions[key] = value
        index.write("version:$key", Json.canonical(Json.of(mapOf("epoch" to value.epoch, "permanent" to value.permanent,
            "owners" to value.owners.map { (messageId, owner) -> mapOf("messageId" to messageId, "revision" to owner.revision, "deleted" to owner.deleted) }))).toByteArray())
    }
    private fun revoked(conversationId: String, id: String) = retired || synchronized(index) { version(entryKey(conversationId, id)).deleted }
    override fun originalEpoch(conversationId: String, attachmentId: String): String = synchronized(index) {
        checkTransitionsReady()
        val value = version(entryKey(conversationId, attachmentId))
        check(!retired && !value.deleted) { "LOCAL_MEDIA_DELETED" }
        "$scopeEpoch:$generation:${value.epoch}"
    }
    override fun <T> withOriginalEpoch(conversationId: String, attachmentId: String, epoch: String, commit: () -> T): T = synchronized(index) {
        check(originalEpoch(conversationId, attachmentId) == epoch) { "LOCAL_MEDIA_GENERATION_CHANGED" }
        commit()
    }
    private fun currentEpoch(conversationId: String, attachmentId: String, epoch: String) =
        runCatching { originalEpoch(conversationId, attachmentId) == epoch }.getOrDefault(false)
    private fun records() = index.keys().filter { it.startsWith("metadata:") }.mapNotNull(::recordAt)
    private fun digest(value: String): String {
        val hex = value.removePrefix("sha256:")
        check(hex.matches(Regex("[a-f0-9]{64}"))) { "MEDIA_INTEGRITY_FAILED" }
        return "sha256:$hex"
    }
    private fun target(conversationId: String, id: String, operation: String): String {
        require(listOf(conversationId, id).all { it.matches(Regex("[A-Za-z0-9._~-]{1,128}")) })
        return "/open-android-intelligence/v2/conversations/$conversationId/attachments/$id/$operation?format=stream-v1"
    }
    private suspend fun request(method: String, target: String, body: ByteArray = ByteArray(0)): JsonValue.JObject {
        val response = (http ?: error("REMOTE_UNAVAILABLE")).execute(SignedGatewayRequest(method, target, body = body,
            headers = if (body.isEmpty()) emptyList() else listOf(RawHeader("Content-Type", "application/json"))))
        check(response.status in 200..299) { if (response.status == 404) "REMOTE_UNAVAILABLE" else "HISTORY_MEDIA_FAILED:${response.status}" }
        return JsonFields.obj(JsonFields.field(JsonFields.obj(Json.parse(response.body.decodeToString())), "data")) ?: error("HISTORY_MEDIA_INVALID")
    }

    override suspend fun metadata(conversationId: String, attachmentId: String): HistoricalMediaMetadata = withContext(Dispatchers.IO) {
        val epoch = originalEpoch(conversationId, attachmentId)
        localMetadata(conversationId, attachmentId)?.let { return@withContext withOriginalEpoch(conversationId, attachmentId, epoch) { it } }
        remoteMetadata(conversationId, attachmentId).also { withOriginalEpoch(conversationId, attachmentId, epoch) {} }
    }
    private suspend fun remoteMetadata(conversationId: String, id: String): HistoricalMediaMetadata {
        val o = JsonFields.obj(JsonFields.field(request("GET", target(conversationId, id, "metadata")), "metadata")) ?: error("HISTORY_MEDIA_INVALID")
        check(JsonFields.string(o, "attachmentId") == id && JsonFields.string(o, "conversationId") == conversationId) { "HISTORY_MEDIA_SCOPE_MISMATCH" }
        val size = JsonFields.long(o, "sizeBytes") ?: error("HISTORY_MEDIA_INVALID")
        check(size >= 0) { "HISTORY_MEDIA_INVALID" }
        return HistoricalMediaMetadata(conversationId, id, JsonFields.string(o, "filename").orEmpty(),
            JsonFields.string(o, "mediaType").orEmpty(), size, digest(JsonFields.string(o, "sha256") ?: error("HISTORY_MEDIA_INVALID")),
            JsonFields.bool(o, "remoteAvailable") == true, size)
    }
    override suspend fun localMetadata(conversationId: String, attachmentId: String): HistoricalMediaMetadata? = withContext(Dispatchers.IO) {
        writes.withLock { localOrMigrate(conversationId, attachmentId)?.metadata }
    }
    private fun localOrMigrate(conversationId: String, id: String): Entry? {
        checkTransitionsReady()
        if (revoked(conversationId, id)) return null
        scopedEntry(conversationId, id)?.let {
            if (File(directory, "${it.contentId}.stage").isFile) return it
            index.delete("metadata:${entryKey(conversationId, id)}")
            index.delete("preview:${entryKey(conversationId, id)}")
        }
        val bytes = legacy.read("metadata:$id") ?: return null
        val o = JsonFields.obj(Json.parse(bytes.decodeToString())) ?: error("LOCAL_MEDIA_INVALID")
        if (JsonFields.string(o, "messageScope") != conversationId) return null
        val content = legacy.read("content:$id") ?: error("LOCAL_MEDIA_UNAVAILABLE")
        try {
            val metadata = HistoricalMediaMetadata(conversationId, id, JsonFields.string(o, "filename").orEmpty(),
                JsonFields.string(o, "mediaType").orEmpty(), JsonFields.long(o, "sizeBytes") ?: error("LOCAL_MEDIA_INVALID"),
                digest(JsonFields.string(o, "sha256") ?: error("LOCAL_MEDIA_INVALID")), false, content.size.toLong())
            save(metadata, ByteArrayInputStream(content), { false }, originalEpoch(conversationId, id))
            // 完整原件与新索引提交后才退休旧副本；中断或失败仍可按旧记录重试。
            legacy.delete("metadata:$id")
            legacy.delete("preview:$id")
            legacy.delete("content:$id")
            return scopedEntry(conversationId, id)
        } finally { content.fill(0) }
    }
    suspend fun migrateLegacy() = withContext(Dispatchers.IO) {
        writes.withLock {
            legacy.keys().filter { it.startsWith("metadata:") }.forEach { key ->
                val o = legacy.read(key)?.let { JsonFields.obj(Json.parse(it.decodeToString())) } ?: error("LOCAL_MEDIA_INVALID")
                localOrMigrate(JsonFields.string(o, "messageScope") ?: error("LOCAL_MEDIA_INVALID"), key.removePrefix("metadata:"))
            }
        }
    }
    override suspend fun ensureLocal(conversationId: String, attachmentId: String): HistoricalMediaMetadata = withContext(Dispatchers.IO) {
        val epoch = originalEpoch(conversationId, attachmentId)
        writes.withLock {
            withOriginalEpoch(conversationId, attachmentId, epoch) {}
            localOrMigrate(conversationId, attachmentId)?.let { return@withLock withOriginalEpoch(conversationId, attachmentId, epoch) { it.metadata } }
            val metadata = remoteMetadata(conversationId, attachmentId)
            fetch(metadata, epoch)
            withOriginalEpoch(conversationId, attachmentId, epoch) { scopedEntry(conversationId, attachmentId)?.metadata ?: error("LOCAL_MEDIA_UNAVAILABLE") }
        }
    }
    override suspend fun retain(metadata: HistoricalMediaMetadata) = withContext(Dispatchers.IO) {
        val epoch = originalEpoch(metadata.conversationId, metadata.attachmentId)
        writes.withLock {
            withOriginalEpoch(metadata.conversationId, metadata.attachmentId, epoch) {}
            if (localOrMigrate(metadata.conversationId, metadata.attachmentId) == null) fetch(metadata, epoch)
        }
    }
    private suspend fun fetch(metadata: HistoricalMediaMetadata, epoch: String) {
        withOriginalEpoch(metadata.conversationId, metadata.attachmentId, epoch) {}
        check(metadata.remoteAvailable) { "REMOTE_UNAVAILABLE" }
        val grant = request("POST", target(metadata.conversationId, metadata.attachmentId, "cache-grant"), "{}".toByteArray())
        val id = JsonFields.string(grant, "grantId") ?: error("MEDIA_GRANT_INVALID")
        require(id.matches(Regex("[A-Za-z0-9_-]{43}")))
        val job = currentCoroutineContext()
        val streamRequest = SignedGatewayRequest("GET", target(metadata.conversationId, metadata.attachmentId, "content") + "&grantId=$id")
        (http ?: error("REMOTE_UNAVAILABLE")).consumeResponse(streamRequest) { status, headers, input ->
            check(status in 200..299) { if (status == 404) "REMOTE_UNAVAILABLE" else "HISTORY_MEDIA_FAILED:$status" }
            val format = headers.filter { it.name.equals("X-Open-Android-Intelligence-Media-Format", true) }
            check(format.size <= 1) { "MEDIA_RESPONSE_INVALID" }
            check(format.isEmpty() || format.single().value == "stream-v1") { "MEDIA_STREAM_UNSUPPORTED" }
            if (format.singleOrNull()?.value == "stream-v1") {
                val type = headers.filter { it.name.equals("Content-Type", true) }.singleOrNull()?.value
                check(type == metadata.mediaType) { "MEDIA_TYPE_MISMATCH" }
                save(metadata, input, { !job.isActive }, epoch)
            } else {
                // 旧服务器的 JSON 回退仅允许有界响应；更大附件要求支持流式下载的插件版本。
                val bytes = readLegacyResponse(input)
                try {
                    val data = JsonFields.obj(JsonFields.field(JsonFields.obj(Json.parse(bytes.decodeToString())), "data")) ?: error("MEDIA_CONTENT_INVALID")
                    check(JsonFields.string(data, "mediaType") == metadata.mediaType) { "MEDIA_TYPE_MISMATCH" }
                    val content = Base64.getDecoder().decode(JsonFields.string(data, "contentBase64") ?: error("MEDIA_CONTENT_INVALID"))
                    try { save(metadata, ByteArrayInputStream(content), { !job.isActive }, epoch) } finally { content.fill(0) }
                } finally { bytes.fill(0) }
            }
        }
    }
    private fun readLegacyResponse(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(EncryptedAttachmentStagingStore.DEFAULT_CHUNK_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            check(output.size().toLong() + count <= LEGACY_RESPONSE_BYTES) { "MEDIA_STREAM_UNSUPPORTED" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    override suspend fun archiveSubmitted(conversationId: String, attachmentId: String, filename: String, mediaType: String,
        content: AttachmentContentSource, sizeBytes: Long, sha256: String, localDraftId: String?) = withContext(Dispatchers.IO) {
        check(sizeBytes >= 0) { "MEDIA_INTEGRITY_FAILED" }
        val metadata = HistoricalMediaMetadata(conversationId, attachmentId, filename, mediaType, sizeBytes, digest(sha256), false, sizeBytes)
        val epoch = originalEpoch(conversationId, attachmentId)
        val job = currentCoroutineContext()
        writes.withLock {
            withOriginalEpoch(conversationId, attachmentId, epoch) {}
            val existing = localOrMigrate(conversationId, attachmentId)
            if (existing != null) {
                check(existing.metadata.sha256 == metadata.sha256 && existing.metadata.sizeBytes == sizeBytes) { "MEDIA_INTEGRITY_FAILED" }
                if (localDraftId != null && localDraftId !in existing.localDraftIds) withOriginalEpoch(conversationId, attachmentId, epoch) {
                    check(job.isActive) { "LOCAL_MEDIA_DELETED" }
                    writeEntry(existing.copy(localDraftIds = existing.localDraftIds + localDraftId))
                }
            } else save(metadata, content.openStream(), { !job.isActive }, epoch, listOfNotNull(localDraftId))
        }
    }
    override suspend fun submittedMetadata(conversationId: String, localDraftId: String): HistoricalMediaMetadata? = withContext(Dispatchers.IO) {
        writes.withLock {
            val candidates = records().filter { it.metadata.conversationId == conversationId && localDraftId in it.localDraftIds }
                .mapNotNull { localOrMigrate(conversationId, it.metadata.attachmentId) }
            check(candidates.map { it.metadata.sha256 to it.metadata.sizeBytes }.distinct().size <= 1) { "ATTACHMENT_ORIGIN_CONFLICT" }
            candidates.firstOrNull()?.metadata
        }
    }
    override suspend fun submittedDraftIds(conversationId: String, attachmentId: String): List<String> = withContext(Dispatchers.IO) {
        writes.withLock { localOrMigrate(conversationId, attachmentId)?.localDraftIds.orEmpty() }
    }
    private fun writeEntry(entry: Entry) {
        val metadata = entry.metadata
        index.write("metadata:${entryKey(metadata.conversationId, metadata.attachmentId)}", Json.canonical(Json.of(mapOf(
            "conversationId" to metadata.conversationId, "attachmentId" to metadata.attachmentId,
            "filename" to metadata.filename, "mediaType" to metadata.mediaType, "sizeBytes" to metadata.sizeBytes,
            "sha256" to digest(metadata.sha256), "contentId" to entry.contentId, "localDraftIds" to entry.localDraftIds))).toByteArray())
    }
    private fun save(metadata: HistoricalMediaMetadata, source: InputStream, cancelled: () -> Boolean, epoch: String,
        localDraftIds: List<String> = emptyList()) {
        val content = source.use { input ->
            withOriginalEpoch(metadata.conversationId, metadata.attachmentId, epoch) {}
            files.stage(input, isCancelled = { cancelled() || !currentEpoch(metadata.conversationId, metadata.attachmentId, epoch) })
        }
        var committed = false
        try {
            check(content.sizeBytes == metadata.sizeBytes && digest(content.sha256Hex) == digest(metadata.sha256)) { "MEDIA_INTEGRITY_FAILED" }
            val preview = previewBytes(content.id, metadata.mediaType)
            withOriginalEpoch(metadata.conversationId, metadata.attachmentId, epoch) {
                check(!cancelled()) { "LOCAL_MEDIA_DELETED" }
                val key = entryKey(metadata.conversationId, metadata.attachmentId)
                if (preview != null) index.write("preview:$key", preview)
                writeEntry(Entry(metadata, content.id, localDraftIds))
                committed = true
            }
        } finally {
            if (!committed) files.delete(content.id)
        }
    }
    private fun previewBytes(contentId: String, type: String): ByteArray? {
        if (!type.startsWith("image/", ignoreCase = true)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        files.openStream(contentId).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 64_000_000L) return null
        val factor = (maxOf(bounds.outWidth, bounds.outHeight) / 512).coerceAtLeast(1)
        val bitmap = files.openStream(contentId).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = factor }) } ?: return null
        return try { ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray().takeIf { it.size <= 256 * 1024 } }
        finally { bitmap.recycle() }
    }
    override suspend fun openOriginal(conversationId: String, attachmentId: String): InputStream = withContext(Dispatchers.IO) {
        val epoch = originalEpoch(conversationId, attachmentId)
        writes.withLock {
            withOriginalEpoch(conversationId, attachmentId, epoch) {
                val stream = files.openStream((localOrMigrate(conversationId, attachmentId) ?: error("LOCAL_MEDIA_UNAVAILABLE")).contentId)
                object : FilterInputStream(stream) {
                    private fun <T> checked(read: () -> T): T = try { withOriginalEpoch(conversationId, attachmentId, epoch, read) }
                        catch (failure: IllegalStateException) { throw IOException("LOCAL_MEDIA_GENERATION_CHANGED", failure) }
                    override fun read(): Int = checked { super.read() }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checked { super.read(buffer, offset, length) }
                    override fun skip(count: Long): Long = checked { super.skip(count) }
                    override fun available(): Int = checked { super.available() }
                }
            }
        }
    }
    override fun isRetained(attachmentId: String): Boolean {
        checkTransitionsReady()
        if (records().any { it.metadata.attachmentId == attachmentId && !revoked(it.metadata.conversationId,attachmentId) }) return true
        val old=legacy.read("metadata:$attachmentId")?.let { JsonFields.obj(Json.parse(it.decodeToString())) }
        return JsonFields.string(old,"messageScope")?.let { !revoked(it,attachmentId) } == true
    }
    override fun preview(attachmentId: String): ByteArray? {
        checkTransitionsReady()
        val candidates = records().filter { it.metadata.attachmentId == attachmentId }
        if (candidates.size > 1) return null
        candidates.singleOrNull()?.metadata?.let { return preview(it.conversationId, attachmentId) }
        val old=legacy.read("metadata:$attachmentId")?.let { JsonFields.obj(Json.parse(it.decodeToString())) }
        val conversationId=JsonFields.string(old,"messageScope") ?: return null
        return if (revoked(conversationId,attachmentId)) null else legacy.read("preview:$attachmentId")
    }
    override fun preview(conversationId: String, attachmentId: String): ByteArray? {
        return synchronized(index) {
            checkTransitionsReady()
            if (revoked(conversationId, attachmentId)) return@synchronized null
            val entry = scopedEntry(conversationId, attachmentId) ?: return@synchronized null
            index.read("preview:${entryKey(conversationId, entry.metadata.attachmentId)}")
        }
    }
    override suspend fun applyTransition(transition: HistoricalMediaTransition) = withContext(Dispatchers.IO) {
        synchronized(index) {
            transition.attachmentIds.distinct().forEach { attachmentId ->
                val key = entryKey(transition.conversationId, attachmentId)
                val old = version(key)
                val known = old.owners[transition.messageId]
                val changed = when {
                    old.permanent -> old
                    transition.deleted && transition.revision == null -> old.copy(epoch = Math.addExact(old.epoch, 1L), permanent = true)
                    known != null && (transition.revision!! < known.revision ||
                        (transition.revision == known.revision && (!transition.deleted || known.deleted))) -> old
                    else -> old.copy(owners = old.owners +
                        (transition.messageId to MediaOwner(transition.revision!!, transition.deleted))).let { changed ->
                        if (old.deleted == changed.deleted) changed else changed.copy(epoch = Math.addExact(old.epoch, 1L))
                    }
                }
                // 相同修订的重放不再换代，但仍补写屏障并完成上次中断的物理清理。
                writeVersion(key, changed)
                if (changed.deleted) purgeOriginal(transition.conversationId, attachmentId)
            }
        }
    }
    override fun remove(attachmentId: String) {
        val entries = records().filter { it.metadata.attachmentId == attachmentId }
        entries.forEach { remove(it.metadata.conversationId, attachmentId) }
        val old = legacy.read("metadata:$attachmentId")?.let { JsonFields.obj(Json.parse(it.decodeToString())) }
        JsonFields.string(old, "messageScope")?.let { remove(it, attachmentId) }
    }
    override fun remove(conversationId: String, attachmentId: String) {
        val key = entryKey(conversationId, attachmentId)
        synchronized(index) {
            val old = version(key)
            writeVersion(key, old.copy(epoch = Math.addExact(old.epoch, 1L), permanent = true))
            // 手动删除保持永久语义；普通消息删除由带修订的变更入口处理。
            index.write("revoked:$key", Json.canonical(Json.of(mapOf("conversationId" to conversationId, "attachmentId" to attachmentId))).toByteArray())
            purgeOriginal(conversationId, attachmentId)
        }
    }
    private fun purgeOriginal(conversationId: String, attachmentId: String) {
        val key = entryKey(conversationId, attachmentId)
        // 文件先清理，索引最后退休；失败时待处理变更仍有原件身份可以重试。
        scopedEntry(conversationId, attachmentId)?.let { files.delete(it.contentId) }
        index.delete("metadata:$key")
        index.delete("preview:$key")
        val old = legacy.read("metadata:$attachmentId")?.let { JsonFields.obj(Json.parse(it.decodeToString())) }
        if (JsonFields.string(old, "messageScope") == conversationId) {
            legacy.delete("content:$attachmentId"); legacy.delete("preview:$attachmentId"); legacy.delete("metadata:$attachmentId")
        }
    }
    fun clearMedia() {
        synchronized(index) {
            generation++
            retired = true
            if (directory.exists()) {
                check(retiredRoot.isDirectory || retiredRoot.mkdirs()) { "MEDIA_RETIRE_FAILED" }
                check(directory.renameTo(File(retiredRoot, UUID.randomUUID().toString()))) { "MEDIA_RETIRE_FAILED" }
            }
            index.erase(); legacy.erase()
            originalKey.delete()
        }
    }
    companion object { private const val LEGACY_RESPONSE_BYTES = 12L * 1024 * 1024 }
}
