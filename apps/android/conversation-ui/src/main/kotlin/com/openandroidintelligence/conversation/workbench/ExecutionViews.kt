package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.openandroidintelligence.conversation.components.M3ExpandableExpressiveCard
import com.openandroidintelligence.conversation.components.rememberExpandChevronRotation
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalAppStateColors
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.isReduced
import com.openandroidintelligence.ui.resources.R as UiR

/**
 * 单条助手消息的正文内容（附件、元数据、复制由宿主渲染，规格 §4.4）。
 *
 * - 正文为空且仍在流式：只显示流式光标；
 * - 正文为空、已结束且无步骤：不渲染占位空气泡；
 * - 没有执行块：保持既有 [MarkdownDocumentView] 的普通正文渲染；
 * - 有执行块（思考 / 原生工具步骤 / 标记工具）：
 *   - 流式执行中或未输出最终正文：按真实顺序实时平铺渲染思考卡、工具卡与中间叙述；
 *   - 回合已结束且已输出最终正文：按回合折叠规则收拢进默认折叠的过程大卡片（仅单张思考卡时保留单卡避免嵌套）。
 */
@Composable
fun AssistantMessageContent(
    entry: TimelineEntry,
    modifier: Modifier = Modifier,
    presentations: StreamingPresentationRegistry? = null,
) {
    val text = entry.text
    val hasStructuredSteps = entry.hasExecutionSteps || entry.hasReasoningSteps
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
    val entryStreaming = entry.isStreaming && !terminalGeneration
    val orderedBlocks = remember(text, entry.reasoning, entry.executionSteps, entry.timelineSteps, entry.isStreaming, terminalGeneration) {
        runCatching {
            buildOrderedEntryBlocks(
                entry = entry,
                nativeSteps = entry.executionSteps,
                terminalGeneration = terminalGeneration,
                streamActive = entryStreaming,
            )
        }.getOrElse {
            if (text.isBlank()) emptyList() else listOf(OrderedTimelineBlock.Other(TimelineBlock.Paragraph(text)))
        }
    }

    val lastExecutionIndex = orderedBlocks.indexOfLast { isExecutionBlock(it.block) }
    if (lastExecutionIndex < 0) {
        StreamingReplyBody(entry, presentations, modifier = modifier) { shownBlocks, presenting ->
            MarkdownDocumentView(blocks = shownBlocks, isStreaming = presenting)
        }
        return
    }

    val stepBlocks = remember(orderedBlocks, lastExecutionIndex) {
        orderedBlocks.subList(0, lastExecutionIndex + 1)
    }
    val finalBlocks = remember(orderedBlocks, lastExecutionIndex) {
        if (lastExecutionIndex + 1 < orderedBlocks.size) {
            orderedBlocks.subList(lastExecutionIndex + 1, orderedBlocks.size)
                .map { it.block }
                .filter { it !is TimelineBlock.Paragraph || it.text.isNotBlank() }
        } else {
            emptyList()
        }
    }
    val groups = remember(stepBlocks) { groupRealtimeOrderedBlocks(stepBlocks) }
    val toolCallCount = remember(stepBlocks) { stepBlocks.count { it is OrderedTimelineBlock.ToolCall } }
    val thoughtCount = remember(stepBlocks) { stepBlocks.count { it is OrderedTimelineBlock.Thought } }
    val otherProcessCount = remember(groups) { groups.count { it is RealtimeGroup.Narrative } }
    val hasFailure = remember(stepBlocks, entryStreaming) {
        stepBlocks.any { item ->
            when (item) {
                is OrderedTimelineBlock.ToolCall -> {
                    val stepStreaming = item.entryStreamingOverride ?: entryStreaming
                    !item.block.isSuccess || (item.block.isRunning && !stepStreaming)
                }
                is OrderedTimelineBlock.Thought -> !item.block.isComplete && !entryStreaming
                else -> false
            }
        }
    }
    val collapseIntoSummaryCard = shouldCollapseTurnProcessCard(
        isStreaming = entryStreaming,
        hasCompletedFinalAnswer = !entryStreaming && (finalBlocks.isNotEmpty() || entry.attachments.isNotEmpty()),
        toolCallCount = toolCallCount,
        thoughtCount = thoughtCount,
        otherProcessCount = otherProcessCount,
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        if (collapseIntoSummaryCard) {
            TurnProcessCollapseCard(
                segmentKey = entry.key,
                toolCallCount = toolCallCount,
                hasFailure = hasFailure,
            ) {
                OrderedRealtimeGroupsContent(
                    entryKey = entry.key,
                    groups = groups,
                    entryStreaming = false,
                    presentations = presentations,
                    suppressToolEntrance = true,
                )
            }
        } else {
            OrderedRealtimeGroupsContent(
                entryKey = entry.key,
                groups = groups,
                entryStreaming = entryStreaming,
                presentations = presentations,
                suppressToolEntrance = false,
            )
        }

        // 卡片自身没有实时指示且尚无尾部最终正文时，末尾补一个光标，
        // 让「还在输出」这件事始终看得见。
        val lastBlock = stepBlocks.lastOrNull()?.block
        val shouldShowTrailingCursor = finalBlocks.isEmpty() &&
            entryStreaming &&
            lastBlock != null &&
            isExecutionBlock(lastBlock) &&
            !cardShowsOwnLiveIndicator(lastBlock, entry.isStreaming)
        if (shouldShowTrailingCursor) {
            StreamingCursor(modifier = Modifier.padding(top = Dimensions.SpaceTiny))
        }

        if (finalBlocks.isNotEmpty()) {
            // 尾部最终回答保持非紧凑正文样式，并接入 StreamingReplyBody 流式逐字渐显与触觉反馈。
            if (presentations != null) {
                StreamingReplyBody(entry, presentations, finalOnly = true) { shownBlocks, presenting ->
                    MarkdownDocumentView(
                        blocks = if (shownBlocks.isNotEmpty() || presenting) shownBlocks else finalBlocks,
                        isStreaming = presenting,
                    )
                }
            } else {
                MarkdownDocumentView(
                    blocks = finalBlocks,
                    isStreaming = entryStreaming,
                )
            }
        }
    }
}

