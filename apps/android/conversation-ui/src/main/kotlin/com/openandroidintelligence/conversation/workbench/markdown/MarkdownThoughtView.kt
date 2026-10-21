package com.openandroidintelligence.conversation.workbench

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.openandroidintelligence.conversation.components.ExpandableRegion
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalAppStateColors
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/**
 * 思维链呈现卡片 (Thought Card)：展示推理思考过程（规格 §4.1）。
 *
 * 状态与交互：
 * - **流式**：默认展开，标题「AI 正在思考」+ 跳动点，正文末尾带流式光标，liveRegion=Polite；
 * - **完成**：自动收起，标题「已深度思考 · N 字 · 可选耗时 · 点击展开/收起」；
 * - **中断**（未闭合但消息已不再流式）：标题「思考已中断 · N 字 · 点击展开」，warning 色 + 图标；
 * - **空内容**：流式中只显示光标，结束后显示「无思考内容」；
 * - 用户点击标题后记录 override，后续流式完成不再强制重置展开状态。
 *
 * 正文走 [MarkdownParser.parseNarrative] + [MarkdownDocumentView]：
 * 不识别 `<think>` / `<tool_call>`，避免思考正文里再嵌套出思考卡；
 * 通过 [LocalMarkdownCompact] 使用 13sp / 18.2sp 的紧凑 Markdown。
 *
 * @param timingKey 宿主提供的耗时观察 key（`<entryKey>#<blockIndex>`）；
 *   为 null 时不显示耗时。
 */
@Composable
fun MarkdownThoughtBlockView(
    block: TimelineBlock.ThoughtBlock,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
    timingKey: String? = null,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val stateColors = LocalAppStateColors.current

    val live = isStreaming && !block.isComplete
    val interrupted = !isStreaming && !block.isComplete
    val hasContent = block.thought.isNotBlank()
    val expandable = hasContent || live

    // 用户手动展开/收起优先于自动状态；live 结束时不覆盖用户选择。
    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: live

    val timingStore = LocalExecutionTiming.current
    LaunchedEffect(timingKey, live) {
        if (timingKey != null) timingStore?.observe(timingKey, live)
    }
    // 运行中的卡片被移出组合（滚动/折叠）时在此结账，避免把离开期间也算进耗时。
    DisposableEffect(timingKey, timingStore) {
        onDispose {
            if (timingKey != null) timingStore?.observe(timingKey, running = false)
        }
    }
    val durationMillis = timingKey?.let { timingStore?.durationOf(it) }
    val durationSuffix = durationMillis?.let { " · ${formatExecutionDuration(it)}" } ?: ""

    val narrativeBlocks = remember(block.thought) { MarkdownParser.parseNarrative(block.thought) }

    val actionSuffix = if (expanded) " · 点击收起" else " · 点击展开"
    val headerText = when {
        live -> if (expanded) "AI 正在思考" else "AI 正在思考 · 点击展开"
        hasContent && interrupted -> "思考已中断 · ${block.thought.length} 字$durationSuffix$actionSuffix"
        hasContent -> "已深度思考 · ${block.thought.length} 字$durationSuffix$actionSuffix"
        interrupted -> "思考已中断 · 无思考内容"
        else -> "无思考内容"
    }
    val headerColor = when {
        live -> MaterialTheme.colorScheme.primary
        interrupted -> stateColors.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val chevronRotation = if (reduceMotion) {
        if (expanded) 180f else 0f
    } else {
        animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = MotionSpecs.spatial(reduceMotion),
            label = "thought-chevron",
        ).value
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dimensions.SpaceTiny),
        shape = RoundedCornerShape(AppRadius.Compact),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimensions.MinimumTouchTarget)
                    .clip(RoundedCornerShape(AppRadius.Small))
                    .then(
                        if (expandable) {
                            Modifier
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = if (expanded) "收起思考过程" else "展开思考过程",
                                ) {
                                    userExpanded = !expanded
                                }
                                .semantics {
                                    stateDescription = if (expanded) "已展开" else "已收起"
                                }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = Dimensions.SpaceCompact, vertical = Dimensions.SpaceSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                Text(
                    text = headerText,
                    style = AppTextStyles.Thought,
                    color = headerColor,
                    modifier = Modifier.weight(1f),
                )
                if (live) {
                    TypingDots(reduced = reduceMotion)
                }
                if (interrupted) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "思考已中断",
                        tint = headerColor,
                        modifier = Modifier.size(Dimensions.SmallIcon),
                    )
                }
                if (expandable) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(Dimensions.SmallIcon)
                            .rotate(chevronRotation),
                    )
                }
            }

            ExpandableRegion(expanded = expandable && expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = Dimensions.SpaceCompact,
                            end = Dimensions.SpaceCompact,
                            bottom = Dimensions.SpaceCompact,
                        ),
                ) {
                    Spacer(Modifier.height(Dimensions.SpaceSmall))
                    if (!hasContent) {
                        // 流式中还没有任何思考文本：只保留一个光标表达「还在想」。
                        StreamingCursor()
                    } else {
                        CompositionLocalProvider(LocalMarkdownCompact provides true) {
                            val bodyModifier = if (live) {
                                Modifier
                                    .fillMaxWidth()
                                    .semantics { liveRegion = LiveRegionMode.Polite }
                            } else {
                                Modifier.fillMaxWidth()
                            }
                            MarkdownDocumentView(
                                blocks = narrativeBlocks,
                                isStreaming = live,
                                modifier = bodyModifier,
                            )
                        }
                    }
                }
            }
        }
    }
}
