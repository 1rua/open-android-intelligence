package com.openandroidintelligence.conversation.workbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.model.AttachmentDraft
import com.openandroidintelligence.conversation.model.AttachmentState
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/**
 * 共享消息输入底栏：
 * 1. 附件加号按钮打开系统选择菜单（拍照、相册选择、文档选择）；
 * 2. 附件暂存草稿胶囊横条；
 * 3. 动态伸缩输入框（支持多行展开与回弹物理动效）；
 * 4. 语音输入入口与发送/停止按钮平滑交叉淡化切换；
 * 5. 状态机严格受控，无虚假快捷项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    generation: GenerationState,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickCamera: () -> Unit,
    onPickGallery: () -> Unit,
    onPickDocument: () -> Unit,
    onVoiceInput: () -> Unit,
    attachments: List<AttachmentDraft> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
    onRetryAttachment: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    applyImePadding: Boolean = true,
    onQuickChip: ((String) -> Unit)? = null,
) {
    val reduced = LocalMotionPolicy.current.reduceMotion
    val running = generation == GenerationState.RUNNING || generation == GenerationState.QUEUED
    val waitingCancel = generation == GenerationState.CANCEL_REQUESTED
    val unknown = generation == GenerationState.OUTCOME_UNKNOWN

    var focused by remember { mutableStateOf(false) }
    var showAttachmentsSheet by remember { mutableStateOf(false) }

    val imeModifier = if (applyImePadding) Modifier.imePadding() else Modifier

    Column(modifier = modifier.fillMaxWidth().then(imeModifier)) {
        // ===== 1. Attachments Preview Strip =====
        AnimatedVisibility(
            visible = attachments.isNotEmpty(),
            enter = fadeIn(MotionSpecs.fade(reduced)),
            exit = fadeOut(MotionSpecs.fade(reduced)),
        ) {
            AttachmentDraftStrip(
                attachments = attachments,
                onRemove = onRemoveAttachment,
                onRetry = onRetryAttachment,
                modifier = Modifier.padding(bottom = Dimensions.SpaceSmall),
            )
        }

        // ===== 2. Main Composer Row =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // [ + ] 添加附件按钮
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .clickable { showAttachmentsSheet = true },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "添加附件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // [ 胶囊输入框 ]
            Surface(
                shape = RoundedCornerShape(AppRadius.Bubble),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .weight(1f)
                    .animateContentSize(MotionSpecs.spatial(reduced)),
            ) {
                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = {
                        Text(
                            "输入消息或 / 命令...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                    minLines = 1,
                    maxLines = 4,
                    shape = RoundedCornerShape(AppRadius.Bubble),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }

            // [ 🎤 ] 语音输入按钮
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onVoiceInput),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    )
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "语音输入",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // [ 发送 / 停止 按钮 ]
            FilledIconButton(
                onClick = if (running) onStop else onSend,
                enabled = running || (canSend && !waitingCancel && !unknown),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                ),
                modifier = Modifier.size(42.dp),
            ) {
                Crossfade(
                    targetState = running || waitingCancel,
                    animationSpec = MotionSpecs.fade(reduced),
                    label = "send-stop",
                ) { stop ->
                    Icon(
                        imageVector = if (stop) Icons.Default.Stop else Icons.Default.ArrowUpward,
                        contentDescription = if (waitingCancel) "等待取消确认" else if (stop) "停止生成" else "发送",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        generationLabel(generation)?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (unknown || generation == GenerationState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = Dimensions.SpaceSmall, vertical = 2.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    // ===== 3. 多模态附件选择底部弹窗 =====
    if (showAttachmentsSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachmentsSheet = false }) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(bottom = Dimensions.SpaceLarge),
            ) {
                Text(
                    "添加到当前对话",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = Dimensions.SpaceLarge, vertical = Dimensions.SpaceSmall),
                )
                AttachmentChoice(
                    icon = Icons.Default.PhotoCamera,
                    title = "拍照",
                    description = "使用系统相机拍摄照片",
                ) {
                    showAttachmentsSheet = false
                    onPickCamera()
                }
                AttachmentChoice(
                    icon = Icons.Default.PhotoLibrary,
                    title = "相册选择",
                    description = "从相册选择已有图片",
                ) {
                    showAttachmentsSheet = false
                    onPickGallery()
                }
                AttachmentChoice(
                    icon = Icons.Default.AttachFile,
                    title = "文档选择",
                    description = "通过系统文件选择器添加文档",
                ) {
                    showAttachmentsSheet = false
                    onPickDocument()
                }
            }
        }
    }
}

@Composable
private fun AttachmentChoice(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimensions.SpaceLarge, vertical = Dimensions.SpaceMedium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimensions.Icon),
            )
            Spacer(Modifier.width(Dimensions.SpaceMedium))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AttachmentDraftStrip(
    attachments: List<AttachmentDraft>,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        items(attachments, key = { it.id.value }) { draft ->
            AttachmentDraftChip(draft, onRemove = { onRemove(draft.id.value) }, onRetry = { onRetry(draft.id.value) })
        }
    }
}

@Composable
fun AttachmentDraftChip(
    draft: AttachmentDraft,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = draft.state
    val failed = state == AttachmentState.RETRYABLE_FAILURE || state == AttachmentState.TERMINAL_FAILURE ||
        state == AttachmentState.OUTCOME_UNKNOWN
    val canRetry = state == AttachmentState.RETRYABLE_FAILURE || state == AttachmentState.OUTCOME_UNKNOWN
    val verifying = state == AttachmentState.VERIFYING
    val working = state == AttachmentState.LOCAL_PREPARING ||
        state == AttachmentState.CREATE_PENDING ||
        state == AttachmentState.UPLOADING ||
        verifying

    Surface(
        shape = RoundedCornerShape(AppRadius.Medium),
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = when {
                    draft.mediaType.startsWith("image/") -> Icons.Default.Image
                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = null,
                tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )

            Column(modifier = Modifier.widthIn(max = 140.dp)) {
                Text(
                    text = draft.filename,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = attachmentProgressLabel(draft),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
                draft.errorMessage?.let { code ->
                    Text(
                        text = attachmentErrorLabel(code),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (working) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (canRetry) {
                IconButton(onClick = onRetry, modifier = Modifier.size(20.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = if (state == AttachmentState.OUTCOME_UNKNOWN) "核实附件上传结果" else "重试上传",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            IconButton(onClick = onRemove, modifier = Modifier.size(20.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "移除附件",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

fun attachmentStateLabel(state: AttachmentState): String = when (state) {
    AttachmentState.LOCAL_PREPARING -> "正在加密暂存"
    AttachmentState.CREATE_PENDING -> "正在申请上传"
    AttachmentState.UPLOADING -> "正在上传"
    AttachmentState.VERIFYING -> "正在核验完整性"
    AttachmentState.VERIFIED -> "已核验，可发送"
    AttachmentState.RETRYABLE_FAILURE -> "上传失败，可重试"
    AttachmentState.TERMINAL_FAILURE -> "无法上传，请移除后重新选择"
    AttachmentState.OUTCOME_UNKNOWN -> "上传结果未知，请先核实"
    AttachmentState.CANCELLED -> "已取消上传"
}

private fun attachmentProgressLabel(draft: AttachmentDraft): String {
    val totalBytes = draft.totalBytes
    return when {
    draft.state == AttachmentState.LOCAL_PREPARING && draft.transferredBytes > 0L ->
        "正在加密暂存 · 已读 ${formatAttachmentSize(draft.transferredBytes)}"
    draft.state == AttachmentState.UPLOADING && totalBytes != null ->
        "正在上传 · ${formatAttachmentSize(draft.transferredBytes)} / ${formatAttachmentSize(totalBytes)}"
    else -> attachmentStateLabel(draft.state)
    }
}

private fun attachmentErrorLabel(code: String): String = when {
    code.contains("ATTACHMENT_STORAGE_UNAVAILABLE") -> "本机存储空间不足或暂存不可用"
    code.contains("ATTACHMENT_READ_FAILED") -> "无法读取所选文件"
    code.contains("ATTACHMENT_DIGEST_MISMATCH") || code.contains("DIGEST_MISMATCH") -> "附件完整性校验失败"
    else -> "错误代码：${code.take(80)}"
}

fun generationLabel(state: GenerationState): String? = when (state) {
    GenerationState.IDLE, GenerationState.COMPLETED -> null
    GenerationState.QUEUED -> "消息已接收，等待回复"
    GenerationState.RUNNING -> "正在接收回复"
    GenerationState.CANCEL_REQUESTED -> "正在请求停止，等待 Gateway 确认"
    GenerationState.CANCELLED -> "本次生成已停止"
    GenerationState.FAILED -> "本次生成失败，请检查连接后重试"
    GenerationState.UNSUPPORTED -> "当前 Gateway 不支持停止生成"
    GenerationState.OUTCOME_UNKNOWN -> "生成结果尚未确认，请先刷新会话核实，避免重复发送"
}

fun formatAttachmentSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> java.lang.String.format(java.util.Locale.getDefault(), "%.1f MB", bytes.toDouble() / (1024 * 1024))
}

@Composable
fun PendingBatchStrip(
    members: List<com.openandroidintelligence.conversation.state.TimelineEntry>,
    modifier: Modifier = Modifier,
) {
    if (members.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(AppRadius.Medium),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            members.forEach { member ->
                Text(
                    text = "● " + member.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "同一批次 · ${members.size} 条 · 等待合并",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
