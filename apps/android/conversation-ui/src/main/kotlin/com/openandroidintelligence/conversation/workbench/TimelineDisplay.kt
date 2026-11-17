package com.openandroidintelligence.conversation.workbench

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.conversation.model.NativeExecutionStep
import com.openandroidintelligence.conversation.state.TimelineEntry

/**
 * 时间线展示项：要么是单条消息（普通回答 / 用户消息 / 审批 / 系统回执），
 * 要么是一段按真实时序平铺的助手执行过程（规格 §3.1 / R3）。
 */
sealed interface TimelineDisplayItem {
    val key: String

    /**
     * LazyColumn 的复用分池标识：同一个 key 的呈现类型变化时（单条消息转为执行段），
     * 必须让 Compose 重建子组合，而不是复用类型已经不匹配的旧节点。
     */
    val contentType: String

    data class Single(
        val entry: TimelineEntry,
        /** 仅用于列表复用；用户行升级服务端身份时，entry.key 仍保留真实消息 ID。 */
        override val key: String = entry.key,
    ) : TimelineDisplayItem {
        override val contentType: String get() = "single"
    }

    data class Execution(val run: AssistantExecutionRun) : TimelineDisplayItem {
        /**
         * 原生步骤用任务与公共来源身份固定段 key，旧 markup 沿用首条消息的 key。
         * 流式增量与事实行替换成历史来源消息时，展示身份保持稳定。
         */
        override val key: String get() = run.segmentKey

        override val contentType: String get() = "execution"
    }
}

/**
 * 一段连续助手执行过程的聚合结果；同一 generation 可有多个展示段。
 *
 * [finalBlocks] 是 [finalEntry] 里「尾部正文之前」的最终回答块；
 * 没有最终正文时为空，宿主不应伪造回答占位。
 */
data class AssistantExecutionRun(
    /**
     * 段身份：原生步骤取任务与公共来源身份，旧 markup 取段内首条消息 key。
     *
     * LazyColumn 的展示项 key 用这个字段，而不是随最终回答变化的 [key]；
     * 否则多消息回合里 finalEntry 换成后一条时，展示项会被当成新项。
     */
    val segmentKey: String,
    val finalEntry: TimelineEntry,
    val finalBlocks: List<TimelineBlock>,
    val steps: List<ExecutionStep>,
    val stepCount: Int,
    val failedStepCount: Int,
    val interrupted: Boolean,
    val isStreaming: Boolean,
    /** 只有最终回答所在消息仍在流式时，才在最终正文末尾画光标。 */
    val finalEntryIsStreaming: Boolean,
    val hasFinalAnswer: Boolean,
    /** 宿主明确提供的任务身份，与连续展示段的身份分开保存。 */
    val generationId: String? = null,
)

/**
 * 助手执行段中的一个步骤，按真实发生时间顺序展平。
 *
 * [stepKey] 只对思考 / 工具卡有值（`<entryKey>#<blockIndex>`），
 * 用作 [ExecutionTimingStore] 的观察 key；叙述与附件不参与耗时统计。
 */
sealed interface ExecutionStep {
    val stepKey: String?
    val entryKey: String

    data class Thought(
        val block: TimelineBlock.ThoughtBlock,
        override val entryKey: String,
        val blockIndex: Int,
        val entryIsStreaming: Boolean,
        val thoughtKey: String = "$entryKey:thought:$blockIndex",
    ) : ExecutionStep {
        override val stepKey: String get() = "$entryKey#$blockIndex"
    }

    data class ToolCall(
        val block: TimelineBlock.ToolCallBlock,
        override val entryKey: String,
        val blockIndex: Int,
        val entryIsStreaming: Boolean,
        val nativeStepId: String? = null,
    ) : ExecutionStep {
        override val stepKey: String get() = nativeStepId?.let { "$entryKey:$it" } ?: "$entryKey#$blockIndex"
    }

    data class Narrative(
        val block: TimelineBlock,
        override val entryKey: String,
    ) : ExecutionStep {
        override val stepKey: String? get() = null
    }

    data class Attachments(val entry: TimelineEntry) : ExecutionStep {
        override val stepKey: String? get() = null
        override val entryKey: String get() = entry.key
    }
}

