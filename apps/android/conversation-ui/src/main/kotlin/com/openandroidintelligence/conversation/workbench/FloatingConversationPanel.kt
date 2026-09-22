package com.openandroidintelligence.conversation.workbench

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.openandroidintelligence.capability.ScreenCaptureSource
import com.openandroidintelligence.conversation.assistant.AssistantSurface
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.theme.Dimensions

/**
 * App 内浮动对话；复用当前账号的真实状态。
 *
 * 圈选的截图来自外部真实采集源 [screenCaptureSource]（宿主装配的
 * MediaProjection 来源，经系统授权对话框显式授予）：来源可用时先采集
 * 再把截图交给圈选层；来源缺失、未授权或采集失败时，圈选层渲染明确的
 * 不可用态，绝不以松手冒充提交。默认 null 时行为与未接入来源完全一致。
 */
@Composable
fun FloatingConversationPanel(
    controller: WorkbenchController, onClose: () -> Unit,
    onPickCamera: () -> Unit, onPickGallery: () -> Unit, onPickDocument: () -> Unit,
    onVoiceInput: () -> Unit, modifier: Modifier = Modifier,
    screenCaptureSource: ScreenCaptureSource? = null,
    onNeedScreenCaptureAuthorization: (() -> Unit)? = null,
) {
    val state by controller.state.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(true) }
    var explainSelection by remember { mutableStateOf(false) }
    BackHandler {
        when {
            explainSelection -> explainSelection = false
            expanded -> expanded = false
            else -> onClose()
        }
    }
    Box(modifier.fillMaxSize()) {
        AssistantSurface(expanded, { expanded = it }, onClose, {
            val source = screenCaptureSource
            if (source == null || source.isAvailable) {
                explainSelection = true
            } else {
                // 来源已装配但尚未经用户显式授权：先走系统授权对话框，
                // 而不是把「未授权」静默渲染成不可用态。宿主未提供授权
                // 入口时保持既有降级（overlay 自己渲染不可用）。
                val requestAuthorization = onNeedScreenCaptureAuthorization
                if (requestAuthorization != null) requestAuthorization() else explainSelection = true
            }
        }) {
            Text(state.activeThreadTitle.ifBlank { "浮动对话" }, style = MaterialTheme.typography.titleMedium)
            val last = (state.timeline as? Loadable.Ready)?.value?.takeLast(1).orEmpty()
            if (last.isNotEmpty()) {
                // The panel shows the newest row, which is often an approval
                // card. Its buttons must reach the same decision path as the full
                // screen: a card whose press goes nowhere is a dead control, and
                // the command behind it stays blocked. Sharing the screen's
                // method reference keeps that path single.
                MessageTimeline(
                    entries = last,
                    onDecide = controller::decideApproval,
                )
            } else {
                Text("与当前 Gateway 继续对话", style = MaterialTheme.typography.bodyMedium)
            }
            val isThinking = (state.generation == GenerationState.QUEUED ||
                state.generation == GenerationState.RUNNING) &&
                last.none { !it.isUser && it.isStreaming }
            if (isThinking) {
                ThinkingIndicator(modifier = Modifier.padding(vertical = Dimensions.SpaceSmall))
            }
            if (state.activeThreadId == null) TextButton(onClick = controller::createThread) { Text("新建对话") }
            CommandMenu(state.catalog, state.draft, controller::selectCommand, controller::loadCatalog)
            ComposerBar(draft = state.draft, onDraftChange = controller::editDraft,
                generation = state.generation,
                canSend = state.activeThreadId != null && (state.draft.isNotBlank() || state.attachments.isNotEmpty()),
                onSend = controller::sendDraft, onStop = controller::stopGeneration,
                onPickCamera = onPickCamera, onPickGallery = onPickGallery, onPickDocument = onPickDocument,
                onVoiceInput = onVoiceInput, attachments = state.attachments,
                onRemoveAttachment = controller::removeAttachment, onRetryAttachment = controller::retryAttachment,
                applyImePadding = false)
            Text("浮动对话仅在此 App 内显示", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Dimensions.SpaceSmall))
        }
        if (explainSelection) {
            ScreenSelectionOverlayHost(
                screenCaptureSource = screenCaptureSource,
                onCancel = { explainSelection = false },
                onConfirmCrop = { crop ->
                    // 确认先于回调（规格：确认后才能回调，不以松手冒充提交）。
                    // 圈选产物走与本地附件同一条真实三步上传链路，让「确认」
                    // 有真实下文；无活动对话时 addAttachment 的 coordinator
                    // 缺失分支会给出明确的 notice，而不是静默丢弃。
                    controller.addAttachment(
                        LocalAttachmentSelection(
                            filename = "screen-crop-${System.currentTimeMillis()}.png",
                            mediaType = "image/png",
                            bytes = crop.pngBytes(),
                        ),
                    )
                    explainSelection = false
                },
            )
        }
    }
}
