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
                conversationId = conversationIdOf(payload),
            )

            "conversation.message.delta" -> timelineUpsert(eventId, occurredAt, payload, "STREAMING")

            "conversation.message.completed" -> timelineUpsert(eventId, occurredAt, payload, "CONFIRMED")

            "conversation.generation.cancelled" -> VerifiedConversationEvent.GenerationCancelled(
                eventId = eventId,
                occurredAt = occurredAt,
                generationId = JsonFields.string(payload, "generationId").orEmpty(),
                conversationId = conversationIdOf(payload),
            )

            "conversation.command.result" -> VerifiedConversationEvent.CommandResult(
                eventId = eventId,
                occurredAt = occurredAt,
                command = JsonFields.string(payload, "command").orEmpty(),
                conversationId = JsonFields.string(payload, "conversationId")?.let { ConversationId(it) },
            )

            "conversation.title.updated" -> {
                val conversationId = JsonFields.string(payload, "conversationId") ?: return null
                VerifiedConversationEvent.TitleUpdated(
                    eventId = eventId,
                    occurredAt = occurredAt,
                    conversationId = ConversationId(conversationId),
                    newTitle = JsonFields.string(payload, "title")
                        ?: JsonFields.string(payload, "newTitle").orEmpty(),
                )
            }

            "conversation.timeline.upsert" -> timelineUpsert(
                eventId = eventId,
                occurredAt = occurredAt,
                payload = payload,
                state = JsonFields.string(payload, "state") ?: "CONFIRMED",
            )

            "conversation.timeline.tombstoned" -> {
                val messageId = JsonFields.string(payload, "messageId") ?: return null
                VerifiedConversationEvent.TimelineTombstoned(
                    eventId = eventId,
                    occurredAt = occurredAt,
                    messageId = messageId,
                    revision = JsonFields.long(payload, "revision") ?: 0L,
                    conversationId = conversationIdOf(payload),
                )
            }

            "conversation.snapshot.invalidated" -> VerifiedConversationEvent.SnapshotInvalidated(
                eventId = eventId,
                occurredAt = occurredAt,
                snapshotRevision = JsonFields.long(payload, "snapshotRevision") ?: 0L,
                conversationId = conversationIdOf(payload),
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

    private fun timelineUpsert(
        eventId: String,
        occurredAt: Long,
        payload: JsonValue.JObject?,
        state: String,
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
                    timestamp = JsonFields.long(payload, "timestamp") ?: occurredAt,
                    state = state,
                    conversationId = conversationIdOf(payload),
                ),
            )
    }

    /**
     * Conversation ids are optional on the legacy event payloads, but when a
     * Gateway sends one it is the only safe way for a shared account stream to
     * keep an event from another thread out of the active timeline.
     */
    private fun conversationIdOf(payload: JsonValue.JObject?): ConversationId? =
        JsonFields.string(payload, "conversationId")
            ?.takeIf { it.isNotBlank() }
            ?.let { value -> runCatching { ConversationId(value) }.getOrNull() }

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
