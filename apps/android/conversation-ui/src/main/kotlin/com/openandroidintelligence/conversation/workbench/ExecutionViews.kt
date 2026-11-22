package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.theme.Dimensions

/**
 * 单条助手消息的正文内容（附件、元数据、复制由宿主渲染，规格 §4.4）。
 *
 * - 正文为空且仍在流式：只显示流式光标；
 * - 正文为空、已结束且无步骤：不渲染占位空气泡；
 * - 没有执行块：保持既有 [MarkdownDocumentView] 的普通正文渲染；
 * - 有执行块（思考 / 原生工具步骤 / 标记工具）：统一构建有序异构步骤列表并单循环平铺渲染，末尾保留流式光标。
 */
@Composable
fun AssistantMessageContent(
    entry: TimelineEntry,
    modifier: Modifier = Modifier,
    presentations: StreamingPresentationRegistry? = null,
) {
    val text = entry.text
    val hasStructuredSteps = entry.timelineSteps.isNotEmpty() || entry.reasoning.isNotEmpty() || entry.executionSteps.isNotEmpty()
    if (text.isBlank() && !hasStructuredSteps) {
        if (entry.isStreaming && (presentations == null || presentations.frameFor(entry)?.active == true)) {
            StreamingCursor(
                modifier = modifier.padding(vertical = Dimensions.SpaceTiny),
            )
        }
        return
    }

    if (!hasStructuredSteps && !mayContainExecutionMarkup(text)) {
        StreamingReplyBody(entry, presentations, modifier = modifier) { shownBlocks, presenting ->
            MarkdownDocumentView(blocks = shownBlocks, isStreaming = presenting)
        }
        return
    }

    val terminalGeneration = entry.executionGenerationState?.isTerminal() == true
    val orderedBlocks = remember(text, entry.reasoning, entry.executionSteps, entry.timelineSteps, entry.isStreaming, terminalGeneration) {
        runCatching {
            buildOrderedEntryBlocks(
                entry = entry,
                nativeSteps = entry.executionSteps,
                terminalGeneration = terminalGeneration,
                streamActive = entry.isStreaming && !terminalGeneration,
            )
        }.getOrElse {
            if (text.isBlank()) emptyList() else listOf(OrderedTimelineBlock.Other(TimelineBlock.Paragraph(text)))
        }
    }

    if (orderedBlocks.none { isExecutionBlock(it.block) }) {
        StreamingReplyBody(entry, presentations, modifier = modifier) { shownBlocks, presenting ->
            MarkdownDocumentView(blocks = shownBlocks, isStreaming = presenting)
        }
        return
    }

    val groups = remember(orderedBlocks) { groupRealtimeOrderedBlocks(orderedBlocks) }
    var thoughtOrdinal = 0
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        groups.forEachIndexed { groupIndex, group ->
            when (group) {
                is RealtimeGroup.Card -> {
                    val item = group.item
                    val timingKey = when (item) {
                        is OrderedTimelineBlock.ToolCall ->
                            item.nativeStepId?.let { "${entry.key}:$it" } ?: "${entry.key}#${group.blockIndex}"
                        else -> "${entry.key}#${group.blockIndex}"
                    }
                    when (item) {
                        is OrderedTimelineBlock.Thought -> MarkdownThoughtBlockView(
                            block = item.block,
                            isStreaming = entry.isStreaming && !terminalGeneration,
                            timingKey = timingKey,
                            thoughtKey = "${entry.key}:thought:${thoughtOrdinal++}",
                            presentations = presentations,
                        )

                        is OrderedTimelineBlock.ToolCall -> MarkdownToolCallView(
                            block = item.block,
                            isStreaming = item.entryStreamingOverride ?: (entry.isStreaming && !terminalGeneration),
                            timingKey = timingKey,
                        )

                        is OrderedTimelineBlock.Other -> Unit
                    }
                }

                is RealtimeGroup.Narrative -> CompositionLocalProvider(
                    LocalMarkdownCompact provides true,
                ) {
                    MarkdownDocumentView(
                        blocks = group.blocks,
                        isStreaming = entry.isStreaming && !terminalGeneration && groupIndex == groups.lastIndex,
                    )
                }
            }
        }

        // 卡片自身没有实时指示时（例如思考已闭合、消息仍在流式），末尾补一个光标，
        // 让「还在输出」这件事始终看得见。
        val lastBlock = orderedBlocks.lastOrNull()?.block
        val shouldShowTrailingCursor = entry.isStreaming &&
            !terminalGeneration &&
            lastBlock != null &&
            isExecutionBlock(lastBlock) &&
            !cardShowsOwnLiveIndicator(lastBlock, entry.isStreaming)
        if (shouldShowTrailingCursor) {
            StreamingCursor(modifier = Modifier.padding(top = Dimensions.SpaceTiny))
        }
    }
}

