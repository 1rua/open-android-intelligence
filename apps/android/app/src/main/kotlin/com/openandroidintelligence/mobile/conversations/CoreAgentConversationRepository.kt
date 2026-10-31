package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.model.StreamHealth
import com.openandroidintelligence.conversation.model.StreamHealthSource
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import com.openandroidintelligence.conversation.ports.BatchAcceptance
import com.openandroidintelligence.conversation.ports.CancelGenerationOutcome
import com.openandroidintelligence.conversation.ports.CancelGenerationResult
import com.openandroidintelligence.conversation.ports.Conversation
import com.openandroidintelligence.conversation.ports.ConversationOrganization
import com.openandroidintelligence.conversation.ports.ConversationPage
import com.openandroidintelligence.conversation.ports.ConversationRepository
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.ports.GenerationTracker
import com.openandroidintelligence.conversation.ports.MessageAcceptance
import com.openandroidintelligence.conversation.ports.MessageBatch
import com.openandroidintelligence.conversation.ports.MessageOutcomeQuery
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.ports.TimelinePage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.gateway.agents.AgentSessionClient
import com.openandroidintelligence.gateway.agents.CoreAgent
import com.openandroidintelligence.gateway.agents.AgentSessionException
import com.openandroidintelligence.gateway.agents.NativeAgentStreamEvent
import com.openandroidintelligence.gateway.agents.NativeGenerationReceipt
import com.openandroidintelligence.gateway.agents.NativeAgentSession
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.openandroidintelligence.gateway.approvals.ApprovalDecision
import com.openandroidintelligence.gateway.approvals.ApprovalDecisionAudit
import com.openandroidintelligence.gateway.approvals.ApprovalDecisionOutcome
import com.openandroidintelligence.conversation.data.GatewayEventDecoder
import com.openandroidintelligence.conversation.model.ApprovalId
import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.ApprovalOutcome
import com.openandroidintelligence.conversation.model.ApprovalSubmissionResult
import com.openandroidintelligence.conversation.model.ApprovalSubmissionOutcome
import com.openandroidintelligence.conversation.ports.MessageDeliveryMode
import com.openandroidintelligence.conversation.ports.PendingDelivery
import com.openandroidintelligence.conversation.ports.PendingDeliveryState
import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import java.time.Instant
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.math.BigDecimal
import java.util.UUID

private const val MAX_APPROVAL_FALLBACKS = 32

/**
 * Bridges the unified OAI Core /agents interface with the Material Design 3 ConversationRepository port.
 * Allows WorkbenchController to drive native Agent conversations seamlessly without UI degradation.
 */
