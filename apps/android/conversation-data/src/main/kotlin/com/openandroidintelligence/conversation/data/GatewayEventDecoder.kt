package com.openandroidintelligence.conversation.data

import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.ApprovalId
import com.openandroidintelligence.conversation.model.ApprovalOption
import com.openandroidintelligence.conversation.model.ApprovalOptionStyle
import com.openandroidintelligence.conversation.model.ApprovalOutcome
import com.openandroidintelligence.conversation.model.ApprovalRequest
import com.openandroidintelligence.conversation.model.ApprovalSeverity
import com.openandroidintelligence.conversation.model.AttachmentDraftId
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.AgentMessageErrorCode
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import com.openandroidintelligence.conversation.ports.CommandOutcome
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue

/**
 * Turns Gateway SSE frames into domain events.
 *
 * The mapping is deliberately closed: an event name the domain does not model
 * returns null rather than being coerced into the nearest shape. Anything the
 * phone cannot interpret stays unknown instead of becoming a fake state the
 * user would trust.
 */
object GatewayEventDecoder {

    /**
     * The documented fallback window when a Gateway omits `timeoutSeconds`.
     *
     * Public so a test can read the same number the decoder uses: the phone
     * never treats its own clock as the authority, it only needs a number to
     * count down from until the Gateway says otherwise.
     */
    const val DEFAULT_APPROVAL_TIMEOUT_SECONDS = 300L

    /**
     * One frame's JSON read once: the event the domain models for it, if it
     * models that name at all, and the generation id the Gateway issued.
     *
     * The stream needs both readings of every frame, so parsing the same bytes
     * twice per frame was the only cost this shape had to remove.
     */
    data class DecodedFrame(val event: VerifiedConversationEvent?, val generationId: String?)

    fun decode(event: GatewayEvent): VerifiedConversationEvent? = decodedEventOf(event, parse(event))

    /**
     * Both readings of one frame, out of a single parse.
     *
     * [DecodedFrame.generationId] is answered even for an event name the domain
     * does not model: cancellation only ever needs the id the Gateway issued,
     * and dropping it because the event was unfamiliar would leave a running
     * generation uncancellable.
     */
    fun decodeWithGenerationId(event: GatewayEvent): DecodedFrame {
        val frame = parse(event)
        return DecodedFrame(
            event = decodedEventOf(event, frame),
            generationId = generationIdOf(frame),
        )
    }

