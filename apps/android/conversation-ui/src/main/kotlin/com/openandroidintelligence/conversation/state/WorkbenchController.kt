package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.batch.DebounceBatcher
import com.openandroidintelligence.conversation.batch.DebouncePolicy
import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.ports.AgentCommandCatalogRepository
import com.openandroidintelligence.conversation.ports.ConversationRepository
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.ports.TimelineMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

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
)

data class WorkbenchUiState(
    val threads: Loadable<List<ConversationSummary>> = Loadable.Idle,
    val timeline: Loadable<List<TimelineEntry>> = Loadable.Idle,
    val catalog: Loadable<AgentCommandCatalog> = Loadable.Idle,
    val activeThreadId: String? = null,
    val activeThreadTitle: String = "",
    val generation: GenerationState = GenerationState.IDLE,
    val draft: String = "",
    val attachments: List<com.openandroidintelligence.conversation.model.AttachmentDraft> = emptyList(),
    /** Batch members collected by the debounce window, newest last. */
    val pendingBatch: List<TimelineEntry> = emptyList(),
    val notice: String? = null,
)

/**
 * The workbench state holder: the single owner of what the conversation
 * screens render.
 *
 * Every state here is either typed from a repository result or from a domain
 * event the Gateway actually sent. There is no path that manufactures a
 * message, a title or a terminal generation state locally.
 */
