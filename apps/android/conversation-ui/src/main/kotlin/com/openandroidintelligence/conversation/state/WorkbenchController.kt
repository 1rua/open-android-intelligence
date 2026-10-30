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
import com.openandroidintelligence.conversation.model.AttachmentDraftId
import com.openandroidintelligence.conversation.model.MAX_COMPOSER_ATTACHMENTS
import com.openandroidintelligence.conversation.model.LocalSendAtom
import com.openandroidintelligence.conversation.model.LocalSubmissionState
import com.openandroidintelligence.conversation.model.mergeLocalSendAtoms
import com.openandroidintelligence.conversation.model.reconcileSnapshotDeletions
import com.openandroidintelligence.conversation.model.OriginalImageState
import com.openandroidintelligence.conversation.workbench.OriginalImagePolicy
import com.openandroidintelligence.conversation.workbench.compressOriginalImageForViewer
import com.openandroidintelligence.conversation.workbench.readOriginalBytes
import com.openandroidintelligence.gateway.auth.SessionRecoveryPausedException
import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import com.openandroidintelligence.gateway.http.EventSessionRejectedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.conversation.model.NativeExecutionStep
import com.openandroidintelligence.conversation.model.toOutcome
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.ports.AgentCommandCatalogRepository
import com.openandroidintelligence.conversation.ports.ConversationRepository
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.ports.ConversationOrganization
import com.openandroidintelligence.conversation.ports.ConversationFolder
import com.openandroidintelligence.conversation.ports.ConversationSortMode
import com.openandroidintelligence.conversation.ports.completeConversations
import com.openandroidintelligence.conversation.ports.sort
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.conversation.ports.completeTimeline
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import androidx.collection.LruCache

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
    val hasRecoverableSubmissions: Boolean = false,
    /** Unknown send outcomes require an explicit reconciliation before another send. */
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
    val canSend: Boolean get() = isOnline && historyHydrated &&
        (draft.isNotBlank() || attachments.any { it.id.value !in inFlightAttachmentIds }) &&
        !managingThreads && !activeThreadArchived &&
        composer != ComposerState.SUBMITTING && composer != ComposerState.WAITING_ATTACHMENTS
    val activeThreadArchived: Boolean get() = (threads as? Loadable.Ready)?.value?.any { it.id.value == activeThreadId && it.archived } == true
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
    scope: CoroutineScope,
    private val repository: ConversationRepository,
    private val catalogRepository: AgentCommandCatalogRepository,
    private val scopeFactory: () -> ConversationScope,
    private val attachmentCoordinator: com.openandroidintelligence.conversation.ports.AttachmentDraftCoordinator? = null,
    debouncePolicy: DebouncePolicy = DebouncePolicy(),
    private val supportsMessageBatches: Boolean = false,
    /** Legacy slash-command capability; the new-thread button uses the Gateway endpoint. */
    @Suppress("unused") private val supportsAgentCommandNew: Boolean = false,
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
    private val persistence: WorkbenchPersistence? = null,
    private val media: com.openandroidintelligence.conversation.ports.HistoricalMediaPort? = null,
    private val downloads: com.openandroidintelligence.conversation.ports.AttachmentDownloadPort? = null,
    private val mediaDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val persistenceDispatcher: CoroutineDispatcher = mediaDispatcher,
    private val allowSending: Boolean = true,
    private val languageCode: String = "zh-CN",
    private val supportsConversationManagement: Boolean = false,
    private val supportsConversationDelete: Boolean = supportsConversationManagement,
    private val supportsConversationArchive: Boolean = supportsConversationManagement,
    private val supportsConversationUnarchive: Boolean = supportsConversationArchive,
    private val supportsConversationRename: Boolean = true,
    private val organizationStoredLocally: Boolean = false,
    /** Native hosts own stable message IDs even when no client submission ID is available. */
    private val authoritativeMessageIds: Boolean = false,
    private val externalBusyThreadIds: () -> Set<String> = { emptySet() },
    private val onClosed: () -> Unit = {},
    /**
     * 原图缓存最多保留几张。
     *
     * 默认 3 张（规格 3.4）；测试注入更小的值即可验证淘汰与清零，而不必加载真图。
     */
    private val originalImageCacheSize: Int = OriginalImagePolicy.CacheEntries,
    /**
     * 原图解码 + 压缩的注入缝。
     *
     * 生产默认走真实实现：先按 bounds 判断尺寸，再用 `ImageDecoder`（应用 EXIF 方向）
     * 或 `BitmapFactory` 采样到最长边 2048px，最后压成 JPEG 85。24MB 读取上限与
     * 2048px 上限都不在这条缝里，注入实现也无法绕过。
     */
    private val originalImageCompressor: (ByteArray) -> ByteArray? =
        { bytes -> compressOriginalImageForViewer(bytes) },
) : AutoCloseable {
    private val controllerJob=SupervisorJob(scope.coroutineContext[Job])
    // 控制器协程里的未捕获异常只应结束这一条订阅，而不是带走整个进程：
    // 事件投影出错是「这一帧没应用上」，不是「App 该退出」。
    private val workScope=CoroutineScope(scope.coroutineContext+controllerJob+CoroutineExceptionHandler { _, failure ->
        GatewayLog.e("OaiWorkbench", "workbench coroutine failed: ${failure.javaClass.simpleName}: ${failure.message}")
    })

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
    private val _state = MutableStateFlow(WorkbenchUiState(approvalCardsSupported = supportsApprovalCards,
        isOnline=allowSending,
        conversationManagementSupported=supportsConversationManagement,
        conversationDeleteSupported=supportsConversationDelete,
        conversationArchiveSupported=supportsConversationArchive,
        conversationUnarchiveSupported=supportsConversationUnarchive,
        conversationRenameSupported=supportsConversationRename,
        organizationStoredLocally=organizationStoredLocally))
    val state: StateFlow<WorkbenchUiState> = _state.asStateFlow()
    private var remoteConversationOnline = true

    /** Agent presence can change without replacing this controller or losing its draft. */
    fun setRemoteConversationOnline(online: Boolean) {
        if (closed || remoteConversationOnline == online) return
        remoteConversationOnline = online
        update { it }
    }
    private val activeWork = WorkbenchActiveWork()
    /** Live sends across every conversation, including work not yet acknowledged by the Gateway. */
    val hasActiveWork: StateFlow<Boolean> = activeWork.active
    /** Excludes other surfaces so a host can combine controllers without feeding busy state back. */
    val ownedBusyThreadIds: Set<String> get() = managementBusyThreadIds(_state.value)
    /** Picker results belong to the exact composer that launched them. */
    var attachmentTargetRevision: Long = 0
        private set
    private var attachmentPreparations = 0
    val availableAttachmentSlots: Int get() =
        (MAX_COMPOSER_ATTACHMENTS - _state.value.attachments.size - attachmentPreparations).coerceAtLeast(0)

    /** Server-mirrored messages for the active thread, by message id. */
    private val submittingBatches=mutableSetOf<String>()
    private val frozenBatches=LinkedHashMap<String,SavedBatch>()
    private val collectingUnits=LinkedHashMap<String,Pair<String,OutgoingMessage>>()
    private val mirrored = LinkedHashMap<String, TimelineMessage>()
    private data class NativeExecutionFacts(
        val revision: Long,
        val occurredAt: Long,
        val steps: List<NativeExecutionStep>,
        val clientMessageId: String?,
        val userMessageId: String?,
    )
    // Native tool observations are view facts, not Assistant messages. They must
    // never enter mirrored history or its message/content revision domain.
    private val executionFacts = LinkedHashMap<Pair<String, String>, NativeExecutionFacts>()
    private val sendAtomsByThread = LinkedHashMap<String, LinkedHashMap<String, LocalSendAtom>>()
    private val sendAtomsMutex=Mutex()
    private var sendAtomsEpoch=0L
    private val snapshotDeletedIdsByThread = LinkedHashMap<String, MutableSet<String>>()

    /** 同步界面路径只读投影；未装载状态由 historyHydrated 明确表示。 */
    private fun atomsFor(threadId: String): Map<String,LocalSendAtom> = sendAtomsByThread[threadId].orEmpty()

    private fun recordExecutionFacts(event: VerifiedConversationEvent.ExecutionStepsUpdated) {
        if (event.generationId.isBlank() || event.executionRevision < 0 || event.steps.any {
                it.stepId.isBlank() || it.eventId.isBlank() || it.toolName.isBlank() || it.revision < 0
            }) return
        val key = event.conversationId.value to event.generationId
        val previous = executionFacts[key]
        if (previous != null && event.executionRevision <= previous.revision) return
        val clientId = event.clientMessageId?.takeIf { it.isNotBlank() }
        val userId = event.userMessageId?.takeIf { it.isNotBlank() }
        // A later snapshot cannot move an existing generation to a new prompt.
        if (previous?.clientMessageId != null && clientId != null && previous.clientMessageId != clientId ||
            previous?.userMessageId != null && userId != null && previous.userMessageId != userId) return
        val steps = linkedMapOf<String, NativeExecutionStep>()
        previous?.steps?.forEach { steps[it.stepId] = it }
        event.steps.forEach { incoming ->
            val old = steps[incoming.stepId]
            val completed = old?.status in setOf(NativeExecutionStatus.COMPLETED, NativeExecutionStatus.FAILED)
            if (old == null || incoming.revision > old.revision &&
                !(completed && incoming.status in setOf(NativeExecutionStatus.RUNNING, NativeExecutionStatus.UNKNOWN))) {
                steps[incoming.stepId] = incoming
            }
        }
        executionFacts[key] = NativeExecutionFacts(event.executionRevision, previous?.occurredAt ?: event.occurredAt,
            steps.values.toList(), previous?.clientMessageId ?: clientId, previous?.userMessageId ?: userId)
    }

    private fun rememberExecutionTerminal(threadId: String, generationId: String): Boolean {
        val changed = finishedGenerations.add(threadId to generationId)
        while (finishedGenerations.size > 4096) finishedGenerations.remove(finishedGenerations.first())
        return changed
    }

    private fun recoveredAtoms(atoms: List<LocalSendAtom>)=atoms.map { atom ->
        if (atom.submissionState==LocalSubmissionState.PREPARED) atom.copy(submissionState=LocalSubmissionState.OUTCOME_UNKNOWN) else atom
    }

    /** 构造器由宿主在 IO 调用；仅装载选中线程和明确保留的待恢复批次。 */
    private fun hydrateInitialSendAtoms(threadIds: Set<String>) {
        threadIds.forEach { threadId ->
            val saved=persistence?.loadSendAtoms(threadId).orEmpty()
            val recovered=recoveredAtoms(saved)
            if (recovered!=saved) persistence?.saveSendAtoms(threadId,recovered)
            val latest=if (recovered!=saved && persistence!=null) persistence.loadSendAtoms(threadId) else recovered
            sendAtomsByThread[threadId]=latest.associateByTo(linkedMapOf()) { it.clientMessageId.value }
        }
    }

    private suspend fun hydrateSendAtoms(threadId: String,refresh: Boolean=false) = sendAtomsMutex.withLock {
        if (!refresh && threadId in sendAtomsByThread) return@withLock
        while (true) {
            val epoch=sendAtomsEpoch
            val saved=loadSendAtomsOnIo(threadId)
            if (epoch!=sendAtomsEpoch) continue
            val recovered=if (threadId in sendAtomsByThread) saved else recoveredAtoms(saved)
            if (recovered!=saved) saveAtoms(threadId,recovered)
            val latest=if (recovered!=saved && persistence!=null) loadSendAtomsOnIo(threadId) else recovered
            if (epoch!=sendAtomsEpoch) continue
            currentCoroutineContext().ensureActive()
            check(!closed) { "WORKBENCH_CLOSED" }
            sendAtomsByThread[threadId]=latest.associateByTo(linkedMapOf()) { it.clientMessageId.value }
            latest.forEach(activeWork::atomChanged)
            break
        }
    }

    private suspend fun saveAtoms(threadId: String, atoms: List<LocalSendAtom>) {
        val store=persistence ?: return
        withContext(persistenceDispatcher) {
            val durable=store as? AwaitableWorkbenchPersistence
            if (durable!=null) durable.saveSendAtomsDurably(threadId,atoms) else store.saveSendAtoms(threadId,atoms)
        }
    }

    /** 媒体与发送恢复统一等待磁盘读取，不能依赖 SQLite 明文缓存永远命中。 */
    private suspend fun loadSendAtomsOnIo(threadId: String): List<LocalSendAtom> {
        val store=persistence ?: return atomsFor(threadId).values.toList()
        return withContext(persistenceDispatcher) { store.loadSendAtoms(threadId) }
    }

    private suspend fun changeSendAtom(threadId: String, clientId: String, change: (LocalSendAtom?) -> LocalSendAtom?) = sendAtomsMutex.withLock {
        // 事件可能先于 HTTP 回执落盘；更新前读取其最新修订，不能用旧缓存倒退状态。
        val existing=loadSendAtomsOnIo(threadId).associateByTo(linkedMapOf()) { it.clientMessageId.value }
        val updated=change(existing[clientId]) ?: return@withLock
        require(updated.conversationId.value == threadId && updated.clientMessageId.value == clientId) { "SEND_ATOM_SCOPE_MISMATCH" }
        existing[clientId] = updated
        saveAtoms(threadId, existing.values.toList())
        var epoch=sendAtomsEpoch
        var latest=if (persistence!=null) loadSendAtomsOnIo(threadId) else existing.values.toList()
        while (epoch!=sendAtomsEpoch) { epoch=sendAtomsEpoch; latest=loadSendAtomsOnIo(threadId) }
        currentCoroutineContext().ensureActive()
        check(!closed) { "WORKBENCH_CLOSED" }
        sendAtomsByThread[threadId]=latest.associateByTo(linkedMapOf()) { it.clientMessageId.value }
        latest.forEach(activeWork::atomChanged)
    }

    private var cancelledSendPersistenceFailure: Throwable? = null

    /** Cancellation settles durable send facts even after the UI has closed; closeAndAwait joins this write. */
    private suspend fun persistCancelledSendOutcomes(threadId: String, clientIds: Collection<String>) = withContext(NonCancellable) {
        try {
            sendAtomsMutex.withLock {
                val latest = loadSendAtomsOnIo(threadId)
                val updated = latest.map { atom ->
                    if (atom.clientMessageId.value in clientIds && !atom.isDeleted && atom.submissionState != LocalSubmissionState.ACCEPTED)
                        atom.copy(submissionState=LocalSubmissionState.OUTCOME_UNKNOWN)
                    else atom
                }
                if (updated != latest) saveAtoms(threadId,updated)
                val durable = if (persistence != null) loadSendAtomsOnIo(threadId) else updated
                sendAtomsByThread[threadId] = durable.associateByTo(linkedMapOf()) { it.clientMessageId.value }
                durable.forEach(activeWork::atomChanged)
            }
        } catch (failure: Exception) {
            if (!closed) throw failure
            val priorFailure = cancelledSendPersistenceFailure
            if (priorFailure == null) cancelledSendPersistenceFailure=failure
            else if (priorFailure !== failure) priorFailure.addSuppressed(failure)
        }
    }

    /** 原批次成员身份不变；已删除成员清空正文，整个原批次只能查询，不能重发。 */
    private fun reconcileDeletedRecoveryUnits(threadId: String) {
        fun clean(message: OutgoingMessage) = if (atomsFor(threadId)[message.clientMessageId.value]?.isDeleted == true)
            message.copy(text="",attachmentIds=emptyList(),command=null) else message
        frozenBatches.entries.filter { it.value.conversationId == threadId }.forEach { (id,batch) ->
            if (batch.messages.all { atomsFor(threadId)[it.clientMessageId.value]?.isDeleted == true }) frozenBatches.remove(id)
            else frozenBatches[id]=batch.copy(messages=batch.messages.map(::clean))
        }
        collectingUnits.entries.filter { it.value.first == threadId }.forEach { (id,unit) ->
            if (atomsFor(threadId)[id]?.isDeleted == true) collectingUnits.remove(id)
            else collectingUnits[id]=threadId to clean(unit.second)
        }
        if (failedSubmission?.let { it.conversationId == threadId && atomsFor(threadId)[it.clientMessageId]?.isDeleted == true } == true) failedSubmission=null
        if (pendingSubmission?.let { it.conversationId == threadId && atomsFor(threadId)[it.clientMessageId]?.isDeleted == true } == true) pendingSubmission=null
    }

    private suspend fun acceptSendAtom(threadId: String, clientId: String, messageId: String, batchId: String? = null, generationId: String? = null) {
        generationId?.let { rememberMessageGeneration(threadId, messageId, clientId, it) }
        activeWork.accepted(clientId, threadId, generationId)
        changeSendAtom(threadId, clientId) { old -> old?.identified(messageId, batchId ?: old.batchId) }
        identifyHistoricalAttachments(threadId, ClientMessageId(clientId), messageId)
    }

    private suspend fun applySendEvent(event: VerifiedConversationEvent) {
        when (event) {
            is VerifiedConversationEvent.ExecutionStepsUpdated -> recordExecutionFacts(event)
            is VerifiedConversationEvent.MessageStatus -> {
                event.generationId?.let { generationId ->
                    rememberMessageGeneration(event.conversationId.value, event.messageId, event.clientMessageId.value, generationId, event.revision)
                }
                activeWork.accepted(event.clientMessageId.value, event.conversationId.value, event.generationId)
                if (event.status in setOf(com.openandroidintelligence.conversation.ports.AgentMessageStatus.COMPLETED,
                        com.openandroidintelligence.conversation.ports.AgentMessageStatus.FAILED)) {
                    activeWork.settledClient(event.conversationId.value, event.clientMessageId.value)
                    event.generationId?.let {
                        activeWork.terminal(event.conversationId.value, it)
                        rememberExecutionTerminal(event.conversationId.value, it)
                    }
                }
            }
            is VerifiedConversationEvent.GenerationCancelled -> event.conversationId?.value?.let {
                rememberCancelledGeneration(it, event.generationId)
                activeWork.terminal(it, event.generationId)
            }
            is VerifiedConversationEvent.PendingDeliveryUpdated -> Unit
            else -> Unit
        }
        val threadId=when (event) {
            is VerifiedConversationEvent.MessageAccepted -> event.conversationId?.value
            is VerifiedConversationEvent.MessageStatus -> event.conversationId.value
            is VerifiedConversationEvent.ExecutionStepsUpdated -> event.conversationId.value
            is VerifiedConversationEvent.TimelineUpsert -> event.message.conversationId?.value
            is VerifiedConversationEvent.TimelineTombstoned -> event.conversationId?.value
            is VerifiedConversationEvent.PendingDeliveryUpdated -> event.delivery.conversationId.value
            else -> null
        }
        threadId?.let { hydrateSendAtoms(it) }
        applyHistoricalAttachmentEvent(event)
        when (event) {
            is VerifiedConversationEvent.MessageAccepted -> event.conversationId?.value?.let { target ->
                // A receipt can only settle the exact recorded local submission.
                // Invalid/foreign correlation must not terminate the event consumer.
                if (event.correlationId.isBlank() || event.messageId.isBlank() ||
                    atomsFor(target)[event.correlationId] == null) return@let
                acceptSendAtom(target, event.correlationId, event.messageId)
                changeSendAtom(target, event.correlationId) { old -> old?.let { atom ->
                    if (event.agentReceived && atom.status == com.openandroidintelligence.conversation.ports.AgentMessageStatus.QUEUED)
                        atom.copy(status = com.openandroidintelligence.conversation.ports.AgentMessageStatus.DELIVERED)
                    else atom
                } }
                nativePendingSubmissions.remove(event.correlationId)?.let { (threadId, attachments) ->
                    attachments.forEach { releaseSubmittedAttachment(threadId, it) }
                    if (failedSubmission?.clientMessageId == event.correlationId) failedSubmission = null
                }
            }
            is VerifiedConversationEvent.MessageStatus -> changeSendAtom(event.conversationId.value, event.clientMessageId.value) { old ->
                old?.let { atom ->
                    if (event.revision <= atom.statusRevision) atom else {
                        // 宿主可能用另一个 id 报告同一条 prompt 的投递状态：只推进状态，
                        // 不改写已确定的本地身份（改写会把时间线里同一行拆成两条）。
                        val provisional = atom.messageId == null || atom.messageId == atom.clientMessageId.value
                        if (!provisional && atom.messageId != event.messageId) {
                            GatewayLog.w("OaiWorkbench",
                                "status identity diverged atom=${atom.messageId} event=${event.messageId} client=${atom.clientMessageId.value}")
                        }
                        atom.identified(event.messageId).copy(
                            status = event.status, statusRevision = event.revision, errorCode = event.errorCode)
                    }
                }
            }
            is VerifiedConversationEvent.TimelineUpsert -> event.message.conversationId?.value?.let { threadId ->
                if (event.message.isDeleted) snapshotDeletedIdsByThread.getOrPut(threadId) { linkedSetOf() }.add(event.message.id)
                val atom = loadSendAtomsOnIo(threadId).firstOrNull {
                    it.messageId == event.message.id || (event.message.clientMessageId != null && it.clientMessageId == event.message.clientMessageId)
                }
                atom?.let { known -> changeSendAtom(threadId, known.clientMessageId.value) { old ->
                    old?.let {
                        if (event.message.isDeleted) it.identified(event.message.id).copy(parts=emptyList(),snapshotDeleted=true)
                        else if (it.snapshotDeleted || event.revision <= it.contentRevision || it.tombstoneRevision?.let { revision -> event.revision <= revision } == true) it
                        else it.identified(event.message.id).copy(parts = event.message.parts,
                            contentRevision = event.revision, tombstoneRevision = null)
                    }
                } }
            }
            is VerifiedConversationEvent.TimelineTombstoned -> event.conversationId?.value?.let { threadId ->
                val atom = loadSendAtomsOnIo(threadId).firstOrNull { it.messageId == event.messageId }
                atom?.let { known -> changeSendAtom(threadId, known.clientMessageId.value) { old ->
                    old?.let {
                        if (event.revision < it.contentRevision || event.revision <= (it.tombstoneRevision ?: -1L)) it
                        else it.copy(parts = emptyList(), tombstoneRevision = event.revision)
                    }
                } }
            }
            is VerifiedConversationEvent.PendingDeliveryUpdated -> {
                val delivery = event.delivery
                changeSendAtom(delivery.conversationId.value, delivery.clientMessageId.value) { old ->
                    old?.copy(
                        submissionState = when (delivery.state) {
                            com.openandroidintelligence.conversation.ports.PendingDeliveryState.QUEUED ->
                                LocalSubmissionState.QUEUED
                            com.openandroidintelligence.conversation.ports.PendingDeliveryState.SENDING ->
                                LocalSubmissionState.QUEUED
                            com.openandroidintelligence.conversation.ports.PendingDeliveryState.FAILED ->
                                LocalSubmissionState.FAILED
                            com.openandroidintelligence.conversation.ports.PendingDeliveryState.OUTCOME_UNKNOWN ->
                                LocalSubmissionState.OUTCOME_UNKNOWN
                        },
                        submissionErrorCode = delivery.errorCode,
                    )
                }
            }
            else -> Unit
        }
    }

    private fun generationAfterAcceptance(threadId: String, clientIds: List<String>, current: GenerationState): GenerationState {
        val statuses = clientIds.map { atomsFor(threadId)[it]?.status }
        return when {
            current in setOf(GenerationState.COMPLETED, GenerationState.CANCELLED, GenerationState.FAILED) -> current
            statuses.isNotEmpty() && statuses.all { it == com.openandroidintelligence.conversation.ports.AgentMessageStatus.COMPLETED } -> GenerationState.COMPLETED
            statuses.any { it == com.openandroidintelligence.conversation.ports.AgentMessageStatus.FAILED } -> GenerationState.FAILED
            statuses.any { it == com.openandroidintelligence.conversation.ports.AgentMessageStatus.DELIVERED } || current == GenerationState.RUNNING -> GenerationState.RUNNING
            else -> GenerationState.QUEUED
        }
    }

    /** A failed follow-up only ends its own submission; an older run can remain active. */
    private fun generationAfterSubmissionFailure(current: GenerationState, outcomeUnknown: Boolean): GenerationState {
        val tracked = (repository as? com.openandroidintelligence.conversation.ports.GenerationTracker)
            ?.generationState?.value
        return when {
            tracked != null && tracked in setOf(GenerationState.COMPLETED, GenerationState.CANCELLED, GenerationState.FAILED) -> tracked
            current in setOf(GenerationState.COMPLETED, GenerationState.CANCELLED, GenerationState.FAILED) -> current
            tracked == GenerationState.CANCEL_REQUESTED || current == GenerationState.CANCEL_REQUESTED -> GenerationState.CANCEL_REQUESTED
            tracked == GenerationState.RUNNING || current == GenerationState.RUNNING -> GenerationState.RUNNING
            outcomeUnknown -> GenerationState.OUTCOME_UNKNOWN
            else -> GenerationState.IDLE
        }
    }
    private val mirroredRevisions = LinkedHashMap<String, Long>()
    private data class MessageStatusReceipt(
        val status: com.openandroidintelligence.conversation.ports.AgentMessageStatus,
        val revision: Long,
        val errorCode: com.openandroidintelligence.conversation.ports.AgentMessageErrorCode?,
    )
    private val messageStatusRevisions = LinkedHashMap<String, Long>()
    private val messageStatusesById = LinkedHashMap<String, MessageStatusReceipt>()
    private val messageStatusesByClientId = LinkedHashMap<String, MessageStatusReceipt>()

    /**
     * 展示行缓存：不含附件的消息行只依赖消息本身与取消标记，可按消息实例复用；
     * 带附件的行依赖多张外部展示状态，任何一张变化都要重建，因此不缓存。
     */
    private data class RenderedTimelineEntry(
        val message: com.openandroidintelligence.conversation.ports.TimelineMessage,
        val cancelled: Boolean,
        val entry: TimelineEntry,
    )
    private val renderedEntryCache = LinkedHashMap<String, RenderedTimelineEntry>()
    private val renderedEntryCacheLimit = 512

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
    private val committingEventIds = LinkedHashSet<String>()

    private val attachmentJobs = LinkedHashMap<String, Job>()
    private val attachmentPreviews = LinkedHashMap<String, ByteArray>()
    private data class HistoricalAttachmentKey(val scope: ConversationScope, val threadId: String, val attachmentId: String)
    private data class HistoricalAttachmentPreview(
        val attachment: com.openandroidintelligence.conversation.model.TimelineAttachment,
        val clientMessageId: ClientMessageId? = null,
        val messageId: String? = null,
        val contentRevision: Long = -1L,
    )
    private data class MediaPresentation(
        val localState: com.openandroidintelligence.conversation.model.AttachmentLocalState = com.openandroidintelligence.conversation.model.AttachmentLocalState.PENDING,
        val localError: String? = null,
        val downloadState: com.openandroidintelligence.conversation.model.AttachmentDownloadState = com.openandroidintelligence.conversation.model.AttachmentDownloadState.IDLE,
        val downloadError: String? = null,
        /** 原图是独立于缩略图的一次按需加载，状态也必须独立记录。 */
        val originalState: OriginalImageState = OriginalImageState.IDLE,
        val originalError: String? = null,
    )
    private val mediaPresentation = LinkedHashMap<HistoricalAttachmentKey, MediaPresentation>()
    private val mediaOwners = LinkedHashMap<HistoricalAttachmentKey, String>()
    private val mediaJobs = LinkedHashMap<HistoricalAttachmentKey, Job>()
    private val downloadJobs = LinkedHashMap<HistoricalAttachmentKey, Job>()
    private val originalJobs = LinkedHashMap<HistoricalAttachmentKey, Job>()
    private val localArchiveJobs = LinkedHashMap<String, Job>()
    private val deletedMedia = linkedSetOf<HistoricalAttachmentKey>()
    private data class MediaMessageKey(val scope: ConversationScope, val threadId: String, val messageId: String)
    private data class MediaDeletion(val revision: Long?)
    private val mediaDeletions = LinkedHashMap<MediaMessageKey, MediaDeletion>()
    private val mediaContentRevisions = LinkedHashMap<MediaMessageKey, Long>()
    private val mediaEpochs = LinkedHashMap<HistoricalAttachmentKey, Long>()
    private val preSubmittedMedia=linkedSetOf<HistoricalAttachmentKey>()
    private val mediaParallelism = Semaphore(2)
    /** 只保存有限的进程内缩略图；会话切换不能丢图，也不能借用其他账号或会话的图片。 */
    private val historicalAttachments = object : LruCache<HistoricalAttachmentKey, HistoricalAttachmentPreview>(MAX_HISTORY_ATTACHMENT_PREVIEWS) {
        override fun entryRemoved(evicted: Boolean, key: HistoricalAttachmentKey, oldValue: HistoricalAttachmentPreview, newValue: HistoricalAttachmentPreview?) {
            oldValue.attachment.previewBytes?.takeIf { it !== newValue?.attachment?.previewBytes }?.fill(0)
        }
    }
    /**
     * 已加载原图的最近使用缓存。
     *
     * 与缩略图缓存分开：缩略图预算按「30 条消息 × 256KB」计算，而一张按展示尺寸
     * 压缩后的原图仍有数百 KB，两者不是同一个量级。只在打开大图预览时填充，最多
     * 保留 [originalImageCacheSize] 张；淘汰和关闭时把字节清零，不把照片留在堆里。
     */
    private val originalImages = object : LruCache<HistoricalAttachmentKey, ByteArray>(originalImageCacheSize) {
        override fun entryRemoved(evicted: Boolean, key: HistoricalAttachmentKey, oldValue: ByteArray, newValue: ByteArray?) {
            if (oldValue !== newValue) oldValue.fill(0)
        }
    }

    private fun historicalAttachmentKey(threadId: String, attachmentId: String) =
        HistoricalAttachmentKey(scopeFactory(), threadId, attachmentId)

    private fun rememberHistoricalAttachment(threadId: String, attachment: com.openandroidintelligence.conversation.model.TimelineAttachment,
        clientMessageId: ClientMessageId? = null, messageId: String? = null) {
        val key = historicalAttachmentKey(threadId, attachment.draftId)
        val previous = historicalAttachments[key]
        // 与选图入口的缩略图边界一致；超出预算仅省略预览，不影响上传的文件字节。
        val preview = attachment.previewBytes?.takeIf { it.size <= MAX_HISTORY_PREVIEW_BYTES }?.copyOf()
        historicalAttachments.put(key, HistoricalAttachmentPreview(attachment.copy(previewBytes = preview),
            clientMessageId ?: previous?.clientMessageId, messageId ?: previous?.messageId, previous?.contentRevision ?: -1L))
    }

    private fun identifyHistoricalAttachments(threadId: String, clientMessageId: ClientMessageId, messageId: String) {
        val account = scopeFactory()
        val atom = atomsFor(threadId).values.firstOrNull {
            it.clientMessageId == clientMessageId && (it.messageId == null || it.messageId == messageId)
        }
        historicalAttachments.snapshot().forEach { (key, value) ->
            if (key.scope == account && key.threadId == threadId && value.clientMessageId == clientMessageId &&
                (value.messageId == null || value.messageId == messageId)) {
                val contentRevision = maxOf(value.contentRevision, atom?.contentRevision ?: -1L)
                mediaOwners[key] = messageId
                // 删除可能先于回执落盘；补齐远端身份时同样遵守持久发送记录的删除屏障。
                if (atom?.isDeleted == true) { historicalAttachments.remove(key); cancelMessageMedia(threadId,messageId,
                    atom.tombstoneRevision.takeUnless { atom.snapshotDeleted }) }
                else historicalAttachments.put(key, value.copy(messageId = messageId, contentRevision = contentRevision))
            }
        }
    }

    /** 删除事件也可能属于当前未显示的会话，必须按消息身份及时释放缩略图。 */
    private suspend fun applyHistoricalAttachmentEvent(event: VerifiedConversationEvent) {
        when (event) {
            is VerifiedConversationEvent.MessageStatus ->
                identifyHistoricalAttachments(event.conversationId.value, event.clientMessageId, event.messageId)
            is VerifiedConversationEvent.TimelineUpsert -> event.message.conversationId?.value?.let { threadId ->
                if (event.message.isDeleted) cancelMessageMedia(threadId,event.message.id)
                else {
                    val ids=event.message.parts.filterIsInstance<com.openandroidintelligence.conversation.model.MessagePart.Attachment>().map { it.draftId.value }
                    if (ids.isNotEmpty() && media != null) withContext(mediaDispatcher) {
                        media.applyTransition(com.openandroidintelligence.conversation.ports.HistoricalMediaTransition(threadId,event.message.id,event.revision,ids,false))
                    }
                    scheduleHistoricalMedia(threadId,listOf(event.message.copy(contentRevision=event.revision)))
                }
                val account = scopeFactory()
                val attachments = event.message.parts.filterIsInstance<com.openandroidintelligence.conversation.model.MessagePart.Attachment>()
                    .map { it.draftId.value }.toSet()
                historicalAttachments.snapshot().forEach { (key, value) ->
                    if (key.scope == account && key.threadId == threadId &&
                        (value.messageId == event.message.id || (value.messageId == null && value.clientMessageId != null && value.clientMessageId == event.message.clientMessageId)) &&
                        (event.message.isDeleted || event.revision > value.contentRevision)) {
                        if (key.attachmentId in attachments) historicalAttachments.put(key, value.copy(messageId = event.message.id, contentRevision = event.revision))
                        else historicalAttachments.remove(key)
                    }
                }
            }
            is VerifiedConversationEvent.TimelineTombstoned -> event.conversationId?.value?.let { threadId ->
                val ids=mediaOwners.filter { it.key.threadId == threadId && it.value == event.messageId }.keys.map { it.attachmentId }
                cancelMessageMedia(threadId,event.messageId,event.revision)
                if (ids.isNotEmpty() && media != null) withContext(mediaDispatcher) {
                    media.applyTransition(com.openandroidintelligence.conversation.ports.HistoricalMediaTransition(threadId,event.messageId,event.revision,ids,true))
                }
                val account = scopeFactory()
                historicalAttachments.snapshot().forEach { (key, value) ->
                    if (key.scope == account && key.threadId == threadId && value.messageId == event.messageId && event.revision >= value.contentRevision) {
                        historicalAttachments.remove(key)
                    }
                }
            }
            else -> Unit
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
    private var generationJob:Job?=null
    private var checkpointJob: Job? = null
    private var pendingCheckpoint: WorkbenchCheckpoint? = null
    private var checkpointFailure: Throwable? = null
    private var closingJobs: List<Job> = emptyList()
    private val persistenceLifecycleMutex = Mutex()
    private var persistenceRetired = false
    private var retirementFailure: Throwable? = null
    /** Watchdog for one accepted turn: the difference between "thinking" and "broken". */
    private var replyWatchdog: Job? = null
    private data class ReplyWatchdogTarget(
        val conversationId: String,
        val clientMessageIds: Set<String>,
        val generationId: String?,
    )
    private var awaitingReply: ReplyWatchdogTarget? = null
    private var activeThreadId: String? = null
    private var threadRequestEpoch = 0L
    private var organizationMutationEpoch = 0L
    private var threadsSnapshotRevision: Long? = null
    private var managementJob: Job? = null
    /** Once acknowledged deleted, replayed frames may not reopen this identity. */
    private val deletedThreads = mutableSetOf<String>()

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

    /** Shares a Gateway-created conversation between simultaneous first-message sends. */
    private var bootstrapJob: Deferred<Result<String>>? = null
    private data class ConversationCreationAttempt(val scope: ConversationScope, val clientId: String, val folderId: String?)
    private var unconfirmedConversationCreation: ConversationCreationAttempt? = null
    private val pendingFolderAssignments = LinkedHashMap<String, String>()
    private var folderAssignmentJob: Job? = null
    private var folderAssignmentScope: ConversationScope? = null
    private suspend fun createGatewayConversation(folderId: String? = _state.value.selectedFolderId): com.openandroidintelligence.conversation.ports.Conversation {
        val requestedScope = scopeFactory()
        val recoveringCreation = unconfirmedConversationCreation?.scope == requestedScope
        val attempt = unconfirmedConversationCreation?.takeIf { it.scope == requestedScope }
            ?: ConversationCreationAttempt(requestedScope, "cconv_" + UUID.randomUUID().toString().replace("-", ""),
                folderId.takeIf { supportsConversationManagement })
                .also { unconfirmedConversationCreation = it }
        // Retrying an uncertain creation, including after restart, uses its original identity.
        flushPendingPersistence()
        val conversation = if (recoveringCreation && repository is com.openandroidintelligence.conversation.ports.ConversationCreationRecovery)
            repository.recoverConversationCreation(attempt.scope, attempt.clientId)
        else repository.createConversation(attempt.scope, attempt.clientId)
        if (unconfirmedConversationCreation === attempt) unconfirmedConversationCreation = null
        if (attempt.folderId != null) {
            pendingFolderAssignments[conversation.id.value] = attempt.folderId
            folderAssignmentScope = attempt.scope
        }
        // The create receipt is final even if the separate organization request fails.
        update { state -> state.copy(threads=Loadable.Ready(state.organization.sort(
            (state.threads as? Loadable.Ready)?.value.orEmpty().filterNot { it.id == conversation.id } +
                ConversationSummary(conversation.id, conversation.title, conversation.createdAt)))) }
        try { flushPendingPersistence() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { update { it.copy(notice="LOCAL_SAVE_FAILED:${errorCodeOf(failure)}") } }
        retryFolderAssignments()
        return conversation
    }

    /** Each refresh retries only classification, never the already-confirmed create request. */
    private fun retryFolderAssignments() {
        if (closed || !allowSending || !supportsConversationManagement || pendingFolderAssignments.isEmpty() || folderAssignmentJob?.isActive == true) return
        val requestedScope = folderAssignmentScope ?: return
        if (requestedScope != scopeFactory()) return
        folderAssignmentJob = workScope.launch {
            var changed = false
            val attempted = mutableSetOf<String>()
            while (true) {
                val (threadId, folderId) = pendingFolderAssignments.entries.firstOrNull { it.key !in attempted } ?: break
                attempted.add(threadId)
                try {
                    organizationMutationEpoch++
                    threadRequestEpoch++
                    val committed = commitOrganizationChange(requestedScope) { organization ->
                        // A manual move, deletion or a lost successful response needs no second write.
                        if (pendingFolderAssignments[threadId] != folderId || threadId in deletedThreads ||
                            organization.folders.none { it.folderId == folderId } || organization.assignments[threadId] != null) organization
                        else organization.copy(assignments=organization.assignments + (threadId to folderId))
                    }
                    if (!committed) return@launch
                    if (pendingFolderAssignments[threadId] == folderId) pendingFolderAssignments.remove(threadId)
                    changed = true
                    update { it }
                    flushPendingPersistence()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    update { it.copy(notice="CONVERSATION_MANAGEMENT_FAILED:${errorCodeOf(failure)}") }
                }
            }
            if (changed) refreshThreads()
        }
    }

    private fun creationBlocksActiveThread(): Boolean =
        (_state.value.creatingThread && _state.value.creationSourceThreadId == activeThreadId) ||
            pendingCreation?.let { it.sourceThreadId == activeThreadId } == true

    private var draftRevision = 0L
    private val finishedGenerations = LinkedHashSet<Pair<String,String>>()
    /** Gateway message status and confirmed cancellation are independent durable facts. */
    private val cancelledGenerations = LinkedHashSet<Pair<String, String>>()
    private class PendingCancellation(val factRevision: Long) {
        var terminalReceipt: VerifiedConversationEvent.MessageStatus? = null
        var trackerTerminal: GenerationState? = null
    }
    private val requestedCancellations = LinkedHashMap<Pair<String, String>, PendingCancellation>()
    private data class MessageGeneration(val id: String, val revision: Long)
    private val messageGenerations = LinkedHashMap<Pair<String, String>, MessageGeneration>()

    private fun rememberMessageGeneration(threadId: String, messageId: String, clientId: String, generationId: String, revision: Long = -1L) {
        listOf(messageId, clientId).forEach { id ->
            val key = threadId to id
            if (revision > (messageGenerations[key]?.revision ?: Long.MIN_VALUE)) {
                messageGenerations[key] = MessageGeneration(generationId, revision)
            }
        }
        while (messageGenerations.size > 8192) messageGenerations.remove(messageGenerations.keys.first())
    }

    private fun rememberCancelledGeneration(threadId: String, generationId: String) {
        cancelledGenerations.add(threadId to generationId)
        finishedGenerations.add(threadId to generationId)
        while (finishedGenerations.size > 4096) finishedGenerations.remove(finishedGenerations.first())
        while (cancelledGenerations.size > 4096) cancelledGenerations.remove(cancelledGenerations.first())
    }

    private fun isCancelledMessage(threadId: String?, generationId: String?, messageId: String, clientId: String? = null,
        confirmedOnly: Boolean = false): Boolean {
        if (threadId == null) return false
        val knownGeneration = generationId ?: messageGenerations[threadId to messageId]?.id
            ?: clientId?.let { messageGenerations[threadId to it]?.id }
        return knownGeneration != null && (threadId to knownGeneration in cancelledGenerations ||
            (!confirmedOnly && threadId to knownGeneration in requestedCancellations))
    }
    private val legacyTerminalBarriers = LinkedHashMap<String,Long>()
    private val retiredLegacyMessageIds = LinkedHashSet<Pair<String,String>>()
    private var generationFactRevision = 0L
    private var lastObservedGenerationId: String? = null
    private fun observeGenerationIdentity(generationId: String?) {
        if (generationId != null && generationId != lastObservedGenerationId) {
            lastObservedGenerationId = generationId
            generationFactRevision++
        }
    }
    private class VoiceAttempt(var conversationId: String? = null, var requestStarted: Boolean = false)
    private data class DraftSubmission(
        val text: String,
        val attachmentIds: List<String>,
        val revision: Long,
        val conversationId: String?,
        val clientMessageId: String = "cm_" + UUID.randomUUID().toString(),
        val isolatedThreadEpoch: Long? = null,
        val voiceAttempt: VoiceAttempt? = null,
        val delivery: com.openandroidintelligence.conversation.ports.MessageDeliveryMode =
            com.openandroidintelligence.conversation.ports.MessageDeliveryMode.NORMAL,
        val optimisticEntry: TimelineEntry? = null,
        val generationBeforeSubmit: GenerationState? = null,
    )
    private var pendingSubmission: DraftSubmission? = null
    private var failedSubmission: DraftSubmission? = null
    private val nativePendingSubmissions = mutableMapOf<String, Pair<String, List<String>>>()
    private val userRenamedThreads = mutableSetOf<String>()
    private val approvalCards = LinkedHashMap<String, ApprovalCardState>()
    private val approvalFallbacks = LinkedHashMap<String, ApprovalCardState.TextOnly>()
    private val pendingDeliveryDrainJobs = LinkedHashMap<String, Job>()

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
        if (closed || newTitle.isBlank()) return
        if (!remoteConversationOnline) {
            update { it.copy(notice="AGENT_OFFLINE") }
            return
        }
        if (!supportsConversationRename || !allowSending) {
            update { it.copy(notice="CONVERSATION_RENAME_UNSUPPORTED") }
            return
        }
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
        workScope.launch {
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

    private fun beginManagement(threadId: String? = null, operation: suspend () -> Unit) {
        if (closed) return
        if (!supportsConversationManagement || !allowSending) {
            update { it.copy(notice="CONVERSATION_MANAGEMENT_UNSUPPORTED") }
            return
        }
        if (_state.value.managingThreads) return
        if (threadId != null && threadId in (ownedBusyThreadIds + externalBusyThreadIds())) {
            update { it.copy(notice="CONVERSATION_BUSY:请等待当前发送和回复结束") }
            return
        }
        organizationMutationEpoch++
        threadRequestEpoch++ // A list requested before this commit cannot restore old metadata.
        update { it.copy(managingThreads=true, notice=null) }
        managementJob=workScope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                update { it.copy(notice="CONVERSATION_MANAGEMENT_FAILED:${errorCodeOf(failure)}") }
            } finally {
                update { it.copy(managingThreads=false) }
                refreshThreads()
            }
        }
    }

    fun archiveThread(threadId: String, archived: Boolean = true) = beginManagement(threadId) {
        check(remoteConversationOnline) { "AGENT_OFFLINE" }
        check(supportsConversationArchive && (archived || supportsConversationUnarchive)) { "CONVERSATION_ARCHIVE_UNSUPPORTED" }
        repository.setArchived(threadId, archived)
        update { state -> state.copy(threads=(state.threads as? Loadable.Ready)?.let { list ->
            Loadable.Ready(list.value.map { if (it.id.value == threadId) it.copy(archived=archived) else it })
        } ?: state.threads) }
    }

    fun deleteThread(threadId: String) = beginManagement(threadId) {
        check(remoteConversationOnline) { "AGENT_OFFLINE" }
        check(supportsConversationDelete) { "CONVERSATION_DELETE_UNSUPPORTED" }
        repository.deleteConversation(threadId)
        deletedThreads.add(threadId)
        sendAtomsEpoch++
        sendAtomsByThread.remove(threadId)
        snapshotDeletedIdsByThread.remove(threadId)
        userRenamedThreads.remove(threadId)
        creationReceipts.remove(threadId)
        executionFacts.keys.removeAll { it.first == threadId }
        approvalCards.entries.removeAll { it.value.request.conversationId?.value == threadId }
        historicalAttachments.snapshot().keys.filter { it.threadId == threadId }.forEach { historicalAttachments.remove(it) }
        val organization = _state.value.organization
        update { state ->
            val remaining=(state.threads as? Loadable.Ready)?.value.orEmpty().filterNot { it.id.value == threadId }
            state.copy(threads=if (remaining.isEmpty()) Loadable.Empty else Loadable.Ready(remaining),
                organization=organization.copy(customOrder=organization.customOrder.filterNot { it == threadId }, assignments=organization.assignments-threadId))
        }
        if (activeThreadId == threadId) leaveManagedThread(threadId)
    }

    /** Completed destructive operations release timeline jobs and the host's active event scope. */
    private fun leaveManagedThread(threadId: String) {
        if (activeThreadId != threadId) return
        attachmentTargetRevision++
        cancelPendingSubmission()
        threadEpoch++
        timelineJob?.cancel()
        disarmReplyWatchdog()
        activeThreadId=null
        onActiveThreadChanged(null)
        mirrored.clear(); mirroredRevisions.clear(); messageStatusRevisions.clear()
        renderedEntryCache.clear()
        messageStatusesById.clear(); messageStatusesByClientId.clear()
        failedSubmission=failedSubmission?.takeUnless { it.conversationId == threadId }
        update { it.copy(activeThreadId=null, activeThreadTitle="", timeline=Loadable.Empty, pendingBatch=emptyList(),
            generation=GenerationState.IDLE, composer=ComposerState.EDITING, historyHydrated=true) }
        if (_state.value.draft.isBlank() && _state.value.attachments.isEmpty()) {
            (_state.value.threads as? Loadable.Ready)?.value?.firstOrNull { !it.archived && it.id.value !in deletedThreads }?.let { openThread(it.id.value) }
        }
    }

    private fun changeOrganization(change: (ConversationOrganization) -> ConversationOrganization) {
        if (closed) return
        val requestedScope=scopeFactory()
        beginManagement {
            commitOrganizationChange(requestedScope, change)
        }
    }

    private suspend fun commitOrganizationChange(
        requestedScope: ConversationScope,
        change: (ConversationOrganization) -> ConversationOrganization,
    ): Boolean {
        repeat(MAX_ORGANIZATION_UPDATE_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            if (closed || requestedScope != scopeFactory()) return false
            // Reapply the same action to each fresh CAS base so another surface's
            // unrelated edits and the user's original action are both retained.
            val current=repository.readOrganization()
            currentCoroutineContext().ensureActive()
            if (closed || requestedScope != scopeFactory()) return false
            val next=change(current)
            if (next == current) { installOrganization(current); return true }
            try {
                val committed=repository.updateOrganization(current.revision,next)
                currentCoroutineContext().ensureActive()
                if (closed || requestedScope != scopeFactory()) return false
                installOrganization(committed)
                return true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (closed || requestedScope != scopeFactory()) return false
                // Only a confirmed CAS conflict permits another write. A lost
                // response may already have committed and must remain visible.
                if (attempt == MAX_ORGANIZATION_UPDATE_ATTEMPTS-1 ||
                    errorCodeOf(failure).substringAfterLast(':') != "ORGANIZATION_CONFLICT") throw failure
            }
        }
        return false
    }

    private fun installOrganization(organization: ConversationOrganization) {
        if (organization.revision < _state.value.organization.revision) return
        update { state -> state.copy(organization=organization,
            selectedFolderId=state.selectedFolderId?.takeIf { id -> organization.folders.any { it.folderId == id } },
            threads=(state.threads as? Loadable.Ready)?.let {
            Loadable.Ready(organization.sort(it.value))
        } ?: state.threads) }
    }

    fun selectFolder(folderId: String?) {
        update { state -> state.copy(selectedFolderId = folderId?.takeIf { id ->
            state.organization.folders.any { it.folderId == id }
        }) }
    }

    fun createFolder(name: String) {
        val clean=name.trim()
        if (clean.isBlank()) return
        val id="folder_"+UUID.randomUUID().toString()
        changeOrganization { it.copy(folders=it.folders+ConversationFolder(id,clean)) }
    }
    fun renameFolder(folderId: String, name: String) {
        val clean=name.trim()
        if (clean.isBlank()) return
        changeOrganization { state ->
            check(state.folders.any { it.folderId == folderId }) { "FOLDER_NOT_FOUND" }
            state.copy(folders=state.folders.map { if (it.folderId == folderId) it.copy(name=clean) else it })
        }
    }
    fun deleteFolder(folderId: String) = changeOrganization { state ->
        state.copy(folders=state.folders.filterNot { it.folderId == folderId }, assignments=state.assignments.filterValues { it != folderId })
    }
    fun assignThreadToFolder(threadId: String, folderId: String?) {
        pendingFolderAssignments.remove(threadId)
        changeOrganization { state ->
            check(folderId == null || state.folders.any { it.folderId == folderId }) { "FOLDER_NOT_FOUND" }
            state.copy(assignments=if (folderId == null) state.assignments-threadId else state.assignments+(threadId to folderId))
        }
    }
    fun setThreadSortMode(mode: ConversationSortMode) = changeOrganization { it.copy(sortMode=mode) }
    fun reorderThreads(orderedIds: List<String>) = changeOrganization { state ->
        check(orderedIds.size == orderedIds.toSet().size) { "CONVERSATION_ORDER_INVALID" }
        // Reordering a filtered folder retains every other conversation's relative order.
        val supplied=orderedIds.toSet()
        val visible=state.sort((_state.value.threads as? Loadable.Ready)?.value.orEmpty()).map { it.id.value }
        val current=(if (state.sortMode == ConversationSortMode.CUSTOM) state.customOrder+visible else visible+state.customOrder).distinct()
        val iterator=orderedIds.iterator()
        val merged=current.map { id -> if (id in supplied && iterator.hasNext()) iterator.next() else id }.toMutableList()
        while (iterator.hasNext()) merged.add(iterator.next())
        state.copy(sortMode=ConversationSortMode.CUSTOM, customOrder=merged.distinct())
    }

    private fun managementBusyThreadIds(state: WorkbenchUiState): Set<String> = buildSet {
        addAll(activeWork.conversationIds())
        pendingSubmission?.conversationId?.let(::add)
        frozenBatches.values.forEach { add(it.conversationId) }
        collectingUnits.values.forEach { add(it.first) }
        pendingCreation?.sourceThreadId?.let(::add)
        if (state.generation in setOf(GenerationState.QUEUED,GenerationState.RUNNING,GenerationState.CANCEL_REQUESTED,GenerationState.OUTCOME_UNKNOWN)) {
            state.activeThreadId?.let(::add)
        }
    }

    override fun close() {
        if (closed) return
        closingJobs=controllerJob.children.toList()
        closed = true
        activeWork.clear()
        _state.update { it.copy(isOnline=false) }
        controllerJob.cancel()
        timelineJob?.cancel()
        attachmentJobs.values.forEach { it.cancel() }
        attachmentJobs.clear()
        attachmentPreviews.clear()
        historicalAttachments.evictAll()
        mediaJobs.values.forEach { it.cancel() }; mediaJobs.clear()
        downloadJobs.values.forEach { it.cancel() }; downloadJobs.clear()
        originalJobs.values.forEach { it.cancel() }; originalJobs.clear()
        originalImages.evictAll()
        localArchiveJobs.values.forEach { it.cancel() }; localArchiveJobs.clear()
        bootstrapJob?.cancel()
        creationSendJob?.cancel()
        creationWatchdog?.cancel()
        creationGraceJob?.cancel()
        creationGraceJob = null
        pendingCreation = null
        settledCreations.clear()
        approvalCards.clear()
        approvalFallbacks.clear()
        eventJob?.cancel()
        healthJob?.cancel()
        generationJob?.cancel()
        disarmReplyWatchdog()
        batcher.close()
        onClosed()
    }

    /** 正常账号切换先提交，再等所有旧 IO 收尾；即时认证撤销仍使用同步 close。 */
    suspend fun closeAndAwait() = persistenceLifecycleMutex.withLock {
        currentCoroutineContext().ensureActive()
        if (persistenceRetired) {
            retirementFailure?.let { throw it }
            return@withLock
        }
        val caller=currentCoroutineContext()[Job]
        var failure: Throwable? = if (closed) checkpointFailure else null
        // Once retirement owns this local boundary, caller cancellation cannot
        // abandon its disk commit. It is propagated after the real result is
        // recorded, never cached as another caller's cancellation.
        withContext(NonCancellable) {
            if (!closed) {
                try { flushOpenPersistence() }
                catch (cause: Throwable) { failure=retirementStorageFailure(cause) }
            }
            close()
            closingJobs.distinct().filter { it !== caller }.forEach { it.join() }
            cancelledSendPersistenceFailure?.let { cause ->
                if (failure == null) failure=retirementStorageFailure(cause)
                else if (failure !== cause) failure?.addSuppressed(cause)
            }
            try { (persistence as? AwaitableWorkbenchPersistence)?.let { store -> withContext(persistenceDispatcher) { store.closeStore() } } }
            catch (cause: Throwable) {
                if (failure == null) failure=retirementStorageFailure(cause) else failure?.addSuppressed(cause)
            }
            retirementFailure=failure
            persistenceRetired=true
        }
        caller?.ensureActive()
        failure?.let { throw it }
    }

    private fun retirementStorageFailure(cause: Throwable): Throwable =
        if (cause is CancellationException) java.io.IOException("LOCAL_SAVE_INTERRUPTED", cause) else cause

    fun cancel() = close()

    fun updateDebouncePolicy(policy:DebouncePolicy) { batcher.updatePolicy(policy) }

    private val batcher = DebounceBatcher(
        scope = workScope,
        policy = debouncePolicy,
        onFlush = { conversationScope, conversationId, messages ->
            submitBatch(conversationScope, conversationId, messages)
        },
    )

    init {
        persistence?.load()?.let { saved ->
            saved.cancellations.forEach { rememberCancelledGeneration(it.conversationId, it.generationId) }
            saved.messageGenerations.forEach { messageGenerations[it.conversationId to it.messageId] = MessageGeneration(it.generationId, it.revision) }
            saved.pendingFolderAssignments.forEach { pendingFolderAssignments[it.conversationId] = it.folderId }
            folderAssignmentScope = scopeFactory()
            unconfirmedConversationCreation = saved.pendingConversationCreation?.let { ConversationCreationAttempt(scopeFactory(), it.clientId, it.folderId) }
            deletedThreads.addAll(saved.deletedThreadIds)
            threadsSnapshotRevision=saved.threadsSnapshotRevision
            hydrateInitialSendAtoms(buildSet {
                saved.threadId?.let(::add)
                saved.submission?.conversationId?.let(::add)
                saved.batches.forEach { add(it.conversationId) }
            })
            saved.batches.forEach { b -> if (b.sealed) frozenBatches[b.batchId]=b else b.messages.forEach { collectingUnits[it.clientMessageId.value]=b.conversationId to it } }
            activeThreadId = saved.threadId
            mirrored.putAll(saved.messages.map(::withLatestMessageStatus).associateBy { it.id })
            mirroredRevisions.putAll(saved.revisions)
            handledEventIds.addAll(saved.eventIds)
            userRenamedThreads.addAll(saved.renamedThreads)
            draftRevision = saved.draftRevision
            failedSubmission = saved.submission?.let { DraftSubmission(it.text,it.attachmentIds,it.revision,it.conversationId,it.clientMessageId) }
            saved.threadId?.let(::reconcileDeletedRecoveryUnits)
            val restoredRows = renderTimeline()
            _state.value = _state.value.copy(threads = if (saved.threads.isEmpty()) Loadable.Empty else Loadable.Ready(saved.organization.sort(saved.threads)),
                organization=saved.organization, pendingFolderAssignments=pendingFolderAssignments.toMap(),
                activeThreadId = saved.threadId, activeThreadTitle = saved.title, draft = saved.draft.ifBlank { failedSubmission?.text.orEmpty() },
                attachments = attachmentCoordinator?.restoredDrafts() ?: saved.attachments, pendingBatch=saved.batches.filter { it.conversationId==saved.threadId }.flatMap { b -> b.messages.filterNot { atomsFor(b.conversationId)[it.clientMessageId.value]?.isDeleted == true }.map { m -> TimelineEntry("local_"+m.clientMessageId.value,"user",m.text,true,atomsFor(b.conversationId)[m.clientMessageId.value]?.timestamp ?: 0L,true,b.batchId) } }, timeline = if (restoredRows.isEmpty()) Loadable.Empty else Loadable.Ready(restoredRows),
                composer = if (failedSubmission != null) ComposerState.FAILED else ComposerState.EDITING,
                hasRecoverableSubmissions = frozenBatches.values.any { it.conversationId == saved.threadId } || collectingUnits.values.any { it.first == saved.threadId } ||
                    saved.threadId?.let { recoverableOrdinaryAtoms(it).isNotEmpty() } == true,
                notice = if (failedSubmission != null) "SEND_OUTCOME_UNKNOWN:点击发送可查询并重试原消息" else if (frozenBatches.isNotEmpty() || collectingUnits.isNotEmpty()) "SEND_OUTCOME_UNKNOWN:请确认并查询待处理批次" else null)
            attachmentCoordinator?.let { coordinator ->
                _state.value.attachments.forEach { observeAttachment(coordinator,it.id.value) }
            }
            onActiveThreadChanged(activeThreadId)
            if (activeThreadId != null) { observeThreadEvents(); activeThreadId?.let(::reloadTimeline) }
        }
        refreshThreads()
        if (supportsConversationManagement) observeThreadEvents()
        workScope.launch { activeWork.ids.collect { update { it } } }
        loadCatalog()
        observeStreamHealth()
        recoverKnownMedia()
        (repository as? com.openandroidintelligence.conversation.ports.GenerationTracker)?.generationState?.let { source ->
            generationJob=workScope.launch { source.collect { generation ->
                val generationId = (repository as? com.openandroidintelligence.conversation.ports.GenerationTracker)?.generationId?.value
                observeGenerationIdentity(generationId)
                val pending = requestedCancellations[activeThreadId to (generationId ?: lastObservedGenerationId)]
                    ?.takeIf { it.factRevision == generationFactRevision }
                if (generation in setOf(GenerationState.FAILED,GenerationState.COMPLETED)) pending?.trackerTerminal = generation
                val executionSettled = generationId != null &&
                    generation in setOf(GenerationState.COMPLETED, GenerationState.FAILED, GenerationState.CANCELLED) &&
                    activeThreadId?.let { rememberExecutionTerminal(it, generationId) } == true
                update { state ->
                    val next = if (generation == GenerationState.IDLE && state.generation in setOf(GenerationState.COMPLETED,GenerationState.CANCELLED,GenerationState.FAILED,GenerationState.UNSUPPORTED)) state
                    else if (generation == GenerationState.FAILED && generationId != null && activeThreadId to generationId in cancelledGenerations)
                        state.copy(generation=GenerationState.CANCELLED)
                    else if (pending != null && generation != GenerationState.COMPLETED)
                        state.copy(generation=GenerationState.CANCEL_REQUESTED)
                    else state.copy(generation=generation)
                    if (executionSettled && (state.timeline is Loadable.Ready || state.timeline is Loadable.Empty))
                        next.copy(timeline = Loadable.Ready(renderTimeline(state.pendingBatch))) else next
                }
            } }
        }
    }

    private fun recoverKnownMedia() {
        val durable=persistence as? AwaitableWorkbenchPersistence ?: return
        if (media == null || closed) return
        workScope.launch {
            try {
                // 启动补存逐个入队并等待，避免大量旧引用一次性创建无界数量的任务。
                withContext(persistenceDispatcher) { durable.loadKnownMediaMessages() }.forEach { message ->
                    val threadId=message.conversationId?.value ?: return@forEach
                    message.parts.filterIsInstance<com.openandroidintelligence.conversation.model.MessagePart.Attachment>().forEach { part ->
                        if (closed) return@launch
                        val key=historicalAttachmentKey(threadId,part.draftId.value)
                        scheduleHistoricalMedia(threadId,listOf(message.copy(parts=listOf(part))))
                        mediaJobs[key]?.join()
                        attachmentCoordinator?.restoredDrafts()?.firstOrNull { attachmentCoordinator.remoteAttachmentId(it.id.value)==part.draftId.value }
                            ?.let { localArchiveJobs[it.id.value]?.join() }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (!closed) update { it.copy(notice="LOCAL_MEDIA_RECOVERY_FAILED:${errorCodeOf(failure)}") } }
        }
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
        healthJob = workScope.launch {
            launch {
                source.streamFailure.collect { failure ->
                    if (failure != null) _state.update { it.copy(notice = "EVENTS_FAILED:$failure") }
                    else _state.update { state -> state.copy(notice = state.notice?.takeUnless { it.startsWith("EVENTS_FAILED:NATIVE_EVENT_SYNC_FAILED:") }) }
                }
            }
            source.streamHealth.collect { health ->
                val previous = _state.value.streamHealth
                update { it.copy(streamHealth = health) }
                if (previous == com.openandroidintelligence.conversation.model.StreamHealth.LIVE &&
                    health != com.openandroidintelligence.conversation.model.StreamHealth.LIVE
                ) {
                    activeThreadId?.let(::reloadTimeline)
                }
                if (health == com.openandroidintelligence.conversation.model.StreamHealth.LIVE && previous != health) {
                    refreshThreads()
                    repository.retryAllPendingDeliveries()
                }
                if (health == com.openandroidintelligence.conversation.model.StreamHealth.FAILED) {
                    update { it.copy(notice = it.notice?.takeIf { notice -> notice.startsWith("EVENTS_FAILED:NATIVE_EVENT_SYNC_FAILED:") }
                        ?: "EVENTS_FAILED:EVENT_STREAM_FAILED") }
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
    private fun armReplyWatchdog(conversationId: String, clientMessageIds: Collection<String>, generationId: String?) {
        if (!replyTimeouts.enabled) return
        val target = ReplyWatchdogTarget(conversationId, clientMessageIds.toSet(), generationId)
        replyWatchdog?.cancel()
        awaitingReply = target
        replyWatchdog = workScope.launch {
            delay(replyTimeouts.firstReplyMillis)
            if (awaitingReply !== target) return@launch
            // The event for this reply may have been produced while the stream
            // was down; the timeline is the authoritative answer either way.
            if (activeThreadId == conversationId) reloadTimeline(conversationId)
            delay((replyTimeouts.giveUpMillis - replyTimeouts.firstReplyMillis).coerceAtLeast(0L))
            if (awaitingReply !== target) return@launch
            awaitingReply = null
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
        awaitingReply = null
    }

    /** Older turns may still publish after a follow-up has armed its watchdog. */
    private fun disarmReplyWatchdogFor(
        conversationId: String,
        generationId: String?,
        clientMessageId: String? = null,
        belongsToCurrentTurn: Boolean = false,
    ) {
        val target = awaitingReply ?: return
        if (target.conversationId != conversationId) return
        val isExactMessage = clientMessageId != null && clientMessageId in target.clientMessageIds
        val hasGenerationIds = generationId != null && target.generationId != null
        val isExactGeneration = hasGenerationIds && generationId == target.generationId
        if (isExactMessage || isExactGeneration || (!hasGenerationIds && belongsToCurrentTurn)) {
            disarmReplyWatchdog()
        }
    }

    fun retryTimeline() {
        activeThreadId?.let(::reloadTimeline) ?: refreshThreads()
    }

    fun retryPendingDeliveries() {
        activeThreadId?.let(::schedulePendingDeliveryDrain)
    }

    fun refreshThreads() {
        if (closed || _state.value.managingThreads) return
        retryFolderAssignments()
        val epoch=++threadRequestEpoch
        val mutationEpoch=organizationMutationEpoch
        val requestedScope=scopeFactory()
        val requestedThreadEpoch=threadEpoch
        val requestedActiveId=activeThreadId
        update { if (it.threads is Loadable.Ready || it.threads is Loadable.Empty) it else it.copy(threads = Loadable.Loading) }
        workScope.launch {
            try {
                val page=repository.completeConversations(requestedScope)
                val organization=if (supportsConversationManagement) repository.readOrganization() else _state.value.organization
                if (closed || epoch != threadRequestEpoch || mutationEpoch != organizationMutationEpoch || requestedScope != scopeFactory()) return@launch
                // Snapshot tokens identify coherent pages; host digest values have
                // no numerical order. Request/mutation epochs above reject late reads.
                threadsSnapshotRevision=page.snapshotRevision
                val installedOrganization=organization.takeUnless { it.revision < _state.value.organization.revision } ?: _state.value.organization
                val threads=installedOrganization.sort(page.conversations.filterNot { it.id.value in deletedThreads })
                update { state -> state.copy(organization=installedOrganization,
                    selectedFolderId=state.selectedFolderId?.takeIf { id -> installedOrganization.folders.any { it.folderId == id } },
                    threads=if (threads.isEmpty()) Loadable.Empty else Loadable.Ready(threads),
                    // A title event's list refresh must honor the same manual-rename protection.
                    activeThreadTitle=if (activeThreadId in userRenamedThreads) state.activeThreadTitle
                        else threads.firstOrNull { it.id.value == activeThreadId }?.title ?: state.activeThreadTitle) }
                val active=activeThreadId
                if (supportsConversationManagement && page.authoritative && active != null && requestedActiveId == active && requestedThreadEpoch == threadEpoch &&
                    bootstrapJob?.isActive != true && !_state.value.creatingThread &&
                    threads.none { it.id.value == active }) {
                    leaveManagedThread(active)
                }
                if (activeThreadId == null && bootstrapJob?.isActive != true && _state.value.draft.isBlank() &&
                    _state.value.attachments.isEmpty() && pendingSubmission == null && failedSubmission == null) {
                    threads.firstOrNull { !it.archived }?.let { openThread(it.id.value) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (epoch != threadRequestEpoch || requestedScope != scopeFactory()) return@launch
                update { state ->
                    if (state.threads is Loadable.Ready || state.threads is Loadable.Empty) state.copy(notice="THREAD_SYNC_FAILED:${errorCodeOf(failure)}")
                    else state.copy(threads=Loadable.Failed(errorCodeOf(failure)))
                }
            }
        }
    }

    private var catalogRequestEpoch = 0L
    fun loadCatalog() {
        if (closed) return
        val epoch = ++catalogRequestEpoch
        val requestedScope = scopeFactory()
        update { it.copy(catalog = Loadable.Loading) }
        workScope.launch {
            val result = try {
                Result.success(catalogRepository.get(gatewayId=requestedScope.gatewayId,languageCode=languageCode))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Result.failure(failure) }
            if (epoch != catalogRequestEpoch || scopeFactory() != requestedScope) return@launch
            update { it.copy(catalog=result.toLoadable { catalog -> catalog.commands.isEmpty() }) }
        }
    }

    fun openThread(threadId: String) {
        if (closed || threadId in deletedThreads) return
        if (activeThreadId == threadId) return
        attachmentTargetRevision++
        cancelPendingSubmission()
        threadEpoch++
        activeThreadId = threadId
        onActiveThreadChanged(threadId)
        if (persistence==null) sendAtomsByThread.putIfAbsent(threadId,linkedMapOf())
        mirrored.clear()
        renderedEntryCache.clear()
        mirroredRevisions.clear()
        messageStatusRevisions.clear()
        messageStatusesById.clear()
        messageStatusesByClientId.clear()
        // 缩略图以账号和会话作为键；切换只清活动消息，不清属于其他会话的有限预览。
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
                historyHydrated = persistence==null,
                timeline = Loadable.Loading,
                pendingBatch = emptyList(),
                pendingDeliveries = emptyList(),
                // Generation, composer and notice describe the conversation being
                // left: a turn it was still running, or the failure that turn
                // produced, must not read as the state of the one being opened.
                generation = GenerationState.IDLE,
                composer = ComposerState.EDITING,
                notice = null,
            )
        }

        timelineJob = workScope.launch {
            if (!restoreLocalTimeline(threadId)) return@launch
            loadPendingDeliveries(threadId)
            try {
                repository.pendingApprovalFallbacks(threadId).forEach { fallback ->
                    if (activeThreadId == threadId) rememberApprovalFallback(fallback)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The live event stream may still deliver the fallback. A failed
                // read must not replace the conversation with an error page.
            }
            val result = Result.runCatching { repository.completeTimeline(threadId) }
            if (!isActive || activeThreadId != threadId) return@launch
            result.fold(
                onSuccess = { page ->
                    if (!isActive || activeThreadId != threadId) return@launch
                    scheduleHistoricalMedia(threadId,page.messages)
                    page.messages.sortedBy { it.timestamp }.forEach { rawMessage ->
                        val message = withLatestMessageStatus(rawMessage)
                        val currentRevision = mirroredRevisions[message.id] ?: 0L
                        if (!mirrored.containsKey(message.id) || (message.contentRevision?.let { it > currentRevision } == true) || (message.state == "CONFIRMED" && currentRevision == 0L)) {
                            if (message.sender == "assistant" && message.state == "CONFIRMED") {
                                pruneConfirmedAssistantDuplicates(message)
                            }
                            mirrored[message.id] = message
                            mirroredRevisions[message.id] = maxOf(currentRevision,message.contentRevision ?: 0L)
                        }
                    }
                    maybeArmCreationGraceFromTimeline(threadId)
                    val hasStreaming = mirrored.values.any { it.sender == "assistant" && it.state == "STREAMING" }
                    update { state ->
                        if (!isActive || state.activeThreadId != threadId) state
                        else state.copy(
                            timeline = if (
                                renderTimeline(state.pendingBatch).isEmpty() &&
                                creationReceiptRow(threadId) == null &&
                                approvalRowsForActiveThread().isEmpty()
                            ) {
                                Loadable.Empty
                            } else {
                                Loadable.Ready(renderTimeline(state.pendingBatch))
                            },
                            generation = (repository as? com.openandroidintelligence.conversation.ports.GenerationTracker)?.generationState?.value
                                ?.takeUnless { it == GenerationState.IDLE } ?: if (hasStreaming) GenerationState.RUNNING else GenerationState.IDLE,
                        )
                    }
                },
                onFailure = { cause ->
                    if (!isActive || activeThreadId != threadId) return@launch
                    update { state ->
                        if (!isActive || state.activeThreadId != threadId) state
                        else if (state.timeline is Loadable.Ready || state.timeline is Loadable.Empty) state.copy(notice="HISTORY_SYNC_FAILED:${errorCodeOf(cause)}")
                        else state.copy(timeline = Loadable.Failed(errorCodeOf(cause)))
                    }
                },
            )
        }
        observeThreadEvents()
    }

    /** Creates a Gateway-owned conversation without sending a slash command. */
    fun createThread() {
        if (closed || _state.value.managingThreads || _state.value.creatingThread || bootstrapJob?.isActive == true) return
        attachmentTargetRevision++
        val sourceThreadId = activeThreadId
        val sourceEpoch = threadEpoch
        val targetFolderId = _state.value.selectedFolderId
        cancelPendingSubmission()
        update { it.copy(creatingThread=true,creationSourceThreadId=sourceThreadId,notice=null) }
        creationSendJob = workScope.launch {
            try {
                val conversation = createGatewayConversation(targetFolderId)
                currentCoroutineContext().ensureActive()
                update { it.copy(creatingThread=false,creationSourceThreadId=null) }
                refreshThreads()
                // Navigation while HTTP is in flight belongs to the user.
                if (threadEpoch == sourceEpoch && activeThreadId == sourceThreadId) {
                    openThread(conversation.id.value)
                    update { it.copy(activeThreadTitle=conversation.title) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) {
                update { it.copy(creatingThread=false,creationSourceThreadId=null,
                    notice="CONVERSATION_CREATE_FAILED:${errorCodeOf(cause)}") }
                refreshThreads()
            }
        }
    }

    fun cancelThreadCreation() {
        if (!_state.value.creatingThread) return
        creationSendJob?.cancel()
        if (pendingCreation != null) abandonAgentThreadCreation("CONVERSATION_CREATE_CANCELLED:USER_CANCELLED")
        update { it.copy(creatingThread=false,creationSourceThreadId=null,notice="CONVERSATION_CREATE_CANCELLED:USER_CANCELLED") }
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

    private fun rememberApprovalFallback(
        event: com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.ApprovalTextFallback,
    ) {
        val rawId = event.approvalId?.value ?: event.eventId
        val safeId = rawId.replace(Regex("[^A-Za-z0-9._~-]"), "_").take(120)
            .ifBlank { "approval_fallback" }
        val request = com.openandroidintelligence.conversation.model.ApprovalRequest(
            approvalId = com.openandroidintelligence.conversation.model.ApprovalId(safeId),
            conversationId = event.conversationId,
            command = event.command,
            reason = event.reason,
            options = emptyList(),
            timeoutSeconds = 0L,
            requestedAt = event.occurredAt,
            expiresAt = Long.MAX_VALUE,
        )
        approvalFallbacks[safeId] = ApprovalCardState.TextOnly(
            request = request,
            commands = event.commands,
            diagnosticCode = event.diagnosticCode,
        )
        while (approvalFallbacks.size > MAX_APPROVAL_CARDS) {
            approvalFallbacks.remove(approvalFallbacks.keys.first())
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
        val fallback = approvalFallbacks.remove(key)
        if (fallback != null) {
            approvalCards[key] = ApprovalCardState.Resolved(
                request = fallback.request,
                outcome = event.outcome,
                decidedAt = event.decidedAt,
            )
            reRenderApprovalRows()
            return
        }
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
        if (choice == ApprovalChoice.UNKNOWN || card.request.options.none { it.choice == choice }) return
        if (card.request.countdownAt(clock()).expired) {
            // The window the Gateway gave is over: the buttons are already grey,
            // and sending a decision the Gateway will refuse would only look
            // like a failure of the app.
            update { it.copy(notice = ApprovalNotices.EXPIRED) }
            return
        }
        approvalCards[approvalId] = ApprovalCardState.Submitting(card.request, choice)
        reRenderApprovalRows()
        workScope.launch {
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
        return (approvalCards.values + approvalFallbacks.values)
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
        creationWatchdog = workScope.launch {
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
        creationGraceJob = workScope.launch {
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
        workScope.launch {
            val detail = runCatching { repository.readConversation(threadId) }.getOrNull()
            refreshThreads()
            if (detail == null) return@launch
            if (activeThreadId == threadId && !isThreadUserRenamed(threadId)) {
                update { it.copy(activeThreadTitle = detail.title) }
            }
        }
    }

    /** Creates the first thread through the same authoritative Gateway endpoint. */
    private fun bootstrapConversationForFirstMessage(optimisticEntry: TimelineEntry?): Deferred<Result<String>> {
        bootstrapJob?.takeIf { it.isActive }?.let { return it }
        return workScope.async {
            try {
                val conversation = createGatewayConversation()
                activeThreadId = conversation.id.value
                onActiveThreadChanged(conversation.id.value)
                mirrored.clear()
                renderedEntryCache.clear()
                mirroredRevisions.clear()
                timelineJob?.cancel()
                update { state ->
                    // The first send was already frozen and shown before the
                    // create request. Its identity stays visible while its
                    // durable send record is written for the new conversation.
                    val pending = optimisticEntry?.let(::listOf).orEmpty()
                    state.copy(activeThreadId = conversation.id.value, activeThreadTitle = conversation.title,
                        timeline = if (pending.isEmpty()) Loadable.Empty else Loadable.Ready(renderTimeline(pending)),
                        pendingBatch = pending, notice = null)
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
        val opensCommands = text.startsWith("/") && !_state.value.draft.startsWith("/")
        // A frozen message belongs to its own pending send. Editing the next
        // draft must not withdraw it while its attachments are still uploading.
        if (pendingSubmission?.optimisticEntry == null) cancelPendingSubmission()
        draftRevision++
        update { it.copy(draft = text) }
        if (opensCommands) loadCatalog()
    }

    fun cancelPendingSubmission() {
        val cancelled = pendingSubmission
        cancelled?.let { activeWork.cancelPending(it.clientMessageId) }
        pendingSubmission = null
        if (_state.value.composer == ComposerState.WAITING_ATTACHMENTS) {
            update { state ->
                val pending = state.pendingBatch.filterNot { it.key == cancelled?.optimisticEntry?.key }
                state.copy(composer = ComposerState.EDITING, notice = null,
                    draft = state.draft.ifEmpty { cancelled?.text.orEmpty() },
                    pendingBatch = pending,
                    timeline = if (cancelled?.optimisticEntry != null) Loadable.Ready(renderTimeline(pending)) else state.timeline,
                    generation = cancelled?.generationBeforeSubmit ?: state.generation)
            }
        }
    }

    /**
     * Prepares and starts uploading a local attachment through the real three-step contract.
     */
    fun addAttachment(selection: com.openandroidintelligence.conversation.ports.LocalAttachmentSelection) {
        if (closed || _state.value.managingThreads || _state.value.activeThreadArchived) return
        if (availableAttachmentSlots == 0) {
            update { it.copy(notice = "每条消息最多添加 $MAX_COMPOSER_ATTACHMENTS 个附件，请先移除附件或发送后再添加") }
            return
        }
        val coordinator = attachmentCoordinator ?: run {
            update { it.copy(notice = "ATTACHMENT_UNAVAILABLE:NO_COORDINATOR") }
            return
        }
        cancelPendingSubmission()
        draftRevision++
        val targetRevision=attachmentTargetRevision
        val targetThreadEpoch=threadEpoch
        attachmentPreparations++
        workScope.launch {
            try {
                val draft = coordinator.prepare(selection)
                val draftId = draft.id.value
                if (closed || targetRevision != attachmentTargetRevision || targetThreadEpoch != threadEpoch) {
                    coordinator.discard(draftId)
                    return@launch
                }
                selection.previewBytes?.let { attachmentPreviews[draftId] = it }
                update { state ->
                    state.copy(attachments = state.attachments + draft)
                }
                observeAttachment(coordinator,draftId)
            } finally {
                attachmentPreparations--
            }
        }
    }

    /** The same live gate is used before recording and before submitting its attachment. */
    fun canSendVoiceAttachment(expectedThreadId: String?): Boolean =
        attachmentCoordinator != null && !closed && allowSending && activeThreadId == expectedThreadId &&
            !_state.value.managingThreads && !_state.value.activeThreadArchived &&
            (repository as? com.openandroidintelligence.conversation.ports.GenerationTracker)?.generationId?.value == null &&
            !creationBlocksActiveThread() && pendingSubmission == null &&
            _state.value.composer !in setOf(ComposerState.SUBMITTING,ComposerState.WAITING_ATTACHMENTS) &&
            _state.value.generation !in setOf(GenerationState.QUEUED,GenerationState.RUNNING,GenerationState.CANCEL_REQUESTED,GenerationState.OUTCOME_UNKNOWN)

    /** A voice recording is one attachment-only submission and never consumes the text draft. */
    suspend fun sendVoiceAttachment(
        selection: com.openandroidintelligence.conversation.ports.LocalAttachmentSelection,
        expectedThreadId: String?,
    ): Boolean {
        val coordinator = attachmentCoordinator ?: return false
        val epoch = threadEpoch
        fun ready() = canSendVoiceAttachment(expectedThreadId) && threadEpoch == epoch
        if (!ready()) return false
        var draftId: String? = null
        var sendJob: Job? = null
        var submitted = false
        var voiceClientId: String? = null
        val previousGeneration = _state.value.generation
        val attempt = VoiceAttempt()
        suspend fun cleanUnsentVoice() {
            val id = draftId ?: return
            val clientId = voiceClientId
            if (!closed && clientId != null) attempt.conversationId?.let { target ->
                sendAtomsMutex.withLock {
                    val latest = loadSendAtomsOnIo(target)
                    val remaining = latest.filterNot { it.clientMessageId.value == clientId && it.submissionState != LocalSubmissionState.ACCEPTED }
                    if (latest != remaining) saveAtoms(target,remaining)
                    sendAtomsByThread[target] = remaining.associateByTo(linkedMapOf()) { it.clientMessageId.value }
                    activeWork.finishSubmission(clientId, remaining.firstOrNull { atom -> atom.clientMessageId.value == clientId })
                }
            }
            if (failedSubmission?.clientMessageId == clientId) failedSubmission = null
            update { state ->
                val pending = state.pendingBatch.filterNot { it.key == "local_$clientId" }
                state.copy(attachments=state.attachments.filterNot { it.id.value == id },pendingBatch=pending,
                    composer=if (threadEpoch == epoch) ComposerState.EDITING else state.composer,
                    generation=if (threadEpoch == epoch) previousGeneration else state.generation,
                    timeline=if (state.timeline is Loadable.Ready) Loadable.Ready(renderTimeline(pending)) else state.timeline)
            }
            // Once closing starts, a prepared identity may already be durable; keep its only source for recovery.
            if (!closed || attempt.conversationId == null) coordinator.discard(id)
            persistCheckpoint()
        }
        try {
            val draft = coordinator.prepare(selection)
            draftId = draft.id.value
            val verified = coordinator.observe(draftId).first { it.state in setOf(
                AttachmentState.VERIFIED,AttachmentState.RETRYABLE_FAILURE,AttachmentState.TERMINAL_FAILURE,
                AttachmentState.OUTCOME_UNKNOWN,AttachmentState.CANCELLED,
            ) }
            if (verified.state != AttachmentState.VERIFIED) error(verified.errorMessage ?: "VOICE_UPLOAD_FAILED")
            if (!ready()) return false
            val currentDraft = coordinator.restoredDrafts().firstOrNull { it.id == draft.id }
                ?: draft.copy(state=AttachmentState.VERIFIED)
            update { it.copy(attachments=it.attachments + currentDraft.copy(state=AttachmentState.VERIFIED)) }
            val submission = DraftSubmission("",listOf(draftId),-1L,expectedThreadId,isolatedThreadEpoch=epoch,voiceAttempt=attempt)
            voiceClientId = submission.clientMessageId
            beginLegacyTurn(expectedThreadId)
            generationFactRevision++
            pendingSubmission = submission
            attachmentTargetRevision++
            activeWork.begin(submission.clientMessageId, submission.conversationId)
            submitted = true
            sendJob = submitWhenAttachmentsVerified()
            sendJob?.join()
            val target = attempt.conversationId ?: expectedThreadId ?: activeThreadId
            return target?.let { atomsFor(it)[submission.clientMessageId]?.submissionState == LocalSubmissionState.ACCEPTED } == true
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                sendJob?.cancelAndJoin()
                val target = attempt.conversationId ?: expectedThreadId ?: activeThreadId
                val atom = target?.let { thread -> voiceClientId?.let { atomsFor(thread)[it] } }
                // An uncertain POST still needs its original for an explicit recovery.
                // Only an archived original (or a request never sent) permits deleting staging.
                if (submitted && attempt.requestStarted) draftId?.let { id ->
                    val archived = target?.let { thread -> coordinator.remoteAttachmentId(id)?.let { remote ->
                        historicalAttachmentKey(thread,remote) in preSubmittedMedia
                    } } == true
                    if (atom == null || atom.submissionState == LocalSubmissionState.ACCEPTED || archived) coordinator.discard(id)
                }
                if (threadEpoch == epoch) update { state ->
                    val pending = state.pendingBatch.filterNot { it.key == "local_$voiceClientId" }
                    state.copy(attachments=state.attachments.filterNot { it.id.value==draftId },
                        composer=ComposerState.EDITING,pendingBatch=pending,
                        timeline=if (state.timeline is Loadable.Ready) Loadable.Ready(renderTimeline(pending)) else state.timeline,
                        generation=if (atom?.submissionState == LocalSubmissionState.OUTCOME_UNKNOWN) GenerationState.OUTCOME_UNKNOWN
                            else if (atom == null) previousGeneration else state.generation,
                        notice=if (atom?.submissionState == LocalSubmissionState.OUTCOME_UNKNOWN) "VOICE_SEND_OUTCOME_UNKNOWN" else "VOICE_SEND_CANCELLED")
                }
                else update { state -> state.copy(attachments=state.attachments.filterNot { it.id.value==draftId }) }
            }
            throw cancelled
        } catch (failure: Exception) {
            if (activeThreadId == expectedThreadId && threadEpoch == epoch)
                update { it.copy(notice="VOICE_SEND_FAILED:${errorCodeOf(failure)}") }
            return false
        } finally {
            if (!submitted) draftId?.let { id -> withContext(NonCancellable) { coordinator.discard(id) } }
            else if (!attempt.requestStarted) withContext(NonCancellable) { cleanUnsentVoice() }
            else {
                // The voice message owns its recovery source; the shared composer owns only its original draft.
                // Failed/unknown send atoms keep the source for an explicit retry, never a later text send.
                val ownsFailure = failedSubmission?.clientMessageId == voiceClientId
                update { state -> state.copy(
                    attachments=state.attachments.filterNot { it.id.value == draftId },
                    composer=if (threadEpoch == epoch && ownsFailure && state.composer == ComposerState.FAILED) ComposerState.EDITING else state.composer,
                ) }
                persistCheckpoint()
            }
        }
    }

    private fun observeAttachment(coordinator: com.openandroidintelligence.conversation.ports.AttachmentDraftCoordinator,draftId: String) {
        attachmentJobs[draftId]?.cancel()
        attachmentJobs[draftId] = workScope.launch {
            coordinator.observe(draftId).collect { draftState ->
                update { state -> state.copy(attachments = state.attachments.map { current ->
                    if (current.id.value == draftId) current.copy(
                        state = draftState.state, progress = draftState.progress,
                        transferredBytes = draftState.transferredBytes,totalBytes = draftState.totalBytes,
                        errorMessage = draftState.errorMessage,
                    ) else current
                }) }
                submitWhenAttachmentsVerified()
            }
        }
    }

    /**
     * Removes an attachment draft and cancels its upload job.
     */
    fun removeAttachment(draftId: String) {
        if (draftId in _state.value.inFlightAttachmentIds) return
        cancelPendingSubmission()
        draftRevision++
        attachmentJobs.remove(draftId)?.cancel()
        attachmentPreviews.remove(draftId)
        attachmentCoordinator?.let { coordinator ->
            workScope.launch {
                try {
                    coordinator.discard(draftId)
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Exception) {
                    update { it.copy(notice = "ATTACHMENT_CLEANUP_FAILED:${errorCodeOf(cause)}") }
                }
            }
        }
        update { state ->
            state.copy(attachments = state.attachments.filterNot { it.id.value == draftId })
        }
    }

    /** 受理后先归档本地原件，成功才释放上传暂存；归档失败不重复发送已受理消息。 */
    private suspend fun releaseSubmittedAttachment(threadId: String,draftId: String) {
        val initialKey=attachmentCoordinator?.remoteAttachmentId(draftId)?.let { historicalAttachmentKey(threadId,it) }
        initialKey?.let { key ->
            atomsFor(threadId).values.firstOrNull { atom -> atom.parts.any { part ->
                part is com.openandroidintelligence.conversation.model.MessagePart.Attachment && part.draftId.value==key.attachmentId
            } }?.messageId?.let { mediaOwners[key]=it }
        }
        val initialEpoch=initialKey?.let { mediaEpochs[it] ?: 0L }
        val currentJob=currentCoroutineContext()[Job]
        localArchiveJobs[draftId]?.takeIf { it.isActive && it !== currentJob }?.let {
            it.join()
            if (attachmentCoordinator?.remoteAttachmentId(draftId) == null) return
        }
        if (initialKey != null && !mediaTaskCurrent(initialKey,initialEpoch!!)) return
        currentJob?.let { localArchiveJobs[draftId]=it }
        attachmentJobs.remove(draftId)?.cancel()
        update { state -> state.copy(attachments = state.attachments.filterNot { it.id.value == draftId }) }
        attachmentCoordinator?.let coordinatorBlock@ { coordinator ->
            val remoteId = coordinator.remoteAttachmentId(draftId)
            val archiveKey=remoteId?.let { historicalAttachmentKey(threadId,it) }
            try {
                if (archiveKey != null && archiveKey in deletedMedia) {
                    coordinator.releaseAfterSubmit(draftId)
                    return@coordinatorBlock
                }
                if (media != null) {
                    if (archiveKey == null || archiveKey !in preSubmittedMedia) coordinator.archiveSubmitted(threadId,draftId,media)
                    archiveKey?.let archiveBlock@ { key ->
                        if (initialEpoch == null || !mediaTaskCurrent(key,initialEpoch)) return@archiveBlock
                        mediaPresentation[key] = (mediaPresentation[key] ?: MediaPresentation()).copy(
                            localState=com.openandroidintelligence.conversation.model.AttachmentLocalState.SAVED,localError=null)
                    }
                }
                if (initialKey != null && !mediaTaskCurrent(initialKey,initialEpoch!!)) return@coordinatorBlock
                coordinator.releaseAfterSubmit(draftId)
                attachmentPreviews.remove(draftId)
            } catch (cause: CancellationException) { throw cause }
            catch (cause: Exception) {
                if (closed || (initialKey != null && !mediaTaskCurrent(initialKey,initialEpoch!!))) return@coordinatorBlock
                archiveKey?.let { key ->
                    mediaPresentation[key]=(mediaPresentation[key] ?: MediaPresentation()).copy(
                        localState=com.openandroidintelligence.conversation.model.AttachmentLocalState.FAILED,localError=errorCodeOf(cause)) }
                update { it.copy(notice = "LOCAL_SAVE_FAILED:${errorCodeOf(cause)}") }
            }
        }
        repaintMedia(threadId)
    }

    private class LocalArchiveFailure(cause:Throwable):Exception("LOCAL_SAVE_FAILED:${errorCodeOf(cause)}",cause)

    /** 用户已确认发送的原件先持久归档；任何失败都发生在消息请求之前。 */
    private suspend fun archiveBeforeMessageSubmit(threadId:String,draftIds:List<String>) {
        if (draftIds.isEmpty() || media == null) return
        val coordinator=attachmentCoordinator ?: throw LocalArchiveFailure(IllegalStateException("ATTACHMENT_ARCHIVE_UNAVAILABLE"))
        try { draftIds.forEach { draftId ->
            coordinator.archiveSubmitted(threadId,draftId,media)
            currentCoroutineContext().ensureActive()
            coordinator.remoteAttachmentId(draftId)?.let { id -> preSubmittedMedia.add(historicalAttachmentKey(threadId,id)) }
        } }
        catch (cancelled:CancellationException) { throw cancelled }
        catch (failure:Exception) { throw LocalArchiveFailure(failure) }
    }

    private suspend fun ensureArchivedMessageParts(threadId:String,attachmentIds:List<String>) {
        val port=media ?: return
        attachmentIds.forEach { id ->
            if (port.localMetadata(threadId,id) == null) {
                val local=attachmentCoordinator?.restoredDrafts()?.firstOrNull { attachmentCoordinator.remoteAttachmentId(it.id.value)==id }
                    ?: throw LocalArchiveFailure(IllegalStateException("LOCAL_MEDIA_SOURCE_UNAVAILABLE"))
                archiveBeforeMessageSubmit(threadId,listOf(local.id.value))
            }
        }
    }

    private data class RetriedAttachmentRefs(val remoteIds:List<String>,val localDraftIds:List<String>)

    /** 只在原消息查询为未受理后执行手动重试；文件来源和客户端消息身份保持不变。 */
    private suspend fun retrySubmittedAttachments(threadId:String,atom:LocalSendAtom):RetriedAttachmentRefs {
        val oldParts=atom.parts.filterIsInstance<com.openandroidintelligence.conversation.model.MessagePart.Attachment>()
        val port=media ?: return RetriedAttachmentRefs(oldParts.map { it.draftId.value },emptyList())
        if (oldParts.isEmpty()) return RetriedAttachmentRefs(emptyList(),emptyList())
        val coordinator=attachmentCoordinator ?: throw LocalArchiveFailure(IllegalStateException("ATTACHMENT_RECOVERY_UNAVAILABLE"))
        val frozenIds=failedSubmission?.takeIf { it.clientMessageId==atom.clientMessageId.value }?.attachmentIds.orEmpty()
        val localIds=mutableListOf<String>()
        val replacements=oldParts.mapIndexed { index,part ->
            val localId=frozenIds.getOrNull(index) ?: coordinator.restoredDrafts().firstOrNull {
                coordinator.remoteAttachmentId(it.id.value)==part.draftId.value
            }?.id?.value ?: port.submittedDraftIds(threadId,part.draftId.value).firstOrNull()
                ?: throw LocalArchiveFailure(IllegalStateException("ATTACHMENT_RECOVERY_UNAVAILABLE"))
            val draft=coordinator.retrySubmittedSource(threadId,localId,port)
            val hash=draft.sha256.removePrefix("sha256:")
            check(part.sizeBytes?.let { it==draft.sizeBytes } != false && part.sha256?.removePrefix("sha256:")?.let { it==hash } != false) {
                "ATTACHMENT_SNAPSHOT_CHANGED"
            }
            val remoteId=coordinator.remoteAttachmentId(localId) ?: error("ATTACHMENT_NOT_VERIFIED")
            localIds+=localId
            verifiedAttachmentPart(remoteId,part.filename,part.mediaType,draft)
        }
        archiveBeforeMessageSubmit(threadId,localIds)
        var next=0
        val parts=atom.parts.map { part -> if (part is com.openandroidintelligence.conversation.model.MessagePart.Attachment) replacements[next++] else part }
        changeSendAtom(threadId,atom.clientMessageId.value) { old ->
            if (old?.isDeleted == true || old?.submissionState==LocalSubmissionState.ACCEPTED) old else old?.copy(parts=parts,submissionErrorCode=null)
        }
        flushPendingPersistence()
        return RetriedAttachmentRefs(replacements.map { it.draftId.value },localIds)
    }

    /**
     * Retries a recoverable failed attachment draft.
     */
    fun retryAttachment(draftId: String) {
        val coordinator = attachmentCoordinator ?: return
        if (closed || !_state.value.isOnline) return
        val original=failedSubmission?.takeIf { draftId in it.attachmentIds } ?: pendingSubmission?.takeIf { draftId in it.attachmentIds }
        val target=original?.conversationId ?: activeThreadId
        workScope.launch {
            try {
                target?.let { hydrateSendAtoms(it) }
                if (original != null && target != null && atomsFor(target).containsKey(original.clientMessageId)) {
                    val query=repository as? com.openandroidintelligence.conversation.ports.MessageOutcomeQuery
                        ?: error("MESSAGE_QUERY_UNAVAILABLE")
                    val known=query.queryMessage(target,ClientMessageId(original.clientMessageId))
                    if (known != null) {
                        if (!known.isDeleted) {
                            acceptSendAtom(target,original.clientMessageId,known.id)
                            releaseSubmittedAttachment(target,draftId)
                        }
                        if (activeThreadId==target) reloadTimeline(target)
                        return@launch
                    }
                }
                if (target != null && media != null) coordinator.ensureSubmittedSource(target,draftId,media)
                coordinator.retry(draftId)
            } catch (cancelled:CancellationException) { throw cancelled }
            catch (failure:Exception) { update { it.copy(notice="ATTACHMENT_RECOVERY_FAILED:${errorCodeOf(failure)}") } }
        }
    }

    /**
     * Sends the current draft.
     *
     * A slash command or anything carrying an attachment is a hard boundary and
     * goes straight out; plain text joins the debounce batch so the Agent sees
     * one ordered aggregate input while every member keeps its identity.
     */
    fun sendDraft() {
        sendDraft(com.openandroidintelligence.conversation.ports.MessageDeliveryMode.NORMAL)
    }

    fun sendDraft(delivery: com.openandroidintelligence.conversation.ports.MessageDeliveryMode) {
        val clickedAt = System.nanoTime()
        if (!allowSending) { update { it.copy(notice="OFFLINE_MIRROR:重新连接后可发送，草稿已保留") }; return }
        val current = _state.value
        if (!current.historyHydrated) {
            update { it.copy(notice="LOCAL_HISTORY_LOADING:正在恢复本地会话，草稿已保留") }
            return
        }
        if (!current.canSend) return
        if (creationBlocksActiveThread()) {
            // A send during the wait would land in the thread being left —
            // exactly the mismatch this flow exists to prevent.
            update { it.copy(notice = "CONVERSATION_CREATING:SEND_BLOCKED") }
            return
        }
        val busy = current.generation in setOf(
            GenerationState.QUEUED,
            GenerationState.RUNNING,
            GenerationState.CANCEL_REQUESTED,
        )
        val effectiveDelivery = when {
            delivery == com.openandroidintelligence.conversation.ports.MessageDeliveryMode.QUEUE && !busy ->
                com.openandroidintelligence.conversation.ports.MessageDeliveryMode.NORMAL
            else -> delivery
        }
        val hasUnknownModeChange = activeThreadId?.let { threadId ->
            atomsFor(threadId).values.any { atom ->
                !atom.isDeleted && atom.submissionState == LocalSubmissionState.OUTCOME_UNKNOWN &&
                    atom.delivery != effectiveDelivery
            }
        } == true
        if (hasUnknownModeChange) {
            update { it.copy(notice = "SEND_OUTCOME_UNKNOWN:请先确认并查询未确认消息") }
            return
        }
        val attachmentIds = current.attachments.map { it.id.value }.filterNot { it in current.inFlightAttachmentIds }
        val remoteAttachmentIds = attachmentIds.map { attachmentCoordinator?.remoteAttachmentId(it) }
        val recoverable = activeThreadId?.let(::recoverableOrdinaryAtoms).orEmpty().filter { atom ->
            !steeringWasNotForwarded(atom) && atom.delivery == effectiveDelivery &&
            atom.parts.filterIsInstance<com.openandroidintelligence.conversation.model.MessagePart.Text>().joinToString("") { it.value } == current.draft &&
                atom.parts.filterIsInstance<com.openandroidintelligence.conversation.model.MessagePart.Attachment>().map { it.draftId.value } == remoteAttachmentIds
        }
        if (recoverable.size > 1) {
            update { it.copy(notice = "SEND_OUTCOME_UNKNOWN:请先确认并查询未确认消息") }
            return
        }
        attachmentTargetRevision++
        pendingSubmission = failedSubmission?.takeIf {
            it.delivery == effectiveDelivery && it.text == current.draft && it.attachmentIds == attachmentIds && it.conversationId == activeThreadId &&
                it.conversationId?.let { id ->
                    val atom = atomsFor(id)[it.clientMessageId]
                    atom?.isDeleted == true || steeringWasNotForwarded(atom)
                } != true
        }?.copy(revision = draftRevision) ?: recoverable.singleOrNull()?.let { atom ->
            DraftSubmission(current.draft, attachmentIds, draftRevision, activeThreadId, atom.clientMessageId.value,
                delivery = effectiveDelivery)
        } ?: DraftSubmission(current.draft, attachmentIds, draftRevision, activeThreadId,
            delivery = effectiveDelivery)
        val frozen = requireNotNull(pendingSubmission)
        GatewayLog.protocolEvidence("send.clicked", mapOf("clientMessageId" to frozen.clientMessageId,
            "conversationId" to frozen.conversationId, "clickedAtNs" to clickedAt))
        val localEntry = TimelineEntry(
            key = "local_${frozen.clientMessageId}", sender = "user", text = frozen.text,
            isUser = true, timestamp = activeThreadId?.let { atomsFor(it)[frozen.clientMessageId]?.timestamp }
                ?: System.currentTimeMillis(), pendingAcceptance = true, batchGroupId = null,
            attachments = current.attachments.filter { it.id.value in frozen.attachmentIds }.map {
                com.openandroidintelligence.conversation.model.TimelineAttachment(it.id.value, it.filename,
                    it.mediaType, previewBytes = attachmentPreviews[it.id.value])
            }, clientMessageId = ClientMessageId(frozen.clientMessageId), localPlaceholder = true,
        )
        pendingSubmission = frozen.copy(optimisticEntry = localEntry, generationBeforeSubmit = current.generation)
        activeWork.begin(frozen.clientMessageId, frozen.conversationId)
        beginLegacyTurn(activeThreadId)
        generationFactRevision++
        // 点击这一次状态变更即显示冻结的发送单元；网络与可靠写盘随后异步执行。
        update { state -> state.copy(draft = "", composer = ComposerState.WAITING_ATTACHMENTS, notice = null,
            timeline = appendLocal(state.timeline, localEntry),
            pendingBatch = state.pendingBatch.filterNot { it.key == localEntry.key } + localEntry,
            generation = if (state.generation == GenerationState.RUNNING) GenerationState.RUNNING else GenerationState.QUEUED) }
        GatewayLog.protocolEvidence("T0.local_bubble", mapOf("clientMessageId" to frozen.clientMessageId,
            "conversationId" to frozen.conversationId))
        submitWhenAttachmentsVerified()
    }

    private suspend fun submitMessageToRepository(
        threadId: String,
        message: OutgoingMessage,
    ): com.openandroidintelligence.conversation.ports.MessageAcceptance =
        if (message.delivery == com.openandroidintelligence.conversation.ports.MessageDeliveryMode.STEER) {
            repository.steerMessage(threadId, message)
        } else {
            repository.submitMessage(threadId, message)
        }

    /** A click freezes the draft; upload progress can release that same submission only once. */
    private fun submitWhenAttachmentsVerified(): Job? {
        val submission = pendingSubmission ?: return null
        val drafts = _state.value.attachments.associateBy { it.id.value }
        if (submission.attachmentIds.any { drafts[it]?.state != AttachmentState.VERIFIED }) return null
        var remoteIds = submission.attachmentIds.map { id ->
            attachmentCoordinator?.remoteAttachmentId(id) ?: run {
                activeWork.cancelPending(submission.clientMessageId)
                pendingSubmission = null
                update { it.copy(composer = ComposerState.FAILED, notice = "ATTACHMENT_NOT_VERIFIED:请重新选择附件") }
                return null
            }
        }
        failedSubmission = submission
        pendingSubmission = null
        activeWork.started(submission.clientMessageId)
        update { it.copy(
            composer = ComposerState.SUBMITTING,
            inFlightAttachmentIds = it.inFlightAttachmentIds + submission.attachmentIds,
        ) }
        return workScope.launch {
            var localEntryKey: String? = submission.optimisticEntry?.key
            var sendTarget: String? = submission.conversationId
            // Whether this send emptied the composer: only then may a failure
            // hand the text back. The draft revision cannot answer that,
            // because releasing the attachment drafts below advances it too.
            var draftWasCleared = submission.optimisticEntry != null
            try {
                if (submission.isolatedThreadEpoch != null &&
                    (threadEpoch != submission.isolatedThreadEpoch || activeThreadId != submission.conversationId)) return@launch
                val target = submission.conversationId ?: activeThreadId
                    ?: bootstrapConversationForFirstMessage(submission.optimisticEntry).await().getOrThrow()
                sendTarget = target
                submission.voiceAttempt?.conversationId = target
                hydrateSendAtoms(target)
                if (atomsFor(target)[submission.clientMessageId]?.isDeleted == true) {
                    failedSubmission=null
                    update { it.copy(composer=ComposerState.EDITING,timeline=Loadable.Ready(renderTimeline(it.pendingBatch))) }
                    return@launch
                }
                if (activeThreadId == target) failedSubmission = submission.copy(conversationId = target)
                persistCheckpoint()
                val submittedAttachments = submission.attachmentIds.map { id ->
                    val d = drafts[id]
                    val att = com.openandroidintelligence.conversation.model.TimelineAttachment(
                        draftId = id,
                        filename = d?.filename.orEmpty(),
                        mediaType = d?.mediaType.orEmpty(),
                        previewBytes = attachmentPreviews[id],
                    )
                    att
                }
                submittedAttachments.zip(remoteIds).forEach { (attachment, remoteId) ->
                    rememberHistoricalAttachment(target, attachment.copy(draftId = remoteId), ClientMessageId(submission.clientMessageId))
                }
                val entry = submission.optimisticEntry ?: TimelineEntry(
                    key = "local_" + submission.clientMessageId, sender = "user",
                    text = submission.text.ifBlank { if (submittedAttachments.isNotEmpty()) "" else "[附件]" }, isUser = true,
                    timestamp = System.currentTimeMillis(), pendingAcceptance = true,
                    batchGroupId = null,
                    attachments = submittedAttachments,
                )
                var message = OutgoingMessage(
                    ClientMessageId(entry.key.removePrefix("local_")),
                    submission.text,
                    remoteIds,
                    delivery = submission.delivery,
                )
                val previousAtom = atomsFor(target)[message.clientMessageId.value]
                localEntryKey = entry.key
                changeSendAtom(target, message.clientMessageId.value) { previous -> previous ?: LocalSendAtom(
                    conversationId = ConversationId(target), clientMessageId = message.clientMessageId,
                    parts = buildList {
                        if (message.text.isNotEmpty()) add(com.openandroidintelligence.conversation.model.MessagePart.Text(message.text))
                        submittedAttachments.zip(remoteIds).forEach { (att, id) -> add(verifiedAttachmentPart(id,att.filename,att.mediaType,drafts[att.draftId])) }
                    }, timestamp = entry.timestamp, delivery = submission.delivery,
                ) }
                flushPendingPersistence()
                archiveBeforeMessageSubmit(target,submission.attachmentIds)
                if (submission.isolatedThreadEpoch != null &&
                    (threadEpoch != submission.isolatedThreadEpoch || activeThreadId != target)) return@launch
                // 发送事实已经持久保存，再清空仍属于本次提交的草稿。
                if (activeThreadId == target && draftRevision == submission.revision) {
                    update { it.copy(draft = "") }
                    draftWasCleared = true
                }
                if (activeThreadId == target) update { state -> state.copy(
                    composer = if (remoteIds.isEmpty()) ComposerState.EDITING else ComposerState.SUBMITTING,
                    timeline = appendLocal(state.timeline, entry),
                    // 重试复用同一个 clientMessageId：先移除同 key 的旧占位行，避免重复渲染。
                    pendingBatch = state.pendingBatch.filterNot { it.key == entry.key } + entry,
                    generation = if (state.generation == GenerationState.RUNNING) GenerationState.RUNNING else GenerationState.QUEUED,
                ) }

                if (eventJob?.isActive != true) {
                    observeThreadEvents()
                }

                val currentTitle = _state.value.activeThreadTitle
                val needsAutoTitle = activeThreadId == target && (currentTitle.isBlank() || currentTitle == "新对话") && !userRenamedThreads.contains(target)
                if (needsAutoTitle) {
                    val autoTitle = com.openandroidintelligence.conversation.title.ConversationTitlePolicy.generateTitle(
                        firstMessage = message,
                        attachmentNames = submittedAttachments.map { it.filename },
                    )
                    if (autoTitle.isNotBlank() && autoTitle != "新对话" && !userRenamedThreads.contains(target)) {
                        update { it.copy(activeThreadTitle = autoTitle) }
                        workScope.launch {
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
                if (supportsMessageBatches && submission.delivery ==
                    com.openandroidintelligence.conversation.ports.MessageDeliveryMode.NORMAL &&
                    !submission.text.trimStart().startsWith("/") && remoteIds.isEmpty()
                ) {
                    collectingUnits[message.clientMessageId.value]=target to message
                    persistCheckpoint()
                    batcher.offer(scopeFactory(), target, message)
                } else {
                    flushPendingPersistence()
                    val acceptance = when {
                        previousAtom?.submissionState == LocalSubmissionState.ACCEPTED ->
                            com.openandroidintelligence.conversation.ports.MessageAcceptance(
                                requireNotNull(previousAtom.messageId), message.clientMessageId.value,
                            )
                        previousAtom != null -> {
                            // 用户确认重试后先查询原身份，不能把丢失回执当成请求未执行。
                            val query = repository as? com.openandroidintelligence.conversation.ports.MessageOutcomeQuery
                                ?: throw java.io.IOException("MESSAGE_QUERY_UNAVAILABLE")
                            val known = try {
                                query.queryMessage(target, message.clientMessageId)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (cause: Exception) {
                                throw java.io.IOException("MESSAGE_QUERY_FAILED:${errorCodeOf(cause)}", cause)
                            }
                            hydrateSendAtoms(target,refresh=true)
                            if (known?.isDeleted == true) changeSendAtom(target,message.clientMessageId.value) { old ->
                                // 重试路径同样只认删除事实：远端 id 与本地已绑定身份不一致时
                                // 改写身份会把同一行拆成两条。
                                old?.identified(known.id)?.copy(parts=emptyList(),snapshotDeleted=true)
                            }
                            if (atomsFor(target)[message.clientMessageId.value]?.isDeleted == true) {
                                reconcileDeletedRecoveryUnits(target)
                                if (activeThreadId == target) update { it.copy(composer=ComposerState.EDITING,
                                    timeline=Loadable.Ready(renderTimeline(it.pendingBatch))) }
                                return@launch
                            }
                            val acceptedBeforeRetry=atomsFor(target)[message.clientMessageId.value]
                                ?.takeIf { it.submissionState==LocalSubmissionState.ACCEPTED }?.messageId
                            if (known == null && acceptedBeforeRetry == null) {
                                // 旧 VERIFIED 仅属于上次上传；等待本次恢复完成后才能使用新远端引用。
                                val retried=retrySubmittedAttachments(target,previousAtom)
                                remoteIds=retried.remoteIds
                                message=message.copy(attachmentIds=remoteIds)
                            }
                            val latest=atomsFor(target)[message.clientMessageId.value]
                            if (latest?.isDeleted == true) {
                                reconcileDeletedRecoveryUnits(target)
                                if (activeThreadId == target) update { it.copy(composer=ComposerState.EDITING,
                                    timeline=Loadable.Ready(renderTimeline(it.pendingBatch))) }
                                return@launch
                            }
                            val acceptedId=known?.id ?: latest?.takeIf { it.submissionState==LocalSubmissionState.ACCEPTED }?.messageId
                            if (acceptedId != null) com.openandroidintelligence.conversation.ports.MessageAcceptance(acceptedId,message.clientMessageId.value)
                            else submitMessageToRepository(target, message)
                        }
                        else -> {
                            if (submission.isolatedThreadEpoch != null &&
                                (threadEpoch != submission.isolatedThreadEpoch || activeThreadId != target)) return@launch
                            submission.voiceAttempt?.requestStarted = true
                            submitMessageToRepository(target, message)
                        }
                    }
                    if (acceptance.delivery == com.openandroidintelligence.conversation.ports.MessageDeliveryMode.QUEUE) {
                        changeSendAtom(target, message.clientMessageId.value) { old ->
                            old?.copy(
                                submissionState = LocalSubmissionState.QUEUED,
                                submissionErrorCode = null,
                            )
                        }
                        if (activeThreadId == target) {
                            val queued = com.openandroidintelligence.conversation.ports.PendingDelivery(
                                clientMessageId = message.clientMessageId,
                                conversationId = ConversationId(target),
                                text = message.text,
                                attachmentCount = remoteIds.size,
                                state = com.openandroidintelligence.conversation.ports.PendingDeliveryState.QUEUED,
                            )
                            update { state ->
                                val remainingBatch = state.pendingBatch.filterNot { it.key == entry.key }
                                val rendered = renderTimeline(remainingBatch).filterNot {
                                    it.clientMessageId == message.clientMessageId
                                }
                                state.copy(
                                    composer = ComposerState.EDITING,
                                    timeline = if (state.timeline is Loadable.Ready || state.timeline is Loadable.Empty)
                                        Loadable.Ready(rendered) else state.timeline,
                                    pendingBatch = remainingBatch,
                                    pendingDeliveries = state.pendingDeliveries.filterNot {
                                        it.clientMessageId == message.clientMessageId
                                    } + queued,
                                    notice = null,
                                )
                            }
                        }
                    } else if (acceptance.delivery ==
                        com.openandroidintelligence.conversation.ports.MessageDeliveryMode.STEER
                    ) {
                        changeSendAtom(target, message.clientMessageId.value) { old ->
                            old?.identified(acceptance.messageId)?.copy(
                                delivery = com.openandroidintelligence.conversation.ports.MessageDeliveryMode.STEER,
                                status = com.openandroidintelligence.conversation.ports.AgentMessageStatus.COMPLETED,
                                statusRevision = maxOf(old.statusRevision, 0L),
                                submissionErrorCode = null,
                            )
                        }
                        activeWork.accepted(message.clientMessageId.value, target, acceptance.generationId)
                        activeWork.settledClient(target, message.clientMessageId.value)
                        if (activeThreadId == target) {
                            update { state ->
                                val remainingBatch = state.pendingBatch.filterNot { it.key == entry.key }
                                state.copy(
                                    composer = ComposerState.EDITING,
                                    timeline = Loadable.Ready(renderTimeline(remainingBatch)),
                                    pendingBatch = remainingBatch,
                                    notice = "STEERED:CURRENT_TURN",
                                )
                            }
                        }
                    } else if (!acceptance.authoritative) {
                        nativePendingSubmissions[message.clientMessageId.value] = target to submission.attachmentIds
                        // An SSE receipt may race the local initiation return.
                        if (atomsFor(target)[message.clientMessageId.value]?.submissionState == LocalSubmissionState.ACCEPTED) {
                            nativePendingSubmissions.remove(message.clientMessageId.value)
                            submission.attachmentIds.forEach { releaseSubmittedAttachment(target, it) }
                            if (failedSubmission?.clientMessageId == submission.clientMessageId) failedSubmission = null
                        }
                        if (activeThreadId == target) update { state -> state.copy(composer = ComposerState.EDITING,
                            timeline = Loadable.Ready(renderTimeline(state.pendingBatch)), notice = null) }
                        persistCheckpoint()
                        return@launch
                    } else {
                        acceptSendAtom(target, message.clientMessageId.value, acceptance.messageId, generationId = acceptance.generationId)
                    if (activeThreadId == target) {
                        val statusReceipt = messageStatusesByClientId[message.clientMessageId.value]
                        if (atomsFor(target)[message.clientMessageId.value]?.isDeleted != true && !mirrored.containsKey(acceptance.messageId)) mirrored[acceptance.messageId] = TimelineMessage(
                            id = acceptance.messageId, sender = "user",
                            parts = buildList {
                                if (message.text.isNotEmpty()) add(com.openandroidintelligence.conversation.model.MessagePart.Text(message.text))
                                submittedAttachments.zip(remoteIds).forEach { (att, remoteId) ->
                                    add(verifiedAttachmentPart(remoteId,att.filename,att.mediaType,drafts[att.draftId]))
                                }
                            },
                            timestamp = entry.timestamp,
                            state = statusReceipt?.status?.wireValue ?: "queued",
                            errorCode = statusReceipt?.errorCode?.wireValue,
                            clientMessageId = message.clientMessageId,
                        )
                        mirroredRevisions.putIfAbsent(acceptance.messageId, 0L)
                        statusReceipt?.let { receipt -> messageStatusRevisions.putIfAbsent(acceptance.messageId, receipt.revision) }
                        update { state ->
                            val remainingBatch = state.pendingBatch.filterNot { row -> row.key == entry.key }
                            state.copy(
                                composer = ComposerState.EDITING,
                                timeline = Loadable.Ready(renderTimeline(remainingBatch)),
                                pendingBatch = remainingBatch,
                                notice = null,
                                generation = generationAfterAcceptance(target, listOf(message.clientMessageId.value), state.generation),
                            )
                        }
                        if (_state.value.generation !in setOf(GenerationState.COMPLETED, GenerationState.CANCELLED, GenerationState.FAILED)) {
                            armReplyWatchdog(target, listOf(message.clientMessageId.value), acceptance.generationId)
                        }
                    }
                    }
                }
                // Released only now: an attachment draft that was dropped
                // before the Gateway accepted the message left a failed send
                // with nothing to retry from.
                submission.attachmentIds.forEach { releaseSubmittedAttachment(target,it) }
                if (failedSubmission?.clientMessageId == submission.clientMessageId) failedSubmission = null
                persistCheckpoint()
            } catch (cancelled: CancellationException) {
                sendTarget?.let { persistCancelledSendOutcomes(it,listOf(submission.clientMessageId)) }
                throw cancelled
            } catch (cause: Exception) {
                if (activeThreadId == sendTarget) failedSubmission = submission.copy(conversationId = sendTarget ?: submission.conversationId)
                val unknown = cause is java.io.IOException || sendTarget?.let { atomsFor(it)[submission.clientMessageId]?.submissionState==LocalSubmissionState.OUTCOME_UNKNOWN } == true
                sendTarget?.let { target -> changeSendAtom(target, submission.clientMessageId) { old ->
                    old?.takeIf { it.submissionState != LocalSubmissionState.ACCEPTED }?.copy(
                        submissionState = if (unknown) LocalSubmissionState.OUTCOME_UNKNOWN else LocalSubmissionState.FAILED,
                        submissionErrorCode = errorCodeOf(cause))
                } }
                if (activeThreadId != sendTarget) { persistCheckpoint(); return@launch }
                // 未知结果保留原消息；输入框和错误提示仅更新其所属会话。
                update { state ->
                    val failedKey = localEntryKey
                    val remainingBatch = if (failedKey == null) {
                        state.pendingBatch
                    } else {
                        state.pendingBatch.filterNot { it.key == failedKey }
                    }
                    state.copy(
                        composer = ComposerState.FAILED,
                        notice = "${if (unknown) "SEND_OUTCOME_UNKNOWN" else "SEND_FAILED"}:${errorCodeOf(cause)}",
                        generation = generationAfterSubmissionFailure(state.generation, unknown),
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
                        draft = if (state.activeThreadId == sendTarget && draftWasCleared && state.draft.isEmpty()) {
                            submission.text
                        } else {
                            state.draft
                        },
                    )
                }
            } finally {
                if (submission.attachmentIds.isNotEmpty()) {
                    update { state -> state.copy(inFlightAttachmentIds = state.inFlightAttachmentIds - submission.attachmentIds.toSet()) }
                }
                activeWork.finishSubmission(submission.clientMessageId, sendTarget?.let { atomsFor(it)[submission.clientMessageId] })
            }
        }
    }

    private suspend fun submitBatch(
        conversationScope: ConversationScope,
        conversationId: String,
        messages: List<OutgoingMessage>,
    ) {
        if (messages.isEmpty()) return
        messages.forEach { activeWork.started(it.clientMessageId.value) }
        try {
            hydrateSendAtoms(conversationId)
        } catch (failure: Exception) {
            messages.forEach { activeWork.finishSubmission(it.clientMessageId.value, atomsFor(conversationId)[it.clientMessageId.value]) }
            throw failure
        }
        if (messages.any { atomsFor(conversationId)[it.clientMessageId.value]?.isDeleted == true }) {
            recoverBatch(conversationId,messages,frozenBatches.values.firstOrNull { it.conversationId == conversationId && it.messages.map { m -> m.clientMessageId } == messages.map { m -> m.clientMessageId } }?.batchId)
            return
        }
        val batchId="batch_"+java.security.MessageDigest.getInstance("SHA-256").digest((conversationId+"\u0000"+messages.joinToString("\u0000") { it.clientMessageId.value }).toByteArray()).joinToString("") { "%02x".format(it) }
        if (!submittingBatches.add(batchId)) return
        val flushedKeys = messages.map { "local_" + it.clientMessageId.value }.toSet()
        var remotelyAccepted = false
        try {
            frozenBatches[batchId]=SavedBatch(batchId,conversationId,messages.toList())
            messages.forEach { msg -> changeSendAtom(conversationId, msg.clientMessageId.value) { old -> old?.copy(batchId = batchId) } }
            messages.forEach { collectingUnits.remove(it.clientMessageId.value) }
            persistCheckpoint()
            flushPendingPersistence()
            ensureArchivedMessageParts(conversationId,messages.flatMap { it.attachmentIds })
            // The batch already carries its conversation: re-deriving the target
            // from a side map is how a member used to end up in the wrong thread.
            beginLegacyTurn(conversationId)
            if (activeThreadId == conversationId) generationFactRevision++
            val acceptance = repository.submitBatch(conversationId, com.openandroidintelligence.conversation.ports.MessageBatch(
                batchId = batchId, messages = messages, clientConversationId = conversationId,
            ))
            remotelyAccepted = true
            messages.forEach { msg ->
                val id = acceptance.memberIds[msg.clientMessageId.value]?.takeIf { it.isNotBlank() } ?: error("SUBMIT_BATCH_FAILED:missing-member-ids")
                acceptSendAtom(conversationId, msg.clientMessageId.value, id, acceptance.batchId, acceptance.generationId)
            }
            frozenBatches.remove(batchId)
            if (activeThreadId != conversationId) { persistCheckpoint(); refreshThreads(); return }
            val pendingEntries = _state.value.pendingBatch
            messages.forEach { msg ->
                val localId = msg.clientMessageId.value
                if (atomsFor(conversationId)[localId]?.isDeleted == true) return@forEach
                if (mirrored.containsKey(acceptance.memberIds.getValue(localId))) return@forEach
                // The Gateway's id is the only key the mirror may use: a
                // member mirrored under its own local id showed up a second
                // time as soon as the same message reached the phone under
                // the id the Gateway had issued for it.
                val id = acceptance.memberIds.getValue(localId)
                val entry = pendingEntries.firstOrNull { it.key == "local_$localId" || it.key == localId }
                val timestamp = atomsFor(conversationId)[localId]?.timestamp ?: entry?.timestamp ?: 0L
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
                    state = atomsFor(conversationId)[localId]?.status?.wireValue ?: "queued",
                    errorCode = atomsFor(conversationId)[localId]?.errorCode?.wireValue,
                    batchId = acceptance.batchId,
                    clientMessageId = msg.clientMessageId,
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
                    generation = generationAfterAcceptance(conversationId, messages.map { it.clientMessageId.value }, state.generation),
                )
            }
            if (_state.value.generation !in setOf(GenerationState.COMPLETED, GenerationState.CANCELLED, GenerationState.FAILED)) {
                armReplyWatchdog(conversationId, messages.map { it.clientMessageId.value }, acceptance.generationId)
            }
            refreshThreads()
        } catch (cancelled: CancellationException) {
            persistCancelledSendOutcomes(conversationId,messages.map { it.clientMessageId.value })
            throw cancelled
        } catch (cause: Exception) {
            var unknown = remotelyAccepted || cause is java.io.IOException
            var failureCode = errorCodeOf(cause)
            try {
                messages.forEach { msg ->
                    changeSendAtom(conversationId, msg.clientMessageId.value) { old ->
                        old?.takeIf { it.submissionState != LocalSubmissionState.ACCEPTED }?.copy(
                            submissionState = if (unknown) LocalSubmissionState.OUTCOME_UNKNOWN else LocalSubmissionState.FAILED,
                            submissionErrorCode = errorCodeOf(cause))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (storageFailure: Exception) {
                unknown = true
                failureCode = "LOCAL_SEND_PERSIST_FAILED:${errorCodeOf(storageFailure)}"
            }
            if (activeThreadId == conversationId) {
                update { state -> state.copy(
                    notice = "${if (unknown) "SEND_OUTCOME_UNKNOWN" else "SEND_FAILED"}:$failureCode",
                    generation = generationAfterSubmissionFailure(state.generation, unknown),
                    timeline = Loadable.Ready(renderTimeline(state.pendingBatch)),
                ) }
            }
        } finally {
            submittingBatches.remove(batchId)
            messages.forEach { activeWork.finishSubmission(it.clientMessageId.value, atomsFor(conversationId)[it.clientMessageId.value]) }
        }
    }

    private fun steeringWasNotForwarded(atom: LocalSendAtom?): Boolean =
        atom != null && atom.delivery == com.openandroidintelligence.conversation.ports.MessageDeliveryMode.STEER &&
            atom.submissionState == LocalSubmissionState.FAILED && atom.submissionErrorCode == "STEER_UNAVAILABLE"

    private fun recoverableOrdinaryAtoms(threadId: String): List<LocalSendAtom> {
        val batchMembers = collectingUnits.filterValues { it.first == threadId }.keys +
            frozenBatches.values.filter { it.conversationId == threadId }.flatMap { batch -> batch.messages.map { it.clientMessageId.value } }
        return atomsFor(threadId).values.filter {
            !it.isDeleted && it.batchId == null && it.clientMessageId.value !in batchMembers &&
                it.submissionState in setOf(LocalSubmissionState.OUTCOME_UNKNOWN, LocalSubmissionState.FAILED)
        }
    }

    /** 用户确认恢复当前会话；先查询原身份，保留原内容及批次归属。 */
    fun retryPendingSubmissions() {
        if (!_state.value.isOnline || !_state.value.historyHydrated) return
        val current = activeThreadId ?: return
        reconcileDeletedRecoveryUnits(current)
        val ready=frozenBatches.values.filter { it.conversationId == current }
        val unsealed=collectingUnits.values.filter { it.first == current }.groupBy { it.first }
        val ordinary = recoverableOrdinaryAtoms(current).toList()
        (ready.flatMap { it.messages } + unsealed.values.flatten().map { it.second }).forEach {
            activeWork.begin(it.clientMessageId.value, current); activeWork.started(it.clientMessageId.value)
        }
        ordinary.forEach { activeWork.begin(it.clientMessageId.value, current); activeWork.started(it.clientMessageId.value) }
        workScope.launch {
            ready.forEach { b -> recoverBatch(b.conversationId, b.messages, b.batchId) }
            unsealed.forEach { (id,units) ->
                if (batcher.hasPending(id)) batcher.flush(id) else recoverBatch(id, units.map { it.second }, null)
            }
            ordinary.forEach { atom -> recoverOrdinary(atom) }
        }
    }

    private suspend fun recoverOrdinary(atom: LocalSendAtom) {
        val threadId = atom.conversationId.value
        if (atom.isDeleted || atomsFor(threadId)[atom.clientMessageId.value]?.isDeleted == true) return
        var querying = true
        var remotelyAccepted = false
        try {
            val query = repository as? com.openandroidintelligence.conversation.ports.MessageOutcomeQuery
                ?: throw java.io.IOException("MESSAGE_QUERY_UNAVAILABLE")
            val known = query.queryMessage(threadId, atom.clientMessageId)
            hydrateSendAtoms(threadId,refresh=true)
            querying = false
            if (known?.isDeleted == true || atomsFor(threadId)[atom.clientMessageId.value]?.isDeleted == true) {
                reconcileDeletedRecoveryUnits(threadId)
                if (activeThreadId == threadId) update { it.copy(timeline=Loadable.Ready(renderTimeline(it.pendingBatch))) }
                else persistCheckpoint()
                return
            }
            val text = atom.parts.joinToString("") { part -> when (part) {
                is com.openandroidintelligence.conversation.model.MessagePart.Text -> part.value
                is com.openandroidintelligence.conversation.model.MessagePart.Command -> part.rawText
                else -> ""
            } }
            var attachmentIds = atom.parts.filterIsInstance<com.openandroidintelligence.conversation.model.MessagePart.Attachment>().map { it.draftId.value }
            flushPendingPersistence()
            val acceptedBeforeRetry=atomsFor(threadId)[atom.clientMessageId.value]
                ?.takeIf { it.submissionState==LocalSubmissionState.ACCEPTED }?.messageId
            val retried=if (known == null && acceptedBeforeRetry == null) retrySubmittedAttachments(threadId,atom)
                else RetriedAttachmentRefs(attachmentIds,emptyList())
            attachmentIds=retried.remoteIds
            if (atomsFor(threadId)[atom.clientMessageId.value]?.isDeleted == true) {
                reconcileDeletedRecoveryUnits(threadId)
                if (activeThreadId == threadId) update { it.copy(timeline=Loadable.Ready(renderTimeline(it.pendingBatch))) }
                return
            }
            val acceptedFromEvent=atomsFor(threadId)[atom.clientMessageId.value]?.takeIf { it.submissionState==LocalSubmissionState.ACCEPTED }?.messageId
            val messageId = known?.id ?: acceptedFromEvent ?: run {
                beginLegacyTurn(threadId)
                if (activeThreadId == threadId) generationFactRevision++
                repository.submitMessage(threadId, OutgoingMessage(atom.clientMessageId, text, attachmentIds)).messageId
            }
            remotelyAccepted = true
            acceptSendAtom(threadId, atom.clientMessageId.value, messageId)
            if (failedSubmission?.clientMessageId == atom.clientMessageId.value) failedSubmission = null
            if (activeThreadId == threadId) {
                val matchingDraft = _state.value.draft == text &&
                    _state.value.attachments.map { attachmentCoordinator?.remoteAttachmentId(it.id.value) } == attachmentIds
                update { state ->
                    val pending = state.pendingBatch.filterNot { it.key == "local_${atom.clientMessageId.value}" }
                    state.copy(timeline = Loadable.Ready(renderTimeline(pending)), pendingBatch = pending,
                        draft = if (matchingDraft) "" else state.draft, composer = ComposerState.EDITING,
                        generation = generationAfterAcceptance(threadId, listOf(atom.clientMessageId.value), state.generation), notice = null)
                }
                _state.value.attachments.filter { attachmentCoordinator?.remoteAttachmentId(it.id.value) in attachmentIds }
                    .map { it.id.value }.plus(retried.localDraftIds).distinct().forEach { releaseSubmittedAttachment(threadId,it) }
            } else persistCheckpoint()
            if (activeThreadId != threadId) retried.localDraftIds.forEach { releaseSubmittedAttachment(threadId,it) }
        } catch (cancelled: CancellationException) {
            if (!querying) persistCancelledSendOutcomes(threadId,listOf(atom.clientMessageId.value))
            throw cancelled
        } catch (cause: Exception) {
            val unknown = querying || remotelyAccepted || cause is java.io.IOException
            changeSendAtom(threadId, atom.clientMessageId.value) { old -> old?.takeIf { it.submissionState != LocalSubmissionState.ACCEPTED }?.copy(
                submissionState = if (unknown) LocalSubmissionState.OUTCOME_UNKNOWN else LocalSubmissionState.FAILED,
                submissionErrorCode = errorCodeOf(cause)) }
            if (activeThreadId == threadId) update { it.copy(
                notice = "${if (unknown) "SEND_OUTCOME_UNKNOWN" else "SEND_FAILED"}:${errorCodeOf(cause)}",
                generation = if (unknown) GenerationState.OUTCOME_UNKNOWN else GenerationState.IDLE,
                timeline = Loadable.Ready(renderTimeline(it.pendingBatch))) }
        } finally {
            activeWork.finishSubmission(atom.clientMessageId.value, atomsFor(threadId)[atom.clientMessageId.value])
        }
    }

    private suspend fun recoverBatch(threadId: String, messages: List<OutgoingMessage>, frozenId: String?) {
        val query = repository as? com.openandroidintelligence.conversation.ports.MessageOutcomeQuery
        if (query == null) {
            if (activeThreadId == threadId) update { it.copy(notice = "SEND_OUTCOME_UNKNOWN:MESSAGE_QUERY_UNAVAILABLE") }
            messages.forEach { activeWork.finishSubmission(it.clientMessageId.value, atomsFor(threadId)[it.clientMessageId.value]) }
            return
        }
        try {
            val liveMessages=messages.filterNot { atomsFor(threadId)[it.clientMessageId.value]?.isDeleted == true }
            val known = liveMessages.map { query.queryMessage(threadId, it.clientMessageId) }
            hydrateSendAtoms(threadId,refresh=true)
            val hasDeletedMember=messages.any { atomsFor(threadId)[it.clientMessageId.value]?.isDeleted == true }
            if (known.all { it != null }) {
                liveMessages.zip(known).forEach { (message, accepted) -> acceptSendAtom(threadId, message.clientMessageId.value, accepted!!.id) }
                frozenId?.let(frozenBatches::remove)
                messages.forEach { collectingUnits.remove(it.clientMessageId.value) }
                persistCheckpoint()
                if (activeThreadId == threadId) reloadTimeline(threadId)
            } else if (hasDeletedMember) {
                reconcileDeletedRecoveryUnits(threadId)
                if (activeThreadId == threadId) update { it.copy(notice="SEND_OUTCOME_UNKNOWN:原批次含已删除消息，只能查询其余成员，禁止重发") }
                else persistCheckpoint()
            } else if (known.any { it != null }) {
                if (activeThreadId == threadId) update { it.copy(notice = "SEND_OUTCOME_UNKNOWN:BATCH_CONFIRMATION_INCOMPLETE") }
            } else {
                val retriedDrafts=mutableListOf<String>()
                val retriedMessages=messages.map { message ->
                    if (message.attachmentIds.isEmpty()) message else {
                        val atom=atomsFor(threadId)[message.clientMessageId.value] ?: error("LOCAL_SEND_ATOM_MISSING")
                        val refs=retrySubmittedAttachments(threadId,atom)
                        retriedDrafts+=refs.localDraftIds
                        message.copy(attachmentIds=refs.remoteIds)
                    }
                }
                if (messages.any { atomsFor(threadId)[it.clientMessageId.value]?.let { atom ->
                    atom.isDeleted || atom.submissionState==LocalSubmissionState.ACCEPTED } == true }) {
                    reconcileDeletedRecoveryUnits(threadId)
                    if (activeThreadId == threadId) update { it.copy(notice="SEND_OUTCOME_UNKNOWN:原批次已有受理或删除事实，请重新查询确认") }
                    return
                }
                submitBatch(scopeFactory(), threadId, retriedMessages)
                if (messages.all { atomsFor(threadId)[it.clientMessageId.value]?.submissionState==LocalSubmissionState.ACCEPTED }) {
                    retriedDrafts.distinct().forEach { releaseSubmittedAttachment(threadId,it) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cause: Exception) {
            if (activeThreadId == threadId) update { it.copy(notice = "SEND_OUTCOME_UNKNOWN:${errorCodeOf(cause)}") }
        } finally {
            messages.forEach { activeWork.finishSubmission(it.clientMessageId.value, atomsFor(threadId)[it.clientMessageId.value]) }
        }
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

    private var threadEpoch = 0L

    private fun beginLegacyTurn(threadId: String?) { threadId?.let(legacyTerminalBarriers::remove) }

    private fun retireLegacyMessage(threadId: String, messageId: String) {
        retiredLegacyMessageIds.add(threadId to messageId)
        while (retiredLegacyMessageIds.size > 4096) retiredLegacyMessageIds.remove(retiredLegacyMessageIds.first())
    }

    /** Retire progress for the exact turn; a final response may have another message id. */
    private fun finishStreaming(
        threadId: String,
        generationId: String?,
        terminal: GenerationState,
        occurredAt: Long = 0L,
        clientMessageId: String? = null,
    ) {
        if (generationId != null) {
            activeWork.terminal(threadId, generationId)
            finishedGenerations.add(threadId to generationId)
            while (finishedGenerations.size > 4096) finishedGenerations.remove(finishedGenerations.first())
        }
        val activeGeneration = (repository as? com.openandroidintelligence.conversation.ports.GenerationTracker)?.generationId?.value
        val allowLegacy = generationId == null || activeGeneration == null || activeGeneration == generationId
        if (allowLegacy) {
            val terminalTime = occurredAt.takeIf { it > 0L }
                ?: mirrored.values.filter { it.sender == "assistant" }.maxOfOrNull { it.timestamp } ?: 0L
            legacyTerminalBarriers.remove(threadId)
            legacyTerminalBarriers[threadId] = terminalTime
            while (legacyTerminalBarriers.size > 4096) legacyTerminalBarriers.remove(legacyTerminalBarriers.keys.first())
            mirrored.values.filter { it.sender == "assistant" && it.generationId == null }
                .forEach { retireLegacyMessage(threadId,it.id) }
        }
        val finals = mirrored.values.filter { it.sender == "assistant" && it.state == "CONFIRMED" }
        mirrored.values.filter { it.sender == "assistant" && it.state == "STREAMING" &&
            (it.generationId == generationId || (allowLegacy && it.generationId == null))
        }.toList().forEach { streaming ->
            if (finals.any { final -> generationId != null && final.generationId == generationId }) {
                mirrored.remove(streaming.id)
                mirroredRevisions.remove(streaming.id)
            } else mirrored[streaming.id] = streaming.copy(state=when (terminal) {
                GenerationState.CANCELLED -> "CANCELLED"
                GenerationState.FAILED -> "FAILED"
                else -> "CONFIRMED"
            })
        }
        disarmReplyWatchdogFor(
            conversationId = threadId,
            generationId = generationId,
            clientMessageId = clientMessageId,
            belongsToCurrentTurn = allowLegacy && isInCurrentTurn(occurredAt),
        )
        if (terminal in setOf(GenerationState.COMPLETED, GenerationState.CANCELLED, GenerationState.FAILED)) {
            schedulePendingDeliveryDrain(threadId)
        }
    }

    private suspend fun loadPendingDeliveries(threadId: String) {
        try {
            val deliveries = repository.pendingDeliveries(threadId)
            if (activeThreadId == threadId) {
                update { state -> state.copy(pendingDeliveries = deliveries) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The live scheduler remains authoritative; a failed read must not
            // make queued text disappear from the durable send atom.
        }
    }

    private fun schedulePendingDeliveryDrain(threadId: String) {
        if (closed || pendingDeliveryDrainJobs[threadId]?.isActive == true) return
        pendingDeliveryDrainJobs[threadId] = workScope.launch {
            try {
                repository.retryPendingDeliveries(threadId)
                loadPendingDeliveries(threadId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (activeThreadId == threadId) {
                    update { it.copy(notice = "QUEUE_DRAIN_FAILED:${errorCodeOf(failure)}") }
                }
            } finally {
                pendingDeliveryDrainJobs.remove(threadId)
            }
        }
    }

    fun stopGeneration() {
        if (closed || _state.value.generation == GenerationState.CANCEL_REQUESTED) return
        val threadId = activeThreadId ?: return
        val epoch = threadEpoch
        val tracker = repository as? com.openandroidintelligence.conversation.ports.GenerationTracker
        val generationId = tracker?.generationId?.value
        if (generationId == null) {
            update { it.copy(notice = "STOP_UNAVAILABLE:NO_GENERATION") }
            return
        }
        val key = threadId to generationId
        if (key in requestedCancellations) return
        observeGenerationIdentity(generationId)
        val pending = PendingCancellation(generationFactRevision)
        requestedCancellations[key] = pending
        update { it.copy(generation = GenerationState.CANCEL_REQUESTED) }
        workScope.launch {
            var outcome = com.openandroidintelligence.conversation.ports.CancelGenerationOutcome.OUTCOME_UNKNOWN
            var notice: String? = null
            try {
                val result = repository.cancelGeneration(threadId,generationId,"req_" + UUID.randomUUID().toString().replace("-", ""))
                outcome = result.outcome
                notice = result.message
                if (outcome == com.openandroidintelligence.conversation.ports.CancelGenerationOutcome.CANCELLED) {
                    rememberCancelledGeneration(threadId, generationId)
                    // Also commit acknowledgements for a conversation the user has since left.
                    flushPendingPersistence()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) { notice = errorCodeOf(cause) }
            finally {
                requestedCancellations.remove(key)
                val currentId = tracker.generationId.value
                if (!closed && activeThreadId == threadId && threadEpoch == epoch &&
                    (currentId == null || currentId == generationId) && generationFactRevision == pending.factRevision) {
                    val receipt = pending.terminalReceipt
                    val confirmed = key in cancelledGenerations
                    val terminal = when {
                        confirmed -> GenerationState.CANCELLED
                        receipt?.status == com.openandroidintelligence.conversation.ports.AgentMessageStatus.FAILED -> GenerationState.FAILED
                        receipt?.status == com.openandroidintelligence.conversation.ports.AgentMessageStatus.COMPLETED -> GenerationState.COMPLETED
                        pending.trackerTerminal != null -> pending.trackerTerminal!!
                        outcome == com.openandroidintelligence.conversation.ports.CancelGenerationOutcome.ALREADY_COMPLETED -> GenerationState.COMPLETED
                        outcome == com.openandroidintelligence.conversation.ports.CancelGenerationOutcome.UNSUPPORTED -> GenerationState.UNSUPPORTED
                        else -> GenerationState.OUTCOME_UNKNOWN
                    }
                    if (terminal in setOf(GenerationState.CANCELLED,GenerationState.COMPLETED,GenerationState.FAILED)) {
                        finishStreaming(threadId,generationId,terminal,receipt?.occurredAt ?: 0L,receipt?.clientMessageId?.value)
                    }
                    update { state -> state.copy(timeline=Loadable.Ready(renderTimeline(state.pendingBatch)), generation=terminal,
                        notice=when {
                            confirmed -> if (outcome == com.openandroidintelligence.conversation.ports.CancelGenerationOutcome.CANCELLED) notice else null
                            receipt?.status == com.openandroidintelligence.conversation.ports.AgentMessageStatus.FAILED -> "AGENT_MESSAGE_FAILED:${receipt.errorCode?.wireValue}"
                            terminal == GenerationState.COMPLETED -> null
                            terminal == GenerationState.FAILED -> state.notice
                            else -> notice
                        }) }
                } else if (activeThreadId == threadId) {
                    // Only the old row may change; a newer turn owns the generation and composer.
                    update { state -> state.copy(timeline=Loadable.Ready(renderTimeline(state.pendingBatch))) }
                }
            }
        }
    }

    fun clearRetainedMediaPreviews() {
        historicalAttachments.evictAll()
        // 退出账号/释放界面时原图同样不能留在堆里：它比缩略图大一个量级。
        originalImages.evictAll()
        val rows = renderTimeline()
        update { it.copy(timeline=if(rows.isEmpty()) Loadable.Empty else Loadable.Ready(rows)) }
    }

    private fun invalidateMediaTask(key: HistoricalAttachmentKey) {
        mediaEpochs[key]=Math.addExact(mediaEpochs[key] ?: 0L,1L)
        mediaJobs.remove(key)?.cancel(); downloadJobs.remove(key)?.cancel(); originalJobs.remove(key)?.cancel()
        localArchiveJobs.keys.filter { attachmentCoordinator?.remoteAttachmentId(it) == key.attachmentId }.toList()
            .forEach { localArchiveJobs.remove(it)?.cancel() }
        historicalAttachments.remove(key); mediaPresentation.remove(key)
        // LruCache 的淘汰回调会把字节清零；这里只是提前释放当前打开过的原图。
        originalImages.remove(key)
    }
    private fun mediaTaskCurrent(key: HistoricalAttachmentKey,epoch: Long): Boolean {
        val owner=mediaOwners[key]
        val deletedOwner=owner?.let { mediaDeletions[MediaMessageKey(key.scope,key.threadId,it)] } != null
        return !closed && key !in deletedMedia && !deletedOwner && scopeFactory()==key.scope && (mediaEpochs[key] ?: 0L)==epoch
    }

    private fun cancelMessageMedia(threadId: String,messageId: String,revision: Long? = null) {
        val messageKey=MediaMessageKey(scopeFactory(),threadId,messageId)
        val old=mediaDeletions[messageKey]
        if (old != null && (old.revision == null || (revision != null && revision <= old.revision))) return
        if (revision != null && revision < (mediaContentRevisions[messageKey] ?: -1L)) return
        mediaDeletions[messageKey]=MediaDeletion(revision)
        if (revision != null) mediaContentRevisions[messageKey]=revision
        mediaOwners.filter { it.key.scope==messageKey.scope && it.key.threadId == threadId && it.value == messageId }.keys.toList().forEach { key ->
            deletedMedia.add(key)
            invalidateMediaTask(key)
        }
    }

    /** 附件保存独立于消息读取；已保存原件优先读本地，队列不阻塞会话首屏和事件确认。 */
    private fun scheduleHistoricalMedia(threadId: String,messages: List<TimelineMessage>) {
        val port = media ?: return
        messages.forEach { message ->
            if (message.isDeleted) { cancelMessageMedia(threadId,message.id); return@forEach }
            val messageKey=MediaMessageKey(scopeFactory(),threadId,message.id)
            val revision=message.contentRevision
            val barrier=mediaDeletions[messageKey]
            if (snapshotDeletedIdsByThread[threadId]?.contains(message.id)==true ||
                (barrier != null && (barrier.revision == null || revision == null || revision <= barrier.revision))) return@forEach
            if (revision != null && revision < (mediaContentRevisions[messageKey] ?: -1L)) return@forEach
            if (barrier != null) {
                mediaDeletions.remove(messageKey)
                val ids=message.parts.filterIsInstance<com.openandroidintelligence.conversation.model.MessagePart.Attachment>().map { it.draftId.value }.toSet()
                mediaOwners.filter { it.key.scope==messageKey.scope && it.key.threadId==threadId && it.value==message.id && it.key.attachmentId in ids }
                    .keys.toList().forEach { key -> deletedMedia.remove(key); invalidateMediaTask(key) }
            }
            if (revision != null) mediaContentRevisions[messageKey]=revision
            message.parts.filterIsInstance<com.openandroidintelligence.conversation.model.MessagePart.Attachment>().forEach attachmentLoop@ { attachment ->
                val key = historicalAttachmentKey(threadId,attachment.draftId.value)
                mediaOwners[key] = message.id
                if (key in deletedMedia || mediaJobs[key]?.isActive == true || closed) return@attachmentLoop
                val epoch=mediaEpochs[key] ?: 0L
                val localDraft = attachmentCoordinator?.restoredDrafts()?.firstOrNull {
                    attachmentCoordinator.remoteAttachmentId(it.id.value) == attachment.draftId.value
                }
                if (localDraft != null) {
                    if (localArchiveJobs[localDraft.id.value]?.isActive != true) localArchiveJobs[localDraft.id.value] = workScope.launch {
                        val atoms=loadSendAtomsOnIo(threadId)
                        val accepted=atoms.any { atom ->
                            atom.submissionState == LocalSubmissionState.ACCEPTED &&
                                ((message.clientMessageId != null && atom.clientMessageId == message.clientMessageId) || atom.messageId == message.id)
                        }
                        if (accepted) releaseSubmittedAttachment(threadId,localDraft.id.value)
                        else if (port.localMetadata(threadId,attachment.draftId.value) == null) {
                            // 本地未知结果仍有重试义务：可以保存副本，但不能提前释放唯一上传暂存。
                            try { attachmentCoordinator.archiveSubmitted(threadId,localDraft.id.value,port) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                if (mediaTaskCurrent(key,epoch)) {
                                    mediaPresentation[key]=(mediaPresentation[key] ?: MediaPresentation()).copy(
                                        localState=com.openandroidintelligence.conversation.model.AttachmentLocalState.FAILED,localError=errorCodeOf(failure))
                                    repaintMedia(threadId)
                                }
                                return@launch
                            }
                        }
                        if (mediaTaskCurrent(key,epoch)) reloadLocalMedia(threadId,message,attachment,port,key,epoch)
                    }
                    return@attachmentLoop
                }
                mediaJobs[key] = workScope.launch {
                    mediaParallelism.withPermit {
                        try {
                            val metadata = port.localMetadata(threadId,attachment.draftId.value)
                                ?: port.ensureLocal(threadId,attachment.draftId.value)
                            if (mediaTaskCurrent(key,epoch)) publishLocalMedia(threadId,message,attachment,metadata,port,key,epoch)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            if (!mediaTaskCurrent(key,epoch)) return@withPermit
                            val error = errorCodeOf(failure)
                            val unavailable = error.contains("REMOTE_UNAVAILABLE") || error.contains("NOT_FOUND") || error.contains("EXPIRED")
                            mediaPresentation[key]=(mediaPresentation[key] ?: MediaPresentation()).copy(
                                localState=if (unavailable) com.openandroidintelligence.conversation.model.AttachmentLocalState.UNAVAILABLE
                                    else com.openandroidintelligence.conversation.model.AttachmentLocalState.FAILED,localError=error)
                            repaintMedia(threadId)
                        }
                    }
                }
            }
        }
    }

    private suspend fun reloadLocalMedia(threadId: String,message: TimelineMessage,
        attachment: com.openandroidintelligence.conversation.model.MessagePart.Attachment,
        port: com.openandroidintelligence.conversation.ports.HistoricalMediaPort,key: HistoricalAttachmentKey,epoch: Long = mediaEpochs[key] ?: 0L) {
        port.localMetadata(threadId,attachment.draftId.value)?.let { publishLocalMedia(threadId,message,attachment,it,port,key,epoch) }
    }

    private suspend fun publishLocalMedia(threadId: String,message: TimelineMessage,
        attachment: com.openandroidintelligence.conversation.model.MessagePart.Attachment,
        metadata: com.openandroidintelligence.conversation.ports.HistoricalMediaMetadata,
        port: com.openandroidintelligence.conversation.ports.HistoricalMediaPort,key: HistoricalAttachmentKey,epoch: Long = mediaEpochs[key] ?: 0L) {
        val preview = withContext(mediaDispatcher) { port.preview(threadId,attachment.draftId.value) }
        if (!mediaTaskCurrent(key,epoch)) { preview?.fill(0); return }
        rememberHistoricalAttachment(threadId,com.openandroidintelligence.conversation.model.TimelineAttachment(
            attachment.draftId.value,metadata.filename,metadata.mediaType,preview,true),message.clientMessageId,message.id)
        mediaPresentation[key]=(mediaPresentation[key] ?: MediaPresentation()).copy(
            localState=com.openandroidintelligence.conversation.model.AttachmentLocalState.SAVED,localError=null)
        repaintMedia(threadId)
    }

    private fun repaintMedia(threadId: String) {
        if (!closed && activeThreadId == threadId) update { it.copy(timeline=Loadable.Ready(renderTimeline(it.pendingBatch))) }
    }

    /** 完整性元数据只来自已经校验的原草稿，显示用的缩略图不充当文件原件。 */
    private fun verifiedAttachmentPart(remoteId: String,filename: String,mediaType: String,
        draft: com.openandroidintelligence.conversation.model.AttachmentDraft?): com.openandroidintelligence.conversation.model.MessagePart.Attachment {
        val verified=draft?.takeIf { it.state == AttachmentState.VERIFIED }
        val hash=verified?.sha256?.removePrefix("sha256:")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let { "sha256:$it" }
        return com.openandroidintelligence.conversation.model.MessagePart.Attachment(AttachmentDraftId(remoteId),filename,mediaType,
            verified?.sizeBytes?.takeIf { hash != null },hash)
    }

    /**
     * 当前宿主是否装配了下载端口。
     *
     * 没有端口时“保存至本地”按钮不该出现：Dialog 会遮住宿主的 Snackbar，
     * 点了只弹一个用户看不到的 notice，等同于假装保存。
     */
    val downloadsSupported: Boolean get() = downloads != null

    fun downloadAttachment(attachmentId: String) {
        val threadId = activeThreadId ?: return
        val port = downloads ?: run { update { it.copy(notice="DOWNLOAD_UNAVAILABLE") }; return }
        val key = historicalAttachmentKey(threadId,attachmentId)
        if (closed || key in deletedMedia || downloadJobs[key]?.isActive == true) return
        val epoch=mediaEpochs[key] ?: 0L
        downloadJobs[key] = workScope.launch {
            mediaPresentation[key]=(mediaPresentation[key] ?: MediaPresentation()).copy(
                downloadState=com.openandroidintelligence.conversation.model.AttachmentDownloadState.DOWNLOADING,downloadError=null)
            repaintMedia(threadId)
            try {
                attachmentCoordinator?.restoredDrafts()?.firstOrNull {
                    attachmentCoordinator.remoteAttachmentId(it.id.value) == attachmentId
                }?.let { draft ->
                    val atoms=loadSendAtomsOnIo(threadId)
                    val accepted=atoms.any { atom -> atom.submissionState == LocalSubmissionState.ACCEPTED &&
                        atom.parts.any { part -> part is com.openandroidintelligence.conversation.model.MessagePart.Attachment && part.draftId.value == attachmentId } }
                    if (accepted) releaseSubmittedAttachment(threadId,draft.id.value)
                    else attachmentCoordinator.archiveSubmitted(threadId,draft.id.value,media ?: error("LOCAL_MEDIA_UNAVAILABLE"))
                }
                val result = port.download(threadId,attachmentId)
                if (!mediaTaskCurrent(key,epoch)) return@launch
                mediaPresentation[key]=(mediaPresentation[key] ?: MediaPresentation()).copy(
                    downloadState=com.openandroidintelligence.conversation.model.AttachmentDownloadState.SAVED,downloadError=null)
                repaintMedia(threadId)
                val recoveryNotice=if (result.recoveryWarningCode == null) "" else "；上次中断的下载尚未恢复，请稍后重试"
                update { it.copy(notice="已保存到下载/OpenAndroidIntelligence：${result.filename}$recoveryNotice") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (!mediaTaskCurrent(key,epoch)) return@launch
                mediaPresentation[key]=(mediaPresentation[key] ?: MediaPresentation()).copy(
                    downloadState=com.openandroidintelligence.conversation.model.AttachmentDownloadState.FAILED,downloadError=errorCodeOf(failure))
                repaintMedia(threadId)
            }
        }
    }

    /**
     * 按需加载一张历史附件的原图。
     *
     * 缩略图随消息同步，原图却可能有几十 MB：只有用户真的打开大图预览时才去取。
     * 读取有 24MB 硬上限，解码后最长边压到 2048px、JPEG 85 再进缓存；缓存只保留
     * 最近 [originalImageCacheSize] 张，淘汰时清零。整个任务与缩略图/下载共用
     * [mediaEpochs] 与 [mediaTaskCurrent]：附件被删除、账号切换或控制器关闭之后，
     * 迟到的原图不能把界面重新点亮。
     */
    fun requestOriginalImage(attachmentId: String) {
        val threadId = activeThreadId ?: return
        if (closed) return
        val key = historicalAttachmentKey(threadId, attachmentId)
        // 已缓存的原图直接复用：重复打开预览不该再读一遍磁盘。
        if (key in deletedMedia || originalImages[key] != null || originalJobs[key]?.isActive == true) return
        val port = media
        val epoch = mediaEpochs[key] ?: 0L
        if (port == null) {
            // 没有媒体端口就没有原件可取：明确失败，而不是让界面一直转圈。
            originalJobs.remove(key)?.cancel()
            mediaPresentation[key] = (mediaPresentation[key] ?: MediaPresentation()).copy(
                originalState = OriginalImageState.FAILED,
                originalError = "LOCAL_MEDIA_UNAVAILABLE",
            )
            repaintMedia(threadId)
            return
        }
        mediaPresentation[key] = (mediaPresentation[key] ?: MediaPresentation()).copy(
            originalState = OriginalImageState.LOADING,
            originalError = null,
        )
        repaintMedia(threadId)
        val job = workScope.launch {
            // 端口用「原件代数」描述这一次读取属于哪一代；不实现的端口返回 null，不阻塞加载。
            val portEpoch = runCatching { port.originalEpoch(threadId, attachmentId) }.getOrNull()
            try {
                val raw = withContext(mediaDispatcher) {
                    // 先确保原件在本地，再打开它；顺序与下载链路一致。
                    port.ensureLocal(threadId, attachmentId)
                    port.openOriginal(threadId, attachmentId).use { input -> readOriginalBytes(input) }
                }
                val compressed = try {
                    withContext(mediaDispatcher) { originalImageCompressor(raw) }
                        ?: error("ORIGINAL_DECODE_FAILED")
                } finally {
                    // 原图字节可能带 EXIF/GPS；压缩成展示图之后立即清零，不在堆里留原件。
                    raw.fill(0)
                }
                if (!mediaTaskCurrent(key, epoch)) return@launch
                val commit = {
                    originalImages.put(key, compressed)
                    mediaPresentation[key] = (mediaPresentation[key] ?: MediaPresentation()).copy(
                        originalState = OriginalImageState.READY,
                        originalError = null,
                    )
                    repaintMedia(threadId)
                }
                if (portEpoch == null) {
                    commit()
                } else if (runCatching { port.withOriginalEpoch(threadId, attachmentId, portEpoch) { commit() } }.isFailure) {
                    // 读取期间原件被替换/删除：这一份已经不属于当前代，必须丢弃。
                    throw IllegalStateException("ORIGINAL_EPOCH_CHANGED")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!mediaTaskCurrent(key, epoch)) return@launch
                mediaPresentation[key] = (mediaPresentation[key] ?: MediaPresentation()).copy(
                    originalState = OriginalImageState.FAILED,
                    originalError = errorCodeOf(failure),
                )
                repaintMedia(threadId)
            }
        }
        originalJobs[key] = job
        // 完成后从在途表里移除，避免一张张原图把任务表涨成泄漏；
        // 只移除自己，迟到的回调不能删掉后来者。
        job.invokeOnCompletion { if (originalJobs[key] === job) originalJobs.remove(key) }
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
        refreshThreads()
        activeThreadId?.let(::reloadTimeline)
    }

    /**
     * 网关投递状态（平台事件流）：原生会话下工作台只订阅 Agent 会话流，收不到
     * `conversation.message.status`，于是"等待 Agent 接收"必须等到首个 token 才消失。
     *
     * 这里只把非终态的 `delivered` 提前显示；**不写 statusRevision**，终态仍由会话流
     * 负责，避免同一个终态被两路事件处理两次。
     */
    fun onGatewayDeliveryStatus(status: VerifiedConversationEvent.MessageStatus) {
        if (closed || status.status != com.openandroidintelligence.conversation.ports.AgentMessageStatus.DELIVERED) return
        workScope.launch {
            val threadId = status.conversationId.value
            if (threadId != activeThreadId) return@launch
            if (atomsFor(threadId).isEmpty()) return@launch
            var promoted = false
            changeSendAtom(threadId, status.clientMessageId.value) { old ->
                if (old != null &&
                    old.submissionState == LocalSubmissionState.ACCEPTED &&
                    old.status == com.openandroidintelligence.conversation.ports.AgentMessageStatus.QUEUED
                ) {
                    promoted = true
                    old.identified(status.messageId).copy(
                        status = com.openandroidintelligence.conversation.ports.AgentMessageStatus.DELIVERED,
                    )
                } else old
            }
            if (!promoted) return@launch
            update { state ->
                if (state.timeline is Loadable.Ready || state.timeline is Loadable.Empty) {
                    state.copy(timeline = Loadable.Ready(renderTimeline(state.pendingBatch)))
                } else {
                    state
                }
            }
        }
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
        eventJob = workScope.launch {
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
                        if (cause is EventSessionRejectedException || cause is SessionRecoveryPausedException) {
                            false
                        } else {
                            delay(1000L)
                            true
                        }
                    }
                }
                .catch { cause ->
                    // The client already attempted authenticated recovery. Keep
                    // the workbench and wait for a new session or explicit wakeup.
                    if (cause !is EventSessionRejectedException && cause !is SessionRecoveryPausedException) throw cause
                }
                .collect { event ->
                    var retryingCommit = false
                    var storageFailures = 0
                    while (isActive) {
                        try {
                            applyConversationEvent(event)
                            // 重试时内存修订可能已推进，投影会提前返回；仍须补齐保存和事件标记。
                            if (retryingCommit && event.eventId !in handledEventIds) commitHandledEvent(event.eventId)
                            break
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            storageFailures++
                            val retryable = failure is java.io.IOException && storageFailures < 4
                            _state.update { it.copy(
                                notice = if (failure is java.io.IOException) "LOCAL_SAVE_FAILED:${errorCodeOf(failure)}" else "EVENT_APPLY_FAILED:${errorCodeOf(failure)}",
                                streamHealth = if (retryable) com.openandroidintelligence.conversation.model.StreamHealth.RECONNECTING
                                    else com.openandroidintelligence.conversation.model.StreamHealth.FAILED) }
                            // 仅瞬时存储错误保留同一帧；最多四次，退出界面会取消等待。
                            if (!retryable) throw failure
                            retryingCommit = true
                            delay((250L shl (storageFailures - 1)).coerceAtMost(1000L))
                        }
                    }
                }
        }
    }

    private suspend fun commitHandledEvent(eventId: String) {
        if (eventId.isNotBlank()) committingEventIds.add(eventId)
        try {
            flushPendingPersistence()
            markEventHandled(eventId)
        } finally {
            committingEventIds.remove(eventId)
        }
    }

    private suspend fun applyConversationEvent(event: VerifiedConversationEvent) {
        val eventContext = currentCoroutineContext()
        eventContext.ensureActive()
        if (event is VerifiedConversationEvent.ConversationsChanged) {
            if (event.eventId.isNotBlank() && event.eventId in handledEventIds) return
            if (event.deleted) event.conversationId?.value?.let { id ->
                deletedThreads.add(id)
                sendAtomsByThread.remove(id)
                executionFacts.keys.removeAll { it.first == id }
                if (activeThreadId == id) leaveManagedThread(id)
            }
            refreshThreads()
            commitHandledEvent(event.eventId)
            return
        }
        val eventThreadId=when (event) {
            is VerifiedConversationEvent.TimelineUpsert -> event.message.conversationId?.value
            is VerifiedConversationEvent.TimelineTombstoned -> event.conversationId?.value
            is VerifiedConversationEvent.MessageStatus -> event.conversationId.value
            is VerifiedConversationEvent.ExecutionStepsUpdated -> event.conversationId.value
            is VerifiedConversationEvent.TitleUpdated -> event.conversationId.value
            is VerifiedConversationEvent.MessageAccepted -> event.conversationId?.value
            is VerifiedConversationEvent.PendingDeliveryUpdated -> event.delivery.conversationId.value
            else -> null
        }
        if (eventThreadId in deletedThreads) return
        // 未携带线程身份的正文或删除事件不能借用当前屏幕的线程身份。
        if ((event is VerifiedConversationEvent.TimelineUpsert && event.message.conversationId == null) ||
            (event is VerifiedConversationEvent.TimelineTombstoned && event.conversationId == null)) return
        applySendEvent(event)
        val currentActiveId = activeThreadId ?: run {
            refreshThreads()
            return
        }
        // Recorded only once the event can actually be routed: an
        // event that arrived while no thread was open must still be
        // applied when one is, not be swallowed as already seen.
        if (event.eventId.isNotBlank() && handledEventIds.contains(event.eventId)) return
        when (event) {
            is VerifiedConversationEvent.ConversationsChanged -> Unit // handled before timeline routing
            is VerifiedConversationEvent.ExecutionStepsUpdated -> {
                if (event.conversationId.value == currentActiveId) update { state ->
                    state.copy(timeline = Loadable.Ready(renderTimeline(state.pendingBatch)))
                }
            }
            is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.MessageAccepted -> {
                val eventConvId = event.conversationId?.value
                val correlationId = event.correlationId
                update { state ->
                    state.copy(pendingDeliveries = state.pendingDeliveries.filterNot {
                        it.clientMessageId.value == correlationId
                    })
                }
                if (eventConvId != null && correlationId.isNotBlank() && event.messageId.isNotBlank()) {
                    // 受理早期只能用客户端占位 id；宿主报出真实 userMessageId 后升级本地身份，
                    // 后续终态事件就不会再与本地身份分叉。
                    changeSendAtom(eventConvId, correlationId) { old ->
                        val identified = old?.takeIf { it.messageId == it.clientMessageId.value }
                            ?.identified(event.messageId) ?: old
                        // 会话流已经确认这条消息属实：先把提示从"等待接收"推进到"已接收"，
                        // 不必等首个 token（Hermes 一轮可能要思考数秒到数十秒）。
                        identified?.let { atom ->
                            if (event.agentReceived && atom.submissionState == LocalSubmissionState.ACCEPTED &&
                                atom.status == com.openandroidintelligence.conversation.ports.AgentMessageStatus.QUEUED
                            ) {
                                atom.copy(status = com.openandroidintelligence.conversation.ports.AgentMessageStatus.DELIVERED)
                            } else atom
                        }
                    }
                }
                if (eventConvId == currentActiveId && correlationId.isNotBlank()) {
                    update { state ->
                        val pending = state.pendingBatch.filterNot {
                            it.key == "local_$correlationId" || it.key == correlationId
                        }
                        state.copy(
                            pendingBatch = pending,
                            timeline = if (state.timeline is Loadable.Ready || state.timeline is Loadable.Empty) {
                                Loadable.Ready(renderTimeline(pending))
                            } else state.timeline,
                        )
                    }
                }
            }

            is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.MessageStatus -> {
                if (event.conversationId.value != currentActiveId &&
                    event.status in setOf(
                        com.openandroidintelligence.conversation.ports.AgentMessageStatus.COMPLETED,
                        com.openandroidintelligence.conversation.ports.AgentMessageStatus.FAILED,
                    )
                ) {
                    schedulePendingDeliveryDrain(event.conversationId.value)
                }
                if (event.conversationId.value != currentActiveId) {
                    refreshThreads()
                    return
                }
                val previousRevision = messageStatusRevisions[event.messageId]
                if (previousRevision != null && event.revision <= previousRevision) return
                val receipt = MessageStatusReceipt(event.status, event.revision, event.errorCode)
                messageStatusRevisions[event.messageId] = event.revision
                messageStatusesById[event.messageId] = receipt
                messageStatusesByClientId[event.clientMessageId.value] = receipt
                mirrored[event.messageId]?.let { existing ->
                    mirrored[event.messageId] = existing.copy(
                        state = event.status.wireValue,
                        errorCode = event.errorCode?.wireValue,
                        generationId = event.generationId ?: existing.generationId,
                    )
                }
                val receiptGeneration = event.generationId ?: messageGenerations[currentActiveId to event.messageId]?.id
                    ?: messageGenerations[currentActiveId to event.clientMessageId.value]?.id
                val pendingCancel = requestedCancellations[currentActiveId to receiptGeneration]
                if (event.status in setOf(com.openandroidintelligence.conversation.ports.AgentMessageStatus.FAILED,
                        com.openandroidintelligence.conversation.ports.AgentMessageStatus.COMPLETED)) pendingCancel?.terminalReceipt = event
                val cancelling = isCancelledMessage(currentActiveId, event.generationId, event.messageId, event.clientMessageId.value)
                val cancelled = isCancelledMessage(currentActiveId, event.generationId, event.messageId, event.clientMessageId.value, confirmedOnly=true)
                val terminal = when (event.status) {
                    com.openandroidintelligence.conversation.ports.AgentMessageStatus.COMPLETED -> GenerationState.COMPLETED
                    com.openandroidintelligence.conversation.ports.AgentMessageStatus.FAILED ->
                        if (cancelled) GenerationState.CANCELLED else if (cancelling) null else GenerationState.FAILED
                    else -> null
                }
                val currentGeneration = (repository as? com.openandroidintelligence.conversation.ports.GenerationTracker)?.generationId?.value
                val settlesCurrent = receiptGeneration == null || currentGeneration == null || currentGeneration == receiptGeneration
                if (terminal == null || settlesCurrent) observeGenerationIdentity(receiptGeneration)
                if (terminal != null) finishStreaming(
                    currentActiveId,receiptGeneration,terminal,event.occurredAt,event.clientMessageId.value,
                )
                val localKey = "local_${event.clientMessageId.value}"
                update { state ->
                    val pending = state.pendingBatch.map { row ->
                        if (row.key == localKey) {
                            row.copy(
                                messageStatus = event.status,
                                messageStatusErrorCode = event.errorCode,
                            )
                        } else row
                    }
                    state.copy(
                        timeline = if (state.timeline is Loadable.Ready || state.timeline is Loadable.Empty) {
                            Loadable.Ready(renderTimeline(pending))
                        } else state.timeline,
                        pendingBatch = pending,
                        generation = if (terminal != null && settlesCurrent) terminal else state.generation,
                        notice = if (settlesCurrent && !cancelling && event.status == com.openandroidintelligence.conversation.ports.AgentMessageStatus.FAILED) {
                            "AGENT_MESSAGE_FAILED:${event.errorCode?.wireValue}"
                        } else if (settlesCurrent && state.notice?.startsWith("AGENT_MESSAGE_FAILED:") == true) {
                            null
                        } else state.notice,
                    )
                }
            }

            is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.TimelineUpsert -> {
                if (event.message.state == "STREAMING" && event.message.generationId?.let { currentActiveId to it in finishedGenerations } == true) return
                val message = withLatestMessageStatus(event.message)
                val eventConvId = message.conversationId?.value
                if (eventConvId != null && eventConvId != currentActiveId) {
                    refreshThreads()
                    return
                }
                if (message.isDeleted || snapshotDeletedIdsByThread[currentActiveId]?.contains(message.id) == true) {
                    mirrored.remove(message.id)
                    mirroredRevisions.remove(message.id)
                    update { it.copy(timeline=Loadable.Ready(renderTimeline(it.pendingBatch))) }
                    commitHandledEvent(event.eventId)
                    return
                }
                if (message.sender == "assistant" && message.state == "STREAMING" && message.generationId == null &&
                    ((currentActiveId to message.id) in retiredLegacyMessageIds || currentActiveId in legacyTerminalBarriers)) {
                    retireLegacyMessage(currentActiveId,message.id)
                    return
                }
                val previousRevision = mirroredRevisions[message.id]
                // A reply newer than the thread's pre-command state is
                // the Agent's turn ending.
                maybeArmCreationGrace(eventConvId, message)
                if (previousRevision != null && event.revision < previousRevision) {
                    return
                }
                if (message.sender == "user" && currentActiveId in legacyTerminalBarriers &&
                    message.timestamp > legacyTerminalBarriers.getValue(currentActiveId) &&
                    message.id !in mirrored && message.clientMessageId?.value?.let { it in atomsFor(currentActiveId) } != true) {
                    beginLegacyTurn(currentActiveId)
                }
                var matchedCurrentTurn = false
                if (message.sender == "assistant") {
                    val messageText = messageTextOf(message)
                    if (message.state == "CONFIRMED") {
                        matchedCurrentTurn = pruneConfirmedAssistantDuplicates(message)
                        disarmReplyWatchdogFor(
                            currentActiveId, message.generationId,
                            clientMessageId = message.clientMessageId?.value,
                            belongsToCurrentTurn = matchedCurrentTurn,
                        )
                    } else if (message.state == "STREAMING") {
                        disarmReplyWatchdogFor(
                            currentActiveId, message.generationId,
                            clientMessageId = message.clientMessageId?.value,
                            belongsToCurrentTurn = isInCurrentTurn(message.timestamp),
                        )
                        // If a confirmed assistant message with this content already exists, skip adding this late streaming chunk
                        if (isAlreadyCoveredByConfirmedReply(message, messageText)) {
                            return
                        }
                        observeGenerationIdentity(message.generationId)
                        // Prune any other older streaming assistant message with different id
                        val oldStreamingKeys = mirrored.filterValues { it.sender == "assistant" && it.state == "STREAMING" && it.id != message.id &&
                            !haveDistinctNativeAssistantIds(it, message) &&
                            (it.generationId == null || message.generationId == null || it.generationId == message.generationId) }.keys.toList()
                        for (k in oldStreamingKeys) {
                            mirrored.remove(k)
                            mirroredRevisions.remove(k)
                        }
                    }
                }
                if (message.sender == "user" || message.sender == "assistant") {
                    if (message.sender == "user") {
                        val clientId = message.clientMessageId?.value
                        if (clientId != null) {
                            // 同一 clientMessageId 只保留一行：受理时写入的占位行与随后到达的
                            // 服务端行可能是不同 id，两行同时存在就会在回复下方多出重复气泡。
                            mirrored.entries.filter { (key, row) ->
                                key != message.id && row.sender == "user" &&
                                    row.clientMessageId?.value == clientId
                            }.map { it.key }.forEach { stale ->
                                mirrored.remove(stale)
                                mirroredRevisions.remove(stale)
                            }
                        }
                    }
                    mirrored[message.id] = message
                    mirroredRevisions[message.id] = event.revision
                }
                if (message.sender == "assistant" &&
                    (message.state == "STREAMING" || message.state == "CONFIRMED")
                ) {
                    promotePromptsDelivered(currentActiveId, message)
                }
                update { state ->
                    if (!eventContext.isActive) return@update state
                    state.copy(
                        timeline = if (state.timeline is Loadable.Ready || state.timeline is Loadable.Empty) {
                            Loadable.Ready(renderTimeline(state.pendingBatch))
                        } else {
                            state.timeline
                        },
                        generation = if (message.sender == "assistant" && message.state == "STREAMING") {
                            GenerationState.RUNNING
                        } else if (message.sender == "assistant" && message.state == "CONFIRMED") {
                            if (message.generationId == null && matchedCurrentTurn && (state.generation == GenerationState.RUNNING || state.generation == GenerationState.QUEUED)) {
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
                if (eventConvId == currentActiveId && event.revision >= (mirroredRevisions[event.messageId] ?: 0L)) {
                    mirrored.remove(event.messageId)
                    mirroredRevisions.remove(event.messageId)
                    messageStatusRevisions.remove(event.messageId)
                    messageStatusesById.remove(event.messageId)
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
                if (eventConvId != null && eventConvId != currentActiveId) {
                    schedulePendingDeliveryDrain(eventConvId)
                }
                if (eventConvId == currentActiveId) {
                    finishStreaming(currentActiveId,event.generationId,GenerationState.CANCELLED,event.occurredAt)
                    val currentGeneration = (repository as? com.openandroidintelligence.conversation.ports.GenerationTracker)?.generationId?.value
                    if (currentGeneration == null || currentGeneration == event.generationId) observeGenerationIdentity(event.generationId)
                    update { state ->
                        state.copy(
                            generation = if (currentGeneration != null && currentGeneration != event.generationId) state.generation else GenerationState.CANCELLED,
                            timeline = Loadable.Ready(renderTimeline(state.pendingBatch)),
                            notice = if (currentGeneration == null || currentGeneration == event.generationId) {
                                state.notice?.takeUnless { it.startsWith("AGENT_MESSAGE_FAILED:") }
                            } else state.notice,
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

            is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.ApprovalTextFallback -> {
                rememberApprovalFallback(event)
            }

            is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.PendingDeliveryUpdated -> {
                val delivery = event.delivery
                if (delivery.conversationId.value == currentActiveId) {
                    update { state ->
                        state.copy(
                            pendingDeliveries = state.pendingDeliveries.filterNot {
                                it.clientMessageId == delivery.clientMessageId
                            } + delivery,
                        )
                    }
                }
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
        GatewayLog.protocolEvidence("T8.event_applied", mapOf("eventId" to event.eventId,
            "eventType" to event.javaClass.simpleName))
        commitHandledEvent(event.eventId)
    }

    private fun reloadTimeline(threadId: String) {
        if (closed) return
        timelineJob?.cancel()
        timelineJob = workScope.launch {
            if (!restoreLocalTimeline(threadId)) return@launch
            val result = Result.runCatching { repository.completeTimeline(threadId) }
            if (!isActive || activeThreadId != threadId) return@launch
            result.onSuccess { page ->
                if (!isActive || activeThreadId != threadId) return@launch
                val deletedIds=page.deletedMessageIds + page.messages.filter { it.isDeleted }.map { it.id }
                deletedIds.forEach { cancelMessageMedia(threadId,it) }
                scheduleHistoricalMedia(threadId,page.messages)
                snapshotDeletedIdsByThread.getOrPut(threadId) { linkedSetOf() }.addAll(deletedIds)
                sendAtomsMutex.withLock {
                    val latestAtoms=loadSendAtomsOnIo(threadId)
                    val reconciled=reconcileSnapshotDeletions(page.messages,latestAtoms).map { atom ->
                        if (atom.messageId in deletedIds) atom.copy(parts=emptyList(),snapshotDeleted=true) else atom
                    }
                    if (reconciled!=latestAtoms) saveAtoms(threadId,reconciled)
                    var epoch=sendAtomsEpoch
                    var latest=if (persistence!=null) loadSendAtomsOnIo(threadId) else reconciled
                    while (epoch!=sendAtomsEpoch) { epoch=sendAtomsEpoch; latest=loadSendAtomsOnIo(threadId) }
                    currentCoroutineContext().ensureActive()
                    if (!closed) sendAtomsByThread[threadId]=latest.associateByTo(linkedMapOf()) { it.clientMessageId.value }
                    latest.forEach(activeWork::atomChanged)
                }
                reconcileDeletedRecoveryUnits(threadId)
                deletedIds.forEach { id -> mirrored.remove(id); mirroredRevisions.remove(id); messageStatusesById.remove(id) }
                historicalAttachments.snapshot().forEach { (key,value) ->
                    if (key.scope == scopeFactory() && key.threadId == threadId && (value.messageId in deletedIds ||
                        value.clientMessageId?.value?.let { atomsFor(threadId)[it]?.isDeleted } == true)) historicalAttachments.remove(key)
                }
                var receivedConfirmedAssistantForCurrentTurn = false
                page.messages.sortedBy { it.timestamp }.forEach { rawMessage ->
                    if (rawMessage.isDeleted || snapshotDeletedIdsByThread[threadId]?.contains(rawMessage.id) == true) return@forEach
                    val message = withLatestMessageStatus(rawMessage)
                    if (message.sender == "user" && message.state.lowercase() in setOf("completed", "failed", "cancelled")) {
                        message.clientMessageId?.value?.let { activeWork.settledClient(threadId, it) }
                    }
                    val currentRevision = mirroredRevisions[message.id] ?: 0L
                    if (!mirrored.containsKey(message.id) || (message.contentRevision?.let { it > currentRevision } == true) || (message.state == "CONFIRMED" && currentRevision == 0L)) {
                        if (message.sender == "assistant" && message.state == "CONFIRMED") {
                            val matched = pruneConfirmedAssistantDuplicates(message)
                            if (matched) {
                                receivedConfirmedAssistantForCurrentTurn = true
                            }
                            disarmReplyWatchdogFor(
                                threadId, message.generationId,
                                clientMessageId = message.clientMessageId?.value,
                                belongsToCurrentTurn = matched,
                            )
                        }
                        mirrored[message.id] = message
                        mirroredRevisions[message.id] = maxOf(currentRevision,message.contentRevision ?: 0L)
                    } else if (message.sender == "assistant" && message.state == "CONFIRMED") {
                        val matched = pruneConfirmedAssistantDuplicates(message)
                        if (matched) {
                            receivedConfirmedAssistantForCurrentTurn = true
                        }
                        disarmReplyWatchdogFor(
                            threadId, message.generationId,
                            clientMessageId = message.clientMessageId?.value,
                            belongsToCurrentTurn = matched,
                        )
                    }
                }
                maybeArmCreationGraceFromTimeline(threadId)
                update { state ->
                    if (!isActive || state.activeThreadId != threadId) state
                    else {
                        val hasStreaming = mirrored.values.any { it.sender == "assistant" && it.state == "STREAMING" }
                        state.copy(
                            timeline = if (
                                renderTimeline(state.pendingBatch).isEmpty() &&
                                creationReceiptRow(threadId) == null &&
                                approvalRowsForActiveThread().isEmpty()
                            ) {
                                Loadable.Empty
                            } else {
                                Loadable.Ready(renderTimeline(state.pendingBatch))
                            },
                            generation = if (hasStreaming) {
                                GenerationState.RUNNING
                            } else if ((repository as? com.openandroidintelligence.conversation.ports.GenerationTracker)?.generationId?.value == null && receivedConfirmedAssistantForCurrentTurn && (state.generation == GenerationState.RUNNING || state.generation == GenerationState.QUEUED)) {
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

    /** 先发布本地双方消息，再等待服务器；网络同步不得遮住已有历史。 */
    private suspend fun restoreLocalTimeline(threadId: String):Boolean {
        return try {
        hydrateSendAtoms(threadId,refresh=true)
        val cached = (persistence as? AwaitableWorkbenchPersistence)?.let { store ->
            withContext(persistenceDispatcher) { store.loadTimeline(threadId) }
        }
        if (closed || activeThreadId != threadId) return false
        cached?.let { page ->
            val deleted = page.deletedMessageIds + page.messages.filter { it.isDeleted }.map { it.id }
            snapshotDeletedIdsByThread.getOrPut(threadId) { linkedSetOf() }.addAll(deleted)
            deleted.forEach { mirrored.remove(it); mirroredRevisions.remove(it) }
            page.messages.filterNot { it.isDeleted || it.id in deleted }.forEach { rawMessage ->
                val message = withLatestMessageStatus(rawMessage)
                val revision = message.contentRevision ?: 0L
                if (message.id !in mirrored || revision >= (mirroredRevisions[message.id] ?: 0L)) {
                    mirrored[message.id] = message
                    mirroredRevisions[message.id] = revision
                }
            }
            scheduleHistoricalMedia(threadId,page.messages)
        }
        val rows = renderTimeline()
        update { state ->
            if (state.activeThreadId != threadId) state
            else state.copy(historyHydrated=true,
                timeline=if (cached!=null || rows.isNotEmpty()) {
                    if (rows.isEmpty()) Loadable.Empty else Loadable.Ready(rows)
                } else state.timeline,
                notice=state.notice?.takeUnless { it.startsWith("LOCAL_HISTORY_LOADING:") })
        }
        true
    } catch (cancelled:CancellationException) { throw cancelled }
      catch (failure:Exception) {
        if (!closed && activeThreadId==threadId) update { it.copy(historyHydrated=false,
            timeline=Loadable.Failed(errorCodeOf(failure)),notice="LOCAL_HISTORY_FAILED:${errorCodeOf(failure)}") }
        false
        }
    }

    private fun hasUserMessageBetween(first: TimelineMessage, second: TimelineMessage): Boolean {
        val minT = minOf(first.timestamp, second.timestamp)
        val maxT = maxOf(first.timestamp, second.timestamp)
        if (minT == maxT) {
            // 宿主可能只提供秒级时间，同一时间并不等于同一轮。历史按原始顺序
            // 插入镜像；新收到的消息尚未插入时位于末尾，仍可识别中间的用户行。
            val ordered = mirrored.values.toList()
            val firstIndex = ordered.indexOfFirst { it.id == first.id }
            val secondIndex = ordered.indexOfFirst { it.id == second.id }.takeIf { it >= 0 } ?: ordered.size
            if (firstIndex < 0 || firstIndex == secondIndex) return false
            return ordered.subList(minOf(firstIndex, secondIndex) + 1, maxOf(firstIndex, secondIndex))
                .any { it.sender == "user" }
        }
        val userInMirrored = mirrored.values.any { it.sender == "user" && it.timestamp > minT && it.timestamp <= maxT }
        val userInPending = _state.value.pendingBatch.any { it.isUser && it.timestamp > minT && it.timestamp <= maxT }
        return userInMirrored || userInPending
    }

    private fun hasUserMessageAfter(t: Long): Boolean {
        val userInMirrored = mirrored.values.any { it.sender == "user" && it.timestamp > t }
        val userInPending = _state.value.pendingBatch.any { it.isUser && it.timestamp > t }
        return userInMirrored || userInPending
    }

    private fun isInCurrentTurn(timestamp: Long): Boolean {
        if (timestamp <= 0L || hasUserMessageAfter(timestamp)) return false
        val latestUserTimestamp = maxOf(
            mirrored.values.filter { it.sender == "user" }.maxOfOrNull { it.timestamp } ?: 0L,
            _state.value.pendingBatch.filter { it.isUser }.maxOfOrNull { it.timestamp } ?: 0L,
        )
        return latestUserTimestamp > 0L && timestamp >= latestUserTimestamp - 5_000L
    }

    /** 原生模式或明确关联到提交的消息以真实行 ID 为准；同轮也可有多条相同正文。 */
    private fun haveDistinctNativeAssistantIds(
        firstId: String, firstClientId: ClientMessageId?, firstLocal: Boolean,
        secondId: String, secondClientId: ClientMessageId?, secondLocal: Boolean,
    ): Boolean {
        if (authoritativeMessageIds) return firstId != secondId && !firstLocal && !secondLocal
        // 旧 Gateway 的无身份行继续沿用兼容规则；原生真实 ID 从不按前缀分类。
        fun isTemporary(id: String) = id.startsWith("local_") || id.startsWith("stream_") || id.startsWith("asst_")
        return (firstClientId != null || secondClientId != null) && firstId != secondId &&
            !firstLocal && !secondLocal && !isTemporary(firstId) && !isTemporary(secondId)
    }

    private fun haveDistinctNativeAssistantIds(first: TimelineMessage, second: TimelineMessage): Boolean =
        haveDistinctNativeAssistantIds(first.id, first.clientMessageId, first.localPlaceholder,
            second.id, second.clientMessageId, second.localPlaceholder)

    private fun haveDistinctNativeAssistantIds(first: TimelineEntry, second: TimelineEntry): Boolean =
        haveDistinctNativeAssistantIds(first.key, first.clientMessageId, first.localPlaceholder,
            second.key, second.clientMessageId, second.localPlaceholder)

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
                !haveDistinctNativeAssistantIds(existing, message) &&
                ((normalizeEntryKey(existing.id).isNotBlank() && normalizeEntryKey(existing.id) == normalizeEntryKey(message.id)) ||
                    (text.isNotBlank() &&
                        (existing.clientMessageId == null || message.clientMessageId == null || existing.clientMessageId == message.clientMessageId) &&
                        !hasUserMessageBetween(existing, message) &&
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
                !haveDistinctNativeAssistantIds(v, message) &&
                ((normalizeEntryKey(k).isNotBlank() && normalizeEntryKey(k) == normalizeEntryKey(message.id)) ||
                    ((v.clientMessageId == null || message.clientMessageId == null || v.clientMessageId == message.clientMessageId) &&
                        !hasUserMessageBetween(v, message) &&
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
        val streamingEntries = mirrored.filterValues { it.sender == "assistant" && it.state == "STREAMING" &&
            (it.generationId == null || message.generationId == null || it.generationId == message.generationId) }
        var supersededActiveStreaming = false
        for ((k, s) in streamingEntries) {
            val sameNormalizedKey = normalizeEntryKey(s.id).isNotBlank() &&
                normalizeEntryKey(s.id) == normalizeEntryKey(message.id)
            val sText = messageTextOf(s)
            val sameClientIdentity = s.clientMessageId != null && s.clientMessageId == message.clientMessageId
            val differentClientIdentities = s.clientMessageId != null && message.clientMessageId != null &&
                s.clientMessageId != message.clientMessageId
            val isSameTurn = !differentClientIdentities && !hasUserMessageBetween(s, message) &&
                message.timestamp >= s.timestamp - 5_000L &&
                (latestUserTimestamp == 0L || message.timestamp >= latestUserTimestamp - 10_000L)
            val isCoveredContent = (messageText.isNotBlank() && (messageText == sText || messageText.startsWith(sText))) ||
                (s.parts.isNotEmpty() && s.parts == message.parts)
            val isStaleEmpty = sText.isBlank() &&
                s.parts.none { it is com.openandroidintelligence.conversation.model.MessagePart.Attachment } &&
                message.timestamp >= s.timestamp

            if (!haveDistinctNativeAssistantIds(s, message) &&
                (sameNormalizedKey || sameClientIdentity || (isSameTurn && (isCoveredContent || isStaleEmpty)))) {
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

    private fun withLatestMessageStatus(message: TimelineMessage): TimelineMessage {
        if (message.isDeleted) return message
        val latest = messageStatusesById[message.id]?.let { receipt ->
            message.copy(state=receipt.status.wireValue,errorCode=receipt.errorCode?.wireValue)
        } ?: message
        return if (latest.state == "STREAMING" && isCancelledMessage(latest.conversationId?.value ?: activeThreadId,
                latest.generationId,latest.id,latest.clientMessageId?.value,confirmedOnly=true)) latest.copy(state="CANCELLED") else latest
    }

    /**
     * 助手已经开始输出，说明这一轮之前提交的 prompt 已被 Agent 接收。
     *
     * 只推进仍为 QUEUED 且已受理的本地原子；**不写 statusRevision**——
     * 服务端随后到达的 COMPLETED/FAILED 仍按 revision 单调规则正常生效。
     */
    private suspend fun promotePromptsDelivered(threadId: String, reply: com.openandroidintelligence.conversation.ports.TimelineMessage) {
        val queued = atomsFor(threadId).values.filter {
            it.submissionState == LocalSubmissionState.ACCEPTED &&
                it.status == com.openandroidintelligence.conversation.ports.AgentMessageStatus.QUEUED &&
                it.messageId != null
        }
        if (queued.isEmpty()) return
        val generationId = reply.generationId
        val linked = if (generationId == null) emptyList() else queued.filter { atom ->
            val messageId = atom.messageId ?: return@filter false
            messageGenerations[threadId to messageId]?.id == generationId ||
                messageGenerations[threadId to atom.clientMessageId.value]?.id == generationId
        }
        val targets = when {
            // 这一轮没有任何 generation 标识时，本轮所有未结算的 prompt 都已经被 Agent 取走。
            generationId == null -> queued
            linked.isNotEmpty() -> linked
            // 映射还没建立（例如只从助手消息里第一次看到 generationId）：只推进最近一条，
            // 不替更早的、可能仍在排队的 prompt 编造送达事实。
            else -> listOfNotNull(queued.maxByOrNull { it.timestamp })
        }
        targets.forEach { atom ->
            changeSendAtom(threadId, atom.clientMessageId.value) { current ->
                if (current != null &&
                    current.status == com.openandroidintelligence.conversation.ports.AgentMessageStatus.QUEUED
                ) {
                    current.copy(status = com.openandroidintelligence.conversation.ports.AgentMessageStatus.DELIVERED)
                } else current
            }
        }
    }

    /**
     * 单条消息到展示行的映射。
     *
     * 流式期间只有末尾消息被替换，历史行按消息实例复用即可，不必每帧重建；
     * 带附件的行依赖多张外部展示状态 map，宁可重建也不能读到过期的附件视图。
     */
    private fun renderedTimelineEntry(
        message: com.openandroidintelligence.conversation.ports.TimelineMessage,
    ): TimelineEntry {
        val cancelled = isCancelledMessage(activeThreadId, message.generationId, message.id, message.clientMessageId?.value)
        val cacheable = message.parts.none {
            it is com.openandroidintelligence.conversation.model.MessagePart.Attachment
        }
        if (cacheable) {
            renderedEntryCache[message.id]
                ?.takeIf { it.message === message && it.cancelled == cancelled }
                ?.let { return it.entry }
        }
        val messageAttachments = message.parts.filterIsInstance<com.openandroidintelligence.conversation.model.MessagePart.Attachment>()
            .map { att ->
                val id = att.draftId.value
                val visible = activeThreadId?.let { historicalAttachments[historicalAttachmentKey(it, id)]?.attachment }?.let { cached ->
                    cached.copy(filename = att.filename.ifBlank { cached.filename }, mediaType = att.mediaType.ifBlank { cached.mediaType })
                } ?: run {
                    val d = _state.value.attachments.firstOrNull { it.id.value == id }
                    com.openandroidintelligence.conversation.model.TimelineAttachment(
                        draftId = id,
                        filename = d?.filename?.takeUnless { it.isBlank() } ?: att.filename,
                        mediaType = d?.mediaType?.takeUnless { it.isBlank() } ?: att.mediaType,
                        previewBytes = attachmentPreviews[id],
                    )
                }
                val presentation = activeThreadId?.let { mediaPresentation[historicalAttachmentKey(it,id)] }
                // 原图字节只在缓存里存在：缓存淘汰后必须回到 IDLE，
                // 不能让一条 READY 记录指向已经被清零的数组。
                val originalBytes = activeThreadId?.let { originalImages[historicalAttachmentKey(it, id)] }
                val originalState = when {
                    originalBytes != null -> OriginalImageState.READY
                    presentation?.originalState == OriginalImageState.LOADING ||
                        presentation?.originalState == OriginalImageState.FAILED -> presentation.originalState
                    else -> OriginalImageState.IDLE
                }
                val withOriginal = visible.copy(
                    originalBytes = originalBytes,
                    originalState = originalState,
                    originalError = presentation?.originalError,
                )
                if (presentation == null) withOriginal else withOriginal.copy(
                    savedOffline=presentation.localState == com.openandroidintelligence.conversation.model.AttachmentLocalState.SAVED,
                    localState=presentation.localState,localError=presentation.localError,
                    downloadState=presentation.downloadState,downloadError=presentation.downloadError)
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
        val entry = TimelineEntry(
            key = message.id,
            sender = message.sender,
            text = displayText,
            isUser = message.sender == "user",
            timestamp = message.timestamp,
            pendingAcceptance = message.state == "PENDING",
            batchGroupId = message.batchId,
            attachments = messageAttachments,
            isStreaming = message.state == "STREAMING",
            messageStatus = com.openandroidintelligence.conversation.ports.AgentMessageStatus.entries
                .firstOrNull { it.wireValue == message.state }
                ?.takeUnless { cancelled && it == com.openandroidintelligence.conversation.ports.AgentMessageStatus.FAILED },
            messageStatusErrorCode = message.errorCode?.takeUnless { cancelled }?.let { code ->
                com.openandroidintelligence.conversation.ports.AgentMessageErrorCode.entries
                    .firstOrNull { it.wireValue == code }
            },
            submissionFailureCode = message.localSubmissionFailure,
            clientMessageId = message.clientMessageId,
            generationId = message.generationId,
            executionSteps = message.executionSteps,
            localPlaceholder = message.localPlaceholder,
        )
        if (cacheable) {
            renderedEntryCache[message.id] = RenderedTimelineEntry(message, cancelled, entry)
            while (renderedEntryCache.size > renderedEntryCacheLimit) {
                renderedEntryCache.remove(renderedEntryCache.keys.first())
            }
        }
        return entry
    }

    private fun renderTimeline(pendingBatch: List<TimelineEntry> = _state.value.pendingBatch): List<TimelineEntry> {
        val activeAtoms = activeThreadId?.let { atomsFor(it).values.toList() }.orEmpty()
        val mergedMessages = mergeLocalSendAtoms(mirrored.values.toList(), activeAtoms)
        val queuedClientIds = _state.value.pendingDeliveries.map { it.clientMessageId.value }.toSet()
        val mirroredEntries = mergedMessages.filterNot {
            it.clientMessageId?.value in queuedClientIds
        }.map(::renderedTimelineEntry)

        val mirroredKeys = mergedMessages.map { it.id }.toSet()
        val mirroredClientIds = mergedMessages.mapNotNull { it.clientMessageId?.value }.toSet()
        val deletedClientIds = activeAtoms.filter { it.isDeleted }.map { it.clientMessageId.value }.toSet()
        val unconfirmedPending = pendingBatch.filterNot { entry ->
            mirroredKeys.contains(entry.key) || mirroredKeys.contains(entry.key.removePrefix("local_")) || mirroredClientIds.contains(entry.key.removePrefix("local_")) || deletedClientIds.contains(entry.key.removePrefix("local_"))
        }

        val rawList = (mirroredEntries + unconfirmedPending).sortedWith(
            compareBy<TimelineEntry> { entry ->
                if (entry.timestamp > 0L) entry.timestamp else Long.MAX_VALUE
            },
        )
        val rendered = deduplicateTimelineEntries(rawList)
        // Inserted after the message rules: an approval is a request, not
        // content, so the folding and pruning above must never touch it. Each
        // card still takes the place its own timestamp earned, so it reads where
        // it happened rather than trailing the whole conversation.
        val withCards = insertApprovalRows(insertNativeExecutionRows(rendered), approvalRowsForActiveThread())
        // A receipt is navigation, not content, for the same reason.
        val receiptRow = activeThreadId?.let(::creationReceiptRow) ?: return ensureUniqueKeys(withCards)
        return ensureUniqueKeys(withCards + receiptRow)
    }

    private fun insertNativeExecutionRows(entries: List<TimelineEntry>): List<TimelineEntry> {
        val threadId = activeThreadId ?: return entries
        fun visibleSteps(generationId: String, steps: List<NativeExecutionStep>): List<NativeExecutionStep> =
            if (threadId to generationId in finishedGenerations) steps.map { step ->
                if (step.status == NativeExecutionStatus.RUNNING) step.copy(status = NativeExecutionStatus.UNKNOWN) else step
            } else steps
        val result = entries.map { entry ->
            val generationId = entry.generationId
            if (generationId != null && entry.executionSteps.isNotEmpty())
                entry.copy(executionSteps = visibleSteps(generationId, entry.executionSteps)) else entry
        }.toMutableList()
        executionFacts.forEach { (identity, facts) ->
            val (conversationId, generationId) = identity
            if (conversationId != threadId || facts.steps.isEmpty()) return@forEach
            val anchors = result.withIndex().filter { (_, entry) ->
                entry.isUser && (facts.userMessageId != null && entry.key == facts.userMessageId ||
                    facts.clientMessageId != null && entry.clientMessageId?.value == facts.clientMessageId)
            }
            // Identity ambiguity fails closed. An unanchored fact is retained
            // until its exact prompt or an actual message of that generation is read.
            if (anchors.size > 1) return@forEach
            val anchor = anchors.singleOrNull()
            val messageIndex = result.indexOfFirst { !it.executionFact && !it.isUser && it.generationId == generationId }
            var index = anchor?.index?.plus(1) ?: messageIndex.takeIf { it >= 0 } ?: return@forEach
            while (index < result.size && result[index].executionFact) index++
            val row = TimelineEntry(
                key = "execution-fact:$generationId", sender = "system", text = "", isUser = false,
                timestamp = anchor?.value?.timestamp ?: result.getOrNull(messageIndex)?.timestamp ?: facts.occurredAt,
                pendingAcceptance = false, batchGroupId = null, generationId = generationId,
                clientMessageId = facts.clientMessageId?.let { runCatching { ClientMessageId(it) }.getOrNull() },
                executionSteps = visibleSteps(generationId, facts.steps), executionFact = true,
            )
            result.add(index, row)
        }
        return result
    }

    /**
     * 展示列表的 key 必须唯一，否则 LazyColumn 会在测量阶段直接抛
     * `IllegalArgumentException: Key ... was already used` 把进程带走。
     * 这里只做最后一道保险：正常路径上重复行应已在合并/去重阶段消除。
     */
    private fun ensureUniqueKeys(entries: List<TimelineEntry>): List<TimelineEntry> {
        if (entries.size <= 1) return entries
        val seen = HashSet<String>(entries.size)
        val dropped = LinkedHashSet<String>()
        val result = entries.filter { entry ->
            if (seen.add(entry.key)) true else { dropped += entry.key; false }
        }
        if (dropped.isNotEmpty()) {
            GatewayLog.w("OaiWorkbench", "dropped duplicate timeline keys=${dropped.toList()}")
        }
        return result
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
            .removePrefix("asst_")
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
                val sameClientIdentity = entry.clientMessageId != null && entry.clientMessageId == lastEntry?.clientMessageId
                val differentClientIdentities = entry.clientMessageId != null && lastEntry?.clientMessageId != null &&
                    entry.clientMessageId != lastEntry.clientMessageId
                val distinctNativeIds = authoritativeMessageIds && lastEntry != null &&
                    !entry.localPlaceholder && !lastEntry.localPlaceholder && entry.key != lastEntry.key
                val isDuplicateUser = lastEntry != null && lastEntry.isUser &&
                    !differentClientIdentities && !distinctNativeIds &&
                    if (authoritativeMessageIds) {
                        // Native history owns identity. A prefix, body or nearby
                        // timestamp cannot turn two real user messages into one.
                        sameClientIdentity || entry.key == lastEntry.key
                    } else {
                        sameClientIdentity || normalizeEntryKey(entry.key) == normalizeEntryKey(lastEntry.key) ||
                            (entry.text == lastEntry.text && entry.attachments == lastEntry.attachments &&
                                Math.abs(entry.timestamp - lastEntry.timestamp) <= 10_000L)
                    }
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
        return deduplicateUserIdentities(result)
    }

    /**
     * 同一 clientMessageId 只允许一条用户行。
     *
     * 实时状态里可能同时存在"受理占位行"和"服务端镜像行"：两者 id 不同、时间戳可能为 0，
     * 而且中间隔着一条助手回复，旧的相邻去重覆盖不到，于是会在回复下方多出一条上一轮消息。
     * 切会话会从仓库重载整条时间线，所以这个重复只在实时状态下可见。
     */
    private fun deduplicateUserIdentities(entries: List<TimelineEntry>): List<TimelineEntry> {
        if (entries.size <= 1) return entries

        val keep = BooleanArray(entries.size) { true }
        val indexByIdentity = HashMap<String, Int>()
        val nativeIdsByClient = if (authoritativeMessageIds) entries.asSequence()
            .filter { it.isUser && !it.localPlaceholder && it.clientMessageId != null }
            .groupBy { requireNotNull(it.clientMessageId).value }
            .mapValues { (_, rows) -> rows.map { it.key }.distinct() } else emptyMap()
        entries.forEachIndexed { index, entry ->
            if (!entry.isUser) return@forEachIndexed
            val clientId = entry.clientMessageId?.value?.takeIf { it.isNotBlank() }
            val identity = if (authoritativeMessageIds) {
                when {
                    // One explicit client can replace its local placeholder,
                    // but cannot collapse several distinct native message IDs.
                    clientId != null && nativeIdsByClient[clientId].orEmpty().size <= 1 -> "client:$clientId"
                    entry.key.isNotBlank() -> "message:${entry.key}"
                    else -> return@forEachIndexed
                }
            } else {
                clientId ?: normalizeEntryKey(entry.key).takeIf { it.isNotBlank() } ?: return@forEachIndexed
            }
            val previous = indexByIdentity[identity]
            if (previous == null) {
                indexByIdentity[identity] = index
            } else if (preferUserRow(entry, entries[previous])) {
                keep[previous] = false
                indexByIdentity[identity] = index
            } else {
                keep[index] = false
            }
        }
        return if (keep.all { it }) entries else entries.filterIndexed { index, _ -> keep[index] }
    }

    /** 保留"已确认、有真实时间戳、内容更完整"的那一行。 */
    private fun preferUserRow(candidate: TimelineEntry, current: TimelineEntry): Boolean {
        if (current.pendingAcceptance != candidate.pendingAcceptance) return current.pendingAcceptance
        val candidateTimed = candidate.timestamp > 0L
        val currentTimed = current.timestamp > 0L
        if (candidateTimed != currentTimed) return candidateTimed
        if (candidateTimed && currentTimed && candidate.timestamp != current.timestamp) {
            return candidate.timestamp < current.timestamp
        }
        return candidate.text.length > current.text.length
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
                    val sameClientIdentity = s.clientMessageId != null && s.clientMessageId == c.clientMessageId
                    val differentClientIdentities = s.clientMessageId != null && c.clientMessageId != null &&
                        s.clientMessageId != c.clientMessageId
                    val timeDiff = c.timestamp - s.timestamp
                    val isSameTurn = !differentClientIdentities && timeDiff >= -5_000L
                    val isCoveredByConfirmed = (s.text.isNotBlank() && (c.text == s.text || c.text.startsWith(s.text))) ||
                        (s.attachments.isNotEmpty() && s.attachments == c.attachments)
                    val isStaleEmpty = s.text.isBlank() && s.attachments.isEmpty() && c.timestamp >= s.timestamp
                    !haveDistinctNativeAssistantIds(s, c) &&
                        (sameNormalizedKey || sameClientIdentity || (isSameTurn && (isCoveredByConfirmed || isStaleEmpty)))
                }
            }
        } else {
            val deduplicatedStreaming = mutableListOf<TimelineEntry>()
            for (s in streaming) {
                val duplicateIndex = deduplicatedStreaming.indexOfFirst { existing ->
                    !haveDistinctNativeAssistantIds(s, existing) &&
                        ((normalizeEntryKey(s.key).isNotBlank() && normalizeEntryKey(s.key) == normalizeEntryKey(existing.key)) ||
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
            // Within one group there is no user message between the entries, so
            // identical content is the same reply published twice — which is what
            // a host that mints a new message id per publish produces. The turn
            // boundary, not a time window, decides whether two identical replies
            // are one message or two.
            val isDuplicate = keptConfirmed.any { existing ->
                !haveDistinctNativeAssistantIds(c, existing) &&
                    ((normalizeEntryKey(c.key).isNotBlank() && normalizeEntryKey(c.key) == normalizeEntryKey(existing.key)) ||
                        ((c.clientMessageId == null || existing.clientMessageId == null || c.clientMessageId == existing.clientMessageId) &&
                            (c.text.isNotEmpty() || c.attachments.isNotEmpty()) &&
                            c.text == existing.text && c.attachments == existing.attachments))
            }
            if (!isDuplicate) {
                keptConfirmed.add(c)
            }
        }

        // 筛掉重复项即可；同时间戳的原生顺序不能因“已确认/流式”分类而改变。
        val kept = (keptConfirmed + keptStreaming).toSet()
        return group.filter { it in kept }
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
        // 同一个 key 只能出现一次：失败重试会复用 clientMessageId，而原子在受理前
        // 也以 local_<id> 呈现，直接追加就会让 LazyColumn 撞上重复 key 崩溃。
        return Loadable.Ready(current.filterNot { it.key == entry.key } + entry)
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

    private fun persistCheckpoint() {
        val store=persistence ?: return
        if (closed) return
        val ui = _state.value
        val pending = pendingSubmission ?: failedSubmission
        val checkpoint = WorkbenchCheckpoint(
            (ui.threads as? Loadable.Ready)?.value.orEmpty(), activeThreadId, ui.activeThreadTitle,
            mirrored.values.toList(), mirroredRevisions.toMap(), ui.draft, draftRevision,
            ui.attachments.filterNot { draft -> pending?.voiceAttempt != null && draft.id.value in pending.attachmentIds },
            pending?.let { SavedSubmission(it.text,it.attachmentIds,it.revision,it.conversationId,it.clientMessageId) },
            userRenamedThreads.toSet(), handledEventIds + committingEventIds, frozenBatches.values.toList()+collectingUnits.values.groupBy { it.first }.map { (id,units) -> SavedBatch("collecting",id,units.map { it.second },false) },
            organization=ui.organization, threadsHydrated=ui.threads is Loadable.Ready || ui.threads is Loadable.Empty,
            threadsSnapshotRevision=threadsSnapshotRevision, deletedThreadIds=deletedThreads.toSet(),
            cancellations=cancelledGenerations.map { SavedCancellation(it.first,it.second) },
            messageGenerations=messageGenerations.map { (key,value) -> SavedMessageGeneration(key.first,key.second,value.id,value.revision) },
            pendingFolderAssignments=pendingFolderAssignments.map { SavedFolderAssignment(it.key,it.value) },
            pendingConversationCreation=unconfirmedConversationCreation?.let { SavedConversationCreation(it.clientId,it.folderId) })
        pendingCheckpoint = checkpoint
        if (checkpointJob?.isActive == true) return
        checkpointJob = workScope.launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                while (true) {
                    val next = pendingCheckpoint ?: break
                    pendingCheckpoint = null
                    withContext(persistenceDispatcher) {
                        val durable=store as? AwaitableWorkbenchPersistence
                        if (durable!=null) durable.saveCheckpoint(next) else store.save(next)
                    }
                }
                checkpointFailure = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                checkpointFailure = failure
                _state.update { it.copy(notice="LOCAL_SAVE_FAILED:${errorCodeOf(failure)}") }
            }
        }
    }

    /** 发送、事件确认和结束账号前必须等待真实提交，错误交给调用方处理。 */
    suspend fun flushPendingPersistence() = persistenceLifecycleMutex.withLock {
        currentCoroutineContext().ensureActive()
        retirementFailure?.let { throw it }
        if (!persistenceRetired) flushOpenPersistence()
    }

    private suspend fun flushOpenPersistence() {
        currentCoroutineContext().ensureActive()
        if (persistence==null) return
        persistCheckpoint()
        val saving = checkpointJob
        saving?.join()
        // join observes completion, not success. A parent-cancelled writer may
        // have exited before the checkpoint reached disk.
        if (saving?.isCancelled == true) throw java.io.IOException("LOCAL_SAVE_INTERRUPTED")
        checkpointFailure?.let { throw it }
        withContext(persistenceDispatcher) { (persistence as? AwaitableWorkbenchPersistence)?.flush() }
    }

    /** Apply a freshly rebuilt baseline while preserving an unsent local draft. */
    fun onMirrorRebuilt() {
        sendAtomsEpoch++
        handledEventIds.clear(); mirrored.clear(); executionFacts.clear(); renderedEntryCache.clear(); mirroredRevisions.clear(); sendAtomsByThread.clear()
        update { it.copy(historyHydrated=activeThreadId==null,timeline=if(activeThreadId==null) it.timeline else Loadable.Loading) }
        refreshThreads(); activeThreadId?.let(::reloadTimeline)
    }

    private fun update(transform: (WorkbenchUiState) -> WorkbenchUiState) {
        if (closed) return
        _state.update { current ->
            // The capability is a fact about the Gateway, not about a screen: it
            // is re-asserted on every update so no caller can drop it. The copy
            // only happens when the transform did not already carry that value,
            // which keeps the common path — one keystroke, one transform — free
            // of a second allocation.
            val transformed = transform(current)
            val recoverable = frozenBatches.values.any { it.conversationId == transformed.activeThreadId } ||
                collectingUnits.values.any { it.first == transformed.activeThreadId } ||
                transformed.activeThreadId?.let { recoverableOrdinaryAtoms(it).isNotEmpty() } == true
            val unknownSubmissionOutcome = transformed.activeThreadId?.let { threadId ->
                frozenBatches.values.any { batch -> batch.conversationId == threadId && batch.messages.any { message ->
                    atomsFor(threadId)[message.clientMessageId.value]?.submissionState == LocalSubmissionState.OUTCOME_UNKNOWN
                } } || collectingUnits.filterValues { it.first == threadId }.keys.any { clientId ->
                    atomsFor(threadId)[clientId]?.submissionState == LocalSubmissionState.OUTCOME_UNKNOWN
                } || recoverableOrdinaryAtoms(threadId).any { it.submissionState == LocalSubmissionState.OUTCOME_UNKNOWN }
            } == true
            val next = transformed.copy(pendingFolderAssignments=pendingFolderAssignments.toMap(), hasRecoverableSubmissions = recoverable,
                hasUnknownSubmissionOutcome = unknownSubmissionOutcome,
                conversationManagementSupported=supportsConversationManagement,
                conversationDeleteSupported=supportsConversationDelete && remoteConversationOnline,
                conversationArchiveSupported=supportsConversationArchive && remoteConversationOnline,
                conversationUnarchiveSupported=supportsConversationUnarchive && remoteConversationOnline,
                conversationRenameSupported=supportsConversationRename && remoteConversationOnline,
                organizationStoredLocally=organizationStoredLocally,
                busyThreadIds=managementBusyThreadIds(transformed)+externalBusyThreadIds())
            if (next.approvalCardsSupported == supportsApprovalCards
            ) next else next.copy(
                approvalCardsSupported = supportsApprovalCards,
            )
        }
        persistCheckpoint()
    }

    private fun isNewConversationCommand(command: String): Boolean =
        command.trim().trimStart('/').equals(NEW_CONVERSATION_COMMAND.trimStart('/'), ignoreCase = true)

    private companion object {
        const val MAX_ORGANIZATION_UPDATE_ATTEMPTS = 3

        /** 沿用最多 30 条媒体显示记录，每项至多 256 KiB 缩略图，总预览字节至多 7.5 MiB。 */
        const val MAX_HISTORY_ATTACHMENT_PREVIEWS = 30
        const val MAX_HISTORY_PREVIEW_BYTES = 256 * 1024

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
