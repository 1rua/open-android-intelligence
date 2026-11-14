package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.util.Base64

/** 每个仓储实例只持有一个 Endpoint，原生消息 ID 无需通过字符串猜测后端。 */
internal class RemoteTimelineProjection(private val sessionId: String, private val nativeSessionId: String = RemoteLocalIds.decode(sessionId)) {
    val rows = linkedMapOf<String, TimelineMessage>()
    var generationId: String? = null
        private set
    var generationState = GenerationState.IDLE
        private set
    private var revision = 0L
    private var lastSeq: Long? = null
    private val attempts = mutableMapOf<String, StringBuilder>()
    private val rowSequences = mutableMapOf<String, Long>()
    private val acpText = StringBuilder()
    private var acpMessageId: String? = null
    private var liveSequence = 0L
    val approvalHandles = mutableMapOf<String, String>()

    fun beginTurn(localRequestId: String) { generationId = localRequestId; generationState = GenerationState.QUEUED }
    fun confirmGeneration(id: String) { generationId = id; generationState = GenerationState.RUNNING }
    fun markUnknown() { generationState = GenerationState.OUTCOME_UNKNOWN }

    fun accept(value: JsonValue, history: Boolean = false): List<VerifiedConversationEvent> {
        if (value.text("type") == "snapshot") {
            val events = value.requiredArray("records").flatMap { accept(it, history = true) }.toMutableList()
            lastSeq = value.number("cursor")
            value.at("assistantStream")?.let { snapshot ->
                snapshot.array("entries")?.forEach { entry -> events += assistantSnapshot(entry) }
                snapshot.at("message")?.let { message -> events += upsert(message, "assistant", true, null) }
            }
            return events
        }
        if (value.text("type") == "assistant-stream") return assistantStream(value.at("frame") ?: return emptyList())
        if (value.text("type") == "waterfall" && value.text("event") == "approval/request") {
            if (value.text("agentId") != nativeSessionId) return emptyList()
            return approval(value.requiredText("eventId"), value.at("request") ?: obj())
        }
        if (value.text("type") == "event" || value.at("event") != null) {
            val event = value.at("event") ?: value
            val seq = event.number("seq")
            if (!history && seq != null && lastSeq != null) {
                if (seq <= lastSeq!!) return emptyList()
                if (seq != lastSeq!! + 1) throw RemoteSessionException("REMOTE_HISTORY_GAP")
            }
            if (seq != null && !history) lastSeq = seq
            return durable(event)
        }
        value.text("method")?.let { return nativeFrame(it, value.at("params") ?: obj()) }
        return emptyList()
    }

    private fun durable(event: JsonValue): List<VerifiedConversationEvent> {
        val data = event.at("data") ?: obj()
        val id = "event:${event.number("seq") ?: ++liveSequence}"
        val time = event.number("time") ?: 0
        val turn = data.text("turnId") ?: data.number("turn")?.toString()
        when (event.text("type")) {
            "turn/start" -> { generationId = turn ?: id; generationState = GenerationState.RUNNING }
            "turn/end" -> {
                generationState = when (data.at("reason")?.text("kind")) {
                    "error" -> GenerationState.FAILED
                    "cancelled", "interrupted" -> GenerationState.CANCELLED
                    else -> GenerationState.COMPLETED
                }
                return finalizeStreaming(id, time)
            }
            "user/message" -> return upsert(data, "user", false, event.number("seq"), time)
            "assistant/message" -> return upsert(data.at("message") ?: data, "assistant", false, event.number("seq"), time)
            "tool/call" -> return execution(data, id, time, false)
            "tool/result" -> return execution(data, id, time, true)
            "approval/asked" -> return approval(data.text("requestHandle") ?: data.text("id") ?: id, data, time)
            "session/title", "title/changed" -> data.text("title")?.let { title ->
                return listOf(VerifiedConversationEvent.TitleUpdated(id, time, ConversationId(sessionId), title))
            }
        }
        return emptyList()
    }

    private fun assistantSnapshot(entry: JsonValue): List<VerifiedConversationEvent> {
        val attempt = entry.requiredText("attemptId")
        val text = entry.text("text") ?: entry.at("message")?.let(::contentText) ?: ""
        attempts[attempt] = StringBuilder(text)
        return upsert(obj("id" to "remote-stream:$attempt", "content" to listOf(obj("type" to "text", "text" to text))),
            "assistant", true, null)
    }

