package com.openandroidintelligence.conversation.ports

import com.openandroidintelligence.conversation.model.*
import kotlinx.coroutines.flow.Flow

data class ConversationScope(
    val profileId: String,
    val gatewayId: String,
    val accountId: String,
    val installId: String,
)

data class PageRequest(val cursor: String? = null, val limit: Int = 50)
data class ConversationPage(val conversations: List<ConversationSummary>, val nextCursor: String?)
data class ConversationSummary(val id: ConversationId, val title: String, val updatedAt: Long)
data class Conversation(val id: ConversationId, val title: String, val createdAt: Long)
data class TimelinePage(val messages: List<TimelineMessage>, val nextCursor: String?)
data class TimelineMessage(
    val id: String,
    val sender: String,
    val parts: List<MessagePart>,
    val timestamp: Long,
    val state: String = "CONFIRMED",
    /** The conversation carried by an SSE event, when the Gateway provides it. */
    val conversationId: ConversationId? = null,
)

data class MessageBatch(
    val batchId: String,
    val messages: List<OutgoingMessage>,
    val clientConversationId: String? = null,
)

data class OutgoingMessage(
    val clientMessageId: ClientMessageId,
    val text: String,
    val attachmentIds: List<String> = emptyList(),
    val command: MessagePart.Command? = null,
)

data class BatchAcceptance(val batchId: String, val acceptedMessageIds: List<String>)
data class MessageAcceptance(val messageId: String, val correlationId: String)

enum class CancelGenerationOutcome {
    CANCELLED,
    ALREADY_COMPLETED,
    UNSUPPORTED,
    OUTCOME_UNKNOWN,
}

data class CancelGenerationResult(val outcome: CancelGenerationOutcome, val message: String? = null)
data class CancelSubmissionResult(val success: Boolean)
data class PendingSubmissionIntent(
    val intentId: SubmitIntentId,
    val clientMessageId: ClientMessageId,
    val draftRevision: Long,
    val text: String,
    val attachments: List<AttachmentDraftId> = emptyList(),
)

data class LocalAttachmentSelection(val filename: String, val mediaType: String, val bytes: ByteArray)
data class AttachmentDraftState(val draftId: AttachmentDraftId, val state: AttachmentState, val progress: Float = 0f)

data class AgentCommand(
    val command: String,
    val description: String,
    val argumentHint: String? = null,
)

data class AgentCommandCatalog(val version: CatalogVersion, val commands: List<AgentCommand>)

sealed interface VerifiedConversationEvent {
    val eventId: String
    val occurredAt: Long

    data class MessageAccepted(
        override val eventId: String,
        override val occurredAt: Long,
        val messageId: String,
        val correlationId: String,
        val conversationId: ConversationId? = null,
    ) : VerifiedConversationEvent

    data class GenerationCancelled(
        override val eventId: String,
        override val occurredAt: Long,
        val generationId: String,
        val conversationId: ConversationId? = null,
    ) : VerifiedConversationEvent

    data class CommandResult(
        override val eventId: String,
        override val occurredAt: Long,
        val command: String,
        val conversationId: ConversationId? = null,
    ) : VerifiedConversationEvent

    data class TitleUpdated(
        override val eventId: String,
        override val occurredAt: Long,
        val conversationId: ConversationId,
        val newTitle: String,
    ) : VerifiedConversationEvent

    data class TimelineUpsert(
        override val eventId: String,
        override val occurredAt: Long,
        val revision: Long,
        val message: TimelineMessage,
    ) : VerifiedConversationEvent

    data class TimelineTombstoned(
        override val eventId: String,
        override val occurredAt: Long,
        val messageId: String,
        val revision: Long,
        val conversationId: ConversationId? = null,
    ) : VerifiedConversationEvent

    data class SnapshotInvalidated(
        override val eventId: String,
        override val occurredAt: Long,
        val snapshotRevision: Long,
        val conversationId: ConversationId? = null,
    ) : VerifiedConversationEvent
}

data class MirrorScope(
    val profileId: String,
    val gatewayId: String,
    val accountId: String,
    val installId: String,
)

interface MirrorSession

interface ConversationRepository {
    suspend fun listConversations(scope: ConversationScope, page: PageRequest): ConversationPage
    suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation
    suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage
    suspend fun submitBatch(batch: MessageBatch): BatchAcceptance
    /** Scoped overload used by delayed batches so a thread switch cannot retarget a send. */
    suspend fun submitBatch(conversationId: String, batch: MessageBatch): BatchAcceptance =
        submitBatch(batch.copy(clientConversationId = conversationId))
    suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance
    /** Scoped overload used by in-flight sends; legacy implementations may delegate. */
    suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance =
        submitMessage(message)
    fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent>
    suspend fun cancelGeneration(generationId: String, requestId: String): CancelGenerationResult
    /** Scoped cancellation overload; the default keeps old test adapters source-compatible. */
    suspend fun cancelGeneration(
        conversationId: String,
        generationId: String,
        requestId: String,
    ): CancelGenerationResult = cancelGeneration(generationId, requestId)
}

/**
 * Optional recovery port for a request whose HTTP result is unknown.
 * Implementations must query the Gateway by the original client message ID
 * before a retry; callers must never infer that a lost response means failure.
 */
interface MessageOutcomeQuery {
    suspend fun queryMessage(conversationId: String, clientMessageId: ClientMessageId): TimelineMessage?
}

interface AgentCommandCatalogRepository {
    suspend fun get(gatewayId: String, languageCode: String): AgentCommandCatalog
}

interface AttachmentDraftCoordinator {
    suspend fun prepare(selection: LocalAttachmentSelection): AttachmentDraft
    suspend fun armSubmission(draftId: String, revision: Long): PendingSubmissionIntent
    suspend fun cancelSubmission(intentId: String): CancelSubmissionResult
    fun observe(draftId: String): Flow<AttachmentDraftState>
    fun retry(draftId: String, selection: LocalAttachmentSelection)
    fun remoteAttachmentId(draftId: String): String?
}

interface ConversationMirrorStore {
    suspend fun open(scope: MirrorScope): MirrorSession
    suspend fun lock(scope: MirrorScope)
    suspend fun wipeForUnpairing(scope: MirrorScope)
    suspend fun wipeForLocalAccountRemoval(scope: MirrorScope)
}

/**
 * The generation identity the Gateway has actually told us about.
 *
 * Cancellation needs a real `generationId`, and the phone may only use one the
 * server issued. Until an event carries it there is nothing to cancel, and the
 * UI must say so rather than invent an id or pretend the stop succeeded.
 */
interface GenerationTracker {
    val generationId: kotlinx.coroutines.flow.StateFlow<String?>
}
