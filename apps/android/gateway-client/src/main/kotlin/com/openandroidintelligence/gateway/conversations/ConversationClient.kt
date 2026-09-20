package com.openandroidintelligence.gateway.conversations

import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayResponse
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.SignedGatewayRequest
import com.openandroidintelligence.gateway.http.requireData
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow

data class NormalizedRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

data class DisplayMetrics(
    val widthPixels: Int,
    val heightPixels: Int,
    val densityDpi: Int,
)

data class VisualContext(
    val bounds: NormalizedRect,
    val displayMetrics: DisplayMetrics,
    val uiHierarchySummary: String? = null,
)

sealed class MessagePart {
    data class Text(val text: String) : MessagePart()
    data class AttachmentRef(
        val attachmentId: String,
        val filename: String = "",
        val mediaType: String = "",
        val visualContext: VisualContext? = null,
    ) : MessagePart()
}

/** The authoritative acceptance of one chat-v1 message. */
data class MessageAcceptance(val messageId: String, val conversationId: String)

data class ConversationThread(
    val conversationId: String,
    val title: String?,
    val lastMessageAt: String?,
)

/** `POST /conversations` result. */
data class ConversationDetail(
    val conversationId: String,
    val title: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val snapshotRevision: Long?,
)

/** One message as the Gateway mirrors it. */
data class GatewayTimelineMessage(
    val messageId: String,
    val sender: String,
    val parts: List<MessagePart>,
    val timestamp: Long?,
    val state: String,
)

/** One page of `GET /conversations/{id}/messages`. */
data class TimelinePage(
    val messages: List<GatewayTimelineMessage>,
    val nextCursor: String?,
    val snapshotRevision: Long?,
)

/** `POST /conversations/{id}/message-batches` request. */
data class MessageBatchRequest(
    val clientBatchId: String,
    val clientConversationId: String,
    val joinMode: String = NEWLINE_V1,
    val members: List<BatchMember>,
) {
    data class BatchMember(
        val clientMessageId: String,
        val text: String,
        val attachmentIds: List<String> = emptyList(),
    )

    companion object {
        /** Adjacent members are joined with exactly one U+000A; nothing is trimmed. */
        const val NEWLINE_V1 = "newline-v1"
    }
}

/** `POST /conversations/{id}/message-batches` result. */
data class BatchAcceptance(
    val batchId: String,
    val status: String,
    val memberIds: Map<String, String>,
    val generationId: String?,
)

/**
 * Conversation listing, creation, timeline reads, batch submission and
 * generation cancellation.
 *
 * A conversation belongs to exactly one account and one Gateway; the client
 * never supplies an account id in the request body, so a cross-account
 * reference cannot be constructed here even by mistake.
 */
class ConversationClient(private val http: GatewayHttpClient) {

    /** The unfiltered Gateway event stream, framed and cursor-tracked. */
    fun rawEvents(): Flow<com.openandroidintelligence.gateway.events.GatewayEvent> = http.events()

    /** Emits the current threads, then a fresh list whenever the server says one changed. */
    fun threads(accountId: String): Flow<List<ConversationThread>> = flow {
        emit(readThreads())
        http.events()
            .filter { it.event == "conversation.updated" || it.event == "conversation.title.updated" }
            .collect { emit(readThreads()) }
    }

    suspend fun readThreads(cursor: String? = null, limit: Int? = null): List<ConversationThread> {
        val response = execute(
            method = "GET",
            target = "/open-android-intelligence/v2/conversations" + query(
                "cursor" to cursor,
                "limit" to limit?.toString(),
            ),
        )
        if (response.status != 200) {
            throw IllegalStateException("CONVERSATIONS_FAILED:${response.status}")
        }
        val body = response.requireData("CONVERSATIONS_FAILED")
        check(JsonFields.array(JsonFields.field(body, "conversations")) != null) { "CONVERSATIONS_FAILED:missing-list" }
        return JsonFields.objects(body, "conversations").mapNotNull { thread ->
            val id = JsonFields.string(thread, "conversationId") ?: return@mapNotNull null
            ConversationThread(
                conversationId = id,
                title = JsonFields.string(thread, "title"),
                lastMessageAt = JsonFields.string(thread, "lastMessageAt"),
            )
        }
    }