    private fun decodedEventOf(event: GatewayEvent, frame: ParsedFrame): VerifiedConversationEvent? {
        val name = event.event ?: return null
        val body = frame.body
        val payload = frame.payload
        val occurredAt = parseOccurredAt(JsonFields.string(body, "occurredAt"))
        val eventId = event.id.orEmpty()

        return when (name) {
            "conversation.message.accepted" -> VerifiedConversationEvent.MessageAccepted(
                eventId = eventId,
                occurredAt = occurredAt,
                messageId = JsonFields.string(payload, "messageId").orEmpty(),
                correlationId = JsonFields.string(payload, "correlationId")
                    ?: JsonFields.string(body, "correlationId").orEmpty(),
                conversationId = conversationIdOf(payload, body),
            )

            "conversation.message.status" -> messageStatus(eventId, occurredAt, payload)

            "conversation.message.delta" -> timelineUpsert(eventId, occurredAt, payload, "STREAMING", body)

            "conversation.message.completed" -> timelineUpsert(eventId, occurredAt, payload, "CONFIRMED", body)

            "conversation.generation.cancelled" -> VerifiedConversationEvent.GenerationCancelled(
                eventId = eventId,
                occurredAt = occurredAt,
                generationId = JsonFields.string(payload, "generationId").orEmpty(),
                conversationId = conversationIdOf(payload, body),
            )

            "conversation.command.result" -> VerifiedConversationEvent.CommandResult(
                eventId = eventId,
                occurredAt = occurredAt,
                command = JsonFields.string(payload, "command").orEmpty(),
                outcome = commandOutcomeOf(JsonFields.string(payload, "outcome")),
                sourceConversationId = conversationIdOfKey(payload, "sourceConversationId"),
                sourceMessageId = JsonFields.string(payload, "sourceMessageId")?.takeIf { it.isNotBlank() },
                conversationId = conversationIdOf(payload, body),
            )

            "conversation.title.updated" -> {
                val conversationId = conversationIdOf(payload, body) ?: return null
                val newTitle = JsonFields.string(payload, "title")
                    ?: JsonFields.string(payload, "newTitle")
                    ?: if (payload !== body) {
                        JsonFields.string(body, "title") ?: JsonFields.string(body, "newTitle").orEmpty()
                    } else ""
                VerifiedConversationEvent.TitleUpdated(
                    eventId = eventId,
                    occurredAt = occurredAt,
                    conversationId = conversationId,
                    newTitle = newTitle,
                )
            }

            "conversation.timeline.upsert" -> timelineUpsert(
                eventId = eventId,
                occurredAt = occurredAt,
                payload = payload,
                state = JsonFields.string(payload, "state") ?: "CONFIRMED",
                body = body,
            )

            "conversation.timeline.tombstoned" -> {
                val messageId = JsonFields.string(payload, "messageId") ?: return null
                VerifiedConversationEvent.TimelineTombstoned(
                    eventId = eventId,
                    occurredAt = occurredAt,
                    messageId = messageId,
                    revision = JsonFields.long(payload, "revision") ?: 0L,
                    conversationId = conversationIdOf(payload, body),
                )
            }

            "conversation.approval.requested" -> approvalRequested(eventId, occurredAt, payload, body)

            "conversation.approval.resolved" -> {
                val approvalId = JsonFields.string(payload, "approvalId")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { ApprovalId(it) }.getOrNull() }
                    ?: return null
                VerifiedConversationEvent.ApprovalResolved(
                    eventId = eventId,
                    occurredAt = occurredAt,
                    approvalId = approvalId,
                    outcome = ApprovalOutcome.of(
                        JsonFields.string(payload, "decision"),
                    ),
                    decidedAt = JsonFields.long(payload, "decidedAt")?.takeIf { it > 0L },
                    conversationId = conversationIdOf(payload, body),
                )
            }

            "conversation.snapshot.invalidated" -> VerifiedConversationEvent.SnapshotInvalidated(
                eventId = eventId,
                occurredAt = occurredAt,
                snapshotRevision = JsonFields.long(payload, "snapshotRevision") ?: 0L,
                conversationId = conversationIdOf(payload, body),
            )

            else -> null
        }
    }

    /**
     * The generation id the Gateway issued, if this frame carries one.
     *
     * Only a server-issued id may be used to cancel, so this reads the payload
     * and never falls back to a local counter.
     */
    fun generationIdOf(event: GatewayEvent): String? = generationIdOf(parse(event))

    private fun generationIdOf(frame: ParsedFrame): String? =
        JsonFields.string(frame.payload, "generationId")?.takeIf { it.isNotBlank() }

    /**
     * One SSE frame's JSON, read once for every reader that needs it.
     *
     * `payload` is the nested object when the Gateway sent one and the body
     * itself otherwise, because legacy frames carry their fields at the top
     * level. The two are the same reference in that case, which is what lets a
     * reader tell "no nested payload" from "a nested one missing the field".
     */
    private class ParsedFrame(val body: JsonValue.JObject?, val payload: JsonValue.JObject?)

    private fun parse(event: GatewayEvent): ParsedFrame {
        val body = runCatching { Json.parse(event.data) }
            .getOrNull()
            ?.let { JsonFields.obj(it) }
        return ParsedFrame(
            body = body,
            payload = JsonFields.obj(JsonFields.field(body, "payload")) ?: body,
        )
    }

    /**
     * One command-execution approval card (contract §7.2).
     *
     * The tiers come from the Gateway and nowhere else: an empty or unreadable
     * option list yields no card at all, because a card with no button would ask
     * the user to decide something the Gateway never offered.
     */
    private fun approvalRequested(
        eventId: String,
        occurredAt: Long,
        payload: JsonValue.JObject?,
        body: JsonValue.JObject?,
    ): VerifiedConversationEvent.ApprovalRequested? {
        val approvalId = JsonFields.string(payload, "approvalId")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { ApprovalId(it) }.getOrNull() }
            ?: return null
        val options = readApprovalOptions(payload)
        if (options.isEmpty()) return null
        val timeoutSeconds = JsonFields.long(payload, "timeoutSeconds")?.takeIf { it > 0L }
            ?: DEFAULT_APPROVAL_TIMEOUT_SECONDS
        val requestedAt = JsonFields.long(payload, "requestedAt")?.takeIf { it > 0L }
            ?: occurredAt.takeIf { it > 0L }
            ?: System.currentTimeMillis()
        // The Gateway's own deadline wins when it sends one: a phone counting to
        // its own arithmetic could grey the card out at a different moment than
        // the Gateway stops accepting an answer.
        val expiresAt = JsonFields.long(payload, "expiresAt")?.takeIf { it > 0L }
            ?: (requestedAt + timeoutSeconds * 1_000L)
        return VerifiedConversationEvent.ApprovalRequested(
            eventId = eventId,
            occurredAt = occurredAt,
            request = ApprovalRequest(
                approvalId = approvalId,
                conversationId = conversationIdOf(payload, body),
                command = JsonFields.string(payload, "command").orEmpty(),
                reason = JsonFields.string(payload, "reason").orEmpty(),
                // The contract fixes severity at `info | elevated | critical`: a
                // value outside that closed set is not a fourth tier this phone
                // may invent, so it becomes no severity line at all.
                severity = ApprovalSeverity.of(JsonFields.string(payload, "severity")),
                options = options,
                timeoutSeconds = timeoutSeconds,
                requestedAt = requestedAt,
                expiresAt = expiresAt,
            ),
            conversationId = conversationIdOf(payload, body),
        )
    }

    private fun readApprovalOptions(payload: JsonValue.JObject?): List<ApprovalOption> {
        val items = JsonFields.array(JsonFields.field(payload, "options"))?.items ?: return emptyList()
        return items.mapNotNull { raw ->
            val option = JsonFields.obj(raw) ?: return@mapNotNull null
            val choice = ApprovalChoice.of(
                JsonFields.string(option, "choice"),
            )
            // An unknown tier is dropped rather than coerced: drawing a button
            // the Gateway would refuse is worse than drawing one fewer.
            if (choice == ApprovalChoice.UNKNOWN) {
                return@mapNotNull null
            }
            ApprovalOption(
                choice = choice,
                label = JsonFields.string(option, "label")?.takeIf { it.isNotBlank() },
                style = ApprovalOptionStyle.of(
                    JsonFields.string(option, "style"),
                ),
            )
        }
    }

    private fun timelineUpsert(
        eventId: String,
        occurredAt: Long,
        payload: JsonValue.JObject?,
        state: String,
        body: JsonValue.JObject? = null,
    ): VerifiedConversationEvent.TimelineUpsert? {
        val messageId = JsonFields.string(payload, "messageId") ?: return null
        return VerifiedConversationEvent.TimelineUpsert(
            eventId = eventId,
            occurredAt = occurredAt,
            revision = JsonFields.long(payload, "revision") ?: 0L,
            message = TimelineMessage(
                id = messageId,
                sender = JsonFields.string(payload, "sender") ?: "assistant",
                parts = readParts(payload),
                timestamp = JsonFields.long(payload, "timestamp")?.takeIf { it > 0L }
                    ?: occurredAt.takeIf { it > 0L }
                    ?: System.currentTimeMillis(),
                state = state,
                conversationId = conversationIdOf(payload, body),
            ),
        )
    }

    private fun messageStatus(
        eventId: String,
        occurredAt: Long,
        payload: JsonValue.JObject?,
    ): VerifiedConversationEvent.MessageStatus? {
        val conversationId = conversationIdOfKey(payload, "conversationId") ?: return null
        val messageId = JsonFields.string(payload, "messageId")
            ?.takeIf { OPAQUE_ID.matches(it) }
            ?: return null
        val clientMessageId = JsonFields.string(payload, "clientMessageId")
            ?.let { runCatching { ClientMessageId(it) }.getOrNull() }
            ?: return null
        val status = when (JsonFields.string(payload, "status")) {
            "queued" -> AgentMessageStatus.QUEUED
            "delivered" -> AgentMessageStatus.DELIVERED
            "completed" -> AgentMessageStatus.COMPLETED
            "failed" -> AgentMessageStatus.FAILED
            else -> return null
        }
        val revision = JsonFields.long(payload, "revision")?.takeIf { it >= 0L } ?: return null
        val rawError = JsonFields.field(payload, "errorCode") ?: return null
        val errorCode = when (rawError) {
            JsonValue.JNull -> null
            is JsonValue.JString -> when (rawError.value) {
                "AGENT_UNAVAILABLE" -> AgentMessageErrorCode.AGENT_UNAVAILABLE
                "ATTACHMENT_READ_FAILED" -> AgentMessageErrorCode.ATTACHMENT_READ_FAILED
                "AGENT_MEDIA_REJECTED" -> AgentMessageErrorCode.AGENT_MEDIA_REJECTED
                "MODEL_REQUEST_REJECTED" -> AgentMessageErrorCode.MODEL_REQUEST_REJECTED
                else -> return null
            }
            else -> return null
        }
        if ((status == AgentMessageStatus.FAILED) != (errorCode != null)) return null
        return VerifiedConversationEvent.MessageStatus(
            eventId = eventId,
            occurredAt = occurredAt,
            conversationId = conversationId,
            messageId = messageId,
            clientMessageId = clientMessageId,
            status = status,
            revision = revision,
            errorCode = errorCode,
        )
    }

    private val CONVERSATION_ID_KEYS = listOf("conversationId", "conversation_id", "chat_id")
    private val OPAQUE_ID = Regex("[A-Za-z0-9._~-]{1,128}")

    /**
     * The closed command answer (contract §7.1).
     *
     * An absent or unrecognised value is `OUTCOME_UNKNOWN` rather than a guess:
     * the UI has to be able to tell "the Agent created something" from "we do
     * not know what happened", because only the first one permits a switch.
     */
    private fun commandOutcomeOf(value: String?): CommandOutcome =
        when (value?.trim()) {
            "created-conversation" -> CommandOutcome.CREATED_CONVERSATION
            "rejected" -> CommandOutcome.REJECTED
            "unsupported" -> CommandOutcome.UNSUPPORTED
            else -> CommandOutcome.OUTCOME_UNKNOWN
        }

    private fun conversationIdOfKey(payload: JsonValue.JObject?, key: String): ConversationId? {
        val value = JsonFields.string(payload, key)?.trim() ?: return null
        if (value.isBlank()) return null
        return runCatching { ConversationId(value) }.getOrNull()
    }

    /**
     * Conversation ids are optional on the legacy event payloads, but when a
     * Gateway sends one it is the only safe way for a shared account stream to
     * keep an event from another thread out of the active timeline.
     */
    private fun conversationIdOf(payload: JsonValue.JObject?, body: JsonValue.JObject? = null): ConversationId? {
        val targets = if (payload === body || body == null) listOfNotNull(payload) else listOfNotNull(payload, body)
        for (target in targets) {
            for (key in CONVERSATION_ID_KEYS) {
                val value = JsonFields.string(target, key)?.trim()
                if (!value.isNullOrBlank()) {
                    return runCatching { ConversationId(value) }.getOrNull()
                }
            }
        }
        return null
    }

    private fun readParts(payload: JsonValue.JObject?): List<MessagePart> {
        val items = JsonFields.array(JsonFields.field(payload, "parts"))?.items
        if (items.isNullOrEmpty()) {
            val text = JsonFields.string(payload, "text") ?: return emptyList()
            return listOf(MessagePart.Text(text))
        }
        return items.mapNotNull { raw ->
            val part = JsonFields.obj(raw) ?: return@mapNotNull null
            when (JsonFields.string(part, "type")) {
                "text" -> MessagePart.Text(
                    JsonFields.string(part, "text").orEmpty(),
                )
                "attachment" -> {
                    val draftId = JsonFields.string(part, "draftId") ?: JsonFields.string(part, "attachmentId")
                    val filename = JsonFields.string(part, "filename").orEmpty()
                    val mediaType = JsonFields.string(part, "mediaType").orEmpty()
                    draftId?.let {
                        MessagePart.Attachment(
                            draftId = AttachmentDraftId(it),
                            filename = filename,
                            mediaType = mediaType,
                        )
                    }
                }
                "command" -> JsonFields.string(part, "rawText")
                    ?.let { MessagePart.Command(it) }
                else -> null
            }
        }
    }

    private fun parseOccurredAt(value: String?): Long =
        value?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: System.currentTimeMillis()
}