/**
 * 执行轮次的「按真实发生时序平铺步骤 + 最终回答」主体（规格 §4.4 / R3）。
 *
 * 彻底移除外层集中折叠面板，严格按 [AssistantExecutionRun.steps] 的先后顺序直接渲染
 * 各自独立的思考卡、工具卡、紧凑叙述与过程附件；
 * 只有 [AssistantExecutionRun.hasFinalAnswer] 为 false 时，最终消息附件才会作为过程附件
 * 出现在 `run.steps` 里，由 [processAttachmentsSlot] 渲染，避免宿主重复渲染。
 */
@Composable
fun AssistantExecutionTurnBody(
    run: AssistantExecutionRun,
    modifier: Modifier = Modifier,
    presentations: StreamingPresentationRegistry? = null,
    finalAnswerSlot: @Composable (List<TimelineBlock>, Boolean) -> Unit = { blocks, streaming ->
        MarkdownDocumentView(blocks = blocks, isStreaming = streaming)
    },
    processAttachmentsSlot: @Composable (TimelineEntry) -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        run.steps.forEachIndexed { index, step ->
            key(step.stepKey ?: "${step.entryKey}:$index") {
                when (step) {
                    is ExecutionStep.Thought -> MarkdownThoughtBlockView(
                        block = step.block,
                        isStreaming = run.isStreaming && step.entryIsStreaming,
                        timingKey = step.stepKey,
                        thoughtKey = step.thoughtKey,
                        presentations = presentations,
                    )

                    is ExecutionStep.ToolCall -> MarkdownToolCallView(
                        block = step.block,
                        isStreaming = run.isStreaming && step.entryIsStreaming,
                        timingKey = step.stepKey,
                    )

                    is ExecutionStep.Narrative -> CompositionLocalProvider(
                        LocalMarkdownCompact provides true,
                    ) {
                        MarkdownDocumentView(
                            blocks = listOf(step.block),
                            isStreaming = false,
                        )
                    }

                    is ExecutionStep.Attachments -> processAttachmentsSlot(step.entry)
                }
            }
        }

        val lastStep = run.steps.lastOrNull()
        val lastShowsLiveIndicator = when (lastStep) {
            is ExecutionStep.Thought -> run.isStreaming && lastStep.entryIsStreaming && !lastStep.block.isComplete
            is ExecutionStep.ToolCall -> run.isStreaming && lastStep.entryIsStreaming && lastStep.block.isRunning
            else -> false
        }
        if (run.isStreaming && !run.hasFinalAnswer && !lastShowsLiveIndicator && run.steps.isNotEmpty()) {
            StreamingCursor(modifier = Modifier.padding(top = Dimensions.SpaceTiny))
        }

        if (run.hasFinalAnswer && run.finalBlocks.isNotEmpty()) {
            // 最终回答是真正的回答，保持普通（非紧凑）Markdown 渲染；
            // 只有整轮仍在流式时才把光标交给正文。
            finalAnswerSlot(run.finalBlocks, run.finalEntryIsStreaming)
        }
    }
}

/** 实时渲染的分组：思维链/工具卡各占一组，连续的非执行块合并成一段紧凑叙述。 */
private sealed interface RealtimeGroup {
    data class Card(val item: OrderedTimelineBlock, val blockIndex: Int) : RealtimeGroup
    data class Narrative(val blocks: List<TimelineBlock>) : RealtimeGroup
}

private fun groupRealtimeOrderedBlocks(blocks: List<OrderedTimelineBlock>): List<RealtimeGroup> {
    val groups = mutableListOf<RealtimeGroup>()
    val narrative = mutableListOf<TimelineBlock>()

    fun flushNarrative() {
        if (narrative.isEmpty()) return
        val visibleBlocks = narrative.filter { block ->
            when (block) {
                is TimelineBlock.Paragraph -> block.text.isNotBlank()
                else -> true
            }
        }
        if (visibleBlocks.isNotEmpty()) {
            groups += RealtimeGroup.Narrative(visibleBlocks)
        }
        narrative.clear()
    }

    blocks.forEachIndexed { index, item ->
        if (isExecutionBlock(item.block)) {
            flushNarrative()
            groups += RealtimeGroup.Card(item = item, blockIndex = index)
        } else {
            val block = item.block
            if (block !is TimelineBlock.Paragraph || block.text.isNotBlank()) {
                narrative += block
            }
        }
    }
    flushNarrative()
    return groups
}

/** 卡片是否自带实时指示（思考未闭合的光标 / 工具的运行点）。 */
private fun cardShowsOwnLiveIndicator(block: TimelineBlock, isStreaming: Boolean): Boolean = when (block) {
    is TimelineBlock.ThoughtBlock -> isStreaming && !block.isComplete
    is TimelineBlock.ToolCallBlock -> isStreaming && block.isRunning
    else -> false
}
