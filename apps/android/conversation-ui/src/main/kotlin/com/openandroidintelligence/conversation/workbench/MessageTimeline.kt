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
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import com.openandroidintelligence.ui.design.LocalMotionPolicy
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
                // Attachments preview strip (Images thumbnail or Document chip)
                if (entry.attachments.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        entry.attachments.forEach { attachment ->
                            if (attachment.isImage && attachment.imageBytes != null) {
                                val bitmap = remember(attachment.imageBytes) {
                                    runCatching {
                                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                        BitmapFactory.decodeByteArray(attachment.imageBytes, 0, attachment.imageBytes.size, opts)
                                        var sample = 1
                                        while (opts.outWidth / sample > 600 || opts.outHeight / sample > 600) {
                                            sample *= 2
                                        }
                                        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                                        BitmapFactory.decodeByteArray(attachment.imageBytes, 0, attachment.imageBytes.size, decodeOpts)?.asImageBitmap()
                                    }.getOrNull()
                                }
                                if (bitmap != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 240.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surface),
                                    ) {
                                        Image(
                                            bitmap = bitmap,
                                            contentDescription = attachment.filename.ifBlank { "图片预览" },
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                } else {
                                    AttachmentFallbackChip(attachment.filename.ifBlank { "图片附件" }, isImage = true)
                                }
                            } else {
                                AttachmentFallbackChip(attachment.filename.ifBlank { "附件文件" }, isImage = attachment.isImage)
                            }
                        }
                    }
                }

                if (entry.text.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = entry.text,
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                        )
                    }
                } else if (entry.attachments.isEmpty()) {
                    SelectionContainer {
                        Text(
                            text = "此消息没有可显示的文本",
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                        )
                    }
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
private fun AttachmentFallbackChip(filename: String, isImage: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (isImage) Icons.Default.Image else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = filename,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
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
            val content = entry.text.ifBlank {
                if (entry.isStreaming) "" else "此消息没有可显示的文本"
            }

            if (content.isBlank() && entry.isStreaming) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    StreamingCursor()
                }
            } else {
                val blocks = remember(content) { parseMarkdownBlocks(content) }

                blocks.forEachIndexed { index, block ->
                    val isLastBlock = index == blocks.lastIndex && entry.isStreaming
                    when (block) {
                        is TimelineBlock.Paragraph -> {
                            val cursorInlineId = "streaming_cursor"
                            val annotatedText = remember(block.text, isLastBlock) {
                                buildAnnotatedString {
                                    append(block.text)
                                    if (isLastBlock) {
                                        append(" ")
                                        appendInlineContent(cursorInlineId, "[cursor]")
                                    }
                                }
                            }
                            val inlineContent = remember(isLastBlock) {
                                if (isLastBlock) {
                                    mapOf(
                                        cursorInlineId to InlineTextContent(
                                            Placeholder(
                                                width = 4.sp,
                                                height = 16.sp,
                                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                                            ),
                                        ) {
                                            StreamingCursor()
                                        },
                                    )
                                } else {
                                    emptyMap()
                                }
                            }

                            SelectionContainer {
                                Text(
                                    text = annotatedText,
                                    inlineContent = inlineContent,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp,
                                )
                            }
                        }
                        is TimelineBlock.CodeBlock -> {
                            MarkdownCodeBlock(block = block, isStreaming = isLastBlock)
                        }
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
                if (entry.isStreaming) {
                    val infiniteTransition = rememberInfiniteTransition(label = "streaming-dot")
                    val dotAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 600),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "dot-alpha",
                    )
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .alpha(dotAlpha)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Text(
                        text = "正在输出…",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
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
}

/**
 * 伴随流式输出闪烁的光标，视觉效果严格对齐 fronted-preview 的 .streaming-cursor
 */
@Composable
fun StreamingCursor(modifier: Modifier = Modifier) {
    val reduced = LocalMotionPolicy.current.reduceMotion
    val alpha = if (reduced) {
        1f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "streaming-cursor")
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 800
                    1f at 0
                    1f at 400
                    0f at 401
                    0f at 800
                },
                repeatMode = RepeatMode.Restart,
            ),
            label = "cursor-alpha",
        ).value
    }
    Box(
        modifier = modifier
            .width(2.5.dp)
            .height(16.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)),
    )
}

/**
 * 正在思考动画组件：对齐 fronted-preview 的 #thinkingIndicator 与 .typing-dot
 */
@Composable
fun ThinkingIndicator(
    modifier: Modifier = Modifier,
    text: String = "AI 正在思考",
) {
    val reduced = LocalMotionPolicy.current.reduceMotion
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        SignalStitch(
            modifier = Modifier
                .padding(top = 4.dp)
                .height(28.dp),
        )
        Spacer(Modifier.width(Dimensions.SpaceSmall))

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 4.dp,
                bottomEnd = 16.dp,
            ),
            modifier = Modifier.padding(vertical = 2.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TypingDots(reduced = reduced)
            }
        }
    }
}

/**
 * 3 颗连续跳动点：对齐 fronted-preview 的 .typing-dot 关键帧动画
 */
@Composable
fun TypingDots(
    modifier: Modifier = Modifier,
    reduced: Boolean = false,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing-dots")
    val dotCount = 3
    val dotAnimations = List(dotCount) { index ->
        if (reduced) {
            remember { mutableStateOf(1f) }
        } else {
            infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1400
                        val delay = index * 160
                        0f at 0
                        if (delay > 0) 0f at delay
                        1f at (delay + 300).coerceAtMost(1400)
                        0f at (delay + 600).coerceAtMost(1400)
                        0f at 1400
                    },
                    repeatMode = RepeatMode.Restart,
                ),
                label = "dot-anim-$index",
            )
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dotAnimations.forEach { animState ->
            val scale = animState.value
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .graphicsLayer {
                        scaleX = 0.4f + 0.6f * scale
                        scaleY = 0.4f + 0.6f * scale
                        alpha = 0.3f + 0.7f * scale
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun MarkdownCodeBlock(
    block: TimelineBlock.CodeBlock,
    isStreaming: Boolean = false,
) {
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
                val cursorInlineId = "streaming_code_cursor"
                val annotatedCode = remember(block.code, isStreaming) {
                    buildAnnotatedString {
                        append(block.code)
                        if (isStreaming) {
                            append(" ")
                            appendInlineContent(cursorInlineId, "[cursor]")
                        }
                    }
                }
                val inlineContent = remember(isStreaming) {
                    if (isStreaming) {
                        mapOf(
                            cursorInlineId to InlineTextContent(
                                Placeholder(
                                    width = 4.sp,
                                    height = 14.sp,
                                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                                ),
                            ) {
                                StreamingCursor()
                            },
                        )
                    } else {
                        emptyMap()
                    }
                }
                SelectionContainer {
                    Text(
                        text = annotatedCode,
                        inlineContent = inlineContent,
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
