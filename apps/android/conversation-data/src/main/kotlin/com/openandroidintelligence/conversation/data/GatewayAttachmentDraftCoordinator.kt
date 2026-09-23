package com.openandroidintelligence.conversation.data

import com.openandroidintelligence.conversation.attachment.AttachmentSubmissionGate
import com.openandroidintelligence.conversation.model.AttachmentDraft
import com.openandroidintelligence.conversation.model.AttachmentDraftId
import com.openandroidintelligence.conversation.model.AttachmentState
import com.openandroidintelligence.conversation.model.SubmitIntentId
import com.openandroidintelligence.conversation.ports.AttachmentDraftCoordinator
import com.openandroidintelligence.conversation.ports.AttachmentDraftState
import com.openandroidintelligence.conversation.ports.CancelSubmissionResult
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.conversation.ports.LocalAttachmentStagingStore
import com.openandroidintelligence.conversation.ports.StagedAttachmentContent
import com.openandroidintelligence.conversation.ports.PendingSubmissionIntent
import com.openandroidintelligence.gateway.attachments.AttachmentUploader
import com.openandroidintelligence.gateway.attachments.AttachmentUploadPhase
import com.openandroidintelligence.gateway.attachments.SelectedAttachment
import com.openandroidintelligence.gateway.http.GatewayRequestBody
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stages bytes locally before upload so length, digest and retry use identical content. */
class GatewayAttachmentDraftCoordinator(
    private val uploader: AttachmentUploader,
    private val gate: AttachmentSubmissionGate,
    private val scope: CoroutineScope,
    private val staging: LocalAttachmentStagingStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AttachmentDraftCoordinator {
    private val drafts = ConcurrentHashMap<String, AttachmentDraft>()
    private val states = ConcurrentHashMap<String, MutableStateFlow<AttachmentDraftState>>()
    private val remoteIds = ConcurrentHashMap<String, String>()
    private val staged = ConcurrentHashMap<String, StagedAttachmentContent>()
    private val selections = ConcurrentHashMap<String, LocalAttachmentSelection>()
    private val jobs = ConcurrentHashMap<String, Job>()

    override suspend fun prepare(selection: LocalAttachmentSelection): AttachmentDraft {
        val draftId = newDraftId()
        val draft = AttachmentDraft(
            id = AttachmentDraftId(draftId),
            filename = selection.filename,
            mediaType = selection.mediaType,
            sizeBytes = 0L,
            sha256 = "",
            state = AttachmentState.LOCAL_PREPARING,
        )
        drafts[draftId] = draft
        selections[draftId] = selection
        stateFlowFor(draftId).value = AttachmentDraftState(draft.id, AttachmentState.LOCAL_PREPARING)
        jobs[draftId] = scope.launch { stageAndUpload(draftId, selection) }
        return draft
    }

    private suspend fun stageAndUpload(draftId: String, selection: LocalAttachmentSelection) {
        try {
            val jobContext = currentCoroutineContext()
            val content = withContext(ioDispatcher) {
                staging.stage(
                    selection = selection,
                    onBytesStaged = { bytes ->
                        updateProgress(draftId, AttachmentState.LOCAL_PREPARING, bytes, null)
                    },
                    isCancelled = { !jobContext.isActive },
                )
            }
            staged[draftId] = content
            selections.remove(draftId)
            drafts.computeIfPresent(draftId) { _, draft ->
                draft.copy(sizeBytes = content.sizeBytes, sha256 = content.sha256Hex)
            }
            uploadStaged(draftId, content)
        } catch (cancelled: CancellationException) {
            update(draftId, AttachmentState.CANCELLED, cancelled.message)
            throw cancelled
        } catch (cause: Exception) {
            update(draftId, failureState(cause), errorCode(cause))
        }
    }

    private suspend fun uploadStaged(draftId: String, content: StagedAttachmentContent) {
        val source = GatewayRequestBody(
            contentLength = content.sizeBytes,
            sha256Hex = content.sha256Hex,
            openStream = { staging.openStream(content.id) },
            onBytesWritten = { bytes ->
                updateProgress(draftId, AttachmentState.UPLOADING, bytes, content.sizeBytes)
            },
        )
        val draft = drafts[draftId] ?: return
        val remoteId = uploader.upload(
            SelectedAttachment(
                filename = draft.filename,
                mediaType = draft.mediaType,
                body = source,
                clientAttachmentId = "att_client_$draftId",
                declaredSha256 = content.sha256Hex,
            ),
            onPhase = { phase ->
                when (val state = phase.toDraftState()) {
                    AttachmentState.CREATE_PENDING, AttachmentState.UPLOADING ->
                        updateProgress(draftId, state, 0L, content.sizeBytes)
                    AttachmentState.VERIFYING ->
                        updateProgress(draftId, state, content.sizeBytes, content.sizeBytes)
                    else -> update(draftId, state)
                }
            },
        )
        remoteIds[draftId] = remoteId
        updateProgress(draftId, AttachmentState.VERIFIED, content.sizeBytes, content.sizeBytes)
    }

    override suspend fun armSubmission(draftId: String, revision: Long): PendingSubmissionIntent {
        val intent = com.openandroidintelligence.conversation.attachment.PendingSubmissionIntent(
            intentId = SubmitIntentId("sbm_${draftId}_$revision"),
            revision = revision,
            text = "",
            attachments = listOf(AttachmentDraftId(draftId)),
        )
        gate.arm(intent)
        return PendingSubmissionIntent(
            intentId = intent.intentId,
            clientMessageId = com.openandroidintelligence.conversation.model.ClientMessageId("cmsg_${draftId}_$revision"),
            draftRevision = revision,
            text = "",
            attachments = intent.attachments,
        )
    }

    override fun remoteAttachmentId(draftId: String): String? = remoteIds[draftId]

    override suspend fun cancelSubmission(intentId: String): CancelSubmissionResult {
        gate.invalidate("cancelled-by-user")
        return CancelSubmissionResult(success = true)
    }

    override fun observe(draftId: String): Flow<AttachmentDraftState> = stateFlowFor(draftId).asStateFlow()

    override fun retry(draftId: String) {
        val content = staged[draftId]
        jobs[draftId]?.cancel()
        jobs[draftId] = scope.launch {
            try {
                if (content == null) {
                    val selection = selections[draftId] ?: return@launch
                    stageAndUpload(draftId, selection)
                } else {
                    uploadStaged(draftId, content)
                }
            } catch (cancelled: CancellationException) {
                update(draftId, AttachmentState.CANCELLED, cancelled.message)
                throw cancelled
            } catch (cause: Exception) {
                update(draftId, failureState(cause), errorCode(cause))
            }
        }
    }

    override suspend fun discard(draftId: String) {
        jobs.remove(draftId)?.cancel()
        val content = staged.remove(draftId)
        selections.remove(draftId)
        remoteIds.remove(draftId)
        update(draftId, AttachmentState.CANCELLED)
        if (content != null) withContext(ioDispatcher) { staging.delete(content.id) }
        states.remove(draftId)
        drafts.remove(draftId)
    }

    override suspend fun releaseAfterSubmit(draftId: String) {
        jobs.remove(draftId)?.cancel()
        val content = staged.remove(draftId)
        selections.remove(draftId)
        remoteIds.remove(draftId)
        states.remove(draftId)
        drafts.remove(draftId)
        if (content != null) withContext(ioDispatcher) { staging.delete(content.id) }
    }

    fun draft(draftId: String): AttachmentDraft? = drafts[draftId]

    private fun stateFlowFor(draftId: String): MutableStateFlow<AttachmentDraftState> =
        states.computeIfAbsent(draftId) {
            MutableStateFlow(AttachmentDraftState(AttachmentDraftId(draftId), AttachmentState.LOCAL_PREPARING))
        }

    private fun updateProgress(draftId: String, state: AttachmentState, transferred: Long, total: Long?) {
        val progress = when {
            total == null -> 0f
            total == 0L -> 1f
            else -> (transferred.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
        }
        drafts.computeIfPresent(draftId) { _, draft ->
            draft.copy(
                state = state,
                progress = progress,
                transferredBytes = transferred,
                totalBytes = total,
                errorMessage = null,
            )
        }
        stateFlowFor(draftId).value = AttachmentDraftState(
            draftId = AttachmentDraftId(draftId),
            state = state,
            progress = progress,
            transferredBytes = transferred,
            totalBytes = total,
        )
    }

    private fun update(draftId: String, state: AttachmentState, error: String? = null) {
        drafts.computeIfPresent(draftId) { _, draft -> draft.copy(state = state, errorMessage = error) }
        val flow = stateFlowFor(draftId)
        flow.value = flow.value.copy(state = state, errorMessage = error)
    }

    private fun AttachmentUploadPhase.toDraftState(): AttachmentState = when (this) {
        AttachmentUploadPhase.CREATE_PENDING -> AttachmentState.CREATE_PENDING
        AttachmentUploadPhase.UPLOADING -> AttachmentState.UPLOADING
        AttachmentUploadPhase.VERIFYING -> AttachmentState.VERIFYING
    }

    private fun failureState(cause: Exception): AttachmentState = when {
        cause.message?.contains("COMMIT_FAILED") == true || cause.message?.contains("ATTACHMENT_STATUS_UNKNOWN") == true ->
            AttachmentState.OUTCOME_UNKNOWN
        cause.message?.contains("ATTACHMENT_ATTEMPT_TERMINAL") == true -> AttachmentState.TERMINAL_FAILURE
        cause is IllegalArgumentException || cause.message?.contains("DIGEST_MISMATCH") == true ->
            AttachmentState.TERMINAL_FAILURE
        else -> AttachmentState.RETRYABLE_FAILURE
    }

    private fun errorCode(cause: Exception): String =
        cause.message?.lineSequence()?.firstOrNull()?.takeIf { it.matches(Regex("[A-Z0-9_]+(?::.*)?")) }
            ?: cause.javaClass.simpleName

    private fun newDraftId(): String {
        val bytes = ByteArray(8)
        java.security.SecureRandom().nextBytes(bytes)
        return "adft_" + bytes.joinToString("") { "%02x".format(it) }
    }
}