/**
 * 执行轮次的「过程步骤 + 最终回答」主体：
 * - **流式执行中或未输出最终回答**：严格按 [AssistantExecutionRun.steps] 真实顺序实时平铺渲染
 *   各自独立的思考卡、工具卡、紧凑叙述与过程附件；
 * - **回合结束且已输出最终回答**（`!run.isStreaming && run.hasCompletedFinalAnswer`）：
 *   - `toolCallCount >= 1`：将全部思考、工具调用及中间过程收拢进默认折叠的「N 次工具调用」大卡片，
 *     点击展开后按原顺序呈现全部思考卡与工具卡（子工具卡默认折叠且不重播入场动画）；
 *   - `toolCallCount == 0`：绝不显示「0 次工具调用」；仅有 1 个思考步且无其他过程步时保留单张
 *     默认收起的思考卡（避免折叠卡套折叠卡），多步思考或含中间过程时折叠进「思考过程」大卡片。
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
    val toolCallCount = remember(run.steps) { run.steps.count { it is ExecutionStep.ToolCall } }
    val collapseIntoSummaryCard = shouldCollapseTurnProcessCard(run)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        if (collapseIntoSummaryCard) {
            TurnProcessCollapseCard(
                segmentKey = run.segmentKey,
                toolCallCount = toolCallCount,
                hasFailure = run.failedStepCount > 0 || run.interrupted,
            ) {
                ExecutionStepsList(
                    run = run,
                    presentations = presentations,
                    processAttachmentsSlot = processAttachmentsSlot,
                    suppressToolEntrance = true,
                )
            }
        } else {
            ExecutionStepsList(
                run = run,
                presentations = presentations,
                processAttachmentsSlot = processAttachmentsSlot,
                suppressToolEntrance = false,
            )
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

/**
 * 回合结束后的过程折叠大卡片：
 * 完整复用工具卡片容器视觉（`surfaceContainerHigh` / `onSurface` / `outlineVariant` 1dp 边框 /
 * `AppRadius.Compact -> AppRadius.Medium` 圆角形变）与统一折叠动效令牌（`ExpandableRegion` +
 * `rememberExpandChevronRotation`），展开状态随 [segmentKey] 持久化避免滚动回收丢失。
 */
