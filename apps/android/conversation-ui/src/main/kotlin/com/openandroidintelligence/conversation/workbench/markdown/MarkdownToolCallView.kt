package com.openandroidintelligence.conversation.workbench

import com.openandroidintelligence.conversation.model.NativeExecutionStatus

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.components.ExpandableRegion
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalAppStateColors
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 工具调用卡片（规格 §4.2）。
 *
 * 摘要栏始终可见：状态图标 + 等宽 `[toolName]` + 状态文字 + 可选耗时 + chevron。
 * - 执行中（`isRunning && 消息仍在流式`）：`执行中` + 跳动点，默认展开；
 * - 成功 / 失败 / 中断：默认收起，展开后完整呈现命令与输出/错误输出。
 *
 * 展开内容只有一层：命令代码块（可横向滚动、可复制）与输出代码块
 * （纵横向滚动、最大高度 [Dimensions.CommandMenuHeight]、可选中），
 * 不再保留第二层「查看输出」开关。用户手动展开/收起优先于自动状态。
 *
 * @param isStreaming 所属消息是否仍在流式输出，用于区分「执行中」与「执行已中断」。
 * @param timingKey 宿主提供的耗时观察 key；为 null 时不显示耗时。
 */
@Composable
fun MarkdownToolCallView(
    block: TimelineBlock.ToolCallBlock,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    timingKey: String? = null,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val stateColors = LocalAppStateColors.current

    // 只有消息仍在流式时，未闭合的 tool_call 才代表「真的在执行」；
    // 流已经结束却仍未闭合，就是中断，结果未知。
    val nativeStatus = block.executionStatus
    val running = nativeStatus == NativeExecutionStatus.RUNNING ||
        nativeStatus == null && block.isRunning && isStreaming
    val interrupted = nativeStatus == null && block.isRunning && !isStreaming
    val failed = nativeStatus == NativeExecutionStatus.FAILED ||
        nativeStatus == null && !block.isSuccess && !running
    val unknown = nativeStatus == NativeExecutionStatus.UNKNOWN
    val completed = nativeStatus == NativeExecutionStatus.COMPLETED
    // Hermes 的纯文本「调用工具：xxx」只有调用事实，没有参数/返回值：显示「已调用」，
    // 不把未知结果伪装成「执行成功」。
    val invocationOnly = block.invocationOnly &&
        block.summary.isNullOrBlank() && block.output.isNullOrBlank() && !running && !interrupted && !failed

    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: running

    val timingStore = LocalExecutionTiming.current
    LaunchedEffect(timingKey, running) {
        if (timingKey != null) timingStore?.observe(timingKey, running)
    }
    // 运行中的卡片被移出组合（滚动/折叠）时在此结账，避免把离开期间也算进耗时。
    DisposableEffect(timingKey, timingStore) {
        onDispose {
            if (timingKey != null) timingStore?.settleOnDispose(timingKey)
        }
    }
    val durationMillis = timingKey?.let { timingStore?.durationOf(it) }

    val statusColor: Color = when {
        running -> MaterialTheme.colorScheme.primary
        interrupted -> stateColors.warning
        unknown -> stateColors.warning
        failed -> MaterialTheme.colorScheme.error
        invocationOnly -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> stateColors.success
    }
    val statusDescription = when {
        running -> "执行中"
        interrupted -> "执行已中断"
        unknown -> "结果未知"
        failed -> "执行失败"
        invocationOnly -> "已调用"
        completed -> "已完成"
        else -> "执行成功"
    }
    val statusIcon: ImageVector = when {
        running -> Icons.Default.PlayArrow
        interrupted -> Icons.Default.Warning
        unknown -> Icons.Default.Warning
        failed -> Icons.Default.Cancel
        invocationOnly -> Icons.Default.Check
        else -> Icons.Default.CheckCircle
    }
    // 摘要栏优先展示真实结果 summary（规格 §4.2「执行成功（或 summary）」）；
    // 只有通用成功/失败文案才退回状态词，长文本在摘要栏单行省略，详情里仍完整展示。
    val summaryText = block.summary
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "执行成功" && it != "执行失败" }
    val statusText = when {
        running -> "执行中"
        interrupted -> "执行已中断 · 结果未知"
        unknown -> "结果未知"
        failed -> summaryText ?: "执行失败"
        invocationOnly -> "已调用"
        completed -> "已完成"
        else -> summaryText ?: "执行成功"
    }
    // 只展示真实观察到的耗时：中断/完成的步骤才可能有结算值。
    val durationSuffix = if (!running && durationMillis != null) {
        " · ${formatExecutionDuration(durationMillis)}"
    } else {
        ""
    }

    val badgeText = toolBadgeText(block.toolName)

    val chevronRotation = if (reduceMotion) {
        if (expanded) 180f else 0f
    } else {
        animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = MotionSpecs.spatial(reduceMotion),
            label = "tool-call-chevron",
        ).value
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dimensions.SpaceTiny),
        shape = RoundedCornerShape(AppRadius.Compact),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                        onClickLabel = if (expanded) "收起工具详情" else "展开工具详情",
                    ) {
                        userExpanded = !expanded
                    }
                    .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                    .padding(horizontal = Dimensions.SpaceCompact, vertical = Dimensions.SpaceSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = statusDescription,
                    tint = statusColor,
                    modifier = Modifier.size(Dimensions.SmallIcon),
                )
                Text(
                    text = badgeText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
                ) {
                    Text(
                        text = statusText + durationSuffix,
                        style = AppTextStyles.Thought,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (running) {
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        } else {
                            Modifier
                        },
                    )
                    if (running) {
                        TypingDots(reduced = reduceMotion)
                    }
                }
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
                ToolCallDetails(block = block, running = running)
            }
        }
    }
}

