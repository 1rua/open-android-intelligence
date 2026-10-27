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
import com.openandroidintelligence.gateway.approvals.ApprovalDecision
import com.openandroidintelligence.gateway.approvals.ApprovalDecisionAudit
import com.openandroidintelligence.gateway.approvals.ApprovalDecisionOutcome
import com.openandroidintelligence.conversation.data.GatewayEventDecoder
import com.openandroidintelligence.conversation.model.ApprovalId
import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.ApprovalOutcome
import com.openandroidintelligence.conversation.model.ApprovalSubmissionResult
import com.openandroidintelligence.conversation.model.ApprovalSubmissionOutcome
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import java.time.Instant
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.math.BigDecimal
import java.util.UUID

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
) : ConversationRepository, GenerationTracker, StreamHealthSource, MessageOutcomeQuery {

    private val _generationId = MutableStateFlow<String?>(null)
    override val generationId: StateFlow<String?> = _generationId.asStateFlow()

    private val _generationState = MutableStateFlow(GenerationState.IDLE)
    override val generationState: StateFlow<GenerationState> = _generationState.asStateFlow()

    private val _streamHealth = MutableStateFlow<StreamHealth>(StreamHealth.LIVE)
    override val streamHealth: Flow<StreamHealth> = _streamHealth.asStateFlow()

    private data class AgentEvent(val agentId: String, val event: VerifiedConversationEvent)
    // The per-turn publisher retains the latest snapshots; this relay must not
    // retain dozens of increasingly large copies of the same response.
    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 1)

    private suspend fun emitEvent(agentId: String, event: VerifiedConversationEvent) {
        _events.emit(AgentEvent(agentId, event))
    }

    private class NativeTurn(val agent: CoreAgent, val conversationId: String) {
        @Volatile var generationId: String? = if (agent.kind == "hermes") null else "local_${UUID.randomUUID()}"
        @Volatile var cancelled: Boolean = false
    }
    private data class ApprovalRoute(val agent: CoreAgent, val conversationId: String, val choices: Set<String>)
    private val turns = ConcurrentHashMap<Pair<String, String>, NativeTurn>()
    private val approvals = ConcurrentHashMap<String, ApprovalRoute>()
    private val generationRoutes = Collections.synchronizedMap(linkedMapOf<String, NativeTurn>())

    private fun rememberGeneration(turn: NativeTurn, generationId: String) {
        turn.generationId = generationId
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

    private fun isSelected(turn: NativeTurn): Boolean =
        currentAgent()?.id == turn.agent.id && activeConversationId() == turn.conversationId

    fun activeConversationChanged() {
        val agentId = currentAgent()?.id
        val conversationId = activeConversationId()
        val turn = if (agentId != null && conversationId != null) turns[agentId to conversationId] else null
        _generationId.value = turn?.generationId
        _generationState.value = when {
            turn == null -> GenerationState.IDLE
            turn.cancelled -> GenerationState.CANCELLED
            turn.generationId == null -> GenerationState.QUEUED
            else -> GenerationState.RUNNING
        }
    }

    private suspend fun emitApproval(turn: NativeTurn, payload: JsonValue.JObject, requested: Boolean,
        deliver: suspend (VerifiedConversationEvent) -> Unit = { emitEvent(turn.agent.id, it) }) {
        if (!supportsApprovalCards || !turn.agent.capabilities.approvals) return
        if (JsonFields.string(payload, "conversationId") != turn.conversationId)
            throw AgentSessionException("AGENT_STREAM_INVALID")
        val type = if (requested) "conversation.approval.requested" else "conversation.approval.resolved"
        val decoded = GatewayEventDecoder.decode(GatewayEvent("evt_${UUID.randomUUID()}", type,
            Json.canonical(Json.of(mapOf("payload" to payload))))) ?: throw AgentSessionException("AGENT_STREAM_INVALID")
        if (decoded is VerifiedConversationEvent.ApprovalRequested) {
            approvals[decoded.request.approvalId.value] = ApprovalRoute(turn.agent, turn.conversationId,
                decoded.request.options.map { it.choice.wireValue }.toSet())
        }
        deliver(decoded)
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
        return ConversationPage(conversations = summaries, nextCursor = null)
    }

    override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation {
        val agent = requireAgent()
        val created = withContext(ioDispatcher) { client.create(agent) }
        val title = created.title?.takeIf { it.isNotBlank() } ?: "新对话"
        val conv = Conversation(
            id = ConversationId(created.id),
            title = title,
            createdAt = parseDate(created.updatedAt),
        )
        emitEvent(agent.id,
            VerifiedConversationEvent.ConversationsChanged(
                eventId = "evt_${UUID.randomUUID()}",
                occurredAt = System.currentTimeMillis(),
                conversationId = conv.id,
                deleted = false,
            )
        )
        return conv
    }

    override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage {
        val agent = requireAgent()
        if (!agent.capabilities.history) {
            return TimelinePage(emptyList(), null)
        }
        val messages = withContext(ioDispatcher) { client.history(agent, conversationId) }
        val timelineMessages = messages.map { msg ->
            val isUser = msg.role.equals("user", ignoreCase = true)
            TimelineMessage(
                id = msg.id,
                sender = if (isUser) "user" else "assistant",
                parts = buildList {
                    if (msg.text.isNotEmpty()) add(MessagePart.Text(msg.text))
                    msg.attachments.forEach { attachment -> add(MessagePart.Attachment(
                        com.openandroidintelligence.conversation.model.AttachmentDraftId(attachment.id),
                        attachment.filename, attachment.mediaType, attachment.sizeBytes, attachment.sha256)) }
                },
                timestamp = parseDate(msg.createdAt),
                conversationId = ConversationId(conversationId),
                clientMessageId = msg.clientMessageId?.let { ClientMessageId(if (isUser) it else "assistant_$it") },
            )
        }
        return TimelinePage(messages = timelineMessages, nextCursor = null)
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
        val agent = requireAgent()
        val key = agent.id to conversationId
        val turn = NativeTurn(agent, conversationId)
        if (turns.putIfAbsent(key, turn) != null) throw AgentSessionException("SESSION_BUSY")
        turn.generationId?.let { rememberGeneration(turn, it) }
        var messageId = message.clientMessageId.value
        var assistantMsgId = "stream_${message.clientMessageId.value}"
        var assistantHasNativeId = false
        var assistantTimestamp = System.currentTimeMillis()
        if (isSelected(turn)) {
            _generationId.value = turn.generationId
            _generationState.value = if (turn.generationId == null) GenerationState.QUEUED else GenerationState.RUNNING
        }
        parentScope.launch(ioDispatcher) {
            val text = StringBuilder()
            // Coalesce complete message snapshots, never raw deltas. UI backpressure
            // can delay this publisher but cannot suspend the HTTP/SSE reader.
            // Each distinct native message and each control event survives conflation.
            val pending = linkedMapOf<String, VerifiedConversationEvent>()
            val snapshots = Channel<List<VerifiedConversationEvent>>(Channel.CONFLATED)
            val publisher = launch {
                val delivered = mutableMapOf<String, String>()
                for (snapshot in snapshots) for (event in snapshot) {
                    val id = if (event is VerifiedConversationEvent.TimelineUpsert) "message:${event.message.id}" else event.eventId
                    if (delivered[id] != event.eventId) {
                        emitEvent(agent.id, event)
                        delivered[id] = event.eventId
                    }
                }
            }
            fun enqueue(key: String, event: VerifiedConversationEvent) {
                pending[key] = event
                snapshots.trySend(pending.values.toList()).getOrThrow()
            }
            var revision = assistantTimestamp
            var lastSnapshotNanos = 0L
            fun publish(state: String) {
                enqueue("message:$assistantMsgId", VerifiedConversationEvent.TimelineUpsert(
                    eventId = "evt_${UUID.randomUUID()}", occurredAt = System.currentTimeMillis(),
                    revision = ++revision, message = TimelineMessage(
                        id = assistantMsgId, sender = "assistant", parts = listOf(MessagePart.Text(text.toString())),
                        timestamp = assistantTimestamp, state = state,
                        clientMessageId = ClientMessageId("assistant_${message.clientMessageId.value}"),
                        generationId = turn.generationId, conversationId = ConversationId(conversationId))))
            }
            suspend fun flush() {
                snapshots.close()
                publisher.join()
            }
            suspend fun cancelled() {
                val id = turn.generationId ?: return
                emitEvent(agent.id, VerifiedConversationEvent.GenerationCancelled("evt_${UUID.randomUUID()}",
                    System.currentTimeMillis(), id, ConversationId(conversationId)))
                if (isSelected(turn)) _generationState.value = GenerationState.CANCELLED
            }
            try {
                enqueue("accepted", VerifiedConversationEvent.MessageAccepted("evt_${UUID.randomUUID()}",
                    System.currentTimeMillis(), messageId, message.clientMessageId.value, ConversationId(conversationId)))
                val result = client.send(agent, conversationId, message.text,
                    attachmentIds = message.attachmentIds, clientMessageId = message.clientMessageId.value, onEvent = { event ->
                    when (event) {
                        is NativeAgentStreamEvent.MessageIdentity -> {
                            event.messageId?.let { remoteId ->
                                if (remoteId != assistantMsgId) {
                                    if (assistantHasNativeId && text.isNotEmpty()) {
                                        publish("CONFIRMED")
                                        text.clear()
                                        assistantTimestamp = System.currentTimeMillis()
                                    }
                                    assistantMsgId = remoteId
                                    assistantHasNativeId = true
                                    lastSnapshotNanos = 0L
                                }
                            }
                            event.userMessageId?.let { remoteId ->
                                if (remoteId != messageId) {
                                    messageId = remoteId
                                    enqueue("accepted", VerifiedConversationEvent.MessageAccepted("evt_${UUID.randomUUID()}",
                                        System.currentTimeMillis(), remoteId, message.clientMessageId.value, ConversationId(conversationId)))
                                }
                            }
                        }
                        is NativeAgentStreamEvent.Generation -> {
                            rememberGeneration(turn, event.generationId)
                            if (isSelected(turn)) {
                                _generationId.value = event.generationId
                                _generationState.value = GenerationState.RUNNING
                            }
                        }
                        is NativeAgentStreamEvent.ApprovalRequested -> emitApproval(turn, event.approval, true) { enqueue(it.eventId, it) }
                        is NativeAgentStreamEvent.ApprovalResolved -> emitApproval(turn, event.approval, false) { enqueue(it.eventId, it) }
                    }
                }) { delta ->
                    if (!turn.cancelled) {
                        text.append(delta)
                        val now = System.nanoTime()
                        if (lastSnapshotNanos == 0L || now - lastSnapshotNanos >= 33_000_000L) {
                            publish("STREAMING")
                            lastSnapshotNanos = now
                        }
                    }
                }
                result.generationId?.let { rememberGeneration(turn, it) }
                result.text?.let { text.clear(); text.append(it) }
                if (result.outcome == "cancelled" || turn.cancelled) {
                    publish("CANCELLED")
                    flush()
                    cancelled()
                } else {
                    publish("CONFIRMED")
                    flush()
                    emitEvent(agent.id, VerifiedConversationEvent.MessageStatus("evt_${UUID.randomUUID()}",
                        System.currentTimeMillis(), ConversationId(conversationId), messageId,
                        message.clientMessageId, AgentMessageStatus.COMPLETED, 1L, null, turn.generationId))
                    if (isSelected(turn)) _generationState.value = GenerationState.COMPLETED
                }
            } catch (cancelled: CancellationException) {
                // Closing a reader says nothing about whether Hermes stopped.
                throw cancelled
            } catch (failure: Exception) {
                if (turn.cancelled) { publish("CANCELLED"); flush(); cancelled() }
                else if (failure is AgentSessionException && failure.code == "OUTCOME_UNKNOWN") {
                    if (text.isNotEmpty()) publish("OUTCOME_UNKNOWN")
                    flush()
                    if (isSelected(turn)) _generationState.value = GenerationState.OUTCOME_UNKNOWN
                } else {
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
                turns.remove(key, turn)
                if (isSelected(turn) && _generationId.value == turn.generationId) _generationId.value = null
            }
        }
        // Only the host's first generation frame can issue a cancellable turn ID.
        return MessageAcceptance(messageId, message.clientMessageId.value, turn.generationId.takeIf { agent.kind != "hermes" })
    }

    override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> {
        val agentId = currentAgent()?.id
        return _events.filter { it.agentId == agentId }.map { it.event }
    }

    override suspend fun cancelGeneration(generationId: String, requestId: String): CancelGenerationResult {
        val conversationId = activeConversationId() ?: return CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        return cancelGeneration(conversationId, generationId, requestId)
    }

    override suspend fun cancelGeneration(conversationId: String, generationId: String, requestId: String): CancelGenerationResult {
        val turn = generationRoutes[generationId]?.takeIf { it.conversationId == conversationId }
            ?: return CancelGenerationResult(CancelGenerationOutcome.OUTCOME_UNKNOWN)
        val agent = turn.agent
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
        return timeline(conversationId, PageRequest(null, 100)).messages.firstOrNull {
            it.sender == "user" && it.clientMessageId == clientMessageId
        }
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
            code == "AGENT_MEDIA_REJECTED" -> com.openandroidintelligence.conversation.ports.AgentMessageErrorCode.AGENT_MEDIA_REJECTED
            code == "MODEL_REQUEST_REJECTED" -> com.openandroidintelligence.conversation.ports.AgentMessageErrorCode.MODEL_REQUEST_REJECTED
            code in setOf("AGENT_STREAM_INVALID", "AGENT_RESPONSE_INVALID", "HOST_INCOMPATIBLE") ->
                com.openandroidintelligence.conversation.ports.AgentMessageErrorCode.PROTOCOL_ERROR
            code in setOf("AGENT_OFFLINE", "AGENT_UNAVAILABLE") -> com.openandroidintelligence.conversation.ports.AgentMessageErrorCode.AGENT_UNAVAILABLE
            else -> com.openandroidintelligence.conversation.ports.AgentMessageErrorCode.AGENT_REQUEST_FAILED
        }
    }
}
