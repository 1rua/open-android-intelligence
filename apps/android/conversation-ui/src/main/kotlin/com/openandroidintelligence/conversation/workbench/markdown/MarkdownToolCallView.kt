package com.openandroidintelligence.conversation.workbench

import android.widget.Toast
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.components.HorizontalDisclosureReveal
import com.openandroidintelligence.conversation.components.M3ExpandableExpressiveCard
import com.openandroidintelligence.conversation.components.rememberExpandChevronRotation
import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalAppStateColors
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.isReduced
import com.openandroidintelligence.ui.resources.R as UiR
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 工具调用卡片（规格 §4.2）。
 *
 * 摘要栏始终可见：状态图标 + 等宽 `[toolName]` + 状态文字 + 可选耗时 + chevron。
 * - 默认收起（含运行中态），点击后展开命令/参数与输出/错误输出，并记住用户展开选择；
 * - 实时新出现的工具卡片通过 [HorizontalDisclosureReveal] 播放一次从左到右的入场显现
 *   （复用统一折叠动效曲线；减少动态时降级为 150ms 无位移淡化），历史加载、滚动回收与
 *   回合折叠大卡片内部展开均不重播；
 * - 当回合结束确实未收到工具结果（[NativeExecutionStatus.UNKNOWN]）时，显式标注「未返回结果」。
 *
 * @param isStreaming 所属消息是否仍在流式输出，用于区分「执行中」与「执行已中断」。
 * @param timingKey 宿主提供的耗时观察 key；为 null 时不显示耗时。
 * @param revealKey 工具卡片入场追踪与展开态记忆 key，默认沿用 [timingKey]。
 * @param animateEntrance 显式指定是否播放本次入场显现；为 null 时自动交由 [LocalToolCardRevealTracker] 决策。
 */