    suspend fun createConversation(
        clientConversationId: String,
        title: String? = null,
    ): ConversationDetail {
        val payload = mutableMapOf<String, Any?>("clientConversationId" to clientConversationId)
        if (title != null) payload["title"] = title
        val response = execute(
            method = "POST",
            target = "/open-android-intelligence/v2/conversations",
            body = Json.canonical(Json.of(payload)).toByteArray(Charsets.UTF_8),
        )
        if (response.status !in 200..299) {
            throw IllegalStateException("CONVERSATION_CREATE_FAILED:${response.status}")
        }
        val body = JsonFields.obj(JsonFields.field(response.requireData("CONVERSATION_CREATE_FAILED"), "conversation"))
            ?: throw IllegalStateException("CONVERSATION_CREATE_FAILED:missing-conversation")
        return ConversationDetail(
            conversationId = JsonFields.string(body, "conversationId")
                ?: throw IllegalStateException("CONVERSATION_CREATE_FAILED:missing-id"),
            title = JsonFields.string(body, "title"),
            createdAt = JsonFields.string(body, "createdAt"),
            updatedAt = JsonFields.string(body, "updatedAt"),
            snapshotRevision = JsonFields.long(body, "snapshotRevision"),
        )
    }

    suspend fun readConversation(conversationId: String): ConversationDetail {
        val response = execute(
            method = "GET",
            target = "/open-android-intelligence/v2/conversations/$conversationId",
        )
        if (response.status != 200) {
            throw IllegalStateException("CONVERSATION_READ_FAILED:${response.status}")
        }
        val body = JsonFields.obj(JsonFields.field(response.requireData("CONVERSATION_READ_FAILED"), "conversation"))
            ?: throw IllegalStateException("CONVERSATION_READ_FAILED:missing-conversation")
        return ConversationDetail(
            conversationId = JsonFields.string(body, "conversationId") ?: conversationId,
            title = JsonFields.string(body, "title"),
            createdAt = JsonFields.string(body, "createdAt"),
            updatedAt = JsonFields.string(body, "updatedAt"),
            snapshotRevision = JsonFields.long(body, "snapshotRevision"),
        )
    }

    suspend fun updateConversationTitle(conversationId: String, title: String): Boolean {
        val payload = mapOf("title" to title)
        val response = execute(
            method = "PATCH",
            target = "/open-android-intelligence/v2/conversations/$conversationId",
            body = Json.canonical(Json.of(payload)).toByteArray(Charsets.UTF_8),
        )
        return response.status in 200..299
    }

    /**
     * Reads one page of the timeline, oldest page first.
     *
     * A missing or unparseable page is an error, never an empty timeline: the
     * two mean different things and the UI must be able to tell them apart.
     */
    suspend fun readTimeline(
        conversationId: String,
        cursor: String? = null,
        limit: Int? = null,
    ): TimelinePage {
        val response = execute(
            method = "GET",
            target = "/open-android-intelligence/v2/conversations/$conversationId/messages" + query(
                "cursor" to cursor,
                "limit" to limit?.toString(),
            ),
        )
        if (response.status != 200) {
            throw IllegalStateException("TIMELINE_FAILED:${response.status}")
        }
        val body = parsed(response) ?: throw IllegalStateException("TIMELINE_FAILED:malformed")
        return TimelinePage(
            messages = JsonFields.objects(body, "messages").mapNotNull { message ->
                val messageId = JsonFields.string(message, "messageId")
                    ?: JsonFields.string(message, "id")
                    ?: return@mapNotNull null
                val rawTimestamp = JsonFields.long(message, "timestamp")
                val timestamp = if (rawTimestamp != null && rawTimestamp > 0L) {
                    rawTimestamp
                } else {
                    parseIsoMillis(JsonFields.string(message, "createdAt"))
                        ?: parseIsoMillis(JsonFields.string(message, "occurredAt"))
                        ?: 0L
                }
                GatewayTimelineMessage(
                    messageId = messageId,
                    sender = JsonFields.string(message, "sender")
                        ?: JsonFields.string(message, "role")
                        ?: "assistant",
                    parts = readParts(message),
                    timestamp = timestamp,
                    state = JsonFields.string(message, "state") ?: "CONFIRMED",
                )
            },
            nextCursor = JsonFields.string(body, "nextCursor"),
            snapshotRevision = JsonFields.long(body, "snapshotRevision"),
        )
    }

