package com.openandroidintelligence.conversation.workbench

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
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
import kotlinx.coroutines.delay
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
    onRequestOriginalAttachment: ((String) -> Unit)? = null,
) {
    val displayItems = rememberTimelineDisplayItems(entries)
    val timing = rememberExecutionTimingStore(threadId = null)
    AttachmentPreviewHost(
        attachments = entries.flatMap { it.attachments },
        onDownload = onDownloadAttachment,
        onRequestOriginal = onRequestOriginalAttachment,
        modifier = modifier.fillMaxWidth(),
    ) { openPreview ->
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimensions.TimelineSpacing),
        ) {
            displayItems.forEach { item ->
                TimelineDisplayRow(
                    item = item,
                    onDecide = onDecide,
                    timing = timing,
                    onDownloadAttachment = onDownloadAttachment,
                    onRequestOriginalAttachment = onRequestOriginalAttachment,
                    onOpenAttachmentPreview = openPreview,
                )
            }
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
    onRequestOriginalAttachment: ((String) -> Unit)? = null,
    onOpenAttachmentPreview: ((String) -> Unit)? = null,
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
        UserMessageBubble(
            entry = entry,
            modifier = modifier,
            onDownloadAttachment = onDownloadAttachment,
            onRequestOriginalAttachment = onRequestOriginalAttachment,
            onOpenAttachmentPreview = onOpenAttachmentPreview,
        )
    } else {
        AssistantMessageRow(
            entry = entry,
            onDownloadAttachment = onDownloadAttachment,
            onRequestOriginalAttachment = onRequestOriginalAttachment,
            onOpenAttachmentPreview = onOpenAttachmentPreview,
            modifier = modifier.semantics {
                contentDescription = "OaiMessage:${entry.key}:${if (entry.isStreaming) "streaming" else "completed"}"
            },
        )
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
    onRequestOriginalAttachment: ((String) -> Unit)? = null,
    onOpenAttachmentPreview: ((String) -> Unit)? = null,
) {
    val failed = entry.submissionFailureCode != null || entry.messageStatus == AgentMessageStatus.FAILED
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
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
                MessageAttachments(
                    attachments = entry.attachments,
                    onDownload = onDownloadAttachment,
                    onRequestOriginal = onRequestOriginalAttachment,
                    onOpenPreview = onOpenAttachmentPreview,
                )

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

        // 复制按钮放在气泡外侧下方：只复制原文，不改变气泡本身的排版与高度。
        if (entry.text.isNotBlank()) {
            MessageCopyAction(
                messageKey = entry.key,
                text = entry.text,
                modifier = Modifier.padding(top = Dimensions.SpaceTiny),
            )
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

/**
 * 宿主级大图预览槽。
 *
 * 预览 id 与唯一的 [AttachmentLightbox] 都放在 Lazy 列表外：列表项因折叠换 key、
 * 流式自动滚底或回收时，用户正在看的大图不会跟着消失；按 id 从最新附件列表里
 * 取对象，保存/原图状态仍能实时刷新。
 */
@Composable
fun AttachmentPreviewHost(
    attachments: List<TimelineAttachment>,
    onDownload: ((String) -> Unit)?,
    onRequestOriginal: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable (openPreview: (String) -> Unit) -> Unit,
) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = openId?.let { id -> attachments.firstOrNull { it.draftId == id } }
    LaunchedEffect(openId, selected) {
        // 消息被删除/切换会话后附件不复存在：关闭预览，而不是继续显示旧对象。
        if (openId != null && selected == null) openId = null
    }
    Box(modifier) {
        content { id -> openId = id }
    }
    if (selected != null) {
        AttachmentLightbox(
            attachment = selected,
            onDismiss = { openId = null },
            onDownload = onDownload,
            onRequestOriginal = onRequestOriginal,
        )
    }
}

/** 所有发送者和附件身份都走同一入口；图片与普通文件分别呈现。 */
@Composable
private fun MessageAttachments(
    attachments: List<TimelineAttachment>,
    onDownload: ((String) -> Unit)?,
    onRequestOriginal: ((String) -> Unit)?,
    onOpenPreview: ((String) -> Unit)? = null,
) {
    attachments.forEach {
        HistoricalAttachmentCard(
            attachment = it,
            onDownload = onDownload,
            onRequestOriginal = onRequestOriginal,
            onOpenPreview = onOpenPreview,
        )
    }
}

/**
 * 附件卡分流：
 * - 图片：气泡内只保留图片本身（文件名与下载按钮都已移除），点击进入全屏预览；
 * - 其它文件：保留文件名、本地/下载状态与下载按钮，PDF 等仍可保存。
 */
@Composable
fun HistoricalAttachmentCard(
    attachment: TimelineAttachment,
    onDownload: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    onRequestOriginal: ((String) -> Unit)? = null,
    onOpenPreview: ((String) -> Unit)? = null,
    showCaption: Boolean = false,
) {
    if (attachment.isImage) {
        ImageAttachmentCard(attachment, modifier, onDownload, onRequestOriginal, onOpenPreview, showCaption)
    } else {
        FileAttachmentCard(attachment, modifier, onDownload)
    }
}

/**
 * 图片附件：不再显示文件名与列表内下载按钮。
 *
 * 图片按比例完整显示（ContentScale.Fit），宽高受 [Dimensions.AttachmentImageMaxHeight]
 * 约束；点击进入 [AttachmentLightbox]，下载入口在预览操作栏与长按菜单中。
 */
@Composable
private fun ImageAttachmentCard(
    attachment: TimelineAttachment,
    modifier: Modifier,
    onDownload: ((String) -> Unit)?,
    onRequestOriginal: ((String) -> Unit)?,
    onOpenPreview: ((String) -> Unit)?,
    showCaption: Boolean,
) {
    // 宿主提供了预览打开回调时，Dialog 由宿主级 AttachmentPreviewHost 持有：
    // 列表项滚动回收/折叠换 key 不会销毁用户正在看的大图。
    var localPreviewVisible by rememberSaveable(attachment.draftId) { mutableStateOf(false) }
    val openPreview: () -> Unit = {
        if (onOpenPreview != null) onOpenPreview(attachment.draftId) else localPreviewVisible = true
    }
    // 缩略图解码放到 IO 线程并交给 ImageDecoder 处理 EXIF 方向；失败时保留可点的
    // 占位，用户仍可进入 Lightbox 尝试加载原图，而不是得到一张空白。
    val image = rememberDecodedPreview(attachment.previewBytes)
    val canOpen = image != null || attachment.localState != AttachmentLocalState.UNAVAILABLE

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
    ) {
        if (showCaption) {
            AttachmentFallbackChip(attachment.filename.ifBlank { "图片附件" }, isImage = true)
        }
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "查看大图：${attachment.filename.ifBlank { "图片" }}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = Dimensions.AttachmentImageMaxHeight)
                    .clip(RoundedCornerShape(AppRadius.Medium))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable(enabled = canOpen, role = Role.Button, onClickLabel = "查看大图") {
                        openPreview()
                    },
            )
        } else {
            ImageUnavailablePlaceholder(
                attachment = attachment,
                canOpen = canOpen,
                onOpen = openPreview,
            )
        }

        when (attachment.localState) {
            AttachmentLocalState.PENDING -> Text(
                "正在读取图片…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AttachmentLocalState.UNAVAILABLE -> Text(
                "原件不可用，暂时无法查看大图",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AttachmentLocalState.FAILED -> Text(
                "本地保存失败，请检查手机存储空间或连接",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            AttachmentLocalState.SAVED -> Unit
        }
        // 图片的下载状态只在 Lightbox 内就地反馈；列表不再重复显示保存结果。
        // 附件库需要直接识别与保存文件；会话气泡保持纯图降噪。
        if (showCaption) {
            TextButton(
                onClick = { onDownload?.invoke(attachment.draftId) },
                enabled = onDownload != null &&
                    attachment.localState != AttachmentLocalState.UNAVAILABLE &&
                    attachment.downloadState != AttachmentDownloadState.DOWNLOADING,
            ) {
                Text(if (attachment.downloadState == AttachmentDownloadState.DOWNLOADING) "下载中…" else "下载")
            }
        }

        if (onOpenPreview == null && localPreviewVisible) {
            AttachmentLightbox(
                attachment = attachment,
                onDismiss = { localPreviewVisible = false },
                onDownload = onDownload,
                onRequestOriginal = onRequestOriginal,
            )
        }
    }
}

