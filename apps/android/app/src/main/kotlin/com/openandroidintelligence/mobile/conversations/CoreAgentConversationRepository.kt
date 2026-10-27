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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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
) : ConversationRepository, GenerationTracker, StreamHealthSource, MessageOutcomeQuery {

    private val _generationId = MutableStateFlow<String?>(null)
    override val generationId: StateFlow<String?> = _generationId.asStateFlow()

    private val _generationState = MutableStateFlow(GenerationState.IDLE)
    override val generationState: StateFlow<GenerationState> = _generationState.asStateFlow()

    private val _streamHealth = MutableStateFlow<StreamHealth>(StreamHealth.LIVE)
    override val streamHealth: Flow<StreamHealth> = _streamHealth.asStateFlow()

    private data class AgentEvent(val agentId: String, val event: VerifiedConversationEvent)
    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64)

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

    private suspend fun emitApproval(turn: NativeTurn, payload: JsonValue.JObject, requested: Boolean) {
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
        emitEvent(turn.agent.id, decoded)
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
                parts = listOf(MessagePart.Text(msg.text)),
                timestamp = parseDate(msg.createdAt),
                conversationId = ConversationId(conversationId),
            )
        }
        return TimelinePage(messages = timelineMessages, nextCursor = null)
    }

    override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance {
        val convId = batch.clientConversationId ?: activeConversationId() ?: error("NO_CONVERSATION")
        val text = batch.messages.firstOrNull()?.text.orEmpty()
        val result = submitMessage(convId, OutgoingMessage(ClientMessageId(UUID.randomUUID().toString()), text))
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
        val messageId = "msg_${UUID.randomUUID()}"
        val assistantMsgId = "asst_${UUID.randomUUID()}"
        if (isSelected(turn)) {
            _generationId.value = turn.generationId
            _generationState.value = if (turn.generationId == null) GenerationState.QUEUED else GenerationState.RUNNING
        }
        parentScope.launch(ioDispatcher) {
            val text = StringBuilder()
            suspend fun publish(state: String) {
                emitEvent(agent.id, VerifiedConversationEvent.TimelineUpsert(
                    eventId = "evt_${UUID.randomUUID()}", occurredAt = System.currentTimeMillis(),
                    revision = System.currentTimeMillis(), message = TimelineMessage(
                        id = assistantMsgId, sender = "assistant", parts = listOf(MessagePart.Text(text.toString())),
                        timestamp = System.currentTimeMillis(), state = state,
                        generationId = turn.generationId, conversationId = ConversationId(conversationId))))
            }
            suspend fun cancelled() {
                val id = turn.generationId ?: return
                emitEvent(agent.id, VerifiedConversationEvent.GenerationCancelled("evt_${UUID.randomUUID()}",
                    System.currentTimeMillis(), id, ConversationId(conversationId)))
                if (isSelected(turn)) _generationState.value = GenerationState.CANCELLED
            }
            try {
                emitEvent(agent.id, VerifiedConversationEvent.MessageAccepted("evt_${UUID.randomUUID()}",
                    System.currentTimeMillis(), messageId, message.clientMessageId.value, ConversationId(conversationId)))
                val result = client.send(agent, conversationId, message.text, onEvent = { event ->
                    when (event) {
                        is NativeAgentStreamEvent.Generation -> {
                            rememberGeneration(turn, event.generationId)
                            if (isSelected(turn)) {
                                _generationId.value = event.generationId
                                _generationState.value = GenerationState.RUNNING
                            }
                        }
                        is NativeAgentStreamEvent.ApprovalRequested -> emitApproval(turn, event.approval, true)
                        is NativeAgentStreamEvent.ApprovalResolved -> emitApproval(turn, event.approval, false)
                    }
                }) { delta ->
                    if (!turn.cancelled) {
                        text.append(delta)
                        publish("STREAMING")
                    }
                }
                result.generationId?.let { rememberGeneration(turn, it) }
                if (result.outcome == "cancelled" || turn.cancelled) {
                    publish("CANCELLED")
                    cancelled()
                } else {
                    publish("CONFIRMED")
                    emitEvent(agent.id, VerifiedConversationEvent.MessageStatus("evt_${UUID.randomUUID()}",
                        System.currentTimeMillis(), ConversationId(conversationId), assistantMsgId,
                        message.clientMessageId, AgentMessageStatus.COMPLETED, 1L, null, turn.generationId))
                    if (isSelected(turn)) _generationState.value = GenerationState.COMPLETED
                }
            } catch (cancelled: CancellationException) {
                // Closing a reader says nothing about whether Hermes stopped.
                throw cancelled
            } catch (failure: Exception) {
                if (turn.cancelled) cancelled()
                else if (failure is AgentSessionException && failure.code == "OUTCOME_UNKNOWN") {
                    if (isSelected(turn)) _generationState.value = GenerationState.OUTCOME_UNKNOWN
                } else {
                    emitEvent(agent.id, VerifiedConversationEvent.MessageStatus("evt_${UUID.randomUUID()}",
                        System.currentTimeMillis(), ConversationId(conversationId), assistantMsgId,
                        message.clientMessageId, AgentMessageStatus.FAILED, 1L,
                        com.openandroidintelligence.conversation.ports.AgentMessageErrorCode.AGENT_UNAVAILABLE, turn.generationId))
                    if (isSelected(turn)) _generationState.value = GenerationState.FAILED
                }
            } finally {
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
        return ConversationOrganization()
    }

    override suspend fun setArchived(conversationId: String, archived: Boolean) {
        val agent = requireAgent()
        if (!agent.capabilities.archive || !agent.online) return
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
        if (!agent.capabilities.delete || !agent.online) return
        withContext(ioDispatcher) { client.delete(agent, conversationId) }
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
        return null
    }

    private fun parseDate(iso: String?): Long {
        if (iso.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            format.parse(iso.substringBefore('.'))?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }
}
