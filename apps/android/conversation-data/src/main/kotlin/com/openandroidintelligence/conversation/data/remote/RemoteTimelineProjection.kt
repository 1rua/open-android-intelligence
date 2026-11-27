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
            val normalizedId = ToolCallPairing.normalizeCallId(callId)
            if (normalizedId != null) {
                (blocks.values.firstOrNull { it is ContentBlock.Tool && it.step.stepId == normalizedId } as? ContentBlock.Tool)
                    ?.let { return it }
            }
            val byKey = blocks[key] as? ContentBlock.Tool ?: return null
            if (normalizedId == null ||
                byKey.step.stepId == normalizedId ||
                byKey.step.stepId == key ||
                byKey.step.stepId.endsWith(":tool:$key")
            ) {
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
        ): Pair<String?, NativeExecutionStep> {
            val normalizedCallId = ToolCallPairing.normalizeCallId(callId)
            val existing = findToolBlock(key, normalizedCallId)
            if (existing != null) {
                val previousStepId = existing.step.stepId
                val mergedArguments = if (existing.step.arguments == "{}" && argumentsDelta.isNotEmpty()) {
                    argumentsDelta
                } else {
                    existing.step.arguments + argumentsDelta
                }
                val updated = existing.step.copy(
                    stepId = normalizedCallId ?: existing.step.stepId,
                    toolName = ToolCallPairing.chooseToolName(existing.step.toolName, name),
                    arguments = mergedArguments,
                    revision = maxOf(existing.step.revision, revision),
                )
                existing.step = updated
                val reboundFrom = previousStepId.takeIf { it != updated.stepId }
                return reboundFrom to updated
            }
            finishTrailingReasoning()
            val stepId = normalizedCallId ?: key
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
            return null to created
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
    private data class PendingToolResult(
        val output: String?,
        val failed: Boolean,
        val status: NativeExecutionStatus,
        val occurredAt: Long,
    )
    private val knownTools = mutableMapOf<String, NativeExecutionStep>()
    private val toolOwnerMessageId = mutableMapOf<String, String>()
    private val toolGenerationByCallId = mutableMapOf<String, String>()
    /** 乱序到达的裸工具结果缓冲（以归一化 callId 为唯一键）；待对应调用到达时自动合并，绝不凭空造孤立 `[tool]` 卡。 */
    private val pendingResults = mutableMapOf<String, PendingToolResult>()
    private var nextOrderSequence = 0L

    /** 查询工具调用当前绑定的宿主消息行 ID，供历史分页收集归属行。 */
    fun ownerMessageIdForTool(callId: String): String? =
        ToolCallPairing.normalizeCallId(callId)?.let { toolOwnerMessageId[it] }

    private fun bufferPendingToolResult(
        callId: String,
        output: String?,
        failed: Boolean,
        status: NativeExecutionStatus,
        occurredAt: Long,
    ) {
        val canonical = ToolCallPairing.normalizeCallId(callId) ?: return
        val existing = pendingResults[canonical]
        val mergedStatus = when {
            failed || existing?.failed == true -> NativeExecutionStatus.FAILED
            ToolCallPairing.isTerminalStatus(status) -> status
            existing != null && ToolCallPairing.isTerminalStatus(existing.status) -> existing.status
            else -> status
        }
        pendingResults[canonical] = PendingToolResult(
            output = ToolCallPairing.chooseOutput(existing?.output, output),
            failed = failed || existing?.failed == true,
            status = mergedStatus,
            occurredAt = existing?.occurredAt?.takeIf { it > 0L } ?: occurredAt,
        )
    }

    private fun applyBufferedResultToCall(step: NativeExecutionStep): NativeExecutionStep {
        val canonical = ToolCallPairing.normalizeCallId(step.stepId) ?: return step
        val pending = pendingResults.remove(canonical) ?: return step
        val incomingStatus = if (pending.failed) NativeExecutionStatus.FAILED else pending.status
        return mergeExecutionStep(
            step,
            step.copy(
                output = pending.output,
                status = incomingStatus,
                revision = ++revision,
            ),
        )
    }
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
                return execution(data, id, source, completed = false, explicitSeq = event.number("seq"), explicitTurn = turn, isBareResult = false)
            }
            "tool/result" -> return execution(data, id, source, completed = true, explicitSeq = event.number("seq"), explicitTurn = turn, isBareResult = true)
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
        val snapGen = entry.nonBlankText("turnId") ?: entry.number("turn")?.toString() ?: generationId
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
                    val step = applyBufferedResultToCall(
                        parseToolBlockStep(
                            block = block,
                            fallbackId = "$rowId:tool:$index",
                            completed = isToolBlockCompleted(block),
                            sourceTime = source.time,
                            sourceMessageId = rowId,
                            orderSeq = allocateOrder(null, historical = source.historical),
                        ),
                    )
                    val merged = content.upsertTool(step, key = index.toString())
                    knownTools[merged.stepId] = merged
                    toolOwnerMessageId[merged.stepId] = rowId
                    if (snapGen != null) toolGenerationByCallId.putIfAbsent(merged.stepId, snapGen)
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
        val attempt = frame.nonBlankText("attemptId") ?: return emptyList()
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
                val activeGen = frame.nonBlankText("turnId") ?: frame.number("turn")?.toString() ?: generationId
                when (chunk.text("type")) {
                    "block-start" -> {
                        val blockType = chunk.text("blockType") ?: "text"
                        if (blockType in TOOL_USE_BLOCK_TYPES) {
                            val callId = extractToolCallId(chunk, allowRecordIdFallback = true) ?: "$rowId:tool:$index"
                            val step = applyBufferedResultToCall(
                                parseToolBlockStep(
                                    block = chunk,
                                    fallbackId = callId,
                                    completed = false,
                                    sourceTime = source.time,
                                    sourceMessageId = rowId,
                                    orderSeq = knownTools[callId]?.orderSequence ?: allocateOrder(null, historical = source.historical),
                                ),
                            )
                            val merged = content.upsertTool(step, key = index)
                            knownTools[merged.stepId] = merged
                            toolOwnerMessageId[merged.stepId] = rowId
                            if (activeGen != null) toolGenerationByCallId.putIfAbsent(merged.stepId, activeGen)
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
                        val callId = extractToolCallId(chunk, allowRecordIdFallback = true)
                        val toolName = extractToolName(chunk, allowGenericFallback = false)
                        val delta = chunk.text("argumentsDelta") ?: chunk.text("delta") ?: chunk.text("text") ?: ""
                        val (reboundFrom, updatedRaw) = content.appendToolDelta(
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
                        if (reboundFrom != null) {
                            knownTools.remove(reboundFrom)
                            if (toolOwnerMessageId[reboundFrom] == rowId) toolOwnerMessageId.remove(reboundFrom)
                            toolGenerationByCallId.remove(reboundFrom)?.let { prevGen ->
                                toolGenerationByCallId.putIfAbsent(updatedRaw.stepId, prevGen)
                            }
                        }
                        val withPending = applyBufferedResultToCall(updatedRaw)
                        val updated = if (withPending !== updatedRaw) content.upsertTool(withPending, key = index) else updatedRaw
                        knownTools[updated.stepId] = updated
                        toolOwnerMessageId[updated.stepId] = rowId
                        if (activeGen != null) toolGenerationByCallId.putIfAbsent(updated.stepId, activeGen)
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
                // 清理指向已墓碑化流式草稿行的 owner 映射，避免后续 tool/result 命中已删除行而落入孤立分支。
                toolOwnerMessageId.entries.removeAll { it.value == rowId }
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
            "item/commandExecution/outputDelta", "item/fileChange/outputDelta", "item/mcpToolCall/progress" -> {
                val delta = params.text("delta") ?: params.text("message") ?: params.text("text") ?: ""
                if (delta.isEmpty()) return emptyList()
                val callId = extractToolCallId(params, allowRecordIdFallback = false)
                    ?: ToolCallPairing.normalizeCallId(params.nonBlankText("itemId"))
                    ?: ToolCallPairing.normalizeCallId(params.nonBlankText("item_id"))
                    ?: return emptyList()
                val turnGen = params.nonBlankText("turnId") ?: if (source.historical) historicalTurnId else generationId
                val existing = knownTools[callId]
                if (existing == null) {
                    val accumulated = (pendingResults[callId]?.output.orEmpty() + delta)
                    bufferPendingToolResult(
                        callId = callId,
                        output = accumulated,
                        failed = false,
                        status = NativeExecutionStatus.RUNNING,
                        occurredAt = source.time,
                    )
                    return emptyList()
                }
                val updated = existing.copy(
                    output = existing.output.orEmpty() + delta,
                    revision = ++revision,
                )
                knownTools[callId] = updated
                val ownerUpdate = applyToolUpdateToOwnerRow(updated, source)
                if (ownerUpdate != null) return listOf(ownerUpdate)
                if (source.historical) return emptyList()
                val eventGeneration = toolGenerationByCallId[callId] ?: turnGen ?: generationId ?: "remote-execution:$sessionId"
                return listOf(
                    VerifiedConversationEvent.ExecutionStepsUpdated(
                        eventId = source.id("tool-delta:$callId:$revision"),
                        occurredAt = source.time,
                        conversationId = ConversationId(sessionId),
                        generationId = eventGeneration,
                        executionRevision = revision,
                        steps = listOf(updated),
                    ),
                )
            }
            "item/started", "item/completed" -> {
                val item = params.at("item") ?: return emptyList()
                val turnGen = params.nonBlankText("turnId") ?: item.nonBlankText("turnId") ?: if (source.historical) historicalTurnId else generationId
                val itemType = item.text("type")
                if (ContextInjectionFilter.isHiddenProtocolItemType(itemType)) return emptyList()
                return when (itemType) {
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
                    "functionCallOutput", "function_call_output" -> execution(
                        value = item,
                        eventId = item.nonBlankText("id") ?: "tool:${++liveSequence}",
                        source = source,
                        completed = true,
                        explicitTurn = turnGen,
                        isBareResult = true,
                    )
                    else -> execution(
                        value = item,
                        eventId = item.nonBlankText("id") ?: "tool:${++liveSequence}",
                        source = source,
                        completed = method == "item/completed",
                        explicitTurn = turnGen,
                        isBareResult = false,
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
                        val updateKind = update.text("sessionUpdate") ?: update.text("type")
                        val isUpdate = updateKind == "tool_call_update"
                        val extractedId = extractToolCallId(update, allowRecordIdFallback = true)
                        val statusText = update.text("status")?.lowercase()
                        val completed = isToolBlockCompleted(update) || statusText in TERMINAL_TOOL_STATUSES
                        val activeGen = if (source.historical) historicalTurnId ?: generationId else generationId
                        if (isUpdate) {
                            val canonicalId = extractedId ?: return emptyList()
                            val existing = knownTools[canonicalId]
                            if (existing != null) {
                                val updated = parseToolBlockStep(
                                    block = update,
                                    fallbackId = canonicalId,
                                    completed = completed,
                                    sourceTime = source.time,
                                    sourceMessageId = existing.sourceMessageId,
                                    orderSeq = existing.orderSequence ?: allocateOrder(null, historical = source.historical),
                                )
                                knownTools[canonicalId] = updated
                                val ownerId = toolOwnerMessageId[canonicalId]
                                if (ownerId != null && ownerId != acpMessageId) {
                                    return listOfNotNull(applyToolUpdateToOwnerRow(updated, source))
                                }
                                val msgId = ownerId ?: ensureAcpAssistantMessage(source)
                                val merged = acpContent.upsertTool(updated)
                                knownTools[merged.stepId] = merged
                                toolOwnerMessageId[merged.stepId] = msgId
                                if (activeGen != null) toolGenerationByCallId.putIfAbsent(merged.stepId, activeGen)
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
                            val explicitName = extractToolName(update, allowGenericFallback = false)
                            if (ToolCallPairing.isPlaceholderToolName(explicitName)) {
                                // 尚未收到对应 tool_call 且缺少真实工具定义的裸 tool_call_update：先按 ID 缓冲，绝不造孤立 [tool] 卡。
                                val failed = isToolResultFailed(update)
                                val bufferedStatus = when {
                                    failed -> NativeExecutionStatus.FAILED
                                    completed -> NativeExecutionStatus.COMPLETED
                                    else -> NativeExecutionStatus.RUNNING
                                }
                                bufferPendingToolResult(
                                    callId = canonicalId,
                                    output = extractToolOutput(update),
                                    failed = failed,
                                    status = bufferedStatus,
                                    occurredAt = source.time,
                                )
                                return emptyList()
                            }
                        }
                        val msgId = ensureAcpAssistantMessage(source)
                        val eventId = extractedId ?: "tool:${++liveSequence}"
                        val rawStep = applyBufferedResultToCall(
                            parseToolBlockStep(
                                block = update,
                                fallbackId = eventId,
                                completed = completed,
                                sourceTime = source.time,
                                sourceMessageId = msgId,
                                orderSeq = knownTools[eventId]?.orderSequence ?: allocateOrder(null, historical = source.historical),
                            ),
                        )
                        val merged = acpContent.upsertTool(rawStep)
                        knownTools[merged.stepId] = merged
                        toolOwnerMessageId[merged.stepId] = msgId
                        if (activeGen != null) toolGenerationByCallId.putIfAbsent(merged.stepId, activeGen)
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

    private fun isAcpToolCatchUpFrame(frame: JsonValue): Boolean {
        if (frame.text("method") != "session/update") return false
        val params = frame.at("params") ?: return false
        if (params.text("threadId")?.let { it != nativeSessionId } == true || params.text("sessionId")?.let { it != nativeSessionId } == true) return false
        val update = params.at("update") ?: params
        val kind = update.text("sessionUpdate") ?: update.text("type")
        if (kind == "tool_call_update") return true
        if (kind == "tool_call") {
            val callId = extractToolCallId(update, allowRecordIdFallback = true)
            return callId == null || callId !in knownTools || isToolBlockCompleted(update)
        }
        return false
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
            } else if (recoverOtherContent || isAcpToolCatchUpFrame(frame)) {
                // 即使本轮已收到部分正文/工具开始帧，仍须回放补帧中的 tool_call_update 与终态工具结果，防止误判为未返回结果。
                accept(frame, source.historical, source.time)
            } else emptyList()
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
        extraToolCalls: List<JsonValue>,
        streaming: Boolean,
        mediaRevision: RemoteMediaRevision,
        rowId: String,
        rowOrder: Long,
        rowGeneration: String?,
        source: Source,
    ): ExtractedMessageContent {
        val parts = mutableListOf<MessagePart>()
        val steps = mutableListOf<ExecutionTimelineStep>()
        val ownerUpdates = mutableListOf<VerifiedConversationEvent>()
        var offset = 0

        fun recordToolUseStep(block: JsonValue, fallbackId: String, orderIndex: Int) {
            val parsed = applyBufferedResultToCall(
                parseToolBlockStep(
                    block = block,
                    fallbackId = fallbackId,
                    completed = isToolBlockCompleted(block),
                    sourceTime = source.time,
                    sourceMessageId = rowId,
                    orderSeq = rowOrder,
                ),
            )
            val merged = knownTools[parsed.stepId]?.let { mergeExecutionStep(it, parsed) } ?: parsed
            knownTools[merged.stepId] = merged
            toolOwnerMessageId[merged.stepId] = rowId
            if (rowGeneration != null) toolGenerationByCallId[merged.stepId] = rowGeneration
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
                    orderIndex = orderIndex,
                )
            }
        }

        blocks.forEachIndexed { index, block ->
            if (block is JsonValue.JString) {
                parts += MessagePart.Text(block.value)
                offset += block.value.length
                return@forEachIndexed
            }
            // 协议块级内部元数据/隐藏标记/非展示块类型直接跳过，防止混入用户或助手正文。
            if (hasInternalMetaFlag(block, block, block.at("metadata")) ||
                ContextInjectionFilter.isHiddenVisibility(block.text("visibility")) ||
                ContextInjectionFilter.isHiddenContentBlockType(block.text("type"))
            ) {
                return@forEachIndexed
            }
            when (block.text("type")) {
                "text", "input_text", "output_text" -> {
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
                    recordToolUseStep(block, fallbackId = "$rowId:tool:$index", orderIndex = index)
                }
                in TOOL_RESULT_BLOCK_TYPES -> {
                    val callId = extractToolCallId(block, allowRecordIdFallback = false) ?: return@forEachIndexed
                    val resultOutput = extractToolOutput(block, includeDataField = true)
                    val isFailed = isToolResultFailed(block)
                    val resultStatus = if (isFailed) NativeExecutionStatus.FAILED else NativeExecutionStatus.COMPLETED
                    val existingIdx = steps.indexOfFirst { it is ExecutionTimelineStep.ToolCallStep && it.stepId == callId }
                    if (existingIdx >= 0) {
                        val old = steps[existingIdx] as ExecutionTimelineStep.ToolCallStep
                        val updated = mergeExecutionStep(
                            old.step,
                            old.step.copy(
                                output = resultOutput,
                                status = resultStatus,
                                revision = ++revision,
                            ),
                        )
                        knownTools[callId] = updated
                        steps[existingIdx] = old.copy(step = updated)
                    } else {
                        val previous = knownTools[callId]
                        if (previous != null) {
                            val updatedStep = mergeExecutionStep(
                                previous,
                                previous.copy(
                                    output = resultOutput,
                                    status = resultStatus,
                                    revision = ++revision,
                                ),
                            )
                            knownTools[callId] = updatedStep
                            applyToolUpdateToOwnerRow(updatedStep, source)?.let { ownerUpdates += it }
                        } else {
                            // 乱序/孤立 tool_result 块先按 callId 缓冲，等待对应调用到达再合并，绝不凭空造 [tool] 卡片。
                            bufferPendingToolResult(
                                callId = callId,
                                output = resultOutput,
                                failed = isFailed,
                                status = resultStatus,
                                occurredAt = source.time,
                            )
                        }
                    }
                }
            }
        }
        // 兼容 OpenAI / Hermes 风格顶层 `tool_calls` 数组，将其投影为同宿主消息的有序工具调用步骤。
        extraToolCalls.forEachIndexed { tcIndex, tcBlock ->
            recordToolUseStep(
                block = tcBlock,
                fallbackId = "$rowId:tool_call:$tcIndex",
                orderIndex = blocks.size + tcIndex,
            )
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
        val id = message.nonBlankText("id") ?: seq?.let { "event-message:$it" } ?: throw RemoteSessionException("REMOTE_MESSAGE_ID_MISSING")
        if (persistentMessageSequence && seq != null && rowSequences[id]?.let { it > seq } == true) return emptyList()
        if (source.historical && source.historyRead == null && rowHistorical[id] == false) return emptyList()
        if (seq != null) rowSequences[id] = seq

        val rowOrder = if (seq != null) {
            rowOrderSequences[id]?.takeIf { !source.historical }
                ?: allocateOrder(seq, historical = source.historical).also { rowOrderSequences[id] = it }
        } else {
            rowOrderSequences.getOrPut(id) { allocateOrder(null, historical = source.historical) }
        }

        val blocks = message.array("content") ?: message.array("input").orEmpty()
        val extraToolCalls = message.array("tool_calls") ?: message.array("toolCalls").orEmpty()
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
        val sourceKind = firstNonBlankText(
            message.at("source")?.text("kind"),
            message.at("source")?.text("type"),
            value.at("source")?.text("kind"),
            value.at("source")?.text("type"),
            message.text("origin"),
            value.text("origin"),
            metadataObj?.text("sourceKind"),
            metadataObj?.text("source_kind"),
            metadataObj?.text("origin"),
        )
        val extracted = extractMessageBlocks(
            blocks = blocks,
            extraToolCalls = extraToolCalls,
            streaming = streaming,
            mediaRevision = RemoteMediaRevision(id, seq, source.historical, source.historyRead),
            rowId = id,
            rowOrder = rowOrder,
            rowGeneration = messageGeneration,
            source = source,
        )
        // OpenAI / Hermes 风格的 `role="tool"` / `tool_result` 顶层消息：在被内部角色过滤丢弃前，先按 `tool_call_id` 配对回写原调用卡。
        val topLevelToolUpdates = mutableListOf<VerifiedConversationEvent>()
        val isToolResultMessage = role.equals("tool", ignoreCase = true) ||
            role.equals("tool_result", ignoreCase = true) ||
            messageType?.lowercase() in setOf("tool_result", "toolresult", "function_result", "function_response")
        if (isToolResultMessage) {
            val topCallId = extractToolCallId(message, allowRecordIdFallback = false)
                ?: extractToolCallId(value, allowRecordIdFallback = false)
            if (topCallId != null) {
                val topOutput = extractToolOutput(message, includeDataField = true)
                    ?: extractToolOutput(value, includeDataField = false)
                    ?: extracted.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value }.takeIf { it.isNotEmpty() }
                val isFailed = isToolResultFailed(message) || isToolResultFailed(value)
                val resultStatus = if (isFailed) NativeExecutionStatus.FAILED else NativeExecutionStatus.COMPLETED
                val existingTool = knownTools[topCallId]
                if (existingTool != null) {
                    val updated = mergeExecutionStep(
                        existingTool,
                        existingTool.copy(
                            output = topOutput,
                            status = resultStatus,
                            revision = ++revision,
                        ),
                    )
                    knownTools[topCallId] = updated
                    applyToolUpdateToOwnerRow(updated, source)?.let { topLevelToolUpdates += it }
                } else {
                    bufferPendingToolResult(
                        callId = topCallId,
                        output = topOutput,
                        failed = isFailed,
                        status = resultStatus,
                        occurredAt = source.time,
                    )
                }
            }
        }
        val isMeta = hasInternalMetaFlag(message, value, metadataObj) ||
            ContextInjectionFilter.isInjectedSourceKind(sourceKind) ||
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
            return extracted.updatedOwnerEvents + topLevelToolUpdates + if (hadRow) {
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
        val mcpServer = value.nonBlankText("server")
        val mcpTool = value.nonBlankText("tool")
        if (mcpServer != null && mcpTool != null) return "$mcpServer: $mcpTool"
        val primary = extractToolString(
            value,
            "toolName",
            "tool_name",
            "name",
            "tool",
            "title",
            "function.name",
            "toolCall.name",
            "toolCall.title",
            "tool_call.name",
        )?.trim()?.takeUnless { ToolCallPairing.isPlaceholderToolName(it) }
        if (primary != null) return primary
        val rawType = extractToolString(value, "kind", "type")?.trim()?.takeIf { it.isNotEmpty() }
        if (rawType != null && rawType !in GENERIC_TOOL_WRAPPER_TYPES && !ToolCallPairing.isPlaceholderToolName(rawType)) {
            return rawType
        }
        return if (allowGenericFallback) "tool" else ""
    }

    private fun formatTextContentNode(node: JsonValue): String? = when (node) {
        is JsonValue.JString -> node.value.takeIf { it.isNotEmpty() }
        is JsonValue.JObject -> {
            val type = node.text("type")?.lowercase()
            when {
                type in setOf("text", "input_text", "output_text", "inputtext", "outputtext") ->
                    node.text("text")?.takeIf { it.isNotEmpty() }
                type == "content" -> node.at("content")?.let(::formatTextContentNode)
                else -> null
            }
        }
        else -> null
    }

    private fun formatToolOutputValue(target: JsonValue): String? = when (target) {
        is JsonValue.JString -> target.value.takeIf { it.isNotEmpty() }
        is JsonValue.JArray -> {
            if (target.items.isEmpty()) {
                null
            } else {
                val extractedTexts = target.items.map(::formatTextContentNode)
                if (extractedTexts.all { it != null }) {
                    extractedTexts.filterNotNull().joinToString("\n").takeIf { it.isNotEmpty() }
                } else {
                    Json.canonical(target)
                }
            }
        }
        is JsonValue.JObject -> {
            formatTextContentNode(target)
                ?: extractMcpContentEnvelopeText(target)
                ?: Json.canonical(target)
        }
        is JsonValue.JNumber -> target.raw
        is JsonValue.JBool -> if (target.value) "true" else "false"
        is JsonValue.JNull -> null
    }

    private fun extractMcpContentEnvelopeText(obj: JsonValue.JObject): String? {
        val wrapperKeys = setOf("content", "contentItems", "content_items", "isError", "is_error", "type")
        if (obj.fields.isEmpty() || !obj.fields.all { (key, _) -> key in wrapperKeys }) return null
        val contentArray = (obj.at("contentItems") ?: obj.at("content_items") ?: obj.at("content")) as? JsonValue.JArray
            ?: return null
        if (contentArray.items.isEmpty()) return null
        val extractedTexts = contentArray.items.map(::formatTextContentNode)
        if (!extractedTexts.all { it != null }) return null
        return extractedTexts.filterNotNull().joinToString("\n").takeIf { it.isNotEmpty() }
    }

    private fun extractToolOutput(block: JsonValue, includeDataField: Boolean = false): String? {
        val outputKeys = arrayOf(
            "rawOutput",
            "raw_output",
            "aggregatedOutput",
            "aggregated_output",
            "formattedOutput",
            "formatted_output",
            "output",
            "result",
            "contentItems",
            "content_items",
            "content",
            "response",
            "error",
            "text",
            "value",
        )
        for (key in outputKeys) {
            val target = extractToolValue(block, key) ?: continue
            val formatted = formatToolOutputValue(target)
            if (formatted != null) return formatted
        }
        val stdout = extractToolValue(block, "stdout")?.let(::formatToolOutputValue)
        val stderr = extractToolValue(block, "stderr")?.let(::formatToolOutputValue)
        val combinedStd = when {
            stdout != null && stderr != null -> "$stdout\n$stderr"
            stdout != null -> stdout
            stderr != null -> stderr
            else -> null
        }
        if (combinedStd != null) return combinedStd
        if (includeDataField) {
            extractToolValue(block, "data")?.let(::formatToolOutputValue)?.let { return it }
        }
        return null
    }

    private fun isToolResultFailed(block: JsonValue): Boolean {
        if (block.flag("isError") == true || block.flag("is_error") == true) return true
        val status = block.text("status")?.lowercase()
        if (status in FAILED_TOOL_STATUSES) return true
        val exitCode = block.number("exitCode") ?: block.number("exit_code")
        if (exitCode != null && exitCode != 0L) return true
        val errorVal = block.at("error")
        if (errorVal != null && errorVal !is JsonValue.JNull) {
            when (errorVal) {
                is JsonValue.JBool -> if (errorVal.value) return true
                is JsonValue.JString -> if (errorVal.value.isNotBlank()) return true
                else -> return true
            }
        }
        return false
    }

    private fun isToolBlockCompleted(block: JsonValue): Boolean {
        val status = block.text("status")?.lowercase()
        if (status in TERMINAL_TOOL_STATUSES) return true
        if (isToolResultFailed(block)) return true
        if (status in NON_TERMINAL_TOOL_STATUSES) return false
        return extractToolOutput(block) != null
    }

    private fun parseToolBlockStep(
        block: JsonValue,
        fallbackId: String,
        completed: Boolean,
        sourceTime: Long,
        sourceMessageId: String?,
        orderSeq: Long,
    ): NativeExecutionStep {
        val id = extractToolCallId(block, allowRecordIdFallback = true)
            ?: ToolCallPairing.normalizeCallId(fallbackId)
            ?: fallbackId
        val previous = knownTools[id]
        val extractedName = extractToolName(block, allowGenericFallback = false)
        val toolName = ToolCallPairing.chooseToolName(previous?.toolName, extractedName)
        val rawArgs = extractToolString(
            block,
            "arguments",
            "rawInput",
            "raw_input",
            "input",
            "args",
            "command",
            "parameters",
            "params",
            "function.arguments",
            "changes",
        )
        val arguments = ToolCallPairing.chooseArguments(previous?.arguments.orEmpty(), rawArgs.orEmpty())
        val output = ToolCallPairing.chooseOutput(previous?.output, extractToolOutput(block, includeDataField = completed))
        val rawStatus = block.text("status")?.lowercase()
        val isFailed = isToolResultFailed(block)
        val status = when {
            isFailed -> NativeExecutionStatus.FAILED
            completed || rawStatus in SUCCEEDED_TOOL_STATUSES -> NativeExecutionStatus.COMPLETED
            previous != null && ToolCallPairing.isTerminalStatus(previous.status) -> previous.status
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
        val callId = extractToolCallId(block, allowRecordIdFallback = false) ?: return
        val output = extractToolOutput(block, includeDataField = true)
        val isFailed = isToolResultFailed(block)
        val status = if (isFailed) NativeExecutionStatus.FAILED else NativeExecutionStatus.COMPLETED
        val previous = knownTools[callId]
        if (previous == null) {
            bufferPendingToolResult(callId, output, isFailed, status, sourceTime)
            return
        }
        val updated = mergeExecutionStep(
            previous,
            previous.copy(
                output = output,
                status = status,
                revision = ++revision,
            ),
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
        isBareResult: Boolean = false,
    ): List<VerifiedConversationEvent> {
        val extractedId = extractToolCallId(value, allowRecordIdFallback = !isBareResult)
        if (isBareResult) {
            if (extractedId != null) {
                val previous = knownTools[extractedId]
                val resultOutput = extractToolOutput(value, includeDataField = true)
                val isFailed = isToolResultFailed(value)
                val resultStatus = if (isFailed) NativeExecutionStatus.FAILED else NativeExecutionStatus.COMPLETED
                if (previous == null) {
                    // 裸工具结果先于调用到达（或原调用缺失）：按 callId 缓冲等待配对，绝不凭空造孤立 `[tool]` 卡片。
                    bufferPendingToolResult(
                        callId = extractedId,
                        output = resultOutput,
                        failed = isFailed,
                        status = resultStatus,
                        occurredAt = source.time,
                    )
                    return emptyList()
                }
                val merged = mergeExecutionStep(
                    previous,
                    previous.copy(
                        toolName = ToolCallPairing.chooseToolName(previous.toolName, extractToolName(value, allowGenericFallback = false)),
                        output = resultOutput,
                        status = resultStatus,
                        revision = ++revision,
                    ),
                )
                knownTools[extractedId] = merged
                val ownerUpdate = applyToolUpdateToOwnerRow(merged, source)
                if (ownerUpdate != null) return listOf(ownerUpdate)
                if (source.historical) return emptyList()
                val eventGeneration = toolGenerationByCallId[extractedId]
                    ?: explicitTurn
                    ?: generationId
                    ?: "remote-execution:$sessionId"
                return listOf(
                    VerifiedConversationEvent.ExecutionStepsUpdated(
                        eventId = source.id(eventId),
                        occurredAt = source.time,
                        conversationId = ConversationId(sessionId),
                        generationId = eventGeneration,
                        executionRevision = ++revision,
                        steps = listOf(merged),
                    ),
                )
            }
            // 无 callId 的裸结果帧：若连真实工具名都缺失（仅占位名或空名），直接丢弃，杜绝 `[tool] 已完成 / 无输出` 孤立卡。
            val explicitName = extractToolName(value, allowGenericFallback = false)
            if (ToolCallPairing.isPlaceholderToolName(explicitName)) {
                return emptyList()
            }
        }
        val id = extractedId ?: ToolCallPairing.normalizeCallId(eventId) ?: eventId
        val previous = knownTools[id]
        val orderSeq = previous?.orderSequence ?: allocateOrder(explicitSeq, historical = source.historical)
        val parsed = applyBufferedResultToCall(
            parseToolBlockStep(
                block = value,
                fallbackId = eventId,
                completed = completed,
                sourceTime = source.time,
                sourceMessageId = previous?.sourceMessageId,
                orderSeq = orderSeq,
            ),
        )
        val merged = previous?.let { mergeExecutionStep(it, parsed) } ?: parsed
        knownTools[id] = merged
        val resolvedTurn = toolGenerationByCallId[id]
            ?: explicitTurn
            ?: (if (source.historical) historicalTurnId else generationId)
        if (resolvedTurn != null) toolGenerationByCallId[id] = resolvedTurn
        val events = mutableListOf<VerifiedConversationEvent>()
        val ownerUpdate = applyToolUpdateToOwnerRow(merged, source)
        if (ownerUpdate != null) {
            events += ownerUpdate
            return events
        } else if (source.historical) {
            val rowId = "history-tool:$id"
            val existingRow = rows[rowId]
            val rowOrder = existingRow?.orderSequence ?: merged.orderSequence ?: orderSeq
            val rowTime = existingRow?.timestamp?.takeIf { it > 0L } ?: merged.occurredAt ?: source.time
            val turnGen = existingRow?.generationId ?: explicitTurn ?: historicalTurnId ?: "history-turn:$sessionId"
            val rowStep = merged.copy(sourceMessageId = rowId, orderSequence = rowOrder, occurredAt = rowTime)
            knownTools[id] = rowStep
            toolOwnerMessageId[id] = rowId
            toolGenerationByCallId[id] = turnGen
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
            return events
        }
        val eventGeneration = toolGenerationByCallId[id] ?: explicitTurn ?: generationId ?: "remote-execution:$sessionId"
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

private val FAILED_TOOL_STATUSES = setOf(
    "failed",
    "error",
    "declined",
    "rejected",
    "denied",
    "cancelled",
    "canceled",
)

private val SUCCEEDED_TOOL_STATUSES = setOf(
    "completed",
    "done",
    "succeeded",
    "success",
)

private val TERMINAL_TOOL_STATUSES = FAILED_TOOL_STATUSES + SUCCEEDED_TOOL_STATUSES

private val NON_TERMINAL_TOOL_STATUSES = setOf(
    "running",
    "in_progress",
    "inprogress",
    "pending",
    "queued",
    "started",
    "executing",
    "calling",
)

private val GENERIC_TOOL_WRAPPER_TYPES = setOf(
    "tool",
    "result",
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
    "function_call_output",
    "functionCallOutput",
    "function_response",
    "functionResponse",
)

/**
 * 全后端唯一的工具调用 ID 提取器（DSH / Claude Code / Codex / OpenCode / Hermes ACP / OpenAI 兼容格式）：
 * - 按显式调用 ID 键优先级读取并跳过空白串（修复 `text(key)` 空串短路 `?:` 的漏配缺陷）；
 * - 递归读取嵌套 `toolCall`/`tool_call`/`call`/`toolUse`/`result`/`item`/`data` 节点；
 * - 仅非独立结果帧（[allowRecordIdFallback] 为 true）才允许回退到顶层 `id` / `item.id`。
 */
internal fun extractToolCallId(value: JsonValue, allowRecordIdFallback: Boolean = true): String? {
    fun pickDirect(node: JsonValue): String? =
        ToolCallPairing.normalizeCallId(node.nonBlankText("callId"))
            ?: ToolCallPairing.normalizeCallId(node.nonBlankText("callID"))
            ?: ToolCallPairing.normalizeCallId(node.nonBlankText("toolCallId"))
            ?: ToolCallPairing.normalizeCallId(node.nonBlankText("tool_call_id"))
            ?: ToolCallPairing.normalizeCallId(node.nonBlankText("call_id"))
            ?: ToolCallPairing.normalizeCallId(node.nonBlankText("tool_use_id"))
            ?: ToolCallPairing.normalizeCallId(node.nonBlankText("toolUseId"))

    pickDirect(value)?.let { return it }

    val nestedKeys = arrayOf("toolCall", "tool_call", "call", "toolUse", "tool_use", "result", "item", "data")
    for (nestedKey in nestedKeys) {
        val nested = value.at(nestedKey) ?: continue
        pickDirect(nested)?.let { return it }
        ToolCallPairing.normalizeCallId(nested.nonBlankText("stepId"))?.let { return it }
        if (nestedKey in setOf("toolCall", "tool_call", "call", "toolUse", "tool_use")) {
            ToolCallPairing.normalizeCallId(nested.nonBlankText("id"))?.let { return it }
        }
    }

    ToolCallPairing.normalizeCallId(value.nonBlankText("stepId"))?.let { return it }

    if (allowRecordIdFallback) {
        ToolCallPairing.normalizeCallId(value.nonBlankText("id"))?.let { return it }
        value.at("item")?.nonBlankText("id")?.let { ToolCallPairing.normalizeCallId(it) }?.let { return it }
    }
    return null
}

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
