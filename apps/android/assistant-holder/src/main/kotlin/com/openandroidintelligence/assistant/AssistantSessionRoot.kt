package com.openandroidintelligence.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.workbench.AttachmentPreviewHost
import com.openandroidintelligence.conversation.workbench.ThinkingIndicator
import com.openandroidintelligence.conversation.workbench.TimelineDisplayRow
import com.openandroidintelligence.conversation.workbench.rememberExecutionTimingStore
import kotlin.math.roundToInt

/** Compose 测试钩子：按稳定标签断言根节点与提示条。 */
internal object AssistantSessionRootTags {
    const val ROOT = "assistant_session_root"
    const val NOTICE = "assistant_session_notice"
}

/**
 * 界面状态持有者：系统助理会话与 ASSIST 兜底页共用同一条转移规则。
 *
 * 只保存 [AssistantUiState]；时间戳等宿主观察量由宿主自己补写，
 * 纯函数 [reduce] 不读时钟。界面只通过 [dispatch] 改状态，宿主读 [state] 决定窗口。
 */
internal class AssistantSessionUiController(
    initial: AssistantUiState = AssistantUiState.Visible(),
) {
    var state: AssistantUiState by mutableStateOf(initial)
        private set

    /**
     * 会话结束回调：状态机第一次进入 [AssistantUiState.Terminated] 时触发一次。
     *
     * 放在控制器上而不是交给组合里的 effect：结束态没有界面可渲染，靠组合去"观察"结束
     * 会出现"界面已经早退、观察者也被一起销毁"的竞态；直接由转移点回调最可靠。
     */
    var onTerminated: (() -> Unit)? = null
        private set

    fun bindOnTerminated(callback: (() -> Unit)?) {
        onTerminated = callback
    }

    fun dispatch(event: AssistantUiEvent) {
        val previous = state
        val next = reduce(previous, event)
        state = next
        if (next is AssistantUiState.Terminated && previous !is AssistantUiState.Terminated) {
            onTerminated?.invoke()
        }
    }

    /** 当前可见状态；已结束时返回 null。 */
    fun visible(): AssistantUiState.Visible? = state as? AssistantUiState.Visible
}

/**
 * 宿主无关的助理界面根组合：系统助理会话与 ASSIST 兜底页用的是**同一棵树**。
 *
 * 职责划分：
 * - 形态完全由 [controller] 里的状态机决定：紧凑输入或展开会话。界面没有"收起成球"
 *   的第二套窗口形态；
 * - **窗口高度由测量驱动**：整棵树的测量高度通过 [onContentHeightChanged] 回传宿主，
 *   宿主据此调用 `window.setLayout`。展开态上限由 [AssistantSessionWindowPolicy] 计算，
 *   0.7 比例与键盘可用区都在纯函数里，界面只消费结果；
 * - 数据全部来自宿主（主进程桥接状态），这里不连接 Gateway、不编造消息。
 *
 * 键盘：窗口是 `SOFT_INPUT_ADJUST_NOTHING`，系统不会替我们缩小窗口，因此底部留白
 * 由这里的 `imePadding()` 承担；窗口贴着屏幕底边，输入栏随之抬到键盘上方。
 *
 * @param onSend 发起发送，返回**主进程是否真正接收**；根组合据此推进状态机：
 *   只有被接收的发送才展开会话，失败保持紧凑形态并保留草稿。
 * @param onExit 状态机判定会话结束（返回键且无覆盖层）时回调宿主。
 */