@Composable
private fun TurnProcessCollapseCard(
    segmentKey: String,
    toolCallCount: Int,
    hasFailure: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduceMotion = LocalMotionPolicy.current.isReduced
    val stateColors = LocalAppStateColors.current
    var expanded by rememberSaveable(segmentKey) { mutableStateOf(false) }

    val title = if (toolCallCount >= 1) {
        pluralStringResource(UiR.plurals.conv_turn_process_tool_calls, toolCallCount, toolCallCount)
    } else {
        stringResource(UiR.string.conv_turn_process_thought_only)
    }
    val leadingIcon = when {
        hasFailure -> Icons.Default.Warning
        toolCallCount >= 1 -> Icons.Default.Build
        else -> Icons.Default.Psychology
    }
    val leadingTint = if (hasFailure) stateColors.warning else MaterialTheme.colorScheme.onSurfaceVariant
    val chevronRotation = rememberExpandChevronRotation(
        expanded = expanded,
        reduced = reduceMotion,
        label = "turn-process-chevron",
    )

    M3ExpandableExpressiveCard(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("turn-process-summary:$segmentKey"),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(Dimensions.StrokeHairline, MaterialTheme.colorScheme.outlineVariant),
        collapsedCornerRadius = AppRadius.Compact,
        expandedCornerRadius = AppRadius.Medium,
        pressedCornerRadius = AppRadius.Medium,
        toggleClickLabel = stringResource(
            if (expanded) UiR.string.conv_turn_process_collapse_label else UiR.string.conv_turn_process_expand_label,
        ),
        toggleStateDescription = stringResource(
            if (expanded) UiR.string.conv_turn_process_state_expanded else UiR.string.conv_turn_process_state_collapsed,
        ),
        header = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimensions.SpaceCompact, vertical = Dimensions.SpaceSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = leadingTint,
                    modifier = Modifier.size(Dimensions.SmallIcon),
                )
                Text(
                    text = title,
                    style = AppTextStyles.Thought,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(Dimensions.SmallIcon)
                        .rotate(chevronRotation),
                )
            }
        },
        expandedContent = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimensions.StrokeHairline)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = Dimensions.SpaceCompact,
                            end = Dimensions.SpaceCompact,
                            bottom = Dimensions.SpaceCompact,
                        ),
                    verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
                    content = content,
                )
            }
        },
    )
}

@Composable
private fun ExecutionStepsList(
    run: AssistantExecutionRun,
    presentations: StreamingPresentationRegistry?,
    processAttachmentsSlot: @Composable (TimelineEntry) -> Unit,
    suppressToolEntrance: Boolean,
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
                    revealKey = toolCallRevealKey(step),
                    animateEntrance = if (suppressToolEntrance) false else null,
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
}

@Composable
private fun OrderedRealtimeGroupsContent(
    entryKey: String,
    groups: List<RealtimeGroup>,
    entryStreaming: Boolean,
    presentations: StreamingPresentationRegistry?,
    suppressToolEntrance: Boolean,
) {
    var thoughtOrdinal = 0
    groups.forEachIndexed { groupIndex, group ->
        when (group) {
            is RealtimeGroup.Card -> {
                val item = group.item
                val timingKey = when (item) {
                    is OrderedTimelineBlock.ToolCall ->
                        orderedToolCallRevealKey(entryKey, item, group.blockIndex)
                    is OrderedTimelineBlock.Thought ->
                        item.stepId?.takeIf { it.isNotBlank() }?.let { "$entryKey:thought:$it" } ?: "$entryKey#${group.blockIndex}"
                    else -> "$entryKey#${group.blockIndex}"
                }
                val currentThoughtKey = if (item is OrderedTimelineBlock.Thought) {
                    "$entryKey:thought:${thoughtOrdinal++}"
                } else null
                key(timingKey) {
                    when (item) {
                        is OrderedTimelineBlock.Thought -> MarkdownThoughtBlockView(
                            block = item.block,
                            isStreaming = entryStreaming,
                            timingKey = timingKey,
                            thoughtKey = currentThoughtKey,
                            presentations = presentations,
                        )

                        is OrderedTimelineBlock.ToolCall -> MarkdownToolCallView(
                            block = item.block,
                            isStreaming = item.entryStreamingOverride ?: entryStreaming,
                            timingKey = timingKey,
                            revealKey = timingKey,
                            animateEntrance = if (suppressToolEntrance) false else null,
                        )

                        is OrderedTimelineBlock.Other -> Unit
                    }
                }
            }

            is RealtimeGroup.Narrative -> key("$entryKey:narrative:$groupIndex") {
                CompositionLocalProvider(
                    LocalMarkdownCompact provides true,
                ) {
                    MarkdownDocumentView(
                        blocks = group.blocks,
                        isStreaming = false,
                    )
                }
            }
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
