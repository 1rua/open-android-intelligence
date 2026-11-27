package com.openandroidintelligence.conversation.workbench

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.openandroidintelligence.conversation.model.ExecutionTimelineStep
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.conversation.model.NativeExecutionStep
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin
import com.openandroidintelligence.conversation.model.ToolCallPairing
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
    /**
     * 本回合是否已结束且输出了最终回答（用于决定是否收拢中间过程大卡片）：
     * 当 `!isStreaming` 且满足以下任一条件时为 `true`：
     * 1. 本执行段内部已包含最终回答（`hasFinalAnswer == true`）；
     * 2. 纯原生工具执行段紧随同回合已完成的独立最终助手回答（兼容 `[Execution, Single(final)]` 拆分契约）。
     */
    val hasCompletedFinalAnswer: Boolean = !isStreaming && hasFinalAnswer,
    /** 宿主明确提供的任务身份，与连续展示段的身份分开保存。 */
    val generationId: String? = null,
)

/**
 * 助手执行段中的一个步骤，按真实发生时间顺序展平。
 *
 * [stepKey] 只对思考 / 工具卡有值（`<entryKey>#<blockIndex>` 或 `execution:<gen>:<stepId>`），
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
        val stepId: String? = null,
    ) : ExecutionStep {
        override val stepKey: String
            get() = stepId?.takeIf { it.isNotBlank() }?.let { "$entryKey:thought:$it" } ?: "$entryKey#$blockIndex"
    }

    data class ToolCall(
        val block: TimelineBlock.ToolCallBlock,
        override val entryKey: String,
        val blockIndex: Int,
        val entryIsStreaming: Boolean,
        val nativeStepId: String? = null,
        val isSnapshot: Boolean = false,
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

/** 单条消息经统一有序构建后的异构块表示，同时服务于 [TimelineDisplayModel] 与 [AssistantMessageContent]。 */
internal sealed interface OrderedTimelineBlock {
    val block: TimelineBlock

    data class Thought(
        override val block: TimelineBlock.ThoughtBlock,
        val stepId: String? = null,
    ) : OrderedTimelineBlock

    data class ToolCall(
        override val block: TimelineBlock.ToolCallBlock,
        val nativeStepId: String? = null,
        val entryStreamingOverride: Boolean? = null,
    ) : OrderedTimelineBlock

    data class Other(
        override val block: TimelineBlock,
    ) : OrderedTimelineBlock
}

/** 步骤是否「没有成功完成」（思考未闭合、工具仍在运行但流已结束）。 */
internal fun ExecutionStep.isInterruptedStep(): Boolean = when (this) {
    is ExecutionStep.Thought -> !block.isComplete && !entryIsStreaming
    is ExecutionStep.ToolCall -> block.isRunning && !entryIsStreaming
    else -> false
}

/**
 * 时间线展示模型：把连续的助手执行消息整理为按真实发生顺序展平的统一异构执行段，其余条目保持单条呈现。
 *
 * 核心原则：
 * 1. 用户消息、审批行、系统回执行打断助手段；已完成的正常回答也关闭当前段，
 *    后续消息不能再把它降级为过程叙述；
 * 2. 同一回合（同 `generationId`）内相邻的思考条目、工具条目与混合条目全部进入同一个有序列表，
 *    不再拆分 `nativeSegment` 与 `segment` 导致同类卡片归堆；
 * 3. 单条消息内部严格按 [TimelineEntry.timelineSteps] 的 `(bodyOffset, orderIndex)` 顺序交错归并，
 *    严禁把思考与工具拆分成两个独立循环分别渲染；
 * 4. 普通多条助手消息（无执行块）不聚合；
 * 5. 解析结果按内容摘要与步骤摘要缓存，上限 [MaxCachedEntries] 条；解析异常安全回退。
 */