@Composable
fun MarkdownToolCallView(
    block: TimelineBlock.ToolCallBlock,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    timingKey: String? = null,
    revealKey: String? = timingKey,
    animateEntrance: Boolean? = null,
) {
    val reduceMotion = LocalMotionPolicy.current.isReduced
    val stateColors = LocalAppStateColors.current

    // 只有消息仍在流式时，未闭合的 tool_call 才代表「真的在执行」；
    // 流已经结束却仍未闭合，就是中断，未返回结果。
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

    // 工具卡片默认折叠（含执行中态），用户手动点击后在列表复用与状态刷新期间稳定记忆。
    var expanded by rememberSaveable(revealKey) { mutableStateOf(false) }

    val revealTracker = LocalToolCardRevealTracker.current
    val shouldAnimateEntrance = animateEntrance ?: remember(revealTracker, revealKey) {
        if (revealTracker != null && !revealKey.isNullOrBlank()) {
            revealTracker.consumeEntranceAnimation(revealKey)
        } else {
            false
        }
    }

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

    val runningLabel = stringResource(UiR.string.conv_tool_status_running)
    val interruptedLabel = stringResource(UiR.string.conv_tool_status_interrupted)
    val interruptedUnknownLabel = stringResource(UiR.string.conv_tool_status_interrupted_unknown)
    val noResultLabel = stringResource(UiR.string.conv_tool_status_no_result)
    val failedLabel = stringResource(UiR.string.conv_tool_status_failed)
    val invocationOnlyLabel = stringResource(UiR.string.conv_tool_status_invocation_only)
    val completedLabel = stringResource(UiR.string.conv_tool_status_completed)
    val execSuccessLabel = stringResource(UiR.string.conv_md_exec_success)
    val execFailedLabel = stringResource(UiR.string.conv_md_exec_failed)
    val defaultToolName = stringResource(UiR.string.conv_md_default_tool_name)
    val defaultToolCallTag = stringResource(UiR.string.conv_tool_call_tag)

    val statusColor: Color = when {
        running -> MaterialTheme.colorScheme.primary
        interrupted -> stateColors.warning
        unknown -> stateColors.warning
        failed -> MaterialTheme.colorScheme.error
        invocationOnly -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> stateColors.success
    }
    val statusDescription = when {
        running -> runningLabel
        interrupted -> interruptedLabel
        unknown -> noResultLabel
        failed -> failedLabel
        invocationOnly -> invocationOnlyLabel
        completed -> completedLabel
        else -> execSuccessLabel
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
        ?.takeIf { it.isNotEmpty() && it != execSuccessLabel && it != execFailedLabel }
    val statusText = when {
        running -> runningLabel
        interrupted -> interruptedUnknownLabel
        unknown -> noResultLabel
        failed -> summaryText ?: failedLabel
        invocationOnly -> invocationOnlyLabel
        completed -> completedLabel
        else -> summaryText ?: execSuccessLabel
    }
    // 只展示真实观察到的耗时：中断/完成的步骤才可能有结算值。
    val durationSuffix = if (!running && durationMillis != null) {
        " · ${formatExecutionDuration(durationMillis)}"
    } else {
        ""
    }

    val badgeText = toolBadgeText(
        toolName = block.toolName,
        defaultToolName = defaultToolName,
        defaultToolCallTag = defaultToolCallTag,
    )

    val chevronRotation = rememberExpandChevronRotation(
        expanded = expanded,
        reduced = reduceMotion,
        label = "tool-call-chevron",
    )

    HorizontalDisclosureReveal(
        animateEntrance = shouldAnimateEntrance,
        modifier = modifier.fillMaxWidth(),
        cornerRadius = AppRadius.Compact,
        reduced = reduceMotion,
    ) {
        M3ExpandableExpressiveCard(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(Dimensions.StrokeHairline, MaterialTheme.colorScheme.outlineVariant),
            collapsedCornerRadius = AppRadius.Compact,
            expandedCornerRadius = AppRadius.Medium,
            pressedCornerRadius = AppRadius.Medium,
            toggleClickLabel = stringResource(
                if (expanded) UiR.string.conv_tool_action_collapse_label else UiR.string.conv_tool_action_expand_label,
            ),
            toggleStateDescription = stringResource(
                if (expanded) UiR.string.conv_assistant_state_expanded else UiR.string.conv_assistant_state_collapsed,
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
            },
            expandedContent = {
                ToolCallDetails(block = block, running = running, interrupted = interrupted)
            },
        )
    }
}

/** 工具名称徽标：空名/占位名归一为 `[执行命令]`，其余统一加方括号。 */
private fun toolBadgeText(
    toolName: String,
    defaultToolName: String,
    defaultToolCallTag: String,
): String = when {
    toolName.isBlank() ||
        toolName.equals("tool_call", ignoreCase = true) ||
        toolName == defaultToolName -> defaultToolCallTag

    toolName.startsWith("[") && toolName.endsWith("]") -> toolName
    else -> "[$toolName]"
}

/**
 * 展开后的详情：命令/参数 → 摘要 → 输出/错误输出。
 *
 * 没有输出时按真实状态明确标注「未返回结果」/「此调用未附带返回值」/「无输出」，
 * 而不是留一片空白让用户猜是否还在加载。
 */
@Composable
private fun ToolCallDetails(
    block: TimelineBlock.ToolCallBlock,
    running: Boolean,
    interrupted: Boolean,
) {
    val execSuccessLabel = stringResource(UiR.string.conv_md_exec_success)
    val execFailedLabel = stringResource(UiR.string.conv_md_exec_failed)
    val summaryText = block.summary
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != execSuccessLabel && it != execFailedLabel }

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
            if (block.command.isNotBlank()) {
                val isShellCommand = block.toolName.lowercase() in listOf(
                    "bash", "sh", "shell", "adb", "adb_shell", "terminal", "cmd",
                )
                val parameterLabel = stringResource(
                    if (isShellCommand) UiR.string.conv_tool_section_command else UiR.string.conv_tool_section_arguments,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
                ) {
                    Text(
                        text = parameterLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    CopyCommandAction(command = block.command)
                }

                CodeSurface(
                    text = block.command,
                    fontSizeSp = 12.5f,
                    lineHeightSp = null,
                    maxHeight = false,
                )

                if (block.command.endsWith("...")) {
                    Text(
                        text = stringResource(UiR.string.conv_tool_params_truncated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                    text = stringResource(
                        if (block.isSuccess) UiR.string.conv_tool_section_output else UiR.string.conv_tool_section_error_output,
                    ),
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
                    text = stringResource(
                        when {
                            block.executionStatus == NativeExecutionStatus.UNKNOWN || interrupted ->
                                UiR.string.conv_tool_status_no_result
                            block.invocationOnly -> UiR.string.conv_tool_invocation_no_return
                            else -> UiR.string.conv_tool_no_output
                        },
                    ),
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

    val copyCommandLabel = stringResource(UiR.string.conv_tool_action_copy_command)
    val announcedCopied = stringResource(UiR.string.conv_tool_announced_copied)
    val toastCopied = stringResource(UiR.string.conv_tool_toast_copied)
    val copiedLabel = stringResource(UiR.string.conv_tool_btn_copied)
    val copyLabel = stringResource(UiR.string.conv_tool_btn_copy)

    Row(
        modifier = Modifier
            .defaultMinSize(
                minWidth = Dimensions.MinimumTouchTarget,
                minHeight = Dimensions.MinimumTouchTarget,
            )
            .clip(RoundedCornerShape(AppRadius.ExtraSmall))
            .clickable(role = Role.Button, onClickLabel = copyCommandLabel) {
                clipboardManager.setText(AnnotatedString(command))
                copied = true
                runCatching { view.announceForAccessibility(announcedCopied) }
                runCatching { Toast.makeText(context, toastCopied, Toast.LENGTH_SHORT).show() }
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
            contentDescription = if (copied) copiedLabel else copyLabel,
            tint = if (copied) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = if (copied) copiedLabel else copyLabel,
            style = MaterialTheme.typography.labelSmall,
            color = if (copied) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
