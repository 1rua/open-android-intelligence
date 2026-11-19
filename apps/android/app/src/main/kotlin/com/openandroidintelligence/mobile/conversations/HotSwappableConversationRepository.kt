package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 支持原地热升级的会话仓储：冷启动阶段以 [offline] 本地加密镜像即时响应，
 * 后台建链成功后通过 [attachOnline] 无缝切换到真实远端仓储，保持同一个 [WorkbenchController]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HotSwappableConversationRepository(
    private val offline: OfflineMirrorRepository,
    private val scope: CoroutineScope,
    initialStreamHealth: StreamHealth = StreamHealth.CONNECTING,
) : ConversationRepository, GenerationTracker, EventCommitBarrier, StreamHealthSource, MessageOutcomeQuery {
    @Volatile
    private var onlineDelegate: MirroredConversationRepository? = null
    private val onlineFlow = MutableStateFlow<MirroredConversationRepository?>(null)
    private val bridgeLock = Any()
    private var bridgeJob: Job? = null

    private val mutableStreamHealth = MutableStateFlow(initialStreamHealth)
    override val streamHealth: Flow<StreamHealth> = mutableStreamHealth.asStateFlow()

    private val mutableStreamFailure = MutableStateFlow<String?>(null)
    override val streamFailure: Flow<String?> = mutableStreamFailure.asStateFlow()

    private val mutableGenerationId = MutableStateFlow<String?>(null)
    override val generationId: StateFlow<String?> = mutableGenerationId.asStateFlow()

    private val mutableGenerationState = MutableStateFlow(GenerationState.IDLE)
    override val generationState: StateFlow<GenerationState> = mutableGenerationState.asStateFlow()

    private val mutableGenerationSnapshot = MutableStateFlow(GenerationSnapshot(null, GenerationState.IDLE, null))
    override val generationSnapshot: StateFlow<GenerationSnapshot> = mutableGenerationSnapshot.asStateFlow()

    val isOnline: Boolean get() = onlineDelegate != null

    /** 在离线或宽限期尚未建链时更新连接健康状态指示（如 CONNECTING / RECONNECTING / FAILED）。 */
    fun setConnectionStreamHealth(health: StreamHealth) {
        synchronized(bridgeLock) {
            if (onlineDelegate == null) {
                mutableStreamHealth.value = health
            }
        }
    }

    /** 原地挂载在线镜像仓储，不重建上层控制器。 */
    fun attachOnline(online: MirroredConversationRepository) {
        synchronized(bridgeLock) {
            bridgeJob?.cancel()
            onlineDelegate = online
            mutableStreamFailure.value = null
            mutableStreamHealth.value = StreamHealth.LIVE
            val initialState = online.generationState?.value ?: GenerationState.IDLE
            mutableGenerationId.value = online.generationId.value
            mutableGenerationState.value = initialState
            mutableGenerationSnapshot.value = online.generationSnapshot?.value
                ?: GenerationSnapshot(online.generationId.value, initialState, null)
            bridgeJob = scope.launch {
                launch {
                    online.streamHealth.collect { raw ->
                        mutableStreamHealth.value = if (raw == StreamHealth.IDLE) StreamHealth.LIVE else raw
                    }
                }
                launch {
                    online.streamFailure.collect { failure ->
                        mutableStreamFailure.value = failure
                    }
                }
                val snapshots = online.generationSnapshot ?: online.generationState?.map { state ->
                    GenerationSnapshot(online.generationId.value, state, null)
                }
                if (snapshots != null) {
                    launch {
                        snapshots.collect { snapshot ->
                            mutableGenerationId.value = snapshot.generationId
                            mutableGenerationState.value = snapshot.state
                            mutableGenerationSnapshot.value = snapshot
                        }
                    }
                }
            }
            onlineFlow.value = online
        }
    }

    /** 网络断开或需要重连时原地退回本地镜像，保留控制器实例和用户输入草稿。 */
    fun detachOnline(nextHealth: StreamHealth = StreamHealth.RECONNECTING) {
        synchronized(bridgeLock) {
            bridgeJob?.cancel()
            bridgeJob = null
            onlineDelegate = null
            onlineFlow.value = null
            mutableStreamHealth.value = nextHealth
            mutableStreamFailure.value = null
            mutableGenerationId.value = null
            mutableGenerationState.value = GenerationState.IDLE
            mutableGenerationSnapshot.value = GenerationSnapshot(null, GenerationState.IDLE, null)
        }
    }

    private fun currentRepo(): ConversationRepository = onlineDelegate ?: offline

    override suspend fun awaitPendingEventCommits() {
        onlineDelegate?.awaitPendingEventCommits()
    }

    override suspend fun queryMessage(conversationId: String, clientMessageId: ClientMessageId): TimelineMessage? =
        onlineDelegate?.queryMessage(conversationId, clientMessageId)

    override suspend fun listConversations(scope: ConversationScope, page: PageRequest): ConversationPage =
        currentRepo().listConversations(scope, page)

    override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation =
        currentRepo().createConversation(scope, clientConversationId)

    override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage =
        currentRepo().timeline(conversationId, page)

    override fun isIdleGenerationReceiptCurrent(conversationId: String, revision: Long): Boolean =
        onlineDelegate?.isIdleGenerationReceiptCurrent(conversationId, revision) ?: false

    override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance =
        currentRepo().submitBatch(batch)

    override suspend fun submitBatch(conversationId: String, batch: MessageBatch): BatchAcceptance =
        currentRepo().submitBatch(conversationId, batch)

    override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance =
        currentRepo().submitMessage(message)

    override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance =
        currentRepo().submitMessage(conversationId, message)

    override suspend fun steerMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance =
        currentRepo().steerMessage(conversationId, message)

    override suspend fun pendingDeliveries(conversationId: String): List<PendingDelivery> =
        currentRepo().pendingDeliveries(conversationId)

    override suspend fun retryPendingDeliveries(conversationId: String) {
        currentRepo().retryPendingDeliveries(conversationId)
    }

    override suspend fun retryAllPendingDeliveries() {
        currentRepo().retryAllPendingDeliveries()
    }

    override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> =
        onlineFlow.flatMapLatest { delegate ->
            delegate?.observeEvents(scope) ?: emptyFlow()
        }

    override suspend fun cancelGeneration(generationId: String, requestId: String): CancelGenerationResult =
        currentRepo().cancelGeneration(generationId, requestId)

    override suspend fun cancelGeneration(conversationId: String, generationId: String, requestId: String): CancelGenerationResult =
        currentRepo().cancelGeneration(conversationId, generationId, requestId)

    override suspend fun updateTitle(conversationId: String, title: String): Boolean =
        currentRepo().updateTitle(conversationId, title)

    override suspend fun readOrganization(): ConversationOrganization =
        currentRepo().readOrganization()

    override suspend fun updateOrganization(expectedRevision: Long, organization: ConversationOrganization): ConversationOrganization =
        currentRepo().updateOrganization(expectedRevision, organization)

    override suspend fun setArchived(conversationId: String, archived: Boolean) {
        currentRepo().setArchived(conversationId, archived)
    }

    override suspend fun deleteConversation(conversationId: String) {
        currentRepo().deleteConversation(conversationId)
    }

    override suspend fun readConversation(conversationId: String): Conversation? =
        currentRepo().readConversation(conversationId)

    override suspend fun submitApprovalDecision(approvalId: ApprovalId, choice: ApprovalChoice): ApprovalSubmissionResult =
        currentRepo().submitApprovalDecision(approvalId, choice)

    override suspend fun pendingApprovalFallbacks(conversationId: String): List<VerifiedConversationEvent.ApprovalTextFallback> =
        currentRepo().pendingApprovalFallbacks(conversationId)
}

