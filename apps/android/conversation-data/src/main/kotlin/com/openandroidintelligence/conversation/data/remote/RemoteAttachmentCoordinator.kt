package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.remote.rpc.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** 本机校验的加密暂存与后端上传分离；只有具体会话存在后才进行真实工作区上传。 */
class RemoteAttachmentCoordinator(
    private val carrier: RemoteSessionCarrier,
    private val scope: CoroutineScope,
    private val staging: LocalAttachmentStagingStore,
    private val recovery: AttachmentDraftRecoveryStore,
    private val mediaSource: RemoteAuthenticatedMediaSource? = null,
    private val workspaceId: String = "",
    private val authorityIdentity: String = "",
    private val isCurrent: () -> Boolean = { true },
) : AttachmentDraftCoordinator {
    private val drafts = ConcurrentHashMap<String, AttachmentDraft>()
    private val content = ConcurrentHashMap<String, StagedAttachmentContent>()
    private val states = ConcurrentHashMap<String, MutableStateFlow<AttachmentDraftState>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val uploads = ConcurrentHashMap<String, RemoteAttachmentUpload>()
    private val uploadLocks = ConcurrentHashMap<String, Mutex>()
    private val connection = carrier.channel.scope
    // 当前实例是进程内的恢复凭据；进程重启后不能拿旧 peer 的临时 completed 表作为权威。
    private val recoverableUploads = ConcurrentHashMap.newKeySet<String>()
    private val authority = Json.canonical(obj("origin" to connection.origin, "account" to connection.accountId,
        "host" to connection.hostDeviceId, "client" to connection.clientDeviceId, "backend" to carrier.backend,
        "workspace" to workspaceId, "identity" to authorityIdentity))
    private fun current() { check(isCurrent() && carrier.channel.scope == connection && scope.isActive) { "REMOTE_ATTACHMENT_SCOPE_CHANGED" } }
    private val ephemeral = ConcurrentHashMap.newKeySet<String>()
    init {
        recovery.load().forEach { record ->
            record.remoteUpload?.let { uploads[record.draft.id.value] = it }
            val unknown = record.remoteUpload?.phase?.let { it != RemoteAttachmentUploadPhase.CONFIRMED } == true
            val draft = record.draft.copy(state = if (unknown) AttachmentState.OUTCOME_UNKNOWN else AttachmentState.VERIFIED,
                errorMessage = if (unknown) "REMOTE_ATTACHMENT_OUTCOME_UNKNOWN" else null)
            drafts[draft.id.value] = draft
            content[draft.id.value] = record.content
            states[draft.id.value] = MutableStateFlow(AttachmentDraftState(draft.id, draft.state, errorMessage = draft.errorMessage))
        }
    }
    override fun restoredDrafts() = drafts.values.toList()
    @Synchronized private fun persist() = recovery.save(drafts.values.filter { it.id.value !in ephemeral || uploads.containsKey(it.id.value) }.mapNotNull { draft ->
        content[draft.id.value]?.let { RecoveredAttachmentDraft(draft, it, null, remoteUpload = uploads[draft.id.value]) }
    })
    override suspend fun prepare(selection: LocalAttachmentSelection): AttachmentDraft {
        val id = "attachment_${UUID.randomUUID()}"
        val draft = AttachmentDraft(AttachmentDraftId(id), selection.filename, selection.mediaType, 0, "", AttachmentState.LOCAL_PREPARING)
        drafts[id] = draft
        states[id] = MutableStateFlow(AttachmentDraftState(draft.id, draft.state))
        if (!selection.recoverAfterRestart) ephemeral += id
        jobs[id] = scope.launch(Dispatchers.IO) {
            try {
                val context = currentCoroutineContext()
                val staged = staging.stage(selection, { bytes -> states[id]?.value = AttachmentDraftState(draft.id,
                    AttachmentState.LOCAL_PREPARING, transferredBytes = bytes) }, { !context.isActive })
                content[id] = staged
                drafts[id] = draft.copy(sizeBytes = staged.sizeBytes, sha256 = "sha256:${staged.sha256Hex}", state = AttachmentState.VERIFIED)
                persist()
                states[id]?.value = AttachmentDraftState(draft.id, AttachmentState.VERIFIED, 1f, staged.sizeBytes, staged.sizeBytes)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { states[id]?.value = AttachmentDraftState(draft.id, AttachmentState.RETRYABLE_FAILURE, errorMessage = "ATTACHMENT_READ_FAILED") }
        }
        return draft
    }
    override fun observe(draftId: String): Flow<AttachmentDraftState> = states[draftId]
        ?: flowOf(AttachmentDraftState(AttachmentDraftId(draftId), AttachmentState.TERMINAL_FAILURE, errorMessage = "ATTACHMENT_NOT_FOUND"))
    override fun remoteAttachmentId(draftId: String): String? = draftId.takeIf { content.containsKey(it) && states[it]?.value?.state == AttachmentState.VERIFIED }
    override suspend fun armSubmission(draftId: String, revision: Long) = PendingSubmissionIntent(SubmitIntentId("intent_${UUID.randomUUID()}"),
        ClientMessageId("message_${UUID.randomUUID()}"), revision, "", listOf(AttachmentDraftId(draftId)))
    override suspend fun cancelSubmission(intentId: String) = CancelSubmissionResult(true)
    override fun retry(draftId: String) {
        if (uploads[draftId]?.phase?.let { it != RemoteAttachmentUploadPhase.CONFIRMED } == true) return
        val staged = content[draftId] ?: return
        jobs[draftId] = scope.launch(Dispatchers.IO) {
            try {
                staging.openStream(staged.id).use { stream -> val buffer = ByteArray(64 * 1024); while (stream.read(buffer) >= 0) currentCoroutineContext().ensureActive() }
                states[draftId]?.value = AttachmentDraftState(AttachmentDraftId(draftId), AttachmentState.VERIFIED)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { states[draftId]?.value = AttachmentDraftState(AttachmentDraftId(draftId), AttachmentState.RETRYABLE_FAILURE, errorMessage = "ATTACHMENT_READ_FAILED") }
        }
    }
    override suspend fun discard(draftId: String) { releaseAfterSubmit(draftId) }
    override suspend fun releaseAfterSubmit(draftId: String) {
        jobs.remove(draftId)?.cancelAndJoin()
        content.remove(draftId)?.let { staging.delete(it.id) }
        drafts.remove(draftId); states.remove(draftId); ephemeral.remove(draftId); uploads.remove(draftId); recoverableUploads.remove(draftId); persist()
    }
    override suspend fun archiveSubmitted(conversationId: String, draftId: String, media: HistoricalMediaPort) {
        val draft = drafts[draftId] ?: throw RemoteSessionException("ATTACHMENT_NOT_FOUND")
        val staged = content[draftId] ?: throw RemoteSessionException("ATTACHMENT_NOT_FOUND")
        media.archiveSubmitted(conversationId, draftId, draft.filename, draft.mediaType,
            AttachmentContentSource { staging.openStream(staged.id) }, staged.sizeBytes, "sha256:${staged.sha256Hex}", draftId)
    }
    override suspend fun ensureSubmittedSource(conversationId: String, draftId: String, media: HistoricalMediaPort) {
        if (content.containsKey(draftId)) return
        val metadata = media.submittedMetadata(conversationId, draftId) ?: throw RemoteSessionException("ATTACHMENT_NOT_FOUND")
        val staged = withContext(Dispatchers.IO) { staging.stage(LocalAttachmentSelection(metadata.filename, metadata.mediaType,
            AttachmentContentSource { runBlocking { media.openOriginal(conversationId, metadata.attachmentId) } })) }
        val draft = AttachmentDraft(AttachmentDraftId(draftId), metadata.filename, metadata.mediaType, staged.sizeBytes,
            "sha256:${staged.sha256Hex}", AttachmentState.VERIFIED)
        drafts[draftId] = draft; content[draftId] = staged
        states[draftId] = MutableStateFlow(AttachmentDraftState(draft.id, draft.state)); persist()
    }
    override suspend fun retrySubmittedSource(conversationId: String, draftId: String, media: HistoricalMediaPort): AttachmentDraft {
        ensureSubmittedSource(conversationId, draftId, media)
        if (uploads[draftId]?.phase?.let { it != RemoteAttachmentUploadPhase.CONFIRMED } == true) promptContent(conversationId, draftId)
        return drafts[draftId] ?: throw RemoteSessionException("ATTACHMENT_NOT_FOUND")
    }

    suspend fun promptContent(localConversationId: String, draftId: String): List<JsonValue> =
        uploadLocks.computeIfAbsent(draftId) { Mutex() }.withLock { promptContentLocked(localConversationId, draftId) }

    private suspend fun promptContentLocked(localConversationId: String, draftId: String): List<JsonValue> {
        current()
        val draft = drafts[draftId] ?: throw RemoteSessionException("ATTACHMENT_NOT_FOUND")
        val staged = content[draftId] ?: throw RemoteSessionException("ATTACHMENT_NOT_FOUND")
        val image = draft.mediaType.startsWith("image/", ignoreCase = true)
        if (image) {
            val bytes = withContext(Dispatchers.IO) { staging.openStream(staged.id).use { it.readBytes() } }
            try {
                val base64 = Base64.getEncoder().encodeToString(bytes)
                return listOf(
                    obj(
                        "type" to "image",
                        "mimeType" to draft.mediaType,
                        "mediaType" to draft.mediaType,
                        "data" to base64,
                        "source" to obj(
                            "type" to "base64",
                            "media_type" to draft.mediaType,
                            "data" to base64,
                        ),
                    ),
                )
            } finally { bytes.fill(0) }
        }
        if ("workspace.upload.v1" !in carrier.channel.capabilities) throw RemoteSessionException("WORKSPACE_UPLOAD_UNSUPPORTED")
        val native = RemoteLocalIds.decode(localConversationId)
        val publicSession = when (carrier.backend) {
            "harness" -> native
            "codex" -> "codex:$native"
            "antigravity" -> "acp:$native"
            else -> if (native.startsWith("${carrier.backend}:")) native else "${carrier.backend}:$native"
        }
        val prior = uploads[draftId]
        if (prior != null) {
            if (prior.authority != authority || prior.conversationId != localConversationId ||
                prior.sizeBytes != staged.sizeBytes || prior.sha256Hex != staged.sha256Hex) throw java.io.IOException("REMOTE_ATTACHMENT_SCOPE_MISMATCH")
            if (prior.phase == RemoteAttachmentUploadPhase.CONFIRMED) return confirmedContent(localConversationId, draftId, prior)
            // 公开 commit 只在原 peer 内幂等；无查询接口，也不能跨重启或跨连接重放 mutation。
            if (prior.phase != RemoteAttachmentUploadPhase.COMMIT_UNKNOWN || prior.uploadId !in recoverableUploads ||
                prior.connectionId != connection.connectionId || prior.connectionGeneration != connection.generation)
                throw java.io.IOException("REMOTE_ATTACHMENT_OUTCOME_UNKNOWN")
            return commit(localConversationId, publicSession, draftId, prior)
        }
        val record = RemoteAttachmentUpload(authority, localConversationId, UUID.randomUUID().toString(),
            connection.connectionId, connection.generation, RemoteAttachmentUploadPhase.PREPARING, staged.sizeBytes, staged.sha256Hex)
        uploads[draftId] = record
        persist()
        val upload = record.uploadId
        val rpc = carrier.channel
        var commitDispatched = false
        try {
            val opened = rpc.invoke("workspace.upload.open", obj("sessionId" to publicSession, "uploadId" to upload,
                "name" to draft.filename, "totalBytes" to staged.sizeBytes))
            current()
            val chunkBytes = opened.number("chunkBytes")?.takeIf { it in 1..65_536 }?.toInt()
                ?: throw RemoteSessionException("REMOTE_UPLOAD_INVALID")
            if (opened.text("uploadId") != upload) throw RemoteSessionException("REMOTE_UPLOAD_INVALID")
            staging.openStream(staged.id).use { stream ->
                val buffer = ByteArray(chunkBytes)
                var offset = 0L
                try {
                    while (offset < staged.sizeBytes) {
                        val count = withContext(Dispatchers.IO) { stream.read(buffer, 0, minOf(chunkBytes.toLong(), staged.sizeBytes - offset).toInt()) }
                        if (count <= 0) throw RemoteSessionException("ATTACHMENT_READ_FAILED")
                        val result = rpc.invoke("workspace.upload.chunk", obj("sessionId" to publicSession, "uploadId" to upload,
                            "offset" to offset, "data" to Base64.getEncoder().encodeToString(buffer.copyOf(count))))
                        current()
                        offset += count
                        if (result.text("uploadId") != upload || result.number("receivedBytes") != offset) throw RemoteSessionException("REMOTE_UPLOAD_INVALID")
                    }
                } finally { buffer.fill(0) }
            }
            current()
            // 在发送 commit 前落盘，崩溃只能留下未知，不能留下可重新 create 的假失败。
            val unknown = record.copy(phase = RemoteAttachmentUploadPhase.COMMIT_UNKNOWN)
            uploads[draftId] = unknown
            persist()
            recoverableUploads += upload
            commitDispatched = true
            return commit(localConversationId, publicSession, draftId, unknown)
        } catch (cause: Exception) {
            if (!commitDispatched) {
                // open/chunk 从不发布文件；仍只中止原上传，确认中止后才允许新的上传身份。
                val aborted = withContext(NonCancellable) { runCatching {
                    rpc.invoke("workspace.upload.abort", obj("sessionId" to publicSession, "uploadId" to upload), timeoutMs = 5_000)
                }.isSuccess }
                if (aborted) { uploads.remove(draftId); persist() }
                else {
                    markUnknown(draftId)
                    if (cause !is CancellationException) throw java.io.IOException("REMOTE_ATTACHMENT_OUTCOME_UNKNOWN", cause)
                }
            }
            throw cause
        }
    }

    private fun markUnknown(draftId: String) {
        drafts.computeIfPresent(draftId) { _, old -> old.copy(state = AttachmentState.OUTCOME_UNKNOWN, errorMessage = "REMOTE_ATTACHMENT_OUTCOME_UNKNOWN") }
        states[draftId]?.value = AttachmentDraftState(AttachmentDraftId(draftId), AttachmentState.OUTCOME_UNKNOWN,
            errorMessage = "REMOTE_ATTACHMENT_OUTCOME_UNKNOWN")
        if (isCurrent() && carrier.channel.scope == connection) persist()
    }

    private suspend fun commit(localConversationId: String, publicSession: String, draftId: String, record: RemoteAttachmentUpload): List<JsonValue> {
        try {
            current()
            val result = carrier.channel.invoke("workspace.upload.commit", obj("sessionId" to publicSession, "uploadId" to record.uploadId))
            current()
            val staged = content[draftId] ?: throw RemoteSessionException("ATTACHMENT_NOT_FOUND")
            val name = result.requiredText("name")
            val path = result.requiredText("path")
            if (result.number("bytes") != staged.sizeBytes || name.isBlank() || path != "./$name" ||
                '/' in name || '\\' in name || name in setOf(".", "..")) throw RemoteSessionException("REMOTE_UPLOAD_INVALID")
            val confirmed = record.copy(phase = RemoteAttachmentUploadPhase.CONFIRMED, path = path, name = name)
            uploads[draftId] = confirmed
            drafts.computeIfPresent(draftId) { _, old -> old.copy(state = AttachmentState.VERIFIED, errorMessage = null) }
            persist()
            states[draftId]?.value = AttachmentDraftState(AttachmentDraftId(draftId), AttachmentState.VERIFIED)
            return confirmedContent(localConversationId, draftId, confirmed)
        } catch (cancelled: CancellationException) {
            markUnknown(draftId)
            throw cancelled
        } catch (_: Exception) {
            markUnknown(draftId)
            throw java.io.IOException("REMOTE_ATTACHMENT_OUTCOME_UNKNOWN")
        }
    }

    private fun confirmedContent(localConversationId: String, draftId: String, record: RemoteAttachmentUpload): List<JsonValue> {
        current()
        val draft = drafts[draftId] ?: throw RemoteSessionException("ATTACHMENT_NOT_FOUND")
        val staged = content[draftId] ?: throw RemoteSessionException("ATTACHMENT_NOT_FOUND")
        val path = record.path ?: throw RemoteSessionException("REMOTE_UPLOAD_INVALID")
        mediaSource?.submitted(localConversationId, draftId, path, record.name ?: draft.filename, draft.mediaType,
            staged.sizeBytes, "sha256:${staged.sha256Hex}")
        return listOf(obj("type" to "text", "text" to path))
    }
}
