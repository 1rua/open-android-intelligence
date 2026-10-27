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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
) : ConversationRepository, GenerationTracker, StreamHealthSource, MessageOutcomeQuery {

    private val _generationId = MutableStateFlow<String?>(null)
    override val generationId: StateFlow<String?> = _generationId.asStateFlow()

    private val _generationState = MutableStateFlow(GenerationState.IDLE)
    override val generationState: StateFlow<GenerationState> = _generationState.asStateFlow()

    private val _streamHealth = MutableStateFlow<StreamHealth>(StreamHealth.LIVE)
    override val streamHealth: Flow<StreamHealth> = _streamHealth.asStateFlow()

    private val _events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 64)

    private var activeStreamingJob: Job? = null

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
        _events.emit(
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
        val messageId = "msg_${UUID.randomUUID()}"
        val genId = "gen_${UUID.randomUUID()}"

        _generationId.value = genId
        _generationState.value = GenerationState.RUNNING

        val accumulatedText = StringBuilder()
        val assistantMsgId = "asst_${UUID.randomUUID()}"
        val assistantTimestamp = System.currentTimeMillis()

        activeStreamingJob?.cancel()
        activeStreamingJob = parentScope.launch(ioDispatcher) {
            try {
                _events.emit(
                    VerifiedConversationEvent.MessageAccepted(
                        eventId = "evt_${UUID.randomUUID()}",
                        occurredAt = System.currentTimeMillis(),
                        messageId = messageId,
                        correlationId = message.clientMessageId.value,
                        conversationId = ConversationId(conversationId),
                    )
                )

                client.send(agent, conversationId, message.text) { delta ->
                    accumulatedText.append(delta)
                    val currentText = accumulatedText.toString()
                    _events.emit(
                        VerifiedConversationEvent.TimelineUpsert(
                            eventId = "evt_${UUID.randomUUID()}",
                            occurredAt = System.currentTimeMillis(),
                            revision = System.currentTimeMillis(),
                            message = TimelineMessage(
                                id = assistantMsgId,
                                sender = "assistant",
                                parts = listOf(MessagePart.Text(currentText)),
                                timestamp = assistantTimestamp,
                                state = "STREAMING",
                                conversationId = ConversationId(conversationId),
                                generationId = genId,
                            )
                        )
                    )
                }

                // Final completed message
                _events.emit(
                    VerifiedConversationEvent.TimelineUpsert(
                        eventId = "evt_${UUID.randomUUID()}",
                        occurredAt = System.currentTimeMillis(),
                        revision = System.currentTimeMillis(),
                        message = TimelineMessage(
                            id = assistantMsgId,
                            sender = "assistant",
                            parts = listOf(MessagePart.Text(accumulatedText.toString())),
                            timestamp = assistantTimestamp,
                            state = "CONFIRMED",
                            conversationId = ConversationId(conversationId),
                            generationId = genId,
                        )
                    )
                )

                _events.emit(
                    VerifiedConversationEvent.MessageStatus(
                        eventId = "evt_${UUID.randomUUID()}",
                        occurredAt = System.currentTimeMillis(),
                        conversationId = ConversationId(conversationId),
                        messageId = messageId,
                        clientMessageId = message.clientMessageId,
                        status = AgentMessageStatus.COMPLETED,
                        revision = 1L,
                        errorCode = null,
                        generationId = genId,
                    )
                )
            } catch (cancelled: CancellationException) {
                _events.emit(
                    VerifiedConversationEvent.GenerationCancelled(
                        eventId = "evt_${UUID.randomUUID()}",
                        occurredAt = System.currentTimeMillis(),
                        generationId = genId,
                        conversationId = ConversationId(conversationId),
                    )
                )
                throw cancelled
            } catch (err: Exception) {
                _events.emit(
                    VerifiedConversationEvent.MessageStatus(
                        eventId = "evt_${UUID.randomUUID()}",
                        occurredAt = System.currentTimeMillis(),
                        conversationId = ConversationId(conversationId),
                        messageId = messageId,
                        clientMessageId = message.clientMessageId,
                        status = AgentMessageStatus.FAILED,
                        revision = 1L,
                        errorCode = com.openandroidintelligence.conversation.ports.AgentMessageErrorCode.AGENT_UNAVAILABLE,
                        generationId = genId,
                    )
                )
            } finally {
                if (_generationId.value == genId) {
                    _generationId.value = null
                    _generationState.value = GenerationState.IDLE
                }
            }
        }

        return MessageAcceptance(
            messageId = messageId,
            correlationId = message.clientMessageId.value,
            generationId = genId,
        )
    }

    override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> = _events.asSharedFlow()

    override suspend fun cancelGeneration(generationId: String, requestId: String): CancelGenerationResult {
        val convId = activeConversationId() ?: return CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        return cancelGeneration(convId, generationId, requestId)
    }

    override suspend fun cancelGeneration(
        conversationId: String,
        generationId: String,
        requestId: String,
    ): CancelGenerationResult {
        val agent = requireAgent()
        if (!agent.capabilities.cancel) {
            return CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        }
        activeStreamingJob?.cancel()
        return try {
            withContext(ioDispatcher) { client.cancel(agent, conversationId) }
            _events.emit(
                VerifiedConversationEvent.GenerationCancelled(
                    eventId = "evt_${UUID.randomUUID()}",
                    occurredAt = System.currentTimeMillis(),
                    generationId = generationId,
                    conversationId = ConversationId(conversationId),
                )
            )
            _generationId.value = null
            _generationState.value = GenerationState.IDLE
            CancelGenerationResult(CancelGenerationOutcome.CANCELLED)
        } catch (_: Exception) {
            CancelGenerationResult(CancelGenerationOutcome.OUTCOME_UNKNOWN)
        }
    }

    override suspend fun updateTitle(conversationId: String, title: String): Boolean {
        val agent = requireAgent()
        if (!agent.capabilities.rename || !agent.online) return false
        return try {
            withContext(ioDispatcher) { client.rename(agent, conversationId, title) }
            _events.emit(
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
        _events.emit(
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
        _events.emit(
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
