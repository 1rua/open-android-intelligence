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

/**
 * The Gateway's answer to a batch submission.
 *
 * [memberIds] is the authoritative client→server message id mapping. A member
 * mirrored under its local client id would appear a second time as soon as the
 * same message arrives from the Gateway under the id the Gateway issued, so
 * callers must key the mirror by [memberIds] rather than by the local id.
 */
data class BatchAcceptance(
    val batchId: String,
    val memberIds: Map<String, String> = emptyMap(),
) {
    /**
     * The ids the Gateway issued for the accepted members.
     *
     * Derived from [memberIds] rather than stored beside it: a second list
     * would be a state that can disagree with the mapping it mirrors.
     */
    val acceptedMessageIds: List<String> get() = memberIds.values.toList()
}
data class MessageAcceptance(val messageId: String, val correlationId: String)

enum class CancelGenerationOutcome {
    CANCELLED,
    ALREADY_COMPLETED,
    UNSUPPORTED,
    OUTCOME_UNKNOWN,
}

data class CancelGenerationResult(val outcome: CancelGenerationOutcome, val message: String? = null)

/**
 * The closed set an Agent-side command may answer with (contract §7.1).
 *
 * `OUTCOME_UNKNOWN` is a real answer too: it is what the Gateway says when it
 * cannot tell whether the command landed, so the UI must not paint it as
 * success or as a refusal it can retry blindly.
 */
enum class CommandOutcome {
    CREATED_CONVERSATION,
    REJECTED,
    UNSUPPORTED,
    OUTCOME_UNKNOWN,
}
data class CancelSubmissionResult(val success: Boolean)
data class PendingSubmissionIntent(
    val intentId: SubmitIntentId,
    val clientMessageId: ClientMessageId,
    val draftRevision: Long,
    val text: String,
    val attachments: List<AttachmentDraftId> = emptyList(),
)

data class LocalAttachmentSelection(val filename: String, val mediaType: String, val bytes: ByteArray)
data class AttachmentDraftState(
    val draftId: AttachmentDraftId,
    val state: AttachmentState,
    val progress: Float = 0f,
    val errorMessage: String? = null,
)

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
        /**
         * The Gateway's closed answer for one command.
         *
         * Only [CommandOutcome.CREATED_CONVERSATION] together with a non-null
         * [conversationId] is permission to switch: the others are facts about
         * what the Agent refused or could not do, and the caller has to say so
         * rather than degrade into a locally invented conversation.
         */
        val outcome: CommandOutcome = CommandOutcome.OUTCOME_UNKNOWN,
        val sourceConversationId: ConversationId? = null,
        val sourceMessageId: String? = null,
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

    /**
     * The Gateway asked the phone to decide one command execution (§7.2).
     *
     * This is a request, not a message: it never becomes a chat bubble and no
     * user turn is spent on it. [request] carries the tiers the Gateway offers,
     * which is the only set the UI may draw.
     */
    data class ApprovalRequested(
        override val eventId: String,
        override val occurredAt: Long,
        val request: ApprovalRequest,
        val conversationId: ConversationId? = null,
    ) : VerifiedConversationEvent

    /**
     * How one approval ended (§7.2).
     *
     * The Gateway owns every terminal outcome, including `timeout` and
     * `withdrawn`: a card may grey itself out on its own countdown, but only
     * this event is allowed to say what actually happened.
     */
    data class ApprovalResolved(
        override val eventId: String,
        override val occurredAt: Long,
        val approvalId: ApprovalId,
        val outcome: ApprovalOutcome,
        val decidedAt: Long? = null,
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
    suspend fun updateTitle(conversationId: String, title: String): Boolean = false

    /**
     * One conversation's authoritative metadata as the Gateway has it.
     *
     * `null` means this repository cannot answer, which is a fact the caller
     * must handle by falling back to the thread list — never by inventing a
     * title or a creation time locally.
     */
    suspend fun readConversation(conversationId: String): Conversation? = null

    /**
     * One approval decision, sent through its own endpoint rather than as text.
     *
     * The default answers `UNSUPPORTED` on purpose: a repository that cannot
     * reach the decision endpoint must say so, because a card that pretends to
     * submit would leave the command blocked while the UI shows it as allowed.
     */
    suspend fun submitApprovalDecision(
        approvalId: ApprovalId,
        choice: ApprovalChoice,
    ): ApprovalSubmissionResult =
        ApprovalSubmissionResult(
            outcome = ApprovalSubmissionOutcome.UNSUPPORTED,
        )
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
