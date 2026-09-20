package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.batch.DebounceBatcher
import com.openandroidintelligence.conversation.batch.DebouncePolicy
import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.ComposerState
import com.openandroidintelligence.conversation.model.AttachmentState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
    val attachments: List<com.openandroidintelligence.conversation.model.TimelineAttachment> = emptyList(),
    val isStreaming: Boolean = false,
)

data class WorkbenchUiState(
    val threads: Loadable<List<ConversationSummary>> = Loadable.Idle,
    val timeline: Loadable<List<TimelineEntry>> = Loadable.Idle,
    val catalog: Loadable<AgentCommandCatalog> = Loadable.Idle,
    val activeThreadId: String? = null,
    val activeThreadTitle: String = "",
    val generation: GenerationState = GenerationState.IDLE,
    val draft: String = "",
    val composer: ComposerState = ComposerState.EDITING,
    val attachments: List<com.openandroidintelligence.conversation.model.AttachmentDraft> = emptyList(),
    /** Batch members collected by the debounce window, newest last. */
    val pendingBatch: List<TimelineEntry> = emptyList(),
    val notice: String? = null,
    /** Whether the inbound reply channel is alive; a dead one explains silence. */
    val streamHealth: com.openandroidintelligence.conversation.model.StreamHealth =
        com.openandroidintelligence.conversation.model.StreamHealth.IDLE,
) {
    val canSend: Boolean get() = (draft.isNotBlank() || attachments.isNotEmpty()) &&
        composer != ComposerState.SUBMITTING && composer != ComposerState.WAITING_ATTACHMENTS
}

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
    private val supportsMessageBatches: Boolean = false,
    /** Reports the active thread so cancellation and events scope to the right conversation. */
    private val onActiveThreadChanged: (String?) -> Unit = {},
    /** The reply channel's health, when the repository can report it. */
    private val streamHealthSource: com.openandroidintelligence.conversation.model.StreamHealthSource? = null,
    /** Injectable wall clock and sleeper so the reply watchdog is testable. */
    private val replyTimeouts: ReplyTimeouts = ReplyTimeouts(),
) : AutoCloseable {

    /**
     * How long a send may stay unanswered before the phone stops trusting
     * silence and does something about it.
     */
    data class ReplyTimeouts(
        /** No first token within this time: pull the timeline as a fallback. */
        val firstReplyMillis: Long = 20_000L,
        /** Still unfinished after this: tell the user instead of spinning forever. */
        val giveUpMillis: Long = 120_000L,
        /** Off for a caller driving the controller with a virtual clock. */
        val enabled: Boolean = true,
    )
    private val _state = MutableStateFlow(WorkbenchUiState())
    val state: StateFlow<WorkbenchUiState> = _state.asStateFlow()

    /** Server-mirrored messages for the active thread, by message id. */
    private val mirrored = LinkedHashMap<String, TimelineMessage>()
    private val mirroredRevisions = LinkedHashMap<String, Long>()

    /**
     * Event ids already applied to the timeline.
     *
     * One frame can be offered more than once: a reconnect replays the account
     * backlog, and re-subscribing restarts the stream. Applying an event once,
     * keyed by the Gateway's own event id, is what keeps a replay from being
     * treated as new traffic by the timeline rules below.
     *
     * The Gateway client drops replays of its own accord; this holds the same
     * rule at the port, so a repository backed by something other than that
     * client cannot reintroduce duplicate frames.
     */
    private val handledEventIds = LinkedHashSet<String>()

    private val attachmentJobs = LinkedHashMap<String, Job>()
    private val attachmentSelections = LinkedHashMap<String, com.openandroidintelligence.conversation.ports.LocalAttachmentSelection>()
    private val historicalAttachments = object : LinkedHashMap<String, com.openandroidintelligence.conversation.model.TimelineAttachment>(32, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, com.openandroidintelligence.conversation.model.TimelineAttachment>?): Boolean {
            return size > 30
        }
    }

    /**
     * Whether this controller has been released.
     *
     * Cancelling the jobs alone is not a terminal state: a method called after
     * close would find no active job and simply start a new subscription on a
     * controller the owner has already thrown away.
     */
    private var closed = false

    private var eventJob: Job? = null
    private var timelineJob: Job? = null
    private var healthJob: Job? = null
    /** Watchdog for one send: the difference between "thinking" and "broken". */
    private var replyWatchdog: Job? = null
    private var awaitingReplyInThread: String? = null
    private var activeThreadId: String? = null
    private var creationJob: Deferred<Result<String>>? = null
    private var draftRevision = 0L
    private data class DraftSubmission(
        val text: String,
        val attachmentIds: List<String>,
        val revision: Long,
        val conversationId: String?,
    )
    private var pendingSubmission: DraftSubmission? = null
    private val userRenamedThreads = mutableSetOf<String>()

    val isCurrentThreadUserRenamed: Boolean
        get() = activeThreadId?.let { userRenamedThreads.contains(it) } ?: false

    fun isThreadUserRenamed(threadId: String): Boolean = userRenamedThreads.contains(threadId)

    /**
     * Renames one thread, on the Gateway's authority.
     *
     * The title is shown optimistically, but a Gateway that did not accept the
     * rename must not leave a title that looks saved: the previous title comes
     * back and the failure is explained. The user-override marker is only kept
     * when the rename actually landed, otherwise an Agent title suggestion would
     * be blocked forever by a rename that never happened.
     */
    fun renameThread(threadId: String, newTitle: String) {
        val previousTitle = if (activeThreadId == threadId) {
            _state.value.activeThreadTitle
        } else {
            threadTitleOf(threadId)
        }
        val alreadyUserRenamed = userRenamedThreads.contains(threadId)
        userRenamedThreads.add(threadId)
        if (activeThreadId == threadId) {
            update { it.copy(activeThreadTitle = newTitle) }
        }
        scope.launch {
            val saved = runCatching { repository.updateTitle(threadId, newTitle) }.getOrDefault(false)
            if (saved) {
                refreshThreads()
                return@launch
            }
            if (!alreadyUserRenamed) {
                userRenamedThreads.remove(threadId)
            }
            if (activeThreadId == threadId && _state.value.activeThreadTitle == newTitle) {
                update {
                    it.copy(
                        activeThreadTitle = previousTitle,
                        notice = "CONVERSATION_RENAME_FAILED:CONVERSATION_RENAME_REJECTED",
                    )
                }
            }
        }
    }

    fun renameActiveThread(newTitle: String) {
        val id = activeThreadId ?: return
        renameThread(id, newTitle)
    }

    override fun close() {
        closed = true
        timelineJob?.cancel()
        attachmentJobs.values.forEach { it.cancel() }
        attachmentJobs.clear()
        creationJob?.cancel()
        eventJob?.cancel()
        healthJob?.cancel()
        disarmReplyWatchdog()
        batcher.close()
    }

    fun cancel() = close()

    private val batcher = DebounceBatcher(
        scope = scope,
        policy = debouncePolicy,
        onFlush = { conversationScope, conversationId, messages ->
            submitBatch(conversationScope, conversationId, messages)
        },
    )

    init {
        refreshThreads()
        loadCatalog()
        observeStreamHealth()
    }

    /**
     * Mirrors the reply channel's health into the state a screen renders.
     *
     * Two transitions matter for a user waiting on an answer: going from live
     * to reconnecting means events produced in that gap may never arrive, so
     * the timeline is pulled once as a fallback; reaching failed is reported
     * outright, because a stream that gave up is a fact the user can act on.
     */
    private fun observeStreamHealth() {
        val source = streamHealthSource ?: return
        healthJob?.cancel()
        healthJob = scope.launch {
            source.streamHealth.collect { health ->
                val previous = _state.value.streamHealth
                update { it.copy(streamHealth = health) }
                if (previous == com.openandroidintelligence.conversation.model.StreamHealth.LIVE &&
                    health != com.openandroidintelligence.conversation.model.StreamHealth.LIVE
                ) {
                    activeThreadId?.let(::reloadTimeline)
                }
                if (health == com.openandroidintelligence.conversation.model.StreamHealth.FAILED) {
                    update { it.copy(notice = "EVENTS_FAILED:EVENT_STREAM_FAILED") }
                }
            }
        }
    }

    /**
     * Starts watching one sent message for an answer.
     *
     * A reply only ever arrives as an event, so a send with no event and no
     * timer can only ever be silence. This turns silence into either a
     * recovered timeline or a message the user can read.
     */
    private fun armReplyWatchdog(conversationId: String) {
        if (!replyTimeouts.enabled) return
        replyWatchdog?.cancel()
        awaitingReplyInThread = conversationId
        replyWatchdog = scope.launch {
            delay(replyTimeouts.firstReplyMillis)
            if (awaitingReplyInThread != conversationId) return@launch
            // The event for this reply may have been produced while the stream
            // was down; the timeline is the authoritative answer either way.
            if (activeThreadId == conversationId) reloadTimeline(conversationId)
            delay((replyTimeouts.giveUpMillis - replyTimeouts.firstReplyMillis).coerceAtLeast(0L))
            if (awaitingReplyInThread != conversationId) return@launch
            awaitingReplyInThread = null
            update { state ->
                if (state.activeThreadId != conversationId) return@update state
                state.copy(
                    notice = "REPLY_TIMEOUT:NO_REPLY_RECEIVED",
                    generation = if (state.generation == GenerationState.RUNNING) {
                        GenerationState.RUNNING
                    } else {
                        GenerationState.OUTCOME_UNKNOWN
                    },
                )
            }
        }
    }

    private fun disarmReplyWatchdog() {
        replyWatchdog?.cancel()
        replyWatchdog = null
        awaitingReplyInThread = null
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
            if (result is Loadable.Ready && activeThreadId == null && creationJob?.isActive != true) {
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
        cancelPendingSubmission()
        activeThreadId = threadId
        onActiveThreadChanged(threadId)
        mirrored.clear()
        mirroredRevisions.clear()
        timelineJob?.cancel()
        disarmReplyWatchdog()
        update {
            it.copy(
                activeThreadId = threadId,
                activeThreadTitle = threadTitleOf(threadId),
                timeline = Loadable.Loading,
                pendingBatch = emptyList(),
            )
        }

        timelineJob = scope.launch {
            val result = Result.runCatching { repository.timeline(threadId, PageRequest()) }
            if (!isActive || activeThreadId != threadId) return@launch
            result.fold(
                onSuccess = { page ->
                    if (!isActive || activeThreadId != threadId) return@launch
                    page.messages.sortedWith(
                        compareBy<TimelineMessage> { it.timestamp }.thenBy { if (it.sender == "user") 0 else 1 }
                    ).forEach { message ->
                        val currentRevision = mirroredRevisions[message.id] ?: 0L
                        if (!mirrored.containsKey(message.id) || (message.state == "CONFIRMED" && currentRevision == 0L)) {
                            if (message.sender == "assistant" && message.state == "CONFIRMED") {
                                pruneConfirmedAssistantDuplicates(message)
                            }
                            mirrored[message.id] = message
                            mirroredRevisions.putIfAbsent(message.id, 0L)
                        }
                    }
                    val hasStreaming = mirrored.values.any { it.sender == "assistant" && it.state == "STREAMING" }
                    update { state ->
                        if (!isActive || state.activeThreadId != threadId) state
                        else state.copy(
                            timeline = if (mirrored.isEmpty()) {
                                Loadable.Empty
                            } else {
                                Loadable.Ready(renderTimeline(state.pendingBatch))
                            },
                            generation = if (hasStreaming) GenerationState.RUNNING else GenerationState.IDLE,
                        )
                    }
                },
                onFailure = { cause ->
                    if (!isActive || activeThreadId != threadId) return@launch
                    update { state ->
                        if (!isActive || state.activeThreadId != threadId) state
                        else state.copy(timeline = Loadable.Failed(errorCodeOf(cause)))
                    }
                },
            )
        }
        observeThreadEvents()
    }

    fun createThread() {
        if (creationJob?.isActive == true) return
        cancelPendingSubmission()
        val creation = createThreadAsync()
        scope.launch {
            creation.await().onFailure { cause ->
                update { it.copy(notice = "CONVERSATION_CREATE_FAILED:${errorCodeOf(cause)}") }
            }
        }
    }

    private fun createThreadAsync(): Deferred<Result<String>> {
        creationJob?.takeIf { it.isActive }?.let { return it }
        return scope.async {
            try {
                val clientId = "cconv_" + UUID.randomUUID().toString().replace("-", "")
                val conversation = repository.createConversation(scopeFactory(), clientId)
                activeThreadId = conversation.id.value
                onActiveThreadChanged(conversation.id.value)
                mirrored.clear()
                mirroredRevisions.clear()
                timelineJob?.cancel()
                update {
                    it.copy(activeThreadId = conversation.id.value, activeThreadTitle = conversation.title,
                        timeline = Loadable.Empty, pendingBatch = emptyList(), notice = null)
                }
                observeThreadEvents()
                refreshThreads()
                Result.success(conversation.id.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                Result.failure(cause)
            }
        }.also { creationJob = it }
    }

    fun editDraft(text: String) {
        cancelPendingSubmission()
        draftRevision++
        update { it.copy(draft = text) }
    }

    fun cancelPendingSubmission() {
        pendingSubmission = null
        if (_state.value.composer == ComposerState.WAITING_ATTACHMENTS) {
            update { it.copy(composer = ComposerState.EDITING, notice = null) }
        }
    }

    /**
     * Prepares and starts uploading a local attachment through the real three-step contract.
     */
    fun addAttachment(selection: com.openandroidintelligence.conversation.ports.LocalAttachmentSelection) {
        val coordinator = attachmentCoordinator ?: run {
            update { it.copy(notice = "ATTACHMENT_UNAVAILABLE:NO_COORDINATOR") }
            return
        }
        cancelPendingSubmission()
        draftRevision++
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
                                    current.copy(state = draftState.state, errorMessage = draftState.errorMessage)
                                } else {
                                    current
                                }
                            },
                        )
                    }
                    submitWhenAttachmentsVerified()
                }
            }
        }
    }

    /**
     * Removes an attachment draft and cancels its upload job.
     */
    fun removeAttachment(draftId: String) {
        cancelPendingSubmission()
        draftRevision++
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
        val current = _state.value
        if (!current.canSend) return
        pendingSubmission = DraftSubmission(current.draft, current.attachments.map { it.id.value }, draftRevision, activeThreadId)
        update { it.copy(composer = ComposerState.WAITING_ATTACHMENTS, notice = null, generation = GenerationState.QUEUED) }
        submitWhenAttachmentsVerified()
    }

    /** A click freezes the draft; upload progress can release that same submission only once. */
    private fun submitWhenAttachmentsVerified() {
        val submission = pendingSubmission ?: return
        val drafts = _state.value.attachments.associateBy { it.id.value }
        if (submission.attachmentIds.any { drafts[it]?.state != AttachmentState.VERIFIED }) return
        val remoteIds = submission.attachmentIds.map { id ->
            attachmentCoordinator?.remoteAttachmentId(id) ?: run {
                pendingSubmission = null
                update { it.copy(composer = ComposerState.FAILED, notice = "ATTACHMENT_NOT_VERIFIED:请重新选择附件") }
                return
            }
        }
        pendingSubmission = null
        update { it.copy(composer = ComposerState.SUBMITTING) }
        scope.launch {
            var localEntryKey: String? = null
            var sendTarget: String? = null
            // Whether this send emptied the composer: only then may a failure
            // hand the text back. The draft revision cannot answer that,
            // because releasing the attachment drafts below advances it too.
            var draftWasCleared = false
            try {
                val target = submission.conversationId ?: activeThreadId ?: createThreadAsync().await().getOrThrow()
                sendTarget = target
                // Only clear the snapshot that was sent; typing during creation keeps the newer draft.
                if (draftRevision == submission.revision) {
                    update { it.copy(draft = "") }
                    draftWasCleared = true
                }
                val submittedAttachments = submission.attachmentIds.map { id ->
                    val sel = attachmentSelections[id]
                    val d = drafts[id]
                    val att = com.openandroidintelligence.conversation.model.TimelineAttachment(
                        draftId = id,
                        filename = sel?.filename ?: d?.filename.orEmpty(),
                        mediaType = sel?.mediaType ?: d?.mediaType.orEmpty(),
                        imageBytes = sel?.bytes,
                    )
                    historicalAttachments[id] = att
                    att
                }
                submission.attachmentIds.zip(remoteIds).forEach { (draftId, remoteId) ->
                    historicalAttachments[draftId]?.let { att ->
                        historicalAttachments[remoteId] = att.copy(draftId = remoteId)
                    }
                }
                val entry = TimelineEntry(
                    key = "local_" + UUID.randomUUID().toString(), sender = "user",
                    text = submission.text.ifBlank { if (submittedAttachments.isNotEmpty()) "" else "[附件]" }, isUser = true,
                    timestamp = System.currentTimeMillis(), pendingAcceptance = true,
                    batchGroupId = null,
                    attachments = submittedAttachments,
                )
                val message = OutgoingMessage(ClientMessageId(entry.key.removePrefix("local_")), submission.text, remoteIds)
                localEntryKey = entry.key
                update { it.copy(composer = ComposerState.EDITING, timeline = appendLocal(it.timeline, entry), pendingBatch = it.pendingBatch + entry, generation = GenerationState.QUEUED) }

                if (eventJob?.isActive != true) {
                    observeThreadEvents()
                }

                val currentTitle = _state.value.activeThreadTitle
                val needsAutoTitle = (currentTitle.isBlank() || currentTitle == "新对话") && !userRenamedThreads.contains(target)
                if (needsAutoTitle) {
                    val autoTitle = com.openandroidintelligence.conversation.title.ConversationTitlePolicy.generateTitle(
                        firstMessage = message,
                        attachmentNames = submittedAttachments.map { it.filename },
                    )
                    if (autoTitle.isNotBlank() && autoTitle != "新对话" && !userRenamedThreads.contains(target)) {
                        update { it.copy(activeThreadTitle = autoTitle) }
                        scope.launch {
                            val saved = runCatching { repository.updateTitle(target, autoTitle) }.getOrDefault(false)
                            if (saved) {
                                refreshThreads()
                            } else if (activeThreadId == target &&
                                _state.value.activeThreadTitle == autoTitle &&
                                !userRenamedThreads.contains(target)
                            ) {
                                // The Gateway never stored this title, so the phone
                                // must not keep displaying it as if it had.
                                update { it.copy(activeThreadTitle = currentTitle) }
                            }
                        }
                    }
                }
                if (supportsMessageBatches && !submission.text.trimStart().startsWith("/") && remoteIds.isEmpty()) {
                    batcher.offer(scopeFactory(), target, message)
                } else {
                    val acceptance = repository.submitMessage(target, message)
                    if (activeThreadId == target) {
                        mirrored[acceptance.messageId] = TimelineMessage(
                            id = acceptance.messageId, sender = "user",
                            parts = buildList {
                                if (message.text.isNotEmpty()) add(com.openandroidintelligence.conversation.model.MessagePart.Text(message.text))
                                submittedAttachments.zip(remoteIds).forEach { (att, remoteId) ->
                                    add(com.openandroidintelligence.conversation.model.MessagePart.Attachment(
                                        draftId = com.openandroidintelligence.conversation.model.AttachmentDraftId(remoteId),
                                        filename = att.filename,
                                        mediaType = att.mediaType,
                                    ))
                                }
                            },
                            timestamp = entry.timestamp,
                        )
                        if (!mirroredRevisions.containsKey(acceptance.messageId)) {
                            mirroredRevisions[acceptance.messageId] = 0L
                        }
                        update { state ->
                            val remainingBatch = state.pendingBatch.filterNot { row -> row.key == entry.key }
                            state.copy(
                                timeline = Loadable.Ready(renderTimeline(remainingBatch)),
                                pendingBatch = remainingBatch,
                                notice = null,
                                generation = if (state.generation == GenerationState.RUNNING) GenerationState.RUNNING else GenerationState.QUEUED,
                            )
                        }
                        armReplyWatchdog(target)
                    }
                }
                // Released only now: an attachment draft that was dropped
                // before the Gateway accepted the message left a failed send
                // with nothing to retry from.
                submission.attachmentIds.forEach(::removeAttachment)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                // A message the Gateway never accepted must not stay on screen
                // as a pending send: the retry used to stack a second copy of
                // the same text beside the one that had already failed, and the
                // user saw the same message twice.
                update { state ->
                    val failedKey = localEntryKey
                    val remainingBatch = if (failedKey == null) {
                        state.pendingBatch
                    } else {
                        state.pendingBatch.filterNot { it.key == failedKey }
                    }
                    state.copy(
                        composer = ComposerState.FAILED,
                        notice = "SEND_FAILED:${errorCodeOf(cause)}",
                        // The timeline belongs to whichever thread is open now:
                        // a send that failed after a thread switch must not
                        // repaint the conversation the user moved to.
                        timeline = if (sendTarget != null && state.activeThreadId == sendTarget) {
                            Loadable.Ready(renderTimeline(remainingBatch))
                        } else {
                            state.timeline
                        },
                        pendingBatch = remainingBatch,
                        // The text returns to the composer while it is still
                        // untouched, so a retry sends it again instead of
                        // leaving the user with nothing to send.
                        draft = if (draftWasCleared && state.draft.isEmpty()) {
                            submission.text
                        } else {
                            state.draft
                        },
                    )
                }
            }
        }
    }

    private suspend fun submitBatch(
        conversationScope: ConversationScope,
        conversationId: String,
        messages: List<OutgoingMessage>,
    ) {
        if (messages.isEmpty()) return
        val batchId = "batch_" + UUID.randomUUID().toString()
        val flushedKeys = messages.map { "local_" + it.clientMessageId.value }.toSet()
        Result.runCatching {
            // The batch already carries its conversation: re-deriving the target
            // from a side map is how a member used to end up in the wrong thread.
            repository.submitBatch(conversationId, com.openandroidintelligence.conversation.ports.MessageBatch(
                batchId = batchId, messages = messages, clientConversationId = conversationId,
            ))
        }.fold(
            onSuccess = { acceptance ->
                val pendingEntries = _state.value.pendingBatch
                messages.forEach { msg ->
                    val localId = msg.clientMessageId.value
                    // The Gateway's id is the only key the mirror may use: a
                    // member mirrored under its own local id showed up a second
                    // time as soon as the same message reached the phone under
                    // the id the Gateway had issued for it.
                    val id = acceptance.memberIds[localId]?.takeIf { it.isNotBlank() } ?: localId
                    val entry = pendingEntries.firstOrNull { it.key == "local_$localId" || it.key == localId }
                    val timestamp = entry?.timestamp ?: System.currentTimeMillis()
                    val parts = buildList {
                        if (msg.text.isNotEmpty()) add(com.openandroidintelligence.conversation.model.MessagePart.Text(msg.text))
                        entry?.attachments?.forEach { att ->
                            add(
                                com.openandroidintelligence.conversation.model.MessagePart.Attachment(
                                    draftId = com.openandroidintelligence.conversation.model.AttachmentDraftId(att.draftId),
                                    filename = att.filename,
                                    mediaType = att.mediaType,
                                )
                            )
                        }
                    }
                    mirrored[id] = TimelineMessage(
                        id = id,
                        sender = "user",
                        parts = parts,
                        timestamp = timestamp,
                    )
                    if (!mirroredRevisions.containsKey(id)) {
                        mirroredRevisions[id] = 0L
                    }
                }
                update { state ->
                    val remainingBatch = state.pendingBatch.filterNot { it.key in flushedKeys }
                    state.copy(
                        timeline = Loadable.Ready(renderTimeline(remainingBatch)),
                        pendingBatch = remainingBatch,
                        notice = null,
                    )
                }
                activeThreadId?.let(::armReplyWatchdog)
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
        update {
            val rest = if (it.draft.contains(' ')) it.draft.substringAfter(' ') else ""
            val newDraft = if (rest.isEmpty()) "$withSlash " else "$withSlash $rest"
            it.copy(draft = newDraft)
        }
    }

    fun stopGeneration() {
        val generationId = (repository as? com.openandroidintelligence.conversation.ports.GenerationTracker)
            ?.generationId?.value
        if (generationId == null) {
            update { it.copy(generation = GenerationState.UNSUPPORTED, notice = "STOP_UNAVAILABLE:NO_GENERATION") }
            return
        }
        update { it.copy(generation = GenerationState.CANCEL_REQUESTED) }
        scope.launch {
            Result.runCatching {
                repository.cancelGeneration(generationId, "req_" + UUID.randomUUID().toString().replace("-", ""))
            }.fold(
                onSuccess = { result ->
                    if (result.outcome == com.openandroidintelligence.conversation.ports.CancelGenerationOutcome.CANCELLED) {
                        mirrored.values.filter { it.state == "STREAMING" }.forEach { streamingMsg ->
                            mirrored[streamingMsg.id] = streamingMsg.copy(state = "CANCELLED")
                        }
                    }
                    update { state ->
                        state.copy(
                            timeline = Loadable.Ready(renderTimeline(state.pendingBatch)),
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

    /**
     * Subscribes to the account's event stream, once.
     *
     * The stream carries every conversation of the account, so switching
     * threads has no reason to restart it. Cancelling and re-opening the
     * subscription per thread switch left the dying stream and the new one
     * overlapping — the window in which one event could be applied twice.
     */
    private fun observeThreadEvents() {
        if (closed) return
        if (eventJob?.isActive == true) return
        eventJob = scope.launch {
            repository.observeEvents(scopeFactory())
                .retryWhen { cause, _ ->
                    if (cause is CancellationException) {
                        false
                    } else {
                        update { it.copy(notice = "EVENTS_FAILED:${errorCodeOf(cause)}") }
                        delay(1000L)
                        true
                    }
                }
                .collect { event ->
                    if (!isActive) return@collect
                    val currentActiveId = activeThreadId ?: run {
                        refreshThreads()
                        return@collect
                    }
                    // Recorded only once the event can actually be routed: an
                    // event that arrived while no thread was open must still be
                    // applied when one is, not be swallowed as already seen.
                    if (!markEventHandled(event.eventId)) return@collect
                    when (event) {
                        is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.MessageAccepted -> {
                            val eventConvId = event.conversationId?.value
                            val correlationId = event.correlationId
                            if (eventConvId == currentActiveId && correlationId.isNotBlank()) {
                                update { state ->
                                    state.copy(
                                        pendingBatch = state.pendingBatch.filterNot {
                                            it.key == "local_$correlationId" || it.key == correlationId
                                        },
                                    )
                                }
                            }
                        }

                        is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.TimelineUpsert -> {
                            val message = event.message
                            val eventConvId = message.conversationId?.value
                            if (eventConvId != null && eventConvId != currentActiveId) {
                                refreshThreads()
                                return@collect
                            }
                            val previousRevision = mirroredRevisions[message.id]
                            if (previousRevision != null && event.revision < previousRevision) {
                                return@collect
                            }
                            var matchedCurrentTurn = false
                            if (message.sender == "assistant") {
                                val messageText = messageTextOf(message)
                                if (message.state == "CONFIRMED") {
                                    matchedCurrentTurn = pruneConfirmedAssistantDuplicates(message)
                                    if (matchedCurrentTurn) {
                                        disarmReplyWatchdog()
                                    }
                                } else if (message.state == "STREAMING") {
                                    disarmReplyWatchdog()
                                    // If a confirmed assistant message with this content already exists, skip adding this late streaming chunk
                                    val alreadyConfirmed = mirrored.values.any {
                                        it.sender == "assistant" &&
                                        it.state == "CONFIRMED" &&
                                        ((normalizeEntryKey(it.id).isNotBlank() && normalizeEntryKey(it.id) == normalizeEntryKey(message.id)) ||
                                         (messageText.isNotBlank() &&
                                          !hasUserMessageBetween(it.timestamp, message.timestamp) &&
                                          it.timestamp >= message.timestamp - 5_000L &&
                                          Math.abs(it.timestamp - message.timestamp) <= 120_000L &&
                                          messageTextOf(it).startsWith(messageText)))
                                    }
                                    if (alreadyConfirmed) {
                                        return@collect
                                    }
                                    // Prune any other older streaming assistant message with different id
                                    val oldStreamingKeys = mirrored.filterValues { it.sender == "assistant" && it.state == "STREAMING" && it.id != message.id }.keys.toList()
                                    for (k in oldStreamingKeys) {
                                        mirrored.remove(k)
                                        mirroredRevisions.remove(k)
                                    }
                                }
                            }
                            if (message.sender == "user" || message.sender == "assistant") {
                                mirrored[message.id] = message
                                mirroredRevisions[message.id] = event.revision
                            }
                            update { state ->
                                if (!isActive) return@update state
                                state.copy(
                                    timeline = if (state.timeline is Loadable.Ready || state.timeline is Loadable.Empty) {
                                        Loadable.Ready(renderTimeline(state.pendingBatch))
                                    } else {
                                        state.timeline
                                    },
                                    generation = if (message.sender == "assistant" && message.state == "STREAMING") {
                                        GenerationState.RUNNING
                                    } else if (message.sender == "assistant" && message.state == "CONFIRMED") {
                                        if (matchedCurrentTurn && (state.generation == GenerationState.RUNNING || state.generation == GenerationState.QUEUED)) {
                                            GenerationState.COMPLETED
                                        } else {
                                            state.generation
                                        }
                                    } else {
                                        state.generation
                                    },
                                )
                            }
                        }

                        is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.TimelineTombstoned -> {
                            val eventConvId = event.conversationId?.value
                            if (eventConvId == currentActiveId) {
                                mirrored.remove(event.messageId)
                                mirroredRevisions.remove(event.messageId)
                                update { state ->
                                    state.copy(timeline = Loadable.Ready(renderTimeline(state.pendingBatch)))
                                }
                            }
                        }

                        is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.TitleUpdated -> {
                            val threadId = event.conversationId.value
                            if (!userRenamedThreads.contains(threadId)) {
                                if (threadId == activeThreadId) {
                                    update { it.copy(activeThreadTitle = event.newTitle) }
                                }
                            }
                            refreshThreads()
                        }

                        is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.GenerationCancelled -> {
                            val eventConvId = event.conversationId?.value
                            if (eventConvId == currentActiveId) {
                                mirrored.values.filter { it.state == "STREAMING" }.forEach { streamingMsg ->
                                    mirrored[streamingMsg.id] = streamingMsg.copy(state = "CANCELLED")
                                }
                                update { state ->
                                    state.copy(
                                        generation = GenerationState.CANCELLED,
                                        timeline = Loadable.Ready(renderTimeline(state.pendingBatch)),
                                    )
                                }
                            }
                        }

                        is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.SnapshotInvalidated -> {
                            val eventConvId = event.conversationId?.value
                            if (eventConvId == currentActiveId) {
                                reloadTimeline(currentActiveId)
                            }
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
        timelineJob?.cancel()
        timelineJob = scope.launch {
            val result = Result.runCatching { repository.timeline(threadId, PageRequest()) }
            if (!isActive || activeThreadId != threadId) return@launch
            result.onSuccess { page ->
                if (!isActive || activeThreadId != threadId) return@launch
                var receivedConfirmedAssistantForCurrentTurn = false
                page.messages.sortedWith(
                    compareBy<TimelineMessage> { it.timestamp }.thenBy { if (it.sender == "user") 0 else 1 }
                ).forEach { message ->
                    val currentRevision = mirroredRevisions[message.id] ?: 0L
                    if (!mirrored.containsKey(message.id) || (message.state == "CONFIRMED" && currentRevision == 0L)) {
                        if (message.sender == "assistant" && message.state == "CONFIRMED") {
                            val matched = pruneConfirmedAssistantDuplicates(message)
                            if (matched) {
                                receivedConfirmedAssistantForCurrentTurn = true
                            }
                        }
                        mirrored[message.id] = message
                        mirroredRevisions.putIfAbsent(message.id, 0L)
                    } else if (message.sender == "assistant" && message.state == "CONFIRMED") {
                        val matched = pruneConfirmedAssistantDuplicates(message)
                        if (matched) {
                            receivedConfirmedAssistantForCurrentTurn = true
                        }
                    }
                }
                if (receivedConfirmedAssistantForCurrentTurn) {
                    disarmReplyWatchdog()
                }
                update { state ->
                    if (!isActive || state.activeThreadId != threadId) state
                    else {
                        val hasStreaming = mirrored.values.any { it.sender == "assistant" && it.state == "STREAMING" }
                        state.copy(
                            timeline = if (mirrored.isEmpty()) Loadable.Empty else Loadable.Ready(renderTimeline(state.pendingBatch)),
                            generation = if (hasStreaming) {
                                GenerationState.RUNNING
                            } else if (receivedConfirmedAssistantForCurrentTurn && (state.generation == GenerationState.RUNNING || state.generation == GenerationState.QUEUED)) {
                                GenerationState.COMPLETED
                            } else {
                                state.generation
                            },
                        )
                    }
                }
            }
        }
    }

    private fun hasUserMessageBetween(t1: Long, t2: Long): Boolean {
        val minT = minOf(t1, t2)
        val maxT = maxOf(t1, t2)
        if (minT == maxT) return false
        val userInMirrored = mirrored.values.any { it.sender == "user" && it.timestamp > minT && it.timestamp <= maxT }
        val userInPending = _state.value.pendingBatch.any { it.isUser && it.timestamp > minT && it.timestamp <= maxT }
        return userInMirrored || userInPending
    }

    private fun hasUserMessageAfter(t: Long): Boolean {
        val userInMirrored = mirrored.values.any { it.sender == "user" && it.timestamp > t }
        val userInPending = _state.value.pendingBatch.any { it.isUser && it.timestamp > t }
        return userInMirrored || userInPending
    }

    private fun messageTextOf(message: TimelineMessage): String =
        message.parts.filterIsInstance<com.openandroidintelligence.conversation.model.MessagePart.Text>()
            .joinToString("") { it.value }

    /**
     * Integrates a confirmed assistant message by:
     * 1. Removing duplicate confirmed assistant messages in [mirrored] (same normalized key or identical content within the SAME turn).
     * 2. Pruning in-flight streaming drafts that this confirmed message supersedes (same normalized key, or within turn window where confirmed text covers streaming text).
     *
     * Returns true if this confirmed message superseded an active streaming draft or
     * belongs to the active turn (its timestamp is at or after the active question, with no intervening user question).
     */
    private fun pruneConfirmedAssistantDuplicates(message: TimelineMessage): Boolean {
        if (message.sender != "assistant" || message.state != "CONFIRMED") return false

        val messageText = messageTextOf(message)

        // 1. Remove duplicate confirmed assistant messages if identical content in the same turn or same normalized key
        val duplicateConfirmedKeys = mirrored.entries.filter { (k, v) ->
            k != message.id &&
                v.sender == "assistant" &&
                v.state == "CONFIRMED" &&
                ((normalizeEntryKey(k).isNotBlank() && normalizeEntryKey(k) == normalizeEntryKey(message.id)) ||
                    (Math.abs(v.timestamp - message.timestamp) <= 60_000L &&
                        !hasUserMessageBetween(v.timestamp, message.timestamp) &&
                        ((messageText.isNotBlank() && messageTextOf(v) == messageText) ||
                            (message.parts.isNotEmpty() && v.parts == message.parts))))
        }.map { it.key }
        for (dupKey in duplicateConfirmedKeys) {
            mirrored.remove(dupKey)
            mirroredRevisions.remove(dupKey)
        }

        // 2. Prune in-flight streaming drafts that this confirmed message supersedes
        val latestUserTimestamp = maxOf(
            mirrored.values.filter { it.sender == "user" }.maxOfOrNull { it.timestamp } ?: 0L,
            _state.value.pendingBatch.filter { it.isUser }.maxOfOrNull { it.timestamp } ?: 0L,
        )
        val streamingEntries = mirrored.filterValues { it.sender == "assistant" && it.state == "STREAMING" }
        var supersededActiveStreaming = false
        for ((k, s) in streamingEntries) {
            val sameNormalizedKey = normalizeEntryKey(s.id).isNotBlank() &&
                normalizeEntryKey(s.id) == normalizeEntryKey(message.id)
            val sText = messageTextOf(s)
            val isTurnWindow = !hasUserMessageBetween(s.timestamp, message.timestamp) &&
                message.timestamp >= s.timestamp - 5_000L &&
                Math.abs(message.timestamp - s.timestamp) <= 120_000L &&
                (latestUserTimestamp == 0L || message.timestamp >= latestUserTimestamp - 10_000L)
            val isCoveredContent = (messageText.isNotBlank() && (messageText == sText || messageText.startsWith(sText))) ||
                (s.parts.isNotEmpty() && s.parts == message.parts)
            val isStaleEmpty = sText.isBlank() &&
                s.parts.none { it is com.openandroidintelligence.conversation.model.MessagePart.Attachment } &&
                message.timestamp >= s.timestamp

            if (sameNormalizedKey || (isTurnWindow && (isCoveredContent || isStaleEmpty))) {
                mirrored.remove(k)
                mirroredRevisions.remove(k)
                supersededActiveStreaming = true
            }
        }

        if (supersededActiveStreaming) {
            return true
        }

        // 3. If there were no streaming drafts superseded, check if this confirmed message belongs
        // to the latest active turn (i.e. arrived at or after the latest user message, and no user message came after it)
        val hasUserAfter = hasUserMessageAfter(message.timestamp)
        return !hasUserAfter && latestUserTimestamp > 0L && message.timestamp >= latestUserTimestamp - 5_000L
    }

    private fun renderTimeline(pendingBatch: List<TimelineEntry> = _state.value.pendingBatch): List<TimelineEntry> {
        val mirroredEntries = mirrored.values
            .map { message ->
                val messageAttachments = message.parts.filterIsInstance<com.openandroidintelligence.conversation.model.MessagePart.Attachment>()
                    .map { att ->
                        val id = att.draftId.value
                        historicalAttachments[id] ?: run {
                            val sel = attachmentSelections[id]
                            val d = _state.value.attachments.firstOrNull { it.id.value == id }
                            com.openandroidintelligence.conversation.model.TimelineAttachment(
                                draftId = id,
                                filename = (sel?.filename ?: d?.filename).takeUnless { it.isNullOrBlank() } ?: att.filename,
                                mediaType = (sel?.mediaType ?: d?.mediaType).takeUnless { it.isNullOrBlank() } ?: att.mediaType,
                                imageBytes = sel?.bytes,
                            )
                        }
                    }
                val textParts = message.parts.joinToString("") { part ->
                    when (part) {
                        is com.openandroidintelligence.conversation.model.MessagePart.Text -> part.value
                        is com.openandroidintelligence.conversation.model.MessagePart.Command -> part.rawText
                        is com.openandroidintelligence.conversation.model.MessagePart.Attachment -> ""
                    }
                }
                val displayText = if (textParts.isBlank() && messageAttachments.isEmpty()) {
                    if (message.parts.any { it is com.openandroidintelligence.conversation.model.MessagePart.Attachment }) "[附件]" else ""
                } else {
                    textParts
                }
                TimelineEntry(
                    key = message.id,
                    sender = message.sender,
                    text = displayText,
                    isUser = message.sender == "user",
                    timestamp = message.timestamp,
                    pendingAcceptance = message.state == "PENDING",
                    batchGroupId = null,
                    attachments = messageAttachments,
                    isStreaming = message.state == "STREAMING",
                )
            }

        val mirroredKeys = mirrored.keys
        val unconfirmedPending = pendingBatch.filterNot { entry ->
            mirroredKeys.contains(entry.key) || mirroredKeys.contains(entry.key.removePrefix("local_"))
        }

        val rawList = (mirroredEntries + unconfirmedPending).sortedWith(
            compareBy<TimelineEntry> { entry ->
                if (entry.timestamp > 0L) entry.timestamp else Long.MAX_VALUE
            }.thenBy { entry ->
                if (entry.pendingAcceptance) 1 else 0
            }.thenBy { entry ->
                if (entry.isStreaming) 0 else 1
            },
        )
        return deduplicateTimelineEntries(rawList)
    }

    private fun normalizeEntryKey(key: String): String =
        key.removePrefix("local_")
            .removePrefix("stream_")
            .removePrefix("msg_")
            .removePrefix("cconv_")

    private fun deduplicateTimelineEntries(entries: List<TimelineEntry>): List<TimelineEntry> {
        if (entries.size <= 1) return entries

        val result = mutableListOf<TimelineEntry>()
        var i = 0
        while (i < entries.size) {
            val entry = entries[i]
            if (entry.isUser) {
                val lastEntry = result.lastOrNull()
                val isDuplicateUser = lastEntry != null && lastEntry.isUser &&
                    ((normalizeEntryKey(entry.key) == normalizeEntryKey(lastEntry.key)) ||
                     (entry.text == lastEntry.text && entry.attachments == lastEntry.attachments &&
                      Math.abs(entry.timestamp - lastEntry.timestamp) <= 10_000L))
                if (isDuplicateUser) {
                    if (lastEntry!!.pendingAcceptance && !entry.pendingAcceptance) {
                        result[result.size - 1] = entry
                    }
                } else {
                    result.add(entry)
                }
                i++
            } else {
                val assistantGroup = mutableListOf<TimelineEntry>()
                while (i < entries.size && !entries[i].isUser) {
                    assistantGroup.add(entries[i])
                    i++
                }
                val deduplicatedAssistant = deduplicateAssistantGroup(assistantGroup)
                result.addAll(deduplicatedAssistant)
            }
        }
        return result
    }

    private fun deduplicateAssistantGroup(group: List<TimelineEntry>): List<TimelineEntry> {
        if (group.size <= 1) return group

        val confirmed = group.filter { !it.isStreaming }
        val streaming = group.filter { it.isStreaming }

        val keptStreaming = if (confirmed.isNotEmpty()) {
            streaming.filter { s ->
                confirmed.none { c ->
                    val sameNormalizedKey = normalizeEntryKey(s.key).isNotBlank() &&
                        normalizeEntryKey(s.key) == normalizeEntryKey(c.key)
                    val timeDiff = c.timestamp - s.timestamp
                    val isSameTurn = timeDiff >= -5_000L && Math.abs(timeDiff) <= 120_000L
                    val isCoveredByConfirmed = (s.text.isNotBlank() && (c.text == s.text || c.text.startsWith(s.text))) ||
                        (s.attachments.isNotEmpty() && s.attachments == c.attachments)
                    val isStaleEmpty = s.text.isBlank() && s.attachments.isEmpty() && c.timestamp >= s.timestamp
                    sameNormalizedKey || (isSameTurn && (isCoveredByConfirmed || isStaleEmpty))
                }
            }
        } else {
            val deduplicatedStreaming = mutableListOf<TimelineEntry>()
            for (s in streaming) {
                val duplicateIndex = deduplicatedStreaming.indexOfFirst { existing ->
                    (normalizeEntryKey(s.key).isNotBlank() && normalizeEntryKey(s.key) == normalizeEntryKey(existing.key)) ||
                    (Math.abs(s.timestamp - existing.timestamp) <= 60_000L &&
                        (existing.text.startsWith(s.text) || s.text.startsWith(existing.text)))
                }
                if (duplicateIndex != -1) {
                    val existing = deduplicatedStreaming[duplicateIndex]
                    if (s.text.length > existing.text.length) {
                        deduplicatedStreaming[duplicateIndex] = s
                    }
                } else {
                    deduplicatedStreaming.add(s)
                }
            }
            deduplicatedStreaming
        }

        val keptConfirmed = mutableListOf<TimelineEntry>()
        for (c in confirmed) {
            val isDuplicate = keptConfirmed.any { existing ->
                (normalizeEntryKey(c.key).isNotBlank() && normalizeEntryKey(c.key) == normalizeEntryKey(existing.key)) ||
                ((c.text.isNotEmpty() || c.attachments.isNotEmpty()) &&
                 c.text == existing.text && c.attachments == existing.attachments &&
                 Math.abs(c.timestamp - existing.timestamp) <= 60_000L)
            }
            if (!isDuplicate) {
                keptConfirmed.add(c)
            }
        }

        return (keptConfirmed + keptStreaming).sortedWith(
            compareBy<TimelineEntry> { entry ->
                if (entry.timestamp > 0L) entry.timestamp else Long.MAX_VALUE
            }.thenBy { entry ->
                if (entry.isStreaming) 0 else 1
            },
        )
    }

    private fun appendLocal(
        currentTimeline: Loadable<List<TimelineEntry>>,
        entry: TimelineEntry,
    ): Loadable<List<TimelineEntry>> {
        val current = when (currentTimeline) {
            is Loadable.Ready -> currentTimeline.value
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

    /**
     * True when this event has not been applied yet.
     *
     * A blank id carries no identity to deduplicate on, so it is always
     * applied; dropping it would lose the frame entirely.
     */
    private fun markEventHandled(eventId: String): Boolean {
        if (eventId.isBlank()) return true
        if (!handledEventIds.add(eventId)) return false
        if (handledEventIds.size > MAX_HANDLED_EVENT_IDS) {
            val iterator = handledEventIds.iterator()
            repeat(MAX_HANDLED_EVENT_IDS / 2) {
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        return true
    }

    private fun update(transform: (WorkbenchUiState) -> WorkbenchUiState) {
        _state.update(transform)
    }

    private companion object {
        /**
         * How many event ids stay remembered.
         *
         * Only a reconnect distance has to fit, and a reconnect resumes from
         * the stored cursor, so that distance is the events produced while the
         * socket was down. The cap keeps a long session bounded, and the events
         * it forgets are ones the timeline pull re-covers anyway.
         */
        const val MAX_HANDLED_EVENT_IDS = 2048
    }
}
