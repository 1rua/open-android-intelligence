package com.openandroidintelligence.conversation.workbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.components.bouncyClickable
import com.openandroidintelligence.conversation.model.AttachmentDraft
import com.openandroidintelligence.conversation.model.AttachmentState
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalAppStateColors
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlin.math.roundToInt

/**
 * 共享消息输入底栏：
 * 1. 附件加号按钮打开系统选择菜单（拍照、相册选择、文档选择）；
 * 2. 附件暂存草稿横条（M14：高度形变，状态文字 + 图标双通道）；
 * 3. 动态伸缩输入框（支持多行展开与形变动效）；
 * 4. 语音输入入口与发送/停止按钮（M07：容器尺寸、颜色与图标一起过渡）；
 * 5. 状态机严格受控，无虚假快捷项。
 *
 * 底部安全区：输入坞自己承担 `navigationBarsPadding()`，宿主只在坞外层加
 * 水平/垂直边距。导航栏 inset 随后被消费，`imePadding()` 只补键盘超出导航栏的高度，
 * 键盘弹出时不会出现「导航栏高度加键盘高度」的双重留白。
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
    voiceInputEnabled: Boolean = true,
    inFlightAttachmentIds: Set<String> = emptySet(),
    hasUnknownSubmissionOutcome: Boolean = false,
) {
    val reduced = LocalMotionPolicy.current.reduceMotion
    val running = generation == GenerationState.RUNNING || generation == GenerationState.QUEUED
    val waitingCancel = generation == GenerationState.CANCEL_REQUESTED
    val unknown = generation == GenerationState.OUTCOME_UNKNOWN
    val hasPendingInput = draft.isNotBlank() || attachments.any { it.id.value !in inFlightAttachmentIds }
    // While a turn is active, a populated composer belongs to the next turn.
    // Keep cancellation visible only when there is nothing queued by the user.
    val showStop = waitingCancel || (running && !hasPendingInput)
    val actionEnabled = when {
        waitingCancel || unknown -> false
        showStop -> running && !hasPendingInput
        else -> hasPendingInput && canSend && !hasUnknownSubmissionOutcome
    }

    var focused by remember { mutableStateOf(false) }
    var showAttachmentsSheet by remember { mutableStateOf(false) }

    val insetModifier = Modifier
        .navigationBarsPadding()
        .consumeWindowInsets(WindowInsets.navigationBars)
        .then(if (applyImePadding) Modifier.imePadding() else Modifier)

    Column(modifier = modifier.fillMaxWidth().then(insetModifier)) {
        // ===== 1. 附件草稿横条：显隐用同一个临界阻尼形变（M14）=====
        AnimatedVisibility(
            visible = attachments.isNotEmpty(),
            enter = fadeIn(MotionSpecs.fade(reduced)) +
                expandVertically(animationSpec = MotionSpecs.morph(reduced)),
            exit = fadeOut(MotionSpecs.fade(reduced)) +
                shrinkVertically(animationSpec = MotionSpecs.morph(reduced)),
        ) {
            AttachmentDraftStrip(
                attachments = attachments,
                inFlightAttachmentIds = inFlightAttachmentIds,
                onRemove = onRemoveAttachment,
                onRetry = onRetryAttachment,
                modifier = Modifier.padding(bottom = Dimensions.SpaceSmall),
            )
        }

        // ===== 2. 输入坞主行：视觉 44dp / 触摸 48dp，主操作 48dp，行距 8dp =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimensions.SpaceTiny),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            ComposerIconAction(
                icon = Icons.Default.Add,
                contentDescription = "添加附件",
                onClick = { showAttachmentsSheet = true },
            )

            ComposerInputField(
                value = draft,
                onValueChange = onDraftChange,
                isFocused = focused,
                onFocusChange = { focused = it },
                modifier = Modifier.weight(1f),
            )

            ComposerIconAction(
                icon = Icons.Default.Mic,
                contentDescription = "语音输入",
                enabled = voiceInputEnabled,
                onClick = onVoiceInput,
            )

            ComposerSendStopAction(
                showStop = showStop,
                waitingCancel = waitingCancel,
                enabled = actionEnabled,
                onSend = onSend,
                onStop = onStop,
            )
        }

        // ===== 3. 生成状态行：输入坞内部左对齐，紧贴输入框下方 =====
        generationLabel(generation)?.let { label ->
            ComposerGenerationStatus(label = label, generation = generation)
        }
    }

    // ===== 4. 多模态附件选择底部弹层 =====
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
                    modifier = Modifier.padding(
                        horizontal = Dimensions.SpaceLarge,
                        vertical = Dimensions.SpaceSmall,
                    ),
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

/**
 * 自绘圆形入口：视觉直径 [Dimensions.ComposerControl]，触摸目标由外圈
 * [Dimensions.MinimumTouchTarget] 兜底；按压走 `bouncyClickable` 的 0.97 内收。
 */
