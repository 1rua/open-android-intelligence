package com.openandroidintelligence.conversation.workbench

import android.graphics.BitmapFactory
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.components.stitchGuide
import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.AttachmentDownloadState
import com.openandroidintelligence.conversation.model.AttachmentLocalState
import com.openandroidintelligence.conversation.model.TimelineAttachment
import com.openandroidintelligence.conversation.ports.AgentMessageErrorCode
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalAppStateColors
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 消息时间线的列表容器。视觉规范严格遵循设计系统与产品规格（2026-10-21 §5.1）：
 * 1. 助手消息：开放式正文 + 由内容高度驱动的连续缝线（[stitchGuide]），适合长文本与 Markdown；
 * 2. 用户消息：右对齐气泡，`surfaceContainerHigh` + `onSurface`，20dp 圆角、尾部方向角 4dp；
 * 3. 元数据：时间与状态统一 `AppTextStyles.MonoSmall` + `onSurfaceVariant`，状态用文字表达，
 *    等待用 warning、失败才用 error —— 颜色永远不是唯一信号；
 * 4. Markdown 代码块：深色圆角容器、语言标签、独立复制代码按钮、横向自由滚动与等宽字体。
 *
 * @param onDecide 提交一次审批决策，走它自己的端点；卡片永不用「在会话里打字」的方式作答
 *   （§7.2）。决策与被按下卡片的标识一起传递，因此一组行可以共用同一个回调而不丢失回答的是哪张审批。
 */
@Composable
fun MessageTimeline(
    entries: List<TimelineEntry>,
    onDecide: (approvalId: String, choice: ApprovalChoice) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    onDownloadAttachment: ((String) -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.TimelineSpacing),
    ) {
        entries.forEach { entry ->
            TimelineRow(entry = entry, onDecide = onDecide, onDownloadAttachment = onDownloadAttachment)
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
    onDownloadAttachment: ((String) -> Unit)? = null,
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
        UserMessageBubble(entry = entry, modifier = modifier, onDownloadAttachment = onDownloadAttachment)
    } else {
        AssistantMessageRow(entry = entry, onDownloadAttachment = onDownloadAttachment, modifier = modifier.semantics {
            contentDescription = "OaiMessage:${entry.key}:${if (entry.isStreaming) "streaming" else "completed"}"
        })
    }
}

/**
 * 用户气泡：`surfaceContainerHigh`（"用户说的内容"这一层），不再是 secondaryContainer。
 * 尾部方向角（右下）压到 4dp，其余三角 20dp —— 方向感来自形状，不靠第二个强调色。
 */
