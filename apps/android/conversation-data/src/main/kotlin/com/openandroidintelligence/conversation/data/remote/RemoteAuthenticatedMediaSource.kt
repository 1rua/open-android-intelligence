package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.AttachmentDraftId
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.HistoricalMediaMetadata
import com.openandroidintelligence.conversation.ports.HistoricalMediaSource
import com.openandroidintelligence.conversation.ports.HistoricalMediaReferenceState
import com.openandroidintelligence.conversation.remote.RemoteFileRef
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.conversation.remote.SessionKey
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking

/** 序号只取自真实会话事件；无序号的实时更新按认证流到达顺序，历史重放不能覆盖实时内容。 */
data class RemoteMediaRevision(val messageId: String, val sequence: Long? = null, val historical: Boolean = false, val historyRead: Long? = null)

/** 原件入口只认当前认证连接接收的历史附件和上传回执；UI 附件 ID 不能变成任意路径。 */
class RemoteAuthenticatedMediaSource(
    private val carrier: RemoteSessionCarrier,
    private val workspace: RemoteWorkspace,
    private val isCurrent: () -> Boolean,
) : HistoricalMediaSource {
    private val connection = carrier.channel.scope
    private val sessions = ConcurrentHashMap.newKeySet<String>()
    private data class Reference(val session: SessionKey, val id: String, val name: String, val type: String,
        val file: RemoteFileRef? = null, val data: String? = null, val size: Long? = null, val sha256: String? = null,
        val attachment: JsonValue? = null)
    private data class Identity(val absolutePath: String, val version: String, val bytes: Long)
    private val references = ConcurrentHashMap<Pair<String, String>, Reference>()
    private val snapshots = ConcurrentHashMap<Pair<String, String>, Identity>()
    private val revisions = ConcurrentHashMap<Pair<String, String>, RemoteMediaRevision>()
    private val referenceStates = ConcurrentHashMap<Pair<String, String>, HistoricalMediaReferenceState>()
    private var mutationSequence = 0L
    private val referenceMutations = ConcurrentHashMap<Pair<String, String>, Long>()
    private val referenceVersions = ConcurrentHashMap<Pair<String, String>, String>()
    private val referenceLock = Any()
    private val versionObservers = java.util.concurrent.CopyOnWriteArrayList<(String, String, String) -> Unit>()
    @Volatile private var closed = false

    init {
        check(workspace.endpoint.key.backendId.value == carrier.backend &&
            workspace.endpoint.key.accountId == connection.accountId && workspace.endpoint.key.hostId == connection.hostDeviceId &&
            workspace.endpoint.key.origin.value == connection.origin) { "HISTORY_MEDIA_SCOPE_MISMATCH" }
        sessions.addAll(workspace.sessionIds.orEmpty())
    }
    override fun checkCurrent() = current()
    private fun current() {
        check(!closed && isCurrent() && carrier.channel.scope == connection) { "REMOTE_MEDIA_SCOPE_CHANGED" }
    }
    override fun referenceVersion(conversationId: String, attachmentId: String): String? = referenceVersions[conversationId to attachmentId]
    override fun referenceState(conversationId: String, attachmentId: String) = referenceStates[conversationId to attachmentId]
    fun beginHistoryRead(): Long = synchronized(referenceLock) { current(); ++mutationSequence }
    override fun knownMetadata(conversationId: String, attachmentId: String): HistoricalMediaMetadata? {
        val ref = references[conversationId to attachmentId] ?: return null
        val size = ref.size ?: return null
        val digest = ref.sha256 ?: return null
        return HistoricalMediaMetadata(conversationId, attachmentId, ref.name, ref.type, size, digest, true, size,
            referenceVersion(conversationId, attachmentId))
    }
    override fun observeReferenceVersions(observer: (String, String, String) -> Unit): AutoCloseable {
        synchronized(referenceLock) {
            current(); versionObservers += observer
            try { referenceVersions.forEach { (key, version) -> observer(key.first, key.second, version) } }
            catch (failure: Exception) { versionObservers -= observer; throw failure }
        }
        return AutoCloseable { versionObservers -= observer }
    }
    override fun <T> withReferenceVersion(conversationId: String, attachmentId: String, expected: String?, commit: () -> T): T = synchronized(referenceLock) {
        current()
        check(referenceVersion(conversationId, attachmentId) == expected) { "REMOTE_MEDIA_REFERENCE_CHANGED" }
        commit()
    }
    fun close() = synchronized(referenceLock) {
        closed = true; references.clear(); snapshots.clear(); sessions.clear(); versionObservers.clear()
    }
    fun confirmSession(nativeSessionId: String) { current(); sessions += nativeSessionId }

    /** 只能由仓储处理来自认证通道的内容块时调用；不从编码后的 UI ID 反推路径。 */
    fun accept(conversationId: String, block: JsonValue, revision: RemoteMediaRevision? = null): MessagePart.Attachment? {
        current()
        val native = RemoteLocalIds.decode(conversationId)
        check(native in sessions) { "REMOTE_MEDIA_SESSION_UNCONFIRMED" }
        if (block.text("type") !in setOf("image", "file")) return null
        val attachment = block.at("attachment")?.let { Json.parse(Json.canonical(it)) }
        val url = block.text("url") ?: block.at("source")?.text("url")
        val dataUrl = url?.let { DATA_URL.matchEntire(it) }
        val data = block.text("data") ?: dataUrl?.groupValues?.get(2)
        val type = attachment?.text("mediaType") ?: block.text("mediaType") ?: block.text("mimeType")
            ?: dataUrl?.groupValues?.get(1) ?: "application/octet-stream"
        if (dataUrl != null) check(type == dataUrl.groupValues[1] && type in IMAGE_TYPES) { "MEDIA_TYPE_MISMATCH" }
        if (block.text("type") == "image" && data != null) check(type in IMAGE_TYPES) { "MEDIA_TYPE_MISMATCH" }
        val path = block.text("path") ?: url?.takeIf { !it.contains(':') }
        val rawId = attachment?.text("attachmentId") ?: block.text("id") ?: path
            ?: data?.let { "inline:${sha(it.toByteArray()).removePrefix("sha256:")}" } ?: url ?: return null
        val id = RemoteLocalIds.encode(rawId)
        val session = SessionKey(workspace.endpoint.key, native)
        val file = path?.let { relativePath(it) }?.let { relative ->
            RemoteFileRef(session, relative, attachment?.number("bytes") ?: block.number("sizeBytes"), block.text("sha256"))
        }
        val size = attachment?.number("bytes") ?: block.number("sizeBytes") ?: block.number("bytes")
        check(size == null || size >= 0) { "HISTORY_MEDIA_INVALID" }
        val digest = block.text("sha256")?.also { check(it.matches(Regex("sha256:[a-f0-9]{64}"))) { "MEDIA_INTEGRITY_FAILED" } }
        val reference = Reference(session, id, attachment?.text("name") ?: block.text("name") ?: block.text("filename")
            ?: path?.substringAfterLast('/').orEmpty(), type, file, data, size, digest, attachment)
        val key = conversationId to id
        val accepted = synchronized(referenceLock) {
            current()
            val previous = references[key]
            val before = revisions[key]
            val same = previous == reference || (reference.data == null && previous?.copy(data = null) == reference)
            // Harness Session V3 的 durable seq 属于同一会话；Codex/ACP 的历史 seq 是重投影位置，禁止持久比较。
            val sequence = revision?.sequence.takeIf { carrier.backend == "harness" }
            val beforeSequence = before?.sequence.takeIf { carrier.backend == "harness" }
            val domain = sequence?.let { "harness-session-v3" }
            val oldState = referenceStates[key]
            val older = revision != null && before != null && when {
                sequence != null && beforeSequence != null -> sequence < beforeSequence
                revision.historical && !before.historical && revision.historyRead == null -> true
                else -> false
            }
            val authoritativeRead = revision?.historyRead?.let { (referenceMutations[key] ?: 0L) <= it } == true
            val newer = revision != null && (before == null || when {
                sequence != null && beforeSequence != null -> sequence > beforeSequence
                authoritativeRead -> true
                !revision.historical -> true
                else -> false
            })
            when {
                older -> previous ?: reference
                previous != null && !same && !newer -> throw RemoteSessionException("REMOTE_MEDIA_REVISION_UNCONFIRMED")
                else -> {
                    val contentVersion = if (same && previous != null) referenceVersions.getValue(key) else sha(Json.canonical(obj(
                        "session" to native, "attachmentId" to id, "content" to block)).toByteArray())
                    val highest = sequence?.let { incoming -> if (oldState?.revisionDomain == domain) maxOf(incoming, oldState?.highestRevision ?: incoming) else incoming }
                    val state = if (same && domain == null && oldState?.revisionDomain != null) requireNotNull(oldState) else HistoricalMediaReferenceState(contentVersion, domain, highest)
                    val oldVersion = referenceVersions[key]
                    val oldSnapshot = snapshots[key]
                    val oldMutation = referenceMutations[key]
                    references[key] = if (same && previous != null) previous else reference
                    if (newer || before == null) revision?.let { revisions[key] = it }
                    referenceVersions[key] = contentVersion
                    referenceStates[key] = state
                    if (!same) snapshots.remove(key)
                    // 新观察即使再次确认相同内容，也必须阻止更早的异步历史覆盖。
                    referenceMutations[key] = if (revision?.historical == true && revision.historyRead != null)
                        maxOf(referenceMutations[key] ?: 0L, revision.historyRead) else ++mutationSequence
                    try {
                        // 内容相同也通知水位推进；缓存只持久更新水位，不丢相同原件。
                        if (state != oldState) versionObservers.forEach { it(conversationId, id, contentVersion) }
                    } catch (failure: Exception) {
                        if (previous == null) references.remove(key) else references[key] = previous
                        if (before == null) revisions.remove(key) else revisions[key] = before
                        if (oldVersion == null) referenceVersions.remove(key) else referenceVersions[key] = oldVersion
                        if (oldState == null) referenceStates.remove(key) else referenceStates[key] = oldState
                        if (oldSnapshot == null) snapshots.remove(key) else snapshots[key] = oldSnapshot
                        if (oldMutation == null) referenceMutations.remove(key) else referenceMutations[key] = oldMutation
                        throw failure
                    }
                    references.getValue(key)
                }
            }
        }
        return MessagePart.Attachment(AttachmentDraftId(id), accepted.name, accepted.type, accepted.size, accepted.sha256)
    }

    /** 上传成功只登记 Host 确认的文件身份，不让上传参数自行授权任意历史路径。 */
    fun submitted(conversationId: String, draftId: String, path: String, name: String, type: String, bytes: Long, digest: String) {
        current()
        val native = RemoteLocalIds.decode(conversationId)
        check(native in sessions) { "REMOTE_MEDIA_SESSION_UNCONFIRMED" }
        val relative = relativePath(path) ?: throw RemoteSessionException("REMOTE_MEDIA_PATH_DENIED")
        val file = RemoteFileRef(SessionKey(workspace.endpoint.key, native), relative, bytes, digest)
        synchronized(referenceLock) {
            val key = conversationId to draftId
            val reference = Reference(file.session, draftId, name, type, file, size = bytes, sha256 = digest)
            val previous = references[key]
            check(previous == null || previous == reference) { "REMOTE_MEDIA_REFERENCE_CHANGED" }
            references[key] = reference
            val version = sha(Json.canonical(obj("session" to native, "attachmentId" to draftId, "path" to relative, "bytes" to bytes, "sha256" to digest)).toByteArray())
            referenceStates[key] = HistoricalMediaReferenceState(version)
            if (referenceVersions.put(key, version) != version) versionObservers.forEach { it(conversationId, draftId, version) }
        }
    }
    private fun relativePath(path: String): String? {
        if ('\u0000' in path || '\\' in path || Regex("^[A-Za-z]:").containsMatchIn(path) || path.contains("://")) return null
        val root = workspace.path.trimEnd('/')
        val relative = if (path.startsWith('/')) {
            if (!path.startsWith("$root/")) return null
            path.removePrefix("$root/")
        } else path.removePrefix("./")
        return relative.takeIf { it.isNotEmpty() && it.split('/').none { part -> part.isEmpty() || part == "." || part == ".." } }
    }
    private fun reference(conversationId: String, attachmentId: String): Reference {
        current()
        return references[conversationId to attachmentId]?.also {
            check(it.session.endpoint == workspace.endpoint.key && it.session.nativeSessionId in sessions) { "HISTORY_MEDIA_SCOPE_MISMATCH" }
        } ?: throw RemoteSessionException("REMOTE_MEDIA_REFERENCE_UNVERIFIED")
    }
    override suspend fun metadata(conversationId: String, attachmentId: String): HistoricalMediaMetadata {
        currentCoroutineContext().ensureActive()
        var ref = reference(conversationId, attachmentId)
        val version = referenceVersion(conversationId, attachmentId)
        if (ref.data == null && ref.file == null && ref.attachment != null && carrier.backend in setOf("harness", "codex")) {
            val remoteAttachmentId = ref.attachment.text("attachmentId") ?: throw RemoteSessionException("REMOTE_MEDIA_UNSUPPORTED")
            val result = if (carrier.backend == "codex") {
                check(remoteAttachmentId.startsWith("codex-image:")) { "REMOTE_MEDIA_UNSUPPORTED" }
                RemoteCarrierWire(carrier.channel, "codex.app").call(obj("method" to "dsh/sessionAttachment", "params" to obj(
                    "threadId" to ref.session.nativeSessionId, "attachmentId" to remoteAttachmentId)), transfer = true)
            } else {
                RemoteCarrierWire(carrier.channel, "harness.remote").call(obj("endpoint" to "session/attachment", "payload" to obj("args" to obj(
                    "request" to obj("sessionId" to ref.session.nativeSessionId, "attachmentId" to remoteAttachmentId, "attachment" to ref.attachment)))), transfer = true)
                    .unwrapResult().unwrapResult()
            }
            currentCoroutineContext().ensureActive(); current()
            val attachment = result.at("attachment") ?: ref.attachment
            check(attachment.text("attachmentId") == remoteAttachmentId &&
                (attachment.text("mediaType") ?: result.text("mediaType")) == ref.type &&
                (ref.size == null || attachment.number("bytes") == ref.size)) { "HISTORY_MEDIA_SCOPE_MISMATCH" }
            val returned = result.requiredText("data")
            val dataUrl = DATA_URL.matchEntire(returned)
            if (dataUrl != null) check(dataUrl.groupValues[1] == ref.type && ref.type in IMAGE_TYPES) { "MEDIA_TYPE_MISMATCH" }
            val fetched = ref.copy(data = dataUrl?.groupValues?.get(2) ?: returned)
            withReferenceVersion(conversationId, attachmentId, version) {
                check(references.replace(conversationId to attachmentId, ref, fetched)) { "REMOTE_MEDIA_REFERENCE_CHANGED" }
            }
            ref = fetched
        }
        if (ref.data != null) {
            val bytes = decode(ref.data!!)
            try {
                check(ref.size == null || bytes.size.toLong() == ref.size) { "MEDIA_INTEGRITY_FAILED" }
                val digest = sha(bytes)
                check(ref.sha256 == null || ref.sha256 == digest) { "MEDIA_INTEGRITY_FAILED" }
                withReferenceVersion(conversationId, attachmentId, version) {
                    check(reference(conversationId, attachmentId) == ref) { "REMOTE_MEDIA_REFERENCE_CHANGED" }
                }
                return HistoricalMediaMetadata(conversationId, attachmentId, ref.name, ref.type, bytes.size.toLong(), digest, true, bytes.size.toLong(), version)
            } finally { bytes.fill(0) }
        }
        val before = stat(ref)
        check(ref.size == null || ref.size == before.bytes) { "REMOTE_FILE_CHANGED" }
        withReferenceVersion(conversationId, attachmentId, version) { snapshots[conversationId to attachmentId] = before }
        return HistoricalMediaMetadata(conversationId, attachmentId, ref.name, ref.type, before.bytes, ref.sha256.orEmpty(), true, before.bytes, version)
    }
    override suspend fun consumeOriginal(metadata: HistoricalMediaMetadata, consume: (InputStream) -> Unit) {
        val context = currentCoroutineContext()
        context.ensureActive()
        val ref = reference(metadata.conversationId, metadata.attachmentId)
        check(referenceVersion(metadata.conversationId, metadata.attachmentId) == metadata.sourceVersion) { "REMOTE_MEDIA_REFERENCE_CHANGED" }
        check(metadata.filename == ref.name && metadata.mediaType == ref.type && metadata.remoteAvailable) { "HISTORY_MEDIA_SCOPE_MISMATCH" }
        fun active() { context.ensureActive(); current(); check(referenceVersion(metadata.conversationId, metadata.attachmentId) == metadata.sourceVersion && reference(metadata.conversationId, metadata.attachmentId) == ref) { "REMOTE_MEDIA_REFERENCE_CHANGED" } }
        if (ref.data != null) {
            val bytes = decode(ref.data)
            try {
                check(bytes.size.toLong() == metadata.sizeBytes && sha(bytes) == metadata.sha256 &&
                    (ref.size == null || ref.size == bytes.size.toLong()) && (ref.sha256 == null || ref.sha256 == metadata.sha256)) { "MEDIA_INTEGRITY_FAILED" }
                val checked = object : FilterInputStream(ByteArrayInputStream(bytes)) {
                    override fun read(): Int { active(); return super.read() }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int { active(); return super.read(buffer, offset, length) }
                }
                checked.use(consume)
                active()
            } finally { bytes.fill(0) }
            return
        }
        val expected = snapshots[metadata.conversationId to metadata.attachmentId] ?: throw RemoteSessionException("REMOTE_MEDIA_REFERENCE_UNVERIFIED")
        check(expected.bytes == metadata.sizeBytes && metadata.sha256 == ref.sha256.orEmpty()) { "MEDIA_INTEGRITY_FAILED" }
        check(stat(ref) == expected) { "REMOTE_FILE_CHANGED" }
        val input = object : InputStream() {
            private var chunk = ByteArray(0)
            private var position = 0
            private var offset = 0L
            private var eof = false
            private var finished = false
            private fun next(): Boolean {
                active()
                if (position < chunk.size) return true
                chunk.fill(0); chunk = ByteArray(0); position = 0
                if (eof) {
                    if (!finished) {
                        val after = runBlocking(context.minusKey(ContinuationInterceptor)) { stat(ref) }
                        check(after == expected) { "REMOTE_FILE_CHANGED" }
                        active(); finished = true
                    }
                    return false
                }
                val length = minOf(CHUNK_BYTES.toLong(), maxOf(1L, expected.bytes - offset)).toInt()
                val value = runBlocking(context.minusKey(ContinuationInterceptor)) { fileCall(ref, "readBytes", obj("offset" to offset, "length" to length)) }
                check(identity(value) == expected && value.number("offset") == offset) { "REMOTE_FILE_CHANGED_OR_INVALID_CHUNK" }
                val data = (value.text("data") ?: throw RemoteSessionException("REMOTE_FILE_INVALID")).let { decode(it, length) }
                val end = value.flag("eof") ?: throw RemoteSessionException("REMOTE_FILE_CHANGED_OR_INVALID_CHUNK")
                if (data.size > length || offset + data.size > expected.bytes ||
                    (end && offset + data.size != expected.bytes) || (!end && data.isEmpty())) {
                    data.fill(0); throw RemoteSessionException("REMOTE_FILE_CHANGED_OR_INVALID_CHUNK")
                }
                active(); chunk = data; eof = end; offset += data.size
                return if (chunk.isEmpty()) next() else true
            }
            override fun read(): Int = if (next()) chunk[position++].toInt() and 255 else -1
            override fun read(buffer: ByteArray, start: Int, length: Int): Int {
                require(start >= 0 && length >= 0 && start <= buffer.size - length)
                if (length == 0) return 0
                if (!next()) return -1
                val count = minOf(length, chunk.size - position)
                chunk.copyInto(buffer, start, position, position + count); position += count
                return count
            }
            override fun close() { chunk.fill(0) }
        }
        input.use(consume)
        active()
    }
    private suspend fun stat(ref: Reference): Identity {
        val identity = identity(fileCall(ref, "stat"))
        val expectedPath = "${workspace.path.trimEnd('/')}/${ref.file?.path ?: throw RemoteSessionException("REMOTE_MEDIA_UNSUPPORTED")}"
        check(identity.absolutePath == expectedPath) { "HISTORY_MEDIA_SCOPE_MISMATCH" }
        return identity
    }
    private fun identity(value: JsonValue): Identity {
        val path = value.requiredText("absolutePath")
        val bytes = value.number("bytes") ?: throw RemoteSessionException("REMOTE_FILE_INVALID")
        check(bytes >= 0 && relativePath(path) != null) { "REMOTE_MEDIA_PATH_DENIED" }
        return Identity(path, value.requiredText("version"), bytes)
    }
    private suspend fun fileCall(ref: Reference, method: String, range: JsonValue? = null): JsonValue {
        currentCoroutineContext().ensureActive(); current()
        check(reference(RemoteLocalIds.encode(ref.session.nativeSessionId), ref.id) == ref) { "REMOTE_MEDIA_REFERENCE_CHANGED" }
        val file = ref.file ?: throw RemoteSessionException("REMOTE_MEDIA_UNSUPPORTED")
        check(file.belongsTo(ref.session)) { "HISTORY_MEDIA_SCOPE_MISMATCH" }
        val stored = ref.session.nativeSessionId
        val prefix = if (carrier.backend == "antigravity") "acp:" else "${carrier.backend}:"
        val native = if (carrier.backend == "harness") stored else stored.removePrefix(prefix)
        val public = when (carrier.backend) {
            "harness" -> native
            "codex" -> "codex:$native"
            "antigravity" -> "acp:$native"
            else -> if (native.startsWith("${carrier.backend}:")) native else "${carrier.backend}:$native"
        }
        val args = obj(*(listOf("workspaceFileScopeId" to public, "path" to file.path) + listOfNotNull(range?.let { "range" to it })).toTypedArray())
        val result = if (carrier.backend == "harness" || carrier.backend == "codex") {
            RemoteCarrierWire(carrier.channel, "harness.remote").call(obj("endpoint" to "workspaceFiles/$method", "payload" to obj("args" to args))).unwrapResult().unwrapResult()
        } else {
            RemoteCarrierWire(carrier.channel, "agent.acp").call(obj("method" to "dsh/toolCall", "params" to obj(
                "sessionId" to native, "backend" to carrier.backend, "endpoint" to "workspaceFiles/$method", "args" to args)))
        }
        currentCoroutineContext().ensureActive(); current()
        check(reference(RemoteLocalIds.encode(ref.session.nativeSessionId), ref.id) == ref) { "REMOTE_MEDIA_REFERENCE_CHANGED" }
        return result
    }
    private fun decode(encoded: String, maxBytes: Int = RemoteCarrierWire.MAX_BYTES): ByteArray {
        check(encoded.length.toLong() <= ((maxBytes.toLong() + 2) / 3) * 4) { "MEDIA_INTEGRITY_FAILED" }
        val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { throw RemoteSessionException("MEDIA_INTEGRITY_FAILED") }
        if (bytes.size > maxBytes || Base64.getEncoder().encodeToString(bytes) != encoded) { bytes.fill(0); throw RemoteSessionException("MEDIA_INTEGRITY_FAILED") }
        return bytes
    }
    private fun sha(bytes: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    companion object {
        private const val CHUNK_BYTES = 256 * 1024
        private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        private val DATA_URL = Regex("^data:([^;,]+);base64,([A-Za-z0-9+/]*={0,2})$")
    }
}
