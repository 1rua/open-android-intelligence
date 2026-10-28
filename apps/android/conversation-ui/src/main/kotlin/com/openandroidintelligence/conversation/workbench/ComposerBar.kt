package com.openandroidintelligence.conversation.workbench

import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.rememberTextFieldState
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
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import com.openandroidintelligence.conversation.ports.PendingDelivery
import com.openandroidintelligence.conversation.ports.PendingDeliveryState
import com.openandroidintelligence.conversation.ports.MessageDeliveryMode
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalAppStateColors
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt

/** Keep the IME's URI permission token alive until the host has staged the selected images. */
data class PastedImageContent(val uris: List<Uri>, val permissionOwner: Bundle? = null)

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
    onPasteImages: ((PastedImageContent) -> Unit)? = null,
    /** Controller and conversation identity; a change discards selection, IME composition and undo. */
    editorKey: Any? = null,
    /** Submission callbacks: selecting a mode does not invoke either one. */
    onQueue: () -> Unit = onSend,
    onSteer: () -> Unit = onSend,
    pendingDeliveries: List<PendingDelivery> = emptyList(),
    onRetryPendingDelivery: () -> Unit = {},
) {
    val reduced = LocalMotionPolicy.current.reduceMotion
    val running = generation == GenerationState.RUNNING || generation == GenerationState.QUEUED
    val waitingCancel = generation == GenerationState.CANCEL_REQUESTED
    val unknown = generation == GenerationState.OUTCOME_UNKNOWN
    val hasPendingInput = draft.isNotBlank() || attachments.any { it.id.value !in inFlightAttachmentIds }
    val showBusyDeliveryChoice = running && hasPendingInput
    var deliveryMode by remember(editorKey, running) { mutableStateOf(MessageDeliveryMode.STEER) }
    val submitDraft = if (showBusyDeliveryChoice) {
        when (deliveryMode) {
            MessageDeliveryMode.QUEUE -> onQueue
            MessageDeliveryMode.STEER -> onSteer
            MessageDeliveryMode.NORMAL -> onSend
        }
    } else onSend
    // A populated composer submits through the selected delivery mode.
    // Keep cancellation visible when the user has no pending input.
    val showStop = waitingCancel || (running && !hasPendingInput)
    val actionEnabled = when {
        waitingCancel || unknown -> false
        showStop -> running && !hasPendingInput
        else -> hasPendingInput && canSend && !hasUnknownSubmissionOutcome
    }

    var focused by remember(editorKey) { mutableStateOf(false) }
    var showAttachmentsSheet by remember { mutableStateOf(false) }

    val insetModifier = Modifier
        .navigationBarsPadding()
        .consumeWindowInsets(WindowInsets.navigationBars)
        .then(if (applyImePadding) Modifier.imePadding() else Modifier)

    Column(modifier = modifier.fillMaxWidth().then(insetModifier)) {
        PendingDeliveryStrip(
            deliveries = pendingDeliveries,
            onRetry = onRetryPendingDelivery,
        )
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

        if (showBusyDeliveryChoice) {
            BusyDeliveryChoice(
                selectedMode = deliveryMode,
                onSelect = { deliveryMode = it },
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

            key(editorKey) {
                ComposerInputField(
                    value = draft,
                    onValueChange = onDraftChange,
                    isFocused = focused,
                    onFocusChange = { focused = it },
                    onPasteImages = onPasteImages,
                    modifier = Modifier.weight(1f),
                )
            }

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
                onSend = submitDraft,
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
                    description = "从相册选择一张或多张图片",
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposerInputField(
    value: String,
    onValueChange: (String) -> Unit,
    isFocused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onPasteImages: ((PastedImageContent) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalMotionPolicy.current.reduceMotion
    val shape = RoundedCornerShape(AppRadius.Bubble)
    // The state-based editor supports Android's image paste/IME content contract.
    // The caller keys this editor by controller/thread so editing state cannot cross conversations.
    val editor = rememberTextFieldState(value)
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    var reportedValue by remember { mutableStateOf(value) }
    // Observe the last report as well: editing and Send may both happen before the next
    // composition, leaving the external string equal to its previous composed value.
    val restoreExternalValue = value != reportedValue
    SideEffect {
        if (restoreExternalValue) {
            if (editor.text.toString() != value) editor.edit { replace(0, length, value) }
            reportedValue = value
        }
    }
    LaunchedEffect(editor) {
        // Undo/redo bypass InputTransformation. Only report a current editor change while
        // the controller still agrees with the last report; late snapshots cannot undo Send
        // or an external draft restoration.
        snapshotFlow { editor.text.toString() to currentValue }.collect { (text, controllerText) ->
            if (text != controllerText && controllerText == currentValue &&
                text == editor.text.toString() && controllerText == reportedValue) {
                reportedValue = text
                currentOnValueChange(text)
            }
        }
    }
    val reportUserEdits = remember {
        InputTransformation {
            // Report in the input transaction, before Send or a thread switch can run.
            // Programmatic draft restoration does not invoke this transformation.
            val text = asCharSequence().toString()
            if (text != reportedValue) {
                reportedValue = text
                currentOnValueChange(text)
            }
        }
    }
    val resolver = LocalContext.current.contentResolver
    val currentOnPasteImages by rememberUpdatedState(onPasteImages)
    val receiver = remember(resolver) {
        ReceiveContentListener { content ->
            if (currentOnPasteImages == null) content else {
                val images = mutableListOf<Uri>()
                val remaining = content.consume { item ->
                    val uri = item.uri
                    val type = uri?.let { runCatching { resolver.getType(it) }.getOrNull() }
                    val image = uri?.scheme == "content" &&
                        (type?.startsWith("image/") ?: content.clipMetadata.clipDescription.hasMimeType("image/*"))
                    if (image) images += checkNotNull(uri)
                    image
                }
                if (images.isNotEmpty()) currentOnPasteImages?.invoke(
                    PastedImageContent(images.distinct(), content.platformTransferableContent?.extras),
                )
                remaining
            }
        }
    }
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
        BasicTextField(
            state = editor,
            inputTransformation = reportUserEdits,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onPasteImages != null) Modifier.contentReceiver(receiver) else Modifier)
                .onFocusChanged { onFocusChange(it.isFocused) },
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 4),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorator = { innerTextField ->
                Box(Modifier.padding(horizontal = Dimensions.SpaceMedium, vertical = Dimensions.SpaceCompact)) {
                    if (editor.text.isEmpty()) Text(
                        "输入消息或 / 命令...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    innerTextField()
                }
            },
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

@Composable
private fun BusyDeliveryChoice(
    selectedMode: MessageDeliveryMode,
    onSelect: (MessageDeliveryMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimensions.SpaceMedium, vertical = Dimensions.SpaceTiny),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = selectedMode == MessageDeliveryMode.QUEUE,
                onClick = { onSelect(MessageDeliveryMode.QUEUE) },
                modifier = Modifier.weight(1f).semantics { contentDescription = "排队等待当前任务完成" },
                label = { Text("排队") },
                leadingIcon = {
                    Icon(
                        if (selectedMode == MessageDeliveryMode.QUEUE) Icons.Default.Check else Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(Dimensions.SmallIcon),
                    )
                },
            )
            FilterChip(
                selected = selectedMode == MessageDeliveryMode.STEER,
                onClick = { onSelect(MessageDeliveryMode.STEER) },
                modifier = Modifier.weight(1f).semantics { contentDescription = "引导当前任务" },
                label = { Text("引导") },
                leadingIcon = {
                    Icon(
                        if (selectedMode == MessageDeliveryMode.STEER) Icons.Default.Check else Icons.Default.Send,
                        contentDescription = null,
                        modifier = Modifier.size(Dimensions.SmallIcon),
                    )
                },
            )
        }
        Text(
            text = "选择方式后点击发送。排队等待本轮结束；引导发送给当前任务。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun PendingDeliveryStrip(
    deliveries: List<PendingDelivery>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (deliveries.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimensions.SpaceMedium, vertical = Dimensions.SpaceTiny),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
    ) {
        deliveries.forEach { delivery ->
            Surface(
                shape = RoundedCornerShape(AppRadius.Small),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Dimensions.SpaceSmall, vertical = Dimensions.SpaceTiny),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(Dimensions.SmallIcon),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = delivery.text.ifBlank { "${delivery.attachmentCount} 个附件" },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = when (delivery.state) {
                                PendingDeliveryState.QUEUED -> "排队中，当前任务结束后发送"
                                PendingDeliveryState.SENDING -> "正在发送"
                                PendingDeliveryState.FAILED -> "发送失败，等待重连后重试"
                                PendingDeliveryState.OUTCOME_UNKNOWN -> "发送结果未知，不会自动重发"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (delivery.state == PendingDeliveryState.FAILED) {
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                }
            }
        }
    }
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
