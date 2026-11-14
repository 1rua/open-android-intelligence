package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.util.Base64

/** 每个仓储实例只持有一个 Endpoint，原生消息 ID 无需通过字符串猜测后端。 */
internal class RemoteTimelineProjection(private val sessionId: String, private val nativeSessionId: String = RemoteLocalIds.decode(sessionId),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val rows = linkedMapOf<String, TimelineMessage>()
    var generationId: String? = null
        private set
    var generationState = GenerationState.IDLE
        private set
    var clientRequestId: String? = null
        private set
    var terminalGeneration: Pair<String, GenerationState>? = null
        private set
    private var revision = 0L
    private var lastSeq: Long? = null
    data class TerminalReceipt(val generationId: String, val state: GenerationState, val occurredAt: Long)
    private val terminals = mutableMapOf<String, TerminalReceipt>()
    fun terminal(id: String): TerminalReceipt? = terminals[id]
    private data class Source(val historical: Boolean, val time: Long) {
        fun id(value: String) = if (historical) "history:$value" else value
    }
    private data class ContentBlock(val kind: String, val text: StringBuilder = StringBuilder(), var complete: Boolean = false)
    private class AssistantContent {
        private val blocks = linkedMapOf<String, ContentBlock>()
        fun start(key: String, kind: String) {
            blocks.values.lastOrNull()?.complete = true
            blocks.putIfAbsent(key, ContentBlock(kind))
        }
        fun append(key: String, kind: String, text: String) {
            if (key !in blocks) start(key, kind)
            blocks.getValue(key).text.append(text)
        }
        fun append(kind: String, text: String) {
            val previous = blocks.entries.lastOrNull()
            val key = if (previous?.value?.kind == kind && !previous.value.complete) previous.key else "block:${blocks.size}"
            append(key, kind, text)
        }
        fun finish(key: String) { blocks[key]?.complete = true }
        fun finish() { blocks.values.forEach { it.complete = true } }
        fun json(): List<JsonValue> = blocks.map { (id, block) -> obj("id" to id, "type" to block.kind, "text" to block.text.toString(), "complete" to block.complete) }
    }
    private val attempts = mutableMapOf<String, AssistantContent>()
    private val liveContent = mutableMapOf<String, AssistantContent>()
    private val codexContent = mutableMapOf<String, AssistantContent>()
    /** 记录行首先来自历史还是实时；无 seq 的实时流也不能被迟到历史回写。 */
    private val rowHistorical = mutableMapOf<String, Boolean>()
    private val rowSequences = mutableMapOf<String, Long>()
    private var acpContent = AssistantContent()
    private var acpTurnHadContent = false
    private var acpMessageId: String? = null
    private var liveSequence = 0L
    val approvalHandles = mutableMapOf<String, String>()

    private fun resetAcpTurnContent() {
        acpMessageId = null
        acpContent = AssistantContent()
        acpTurnHadContent = false
    }

    fun beginTurn(localRequestId: String) {
        generationId = localRequestId
        generationState = GenerationState.QUEUED
        resetAcpTurnContent()
    }
    fun confirmGeneration(id: String) {
        val previous = generationId
        // 只有本地 request 身份等待远端回执时才做身份迁移；新一轮真实 turn
        // 不能继承上一轮已经记录的终态。
        if (previous?.startsWith("request:") == true && previous != id) {
            terminals[previous]?.let { receipt ->
                terminals.putIfAbsent(id, receipt.copy(generationId = id))
            }
        }
        generationId = id
        terminals[id]?.let { receipt ->
            terminalGeneration = id to receipt.state
            generationState = receipt.state
        } ?: run { generationState = GenerationState.RUNNING }
    }
    private fun recordTerminal(id: String, state: GenerationState, source: Source) {
        if (source.historical) return
        val receipt = terminals.getOrPut(id) { TerminalReceipt(id, state, source.time) }
        terminalGeneration = id to receipt.state
        if (id == generationId) generationState = receipt.state
    }
    fun markUnknown() { generationState = GenerationState.OUTCOME_UNKNOWN }

    fun accept(value: JsonValue, history: Boolean = false): List<VerifiedConversationEvent> {
        return accept(value, history, clock())
    }

    private fun accept(value: JsonValue, history: Boolean, receivedAt: Long): List<VerifiedConversationEvent> {
        if (!history) terminalGeneration = null
        val source = Source(history, if (history) 0 else receivedAt)
        if (value.text("type") == "snapshot") {
            val events = value.requiredArray("records").flatMap { accept(it, history = true, receivedAt) }.toMutableList()
            value.number("cursor")?.let { cursor -> if (lastSeq == null || cursor > lastSeq!!) lastSeq = cursor }
            value.at("assistantStream")?.let { snapshot ->
                snapshot.array("entries")?.forEach { entry -> events += assistantSnapshot(entry, Source(true, 0)) }
                snapshot.at("message")?.let { message -> events += upsert(message, "assistant", true, null, Source(true, 0)) }
            }
            return events
        }
        if (value.text("type") == "assistant-stream") return assistantStream(value.at("frame") ?: return emptyList(), source)
        if (value.text("type") == "waterfall" && value.text("event") == "approval/request") {
            if (value.text("agentId") != nativeSessionId) return emptyList()
            return approval(value.requiredText("eventId"), value.at("request") ?: obj(), source)
        }
        if (value.text("type") == "event" || value.at("event") != null) {
            val event = value.at("event") ?: value
            val seq = event.number("seq")
            if (!history && seq != null && lastSeq != null) {
                if (seq <= lastSeq!!) return emptyList()
                if (seq != lastSeq!! + 1) throw RemoteSessionException("REMOTE_HISTORY_GAP")
            }
            if (seq != null && !history) lastSeq = seq
            return durable(event, Source(history, event.number("time")?.takeIf { it > 0 } ?: source.time))
        }
        value.text("method")?.let { return nativeFrame(it, value.at("params") ?: obj(), source) }
        return emptyList()
    }

    private fun durable(event: JsonValue, source: Source): List<VerifiedConversationEvent> {
        val data = event.at("data") ?: obj()
        val id = "event:${event.number("seq") ?: ++liveSequence}"
        val time = source.time
        val turn = data.text("turnId") ?: data.number("turn")?.toString()
        when (event.text("type")) {
            "turn/start" -> if (!source.historical) {
                confirmGeneration(turn ?: id)
                clientRequestId = null
            }
            "turn/end" -> {
                val terminal = when (data.at("reason")?.text("kind")) {
                    "error" -> GenerationState.FAILED
                    "cancelled", "interrupted" -> GenerationState.CANCELLED
                    else -> GenerationState.COMPLETED
                }
                val terminalId = turn ?: generationId
                terminalId?.let { recordTerminal(it, terminal, source) }
                return if (source.historical || terminalId != generationId) emptyList() else finalizeStreaming(id, source)
            }
            "user/message" -> {
                if (!source.historical) clientRequestId = data.at("source")?.text("rpcId")
                return upsert(data, "user", false, event.number("seq"), source, turn)
            }
            "assistant/message" -> return upsert(data.at("message") ?: data, "assistant", false, event.number("seq"), source, turn)
            "tool/call" -> return execution(data, id, source, false)
            "tool/result" -> return execution(data, id, source, true)
            "approval/asked" -> return approval(data.text("requestHandle") ?: data.text("id") ?: id, data, source)
            "session/title", "title/changed" -> data.text("title")?.let { title ->
                return listOf(VerifiedConversationEvent.TitleUpdated(source.id(id), time, ConversationId(sessionId), title))
            }
        }
        return emptyList()
    }

    private fun assistantSnapshot(entry: JsonValue, source: Source): List<VerifiedConversationEvent> {
        val attempt = entry.requiredText("attemptId")
        val content = AssistantContent()
        entry.array("content")?.forEachIndexed { index, block ->
            val kind = block.text("type") ?: return@forEachIndexed
            if (kind in setOf("text", "reasoning")) content.append(index.toString(), kind, block.text("text").orEmpty())
        }
        if (entry.array("content") == null) content.append("text", entry.text("text") ?: entry.at("message")?.let(::contentText).orEmpty())
        attempts[attempt] = content
        return upsertContent("remote-stream:$attempt", content, source)
    }

    private fun assistantStream(frame: JsonValue, source: Source): List<VerifiedConversationEvent> {
        val attempt = frame.text("attemptId") ?: return emptyList()
        when (frame.text("type")) {
            "start" -> {
                attempts[attempt] = AssistantContent()
                if (!source.historical) {
                    generationId = frame.number("turn")?.toString() ?: generationId
                    generationState = terminals[generationId]?.state ?: GenerationState.RUNNING
                }
            }
            "chunk" -> {
                val chunk = frame.at("chunk") ?: return emptyList()
                val content = attempts[attempt] ?: throw RemoteSessionException("REMOTE_ASSISTANT_STREAM_GAP")
                val index = chunk.number("index")?.toString() ?: "0"
                when (chunk.text("type")) {
                    "block-start" -> content.start(index, chunk.text("blockType") ?: "text")
                    "text-delta" -> content.append(index, "text", chunk.text("text").orEmpty())
                    "reasoning-delta" -> content.append(index, "reasoning", chunk.text("text").orEmpty())
                    "block-end" -> content.finish(index)
                    else -> return emptyList()
                }
                return upsertContent("remote-stream:$attempt", content, source)
            }
            "end" -> {
                attempts.remove(attempt)
                val messageId = "remote-stream:$attempt"
                liveContent.remove(messageId)
                rowHistorical.remove(messageId)
                rows.remove(messageId)
                return listOf(VerifiedConversationEvent.TimelineTombstoned(source.id("stream-end:${++revision}"), source.time,
                    messageId, revision, ConversationId(sessionId)))
            }
        }
        return emptyList()
    }

    private fun nativeFrame(method: String, params: JsonValue, source: Source): List<VerifiedConversationEvent> {
        if (params.text("threadId")?.let { it != nativeSessionId } == true || params.text("sessionId")?.let { it != nativeSessionId } == true) return emptyList()
        when (method) {
            "turn/started" -> if (!source.historical) {
                (params.at("turn")?.text("id") ?: params.text("turnId"))?.let(::confirmGeneration)
            }
            "turn/completed" -> {
                val terminalId = params.at("turn")?.text("id") ?: params.text("turnId") ?: generationId
                val terminal = when (params.at("turn")?.text("status")) {
                    "failed" -> GenerationState.FAILED
                    "interrupted", "cancelled" -> GenerationState.CANCELLED
                    else -> GenerationState.COMPLETED
                }
                terminalId?.let { recordTerminal(it, terminal, source) }
                if (source.historical || terminalId != generationId) return emptyList()
                return finalizeStreaming("turn-end:${++liveSequence}", source)
            }
            "item/agentMessage/delta" -> {
                val id = params.requiredText("itemId")
                val old = rows[id]?.parts?.filterIsInstance<MessagePart.Text>()?.joinToString("") { it.value }.orEmpty()
                return upsert(obj("id" to id, "content" to listOf(obj("type" to "text", "text" to old + (params.text("delta") ?: "")))), "assistant", true, null, source)
            }
            "item/reasoning/summaryTextDelta", "item/reasoning/textDelta", "item/plan/delta" -> {
                val id = params.requiredText("itemId")
                val content = codexContent.getOrPut(id) { AssistantContent() }
                val part = if (method.endsWith("summaryTextDelta")) "summary:${params.number("summaryIndex") ?: 0}"
                    else "content:${params.number("contentIndex") ?: 0}"
                content.append(part, "reasoning", params.text("delta").orEmpty())
                return upsertContent(id, content, source)
            }
            "item/started", "item/completed" -> {
                val item = params.at("item") ?: return emptyList()
                return when (item.text("type")) {
                    "agentMessage" -> upsert(obj("id" to item.requiredText("id"), "content" to listOf(obj("type" to "text", "text" to (item.text("text") ?: "")))), "assistant", method == "item/started", null, source)
                    "userMessage" -> upsert(item, "user", false, null, source)
                    "reasoning", "plan" -> {
                        val id = item.requiredText("id")
                        val content = codexContent.getOrPut(id) { AssistantContent() }
                        if (method == "item/completed") content.finish()
                        val text = (item.array("summary").orEmpty() + item.array("content").orEmpty()).mapNotNull { it.text("text") }.joinToString("")
                        if (text.isNotEmpty() && id !in rows) content.append("reasoning", text)
                        upsertContent(id, content, source, method == "item/started")
                    }
                    else -> execution(item, item.text("id") ?: "tool:${++liveSequence}", source, method == "item/completed")
                }
            }
            "item/commandExecution/requestApproval", "item/fileChange/requestApproval", "session/request_permission", "cursor/ask_question", "cursor/create_plan" ->
                return approval(params.requiredText("requestHandle"), params, source)
            "session/update" -> {
                val update = params.at("update") ?: params
                when (update.text("sessionUpdate") ?: update.text("type")) {
                    "agent_message_chunk", "agent_message", "agent_thought_chunk", "agent_thought" -> {
                        val content = update.at("content") ?: return emptyList()
                        if (content.text("type") != "text") return emptyList()
                        if (acpMessageId == null) acpMessageId = "remote-acp-stream:${++liveSequence}"
                        val kind = if ((update.text("sessionUpdate") ?: update.text("type")) in setOf("agent_thought_chunk", "agent_thought")) "reasoning" else "text"
                        acpContent.append(kind, content.text("text").orEmpty())
                        acpTurnHadContent = true
                        if (!source.historical) generationState = GenerationState.RUNNING
                        return upsertContent(requireNotNull(acpMessageId), acpContent, source)
                    }
                    "user_message_chunk" -> return upsert(obj("id" to "remote-acp-user:${++liveSequence}", "content" to listOf(update.at("content") ?: obj())), "user", false, null, source)
                    "tool_call", "tool_call_update" -> return execution(update, update.text("toolCallId") ?: "tool:${++liveSequence}", source,
                        update.text("status") in setOf("completed", "failed"))
                    "session_info_update" -> update.text("title")?.let { return listOf(VerifiedConversationEvent.TitleUpdated(source.id("title:${++revision}"), source.time, ConversationId(sessionId), it)) }
                    "prompt_completed", "prompt_failed" -> {
                        val catchUp = update.array("catchUp").orEmpty()
                        val recovered = if (!acpTurnHadContent && catchUp.isNotEmpty()) {
                            // catchUp 是本轮独立快照；清掉上一轮可能因断线残留的 ACP 内容再逐帧回放。
                            resetAcpTurnContent()
                            catchUp.flatMap { accept(it, source.historical, source.time) }
                        } else emptyList()
                        val state = when {
                            update.text("sessionUpdate") == "prompt_failed" -> GenerationState.FAILED
                            update.text("stopReason") == "cancelled" -> GenerationState.CANCELLED
                            update.text("stopReason") == "end_turn" -> GenerationState.COMPLETED
                            else -> GenerationState.OUTCOME_UNKNOWN
                        }
                        generationId?.let { recordTerminal(it, state, source) }
                        return recovered + finalizeStreaming("turn-end:${++liveSequence}", source)
                    }
                }
            }
        }
        return emptyList()
    }

    private fun finalizeStreaming(eventId: String, source: Source): List<VerifiedConversationEvent> {
        resetAcpTurnContent()
        return rows.values.filter { it.state == "STREAMING" && it.generationId == generationId }.map { message ->
            val content = liveContent.remove(message.id)
            val completed = generationState == GenerationState.COMPLETED
            if (completed) content?.finish()
            val confirmed = message.copy(state = "CONFIRMED", contentRevision = ++revision,
                parts = content?.let { contentParts(it.json()) } ?: message.parts,
                reasoning = content?.let { reasoning(it.json(), !completed) } ?: message.reasoning.map { if (completed) it.copy(complete = true) else it })
            rows[message.id] = confirmed
            VerifiedConversationEvent.TimelineUpsert(source.id("$eventId:${message.id}"), source.time, revision, confirmed)
        }
    }

    private fun upsertContent(id: String, content: AssistantContent, source: Source, streaming: Boolean = true): List<VerifiedConversationEvent> {
        liveContent[id] = content
        return upsert(obj("id" to id, "content" to content.json()), "assistant", streaming, null, source)
    }

    private fun contentParts(blocks: List<JsonValue>): List<MessagePart> = blocks.mapNotNull { block -> when (block.text("type")) {
        "text" -> MessagePart.Text(block.text("text").orEmpty())
        "image", "file" -> {
            val ref = block.text("id") ?: block.text("path") ?: block.text("url") ?: block.at("source")?.text("url")
            ref?.let { MessagePart.Attachment(AttachmentDraftId(RemoteLocalIds.encode(it)), block.text("name").orEmpty(), block.text("mimeType").orEmpty()) }
        }
        else -> null
    } }

    private fun reasoning(blocks: List<JsonValue>, streaming: Boolean): List<MessageReasoning> {
        var offset = 0
        return blocks.mapIndexedNotNull { index, block -> when (block.text("type")) {
            "text" -> { offset += block.text("text").orEmpty().length; null }
            "reasoning" -> MessageReasoning(block.text("id") ?: "reasoning:$index", block.text("text").orEmpty(), offset,
                !streaming || block.flag("complete") == true)
            else -> null
        } }
    }

    private fun upsert(value: JsonValue, role: String, streaming: Boolean, seq: Long?, source: Source, messageGeneration: String? = generationId): List<VerifiedConversationEvent> {
        val message = value.at("message") ?: value
        val id = message.text("id") ?: seq?.let { "event-message:$it" } ?: throw RemoteSessionException("REMOTE_MESSAGE_ID_MISSING")
        if (seq != null && rowSequences[id]?.let { it > seq } == true) return emptyList()
        if (source.historical && rowHistorical[id] == false) return emptyList()
        if (seq != null) rowSequences[id] = seq
        val blocks = message.array("content").orEmpty()
        val parts = contentParts(blocks)
            .ifEmpty { message.text("text")?.let { listOf(MessagePart.Text(it)) }.orEmpty() }
        val clientId = message.at("source")?.text("rpcId")?.let(::ClientMessageId)
        val row = TimelineMessage(id, role, parts, source.time, if (streaming) "STREAMING" else "CONFIRMED", ConversationId(sessionId),
            clientMessageId = clientId, contentRevision = ++revision, generationId = messageGeneration,
            localPlaceholder = id.startsWith("remote-stream:") || id.startsWith("remote-acp-stream:"), reasoning = reasoning(blocks, streaming))
        rows[id] = row
        rowHistorical[id] = source.historical
        return listOf(VerifiedConversationEvent.TimelineUpsert(source.id("message:$id:$revision"), source.time, revision, row,
            if (streaming && !source.historical) StreamPresentationOrigin.LIVE else StreamPresentationOrigin.SNAPSHOT))
    }

    private fun contentText(value: JsonValue): String = value.array("content").orEmpty().mapNotNull { if (it.text("type") == "text") it.text("text") else null }.joinToString("")

    private fun execution(value: JsonValue, eventId: String, source: Source, completed: Boolean): List<VerifiedConversationEvent> {
        val id = value.text("callId") ?: value.text("toolCallId") ?: value.text("id") ?: eventId
        val step = NativeExecutionStep(stepId = id, eventId = eventId, toolName = value.text("name") ?: value.text("title") ?: value.text("type") ?: "tool",
            arguments = value.text("arguments") ?: value.text("command") ?: "",
            status = if (!completed) NativeExecutionStatus.RUNNING else if (value.flag("isError") == true || value.text("status") == "failed") NativeExecutionStatus.FAILED else NativeExecutionStatus.COMPLETED,
            output = value.text("aggregatedOutput"), revision = ++revision, occurredAt = source.time)
        return listOf(VerifiedConversationEvent.ExecutionStepsUpdated(source.id(eventId), source.time, ConversationId(sessionId), generationId ?: "remote-execution:$sessionId", ++revision, listOf(step)))
    }

    private fun approval(handle: String, value: JsonValue, source: Source): List<VerifiedConversationEvent> {
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(handle.toByteArray())
        approvalHandles[id] = handle
        if (!source.historical) generationState = GenerationState.RUNNING
        val request = ApprovalRequest(ApprovalId(id), ConversationId(sessionId), value.text("command") ?: value.text("toolName")
            ?: value.at("toolCall")?.text("title") ?: value.text("title") ?: Json.canonical(value), value.text("reason") ?: "",
            options = listOf(ApprovalOption(ApprovalChoice.ONCE), ApprovalOption(ApprovalChoice.DENY)), timeoutSeconds = 0,
            requestedAt = source.time, expiresAt = Long.MAX_VALUE)
        return listOf(VerifiedConversationEvent.ApprovalRequested(source.id("approval:$id"), source.time, request, ConversationId(sessionId)))
    }
}
