package com.openandroidintelligence.conversation.data

import com.openandroidintelligence.conversation.model.ConversationId
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

    fun decode(event: GatewayEvent): VerifiedConversationEvent? {
        val name = event.event ?: return null
        val body = runCatching { Json.parse(event.data) }
            .getOrNull()
            ?.let { JsonFields.obj(it) }
        val payload = JsonFields.obj(JsonFields.field(body, "payload")) ?: body
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
                    ?.let { runCatching { com.openandroidintelligence.conversation.model.ApprovalId(it) }.getOrNull() }
                    ?: return null
                VerifiedConversationEvent.ApprovalResolved(
                    eventId = eventId,
                    occurredAt = occurredAt,
                    approvalId = approvalId,
                    outcome = com.openandroidintelligence.conversation.model.ApprovalOutcome.of(
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
    fun generationIdOf(event: GatewayEvent): String? {
        val body = runCatching { Json.parse(event.data) }
            .getOrNull()
            ?.let { JsonFields.obj(it) }
            ?: return null
        val payload = JsonFields.obj(JsonFields.field(body, "payload")) ?: body
        return JsonFields.string(payload, "generationId")?.takeIf { it.isNotBlank() }
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
            ?.let { runCatching { com.openandroidintelligence.conversation.model.ApprovalId(it) }.getOrNull() }
            ?: return null
        val options = readApprovalOptions(payload)
        if (options.isEmpty()) return null
        val timeoutSeconds = JsonFields.long(payload, "timeoutSeconds")?.takeIf { it > 0L }
            ?: DEFAULT_APPROVAL_TIMEOUT_SECONDS
        val requestedAt = JsonFields.long(payload, "requestedAt")?.takeIf { it > 0L }
            ?: occurredAt.takeIf { it > 0L }
            ?: System.currentTimeMillis()
        return VerifiedConversationEvent.ApprovalRequested(
            eventId = eventId,
            occurredAt = occurredAt,
            request = com.openandroidintelligence.conversation.model.ApprovalRequest(
                approvalId = approvalId,
                conversationId = conversationIdOf(payload, body),
                command = JsonFields.string(payload, "command").orEmpty(),
                reason = JsonFields.string(payload, "reason").orEmpty(),
                severity = JsonFields.string(payload, "severity")?.takeIf { it.isNotBlank() },
                options = options,
                timeoutSeconds = timeoutSeconds,
                requestedAt = requestedAt,
            ),
            conversationId = conversationIdOf(payload, body),
        )
    }

    private fun readApprovalOptions(payload: JsonValue.JObject?): List<com.openandroidintelligence.conversation.model.ApprovalOption> {
        val items = JsonFields.array(JsonFields.field(payload, "options"))?.items ?: return emptyList()
        return items.mapNotNull { raw ->
            val option = JsonFields.obj(raw) ?: return@mapNotNull null
            val choice = com.openandroidintelligence.conversation.model.ApprovalChoice.of(
                JsonFields.string(option, "choice"),
            )
            // An unknown tier is dropped rather than coerced: drawing a button
            // the Gateway would refuse is worse than drawing one fewer.
            if (choice == com.openandroidintelligence.conversation.model.ApprovalChoice.UNKNOWN) {
                return@mapNotNull null
            }
            com.openandroidintelligence.conversation.model.ApprovalOption(
                choice = choice,
                label = JsonFields.string(option, "label")?.takeIf { it.isNotBlank() },
                style = com.openandroidintelligence.conversation.model.ApprovalOptionStyle.of(
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
            message = com.openandroidintelligence.conversation.ports.TimelineMessage(
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

    private val CONVERSATION_ID_KEYS = listOf("conversationId", "conversation_id", "chat_id")

    /**
     * The closed command answer (contract §7.1).
     *
     * An absent or unrecognised value is `OUTCOME_UNKNOWN` rather than a guess:
     * the UI has to be able to tell "the Agent created something" from "we do
     * not know what happened", because only the first one permits a switch.
     */
    private fun commandOutcomeOf(value: String?): com.openandroidintelligence.conversation.ports.CommandOutcome =
        when (value?.trim()) {
            "created-conversation" -> com.openandroidintelligence.conversation.ports.CommandOutcome.CREATED_CONVERSATION
            "rejected" -> com.openandroidintelligence.conversation.ports.CommandOutcome.REJECTED
            "unsupported" -> com.openandroidintelligence.conversation.ports.CommandOutcome.UNSUPPORTED
            else -> com.openandroidintelligence.conversation.ports.CommandOutcome.OUTCOME_UNKNOWN
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

    private fun readParts(payload: JsonValue.JObject?): List<com.openandroidintelligence.conversation.model.MessagePart> {
        val items = JsonFields.array(JsonFields.field(payload, "parts"))?.items
        if (items.isNullOrEmpty()) {
            val text = JsonFields.string(payload, "text") ?: return emptyList()
            return listOf(com.openandroidintelligence.conversation.model.MessagePart.Text(text))
        }
        return items.mapNotNull { raw ->
            val part = JsonFields.obj(raw) ?: return@mapNotNull null
            when (JsonFields.string(part, "type")) {
                "text" -> com.openandroidintelligence.conversation.model.MessagePart.Text(
                    JsonFields.string(part, "text").orEmpty(),
                )
                "attachment" -> {
                    val draftId = JsonFields.string(part, "draftId") ?: JsonFields.string(part, "attachmentId")
                    val filename = JsonFields.string(part, "filename").orEmpty()
                    val mediaType = JsonFields.string(part, "mediaType").orEmpty()
                    draftId?.let {
                        com.openandroidintelligence.conversation.model.MessagePart.Attachment(
                            draftId = com.openandroidintelligence.conversation.model.AttachmentDraftId(it),
                            filename = filename,
                            mediaType = mediaType,
                        )
                    }
                }
                "command" -> JsonFields.string(part, "rawText")
                    ?.let { com.openandroidintelligence.conversation.model.MessagePart.Command(it) }
                else -> null
            }
        }
    }

    private fun parseOccurredAt(value: String?): Long =
        value?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: System.currentTimeMillis()
}
