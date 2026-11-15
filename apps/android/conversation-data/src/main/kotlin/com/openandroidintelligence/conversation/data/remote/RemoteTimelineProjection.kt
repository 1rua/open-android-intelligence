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
    private val mediaReference: ((String, JsonValue, RemoteMediaRevision) -> MessagePart.Attachment?)? = null,
    private val persistentMessageSequence: Boolean = true,
    private val matchStartedGenerationToSubmission: Boolean = true,
    private val onFailedTerminal: (String?) -> Unit = {},
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
    data class TerminalReceipt(val generationId: String, val state: GenerationState, val occurredAt: Long, val errorCode: String? = null)
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
        fun remove(key: String) { blocks.remove(key) }
        fun finish(key: String) { blocks[key]?.complete = true }
        fun finish() { blocks.values.forEach { it.complete = true } }
        fun json(): List<JsonValue> = blocks.map { (id, block) -> obj("id" to id, "type" to block.kind, "text" to block.text.toString(), "complete" to block.complete) }
    }
    // 隐藏身份只属于本次会话投影；流结束与补历史不能撤销已确认的内部内容边界。
    private val hiddenMessages = mutableSetOf<String>()
    private val hiddenAttempts = mutableSetOf<String>()
    private val hiddenBlocks = mutableMapOf<String, MutableSet<String>>()
    private val rawMessageText = mutableMapOf<String, String>()
    private val attempts = mutableMapOf<String, AssistantContent>()
    private val liveContent = mutableMapOf<String, AssistantContent>()
    private val codexContent = mutableMapOf<String, AssistantContent>()
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
    private fun recordTerminal(id: String, state: GenerationState, source: Source, errorCode: String? = null) {
        if (source.historical) return
        val receipt = terminals.getOrPut(id) {
            TerminalReceipt(id, state, source.time, errorCode).also {
                // 即使本机没有 pending，也在首次收到真实失败时记录，重复终态不重复日志。
                if (it.state == GenerationState.FAILED) onFailedTerminal(it.errorCode)
            }
        }
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
                snapshot.at("activeAttempt")?.let { active ->
                    val attempt = active.requiredText("attemptId")
                    val snapshotSource = Source(true, 0, historyRead)
                    if (internalMessage(active) || attempt in hiddenAttempts) {
                        events += suppressAttempt(attempt, snapshotSource)
                    } else {
                        val content = AssistantContent()
                        active.array("stream").orEmpty().forEach { record ->
                            if (record.text("type") == "chunk") record.at("chunk")?.let { appendAssistantChunk(attempt, content, it) }
                        }
                        attempts[attempt] = content
                        if (!history) active.number("turn")?.toString()?.let(::confirmGeneration)
                        events += upsertContent("remote-stream:$attempt", content, snapshotSource)
                    }
                }
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
                terminalId?.let { recordTerminal(it, terminal, source, data.at("reason")?.at("error")?.text("code")) }
                return if (source.historical || terminalId != generationId) emptyList() else finalizeStreaming(id, source)
            }
            "user/message" -> {
                if (!source.historical && !internalMessage(data)) clientRequestId = data.at("source")?.text("rpcId")
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
        if (internalMessage(entry) || entry.at("message")?.let(::internalMessage) == true || attempt in hiddenAttempts)
            return suppressAttempt(attempt, source)
        val content = AssistantContent()
        entry.array("content")?.forEachIndexed { index, block ->
            val key = block.text("id") ?: block.number("index")?.toString() ?: index.toString()
            if (internalMessage(block)) hiddenBlocks.getOrPut(attempt) { mutableSetOf() } += key
            val kind = block.text("type") ?: return@forEachIndexed
            if (key !in hiddenBlocks[attempt].orEmpty() && kind in setOf("text", "reasoning"))
                content.append(key, kind, block.text("text").orEmpty())
        }
        if (entry.array("content") == null) content.append("text", entry.text("text") ?: entry.at("message")?.let(::contentText).orEmpty())
        attempts[attempt] = content
        return upsertContent("remote-stream:$attempt", content, source)
    }

    private fun assistantStream(frame: JsonValue, source: Source): List<VerifiedConversationEvent> {
        val attempt = frame.text("attemptId") ?: return emptyList()
        if (internalMessage(frame) || attempt in hiddenAttempts) return suppressAttempt(attempt, source)
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
                // 未知状态或元分块不属于正文，不要求存在正文尝试；已知分块仍保留缺口检测。
                if (chunk.text("type") !in setOf("block-start", "text-delta", "reasoning-delta", "block-end")) return emptyList()
                val content = attempts[attempt]
                if (content == null) {
                    if (internalMessage(chunk) || chunk.at("block")?.let(::internalMessage) == true) {
                        hiddenBlocks.getOrPut(attempt) { mutableSetOf() } += chunk.number("index")?.toString() ?: "0"
                        return emptyList()
                    }
                    if ((chunk.number("index")?.toString() ?: "0") in hiddenBlocks[attempt].orEmpty()) return emptyList()
                    throw RemoteSessionException("REMOTE_ASSISTANT_STREAM_GAP")
                }
                appendAssistantChunk(attempt, content, chunk)
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

    private fun appendAssistantChunk(attempt: String, content: AssistantContent, chunk: JsonValue) {
        val index = chunk.number("index")?.toString() ?: "0"
        if (internalMessage(chunk) || chunk.at("block")?.let(::internalMessage) == true) {
            hiddenBlocks.getOrPut(attempt) { mutableSetOf() } += index
            content.remove(index)
            return
        }
        if (index in hiddenBlocks[attempt].orEmpty()) return
        when (chunk.text("type")) {
            "block-start" -> content.start(index, chunk.text("blockType") ?: "text")
            "text-delta" -> content.append(index, "text", chunk.text("text").orEmpty())
            "reasoning-delta" -> content.append(index, "reasoning", chunk.text("text").orEmpty())
            "block-end" -> content.finish(index)
        }
    }

    private fun nativeFrame(method: String, params: JsonValue, source: Source): List<VerifiedConversationEvent> {
        // 先核对会话归属，再把外层元标记绑定到其中的 item；外部会话不能污染本投影的隐藏集合。
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
                terminalId?.let { recordTerminal(it, terminal, source, params.at("turn")?.at("error")?.text("code")) }
                if (source.historical || terminalId != generationId) return emptyList()
                return finalizeStreaming("turn-end:${++liveSequence}", source)
            }
            "item/agentMessage/delta" -> {
                val id = params.requiredText("itemId")
                if (internalMessage(params)) return suppressMessage(id, source)
                if (id in hiddenMessages) return emptyList()
                // 清洗只影响发布内容；跨分块标签需要继续基于原始累计正文识别。
                val old = rawMessageText[id].orEmpty()
                return upsert(obj("id" to id, "content" to listOf(obj("type" to "text", "text" to old + (params.text("delta") ?: "")))), "assistant", true, null, source)
            }
            "item/reasoning/summaryTextDelta", "item/reasoning/textDelta", "item/plan/delta" -> {
                val id = params.requiredText("itemId")
                if (internalMessage(params)) return suppressMessage(id, source)
                if (id in hiddenMessages) return emptyList()
                val content = codexContent.getOrPut(id) { AssistantContent() }
                val part = if (method.endsWith("summaryTextDelta")) "summary:${params.number("summaryIndex") ?: 0}"
                    else "content:${params.number("contentIndex") ?: 0}"
                content.append(part, "reasoning", params.text("delta").orEmpty())
                return upsertContent(id, content, source)
            }
            "item/started", "item/completed" -> {
                val item = params.at("item") ?: return emptyList()
                val itemType = item.text("type")
                val messageItem = itemType in setOf("agentMessage", "userMessage", "reasoning", "plan")
                if ((internalMessage(params) || internalMessage(item)) && messageItem)
                    return suppressMessage(item.text("id"), source)
                if (item.text("id") in hiddenMessages && messageItem) return emptyList()
                return when (itemType) {
                    "agentMessage" -> upsert(withContent(item, listOf(obj("type" to "text", "text" to (item.text("text") ?: "")))), "assistant", method == "item/started", null, source)
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
                    "commandExecution", "mcpToolCall", "dynamicToolCall", "fileChange", "functionCallOutput", "hookPrompt",
                    "collabAgentToolCall", "subAgentActivity", "webSearch", "imageView", "imageGeneration", "sleep",
                    "enteredReviewMode", "exitedReviewMode", "contextCompaction" ->
                        execution(item, item.text("id") ?: "tool:${++liveSequence}", source, method == "item/completed")
                    else -> emptyList()
                }
            }
            "item/commandExecution/requestApproval", "item/fileChange/requestApproval", "session/request_permission", "cursor/ask_question", "cursor/create_plan" ->
                return approval(params.requiredText("requestHandle"), params, source)
            "session/update" -> {
                val update = params.at("update") ?: params
                val updateType = update.text("sessionUpdate") ?: update.text("type")
                when (updateType) {
                    "agent_message_chunk", "agent_message", "agent_thought_chunk", "agent_thought" -> {
                        val content = update.at("content") ?: return emptyList()
                        if (content.text("type") != "text") return emptyList()
                        if (internalMessage(params) || internalMessage(update) || internalMessage(content)) {
                            if (acpMessageId == null) acpMessageId = "remote-acp-stream:${++liveSequence}"
                            return suppressMessage(acpMessageId, source)
                        }
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
        return rows.values.filter { it.state == "STREAMING" && it.generationId == generationId }.flatMap { message ->
            val content = liveContent.remove(message.id)
            val completed = generationState == GenerationState.COMPLETED
            if (completed) content?.finish()
            val confirmed = message.copy(state = "CONFIRMED", contentRevision = ++revision,
                parts = content?.let { contentParts(it.json(), RemoteMediaRevision(message.id, rowSequences[message.id], source.historical, source.historyRead)) } ?: message.parts,
                reasoning = content?.let { reasoning(it.json(), !completed) } ?: message.reasoning.map { if (completed) it.copy(complete = true) else it })
            val visible = ConversationContentSanitizer.message(confirmed) ?: return@flatMap hide(message.id, source)
            rows[message.id] = visible
            listOf(VerifiedConversationEvent.TimelineUpsert(source.id("$eventId:${message.id}"), source.time, revision, visible))
        }
    }

    private fun upsertContent(id: String, content: AssistantContent, source: Source, streaming: Boolean = true): List<VerifiedConversationEvent> {
        if (id in hiddenMessages) return emptyList()
        liveContent[id] = content
        return upsert(obj("id" to id, "content" to content.json()), "assistant", streaming, null, source)
    }

    private fun contentParts(blocks: List<JsonValue>, mediaRevision: RemoteMediaRevision): List<MessagePart> = blocks.mapNotNull { block -> when (block.text("type")) {
        "text" -> MessagePart.Text(block.text("text").orEmpty())
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
            "reasoning" -> MessageReasoning(block.text("id") ?: "reasoning:$index", block.text("text").orEmpty(), offset,
                !streaming || block.flag("complete") == true)
            else -> null
        } }
    }

    private fun upsert(value: JsonValue, role: String, streaming: Boolean, seq: Long?, source: Source, messageGeneration: String? = generationId): List<VerifiedConversationEvent> {
        val message = value.at("message") ?: value
        if (internalMessage(value) || internalMessage(message))
            return suppressMessage(message.text("id") ?: seq?.let { "event-message:$it" }, source)
        val id = message.text("id") ?: seq?.let { "event-message:$it" } ?: throw RemoteSessionException("REMOTE_MESSAGE_ID_MISSING")
        if (id in hiddenMessages) return emptyList()
        if (persistentMessageSequence && seq != null && rowSequences[id]?.let { it > seq } == true) return emptyList()
        if (source.historical && source.historyRead == null && rowHistorical[id] == false) return emptyList()
        if (seq != null) rowSequences[id] = seq
        val blocks = message.array("content").orEmpty().filterNot(::internalMessage)
        rawMessageText[id] = blocks.filter { it.text("type") == "text" }.joinToString("") { it.text("text").orEmpty() }
            .ifEmpty { message.text("text").orEmpty() }
        val parts = contentParts(blocks, RemoteMediaRevision(id, seq, source.historical, source.historyRead))
            .ifEmpty { message.text("text")?.let { listOf(MessagePart.Text(it)) }.orEmpty() }
        val previous = rows[id]
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
        val row = TimelineMessage(id, role, parts, source.time, if (streaming) "STREAMING" else "CONFIRMED", ConversationId(sessionId),
            clientMessageId = clientId, contentRevision = ++revision, generationId = nativeGeneration,
            localPlaceholder = id.startsWith("remote-stream:") || id.startsWith("remote-acp-stream:"), reasoning = reasoning(blocks, streaming),
            historySequence = if (persistentMessageSequence) seq ?: previous?.historySequence else null)
        val visible = ConversationContentSanitizer.message(row) ?: return hide(id, source)
        rows[id] = visible
        rowHistorical[id] = source.historical
        return listOf(VerifiedConversationEvent.TimelineUpsert(source.id("message:$id:$revision"), source.time, revision, visible,
            if (streaming && !source.historical) StreamPresentationOrigin.LIVE else StreamPresentationOrigin.SNAPSHOT))
    }

    private fun internalMessage(value: JsonValue): Boolean = value.text("role")?.let { it !in setOf("user", "assistant") } == true || value.flag("isMeta") == true

    private fun withContent(value: JsonValue, content: List<JsonValue>): JsonValue = JsonValue.JObject(
        (value as? JsonValue.JObject)?.fields.orEmpty().filterNot { it.first == "content" } + ("content" to JsonValue.JArray(content)))

    private fun suppressAttempt(attempt: String, source: Source): List<VerifiedConversationEvent> {
        hiddenAttempts += attempt
        attempts.remove(attempt)
        return suppressMessage("remote-stream:$attempt", source)
    }

    private fun suppressMessage(id: String?, source: Source): List<VerifiedConversationEvent> {
        if (id == null) return emptyList()
        hiddenMessages += id
        rawMessageText.remove(id)
        liveContent.remove(id)
        codexContent.remove(id)
        return hide(id, source)
    }

    private fun hide(id: String?, source: Source): List<VerifiedConversationEvent> {
        if (id == null || rows.remove(id) == null) return emptyList()
        rowHistorical.remove(id)
        return listOf(VerifiedConversationEvent.TimelineTombstoned(source.id("hidden:$id:${++revision}"), source.time,
            id, revision, ConversationId(sessionId)))
    }

    private fun contentText(value: JsonValue): String = value.array("content").orEmpty().mapNotNull { if (it.text("type") == "text") it.text("text") else null }.joinToString("")

    /** 与固定上游的 itemText/textArray 一致，完整正文优先于摘要。 */
    private fun codexItemText(item: JsonValue): String? {
        item.text("text")?.let { return it }
        item.text("content")?.let { return it }
        val content = item.array("content") ?: item.array("input")
        content?.mapNotNull { part -> (part as? JsonValue.JString)?.value ?: part.text("text") ?: part.text("content") }
            ?.takeIf { it.isNotEmpty() }?.let { return it.joinToString("\n") }
        return item.array("summary")?.takeIf { parts -> parts.all { it is JsonValue.JString } }
            ?.joinToString("\n") { (it as JsonValue.JString).value }
    }

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