@Composable
internal fun AssistantSessionRoot(
    draft: String,
    onDraftChange: (String) -> Unit,
    enabled: Boolean,
    isGenerating: Boolean,
    isStopping: Boolean,
    hasUnknownOutcome: Boolean,
    onSend: () -> Boolean,
    onStop: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    controller: AssistantSessionUiController = remember { AssistantSessionUiController() },
    timeline: List<TimelineEntry> = emptyList(),
    threadId: String? = null,
    notice: String? = null,
    onDismissNotice: (() -> Unit)? = null,
    previews: AssistantLocalPreviews? = null,
    /** 初值文本（ASSIST 的 `EXTRA_TEXT`）：草稿为空时填入一次，不覆盖用户已输入内容。 */
    initialText: String? = null,
    onStartRecording: () -> Unit = {},
    onStartScreenSelection: () -> Unit = {},
    onOpenApp: () -> Unit = {},
    onContentHeightChanged: (Int) -> Unit = {},
    /**
     * 进入组合时是否当作一次新的系统唤起（重置为紧凑输入）。
     *
     * 系统助理会话每次唤起都从紧凑输入开始，所以默认 true；ASSIST 兜底页不是系统唤起，
     * 它一进来就是可对话的展开形态，传 false 并自行给控制器一个展开初值。
     */
    resetOnEntry: Boolean = true,
) {
    // 进入组合即一次新的系统唤起：旧形态与覆盖层都不继承。
    LaunchedEffect(controller, resetOnEntry) {
        if (resetOnEntry) controller.dispatch(AssistantUiEvent.SessionShown)
    }
    // 会话结束的通知挂在控制器的转移点上（见 [AssistantSessionUiController.dispatch]）：
    // 结束态没有界面可渲染，不能指望根组合继续留在组合里"观察到"它。
    LaunchedEffect(controller, onExit) {
        controller.bindOnTerminated(onExit)
    }

    val state = controller.state
    val visible = state as? AssistantUiState.Visible
    if (visible == null) return
    val expanded = visible.surface == AssistantSurfaceState.EXPANDED_CONVERSATION

    // 草稿的权威在主进程；主进程推回的草稿与本地编辑不一致时以主进程为准
    // （换会话、外部清空都会走到这里）。
    val editor = rememberTextFieldState(draft)
    LaunchedEffect(draft) {
        if (editor.text.toString() != draft) editor.setTextAndPlaceCursorAtEnd(draft)
    }
    val reportDraft: (String) -> Unit = { value ->
        val bounded = value.take(MAX_ASSISTANT_DRAFT_CHARS)
        if (editor.text.toString() != bounded) editor.setTextAndPlaceCursorAtEnd(bounded)
        onDraftChange(bounded)
    }
    // 初值只种一次，且只在草稿仍为空时：用户已经敲进去的内容优先。
    LaunchedEffect(initialText) {
        val seed = initialText?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (editor.text.isNotEmpty() || draft.isNotEmpty()) return@LaunchedEffect
        editor.setTextAndPlaceCursorAtEnd(seed.take(MAX_ASSISTANT_DRAFT_CHARS))
        onDraftChange(editor.text.toString())
    }
    val send: () -> Unit = {
        controller.dispatch(AssistantUiEvent.SendRequested(accepted = onSend()))
    }

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    // 可用高度取屏幕高度而不是窗口高度：窗口高度正是本层上报的结果，
    // 用它当输入会形成"测量 → 改窗口 → 再测量"的反馈环，面板会来回抖。
    val availableHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }.roundToInt()
    val imeHeightPx = WindowInsets.ime.getBottom(density)
    val topInsetPx = WindowInsets.safeDrawing.getTop(density)
    val maxPanelHeight = with(density) {
        AssistantSessionWindowPolicy
            .calculateMaxHeight(availableHeightPx, imeHeightPx, topInsetPx)
            .toDp()
    }
    // 宿主通常自己持有会话级预览存储；没有时给一个随会话身份的临时实例，
    // 保证时间线里"本进程圈选/录音"的缩略图与时长仍有统一来源。
    val activePreviews = previews ?: rememberAssistantLocalPreviews(threadId)
    // 根节点必须**离开窗口高度约束测量**：窗口高度正是本层上报的结果，若内容被当前
    // 窗口高度（EXACTLY 约束）卡住，内容就永远长不高，展开会话永远停在输入栏高度。
    // `wrapContentHeight(unbounded = true)` 让内容以无限最大高度测量，再把测量结果
    // 上报给宿主；宿主据此把窗口调到刚好包住内容。inset / IME padding 放在测量节点
    // 内侧，上报的高度因此包含导航栏与键盘留白 —— 窗口贴底时输入栏才会出现在键盘上方。
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
                .onSizeChanged { onContentHeightChanged(it.height) }
                // 会话窗口可能落在导航栏/挖孔之上；inset 为 0 时这一层是空操作。
                .windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.displayCutout),
                )
                .imePadding()
                .padding(
                    start = Dimensions.SpaceMedium,
                    end = Dimensions.SpaceMedium,
                    top = Dimensions.SpaceSmall,
                    bottom = Dimensions.SpaceSmall,
                )
                .testTag(AssistantSessionRootTags.ROOT),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            if (!notice.isNullOrBlank()) {
                AssistantNoticeBar(
                    text = notice,
                    onDismiss = onDismissNotice,
                    modifier = Modifier.testTag(AssistantSessionRootTags.NOTICE),
                )
            }
            val composer: @Composable (Boolean) -> Unit = { animateEntrance ->
                AssistantCompactBar(
                    editor = editor,
                    onDraftChange = reportDraft,
                    isGenerating = isGenerating,
                    isStopping = isStopping,
                    onSend = send,
                    onStop = onStop,
                    onStartRecording = onStartRecording,
                    onStartScreenSelection = onStartScreenSelection,
                    // 未连接时不禁用输入：发送失败会保留草稿并给出提示，
                    // 而不是让用户对着一个灰掉的输入框无从下手。
                    enabled = enabled,
                    hasUnknownOutcome = hasUnknownOutcome,
                    // 展开会话里的输入栏由面板的展开动画带出，不再单独弹一次。
                    animateEntrance = animateEntrance,
                )
            }
            if (expanded) {
                ExpandedConversation(
                    timeline = timeline,
                    previews = activePreviews,
                    threadId = threadId,
                    isGenerating = isGenerating,
                    maxPanelHeight = maxPanelHeight,
                    enabled = enabled,
                    onOpenApp = onOpenApp,
                    composer = { composer(false) },
                )
            } else {
                composer(true)
            }
        }
    }
}

