package com.openandroidintelligence.conversation.data

import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.ports.AgentCommandCatalogRepository
import com.openandroidintelligence.conversation.ports.BatchAcceptance
import com.openandroidintelligence.conversation.ports.Conversation
import com.openandroidintelligence.conversation.ports.ConversationPage
import com.openandroidintelligence.conversation.ports.ConversationRepository
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.ports.MessageAcceptance
import com.openandroidintelligence.conversation.ports.MessageBatch
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.ports.TimelinePage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.gateway.commands.CommandCatalogClient
import com.openandroidintelligence.gateway.conversations.BatchAcceptance as WireBatchAcceptance
import com.openandroidintelligence.gateway.conversations.ConversationClient
import com.openandroidintelligence.gateway.conversations.MessageBatchRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * The domain's conversation port, backed by the real Gateway Protocol v2 client.
 *
 * Nothing here manufactures a conversation, a message or an outcome. Every
 * result is whatever the Gateway returned, and a failure keeps its code so the
 * UI can tell "empty" from "we could not ask".
 */
class GatewayConversationRepository(
    private val client: ConversationClient,
    private val decoder: GatewayEventDecoder = GatewayEventDecoder,
    /** Where the transport reports whether the reply channel is actually alive. */
    private val streamStatus: com.openandroidintelligence.gateway.events.EventStreamStatusSink? = null,
    /** The thread cancellation and event scope act on; owned by the screen holder. */
    private val activeConversationId: () -> String? = { null },
) : ConversationRepository,
    com.openandroidintelligence.conversation.ports.GenerationTracker,
    com.openandroidintelligence.conversation.model.StreamHealthSource {

    private val _generationId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    override val generationId: kotlinx.coroutines.flow.StateFlow<String?> = _generationId

    /**
     * The transport's own status, in the domain's vocabulary.
     *
     * Without this a screen could only infer health from silence, which is
     * exactly how a dropped reply used to look like an app that never answered.
     */
    override val streamHealth: kotlinx.coroutines.flow.Flow<com.openandroidintelligence.conversation.model.StreamHealth> =
        com.openandroidintelligence.conversation.data.StreamHealthBridge.of(streamStatus)


    override suspend fun listConversations(
        scope: ConversationScope,
        page: PageRequest,
    ): ConversationPage {
        val threads = client.readThreads()
        return ConversationPage(
            conversations = threads.map { thread ->
                ConversationSummary(
                    id = ConversationId(thread.conversationId),
                    title = thread.title?.takeIf { it.isNotBlank() } ?: "新对话",
                    updatedAt = parseMillis(thread.lastMessageAt),
                )
            },
            // The v2 list endpoint is not cursor-paginated yet; a single page is
            // the honest answer instead of an invented next cursor.
            nextCursor = null,
        )
    }

    override suspend fun createConversation(
        scope: ConversationScope,
        clientConversationId: String,
    ): Conversation {
        val detail = client.createConversation(clientConversationId)
        return Conversation(
            id = ConversationId(detail.conversationId),
            title = detail.title ?: "新对话",
            createdAt = parseMillis(detail.createdAt),
        )
    }

    override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage {
        val result = client.readTimeline(
            conversationId = conversationId,
            cursor = page.cursor,
            limit = page.limit,
        )
        var lastValidTimestamp = 0L
        return TimelinePage(
            messages = result.messages.mapIndexed { index, message ->
                val rawTs = message.timestamp ?: 0L
                val resolvedTimestamp = if (rawTs > 0L) {
                    lastValidTimestamp = rawTs
                    rawTs
                } else if (lastValidTimestamp > 0L) {
                    lastValidTimestamp += 1L
                    lastValidTimestamp
                } else {
                    val fallbackBase = System.currentTimeMillis() - ((result.messages.size - index) * 1000L)
                    fallbackBase.coerceAtLeast(1L)
                }
                com.openandroidintelligence.conversation.ports.TimelineMessage(
                    id = message.messageId,
                    sender = message.sender,
                    parts = message.parts.map { part ->
                        when (part) {
                            is com.openandroidintelligence.gateway.conversations.MessagePart.Text ->
                                com.openandroidintelligence.conversation.model.MessagePart.Text(part.text)
                            is com.openandroidintelligence.gateway.conversations.MessagePart.AttachmentRef ->
                                com.openandroidintelligence.conversation.model.MessagePart.Attachment(
                                    draftId = com.openandroidintelligence.conversation.model.AttachmentDraftId(part.attachmentId),
                                    filename = part.filename,
                                    mediaType = part.mediaType,
                                )
                        }
                    },
                    timestamp = resolvedTimestamp,
                    state = message.state,
                )
            },
            nextCursor = result.nextCursor,
        )
    }

    override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance {
        val conversationId = batch.clientConversationId
            ?: activeConversationId()
            ?: throw IllegalStateException("SUBMIT_BATCH_FAILED:no-conversation")
        val acceptance: WireBatchAcceptance = client.submitBatch(
            conversationId = conversationId,
            batch = MessageBatchRequest(
                clientBatchId = batch.batchId,
                clientConversationId = conversationId,
                members = batch.messages.map { message ->
                    MessageBatchRequest.BatchMember(
                        clientMessageId = message.clientMessageId.value,
                        text = message.text,
                        attachmentIds = message.attachmentIds,
                    )
                },
            ),
        )
        // The mapping travels upward unchanged: it is the only way a caller can
        // key a mirrored member by the id the Gateway will use for it.
        return BatchAcceptance(batchId = acceptance.batchId, memberIds = acceptance.memberIds)
    }

    override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance = submitMessage(
        activeConversationId() ?: throw IllegalStateException("SUBMIT_MESSAGE_FAILED:no-conversation"),
        message,
    )

    override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
        val response = client.sendMessage(
            conversationId = conversationId,
            clientMessageId = message.clientMessageId.value,
            text = message.text,
            attachmentIds = message.attachmentIds,
        )
        return MessageAcceptance(response.messageId, message.clientMessageId.value)
    }

    /**
     * Cancellation results stay a closed set.
     *
     * A 404 means the generation already finished, which is a different fact
     * from "we asked and it stopped"; the UI shows them differently.
     */
    override suspend fun cancelGeneration(
        generationId: String,
        requestId: String,
    ): com.openandroidintelligence.conversation.ports.CancelGenerationResult {
        val conversationId = activeConversationId()
            ?: return com.openandroidintelligence.conversation.ports.CancelGenerationResult(
                outcome = com.openandroidintelligence.conversation.ports.CancelGenerationOutcome.UNSUPPORTED,
                message = "NO_ACTIVE_CONVERSATION",
            )
        val outcome = client.cancelGeneration(conversationId, generationId, requestId)
        return com.openandroidintelligence.conversation.ports.CancelGenerationResult(
            outcome = when (outcome) {
                "CANCELLED" -> com.openandroidintelligence.conversation.ports.CancelGenerationOutcome.CANCELLED
                "ALREADY_COMPLETED" -> com.openandroidintelligence.conversation.ports.CancelGenerationOutcome.ALREADY_COMPLETED
                "UNSUPPORTED" -> com.openandroidintelligence.conversation.ports.CancelGenerationOutcome.UNSUPPORTED
                else -> com.openandroidintelligence.conversation.ports.CancelGenerationOutcome.OUTCOME_UNKNOWN
            },
            message = outcome,
        )
    }

    override suspend fun updateTitle(conversationId: String, title: String): Boolean =
        runCatching { client.updateConversationTitle(conversationId, title) }.getOrDefault(false)

    override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> =
        client.rawEvents().mapNotNull { event ->
            decoder.generationIdOf(event)?.let { _generationId.value = it }
            decoder.decode(event)
        }

    private fun parseMillis(value: String?): Long =
        value?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
}

/**
 * The Agent command catalog, read from the Gateway.
 *
 * The version is sanitised into a wire-safe value: a catalog version is an
 * opaque token to the phone, but a malformed one must not crash the composer.
 */
class GatewayCommandCatalogRepository(
    private val client: CommandCatalogClient,
) : AgentCommandCatalogRepository {

    override suspend fun get(gatewayId: String, languageCode: String): AgentCommandCatalog {
        val catalog = client.get(languageCode)
        val version = catalog.catalogVersion.takeIf { WIRE_ID.matches(it) } ?: "unknown"
        return AgentCommandCatalog(
            version = CatalogVersion(version),
            commands = catalog.commands.map { entry ->
                com.openandroidintelligence.conversation.ports.AgentCommand(
                    command = entry.invocation,
                    description = entry.description.ifBlank { entry.title },
                    argumentHint = entry.title.takeIf { entry.acceptsArguments },
                )
            },
        )
    }

    private companion object {
        val WIRE_ID = Regex("^[A-Za-z0-9._~-]+$")
    }
}