/** 步骤是否「没有成功完成」（思考未闭合、工具仍在运行但流已结束）。 */
internal fun ExecutionStep.isInterruptedStep(): Boolean = when (this) {
    is ExecutionStep.Thought -> !block.isComplete && !entryIsStreaming
    is ExecutionStep.ToolCall -> block.isRunning && !entryIsStreaming
    else -> false
}

/**
 * 时间线展示模型：把连续的助手执行消息整理为按时序展开的执行段，其余条目保持单条呈现。
 *
 * 规则严格对齐规格 §3.1：
 * 1. 用户消息、审批行、系统回执行打断助手段；已完成的正常回答也关闭当前段，
 *    后续消息不能再把它降级为过程叙述；
 * 2. 段内出现明确的思考/工具块即使用统一执行容器，运行中也保持同一展示类型；
 * 3. 普通多条助手消息（无执行块）不聚合；
 * 4. 归入执行段时 `finalEntry` 取最后一条有尾部正文的助手消息，没有则取段内最后一条；
 * 5. 解析结果按 `entry.key + 文本长度 + 内容 hash` 缓存，上限 [MaxCachedEntries] 条；
 *    解析异常回退为单段纯文本，界面不崩。
 */
class TimelineDisplayModel(
    private val parse: (String) -> List<TimelineBlock> = MarkdownParser::parse,
) {
    /**
     * 解析缓存：accessOrder = true 让「最近用过的」留在尾部，
     * 超出上限时从最久未用的开始淘汰。
     */
    private val parsedCache = LinkedHashMap<String, List<TimelineBlock>>(16, 0.75f, true)

    /** 当前会话中无身份冲突的用户行，沿用首次出现的展示键。 */
    private val userItemKeys = mutableMapOf<String, String>()

    /** 当前缓存条数（测试用于验证上限）。 */
    internal val cachedEntryCount: Int get() = parsedCache.size

    /** 不依赖 Compose，测试可直接驱动展示身份与执行段矩阵。 */
    fun build(
        entries: List<TimelineEntry>,
        generation: GenerationState? = null,
    ): List<TimelineDisplayItem> {
        if (entries.isEmpty()) {
            userItemKeys.clear()
            return emptyList()
        }

        // 同任务同一步的后续快照只更新首次来源位置，不能跨引导复制卡片。
        val latestNativeSteps = linkedMapOf<Pair<String, String>, NativeExecutionStep>()
        val nativeStepSources = mutableMapOf<Pair<String, String>, Int>()
        entries.forEachIndexed { index, entry ->
            val generationId = entry.generationId?.takeIf { it.isNotBlank() }
            if (!isExecutionEligible(entry) || generationId == null) return@forEachIndexed
            entry.executionSteps.forEach { step ->
                val identity = generationId to step.stepId
                val previous = latestNativeSteps[identity]
                if (previous == null) {
                    nativeStepSources[identity] = index
                    latestNativeSteps[identity] = step
                } else if (step.revision > previous.revision) {
                    latestNativeSteps[identity] = step.copy(
                        sourceMessageId = previous.sourceMessageId?.takeIf { it.isNotBlank() } ?: step.sourceMessageId,
                        occurredAt = previous.occurredAt ?: step.occurredAt,
                    )
                }
            }
        }

        val items = mutableListOf<TimelineDisplayItem>()
        val segment = mutableListOf<EntryAnalysis>()
        // 只收集实际相邻且 generation 一致的执行源；后续步骤不能提前到引导前。
        val nativeSegment = mutableListOf<TimelineEntry>()

        fun flushNativeSegment() {
            if (nativeSegment.isEmpty()) return
            items += TimelineDisplayItem.Execution(
                buildNativeRun(requireNotNull(nativeSegment.first().generationId), nativeSegment.toList()),
            )
            nativeSegment.clear()
        }

        fun flushSegment() {
            if (segment.isEmpty()) return
            items += buildSegment(segment.toList(), generation)
            segment.clear()
        }

        entries.forEachIndexed { index, entry ->
            if (isExecutionEligible(entry) && !entry.generationId.isNullOrBlank() && entry.executionSteps.isNotEmpty()) {
                val generationId = requireNotNull(entry.generationId)
                val sourceSteps = entry.executionSteps.distinctBy { it.stepId }.mapNotNull { step ->
                    val identity = generationId to step.stepId
                    if (nativeStepSources[identity] == index) latestNativeSteps[identity] else null
                }
                val hasReasoningOrMarkup = entry.reasoning.isNotEmpty() || mayContainExecutionMarkup(entry.text)
                if (!hasReasoningOrMarkup) {
                    if (sourceSteps.isNotEmpty()) {
                        flushSegment()
                        if (nativeSegment.firstOrNull()?.generationId != generationId) flushNativeSegment()
                        nativeSegment += entry.copy(executionSteps = sourceSteps)
                    }
                    // 只有重复步骤的事实行不生成新容器；合法正文和附件始终原位保留，
                    // 同时剥离已被原生段消费或前序渲染过的 executionSteps，避免正文气泡重复渲染工具卡。
                    if (entry.text.isNotBlank() || entry.attachments.isNotEmpty()) {
                        flushSegment()
                        flushNativeSegment()
                        items += TimelineDisplayItem.Single(entry.copy(executionSteps = emptyList()))
                    }
                } else if (sourceSteps.isNotEmpty()) {
                    // 同一条目同时携带原生工具步骤与思考/标记时，按发生顺序合并为单一有序执行段，绝不丢弃思考。
                    flushSegment()
                    flushNativeSegment()
                    items += TimelineDisplayItem.Execution(
                        buildMixedNativeAndParsedRun(
                            entry = entry,
                            generationId = generationId,
                            nativeSteps = sourceSteps,
                            generation = generation,
                        ),
                    )
                } else {
                    // 重复的原生步骤已在前序来源位置渲染，剥离重复步骤后继续渲染本行携带的思考/正文。
                    flushNativeSegment()
                    val stripped = entry.copy(executionSteps = emptyList())
                    if (segment.isNotEmpty() && segment.last().entry.generationId != stripped.generationId) flushSegment()
                    val analysis = analyse(stripped)
                    segment += analysis
                    if ((!stripped.isStreaming || generation?.isTerminal() == true) && analysis.finalVisible) flushSegment()
                }
            } else if (isExecutionEligible(entry)) {
                flushNativeSegment()
                if (segment.isNotEmpty() && segment.last().entry.generationId != entry.generationId) flushSegment()
                val analysis = analyse(entry)
                segment += analysis
                // 已完成且有正常正文的消息就是一个回答边界。后续助手消息即使
                // 没有用户分隔，也不能把这条回答降级为下一段工具过程的叙述。
                if ((!entry.isStreaming || generation?.isTerminal() == true) && analysis.finalVisible) flushSegment()
            } else {
                flushNativeSegment()
                flushSegment()
                items += TimelineDisplayItem.Single(entry)
            }
        }
        flushNativeSegment()
        flushSegment()
        return retainUserItemKeys(items)
    }

    private fun retainUserItemKeys(items: List<TimelineDisplayItem>): List<TimelineDisplayItem> {
        val singleUsersByClient = items.filterIsInstance<TimelineDisplayItem.Single>()
            .filter { it.entry.isUser && it.entry.clientMessageId != null }
            .groupBy { requireNotNull(it.entry.clientMessageId).value }
            .filterValues { it.size == 1 }
        // 不跨缺失行或身份冲突保留别名，也不随历史会话无限积累。
        userItemKeys.keys.retainAll(singleUsersByClient.keys)
        val reservedKeys = items.mapTo(hashSetOf()) { it.key }
        return items.map { item ->
            if (item !is TimelineDisplayItem.Single || !item.entry.isUser) return@map item
            val clientId = item.entry.clientMessageId?.value ?: return@map item
            // 同一 client 对应多行时逐条保留真实键，不能把合法消息交给列表误合并。
            if (clientId !in singleUsersByClient) return@map item
            val firstKey = userItemKeys.getOrPut(clientId) { item.key }
            val key = if (firstKey == item.key || reservedKeys.add(firstKey)) firstKey else item.key
            // 旧别名若与另一条消息或执行段撞键，退回本行真实键并保持后续帧稳定。
            userItemKeys[clientId] = key
            item.copy(key = key)
        }
    }

    private fun buildNativeRun(generationId: String, entries: List<TimelineEntry>): AssistantExecutionRun {
        val latest = linkedMapOf<String, NativeExecutionStep>()
        entries.forEach { entry -> entry.executionSteps.forEach { step ->
            val previous = latest[step.stepId]
            if (previous == null || step.revision > previous.revision) latest[step.stepId] = step
        } }
        val segmentKey = entries.first().executionSteps.firstOrNull()?.sourceMessageId
            ?.takeIf { it.isNotBlank() }?.let { "execution:$generationId:$it" } ?: entries.first().key
        // 任务状态由明确的 generation 投影提供，不能用本段某一步完成代替任务终态。
        val generationState = entries.firstNotNullOfOrNull { it.executionGenerationState }
        val terminalGeneration = generationState?.isTerminal() == true
        val streamActive = when {
            terminalGeneration -> false
            generationState == GenerationState.RUNNING || generationState == GenerationState.QUEUED ||
                generationState == GenerationState.CANCEL_REQUESTED -> true
            else -> latest.values.any { it.status == NativeExecutionStatus.RUNNING }
        }
        // 步骤身份属于任务，不能随引导前后的展示段变化。
        val taskKey = "execution:$generationId"
        val steps = latest.values.mapIndexed { index, step ->
            // 明确终态压住迟到的运行快照；没有真实结果时仍是未知，不能伪造成功。
            val status = if (terminalGeneration && step.status == NativeExecutionStatus.RUNNING)
                NativeExecutionStatus.UNKNOWN else step.status
            ExecutionStep.ToolCall(
                block = TimelineBlock.ToolCallBlock(
                    toolName = step.toolName,
                    command = step.arguments,
                    output = step.output,
                    isSuccess = status != NativeExecutionStatus.FAILED,
                    isRunning = status == NativeExecutionStatus.RUNNING,
                    executionStatus = status,
                ),
                entryKey = taskKey,
                blockIndex = index,
                entryIsStreaming = streamActive && status == NativeExecutionStatus.RUNNING,
                nativeStepId = step.stepId,
            )
        }
        return AssistantExecutionRun(
            segmentKey = segmentKey,
            finalEntry = entries.first(),
            finalBlocks = emptyList(),
            steps = steps,
            stepCount = steps.size,
            failedStepCount = latest.values.count { it.status == NativeExecutionStatus.FAILED },
            interrupted = false,
            isStreaming = streamActive,
            finalEntryIsStreaming = false,
            hasFinalAnswer = false,
            generationId = generationId,
        )
    }

    private fun buildMixedNativeAndParsedRun(
        entry: TimelineEntry,
        generationId: String,
        nativeSteps: List<NativeExecutionStep>,
        generation: GenerationState?,
    ): AssistantExecutionRun {
        val stripped = entry.copy(executionSteps = emptyList())
        val analysis = analyseParsed(stripped)
        val blocks = requireNotNull(analysis.blocks)
        val generationState = entry.executionGenerationState ?: generation
        val terminalGeneration = generationState?.isTerminal() == true
        val streamActive = !terminalGeneration && (
            entry.isStreaming ||
                generationState == GenerationState.RUNNING ||
                generationState == GenerationState.QUEUED ||
                generationState == GenerationState.CANCEL_REQUESTED ||
                nativeSteps.any { it.status == NativeExecutionStatus.RUNNING }
            )
        val endExclusive = if (analysis.executionCount > 0) analysis.tailStartIndex else 0
        val leadingThoughtCount = blocks.take(endExclusive).takeWhile { it is TimelineBlock.ThoughtBlock }.size
        val steps = mutableListOf<ExecutionStep>()
        var thoughtOrdinal = 0

        if (!analysis.finalVisible && entry.attachments.isNotEmpty()) {
            steps += ExecutionStep.Attachments(entry)
        }

        for (index in 0 until leadingThoughtCount) {
            val thought = blocks[index] as TimelineBlock.ThoughtBlock
            steps += ExecutionStep.Thought(
                block = thought,
                entryKey = entry.key,
                blockIndex = index,
                entryIsStreaming = entry.isStreaming && !terminalGeneration,
                thoughtKey = "${entry.key}:thought:${thoughtOrdinal++}",
            )
        }

        val taskKey = "execution:$generationId"
        nativeSteps.forEachIndexed { nativeIndex, step ->
            val status = if (terminalGeneration && step.status == NativeExecutionStatus.RUNNING) {
                NativeExecutionStatus.UNKNOWN
            } else {
                step.status
            }
            steps += ExecutionStep.ToolCall(
                block = TimelineBlock.ToolCallBlock(
                    toolName = step.toolName,
                    command = step.arguments,
                    output = step.output,
                    isSuccess = status != NativeExecutionStatus.FAILED,
                    isRunning = status == NativeExecutionStatus.RUNNING,
                    executionStatus = status,
                ),
                entryKey = taskKey,
                blockIndex = nativeIndex,
                entryIsStreaming = streamActive && status == NativeExecutionStatus.RUNNING,
                nativeStepId = step.stepId,
            )
        }

        for (index in leadingThoughtCount until endExclusive) {
            val block = blocks[index]
            if (block is TimelineBlock.Paragraph && block.text.isBlank()) continue
            steps += when (block) {
                is TimelineBlock.ThoughtBlock -> ExecutionStep.Thought(
                    block = block,
                    entryKey = entry.key,
                    blockIndex = index,
                    entryIsStreaming = entry.isStreaming && !terminalGeneration,
                    thoughtKey = "${entry.key}:thought:${thoughtOrdinal++}",
                )

                is TimelineBlock.ToolCallBlock -> ExecutionStep.ToolCall(
                    block = block,
                    entryKey = entry.key,
                    blockIndex = index,
                    entryIsStreaming = entry.isStreaming && !terminalGeneration,
                )

                else -> ExecutionStep.Narrative(block = block, entryKey = entry.key)
            }
        }

        val stepCount = steps.count { it is ExecutionStep.Thought || it is ExecutionStep.ToolCall }
        val failedStepCount = steps.count { step ->
            when (step) {
                is ExecutionStep.ToolCall -> !step.block.isSuccess || step.isInterruptedStep()
                is ExecutionStep.Thought -> step.isInterruptedStep()
                else -> false
            }
        }
        val interrupted = steps.any { it.isInterruptedStep() }
        val segmentKey = nativeSteps.firstOrNull()?.sourceMessageId
            ?.takeIf { it.isNotBlank() }?.let { "execution:$generationId:$it" } ?: entry.key

        return AssistantExecutionRun(
            segmentKey = segmentKey,
            finalEntry = if (!streamActive && entry.isStreaming) entry.copy(isStreaming = false) else entry,
            finalBlocks = if (analysis.finalVisible) analysis.finalBlocks else emptyList(),
            steps = steps,
            stepCount = stepCount,
            failedStepCount = failedStepCount,
            interrupted = interrupted,
            isStreaming = streamActive,
            finalEntryIsStreaming = entry.isStreaming && !terminalGeneration,
            hasFinalAnswer = analysis.finalVisible,
            generationId = generationId,
        )
    }

    private fun buildSegment(
        analyses: List<EntryAnalysis>,
        generation: GenerationState?,
    ): List<TimelineDisplayItem> {
        val entries = analyses.map { it.entry }
        val hasExecutionBlocks = analyses.any { it.executionCount > 0 }
        // 只有明确终态才强制结束流式判定；OUTCOME_UNKNOWN / UNSUPPORTED / IDLE
        // 仍按每条消息的 isStreaming 判断（取消结果未知时可能还有增量到达）。
        // Hermes 的实时快照可能一直停留在 STREAMING，直到切会话重载才变成
        // CONFIRMED；生成已经明确结束时必须允许收束执行段，不再依赖消息标记。
        val terminalGeneration = generation?.isTerminal() == true
        val streamActive = !terminalGeneration && entries.any { it.isStreaming }
        // 明确的执行块从首个流式快照起就在统一容器内；是否已有最终正文只决定回答区域。
        if (!hasExecutionBlocks) {
            return entries.map { entry ->
                TimelineDisplayItem.Single(if (terminalGeneration) entry.copy(isStreaming = false) else entry)
            }
        }
        val run = buildRun(analyses, streamActive)
        // 既没有可展示步骤、也没有最终正文时，构建执行段只会得到空内容；
        // 这种边界（工具调用后正文为空 / 流被截断）保持逐条呈现。
        if (run.steps.isEmpty() && !run.hasFinalAnswer) {
            return entries.map { entry ->
                TimelineDisplayItem.Single(if (terminalGeneration) entry.copy(isStreaming = false) else entry)
            }
        }
        return listOf(TimelineDisplayItem.Execution(run))
    }

    private fun buildRun(
        analyses: List<EntryAnalysis>,
        streamActive: Boolean,
    ): AssistantExecutionRun {
        // 只有包含执行块的段才解析完整块；普通助手消息在 [analyse] 里走快速路径。
        val resolved = analyses.map { analysis ->
            if (analysis.blocks == null) analyseParsed(analysis.entry) else analysis
        }
        val finalAnalysis = resolved.lastOrNull { it.finalVisible } ?: resolved.last()
        val finalEntry = finalAnalysis.entry
        val hasFinalAnswer = finalAnalysis.finalVisible
        val steps = mutableListOf<ExecutionStep>()

        resolved.forEach { analysis ->
            val blocks = requireNotNull(analysis.blocks)
            val isFinalEntry = analysis.entry.key == finalEntry.key
            // 最终回答的附件由宿主渲染在答案上方；没有最终正文时改由过程区承接，不丢附件。
            if ((!isFinalEntry || !hasFinalAnswer) && analysis.entry.attachments.isNotEmpty()) {
                steps += ExecutionStep.Attachments(analysis.entry)
            }
            // 最终消息只把「尾部正文之前的块」收进过程区，尾部正文留在 finalBlocks。
            val endExclusive = if (isFinalEntry) analysis.tailStartIndex else blocks.size
            var thoughtOrdinal = 0
            blocks.forEachIndexed { index, block ->
                if (index >= endExclusive) return@forEachIndexed
                if (block is TimelineBlock.Paragraph && block.text.isBlank()) return@forEachIndexed
                steps += when (block) {
                    is TimelineBlock.ThoughtBlock -> ExecutionStep.Thought(
                        block = block,
                        entryKey = analysis.entry.key,
                        blockIndex = index,
                        entryIsStreaming = analysis.entry.isStreaming && streamActive,
                        thoughtKey = "${analysis.entry.key}:thought:${thoughtOrdinal++}",
                    )

                    is TimelineBlock.ToolCallBlock -> ExecutionStep.ToolCall(
                        block = block,
                        entryKey = analysis.entry.key,
                        blockIndex = index,
                        entryIsStreaming = analysis.entry.isStreaming && streamActive,
                    )

                    else -> ExecutionStep.Narrative(block = block, entryKey = analysis.entry.key)
                }
            }
        }

        val stepCount = steps.count { it is ExecutionStep.Thought || it is ExecutionStep.ToolCall }
        // 失败步骤数包含中断步骤：两者都是「没有成功完成的执行」，标题统一显示为失败。
        val failedStepCount = steps.count { step ->
            when (step) {
                is ExecutionStep.ToolCall -> !step.block.isSuccess || step.isInterruptedStep()
                is ExecutionStep.Thought -> step.isInterruptedStep()
                else -> false
            }
        }
        val interrupted = steps.any { it.isInterruptedStep() }

        return AssistantExecutionRun(
            segmentKey = analyses.first().entry.key,
            finalEntry = if (!streamActive && finalEntry.isStreaming) finalEntry.copy(isStreaming = false) else finalEntry,
            finalBlocks = if (hasFinalAnswer) finalAnalysis.finalBlocks else emptyList(),
            steps = steps,
            stepCount = stepCount,
            failedStepCount = failedStepCount,
            interrupted = interrupted,
            isStreaming = streamActive,
            finalEntryIsStreaming = finalEntry.isStreaming && streamActive,
            hasFinalAnswer = hasFinalAnswer,
            generationId = analyses.first().entry.generationId?.takeIf { it.isNotBlank() },
        )
    }

    /** 单条助手消息的解析结果与「尾部正文」定位。 */
    private data class EntryAnalysis(
        val entry: TimelineEntry,
        /** null 表示没有执行标记、尚未完整解析；只在段包含执行块时解析。 */
        val blocks: List<TimelineBlock>?,
        val executionCount: Int,
        val tailStartIndex: Int,
        val finalBlocks: List<TimelineBlock>,
        val finalVisible: Boolean,
    )

    /**
     * 先做廉价预扫描：没有执行标记的消息只记录“是否有可见正文”，不解析 Markdown。
     *
     * 旧实现每次都解析整段会话，长历史 + 流式增量会反复在主线程解析全部历史；
     * 而普通消息只有在所属执行段真的包含执行块时才需要完整块（叙述内容）。
     */
    private fun analyse(entry: TimelineEntry): EntryAnalysis {
        if (entry.reasoning.isEmpty() && !mayContainExecutionMarkup(entry.text)) {
            return EntryAnalysis(
                entry = entry,
                blocks = null,
                executionCount = 0,
                tailStartIndex = 0,
                finalBlocks = emptyList(),
                finalVisible = entry.text.isNotBlank(),
            )
        }
        return analyseParsed(entry)
    }

    private fun analyseParsed(entry: TimelineEntry): EntryAnalysis {
        val blocks = parseEntry(entry)
        var lastExecutionIndex = -1
        var executionCount = 0
        blocks.forEachIndexed { index, block ->
            if (isExecutionBlock(block)) {
                lastExecutionIndex = index
                executionCount++
            }
        }
        val tailStartIndex = if (lastExecutionIndex < 0) 0 else lastExecutionIndex + 1
        val finalBlocks = if (tailStartIndex <= blocks.lastIndex) {
            blocks.subList(tailStartIndex, blocks.size).toList()
        } else {
            emptyList()
        }
        return EntryAnalysis(
            entry = entry,
            blocks = blocks,
            executionCount = executionCount,
            tailStartIndex = tailStartIndex,
            finalBlocks = finalBlocks,
            finalVisible = hasVisibleMarkdownContent(finalBlocks),
        )
    }

    private fun parseEntry(entry: TimelineEntry): List<TimelineBlock> {
        // 缓存 key 包含文本长度与内容 hash：流式过程中同长度内容被改写时不会命中旧结果，
        // 同时保留「文本长度变化才重解析」的规格语义（规格 §3.1 第 8 条）。
        val cacheKey = "${entry.key}@${entry.text.length}@${entry.text.hashCode()}@${entry.reasoning.hashCode()}"
        parsedCache[cacheKey]?.let { return it }

        val blocks = runCatching { structuredTimelineBlocks(entry, parse) }
            .getOrElse {
                if (entry.text.isBlank()) emptyList() else listOf(TimelineBlock.Paragraph(entry.text))
            }
            .let { parsed ->
                if (parsed.isEmpty() && entry.text.isNotBlank()) {
                    listOf(TimelineBlock.Paragraph(entry.text))
                } else {
                    parsed
                }
            }

        parsedCache[cacheKey] = blocks
        while (parsedCache.size > MaxCachedEntries) {
            val eldest = parsedCache.keys.iterator()
            if (!eldest.hasNext()) break
            eldest.next()
            eldest.remove()
        }
        return blocks
    }

    private companion object {
        /** 解析缓存上限：够覆盖一屏多的历史消息，又不会随会话无限增长。 */
        const val MaxCachedEntries = 128
    }
}