@Composable
private fun UserMessageBubble(
    entry: TimelineEntry,
    modifier: Modifier = Modifier,
    onDownloadAttachment: ((String) -> Unit)? = null,
) {
    val failed = entry.submissionFailureCode != null || entry.messageStatus == AgentMessageStatus.FAILED
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(
                topStart = AppRadius.Bubble,
                topEnd = AppRadius.Bubble,
                bottomStart = AppRadius.Bubble,
                bottomEnd = AppRadius.ExtraSmall,
            ),
            modifier = Modifier
                .widthIn(max = Dimensions.MessageWidth)
                .fillMaxWidth(Dimensions.MessageWidthFraction),
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = Dimensions.SpaceMedium,
                    vertical = Dimensions.SpaceCompact,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                MessageAttachments(entry.attachments, onDownloadAttachment)

                if (entry.text.isNotBlank()) {
                    SelectionContainer {
                        Text(text = entry.text, style = MaterialTheme.typography.bodyLarge)
                    }
                } else if (entry.attachments.isEmpty()) {
                    SelectionContainer {
                        Text(
                            text = "此消息没有可显示的文本",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }

                MessageMetadata(
                    entry = entry,
                    failed = failed,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

fun messageStatusLabel(status: AgentMessageStatus, errorCode: AgentMessageErrorCode?): String = when (status) {
    AgentMessageStatus.QUEUED -> "等待 Agent 接收"
    AgentMessageStatus.DELIVERED -> "Agent 已接收"
    AgentMessageStatus.COMPLETED -> "Agent 处理完成"
    AgentMessageStatus.FAILED -> "Agent 处理失败：" + when (errorCode) {
        AgentMessageErrorCode.AGENT_UNAVAILABLE -> "Agent 暂不可用"
        AgentMessageErrorCode.ATTACHMENT_READ_FAILED -> "Agent 读取附件失败"
        AgentMessageErrorCode.AGENT_MEDIA_REJECTED -> "Agent 无法处理此媒体"
        AgentMessageErrorCode.MODEL_REQUEST_REJECTED -> "模型请求格式不接受"
        null -> "请重试或重新选择附件"
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
            modifier = Modifier.padding(horizontal = Dimensions.SpaceSmall, vertical = Dimensions.SpaceSmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            Icon(
                imageVector = if (isImage) Icons.Default.Image else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
            Text(
                text = filename,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 所有发送者和附件身份都使用同一种卡片，正文为空时仍保留附件内容。 */
@Composable
private fun MessageAttachments(attachments: List<TimelineAttachment>, onDownload: ((String) -> Unit)?) {
    attachments.forEach { HistoricalAttachmentCard(it, onDownload) }
}

@Composable
fun HistoricalAttachmentCard(attachment: TimelineAttachment, onDownload: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimensions.SpaceMedium), verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            AttachmentFallbackChip(attachment.filename.ifBlank { if (attachment.isImage) "图片附件" else "附件文件" }, attachment.isImage)
            val bytes = attachment.previewBytes
            val image = remember(bytes) {
                bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
            }
            if (image != null) Image(image, contentDescription = attachment.filename.ifBlank { "图片预览" },
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp))
            when (attachment.localState) {
                AttachmentLocalState.PENDING -> Text("正在保存到本机…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AttachmentLocalState.UNAVAILABLE -> Text("原件不可用，附件信息仍保留", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AttachmentLocalState.FAILED -> Text("本地保存失败，请检查手机存储空间或连接", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                AttachmentLocalState.SAVED -> Unit
            }
            when (attachment.downloadState) {
                AttachmentDownloadState.SAVED -> Text("已保存到下载目录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AttachmentDownloadState.FAILED -> Text("下载失败，请重试", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
            TextButton(onClick = { onDownload?.invoke(attachment.draftId) }, enabled =
                onDownload != null && attachment.localState != AttachmentLocalState.UNAVAILABLE && attachment.downloadState != AttachmentDownloadState.DOWNLOADING) {
                Text(if (attachment.downloadState == AttachmentDownloadState.DOWNLOADING) "下载中…" else "下载")
            }
        }
    }
}

/**
 * 助手正文：不再每块各画一段固定 32dp 的缝线，而是让 [stitchGuide] 跟着内容长度走。
 * 正文直接交给 [MarkdownDocumentView]，字号走 bodyLarge（16sp / 26sp 行高）。
 */
@Composable
private fun AssistantMessageRow(
    entry: TimelineEntry,
    modifier: Modifier = Modifier,
    onDownloadAttachment: ((String) -> Unit)? = null,
) {
    val failed = entry.messageStatus == AgentMessageStatus.FAILED || entry.submissionFailureCode != null
    Column(
        modifier = modifier
            .fillMaxWidth()
            .stitchGuide(failed = failed)
            .padding(start = Dimensions.StitchGutter),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        MessageAttachments(entry.attachments, onDownloadAttachment)
        val content = entry.text.ifBlank {
            if (entry.isStreaming || entry.attachments.isNotEmpty()) "" else "此消息没有可显示的文本"
        }

        if (content.isBlank() && entry.isStreaming) {
            StreamingCursor(modifier = Modifier.padding(vertical = Dimensions.SpaceTiny))
        } else if (content.isNotBlank()) {
            val blocks = remember(content) { parseMarkdownBlocks(content) }
            MarkdownDocumentView(
                blocks = blocks,
                isStreaming = entry.isStreaming,
            )
        }

        MessageMetadata(entry = entry, failed = failed)
    }
}

/**
 * 时间 / 送达状态：统一等宽小字，状态用文字而不是"变色圆点"。
 *
 * 颜色映射只有两处例外：等待（QUEUED / 等待合并）用 warning，失败才用 error；
 * 其余保持 onSurfaceVariant —— 时间戳不该抢正文的注意力。
 */
@Composable
private fun MessageMetadata(
    entry: TimelineEntry,
    failed: Boolean,
    modifier: Modifier = Modifier,
) {
    val stateColors = LocalAppStateColors.current
    val meta: Pair<String, Color>? = when {
        entry.submissionFailureCode != null ->
            "发送失败 · ${entry.submissionFailureCode}" to MaterialTheme.colorScheme.error
        entry.messageStatus != null ->
            messageStatusLabel(entry.messageStatus, entry.messageStatusErrorCode) to when {
                failed -> MaterialTheme.colorScheme.error
                entry.messageStatus == AgentMessageStatus.QUEUED -> stateColors.warning
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        entry.pendingAcceptance ->
            (if (entry.batchGroupId != null) "等待合并" else "等待 Gateway 接收") to stateColors.warning
        entry.isStreaming -> "正在输出…" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> formatTime(entry.timestamp).takeIf { it.isNotEmpty() }
            ?.let { it to MaterialTheme.colorScheme.onSurfaceVariant }
    }
    meta?.let { (text, color) ->
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            // 流式状态点与文字并存：点只是"还在长"的辅助信号，减少动态时保持静止。
            if (entry.isStreaming) StreamingStatusDot()
            Text(
                text = text,
                style = AppTextStyles.MonoSmall,
                color = color,
                maxLines = 1,
            )
        }
    }
}

/** 流式输出的状态点：极缓的 1.2s alpha 呼吸；减少动态时完全静止。 */
@Composable
private fun StreamingStatusDot() {
    val reduced = LocalMotionPolicy.current.reduceMotion
    val alpha = if (reduced) {
        0.75f
    } else {
        val transition = rememberInfiniteTransition(label = "streaming-dot")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "streaming-dot-alpha",
        ).value
    }
    Box(
        modifier = Modifier
            .size(Dimensions.SpaceTiny)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
    )
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
            .width(2.dp)
            .height(16.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}

/** 思考点：3 点错峰位移 2dp + alpha，一个循环 1400ms；减少动态时静态。 */
private val TypingDotSize = 6.dp
private val TypingDotRise = 2.dp
private const val TypingDotsCycleMillis = 1400
private const val TypingDotsStaggerMillis = 160
private const val StaticTypingDotAlpha = 0.55f

/**
 * 正在思考动画组件：对齐 fronted-preview 的 #thinkingIndicator 与 .typing-dot
 */
@Composable
fun ThinkingIndicator(
    modifier: Modifier = Modifier,
    text: String = "AI 正在思考",
) {
    val reduced = LocalMotionPolicy.current.reduceMotion
    Box(
        modifier = modifier
            .fillMaxWidth()
            .stitchGuide()
            .padding(start = Dimensions.StitchGutter),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(
                topStart = AppRadius.Bubble,
                topEnd = AppRadius.Bubble,
                bottomStart = AppRadius.ExtraSmall,
                bottomEnd = AppRadius.Bubble,
            ),
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = Dimensions.SpaceMedium,
                    vertical = Dimensions.SpaceCompact,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
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
 * 3 颗连续跳动点：对齐 fronted-preview 的 .typing-dot 关键帧动画。
 *
 * 位移与 alpha 都放在 `graphicsLayer` 的绘制 lambda 里读取，逐帧只失效图层、不触发重组。
 */
@Composable
fun TypingDots(
    modifier: Modifier = Modifier,
    reduced: Boolean = false,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing-dots")
    val progress: List<State<Float>?> = if (reduced) {
        List(3) { null }
    } else {
        List(3) { index ->
            infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = TypingDotsCycleMillis
                        val start = index * TypingDotsStaggerMillis
                        0f at 0
                        if (start > 0) 0f at start
                        1f at (start + 300).coerceAtMost(TypingDotsCycleMillis)
                        0f at (start + 700).coerceAtMost(TypingDotsCycleMillis)
                        0f at TypingDotsCycleMillis
                    },
                    repeatMode = RepeatMode.Restart,
                ),
                label = "dot-anim-$index",
            )
        }
    }
    val risePx = with(LocalDensity.current) { TypingDotRise.toPx() }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        progress.forEach { state ->
            Box(
                modifier = Modifier
                    .size(TypingDotSize)
                    .graphicsLayer {
                        val value = state?.value ?: 0f
                        translationY = -risePx * value
                        alpha = if (state == null) StaticTypingDotAlpha else 0.35f + 0.65f * value
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
