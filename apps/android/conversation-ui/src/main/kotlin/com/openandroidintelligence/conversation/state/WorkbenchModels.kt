package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.ComposerState
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.ports.ConversationOrganization
import com.openandroidintelligence.conversation.ports.ConversationSummary

/**
 * One timeline entry for rendering: either a mirrored server message or a local
 * send unit that is still waiting for the Gateway's acceptance.
 *
 * Local units keep their own identity so a debounce batch never loses a member;
 * acceptance is the server's word, never a local timer's.
 */
data class TimelineEntry(
    val key: String,
    val sender: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long,
    val pendingAcceptance: Boolean,
    val batchGroupId: String?,
    val attachments: List<com.openandroidintelligence.conversation.model.TimelineAttachment> = emptyList(),
    val isStreaming: Boolean = false,
    val messageStatus: com.openandroidintelligence.conversation.ports.AgentMessageStatus? = null,
    val messageStatusErrorCode: com.openandroidintelligence.conversation.ports.AgentMessageErrorCode? = null,
    val submissionFailureCode: String? = null,
    /**
     * The conversation this row navigates to, when it is a system row rather
     * than a message.
     *
     * The thread a `/new` was sent from keeps a receipt for what that command
     * created, so the new conversation stays reachable after the phone has moved
     * — including when the user had already left the source thread when the
     * answer arrived. A receipt is local navigation, never Gateway content.
     */
    val systemThreadId: String? = null,
    /**
     * The approval card this row is, when it is not a message at all.
     *
     * An approval is a request the Gateway made, not something the user or the
     * Agent said, so it rides as a row of its own: message folding and
     * duplicate-pruning never see it, and the card keeps its own state.
     */
    val approval: ApprovalCardState? = null,
    /** 明确的客户端身份优先于旧历史的正文相似判断。 */
    val clientMessageId: ClientMessageId? = null,
    val generationId: String? = null,
    val executionSteps: List<com.openandroidintelligence.conversation.model.NativeExecutionStep> = emptyList(),
    val localPlaceholder: Boolean = false,
    /** View-only execution facts; this row is never an Assistant message in mirrored history. */
    val executionFact: Boolean = false,
    /** 该执行过程所属任务的明确状态，独立于消息正文是否仍在流式。 */
    val executionGenerationState: GenerationState? = null,
    /** UI 临时来源；历史装载重新建立快照基线，不进入 checkpoint。 */
    val presentation: StreamPresentationMetadata? = null,
    val reasoning: List<com.openandroidintelligence.conversation.model.MessageReasoning> = emptyList(),
    val isMeta: Boolean = false,
)

data class WorkbenchUiState(
    val threads: Loadable<List<ConversationSummary>> = Loadable.Idle,
    val timeline: Loadable<List<TimelineEntry>> = Loadable.Idle,
    val catalog: Loadable<AgentCommandCatalog> = Loadable.Idle,
    val activeThreadId: String? = null,
    val activeThreadTitle: String = "",
    val generation: GenerationState = GenerationState.IDLE,
    val draft: String = "",
    val draftEpoch: Long = 0L,
    val draftVersion: Long = 0L,
    val timelineOpenedAtNs: Long = 0L,
    val timelineRequestEpoch: Long = 0L,
    val timelineDataSource: String? = null,
    val composer: ComposerState = ComposerState.EDITING,
    val attachments: List<com.openandroidintelligence.conversation.model.AttachmentDraft> = emptyList(),
    /** Batch members collected by the debounce window, newest last. */
    val pendingBatch: List<TimelineEntry> = emptyList(),
    /** Phone-held corrections waiting for the current Agent turn to finish. */
    val pendingDeliveries: List<com.openandroidintelligence.conversation.ports.PendingDelivery> = emptyList(),
    val notice: String? = null,
    /** True while the Gateway's create-conversation request is in flight. */
    val creatingThread: Boolean = false,
    /** The thread the create request started from, when there is one. */
    val creationSourceThreadId: String? = null,
    /** Whether the inbound reply channel is alive; a dead one explains silence. */
    val streamHealth: com.openandroidintelligence.conversation.model.StreamHealth =
        com.openandroidintelligence.conversation.model.StreamHealth.IDLE,
    /**
     * Whether an approval card can be answered on this Gateway at all.
     *
     * False is a fact the screen has to show: the text command remains the way
     * to answer, and no card is drawn that would pretend otherwise. It is
     * re-asserted by every [WorkbenchController] update, so it can never drift
     * away from the capability the Gateway actually negotiated.
     *
     * The default is false because this is a statement about the Gateway rather
     * than about the screen: with no negotiated capability in hand, "unsupported"
     * is the only answer a phone is entitled to give, and drawing a card on a
     * guess would put live buttons over a decision that has nowhere to go.
     */
    val approvalCardsSupported: Boolean = false,
    val isOnline: Boolean = true,
    /** Agent 在线与支持发送分别呈现，缺少发送能力不能冒充离线。 */
    val sendSupported: Boolean = true,
    val hasRecoverableSubmissions: Boolean = false,
    /** 未确认的发送结果单独保留，不阻止用户继续发送新消息。 */
    val hasUnknownSubmissionOutcome: Boolean = false,
    /** 本地发送身份装载完成之前不允许把旧消息误作一条新提交。 */
    val historyHydrated: Boolean = true,
    val organization: ConversationOrganization = ConversationOrganization(),
    /** Current browser selection also supplies the default for new conversations. */
    val selectedFolderId: String? = null,
    val pendingFolderAssignments: Map<String, String> = emptyMap(),
    val conversationManagementSupported: Boolean = false,
    val conversationDeleteSupported: Boolean = false,
    val conversationArchiveSupported: Boolean = false,
    val conversationUnarchiveSupported: Boolean = false,
    val conversationRenameSupported: Boolean = false,
    /** Native Agent folders and ordering are encrypted preferences on this phone. */
    val organizationStoredLocally: Boolean = false,
    val managingThreads: Boolean = false,
    val busyThreadIds: Set<String> = emptySet(),
    /** Attachments held by an in-flight Gateway send, not new composer input. */
    val inFlightAttachmentIds: Set<String> = emptySet(),
) {
    /** Keep a confirmed new conversation reachable while its durable folder work is pending. */
    val browserOrganization: ConversationOrganization get() = organization.copy(assignments=organization.assignments +
        pendingFolderAssignments.filter { (id, folderId) -> id !in organization.assignments && organization.folders.any { it.folderId == folderId } })
    val canSend: Boolean get() = isOnline && sendSupported && historyHydrated &&
        (draft.isNotBlank() || attachments.any { it.id.value !in inFlightAttachmentIds }) &&
        !managingThreads && !activeThreadArchived &&
        composer != ComposerState.SUBMITTING && composer != ComposerState.WAITING_ATTACHMENTS
    val activeThreadArchived: Boolean get() = (threads as? Loadable.Ready)?.value?.any { it.id.value == activeThreadId && it.archived } == true
}
