package com.openandroidintelligence.conversation.workbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/** 始终挂载的编辑器：业务事实由 controller 提供，底部菜单只发出用户选择动作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerBar(
    draft: String, onDraftChange: (String) -> Unit,
    generation: GenerationState, canSend: Boolean,
    onSend: () -> Unit, onStop: () -> Unit,
    onPickCamera: () -> Unit, onPickGallery: () -> Unit, onPickDocument: () -> Unit,
    onVoiceInput: () -> Unit,
    attachments: List<AttachmentDraft> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {}, onRetryAttachment: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    applyImePadding: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    var showAttachments by remember { mutableStateOf(false) }
    val reduced = LocalMotionPolicy.current.reduceMotion
    val radius by animateDpAsState(
        if (focused || attachments.isNotEmpty()) Dimensions.SpaceMedium else Dimensions.SpaceXLarge,
        MotionSpecs.spatial(reduced), label = "composer-radius",
    )
    val running = generation == GenerationState.RUNNING || generation == GenerationState.QUEUED
    val waitingCancel = generation == GenerationState.CANCEL_REQUESTED
    val unknown = generation == GenerationState.OUTCOME_UNKNOWN

    Column(modifier = modifier.fillMaxWidth().then(if (applyImePadding) Modifier.imePadding() else Modifier)) {
        AnimatedVisibility(
            visible = attachments.isNotEmpty(),
            enter = fadeIn(MotionSpecs.fade(reduced)), exit = fadeOut(MotionSpecs.fade(reduced)),
        ) {
            AttachmentDraftStrip(attachments, onRemoveAttachment, onRetryAttachment,
                Modifier.padding(bottom = Dimensions.SpaceSmall))
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(radius),
            modifier = Modifier.fillMaxWidth().animateContentSize(MotionSpecs.spatial(reduced)),
        ) {
            Column(Modifier.padding(Dimensions.SpaceSmall)) {
                TextField(
                    value = draft, onValueChange = onDraftChange,
                    placeholder = { Text("写下你的想法，或输入 / 查找命令") },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                    minLines = 1, maxLines = 5,
                    shape = MaterialTheme.shapes.large,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showAttachments = true }) {
                        Icon(Icons.Default.Add, "添加附件")
                    }
                    IconButton(onClick = onVoiceInput) { Icon(Icons.Default.MicNone, "系统语音输入") }
                    Spacer(Modifier.weight(1f))
                    FilledIconButton(
                        onClick = if (running) onStop else onSend,
                        enabled = running || (canSend && !waitingCancel && !unknown),
                        modifier = Modifier.size(Dimensions.MinimumTouchTarget),
                    ) {
                        Crossfade(targetState = running || waitingCancel, animationSpec = MotionSpecs.fade(reduced), label = "send-stop") { stop ->
                            Icon(if (stop) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                                if (waitingCancel) "等待取消确认" else if (stop) "停止生成" else "发送消息")
                        }
                    }
                }
            }
        }
        generationLabel(generation)?.let { label ->
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = if (unknown || generation == GenerationState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Dimensions.SpaceSmall).semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
    if (showAttachments) {
        ModalBottomSheet(onDismissRequest = { showAttachments = false }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = Dimensions.SpaceLarge)) {
                Text("添加到当前对话", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = Dimensions.SpaceLarge, vertical = Dimensions.SpaceSmall))
                AttachmentChoice(Icons.Default.PhotoCamera, "拍摄照片", "使用系统相机") { showAttachments = false; onPickCamera() }
                AttachmentChoice(Icons.Default.PhotoLibrary, "选择图片", "只读取你选中的图片") { showAttachments = false; onPickGallery() }
                AttachmentChoice(Icons.Default.AttachFile, "选择文件", "通过系统文件选择器添加") { showAttachments = false; onPickDocument() }
            }
        }
    }
}

@Composable
private fun AttachmentChoice(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(description) },
            leadingContent = { Icon(icon, null) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
    }
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

fun attachmentStateLabel(state: AttachmentState): String = when (state) {
    AttachmentState.LOCAL_PREPARING -> "正在准备文件"
    AttachmentState.CREATE_PENDING -> "正在申请上传"
    AttachmentState.UPLOADING -> "正在上传"
    AttachmentState.VERIFYING -> "正在核验完整性"
    AttachmentState.VERIFIED -> "已核验，可发送"
    AttachmentState.RETRYABLE_FAILURE -> "上传失败，可重试"
    AttachmentState.TERMINAL_FAILURE -> "无法上传，请移除后重新选择"
    AttachmentState.OUTCOME_UNKNOWN -> "上传结果未知，请先核实"
    AttachmentState.CANCELLED -> "已取消上传"
}

@Composable
fun AttachmentDraftStrip(attachments: List<AttachmentDraft>, onRemove: (String) -> Unit,
    onRetry: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
        items(attachments, key = { it.id.value }) { item ->
            AttachmentDraftChip(item, { onRemove(item.id.value) }, { onRetry(item.id.value) }, Modifier.widthIn(max = Dimensions.DrawerWidth))
        }
    }
}

@Composable
fun AttachmentDraftChip(draft: AttachmentDraft, onRemove: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val busy = draft.state in setOf(AttachmentState.LOCAL_PREPARING, AttachmentState.CREATE_PENDING, AttachmentState.UPLOADING, AttachmentState.VERIFYING)
    val failed = draft.state == AttachmentState.RETRYABLE_FAILURE || draft.state == AttachmentState.TERMINAL_FAILURE
    Surface(color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Row(Modifier.padding(start = Dimensions.SpaceMedium), verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(Modifier.size(Dimensions.Progress), strokeWidth = Dimensions.StrokeStitch)
            else Icon(when {
                failed -> Icons.Default.ErrorOutline
                draft.state == AttachmentState.VERIFIED -> Icons.Default.CheckCircleOutline
                draft.state == AttachmentState.OUTCOME_UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline
                else -> Icons.Default.Description
            }, null)
            Column(Modifier.weight(1f).padding(Dimensions.SpaceSmall)) {
                Text(draft.filename, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(attachmentStateLabel(draft.state), style = MaterialTheme.typography.bodySmall)
                Text(formatAttachmentSize(draft.sizeBytes), style = MaterialTheme.typography.labelSmall)
            }
            if (draft.state == AttachmentState.RETRYABLE_FAILURE) IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, "重试上传 ${draft.filename}") }
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "移除附件 ${draft.filename}") }
        }
    }
}

fun formatAttachmentSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> java.lang.String.format(java.util.Locale.getDefault(), "%.1f MB", bytes.toDouble() / (1024 * 1024))
}

@Composable
fun PendingBatchStrip(members: List<TimelineEntry>, modifier: Modifier = Modifier) {
    val reduced = LocalMotionPolicy.current.reduceMotion
    AnimatedVisibility(members.isNotEmpty(), enter = fadeIn(MotionSpecs.fade(reduced)), exit = fadeOut(MotionSpecs.fade(reduced))) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium,
            modifier = modifier.fillMaxWidth().padding(horizontal = Dimensions.SpaceMedium, vertical = Dimensions.SpaceTiny)) {
            Text("${members.size} 条消息待发送 · 等待合并或 Gateway 确认", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(Dimensions.SpaceCompact))
        }
    }
}

@Composable
fun GatewayStatusLine(title: String, status: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(Dimensions.SpaceMedium)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