class WorkbenchController(
    private val scope: CoroutineScope,
    private val repository: ConversationRepository,
    private val catalogRepository: AgentCommandCatalogRepository,
    private val scopeFactory: () -> ConversationScope,
    private val attachmentCoordinator: com.openandroidintelligence.conversation.ports.AttachmentDraftCoordinator? = null,
    debouncePolicy: DebouncePolicy = DebouncePolicy(),
    /** Reports the active thread so cancellation and events scope to the right conversation. */
    private val onActiveThreadChanged: (String?) -> Unit = {},
) {
    private val _state = MutableStateFlow(WorkbenchUiState())
    val state: StateFlow<WorkbenchUiState> = _state.asStateFlow()

    /** Server-mirrored messages for the active thread, by message id. */
    private val mirrored = LinkedHashMap<String, TimelineMessage>()

    private val attachmentJobs = LinkedHashMap<String, Job>()
    private val attachmentSelections = LinkedHashMap<String, com.openandroidintelligence.conversation.ports.LocalAttachmentSelection>()

    private var eventJob: Job? = null
    private var activeThreadId: String? = null

    private val batcher = DebounceBatcher(
        scope = scope,
        policy = debouncePolicy,
        onFlush = { conversationScope, messages -> submitBatch(conversationScope, messages) },
    )

    init {
        refreshThreads()
        loadCatalog()
    }

    fun retryTimeline() {
        activeThreadId?.let(::reloadTimeline) ?: refreshThreads()
    }

    fun refreshThreads() {
        update { it.copy(threads = Loadable.Loading) }
        scope.launch {
            val result = Result
                .runCatching { repository.listConversations(scopeFactory(), PageRequest()) }
                .toLoadable { page -> page.conversations.isEmpty() }
                .map { page -> page.conversations }
            update { it.copy(threads = result) }
            // Open the most recent thread automatically on a first successful load.
            if (result is Loadable.Ready && activeThreadId == null) {
                result.value.maxByOrNull { summary -> summary.updatedAt }?.let { openThread(it.id.value) }
            }
        }
    }

    fun loadCatalog() {
        update { it.copy(catalog = Loadable.Loading) }
        scope.launch {
            val result = Result
                .runCatching { catalogRepository.get(gatewayId = scopeFactory().gatewayId, languageCode = "zh-CN") }
                .toLoadable { catalog -> catalog.commands.isEmpty() }
            update { it.copy(catalog = result) }
        }
    }

    fun openThread(threadId: String) {
        if (activeThreadId == threadId) return
        activeThreadId = threadId
        onActiveThreadChanged(threadId)
        mirrored.clear()
        eventJob?.cancel()
        update {
            it.copy(
                activeThreadId = threadId,
                activeThreadTitle = threadTitleOf(threadId),
                timeline = Loadable.Loading,
                pendingBatch = emptyList(),
            )
        }

        scope.launch {
            Result.runCatching { repository.timeline(threadId, PageRequest()) }.fold(
                onSuccess = { page ->
                    mirrored.clear()
                    page.messages.forEach { mirrored[it.id] = it }
                    update { state ->
                        state.copy(
                            timeline = if (page.messages.isEmpty()) {
                                Loadable.Empty
                            } else {
                                Loadable.Ready(renderTimeline())
                            },
                        )
                    }
                },
                onFailure = { cause ->
                    update { it.copy(timeline = Loadable.Failed(errorCodeOf(cause))) }
                },
            )
        }
        observeThreadEvents()
    }

    fun createThread() {
        val clientConversationId = "cconv_" + UUID.randomUUID().toString().replace("-", "")
        scope.launch {
            Result.runCatching {
                repository.createConversation(scopeFactory(), clientConversationId)
            }.fold(
                onSuccess = { conversation ->
                    activeThreadId = conversation.id.value
                    onActiveThreadChanged(conversation.id.value)
                    mirrored.clear()
                    eventJob?.cancel()
                    update {
                        it.copy(
                            activeThreadId = conversation.id.value,
                            activeThreadTitle = conversation.title,
                            timeline = Loadable.Empty,
                            pendingBatch = emptyList(),
                            notice = null,
                        )
                    }
                    observeThreadEvents()
                    refreshThreads()
                },
                onFailure = { cause -> update { it.copy(notice = "CONVERSATION_CREATE_FAILED:${errorCodeOf(cause)}") } },
            )
        }
    }

    fun editDraft(text: String) {
        update { it.copy(draft = text) }
    }

    /**
     * Prepares and starts uploading a local attachment through the real three-step contract.
     */
    fun addAttachment(selection: com.openandroidintelligence.conversation.ports.LocalAttachmentSelection) {
        val coordinator = attachmentCoordinator ?: run {
            update { it.copy(notice = "ATTACHMENT_UNAVAILABLE:NO_COORDINATOR") }
            return
        }
        scope.launch {
            val draft = coordinator.prepare(selection)
            val draftId = draft.id.value
            attachmentSelections[draftId] = selection
            update { state ->
                state.copy(attachments = state.attachments + draft)
            }
            attachmentJobs[draftId]?.cancel()
            attachmentJobs[draftId] = scope.launch {
                coordinator.observe(draftId).collect { draftState ->
                    update { state ->
                        state.copy(
                            attachments = state.attachments.map { current ->
                                if (current.id.value == draftId) {
                                    current.copy(state = draftState.state)
                                } else {
                                    current
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * Removes an attachment draft and cancels its upload job.
     */
    fun removeAttachment(draftId: String) {
        attachmentJobs.remove(draftId)?.cancel()
        attachmentSelections.remove(draftId)
        update { state ->
            state.copy(attachments = state.attachments.filterNot { it.id.value == draftId })
        }
    }

    /**
     * Retries a recoverable failed attachment draft.
     */
    fun retryAttachment(draftId: String) {
        val coordinator = attachmentCoordinator ?: return
        val selection = attachmentSelections[draftId] ?: return
        coordinator.retry(draftId, selection)
    }

    /**
     * Sends the current draft.
     *
     * A slash command or anything carrying an attachment is a hard boundary and
     * goes straight out; plain text joins the debounce batch so the Agent sees
     * one ordered aggregate input while every member keeps its identity.
     */
    fun sendDraft() {
        val text = _state.value.draft.trim()
        val currentAttachments = _state.value.attachments

        // Check if there are unverified attachments still uploading
        val pendingUploads = currentAttachments.filter { it.state != com.openandroidintelligence.conversation.model.AttachmentState.VERIFIED }
        if (pendingUploads.isNotEmpty()) {
            update { it.copy(notice = "WAITING_ATTACHMENTS:附件正在上传中，请稍候") }
            return
        }

        if (text.isEmpty() && currentAttachments.isEmpty()) return

        val verifiedAttachmentIds = currentAttachments.mapNotNull { draft ->
            attachmentCoordinator?.remoteAttachmentId(draft.id.value) ?: draft.id.value
        }

        update { it.copy(draft = "", attachments = emptyList()) }
        currentAttachments.forEach { removeAttachment(it.id.value) }

        if (text.startsWith("/") || verifiedAttachmentIds.isNotEmpty()) {
            sendImmediate(text, verifiedAttachmentIds)
        } else {
            val entry = TimelineEntry(
                key = "local_" + UUID.randomUUID().toString(),
                sender = "user",
                text = text,
                isUser = true,
                timestamp = System.currentTimeMillis(),
                pendingAcceptance = true,
                batchGroupId = "batch_now",
            )
            update { it.copy(pendingBatch = it.pendingBatch + entry) }
            batcher.offer(
                scopeFactory(),
                OutgoingMessage(
                    clientMessageId = ClientMessageId(entry.key.removePrefix("local_")),
                    text = text,
                    attachmentIds = verifiedAttachmentIds,
                ),
            )
        }
    }

    private fun sendImmediate(text: String, attachmentIds: List<String> = emptyList()) {
        val entry = TimelineEntry(
            key = "local_" + UUID.randomUUID().toString(),
            sender = "user",
            text = if (text.isBlank() && attachmentIds.isNotEmpty()) "[附件已发送]" else text,
            isUser = true,
            timestamp = System.currentTimeMillis(),
            pendingAcceptance = true,
            batchGroupId = null,
        )
        update { it.copy(timeline = appendLocal(entry), pendingBatch = it.pendingBatch + entry) }
        scope.launch {
            Result.runCatching {
                repository.submitMessage(
                    OutgoingMessage(
                        clientMessageId = ClientMessageId(entry.key.removePrefix("local_")),
                        text = text,
                        attachmentIds = attachmentIds,
                    ),
                )
            }.fold(
                onSuccess = { update { s -> s.copy(notice = null) } },
                onFailure = { cause ->
                    update { s -> s.copy(notice = "SEND_FAILED:${errorCodeOf(cause)}") }
                },
            )
        }
    }

    private suspend fun submitBatch(conversationScope: ConversationScope, messages: List<OutgoingMessage>) {
        if (messages.isEmpty()) return
        val batchId = "batch_" + UUID.randomUUID().toString()
        val flushedKeys = messages.map { "local_" + it.clientMessageId.value }.toSet()
        Result.runCatching {
            repository.submitBatch(
                com.openandroidintelligence.conversation.ports.MessageBatch(
                    batchId = batchId,
                    messages = messages,
                    clientConversationId = activeThreadId,
                ),
            )
        }.fold(
            onSuccess = { acceptance ->
                update { state ->
                    state.copy(
                        pendingBatch = state.pendingBatch.filterNot { it.key in flushedKeys },
                        notice = null,
                    )
                }
                refreshThreads()
            },
            onFailure = { cause ->
                update { it.copy(notice = "SEND_FAILED:${errorCodeOf(cause)}") }
            },
        )
    }

    /** Fills the composer with a command; the user still confirms the send. */
    fun selectCommand(command: String) {
        val withSlash = if (command.startsWith("/")) command else "/$command"
        update { it.copy(draft = if (it.draft.isBlank()) withSlash else "$withSlash ") }
    }

    fun stopGeneration() {
        val generationId = (repository as? com.openandroidintelligence.conversation.ports.GenerationTracker)
            ?.generationId?.value
        if (generationId == null) {
            update { it.copy(notice = "STOP_UNAVAILABLE:NO_GENERATION") }
            return
        }
        update { it.copy(generation = GenerationState.CANCEL_REQUESTED) }
        scope.launch {
            Result.runCatching {
                repository.cancelGeneration(generationId, "req_" + UUID.randomUUID().toString().replace("-", ""))
            }.fold(
                onSuccess = { result ->
                    update { state ->
                        state.copy(
                            generation = when (result.outcome) {
                                com.openandroidintelligence.conversation.ports.CancelGenerationOutcome.CANCELLED ->
                                    GenerationState.CANCELLED
                                com.openandroidintelligence.conversation.ports.CancelGenerationOutcome.ALREADY_COMPLETED ->
                                    GenerationState.COMPLETED
                                com.openandroidintelligence.conversation.ports.CancelGenerationOutcome.UNSUPPORTED ->
                                    GenerationState.UNSUPPORTED
                                com.openandroidintelligence.conversation.ports.CancelGenerationOutcome.OUTCOME_UNKNOWN ->
                                    GenerationState.OUTCOME_UNKNOWN
                            },
                            notice = result.message,
                        )
                    }
                },
                onFailure = { cause ->
                    update { it.copy(generation = GenerationState.OUTCOME_UNKNOWN, notice = errorCodeOf(cause)) }
                },
            )
        }
    }

    fun dismissNotice() {
        update { it.copy(notice = null) }
    }

    private fun observeThreadEvents() {
        eventJob?.cancel()
        eventJob = scope.launch {
            repository.observeEvents(scopeFactory()).collect { event ->
                when (event) {
                    is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.MessageAccepted -> {
                        update { state ->
                            state.copy(
                                pendingBatch = state.pendingBatch.filterNot { it.key.endsWith(event.correlationId) },
                            )
                        }
                    }

                    is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.TimelineUpsert -> {
                        val message = event.message
                        if (message.sender == "user" || message.sender == "assistant") {
                            mirrored[message.id] = message
                        }
                        update { state ->
                            state.copy(
                                timeline = if (state.timeline is Loadable.Ready || state.timeline is Loadable.Empty) {
                                    Loadable.Ready(renderTimeline())
                                } else {
                                    state.timeline
                                },
                                generation = if (message.sender == "assistant" && message.state == "STREAMING") {
                                    GenerationState.RUNNING
                                } else if (message.sender == "assistant" && message.state == "CONFIRMED") {
                                    GenerationState.COMPLETED
                                } else {
                                    state.generation
                                },
                            )
                        }
                    }

                    is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.TimelineTombstoned -> {
                        mirrored.remove(event.messageId)
                        update { state ->
                            state.copy(timeline = Loadable.Ready(renderTimeline()))
                        }
                    }

                    is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.TitleUpdated -> {
                        if (event.conversationId.value == activeThreadId) {
                            update { it.copy(activeThreadTitle = event.newTitle) }
                        }
                        refreshThreads()
                    }

                    is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.GenerationCancelled -> {
                        update { it.copy(generation = GenerationState.CANCELLED) }
                    }

                    is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.SnapshotInvalidated -> {
                        activeThreadId?.let { id -> reloadTimeline(id) }
                    }

                    is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.CommandResult -> {
                        event.conversationId?.let { created ->
                            update { it.copy(notice = "已创建新对话") }
                            openThread(created.value)
                        }
                    }
                }
            }
        }
    }

    private fun reloadTimeline(threadId: String) {
        scope.launch {
            Result.runCatching { repository.timeline(threadId, PageRequest()) }
                .onSuccess { page ->
                    mirrored.clear()
                    page.messages.forEach { mirrored[it.id] = it }
                    update { it.copy(timeline = if (page.messages.isEmpty()) Loadable.Empty else Loadable.Ready(renderTimeline())) }
                }
        }
    }

    private fun renderTimeline(): List<TimelineEntry> =
        mirrored.values
            .sortedBy { it.timestamp }
            .map { message ->
                TimelineEntry(
                    key = message.id,
                    sender = message.sender,
                    text = message.parts.joinToString("") { part ->
                        when (part) {
                            is com.openandroidintelligence.conversation.model.MessagePart.Text -> part.value
                            is com.openandroidintelligence.conversation.model.MessagePart.Command -> part.rawText
                            is com.openandroidintelligence.conversation.model.MessagePart.Attachment -> "[附件]"
                        }
                    },
                    isUser = message.sender == "user",
                    timestamp = message.timestamp,
                    pendingAcceptance = message.state == "PENDING",
                    batchGroupId = null,
                )
            }

    private fun appendLocal(entry: TimelineEntry): Loadable<List<TimelineEntry>> {
        val current = when (val existing = _state.value.timeline) {
            is Loadable.Ready -> existing.value
            is Loadable.Empty -> emptyList()
            else -> emptyList()
        }
        return Loadable.Ready(current + entry)
    }

    private fun threadTitleOf(threadId: String): String =
        (_state.value.threads as? Loadable.Ready)
            ?.value
            ?.firstOrNull { it.id.value == threadId }
            ?.title
            ?: "新对话"

    private fun update(transform: (WorkbenchUiState) -> WorkbenchUiState) {
        _state.value = transform(_state.value)
    }
}
