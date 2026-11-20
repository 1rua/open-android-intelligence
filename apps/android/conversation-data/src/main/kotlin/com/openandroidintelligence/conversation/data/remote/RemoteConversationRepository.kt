package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.conversation.sanitizer.ContextInjectionFilter
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.gateway.remote.rpc.RemoteDeliveryOutcome
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 历史快照对账视图：显式区分“远端已证明空闲”与“分页响应缺失活跃轮次元数据”。 */
data class RemoteReconciliationSnapshot(
    val activeGenerationId: String?,
    val activityKnown: Boolean,
    val messages: List<TimelineMessage>,
)

/** 仓储拥有一个固定端点和连接代次；切换只能创建新实例，旧结果无法改写新分区。 */
class RemoteConversationRepository(
    val carrier: RemoteSessionCarrier,
    val workspace: RemoteWorkspace,
    private val parentScope: CoroutineScope,
    private val activeConversationId: () -> String?,
    private val attachmentContent: suspend (String, String) -> List<JsonValue> = { _, _ -> throw RemoteSessionException("REMOTE_ATTACHMENT_UNAVAILABLE") },
    private val clock: () -> Long = System::currentTimeMillis,
    private val mediaSource: RemoteAuthenticatedMediaSource? = null,
    private val onEvent: suspend (EndpointKey, VerifiedConversationEvent) -> Unit = { _, _ -> },
    private val onTimelineSnapshot: (EndpointKey, String, RemoteReconciliationSnapshot) -> Unit = { _, _, _ -> },
) : ConversationRepository, GenerationTracker, EventCommitBarrier, StreamHealthSource, MessageOutcomeQuery {
    private val connectionScope = carrier.channel.scope
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val projections = ConcurrentHashMap<String, RemoteTimelineProjection>()
    private val follows = ConcurrentHashMap<String, Job>()
    private val ready = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private enum class IdentityResolution { PROMPT_FINISHED, TURN_ENDED }
    private data class PendingTurn(val message: OutgoingMessage, val members: List<ClientMessageId>, var generation: String,
        val identityReady: CompletableDeferred<IdentityResolution>)
    private val pending = ConcurrentHashMap<String, LinkedHashMap<String, PendingTurn>>()
    private val batchMembers = ConcurrentHashMap<String, List<ClientMessageId>>()
    private val models = ConcurrentHashMap<String, RemoteModelSelection>()
    private val rows = ConcurrentHashMap<String, RemoteSessionRow>()
    private val announcedCommands = MutableStateFlow<Map<String, AgentCommandCatalog>>(emptyMap())
    private val commandLock = Any()
    private class CommandCreation {
        val candidates = mutableMapOf<String, AgentCommandCatalog>()
    }
    private val commandCreations = mutableSetOf<CommandCreation>()
    private val commandAnnouncements: Flow<RemoteCommandAnnouncement>?

    val commandCatalog: AgentCommandCatalogRepository = object : AgentCommandCatalogRepository {
        override suspend fun get(gatewayId: String, languageCode: String): AgentCommandCatalog =
            get(gatewayId, languageCode, activeConversationId())

        override suspend fun get(gatewayId: String, languageCode: String, conversationId: String?): AgentCommandCatalog {
            current()
            val nativeId = conversationId?.let(RemoteLocalIds::decode)
            val fetched = carrier.commands(nativeId)
            current()
            return conversationId?.let { announcedCommands.value[it] } ?: fetched ?: RemoteCommandCatalog.empty
        }

        override fun observe(gatewayId: String, conversationId: String?): Flow<AgentCommandCatalog> =
            if (conversationId == null) emptyFlow()
            else announcedCommands.mapNotNull { it[conversationId] }.distinctUntilChanged()
    }

    private fun acceptCommandAnnouncement(announcement: RemoteCommandAnnouncement) = synchronized(commandLock) {
        if (!isCurrent()) return@synchronized
        val id = try {
            RemoteLocalIds.encode(announcement.sessionId)
        } catch (_: IllegalArgumentException) {
            return@synchronized
        }
        if (rows.containsKey(id)) {
            announcedCommands.value += id to announcement.catalog
        } else {
            // 尚未确认的会话只能进入正在创建的请求，不能被目录读取或观察到。
            commandCreations.forEach { it.candidates[id] = announcement.catalog }
        }
    }

    private data class DispatchedEvent(val event: VerifiedConversationEvent, val ack: CompletableDeferred<Unit>)
    private val eventSubscribers = CopyOnWriteArrayList<Channel<DispatchedEvent>>()
    // 统一计数：包含会话发布临界区及已派发待订阅方处理完毕的在途事件，避免跨调度器短暂全零穿透。
    private val inFlightEventBarriers = MutableStateFlow(0)
    private val health = MutableStateFlow(StreamHealth.IDLE)
    override val streamHealth: Flow<StreamHealth> = health
    private val failure = MutableStateFlow<String?>(null)
    override val streamFailure: Flow<String?> = failure
    private val generation = RemoteGenerationState()
    override val generationSnapshot: StateFlow<GenerationSnapshot> = generation.snapshot
    override val generationId: StateFlow<String?> = generation.generationId
    override val generationState: StateFlow<GenerationState> = generation.generationState
    private val approvals = RemoteApprovalInbox(clock)
    private val held = ConcurrentHashMap<String, LinkedHashMap<String, OutgoingMessage>>()
    private val deliveryLocks = ConcurrentHashMap<String, Mutex>()
    // 串行化同一会话内的投影推进、事件发布与状态快照更新，防止回执与终态在不同协程间错位。
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()
    private val acceptedUserMessages = ConcurrentHashMap<Pair<String, String>, String>()

    override suspend fun awaitPendingEventCommits() {
        inFlightEventBarriers.first { it == 0 }
    }

    private suspend inline fun <T> withSessionLock(id: String, crossinline block: suspend () -> T): T =
        sessionLocks.computeIfAbsent(id) { Mutex() }.withLock {
            inFlightEventBarriers.update { it + 1 }
            try {
                block()
            } finally {
                inFlightEventBarriers.update { (it - 1).coerceAtLeast(0) }
            }
        }

    init {
        require(workspace.endpoint.key.backendId.value == carrier.backend) { "REMOTE_BACKEND_MISMATCH" }
        commandAnnouncements = carrier.commandAnnouncements(workspace)
        commandAnnouncements?.let { announcements ->
            // 同一订阅贯穿创建和实时阶段，避免等待开流时漏收或让旧帧覆盖新值。
            scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                announcements.collect(::acceptCommandAnnouncement)
            }
        }
    }
    private fun isCurrent() = lifetime.isActive && carrier.channel.scope == connectionScope
    private fun current() {
        check(isCurrent()) { "REMOTE_CONNECTION_REPLACED" }
    }
    private fun projection(id: String) = projections.computeIfAbsent(id) { RemoteTimelineProjection(it, clock = clock, mediaReference = mediaSource?.let { source -> source::accept }, persistentMessageSequence = carrier.backend == "harness", matchStartedGenerationToSubmission = carrier.backend != "codex") }
    fun activeConversationChanged() = generation.publish {
        val id = activeConversationId()
        val projection = id?.let(projections::get)
        projection?.let { synchronized(it) { GenerationSnapshot(it.generationId, it.generationState, id) } }
            ?: GenerationSnapshot(null, GenerationState.IDLE, id)
    }
    override suspend fun listConversations(scope: ConversationScope, page: PageRequest): ConversationPage {
        current()
        val result = carrier.sessions(workspace, page)
        synchronized(commandLock) {
            current()
            result.items.forEach { rows[RemoteLocalIds.encode(it.id)] = it }
        }
        result.items.forEach { mediaSource?.confirmSession(it.id) }
        return ConversationPage(
            result.items.map {
                ConversationSummary(
                    ConversationId(RemoteLocalIds.encode(it.id)),
                    ContextInjectionFilter.sanitizeSummaryText(it.title),
                    it.updatedAt,
                    it.archived,
                )
            },
            result.nextCursor,
        )
    }
    override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation {
        current()
        val creation = commandAnnouncements?.let {
            synchronized(commandLock) {
                current()
                CommandCreation().also { commandCreations.add(it) }
            }
        }
        try {
            val created = carrier.create(workspace)
            val localId = RemoteLocalIds.encode(created.id)
            synchronized(commandLock) {
                current()
                // 把会话确证和候选提升放在同一处，后到的已确证公告可直接覆盖。
                rows[localId] = created
                if (localId !in announcedCommands.value) creation?.candidates?.get(localId)?.let { catalog ->
                    announcedCommands.value += localId to catalog
                }
            }
            mediaSource?.confirmSession(created.id)
            return Conversation(ConversationId(localId), ContextInjectionFilter.sanitizeSummaryText(created.title), created.createdAt)
        } finally {
            if (creation != null) synchronized(commandLock) { commandCreations.remove(creation) }
        }
    }
    override suspend fun readConversation(conversationId: String): Conversation? =
        rows[conversationId]?.let { Conversation(ConversationId(conversationId), ContextInjectionFilter.sanitizeSummaryText(it.title), it.createdAt) }
    override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage =
        loadTimeline(conversationId, page, notifySnapshot = true).first

    /** 仅拉取并合并第 1 页权威快照，供通知对账器读取远端活跃轮次、元数据确定性与最新消息。 */
    suspend fun fetchReconciliationSnapshot(conversationId: String): RemoteReconciliationSnapshot {
        val (_, snapshot) = loadTimeline(conversationId, PageRequest(), notifySnapshot = false)
        return snapshot
    }

    private suspend fun loadTimeline(
        conversationId: String,
        page: PageRequest,
        notifySnapshot: Boolean,
    ): Pair<TimelinePage, RemoteReconciliationSnapshot> {
        current()
        ensureFollow(conversationId)
        val mediaRead = mediaSource?.beginHistoryRead()
        val result = carrier.history(RemoteLocalIds.decode(conversationId), page)
        current()
        val target = projection(conversationId)
        withSessionLock(conversationId) {
            synchronized(target) { result.records.forEach { target.accept(it, history = true, historyRead = mediaRead) } }
            activeConversationChanged()
        }
        val pageIds = result.records.mapNotNull { record ->
            val event = record.at("event") ?: record
            val data = event.at("data") ?: return@mapNotNull null
            (data.at("message") ?: data).text("id")
        }.toSet()
        val (snapshotRows, snapshotMessages) = synchronized(target) {
            target.rows.values.filter { it.id in pageIds } to target.rows.values.toList()
        }
        val snapshot = RemoteReconciliationSnapshot(result.activeGenerationId, result.activityKnown, snapshotMessages)
        if (notifySnapshot && page.cursor == null && carrier.backend in setOf("harness", "codex")) {
            val hasPending = pending[conversationId]?.let { turns -> synchronized(turns) { turns.isNotEmpty() } } == true
            val currentState = synchronized(target) { target.generationState }
            val authoritativeSnapshot = (result.revision != null && result.activityKnown) || result.activeGenerationId != null
            if (!hasPending && (authoritativeSnapshot || currentState !in setOf(GenerationState.QUEUED, GenerationState.RUNNING))) {
                onTimelineSnapshot(workspace.endpoint.key, conversationId, snapshot)
            }
        }
        return TimelinePage(snapshotRows, result.nextCursor, result.revision) to snapshot
    }
    private suspend fun ensureFollow(id: String) {
        val opened = ready.computeIfAbsent(id) { CompletableDeferred() }
        follows.computeIfAbsent(id) {
            scope.launch {
                health.value = StreamHealth.CONNECTING
                try {
                    val sessionId = RemoteLocalIds.decode(id)
                    carrier.followWithApprovalSource(sessionId).collect { frame ->
                        current()
                        if (frame.value.text("type") == "remote-stream-ready") {
                            health.value = StreamHealth.LIVE
                            opened.complete(Unit)
                            return@collect
                        }
                        withSessionLock(id) {
                            val target = projection(id)
                            val approvalEvents = if (SessionCapability.APPROVAL in carrier.capabilities) {
                                approvals.accept(id, sessionId, frame)
                            } else emptyList()
                            val (decoded, ended) = synchronized(target) {
                                val decoded = target.accept(frame.value)
                                if (approvalEvents.any { it is VerifiedConversationEvent.ApprovalRequested || it is VerifiedConversationEvent.ApprovalTextFallback }) {
                                    target.markApprovalRequested()
                                }
                                val ended = target.terminalGeneration?.takeIf { it.first == target.generationId }
                                    ?.let { it.first to target.submittedGenerationId }
                                decoded to ended
                            }
                            if (ended != null) pending[id]?.let { turns -> synchronized(turns) {
                                turns.values.filter { turn -> turn.generation == ended.first ||
                                    "request:${turn.message.clientMessageId.value}" == ended.second }
                                    .forEach { it.identityReady.complete(IdentityResolution.TURN_ENDED) }
                            } }
                            target.clientRequestId?.let { request -> pending[id]?.let { turns -> synchronized(turns) {
                                turns[request]?.let { turn -> target.generationId?.let { turn.generation = it } }
                            } } }
                            decoded.forEach { publish(id, it) }
                            approvalEvents.forEach { publish(id, it) }
                            val settledCount = settlePending(id, target)
                            if (ended != null && settledCount == 0) {
                                val hasPending = pending[id]?.let { turns -> synchronized(turns) { turns.isNotEmpty() } } == true
                                if (!hasPending) {
                                    synchronized(target) { target.terminal(ended.first) }?.let { terminal ->
                                        publishRemoteTurnTerminal(id, terminal)
                                    }
                                }
                            }
                            activeConversationChanged()
                        }
                        // 队列跟随原生任务状态，不依赖该轮是否由本机提交。
                        retryPendingDeliveries(id)
                    }
                } catch (cancelled: CancellationException) {
                    if (!opened.isCompleted) opened.completeExceptionally(cancelled)
                    throw cancelled
                } catch (cause: Exception) {
                    val code = (cause as? RemoteSessionException)?.code ?: "REMOTE_STREAM_FAILED"
                    failure.value = code
                    health.value = StreamHealth.FAILED
                    if (!opened.isCompleted) opened.completeExceptionally(cause)
                }
            }
        }
        opened.await()
    }
    private suspend fun publish(id: String, event: VerifiedConversationEvent) {
        current()
        if (event is VerifiedConversationEvent.TimelineUpsert &&
            ContextInjectionFilter.classifySender(event.message.sender) == ContextInjectionFilter.SenderRole.USER
        ) {
            event.message.clientMessageId?.let { clientId ->
                if (acceptedUserMessages.putIfAbsent(id to clientId.value, event.message.id) == null) {
                    publish(id, VerifiedConversationEvent.MessageAccepted("user-accepted:${clientId.value}", event.occurredAt,
                        event.message.id, clientId.value, ConversationId(id)))
                }
            }
        }
        onEvent(workspace.endpoint.key, event)
        current()
        for (channel in eventSubscribers.toList()) {
            val ack = CompletableDeferred<Unit>()
            inFlightEventBarriers.update { it + 1 }
            ack.invokeOnCompletion { inFlightEventBarriers.update { count -> (count - 1).coerceAtLeast(0) } }
            try {
                channel.send(DispatchedEvent(event, ack))
            } catch (_: ClosedSendChannelException) {
                ack.complete(Unit)
            } catch (cancelled: CancellationException) {
                ack.complete(Unit)
                currentCoroutineContext().ensureActive()
            }
        }
    }
    override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> = flow {
        val channel = Channel<DispatchedEvent>(
            capacity = Channel.RENDEZVOUS,
            onUndeliveredElement = { it.ack.complete(Unit) },
        )
        eventSubscribers.add(channel)
        try {
            for (item in channel) {
                try {
                    emit(item.event)
                } finally {
                    item.ack.complete(Unit)
                }
            }
        } finally {
            eventSubscribers.remove(channel)
            channel.cancel()
        }
    }
    override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance =
        submitMessage(activeConversationId() ?: throw RemoteSessionException("CONVERSATION_REQUIRED"), message)
    override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
        current()
        ensureFollow(conversationId)
        if (message.delivery == MessageDeliveryMode.STEER) return steerMessage(conversationId, message)
        if (message.delivery == MessageDeliveryMode.QUEUE) {
            val queuedResult = withSessionLock(conversationId) {
                val target = projection(conversationId)
                val busy = synchronized(target) { target.generationState in setOf(GenerationState.RUNNING, GenerationState.QUEUED) }
                if (!busy) return@withSessionLock null
                val queue = held.computeIfAbsent(conversationId) { linkedMapOf() }
                synchronized(queue) { queue[message.clientMessageId.value] = message.copy(delivery = MessageDeliveryMode.NORMAL) }
                publish(conversationId, VerifiedConversationEvent.PendingDeliveryUpdated("queue:${message.clientMessageId.value}", clock(),
                    PendingDelivery(message.clientMessageId, ConversationId(conversationId), message.text, message.attachmentIds.size, PendingDeliveryState.QUEUED)))
                MessageAcceptance(message.clientMessageId.value, message.clientMessageId.value, delivery = MessageDeliveryMode.QUEUE, authoritative = false)
            }
            if (queuedResult != null) return queuedResult
        }
        val content = buildList {
            if (message.text.isNotEmpty()) add(obj("type" to "text", "text" to message.text))
            message.attachmentIds.forEach { draftId ->
                try { addAll(attachmentContent(conversationId, draftId)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: RemoteRpcException) {
                    throw java.io.IOException("REMOTE_ATTACHMENT_MESSAGE_NOT_SENT:${failure.code}", failure)
                }
                catch (failure: java.io.IOException) {
                    // 尚未调用 prompt；附件未知不能伪装成消息已发，也不能使下一次创建新附件。
                    throw java.io.IOException("REMOTE_ATTACHMENT_MESSAGE_NOT_SENT:${failure.message}", failure)
                }
            }
        }
        if (content.isEmpty()) throw RemoteSessionException("INVALID_MESSAGE")
        val localGeneration = "request:${message.clientMessageId.value}"
        val turn = PendingTurn(message, batchMembers.remove(message.clientMessageId.value) ?: listOf(message.clientMessageId),
            localGeneration, CompletableDeferred(lifetime))
        val turns = pending.computeIfAbsent(conversationId) { linkedMapOf() }
        synchronized(turns) { check(turns.putIfAbsent(message.clientMessageId.value, turn) == null) { "REMOTE_SUBMISSION_ALREADY_PENDING" } }
        val target = projection(conversationId)
        withSessionLock(conversationId) {
            synchronized(target) {
                if (target.generationState !in setOf(GenerationState.RUNNING, GenerationState.QUEUED, GenerationState.OUTCOME_UNKNOWN))
                    target.beginTurn(localGeneration)
            }
            activeConversationChanged()
        }
        scope.launch {
            try {
                val result = carrier.prompt(RemoteLocalIds.decode(conversationId), message.clientMessageId.value, content, models[conversationId])
                current()
                val explicitGeneration = result.at("turn")?.text("id") ?: result.text("generationId")
                if (result.flag("accepted") == false) {
                    throw RemoteSessionException("REMOTE_PROMPT_NOT_ACCEPTED")
                }
                val settledInLock = withSessionLock(conversationId) {
                    val nativeGeneration = synchronized(target) {
                        explicitGeneration ?: target.resolveGeneration(turn.generation)
                    }
                    turn.generation = nativeGeneration
                    val identified = synchronized(target) {
                        target.bindSubmittedGeneration(localGeneration, nativeGeneration)
                        if (carrier.backend == "codex" && result.at("turn")?.text("id") != null)
                            target.bindUserRequest(nativeGeneration, message.clientMessageId) else emptyList()
                    }
                    identified.forEach { publish(conversationId, it) }
                    turn.identityReady.complete(IdentityResolution.PROMPT_FINISHED)
                    val stillCurrent = synchronized(turns) { turns[message.clientMessageId.value] === turn }
                    if (stillCurrent) {
                        turn.members.forEach { member -> publish(conversationId, VerifiedConversationEvent.MessageStatus("accepted:${member.value}", clock(),
                            ConversationId(conversationId), acceptedUserMessages[conversationId to member.value] ?: member.value,
                            member, AgentMessageStatus.DELIVERED, 1, null, nativeGeneration)) }
                        // 流可先于 RPC 回执完成；用持久到本连接结束的终态事实再次核对，而不是重启该轮。
                        settlePending(conversationId, target)
                    }
                    activeConversationChanged()
                    stillCurrent
                }
                if (settledInLock) retryPendingDeliveries(conversationId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) {
                // 已发出的 mutation 没有回执时保持原提交身份，不能画成可盲目重发的确定失败。
                failure.value = (cause as? RemoteSessionException)?.code ?: "REMOTE_SUBMISSION_OUTCOME_UNKNOWN"
                val rejected = (cause is RemoteRpcException && cause.deliveryOutcome == RemoteDeliveryOutcome.NOT_SENT) ||
                    cause is RemoteSessionException && cause.code == "REMOTE_PROMPT_NOT_ACCEPTED"
                withSessionLock(conversationId) {
                    if (rejected) {
                        synchronized(turns) { turns.remove(message.clientMessageId.value) }
                        turn.members.forEach { member -> publish(conversationId, VerifiedConversationEvent.MessageStatus("rejected:${member.value}", clock(),
                            ConversationId(conversationId), message.clientMessageId.value, member, AgentMessageStatus.FAILED,
                            2, AgentMessageErrorCode.AGENT_REQUEST_FAILED, localGeneration)) }
                        val code = if ((cause as? RemoteRpcException)?.deliveryOutcome == RemoteDeliveryOutcome.NOT_SENT)
                            "REMOTE_SUBMISSION_NOT_SENT" else "REMOTE_SUBMISSION_REJECTED"
                        turn.members.forEach { member -> publish(conversationId, VerifiedConversationEvent.PendingDeliveryUpdated("not-sent:${member.value}", clock(),
                            PendingDelivery(member, ConversationId(conversationId), message.text, message.attachmentIds.size,
                                PendingDeliveryState.FAILED, code, localGeneration))) }
                    } else {
                        synchronized(target) {
                            if (target.generationId == localGeneration) target.markUnknown()
                        }
                        turn.members.forEach { member -> publish(conversationId, VerifiedConversationEvent.PendingDeliveryUpdated("unknown:${member.value}", clock(),
                            PendingDelivery(member, ConversationId(conversationId), message.text, message.attachmentIds.size,
                                PendingDeliveryState.OUTCOME_UNKNOWN, failure.value, localGeneration))) }
                    }
                    turn.identityReady.complete(IdentityResolution.PROMPT_FINISHED)
                    activeConversationChanged()
                }
            } finally {
                // 身份等待依赖原 prompt 的成功、失败和超时；连接关闭由父任务直接取消。
                if (!turn.identityReady.isCompleted) turn.identityReady.complete(IdentityResolution.PROMPT_FINISHED)
            }
        }
        return MessageAcceptance(message.clientMessageId.value, message.clientMessageId.value, localGeneration,
            message.delivery, authoritative = false)
    }
    private suspend fun settlePending(id: String, target: RemoteTimelineProjection): Int {
        val turns = pending[id] ?: return 0
        val entries = synchronized(turns) { turns.values.mapNotNull { turn ->
            synchronized(target) { target.terminal(turn.generation) }?.let { turn to it }
        } }
        if (entries.isEmpty()) return 0
        var settled = 0
        entries.forEach { (entry, terminal) ->
            if (synchronized(turns) { turns.remove(entry.message.clientMessageId.value) } !== entry) return@forEach
            settled++
            if (terminal.state == GenerationState.CANCELLED) {
                publish(id, VerifiedConversationEvent.GenerationCancelled("cancelled:${entry.message.clientMessageId.value}", terminal.occurredAt,
                    terminal.generationId, ConversationId(id)))
                return@forEach
            }
            val status = when (terminal.state) {
                GenerationState.COMPLETED -> AgentMessageStatus.COMPLETED
                GenerationState.FAILED -> AgentMessageStatus.FAILED
                else -> return@forEach
            }
            entry.members.forEach { member -> publish(id, VerifiedConversationEvent.MessageStatus("terminal:${member.value}", terminal.occurredAt, ConversationId(id),
                acceptedUserMessages[id to member.value] ?: member.value, member, status, 2, if (status == AgentMessageStatus.FAILED) AgentMessageErrorCode.AGENT_REQUEST_FAILED else null,
                terminal.generationId)) }
        }
        return settled
    }
    /** 远端或恢复流中观测到的非本机发起轮次结束时，向持久化/通知宿补发同轮次终态事件。 */
    private suspend fun publishRemoteTurnTerminal(id: String, terminal: RemoteTimelineProjection.TerminalReceipt) {
        current()
        val event = when (terminal.state) {
            GenerationState.CANCELLED -> VerifiedConversationEvent.GenerationCancelled(
                "remote-cancelled:${terminal.generationId}", terminal.occurredAt, terminal.generationId, ConversationId(id),
            )
            GenerationState.COMPLETED, GenerationState.FAILED -> {
                val status = if (terminal.state == GenerationState.FAILED) AgentMessageStatus.FAILED else AgentMessageStatus.COMPLETED
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(terminal.generationId.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
                val syntheticUser = "remote-turn-$digest"
                VerifiedConversationEvent.MessageStatus(
                    "remote-terminal:${terminal.generationId}", terminal.occurredAt, ConversationId(id),
                    syntheticUser, ClientMessageId(syntheticUser), status, 2,
                    if (status == AgentMessageStatus.FAILED) AgentMessageErrorCode.AGENT_REQUEST_FAILED else null,
                    terminal.generationId,
                )
            }
            else -> return
        }
        onEvent(workspace.endpoint.key, event)
    }
    override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance =
        submitBatch(batch.clientConversationId ?: activeConversationId() ?: throw RemoteSessionException("CONVERSATION_REQUIRED"), batch)
    override suspend fun submitBatch(conversationId: String, batch: MessageBatch): BatchAcceptance {
        if (batch.messages.isEmpty()) throw RemoteSessionException("INVALID_MESSAGE")
        val first = batch.messages.first()
        val combined = first.copy(text = batch.messages.joinToString("\n") { it.text },
            attachmentIds = batch.messages.flatMap { it.attachmentIds })
        batchMembers[first.clientMessageId.value] = batch.messages.map { it.clientMessageId }
        val result = submitMessage(conversationId, combined)
        return BatchAcceptance(batch.batchId, batch.messages.associate { it.clientMessageId.value to result.messageId }, result.generationId, result.authoritative)
    }
    override suspend fun pendingDeliveries(conversationId: String): List<PendingDelivery> = held[conversationId]?.let { queue ->
        synchronized(queue) { queue.values.map { PendingDelivery(it.clientMessageId, ConversationId(conversationId), it.text, it.attachmentIds.size, PendingDeliveryState.QUEUED) } }
    }.orEmpty()
    override suspend fun retryPendingDeliveries(conversationId: String) {
        // 附件准备会挂起；同会话的其他触发者必须等队首建立任务后再判断是否继续。
        deliveryLocks.computeIfAbsent(conversationId) { Mutex() }.withLock {
            if (projection(conversationId).generationState in setOf(GenerationState.RUNNING, GenerationState.QUEUED, GenerationState.OUTCOME_UNKNOWN)) return@withLock
            val queue = held[conversationId] ?: return@withLock
            val next = synchronized(queue) { queue.entries.firstOrNull()?.also { queue.remove(it.key) }?.value } ?: return@withLock
            submitMessage(conversationId, next)
        }
    }
    override suspend fun retryAllPendingDeliveries() { held.keys.toList().forEach { retryPendingDeliveries(it) } }
    override suspend fun steerMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
        current()
        if (SessionCapability.STEER !in carrier.capabilities || message.attachmentIds.isNotEmpty()) throw RemoteSessionException("STEERING_UNSUPPORTED")
        val generation = projection(conversationId).generationId ?: throw RemoteSessionException("NO_RUNNING_GENERATION")
        carrier.steer(RemoteLocalIds.decode(conversationId), generation, message.clientMessageId.value, message.text)
        current()
        return MessageAcceptance(message.clientMessageId.value, message.clientMessageId.value, generation, MessageDeliveryMode.STEER, false)
    }
    override suspend fun cancelGeneration(generationId: String, requestId: String): CancelGenerationResult {
        val id = activeConversationId() ?: return CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        return cancelGeneration(id, generationId, requestId)
    }
    override suspend fun cancelGeneration(conversationId: String, generationId: String, requestId: String): CancelGenerationResult {
        current()
        if (SessionCapability.CANCEL !in carrier.capabilities) return CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        val target = projection(conversationId)
        val identity = synchronized(target) {
            // Codex 的 start 通知不含 clientRequestId，只有本次 prompt 回执可以绑定临时身份。
            // 允许已显示真实 turn 的同轮临时请求继续等待，但绝不借用该 turn 发中断。
            val unresolved = target.isUnconfirmedGeneration(generationId)
            if (!unresolved || carrier.backend != "codex") {
                target.cancellationGeneration(generationId) ?: throw RemoteSessionException("STALE_GENERATION")
            } else if (target.generationState !in setOf(GenerationState.QUEUED, GenerationState.RUNNING, GenerationState.OUTCOME_UNKNOWN)) {
                throw RemoteSessionException("STALE_GENERATION")
            }
            unresolved
        }
        if (carrier.backend == "codex" && identity) {
            val turns = pending[conversationId] ?: throw RemoteSessionException("STALE_GENERATION")
            val request = synchronized(turns) { turns[generationId.removePrefix("request:")] }
                ?: throw RemoteSessionException("STALE_GENERATION")
            if (request.identityReady.await() == IdentityResolution.TURN_ENDED) throw RemoteSessionException("STALE_GENERATION")
            current()
        }
        val nativeGeneration = synchronized(target) {
            if (carrier.backend == "codex" && target.isUnconfirmedGeneration(generationId)) {
                if (target.generationState !in setOf(GenerationState.QUEUED, GenerationState.RUNNING, GenerationState.OUTCOME_UNKNOWN))
                    throw RemoteSessionException("STALE_GENERATION")
                return CancelGenerationResult(CancelGenerationOutcome.OUTCOME_UNKNOWN, "REMOTE_GENERATION_UNCONFIRMED")
            }
            target.cancellationGeneration(generationId) ?: throw RemoteSessionException("STALE_GENERATION")
        }
        // 此处到载体调用之间没有挂起；仅 session/cancel 的后端不提供服务端原子轮次比较。
        carrier.interrupt(RemoteLocalIds.decode(conversationId), nativeGeneration)
        current()
        return CancelGenerationResult(CancelGenerationOutcome.OUTCOME_UNKNOWN)
    }
    override suspend fun queryMessage(conversationId: String, clientMessageId: ClientMessageId): TimelineMessage? {
        current()
        // 原生后端没有按客户端请求身份查询的公开接口；投影缺项不能证明原 prompt 未执行。
        return synchronized(projection(conversationId)) {
            projection(conversationId).rows.values.firstOrNull { it.clientMessageId == clientMessageId }
        } ?: throw java.io.IOException("REMOTE_MESSAGE_QUERY_UNAVAILABLE")
    }
    override suspend fun submitApprovalDecision(approvalId: ApprovalId, choice: ApprovalChoice): ApprovalSubmissionResult {
        if (SessionCapability.APPROVAL !in carrier.capabilities || choice !in setOf(ApprovalChoice.ONCE, ApprovalChoice.DENY)) {
            return ApprovalSubmissionResult(ApprovalSubmissionOutcome.UNSUPPORTED)
        }
        return approvals.submit(approvalId, choice) { id ->
            lifetime.isActive && carrier.channel.scope == connectionScope && activeConversationId() == id
        }
    }
    override suspend fun updateTitle(conversationId: String, title: String): Boolean { current(); carrier.rename(RemoteLocalIds.decode(conversationId), title); current(); return true }
    override suspend fun setArchived(conversationId: String, archived: Boolean) { current(); carrier.archive(RemoteLocalIds.decode(conversationId), archived); current() }
    suspend fun modelCatalog(sessionId: String): RemoteModelCatalog { current(); return carrier.models(RemoteLocalIds.decode(sessionId)).also { current() } }
    suspend fun selectModel(sessionId: String, selection: RemoteModelSelection): RemoteModelSelection {
        current()
        return carrier.selectModel(RemoteLocalIds.decode(sessionId), selection).also { current(); models[sessionId] = it }
    }
    suspend fun close() {
        generation.close()
        eventSubscribers.toList().forEach { it.cancel() }
        eventSubscribers.clear()
        lifetime.cancelAndJoin()
        synchronized(commandLock) {
            commandCreations.clear()
            announcedCommands.value = emptyMap()
        }
        health.value = StreamHealth.IDLE
        approvals.clear()
        pending.clear()
        acceptedUserMessages.clear()
        sessionLocks.clear()
        deliveryLocks.clear()
    }
}