    /** Recovers the authoritative result of one send when the response was lost. */
    suspend fun queryMessage(conversationId: String, clientMessageId: String): GatewayTimelineMessage? {
        val response = execute(
            method = "GET",
            target = "/open-android-intelligence/v2/conversations/$conversationId/messages" + query(
                "clientMessageId" to clientMessageId,
            ),
        )
        if (response.status != 200) {
            throw IllegalStateException("MESSAGE_QUERY_FAILED:${response.status}")
        }
        val body = parsed(response) ?: return null
        val raw = JsonFields.objects(body, "messages").firstOrNull()
            ?: JsonFields.obj(JsonFields.field(body, "message"))
            ?: return null
        val messageId = JsonFields.string(raw, "messageId") ?: return null
        val rawTimestamp = JsonFields.long(raw, "timestamp")
        val timestamp = if (rawTimestamp != null && rawTimestamp > 0L) {
            rawTimestamp
        } else {
            parseIsoMillis(JsonFields.string(raw, "createdAt"))
                ?: parseIsoMillis(JsonFields.string(raw, "occurredAt"))
                ?: 0L
        }
        return GatewayTimelineMessage(
            messageId = messageId,
            sender = JsonFields.string(raw, "sender") ?: "assistant",
            parts = readParts(raw),
            timestamp = timestamp,
            state = JsonFields.string(raw, "state") ?: "CONFIRMED",
        )
    }

    /** Gateway Protocol v2 §7: one text plus ordered verified attachment references. */
    suspend fun sendMessage(
        conversationId: String,
        clientMessageId: String,
        text: String,
        attachmentIds: List<String> = emptyList(),
    ): MessageAcceptance {
        val payload = mapOf(
            "clientMessageId" to clientMessageId,
            "text" to text,
            "attachments" to attachmentIds.map { mapOf("attachmentId" to it) },
        )
        val response = execute(
            method = "POST",
            target = "/open-android-intelligence/v2/conversations/$conversationId/messages",
            body = Json.canonical(Json.of(payload)).toByteArray(Charsets.UTF_8),
        )
        val data = response.requireData("SEND_MESSAGE_FAILED")
        val message = JsonFields.obj(JsonFields.field(data, "message"))
        check(JsonFields.string(message, "status") == "accepted" &&
            JsonFields.string(message, "conversationId") == conversationId) { "SEND_MESSAGE_FAILED:invalid-acceptance" }
        return MessageAcceptance(
            messageId = JsonFields.string(message, "messageId")
                ?: throw IllegalStateException("SEND_MESSAGE_FAILED:missing-message-id"),
            conversationId = conversationId,
        )
    }

    /** One ordered aggregate input for the Agent; members keep their own identity. */
    suspend fun submitBatch(
        conversationId: String,
        batch: MessageBatchRequest,
    ): BatchAcceptance {
        val payload = mapOf(
            "clientBatchId" to batch.clientBatchId,
            "clientConversationId" to batch.clientConversationId,
            "joinMode" to batch.joinMode,
            "members" to batch.members.map { member ->
                mutableMapOf<String, Any?>(
                    "clientMessageId" to member.clientMessageId,
                    "text" to member.text,
                ).apply {
                    if (member.attachmentIds.isNotEmpty()) {
                        put("attachments", member.attachmentIds.map { mapOf("attachmentId" to it) })
                    }
                }
            },
        )
        val response = execute(
            method = "POST",
            target = "/open-android-intelligence/v2/conversations/$conversationId/message-batches",
            body = Json.canonical(Json.of(payload)).toByteArray(Charsets.UTF_8),
        )
        if (response.status !in 200..299) {
            throw IllegalStateException("SUBMIT_BATCH_FAILED:${response.status}")
        }
        val body = parsed(response) ?: throw IllegalStateException("SUBMIT_BATCH_FAILED:malformed")
        val memberIds = JsonFields.objects(body, "members").mapNotNull { member ->
            val client = JsonFields.string(member, "clientMessageId") ?: return@mapNotNull null
            val server = JsonFields.string(member, "messageId") ?: return@mapNotNull null
            client to server
        }.toMap()
        // The mapping is the only authoritative link between a member the phone
        // sent and the id the Gateway issued for it. An incomplete answer would
        // leave the caller keying the mirror by its own local id — exactly the
        // duplicate this mapping exists to prevent — so it is refused instead of
        // being degraded into a silent second copy on screen.
        check(memberIds.size == batch.members.size) { "SUBMIT_BATCH_FAILED:missing-member-ids" }
        return BatchAcceptance(
            batchId = JsonFields.string(body, "batchId")
                ?: throw IllegalStateException("SUBMIT_BATCH_FAILED:missing-batch-id"),
            status = JsonFields.string(body, "status") ?: "accepted",
            memberIds = memberIds,
            generationId = JsonFields.string(body, "generationId"),
        )
    }

