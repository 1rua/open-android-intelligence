package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.conversation.sanitizer.ContextInjectionFilter
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue

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
    fun terminal(id: String): TerminalReceipt? = terminals[id] ?: terminals[resolveGeneration(id)]
    private data class Source(val historical: Boolean, val time: Long, val historyRead: Long? = null) {
        fun id(value: String) = if (historical) "history:$value" else value
    }
    private sealed class ContentBlock {
        abstract val kind: String
        class Text(val text: StringBuilder = StringBuilder(), var complete: Boolean = false) : ContentBlock() {
            override val kind: String = "text"
        }
        class Reasoning(val text: StringBuilder = StringBuilder(), var complete: Boolean = false) : ContentBlock() {
            override val kind: String = "reasoning"
        }
        class Tool(var step: NativeExecutionStep, var complete: Boolean = false) : ContentBlock() {
            override val kind: String = "tool_call"
        }
    }
    private class AssistantContent {
        private val blocks = linkedMapOf<String, ContentBlock>()
        private fun normalizeKind(kind: String): String = when (kind) {
            "thinking", "thought" -> "reasoning"
            else -> kind
        }
        private fun matchesKind(block: ContentBlock, normalizedKind: String): Boolean = when (block) {
            is ContentBlock.Text -> normalizedKind == "text"
            is ContentBlock.Reasoning -> normalizedKind == "reasoning"
            is ContentBlock.Tool -> normalizedKind in TOOL_USE_BLOCK_TYPES
        }
        private fun findToolBlock(key: String, callId: String?): ContentBlock.Tool? {
            val normalizedId = callId?.takeIf { it.isNotBlank() }
            if (normalizedId != null) {
                (blocks.values.firstOrNull { it is ContentBlock.Tool && it.step.stepId == normalizedId } as? ContentBlock.Tool)
                    ?.let { return it }
            }
            val byKey = blocks[key] as? ContentBlock.Tool ?: return null
            if (normalizedId == null || byKey.step.stepId == normalizedId || byKey.step.stepId == key) {
                return byKey
            }
            return null
        }
        private fun allocateInsertKey(preferredKey: String, prefix: String): String {
            if (preferredKey !in blocks) return preferredKey
            val candidate = "$prefix:$preferredKey"
            if (candidate !in blocks) return candidate
            return "$prefix:$preferredKey:${blocks.size}"
        }
        fun firstReasoningKey(): String? =
            blocks.entries.firstOrNull { it.value is ContentBlock.Reasoning }?.key
        fun finishTrailingReasoning() {
            val last = blocks.values.lastOrNull()
            if (last is ContentBlock.Reasoning) last.complete = true
        }
        fun start(key: String, kind: String) {
            finishTrailingReasoning()
            val normalized = normalizeKind(kind)
            val existing = blocks[key]
            val targetKey = if (existing == null || matchesKind(existing, normalized)) {
                key
            } else {
                allocateInsertKey(key, normalized)
            }
            blocks.putIfAbsent(targetKey, when {
                normalized == "reasoning" -> ContentBlock.Reasoning()
                normalized in TOOL_USE_BLOCK_TYPES ->
                    ContentBlock.Tool(NativeExecutionStep(targetKey, targetKey, "tool", "", status = NativeExecutionStatus.RUNNING))
                else -> ContentBlock.Text()
            })
        }
        fun append(key: String, kind: String, text: String) {
            val normalizedKind = normalizeKind(kind)
            val existing = blocks[key]
            val targetKey = when {
                existing == null -> {
                    start(key, normalizedKind)
                    key
                }
                matchesKind(existing, normalizedKind) -> key
                else -> {
                    val scopedKey = "$normalizedKind:$key"
                    val scopedExisting = blocks[scopedKey]
                    if (scopedExisting == null) {
                        start(scopedKey, normalizedKind)
                        scopedKey
                    } else if (matchesKind(scopedExisting, normalizedKind)) {
                        scopedKey
                    } else {
                        val freshKey = allocateInsertKey(key, normalizedKind)
                        start(freshKey, normalizedKind)
                        freshKey
                    }
                }
            }
            when (val block = blocks.getValue(targetKey)) {
                is ContentBlock.Text -> block.text.append(text)
                is ContentBlock.Reasoning -> block.text.append(text)
                is ContentBlock.Tool -> block.step = block.step.copy(arguments = block.step.arguments + text)
            }
        }
        fun append(kind: String, text: String) {
            val normalizedKind = normalizeKind(kind)
            val previous = blocks.entries.lastOrNull()
            val reusePrevious = when (val prev = previous?.value) {
                is ContentBlock.Text -> normalizedKind == "text" && !prev.complete
                is ContentBlock.Reasoning -> normalizedKind == "reasoning" && !prev.complete
                else -> false
            }
            val key = if (reusePrevious) requireNotNull(previous).key else "block:${blocks.size}"
            append(key, normalizedKind, text)
        }
        fun upsertTool(step: NativeExecutionStep, key: String = "tool:${step.stepId}"): NativeExecutionStep {
            val existing = findToolBlock(key, step.stepId)
            if (existing != null) {
                val merged = mergeExecutionStep(existing.step, step)
                existing.step = merged
                if (merged.status == NativeExecutionStatus.COMPLETED || merged.status == NativeExecutionStatus.FAILED) {
                    existing.complete = true
                }
                return merged
            }
            finishTrailingReasoning()
            val insertKey = allocateInsertKey(key, "tool:${step.stepId}")
            blocks[insertKey] = ContentBlock.Tool(
                step = step,
                complete = step.status == NativeExecutionStatus.COMPLETED || step.status == NativeExecutionStatus.FAILED,
            )
            return step
        }
        fun appendToolDelta(
            key: String,
            callId: String?,
            name: String?,
            argumentsDelta: String,
            revision: Long,
            occurredAt: Long,
            allocateOrderSeq: () -> Long,
            sourceMessageId: String?,
        ): NativeExecutionStep {
            val existing = findToolBlock(key, callId)
            if (existing != null) {
                val mergedArguments = if (existing.step.arguments == "{}" && argumentsDelta.isNotEmpty()) {
                    argumentsDelta
                } else {
                    existing.step.arguments + argumentsDelta
                }
                val updated = existing.step.copy(
                    stepId = callId?.takeIf { it.isNotBlank() } ?: existing.step.stepId,
                    toolName = name?.takeIf { it.isNotBlank() } ?: existing.step.toolName,
                    arguments = mergedArguments,
                    revision = maxOf(existing.step.revision, revision),
                )
                existing.step = updated
                return updated
            }
            finishTrailingReasoning()
            val stepId = callId?.takeIf { it.isNotBlank() } ?: key
            val created = NativeExecutionStep(
                stepId = stepId,
                eventId = stepId,
                toolName = name?.takeIf { it.isNotBlank() } ?: "tool",
                arguments = argumentsDelta,
                status = NativeExecutionStatus.RUNNING,
                revision = revision,
                sourceMessageId = sourceMessageId,
                occurredAt = occurredAt,
                orderSequence = allocateOrderSeq(),
            )
            val insertKey = allocateInsertKey(key, "tool:$stepId")
            blocks[insertKey] = ContentBlock.Tool(created, complete = false)
            return created
        }
        fun finish(key: String) {
            val target = blocks[key]
                ?: blocks["tool:$key"]
                ?: key.toIntOrNull()?.let { idx -> blocks.values.elementAtOrNull(idx) }
            when (target) {
                is ContentBlock.Text -> target.complete = true
                is ContentBlock.Reasoning -> target.complete = true
                is ContentBlock.Tool -> target.complete = true
                null -> Unit
            }
        }
        fun finish(completeReasoningAndText: Boolean = true, terminalToolsAsUnknown: Boolean = false) {
            blocks.values.forEach { block ->
                when (block) {
                    is ContentBlock.Text -> if (completeReasoningAndText) block.complete = true
                    is ContentBlock.Reasoning -> if (completeReasoningAndText) block.complete = true
                    is ContentBlock.Tool -> {
                        block.complete = true
                        if (terminalToolsAsUnknown && block.step.status == NativeExecutionStatus.RUNNING) {
                            block.step = block.step.copy(status = NativeExecutionStatus.UNKNOWN)
                        }
                    }
                }
            }
        }
        fun toRawParts(): List<MessagePart> = blocks.values.mapNotNull { block ->
            if (block is ContentBlock.Text) MessagePart.Text(block.text.toString()) else null
        }
        fun toTimelineSteps(streaming: Boolean): List<ExecutionTimelineStep> {
            var offset = 0
            val result = mutableListOf<ExecutionTimelineStep>()
            blocks.entries.forEachIndexed { index, (id, block) ->
                when (block) {
                    is ContentBlock.Text -> offset += block.text.length
                    is ContentBlock.Reasoning -> result += ExecutionTimelineStep.ThinkingStep(
                        stepId = id,
                        text = block.text.toString(),
                        bodyOffset = offset,
                        complete = !streaming || block.complete,
                        orderIndex = index,
                    )
                    is ContentBlock.Tool -> result += ExecutionTimelineStep.ToolCallStep(
                        step = block.step,
                        bodyOffset = offset,
                        orderIndex = index,
                    )
                }
            }
            return result
        }
    }
    private val attempts = mutableMapOf<String, AssistantContent>()
    private val liveContent = mutableMapOf<String, AssistantContent>()
    private val codexContent = mutableMapOf<String, AssistantContent>()
    private val codexAgentMessages = mutableMapOf<String, StringBuilder>()
    /** 记录行首先来自历史还是实时；无 seq 的实时流也不能被迟到历史回写。 */
    private val rowHistorical = mutableMapOf<String, Boolean>()
    private val rowSequences = mutableMapOf<String, Long>()
    private val rowOrderSequences = mutableMapOf<String, Long>()
    private val knownTools = mutableMapOf<String, NativeExecutionStep>()
    private val toolOwnerMessageId = mutableMapOf<String, String>()
    private var nextOrderSequence = 0L
    private var historicalTurnId: String? = null
    private var lastHistoricalTimestamp = 0L
    private val userRequestsByGeneration = mutableMapOf<String, ClientMessageId>()
    private val userItemsByGeneration = mutableMapOf<String, String>()
    private val ambiguousUserGenerations = mutableSetOf<String>()

    private fun allocateOrder(explicitSeq: Long? = null, historical: Boolean = false): Long {
        if (explicitSeq != null && historical) {
            if (explicitSeq > nextOrderSequence) nextOrderSequence = explicitSeq
            return explicitSeq
        }
        val allocated = if (explicitSeq != null && explicitSeq > nextOrderSequence) {
            explicitSeq
        } else {
            nextOrderSequence + 1L
        }
        nextOrderSequence = allocated
        return allocated
    }

    /** Codex 只有本次 prompt 回执可证明 turn 归属；先到的用户回声随后按同一原生 turn 补齐身份。 */
    fun bindUserRequest(nativeGeneration: String, clientId: ClientMessageId): List<VerifiedConversationEvent> {
        val previous = userRequestsByGeneration.putIfAbsent(nativeGeneration, clientId)
        check(previous == null || previous == clientId) { "REMOTE_GENERATION_MISMATCH" }
        val candidates = rows.values.filter {
            ContextInjectionFilter.classifySender(it.sender) == ContextInjectionFilter.SenderRole.USER &&
                it.generationId == nativeGeneration
        }
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
    private var acpMessageTimestamp: Long = 0L
    private var acpMessageOrder: Long? = null
    private var lastAcpHistoricalUserId: String? = null
    private val acpUserContent = mutableMapOf<String, MutableList<JsonValue>>()
    private var liveSequence = 0L

    private fun resetAcpTurnContent() {
        acpMessageId = null
        acpMessageTimestamp = 0L
        acpMessageOrder = null
        acpContent = AssistantContent()
        acpTurnHadContent = false
    }

    private fun flushTrailingHistoricalAcpTurn(source: Source): List<VerifiedConversationEvent> {
        val previousId = acpMessageId?.takeIf { it.startsWith("history-acp:") } ?: return emptyList()
        val previousContent = acpContent
        val previousTime = acpMessageTimestamp
        val previousOrder = acpMessageOrder
        previousContent.finish(terminalToolsAsUnknown = true)
        resetAcpTurnContent()
        return upsertContent(
            id = previousId,
            content = previousContent,
            source = source,
            streaming = false,
            messageTimestamp = previousTime,
            explicitOrder = previousOrder,
        )
    }

    /** 历史批次读取完成后收口尾部历史 ACP 轮次与历史临时回合状态，避免串入后续实时流或跳过 catchUp。 */
    fun finishHistoricalReplay(historyRead: Long? = null): List<VerifiedConversationEvent> {
        val source = Source(historical = true, time = lastHistoricalTimestamp, historyRead = historyRead)
        val flushed = flushTrailingHistoricalAcpTurn(source)
        val settled = historicalTurnId?.let { settleHistoricalRunningTools(it, source) }.orEmpty()
        historicalTurnId = null
        lastAcpHistoricalUserId = null
        return flushed + settled
    }

    private fun ensureAcpAssistantMessage(source: Source): String {
        if (!source.historical && acpMessageId?.startsWith("history-acp:") == true) {
            flushTrailingHistoricalAcpTurn(Source(historical = true, time = acpMessageTimestamp))
        }
        if (acpMessageId == null) {
            if (!source.historical && (generationId == null ||
                (generationState in setOf(GenerationState.COMPLETED, GenerationState.FAILED, GenerationState.CANCELLED) &&
                    terminals.containsKey(resolveGeneration(generationId!!))))) {
                generationId = "acp-turn:${++liveSequence}"
            }
            acpMessageId = if (source.historical) "history-acp:${++liveSequence}" else "remote-acp-stream:${++liveSequence}"
            acpMessageTimestamp = source.time
            acpMessageOrder = allocateOrder(null, historical = source.historical)
        }
        return requireNotNull(acpMessageId)
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
    fun markApprovalRequested() { generationState = GenerationState.RUNNING }

    fun accept(value: JsonValue, history: Boolean = false, historyRead: Long? = null): List<VerifiedConversationEvent> {
        return accept(value, history, clock(), historyRead)
    }

    private fun accept(value: JsonValue, history: Boolean, receivedAt: Long, historyRead: Long? = null): List<VerifiedConversationEvent> {
        if (!history) terminalGeneration = null
        val source = Source(history, if (history) 0 else receivedAt, historyRead)
        if (value.text("type") == "snapshot") {
            val events = value.requiredArray("records").flatMap { accept(it, history = true, receivedAt, historyRead) }.toMutableList()
            events += finishHistoricalReplay(historyRead)
            value.number("cursor")?.let { cursor -> if (lastSeq == null || cursor > lastSeq!!) lastSeq = cursor }
            value.at("assistantStream")?.let { snapshot ->
                snapshot.array("entries")?.forEach { entry -> events += assistantSnapshot(entry, Source(true, 0, historyRead)) }
                snapshot.at("message")?.let { message -> events += upsert(message, "assistant", true, null, Source(true, 0, historyRead)) }
            }
            return events
        }
        if (value.text("type") == "assistant-stream") return assistantStream(value.at("frame") ?: return emptyList(), source)
        if (value.text("type") == "event" || value.at("event") != null) {
            val event = value.at("event") ?: value
            val seq = event.number("seq")
            if (!history && seq != null && lastSeq != null) {
                if (seq <= lastSeq!!) return emptyList()
                if (seq != lastSeq!! + 1) throw RemoteSessionException("REMOTE_HISTORY_GAP")
            }
            if (seq != null && !history) lastSeq = seq
            val eventTime = event.number("time")?.takeIf { it > 0 }
            val effectiveTime = if (history) {
                if (eventTime != null) {
                    lastHistoricalTimestamp = eventTime
                    eventTime
                } else {
                    lastHistoricalTimestamp
                }
            } else {
                eventTime ?: source.time
            }
            return durable(event, Source(history, effectiveTime, historyRead))
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
            "turn/start" -> if (source.historical) {
                historicalTurnId = turn ?: id
            } else {
                confirmGeneration(turn ?: id)
                clientRequestId = null
            }
            "turn/end" -> {
                if (source.historical) {
                    val endedTurn = turn ?: historicalTurnId
                    val settled = if (endedTurn != null) settleHistoricalRunningTools(endedTurn, source) else emptyList()
                    if (turn == null || turn == historicalTurnId) historicalTurnId = null
                    return settled
                }
                val terminal = when (data.at("reason")?.text("kind")) {
                    "error" -> GenerationState.FAILED
                    "cancelled", "interrupted" -> GenerationState.CANCELLED
                    else -> GenerationState.COMPLETED
                }
                val terminalId = turn ?: generationId
                terminalId?.let { recordTerminal(it, terminal, source) }
                return if (terminalId != generationId) emptyList() else finalizeStreaming(id, source)
            }
            "user/message" -> {
                if (!source.historical) clientRequestId = data.at("source")?.text("rpcId")
                return upsert(data, "user", false, event.number("seq"), source, turn ?: if (source.historical) historicalTurnId else generationId)
            }
            "assistant/message" -> return upsert(data, "assistant", false, event.number("seq"), source, turn ?: if (source.historical) historicalTurnId else generationId)
            "tool/call" -> {
                if (!source.historical) attempts.values.forEach { it.finishTrailingReasoning() }
                return execution(data, id, source, false, explicitSeq = event.number("seq"), explicitTurn = turn)
            }
            "tool/result" -> return execution(data, id, source, true, explicitSeq = event.number("seq"), explicitTurn = turn)
            "session/title", "title/changed" -> data.text("title")?.let { rawTitle ->
                val cleanTitle = ContextInjectionFilter.sanitizeSummaryText(rawTitle)
                if (cleanTitle.isEmpty()) return emptyList()
                return listOf(VerifiedConversationEvent.TitleUpdated(source.id(id), time, ConversationId(sessionId), cleanTitle))
            }
        }
        return emptyList()
    }

    private fun assistantSnapshot(entry: JsonValue, source: Source): List<VerifiedConversationEvent> {
        val attempt = entry.requiredText("attemptId")
        val rowId = "remote-stream:$attempt"
        val content = AssistantContent()
        entry.array("content")?.forEachIndexed { index, block ->
            val kind = block.text("type") ?: return@forEachIndexed
            when (kind) {
                "text" -> content.append(index.toString(), "text", block.text("text").orEmpty())
                "reasoning", "thinking", "thought" -> {
                    val thoughtText = block.text("text")
                        ?: block.text("thinking")
                        ?: block.text("thought")
                        ?: block.text("reasoning")
                        ?: ""
                    content.append(index.toString(), "reasoning", thoughtText)
                    if (block.flag("complete") == true) content.finish(index.toString())
                }
                in TOOL_USE_BLOCK_TYPES -> {
                    val step = parseToolBlockStep(
                        block = block,
                        fallbackId = "$rowId:tool:$index",
                        completed = isToolBlockCompleted(block),
                        sourceTime = source.time,
                        sourceMessageId = rowId,
                        orderSeq = allocateOrder(null, historical = source.historical),
                    )
                    val merged = content.upsertTool(step, key = index.toString())
                    knownTools[merged.stepId] = merged
                    toolOwnerMessageId[merged.stepId] = rowId
                }
                in TOOL_RESULT_BLOCK_TYPES -> {
                    applyToolResultBlockToContent(block, content, source.time)
                }
            }
        }
        if (entry.array("content") == null) content.append("text", entry.text("text") ?: entry.at("message")?.let(::contentText).orEmpty())
        attempts[attempt] = content
        return upsertContent(rowId, content, source)
    }

    private fun assistantStream(frame: JsonValue, source: Source): List<VerifiedConversationEvent> {
        val attempt = frame.text("attemptId") ?: return emptyList()
        val rowId = "remote-stream:$attempt"
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
                        if (blockType in TOOL_USE_BLOCK_TYPES) {
                            val callId = chunk.text("id") ?: chunk.text("callId") ?: chunk.text("toolCallId") ?: "$rowId:tool:$index"
                            val step = parseToolBlockStep(
                                block = chunk,
                                fallbackId = callId,
                                completed = false,
                                sourceTime = source.time,
                                sourceMessageId = rowId,
                                orderSeq = knownTools[callId]?.orderSequence ?: allocateOrder(null),
                            )
                            val merged = content.upsertTool(step, key = index)
                            knownTools[merged.stepId] = merged
                            toolOwnerMessageId[merged.stepId] = rowId
                        } else {
                            content.start(index, if (blockType in setOf("thinking", "thought")) "reasoning" else blockType)
                        }
                    }
                    "text-delta" -> content.append(index, "text", chunk.text("text") ?: chunk.text("delta").orEmpty())
                    "reasoning-delta", "thinking-delta", "thought-delta" -> content.append(
                        index,
                        "reasoning",
                        chunk.text("text") ?: chunk.text("thinking") ?: chunk.text("delta").orEmpty(),
                    )
                    "tool-call-delta", "tool_call_delta", "tool-call", "tool_call" -> {
                        val callId = chunk.text("id") ?: chunk.text("callId") ?: chunk.text("toolCallId")
                        val toolName = extractToolName(chunk, allowGenericFallback = false)
                        val delta = chunk.text("argumentsDelta") ?: chunk.text("delta") ?: chunk.text("text") ?: ""
                        val updated = content.appendToolDelta(
                            key = index,
                            callId = callId,
                            name = toolName,
                            argumentsDelta = delta,
                            revision = ++revision,
                            occurredAt = source.time,
                            allocateOrderSeq = {
                                callId?.let { knownTools[it]?.orderSequence } ?: allocateOrder(null, historical = source.historical)
                            },
                            sourceMessageId = rowId,
                        )
                        knownTools[updated.stepId] = updated
                        toolOwnerMessageId[updated.stepId] = rowId
                    }
                    "block-end" -> content.finish(index)
                    else -> return emptyList()
                }
                return upsertContent(rowId, content, source)
            }
            "end" -> {
                attempts.remove(attempt)
                liveContent.remove(rowId)
                rowHistorical.remove(rowId)
                rows.remove(rowId)
                return listOf(VerifiedConversationEvent.TimelineTombstoned(source.id("stream-end:${++revision}"), source.time,
                    rowId, revision, ConversationId(sessionId)))
            }
        }
        return emptyList()
    }

    private fun nativeFrame(method: String, params: JsonValue, source: Source): List<VerifiedConversationEvent> {
        if (params.text("threadId")?.let { it != nativeSessionId } == true || params.text("sessionId")?.let { it != nativeSessionId } == true) return emptyList()
        when (method) {
            "turn/started" -> {
                val startedTurnId = params.at("turn")?.text("id") ?: params.text("turnId")
                if (source.historical) {
                    if (startedTurnId != null) historicalTurnId = startedTurnId
                } else {
                    startedTurnId?.let(::confirmGeneration)
                }
            }
            "turn/completed" -> {
                val terminalId = params.at("turn")?.text("id") ?: params.text("turnId") ?: if (source.historical) historicalTurnId else generationId
                if (source.historical) {
                    val settled = if (terminalId != null) settleHistoricalRunningTools(terminalId, source) else emptyList()
                    if (terminalId == null || terminalId == historicalTurnId) historicalTurnId = null
                    return settled
                }
                val terminal = when (params.at("turn")?.text("status")) {
                    "failed" -> GenerationState.FAILED
                    "interrupted", "cancelled" -> GenerationState.CANCELLED
                    else -> GenerationState.COMPLETED
                }
                terminalId?.let { recordTerminal(it, terminal, source) }
                if (terminalId != generationId) return emptyList()
                return finalizeStreaming("turn-end:${++liveSequence}", source)
            }
            "item/agentMessage/delta" -> {
                val id = params.requiredText("itemId")
                val buffer = codexAgentMessages.getOrPut(id) { StringBuilder() }
                buffer.append(params.text("delta").orEmpty())
                val turnGen = params.text("turnId") ?: if (source.historical) historicalTurnId else generationId
                return upsert(obj("id" to id, "content" to listOf(obj("type" to "text", "text" to buffer.toString()))), "assistant", true, null, source, turnGen)
            }
            "item/reasoning/summaryTextDelta", "item/reasoning/textDelta", "item/plan/delta" -> {
                val id = params.requiredText("itemId")
                val content = codexContent.getOrPut(id) { AssistantContent() }
                val part = if (method.endsWith("summaryTextDelta")) "summary:${params.number("summaryIndex") ?: 0}"
                    else "content:${params.number("contentIndex") ?: 0}"
                content.append(part, "reasoning", params.text("delta").orEmpty())
                val turnGen = params.text("turnId") ?: if (source.historical) historicalTurnId else generationId
                return upsertContent(id, content, source, explicitGeneration = turnGen)
            }
            "item/started", "item/completed" -> {
                val item = params.at("item") ?: return emptyList()
                val turnGen = params.text("turnId") ?: item.text("turnId") ?: if (source.historical) historicalTurnId else generationId
                return when (item.text("type")) {
                    "agentMessage" -> {
                        val itemId = item.requiredText("id")
                        if (method == "item/completed") {
                            codexAgentMessages.remove(itemId)
                        }
                        upsert(obj("id" to itemId, "content" to listOf(obj("type" to "text", "text" to (item.text("text") ?: "")))), "assistant", method == "item/started", null, source, turnGen)
                    }
                    "userMessage" -> upsert(item, "user", false, null, source, turnGen)
                    "reasoning", "plan" -> {
                        val id = item.requiredText("id")
                        val completeText = codexItemText(item)
                        val existingKey = codexContent[id]?.firstReasoningKey() ?: "content:0"
                        val content = if (completeText != null) {
                            // 完整条目是该 item 的权威快照，替换增量内容并继承原思考块 key，避免 UI stepKey 突变。
                            AssistantContent().also { snapshot ->
                                snapshot.append(existingKey, "reasoning", completeText)
                                codexContent[id] = snapshot
                            }
                        } else codexContent.getOrPut(id) { AssistantContent() }
                        if (method == "item/completed") content.finish()
                        upsertContent(id, content, source, method == "item/started", explicitGeneration = turnGen)
                    }
                    else -> execution(
                        value = item,
                        eventId = item.text("id") ?: "tool:${++liveSequence}",
                        source = source,
                        completed = method == "item/completed",
                        explicitTurn = turnGen,
                    )
                }
            }
            "session/update" -> {
                val update = params.at("update") ?: params
                when (update.text("sessionUpdate") ?: update.text("type")) {
                    "agent_message_chunk", "agent_message", "agent_thought_chunk", "agent_thought" -> {
                        val content = update.at("content") ?: return emptyList()
                        if (content.text("type") != "text" && content !is JsonValue.JString && update.text("delta") == null) return emptyList()
                        val msgId = ensureAcpAssistantMessage(source)
                        val kind = if ((update.text("sessionUpdate") ?: update.text("type")) in setOf("agent_thought_chunk", "agent_thought")) "reasoning" else "text"
                        val textDelta = content.text("text") ?: (content as? JsonValue.JString)?.value ?: update.text("delta").orEmpty()
                        acpContent.append(kind, textDelta)
                        acpTurnHadContent = true
                        if (!source.historical) generationState = GenerationState.RUNNING
                        return upsertContent(
                            id = msgId,
                            content = acpContent,
                            source = source,
                            streaming = !source.historical,
                            messageTimestamp = acpMessageTimestamp,
                            explicitOrder = acpMessageOrder,
                        )
                    }
                    "user_message_chunk" -> {
                        val chunk = update.at("content") ?: return emptyList()
                        val messageId = update.text("messageId")?.takeIf { it.isNotBlank() }
                        val finalizedEvents = if (source.historical && acpTurnHadContent && acpMessageId != null &&
                            (messageId == null || messageId != lastAcpHistoricalUserId)) {
                            val previousId = requireNotNull(acpMessageId)
                            val previousContent = acpContent
                            val previousTime = acpMessageTimestamp
                            val previousOrder = acpMessageOrder
                            previousContent.finish(terminalToolsAsUnknown = true)
                            resetAcpTurnContent()
                            upsertContent(
                                id = previousId,
                                content = previousContent,
                                source = source,
                                streaming = false,
                                messageTimestamp = previousTime,
                                explicitOrder = previousOrder,
                            )
                        } else emptyList()
                        if (source.historical) lastAcpHistoricalUserId = messageId
                        // 只合并后端明确标为同一消息的分片；旧协议缺少身份时不能猜测消息边界。
                        val id = messageId?.let { "remote-acp-user:message:$it" } ?: "remote-acp-user:${++liveSequence}"
                        val content = if (messageId != null) {
                            acpUserContent.getOrPut(id) { mutableListOf() }.apply { add(chunk) }
                        } else listOf(chunk)
                        return finalizedEvents + upsert(obj("id" to id, "content" to content), "user", false, null, source,
                            messageTimestamp = rows[id]?.timestamp?.takeIf { it > 0L } ?: source.time)
                    }
                    "tool_call", "tool_call_update" -> {
                        val msgId = ensureAcpAssistantMessage(source)
                        val eventId = update.text("toolCallId") ?: update.text("callId") ?: update.text("id") ?: "tool:${++liveSequence}"
                        val statusText = update.text("status")?.lowercase()
                        val completed = statusText in setOf("completed", "done", "succeeded", "failed", "error")
                        val rawStep = parseToolBlockStep(
                            block = update,
                            fallbackId = eventId,
                            completed = completed,
                            sourceTime = source.time,
                            sourceMessageId = msgId,
                            orderSeq = knownTools[eventId]?.orderSequence ?: allocateOrder(null),
                        )
                        val merged = acpContent.upsertTool(rawStep)
                        knownTools[merged.stepId] = merged
                        toolOwnerMessageId[merged.stepId] = msgId
                        acpTurnHadContent = true
                        if (!source.historical) generationState = GenerationState.RUNNING
                        return upsertContent(
                            id = msgId,
                            content = acpContent,
                            source = source,
                            streaming = !source.historical,
                            messageTimestamp = acpMessageTimestamp,
                            explicitOrder = acpMessageOrder,
                        )
                    }
                    "session_info_update" -> update.text("title")?.let { rawTitle ->
                        val cleanTitle = ContextInjectionFilter.sanitizeSummaryText(rawTitle)
                        if (cleanTitle.isEmpty()) return emptyList()
                        return listOf(VerifiedConversationEvent.TitleUpdated(source.id("title:${++revision}"), source.time, ConversationId(sessionId), cleanTitle))
                    }
                    "prompt_completed", "prompt_failed" -> {
                        val recovered = recoverAcpCatchUp(update.array("catchUp").orEmpty(), source)
                        val state = when {
                            update.text("sessionUpdate") == "prompt_failed" -> GenerationState.FAILED
                            update.text("stopReason") == "cancelled" -> GenerationState.CANCELLED
                            update.text("stopReason") == "end_turn" -> GenerationState.COMPLETED
                            else -> GenerationState.OUTCOME_UNKNOWN
                        }
                        val previousGeneration = generationId
                        if (!source.historical) {
                            val terminalId = generationId ?: acpMessageId ?: "acp-turn:${++liveSequence}"
                            if (generationId == null) generationId = terminalId
                            recordTerminal(terminalId, state, source)
                        }
                        return recovered + finalizeStreaming("turn-end:${++liveSequence}", source, previousGeneration)
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
        val flushedHistory = if (!source.historical && acpMessageId?.startsWith("history-acp:") == true) {
            flushTrailingHistoricalAcpTurn(Source(historical = true, time = acpMessageTimestamp))
        } else emptyList()
        val users = linkedMapOf<String, MutableList<JsonValue>>()
        frames.forEach { frame -> identifiedAcpUserChunk(frame)?.let { (id, chunk) ->
            users.getOrPut(id) { mutableListOf() }.add(chunk)
        } }
        val recoverOtherContent = !acpTurnHadContent
        if (recoverOtherContent && frames.isNotEmpty()) resetAcpTurnContent()
        return flushedHistory + frames.flatMap { frame ->
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

    private fun finalizeStreaming(
        eventId: String,
        source: Source,
        previousGeneration: String? = generationId,
    ): List<VerifiedConversationEvent> {
        resetAcpTurnContent()
        return rows.values.filter {
            it.state == "STREAMING" && (it.generationId == generationId || it.generationId == previousGeneration)
        }.mapNotNull { message ->
            val content = liveContent.remove(message.id)
            val completed = generationState == GenerationState.COMPLETED
            content?.finish(completeReasoningAndText = completed, terminalToolsAsUnknown = true)
            val rawCandidateParts = content?.toRawParts() ?: (message.rawParts ?: message.parts)
            val role = ContextInjectionFilter.contentSanitizationRole(message.sender)
            val rawTimelineSteps = content?.toTimelineSteps(streaming = !completed)
            val rawReasoning = rawTimelineSteps?.toMessageReasonings()
            val sanitized = ContextInjectionFilter.sanitizeParts(
                parts = rawCandidateParts,
                role = role,
                streaming = !completed,
                reasoning = rawReasoning.orEmpty(),
                timelineSteps = rawTimelineSteps.orEmpty(),
            )
            val newParts = sanitized.parts
            val preservedRaw = message.rawParts ?: sanitized.rawParts
            val newTimelineSteps = if (rawTimelineSteps != null) {
                sanitized.timelineSteps
            } else {
                val settledSteps = message.timelineSteps.map { step ->
                    when (step) {
                        is ExecutionTimelineStep.ThinkingStep ->
                            if (completed) step.copy(complete = true) else step
                        is ExecutionTimelineStep.ToolCallStep ->
                            if (step.step.status == NativeExecutionStatus.RUNNING) {
                                step.copy(step = step.step.copy(status = NativeExecutionStatus.UNKNOWN))
                            } else step
                    }
                }
                ContextInjectionFilter.rebaseTimelineStepsOnProjection(
                    message = message.copy(timelineSteps = settledSteps),
                    targetParts = newParts,
                    role = role,
                    streaming = !completed,
                )
            }
            val newReasoning = if (rawReasoning != null) {
                sanitized.reasoning
            } else if (newTimelineSteps.any { it is ExecutionTimelineStep.ThinkingStep }) {
                newTimelineSteps.filterIsInstance<ExecutionTimelineStep.ThinkingStep>()
                    .map { MessageReasoning(it.stepId, it.text, it.bodyOffset, it.complete) }
            } else {
                val completedReasoning = message.reasoning.map { if (completed) it.copy(complete = true) else it }
                ContextInjectionFilter.rebaseReasoningOnProjection(
                    message = message.copy(reasoning = completedReasoning),
                    targetParts = newParts,
                    role = role,
                    streaming = !completed,
                )
            }
            val newExecutionSteps = mergeDerivedExecutionSteps(
                timelineSteps = newTimelineSteps,
                existingSteps = message.executionSteps.map { step ->
                    if (step.status == NativeExecutionStatus.RUNNING) step.copy(status = NativeExecutionStatus.UNKNOWN) else step
                },
            )
            val sanitizedText = newParts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value }
            val hasAttachments = newParts.any { it is MessagePart.Attachment }
            val hasSteps = newExecutionSteps.isNotEmpty() || newTimelineSteps.any { it is ExecutionTimelineStep.ToolCallStep }
            val hasReasoning = newReasoning.isNotEmpty() || newTimelineSteps.any { it is ExecutionTimelineStep.ThinkingStep }
            if (ContextInjectionFilter.shouldDropMessage(
                    text = sanitizedText,
                    hasAttachments = hasAttachments,
                    hasSteps = hasSteps,
                    hasReasoning = hasReasoning,
                    isMeta = message.isMeta,
                    sender = message.sender,
                    visibility = message.visibility,
                    messageType = message.messageType,
                )
            ) {
                rows.remove(message.id)
                return@mapNotNull null
            }
            val confirmed = message.copy(
                state = "CONFIRMED",
                contentRevision = ++revision,
                parts = newParts,
                reasoning = newReasoning,
                executionSteps = newExecutionSteps,
                timelineSteps = newTimelineSteps,
                generationId = message.generationId ?: generationId,
                rawParts = preservedRaw,
            )
            rows[message.id] = confirmed
            VerifiedConversationEvent.TimelineUpsert(source.id("$eventId:${message.id}"), source.time, revision, confirmed)
        }
    }

    private fun upsertContent(
        id: String,
        content: AssistantContent,
        source: Source,
        streaming: Boolean = true,
        messageTimestamp: Long = source.time,
        explicitOrder: Long? = null,
        explicitGeneration: String? = null,
    ): List<VerifiedConversationEvent> {
        if (streaming) liveContent[id] = content else liveContent.remove(id)
        val previous = rows[id]
        val rowOrder = explicitOrder ?: rowOrderSequences.getOrPut(id) { allocateOrder(null, historical = source.historical) }
        val effectiveTimestamp = previous?.timestamp?.takeIf { it > 0L } ?: messageTimestamp
        val rawCandidateParts = content.toRawParts()
        val rawTimelineSteps = content.toTimelineSteps(streaming = streaming)
        val rawReasoning = rawTimelineSteps.toMessageReasonings()
        val sanitizedResult = ContextInjectionFilter.sanitizeParts(
            parts = rawCandidateParts,
            role = ContextInjectionFilter.SenderRole.ASSISTANT,
            streaming = streaming,
            reasoning = rawReasoning,
            timelineSteps = rawTimelineSteps,
        )
        val parts = sanitizedResult.parts
        val preservedRawParts = sanitizedResult.rawParts
        val timelineSteps = sanitizedResult.timelineSteps
        val reasoningList = sanitizedResult.reasoning
        val executionSteps = mergeDerivedExecutionSteps(timelineSteps, previous?.executionSteps.orEmpty())
        val hasAttachments = parts.any { it is MessagePart.Attachment }
        val hasSteps = executionSteps.isNotEmpty() || timelineSteps.any { it is ExecutionTimelineStep.ToolCallStep }
        val hasReasoning = reasoningList.isNotEmpty() || timelineSteps.any { it is ExecutionTimelineStep.ThinkingStep }
        val sanitizedText = parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value }
        if (ContextInjectionFilter.shouldDropMessage(
                text = sanitizedText,
                hasAttachments = hasAttachments,
                hasSteps = hasSteps,
                hasReasoning = hasReasoning,
                isMeta = false,
                sender = "assistant",
                visibility = null,
                messageType = null,
            )
        ) {
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
                    conversationId = ConversationId(sessionId),
                ))
            } else emptyList()
        }
        val nativeGeneration = previous?.generationId ?: explicitGeneration ?: if (source.historical) historicalTurnId ?: generationId else generationId
        val row = TimelineMessage(
            id = id,
            sender = "assistant",
            parts = parts,
            timestamp = effectiveTimestamp,
            state = if (streaming) "STREAMING" else "CONFIRMED",
            conversationId = ConversationId(sessionId),
            clientMessageId = previous?.clientMessageId,
            contentRevision = ++revision,
            generationId = nativeGeneration,
            executionSteps = executionSteps,
            localPlaceholder = id.startsWith("remote-stream:") || id.startsWith("remote-acp-stream:"),
            reasoning = reasoningList,
            rawParts = preservedRawParts,
            timelineSteps = timelineSteps,
            orderSequence = rowOrder,
        )
        rows[id] = row
        rowHistorical[id] = source.historical
        return listOf(VerifiedConversationEvent.TimelineUpsert(
            source.id("message:$id:$revision"),
            source.time,
            revision,
            row,
            if (streaming && !source.historical) StreamPresentationOrigin.LIVE else StreamPresentationOrigin.SNAPSHOT,
        ))
    }

    private data class ExtractedMessageContent(
        val parts: List<MessagePart>,
        val timelineSteps: List<ExecutionTimelineStep>,
        val updatedOwnerEvents: List<VerifiedConversationEvent>,
    )

    private fun extractMessageBlocks(
        blocks: List<JsonValue>,
        streaming: Boolean,
        mediaRevision: RemoteMediaRevision,
        rowId: String,
        rowOrder: Long,
        source: Source,
    ): ExtractedMessageContent {
        val parts = mutableListOf<MessagePart>()
        val steps = mutableListOf<ExecutionTimelineStep>()
        val ownerUpdates = mutableListOf<VerifiedConversationEvent>()
        var offset = 0
        blocks.forEachIndexed { index, block ->
            when (block.text("type")) {
                "text" -> {
                    val value = block.text("text").orEmpty()
                    parts += MessagePart.Text(value)
                    offset += value.length
                }
                "image", "file" -> {
                    val attachment = if (mediaReference != null) {
                        mediaReference.invoke(sessionId, block, mediaRevision)
                    } else {
                        val ref = block.text("id") ?: block.text("path") ?: block.text("url") ?: block.at("source")?.text("url")
                        ref?.let { MessagePart.Attachment(AttachmentDraftId(RemoteLocalIds.encode(it)), block.text("name").orEmpty(), block.text("mimeType").orEmpty()) }
                    }
                    if (attachment != null) parts += attachment
                }
                "reasoning", "thinking", "thought" -> {
                    val thoughtText = block.text("text")
                        ?: block.text("thinking")
                        ?: block.text("thought")
                        ?: block.text("reasoning")
                        ?: ""
                    steps += ExecutionTimelineStep.ThinkingStep(
                        stepId = block.text("id") ?: "reasoning:$index",
                        text = thoughtText,
                        bodyOffset = offset,
                        complete = !streaming || block.flag("complete") == true,
                        orderIndex = index,
                    )
                }
                in TOOL_USE_BLOCK_TYPES -> {
                    val parsed = parseToolBlockStep(
                        block = block,
                        fallbackId = "$rowId:tool:$index",
                        completed = isToolBlockCompleted(block),
                        sourceTime = source.time,
                        sourceMessageId = rowId,
                        orderSeq = rowOrder,
                    )
                    val merged = knownTools[parsed.stepId]?.let { mergeExecutionStep(it, parsed) } ?: parsed
                    knownTools[merged.stepId] = merged
                    toolOwnerMessageId[merged.stepId] = rowId
                    if (!rowId.startsWith("history-tool:")) {
                        val tempToolRowId = "history-tool:${merged.stepId}"
                        if (rows.remove(tempToolRowId) != null) {
                            rowHistorical.remove(tempToolRowId)
                            rowOrderSequences.remove(tempToolRowId)
                            ownerUpdates += VerifiedConversationEvent.TimelineTombstoned(
                                eventId = source.id("claim-tool-tombstone:$tempToolRowId:${++revision}"),
                                occurredAt = source.time,
                                messageId = tempToolRowId,
                                revision = revision,
                                conversationId = ConversationId(sessionId),
                            )
                        }
                    }
                    val existingIdx = steps.indexOfFirst { it is ExecutionTimelineStep.ToolCallStep && it.stepId == merged.stepId }
                    if (existingIdx >= 0) {
                        val old = steps[existingIdx] as ExecutionTimelineStep.ToolCallStep
                        steps[existingIdx] = old.copy(step = merged)
                    } else {
                        steps += ExecutionTimelineStep.ToolCallStep(
                            step = merged,
                            bodyOffset = offset,
                            orderIndex = index,
                        )
                    }
                }
                in TOOL_RESULT_BLOCK_TYPES -> {
                    val callId = extractToolCallId(block) ?: return@forEachIndexed
                    val resultOutput = extractToolOutput(block)
                    val isFailed = isToolResultFailed(block)
                    val existingIdx = steps.indexOfFirst { it is ExecutionTimelineStep.ToolCallStep && it.stepId == callId }
                    if (existingIdx >= 0) {
                        val old = steps[existingIdx] as ExecutionTimelineStep.ToolCallStep
                        val updated = old.step.copy(
                            output = resultOutput ?: old.step.output,
                            status = if (isFailed) NativeExecutionStatus.FAILED else NativeExecutionStatus.COMPLETED,
                            revision = ++revision,
                        )
                        knownTools[callId] = updated
                        steps[existingIdx] = old.copy(step = updated)
                    } else {
                        val updatedStep = (knownTools[callId] ?: NativeExecutionStep(
                            stepId = callId,
                            eventId = callId,
                            toolName = extractToolName(block, allowGenericFallback = true),
                            arguments = "",
                            occurredAt = source.time,
                            orderSequence = rowOrder,
                        )).let { prev ->
                            prev.copy(
                                output = resultOutput ?: prev.output,
                                status = if (isFailed) NativeExecutionStatus.FAILED else NativeExecutionStatus.COMPLETED,
                                revision = ++revision,
                            )
                        }
                        knownTools[callId] = updatedStep
                        applyToolUpdateToOwnerRow(updatedStep, source)?.let { ownerUpdates += it }
                    }
                }
            }
        }
        return ExtractedMessageContent(parts, steps, ownerUpdates)
    }

    private fun upsert(value: JsonValue, fallbackRole: String, streaming: Boolean, seq: Long?, source: Source,
        messageGeneration: String? = generationId, messageTimestamp: Long = source.time): List<VerifiedConversationEvent> {
        val message = value.at("message") ?: value
        val metadataObj = message.at("metadata") ?: value.at("metadata")
        // 显式保留协议层声明的发送者（含明确空字符串），并将 user/assistant 别名归一为标准角色，防止缺失发送者误默认归类为 assistant 或无法匹配本地原子
        val explicitSenderPresent = firstPresentText(
            message.text("sender"),
            message.text("role"),
            message.at("author")?.text("role"),
            message.at("author")?.text("sender"),
            value.text("sender"),
            value.text("role"),
            metadataObj?.text("role"),
            metadataObj?.text("sender"),
        )
        val role = ContextInjectionFilter.canonicalSender(explicitSenderPresent ?: fallbackRole)
        val id = message.text("id") ?: seq?.let { "event-message:$it" } ?: throw RemoteSessionException("REMOTE_MESSAGE_ID_MISSING")
        if (persistentMessageSequence && seq != null && rowSequences[id]?.let { it > seq } == true) return emptyList()
        if (source.historical && source.historyRead == null && rowHistorical[id] == false) return emptyList()
        if (seq != null) rowSequences[id] = seq

        val rowOrder = if (seq != null) {
            rowOrderSequences[id]?.takeIf { !source.historical }
                ?: allocateOrder(seq, historical = source.historical).also { rowOrderSequences[id] = it }
        } else {
            rowOrderSequences.getOrPut(id) { allocateOrder(null, historical = source.historical) }
        }

        val blocks = message.array("content").orEmpty()
        val visibility = firstNonBlankText(
            message.text("visibility"),
            message.text("displayVisibility"),
            value.text("visibility"),
            metadataObj?.text("visibility"),
        )
        val messageType = firstNonBlankText(
            message.text("messageType"),
            message.text("message_type"),
            message.text("kind"),
            message.text("type"),
            value.text("messageType"),
            value.text("message_type"),
            metadataObj?.text("messageType"),
            metadataObj?.text("message_type"),
            metadataObj?.text("kind"),
        )
        val extracted = extractMessageBlocks(
            blocks = blocks,
            streaming = streaming,
            mediaRevision = RemoteMediaRevision(id, seq, source.historical, source.historyRead),
            rowId = id,
            rowOrder = rowOrder,
            source = source,
        )
        val isMeta = hasInternalMetaFlag(message, value, metadataObj) ||
            ContextInjectionFilter.isNonConversationalMessage(
                sender = role,
                isMeta = false,
                visibility = visibility,
                messageType = messageType,
            )
        if (isMeta) {
            val hadRow = rows.containsKey(id)
            rows.remove(id)
            rowHistorical.remove(id)
            rowSequences.remove(id)
            return extracted.updatedOwnerEvents + if (hadRow) {
                listOf(VerifiedConversationEvent.TimelineTombstoned(
                    eventId = source.id("meta-tombstone:$id:${++revision}"),
                    occurredAt = source.time,
                    messageId = id,
                    revision = revision,
                    conversationId = ConversationId(sessionId)
                ))
            } else emptyList()
        }

        val rawCandidateParts = extracted.parts
            .ifEmpty {
                message.text("text")?.let { raw -> listOf(MessagePart.Text(raw)) }.orEmpty()
            }
        val rawTimelineSteps = extracted.timelineSteps
        val rawReasoning = rawTimelineSteps.toMessageReasonings()
        val sanitizedResult = ContextInjectionFilter.sanitizeParts(
            parts = rawCandidateParts,
            role = ContextInjectionFilter.contentSanitizationRole(role),
            streaming = streaming,
            reasoning = rawReasoning,
            timelineSteps = rawTimelineSteps,
        )
        val parts = sanitizedResult.parts
        val preservedRawParts = sanitizedResult.rawParts
        val previous = rows[id]
        val timelineSteps = sanitizedResult.timelineSteps
        val reasoningList = sanitizedResult.reasoning
        val executionSteps = mergeDerivedExecutionSteps(timelineSteps, previous?.executionSteps.orEmpty())
        val hasAttachments = parts.any { it is MessagePart.Attachment }
        val hasSteps = executionSteps.isNotEmpty() || timelineSteps.any { it is ExecutionTimelineStep.ToolCallStep }
        val hasReasoning = reasoningList.isNotEmpty() || timelineSteps.any { it is ExecutionTimelineStep.ThinkingStep }
        val sanitizedText = parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value }

        if (ContextInjectionFilter.shouldDropMessage(
                text = sanitizedText,
                hasAttachments = hasAttachments,
                hasSteps = hasSteps,
                hasReasoning = hasReasoning,
                isMeta = false,
                sender = role,
                visibility = visibility,
                messageType = messageType,
            )) {
            val hadRow = rows.containsKey(id)
            rows.remove(id)
            rowHistorical.remove(id)
            rowSequences.remove(id)
            return extracted.updatedOwnerEvents + if (hadRow) {
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
        val effectiveTimestamp = previous?.timestamp?.takeIf { it > 0L } ?: messageTimestamp
        val row = TimelineMessage(id, role, parts, effectiveTimestamp, if (streaming) "STREAMING" else "CONFIRMED", ConversationId(sessionId),
            clientMessageId = clientId, contentRevision = ++revision, generationId = nativeGeneration,
            executionSteps = executionSteps,
            localPlaceholder = id.startsWith("remote-stream:") || id.startsWith("remote-acp-stream:"),
            reasoning = reasoningList,
            visibility = visibility,
            messageType = messageType,
            rawParts = preservedRawParts,
            timelineSteps = timelineSteps,
            orderSequence = rowOrder)
        rows[id] = row
        rowHistorical[id] = source.historical
        return extracted.updatedOwnerEvents + listOf(VerifiedConversationEvent.TimelineUpsert(source.id("message:$id:$revision"), source.time, revision, row,
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

    // extractToolCallId 已提升为同包顶层 internal 辅助函数，供 RemoteConversationRepository 复用。

    private fun extractToolName(value: JsonValue, allowGenericFallback: Boolean = true): String {
        val primary = extractToolString(value, "toolName", "name", "tool", "title", "function.name")
            ?.takeIf { it.isNotBlank() }
        if (primary != null) return primary
        val rawType = extractToolString(value, "kind", "type")?.takeIf { it.isNotBlank() }
        if (rawType != null && rawType !in GENERIC_TOOL_WRAPPER_TYPES) return rawType
        return if (allowGenericFallback) "tool" else ""
    }

    private fun extractToolOutput(block: JsonValue): String? =
        extractToolString(block, "rawOutput", "aggregatedOutput", "output", "result", "content", "response", "error")

    private fun isToolResultFailed(block: JsonValue): Boolean =
        block.flag("isError") == true || block.flag("is_error") == true ||
            block.text("status")?.lowercase() in setOf("failed", "error")

    private fun isToolBlockCompleted(block: JsonValue): Boolean {
        val status = block.text("status")?.lowercase()
        if (status in setOf("completed", "done", "succeeded", "failed", "error")) return true
        if (isToolResultFailed(block)) return true
        return extractToolString(block, "rawOutput", "aggregatedOutput", "output", "result", "response") != null
    }

    private fun parseToolBlockStep(
        block: JsonValue,
        fallbackId: String,
        completed: Boolean,
        sourceTime: Long,
        sourceMessageId: String?,
        orderSeq: Long,
    ): NativeExecutionStep {
        val id = extractToolCallId(block) ?: fallbackId
        val previous = knownTools[id]
        val extractedName = extractToolName(block, allowGenericFallback = false)
        val toolName = when {
            extractedName.isNotBlank() -> extractedName
            previous != null && previous.toolName.isNotBlank() -> previous.toolName
            else -> "tool"
        }
        val rawArgs = extractToolString(
            block,
            "arguments",
            "rawInput",
            "input",
            "args",
            "command",
            "parameters",
            "params",
            "function.arguments",
            "changes",
        )
        val arguments = rawArgs?.takeIf { it.isNotEmpty() } ?: previous?.arguments.orEmpty()
        val output = extractToolOutput(block) ?: previous?.output
        val rawStatus = block.text("status")?.lowercase()
        val isFailed = isToolResultFailed(block)
        val status = when {
            isFailed -> NativeExecutionStatus.FAILED
            completed || rawStatus in setOf("completed", "done", "succeeded") -> NativeExecutionStatus.COMPLETED
            previous?.status == NativeExecutionStatus.COMPLETED || previous?.status == NativeExecutionStatus.FAILED -> previous.status
            else -> NativeExecutionStatus.RUNNING
        }
        val step = NativeExecutionStep(
            stepId = id,
            eventId = fallbackId,
            toolName = toolName,
            arguments = arguments,
            output = output,
            status = status,
            revision = ++revision,
            sourceMessageId = NativeExecutionStep.resolveSourceMessageId(previous?.sourceMessageId, sourceMessageId),
            occurredAt = previous?.occurredAt?.takeIf { it > 0L } ?: sourceTime,
            orderSequence = previous?.orderSequence ?: orderSeq,
        )
        return previous?.let { mergeExecutionStep(it, step) } ?: step
    }

    private fun applyToolResultBlockToContent(block: JsonValue, content: AssistantContent, sourceTime: Long) {
        val callId = extractToolCallId(block) ?: return
        val previous = knownTools[callId] ?: return
        val output = extractToolOutput(block)
        val isFailed = isToolResultFailed(block)
        val updated = previous.copy(
            output = output ?: previous.output,
            status = if (isFailed) NativeExecutionStatus.FAILED else NativeExecutionStatus.COMPLETED,
            revision = ++revision,
        )
        val merged = content.upsertTool(updated)
        knownTools[callId] = merged
    }

    private fun applyToolUpdateToOwnerRow(step: NativeExecutionStep, source: Source): VerifiedConversationEvent.TimelineUpsert? {
        val ownerId = toolOwnerMessageId[step.stepId] ?: return null
        // 同步回写活跃流式缓冲，防止后续增量分片覆盖已完成/失败的工具结果。
        liveContent[ownerId]?.upsertTool(step)
        if (ownerId == acpMessageId) acpContent.upsertTool(step)
        val ownerRow = rows[ownerId] ?: return null
        var changed = false
        val updatedTimelineSteps = ownerRow.timelineSteps.map { item ->
            if (item is ExecutionTimelineStep.ToolCallStep && item.stepId == step.stepId) {
                changed = true
                item.copy(step = mergeExecutionStep(item.step, step))
            } else item
        }
        val updatedExecSteps = if (ownerRow.executionSteps.any { it.stepId == step.stepId }) {
            changed = true
            ownerRow.executionSteps.map { old -> if (old.stepId == step.stepId) mergeExecutionStep(old, step) else old }
        } else {
            mergeDerivedExecutionSteps(updatedTimelineSteps, ownerRow.executionSteps)
        }
        if (!changed) return null
        val updatedRow = ownerRow.copy(
            contentRevision = ++revision,
            timelineSteps = updatedTimelineSteps,
            executionSteps = updatedExecSteps,
        )
        rows[ownerId] = updatedRow
        return VerifiedConversationEvent.TimelineUpsert(
            eventId = source.id("tool-owner-update:$ownerId:${step.stepId}:$revision"),
            occurredAt = source.time,
            revision = revision,
            message = updatedRow,
            presentationOrigin = StreamPresentationOrigin.SNAPSHOT,
        )
    }

    private fun settleHistoricalRunningTools(
        turnId: String,
        source: Source,
    ): List<VerifiedConversationEvent.TimelineUpsert> {
        val settledEvents = mutableListOf<VerifiedConversationEvent.TimelineUpsert>()
        rows.entries.toList().forEach { (id, row) ->
            if (row.generationId != turnId) return@forEach
            val hasRunning = row.executionSteps.any { it.status == NativeExecutionStatus.RUNNING } ||
                row.timelineSteps.any { it is ExecutionTimelineStep.ToolCallStep && it.step.status == NativeExecutionStatus.RUNNING }
            if (!hasRunning) return@forEach
            val settledTimeline = row.timelineSteps.map { item ->
                if (item is ExecutionTimelineStep.ToolCallStep && item.step.status == NativeExecutionStatus.RUNNING) {
                    val settledStep = item.step.copy(status = NativeExecutionStatus.UNKNOWN, revision = ++revision)
                    knownTools[settledStep.stepId] = settledStep
                    item.copy(step = settledStep)
                } else item
            }
            val settledExec = row.executionSteps.map { step ->
                if (step.status == NativeExecutionStatus.RUNNING) {
                    val settledStep = knownTools[step.stepId]?.takeIf { it.status == NativeExecutionStatus.UNKNOWN }
                        ?: step.copy(status = NativeExecutionStatus.UNKNOWN, revision = ++revision).also { knownTools[it.stepId] = it }
                    settledStep
                } else step
            }
            val updatedRow = row.copy(
                contentRevision = ++revision,
                timelineSteps = settledTimeline,
                executionSteps = settledExec,
            )
            rows[id] = updatedRow
            settledEvents += VerifiedConversationEvent.TimelineUpsert(
                eventId = source.id("settle-history-tools:$id:$revision"),
                occurredAt = source.time,
                revision = revision,
                message = updatedRow,
                presentationOrigin = StreamPresentationOrigin.SNAPSHOT,
            )
        }
        return settledEvents
    }

    private fun execution(
        value: JsonValue,
        eventId: String,
        source: Source,
        completed: Boolean,
        explicitSeq: Long? = null,
        explicitTurn: String? = null,
    ): List<VerifiedConversationEvent> {
        val id = extractToolCallId(value) ?: eventId
        val previous = knownTools[id]
        val orderSeq = previous?.orderSequence ?: allocateOrder(explicitSeq, historical = source.historical)
        val parsed = parseToolBlockStep(
            block = value,
            fallbackId = eventId,
            completed = completed,
            sourceTime = source.time,
            sourceMessageId = previous?.sourceMessageId,
            orderSeq = orderSeq,
        )
        val merged = previous?.let { mergeExecutionStep(it, parsed) } ?: parsed
        knownTools[id] = merged
        val events = mutableListOf<VerifiedConversationEvent>()
        val ownerUpdate = applyToolUpdateToOwnerRow(merged, source)
        if (ownerUpdate != null) {
            events += ownerUpdate
        } else if (source.historical) {
            val rowId = "history-tool:$id"
            val existingRow = rows[rowId]
            val rowOrder = existingRow?.orderSequence ?: merged.orderSequence ?: orderSeq
            val rowTime = existingRow?.timestamp?.takeIf { it > 0L } ?: merged.occurredAt ?: source.time
            val turnGen = existingRow?.generationId ?: explicitTurn ?: historicalTurnId ?: "history-turn:$sessionId"
            val rowStep = merged.copy(sourceMessageId = rowId, orderSequence = rowOrder, occurredAt = rowTime)
            knownTools[id] = rowStep
            val row = TimelineMessage(
                id = rowId,
                sender = "assistant",
                parts = emptyList(),
                timestamp = rowTime,
                state = "CONFIRMED",
                conversationId = ConversationId(sessionId),
                contentRevision = ++revision,
                generationId = turnGen,
                executionSteps = listOf(rowStep),
                timelineSteps = listOf(ExecutionTimelineStep.ToolCallStep(rowStep, bodyOffset = 0, orderIndex = 0)),
                orderSequence = rowOrder,
            )
            rows[rowId] = row
            rowHistorical[rowId] = true
            rowOrderSequences[rowId] = rowOrder
            events += VerifiedConversationEvent.TimelineUpsert(
                eventId = source.id("history-tool:$id:$revision"),
                occurredAt = source.time,
                revision = revision,
                message = row,
                presentationOrigin = StreamPresentationOrigin.SNAPSHOT,
            )
        }
        val eventGeneration = explicitTurn ?: (if (source.historical) historicalTurnId else generationId) ?: "remote-execution:$sessionId"
        events += VerifiedConversationEvent.ExecutionStepsUpdated(
            source.id(eventId), source.time, ConversationId(sessionId),
            eventGeneration, ++revision, listOf(knownTools.getValue(id))
        )
        return events
    }

    private fun firstPresentText(vararg candidates: String?): String? {
        for (candidate in candidates) {
            if (candidate != null) return candidate.trim()
        }
        return null
    }

    private fun firstNonBlankText(vararg candidates: String?): String? {
        for (candidate in candidates) {
            val trimmed = candidate?.trim()
            if (!trimmed.isNullOrEmpty()) return trimmed
        }
        return null
    }

    private fun hasInternalMetaFlag(message: JsonValue, value: JsonValue, metadataObj: JsonValue?): Boolean {
        val keys = listOf(
            "isMeta",
            "is_meta",
            "hidden",
            "isHidden",
            "is_hidden",
            "internal",
            "isInternal",
            "is_internal",
            "ephemeral",
            "isEphemeral",
            "is_ephemeral",
            "synthetic",
            "isSynthetic",
            "is_synthetic",
            "injected",
            "isInjected",
            "is_injected",
            "systemOnly",
            "system_only",
            "contextOnly",
            "context_only",
        )
        return keys.any { key ->
            message.flag(key) == true || value.flag(key) == true || metadataObj?.flag(key) == true
        }
    }

}

private val TOOL_USE_BLOCK_TYPES = setOf(
    "tool_use",
    "tool_call",
    "toolCall",
    "tool-call",
    "function_call",
    "functionCall",
)

private val TOOL_RESULT_BLOCK_TYPES = setOf(
    "tool_result",
    "toolResult",
    "tool-result",
    "function_response",
    "functionResponse",
)

private val GENERIC_TOOL_WRAPPER_TYPES = setOf(
    "tool_use",
    "tool_call",
    "toolCall",
    "tool-call",
    "tool-call-delta",
    "tool_call_delta",
    "block-start",
    "block_start",
    "tool_result",
    "toolResult",
    "tool-result",
    "function",
    "function_call",
    "functionCall",
    "function_response",
    "functionResponse",
)

internal fun extractToolCallId(value: JsonValue): String? =
    value.text("callId") ?: value.text("toolCallId") ?: value.text("tool_call_id")
        ?: value.text("tool_use_id") ?: value.text("toolUseId") ?: value.text("stepId") ?: value.text("id")

private fun mergeExecutionStep(existing: NativeExecutionStep, incoming: NativeExecutionStep): NativeExecutionStep =
    existing.mergeWith(incoming)

private fun mergeDerivedExecutionSteps(
    timelineSteps: List<ExecutionTimelineStep>,
    existingSteps: List<NativeExecutionStep>,
): List<NativeExecutionStep> {
    val merged = linkedMapOf<String, NativeExecutionStep>()
    timelineSteps.forEach { item ->
        if (item is ExecutionTimelineStep.ToolCallStep) {
            merged[item.step.stepId] = merged[item.step.stepId]?.let { mergeExecutionStep(it, item.step) } ?: item.step
        }
    }
    existingSteps.forEach { old ->
        if (old.stepId !in merged) {
            merged[old.stepId] = old
        }
    }
    return merged.values.toList()
}