/** 工具名称徽标：空名/占位名归一为 `[执行命令]`，其余统一加方括号。 */
private fun toolBadgeText(toolName: String): String = when {
    toolName.isBlank() ||
        toolName.equals("tool_call", ignoreCase = true) ||
        toolName == "执行命令" -> "[执行命令]"

    toolName.startsWith("[") && toolName.endsWith("]") -> toolName
    else -> "[$toolName]"
}

/**
 * 展开后的详情：命令 → 摘要 → 输出/错误输出。
 *
 * 没有输出时明确显示「无输出」，而不是留一片空白让用户猜是否还在加载。
 */
@Composable
private fun ToolCallDetails(
    block: TimelineBlock.ToolCallBlock,
    running: Boolean,
) {
    val summaryText = block.summary
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "执行成功" && it != "执行失败" }

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
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                Text(
                    text = "命令",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (block.command.isNotBlank()) {
                    CopyCommandAction(command = block.command)
                }
            }

            if (block.command.isNotBlank()) {
                CodeSurface(
                    text = block.command,
                    fontSizeSp = 12.5f,
                    lineHeightSp = null,
                    maxHeight = false,
                )
            } else {
                Text(
                    text = "Hermes 在消息中未提供命令参数",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (block.command.endsWith("...")) {
                Text(
                    text = "参数来自 Hermes 预览，可能已被截断",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (summaryText != null) {
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            val output = block.output
            if (!output.isNullOrBlank()) {
                Text(
                    text = if (block.isSuccess) "输出" else "错误输出",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CodeSurface(
                    text = output,
                    fontSizeSp = 11.5f,
                    lineHeightSp = 16f,
                    maxHeight = true,
                )
            } else if (!running) {
                Text(
                    text = when {
                        block.executionStatus == NativeExecutionStatus.UNKNOWN -> "未收到工具结果"
                        block.invocationOnly -> "此调用未附带返回值"
                        else -> "无输出"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 只读代码块：等宽、可选中；[maxHeight] 时限制高度并同时支持纵向与横向滚动，
 * 完整保留文本、不截断。
 */
@Composable
private fun CodeSurface(
    text: String,
    fontSizeSp: Float,
    lineHeightSp: Float?,
    maxHeight: Boolean,
) {
    var surfaceModifier = Modifier.fillMaxWidth()
    if (maxHeight) {
        surfaceModifier = surfaceModifier.heightIn(max = Dimensions.CommandMenuHeight)
    }
    surfaceModifier = surfaceModifier
        .clip(RoundedCornerShape(AppRadius.Small))
        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
    if (maxHeight) {
        surfaceModifier = surfaceModifier.verticalScroll(rememberScrollState())
    }
    surfaceModifier = surfaceModifier
        .horizontalScroll(rememberScrollState())
        .padding(Dimensions.SpaceSmall)

    Box(modifier = surfaceModifier) {
        SelectionContainer {
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSizeSp.sp,
                lineHeight = (lineHeightSp ?: (fontSizeSp * 1.4f)).sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 复制命令：1.5 秒「已复制」反馈，同时给读屏与 Toast 一个明确结果。 */
@Composable
private fun CopyCommandAction(command: String) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .defaultMinSize(
                minWidth = Dimensions.MinimumTouchTarget,
                minHeight = Dimensions.MinimumTouchTarget,
            )
            .clip(RoundedCornerShape(AppRadius.ExtraSmall))
            .clickable(role = Role.Button, onClickLabel = "复制命令") {
                clipboardManager.setText(AnnotatedString(command))
                copied = true
                runCatching { view.announceForAccessibility("已复制命令") }
                runCatching { Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show() }
                coroutineScope.launch {
                    delay(1500)
                    copied = false
                }
            }
            .padding(horizontal = Dimensions.SpaceSmall, vertical = Dimensions.SpaceTiny),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
    ) {
        Icon(
            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = if (copied) "已复制" else "复制",
            tint = if (copied) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = if (copied) "已复制" else "复制",
            style = MaterialTheme.typography.labelSmall,
            color = if (copied) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
