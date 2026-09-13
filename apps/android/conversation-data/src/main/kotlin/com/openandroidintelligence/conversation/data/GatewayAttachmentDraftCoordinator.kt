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
import com.openandroidintelligence.conversation.ports.PendingSubmissionIntent
import com.openandroidintelligence.gateway.attachments.AttachmentUploader
import com.openandroidintelligence.gateway.attachments.AttachmentUploadPhase
import com.openandroidintelligence.gateway.attachments.SelectedAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Uploads a local selection through the three-step attachment contract and
 * reports real phases.
 *
 * A draft only reaches [AttachmentState.VERIFIED] when the Gateway commit
 * succeeded. Every other path keeps the draft and its bytes so the user can
 * retry or remove it, because a silent drop is indistinguishable from success.
 */
class GatewayAttachmentDraftCoordinator(
    private val uploader: AttachmentUploader,
    private val gate: AttachmentSubmissionGate,
    private val scope: CoroutineScope,
) : AttachmentDraftCoordinator {

    private val drafts = LinkedHashMap<String, AttachmentDraft>()
    private val states = LinkedHashMap<String, MutableStateFlow<AttachmentDraftState>>()
    private val remoteIds = LinkedHashMap<String, String>()
    private val jobs = LinkedHashMap<String, Job>()

    override suspend fun prepare(selection: LocalAttachmentSelection): AttachmentDraft {
        val draftId = newDraftId()
        val sha256 = sha256Hex(selection.bytes)
        val draft = AttachmentDraft(
            id = AttachmentDraftId(draftId),
            filename = selection.filename,
            mediaType = selection.mediaType,
            sizeBytes = selection.bytes.size.toLong(),
            sha256 = sha256,
            state = AttachmentState.LOCAL_PREPARING,
        )
        drafts[draftId] = draft
        stateFlowFor(draftId).value = AttachmentDraftState(
            draftId = AttachmentDraftId(draftId),
            state = AttachmentState.LOCAL_PREPARING,
        )

        jobs[draftId] = scope.launch {
            runCatching {
                val remoteId = uploader.upload(
                    SelectedAttachment(
                        filename = selection.filename,
                        mediaType = selection.mediaType,
                        content = selection.bytes,
                        declaredSha256 = sha256,
                    ),
                    onPhase = { phase -> update(draftId, phase.toDraftState()) },
                )
                remoteIds[draftId] = remoteId
                update(draftId, AttachmentState.VERIFIED)
            }.onFailure { cause ->
                val unknown = (cause is IllegalStateException &&
                    cause.message?.contains("COMMIT_FAILED") == true) ||
                    cause.message?.contains("COMMIT_FAILED") == true
                val terminal = cause is IllegalArgumentException ||
                    cause.message?.contains("DIGEST_MISMATCH") == true
                val targetState = when {
                    unknown -> AttachmentState.OUTCOME_UNKNOWN
                    terminal -> AttachmentState.TERMINAL_FAILURE
                    else -> AttachmentState.RETRYABLE_FAILURE
                }
                update(
                    draftId,
                    targetState,
                    cause.message,
                )
            }
        }
        return draft
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

    /** The server attachment id, only meaningful once the draft is verified. */
    override fun remoteAttachmentId(draftId: String): String? = remoteIds[draftId]

    override suspend fun cancelSubmission(intentId: String): CancelSubmissionResult {
        gate.invalidate("cancelled-by-user")
        return CancelSubmissionResult(success = true)
    }

    override fun observe(draftId: String): Flow<AttachmentDraftState> = stateFlowFor(draftId).asStateFlow()

    /** Retries a draft that failed in a way the user can recover from. */
    override fun retry(draftId: String, selection: LocalAttachmentSelection) {
        jobs[draftId]?.cancel()
        jobs[draftId] = scope.launch {
            runCatching {
                val remoteId = uploader.upload(
                    SelectedAttachment(
                        filename = selection.filename,
                        mediaType = selection.mediaType,
                        content = selection.bytes,
                    ),
                    onPhase = { phase -> update(draftId, phase.toDraftState()) },
                )
                remoteIds[draftId] = remoteId
                update(draftId, AttachmentState.VERIFIED)
            }.onFailure { cause ->
                val unknown = (cause is IllegalStateException &&
                    cause.message?.contains("COMMIT_FAILED") == true) ||
                    cause.message?.contains("COMMIT_FAILED") == true
                val terminal = cause is IllegalArgumentException ||
                    cause.message?.contains("DIGEST_MISMATCH") == true
                val targetState = when {
                    unknown -> AttachmentState.OUTCOME_UNKNOWN
                    terminal -> AttachmentState.TERMINAL_FAILURE
                    else -> AttachmentState.RETRYABLE_FAILURE
                }
                update(draftId, targetState, cause.message)
            }
        }
    }

    fun draft(draftId: String): AttachmentDraft? = drafts[draftId]

    private fun stateFlowFor(draftId: String): MutableStateFlow<AttachmentDraftState> =
        states.getOrPut(draftId) {
            MutableStateFlow(
                AttachmentDraftState(
                    draftId = AttachmentDraftId(draftId),
                    state = AttachmentState.LOCAL_PREPARING,
                ),
            )
        }

    private fun update(draftId: String, state: AttachmentState, error: String? = null) {
        val draft = drafts[draftId]
        if (draft != null) {
            drafts[draftId] = draft.copy(state = state, errorMessage = error)
        }
        states[draftId]?.value = AttachmentDraftState(
            draftId = AttachmentDraftId(draftId),
            state = state,
            progress = if (state == AttachmentState.VERIFIED) 1f else 0f,
        )
    }

    private fun AttachmentUploadPhase.toDraftState(): AttachmentState = when (this) {
        AttachmentUploadPhase.CREATE_PENDING -> AttachmentState.CREATE_PENDING
        AttachmentUploadPhase.UPLOADING -> AttachmentState.UPLOADING
        AttachmentUploadPhase.VERIFYING -> AttachmentState.VERIFYING
    }

    private fun newDraftId(): String {
        val bytes = ByteArray(8)
        java.security.SecureRandom().nextBytes(bytes)
        return "adft_" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
