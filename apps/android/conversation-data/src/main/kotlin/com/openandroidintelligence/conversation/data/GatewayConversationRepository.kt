package com.openandroidintelligence.conversation.data

import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.ApprovalId
import com.openandroidintelligence.conversation.model.ApprovalOutcome
import com.openandroidintelligence.conversation.model.ApprovalSubmissionOutcome
import com.openandroidintelligence.conversation.model.ApprovalSubmissionResult
import com.openandroidintelligence.conversation.model.AttachmentDraftId
import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.model.StreamHealth
import com.openandroidintelligence.conversation.model.StreamHealthSource
import com.openandroidintelligence.conversation.ports.AgentCommand
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.ports.AgentCommandCatalogRepository
import com.openandroidintelligence.conversation.ports.BatchAcceptance
import com.openandroidintelligence.conversation.ports.CancelGenerationOutcome
import com.openandroidintelligence.conversation.ports.CancelGenerationResult
import com.openandroidintelligence.conversation.ports.Conversation
import com.openandroidintelligence.conversation.ports.ConversationPage
import com.openandroidintelligence.conversation.ports.ConversationRepository
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.ports.GenerationTracker
import com.openandroidintelligence.conversation.ports.MessageAcceptance
import com.openandroidintelligence.conversation.ports.MessageBatch
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.ports.TimelinePage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.gateway.approvals.ApprovalClient
import com.openandroidintelligence.gateway.approvals.ApprovalDecision
import com.openandroidintelligence.gateway.approvals.ApprovalDecisionOutcome
import com.openandroidintelligence.gateway.commands.CommandCatalogClient
import com.openandroidintelligence.gateway.conversations.BatchAcceptance as WireBatchAcceptance
import com.openandroidintelligence.gateway.conversations.ConversationClient
import com.openandroidintelligence.gateway.conversations.MessageBatchRequest
import com.openandroidintelligence.gateway.conversations.MessagePart as WireMessagePart
import com.openandroidintelligence.gateway.events.EventStreamStatusSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val streamStatus: EventStreamStatusSink? = null,
    /**
     * The approval decision endpoint, when this Gateway negotiated §7.2 cards.
     *
     * Absent means the Gateway cannot take a decision at all, which is a fact
     * the UI has to show: a card without this client would accept a press and
     * then leave the command blocked.
     */
    private val approvals: ApprovalClient? = null,
    /** The thread cancellation and event scope act on; owned by the screen holder. */
    private val activeConversationId: () -> String? = { null },
) : ConversationRepository,
    com.openandroidintelligence.conversation.ports.MessageOutcomeQuery,
    GenerationTracker,
    StreamHealthSource {

    private val _generationId = MutableStateFlow<String?>(null)
    override val generationId: StateFlow<String?> = _generationId
    private val generations=mutableMapOf<String,LinkedHashMap<String,com.openandroidintelligence.conversation.model.GenerationState>>()
    private val terminalGenerations=LinkedHashSet<Pair<String,String>>()
    private companion object {
        // 与 HTTP 事件身份窗口采用相同容量，终态记录不会无限积累。
        private const val MAX_TERMINAL_GENERATIONS = 4096
    }
    private val _generationState=MutableStateFlow(com.openandroidintelligence.conversation.model.GenerationState.IDLE)
    override val generationState:StateFlow<com.openandroidintelligence.conversation.model.GenerationState> = _generationState
    @Synchronized fun activeConversationChanged() {
        val current=generations[activeConversationId()]?.entries?.firstOrNull { it.value!=com.openandroidintelligence.conversation.model.GenerationState.QUEUED }
            ?: generations[activeConversationId()]?.entries?.firstOrNull()
        _generationId.value=current?.key
        _generationState.value=current?.value ?: com.openandroidintelligence.conversation.model.GenerationState.IDLE
    }
    @Synchronized fun installCurrentGeneration(conversationId:String,current:Pair<String,String>?) {
        if (current != null && (conversationId to current.first) in terminalGenerations) {
            activeConversationChanged()
            return
        }
        generations.remove(conversationId)
        current?.let { generations[conversationId]=linkedMapOf(it.first to when(it.second) {
            "queued" -> com.openandroidintelligence.conversation.model.GenerationState.QUEUED
            "unknown" -> com.openandroidintelligence.conversation.model.GenerationState.OUTCOME_UNKNOWN
            else -> com.openandroidintelligence.conversation.model.GenerationState.RUNNING
        }) }
        activeConversationChanged()
    }
    @Synchronized private fun acceptGeneration(conversationId:String,id:String?) {
        id?.takeUnless { (conversationId to it) in terminalGenerations }?.let {
            generations.getOrPut(conversationId) { linkedMapOf() }.putIfAbsent(it,com.openandroidintelligence.conversation.model.GenerationState.QUEUED)
        }
        activeConversationChanged()
    }
    private fun rememberTerminalGeneration(conversationId:String,id:String) {
        terminalGenerations.add(conversationId to id)
        if (terminalGenerations.size > MAX_TERMINAL_GENERATIONS) terminalGenerations.iterator().let {
            it.next()
            it.remove()
        }
    }
    @Synchronized private fun generationEvent(decoded:GatewayEventDecoder.DecodedFrame) {
        val conversation=decoded.conversationId ?: return
        val id=decoded.generationId ?: return
        // 同一明确生成的终态不可逆，旧事件或较晚的 HTTP 回执只能补充受理身份。
        if ((conversation to id) in terminalGenerations) return
        val entries=generations.getOrPut(conversation) { linkedMapOf() }
        when(val e=decoded.event) {
            is VerifiedConversationEvent.MessageStatus -> when(e.status) {
                com.openandroidintelligence.conversation.ports.AgentMessageStatus.QUEUED -> entries.putIfAbsent(id,com.openandroidintelligence.conversation.model.GenerationState.QUEUED)
                com.openandroidintelligence.conversation.ports.AgentMessageStatus.DELIVERED -> entries[id]=com.openandroidintelligence.conversation.model.GenerationState.RUNNING
                com.openandroidintelligence.conversation.ports.AgentMessageStatus.COMPLETED,
                com.openandroidintelligence.conversation.ports.AgentMessageStatus.FAILED -> {
                    rememberTerminalGeneration(conversation,id)
                    entries.remove(id)
                }
            }
            is VerifiedConversationEvent.GenerationCancelled -> {
                rememberTerminalGeneration(conversation,id)
                entries.remove(id)
            }
            else -> Unit
        }
        if (entries.isEmpty()) generations.remove(conversation)
        activeConversationChanged()
    }

    /**
     * The transport's own status, in the domain's vocabulary.
     *
     * Without this a screen could only infer health from silence, which is
     * exactly how a dropped reply used to look like an app that never answered.
     */
    override val streamHealth: Flow<StreamHealth> = StreamHealthBridge.of(streamStatus)


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
                TimelineMessage(
                    id = message.messageId,
                    sender = message.sender,
                    parts = message.parts.map { part ->
                        when (part) {
                            is WireMessagePart.Text ->
                                MessagePart.Text(part.text)
                            is WireMessagePart.AttachmentRef ->
                                MessagePart.Attachment(
                                    draftId = AttachmentDraftId(part.attachmentId),
                                    filename = part.filename,
                                    mediaType = part.mediaType,
                                )
                        }
                    },
                    timestamp = resolvedTimestamp,
                    state = message.state,
                    batchId=message.batchId,
                    clientMessageId=message.clientMessageId?.let { com.openandroidintelligence.conversation.model.ClientMessageId(it) },
                )
            },
            nextCursor = result.nextCursor,
            snapshotRevision = result.snapshotRevision,
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
        acceptGeneration(conversationId,acceptance.generationId)
        return BatchAcceptance(batchId = acceptance.batchId, memberIds = acceptance.memberIds)
    }

    override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance = submitMessage(
        activeConversationId() ?: throw IllegalStateException("SUBMIT_MESSAGE_FAILED:no-conversation"),
        message,
    )

    override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
        val response = try { client.sendMessage(
            conversationId = conversationId,
            clientMessageId = message.clientMessageId.value,
            text = message.text,
            attachmentIds = message.attachmentIds,
        ) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cause: Exception) {
            // The server may have committed before the transport lost the ACK.
            // Query by the frozen business ID before allowing a manual retry.
            val accepted = try { client.queryMessage(conversationId, message.clientMessageId.value) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (accepted != null) return MessageAcceptance(accepted.messageId, message.clientMessageId.value)
            throw cause
        }
        acceptGeneration(conversationId,response.generationId)
        return MessageAcceptance(response.messageId, message.clientMessageId.value)
    }

    override suspend fun queryMessage(conversationId: String, clientMessageId: com.openandroidintelligence.conversation.model.ClientMessageId): TimelineMessage? {
        val message = client.queryMessage(conversationId, clientMessageId.value) ?: return null
        check(message.clientMessageId == null || message.clientMessageId == clientMessageId.value) { "MESSAGE_QUERY_ID_MISMATCH" }
        return TimelineMessage(message.messageId, message.sender, message.parts.map { part ->
            when (part) {
                is WireMessagePart.Text -> MessagePart.Text(part.text)
                is WireMessagePart.AttachmentRef -> MessagePart.Attachment(AttachmentDraftId(part.attachmentId), part.filename, part.mediaType)
            }
        }, message.timestamp ?: 0L, message.state, conversationId = ConversationId(conversationId),
            clientMessageId = com.openandroidintelligence.conversation.model.ClientMessageId(message.clientMessageId ?: clientMessageId.value))
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
    ): CancelGenerationResult {
        val conversationId = activeConversationId()
            ?: return CancelGenerationResult(
                outcome = CancelGenerationOutcome.UNSUPPORTED,
                message = "NO_ACTIVE_CONVERSATION",
            )
        val outcome = client.cancelGeneration(conversationId, generationId, requestId)
        return CancelGenerationResult(
            outcome = when (outcome) {
                "CANCELLED" -> CancelGenerationOutcome.CANCELLED
                "ALREADY_COMPLETED" -> CancelGenerationOutcome.ALREADY_COMPLETED
                "UNSUPPORTED" -> CancelGenerationOutcome.UNSUPPORTED
                else -> CancelGenerationOutcome.OUTCOME_UNKNOWN
            },
            message = outcome,
        )
    }

    override suspend fun updateTitle(conversationId: String, title: String): Boolean =
        runCatching { client.updateConversationTitle(conversationId, title) }.getOrDefault(false)

    /**
     * One conversation's metadata, straight from the Gateway.
     *
     * A new conversation arrives as an id carried by a command result; reading
     * it back here is how the phone learns the title the Gateway actually
     * stored instead of assuming one.
     */
    override suspend fun readConversation(conversationId: String): Conversation? {
        val detail = runCatching { client.readConversation(conversationId) }.getOrNull() ?: return null
        return Conversation(
            id = ConversationId(detail.conversationId),
            title = detail.title?.takeIf { it.isNotBlank() } ?: "新对话",
            createdAt = parseMillis(detail.createdAt),
        )
    }

    override suspend fun submitApprovalDecision(
        approvalId: ApprovalId,
        choice: ApprovalChoice,
    ): ApprovalSubmissionResult {
        val client = approvals ?: return ApprovalSubmissionResult(
            outcome = ApprovalSubmissionOutcome.UNSUPPORTED,
        )
        // The four tiers a card can draw share one wire vocabulary with the
        // Gateway's own enum, so this is a translation of the same token rather
        // than a second table kept in step by hand. `UNKNOWN` is no tier at all:
        // sending it would be a guess dressed up as a decision.
        val decision = ApprovalDecision.of(choice.wireValue)
            ?: return ApprovalSubmissionResult(outcome = ApprovalSubmissionOutcome.UNSUPPORTED)
        val result = try {
            client.submitDecision(approvalId.value, decision)
        } catch (cancellation: CancellationException) {
            // A cancelled coroutine is not a failed submission: counting it as
            // one would paint a Gateway refusal over a scope that was simply
            // torn down, and hide the cancellation from the caller.
            throw cancellation
        } catch (_: Throwable) {
            return ApprovalSubmissionResult(outcome = ApprovalSubmissionOutcome.FAILED)
        }
        return ApprovalSubmissionResult(
            outcome = when (result.outcome) {
                ApprovalDecisionOutcome.SUBMITTED ->
                    ApprovalSubmissionOutcome.SUBMITTED
                ApprovalDecisionOutcome.ALREADY_RESOLVED ->
                    ApprovalSubmissionOutcome.ALREADY_RESOLVED
                ApprovalDecisionOutcome.EXPIRED ->
                    ApprovalSubmissionOutcome.EXPIRED
                ApprovalDecisionOutcome.NOT_FOUND ->
                    ApprovalSubmissionOutcome.NOT_FOUND
                ApprovalDecisionOutcome.UNSUPPORTED ->
                    ApprovalSubmissionOutcome.UNSUPPORTED
                ApprovalDecisionOutcome.FAILED ->
                    ApprovalSubmissionOutcome.FAILED
            },
            // The Gateway names the tier it recorded with the same token the
            // pressed option carried, so the answer reads back through the one
            // closed set instead of a second translation written by hand.
            choice = result.decision?.let { recorded -> ApprovalChoice.of(recorded.wireValue) },
            errorCode = result.errorCode,
            // `timeout` and `withdrawn` are outcomes the Gateway owns, not tiers
            // a phone may press: mapping them onto a choice would invent a press
            // that never happened.
            settledOutcome = when (result.rawDecision) {
                "timeout" -> ApprovalOutcome.TIMED_OUT
                "withdrawn" -> ApprovalOutcome.WITHDRAWN
                else -> null
            },
        )
    }

    override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> =
        client.rawEvents().mapNotNull { event ->
            // One parse per frame, and the generation id is published before the
            // event, exactly as when the two readers ran in sequence: a frame
            // that starts a generation has to be cancellable by the time its
            // event reaches the UI.
            val decoded = decoder.decodeWithGenerationId(event)
            generationEvent(decoded)
            decoded.event
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
                AgentCommand(
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