class CoreAgentConversationRepository(
    private val client: AgentSessionClient,
    private val currentAgent: () -> CoreAgent?,
    private val activeConversationId: () -> String? = { null },
    private val parentScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val supportsApprovalCards: Boolean = false,
    private val supportsGenerationCancel: Boolean = true,
    private val approvalAudit: ApprovalDecisionAudit? = null,
    private val organizationStore: NativeConversationOrganizationStore = MemoryNativeConversationOrganizationStore(),
    private val storeForAgent: ((CoreAgent) -> NativeConversationStore)? = null,
) : ConversationRepository, GenerationTracker, StreamHealthSource, MessageOutcomeQuery,
    com.openandroidintelligence.conversation.ports.ConversationCreationRecovery {

    private val _generationId = MutableStateFlow<String?>(null)
    override val generationId: StateFlow<String?> = _generationId.asStateFlow()

    private val _generationState = MutableStateFlow(GenerationState.IDLE)
    override val generationState: StateFlow<GenerationState> = _generationState.asStateFlow()

    private val _streamHealth = MutableStateFlow<StreamHealth>(StreamHealth.IDLE)
    override val streamHealth: Flow<StreamHealth> = _streamHealth.asStateFlow()
    private val _streamFailure = MutableStateFlow<String?>(null)
    override val streamFailure: Flow<String?> = _streamFailure.asStateFlow()

    private data class AgentEvent(val agentId: String, val event: VerifiedConversationEvent)
    // 4 帧：消费者偶发慢一拍时仍能按序播放中间帧，而不是立刻退化成"只剩最后一帧"。
    // 再大就会长期保留多份完整正文副本（内存代价由内容长度决定）。
    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 4)

    private suspend fun emitEvent(agentId: String, event: VerifiedConversationEvent) {
        val agent = currentAgent()?.takeIf { it.id == agentId }
            ?: turns.values.firstOrNull { it.agent.id == agentId }?.agent
            ?: recoveringTurns.values.firstOrNull { it.agent.id == agentId }?.agent
        val intermediate = event is VerifiedConversationEvent.TimelineUpsert && event.message.state == "STREAMING"
        if (agent != null && !intermediate) persistEvent(agent, event)
        publishEvent(agentId, event)
    }

    private suspend fun persistEvent(agent: CoreAgent, event: VerifiedConversationEvent) {
        try {
            storeForAgent?.invoke(agent)?.let { store ->
                store.commitEvents(listOf(event))
                GatewayLog.protocolEvidence("native.event_saved", mapOf("agentId" to agent.id, "eventId" to event.eventId))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw NativePersistenceFailed(failure) }
    }

    private class NativePersistenceFailed(cause: Exception) : Exception("LOCAL_SAVE_FAILED", cause)

    private suspend fun publishEvent(agentId: String, event: VerifiedConversationEvent) {
        _events.emit(AgentEvent(agentId, event))
        GatewayLog.protocolEvidence("T7.event_emitted", mapOf("agentId" to agentId,
            "eventId" to event.eventId, "eventType" to event.javaClass.simpleName))
    }

    private class NativeTurn(val agent: CoreAgent, val conversationId: String, val clientMessageId: String? = null) {
        @Volatile var state: GenerationState = GenerationState.QUEUED
        @Volatile var userMessageId: String? = null
        @Volatile var receiptRevision: Long = -1
        val messageRevisions = ConcurrentHashMap<String, Long>()
        @Volatile var generationId: String? = if (agent.kind == "hermes") null else "local_${UUID.randomUUID()}"
        @Volatile var hostGenerationId: String? = null
        @Volatile var cancelled: Boolean = false
    }
    private data class ApprovalRoute(val agent: CoreAgent, val conversationId: String, val choices: Set<String>)
    private val turns = ConcurrentHashMap<Pair<String, String>, NativeTurn>()
    private val recoveringTurns = ConcurrentHashMap<Pair<String, String>, NativeTurn>()
    private val recoveryJobs = ConcurrentHashMap<Pair<String, String>, Job>()
    private val settledClients = ConcurrentHashMap<Pair<String, String>, String>()
    private val ambiguousEventGenerations = ConcurrentHashMap<Pair<String, String>, Map<String, NativeGenerationReceipt>>()
    private val approvals = ConcurrentHashMap<String, ApprovalRoute>()
    private val resolvedApprovals = Collections.synchronizedSet(mutableSetOf<Pair<String, String>>())
    private val approvalFallbacks =
        ConcurrentHashMap<String, MutableList<VerifiedConversationEvent.ApprovalTextFallback>>()
    private val generationRoutes = Collections.synchronizedMap(linkedMapOf<String, NativeTurn>())

    private fun rememberGeneration(turn: NativeTurn, generationId: String, fromHost: Boolean = true) {
        turn.generationId = generationId
        if (fromHost) turn.hostGenerationId = generationId
        synchronized(generationRoutes) {
            generationRoutes[generationId] = turn
            if (generationRoutes.size > 1024) {
                val entries = generationRoutes.entries.iterator()
                while (entries.hasNext() && generationRoutes.size > 1024) {
                    val old = entries.next().value
                    if (turns[old.agent.id to old.conversationId] !== old) entries.remove()
                }
            }
        }
    }

    private fun ownsTurn(turn: NativeTurn): Boolean {
        val key = turn.agent.id to turn.conversationId
        return turns[key] === turn || recoveringTurns[key] === turn
    }

    private fun isSelected(turn: NativeTurn): Boolean =
        currentAgent()?.id == turn.agent.id && activeConversationId() == turn.conversationId && ownsTurn(turn)

    fun activeConversationChanged() {
        val agentId = currentAgent()?.id
        val conversationId = activeConversationId()
        val turn = if (agentId != null && conversationId != null) turns[agentId to conversationId] ?: recoveringTurns[agentId to conversationId] else null
        _generationId.value = turn?.generationId
        _generationState.value = when {
            ambiguousEventGenerations.containsKey(agentId to conversationId) -> GenerationState.OUTCOME_UNKNOWN
            turn == null -> GenerationState.IDLE
            turn.cancelled -> GenerationState.CANCELLED
            else -> turn.state
        }
        _streamHealth.value = if (turn == null) StreamHealth.IDLE else
            if (recoveringTurns[agentId to conversationId] === turn) StreamHealth.RECONNECTING else StreamHealth.CONNECTING
        if (turn != null && recoveringTurns[agentId to conversationId] === turn) startRecovery(turn)
        restartEventSync()
    }

    /** A Core connection transition restarts reads only; it never submits a prompt. */
    fun connectionRestored() {
        recoveringTurns.values.filterNot { it.agent.capabilities.eventSync }.forEach(::startRecovery)
        restartEventSync(force = true)
    }

    private class EventSyncOwner(val agent: CoreAgent, val conversationId: String) {
        lateinit var job: Job
    }
    // Repository 的 client、parentScope 和 store 已固定账号；每个 Agent/会话各有一个读取所有者。
    private val eventSyncOwners = ConcurrentHashMap<Pair<String, String>, EventSyncOwner>()

    private fun eventSyncNeeded(key: Pair<String, String>): Boolean =
        currentAgent()?.id == key.first && activeConversationId() == key.second ||
            turns.containsKey(key) || recoveringTurns.containsKey(key) ||
            ambiguousEventGenerations.containsKey(key) || queuedSends.containsKey(key)

    @Synchronized
    private fun restartEventSync(force: Boolean = false) {
        val desired = linkedMapOf<Pair<String, String>, CoreAgent>()
        (turns.values + recoveringTurns.values).forEach { desired[it.agent.id to it.conversationId] = it.agent }
        eventSyncOwners.forEach { (key, owner) -> if (eventSyncNeeded(key)) desired[key] = owner.agent }
        val selected = currentAgent()
        val conversationId = activeConversationId()
        if (selected != null && conversationId != null) desired[selected.id to conversationId] = selected
        eventSyncOwners.forEach { (key, owner) ->
            if (!eventSyncNeeded(key) && eventSyncOwners.remove(key, owner)) owner.job.cancel()
        }
        desired.forEach { (key, agent) -> startEventSync(agent, key.second, force) }
    }

    @Synchronized
    private fun startEventSync(agent: CoreAgent, conversationId: String, force: Boolean = false) {
        if (!agent.capabilities.eventSync || storeForAgent == null) return
        val key = agent.id to conversationId
        val previous = eventSyncOwners[key]
        if (!force && previous?.job?.isActive == true) return
        val owner = EventSyncOwner(agent, conversationId)
        previous?.job?.cancel()
        if (currentAgent()?.id == agent.id && activeConversationId() == conversationId) _streamFailure.value = null
        owner.job = parentScope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            previous?.job?.join()
            fun requireOwner() {
                if (eventSyncOwners[key] !== owner || !parentScope.isActive)
                    throw CancellationException("NATIVE_EVENT_OWNER_CHANGED")
            }
            try {
                requireOwner()
                val store = storeForAgent.invoke(agent)
                var committedSnapshot: RecoverySnapshot? = null
                val sync = NativeEventSynchronizer(client, agent, conversationId, store,
                    refresh = { page ->
                        val snapshot = recoverySnapshot(agent, conversationId, page)
                        committedSnapshot = snapshot
                        snapshot.events
                    },
                    publish = { publishEvent(agent.id, it) },
                    publishApproval = { approval ->
                        val turn = NativeTurn(agent, conversationId)
                        emitApproval(turn, approval.approval, approval.type == "approval_requested") { publishEvent(agent.id, it) }
                    },
                    committed = {
                        requireOwner()
                        committedSnapshot?.let { reconcileCommittedSnapshot(agent, conversationId, it) }
                        committedSnapshot = null
                    }, checkOwner = ::requireOwner)
                var failures = 0
                while (isActive) {
                    try {
                        val more = sync.synchronize(if (failures == 0) 25000 else 0)
                        failures = 0
                        if (currentAgent()?.id == agent.id && activeConversationId() == conversationId) {
                            _streamFailure.value = null
                            _streamHealth.value = StreamHealth.LIVE
                        }
                        if (!eventSyncNeeded(agent.id to conversationId)) return@launch
                        if (!more) delay(50)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        failures++
                        val cause = (failure as? NativeEventSyncFailure)?.cause ?: failure
                        val code = (cause as? AgentSessionException)?.code ?: cause.javaClass.simpleName
                        val transient = cause is java.io.IOException || cause is AgentSessionException && cause.code in setOf(
                            "AGENT_TIMEOUT", "AGENT_UNAVAILABLE", "EVENT_ACK_UNAVAILABLE")
                        val retryable = transient && failures < 6
                        if (currentAgent()?.id == agent.id && activeConversationId() == conversationId) {
                            _streamHealth.value = if (retryable) StreamHealth.RECONNECTING else StreamHealth.FAILED
                            _streamFailure.value = if (retryable) null else
                                "NATIVE_EVENT_SYNC_FAILED:${(failure as? NativeEventSyncFailure)?.stage ?: "READ_FAILED"}:$code"
                        }
                        GatewayLog.w("OaiNativeEvents", "sync failed code=$code attempt=$failures")
                        if (!retryable) return@launch
                        delay((250L shl (failures - 1)).coerceAtMost(8000))
                    }
                }
            } finally {
                if (!eventSyncNeeded(key)) eventSyncOwners.remove(key, owner)
            }
        }
        eventSyncOwners[key] = owner
        owner.job.start()
    }

    private suspend fun trackedRecoveryIdentities(agent: CoreAgent, conversationId: String): List<NativeRecoveryIdentity> {
        val key = agent.id to conversationId
        val turn = turns[key] ?: recoveringTurns[key]
        val tracked = turn?.clientMessageId?.let { listOf(NativeRecoveryIdentity(it, turn.hostGenerationId)) }.orEmpty()
        val ambiguous = ambiguousEventGenerations[key]?.values.orEmpty().map { NativeRecoveryIdentity(it.clientMessageId, it.generationId) }
        return (tracked + ambiguous + storeForAgent?.invoke(agent)?.recoveryIdentities(conversationId).orEmpty()).distinct()
    }

    private data class RecoverySnapshot(val events: List<VerifiedConversationEvent>, val receipts: List<NativeGenerationReceipt>,
        val currentGenerationId: String? = null)

    private suspend fun recoverySnapshot(agent: CoreAgent, conversationId: String,
        page: com.openandroidintelligence.gateway.agents.NativeEventPage): RecoverySnapshot {
        val receipts = mutableListOf<NativeGenerationReceipt>()
        val history = client.history(agent, conversationId)
        val facts = history.map { native ->
            val message = timelineMessage(native, conversationId)
            VerifiedConversationEvent.TimelineUpsert("native-history:${agent.id}:$conversationId:${message.id}:${message.contentRevision}",
                System.currentTimeMillis(), message.contentRevision ?: 0, message)
        }.toMutableList<VerifiedConversationEvent>()
        for (native in history) {
            val generationId = native.generationId ?: continue
            if (native.executionSteps.isEmpty()) continue
            val revision = native.executionSteps.maxOf { it.revision }
            facts += VerifiedConversationEvent.ExecutionStepsUpdated("native-tools:$generationId:$revision", System.currentTimeMillis(),
                ConversationId(conversationId), generationId, revision, timelineMessage(native, conversationId).executionSteps,
                native.clientMessageId, native.id.takeIf { native.role == "user" })
        }
        if (agent.capabilities.generationRecovery) {
            val identities = linkedMapOf<String, NativeRecoveryIdentity>()
            trackedRecoveryIdentities(agent, conversationId).forEach { identities[it.clientMessageId] = it }
            // Bootstrap 补齐所有明确绑定的轮次；不把原生/steering 的 client ID 当作受理凭据。
            if (page.approvals != null) history.filter { it.role == "user" && it.generationId != null }.forEach { message ->
                message.clientMessageId?.let { identities[it] = NativeRecoveryIdentity(it, message.generationId) }
            }
            for (event in page.events) {
                val payload = JsonFields.obj(JsonFields.field(event, "payload")) ?: continue
                val generation = JsonFields.string(payload, "generationId")
                val clientId = JsonFields.string(payload, "clientMessageId")
                if (clientId != null) identities[clientId] = NativeRecoveryIdentity(clientId, generation)
                else history.filter { it.role == "user" && it.clientMessageId != null &&
                    (generation != null && it.generationId == generation || it.id == JsonFields.string(event, "correlationId"))
                }.forEach { message ->
                    val associatedClient = requireNotNull(message.clientMessageId)
                    identities[associatedClient] = NativeRecoveryIdentity(associatedClient, message.generationId)
                }
            }
            for ((clientId, identity) in identities) {
                val receipt = try { client.generation(agent, conversationId, clientId, identity.generationId) }
                catch (failure: AgentSessionException) {
                    // 缺少 ledger 是缺少执行证据：保留正文与未知状态，不阻止其他轮次同步。
                    if (failure.code == "GENERATION_NOT_FOUND") continue
                    throw failure
                }
                receipts += receipt
                facts += VerifiedConversationEvent.MessageAccepted("native-receipt:${receipt.generationId}:${receipt.revision}",
                    System.currentTimeMillis(), receipt.userMessageId, clientId, ConversationId(conversationId), agentReceived = false)
                if (receipt.state == "cancelled") facts += VerifiedConversationEvent.GenerationCancelled(
                    "native-cancel:${receipt.generationId}:${receipt.revision}", System.currentTimeMillis(), receipt.generationId, ConversationId(conversationId))
                else if (receipt.state != "unknown") facts += VerifiedConversationEvent.MessageStatus(
                    "native-status:${receipt.generationId}:${receipt.revision}", System.currentTimeMillis(), ConversationId(conversationId),
                    receipt.userMessageId, ClientMessageId(clientId), when (receipt.state) {
                        "completed" -> AgentMessageStatus.COMPLETED
                        "failed" -> AgentMessageStatus.FAILED
                        "queued" -> AgentMessageStatus.QUEUED
                        else -> AgentMessageStatus.DELIVERED
                    }, receipt.revision, null, receipt.generationId)
            }
        }
        // 生命周期修订只在各自 generation 内比较。多条未结回执必须询问明确活动身份，
        // 不用历史顺序、消息时间或不同轮次之间的 revision 大小推断。
        val pending = receipts.filter { it.state in setOf("queued", "running", "unknown") }
        val current = if (pending.size > 1 && agent.online && agent.capabilities.send)
            client.currentGeneration(agent, conversationId)?.generationId else null
        return RecoverySnapshot(facts, receipts, current)
    }

    private fun reconcileCommittedSnapshot(agent: CoreAgent, conversationId: String, snapshot: RecoverySnapshot) {
        val key = agent.id to conversationId
        val pending = snapshot.receipts.filter { it.state in setOf("queued", "running", "unknown") }
        val unresolved = ambiguousEventGenerations[key]
        if (unresolved != null) {
            val reconciled = unresolved.toMutableMap()
            snapshot.receipts.forEach { receipt ->
                val prior = reconciled[receipt.generationId]
                if (prior == null && receipt in pending || prior != null && prior.clientMessageId == receipt.clientMessageId &&
                    receipt.revision >= prior.revision && (prior.state !in setOf("completed", "failed", "cancelled") ||
                        receipt.state in setOf("completed", "failed", "cancelled"))) reconciled[receipt.generationId] = receipt
            }
            // Missing/404/空 history 不是终态。保留每条原 generation 身份及其已落盘修订，直到全部证实结束。
            ambiguousEventGenerations[key] = reconciled
            if (reconciled.values.any { it.state !in setOf("completed", "failed", "cancelled") }) return
            synchronized(recoveryJobs) {
                reconciled.values.forEach { receipt ->
                    val turn = generationRoutes[receipt.generationId] ?: NativeTurn(agent, conversationId, receipt.clientMessageId)
                    rememberGeneration(turn, receipt.generationId)
                    turn.receiptRevision = receipt.revision
                    turn.userMessageId = receipt.userMessageId
                    turn.state = when (receipt.state) {
                        "completed" -> GenerationState.COMPLETED
                        "failed" -> GenerationState.FAILED
                        else -> GenerationState.CANCELLED
                    }
                    settledClients[key] = receipt.clientMessageId
                }
                ambiguousEventGenerations.remove(key)
                queueUnknownBarriers.remove(key)
                if (currentAgent()?.id == agent.id && activeConversationId() == conversationId &&
                    turns[key] == null && recoveringTurns[key] == null) {
                    _generationId.value = null
                    _generationState.value = GenerationState.IDLE
                    _streamHealth.value = StreamHealth.IDLE
                }
            }
            parentScope.launch(ioDispatcher) { drainQueuedSends(agent, conversationId) }
            return
        }
        val activeClient = (turns[key] ?: recoveringTurns[key])?.clientMessageId
        val selected = if (activeClient != null) snapshot.receipts.singleOrNull { it.clientMessageId == activeClient }
            else if (pending.size > 1) pending.singleOrNull { it.generationId == snapshot.currentGenerationId }
            else pending.singleOrNull() ?: snapshot.receipts.singleOrNull()
        if (selected != null) {
            applyCommittedReceipt(agent, selected)
        } else if (activeClient == null && pending.size > 1) {
            ambiguousEventGenerations[key] = pending.associateBy { it.generationId }
            if (currentAgent()?.id == agent.id && activeConversationId() == conversationId) {
                _generationId.value = null
                _generationState.value = GenerationState.OUTCOME_UNKNOWN
            }
        }
    }

    /** 仅由已成功提交本机镜像的事件页调用；迟到回执不能占用另一轮的发送槽。 */
    private fun applyCommittedReceipt(agent: CoreAgent, receipt: NativeGenerationReceipt) {
        val key = agent.id to receipt.conversationId
        val terminal = receipt.state in setOf("completed", "failed", "cancelled")
        var drain = false
        synchronized(recoveryJobs) {
            val active = turns[key] ?: recoveringTurns[key]
            if (active != null && active.clientMessageId != receipt.clientMessageId) return
            val remembered = generationRoutes[receipt.generationId]?.takeIf {
                it.agent.id == agent.id && it.conversationId == receipt.conversationId && it.clientMessageId == receipt.clientMessageId
            }
            val turn = active ?: remembered ?: NativeTurn(agent, receipt.conversationId, receipt.clientMessageId)
            if (receipt.revision < turn.receiptRevision || turn.hostGenerationId?.let { it != receipt.generationId } == true) return
            if (turn.state in setOf(GenerationState.COMPLETED, GenerationState.FAILED, GenerationState.CANCELLED) && !terminal) return
            rememberGeneration(turn, receipt.generationId)
            turn.receiptRevision = receipt.revision
            turn.userMessageId = receipt.userMessageId
            turn.state = when (receipt.state) {
                "queued" -> GenerationState.QUEUED
                "running" -> GenerationState.RUNNING
                "completed" -> GenerationState.COMPLETED
                "failed" -> GenerationState.FAILED
                "cancelled" -> GenerationState.CANCELLED
                else -> GenerationState.OUTCOME_UNKNOWN
            }
            if (terminal) {
                settledClients[key] = receipt.clientMessageId
                queueUnknownBarriers.remove(key)
                turns.remove(key, turn)
                recoveringTurns.remove(key, turn)
                recoveryJobs.remove(key)?.cancel()
                drain = true
            } else {
                recoveringTurns[key] = turn
            }
            if (currentAgent()?.id == agent.id && activeConversationId() == receipt.conversationId) {
                _generationState.value = turn.state
                _generationId.value = receipt.generationId.takeUnless { terminal }
                _streamHealth.value = if (terminal) StreamHealth.IDLE else StreamHealth.LIVE
                _streamFailure.value = null
            }
        }
        if (drain) parentScope.launch(ioDispatcher) { drainQueuedSends(agent, receipt.conversationId) }
    }

    private suspend fun emitApproval(
        turn: NativeTurn,
        payload: JsonValue.JObject,
        requested: Boolean,
        deliver: suspend (VerifiedConversationEvent) -> Unit = { emitEvent(turn.agent.id, it) },
    ) {
        val payloadConversation = JsonFields.string(payload, "conversationId")
        if (payloadConversation != null && payloadConversation != turn.conversationId) {
            // A mismatched frame could move another native session's command
            // into this thread, so keep that boundary fail-closed.
            throw AgentSessionException("AGENT_STREAM_INVALID")
        }
        val rawId = JsonFields.string(payload, "approvalId")
        val store = storeForAgent?.invoke(turn.agent)
        if (rawId != null) {
            val key = turn.agent.id to rawId
            val wasResolved = key in resolvedApprovals || store?.loadApprovals(turn.conversationId)?.any {
                it.type == "approval_resolved" && JsonFields.string(it.approval, "approvalId") == rawId
            } == true
            if (requested && wasResolved) return
            val scopedPayload = if (payloadConversation == null) payload.copy(fields = payload.fields +
                ("conversationId" to JsonValue.JString(turn.conversationId))) else payload
            val item = com.openandroidintelligence.gateway.agents.NativeEventApproval(
                if (requested) "approval_requested" else "approval_resolved", scopedPayload)
            if (runCatching { NativeEventSynchronizer.approvalEvent(item, turn.conversationId) }.isSuccess) {
                try { store?.commitApprovals(turn.conversationId, listOf(item)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { throw NativePersistenceFailed(failure) }
                if (!requested) resolvedApprovals.add(key)
            }
        }
        if (!requested) {
            val rawApprovalId = JsonFields.string(payload, "approvalId")
            val hasFallback = rawApprovalId != null && approvalFallbacks[turn.conversationId]
                ?.any { it.approvalId?.value == rawApprovalId } == true
            if ((!supportsApprovalCards || !turn.agent.capabilities.approvals) && !hasFallback) return
            runCatching {
                GatewayEventDecoder.decode(GatewayEvent("evt_${UUID.randomUUID()}", "conversation.approval.resolved",
                    Json.canonical(Json.of(mapOf("payload" to payload)))))
            }.getOrNull()?.let { resolved ->
                if (resolved is VerifiedConversationEvent.ApprovalResolved) {
                    approvalFallbacks[resolved.conversationId?.value ?: turn.conversationId]?.removeAll {
                        it.approvalId == resolved.approvalId
                    }
                }
                deliver(resolved)
            }
            return
        }
        if (supportsApprovalCards && turn.agent.capabilities.approvals) {
            val decoded = runCatching {
                GatewayEventDecoder.decode(GatewayEvent("evt_${UUID.randomUUID()}", "conversation.approval.requested",
                    Json.canonical(Json.of(mapOf("payload" to payload)))))
            }.getOrNull()
            if (decoded is VerifiedConversationEvent.ApprovalRequested) {
                approvals[decoded.request.approvalId.value] = ApprovalRoute(
                    turn.agent,
                    turn.conversationId,
                    decoded.request.options.map { it.choice.wireValue }.toSet(),
                )
                deliver(decoded)
                return
            }
        }
        val fallback = approvalTextFallback(
            turn.conversationId,
            payload,
            cardNegotiated = supportsApprovalCards && turn.agent.capabilities.approvals,
        )
        approvalFallbacks.getOrPut(turn.conversationId) {
            Collections.synchronizedList(mutableListOf())
        }.apply {
            removeAll { existing -> fallback.approvalId != null && existing.approvalId == fallback.approvalId }
            add(fallback)
            while (size > MAX_APPROVAL_FALLBACKS) removeAt(0)
        }
        deliver(fallback)
    }

    private fun approvalTextFallback(
        conversationId: String,
        payload: JsonValue.JObject,
        cardNegotiated: Boolean,
    ): VerifiedConversationEvent.ApprovalTextFallback {
        val rawId = JsonFields.string(payload, "approvalId")
        val approvalId = rawId?.let { runCatching { ApprovalId(it) }.getOrNull() }
        val choices = JsonFields.array(JsonFields.field(payload, "options"))?.items.orEmpty().mapNotNull { option ->
            JsonFields.obj(option)?.let { JsonFields.string(it, "choice") }
        }.toSet()
        val recognizedChoices = choices.intersect(setOf("once", "session", "always", "deny"))
        val commands = buildList {
            if ("once" in choices || recognizedChoices.isEmpty()) add("/approve")
            if ("session" in choices) add("/approve session")
            if ("always" in choices) add("/approve always")
            if ("deny" in choices || recognizedChoices.isEmpty()) add("/deny")
        }.distinct()
        return VerifiedConversationEvent.ApprovalTextFallback(
            eventId = "evt_${UUID.randomUUID()}",
            occurredAt = System.currentTimeMillis(),
            conversationId = ConversationId(conversationId),
            approvalId = approvalId,
            command = JsonFields.string(payload, "command").orEmpty(),
            reason = JsonFields.string(payload, "reason").orEmpty(),
            commands = commands,
            diagnosticCode = if (cardNegotiated) "APPROVAL_PAYLOAD_INVALID" else "APPROVAL_CARD_UNSUPPORTED",
        )
    }

    override suspend fun pendingApprovalFallbacks(
        conversationId: String,
    ): List<VerifiedConversationEvent.ApprovalTextFallback> = synchronized(approvalFallbacks) {
        approvalFallbacks[conversationId].orEmpty().toList()
    }

    private fun requireAgent(): CoreAgent {
        return currentAgent() ?: throw IllegalStateException("NO_ACTIVE_AGENT_SELECTED")
    }

    override suspend fun listConversations(scope: ConversationScope, page: PageRequest): ConversationPage {
        val agent = requireAgent()
        if (!agent.capabilities.list) {
            return ConversationPage(emptyList(), null)
        }
        val sessions = withContext(ioDispatcher) { client.sessions(agent) }
        val summaries = sessions.map { session ->
            val displayTitle = session.title?.takeIf { it.isNotBlank() } ?: "新对话"
            ConversationSummary(
                id = ConversationId(session.id),
                title = displayTitle,
                updatedAt = parseDate(session.updatedAt),
                archived = session.archived,
            )
        }
        return ConversationPage(conversations = summaries, nextCursor = null).also { page ->
            storeForAgent?.invoke(agent)?.saveConversations(page)
        }
    }

    private class CreationAttempt {
        val mutex = Mutex()
        var attempted = false
        var session: NativeAgentSession? = null
    }
    private val creations = ConcurrentHashMap<Triple<ConversationScope, String, String>, CreationAttempt>()
    private val creationPublicationLock = Any()
    private var creationPublication: Job? = null

    private fun publishCreation(agent: CoreAgent, event: VerifiedConversationEvent.ConversationsChanged) {
        val job = synchronized(creationPublicationLock) {
            val previous = creationPublication
            parentScope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
                previous?.join()
                currentCoroutineContext().ensureActive()
                if (currentAgent()?.id == agent.id) publishEvent(agent.id, event)
            }.also { creationPublication = it }
        }
        job.start()
    }

    override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation =
        createOrRecover(scope, clientConversationId, recoveryOnly = false)

    override suspend fun recoverConversationCreation(scope: ConversationScope, clientConversationId: String): Conversation =
        createOrRecover(scope, clientConversationId, recoveryOnly = true)

    private suspend fun createOrRecover(scope: ConversationScope, clientId: String, recoveryOnly: Boolean): Conversation {
        val agent = requireAgent()
        val attempt = creations.getOrPut(Triple(scope, agent.id, clientId)) { CreationAttempt() }
        return attempt.mutex.withLock {
            val store = storeForAgent?.invoke(agent)
            val saved = withContext(ioDispatcher) { store?.creation(clientId) }
            val created = withContext(ioDispatcher) {
                attempt.session ?: saved?.session ?: if (attempt.attempted || recoveryOnly) {
                    recoverCreation(agent, clientId)
                } else {
                    // 先标记：连接被取消或响应解析失败，也不能丢掉可能已受理的身份。
                    attempt.attempted = true
                    try { client.create(agent, requestId = clientId) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        if (failure is AgentSessionException && failure.status in 400..499 && failure.status !in setOf(408, 409)) throw failure
                        recoverCreation(agent, clientId)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (currentAgent()?.id != agent.id) throw CancellationException("NATIVE_CREATE_OWNER_CHANGED")
            attempt.session = created
            val conversation = Conversation(ConversationId(created.id), created.title?.takeIf(String::isNotBlank) ?: "新对话", parseDate(created.updatedAt))
            val receipt = saved ?: NativeCreationReceipt(created, "evt_${UUID.randomUUID()}", System.currentTimeMillis())
            // 回执、通知身份和列表增量同事务保存；UI 消费者不能拖住权威 ID 返回。
            withContext(ioDispatcher) { store?.commitCreation(clientId, receipt) }
            GatewayLog.protocolEvidence("native.creation_saved", mapOf("agentId" to agent.id,
                "requestId" to clientId, "conversationId" to created.id, "eventId" to receipt.eventId))
            val event = VerifiedConversationEvent.ConversationsChanged(receipt.eventId, receipt.occurredAt, conversation.id, deleted = false)
            publishCreation(agent, event)
            conversation
        }
    }

    private suspend fun recoverCreation(agent: CoreAgent, clientId: String): NativeAgentSession {
        if (!agent.capabilities.operationRecovery) throw AgentSessionException("OUTCOME_UNKNOWN")
        repeat(4) { index ->
            val result = try { client.operation(agent, clientId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure is AgentSessionException && failure.status != 404) throw failure
                throw AgentSessionException("OUTCOME_UNKNOWN")
            }
            when (result.state) {
                "resolved" -> return requireNotNull(result.session)
                "unknown" -> throw AgentSessionException("OUTCOME_UNKNOWN")
            }
            if (index < 3) delay(250L shl index)
        }
        throw AgentSessionException("OUTCOME_UNKNOWN")
    }

    override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage {
        val agent = requireAgent()
        if (!agent.capabilities.history) {
            return TimelinePage(emptyList(), null)
        }
        val messages = withContext(ioDispatcher) { client.history(agent, conversationId) }
        val timelineMessages = messages.map { timelineMessage(it, conversationId) }
        storeForAgent?.invoke(agent)?.let { store ->
            store.commitEvents(timelineMessages.map { message -> VerifiedConversationEvent.TimelineUpsert(
                "native-read:${agent.id}:${message.id}:${message.contentRevision}", System.currentTimeMillis(), message.contentRevision ?: 0, message) })
            store.loadApprovals(conversationId).forEach { approval ->
                emitApproval(NativeTurn(agent, conversationId), approval.approval, approval.type == "approval_requested") { publishEvent(agent.id, it) }
            }
        }
        if (agent.capabilities.generationRecovery && !agent.capabilities.eventSync && turns[agent.id to conversationId] == null &&
            recoveringTurns[agent.id to conversationId] == null) {
            val known = trackedRecoveryIdentities(agent, conversationId).lastOrNull()
                ?: messages.lastOrNull { it.role == "user" && it.clientMessageId != null && it.generationId != null }
                    ?.let { NativeRecoveryIdentity(requireNotNull(it.clientMessageId), it.generationId) }
            known?.let { identity ->
                if (settledClients[agent.id to conversationId] != identity.clientMessageId) {
                    val turn = NativeTurn(agent, conversationId, identity.clientMessageId)
                    identity.generationId?.let { rememberGeneration(turn, it) }
                    startRecovery(turn)
                }
            }
        }
        return TimelinePage(messages = timelineMessages, nextCursor = null)
    }

    private fun timelineMessage(msg: com.openandroidintelligence.gateway.agents.NativeAgentMessage,
        conversationId: String): TimelineMessage {
            val isUser = msg.role.equals("user", ignoreCase = true)
            return TimelineMessage(
                id = msg.id,
                sender = msg.role.lowercase().takeIf { it in setOf("user", "assistant", "system", "tool") } ?: "system",
                parts = buildList {
                    if (msg.text.isNotEmpty()) add(MessagePart.Text(msg.text))
                    msg.attachments.forEach { attachment -> add(MessagePart.Attachment(
                        com.openandroidintelligence.conversation.model.AttachmentDraftId(attachment.id),
                        attachment.filename, attachment.mediaType, attachment.sizeBytes, attachment.sha256)) }
                },
                timestamp = parseDate(msg.createdAt),
                conversationId = ConversationId(conversationId),
                clientMessageId = msg.clientMessageId?.let { ClientMessageId(if (isUser) it else "assistant_$it") },
                generationId = msg.generationId,
                contentRevision = msg.contentRevision,
                state = msg.state ?: "CONFIRMED",
                executionSteps = msg.executionSteps.map { step ->
                    com.openandroidintelligence.conversation.model.NativeExecutionStep(
                        step.stepId, step.eventId, step.toolName, step.arguments, step.output,
                        runCatching { com.openandroidintelligence.conversation.model.NativeExecutionStatus.valueOf(step.status.uppercase()) }
                            .getOrDefault(com.openandroidintelligence.conversation.model.NativeExecutionStatus.UNKNOWN),
                        step.revision,
                    )
                },
            )
        }

    private suspend fun restoreHistory(turn: NativeTurn) {
        val history = client.history(turn.agent, turn.conversationId)
        for (native in history) {
            val message = timelineMessage(native, turn.conversationId)
            val previous = turn.messageRevisions[message.id] ?: -1L
            val revision = message.contentRevision ?: (previous + 1)
            // A delayed host snapshot cannot overwrite a newer live snapshot.
            if (revision < previous) continue
            turn.messageRevisions[message.id] = revision
            emitEvent(turn.agent.id, VerifiedConversationEvent.TimelineUpsert(
                "history:${turn.agent.id}:${turn.conversationId}:${message.id}:$revision:${turn.receiptRevision}",
                System.currentTimeMillis(), revision, message))
        }
    }

    private fun startRecovery(turn: NativeTurn) {
        val clientId = turn.clientMessageId ?: return
        if (!turn.agent.capabilities.generationRecovery) return
        val key = turn.agent.id to turn.conversationId
        if (turn.agent.capabilities.eventSync && storeForAgent != null) {
            synchronized(recoveryJobs) {
                if (turns[key]?.let { it !== turn } == true || recoveringTurns[key]?.let { it !== turn } == true ||
                    settledClients[key] == clientId) return
                recoveringTurns[key] = turn
            }
            startEventSync(turn.agent, turn.conversationId, force = true)
            return
        }
        val job = synchronized(recoveryJobs) {
            // A historical lookup cannot take ownership from another live client.
            if (turns[key]?.let { it !== turn } == true || recoveringTurns[key]?.let { it !== turn } == true) return
            if (recoveryJobs[key]?.isActive == true || recoveryJobs[key]?.isCompleted == false) return
            recoveringTurns[key] = turn
            parentScope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
                var failures = 0
                var unchangedUnknown = 0
                var drainAfterRecovery = false
                try {
                    while (isActive) {
                        try {
                            val previousRevision = turn.receiptRevision
                            val receipt = client.generation(turn.agent, turn.conversationId, clientId,
                                turn.hostGenerationId, previousRevision.takeIf { it >= 0 },
                                if (previousRevision >= 0) 25000 else 0)
                            if (receipt.revision < previousRevision) throw AgentSessionException("AGENT_RESPONSE_INVALID")
                            if (turn.state in setOf(GenerationState.COMPLETED, GenerationState.FAILED, GenerationState.CANCELLED) &&
                                receipt.state !in setOf("completed", "failed", "cancelled"))
                                throw AgentSessionException("AGENT_RESPONSE_INVALID")
                            rememberGeneration(turn, receipt.generationId)
                            turn.userMessageId = receipt.userMessageId
                            turn.receiptRevision = receipt.revision
                            if (isSelected(turn)) {
                                _generationId.value = receipt.generationId
                                _streamHealth.value = StreamHealth.LIVE
                            }
                            emitEvent(turn.agent.id, VerifiedConversationEvent.MessageAccepted(
                                "receipt:${receipt.generationId}:${receipt.revision}:accepted", System.currentTimeMillis(),
                                receipt.userMessageId, clientId, ConversationId(turn.conversationId), agentReceived = false))
                            // Snapshot compensation restores all real messages and structured steps, including
                            // earlier non-prefix edits in a generation with more than one assistant message.
                            restoreHistory(turn)
                            turn.state = when (receipt.state) {
                                "queued" -> GenerationState.QUEUED
                                "running" -> GenerationState.RUNNING
                                "completed" -> GenerationState.COMPLETED
                                "failed" -> GenerationState.FAILED
                                "cancelled" -> GenerationState.CANCELLED
                                else -> GenerationState.OUTCOME_UNKNOWN
                            }
                            if (isSelected(turn)) _generationState.value = turn.state
                            val terminal = receipt.state in setOf("completed", "failed", "cancelled")
                            if (receipt.state == "cancelled") {
                                emitEvent(turn.agent.id, VerifiedConversationEvent.GenerationCancelled(
                                    "receipt:${receipt.generationId}:${receipt.revision}:cancelled", System.currentTimeMillis(),
                                    receipt.generationId, ConversationId(turn.conversationId)))
                            } else if (receipt.state != "unknown") {
                                emitEvent(turn.agent.id, VerifiedConversationEvent.MessageStatus(
                                    "receipt:${receipt.generationId}:${receipt.revision}:status", System.currentTimeMillis(),
                                    ConversationId(turn.conversationId), receipt.userMessageId, ClientMessageId(clientId),
                                    when (receipt.state) {
                                        "completed" -> AgentMessageStatus.COMPLETED
                                        "failed" -> AgentMessageStatus.FAILED
                                        "queued" -> AgentMessageStatus.QUEUED
                                        else -> AgentMessageStatus.DELIVERED
                                    }, receipt.revision, null, receipt.generationId))
                            }
                            // GET 成功不代表本机提交成功；整轮落地后才重置失败预算。
                            failures = 0
                            if (isSelected(turn)) _streamFailure.value = null
                            GatewayLog.protocolEvidence("T11.generation_reconciled", mapOf("agentId" to turn.agent.id,
                                "conversationId" to turn.conversationId, "clientMessageId" to clientId,
                                "generationId" to receipt.generationId, "state" to receipt.state, "revision" to receipt.revision))
                            if (terminal) {
                                settledClients[key] = clientId
                                queueUnknownBarriers.remove(key)
                                if (isSelected(turn)) { _generationId.value = null; _streamHealth.value = StreamHealth.IDLE }
                                drainAfterRecovery = true
                                return@launch
                            }
                            unchangedUnknown = if (receipt.state == "unknown" && receipt.revision == previousRevision)
                                unchangedUnknown + 1 else 0
                            if (unchangedUnknown >= 3) {
                                if (isSelected(turn)) _streamHealth.value = StreamHealth.FAILED
                                return@launch
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            failures++
                            GatewayLog.w("OaiLatency", "recovery.failed agent=${turn.agent.id} conversation=${turn.conversationId} " +
                                "client=$clientId type=${failure.javaClass.simpleName} code=${(failure as? AgentSessionException)?.code} attempt=$failures")
                            if (turn.state !in setOf(GenerationState.COMPLETED, GenerationState.FAILED, GenerationState.CANCELLED))
                                turn.state = GenerationState.OUTCOME_UNKNOWN
                            if (isSelected(turn)) {
                                _generationState.value = turn.state
                                _streamHealth.value = StreamHealth.RECONNECTING
                                _streamFailure.value = "NATIVE_EVENT_SYNC_FAILED:GENERATION_RECOVERY:${failure.message ?: failure.javaClass.simpleName}"
                            }
                            if (failures >= 6 || (failure as? AgentSessionException)?.code in setOf(
                                    "SESSION_REVOKED", "UNSUPPORTED", "UNSUPPORTED_CAPABILITY", "GENERATION_NOT_FOUND", "AGENT_RESPONSE_INVALID")) {
                                if (isSelected(turn)) _streamHealth.value = StreamHealth.FAILED
                                return@launch
                            }
                            // Only transient read failures retry, with a finite increasing backoff.
                            delay((250L shl (failures - 1)).coerceAtMost(8000))
                        }
                    }
                } finally {
                    val completedJob = currentCoroutineContext()[Job]
                    synchronized(recoveryJobs) {
                        recoveryJobs.remove(key, completedJob)
                        if (drainAfterRecovery) recoveringTurns.remove(key, turn)
                    }
                    // Relinquish both recovery slots before a fast queued turn
                    // can complete and request its own reconciliation.
                    if (drainAfterRecovery && isActive) {
                        parentScope.launch(ioDispatcher) { drainQueuedSends(turn.agent, turn.conversationId) }
                    }
                }
            }.also { recoveryJobs[key] = it }
        }
        job.start()
    }

    override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance {
        val convId = batch.clientConversationId ?: activeConversationId() ?: error("NO_CONVERSATION")
        if (batch.messages.size != 1) throw AgentSessionException("UNSUPPORTED_CAPABILITY")
        val result = submitMessage(convId, batch.messages.single())
        return BatchAcceptance(
            batchId = UUID.randomUUID().toString(),
            memberIds = mapOf(batch.messages.first().clientMessageId.value to result.messageId),
            generationId = result.generationId,
        )
    }

    override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance {
        val convId = activeConversationId() ?: error("NO_ACTIVE_CONVERSATION")
        return submitMessage(convId, message)
    }

    override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
        if (message.delivery == MessageDeliveryMode.STEER) return steerMessage(conversationId, message)
        return submitMessageForAgent(requireAgent(), conversationId, message)
    }

    private suspend fun submitMessageForAgent(agent: CoreAgent, conversationId: String, message: OutgoingMessage): MessageAcceptance {
        val key = agent.id to conversationId
        val turn = NativeTurn(agent, conversationId, message.clientMessageId.value)
        val previous = synchronized(recoveryJobs) {
            if (ambiguousEventGenerations.containsKey(key)) throw AgentSessionException("OUTCOME_UNKNOWN")
            val recovering = recoveringTurns[key]
            if (recovering != null) {
                if (message.delivery != MessageDeliveryMode.QUEUE || recovering.cancelled ||
                    recovering.state !in setOf(GenerationState.RUNNING, GenerationState.QUEUED) || recovering.hostGenerationId == null)
                    throw AgentSessionException("OUTCOME_UNKNOWN")
                recovering
            } else turns.putIfAbsent(key, turn)
        }
        if (previous != null) {
            if (message.delivery != MessageDeliveryMode.QUEUE) {
                return steerMessage(conversationId, message)
            }
            enqueueSend(agent, key, message)
            GatewayLog.d("OaiLatency",
                "send.queued client=${message.clientMessageId.value} conversation=$conversationId")
            return MessageAcceptance(
                message.clientMessageId.value,
                message.clientMessageId.value,
                null,
                MessageDeliveryMode.QUEUE,
            )
        }
        // 发送到首帧的耗时是"等待接收"体感的主要来源：记录阶段时间点便于定位回归。
        val startedAt = System.nanoTime()
        GatewayLog.d("OaiLatency", "send.start client=${message.clientMessageId.value} conversation=$conversationId")
        turn.generationId?.let { rememberGeneration(turn, it, fromHost = false) }
        var messageId = message.clientMessageId.value
        var userAccepted = false
        var assistantMsgId = "stream_${message.clientMessageId.value}"
        var assistantHasNativeId = false
        var firstDeltaLogged = false
        var assistantTimestamp = System.currentTimeMillis()
        if (isSelected(turn)) {
            _generationId.value = turn.generationId
            _generationState.value = if (turn.generationId == null) GenerationState.QUEUED else GenerationState.RUNNING
            _streamHealth.value = StreamHealth.CONNECTING
        }
        GatewayLog.protocolEvidence("send.scheduled", mapOf("agentId" to agent.id,
            "conversationId" to conversationId, "clientMessageId" to message.clientMessageId.value))
        parentScope.launch(ioDispatcher) {
            GatewayLog.protocolEvidence("send.dispatched", mapOf("agentId" to agent.id,
                "conversationId" to conversationId, "clientMessageId" to message.clientMessageId.value))
            var drainAfterTurn = false
            var recoverAfterTurn = false
            var nativeRevision: Long? = null
            var nativeEventId: String? = null
            var nativeExecutionRevision = -1L
            val text = StringBuilder()
            // Coalesce complete message snapshots, never raw deltas. UI backpressure
            // can delay this publisher but cannot suspend the HTTP/SSE reader.
            // Each distinct native message and each control event survives conflation.
            val pending = linkedMapOf<String, VerifiedConversationEvent>()
            val snapshots = Channel<List<VerifiedConversationEvent>>(Channel.CONFLATED)
            var publicationFailure: NativePersistenceFailed? = null
            val publisher = launch {
                val delivered = mutableMapOf<String, String>()
                for (snapshot in snapshots) for (event in snapshot) {
                    val id = when (event) {
                        is VerifiedConversationEvent.TimelineUpsert -> "message:${event.message.id}"
                        is VerifiedConversationEvent.ExecutionStepsUpdated -> "execution:${event.generationId}"
                        else -> event.eventId
                    }
                    if (delivered[id] != event.eventId) {
                        try { emitEvent(agent.id, event) }
                        catch (failure: NativePersistenceFailed) { publicationFailure = failure; return@launch }
                        delivered[id] = event.eventId
                    }
                }
            }
            fun enqueue(key: String, event: VerifiedConversationEvent) {
                pending[key] = event
                snapshots.trySend(pending.values.toList()).getOrThrow()
            }
            var revision = 0L
            var lastSnapshotNanos = 0L
            var lastPublishedState: String? = null
            var lastPublishedLength = -1
            fun publish(state: String, associateClient: Boolean = true) {
                val current = text.toString()
                // 没有任何可见变化时不占用管道：每一帧都应该携带新内容，否则
                // 真正的打字帧会被同内容的空帧挤掉。
                if (state == lastPublishedState && current.length == lastPublishedLength) return
                lastPublishedState = state
                lastPublishedLength = current.length
                val contentRevision = nativeRevision ?: maxOf(++revision, (turn.messageRevisions[assistantMsgId] ?: -1L) + 1)
                turn.messageRevisions[assistantMsgId] = contentRevision
                enqueue("message:$assistantMsgId", VerifiedConversationEvent.TimelineUpsert(
                    eventId = "${nativeEventId ?: "evt_${UUID.randomUUID()}"}:$state", occurredAt = System.currentTimeMillis(),
                    revision = contentRevision, message = TimelineMessage(
                        id = assistantMsgId, sender = "assistant", parts = listOf(MessagePart.Text(current)),
                        timestamp = assistantTimestamp, state = state,
                        clientMessageId = if (associateClient) ClientMessageId("assistant_${message.clientMessageId.value}") else null,
                        contentRevision = nativeRevision, localPlaceholder = !assistantHasNativeId,
                        generationId = turn.generationId, conversationId = ConversationId(conversationId))))
            }
            suspend fun flush() {
                snapshots.close()
                publisher.join()
                publicationFailure?.let { failure -> publicationFailure = null; throw failure }
            }
            suspend fun cancelled() {
                val id = turn.generationId ?: return
                emitEvent(agent.id, VerifiedConversationEvent.GenerationCancelled("evt_${UUID.randomUUID()}",
                    System.currentTimeMillis(), id, ConversationId(conversationId)))
                turn.state = GenerationState.CANCELLED
                if (isSelected(turn)) _generationState.value = GenerationState.CANCELLED
            }
            try {
                val result = client.send(agent, conversationId, message.text,
                    attachmentIds = message.attachmentIds, clientMessageId = message.clientMessageId.value, onEvent = { event ->
                    when (event) {
                        is NativeAgentStreamEvent.MessageIdentity -> {
                            if (isSelected(turn)) _streamHealth.value = StreamHealth.LIVE
                            event.messageId?.let { remoteId ->
                                if (remoteId != assistantMsgId) {
                                    if (assistantHasNativeId && text.isNotEmpty()) {
                                        publish("CONFIRMED", associateClient = false)
                                        text.clear()
                                        assistantTimestamp = System.currentTimeMillis()
                                    }
                                    if (!assistantHasNativeId) pending.remove("message:$assistantMsgId")
                                    assistantMsgId = remoteId
                                    assistantHasNativeId = true
                                    lastSnapshotNanos = 0L
                                    lastPublishedState = null
                                    lastPublishedLength = -1
                                }
                            }
                            nativeRevision = event.contentRevision
                            nativeEventId = event.eventId
                            event.userMessageId?.let { remoteId ->
                                turn.userMessageId = remoteId
                                if (!userAccepted || remoteId != messageId) {
                                    messageId = remoteId
                                    userAccepted = true
                                    enqueue("accepted", VerifiedConversationEvent.MessageAccepted("evt_${UUID.randomUUID()}",
                                        System.currentTimeMillis(), remoteId, message.clientMessageId.value, ConversationId(conversationId),
                                        agentReceived = false))
                                }
                                GatewayLog.d("OaiLatency",
                                    "accept.identity elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000} client=${message.clientMessageId.value}")
                            }
                        }
                        is NativeAgentStreamEvent.Generation -> {
                            rememberGeneration(turn, event.generationId)
                            val lifecycleRevision = event.revision
                            val lifecycleState = event.state
                            if (!turn.cancelled && turn.state !in setOf(GenerationState.COMPLETED, GenerationState.FAILED, GenerationState.CANCELLED)) {
                                val advancesLifecycle = lifecycleState != null && lifecycleRevision != null && lifecycleRevision > turn.receiptRevision
                                val legacyGeneration = lifecycleState == null && event.executionSteps == null
                                if (advancesLifecycle || legacyGeneration) {
                                    if (advancesLifecycle) turn.receiptRevision = requireNotNull(lifecycleRevision)
                                    turn.state = when (lifecycleState) {
                                        "queued" -> GenerationState.QUEUED
                                        "unknown" -> GenerationState.OUTCOME_UNKNOWN
                                        else -> GenerationState.RUNNING
                                    }
                                    if (isSelected(turn)) {
                                        _generationId.value = event.generationId
                                        _generationState.value = turn.state
                                    }
                                    if (advancesLifecycle && lifecycleState in setOf("queued", "running")) {
                                        val acceptedId = event.userMessageId ?: turn.userMessageId
                                        if (acceptedId != null) enqueue("delivery:${event.generationId}", VerifiedConversationEvent.MessageStatus(
                                            "lifecycle:${event.generationId}:$lifecycleRevision", System.currentTimeMillis(),
                                            ConversationId(conversationId), acceptedId, message.clientMessageId,
                                            if (lifecycleState == "queued") AgentMessageStatus.QUEUED else AgentMessageStatus.DELIVERED,
                                            requireNotNull(lifecycleRevision), null, event.generationId))
                                    }
                                }
                            }
                            val executionRevision = event.executionRevision
                            val executionSteps = event.executionSteps
                            if (executionSteps != null && executionRevision != null && executionRevision > nativeExecutionRevision) {
                                nativeExecutionRevision = executionRevision
                                enqueue("execution:${event.generationId}", VerifiedConversationEvent.ExecutionStepsUpdated(
                                    requireNotNull(event.eventId), System.currentTimeMillis(), ConversationId(conversationId),
                                    event.generationId, executionRevision, executionSteps.map { step ->
                                        com.openandroidintelligence.conversation.model.NativeExecutionStep(
                                            step.stepId, step.eventId, step.toolName, step.arguments, step.output,
                                            runCatching { com.openandroidintelligence.conversation.model.NativeExecutionStatus.valueOf(step.status.uppercase()) }
                                                .getOrDefault(com.openandroidintelligence.conversation.model.NativeExecutionStatus.UNKNOWN),
                                            step.revision)
                                    }, event.clientMessageId, event.userMessageId))
                            }
                        }
                        is NativeAgentStreamEvent.ApprovalRequested -> emitApproval(turn, event.approval, true) { enqueue(it.eventId, it) }
                        is NativeAgentStreamEvent.ApprovalResolved -> emitApproval(turn, event.approval, false) { enqueue(it.eventId, it) }
                    }
                }) { delta ->
                    if (!turn.cancelled) {
                        if (!firstDeltaLogged && delta.isNotEmpty()) {
                            firstDeltaLogged = true
                            GatewayLog.d("OaiLatency",
                                "first.delta elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000} client=${message.clientMessageId.value}")
                        }
                        if (isSelected(turn)) _streamHealth.value = StreamHealth.LIVE
                        text.append(delta)
                        val now = System.nanoTime()
                        if (lastSnapshotNanos == 0L || now - lastSnapshotNanos >= 33_000_000L) {
                            publish("STREAMING")
                            lastSnapshotNanos = now
                        }
                    }
                }
                result.generationId?.let { rememberGeneration(turn, it) }
                result.revision?.let { turn.receiptRevision = maxOf(turn.receiptRevision, it) }
                result.text?.let { text.clear(); text.append(it) }
                GatewayLog.d("OaiLatency",
                    "stream.done outcome=${result.outcome} elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000} client=${message.clientMessageId.value}")
                if (result.outcome == "cancelled" || turn.cancelled) {
                    publish("CANCELLED")
                    flush()
                    cancelled()
                    drainAfterTurn = true
                    recoverAfterTurn = agent.capabilities.generationRecovery
                } else if (result.outcome != "completed") {
                    publish("OUTCOME_UNKNOWN")
                    flush()
                    queueUnknownBarriers.add(key)
                    turn.state = GenerationState.OUTCOME_UNKNOWN
                    recoverAfterTurn = true
                    if (isSelected(turn)) _generationState.value = GenerationState.OUTCOME_UNKNOWN
                } else {
                    publish("CONFIRMED")
                    flush()
                    emitEvent(agent.id, VerifiedConversationEvent.MessageStatus("evt_${UUID.randomUUID()}",
                        System.currentTimeMillis(), ConversationId(conversationId), messageId,
                        message.clientMessageId, AgentMessageStatus.COMPLETED, result.revision ?: 1L, null, turn.generationId))
                    turn.state = GenerationState.COMPLETED
                    if (isSelected(turn)) _generationState.value = GenerationState.COMPLETED
                    drainAfterTurn = true
                    recoverAfterTurn = agent.capabilities.generationRecovery
                }
            } catch (cancelled: CancellationException) {
                // Closing a reader says nothing about whether Hermes stopped.
                throw cancelled
            } catch (failure: Exception) {
                GatewayLog.w("OaiLatency", "turn.failed client=${message.clientMessageId.value} " +
                    "type=${failure.javaClass.simpleName} code=${(failure as? AgentSessionException)?.code}")
                if (settledClients[key] == turn.clientMessageId) { drainAfterTurn = true }
                else if (turn.cancelled) { publish("CANCELLED"); flush(); cancelled() }
                else if (failure is NativePersistenceFailed) {
                    queueUnknownBarriers.add(key)
                    turn.state = GenerationState.OUTCOME_UNKNOWN
                    recoverAfterTurn = true
                    if (isSelected(turn)) {
                        _generationState.value = GenerationState.OUTCOME_UNKNOWN
                        _streamHealth.value = StreamHealth.RECONNECTING
                        _streamFailure.value = "NATIVE_EVENT_SYNC_FAILED:LOCAL_SAVE_FAILED"
                    }
                }
                else if (failure is AgentSessionException && (failure.code in setOf(
                        "OUTCOME_UNKNOWN", "AGENT_TIMEOUT", "AGENT_STREAM_INTERRUPTED", "STREAM_INTERRUPTED",
                        "AGENT_UNAVAILABLE", "AGENT_STREAM_TOO_LARGE", "AGENT_STREAM_INVALID", "INVALID_AGENT_EVENT", "CURSOR_EXPIRED",
                    ) || failure.code == "AGENT_FAILED" && agent.capabilities.generationRecovery && turn.userMessageId != null)) {
                    queueUnknownBarriers.add(key)
                    turn.state = GenerationState.OUTCOME_UNKNOWN
                    recoverAfterTurn = true
                    if (isSelected(turn)) _streamHealth.value = StreamHealth.RECONNECTING
                    if (text.isNotEmpty()) publish("OUTCOME_UNKNOWN")
                    flush()
                    if (isSelected(turn)) _generationState.value = GenerationState.OUTCOME_UNKNOWN
                } else {
                    queueUnknownBarriers.add(key)
                    if (text.isNotEmpty()) publish("FAILED")
                    flush()
                    emitEvent(agent.id, VerifiedConversationEvent.MessageStatus("evt_${UUID.randomUUID()}",
                        System.currentTimeMillis(), ConversationId(conversationId), messageId,
                        message.clientMessageId, AgentMessageStatus.FAILED, 1L,
                        messageError(failure), turn.generationId))
                    if (isSelected(turn)) _generationState.value = GenerationState.FAILED
                }
            } finally {
                snapshots.close()
                val needsRecovery = recoverAfterTurn && agent.capabilities.generationRecovery && settledClients[key] != turn.clientMessageId
                synchronized(recoveryJobs) {
                    if (!needsRecovery && isSelected(turn) && _generationId.value == turn.generationId) {
                        _generationId.value = null
                        _streamHealth.value = if (drainAfterTurn) StreamHealth.IDLE else StreamHealth.FAILED
                    }
                    // Transfer ownership atomically: another send cannot enter
                    // between the live reader and its durable reconciliation.
                    if (turns.remove(key, turn) && needsRecovery) recoveringTurns[key] = turn
                }
                if (needsRecovery) startRecovery(turn)
                if (drainAfterTurn && !recoverAfterTurn) {
                    queueUnknownBarriers.remove(key)
                    // A confirmed terminal state is the only local proof that
                    // draining the next queued turn cannot overlap this one.
                    parentScope.launch(ioDispatcher) { drainQueuedSends(agent, conversationId) }
                }
            }
        }
        // Only the host's first generation frame can issue a cancellable turn ID.
        return MessageAcceptance(messageId, message.clientMessageId.value, turn.generationId.takeIf { agent.kind != "hermes" },
            authoritative = false)
    }

    private data class QueuedSend(
        val message: OutgoingMessage,
        var state: PendingDeliveryState = PendingDeliveryState.QUEUED,
        var errorCode: String? = null,
    )

    /** 按 (agent, conversation) 排队的追问；只在上一轮结束后逐条发出。 */
    private val queuedSends = ConcurrentHashMap<Pair<String, String>, ArrayDeque<QueuedSend>>()
    private val queuedAgents = ConcurrentHashMap<String, CoreAgent>()
    private val queueUnknownBarriers = Collections.synchronizedSet(mutableSetOf<Pair<String, String>>())

    private fun enqueueSend(agent: CoreAgent, key: Pair<String, String>, message: OutgoingMessage) {
        synchronized(queuedSends) {
            queuedAgents[key.first] = agent
            queuedSends.getOrPut(key) { ArrayDeque() }.addLast(QueuedSend(message))
        }
    }

    private fun takeQueuedSend(key: Pair<String, String>): QueuedSend? = synchronized(queuedSends) {
        val queue = queuedSends[key] ?: return null
        val next = queue.removeFirstOrNull()
        if (queue.isEmpty()) queuedSends.remove(key)
        next
    }

    private fun requeueSend(key: Pair<String, String>, item: QueuedSend) {
        synchronized(queuedSends) {
            queuedSends.getOrPut(key) { ArrayDeque() }.addFirst(item)
        }
    }

    private suspend fun drainQueuedSends(agent: CoreAgent, conversationId: String) {
        val key = agent.id to conversationId
        if (turns.containsKey(key) || recoveringTurns.containsKey(key)) return
        if (key in queueUnknownBarriers) return
        val head = synchronized(queuedSends) { queuedSends[key]?.firstOrNull() }
        if (head?.state == PendingDeliveryState.OUTCOME_UNKNOWN) return
        val next = takeQueuedSend(key) ?: return
        next.state = PendingDeliveryState.SENDING
        try {
            submitMessageForAgent(agent, conversationId, next.message.copy(delivery = MessageDeliveryMode.QUEUE))
        } catch (busy: AgentSessionException) {
            if (busy.code != "SESSION_BUSY") {
                GatewayLog.w("OaiLatency", "queued send failed client=${next.message.clientMessageId.value} code=${busy.code}")
                next.state = if (busy.code == "AGENT_OFFLINE") PendingDeliveryState.FAILED else PendingDeliveryState.OUTCOME_UNKNOWN
                next.errorCode = busy.code
                requeueSend(key, next)
                emitPendingDelivery(agent.id, conversationId, next)
                return
            }
            // 另一个发送抢先占用了这一轮：放回队首，等下一次 finally 再试。
            next.state = PendingDeliveryState.QUEUED
            next.errorCode = null
            requeueSend(key, next)
        } catch (failure: Exception) {
            GatewayLog.w("OaiLatency", "queued send failed client=${next.message.clientMessageId.value} error=${failure.javaClass.simpleName}")
            next.state = PendingDeliveryState.OUTCOME_UNKNOWN
            next.errorCode = (failure as? AgentSessionException)?.code ?: "QUEUE_SEND_OUTCOME_UNKNOWN"
            requeueSend(key, next)
            emitPendingDelivery(agent.id, conversationId, next)
        }
    }

    private suspend fun emitPendingDelivery(
        agentId: String,
        conversationId: String,
        item: QueuedSend,
    ) {
        emitEvent(
            agentId,
            VerifiedConversationEvent.PendingDeliveryUpdated(
                eventId = "evt_${UUID.randomUUID()}",
                occurredAt = System.currentTimeMillis(),
                delivery = PendingDelivery(
                    clientMessageId = item.message.clientMessageId,
                    conversationId = ConversationId(conversationId),
                    text = item.message.text,
                    attachmentCount = item.message.attachmentIds.size,
                    state = item.state,
                    errorCode = item.errorCode,
                ),
            ),
        )
    }

    override suspend fun pendingDeliveries(conversationId: String): List<PendingDelivery> {
        val agent = requireAgent()
        return synchronized(queuedSends) {
            queuedSends[agent.id to conversationId].orEmpty().map { item ->
                PendingDelivery(
                    clientMessageId = item.message.clientMessageId,
                    conversationId = ConversationId(conversationId),
                    text = item.message.text,
                    attachmentCount = item.message.attachmentIds.size,
                    state = item.state,
                    errorCode = item.errorCode,
                )
            }
        }
    }

    override suspend fun retryPendingDeliveries(conversationId: String) {
        retryPendingDeliveriesFor(requireAgent(), conversationId)
    }

    private suspend fun retryPendingDeliveriesFor(agent: CoreAgent, conversationId: String) {
        val key = agent.id to conversationId
        val queued = synchronized(queuedSends) { queuedSends[key]?.toList().orEmpty() }
        if (queued.isEmpty() || turns.containsKey(key) || recoveringTurns.containsKey(key)) return
        if (queued.first().state == PendingDeliveryState.OUTCOME_UNKNOWN) return
        val current = try {
            withContext(ioDispatcher) { client.currentGeneration(agent, conversationId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            queueUnknownBarriers.add(key)
            GatewayLog.w(
                "OaiLatency",
                "queue.probe_failed conversation=$conversationId code=${(failure as? AgentSessionException)?.code}",
            )
            return
        }
        if (current != null) {
            queueUnknownBarriers.add(key)
            return
        }
        queueUnknownBarriers.remove(key)
        synchronized(queuedSends) {
            queuedSends[key].orEmpty().forEach { item ->
                if (item.state == PendingDeliveryState.FAILED) {
                    item.state = PendingDeliveryState.QUEUED
                    item.errorCode = null
                }
            }
        }
        drainQueuedSends(agent, conversationId)
    }

    override suspend fun retryAllPendingDeliveries() {
        val pending = synchronized(queuedSends) {
            queuedSends.keys.mapNotNull { key -> queuedAgents[key.first]?.let { agent -> key to agent } }
        }
        pending.forEach { (key, agent) ->
            retryPendingDeliveriesFor(agent, key.second)
        }
    }

    override suspend fun steerMessage(
        conversationId: String,
        message: OutgoingMessage,
    ): MessageAcceptance {
        val agent = requireAgent()
        val key = agent.id to conversationId
        if (!agent.capabilities.steer) throw AgentSessionException("UNSUPPORTED_CAPABILITY")
        if (message.attachmentIds.isNotEmpty()) throw AgentSessionException("STEER_ATTACHMENTS_UNSUPPORTED")
        val turn = synchronized(recoveryJobs) {
            if (ambiguousEventGenerations.containsKey(key)) throw AgentSessionException("OUTCOME_UNKNOWN")
            listOfNotNull(turns[key], recoveringTurns[key]).distinct().singleOrNull()
                ?.takeIf { it.state == GenerationState.RUNNING && !it.cancelled && it.hostGenerationId != null }
                ?: throw AgentSessionException("OUTCOME_UNKNOWN")
        }
        val generationId = requireNotNull(turn.hostGenerationId)
        val result = withContext(ioDispatcher) {
            if (!ownsTurn(turn) || turn.hostGenerationId != generationId || turn.state != GenerationState.RUNNING ||
                turn.cancelled || ambiguousEventGenerations.containsKey(key)) throw AgentSessionException("OUTCOME_UNKNOWN")
            client.steer(agent, conversationId, generationId, message.text, message.clientMessageId.value)
        }
        if (result.generationId != generationId || result.outcome != "accepted") throw AgentSessionException("OUTCOME_UNKNOWN")
        GatewayLog.d(
            "OaiLatency",
            "send.steered client=${message.clientMessageId.value} conversation=$conversationId generation=$generationId",
        )
        return MessageAcceptance(
            messageId = "steer_${message.clientMessageId.value}",
            correlationId = message.clientMessageId.value,
            generationId = result.generationId,
            delivery = MessageDeliveryMode.STEER,
        )
    }

    override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> {
        restartEventSync()
        val subscribedAgentId = currentAgent()?.id
        return _events.filter { it.agentId == subscribedAgentId }.map { it.event }
    }

    override suspend fun cancelGeneration(generationId: String, requestId: String): CancelGenerationResult {
        val conversationId = activeConversationId() ?: return CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        return cancelGeneration(conversationId, generationId, requestId)
    }

    override suspend fun cancelGeneration(conversationId: String, generationId: String, requestId: String): CancelGenerationResult {
        val turn = generationRoutes[generationId]?.takeIf { it.conversationId == conversationId }
            ?: return CancelGenerationResult(CancelGenerationOutcome.OUTCOME_UNKNOWN)
        val agent = turn.agent
        if (ambiguousEventGenerations.containsKey(agent.id to conversationId) ||
            turn.hostGenerationId != generationId || generationId.startsWith("local_"))
            return CancelGenerationResult(CancelGenerationOutcome.OUTCOME_UNKNOWN)
        if (!supportsGenerationCancel || !agent.capabilities.cancel) return CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        return try {
            val receipt = withContext(ioDispatcher) { client.cancel(agent, conversationId, generationId, requestId) }
            val outcome = when (receipt.outcome) {
                "cancelled" -> CancelGenerationOutcome.CANCELLED
                "already_completed" -> CancelGenerationOutcome.ALREADY_COMPLETED
                else -> CancelGenerationOutcome.OUTCOME_UNKNOWN
            }
            if (outcome == CancelGenerationOutcome.CANCELLED) {
                turns[agent.id to conversationId]?.takeIf { it.generationId == generationId }?.cancelled = true
                emitEvent(agent.id, VerifiedConversationEvent.GenerationCancelled("evt_${UUID.randomUUID()}",
                    System.currentTimeMillis(), generationId, ConversationId(conversationId)))
                if (_generationId.value == generationId && isSelected(turn))
                    _generationState.value = GenerationState.CANCELLED
            }
            CancelGenerationResult(outcome)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { CancelGenerationResult(CancelGenerationOutcome.OUTCOME_UNKNOWN) }
    }

    override suspend fun submitApprovalDecision(approvalId: ApprovalId, choice: ApprovalChoice): ApprovalSubmissionResult {
        val route = approvals[approvalId.value] ?: return ApprovalSubmissionResult(ApprovalSubmissionOutcome.NOT_FOUND)
        if (!supportsApprovalCards || !route.agent.capabilities.approvals || choice.wireValue !in route.choices)
            return ApprovalSubmissionResult(ApprovalSubmissionOutcome.UNSUPPORTED)
        val decision = ApprovalDecision.of(choice.wireValue) ?: return ApprovalSubmissionResult(ApprovalSubmissionOutcome.UNSUPPORTED)
        val result = try {
            withContext(ioDispatcher) { client.submitApprovalDecision(route.agent, route.conversationId,
                approvalId.value, decision, approvalAudit) }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { return ApprovalSubmissionResult(ApprovalSubmissionOutcome.FAILED) }
        return ApprovalSubmissionResult(
            outcome = when (result.outcome) {
                ApprovalDecisionOutcome.SUBMITTED -> ApprovalSubmissionOutcome.SUBMITTED
                ApprovalDecisionOutcome.ALREADY_RESOLVED -> ApprovalSubmissionOutcome.ALREADY_RESOLVED
                ApprovalDecisionOutcome.EXPIRED -> ApprovalSubmissionOutcome.EXPIRED
                ApprovalDecisionOutcome.NOT_FOUND -> ApprovalSubmissionOutcome.NOT_FOUND
                ApprovalDecisionOutcome.UNSUPPORTED -> ApprovalSubmissionOutcome.UNSUPPORTED
                ApprovalDecisionOutcome.FAILED -> ApprovalSubmissionOutcome.FAILED
            },
            choice = result.decision?.let { ApprovalChoice.of(it.wireValue) }, errorCode = result.errorCode,
            settledOutcome = when (result.rawDecision) {
                "timeout" -> ApprovalOutcome.TIMED_OUT
                "withdrawn" -> ApprovalOutcome.WITHDRAWN
                else -> null
            })
    }

    override suspend fun updateTitle(conversationId: String, title: String): Boolean {
        val agent = requireAgent()
        if (!agent.capabilities.rename || !agent.online) return false
        return try {
            withContext(ioDispatcher) { client.rename(agent, conversationId, title) }
            emitEvent(agent.id,
                VerifiedConversationEvent.TitleUpdated(
                    eventId = "evt_${UUID.randomUUID()}",
                    occurredAt = System.currentTimeMillis(),
                    conversationId = ConversationId(conversationId),
                    newTitle = title,
                )
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun readOrganization(): ConversationOrganization {
        val agent = requireAgent()
        return withContext(ioDispatcher) { organizationStore.read(agent.id) }
    }

    override suspend fun updateOrganization(expectedRevision: Long, organization: ConversationOrganization): ConversationOrganization {
        val agent = requireAgent()
        return withContext(ioDispatcher) { organizationStore.update(agent.id, expectedRevision, organization) }
    }

    override suspend fun setArchived(conversationId: String, archived: Boolean) {
        val agent = requireAgent()
        if (!agent.online) throw AgentSessionException("AGENT_OFFLINE")
        if (!agent.capabilities.archive || !archived) throw AgentSessionException("UNSUPPORTED_CAPABILITY")
        withContext(ioDispatcher) { client.archive(agent, conversationId) }
        emitEvent(agent.id,
            VerifiedConversationEvent.ConversationsChanged(
                eventId = "evt_${UUID.randomUUID()}",
                occurredAt = System.currentTimeMillis(),
                conversationId = ConversationId(conversationId),
                deleted = false,
            )
        )
    }

    override suspend fun deleteConversation(conversationId: String) {
        val agent = requireAgent()
        if (!agent.online) throw AgentSessionException("AGENT_OFFLINE")
        if (!agent.capabilities.delete) throw AgentSessionException("UNSUPPORTED_CAPABILITY")
        withContext(ioDispatcher) { client.delete(agent, conversationId) }
        try { withContext(ioDispatcher) { organizationStore.removeConversation(agent.id, conversationId) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The host already confirmed deletion; stale preference IDs cannot restore a session. */ }
        emitEvent(agent.id,
            VerifiedConversationEvent.ConversationsChanged(
                eventId = "evt_${UUID.randomUUID()}",
                occurredAt = System.currentTimeMillis(),
                conversationId = ConversationId(conversationId),
                deleted = true,
            )
        )
    }

    override suspend fun queryMessage(conversationId: String, clientMessageId: ClientMessageId): TimelineMessage? {
        val agent = requireAgent()
        // A restored local send atom has no in-memory NativeTurn. Query its durable
        // identity before returning absence; otherwise an explicit retry could resend.
        val receipt = if (agent.capabilities.generationRecovery) withContext(ioDispatcher) {
            client.generation(agent, conversationId, clientMessageId.value)
        } else null
        val message = withContext(ioDispatcher) { client.history(agent, conversationId) }
            .map { timelineMessage(it, conversationId) }
            .firstOrNull { it.sender == "user" && it.clientMessageId == clientMessageId }
        if (receipt != null && settledClients[agent.id to conversationId] != clientMessageId.value) {
            val key = agent.id to conversationId
            val recovery = synchronized(recoveryJobs) {
                val owner = turns[key] ?: recoveringTurns[key]
                when {
                    owner == null -> NativeTurn(agent, conversationId, clientMessageId.value)
                    owner.clientMessageId == clientMessageId.value && turns[key] == null -> owner
                    else -> null
                }
            }
            recovery?.let { turn ->
                rememberGeneration(turn, receipt.generationId)
                startRecovery(turn)
            }
        }
        return message ?: receipt?.let { TimelineMessage(it.userMessageId, "user", emptyList(), 0,
            conversationId = ConversationId(conversationId), clientMessageId = clientMessageId,
            generationId = it.generationId) }
    }

    private fun parseDate(value: String?): Long {
        val date = value?.trim()?.takeIf(String::isNotEmpty) ?: return 0L
        date.toBigDecimalOrNull()?.let { number ->
            return runCatching {
                (if (number.abs() < BigDecimal("100000000000")) number * BigDecimal(1000) else number)
                    .setScale(0, java.math.RoundingMode.DOWN).longValueExact()
            }.getOrDefault(0L)
        }
        return runCatching { Instant.parse(date).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(date).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(date).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrDefault(0L)
    }

    private fun messageError(failure: Exception): com.openandroidintelligence.conversation.ports.AgentMessageErrorCode {
        val code = (failure as? AgentSessionException)?.code ?: failure.message
        return when {
            code in setOf("INVALID_MESSAGE", "INVALID_ATTACHMENT", "INVALID_ATTACHMENT_IDS", "MESSAGE_TOO_LARGE") ->
                com.openandroidintelligence.conversation.ports.AgentMessageErrorCode.INVALID_MESSAGE
            code in setOf("UNSUPPORTED", "UNSUPPORTED_CAPABILITY", "CAPABILITY_UNSUPPORTED") ->
                com.openandroidintelligence.conversation.ports.AgentMessageErrorCode.UNSUPPORTED_CAPABILITY
            code?.startsWith("ATTACHMENT_") == true -> com.openandroidintelligence.conversation.ports.AgentMessageErrorCode.ATTACHMENT_READ_FAILED
            code == "UNSUPPORTED_APPROVAL" -> com.openandroidintelligence.conversation.ports.AgentMessageErrorCode.APPROVAL_UNSUPPORTED
            code == "AGENT_MEDIA_REJECTED" -> com.openandroidintelligence.conversation.ports.AgentMessageErrorCode.AGENT_MEDIA_REJECTED
            code == "MODEL_REQUEST_REJECTED" -> com.openandroidintelligence.conversation.ports.AgentMessageErrorCode.MODEL_REQUEST_REJECTED
            code in setOf("AGENT_STREAM_INVALID", "AGENT_RESPONSE_INVALID", "HOST_INCOMPATIBLE") ->
                com.openandroidintelligence.conversation.ports.AgentMessageErrorCode.PROTOCOL_ERROR
            code in setOf("AGENT_OFFLINE", "AGENT_UNAVAILABLE") -> com.openandroidintelligence.conversation.ports.AgentMessageErrorCode.AGENT_UNAVAILABLE
            else -> com.openandroidintelligence.conversation.ports.AgentMessageErrorCode.AGENT_REQUEST_FAILED
        }
    }
}