/**
 * 展开态：会话框 + 输入栏共用一张卡片，高度上限由 [maxPanelHeight] 约束。
 *
 * 消息渲染直接复用 App 工作台的 `TimelineDisplayRow`：助理界面不是"另一套聊天界面"。
 * 本地缩略图与语音时长由 [previews] 按 `localKey` 补回，取不到时如实显示占位。
 */
@Composable
private fun ExpandedConversation(
    timeline: List<TimelineEntry>,
    previews: AssistantLocalPreviews,
    threadId: String?,
    isGenerating: Boolean,
    maxPanelHeight: Dp,
    enabled: Boolean,
    onOpenApp: () -> Unit,
    composer: @Composable () -> Unit,
) {
    val generation: GenerationState? = null
    val content = rememberAssistantTimelineContent(
        entries = timeline,
        previews = previews,
        threadId = threadId,
        generation = generation,
    )
    val timing = rememberExecutionTimingStore(threadId = threadId)
    // 还没有流式正文时用真实的生成状态说明"在等什么"，而不是显示空态。
    val isThinking = isGenerating && content.entries.none { !it.isUser && it.isStreaming }

    AttachmentPreviewHost(
        attachments = content.entries.flatMap { it.attachments },
        onDownload = null,
        onRequestOriginal = null,
        modifier = Modifier.fillMaxWidth(),
    ) { openPreview ->
        AssistantExpandedPanel(
            expanded = true,
            messages = content.displayItems,
            itemKey = { it.key },
            emptyContent = {
                if (isThinking) {
                    ThinkingIndicator()
                } else {
                    AssistantEmptyState(enabled = enabled, onOpenApp = onOpenApp)
                }
            },
            composer = composer,
            itemContent = { item ->
                TimelineDisplayRow(
                    item = item,
                    // 审批决策通道已由桥接层提供（AssistantBridgeClient.decideApproval），
                    // 但本入口尚未把按键接到宿主；在接线完成前不伪造一次已提交的裁决。
                    onDecide = { _, _ -> },
                    timing = timing,
                    onOpenAttachmentPreview = openPreview,
                    evidenceOwner = threadId,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            modifier = Modifier.heightIn(max = maxPanelHeight),
        )
    }
}

/**
 * 空态：没有消息时给出一句可执行的下一步。
 *
 * 只覆盖真实存在的原因（未连接）；不显示示例消息，也不在未连接时伪造一个空对话。
 */
@Composable
private fun AssistantEmptyState(
    enabled: Boolean,
    onOpenApp: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(AppRadius.Medium),
    ) {
        Column(
            modifier = Modifier.padding(Dimensions.SpaceMedium),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            if (enabled) {
                Text(text = "还没有消息", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "这里会显示当前对话的真实消息，输入内容后发送即可开始。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(text = "助理服务未连接", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "助理进程无法访问主进程的对话状态。请先打开 App，再从系统助理重新唤起。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenApp) { Text("打开 App") }
            }
        }
    }
}