@Composable
private fun ComposerIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(Dimensions.MinimumTouchTarget)
            .clip(CircleShape)
            .bouncyClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.size(Dimensions.ComposerControl),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = if (enabled) 1f else 0.38f),
                    modifier = Modifier.size(Dimensions.Icon),
                )
            }
        }
    }
}

/** 输入框容器：`surfaceContainer` + 20dp 圆角 + 48dp 最小高度，聚焦时描边渐显。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerInputField(
    value: String,
    onValueChange: (String) -> Unit,
    isFocused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalMotionPolicy.current.reduceMotion
    val shape = RoundedCornerShape(AppRadius.Bubble)
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0f)
        },
        animationSpec = if (reduced) snap() else tween(MotionSpecs.Fast, easing = MotionSpecs.StandardEasing),
        label = "composer-field-border",
    )
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .heightIn(min = Dimensions.ComposerMinHeight)
            .animateContentSize(MotionSpecs.morph(reduced))
            .border(Dimensions.StrokeHairline, borderColor, shape),
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    "输入消息或 / 命令...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onFocusChange(it.isFocused) },
            minLines = 1,
            maxLines = 4,
            shape = shape,
            textStyle = MaterialTheme.typography.bodyLarge,
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
}

/**
 * 发送 / 停止主操作（M07）：
 * 容器宽度、圆角、底色与图标在同一时间窗内一起变化，而不是只换一个图标；
 * 减少动态时宽度与圆角直接落位，只保留极短颜色过渡。
 */
@Composable
private fun ComposerSendStopAction(
    showStop: Boolean,
    waitingCancel: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalMotionPolicy.current.reduceMotion
    val targetWidth = if (showStop) StopActionWidth else Dimensions.ComposerPrimaryAction
    val width by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = MotionSpecs.snappy(reduced),
        label = "composer-action-width",
    )
    val cornerPercent by animateFloatAsState(
        targetValue = if (showStop) StopActionCornerPercent else 50f,
        animationSpec = MotionSpecs.snappy(reduced),
        label = "composer-action-corner",
    )
    val colorSpec = if (reduced) snap<Color>() else tween(MotionSpecs.Fast, easing = MotionSpecs.StandardEasing)
    val containerColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.surfaceContainer
            showStop -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = colorSpec,
        label = "composer-action-container",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            showStop -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onPrimary
        },
        animationSpec = colorSpec,
        label = "composer-action-content",
    )
    val shape = RoundedCornerShape(percent = cornerPercent.roundToInt())

    Box(
        modifier = modifier
            .width(width)
            .height(Dimensions.ComposerPrimaryAction)
            .clip(shape)
            .bouncyClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = if (showStop) onStop else onSend,
            )
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = showStop,
            animationSpec = if (reduced) snap() else tween(MotionSpecs.Fast, easing = MotionSpecs.LinearOutSlowInEasing),
            label = "send-stop",
        ) { stop ->
            Icon(
                imageVector = if (stop) Icons.Default.Stop else Icons.Default.ArrowUpward,
                contentDescription = if (waitingCancel) {
                    "等待取消确认"
                } else if (stop) {
                    "停止生成"
                } else {
                    "发送"
                },
                tint = contentColor,
                modifier = Modifier.size(Dimensions.Icon),
            )
        }
    }
}