    private fun assistantStream(frame: JsonValue): List<VerifiedConversationEvent> {
        val attempt = frame.text("attemptId") ?: return emptyList()
        when (frame.text("type")) {
            "start" -> {
                attempts[attempt] = StringBuilder()
                generationId = frame.number("turn")?.toString() ?: generationId
                generationState = GenerationState.RUNNING
            }
            "chunk" -> {
                val chunk = frame.at("chunk") ?: return emptyList()
                if (chunk.text("type") != "text-delta") return emptyList()
                val text = attempts[attempt] ?: throw RemoteSessionException("REMOTE_ASSISTANT_STREAM_GAP")
                text.append(chunk.text("text") ?: "")
                return upsert(obj("id" to "remote-stream:$attempt", "content" to listOf(obj("type" to "text", "text" to text.toString()))), "assistant", true, null)
            }
            "end" -> {
                attempts.remove(attempt)
                val messageId = "remote-stream:$attempt"
                rows.remove(messageId)
                return listOf(VerifiedConversationEvent.TimelineTombstoned("stream-end:${++revision}", 0, messageId, revision, ConversationId(sessionId)))
            }
        }
        return emptyList()
    }

    private fun nativeFrame(method: String, params: JsonValue): List<VerifiedConversationEvent> {
        if (params.text("threadId")?.let { it != nativeSessionId } == true || params.text("sessionId")?.let { it != nativeSessionId } == true) return emptyList()
        when (method) {
            "turn/started" -> { generationId = params.at("turn")?.text("id") ?: params.text("turnId"); generationState = GenerationState.RUNNING }
            "turn/completed" -> {
                generationState = when (params.at("turn")?.text("status")) {
                    "failed" -> GenerationState.FAILED
                    "interrupted", "cancelled" -> GenerationState.CANCELLED
                    else -> GenerationState.COMPLETED
                }
                return finalizeStreaming("turn-end:${++liveSequence}", 0)
            }
            "item/agentMessage/delta" -> {
                val id = params.requiredText("itemId")
                val old = rows[id]?.parts?.filterIsInstance<MessagePart.Text>()?.joinToString("") { it.value }.orEmpty()
                return upsert(obj("id" to id, "content" to listOf(obj("type" to "text", "text" to old + (params.text("delta") ?: "")))), "assistant", true, null)
            }
            "item/started", "item/completed" -> {
                val item = params.at("item") ?: return emptyList()
                return when (item.text("type")) {
                    "agentMessage" -> upsert(obj("id" to item.requiredText("id"), "content" to listOf(obj("type" to "text", "text" to (item.text("text") ?: "")))), "assistant", method == "item/started", null)
                    "userMessage" -> upsert(item, "user", false, null)
                    else -> execution(item, item.text("id") ?: "tool:${++liveSequence}", 0, method == "item/completed")
                }
            }
            "item/commandExecution/requestApproval", "item/fileChange/requestApproval", "session/request_permission", "cursor/ask_question", "cursor/create_plan" ->
                return approval(params.requiredText("requestHandle"), params)
            "session/update" -> {
                val update = params.at("update") ?: params
                when (update.text("sessionUpdate") ?: update.text("type")) {
                    "agent_message_chunk", "agent_message" -> {
                        val content = update.at("content") ?: return emptyList()
                        if (content.text("type") != "text") return emptyList()
                        if (acpMessageId == null) { acpMessageId = "remote-acp-stream:${++liveSequence}"; acpText.setLength(0) }
                        acpText.append(content.text("text") ?: "")
                        generationState = GenerationState.RUNNING
                        return upsert(obj("id" to acpMessageId, "content" to listOf(obj("type" to "text", "text" to acpText.toString()))), "assistant", true, null)
                    }
                    "user_message_chunk" -> return upsert(obj("id" to "remote-acp-user:${++liveSequence}", "content" to listOf(update.at("content") ?: obj())), "user", false, null)
                    "tool_call", "tool_call_update" -> return execution(update, update.text("toolCallId") ?: "tool:${++liveSequence}", 0,
                        update.text("status") in setOf("completed", "failed"))
                    "session_info_update" -> update.text("title")?.let { return listOf(VerifiedConversationEvent.TitleUpdated("title:${++revision}", 0, ConversationId(sessionId), it)) }
                    "prompt_completed", "prompt_failed" -> {
                        if (acpText.isEmpty()) update.array("catchUp")?.forEach { accept(it) }
                        generationState = when {
                            update.text("sessionUpdate") == "prompt_failed" -> GenerationState.FAILED
                            update.text("stopReason") == "cancelled" -> GenerationState.CANCELLED
                            update.text("stopReason") == "end_turn" -> GenerationState.COMPLETED
                            else -> GenerationState.OUTCOME_UNKNOWN
                        }
                        return finalizeStreaming("turn-end:${++liveSequence}", 0)
                    }
                }
            }
        }
        return emptyList()
    }

