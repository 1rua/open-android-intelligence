package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.openandroidintelligence.conversation.components.ExpandableRegion
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalAppStateColors
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/**
 * 单条助手消息的正文内容（附件、元数据、复制由宿主渲染，规格 §4.4）。
 *
 * - 正文为空且仍在流式：只显示流式光标；
 * - 正文为空、已结束且没有附件：显示「此消息没有可显示的文本」；
 * - 没有执行块：保持既有 [MarkdownDocumentView] 的普通正文渲染；
 * - 有执行块且尚未聚合（执行仍在进行、或还没有最终回答）：
 *   逐块渲染实时思维链卡 / 工具卡与紧凑叙述，末尾保留流式光标。
 */
@Composable
fun AssistantMessageContent(entry: TimelineEntry, modifier: Modifier = Modifier) {
    val text = entry.text
    if (text.isBlank()) {
        when {
            entry.isStreaming -> StreamingCursor(
                modifier = modifier.padding(vertical = Dimensions.SpaceTiny),
            )

            entry.attachments.isEmpty() -> Text(
                text = "此消息没有可显示的文本",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = modifier.fillMaxWidth(),
            )

            else -> Unit
        }
        return
    }

    val blocks = remember(text) {
        runCatching { MarkdownParser.parse(text) }
            .getOrElse { listOf(TimelineBlock.Paragraph(text)) }
    }

    if (blocks.none { isExecutionBlock(it) }) {
        MarkdownDocumentView(blocks = blocks, isStreaming = entry.isStreaming, modifier = modifier)
        return
    }

    val groups = remember(blocks) { groupRealtimeBlocks(blocks) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        groups.forEachIndexed { groupIndex, group ->
            when (group) {
                is RealtimeGroup.Card -> {
                    // 实时卡片沿用消息自身的 key + 块序号作为耗时观察 key，
                    // 收进聚合面板后同一个 key 仍可命中已观察的耗时。
                    val timingKey = "${entry.key}#${group.blockIndex}"
                    when (val block = group.block) {
                        is TimelineBlock.ThoughtBlock -> MarkdownThoughtBlockView(
                            block = block,
                            isStreaming = entry.isStreaming,
                            timingKey = timingKey,
                        )

                        is TimelineBlock.ToolCallBlock -> MarkdownToolCallView(
                            block = block,
                            isStreaming = entry.isStreaming,
                            timingKey = timingKey,
                        )

                        else -> Unit
                    }
                }

                is RealtimeGroup.Narrative -> CompositionLocalProvider(
                    LocalMarkdownCompact provides true,
                ) {
                    MarkdownDocumentView(
                        blocks = group.blocks,
                        isStreaming = entry.isStreaming && groupIndex == groups.lastIndex,
                    )
                }
            }
        }

        // 卡片自身没有实时指示时（例如思考已闭合、消息仍在流式），末尾补一个光标，
        // 让「还在输出」这件事始终看得见。
        val lastBlock = blocks.lastOrNull()
        val shouldShowTrailingCursor = entry.isStreaming &&
            lastBlock != null &&
            isExecutionBlock(lastBlock) &&
            !cardShowsOwnLiveIndicator(lastBlock, entry.isStreaming)
        if (shouldShowTrailingCursor) {
            StreamingCursor(modifier = Modifier.padding(top = Dimensions.SpaceTiny))
        }
    }
}

/**
 * 执行轮次的「聚合面板 + 最终回答」主体（规格 §4.4）。
 *
 * 最终消息的附件、元数据与复制由宿主在 [AssistantExecutionTurnBody] 外层渲染；
 * 只有 [AssistantExecutionRun.hasFinalAnswer] 为 false 时，最终消息附件才会作为过程附件
 * 出现在 `run.steps` 里，由 [processAttachmentsSlot] 渲染，避免宿主重复渲染。
 */
@Composable
fun AssistantExecutionTurnBody(
    run: AssistantExecutionRun,
    modifier: Modifier = Modifier,
    processAttachmentsSlot: @Composable (TimelineEntry) -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        ExecutionProcessPanel(run = run, processAttachmentsSlot = processAttachmentsSlot)
        if (run.hasFinalAnswer && run.finalBlocks.isNotEmpty()) {
            // 最终回答是真正的回答，保持普通（非紧凑）Markdown 渲染；
            // 只有整轮仍在流式时才把光标交给正文。
            MarkdownDocumentView(blocks = run.finalBlocks, isStreaming = run.isStreaming)
        }
    }
}

/**
 * 执行过程折叠面板（规格 §4.3）。
 *
 * 首次出现即折叠；用户手动展开后保持，不因流式增量重建而强制收起。
 * 标题两种文案：
 * - 折叠：「已完成 N 步执行与思考 · 点击展开」；
 * - 展开：「执行过程 · N 步 · 点击收起」；
 * - 有失败/中断时追加「· 含 M 步失败」（中断另行标注「· 已中断」）。
 */
