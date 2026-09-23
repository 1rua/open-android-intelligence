package com.openandroidintelligence.conversation.model

enum class SurfaceState { HIDDEN, EXPANDED, SELECTING_SCREEN, CROP_PREVIEW, DOCKED, TERMINATED }

enum class GenerationState {
    IDLE, QUEUED, RUNNING, CANCEL_REQUESTED, CANCELLED,
    COMPLETED, FAILED, UNSUPPORTED, OUTCOME_UNKNOWN,
}

enum class ComposerState {
    EDITING, DEBOUNCE_COLLECTING, SEALED, WAITING_NETWORK,
    WAITING_ATTACHMENTS, SUBMITTING, ACCEPTED, FAILED,
}

enum class AttachmentState {
    LOCAL_PREPARING, CREATE_PENDING, UPLOADING, VERIFYING, VERIFIED,
    RETRYABLE_FAILURE, TERMINAL_FAILURE, OUTCOME_UNKNOWN, CANCELLED,
}

enum class MirrorSyncState {
    SYNCED, CATCHING_UP, OFFLINE_MIRROR, STALE_MIRROR, RESYNC_REQUIRED,
    ACCOUNT_LOCKED, LOCAL_DATA_REMOVED, PAIRING_REVOKED,
}

data class AttachmentDraft(
    val id: AttachmentDraftId,
    val filename: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val state: AttachmentState = AttachmentState.LOCAL_PREPARING,
    val progress: Float = 0f,
    val transferredBytes: Long = 0,
    val totalBytes: Long? = null,
    val errorMessage: String? = null,
)

data class ConversationSessionState(
    val conversationId: ConversationId? = null,
    val surface: SurfaceState = SurfaceState.HIDDEN,
    val generation: GenerationState = GenerationState.IDLE,
    val composer: ComposerState = ComposerState.EDITING,
    val attachments: List<AttachmentDraft> = emptyList(),
    val mirrorSync: MirrorSyncState = MirrorSyncState.SYNCED,
    val pendingSubmissionIntent: SubmitIntentId? = null,
    val lastError: String? = null,
)

class InvalidConversationTransition(message: String) : IllegalStateException(message)
