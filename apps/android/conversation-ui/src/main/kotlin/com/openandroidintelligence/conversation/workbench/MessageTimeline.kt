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
import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.theme.AppRadius
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
 * 消息时间线的列表容器。渲染规范严格遵循设计系统与交互规范（设计规格 §4.4、§6.1）：
 * 1. 助手消息：开放式正文，左侧信号缝线（Signal Stitch），适合长文本、Markdown 与代码块；
 * 2. 用户消息：右侧 tonal surface 气泡，最大宽度 82–85%，使用右下较小圆角提供方向感；
 * 3. Markdown 代码块：深色圆角容器、语言标签、独立复制代码按钮、横向自由滚动与等宽字体；
 * 4. 时间戳与状态指示点：技术元数据使用 Roboto Mono/等宽字体，状态点如实反映接收与发送就绪状态。
 *
 * @param onDecide 提交一次审批决策，走它自己的端点；卡片永不用「在会话里打字」的方式作答
 *   （§7.2）。决策与被按下卡片的标识一起传递，因此一组行可以共用同一个回调而不丢失回答的是哪张审批。
 */
@Composable
fun MessageTimeline(
    entries: List<TimelineEntry>,
    onDecide: (approvalId: String, choice: ApprovalChoice) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
    ) {
        entries.forEach { entry ->
            TimelineRow(entry = entry, onDecide = onDecide)
        }
    }
}

/**
 * 时间线中的一行：审批请求独占一行渲染卡片，其余条目按消息方向分派。
 *
 * 卡片把自身的 approvalId 绑定进 [onDecide]，容器因此不必再解构条目：
 * 被按下的那张卡片就是被回答的那张审批（§7.2）。
 */
@Composable
fun TimelineRow(
    entry: TimelineEntry,
    onDecide: (String, ApprovalChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val approval = entry.approval
    if (approval != null) {
        // A request the Gateway made, not something anyone said: it owns
        // a row of its own so message folding never touches it.
        ApprovalCard(
            state = approval,
            onDecide = { choice -> onDecide(approval.request.approvalId.value, choice) },
            modifier = modifier.fillMaxWidth(0.85f),
        )
    } else if (entry.isUser) {
        UserMessageBubble(entry = entry, modifier = modifier)
    } else {
        AssistantMessageRow(entry = entry, modifier = modifier)
    }
}

@Composable
private fun UserMessageBubble(entry: TimelineEntry, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
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
                                            .clip(RoundedCornerShape(AppRadius.Small))
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
        shape = RoundedCornerShape(AppRadius.Small),
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
private fun AssistantMessageRow(entry: TimelineEntry, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
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
                MarkdownDocumentView(
                    blocks = blocks,
                    isStreaming = entry.isStreaming,
                )
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

internal fun parseMarkdownBlocks(text: String): List<TimelineBlock> =
    MarkdownParser.parse(text)

private fun formatTime(epochMillis: Long): String = if (epochMillis <= 0) "" else
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis))