/** 生成状态行：文案来自真实 [GenerationState]，颜色只是第二通道。 */
@Composable
private fun ComposerGenerationStatus(
    label: String,
    generation: GenerationState,
) {
    val waiting = generation == GenerationState.QUEUED ||
        generation == GenerationState.CANCEL_REQUESTED ||
        generation == GenerationState.OUTCOME_UNKNOWN
    val color = when {
        generation == GenerationState.FAILED -> MaterialTheme.colorScheme.error
        waiting -> LocalAppStateColors.current.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when {
        generation == GenerationState.FAILED -> Icons.Default.ErrorOutline
        waiting -> Icons.Default.HourglassTop
        else -> Icons.Default.Info
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Dimensions.ComposerControl + Dimensions.SpaceSmall,
                end = Dimensions.SpaceSmall,
                top = Dimensions.SpaceTiny,
            )
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(Dimensions.SmallIcon),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/**
 * 底部弹层的单个选项：M3 ListItem 的视觉节奏（40dp tonal 圆形前置图标 +
 * 标题/说明两行），条目最小高度 56dp，并额外应用底部安全区。
 */
@Composable
private fun AttachmentChoice(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AttachmentChoiceHeight)
            .bouncyClickable(
                role = Role.Button,
                onClickLabel = title,
                onClick = onClick,
            )
            .padding(horizontal = Dimensions.SpaceLarge, vertical = Dimensions.SpaceCompact),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(AttachmentChoiceIconSize),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(Dimensions.Icon),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AttachmentDraftStrip(
    attachments: List<AttachmentDraft>,
    inFlightAttachmentIds: Set<String>,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        items(attachments, key = { it.id.value }) { draft ->
            AttachmentDraftChip(
                draft,
                onRemove = { onRemove(draft.id.value) },
                onRetry = { onRetry(draft.id.value) },
                removeEnabled = draft.id.value !in inFlightAttachmentIds,
            )
        }
    }
}

/**
 * 附件草稿卡片。
 *
 * 状态必须双通道呈现（规格 §4.1）：文字永远来自真实 [AttachmentState]，
 * 颜色只负责第二层区分。等待/结果未知走 `warning` 语义色，失败走 `error`，
 * 「上传中 / 等待合并」绝不画成「已完成」的样子。
 */
@Composable
fun AttachmentDraftChip(
    draft: AttachmentDraft,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    removeEnabled: Boolean = true,
) {
    val reduced = LocalMotionPolicy.current.reduceMotion
    val stateColors = LocalAppStateColors.current
    val state = draft.state
    val failed = state == AttachmentState.RETRYABLE_FAILURE || state == AttachmentState.TERMINAL_FAILURE
    val unknown = state == AttachmentState.OUTCOME_UNKNOWN
    val canRetry = state == AttachmentState.RETRYABLE_FAILURE || state == AttachmentState.OUTCOME_UNKNOWN
    val verifying = state == AttachmentState.VERIFYING
    val working = state == AttachmentState.LOCAL_PREPARING ||
        state == AttachmentState.CREATE_PENDING ||
        state == AttachmentState.UPLOADING ||
        verifying

    val container = when {
        failed -> MaterialTheme.colorScheme.errorContainer
        unknown -> stateColors.warningContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        unknown -> stateColors.onWarningContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val stateTint = when {
        failed -> MaterialTheme.colorScheme.error
        unknown -> stateColors.warning
        state == AttachmentState.VERIFIED -> stateColors.success
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(AppRadius.Medium),
        color = container,
        contentColor = content,
        modifier = modifier.animateContentSize(MotionSpecs.morph(reduced)),
    ) {
        Row(
            modifier = Modifier.padding(
                start = Dimensions.SpaceCompact,
                end = Dimensions.SpaceTiny,
                top = Dimensions.SpaceTiny,
                bottom = Dimensions.SpaceTiny,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            Icon(
                imageVector = when {
                    draft.mediaType.startsWith("image/") -> Icons.Default.Image
                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = null,
                tint = stateTint,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )

            Column(modifier = Modifier.widthIn(max = AttachmentNameWidth)) {
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
                    color = content.copy(alpha = 0.8f),
                )
                draft.errorMessage?.let { code ->
                    Text(
                        text = attachmentErrorLabel(code),
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = 0.9f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (working) {
                CircularProgressIndicator(
                    modifier = Modifier.size(AttachmentProgressSize),
                    strokeWidth = 2.dp,
                    color = stateTint,
                )
            } else {
                Icon(
                    imageVector = attachmentStatusIcon(state),
                    contentDescription = null,
                    tint = stateTint,
                    modifier = Modifier.size(Dimensions.SmallIcon),
                )
            }

            if (canRetry) {
                IconButton(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = if (unknown) "核实附件上传结果" else "重试上传",
                        tint = stateTint,
                        modifier = Modifier.size(Dimensions.SmallIcon),
                    )
                }
            }

            IconButton(onClick = onRemove, enabled = removeEnabled) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "移除附件",
                    tint = content.copy(alpha = if (removeEnabled) 0.8f else 0.38f),
                    modifier = Modifier.size(Dimensions.SmallIcon),
                )
            }
        }
    }
}

private fun attachmentStatusIcon(state: AttachmentState): ImageVector = when (state) {
    AttachmentState.VERIFIED -> Icons.Default.CheckCircle
    AttachmentState.RETRYABLE_FAILURE,
    AttachmentState.TERMINAL_FAILURE,
    -> Icons.Default.ErrorOutline
    AttachmentState.OUTCOME_UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline
    AttachmentState.CANCELLED -> Icons.Default.Cancel
    AttachmentState.LOCAL_PREPARING,
    AttachmentState.CREATE_PENDING,
    AttachmentState.UPLOADING,
    AttachmentState.VERIFYING,
    -> Icons.Default.Schedule
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

/**
 * 同一批次的待合并消息条：紧凑一行到多行，左侧 3dp 主色指示条，
 * 右侧 `labelSmall` 计数。不再用 `surfaceVariant` 铺一整块色。
 */
@Composable
fun PendingBatchStrip(
    members: List<com.openandroidintelligence.conversation.state.TimelineEntry>,
    modifier: Modifier = Modifier,
) {
    if (members.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = Dimensions.SpaceMedium, vertical = Dimensions.SpaceTiny),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        Box(
            modifier = Modifier
                .width(PendingBatchIndicatorWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(AppRadius.ExtraSmall))
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
        ) {
            members.forEach { member ->
                Text(
                    text = member.text.ifBlank { "（空消息）" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = "${members.size} 条 · 等待合并",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 发送/停止形变宽度：停止态略宽，形成可见的容器尺寸变化。 */
private val StopActionWidth = 56.dp

/** 停止态的圆角百分比（相对短边），由圆形渐变到胶囊。 */
private const val StopActionCornerPercent = 36f

/** 底部弹层选项的最小高度。 */
private val AttachmentChoiceHeight = 56.dp

/** 底部弹层选项前置 tonal 圆形图标的直径。 */
private val AttachmentChoiceIconSize = 40.dp

/** 附件卡片文件名区域的最大宽度，避免长文件名把操作按钮挤出屏幕。 */
private val AttachmentNameWidth = 150.dp

/** 附件卡片上传中的不定进度直径。 */
private val AttachmentProgressSize = 14.dp

/** 批次条左侧指示条宽度。 */
private val PendingBatchIndicatorWidth = 3.dp