    /**
     * Cancellation is a distinct endpoint with its own request id, and the
     * outcome stays a closed set: the UI must not claim "stopped" until the
     * server says which of the terminal outcomes actually happened.
     */
    suspend fun cancelGeneration(
        conversationId: String,
        generationId: String,
        requestId: String,
    ): String {
        val response = execute(
            method = "POST",
            target = "/open-android-intelligence/v2/conversations/$conversationId/generations/$generationId/cancel",
            body = Json.canonical(Json.of(mapOf("requestId" to requestId))).toByteArray(Charsets.UTF_8),
        )
        when (response.status) {
            404 -> return "ALREADY_COMPLETED"
            in 200..299 -> {
                val body = parsed(response) ?: return "CANCELLED"
                return JsonFields.string(body, "outcome") ?: "CANCELLED"
            }
            else -> throw IllegalStateException("CANCEL_GENERATION_FAILED:${response.status}")
        }
    }

    private suspend fun execute(
        method: String,
        target: String,
        body: ByteArray = ByteArray(0),
    ): GatewayResponse = http.execute(
        SignedGatewayRequest(
            method = method,
            target = target,
            headers = if (body.isEmpty()) {
                listOf(RawHeader("Accept", "application/json"))
            } else {
                listOf(
                    RawHeader("Content-Type", "application/json"),
                    RawHeader("Accept", "application/json"),
                )
            },
            body = body,
        ),
    )

    private fun parsed(response: GatewayResponse): JsonValue.JObject? =
        response.requireData("CONVERSATION_RESPONSE_FAILED")

    private fun readParts(message: JsonValue.JObject): List<MessagePart> {
        val parts = JsonFields.array(JsonFields.field(message, "parts"))?.items
        if (parts.isNullOrEmpty()) {
            val text = JsonFields.string(message, "text")
            return if (text == null) emptyList() else listOf(MessagePart.Text(text))
        }
        return parts.mapNotNull { raw ->
            val part = JsonFields.obj(raw) ?: return@mapNotNull null
            when (JsonFields.string(part, "type")) {
                "text" -> MessagePart.Text(JsonFields.string(part, "text").orEmpty())
                "attachment_ref", "attachment" -> {
                    val id = JsonFields.string(part, "attachmentId") ?: JsonFields.string(part, "id")
                    val filename = JsonFields.string(part, "filename").orEmpty()
                    val mediaType = JsonFields.string(part, "mediaType").orEmpty()
                    id?.let {
                        MessagePart.AttachmentRef(
                            attachmentId = it,
                            filename = filename,
                            mediaType = mediaType,
                        )
                    }
                }
                else -> null
            }
        }
    }

    internal fun parseIsoMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        // 1. Standard ISO-8601 parsing (e.g. 2026-09-20T16:00:00.000Z or with offset +08:00)
        runCatching { java.time.Instant.parse(trimmed).toEpochMilli() }.getOrNull()?.let { return it }

        // 2. Space-separated normalized to 'T'
        val normalized = trimmed.replace(' ', 'T')
        runCatching { java.time.Instant.parse(normalized).toEpochMilli() }.getOrNull()?.let { return it }

        // 3. OffsetDateTime / ZonedDateTime
        runCatching { java.time.OffsetDateTime.parse(normalized).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        runCatching {
            java.time.ZonedDateTime.parse(normalized, java.time.format.DateTimeFormatter.ISO_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }

        // 4. LocalDateTime without timezone -> assume UTC
        runCatching {
            val local = java.time.LocalDateTime.parse(
                if (normalized.endsWith("Z")) normalized.dropLast(1) else normalized,
                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            )
            local.toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()?.let { return it }

        // 5. String of digits (epoch timestamp)
        trimmed.toLongOrNull()?.takeIf { it > 0L }?.let { return it }

        return null
    }

    /**
     * Builds a query string already in canonical order.
     *
     * [CanonicalTarget.canonicalize] refuses to rewrite anything, so the target
     * handed to the signer must already have its parameters sorted by name.
     */
    private fun query(vararg pairs: Pair<String, String?>): String {
        val present = pairs.mapNotNull { (name, value) -> value?.let { name to it } }
        if (present.isEmpty()) return ""
        val sorted = present.sortedWith { left, right ->
            val byName = left.first.compareTo(right.first)
            if (byName != 0) byName else left.second.compareTo(right.second)
        }
        return "?" + sorted.joinToString("&") { (name, value) -> "$name=$value" }
    }
}