/** 上游思考块直接进入既有执行卡片，正文继续使用原 Markdown 解析与来源偏移。 */
internal fun structuredTimelineBlocks(entry: TimelineEntry, parse: (String) -> List<TimelineBlock> = MarkdownParser::parse): List<TimelineBlock> {
    if (entry.reasoning.isEmpty()) return parse(entry.text).filter { it !is TimelineBlock.Paragraph || it.text.isNotBlank() }
    val blocks = mutableListOf<TimelineBlock>()
    var offset = 0
    entry.reasoning.sortedBy { it.bodyOffset }.forEach { reasoning ->
        val next = reasoning.bodyOffset.coerceIn(offset, entry.text.length)
        if (next > offset) {
            val textSlice = entry.text.substring(offset, next)
            if (textSlice.isNotBlank()) {
                blocks += parse(textSlice).filter { it !is TimelineBlock.Paragraph || it.text.isNotBlank() }
            }
        }
        blocks += TimelineBlock.ThoughtBlock(reasoning.text, reasoning.complete)
        offset = next
    }
    if (offset < entry.text.length) {
        val trailing = entry.text.substring(offset)
        if (trailing.isNotBlank()) {
            blocks += parse(trailing).filter { it !is TimelineBlock.Paragraph || it.text.isNotBlank() }
        }
    }
    return blocks
}

