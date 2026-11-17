package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.conversation.sanitizer.SystemPromptSanitizer
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.util.Base64

/** 每个仓储实例只持有一个 Endpoint，原生消息 ID 无需通过字符串猜测后端。 */
internal class RemoteTimelineProjection(private val sessionId: String, private val nativeSessionId: String = RemoteLocalIds.decode(sessionId),
    private val clock: () -> Long = System::currentTimeMillis,
    private val mediaReference: ((String, JsonValue, RemoteMediaRevision) -> MessagePart.Attachment?)? = null,
    private val persistentMessageSequence: Boolean = true,
    private val matchStartedGenerationToSubmission: Boolean = true,
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
    // 临时提交身份只能升级为本轮真实身份，不能在下一轮重新指向当前任务。
    private val generationAliases = mutableMapOf<String, String>()
    // 仅标识 beginTurn 建立的本地等待窗口，不授权使用通知中的原生身份。
    var submittedGenerationId: String? = null
        private set
    fun resolveGeneration(id: String): String = generationAliases[id] ?: id
    fun isUnconfirmedGeneration(id: String): Boolean = generationAliases[id] == id
    fun bindSubmittedGeneration(localId: String, nativeId: String) {
        val previous = generationAliases[localId]
        check(previous == null || previous == localId || previous == nativeId) { "REMOTE_GENERATION_MISMATCH" }
        generationAliases[localId] = nativeId
        terminals[localId]?.let { receipt -> terminals.putIfAbsent(nativeId, receipt.copy(generationId = nativeId)) }
        if (generationId == localId) confirmGeneration(nativeId)
    }
    fun cancellationGeneration(expectedId: String): String? {
        val resolved = resolveGeneration(expectedId)
        return resolved.takeIf {
            it == generationId && terminals[it] == null && generationState in setOf(
                GenerationState.QUEUED, GenerationState.RUNNING, GenerationState.OUTCOME_UNKNOWN)
        }
    }
    fun terminal(id: String): TerminalReceipt? = terminals[id]
    private data class Source(val historical: Boolean, val time: Long, val historyRead: Long? = null) {
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
    private val codexAgentMessages = mutableMapOf<String, StringBuilder>()
    /** 记录行首先来自历史还是实时；无 seq 的实时流也不能被迟到历史回写。 */
    private val rowHistorical = mutableMapOf<String, Boolean>()
    private val rowSequences = mutableMapOf<String, Long>()
    private val userRequestsByGeneration = mutableMapOf<String, ClientMessageId>()
    private val userItemsByGeneration = mutableMapOf<String, String>()
    private val ambiguousUserGenerations = mutableSetOf<String>()

    /** Codex 只有本次 prompt 回执可证明 turn 归属；先到的用户回声随后按同一原生 turn 补齐身份。 */
    fun bindUserRequest(nativeGeneration: String, clientId: ClientMessageId): List<VerifiedConversationEvent> {
        val previous = userRequestsByGeneration.putIfAbsent(nativeGeneration, clientId)
        check(previous == null || previous == clientId) { "REMOTE_GENERATION_MISMATCH" }
        val candidates = rows.values.filter { it.sender == "user" && it.generationId == nativeGeneration }
        if (candidates.size > 1) ambiguousUserGenerations += nativeGeneration
        val row = candidates.singleOrNull()?.takeUnless { nativeGeneration in ambiguousUserGenerations }
            ?: return emptyList()
        if (row.clientMessageId != null && row.clientMessageId != clientId) return emptyList()
        userItemsByGeneration.putIfAbsent(nativeGeneration, row.id)
        if (row.clientMessageId == clientId) return emptyList()
        val identified = row.copy(clientMessageId = clientId, contentRevision = ++revision)
        rows[row.id] = identified
        return listOf(VerifiedConversationEvent.TimelineUpsert("request-identity:${row.id}:$revision", clock(), revision, identified))
    }

    private var acpContent = AssistantContent()
    private var acpTurnHadContent = false
    private var acpMessageId: String? = null
    private val acpUserContent = mutableMapOf<String, MutableList<JsonValue>>()
    private var liveSequence = 0L
    val approvalHandles = mutableMapOf<String, String>()

    private fun resetAcpTurnContent() {
        acpMessageId = null
        acpContent = AssistantContent()
        acpTurnHadContent = false
    }

    fun beginTurn(localRequestId: String) {
        generationAliases.putIfAbsent(localRequestId, localRequestId)
        submittedGenerationId = localRequestId
        generationId = localRequestId
        generationState = GenerationState.QUEUED
        resetAcpTurnContent()
    }
    fun confirmGeneration(id: String) {
        val previous = generationId
        if (previous != id && previous != submittedGenerationId) submittedGenerationId = null
        // 只有本地 request 身份等待远端回执时才做身份迁移；新一轮真实 turn
        // 不能继承上一轮已经记录的终态。
        if (matchStartedGenerationToSubmission && previous != null && isUnconfirmedGeneration(previous) && previous != id) {
            generationAliases[previous] = id
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

    fun accept(value: JsonValue, history: Boolean = false, historyRead: Long? = null): List<VerifiedConversationEvent> {
        return accept(value, history, clock(), historyRead)
    }

    private fun accept(value: JsonValue, history: Boolean, receivedAt: Long, historyRead: Long? = null): List<VerifiedConversationEvent> {
        if (!history) terminalGeneration = null
        val source = Source(history, if (history) 0 else receivedAt, historyRead)
        if (value.text("type") == "snapshot") {
            val events = value.requiredArray("records").flatMap { accept(it, history = true, receivedAt, historyRead) }.toMutableList()
            value.number("cursor")?.let { cursor -> if (lastSeq == null || cursor > lastSeq!!) lastSeq = cursor }
            value.at("assistantStream")?.let { snapshot ->
                snapshot.array("entries")?.forEach { entry -> events += assistantSnapshot(entry, Source(true, 0, historyRead)) }
                snapshot.at("message")?.let { message -> events += upsert(message, "assistant", true, null, Source(true, 0, historyRead)) }
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
            return durable(event, Source(history, event.number("time")?.takeIf { it > 0 } ?: source.time, historyRead))
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
            "assistant/message" -> return upsert(data, "assistant", false, event.number("seq"), source, turn)
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
            if (kind in setOf("text", "reasoning", "thinking", "thought")) {
                val normalizedKind = if (kind in setOf("thinking", "thought")) "reasoning" else kind
                content.append(index.toString(), normalizedKind, block.text("text").orEmpty())
            }
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
                    "block-start" -> {
                        val blockType = chunk.text("blockType") ?: "text"
                        content.start(index, if (blockType in setOf("thinking", "thought")) "reasoning" else blockType)
                    }
                    "text-delta" -> content.append(index, "text", chunk.text("text").orEmpty())
                    "reasoning-delta", "thinking-delta", "thought-delta" -> content.append(index, "reasoning", chunk.text("text").orEmpty())
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
                val buffer = codexAgentMessages.getOrPut(id) { StringBuilder() }
                buffer.append(params.text("delta").orEmpty())
                return upsert(obj("id" to id, "content" to listOf(obj("type" to "text", "text" to buffer.toString()))), "assistant", true, null, source)
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
                    "agentMessage" -> {
                        val itemId = item.requiredText("id")
                        if (method == "item/completed") {
                            codexAgentMessages.remove(itemId)
                        }
                        upsert(obj("id" to itemId, "content" to listOf(obj("type" to "text", "text" to (item.text("text") ?: "")))), "assistant", method == "item/started", null, source)
                    }
                    "userMessage" -> upsert(item, "user", false, null, source, params.text("turnId"))
                    "reasoning", "plan" -> {
                        val id = item.requiredText("id")
                        val completeText = codexItemText(item)
                        val content = if (completeText != null) {
                            // 完整条目是该 item 的权威快照，替换增量内容，不能重复拼接。
                            AssistantContent().also { snapshot ->
                                snapshot.append("reasoning", completeText)
                                codexContent[id] = snapshot
                            }
                        } else codexContent.getOrPut(id) { AssistantContent() }
                        if (method == "item/completed") content.finish()
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
                        if (content.text("type") != "text" && content !is JsonValue.JString && update.text("delta") == null) return emptyList()
                        if (acpMessageId == null) acpMessageId = "remote-acp-stream:${++liveSequence}"
                        val kind = if ((update.text("sessionUpdate") ?: update.text("type")) in setOf("agent_thought_chunk", "agent_thought")) "reasoning" else "text"
                        val textDelta = content.text("text") ?: (content as? JsonValue.JString)?.value ?: update.text("delta").orEmpty()
                        acpContent.append(kind, textDelta)
                        acpTurnHadContent = true
                        if (!source.historical) generationState = GenerationState.RUNNING
                        return upsertContent(requireNotNull(acpMessageId), acpContent, source)
                    }
                    "user_message_chunk" -> {
                        val chunk = update.at("content") ?: return emptyList()
                        val messageId = update.text("messageId")?.takeIf { it.isNotBlank() }
                        // 只合并后端明确标为同一消息的分片；旧协议缺少身份时不能猜测消息边界。
                        val id = messageId?.let { "remote-acp-user:message:$it" } ?: "remote-acp-user:${++liveSequence}"
                        val content = if (messageId != null) {
                            acpUserContent.getOrPut(id) { mutableListOf() }.apply { add(chunk) }
                        } else listOf(chunk)
                        return upsert(obj("id" to id, "content" to content), "user", false, null, source,
                            messageTimestamp = rows[id]?.timestamp?.takeIf { it > 0L } ?: source.time)
                    }
                    "tool_call", "tool_call_update" -> return execution(update, update.text("toolCallId") ?: "tool:${++liveSequence}", source,
                        update.text("status") in setOf("completed", "failed"))
                    "session_info_update" -> update.text("title")?.let { return listOf(VerifiedConversationEvent.TitleUpdated(source.id("title:${++revision}"), source.time, ConversationId(sessionId), it)) }
                    "prompt_completed", "prompt_failed" -> {
                        val recovered = recoverAcpCatchUp(update.array("catchUp").orEmpty(), source)
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

    private fun identifiedAcpUserChunk(frame: JsonValue): Pair<String, JsonValue>? {
        if (frame.text("method") != "session/update") return null
        val params = frame.at("params") ?: return null
        if (params.text("threadId")?.let { it != nativeSessionId } == true || params.text("sessionId")?.let { it != nativeSessionId } == true) return null
        val update = params.at("update") ?: params
        if ((update.text("sessionUpdate") ?: update.text("type")) != "user_message_chunk") return null
        val messageId = update.text("messageId")?.takeIf { it.isNotBlank() } ?: return null
        val content = update.at("content") ?: return null
        return "remote-acp-user:message:$messageId" to content
    }

    private fun recoverAcpCatchUp(frames: List<JsonValue>, source: Source): List<VerifiedConversationEvent> {
        val users = linkedMapOf<String, MutableList<JsonValue>>()
        frames.forEach { frame -> identifiedAcpUserChunk(frame)?.let { (id, chunk) ->
            users.getOrPut(id) { mutableListOf() }.add(chunk)
        } }
        val recoverOtherContent = !acpTurnHadContent
        if (recoverOtherContent && frames.isNotEmpty()) resetAcpTurnContent()
        return frames.flatMap { frame ->
            val user = identifiedAcpUserChunk(frame)
            if (user != null) {
                val (id, _) = user
                val snapshot = users.remove(id) ?: return@flatMap emptyList()
                val known = acpUserContent[id].orEmpty()
                // 补帧可能被截断；仅在按顺序包含全部已收分片时替换，避免丢失已知正文。
                var included = 0
                snapshot.forEach { chunk -> if (included < known.size && chunk == known[included]) included++ }
                if (included != known.size) return@flatMap emptyList()
                acpUserContent[id] = snapshot
                upsert(obj("id" to id, "content" to snapshot), "user", false, null, source,
                    messageTimestamp = rows[id]?.timestamp?.takeIf { it > 0L } ?: source.time)
            } else if (recoverOtherContent) accept(frame, source.historical, source.time) else emptyList()
        }
    }

    private fun finalizeStreaming(eventId: String, source: Source): List<VerifiedConversationEvent> {
        resetAcpTurnContent()
        return rows.values.filter { it.state == "STREAMING" && it.generationId == generationId }.mapNotNull { message ->
            val content = liveContent.remove(message.id)
            val completed = generationState == GenerationState.COMPLETED
            if (completed) content?.finish()
            val newParts = content?.let { contentParts(it.json(), RemoteMediaRevision(message.id, rowSequences[message.id], source.historical, source.historyRead)) } ?: message.parts
            val newReasoning = content?.let { reasoning(it.json(), !completed) } ?: message.reasoning.map { if (completed) it.copy(complete = true) else it }
            val sanitizedText = newParts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value }
            val hasAttachments = newParts.any { it is MessagePart.Attachment }
            val hasSteps = message.executionSteps.isNotEmpty()
            val hasReasoning = newReasoning.isNotEmpty()
            if (SystemPromptSanitizer.shouldDropMessage(sanitizedText, hasAttachments, hasSteps, hasReasoning, false)) {
                rows.remove(message.id)
                return@mapNotNull null
            }
            val confirmed = message.copy(state = "CONFIRMED", contentRevision = ++revision,
                parts = newParts,
                reasoning = newReasoning)
            rows[message.id] = confirmed
            VerifiedConversationEvent.TimelineUpsert(source.id("$eventId:${message.id}"), source.time, revision, confirmed)
        }
    }

    private fun upsertContent(id: String, content: AssistantContent, source: Source, streaming: Boolean = true): List<VerifiedConversationEvent> {
        liveContent[id] = content
        return upsert(obj("id" to id, "content" to content.json()), "assistant", streaming, null, source)
    }

    private fun contentParts(blocks: List<JsonValue>, mediaRevision: RemoteMediaRevision): List<MessagePart> = blocks.mapNotNull { block -> when (block.text("type")) {
        "text" -> {
            val sanitized = SystemPromptSanitizer.sanitize(block.text("text").orEmpty())
            if (sanitized.isEmpty()) null else MessagePart.Text(sanitized)
        }
        "image", "file" -> {
            if (mediaReference != null) return@mapNotNull mediaReference.invoke(sessionId, block, mediaRevision)
            val ref = block.text("id") ?: block.text("path") ?: block.text("url") ?: block.at("source")?.text("url")
            ref?.let { MessagePart.Attachment(AttachmentDraftId(RemoteLocalIds.encode(it)), block.text("name").orEmpty(), block.text("mimeType").orEmpty()) }
        }
        else -> null
    } }

    private fun reasoning(blocks: List<JsonValue>, streaming: Boolean): List<MessageReasoning> {
        var offset = 0
        return blocks.mapIndexedNotNull { index, block -> when (block.text("type")) {
            "text" -> { offset += block.text("text").orEmpty().length; null }
            "reasoning", "thinking", "thought" -> MessageReasoning(block.text("id") ?: "reasoning:$index", block.text("text").orEmpty(), offset,
                !streaming || block.flag("complete") == true)
            else -> null
        } }
    }

    private fun upsert(value: JsonValue, role: String, streaming: Boolean, seq: Long?, source: Source,
        messageGeneration: String? = generationId, messageTimestamp: Long = source.time): List<VerifiedConversationEvent> {
        val message = value.at("message") ?: value
        val id = message.text("id") ?: seq?.let { "event-message:$it" } ?: throw RemoteSessionException("REMOTE_MESSAGE_ID_MISSING")
        if (persistentMessageSequence && seq != null && rowSequences[id]?.let { it > seq } == true) return emptyList()
        if (source.historical && source.historyRead == null && rowHistorical[id] == false) return emptyList()
        if (seq != null) rowSequences[id] = seq

        val isMeta = message.flag("isMeta") == true || value.flag("isMeta") == true || message.at("metadata")?.flag("isMeta") == true
        if (isMeta) {
            val hadRow = rows.containsKey(id)
            rows.remove(id)
            rowHistorical.remove(id)
            rowSequences.remove(id)
            return if (hadRow) {
                listOf(VerifiedConversationEvent.TimelineTombstoned(
                    eventId = source.id("meta-tombstone:$id:${++revision}"),
                    occurredAt = source.time,
                    messageId = id,
                    revision = revision,
                    conversationId = ConversationId(sessionId)
                ))
            } else emptyList()
        }

        val blocks = message.array("content").orEmpty()
        val parts = contentParts(blocks, RemoteMediaRevision(id, seq, source.historical, source.historyRead))
            .ifEmpty {
                message.text("text")?.let { raw ->
                    val sanitized = SystemPromptSanitizer.sanitize(raw)
                    if (sanitized.isNotEmpty()) listOf(MessagePart.Text(sanitized)) else emptyList()
                }.orEmpty()
            }
        val previous = rows[id]
        val reasoningList = reasoning(blocks, streaming)
        val hasAttachments = parts.any { it is MessagePart.Attachment }
        val hasSteps = previous?.executionSteps?.isNotEmpty() == true
        val hasReasoning = reasoningList.isNotEmpty()
        val sanitizedText = parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value }

        if (SystemPromptSanitizer.shouldDropMessage(
                text = sanitizedText,
                hasAttachments = hasAttachments,
                hasSteps = hasSteps,
                hasReasoning = hasReasoning,
                isMeta = false
            )) {
            val hadRow = rows.containsKey(id)
            rows.remove(id)
            rowHistorical.remove(id)
            rowSequences.remove(id)
            return if (hadRow) {
                listOf(VerifiedConversationEvent.TimelineTombstoned(
                    eventId = source.id("empty-tombstone:$id:${++revision}"),
                    occurredAt = source.time,
                    messageId = id,
                    revision = revision,
                    conversationId = ConversationId(sessionId)
                ))
            } else emptyList()
        }

        // 历史 user/message 不回传请求身份；同一原生 item 的已确认映射不能在补历史时丢失。
        val nativeGeneration = if (role == "user" && source.historical) previous?.generationId ?: messageGeneration else messageGeneration
        val directClientId = message.at("source")?.text("rpcId")?.let(::ClientMessageId)
        if (role == "user" && nativeGeneration != null && rows.values.any {
            it.sender == "user" && it.generationId == nativeGeneration && it.id != id
        }) ambiguousUserGenerations += nativeGeneration
        val requestClientId = if (role == "user" && nativeGeneration != null && nativeGeneration !in ambiguousUserGenerations) userRequestsByGeneration[nativeGeneration]?.takeIf {
            val knownItem = userItemsByGeneration.putIfAbsent(nativeGeneration, id)
            knownItem == null || knownItem == id
        } else null
        val clientId = directClientId ?: previous?.clientMessageId ?: requestClientId
        val row = TimelineMessage(id, role, parts, messageTimestamp, if (streaming) "STREAMING" else "CONFIRMED", ConversationId(sessionId),
            clientMessageId = clientId, contentRevision = ++revision, generationId = nativeGeneration,
            executionSteps = previous?.executionSteps.orEmpty(),
            localPlaceholder = id.startsWith("remote-stream:") || id.startsWith("remote-acp-stream:"), reasoning = reasoningList)
        rows[id] = row
        rowHistorical[id] = source.historical
        return listOf(VerifiedConversationEvent.TimelineUpsert(source.id("message:$id:$revision"), source.time, revision, row,
            if (streaming && !source.historical) StreamPresentationOrigin.LIVE else StreamPresentationOrigin.SNAPSHOT))
    }

    private fun contentText(value: JsonValue): String = value.array("content").orEmpty().mapNotNull { if (it.text("type") == "text") it.text("text") else null }.joinToString("")

    /** 与固定上游的 itemText/textArray 一致，完整正文优先于摘要。 */
    private fun codexItemText(item: JsonValue): String? {
        item.text("text")?.let { return it }
        item.text("content")?.let { return it }
        item.text("summary")?.let { return it }
        val content = item.array("content") ?: item.array("input")
        content?.mapNotNull { part -> (part as? JsonValue.JString)?.value ?: part.text("text") ?: part.text("content") }
            ?.takeIf { it.isNotEmpty() }?.let { return it.joinToString("\n") }
        return item.array("summary")?.takeIf { parts -> parts.all { it is JsonValue.JString } }
            ?.joinToString("\n") { (it as JsonValue.JString).value }
    }

    private fun extractToolValue(value: JsonValue, key: String): JsonValue? {
        val target = if (key.contains('.')) {
            val parts = key.split('.')
            var current: JsonValue? = value
            for (part in parts) {
                current = current?.at(part)
                if (current == null) break
            }
            current ?: value.at(key)
        } else {
            value.at(key)
        }
        return target?.takeUnless { it is JsonValue.JNull }
    }

    private fun extractToolString(value: JsonValue, vararg keys: String): String? {
        for (key in keys) {
            val target = extractToolValue(value, key) ?: continue
            val result = when (target) {
                is JsonValue.JString -> target.value
                is JsonValue.JObject, is JsonValue.JArray -> Json.canonical(target)
                is JsonValue.JNumber -> target.raw
                is JsonValue.JBool -> if (target.value) "true" else "false"
                is JsonValue.JNull -> null
            }
            if (result != null) return result
        }
        return null
    }

    private fun execution(value: JsonValue, eventId: String, source: Source, completed: Boolean): List<VerifiedConversationEvent> {
        val id = value.text("callId") ?: value.text("toolCallId") ?: value.text("tool_call_id")
            ?: value.text("stepId") ?: value.text("id") ?: eventId
        val rawToolName = extractToolString(value, "toolName", "name", "tool", "title", "function.name", "type")
        val toolName = rawToolName?.takeIf { it.isNotBlank() } ?: "tool"
        val arguments = extractToolString(value, "arguments", "input", "args", "command", "parameters", "params", "function.arguments").orEmpty()
        val output = extractToolString(value, "aggregatedOutput", "output", "result", "content", "response")
        val isFailed = value.flag("isError") == true || value.flag("is_error") == true || value.text("status") in setOf("failed", "error")
        val step = NativeExecutionStep(
            stepId = id,
            eventId = eventId,
            toolName = toolName,
            arguments = arguments,
            status = if (!completed) NativeExecutionStatus.RUNNING else if (isFailed) NativeExecutionStatus.FAILED else NativeExecutionStatus.COMPLETED,
            output = output,
            revision = ++revision,
            occurredAt = source.time
        )
        return listOf(VerifiedConversationEvent.ExecutionStepsUpdated(
            source.id(eventId), source.time, ConversationId(sessionId),
            generationId ?: "remote-execution:$sessionId", ++revision, listOf(step)
        ))
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