/** 支持在线升级原地接入的附件草稿协调器。 */
class HotSwappableAttachmentCoordinator : AttachmentDraftCoordinator {
    @Volatile
    private var delegate: AttachmentDraftCoordinator? = null

    val isAttached: Boolean get() = delegate != null

    fun attach(coordinator: AttachmentDraftCoordinator) {
        delegate = coordinator
    }

    fun detach() {
        delegate = null
    }

    override fun restoredDrafts(): List<AttachmentDraft> = delegate?.restoredDrafts().orEmpty()

    override suspend fun prepare(selection: LocalAttachmentSelection): AttachmentDraft =
        (delegate ?: error("REMOTE_ATTACHMENT_OFFLINE")).prepare(selection)

    override suspend fun armSubmission(draftId: String, revision: Long): PendingSubmissionIntent =
        (delegate ?: error("REMOTE_ATTACHMENT_OFFLINE")).armSubmission(draftId, revision)

    override suspend fun cancelSubmission(intentId: String): CancelSubmissionResult =
        delegate?.cancelSubmission(intentId) ?: CancelSubmissionResult(false)

    override fun observe(draftId: String): Flow<AttachmentDraftState> =
        delegate?.observe(draftId)
            ?: flowOf(AttachmentDraftState(AttachmentDraftId(draftId), AttachmentState.RETRYABLE_FAILURE, errorMessage = "REMOTE_ATTACHMENT_OFFLINE"))