/**
 * 时间线展示项缓存：同一批 entries 只重算一次，会话切换 / 流式增量时重建。
 */
@Composable
fun rememberTimelineDisplayItems(
    entries: List<TimelineEntry>,
    threadId: String? = null,
    generation: GenerationState? = null,
): List<TimelineDisplayItem> {
    // 解析缓存与用户展示身份属于当前会话，换会话时重建。
    val model = remember(threadId) { TimelineDisplayModel() }
    return remember(model, entries, generation) { model.build(entries, generation) }
}

/** 只有明确终态才强制结束流式判定；OUTCOME_UNKNOWN/UNSUPPORTED 仍可能继续输出。 */
internal fun GenerationState.isTerminal(): Boolean =
    this == GenerationState.COMPLETED ||
        this == GenerationState.FAILED ||
        this == GenerationState.CANCELLED

/** 只有助手消息或明确的执行事实能进入执行段，审批与系统导航行始终独立。 */
private fun isExecutionEligible(entry: TimelineEntry): Boolean =
    !entry.isUser && (entry.sender == "assistant" || entry.executionFact) &&
        entry.approval == null && entry.systemThreadId == null

/**
 * 廉价预扫描：只有可能包含执行块的消息才需要完整 Markdown 解析。
 *
 * `$ adb` 是普通 bash 围栏升格工具卡的兼容路径；保守地包含它，避免漏掉工具卡。
 */
