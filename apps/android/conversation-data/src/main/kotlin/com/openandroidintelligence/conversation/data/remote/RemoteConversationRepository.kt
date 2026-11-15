package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.diagnostics.GatewayLog
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
    private val clock: () -> Long = System::currentTimeMillis,
    private val mediaSource: RemoteAuthenticatedMediaSource? = null,
    private val onEvent: suspend (EndpointKey, VerifiedConversationEvent) -> Unit = { _, _ -> },
) : ConversationRepository, GenerationTracker, StreamHealthSource, MessageOutcomeQuery {
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
    private val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 4)
    private val health = MutableStateFlow(StreamHealth.IDLE)
    override val streamHealth: Flow<StreamHealth> = health
    private val failure = MutableStateFlow<String?>(null)
    override val streamFailure: Flow<String?> = failure
    private val generation = RemoteGenerationState()
    override val generationSnapshot: StateFlow<GenerationSnapshot> = generation.snapshot
    override val generationId: StateFlow<String?> = generation.generationId
    override val generationState: StateFlow<GenerationState> = generation.generationState
    private val approvals = ConcurrentHashMap<String, Pair<String, String>>()
    private val held = ConcurrentHashMap<String, LinkedHashMap<String, OutgoingMessage>>()
    private val acceptedUserMessages = ConcurrentHashMap<Pair<String, String>, String>()

    init { require(workspace.endpoint.key.backendId.value == carrier.backend) { "REMOTE_BACKEND_MISMATCH" } }
    private fun current() {
        check(lifetime.isActive && carrier.channel.scope == connectionScope) { "REMOTE_CONNECTION_REPLACED" }
    }
    private fun projection(id: String) = projections.computeIfAbsent(id) {
        RemoteTimelineProjection(it, clock = clock, mediaReference = mediaSource?.let { source -> source::accept },
            persistentMessageSequence = carrier.backend == "harness", matchStartedGenerationToSubmission = carrier.backend != "codex",
            onFailedTerminal = { code -> logRemoteSessionFailure(carrier.backend, "terminal", code ?: "REMOTE_AGENT_FAILED",
                generationState = GenerationState.FAILED) })
    }
    fun activeConversationChanged() = generation.publish {
        val id = activeConversationId()
        val projection = id?.let(projections::get)
        projection?.let { synchronized(it) { GenerationSnapshot(it.generationId, it.generationState, id) } }
            ?: GenerationSnapshot(null, GenerationState.IDLE, id)
    }
    override suspend fun listConversations(scope: ConversationScope, page: PageRequest): ConversationPage {
        current()
        val result = carrier.sessions(workspace, page)
        current()
        result.items.forEach { rows[RemoteLocalIds.encode(it.id)] = it; mediaSource?.confirmSession(it.id) }
        return ConversationPage(result.items.map { ConversationSummary(ConversationId(RemoteLocalIds.encode(it.id)), it.title, it.updatedAt, it.archived) }, result.nextCursor)
    }
    override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation {
        current()
        val created = carrier.create(workspace)
        current()
        val localId = RemoteLocalIds.encode(created.id)
        rows[localId] = created
        mediaSource?.confirmSession(created.id)
        return Conversation(ConversationId(localId), created.title, created.createdAt)
    }
    override suspend fun readConversation(conversationId: String): Conversation? = rows[conversationId]?.let { Conversation(ConversationId(conversationId), it.title, it.createdAt) }
    override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage {
        current()
        ensureFollow(conversationId)
        val mediaRead = mediaSource?.beginHistoryRead()
        val result = carrier.history(RemoteLocalIds.decode(conversationId), page)
        current()
        val target = projection(conversationId)
        val projected = synchronized(target) { result.records.flatMap { target.accept(it, history = true, historyRead = mediaRead) } }
        projected.forEach { rememberApproval(conversationId, it) }
        activeConversationChanged()
        // 页内顺序来自这次真实 records，不使用可能重编号的 Codex seq 或手机时钟。
        // 每页独立身份保证分页、重读与其他会话的位置不会被拿来互相比大小。
        val pageId = UUID.randomUUID().toString()
        val positions = result.records.mapIndexedNotNull { index, record ->
            val event = record.at("event") ?: record
            val data = event.at("data") ?: return@mapIndexedNotNull null
            (data.at("message") ?: data).text("id")?.let { it to HistoryMessagePosition(pageId, index) }
        }.toMap()
        return TimelinePage(synchronized(target) { target.rows.values.mapNotNull { row ->
            positions[row.id]?.let { row.copy(historyPosition = it) }
        } }, result.nextCursor, result.revision)
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
                        val (decoded, ended) = synchronized(target) {
                            val decoded = target.accept(frame)
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
                        settlePending(id, target)
                        activeConversationChanged()
                    }
                } catch (cancelled: CancellationException) {
                    if (!opened.isCompleted) opened.completeExceptionally(cancelled)
                    throw cancelled
                } catch (cause: Exception) {
                    val code = (cause as? RemoteSessionException)?.code ?: "REMOTE_STREAM_FAILED"
                    logRemoteSessionFailure(carrier.backend, "stream", code, cause)
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
        if (event is VerifiedConversationEvent.TimelineUpsert && event.message.sender == "user") {
            event.message.clientMessageId?.let { clientId ->
                if (acceptedUserMessages.putIfAbsent(id to clientId.value, event.message.id) == null) {
                    publish(id, VerifiedConversationEvent.MessageAccepted("user-accepted:${clientId.value}", event.occurredAt,
                        event.message.id, clientId.value, ConversationId(id)))
                }
            }
        }
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
            publish(conversationId, VerifiedConversationEvent.PendingDeliveryUpdated("queue:${message.clientMessageId.value}", clock(),
                PendingDelivery(message.clientMessageId, ConversationId(conversationId), message.text, message.attachmentIds.size, PendingDeliveryState.QUEUED)))
            return MessageAcceptance(message.clientMessageId.value, message.clientMessageId.value, delivery = MessageDeliveryMode.QUEUE, authoritative = false)
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
        synchronized(target) {
            if (target.generationState !in setOf(GenerationState.RUNNING, GenerationState.QUEUED, GenerationState.OUTCOME_UNKNOWN))
                target.beginTurn(localGeneration)
        }
        activeConversationChanged()
        scope.launch {
            try {
                val result = carrier.prompt(RemoteLocalIds.decode(conversationId), message.clientMessageId.value, content, models[conversationId])
                current()
                val nativeGeneration = result.at("turn")?.text("id") ?: result.text("generationId") ?: turn.generation
                if (result.flag("accepted") == false) {
                    throw RemoteSessionException("REMOTE_PROMPT_NOT_ACCEPTED")
                }
                turn.generation = nativeGeneration
                val identified = synchronized(target) {
                    target.bindSubmittedGeneration(localGeneration, nativeGeneration)
                    if (carrier.backend == "codex" && result.at("turn")?.text("id") != null)
                        target.bindUserRequest(nativeGeneration, message.clientMessageId) else emptyList()
                }
                identified.forEach { publish(conversationId, it) }
                turn.identityReady.complete(IdentityResolution.PROMPT_FINISHED)
                if (synchronized(turns) { turns[message.clientMessageId.value] !== turn }) return@launch
                turn.members.forEach { member -> publish(conversationId, VerifiedConversationEvent.MessageStatus("accepted:${member.value}", clock(),
                    ConversationId(conversationId), acceptedUserMessages[conversationId to member.value] ?: member.value,
                    member, AgentMessageStatus.DELIVERED, 1, null, nativeGeneration)) }
                // 流可先于 RPC 回执完成；用持久到本连接结束的终态事实再次核对，而不是重启该轮。
                settlePending(conversationId, target)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) {
                logRemoteSessionFailure(carrier.backend, "prompt", (cause as? RemoteSessionException)?.code
                    ?: (cause as? RemoteRpcException)?.code ?: "REMOTE_SUBMISSION_OUTCOME_UNKNOWN", cause,
                    (cause as? RemoteRpcException)?.deliveryOutcome?.name)
                // 已发出的 mutation 没有回执时保持原提交身份，不能画成可盲目重发的确定失败。
                failure.value = (cause as? RemoteSessionException)?.code ?: "REMOTE_SUBMISSION_OUTCOME_UNKNOWN"
                val rejected = (cause is RemoteRpcException && cause.deliveryOutcome == RemoteDeliveryOutcome.NOT_SENT) ||
                    cause is RemoteSessionException && cause.code == "REMOTE_PROMPT_NOT_ACCEPTED"
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
                    if (projection(conversationId).generationId == localGeneration) projection(conversationId).markUnknown()
                    turn.members.forEach { member -> publish(conversationId, VerifiedConversationEvent.PendingDeliveryUpdated("unknown:${member.value}", clock(),
                        PendingDelivery(member, ConversationId(conversationId), message.text, message.attachmentIds.size,
                            PendingDeliveryState.OUTCOME_UNKNOWN, failure.value, localGeneration))) }
                }
            } finally {
                // 身份等待依赖原 prompt 的成功、失败和超时；连接关闭由父任务直接取消。
                turn.identityReady.complete(IdentityResolution.PROMPT_FINISHED)
                activeConversationChanged()
            }
        }
        return MessageAcceptance(message.clientMessageId.value, message.clientMessageId.value, localGeneration,
            message.delivery, authoritative = false)
    }
    private suspend fun settlePending(id: String, target: RemoteTimelineProjection) {
        val turns = pending[id] ?: return
        val entries = synchronized(turns) { turns.values.mapNotNull { turn ->
            synchronized(target) { target.terminal(turn.generation) }?.let { turn to it }
        } }
        if (entries.isEmpty()) return
        entries.forEach { (entry, terminal) ->
            if (synchronized(turns) { turns.remove(entry.message.clientMessageId.value) } !== entry) return@forEach
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
        generation.close()
        lifetime.cancelAndJoin()
        health.value = StreamHealth.IDLE
        approvals.clear()
        pending.clear()
        acceptedUserMessages.clear()
    }
}


/** 只保留结构化错误码和源码栈，禁止把异常消息、正文或会话身份带入日志。 */
internal fun logRemoteSessionFailure(backend: String, phase: String, code: String, cause: Throwable? = null,
    deliveryOutcome: String? = null, generationState: GenerationState? = null) {
    val safeCode = code.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,95}")) } ?: "REMOTE_FAILURE"
    val safeBackend = backend.takeIf { it.matches(Regex("[a-z][a-z0-9_-]{0,31}")) } ?: "unknown"
    val stack = cause?.stackTrace.orEmpty().take(12).map { frame ->
        val file = frame.fileName?.substringAfterLast('/')?.substringAfterLast('\\')
        "${frame.className}.${frame.methodName}(${file ?: "unknown"}:${frame.lineNumber})"
    }
    GatewayLog.w("OaiRemoteSession", Json.canonical(obj("phase" to phase, "backend" to safeBackend,
        "code" to safeCode, "deliveryOutcome" to deliveryOutcome?.takeIf { it in setOf("NOT_SENT", "REJECTED", "UNKNOWN") },
        "state" to generationState?.name,
        "exception" to cause?.javaClass?.simpleName, "sourceStack" to stack)))
}