@Composable
fun ExecutionProcessPanel(
    run: AssistantExecutionRun,
    modifier: Modifier = Modifier,
    processAttachmentsSlot: @Composable (TimelineEntry) -> Unit = {},
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val stateColors = LocalAppStateColors.current

    val hasFailure = run.steps.any { it is ExecutionStep.ToolCall && !it.block.isSuccess }
    val accentColor = when {
        hasFailure -> MaterialTheme.colorScheme.error
        run.interrupted -> stateColors.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // 默认折叠；同一 run key 内保持用户选择（流式增量会重建 run，但 key 不变）。
    // 用 rememberSaveable：列表项滚出再滚回时用户展开偏好不丢。
    var expanded by rememberSaveable(run.key) { mutableStateOf(false) }

    val chevronRotation = if (reduceMotion) {
        if (expanded) 180f else 0f
    } else {
        animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = MotionSpecs.spatial(reduceMotion),
            label = "execution-panel-chevron",
        ).value
    }

    val baseTitle = if (expanded) {
        "执行过程 · ${run.stepCount} 步"
    } else {
        "已完成 ${run.stepCount} 步执行与思考"
    }
    val failureSuffix = if (run.failedStepCount > 0) " · 含 ${run.failedStepCount} 步失败" else ""
    val interruptedSuffix = if (run.interrupted) " · 已中断" else ""
    val actionSuffix = if (expanded) " · 点击收起" else " · 点击展开"
    val title = "$baseTitle$failureSuffix$interruptedSuffix$actionSuffix"

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.Medium),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(Dimensions.StrokeHairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimensions.MinimumTouchTarget)
                    .clip(RoundedCornerShape(AppRadius.Small))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "收起执行过程" else "展开执行过程",
                    ) {
                        expanded = !expanded
                    }
                    .semantics {
                        contentDescription =
                            "执行过程，已完成 ${run.stepCount} 步，${if (expanded) "已展开" else "已收起"}"
                        stateDescription = if (expanded) "已展开" else "已收起"
                    }
                    .padding(horizontal = Dimensions.SpaceCompact, vertical = Dimensions.SpaceSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                if (hasFailure || run.interrupted) {
                    Icon(
                        imageVector = if (hasFailure) Icons.Default.ErrorOutline else Icons.Default.Warning,
                        contentDescription = when {
                            hasFailure -> "执行过程包含失败步骤"
                            else -> "执行过程中断"
                        },
                        tint = accentColor,
                        modifier = Modifier.size(Dimensions.SmallIcon),
                    )
                }
                Text(
                    text = title,
                    style = AppTextStyles.Thought,
                    color = accentColor,
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

            ExpandableRegion(expanded = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = Dimensions.SpaceCompact,
                            end = Dimensions.SpaceCompact,
                            bottom = Dimensions.SpaceCompact,
                        ),
                    verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
                ) {
                    run.steps.forEach { step ->
                        when (step) {
                            is ExecutionStep.Thought -> MarkdownThoughtBlockView(
                                block = step.block,
                                // 过程区里的步骤只在整轮仍流式时保留实时指示；
                                // 已折叠的步骤默认是已完成或已中断的。
                                isStreaming = run.isStreaming && step.entryIsStreaming,
                                timingKey = step.stepKey,
                            )

                            is ExecutionStep.ToolCall -> MarkdownToolCallView(
                                block = step.block,
                                isStreaming = run.isStreaming && step.entryIsStreaming,
                                timingKey = step.stepKey,
                            )

                            is ExecutionStep.Narrative -> CompositionLocalProvider(
                                LocalMarkdownCompact provides true,
                            ) {
                                MarkdownDocumentView(blocks = listOf(step.block), isStreaming = false)
                            }

                            is ExecutionStep.Attachments -> processAttachmentsSlot(step.entry)
                        }
                    }
                }
            }
        }
    }
}

/** 实时渲染的分组：思维链/工具卡各占一组，连续的非执行块合并成一段紧凑叙述。 */
private sealed interface RealtimeGroup {
    data class Card(val block: TimelineBlock, val blockIndex: Int) : RealtimeGroup
    data class Narrative(val blocks: List<TimelineBlock>) : RealtimeGroup
}

private fun groupRealtimeBlocks(blocks: List<TimelineBlock>): List<RealtimeGroup> {
    val groups = mutableListOf<RealtimeGroup>()
    val narrative = mutableListOf<TimelineBlock>()

    fun flushNarrative() {
        if (narrative.isEmpty()) return
        groups += RealtimeGroup.Narrative(narrative.toList())
        narrative.clear()
    }

    blocks.forEachIndexed { index, block ->
        if (isExecutionBlock(block)) {
            flushNarrative()
            groups += RealtimeGroup.Card(block = block, blockIndex = index)
        } else {
            narrative += block
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