@Composable
private fun ImageUnavailablePlaceholder(
    attachment: TimelineAttachment,
    canOpen: Boolean,
    onOpen: () -> Unit,
) {
    val shape = RoundedCornerShape(AppRadius.Medium)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(
                min = Dimensions.AttachmentImageMaxHeight / 2,
                max = Dimensions.AttachmentImageMaxHeight,
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(Dimensions.StrokeHairline, MaterialTheme.colorScheme.outlineVariant, shape)
            .then(
                if (canOpen) {
                    Modifier
                        .clickable(role = Role.Button, onClickLabel = "查看大图", onClick = onOpen)
                        .semantics {
                            contentDescription = "查看大图：${attachment.filename.ifBlank { "图片" }}"
                        }
                } else {
                    Modifier.semantics { contentDescription = "图片预览不可用" }
                },
            )
            .padding(Dimensions.SpaceMedium),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimensions.Icon),
        )
        Text(
            text = if (attachment.localState == AttachmentLocalState.UNAVAILABLE) {
                "原件不可用，暂时无法查看"
            } else {
                "图片预览不可用"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 普通文件附件：文件名是唯一身份，因此保留文件名与下载按钮。 */
@Composable
private fun FileAttachmentCard(
    attachment: TimelineAttachment,
    modifier: Modifier,
    onDownload: ((String) -> Unit)?,
) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimensions.SpaceMedium), verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            AttachmentFallbackChip(attachment.filename.ifBlank { "附件文件" }, isImage = false)
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
    onRequestOriginalAttachment: ((String) -> Unit)? = null,
    onOpenAttachmentPreview: ((String) -> Unit)? = null,
) {
    val failed = entry.messageStatus == AgentMessageStatus.FAILED || entry.submissionFailureCode != null
    Column(
        modifier = modifier
            .fillMaxWidth()
            .stitchGuide(failed = failed)
            .padding(start = Dimensions.StitchGutter),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        MessageAttachments(
            attachments = entry.attachments,
            onDownload = onDownloadAttachment,
            onRequestOriginal = onRequestOriginalAttachment,
            onOpenPreview = onOpenAttachmentPreview,
        )

        // 文本内容（含实时执行卡片/最终回答）统一交给执行过程渲染层；
        // 附件、元数据与复制仍由这一层负责，保持四个宿主一致。
        AssistantMessageContent(entry = entry)

        MessageMetadata(entry = entry, failed = failed)

        // 与用户气泡一致的「原文复制」入口：复制的是含 Markdown 标记的原始内容。
        if (entry.text.isNotBlank()) {
            MessageCopyAction(messageKey = entry.key, text = entry.text)
        }
    }
}

