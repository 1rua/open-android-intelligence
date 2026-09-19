package com.openandroidintelligence.conversation.workbench

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.components.ExpandableRegion
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 工具调用与执行结果卡片 (Tool Call & Result Card)：
 * 以结构化紧凑卡片呈现 Agent 调用的外部工具/命令，包括等宽工具名称 Badge、参数代码块、
 * 复制动作反馈、执行状态指示条及可折叠输出日志。
 *
 * 所有颜色取自 M3 语义角色：成功/失败用 primaryContainer / errorContainer 这对角色表达，
 * 而不是写死某套编辑器主题色 —— 这样深色模式与动态取色下都不会失真。
 */
@Composable
fun MarkdownToolCallView(
    block: TimelineBlock.ToolCallBlock,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var outputExpanded by remember { mutableStateOf(false) }

    val reduceMotion = LocalMotionPolicy.current.reduceMotion

    val badgeText = when {
        block.toolName.isBlank() || block.toolName.equals("tool_call", ignoreCase = true) || block.toolName == "执行命令" -> "[执行命令]"
        block.toolName.startsWith("[") && block.toolName.endsWith("]") -> block.toolName
        else -> "[${block.toolName}]"
    }

    val hasResult = !block.summary.isNullOrBlank() || !block.output.isNullOrBlank()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dimensions.SpaceTiny),
        shape = RoundedCornerShape(AppRadius.Medium),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(Dimensions.StrokeHairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 标题栏：比卡面深一级的容器角色
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = Dimensions.SpaceCompact, vertical = Dimensions.SpaceSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = badgeText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier
                        .defaultMinSize(
                            minWidth = Dimensions.MinimumTouchTarget,
                            minHeight = Dimensions.MinimumTouchTarget,
                        )
                        .clip(RoundedCornerShape(AppRadius.Small))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "复制代码",
                        ) {
                            clipboardManager.setText(AnnotatedString(block.command))
                            copied = true
                            try {
                                view.announceForAccessibility("已复制到剪贴板")
                            } catch (_: Throwable) {
                            }
                            try {
                                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            } catch (_: Throwable) {
                            }
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
                        fontSize = 12.sp,
                        color = if (copied) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            // 命令内容区：等宽字体、横向滚动
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Dimensions.SpaceCompact, vertical = 10.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = block.command,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // 结果指示条（存在 summary 或 output 时呈现）
            if (hasResult) {
                val stripColor = if (block.isSuccess) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
                val statusColor = if (block.isSuccess) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }
                val statusText = if (block.isSuccess) {
                    block.summary?.ifBlank { null } ?: "执行成功"
                } else {
                    block.summary?.ifBlank { null } ?: block.output ?: "执行失败"
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(stripColor)
                        .animateContentSize(MotionSpecs.spatial(reduceMotion)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimensions.SpaceCompact, vertical = Dimensions.SpaceSmall),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f, fill = false),
                        ) {
                            Icon(
                                imageVector = if (block.isSuccess) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                contentDescription = if (block.isSuccess) "执行成功" else "执行失败",
                                tint = statusColor,
                                modifier = Modifier.size(Dimensions.SpaceMedium),
                            )
                            Text(
                                text = statusText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = statusColor,
                                maxLines = if (outputExpanded) Int.MAX_VALUE else 2,
                            )
                        }

                        if (!block.output.isNullOrBlank()) {
                            Spacer(Modifier.width(Dimensions.SpaceSmall))
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(AppRadius.ExtraSmall))
                                    .clickable { outputExpanded = !outputExpanded }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = if (outputExpanded) "收起输出" else "查看输出",
                                    fontSize = 12.sp,
                                    color = statusColor,
                                    fontWeight = FontWeight.Medium,
                                )
                                Icon(
                                    imageVector = if (outputExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (outputExpanded) "收起输出" else "查看输出",
                                    tint = statusColor,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }

                    // 展开的详细输出日志框：统一走 Emphasized 的 expandVertically + fadeIn
                    ExpandableRegion(expanded = outputExpanded) {
                        HorizontalDivider(
                            color = statusColor.copy(alpha = 0.2f),
                            thickness = Dimensions.StrokeHairline / 2,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = Dimensions.CommandMenuHeight)
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(Dimensions.SpaceSmall),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = block.output.orEmpty(),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 16.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
