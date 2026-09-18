package com.openandroidintelligence.conversation.workbench

import androidx.compose.runtime.Composable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 工具调用与执行结果卡片 (Tool Call & Result Card)：
 * 以结构化紧凑卡片呈现 Agent 调用的外部工具/命令，包括等宽工具名称 Badge、参数代码块、
 * 复制动作反馈、执行状态指示条及可折叠输出日志。
 */
@Composable
fun MarkdownToolCallView(
    block: TimelineBlock.ToolCallBlock,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var outputExpanded by remember { mutableStateOf(false) }

    val motionPolicy = LocalMotionPolicy.current
    val reduceMotion = motionPolicy.reduceMotion

    val badgeText = when {
        block.toolName.isBlank() || block.toolName.equals("tool_call", ignoreCase = true) || block.toolName == "执行命令" -> "[执行命令]"
        block.toolName.startsWith("[") && block.toolName.endsWith("]") -> block.toolName
        else -> "[${block.toolName}]"
    }

    val hasResult = !block.summary.isNullOrBlank() || !block.output.isNullOrBlank()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0D1117),
        border = BorderStroke(1.dp, Color(0xFF30363D)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 标题栏：#161B22 背景
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = badgeText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF8B949E),
                )

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            clipboardManager.setText(AnnotatedString(block.command))
                            copied = true
                            coroutineScope.launch {
                                delay(1500)
                                copied = false
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (copied) "已复制" else "复制",
                        tint = if (copied) Color(0xFF4ADE80) else Color(0xFF8B949E),
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = if (copied) "已复制" else "复制",
                        fontSize = 12.sp,
                        color = if (copied) Color(0xFF4ADE80) else Color(0xFF8B949E),
                    )
                }
            }

            // 命令内容区：#0D1117 背景，等宽字体，横向滚动
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D1117))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = block.command,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        color = Color(0xFFC9D1D9),
                    )
                }
            }

            // 结果指示条 (当存在 summary 或 output 时呈现)
            if (hasResult) {
                val stripBg = if (block.isSuccess) Color(0x1F4ADE80) else Color(0x1FF87171)
                val statusColor = if (block.isSuccess) Color(0xFF4ADE80) else Color(0xFFF87171)
                val statusText = if (block.isSuccess) {
                    block.summary?.ifBlank { null } ?: "执行成功"
                } else {
                    block.summary?.ifBlank { null } ?: block.output ?: "执行失败"
                }

                val resultModifier = if (reduceMotion) {
                    Modifier.fillMaxWidth().background(stripBg)
                } else {
                    Modifier.fillMaxWidth().background(stripBg).animateContentSize(
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f),
                    )
                }

                Column(
                    modifier = resultModifier,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
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
                                modifier = Modifier.size(16.dp),
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
                            Spacer(Modifier.width(8.dp))
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
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

                    // 展开的详细输出日志框
                    if (!block.output.isNullOrBlank() && outputExpanded) {
                        HorizontalDivider(
                            color = statusColor.copy(alpha = 0.2f),
                            thickness = 0.5.dp,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .background(Color(0xFF090D12))
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(10.dp),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = block.output,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    color = Color(0xFFC9D1D9),
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