/**
 * 时间线一行的一次渲染结果：普通条目仍交给 [TimelineRow]，
 * 折叠后的执行段交给 [ExecutionTurnRow]。耗时 store 在这一层注入，
 * 因此从实时卡片收进聚合面板时，已观察到的耗时不会丢失。
 */
@Composable
fun TimelineDisplayRow(
    item: TimelineDisplayItem,
    onDecide: (String, ApprovalChoice) -> Unit,
    timing: ExecutionTimingStore,
    modifier: Modifier = Modifier,
    onDownloadAttachment: ((String) -> Unit)? = null,
    onRequestOriginalAttachment: ((String) -> Unit)? = null,
    onOpenAttachmentPreview: ((String) -> Unit)? = null,
) {
    CompositionLocalProvider(LocalExecutionTiming provides timing) {
        when (item) {
            is TimelineDisplayItem.Single -> TimelineRow(
                entry = item.entry,
                onDecide = onDecide,
                modifier = modifier,
                onDownloadAttachment = onDownloadAttachment,
                onRequestOriginalAttachment = onRequestOriginalAttachment,
                onOpenAttachmentPreview = onOpenAttachmentPreview,
            )

            is TimelineDisplayItem.Execution -> ExecutionTurnRow(
                run = item.run,
                modifier = modifier,
                onDownloadAttachment = onDownloadAttachment,
                onRequestOriginalAttachment = onRequestOriginalAttachment,
                onOpenAttachmentPreview = onOpenAttachmentPreview,
            )
        }
    }
}

