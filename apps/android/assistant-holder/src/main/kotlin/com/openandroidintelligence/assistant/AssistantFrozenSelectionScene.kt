package com.openandroidintelligence.assistant

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.openandroidintelligence.conversation.theme.Dimensions

/** Compose 测试钩子：定格态根节点与底部输入区。 */
internal object AssistantFrozenSceneTags {
    const val ROOT = "assistant_frozen_scene"
    const val DOCK = "assistant_frozen_dock"
}

/**
 * 「唤起即画圈」的定格界面：系统截图铺满屏幕，用户直接在上面画圈。
 *
 * 层级（自下而上）：
 * 1. 系统截图 + 实时轨迹 + 手势层（[AssistantLiveSelectionSurface]）；
 * 2. 底部输入区（横条 + 紧凑输入栏）与提示条。
 *
 * 排除区的双重保险：输入区用 `onGloballyPositioned` 自报矩形（与圈选画布同一坐标系），
 * [AssistantLiveSelectionSurface] 在 `onDragStart` 里再判断一次。层级顺序本身已经让
 * 输入区优先拿到触摸，这里只是兜住 `detectDragGestures` 仍会看到 down 的情况。
 *
 * 与 [AssistantSessionRoot] 的分工：那一棵树负责「紧凑/展开」的测量驱动形态；
 * 定格态是临时全屏模式，窗口由会话铺满，因此自己处理 inset ——
 * 键盘弹出时只留 IME 留白，其余情况留安全区，两者不叠加（否则输入区会被推走）。
 */
@Composable
internal fun AssistantFrozenSelectionScene(
    screenshot: Bitmap,
    previews: AssistantLocalPreviews,
    draft: String,
    onDraftChange: (String) -> Unit,
    enabled: Boolean,
    isGenerating: Boolean,
    isStopping: Boolean,
    hasUnknownOutcome: Boolean,
    onSend: () -> Boolean,
    onStop: () -> Unit,
    controller: AssistantSessionUiController,
    modifier: Modifier = Modifier,
    notice: String? = null,
    onDismissNotice: (() -> Unit)? = null,
    attachments: List<AssistantCompactAttachment> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
    onStartRecording: () -> Unit = {},
    /** 正在录音时的真实时长与音量；null 表示回到输入态。 */
    recording: com.openandroidintelligence.conversation.voice.ActiveVoiceRecording? = null,
    onCancelRecording: () -> Unit = {},
    onFinishRecording: () -> Unit = {},
    onAddAttachment: (AssistantAttachmentKind) -> Unit = {},
    onRequestHandoff: () -> Unit = {},
    onSelectionReady: (AssistantSelectionResult) -> Unit = {},
    onSelectionRejected: () -> Unit = {},
    /** 当前附件是否已达上限：达到后停止接受新选区，并给出一次提示。 */
    acceptsNewSelection: Boolean = true,
    /**
     * 发送被主进程接收：宿主据此退出全屏、回到测量驱动的会话形态。
     *
     * 状态机已经把覆盖层清掉，窗口如果还停在满屏就会盖住下方 App；这一步是
     * 「状态机与窗口几何同步推进」的界面侧一半。
     */
    onExitFrozen: () -> Unit = {},
) {
    val density = LocalDensity.current
    val imeHeightPx = WindowInsets.ime.getBottom(density)
    // 键盘与安全区二选一：键盘弹出时只留 IME 留白，否则窗口底部会多出一段平台栏高度。
    val useImeInset = imeHeightPx > 0

    // 输入区自报矩形（根坐标系）：圈选层与它共用同一画布坐标系。
    var dockRect by remember { mutableStateOf<Rect?>(null) }
    val editor = rememberTextFieldState(draft)
    LaunchedEffect(draft) {
        if (editor.text.toString() != draft) editor.setTextAndPlaceCursorAtEnd(draft)
    }
    val reportDraft: (String) -> Unit = { value ->
        val bounded = value.take(MAX_ASSISTANT_DRAFT_CHARS)
        if (editor.text.toString() != bounded) editor.setTextAndPlaceCursorAtEnd(bounded)
        onDraftChange(bounded)
    }
    val send: () -> Unit = {
        val accepted = onSend()
        controller.dispatch(AssistantUiEvent.SendRequested(accepted = accepted))
        // 只有被真正接收的发送才会离开定格；失败保持定格，用户可以继续编辑/重试。
        if (accepted) onExitFrozen()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(AssistantFrozenSceneTags.ROOT),
    ) {
        AssistantLiveSelectionSurface(
            screenshot = screenshot,
            previews = previews,
            localKey = FROZEN_SELECTION_LOCAL_KEY,
            onSelectionReady = onSelectionReady,
            enabled = acceptsNewSelection,
            exclusionRects = { listOfNotNull(dockRect) },
            onRejected = onSelectionRejected,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(
                    if (useImeInset) Modifier.imePadding()
                    // 键盘收起时内容会画到系统栏下：用安全区补顶/底留白。
                    else Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                )
                .padding(
                    start = Dimensions.SpaceMedium,
                    end = Dimensions.SpaceMedium,
                    top = Dimensions.SpaceSmall,
                    bottom = Dimensions.SpaceSmall,
                )
                .testTag(AssistantFrozenSceneTags.DOCK)
                .onGloballyPositioned { coordinates ->
                    // 自报的矩形与圈选画布同在根坐标系：圈选层据此排除输入区。
                    val bounds = coordinates.boundsInRoot()
                    dockRect = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                },
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            if (!notice.isNullOrBlank()) {
                AssistantNoticeBar(
                    text = notice,
                    onDismiss = onDismissNotice,
                    modifier = Modifier.testTag(AssistantSessionRootTags.NOTICE),
                )
            }
            AssistantDragHandle(
                onRequestHandoff = onRequestHandoff,
                enabled = enabled,
                stateDescription = "画圈选屏",
            )
            AssistantCompactBar(
                editor = editor,
                onDraftChange = reportDraft,
                isGenerating = isGenerating,
                isStopping = isStopping,
                onSend = send,
                onStop = onStop,
                onStartRecording = onStartRecording,
                onAddAttachment = onAddAttachment,
                attachments = attachments,
                onRemoveAttachment = onRemoveAttachment,
                recording = recording,
                onCancelRecording = onCancelRecording,
                onFinishRecording = onFinishRecording,
                enabled = enabled,
                hasUnknownOutcome = hasUnknownOutcome,
                // 定格态不是一个新面板：输入栏直接从底部淡入即可，保留进场动画。
                animateEntrance = true,
            )
        }
    }
}

/** 定格态圈选产物的本地键基名；连续圈选会追加序号（见 `liveSelectionLocalKey`）。 */
private const val FROZEN_SELECTION_LOCAL_KEY = "assistant-frozen-selection"