    fun finishTurn(): List<VerifiedConversationEvent> {
        generationState = GenerationState.IDLE
        return finalizeStreaming("prompt-end:${++liveSequence}", 0)
    }

    private fun finalizeStreaming(eventId: String, time: Long): List<VerifiedConversationEvent> {
        acpMessageId = null
        return rows.values.filter { it.state == "STREAMING" }.map { message ->
            val confirmed = message.copy(state = "CONFIRMED", contentRevision = ++revision)
            rows[message.id] = confirmed
            VerifiedConversationEvent.TimelineUpsert("$eventId:${message.id}", time, revision, confirmed)
        }
    }

    private fun upsert(value: JsonValue, role: String, streaming: Boolean, seq: Long?, time: Long = 0): List<VerifiedConversationEvent> {
        val message = value.at("message") ?: value
        val id = message.text("id") ?: seq?.let { "event-message:$it" } ?: throw RemoteSessionException("REMOTE_MESSAGE_ID_MISSING")
        if (seq != null && rowSequences[id]?.let { it > seq } == true) return emptyList()
        if (seq != null) rowSequences[id] = seq
        val parts = message.array("content")?.mapNotNull { block -> when (block.text("type")) {
            "text" -> MessagePart.Text(block.text("text") ?: "")
            "image", "file" -> {
                val ref = block.text("id") ?: block.text("path") ?: block.text("url") ?: block.at("source")?.text("url")
                ref?.let { MessagePart.Attachment(AttachmentDraftId(RemoteLocalIds.encode(it)), block.text("name") ?: "", block.text("mimeType") ?: "") }
            }
            else -> null
        } }.orEmpty().ifEmpty { message.text("text")?.let { listOf(MessagePart.Text(it)) }.orEmpty() }
        val clientId = message.at("source")?.text("rpcId")?.let(::ClientMessageId)
        val row = TimelineMessage(id, role, parts, time, if (streaming) "STREAMING" else "CONFIRMED", ConversationId(sessionId),
            clientMessageId = clientId, contentRevision = ++revision, generationId = generationId,
            localPlaceholder = id.startsWith("remote-stream:") || id.startsWith("remote-acp-stream:"))
        rows[id] = row
        return listOf(VerifiedConversationEvent.TimelineUpsert("message:$id:$revision", time, revision, row,
            if (streaming) StreamPresentationOrigin.LIVE else StreamPresentationOrigin.SNAPSHOT))
    }

    private fun contentText(value: JsonValue): String = value.array("content").orEmpty().mapNotNull { if (it.text("type") == "text") it.text("text") else null }.joinToString("")

    private fun execution(value: JsonValue, eventId: String, time: Long, completed: Boolean): List<VerifiedConversationEvent> {
        val id = value.text("callId") ?: value.text("toolCallId") ?: value.text("id") ?: eventId
        val step = NativeExecutionStep(stepId = id, eventId = eventId, toolName = value.text("name") ?: value.text("title") ?: value.text("type") ?: "tool",
            arguments = value.text("arguments") ?: value.text("command") ?: "",
            status = if (!completed) NativeExecutionStatus.RUNNING else if (value.flag("isError") == true || value.text("status") == "failed") NativeExecutionStatus.FAILED else NativeExecutionStatus.COMPLETED,
            output = value.text("aggregatedOutput"), revision = ++revision, occurredAt = time)
        return listOf(VerifiedConversationEvent.ExecutionStepsUpdated(eventId, time, ConversationId(sessionId), generationId ?: "remote-execution:$sessionId", ++revision, listOf(step)))
    }

    private fun approval(handle: String, value: JsonValue, time: Long = 0): List<VerifiedConversationEvent> {
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(handle.toByteArray())
        approvalHandles[id] = handle
        generationState = GenerationState.RUNNING
        val request = ApprovalRequest(ApprovalId(id), ConversationId(sessionId), value.text("command") ?: value.text("toolName")
            ?: value.at("toolCall")?.text("title") ?: value.text("title") ?: Json.canonical(value), value.text("reason") ?: "",
            options = listOf(ApprovalOption(ApprovalChoice.ONCE), ApprovalOption(ApprovalChoice.DENY)), timeoutSeconds = 0,
            requestedAt = time, expiresAt = Long.MAX_VALUE)
        return listOf(VerifiedConversationEvent.ApprovalRequested("approval:$id", time, request, ConversationId(sessionId)))
    }
}
