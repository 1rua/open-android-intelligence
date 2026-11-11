package com.openandroidintelligence.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.voice.ActiveVoiceRecording
import com.openandroidintelligence.conversation.workbench.AttachmentPreviewHost
import com.openandroidintelligence.conversation.workbench.ThinkingIndicator
import com.openandroidintelligence.conversation.workbench.TimelineDisplayRow
import com.openandroidintelligence.conversation.workbench.rememberExecutionTimingStore
import kotlin.math.roundToInt

/** 横条拖满时整棵浮层的上移距离：足够表达「正在往上推」，又不至于把内容顶出屏幕。 */
private val handoffLiftDistance = 72.dp

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

    /**
     * 状态变化观察者：每次 [dispatch] 写入新状态后回调一次。
     *
     * 窗口几何必须由**状态观察单点驱动**：文字发送、语音发送、返回键、系统重新截图、
     * 会话结束都可能把覆盖层从定格态清掉，逐个调用点补「退出全屏」一定会漏掉一条
     * （语音发送就漏了，窗口留在全屏吞掉下方 App 的触摸）。观察者挂在状态转移点上，
     * 任何路径都自动一致。
     */
    var onStateChanged: ((AssistantUiState) -> Unit)? = null
        private set

    fun bindOnStateChanged(callback: ((AssistantUiState) -> Unit)?) {
        onStateChanged = callback
    }

    fun dispatch(event: AssistantUiEvent) {
        val previous = state
        val next = reduce(previous, event)
        state = next
        onStateChanged?.invoke(next)
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
 * 由这里的窗口 inset 并集（含 IME）承担；窗口贴着屏幕底边，输入栏随之抬到键盘上方。
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
    /** 当前生成状态；用于执行段终态折叠与流式光标，缺省表示尚未拿到。 */
    generation: GenerationState? = null,
    notice: String? = null,
    onDismissNotice: (() -> Unit)? = null,
    previews: AssistantLocalPreviews? = null,
    /** 初值文本（ASSIST 的 `EXTRA_TEXT`）：草稿为空时填入一次，不覆盖用户已输入内容。 */
    initialText: String? = null,
    onStartRecording: () -> Unit = {},
    /** 正在录音时的真实时长与音量；没有录音时为 null，输入栏回到输入态。 */
    recording: ActiveVoiceRecording? = null,
    onCancelRecording: () -> Unit = {},
    onFinishRecording: () -> Unit = {},
    /** 当前草稿里的附件（元数据 + 本进程缩略图）；移除动作交回宿主。 */
    attachments: List<AssistantCompactAttachment> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
    /** 审批决策：卡片把自身的 approvalId 绑定进回调，宿主负责提交。 */
    onDecide: (approvalId: String, choice: ApprovalChoice) -> Unit = { _, _ -> },
    /**
     * 屏幕圈选入口：**只有 ASSIST 兜底页**传入（它没有系统截图，点它给出说明）。
     * 系统助理会话传 null，输入栏里没有第二个圈选入口。
     */
    onStartScreenSelection: (() -> Unit)? = null,
    onOpenApp: () -> Unit = {},
    /** 横条上滑超过阈值：请求把当前会话交给主 App（严格交接，失败保留浮层）。 */
    onRequestHandoff: () -> Unit = {},
    /** 拖动横条期间窗口是否需要临时铺满（见 [AssistantDragHandle.onDragWindowExpanded]）。 */
    onDragWindowExpanded: (Boolean) -> Unit = {},
    /** 点击「＋」：打开系统文件/媒体选择器。 */
    onAddAttachment: (AssistantAttachmentKind) -> Unit = {},
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
    // 这里只把「屏幕高度（像素）」交给面板，高度上限由面板自己算一次 ——
    // 两层各算一次会把 0.7 乘两次，键盘一弹出面板就只剩一条消息高。
    val availableHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }.roundToInt()
    // 宿主通常自己持有会话级预览存储；没有时给一个随会话身份的临时实例，
    // 保证时间线里"本进程圈选/录音"的缩略图与时长仍有统一来源。
    val activePreviews = previews ?: rememberAssistantLocalPreviews(threadId)
    // 上滑交接的跟手位移：横条把进度（0..1）报上来，整棵浮层随手指上移并轻微淡化。
    // 位移/透明度只作用于 graphicsLayer，不参与测量，因此窗口高度不会跟着抖。
    var handoffProgress by remember { mutableStateOf(0f) }
    val handoffShiftPx = with(density) { handoffLiftDistance.toPx() }
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
                .graphicsLayer {
                    // 交接进度是纯绘制量：浮层跟手上移、并随距离轻微淡出（上限 0.35）。
                    translationY = -handoffShiftPx * handoffProgress
                    alpha = 1f - 0.35f * handoffProgress
                }
                // 会话窗口可能落在导航栏/挖孔之上；inset 为 0 时这一层是空操作。
                // 导航栏、挖孔与键盘必须取**并集**：分别叠加会把同一段高度扣两次，
                // 键盘弹出后输入栏被推得过高、面板被压扁。
                .windowInsetsPadding(
                    WindowInsets.navigationBars
                        .union(WindowInsets.displayCutout)
                        .union(WindowInsets.ime),
                )
                .padding(
                    start = Dimensions.SpaceMedium,
                    end = Dimensions.SpaceMedium,
                    top = Dimensions.SpaceSmall,
                    bottom = Dimensions.SpaceSmall,
                )
                .testTag(AssistantSessionRootTags.ROOT),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            // 整个助手只有一个横条：紧凑态在输入栏卡片上方，展开态在整个会话面板之上。
            // 它放在 Column 最顶部，因此两种形态共用同一实例，且始终显示（需求 5.1）。
            AssistantDragHandle(
                onRequestHandoff = onRequestHandoff,
                enabled = enabled,
                stateDescription = if (expanded) "展开会话" else "紧凑输入",
                onDragProgress = { handoffProgress = it },
                onDragWindowExpanded = onDragWindowExpanded,
            )
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
                    onAddAttachment = onAddAttachment,
                    // 展开态输入栏只保留外缘底部圆角，与消息区拼成同一张卡片；
                    // 紧凑态没有消息区，保持完整的胶囊圆角。
                    cardShape = if (animateEntrance) null else InputBarShape,
                    attachments = attachments,
                    onRemoveAttachment = onRemoveAttachment,
                    recording = recording,
                    onCancelRecording = onCancelRecording,
                    onFinishRecording = onFinishRecording,
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
                    generation = generation,
                    isGenerating = isGenerating,
                    availableHeightPx = availableHeightPx,
                    enabled = enabled,
                    onOpenApp = onOpenApp,
                    onDecide = onDecide,
                    composer = { composer(false) },
                )
            } else {
                composer(true)
            }
        }
    }
}

