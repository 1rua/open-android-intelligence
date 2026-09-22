package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.batch.DebounceBatcher
import com.openandroidintelligence.conversation.batch.DebouncePolicy
import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.ApprovalId
import com.openandroidintelligence.conversation.model.ApprovalOutcome
import com.openandroidintelligence.conversation.model.ApprovalSubmissionOutcome
import com.openandroidintelligence.conversation.model.ApprovalSubmissionResult
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
import com.openandroidintelligence.conversation.model.toOutcome
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    /**
     * True while the phone is waiting for the Agent's own new conversation.
     *
     * Nothing else has changed yet: the switch happens only once the Gateway
     * names the conversation it created, so this flag describes a request in
     * flight rather than a conversation that already exists.
     */
    val creatingThread: Boolean = false,
    /**
     * The thread a `/new` in flight was sent from, when there is one.
     *
     * The wait belongs to that thread: switching away must not make the newly
     * opened conversation look like it is waiting for something of its own.
     */
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
    /**
     * Whether this Gateway agreed to serve the `/new` command entry.
     *
     * Without it the workbench refuses to "create" anything at all: a
     * conversation the phone invented would exist only here, and every message
     * sent afterwards would be filed under an id the Agent has never heard of.
     */
    private val supportsAgentCommandNew: Boolean = false,
    /** How long the phone waits for the Agent to answer `/new`. */
    private val newConversationTimeouts: NewConversationTimeouts = NewConversationTimeouts(),
    /** Reports the active thread so cancellation and events scope to the right conversation. */
    private val onActiveThreadChanged: (String?) -> Unit = {},
    /** The reply channel's health, when the repository can report it. */
    private val streamHealthSource: com.openandroidintelligence.conversation.model.StreamHealthSource? = null,
    /** Injectable wall clock and sleeper so the reply watchdog is testable. */
    private val replyTimeouts: ReplyTimeouts = ReplyTimeouts(),
    /**
     * Whether this Gateway serves interactive approval cards (contract §7.2).
     *
     * Without it the screen must say cards are unavailable rather than draw one:
     * a card whose press cannot be delivered would leave the command blocked
     * while the UI shows a decision the Gateway never received.
     */
    private val supportsApprovalCards: Boolean = false,
    /** Wall clock, injectable so a countdown can be advanced by a test. */
    private val clock: () -> Long = { System.currentTimeMillis() },
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

    /**
     * How long the phone waits for the Agent to answer its own `/new`.
     *
     * The new conversation arrives as an event, so silence is indistinguishable
     * from "nothing happened". Giving up is what turns that silence into a fact
     * the user can read instead of a spinner that never ends.
     */
    data class NewConversationTimeouts(
        /**
         * Long enough for a loaded Gateway to accept a message and publish its
         * event, short enough that a dead command entry is reported while the
         * user is still looking at it.
         */
        val timeoutMillis: Long = NEW_CONVERSATION_TIMEOUT_MILLIS,
        /**
         * How long a result may still be in flight after the Agent already
         * answered in the thread `/new` was sent from.
         *
         * The Agent's own turn is over at that point, so the command result is
         * either already on its way or will never arrive. Long enough to absorb
         * the gap between two frames of the same turn on a slow link, short
         * enough that "creating" does not outlive the reply the user is reading.
         */
        val commandResultGraceMillis: Long = COMMAND_RESULT_GRACE_MILLIS,
        /** Off for a caller driving the controller with a virtual clock. */
        val enabled: Boolean = true,
    )
    // The capability is written in from the start: the data class default only
    // says "no negotiated fact yet", so a Gateway that did offer cards would
    // otherwise read as one that never offered them until the first update ran.
    private val _state = MutableStateFlow(WorkbenchUiState(approvalCardsSupported = supportsApprovalCards))
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

    /**
     * The one `/new` request waiting for the Agent's answer.
     *
     * At most one exists, keyed to the thread it was sent from: the switch is
     * only safe for as long as the user is still looking at that thread.
     */
    private data class PendingCreation(
        val sourceThreadId: String,
        val clientMessageId: ClientMessageId,
        /**
         * The newest timestamp the source thread already had when `/new` was sent.
         *
         * A reply newer than this is part of this turn; anything at or below it is
         * history the phone had already read (a replay, not this turn's answer).
         * Comparing server timestamps with each other avoids trusting the phone's
         * clock, which the host's clock may disagree with.
         */
        val replyBaselineTimestamp: Long = 0L,
        /** The id the Gateway issued for the `/new` message, once it answered. */
        var sourceMessageId: String? = null,
        /**
         * Answers that arrived before the send response could name the request.
         *
         * The event stream and the HTTP reply are separate channels, so the
         * result of this very `/new` can show up while [sourceMessageId] is
         * still null. They wait here instead of being judged against a
         * half-known request.
         */
        val parkedResults: MutableList<com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.CommandResult> =
            mutableListOf(),
    )

    private var pendingCreation: PendingCreation? = null
    private var creationSendJob: Job? = null
    private var creationWatchdog: Job? = null
    /** Armed once the Agent answers in the source thread while a result is pending. */
    private var creationGraceJob: Job? = null

    /**
     * A `/new` request this phone stopped waiting for.
     *
     * Only what a late answer can be recognised by is kept. The answer may not
     * reverse the ending the user was already told about — but it is also the
     * one answer this record is about, so the record is spent on first match
     * rather than shadowing every later result from the same thread.
     */
    private data class SettledCreation(
        val sourceThreadId: String,
        val sourceMessageId: String?,
    )

    /**
     * Requests this phone stopped waiting for.
     *
     * A late answer must not reverse a timeout or a failed send: the user was
     * already told the request ended, so the answer is only allowed to enrich
     * the thread list, never to move the screen.
     */
    private val settledCreations = ArrayDeque<SettledCreation>()

    /**
     * What each `/new` this phone sent from a thread actually created.
     *
     * The conversation the user was reading keeps a compact receipt for it, so
     * "the new conversation" is reachable by one tap instead of being lost the
     * moment the phone switches away.
     */
    private data class CreationReceipt(val threadId: String, val createdAt: Long)

    private val creationReceipts = LinkedHashMap<String, CreationReceipt>()

    /**
     * The zero-conversation bootstrap still talks to the Gateway directly.
     *
     * `/new` is a message, so it needs a thread to travel in. When an account
     * has none yet there is nowhere to send it, and the first user message
     * would otherwise have no thread to be filed under either. This is the one
     * place a conversation may be created by the endpoint instead of the Agent,
     * and it exists solely so that "nothing exists yet" is not a dead end.
     */
    private var bootstrapJob: Deferred<Result<String>>? = null
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
        bootstrapJob?.cancel()
        creationSendJob?.cancel()
        creationWatchdog?.cancel()
        creationGraceJob?.cancel()
        creationGraceJob = null
        pendingCreation = null
        settledCreations.clear()
        approvalCards.clear()
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
            if (result is Loadable.Ready && activeThreadId == null && bootstrapJob?.isActive != true) {
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
        // Rendering cache of the conversation being left: without this, a message
        // of the newly opened thread could be rendered with an attachment's
        // filename from another one.
        historicalAttachments.clear()
        // Approval cards are deliberately NOT cleared here: they belong to the
        // account, not to the open thread. Coming back must show the same card
        // with the countdown it had, never a fresh one — and a card the Gateway
        // already settled must not come back as a question.
        // (mirrored/mirroredRevisions are message state and are cleared above.)
        timelineJob?.cancel()
        disarmReplyWatchdog()
        update {
            it.copy(
                activeThreadId = threadId,
                activeThreadTitle = threadTitleOf(threadId),
                timeline = Loadable.Loading,
                pendingBatch = emptyList(),
                // Generation, composer and notice describe the conversation being
                // left: a turn it was still running, or the failure that turn
                // produced, must not read as the state of the one being opened.
                generation = GenerationState.IDLE,
                composer = ComposerState.EDITING,
                notice = null,
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
                    maybeArmCreationGraceFromTimeline(threadId)
                    val hasStreaming = mirrored.values.any { it.sender == "assistant" && it.state == "STREAMING" }
                    update { state ->
                        if (!isActive || state.activeThreadId != threadId) state
                        else state.copy(
                            timeline = if (
                                mirrored.isEmpty() &&
                                creationReceiptRow(threadId) == null &&
                                approvalRowsForActiveThread().isEmpty()
                            ) {
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

    /**
     * Asks the Agent for a new conversation.
     *
     * The phone does not decide the new conversation's identity: it sends
     * `/new` into the thread it is leaving and waits for the Gateway to name
     * what it created. Until that named answer arrives nothing here moves — the
     * timeline, the title and the send target all still belong to the thread
     * the user can see, which is exactly why a failure needs no rollback of a
     * state that was never claimed.
     */
    fun createThread() {
        if (closed) return
        if (pendingCreation != null || bootstrapJob?.isActive == true) return
        if (!supportsAgentCommandNew) {
            // Refusing is the honest answer. Manufacturing a thread here would
            // put the user in a conversation the Gateway cannot serve, and the
            // first message would be filed under an id only this phone knows.
            update { it.copy(notice = "CONVERSATION_CREATE_UNSUPPORTED:GATEWAY_UNSUPPORTED") }
            return
        }
        val sourceThreadId = activeThreadId
        if (sourceThreadId == null) {
            // `/new` travels as a message, so it needs the thread it leaves.
            // With no thread there is nothing to send it into.
            update { it.copy(notice = "CONVERSATION_CREATE_UNAVAILABLE:NO_SOURCE_CONVERSATION") }
            return
        }
        cancelPendingSubmission()
        // A retry supersedes what this thread was given up on before: the
        // answer to an abandoned request cannot keep shadowing a new one.
        settledCreations.removeAll { it.sourceThreadId == sourceThreadId }
        val clientMessageId = ClientMessageId("cmd_" + UUID.randomUUID().toString().replace("-", ""))
        pendingCreation = PendingCreation(
            sourceThreadId = sourceThreadId,
            clientMessageId = clientMessageId,
            // Read before the command is mirrored, so the echo of `/new` itself
            // cannot raise the baseline above the reply we are waiting for.
            replyBaselineTimestamp = mirrored.values.maxOfOrNull { it.timestamp } ?: 0L,
        )
        update { it.copy(creatingThread = true, creationSourceThreadId = sourceThreadId, notice = null) }
        armCreationWatchdog()
        observeThreadEvents()
        creationSendJob?.cancel()
        creationSendJob = scope.launch {
            Result.runCatching { repository.submitMessage(sourceThreadId, OutgoingMessage(clientMessageId, NEW_CONVERSATION_COMMAND)) }
                .onFailure { cause ->
                    if (cause is CancellationException) throw cause
                    // Nothing to undo beyond the wait: no local thread was made.
                    abandonAgentThreadCreation("CONVERSATION_CREATE_FAILED:${errorCodeOf(cause)}")
                }
                .onSuccess { acceptance ->
                    val waiting = pendingCreation
                    if (waiting == null || waiting.clientMessageId.value != clientMessageId.value) return@onSuccess
                    // Recorded so a later command result can be tied to this
                    // very request instead of to any other `/new` on the account.
                    waiting.sourceMessageId = acceptance.messageId
                    // Only now can the results that beat this reply be judged.
                    replayParkedCreationResults(waiting)
                    // The command stays visible in the thread it was sent from,
                    // mirrored under the id the Gateway issued for it.
                    mirrorCommandInSourceThread(sourceThreadId, clientMessageId.value, acceptance.messageId)
                }
        }
    }

    /**
     * Stops waiting for the Agent's answer to `/new`.
     *
     * The in-flight request is cancelled, so the Agent may never create anything
     * from it at all. If it did create something before noticing, that answer is
     * treated like any other abandoned one — it may still enrich the thread list,
     * but it must not move the screen after the wait was reported over.
     */
    fun cancelThreadCreation() {
        if (pendingCreation == null) return
        creationSendJob?.cancel()
        abandonAgentThreadCreation("CONVERSATION_CREATE_CANCELLED:USER_CANCELLED")
    }

    /**
     * Remembers where a `/new` sent from one thread ended up.
     *
     * The phone does not author conversations, so the only honest receipt is one
     * for a thread this phone actually sent the command from; without a source
     * there is nothing to attach the receipt to.
     */
    private fun rememberCreationReceipt(sourceThreadId: String?, createdThreadId: String) {
        if (sourceThreadId == null || sourceThreadId == createdThreadId) return
        creationReceipts.remove(sourceThreadId)
        creationReceipts[sourceThreadId] = CreationReceipt(createdThreadId, System.currentTimeMillis())
        while (creationReceipts.size > MAX_CREATION_RECEIPTS) {
            val eldest = creationReceipts.keys.firstOrNull() ?: break
            creationReceipts.remove(eldest)
        }
    }

    /** The navigation row a thread shows when a `/new` sent from it succeeded. */
    private fun creationReceiptRow(threadId: String): TimelineEntry? {
        val receipt = creationReceipts[threadId] ?: return null
        return TimelineEntry(
            key = "system_new_created_${receipt.threadId}",
            sender = "system",
            text = NEW_CONVERSATION_NOTICE,
            isUser = false,
            timestamp = receipt.createdAt,
            pendingAcceptance = false,
            batchGroupId = null,
            systemThreadId = receipt.threadId,
        )
    }

    /**
     * Every approval card this phone has been shown, keyed by approval id.
     *
     * The cards deliberately outlive the thread they arrived in: leaving a
     * conversation and coming back must show the same card with the same
     * countdown and the same outcome, and a settled card must never come back as
     * a question. The map is bounded so a long session cannot grow without end.
     */
    private val approvalCards = LinkedHashMap<String, ApprovalCardState>()

    /**
     * Records one approval the Gateway asked for (contract §7.2).
     *
     * A replay of an event the phone already applied changes nothing, and a
     * second `requested` for a card that is already settled is ignored: the
     * Gateway has had its say, and re-opening the question locally would put a
     * live button under a command that is no longer waiting.
     */
    private fun rememberApprovalRequest(
        event: com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.ApprovalRequested,
    ) {
        // A Gateway that cannot take a decision gets no card at all: drawing one
        // would put live buttons under a command whose answer has nowhere to go
        // (contract §4 — never fake a card that cannot be submitted).
        if (!supportsApprovalCards) return
        val existing = approvalCards[event.request.approvalId.value]
        if (existing != null) return
        approvalCards[event.request.approvalId.value] = ApprovalCardState.Waiting(event.request)
        while (approvalCards.size > MAX_APPROVAL_CARDS) {
            val eldest = approvalCards.keys.firstOrNull() ?: break
            approvalCards.remove(eldest)
        }
        reRenderApprovalRows()
    }

    /**
     * Applies how one approval ended.
     *
     * Only the Gateway may settle a card. Its answer also wins over a press that
     * is still in flight: the user sees the outcome the Gateway recorded rather
     * than the one the phone was hoping for.
     */
    private fun applyApprovalResolution(
        event: com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.ApprovalResolved,
    ) {
        val key = event.approvalId.value
        val card = approvalCards[key] ?: return
        if (card is ApprovalCardState.Resolved && card.outcome != ApprovalOutcome.UNKNOWN) {
            return
        }
        approvalCards[key] = ApprovalCardState.Resolved(
            request = card.request,
            outcome = event.outcome,
            decidedAt = event.decidedAt,
        )
        reRenderApprovalRows()
    }

    /**
     * One button press, sent through the approval's own endpoint.
     *
     * The whole group locks before anything is sent: two presses for one card
     * would be two decisions, and the Gateway is the only thing allowed to say
     * which one counted. A failed press becomes a live card again with a
     * structured notice — it is never painted as the decision the user asked for.
     */
    fun decideApproval(approvalId: String, choice: ApprovalChoice) {
        if (closed) return
        // Checked before anything else: on a Gateway without the decision
        // endpoint there is no card to press, so a decision must be refused as
        // an unavailable capability rather than by finding no card.
        if (!supportsApprovalCards) {
            update { it.copy(notice = ApprovalNotices.UNSUPPORTED) }
            return
        }
        val card = approvalCards[approvalId]
        if (card !is ApprovalCardState.Waiting) return
        if (card.request.countdownAt(clock()).expired) {
            // The window the Gateway gave is over: the buttons are already grey,
            // and sending a decision the Gateway will refuse would only look
            // like a failure of the app.
            update { it.copy(notice = ApprovalNotices.EXPIRED) }
            return
        }
        approvalCards[approvalId] = ApprovalCardState.Submitting(card.request, choice)
        reRenderApprovalRows()
        scope.launch {
            val result = submitApprovalDecision(approvalId, choice)
            applyApprovalSubmission(approvalId, result)
        }
    }

    private suspend fun submitApprovalDecision(
        approvalId: String,
        choice: ApprovalChoice,
    ): ApprovalSubmissionResult {
        // An id the wire format cannot carry is refused here rather than sent:
        // the press still comes back as a live card, with the refusal stated.
        val id = try {
            ApprovalId(approvalId)
        } catch (cause: Exception) {
            // Cancellation is never a refusal: it keeps travelling.
            if (cause is CancellationException) throw cause
            return ApprovalSubmissionResult(outcome = ApprovalSubmissionOutcome.FAILED)
        }
        return try {
            repository.submitApprovalDecision(id, choice)
        } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            ApprovalSubmissionResult(outcome = ApprovalSubmissionOutcome.FAILED)
        }
    }

    /**
     * Turns the Gateway's answer to one press into the card's state.
     *
     * `SUBMITTED` does not settle the card on its own: the phone has been told
     * the decision is with the Gateway, not that the Gateway granted it, so the
     * `resolved` event is what ends the wait. A refusal the Gateway explains —
     * already answered elsewhere, or expired — is a fact it did record, so that
     * one is final too.
     */
    private fun applyApprovalSubmission(
        approvalId: String,
        result: ApprovalSubmissionResult,
    ) {
        val card = approvalCards[approvalId] ?: return
        if (card !is ApprovalCardState.Submitting) return
        when (result.outcome) {
            ApprovalSubmissionOutcome.SUBMITTED -> {
                // Stay in flight: only the Gateway's own event may close it.
                return
            }
            ApprovalSubmissionOutcome.ALREADY_RESOLVED -> {
                approvalCards[approvalId] = ApprovalCardState.Resolved(
                    request = card.request,
                    // A settled verdict the Gateway named (`timeout`/`withdrawn`)
                    // is shown as it is; a tier translates through the shared
                    // token table, and a missing one stays unknown.
                    outcome = result.settledOutcome
                        ?: (result.choice?.toOutcome() ?: ApprovalOutcome.UNKNOWN),
                    decidedAt = clock(),
                )
                reRenderApprovalRows()
            }
            ApprovalSubmissionOutcome.EXPIRED -> {
                approvalCards[approvalId] = ApprovalCardState.Resolved(
                    request = card.request,
                    outcome = result.settledOutcome ?: ApprovalOutcome.TIMED_OUT,
                    decidedAt = clock(),
                )
                reRenderApprovalRows()
            }
            ApprovalSubmissionOutcome.NOT_FOUND -> {
                // The Gateway does not know this approval, so nothing can answer
                // it any more. What became of the command is unknown to this
                // phone — claiming "withdrawn" would state that it did not run,
                // which the Gateway never said.
                approvalCards[approvalId] = ApprovalCardState.Resolved(
                    request = card.request,
                    outcome = ApprovalOutcome.UNKNOWN,
                    decidedAt = clock(),
                )
                reRenderApprovalRows()
            }
            ApprovalSubmissionOutcome.UNSUPPORTED -> {
                approvalCards[approvalId] = ApprovalCardState.Waiting(card.request)
                reRenderApprovalRows()
                update { it.copy(notice = ApprovalNotices.UNSUPPORTED) }
            }
            ApprovalSubmissionOutcome.FAILED -> {
                approvalCards[approvalId] = ApprovalCardState.Waiting(card.request)
                reRenderApprovalRows()
                update { it.copy(notice = ApprovalNotices.FAILED) }
            }
        }
    }

    /** The rows the active thread's approval cards contribute, oldest first. */
    private fun approvalRowsForActiveThread(): List<TimelineEntry> {
        val threadId = activeThreadId ?: return emptyList()
        return approvalCards.values
            .filter { card -> card.request.conversationId?.value == threadId }
            .map { card ->
                TimelineEntry(
                    key = approvalTimelineKey(card.request.approvalId.value),
                    sender = "system",
                    text = card.request.command,
                    isUser = false,
                    timestamp = card.request.requestedAt,
                    pendingAcceptance = false,
                    batchGroupId = null,
                    approval = card,
                )
            }
            .sortedBy { it.timestamp }
    }

    private fun reRenderApprovalRows() {
        update { state ->
            if (state.timeline is Loadable.Ready || state.timeline is Loadable.Empty) {
                state.copy(timeline = Loadable.Ready(renderTimeline(state.pendingBatch)))
            } else {
                state
            }
        }
    }

    private fun mirrorCommandInSourceThread(sourceThreadId: String, clientMessageId: String, messageId: String) {
        if (activeThreadId != sourceThreadId) return
        mirrored[messageId] = TimelineMessage(
            id = messageId,
            sender = "user",
            parts = listOf(com.openandroidintelligence.conversation.model.MessagePart.Command(NEW_CONVERSATION_COMMAND)),
            timestamp = System.currentTimeMillis(),
            conversationId = ConversationId(sourceThreadId),
        )
        mirroredRevisions.putIfAbsent(messageId, 0L)
        update { state ->
            if (state.activeThreadId != sourceThreadId) state
            else state.copy(timeline = Loadable.Ready(renderTimeline(state.pendingBatch)))
        }
    }

    private fun armCreationWatchdog() {
        if (!newConversationTimeouts.enabled) return
        val waiting = pendingCreation ?: return
        // A new request starts a new wait: a grace armed for the previous one
        // must not end it.
        creationGraceJob?.cancel()
        creationGraceJob = null
        creationWatchdog?.cancel()
        creationWatchdog = scope.launch {
            delay(newConversationTimeouts.timeoutMillis)
            if (pendingCreation !== waiting) return@launch
            abandonAgentThreadCreation("CONVERSATION_CREATE_TIMEOUT:NO_COMMAND_RESULT")
        }
    }

    private fun disarmCreationWatchdog() {
        creationWatchdog?.cancel()
        creationWatchdog = null
        creationGraceJob?.cancel()
        creationGraceJob = null
    }

    /**
     * Stops trusting that a command result is still on its way.
     *
     * The Agent finished a turn in the thread `/new` was sent from and nothing
     * arrived that names a created conversation. A host that owns the command
     * entry answers with `conversation.command.result`; one that merely passed
     * `/new` on to the Agent never will. Waiting the full timeout after the Agent
     * has visibly answered is exactly the "creating forever" spinner, so the wait
     * ends here — honestly, without inventing a conversation.
     */
    private fun armCreationGrace() {
        if (!newConversationTimeouts.enabled) return
        if (creationGraceJob?.isActive == true) return
        val waiting = pendingCreation ?: return
        creationGraceJob = scope.launch {
            delay(newConversationTimeouts.commandResultGraceMillis)
            if (pendingCreation !== waiting) return@launch
            abandonAgentThreadCreation("CONVERSATION_CREATE_NO_RESULT:AGENT_REPLIED_WITHOUT_RESULT")
        }
    }

    /**
     * Whether one event is evidence that the `/new` turn is over.
     *
     * Only a reply newer than the thread's state when the command was sent counts:
     * a reconnect replays the account backlog, and history is not evidence about
     * this request. The reply also has to belong to the thread the command was
     * sent from — an answer in some other conversation says nothing about this one.
     *
     * A streaming host publishes the reply as deltas and then a completion; both
     * carry the reply's own timestamp, so either frame is enough to recognise it.
     */
    private fun maybeArmCreationGrace(conversationId: String?, message: TimelineMessage) {
        val waiting = pendingCreation ?: return
        if (conversationId != waiting.sourceThreadId) return
        if (message.sender != "assistant" || message.state != "CONFIRMED") return
        if (message.timestamp <= waiting.replyBaselineTimestamp) return
        armCreationGrace()
    }

    /**
     * The same evidence, read instead of pushed.
     *
     * A reply can also reach the phone through a timeline read — a reconnect, a
     * health-driven reload, a manual retry — and the wait has to end the same way.
     */
    private fun maybeArmCreationGraceFromTimeline(threadId: String) {
        val waiting = pendingCreation ?: return
        if (threadId != waiting.sourceThreadId) return
        // Without a baseline the thread was empty when the command was sent, and a
        // reply read back from it cannot be told apart from one that predates the
        // request: the slower watchdog is the honest answer there.
        if (waiting.replyBaselineTimestamp <= 0L) return
        val newestReply = mirrored.values
            .filter { it.sender == "assistant" && it.state == "CONFIRMED" }
            .maxOfOrNull { it.timestamp }
            ?: return
        if (newestReply > waiting.replyBaselineTimestamp) armCreationGrace()
    }

    private fun abandonAgentThreadCreation(notice: String) {
        val waiting = pendingCreation ?: return
        pendingCreation = null
        // The wait is over either way; a late answer belongs to a request the
        // user has already been told ended, so it must not still move them.
        rememberSettledCreation(waiting)
        disarmCreationWatchdog()
        update { it.copy(creatingThread = false, creationSourceThreadId = null, notice = notice) }
    }

    private fun rememberSettledCreation(waiting: PendingCreation) {
        // Answers parked for this wait die with it: a send judged failed leaves
        // the decision to the user rather than completing it behind their back.
        settledCreations.addLast(SettledCreation(waiting.sourceThreadId, waiting.sourceMessageId))
        while (settledCreations.size > MAX_SETTLED_CREATIONS) settledCreations.removeFirst()
    }

    /**
     * Applies the Agent's answer to `/new`.
     *
     * Only a `created-conversation` outcome naming a conversation is authority
     * to switch, and only while the user is still where the request started:
     * hijacking the screen after they moved on would be the same
     * disagreement in the opposite direction.
     */
    private fun applyAgentThreadCreation(
        event: com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.CommandResult,
    ) {
        val waiting = pendingCreation ?: return
        val eventSource = event.sourceConversationId?.value
        if (eventSource != null && eventSource != waiting.sourceThreadId) return
        // An answer that names a source message while ours is still unnamed
        // cannot be told apart honestly, so it waits for the send response
        // instead of being judged on the conversation alone.
        if (waiting.sourceMessageId == null && !event.sourceMessageId.isNullOrBlank()) {
            if (waiting.parkedResults.none { it.eventId == event.eventId } &&
                waiting.parkedResults.size < MAX_PARKED_CREATION_RESULTS
            ) waiting.parkedResults += event
            return
        }
        if (!isThisCreationAnswer(event, waiting)) return
        when (event.outcome) {
            com.openandroidintelligence.conversation.ports.CommandOutcome.CREATED_CONVERSATION -> {
                val newThreadId = event.conversationId?.value
                if (newThreadId.isNullOrBlank()) {
                    abandonAgentThreadCreation("CONVERSATION_CREATE_FAILED:MISSING_CONVERSATION_ID")
                    return
                }
                // The receipt is kept either way: the user may already have
                // moved on, and the conversation that was created must stay
                // reachable from the thread that asked for it.
                rememberCreationReceipt(waiting.sourceThreadId, newThreadId)
                pendingCreation = null
                disarmCreationWatchdog()
                if (activeThreadId != waiting.sourceThreadId) {
                    update { it.copy(creatingThread = false, creationSourceThreadId = null, notice = NEW_CONVERSATION_NOTICE) }
                    refreshThreads()
                    return
                }
                switchToAgentCreatedThread(newThreadId)
            }
            com.openandroidintelligence.conversation.ports.CommandOutcome.REJECTED ->
                abandonAgentThreadCreation("CONVERSATION_CREATE_FAILED:REJECTED")
            com.openandroidintelligence.conversation.ports.CommandOutcome.UNSUPPORTED ->
                abandonAgentThreadCreation("CONVERSATION_CREATE_UNSUPPORTED:COMMAND_REJECTED")
            com.openandroidintelligence.conversation.ports.CommandOutcome.OUTCOME_UNKNOWN ->
                abandonAgentThreadCreation("CONVERSATION_CREATE_FAILED:OUTCOME_UNKNOWN")
        }
    }

    /**
     * Whether one command result is the answer to the request still pending.
     *
     * The event stream is account-wide, so `/new` results produced elsewhere —
     * another device, another thread of this account — arrive here too. A
     * result that names a different source is not ours and must not move the
     * screen; a result with no source at all is accepted only because a
     * Gateway that predates the field cannot be told apart from ours.
     */
    private fun isThisCreationAnswer(
        event: com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.CommandResult,
        waiting: PendingCreation,
    ): Boolean {
        val source = event.sourceConversationId?.value
        if (source != null && source != waiting.sourceThreadId) return false
        val sourceMessage = event.sourceMessageId
        if (!sourceMessage.isNullOrBlank() && waiting.sourceMessageId != null &&
            sourceMessage != waiting.sourceMessageId
        ) return false
        return true
    }

    /**
     * Judges the answers that arrived before the send response did.
     *
     * Once the Gateway has named the `/new` message, every parked result can
     * finally be matched against it; the first one that is ours decides, and
     * the rest are dropped because one request has one answer.
     */
    private fun replayParkedCreationResults(waiting: PendingCreation) {
        val parked = waiting.parkedResults.toList()
        waiting.parkedResults.clear()
        parked.firstOrNull { isThisCreationAnswer(it, waiting) }?.let(::applyAgentThreadCreation)
    }

    /** The single way a Gateway-named conversation becomes the open one. */
    private fun switchToAgentCreatedThread(newThreadId: String) {
        openThread(newThreadId)
        syncThreadMetadata(newThreadId)
        update {
            it.copy(
                creatingThread = false,
                creationSourceThreadId = null,
                notice = NEW_CONVERSATION_NOTICE,
            )
        }
    }

    /**
     * Pulls the conversation the Gateway named, so what the user reads comes
     * from the Gateway rather than from what the phone assumed.
     */
    private fun syncThreadMetadata(threadId: String) {
        scope.launch {
            val detail = runCatching { repository.readConversation(threadId) }.getOrNull()
            refreshThreads()
            if (detail == null) return@launch
            if (activeThreadId == threadId && !isThreadUserRenamed(threadId)) {
                update { it.copy(activeThreadTitle = detail.title) }
            }
        }
    }

    /**
     * The one sanctioned place a conversation is created by endpoint.
     *
     * Reachable only from the first send on an account that has no thread:
     * `/new` is a message, so it needs somewhere to travel, and a first message
     * needs a thread to be filed under. The "new conversation" entry point
     * never calls this — it asks the Agent, which is what makes the resulting
     * id authoritative on both sides.
     */
    private fun bootstrapConversationForFirstMessage(): Deferred<Result<String>> {
        bootstrapJob?.takeIf { it.isActive }?.let { return it }
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
        }.also { bootstrapJob = it }
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
        if (pendingCreation != null) {
            // A send during the wait would land in the thread being left —
            // exactly the mismatch this flow exists to prevent.
            update { it.copy(notice = "CONVERSATION_CREATING:SEND_BLOCKED") }
            return
        }
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
                val target = submission.conversationId ?: activeThreadId
                    ?: bootstrapConversationForFirstMessage().await().getOrThrow()
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
     * Re-synchronizes after the app became visible again.
     *
     * A background gap is invisible to the channel itself: the Gateway keeps
     * heartbeating, so the stream stays "live" while its bounded
     * per-subscriber queue drops frames, and a drop is only ever compensated
     * by a cursor replay. Nothing on this side can tell a healthy stream from
     * a silently incomplete one, so coming back to the foreground does what a
     * break the phone did notice already does: re-open the subscription from
     * the stored cursor so the Gateway replays the gap, and re-read the
     * authoritative timeline (contract §9).
     *
     * The channel goes first: the snapshot is taken after the replacement has
     * been asked for, so it is the later of the two reads and the one that can
     * also cover a frame the handover itself dropped.
     */
    fun onForegrounded() {
        if (closed) return
        restartEventStream()
        retryTimeline()
    }

    /**
     * Re-opens the account's subscription from the stored cursor.
     *
     * Unlike a thread switch, this is not a reason to invent a second
     * subscription: the running one is handed over as the one being wound
     * down, so the account still has exactly one.
     */
    private fun restartEventStream() {
        if (closed) return
        val running = eventJob
        if (running == null || !running.isActive) {
            observeThreadEvents()
            return
        }
        observeThreadEvents(windingDown = running)
    }

    /**
     * Subscribes to the account's event stream, once.
     *
     * The stream carries every conversation of the account, so switching
     * threads has no reason to restart it. Cancelling and re-opening the
     * subscription per thread switch left the dying stream and the new one
     * overlapping — the window in which one event could be applied twice.
     *
     * [windingDown] is the subscription this call replaces, and handing one
     * over is itself the reason the guard above must not apply: it is cancelled
     * and waited out before a single event is collected, so the rule holds
     * across a replacement too. The transport closes its socket through the
     * cancelled flow's own completion handler, so the wait ends in milliseconds
     * rather than lasting as long as a stalled read — and it is bounded anyway,
     * because a cancellation the transport failed to honour must not cost the
     * phone its channel. The overlap such a timeout would leave behind is what
     * the delivery dedup already covers.
     */
    private fun observeThreadEvents(windingDown: Job? = null) {
        if (closed) return
        if (windingDown == null && eventJob?.isActive == true) return
        eventJob = scope.launch {
            windingDown?.let { replaced ->
                withTimeoutOrNull(HANDOVER_TIMEOUT_MILLIS) { replaced.cancelAndJoin() }
            }
            if (!isActive) return@launch
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
                            // A reply newer than the thread's pre-command state is
                            // the Agent's turn ending.
                            maybeArmCreationGrace(eventConvId, message)
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
                                    if (isAlreadyCoveredByConfirmedReply(message, messageText)) {
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

                        is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.ApprovalRequested -> {
                            rememberApprovalRequest(event)
                        }

                        is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.ApprovalResolved -> {
                            applyApprovalResolution(event)
                        }

                        is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.CommandResult -> {
                            if (isNewConversationCommand(event.command)) {
                                if (pendingCreation != null) {
                                    applyAgentThreadCreation(event)
                                } else if (event.outcome == com.openandroidintelligence.conversation.ports.CommandOutcome.CREATED_CONVERSATION) {
                                    // The user sent `/new` themselves; the same
                                    // authority applies, only without the wait.
                                    // They still have to be looking at the thread
                                    // it was sent from, or the jump would yank
                                    // them out of whatever they moved to.
                                    val source = event.sourceConversationId?.value
                                    // Not so for an answer we already gave up on:
                                    // its timeout told the user "stayed put", and
                                    // arriving late must not silently undo that.
                                    // The record is spent on it, because it is
                                    // the only answer the record is about — a
                                    // later, genuinely new one must still land.
                                    val abandoned = settledCreations.firstOrNull { settled ->
                                        settled.sourceThreadId == source && (
                                            settled.sourceMessageId == null ||
                                                event.sourceMessageId.isNullOrBlank() ||
                                                settled.sourceMessageId == event.sourceMessageId
                                            )
                                    }
                                    if (abandoned != null) settledCreations.remove(abandoned)
                                    if (abandoned == null && (source == null || source == activeThreadId)) {
                                        event.conversationId?.let { created ->
                                            rememberCreationReceipt(source, created.value)
                                            switchToAgentCreatedThread(created.value)
                                        }
                                    } else {
                                        // Not switching is not the same as not
                                        // knowing: the source thread still gets its
                                        // receipt, so the new conversation is
                                        // reachable from where it was asked for.
                                        event.conversationId?.let { created ->
                                            rememberCreationReceipt(source, created.value)
                                        }
                                        update { it.copy(notice = NEW_CONVERSATION_NOTICE) }
                                        refreshThreads()
                                    }
                                }
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
                maybeArmCreationGraceFromTimeline(threadId)
                update { state ->
                    if (!isActive || state.activeThreadId != threadId) state
                    else {
                        val hasStreaming = mirrored.values.any { it.sender == "assistant" && it.state == "STREAMING" }
                        state.copy(
                            timeline = if (
                                mirrored.isEmpty() &&
                                creationReceiptRow(threadId) == null &&
                                approvalRowsForActiveThread().isEmpty()
                            ) {
                                Loadable.Empty
                            } else {
                                Loadable.Ready(renderTimeline(state.pendingBatch))
                            },
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
     * Whether a late streaming chunk is already covered by a confirmed reply.
     *
     * The two frames of one reply can be minutes apart (a tool round sits between
     * them), so the turn — no user message in between — decides, not a clock.
     * The 5s tolerance only absorbs equal-or-slightly-earlier timestamps, not a
     * whole turn.
     */
    private fun isAlreadyCoveredByConfirmedReply(message: TimelineMessage, text: String): Boolean =
        mirrored.values.any { existing ->
            existing.sender == "assistant" &&
                existing.state == "CONFIRMED" &&
                ((normalizeEntryKey(existing.id).isNotBlank() && normalizeEntryKey(existing.id) == normalizeEntryKey(message.id)) ||
                    (text.isNotBlank() &&
                        !hasUserMessageBetween(existing.timestamp, message.timestamp) &&
                        existing.timestamp >= message.timestamp - 5_000L &&
                        messageTextOf(existing).startsWith(text)))
        }

    /**
     * Integrates a confirmed assistant message by:
     * 1. Removing duplicate confirmed assistant messages in [mirrored] (same normalized key or identical content within the SAME turn).
     * 2. Pruning in-flight streaming drafts that this confirmed message supersedes (same normalized key, or in the same turn where confirmed text covers streaming text).
     *
     * A turn is bounded by user messages, not by a clock: one Agent turn can
     * easily outlive any window a phone might pick (tool calls alone can take
     * minutes), and a window that expires leaves the same reply in the timeline
     * once per publish. The user message is the only boundary that means
     * something to both sides.
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
                    (!hasUserMessageBetween(v.timestamp, message.timestamp) &&
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
            val isSameTurn = !hasUserMessageBetween(s.timestamp, message.timestamp) &&
                message.timestamp >= s.timestamp - 5_000L &&
                (latestUserTimestamp == 0L || message.timestamp >= latestUserTimestamp - 10_000L)
            val isCoveredContent = (messageText.isNotBlank() && (messageText == sText || messageText.startsWith(sText))) ||
                (s.parts.isNotEmpty() && s.parts == message.parts)
            val isStaleEmpty = sText.isBlank() &&
                s.parts.none { it is com.openandroidintelligence.conversation.model.MessagePart.Attachment } &&
                message.timestamp >= s.timestamp

            if (sameNormalizedKey || (isSameTurn && (isCoveredContent || isStaleEmpty))) {
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
        val rendered = deduplicateTimelineEntries(rawList)
        // Inserted after the message rules: an approval is a request, not
        // content, so the folding and pruning above must never touch it. Each
        // card still takes the place its own timestamp earned, so it reads where
        // it happened rather than trailing the whole conversation.
        val withCards = insertApprovalRows(rendered, approvalRowsForActiveThread())
        // A receipt is navigation, not content, for the same reason.
        val receiptRow = activeThreadId?.let(::creationReceiptRow) ?: return withCards
        return withCards + receiptRow
    }

    /**
     * Places approval rows among the messages without reordering the messages.
     *
     * Sorting the whole list again would let a card change the order the message
     * rules just decided, so cards are dropped into the gap their own timestamp
     * falls in and everything else keeps its place.
     */
    private fun insertApprovalRows(
        rendered: List<TimelineEntry>,
        cards: List<TimelineEntry>,
    ): List<TimelineEntry> {
        if (cards.isEmpty()) return rendered
        val result = rendered.toMutableList()
        for (card in cards) {
            val index = result.indexOfFirst { existing ->
                existing.timestamp > 0L && existing.timestamp > card.timestamp
            }
            if (index < 0) result.add(card) else result.add(index, card)
        }
        return result
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
                    // Same group means no user message between them, so this is
                    // the same turn by construction: no clock window needed.
                    val timeDiff = c.timestamp - s.timestamp
                    val isSameTurn = timeDiff >= -5_000L
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
                    (existing.text.startsWith(s.text) || s.text.startsWith(existing.text))
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
            // Within one group there is no user message between the entries, so
            // identical content is the same reply published twice — which is what
            // a host that mints a new message id per publish produces. The turn
            // boundary, not a time window, decides whether two identical replies
            // are one message or two.
            val isDuplicate = keptConfirmed.any { existing ->
                (normalizeEntryKey(c.key).isNotBlank() && normalizeEntryKey(c.key) == normalizeEntryKey(existing.key)) ||
                ((c.text.isNotEmpty() || c.attachments.isNotEmpty()) &&
                 c.text == existing.text && c.attachments == existing.attachments)
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
        _state.update { current ->
            // The capability is a fact about the Gateway, not about a screen: it
            // is re-asserted on every update so no caller can drop it. The copy
            // only happens when the transform did not already carry that value,
            // which keeps the common path — one keystroke, one transform — free
            // of a second allocation.
            val next = transform(current)
            if (next.approvalCardsSupported == supportsApprovalCards) {
                next
            } else {
                next.copy(approvalCardsSupported = supportsApprovalCards)
            }
        }
    }

    private fun isNewConversationCommand(command: String): Boolean =
        command.trim().trimStart('/').equals(NEW_CONVERSATION_COMMAND.trimStart('/'), ignoreCase = true)

    private companion object {
        /**
         * How long a replacement subscription waits for the one it replaces.
         *
         * Cancelling closes the socket through the flow's own completion
         * handler, so the real wait is milliseconds. This bound exists only so
         * a cancellation the transport failed to honour cannot cost the phone
         * its channel; the overlap a timeout leaves behind is deduplicated at
         * the transport and again at the port.
         */
        const val HANDOVER_TIMEOUT_MILLIS = 2_000L

        /**
         * How many event ids stay remembered.
         *
         * Only a reconnect distance has to fit, and a reconnect resumes from
         * the stored cursor, so that distance is the events produced while the
         * socket was down. The cap keeps a long session bounded, and the events
         * it forgets are ones the timeline pull re-covers anyway.
         */
        const val MAX_HANDLED_EVENT_IDS = 2048

        /**
         * The reserved `/new` invocation (contract §7.1).
         *
         * It travels as ordinary text: this constant only names the boundary
         * between "the Agent has to create a thread" and "leave the text alone".
         */
        const val NEW_CONVERSATION_COMMAND = "/new"

        /**
         * The one sentence this phone uses when the Agent created a conversation.
         *
         * It is both the notice and the label of the source thread's receipt, so
         * the two can never drift into two different stories about one event.
         */
        const val NEW_CONVERSATION_NOTICE = "已创建新对话"

        /** The default wait for the Agent's answer to `/new`. */
        const val NEW_CONVERSATION_TIMEOUT_MILLIS = 60_000L

        /**
         * The default grace after the Agent answered in the source thread.
         *
         * Two frames of one turn can be seconds apart on a slow link, and the
         * user is already reading the reply by then; this is long enough for the
         * result to still win, short enough that the wait does not outlive it.
         */
        const val COMMAND_RESULT_GRACE_MILLIS = 5_000L

        /**
         * How many abandoned `/new` requests stay remembered.
         *
         * Only a late answer has to be recognised, so the distance to cover is
         * a few retries; the cap keeps a long session bounded.
         */
        const val MAX_SETTLED_CREATIONS = 8

        /**
         * How many answers may wait for the send response at once.
         *
         * One `/new` has one answer; the cap only exists so a Gateway that
         * floods the stream cannot grow the wait without bound.
         */
        const val MAX_PARKED_CREATION_RESULTS = 8

        /**
         * How many `/new` receipts stay navigable.
         *
         * A receipt is a one-tap way back to a conversation this phone created;
         * only the most recent ones are worth keeping, and the cap keeps a long
         * session bounded.
         */
        const val MAX_CREATION_RECEIPTS = 8

        /**
         * How many approval cards stay on screen.
         *
         * A settled card is part of the record of what happened, so it has to
         * survive leaving the thread; the cap only exists so a session that sees
         * many approvals cannot grow without bound.
         */
        const val MAX_APPROVAL_CARDS = 32
    }
}