    override fun retry(draftId: String) {
        delegate?.retry(draftId)
    }

    override suspend fun discard(draftId: String) {
        delegate?.discard(draftId)
    }

    override suspend fun releaseAfterSubmit(draftId: String) {
        delegate?.releaseAfterSubmit(draftId)
    }

    override suspend fun archiveSubmitted(conversationId: String, draftId: String, media: HistoricalMediaPort) {
        (delegate ?: error("REMOTE_ATTACHMENT_OFFLINE")).archiveSubmitted(conversationId, draftId, media)
    }

    override suspend fun ensureSubmittedSource(conversationId: String, draftId: String, media: HistoricalMediaPort) {
        (delegate ?: error("REMOTE_ATTACHMENT_OFFLINE")).ensureSubmittedSource(conversationId, draftId, media)
    }

    override suspend fun retrySubmittedSource(conversationId: String, draftId: String, media: HistoricalMediaPort): AttachmentDraft =
        (delegate ?: error("REMOTE_ATTACHMENT_OFFLINE")).retrySubmittedSource(conversationId, draftId, media)

    override fun remoteAttachmentId(draftId: String): String? = delegate?.remoteAttachmentId(draftId)
}

/** 支持冷启动后在线补拉的命令目录仓储。 */
class HotSwappableCommandCatalogRepository(
    private val loader: suspend (gatewayId: String, languageCode: String) -> AgentCommandCatalog,
) : AgentCommandCatalogRepository {
    override suspend fun get(gatewayId: String, languageCode: String): AgentCommandCatalog =
        loader(gatewayId, languageCode)
}

/** 支持冷启动先本地初始化媒体缓存、建链后再接入认证源的热升级媒体数据源。 */
class HotSwappableHistoricalMediaSource : HistoricalMediaSource {
    @Volatile
    private var delegate: HistoricalMediaSource? = null
    private val lock = Any()
    private val observers = CopyOnWriteArrayList<(String, String, String) -> Unit>()
    private var registration: AutoCloseable? = null

    fun attach(source: HistoricalMediaSource) {
        synchronized(lock) {
            registration?.close()
            delegate = source
            registration = source.observeReferenceVersions { conversationId, attachmentId, version ->
                observers.forEach { observer -> observer(conversationId, attachmentId, version) }
            }
        }
    }

    fun detach() {
        synchronized(lock) {
            registration?.close()
            registration = null
            delegate = null
        }
    }

    override fun checkCurrent() {
        (delegate ?: error("REMOTE_MEDIA_OFFLINE")).checkCurrent()
    }

    override fun referenceVersion(conversationId: String, attachmentId: String): String? =
        delegate?.referenceVersion(conversationId, attachmentId)

    override fun referenceState(conversationId: String, attachmentId: String): HistoricalMediaReferenceState? =
        delegate?.referenceState(conversationId, attachmentId)

    override fun knownMetadata(conversationId: String, attachmentId: String): HistoricalMediaMetadata? =
        delegate?.knownMetadata(conversationId, attachmentId)

    override fun observeReferenceVersions(observer: (String, String, String) -> Unit): AutoCloseable {
        observers.add(observer)
        return AutoCloseable { observers.remove(observer) }
    }

    override fun <T> withReferenceVersion(conversationId: String, attachmentId: String, expected: String?, commit: () -> T): T =
        (delegate ?: throw IOException("REMOTE_MEDIA_OFFLINE")).withReferenceVersion(conversationId, attachmentId, expected, commit)

    override suspend fun metadata(conversationId: String, attachmentId: String): HistoricalMediaMetadata =
        (delegate ?: throw IOException("REMOTE_MEDIA_OFFLINE")).metadata(conversationId, attachmentId)

    override suspend fun consumeOriginal(metadata: HistoricalMediaMetadata, consume: (InputStream) -> Unit) {
        (delegate ?: throw IOException("REMOTE_MEDIA_OFFLINE")).consumeOriginal(metadata, consume)
    }
}
