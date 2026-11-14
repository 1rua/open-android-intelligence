package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.gateway.remote.rpc.RemoteDeliveryOutcome
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** 仓储拥有一个固定端点和连接代次；切换只能创建新实例，旧结果无法改写新分区。 */
class RemoteConversationRepository(
    val carrier: RemoteSessionCarrier,
    val workspace: RemoteWorkspace,
    private val parentScope: CoroutineScope,
    private val activeConversationId: () -> String?,
    private val attachmentContent: suspend (String, String) -> List<JsonValue> = { _, _ -> throw RemoteSessionException("REMOTE_ATTACHMENT_UNAVAILABLE") },
    private val onEvent: suspend (EndpointKey, VerifiedConversationEvent) -> Unit = { _, _ -> },
) : ConversationRepository, GenerationTracker, StreamHealthSource, MessageOutcomeQuery {
    private val connectionScope = carrier.channel.scope
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val projections = ConcurrentHashMap<String, RemoteTimelineProjection>()
    private val follows = ConcurrentHashMap<String, Job>()
    private val ready = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private data class PendingTurn(val message: OutgoingMessage, val members: List<ClientMessageId>, var generation: String)
    private val pending = ConcurrentHashMap<String, LinkedHashMap<String, PendingTurn>>()
    private val batchMembers = ConcurrentHashMap<String, List<ClientMessageId>>()
    private val models = ConcurrentHashMap<String, RemoteModelSelection>()
    private val rows = ConcurrentHashMap<String, RemoteSessionRow>()
    private val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 4)
    private val health = MutableStateFlow(StreamHealth.IDLE)
    override val streamHealth: Flow<StreamHealth> = health
    private val failure = MutableStateFlow<String?>(null)
    override val streamFailure: Flow<String?> = failure
    private val currentGeneration = MutableStateFlow<String?>(null)
    override val generationId: StateFlow<String?> = currentGeneration
    private val currentState = MutableStateFlow(GenerationState.IDLE)
    override val generationState: StateFlow<GenerationState> = currentState
    private val approvals = ConcurrentHashMap<String, Pair<String, String>>()
    private val held = ConcurrentHashMap<String, LinkedHashMap<String, OutgoingMessage>>()

    init { require(workspace.endpoint.key.backendId.value == carrier.backend) { "REMOTE_BACKEND_MISMATCH" } }
    private fun current() {
        check(lifetime.isActive && carrier.channel.scope == connectionScope) { "REMOTE_CONNECTION_REPLACED" }
    }
    private fun projection(id: String) = projections.computeIfAbsent(id) { RemoteTimelineProjection(it) }
    fun activeConversationChanged() {
        val projection = activeConversationId()?.let(projections::get)
        currentGeneration.value = projection?.generationId
        currentState.value = projection?.generationState ?: GenerationState.IDLE
    }
    override suspend fun listConversations(scope: ConversationScope, page: PageRequest): ConversationPage {
        current()
        val result = carrier.sessions(workspace, page)
        current()
        result.items.forEach { rows[RemoteLocalIds.encode(it.id)] = it }
        return ConversationPage(result.items.map { ConversationSummary(ConversationId(RemoteLocalIds.encode(it.id)), it.title, it.updatedAt, it.archived) }, result.nextCursor)
    }
    override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation {
        current()
        val created = carrier.create(workspace)
        current()
        val localId = RemoteLocalIds.encode(created.id)
        rows[localId] = created
        return Conversation(ConversationId(localId), created.title, created.createdAt)
    }
    override suspend fun readConversation(conversationId: String): Conversation? = rows[conversationId]?.let { Conversation(ConversationId(conversationId), it.title, it.createdAt) }
    override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage {
        current()
        ensureFollow(conversationId)
        val result = carrier.history(RemoteLocalIds.decode(conversationId), page)
        current()
        val target = projection(conversationId)
        val projected = synchronized(target) { result.records.flatMap { target.accept(it, history = true) } }
        projected.forEach { rememberApproval(conversationId, it) }
        activeConversationChanged()
        val pageIds = result.records.mapNotNull { record ->
            val event = record.at("event") ?: record
            val data = event.at("data") ?: return@mapNotNull null
            (data.at("message") ?: data).text("id")
        }.toSet()
        return TimelinePage(synchronized(target) { target.rows.values.filter { it.id in pageIds } },
            result.nextCursor, result.revision)
    }
    private suspend fun ensureFollow(id: String) {
        val opened = ready.computeIfAbsent(id) { CompletableDeferred() }
        follows.computeIfAbsent(id) {
            scope.launch {
                health.value = StreamHealth.CONNECTING
                try {
                    carrier.follow(RemoteLocalIds.decode(id)).collect { frame ->
                        current()
                        if (frame.text("type") == "remote-stream-ready") {
                            health.value = StreamHealth.LIVE
                            opened.complete(Unit)
                            return@collect
                        }
                        val target = projection(id)
                        val decoded = synchronized(target) { target.accept(frame) }
                        target.clientRequestId?.let { request -> pending[id]?.let { turns -> synchronized(turns) {
                            turns[request]?.let { turn -> target.generationId?.let { turn.generation = it } }
                        } } }
                        decoded.forEach { publish(id, it) }
                        settlePending(id, target)
                        activeConversationChanged()
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
    private fun rememberApproval(id: String, event: VerifiedConversationEvent) {
        if (event is VerifiedConversationEvent.ApprovalRequested) projection(id).approvalHandles[event.request.approvalId.value]?.let {
            approvals[event.request.approvalId.value] = id to it
        }
    }
    private suspend fun publish(id: String, event: VerifiedConversationEvent) {
        current()
        rememberApproval(id, event)
        onEvent(workspace.endpoint.key, event)
        current()
        events.emit(event)
    }
    override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> = events.asSharedFlow()
    override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance =
        submitMessage(activeConversationId() ?: throw RemoteSessionException("CONVERSATION_REQUIRED"), message)
    override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
        current()
        ensureFollow(conversationId)
        if (message.delivery == MessageDeliveryMode.STEER) return steerMessage(conversationId, message)
        if (message.delivery == MessageDeliveryMode.QUEUE && projection(conversationId).generationState in setOf(GenerationState.RUNNING, GenerationState.QUEUED)) {
            val queue = held.computeIfAbsent(conversationId) { linkedMapOf() }
            synchronized(queue) { queue[message.clientMessageId.value] = message.copy(delivery = MessageDeliveryMode.NORMAL) }
            publish(conversationId, VerifiedConversationEvent.PendingDeliveryUpdated("queue:${message.clientMessageId.value}", 0,
                PendingDelivery(message.clientMessageId, ConversationId(conversationId), message.text, message.attachmentIds.size, PendingDeliveryState.QUEUED)))
            return MessageAcceptance(message.clientMessageId.value, message.clientMessageId.value, delivery = MessageDeliveryMode.QUEUE, authoritative = false)
        }
        val content = buildList {
            if (message.text.isNotEmpty()) add(obj("type" to "text", "text" to message.text))
            message.attachmentIds.forEach { addAll(attachmentContent(conversationId, it)) }
        }
        if (content.isEmpty()) throw RemoteSessionException("INVALID_MESSAGE")
        val localGeneration = "request:${message.clientMessageId.value}"
        val turn = PendingTurn(message, batchMembers.remove(message.clientMessageId.value) ?: listOf(message.clientMessageId), localGeneration)
        val turns = pending.computeIfAbsent(conversationId) { linkedMapOf() }
        synchronized(turns) { check(turns.putIfAbsent(message.clientMessageId.value, turn) == null) { "REMOTE_SUBMISSION_ALREADY_PENDING" } }
        if (projection(conversationId).generationState !in setOf(GenerationState.RUNNING, GenerationState.QUEUED, GenerationState.OUTCOME_UNKNOWN))
            projection(conversationId).beginTurn(localGeneration)
        activeConversationChanged()
        scope.launch {
            try {
                val result = carrier.prompt(RemoteLocalIds.decode(conversationId), message.clientMessageId.value, content, models[conversationId])
                current()
                val nativeGeneration = result.at("turn")?.text("id") ?: result.text("generationId") ?: localGeneration
                if (result.flag("accepted") == false) {
                    throw RemoteSessionException("REMOTE_PROMPT_NOT_ACCEPTED")
                }
                turn.generation = nativeGeneration
                if (projection(conversationId).generationId == localGeneration) projection(conversationId).confirmGeneration(nativeGeneration)
                turn.members.forEach { member -> publish(conversationId, VerifiedConversationEvent.MessageStatus("accepted:${member.value}", 0,
                    ConversationId(conversationId), message.clientMessageId.value, member, AgentMessageStatus.DELIVERED, 1, null, nativeGeneration)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) {
                // 已发出的 mutation 没有回执时保持原提交身份，不能画成可盲目重发的确定失败。
                failure.value = (cause as? RemoteSessionException)?.code ?: "REMOTE_SUBMISSION_OUTCOME_UNKNOWN"
                val rejected = (cause is RemoteRpcException && cause.deliveryOutcome != RemoteDeliveryOutcome.UNKNOWN) ||
                    cause is RemoteSessionException && cause.code == "REMOTE_PROMPT_NOT_ACCEPTED"
                if (rejected) {
                    synchronized(turns) { turns.remove(message.clientMessageId.value) }
                    turn.members.forEach { member -> publish(conversationId, VerifiedConversationEvent.MessageStatus("rejected:${member.value}", 0,
                        ConversationId(conversationId), message.clientMessageId.value, member, AgentMessageStatus.FAILED,
                        2, AgentMessageErrorCode.AGENT_REQUEST_FAILED, localGeneration)) }
                } else {
                    if (projection(conversationId).generationId == localGeneration) projection(conversationId).markUnknown()
                    turn.members.forEach { member -> publish(conversationId, VerifiedConversationEvent.PendingDeliveryUpdated("unknown:${member.value}", 0,
                        PendingDelivery(member, ConversationId(conversationId), message.text, message.attachmentIds.size,
                            PendingDeliveryState.OUTCOME_UNKNOWN, failure.value, localGeneration))) }
                }
            } finally { activeConversationChanged() }
        }
        return MessageAcceptance(message.clientMessageId.value, message.clientMessageId.value, localGeneration,
            message.delivery, authoritative = false)
    }
    private suspend fun settlePending(id: String, target: RemoteTimelineProjection) {
        val turns = pending[id] ?: return
        val terminal = target.terminalGeneration ?: return
        val entries = synchronized(turns) { turns.values.filter { it.generation == terminal.first } }
        if (entries.isEmpty()) return
        val status = when (terminal.second) {
            GenerationState.COMPLETED -> AgentMessageStatus.COMPLETED
            GenerationState.FAILED -> AgentMessageStatus.FAILED
            else -> return
        }
        entries.forEach { entry ->
            synchronized(turns) { turns.remove(entry.message.clientMessageId.value) }
            entry.members.forEach { member -> publish(id, VerifiedConversationEvent.MessageStatus("terminal:${member.value}", 0, ConversationId(id),
                entry.message.clientMessageId.value, member, status, 2, if (status == AgentMessageStatus.FAILED) AgentMessageErrorCode.AGENT_REQUEST_FAILED else null,
                terminal.first)) }
        }
        retryPendingDeliveries(id)
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
        if (projection(conversationId).generationState in setOf(GenerationState.RUNNING, GenerationState.QUEUED, GenerationState.OUTCOME_UNKNOWN)) return
        val queue = held[conversationId] ?: return
        val next = synchronized(queue) { queue.entries.firstOrNull()?.also { queue.remove(it.key) }?.value } ?: return
        submitMessage(conversationId, next)
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
        carrier.interrupt(RemoteLocalIds.decode(conversationId), projection(conversationId).generationId ?: generationId)
        current()
        return CancelGenerationResult(CancelGenerationOutcome.OUTCOME_UNKNOWN)
    }
    override suspend fun queryMessage(conversationId: String, clientMessageId: ClientMessageId): TimelineMessage? =
        projection(conversationId).rows.values.firstOrNull { it.clientMessageId == clientMessageId }
    override suspend fun submitApprovalDecision(approvalId: ApprovalId, choice: ApprovalChoice): ApprovalSubmissionResult {
        current()
        if (choice !in setOf(ApprovalChoice.ONCE, ApprovalChoice.DENY)) return ApprovalSubmissionResult(ApprovalSubmissionOutcome.UNSUPPORTED)
        val (id, handle) = approvals[approvalId.value] ?: return ApprovalSubmissionResult(ApprovalSubmissionOutcome.NOT_FOUND)
        carrier.respond(RemoteLocalIds.decode(id), handle, choice == ApprovalChoice.ONCE)
        current()
        approvals.remove(approvalId.value)
        return ApprovalSubmissionResult(ApprovalSubmissionOutcome.SUBMITTED, choice)
    }
    override suspend fun updateTitle(conversationId: String, title: String): Boolean { current(); carrier.rename(RemoteLocalIds.decode(conversationId), title); current(); return true }
    override suspend fun setArchived(conversationId: String, archived: Boolean) { current(); carrier.archive(RemoteLocalIds.decode(conversationId), archived); current() }
    suspend fun modelCatalog(sessionId: String): RemoteModelCatalog { current(); return carrier.models(RemoteLocalIds.decode(sessionId)).also { current() } }
    suspend fun selectModel(sessionId: String, selection: RemoteModelSelection): RemoteModelSelection {
        current()
        return carrier.selectModel(RemoteLocalIds.decode(sessionId), selection).also { current(); models[sessionId] = it }
    }
    suspend fun close() {
        lifetime.cancelAndJoin()
        health.value = StreamHealth.IDLE
        approvals.clear()
        pending.clear()
    }
}
