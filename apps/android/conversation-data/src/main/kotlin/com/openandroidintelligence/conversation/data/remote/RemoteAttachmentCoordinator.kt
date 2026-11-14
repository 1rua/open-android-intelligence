package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.gateway.schema.JsonValue
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
) : AttachmentDraftCoordinator {
    private val drafts = ConcurrentHashMap<String, AttachmentDraft>()
    private val content = ConcurrentHashMap<String, StagedAttachmentContent>()
    private val states = ConcurrentHashMap<String, MutableStateFlow<AttachmentDraftState>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val ephemeral = ConcurrentHashMap.newKeySet<String>()
    init {
        recovery.load().forEach { record ->
            val draft = record.draft.copy(state = AttachmentState.VERIFIED)
            drafts[draft.id.value] = draft
            content[draft.id.value] = record.content
            states[draft.id.value] = MutableStateFlow(AttachmentDraftState(draft.id, AttachmentState.VERIFIED))
        }
    }
    override fun restoredDrafts() = drafts.values.toList()
    @Synchronized private fun persist() = recovery.save(drafts.values.filter { it.id.value !in ephemeral }.mapNotNull { draft ->
        content[draft.id.value]?.let { RecoveredAttachmentDraft(draft, it, null) }
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
        drafts.remove(draftId); states.remove(draftId); ephemeral.remove(draftId); persist()
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
        return drafts[draftId] ?: throw RemoteSessionException("ATTACHMENT_NOT_FOUND")
    }

    suspend fun promptContent(localConversationId: String, draftId: String): List<JsonValue> {
        val draft = drafts[draftId] ?: throw RemoteSessionException("ATTACHMENT_NOT_FOUND")
        val staged = content[draftId] ?: throw RemoteSessionException("ATTACHMENT_NOT_FOUND")
        val image = draft.mediaType.startsWith("image/") && (carrier.backend in setOf("harness", "codex", "antigravity", "hermes"))
        if (image) {
            val bytes = withContext(Dispatchers.IO) { staging.openStream(staged.id).use { it.readBytes() } }
            try {
                val base64 = Base64.getEncoder().encodeToString(bytes)
                return listOf(if (carrier.backend == "codex") obj("type" to "image", "url" to "data:${draft.mediaType};base64,$base64")
                    else obj("type" to "image", "mimeType" to draft.mediaType, "data" to base64))
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
        val upload = UUID.randomUUID().toString()
        val rpc = carrier.channel
        var committed = false
        try {
            val opened = rpc.invoke("workspace.upload.open", obj("sessionId" to publicSession, "uploadId" to upload,
                "name" to draft.filename, "totalBytes" to staged.sizeBytes))
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
                        offset += count
                        if (result.text("uploadId") != upload || result.number("receivedBytes") != offset) throw RemoteSessionException("REMOTE_UPLOAD_INVALID")
                    }
                } finally { buffer.fill(0) }
            }
            val result = rpc.invoke("workspace.upload.commit", obj("sessionId" to publicSession, "uploadId" to upload))
            if (result.number("bytes") != staged.sizeBytes || result.text("name") != draft.filename) throw RemoteSessionException("REMOTE_UPLOAD_INVALID")
            val path = result.requiredText("path")
            committed = true
            return listOf(obj("type" to "text", "text" to path))
        } finally {
            if (!committed) withContext(NonCancellable) { runCatching { rpc.invoke("workspace.upload.abort", obj("sessionId" to publicSession, "uploadId" to upload), timeoutMs = 5_000) } }
        }
    }
}
