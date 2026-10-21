package com.openandroidintelligence.conversation.workbench

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.openandroidintelligence.conversation.state.TimelineEntry

/**
 * 时间线展示项：要么是单条消息（普通回答 / 用户消息 / 审批 / 系统回执），
 * 要么是一段被折叠的助手执行过程（规格 §3.1）。
 */
sealed interface TimelineDisplayItem {
    val key: String

    data class Single(val entry: TimelineEntry) : TimelineDisplayItem {
        /** 单条消息沿用消息自身的 key，宿主的滚动/新鲜项跟踪语义不变。 */
        override val key: String get() = entry.key
    }

    data class Execution(val run: AssistantExecutionRun) : TimelineDisplayItem {
        /** 用段身份而不是最终回答 key：finalEntry 变化时展示项不应被当成新条目。 */
        override val key: String get() = run.segmentKey
    }
}

/**
 * 一轮助手执行过程的聚合结果。
 *
 * [finalBlocks] 是 [finalEntry] 里「尾部正文之前」的最终回答块；
 * 没有最终正文时为空，宿主不应伪造回答占位。
 */
data class AssistantExecutionRun(
    val key: String,
    /**
     * 段身份：段内第一条助手消息的 key。
     *
     * 聚合面板的展开偏好与 LazyColumn 的展示项 key 用这个字段，而不是随最终回答
     * 变化的 [key]；否则多消息回合里 finalEntry 换成后一条时，面板会被当成新项。
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
)

/**
 * 折叠进执行过程面板的一个步骤，按时间顺序展平。
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
    ) : ExecutionStep {
        override val stepKey: String get() = "$entryKey#$blockIndex"
    }

    data class ToolCall(
        val block: TimelineBlock.ToolCallBlock,
        override val entryKey: String,
        val blockIndex: Int,
        val entryIsStreaming: Boolean,
    ) : ExecutionStep {
        override val stepKey: String get() = "$entryKey#$blockIndex"
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
 * 时间线展示模型：把连续的助手消息折叠成执行段，其余条目保持单条呈现。
 *
 * 规则严格对齐规格 §3.1：
 * 1. 用户消息、审批行、系统回执行是 [TimelineDisplayItem.Single]，并打断连续助手段；
 * 2. 段内至少有一个思考/工具块，且（已出现尾部正文 或 整段不再流式）才折叠；
 * 3. 普通多条助手消息（无执行块）不聚合；
 * 4. 折叠时 `finalEntry` 取最后一条有尾部正文的助手消息，没有则取段内最后一条；
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

    /**
     * 当前仍在增量的执行段里，是否已经出现过最终正文。
     *
     * 只用一段 key：同一时刻只可能有一轮在流式；历史段不需要这个标记。
     * 这样「片段 → 工具块 → 片段」的多步输出不会在折叠/展开之间来回跳。
     */
    private var latchedFinalSegmentKey: String? = null

    /** 当前缓存条数（测试用于验证上限）。 */
    internal val cachedEntryCount: Int get() = parsedCache.size

    /** 纯函数入口：不依赖 Compose，测试可直接驱动折叠矩阵。 */
    fun build(entries: List<TimelineEntry>): List<TimelineDisplayItem> {
        if (entries.isEmpty()) return emptyList()

        val items = mutableListOf<TimelineDisplayItem>()
        val segment = mutableListOf<TimelineEntry>()

        fun flushSegment() {
            if (segment.isEmpty()) return
            // 只有最后一段会继续增量；历史段不需要「已开始最终回答」的粘滞状态。
            val isLastSegment = segment.last().key == entries.last().key
            items += buildSegment(segment.toList(), isLastSegment)
            segment.clear()
        }

        entries.forEach { entry ->
            if (isExecutionEligible(entry)) {
                segment += entry
            } else {
                flushSegment()
                items += TimelineDisplayItem.Single(entry)
            }
        }
        flushSegment()
        return items
    }

    private fun buildSegment(entries: List<TimelineEntry>, isLastSegment: Boolean): List<TimelineDisplayItem> {
        val analyses = entries.map(::analyse)
        val hasExecutionBlocks = analyses.any { it.executionCount > 0 }
        val anyFinalVisible = analyses.any { it.finalVisible }
        val anyStreaming = entries.any { it.isStreaming }
        val segmentKey = entries.first().key
        // 一旦这一段出现过最终正文，就不能因为后续又出现一个工具块而把已折叠的
        // 面板重新展开：展开/收起来回跳比晚一点显示更糟。这个粘滞标记只作用于
        // 仍在增量的最后一段，历史段由 anyFinalVisible / !anyStreaming 自然覆盖。
        val stickyFinalStarted = isLastSegment && latchedFinalSegmentKey == segmentKey
        if (isLastSegment && anyFinalVisible) latchedFinalSegmentKey = segmentKey
        val shouldFold = hasExecutionBlocks && (anyFinalVisible || stickyFinalStarted || !anyStreaming)

        if (!shouldFold) {
            return entries.map { TimelineDisplayItem.Single(it) }
        }
        return listOf(TimelineDisplayItem.Execution(buildRun(analyses)))
    }

    private fun buildRun(analyses: List<EntryAnalysis>): AssistantExecutionRun {
        // 只有需要折叠的段才解析完整块；普通助手消息在 [analyse] 里走快速路径。
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
            blocks.forEachIndexed { index, block ->
                if (index >= endExclusive) return@forEachIndexed
                steps += when (block) {
                    is TimelineBlock.ThoughtBlock -> ExecutionStep.Thought(
                        block = block,
                        entryKey = analysis.entry.key,
                        blockIndex = index,
                        entryIsStreaming = analysis.entry.isStreaming,
                    )

                    is TimelineBlock.ToolCallBlock -> ExecutionStep.ToolCall(
                        block = block,
                        entryKey = analysis.entry.key,
                        blockIndex = index,
                        entryIsStreaming = analysis.entry.isStreaming,
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
            key = "execution:${finalEntry.key}",
            segmentKey = "execution_segment:${analyses.first().entry.key}",
            finalEntry = finalEntry,
            finalBlocks = if (hasFinalAnswer) finalAnalysis.finalBlocks else emptyList(),
            steps = steps,
            stepCount = stepCount,
            failedStepCount = failedStepCount,
            interrupted = interrupted,
            isStreaming = analyses.any { it.entry.isStreaming },
            finalEntryIsStreaming = finalEntry.isStreaming,
            hasFinalAnswer = hasFinalAnswer,
        )
    }

    /** 单条助手消息的解析结果与「尾部正文」定位。 */
    private data class EntryAnalysis(
        val entry: TimelineEntry,
        /** null 表示没有执行标记、尚未完整解析；只在段需要折叠时解析。 */
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
     * 而普通消息只有在所属执行段真的需要折叠时才需要完整块（叙述内容）。
     */
    private fun analyse(entry: TimelineEntry): EntryAnalysis {
        if (!mayContainExecutionMarkup(entry.text)) {
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
        val cacheKey = "${entry.key}@${entry.text.length}@${entry.text.hashCode()}"
        parsedCache[cacheKey]?.let { return it }

        val blocks = runCatching { parse(entry.text) }
            .getOrElse { listOf(TimelineBlock.Paragraph(entry.text)) }
            .ifEmpty { listOf(TimelineBlock.Paragraph(entry.text)) }

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

/**
 * 时间线展示项缓存：同一批 entries 只重算一次，会话切换 / 流式增量时重建。
 */
@Composable
fun rememberTimelineDisplayItems(
    entries: List<TimelineEntry>,
    threadId: String? = null,
): List<TimelineDisplayItem> {
    // 缓存与粘滞状态都属于一段会话：换会话必须重建，避免跨会话 id 命中/误折叠。
    val model = remember(threadId) { TimelineDisplayModel() }
    return remember(entries) { model.build(entries) }
}

/** 能进入执行段的消息：既不是用户消息，也不是审批/系统回执行。 */
private fun isExecutionEligible(entry: TimelineEntry): Boolean =
    !entry.isUser && entry.approval == null && entry.systemThreadId == null

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

/** 执行块：思考与工具调用。步骤计数、折叠条件都以它为准。 */
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