/**
 * 展开态：会话框 + 输入栏共用一张卡片，高度上限由面板按 `availableHeightPx` 算一次。
 *
 * 消息渲染直接复用 App 工作台的 `TimelineDisplayRow`：助理界面不是"另一套聊天界面"。
 * 本地缩略图与语音时长由 [previews] 按 `localKey` 补回，取不到时如实显示占位。
 */
@Composable
private fun ExpandedConversation(
    timeline: List<TimelineEntry>,
    previews: AssistantLocalPreviews,
    threadId: String?,
    generation: GenerationState?,
    isGenerating: Boolean,
    availableHeightPx: Int,
    enabled: Boolean,
    onOpenApp: () -> Unit,
    onDecide: (approvalId: String, choice: ApprovalChoice) -> Unit,
    composer: @Composable () -> Unit,
) {
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
                    // 审批决策走桥接层的 `approval` 命令；卡片已把自身的 approvalId
                    // 绑定进回调，这里只做转发，不伪造一次已提交的裁决。
                    onDecide = onDecide,
                    timing = timing,
                    onOpenAttachmentPreview = openPreview,
                    evidenceOwner = threadId,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            // 高度上限由面板按 availableHeightPx 自己算一次；这一层不再 heightIn。
            availableHeightPx = availableHeightPx,
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
