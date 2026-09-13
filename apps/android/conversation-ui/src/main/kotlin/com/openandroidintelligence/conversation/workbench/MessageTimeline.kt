package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
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
import com.openandroidintelligence.conversation.components.SignalStitch
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.theme.Dimensions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 消息时间线：严格遵循设计系统与交互规范（设计规格 §4.4、§6.1）：
 * 1. 助手消息：开放式正文，左侧信号缝线（Signal Stitch），适合长文本、Markdown 与代码块；
 * 2. 用户消息：右侧 tonal surface 气泡，最大宽度 82–85%，使用右下较小圆角提供方向感；
 * 3. Markdown 代码块：深色圆角容器、语言标签、独立复制代码按钮、横向自由滚动与等宽字体；
 * 4. 时间戳与状态指示点：技术元数据使用 Roboto Mono/等宽字体，状态点如实反映接收与发送就绪状态。
 */
@Composable
fun MessageTimeline(entries: List<TimelineEntry>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
    ) {
        entries.forEach { entry ->
            if (entry.isUser) {
                UserMessageBubble(entry = entry)
            } else {
                AssistantMessageRow(entry = entry)
            }
        }
    }
}

@Composable
private fun UserMessageBubble(entry: TimelineEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 4.dp,
            ),
            modifier = Modifier
                .widthIn(max = Dimensions.MessageWidth)
                .fillMaxWidth(0.85f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = entry.text.ifBlank { "此消息没有可显示的文本" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                    )
                }

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (entry.pendingAcceptance) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.tertiary),
                        )
                        Text(
                            text = if (entry.batchGroupId != null) "等待合并" else "等待 Gateway 接收",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val timeStr = formatTime(entry.timestamp)
                        if (timeStr.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                            )
                            Text(
                                text = timeStr,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantMessageRow(entry: TimelineEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        SignalStitch(
            modifier = Modifier
                .padding(top = 4.dp)
                .height(32.dp),
        )
        Spacer(Modifier.width(Dimensions.SpaceSmall))

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val content = entry.text.ifBlank { "此消息没有可显示的文本" }
            val blocks = remember(content) { parseMarkdownBlocks(content) }

            blocks.forEach { block ->
                when (block) {
                    is TimelineBlock.Paragraph -> {
                        SelectionContainer {
                            Text(
                                text = block.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                lineHeight = 22.sp,
                            )
                        }
                    }
                    is TimelineBlock.CodeBlock -> {
                        MarkdownCodeBlock(block = block)
                    }
                }
            }

            // Timestamp and Status Dot
            val timeStr = formatTime(entry.timestamp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                if (timeStr.isNotEmpty()) {
                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownCodeBlock(block: TimelineBlock.CodeBlock) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()

    val containerBg = if (isDark) Color(0xFF0E1412) else Color(0xFFEAEFEB)
    val headerBg = if (isDark) Color(0xFF151E1A) else Color(0xFFDFE6E1)
    val borderColor = if (isDark) Color(0xFF26332E) else Color(0xFFD5E2DC)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = containerBg,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.language,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            clipboardManager.setText(AnnotatedString(block.code))
                            copied = true
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "复制代码",
                        tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = if (copied) "已复制" else "复制",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        color = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = block.code,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

internal sealed interface TimelineBlock {
    data class Paragraph(val text: String) : TimelineBlock
    data class CodeBlock(val language: String, val code: String) : TimelineBlock
}

internal fun parseMarkdownBlocks(text: String): List<TimelineBlock> {
    if (!text.contains("```")) {
        return listOf(TimelineBlock.Paragraph(text))
    }
    val blocks = mutableListOf<TimelineBlock>()
    val regex = Regex("```([a-zA-Z0-9_+-]*)[\\r\\n]+([\\s\\S]*?)```")
    var lastIndex = 0
    for (match in regex.findAll(text)) {
        val before = text.substring(lastIndex, match.range.first).trim()
        if (before.isNotEmpty()) {
            blocks.add(TimelineBlock.Paragraph(before))
        }
        val language = match.groupValues[1].trim()
        val code = match.groupValues[2].trimEnd()
        blocks.add(TimelineBlock.CodeBlock(language.ifBlank { "代码" }, code))
        lastIndex = match.range.last + 1
    }
    val remaining = text.substring(lastIndex).trim()
    if (remaining.isNotEmpty()) {
        blocks.add(TimelineBlock.Paragraph(remaining))
    }
    return blocks.ifEmpty { listOf(TimelineBlock.Paragraph(text)) }
}

private fun formatTime(epochMillis: Long): String = if (epochMillis <= 0) "" else
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis))
