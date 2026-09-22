package com.openandroidintelligence.conversation.workbench

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.openandroidintelligence.conversation.assistant.AssistantSurface
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.selection.ScreenSelectionOverlay
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.theme.Dimensions

/** App 内浮动对话；复用当前账号的真实状态，不宣称跨 App 默认助理或截图权限。 */
@Composable
fun FloatingConversationPanel(
    controller: WorkbenchController, onClose: () -> Unit,
    onPickCamera: () -> Unit, onPickGallery: () -> Unit, onPickDocument: () -> Unit,
    onVoiceInput: () -> Unit, modifier: Modifier = Modifier,
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
        AssistantSurface(expanded, { expanded = it }, onClose, { explainSelection = true }) {
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
            // No Assist screenshot has been supplied by the platform. The overlay
            // renders its unavailable state and cannot invoke this crop callback.
            ScreenSelectionOverlay(
                onCropConfirmed = { _, _, _, _ -> error("SCREENSHOT_SOURCE_UNAVAILABLE") },
                onCancel = { explainSelection = false }, screenshot = null,
            )
        }
    }
}