/**
 * 一个被折叠的执行段：最终消息附件 → 执行过程面板 + 最终回答 → 元数据/复制。
 *
 * 过程条目自身的附件由 [AssistantExecutionTurnBody] 的 slot 在面板内部渲染；
 * 没有最终回答时，最终消息的附件也属于过程内容，因此不在外层重复渲染。
 */
@Composable
private fun ExecutionTurnRow(
    run: AssistantExecutionRun,
    modifier: Modifier,
    onDownloadAttachment: ((String) -> Unit)?,
    onRequestOriginalAttachment: ((String) -> Unit)?,
    onOpenAttachmentPreview: ((String) -> Unit)?,
) {
    val failed = run.finalEntry.messageStatus == AgentMessageStatus.FAILED ||
        run.finalEntry.submissionFailureCode != null
    Column(
        modifier = modifier
            .fillMaxWidth()
            .stitchGuide(failed = failed)
            .padding(start = Dimensions.StitchGutter),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        if (run.hasFinalAnswer) {
            MessageAttachments(
                attachments = run.finalEntry.attachments,
                onDownload = onDownloadAttachment,
                onRequestOriginal = onRequestOriginalAttachment,
                onOpenPreview = onOpenAttachmentPreview,
            )
        }

        AssistantExecutionTurnBody(
            run = run,
            processAttachmentsSlot = { processEntry ->
                if (processEntry.attachments.isNotEmpty()) {
                    MessageAttachments(
                        attachments = processEntry.attachments,
                        onDownload = onDownloadAttachment,
                        onRequestOriginal = onRequestOriginalAttachment,
                        onOpenPreview = onOpenAttachmentPreview,
                    )
                }
            },
        )

        MessageMetadata(entry = run.finalEntry, failed = failed)

        // 复制的是最终消息的原始文本（含 Markdown 标记），与既有逐条复制语义一致。
        if (run.finalEntry.text.isNotBlank()) {
            MessageCopyAction(messageKey = run.finalEntry.key, text = run.finalEntry.text)
        }
    }
}

/** 复制成功提示的展示时长：足够看清，又不会长期占位。 */
private const val CopyFeedbackDurationMillis = 1600L

/**
 * 单条消息的「复制原文」按钮。
 *
 * 复制的是消息未渲染前的原始内容（含 Markdown 标记），与界面上看到的样子无关；
 * 成功后按钮原地变成「复制成功」并在 1.6 秒后恢复，不弹 Toast、不推动正文布局。
 */
@Composable
fun MessageCopyAction(
    messageKey: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    // 反馈状态跟随消息身份而不是正文：流式输出更新 text 时不能把「复制成功」提前清掉
    var copied by remember(messageKey) { mutableStateOf(false) }
    // 每次点击递增，保证连续点击会重新计时，而不是因为 copied 已经是 true 而忽略
    var copyRequests by remember(messageKey) { mutableStateOf(0) }

    LaunchedEffect(copyRequests) {
        if (copyRequests == 0) return@LaunchedEffect
        copied = true
        delay(CopyFeedbackDurationMillis)
        copied = false
    }

    Row(
        modifier = modifier
            .heightIn(min = Dimensions.MinimumTouchTarget)
            .clip(RoundedCornerShape(AppRadius.ExtraSmall))
            .clickable(role = Role.Button) {
                clipboardManager.setText(AnnotatedString(text))
                copyRequests++
            }
            // 复制成功属于状态变化：用 liveRegion 让读屏软件在按钮聚焦时也能播报结果。
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = Dimensions.SpaceSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
    ) {
        Icon(
            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = null,
            tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimensions.SmallIcon),
        )
        Text(
            text = if (copied) "复制成功" else "复制",
            style = MaterialTheme.typography.labelSmall,
            color = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