class TimelineDisplayModel(
    private val parse: (String) -> List<TimelineBlock> = MarkdownParser::parse,
) {
    /**
     * 解析缓存：accessOrder = true 让「最近用过的」留在尾部，
     * 超出上限时从最久未用的开始淘汰。
     */
    private val parsedCache = LinkedHashMap<String, List<OrderedTimelineBlock>>(16, 0.75f, true)

    /** 当前会话中无身份冲突的用户行，沿用首次出现的展示键。 */
    private val userItemKeys = mutableMapOf<String, String>()

    /** 当前缓存条数（测试用于验证上限）。 */
    internal val cachedEntryCount: Int get() = parsedCache.size

    private data class TurnSegmentItem(
        val entry: TimelineEntry,
        val sourceSteps: List<NativeExecutionStep>,
        val nativeOnly: Boolean,
        val analysis: EntryAnalysis?,
    )

    /** 不依赖 Compose，测试可直接驱动展示身份与执行段矩阵。 */
    fun build(
        entries: List<TimelineEntry>,
        generation: GenerationState? = null,
    ): List<TimelineDisplayItem> {
        if (entries.isEmpty()) {
            userItemKeys.clear()
            return emptyList()
        }

        // 同任务同一步的后续快照只更新首次来源位置，不能跨引导复制卡片；
        // 以去空白后的 stepId 为核心键，兼容跨临时 generationId 别名合并，并采用非破坏性字段保留。
        val latestNativeSteps = linkedMapOf<Pair<String, String>, NativeExecutionStep>()
        val nativeStepSources = mutableMapOf<Pair<String, String>, Int>()
        val identityAliases = mutableMapOf<Pair<String, String>, Pair<String, String>>()
        val canonicalKeysByStepId = mutableMapOf<String, MutableList<Pair<String, String>>>()
        val ephemeralToAuthoritativeGeneration = mutableMapOf<String, String>()

        fun hasTurnBreakBetween(fromIndex: Int, toIndex: Int): Boolean {
            for (idx in (fromIndex + 1) until toIndex) {
                if (!isExecutionEligible(entries[idx])) return true
            }
            return false
        }

        entries.forEachIndexed { index, entry ->
            val generationId = entry.generationId?.takeIf { it.isNotBlank() }
            if (!isExecutionEligible(entry) || generationId == null) return@forEachIndexed
            entry.allNativeSteps().forEach { rawStep ->
                val cleanStepId = normalizeNativeStepId(rawStep.stepId)
                val step = if (rawStep.stepId == cleanStepId) rawStep else rawStep.copy(stepId = cleanStepId)
                val rawIdentity = generationId to cleanStepId
                val canonical = identityAliases[rawIdentity] ?: run {
                    val existingMatch = canonicalKeysByStepId[cleanStepId]
                        ?.lastOrNull { existingKey ->
                            val existingGen = existingKey.first
                            val existingIndex = nativeStepSources[existingKey] ?: return@lastOrNull false
                            (ToolCallPairing.isProvisionalGenerationId(generationId) ||
                                ToolCallPairing.isProvisionalGenerationId(existingGen)) &&
                                !hasTurnBreakBetween(existingIndex, index)
                        }
                    if (existingMatch != null) {
                        val existingGen = existingMatch.first
                        if (ToolCallPairing.isProvisionalGenerationId(existingGen) &&
                            !ToolCallPairing.isProvisionalGenerationId(generationId)
                        ) {
                            ephemeralToAuthoritativeGeneration[existingGen] = generationId
                        } else if (!ToolCallPairing.isProvisionalGenerationId(existingGen) &&
                            ToolCallPairing.isProvisionalGenerationId(generationId)
                        ) {
                            ephemeralToAuthoritativeGeneration[generationId] = existingGen
                        }
                        existingMatch
                    } else {
                        rawIdentity
                    }
                }
                identityAliases[rawIdentity] = canonical
                val previous = latestNativeSteps[canonical]
                if (previous == null) {
                    nativeStepSources[canonical] = index
                    latestNativeSteps[canonical] = step
                    canonicalKeysByStepId.getOrPut(cleanStepId) { mutableListOf() }.add(canonical)
                } else {
                    latestNativeSteps[canonical] = previous.applyNewerSnapshot(step)
                }
            }
        }

        fun effectiveGenerationId(rawGenerationId: String?): String? {
            val clean = rawGenerationId?.takeIf { it.isNotBlank() } ?: return null
            return ephemeralToAuthoritativeGeneration[clean] ?: clean
        }

        val items = mutableListOf<TimelineDisplayItem>()
        val turnSegment = mutableListOf<TurnSegmentItem>()

        fun flushTurnSegment() {
            if (turnSegment.isEmpty()) return
            items += buildTurnSegment(turnSegment.toList(), generation)
            turnSegment.clear()
        }

        fun hasTrailingExecutionInSameGeneration(fromIndex: Int, resolvedGenerationId: String?): Boolean {
            if (resolvedGenerationId.isNullOrBlank()) return false
            for (nextIdx in (fromIndex + 1) until entries.size) {
                val next = entries[nextIdx]
                if (!isExecutionEligible(next) || effectiveGenerationId(next.generationId) != resolvedGenerationId) {
                    return false
                }
                val hasExecutionPayload = next.executionFact ||
                    next.allNativeSteps().isNotEmpty() ||
                    next.hasReasoningSteps ||
                    mayContainExecutionMarkup(next.text)
                if (hasExecutionPayload) return true
            }
            return false
        }

        entries.forEachIndexed { index, rawEntry ->
            if (!isExecutionEligible(rawEntry)) {
                flushTurnSegment()
                items += TimelineDisplayItem.Single(rawEntry)
                return@forEachIndexed
            }

            val rawGenerationId = rawEntry.generationId?.takeIf { it.isNotBlank() }
            val resolvedGenerationId = effectiveGenerationId(rawGenerationId)
            val entry = if (rawGenerationId != null && resolvedGenerationId != null && resolvedGenerationId != rawGenerationId) {
                rawEntry.copy(generationId = resolvedGenerationId)
            } else {
                rawEntry
            }

            if (turnSegment.isNotEmpty() && turnSegment.last().entry.generationId != entry.generationId) {
                flushTurnSegment()
            }

            val allEntryNative = if (rawGenerationId != null) rawEntry.allNativeSteps() else emptyList()
            val sourceSteps = if (rawGenerationId != null && allEntryNative.isNotEmpty()) {
                allEntryNative
                    .distinctBy { normalizeNativeStepId(it.stepId) }
                    .mapNotNull { step ->
                        val cleanStepId = normalizeNativeStepId(step.stepId)
                        val canonical = identityAliases[rawGenerationId to cleanStepId] ?: (rawGenerationId to cleanStepId)
                        if (nativeStepSources[canonical] == index) latestNativeSteps[canonical] else null
                    }
            } else {
                emptyList()
            }
            val sourceStepById = sourceSteps.associateBy { normalizeNativeStepId(it.stepId) }
            val hasOwnThinking = entry.hasReasoningSteps
            val hasMarkup = mayContainExecutionMarkup(entry.text)
            val hasTimelineSteps = entry.timelineSteps.isNotEmpty()
            val hasInterleavedToolOffset = entry.timelineSteps.any { it.bodyOffset > 0 }

            if (resolvedGenerationId != null && allEntryNative.isNotEmpty() && !hasOwnThinking && !hasMarkup && !hasInterleavedToolOffset) {
                // 纯原生工具条目：如果带重复步骤（已在前序渲染），跳过工具卡但保留合法正文/附件。
                if (sourceSteps.isNotEmpty()) {
                    val toolTimelineSteps = entry.timelineSteps.mapNotNull { step ->
                        if (step is ExecutionTimelineStep.ToolCallStep) {
                            sourceStepById[normalizeNativeStepId(step.stepId)]?.let { updated -> step.copy(step = updated) }
                        } else null
                    }
                    val toolOnlyEntry = entry.copy(
                        text = "",
                        attachments = emptyList(),
                        executionSteps = sourceSteps,
                        timelineSteps = toolTimelineSteps,
                    )
                    turnSegment += TurnSegmentItem(
                        entry = toolOnlyEntry,
                        sourceSteps = sourceSteps,
                        nativeOnly = true,
                        analysis = null,
                    )
                }
                if (entry.text.isNotBlank() || entry.attachments.isNotEmpty()) {
                    val stripped = entry.copy(executionSteps = emptyList(), timelineSteps = emptyList())
                    if (turnSegment.isNotEmpty() && turnSegment.all { it.nativeOnly } &&
                        !hasTrailingExecutionInSameGeneration(index, resolvedGenerationId)
                    ) {
                        // 纯原生工具段后的独立正文保持单条气泡（兼容既有一体化源消息与原生步骤契约）。
                        flushTurnSegment()
                        items += TimelineDisplayItem.Single(stripped)
                    } else {
                        val analysis = analyse(stripped, emptyList(), generation)
                        turnSegment += TurnSegmentItem(stripped, emptyList(), nativeOnly = false, analysis = analysis)
                        if ((!stripped.isStreaming || generation?.isTerminal() == true || stripped.executionGenerationState?.isTerminal() == true) &&
                            analysis.finalVisible &&
                            !hasTrailingExecutionInSameGeneration(index, resolvedGenerationId)
                        ) {
                            flushTurnSegment()
                        }
                    }
                }
                return@forEachIndexed
            }

            // 含思考 / 异构 timelineSteps / 标记工具 / 普通正文
            val filteredTimelineSteps = if (!hasTimelineSteps) {
                emptyList()
            } else if (resolvedGenerationId != null) {
                entry.timelineSteps.mapNotNull { step ->
                    when (step) {
                        is ExecutionTimelineStep.ThinkingStep -> step
                        is ExecutionTimelineStep.ToolCallStep ->
                            sourceStepById[normalizeNativeStepId(step.stepId)]?.let { updated -> step.copy(step = updated) }
                    }
                }
            } else {
                entry.timelineSteps
            }
            val cleanedEntry = entry.copy(
                executionSteps = sourceSteps,
                timelineSteps = filteredTimelineSteps,
            )
            val isPlainAnswerWithoutExecution = !hasOwnThinking &&
                !hasMarkup &&
                sourceSteps.isEmpty() &&
                filteredTimelineSteps.isEmpty()

            if (isPlainAnswerWithoutExecution &&
                turnSegment.isNotEmpty() &&
                turnSegment.all { it.nativeOnly } &&
                !hasTrailingExecutionInSameGeneration(index, resolvedGenerationId)
            ) {
                // 纯工具段（无任何思考/标记）紧接纯最终答案时，保持工具段与最终答案独立（既有契约）。
                flushTurnSegment()
            }

            val analysis = analyse(cleanedEntry, sourceSteps, generation)
            turnSegment += TurnSegmentItem(
                entry = cleanedEntry,
                sourceSteps = sourceSteps,
                nativeOnly = false,
                analysis = analysis,
            )
            // 已完成且有正常正文的消息在其后不存在同回合执行事实时才作为回答边界收口，
            // 防止同回合多段工具与中间叙述被拆成多张折叠卡片或排到最终回答下方。
            val entryTerminal = !cleanedEntry.isStreaming ||
                generation?.isTerminal() == true ||
                cleanedEntry.executionGenerationState?.isTerminal() == true
            if (entryTerminal && analysis.finalVisible && !hasTrailingExecutionInSameGeneration(index, resolvedGenerationId)) {
                flushTurnSegment()
            }
        }
        flushTurnSegment()
        val withCompletionFlags = markCompletedFinalAnswers(items, generation)
        return retainUserItemKeys(withCompletionFlags)
    }

    /**
     * 为执行段补齐 [AssistantExecutionRun.hasCompletedFinalAnswer]：
     * 既覆盖段内自带最终正文，也覆盖纯原生工具段紧随同回合独立最终助手气泡（`[Execution, Single(final)]`）的契约。
     */
    private fun markCompletedFinalAnswers(
        items: List<TimelineDisplayItem>,
        generation: GenerationState?,
    ): List<TimelineDisplayItem> {
        val activeTopGeneration = generation == GenerationState.RUNNING ||
            generation == GenerationState.QUEUED ||
            generation == GenerationState.CANCEL_REQUESTED
        return items.mapIndexed { index, item ->
            if (item !is TimelineDisplayItem.Execution) return@mapIndexed item
            val run = item.run
            if (run.isStreaming) {
                return@mapIndexed if (run.hasCompletedFinalAnswer) {
                    item.copy(run = run.copy(hasCompletedFinalAnswer = false))
                } else {
                    item
                }
            }
            if (run.hasFinalAnswer) {
                return@mapIndexed if (run.hasCompletedFinalAnswer) {
                    item
                } else {
                    item.copy(run = run.copy(hasCompletedFinalAnswer = true))
                }
            }
            val nextSingle = items.getOrNull(index + 1) as? TimelineDisplayItem.Single
            val nextEntry = nextSingle?.entry
            val sameTurnGeneration = when {
                nextEntry == null -> false
                run.generationId != null && nextEntry.generationId != null ->
                    nextEntry.generationId == run.generationId ||
                        ToolCallPairing.isProvisionalGenerationId(run.generationId) ||
                        ToolCallPairing.isProvisionalGenerationId(nextEntry.generationId)
                else -> true
            }
            val followedByCompletedSameTurnAnswer = nextEntry != null &&
                isExecutionEligible(nextEntry) &&
                sameTurnGeneration &&
                (nextEntry.text.isNotBlank() || nextEntry.attachments.isNotEmpty()) &&
                !nextEntry.isStreaming &&
                (nextEntry.executionGenerationState == null || nextEntry.executionGenerationState.isTerminal()) &&
                !(activeTopGeneration && index + 1 == items.lastIndex && nextEntry.executionGenerationState?.isTerminal() != true)
            if (followedByCompletedSameTurnAnswer == run.hasCompletedFinalAnswer) {
                item
            } else {
                item.copy(run = run.copy(hasCompletedFinalAnswer = followedByCompletedSameTurnAnswer))
            }
        }
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

    private fun buildTurnSegment(
        segment: List<TurnSegmentItem>,
        generation: GenerationState?,
    ): List<TimelineDisplayItem> {
        val allNativeOnly = segment.all { it.nativeOnly }
        val generationState = segment.firstNotNullOfOrNull { it.entry.executionGenerationState } ?: generation
        val terminalGeneration = generationState?.isTerminal() == true

        if (allNativeOnly) {
            return listOf(TimelineDisplayItem.Execution(buildUnifiedRun(segment, generation, allNativeOnly = true)))
        }

        val hasAnyExecution = segment.any { item ->
            item.nativeOnly || item.sourceSteps.isNotEmpty() || (item.analysis?.executionCount ?: 0) > 0
        }
        if (!hasAnyExecution) {
            return segment.map { item ->
                TimelineDisplayItem.Single(
                    if (terminalGeneration) item.entry.copy(isStreaming = false) else item.entry,
                )
            }
        }

        val run = buildUnifiedRun(segment, generation, allNativeOnly = false)
        if (run.steps.isEmpty() && !run.hasFinalAnswer) {
            return segment.map { item ->
                TimelineDisplayItem.Single(
                    if (terminalGeneration) item.entry.copy(isStreaming = false) else item.entry,
                )
            }
        }
        return listOf(TimelineDisplayItem.Execution(run))
    }

    /**
     * 统一执行段构建器：无论段内是纯原生工具、纯思考、单条异构混合还是跨条交错（思考1 -> 工具A -> 思考2 -> 工具B），
     * 全部通过唯一有序循环展平到 [AssistantExecutionRun.steps]，绝不按类型拆分归堆。
     */
    private fun buildUnifiedRun(
        segment: List<TurnSegmentItem>,
        generation: GenerationState?,
        allNativeOnly: Boolean,
    ): AssistantExecutionRun {
        val generationId = segment.firstNotNullOfOrNull { it.entry.generationId?.takeIf { id -> id.isNotBlank() } }
        val explicitGenerationState = segment.firstNotNullOfOrNull { it.entry.executionGenerationState }
        val effectiveGenerationState = explicitGenerationState ?: if (allNativeOnly) null else generation
        val terminalGeneration = effectiveGenerationState?.isTerminal() == true
        val allNativeSteps = segment.flatMap { it.sourceSteps }
        val streamActive = when {
            terminalGeneration -> false
            allNativeOnly -> when (explicitGenerationState) {
                GenerationState.RUNNING, GenerationState.QUEUED, GenerationState.CANCEL_REQUESTED -> true
                else -> allNativeSteps.any { it.status == NativeExecutionStatus.RUNNING }
            }
            else -> segment.any { it.entry.isStreaming } ||
                explicitGenerationState == GenerationState.RUNNING ||
                explicitGenerationState == GenerationState.QUEUED ||
                explicitGenerationState == GenerationState.CANCEL_REQUESTED ||
                allNativeSteps.any { it.status == NativeExecutionStatus.RUNNING }
        }

        val resolved = segment.map { item ->
            if (item.nativeOnly) {
                item
            } else {
                val existing = item.analysis
                val resolvedAnalysis = if (existing?.blocks == null) {
                    analyseParsed(item.entry, item.sourceSteps, terminalGeneration, streamActive)
                } else {
                    existing
                }
                item.copy(analysis = resolvedAnalysis)
            }
        }

        val finalItem = if (allNativeOnly) {
            resolved.first()
        } else {
            resolved.lastOrNull { it.analysis?.finalVisible == true } ?: resolved.last()
        }
        val finalEntry = finalItem.entry
        val hasFinalAnswer = !allNativeOnly && finalItem.analysis?.finalVisible == true
        val finalBlocks = if (hasFinalAnswer) finalItem.analysis?.finalBlocks.orEmpty() else emptyList()
        val taskKey = generationId?.let { "execution:$it" }

        val steps = mutableListOf<ExecutionStep>()
        var nativeToolOrdinal = 0

        resolved.forEach { item ->
            val entry = item.entry
            if (item.nativeOnly) {
                item.sourceSteps.forEach { step ->
                    val status = if (terminalGeneration && step.status == NativeExecutionStatus.RUNNING) {
                        NativeExecutionStatus.UNKNOWN
                    } else {
                        step.status
                    }
                    steps += ExecutionStep.ToolCall(
                        block = nativeStepToToolCallBlock(step, status),
                        entryKey = taskKey ?: entry.key,
                        blockIndex = nativeToolOrdinal++,
                        entryIsStreaming = streamActive && status == NativeExecutionStatus.RUNNING,
                        nativeStepId = step.stepId,
                        isSnapshot = entry.presentation?.origin == StreamPresentationOrigin.SNAPSHOT,
                    )
                }
                return@forEach
            }

            val analysis = requireNotNull(item.analysis)
            val blocks = requireNotNull(analysis.blocks)
            val isFinalEntry = entry.key == finalEntry.key && item === finalItem
            if ((!isFinalEntry || !hasFinalAnswer) && entry.attachments.isNotEmpty()) {
                steps += ExecutionStep.Attachments(entry)
            }
            val endExclusive = if (isFinalEntry) analysis.tailStartIndex else blocks.size
            var thoughtOrdinal = 0
            blocks.forEachIndexed { index, ordered ->
                if (index >= endExclusive) return@forEachIndexed
                val block = ordered.block
                if (block is TimelineBlock.Paragraph && block.text.isBlank()) return@forEachIndexed
                steps += when (ordered) {
                    is OrderedTimelineBlock.Thought -> ExecutionStep.Thought(
                        block = ordered.block,
                        entryKey = entry.key,
                        blockIndex = index,
                        entryIsStreaming = entry.isStreaming && streamActive,
                        thoughtKey = "${entry.key}:thought:${thoughtOrdinal++}",
                        stepId = ordered.stepId,
                    )

                    is OrderedTimelineBlock.ToolCall -> {
                        val isNative = ordered.nativeStepId != null
                        val toolRunning = ordered.block.isRunning
                        ExecutionStep.ToolCall(
                            block = ordered.block,
                            entryKey = if (isNative) (taskKey ?: entry.key) else entry.key,
                            blockIndex = if (isNative) nativeToolOrdinal++ else index,
                            entryIsStreaming = if (isNative) {
                                streamActive && toolRunning
                            } else {
                                entry.isStreaming && streamActive
                            },
                            nativeStepId = ordered.nativeStepId,
                            isSnapshot = entry.presentation?.origin == StreamPresentationOrigin.SNAPSHOT,
                        )
                    }

                    is OrderedTimelineBlock.Other -> ExecutionStep.Narrative(
                        block = block,
                        entryKey = entry.key,
                    )
                }
            }
        }

        val stepCount = steps.count { it is ExecutionStep.Thought || it is ExecutionStep.ToolCall }
        val failedStepCount = if (allNativeOnly) {
            allNativeSteps.count { it.status == NativeExecutionStatus.FAILED }
        } else {
            steps.count { step ->
                when (step) {
                    is ExecutionStep.ToolCall -> !step.block.isSuccess || step.isInterruptedStep()
                    is ExecutionStep.Thought -> step.isInterruptedStep()
                    else -> false
                }
            }
        }
        val interrupted = if (allNativeOnly) false else steps.any { it.isInterruptedStep() }

        val firstItem = segment.first()
        val firstSourceMessageId = firstItem.sourceSteps.firstOrNull()?.sourceMessageId?.takeIf { it.isNotBlank() }
        val segmentKey = if (generationId != null && firstSourceMessageId != null) {
            "execution:$generationId:$firstSourceMessageId"
        } else {
            firstItem.entry.key
        }

        return AssistantExecutionRun(
            segmentKey = segmentKey,
            finalEntry = if (!streamActive && finalEntry.isStreaming && !allNativeOnly) finalEntry.copy(isStreaming = false) else finalEntry,
            finalBlocks = finalBlocks,
            steps = steps,
            stepCount = stepCount,
            failedStepCount = failedStepCount,
            interrupted = interrupted,
            isStreaming = streamActive,
            finalEntryIsStreaming = !allNativeOnly && finalEntry.isStreaming && streamActive,
            hasFinalAnswer = hasFinalAnswer,
            generationId = generationId,
        )
    }

    /** 单条助手消息的解析结果与「尾部正文」定位。 */
    private data class EntryAnalysis(
        val entry: TimelineEntry,
        /** null 表示没有执行标记、尚未完整解析；只在段包含执行块时解析。 */
        val blocks: List<OrderedTimelineBlock>?,
        val executionCount: Int,
        val tailStartIndex: Int,
        val finalBlocks: List<TimelineBlock>,
        val finalVisible: Boolean,
    )

    /**
     * 先做廉价预扫描：没有执行标记和结构化步骤的消息只记录“是否有可见正文”，不解析 Markdown。
     */
    private fun analyse(
        entry: TimelineEntry,
        sourceSteps: List<NativeExecutionStep>,
        generation: GenerationState?,
    ): EntryAnalysis {
        if (entry.timelineSteps.isEmpty() &&
            entry.reasoning.isEmpty() &&
            sourceSteps.isEmpty() &&
            !mayContainExecutionMarkup(entry.text)
        ) {
            return EntryAnalysis(
                entry = entry,
                blocks = null,
                executionCount = 0,
                tailStartIndex = 0,
                finalBlocks = emptyList(),
                finalVisible = entry.text.isNotBlank(),
            )
        }
        val generationState = entry.executionGenerationState ?: generation
        val terminalGeneration = generationState?.isTerminal() == true
        val streamActive = !terminalGeneration && (
            entry.isStreaming ||
                generationState == GenerationState.RUNNING ||
                generationState == GenerationState.QUEUED ||
                generationState == GenerationState.CANCEL_REQUESTED ||
                sourceSteps.any { it.status == NativeExecutionStatus.RUNNING }
            )
        return analyseParsed(entry, sourceSteps, terminalGeneration, streamActive)
    }

    private fun analyseParsed(
        entry: TimelineEntry,
        sourceSteps: List<NativeExecutionStep>,
        terminalGeneration: Boolean,
        streamActive: Boolean,
    ): EntryAnalysis {
        val blocks = parseEntry(entry, sourceSteps, terminalGeneration, streamActive)
        var lastExecutionIndex = -1
        var executionCount = 0
        blocks.forEachIndexed { index, ordered ->
            if (isExecutionBlock(ordered.block)) {
                lastExecutionIndex = index
                executionCount++
            }
        }
        val tailStartIndex = if (lastExecutionIndex < 0) 0 else lastExecutionIndex + 1
        val finalBlocks = if (tailStartIndex <= blocks.lastIndex) {
            blocks.subList(tailStartIndex, blocks.size).map { it.block }
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

    private fun parseEntry(
        entry: TimelineEntry,
        sourceSteps: List<NativeExecutionStep>,
        terminalGeneration: Boolean,
        streamActive: Boolean,
    ): List<OrderedTimelineBlock> {
        val cacheKey = "${entry.key}@${entry.text.length}@${entry.text.hashCode()}@" +
            "${entry.reasoning.hashCode()}@${entry.timelineSteps.hashCode()}@${sourceSteps.hashCode()}@" +
            "$terminalGeneration@$streamActive"
        parsedCache[cacheKey]?.let { return it }

        val blocks = runCatching {
            buildOrderedEntryBlocks(
                entry = entry,
                nativeSteps = sourceSteps,
                terminalGeneration = terminalGeneration,
                streamActive = streamActive,
                parse = parse,
            )
        }.getOrElse {
            if (entry.text.isBlank()) emptyList() else listOf(OrderedTimelineBlock.Other(TimelineBlock.Paragraph(entry.text)))
        }.let { parsed ->
            if (parsed.isEmpty() && entry.text.isNotBlank()) {
                listOf(OrderedTimelineBlock.Other(TimelineBlock.Paragraph(entry.text)))
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

private fun normalizeNativeStepId(stepId: String): String =
    ToolCallPairing.normalizeCallId(stepId) ?: stepId

private fun TimelineEntry.allNativeSteps(): List<NativeExecutionStep> {
    if (timelineSteps.isEmpty()) {
        val combined = linkedMapOf<String, NativeExecutionStep>()
        executionSteps.forEach { rawStep ->
            val cleanId = normalizeNativeStepId(rawStep.stepId)
            val step = if (rawStep.stepId == cleanId) rawStep else rawStep.copy(stepId = cleanId)
            val existing = combined[cleanId]
            combined[cleanId] = existing?.applyNewerSnapshot(step) ?: step
        }
        return combined.values.toList()
    }
    val combined = linkedMapOf<String, NativeExecutionStep>()
    timelineSteps.forEach { step ->
        if (step is ExecutionTimelineStep.ToolCallStep) {
            val cleanId = normalizeNativeStepId(step.step.stepId)
            val normalized = if (step.step.stepId == cleanId) step.step else step.step.copy(stepId = cleanId)
            val existing = combined[cleanId]
            combined[cleanId] = existing?.applyNewerSnapshot(normalized) ?: normalized
        }
    }
    executionSteps.forEach { rawStep ->
        val cleanId = normalizeNativeStepId(rawStep.stepId)
        val step = if (rawStep.stepId == cleanId) rawStep else rawStep.copy(stepId = cleanId)
        val existing = combined[cleanId]
        combined[cleanId] = existing?.applyNewerSnapshot(step) ?: step
    }
    return combined.values.toList()
}

private fun nativeStepToToolCallBlock(
    step: NativeExecutionStep,
    effectiveStatus: NativeExecutionStatus = step.status,
): TimelineBlock.ToolCallBlock = TimelineBlock.ToolCallBlock(
    toolName = step.toolName,
    command = step.arguments,
    output = step.output,
    isSuccess = effectiveStatus != NativeExecutionStatus.FAILED,
    isRunning = effectiveStatus == NativeExecutionStatus.RUNNING,
    executionStatus = effectiveStatus,
)

private fun wrapParsedBlocks(blocks: List<TimelineBlock>): List<OrderedTimelineBlock> =
    blocks.filter { it !is TimelineBlock.Paragraph || it.text.isNotBlank() }.map { block ->
        when (block) {
            is TimelineBlock.ThoughtBlock -> OrderedTimelineBlock.Thought(block)
            is TimelineBlock.ToolCallBlock -> OrderedTimelineBlock.ToolCall(block)
            else -> OrderedTimelineBlock.Other(block)
        }
    }

/**
 * 统一单条消息的有序异构块构建器：
 * - 优先使用 [TimelineEntry.timelineSteps] 按 `(bodyOffset, orderIndex)` 单循环交错归并思考、工具与正文切片；
 * - 对旧数据（仅携带 `reasoning` / `executionSteps` 而无 `timelineSteps`）自动归一化为同一有序列表再单循环构建。
 */
internal fun buildOrderedEntryBlocks(
    entry: TimelineEntry,
    nativeSteps: List<NativeExecutionStep> = entry.executionSteps,
    terminalGeneration: Boolean = entry.executionGenerationState?.isTerminal() == true,
    streamActive: Boolean = entry.isStreaming && !terminalGeneration,
    parse: (String) -> List<TimelineBlock> = MarkdownParser::parse,
): List<OrderedTimelineBlock> {
    val nativeById = nativeSteps.associateBy { normalizeNativeStepId(it.stepId) }
    val unifiedSteps: List<ExecutionTimelineStep> = when {
        entry.timelineSteps.isNotEmpty() -> {
            val baseSteps = if (entry.timelineSteps.none { it is ExecutionTimelineStep.ThinkingStep } && entry.reasoning.isNotEmpty()) {
                val fromReasoning = entry.reasoning.mapIndexed { idx, r ->
                    ExecutionTimelineStep.ThinkingStep(
                        stepId = r.id,
                        text = r.text,
                        bodyOffset = r.bodyOffset,
                        complete = r.complete,
                        orderIndex = idx,
                    )
                }
                val shift = fromReasoning.size
                fromReasoning + entry.timelineSteps.map { step ->
                    when (step) {
                        is ExecutionTimelineStep.ThinkingStep -> step.copy(orderIndex = step.orderIndex + shift)
                        is ExecutionTimelineStep.ToolCallStep -> step.copy(orderIndex = step.orderIndex + shift)
                    }
                }
            } else {
                entry.timelineSteps
            }
            val existingToolIds = baseSteps.filterIsInstance<ExecutionTimelineStep.ToolCallStep>()
                .mapTo(hashSetOf()) { normalizeNativeStepId(it.stepId) }
            val extraNative = nativeSteps.filterNot { normalizeNativeStepId(it.stepId) in existingToolIds }
            if (extraNative.isEmpty()) {
                baseSteps
            } else {
                val baseOrder = (baseSteps.maxOfOrNull { it.orderIndex } ?: -1) + 1
                val defaultOffset = baseSteps.maxOfOrNull { it.bodyOffset } ?: 0
                baseSteps + extraNative.mapIndexed { idx, step ->
                    ExecutionTimelineStep.ToolCallStep(step = step, bodyOffset = defaultOffset, orderIndex = baseOrder + idx)
                }
            }
        }
        entry.reasoning.isNotEmpty() || nativeSteps.isNotEmpty() -> {
            val fromReasoning = entry.reasoning.mapIndexed { idx, r ->
                ExecutionTimelineStep.ThinkingStep(
                    stepId = r.id,
                    text = r.text,
                    bodyOffset = r.bodyOffset,
                    complete = r.complete,
                    orderIndex = idx,
                )
            }
            val baseOrder = fromReasoning.size
            val defaultOffset = fromReasoning.maxOfOrNull { it.bodyOffset } ?: 0
            val fromTools = nativeSteps.mapIndexed { idx, step ->
                ExecutionTimelineStep.ToolCallStep(
                    step = step,
                    bodyOffset = defaultOffset,
                    orderIndex = baseOrder + idx,
                )
            }
            fromReasoning + fromTools
        }
        else -> emptyList()
    }

    if (unifiedSteps.isEmpty()) {
        return wrapParsedBlocks(parse(entry.text))
    }

    val sortedSteps = unifiedSteps.sortedWith(
        compareBy<ExecutionTimelineStep>({ it.bodyOffset }, { it.orderIndex }),
    )
    val result = mutableListOf<OrderedTimelineBlock>()
    var offset = 0
    sortedSteps.forEach { step ->
        val next = step.bodyOffset.coerceIn(offset, entry.text.length)
        if (next > offset) {
            val textSlice = entry.text.substring(offset, next)
            if (textSlice.isNotBlank()) {
                result += wrapParsedBlocks(parse(textSlice))
            }
        }
        when (step) {
            is ExecutionTimelineStep.ThinkingStep -> {
                result += OrderedTimelineBlock.Thought(
                    block = TimelineBlock.ThoughtBlock(step.text, step.complete),
                    stepId = step.stepId,
                )
            }

            is ExecutionTimelineStep.ToolCallStep -> {
                val resolvedStep = nativeById[normalizeNativeStepId(step.stepId)]?.let { latest ->
                    step.step.applyNewerSnapshot(latest)
                } ?: step.step
                val effectiveStatus = if (terminalGeneration && resolvedStep.status == NativeExecutionStatus.RUNNING) {
                    NativeExecutionStatus.UNKNOWN
                } else {
                    resolvedStep.status
                }
                result += OrderedTimelineBlock.ToolCall(
                    block = nativeStepToToolCallBlock(resolvedStep, effectiveStatus),
                    nativeStepId = resolvedStep.stepId,
                    entryStreamingOverride = streamActive && effectiveStatus == NativeExecutionStatus.RUNNING,
                )
            }
        }
        offset = next
    }
    if (offset < entry.text.length) {
        val trailing = entry.text.substring(offset)
        if (trailing.isNotBlank()) {
            result += wrapParsedBlocks(parse(trailing))
        }
    }
    return result
}

/** 统一有序块投影：供流式思考驱动与测试共用同一块序，不再拆分思考与工具。 */
internal fun structuredTimelineBlocks(
    entry: TimelineEntry,
    parse: (String) -> List<TimelineBlock> = MarkdownParser::parse,
): List<TimelineBlock> = buildOrderedEntryBlocks(
    entry = entry,
    nativeSteps = if (entry.timelineSteps.isNotEmpty()) entry.executionSteps else emptyList(),
    terminalGeneration = entry.executionGenerationState?.isTerminal() == true,
    streamActive = entry.isStreaming,
    parse = parse,
).map { it.block }

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