internal fun mayContainExecutionMarkup(text: String): Boolean =
    text.contains("<think", ignoreCase = true) ||
        text.contains("<tool_call", ignoreCase = true) ||
        text.contains("```tool_call", ignoreCase = true) ||
        text.contains("```tool:", ignoreCase = true) ||
        text.contains("\$ adb", ignoreCase = true)

/** 执行块：思考与工具调用。步骤计数与执行段判定都以它为准。 */
internal fun isExecutionBlock(block: TimelineBlock): Boolean =
    block is TimelineBlock.ThoughtBlock || block is TimelineBlock.ToolCallBlock

/**
 * 块列表里是否存在「真正看得见的内容」。
 *
 * 用于判断尾部是否已经有最终正文：空段落、纯分割线、空列表都不算回答。
 */
internal fun hasVisibleMarkdownContent(blocks: List<TimelineBlock>): Boolean = blocks.any { block ->
    when (block) {
        is TimelineBlock.Paragraph -> block.text.isNotBlank()
        is TimelineBlock.CodeBlock -> block.code.isNotBlank()
        is TimelineBlock.Heading -> block.text.isNotBlank()
        is TimelineBlock.ListBlock -> block.items.any { it.text.isNotBlank() }
        is TimelineBlock.Table ->
            block.headers.any { it.isNotBlank() } || block.rows.any { row -> row.any { it.isNotBlank() } }

        is TimelineBlock.MathBlock -> block.latex.isNotBlank()
        is TimelineBlock.Blockquote -> hasVisibleMarkdownContent(block.blocks)
        is TimelineBlock.ThematicBreak -> false
        is TimelineBlock.ThoughtBlock -> block.thought.isNotBlank()
        is TimelineBlock.ToolCallBlock ->
            block.command.isNotBlank() || !block.output.isNullOrBlank() || !block.summary.isNullOrBlank()
    }
}
