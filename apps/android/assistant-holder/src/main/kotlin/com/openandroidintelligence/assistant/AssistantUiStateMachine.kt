package com.openandroidintelligence.assistant

/**
 * 助理界面的两种表面形态。
 *
 * [COMPACT_INPUT] 是唤出后的默认形态：只有输入坞；
 * [EXPANDED_CONVERSATION] 只有在一条消息被真正接收后才进入，用来展示对话内容。
 * 两者是同一张卡片的形态差异，不是两份互不相干的界面。
 */
enum class AssistantSurfaceState { COMPACT_INPUT, EXPANDED_CONVERSATION }

/**
 * 叠加在助理表面之上的临时模式。
 *
 * 同一时刻最多只有一个覆盖层：圈选屏幕与录音都要求用户把注意力交给全屏/麦克风，
 * 不能同时进行。[NONE] 表示没有覆盖层。
 */
enum class AssistantOverlay { NONE, SELECTING_SCREEN, RECORDING_VOICE }

/**
 * 助理界面状态。
 *
 * 用密封类而不是单个 data class："会话已经结束"才不会被误当成一份仍可渲染的状态。
 * [Terminated] 没有表面、覆盖层与展开时间可读，调用方必须显式分支处理。
 */
sealed class AssistantUiState {

    /**
     * 界面仍在显示，可以渲染 [surface] 与 [overlay]。
     *
     * [expandedSince] 是"展开开始的时刻"，由宿主在观察到进入
     * [AssistantSurfaceState.EXPANDED_CONVERSATION] 后通过 [recordExpandedAt] 补写；
     * [reduce] 自身不读时钟，不在纯函数里伪造时间戳。
     */
    data class Visible(
        val surface: AssistantSurfaceState = AssistantSurfaceState.COMPACT_INPUT,
        val overlay: AssistantOverlay = AssistantOverlay.NONE,
        val expandedSince: Long? = null,
    ) : AssistantUiState()

    /** 助理会话已经结束；只有新的 [AssistantUiEvent.SessionShown] 能重新开始。 */
    data object Terminated : AssistantUiState()
}

/**
 * 驱动助理界面状态的全部输入。
 *
 * 事件只描述"发生了什么"，不携带界面文案或渲染决定；同一个事件序列必须得到
 * 同一个状态，方便在进程重建、测试和不同宿主之间复现。
 */
sealed interface AssistantUiEvent {

    /** 系统重新唤起助理：每次都以紧凑输入开始，不继承上一次的展开与覆盖层。 */
    data object SessionShown : AssistantUiEvent

    /** 输入框内容变化；草稿的权威在主进程，界面状态机不保存文本。 */
    data class DraftChanged(val text: String) : AssistantUiEvent

    /** 新增一个附件草稿；附件列表由附件协调器持有，这里只触发一次重绘。 */
    data object AttachmentAdded : AssistantUiEvent

    /** 用户请求发送；[accepted] 是主进程/Gateway 是否真正接收了这条消息。 */
    data class SendRequested(val accepted: Boolean) : AssistantUiEvent

    /** 用户开始圈选屏幕。 */
    data object SelectionRequested : AssistantUiEvent

    /** 圈选完成并裁剪出图片（图片已作为附件加入）。 */
    data object SelectionCropped : AssistantUiEvent

    /** 用户取消圈选。 */
    data object SelectionCancelled : AssistantUiEvent

    /** 用户开始录音。 */
    data object RecordingStarted : AssistantUiEvent

    /** 用户取消录音。 */
    data object RecordingCancelled : AssistantUiEvent

    /** 录音已经作为消息发送。 */
    data object VoiceSent : AssistantUiEvent

    /** 系统返回键（未被子组件消费）。 */
    data object BackPressed : AssistantUiEvent

    /** 助理会话被系统隐藏或结束。 */
    data object SessionHidden : AssistantUiEvent
}

/**
 * 助理界面状态机。
 *
 * 转移规则（与设计规格 §9.2、§10 对齐）：
 * - 每次唤起都重置为紧凑输入，不继承上一次会话的展开态与覆盖层；
 * - 只有被真正接收的发送才会展开对话；发送失败保持原形态，草稿与重试入口不动；
 * - 消息接收、草稿编辑、附件增加，以及回复走完终态，都不会把已展开的对话折叠回去；
 * - 返回键先消费覆盖层（圈选 → 录音 → 退出），没有覆盖层时才结束会话。
 *
 * 这是一个纯函数：不读时钟、不读写全局状态，同样的 [state] + [event] 永远得到
 * 同样的结果。
 */
fun reduce(state: AssistantUiState, event: AssistantUiEvent): AssistantUiState =
    when (state) {
        // 结束态只允许被一次新的系统唤起重新建立；其余事件不会复活旧会话。
        AssistantUiState.Terminated ->
            if (event == AssistantUiEvent.SessionShown) AssistantUiState.Visible() else state

        is AssistantUiState.Visible -> reduceVisible(state, event)
    }

private fun reduceVisible(
    state: AssistantUiState.Visible,
    event: AssistantUiEvent,
): AssistantUiState =
    when (event) {
        AssistantUiEvent.SessionShown -> AssistantUiState.Visible()

        // 草稿文本与附件列表的权威都不在这里；状态机只负责界面形态。
        is AssistantUiEvent.DraftChanged,
        AssistantUiEvent.AttachmentAdded ->
            state

        is AssistantUiEvent.SendRequested ->
            if (event.accepted) {
                // 只有真正接收的消息才值得展开；失败时保留紧凑形态与可重试的输入。
                state.copy(surface = AssistantSurfaceState.EXPANDED_CONVERSATION)
            } else {
                state
            }

        AssistantUiEvent.SelectionRequested ->
            state.copy(overlay = AssistantOverlay.SELECTING_SCREEN)

        AssistantUiEvent.SelectionCropped,
        AssistantUiEvent.SelectionCancelled,
        AssistantUiEvent.RecordingCancelled ->
            state.copy(overlay = AssistantOverlay.NONE)

        AssistantUiEvent.RecordingStarted ->
            state.copy(overlay = AssistantOverlay.RECORDING_VOICE)

        AssistantUiEvent.VoiceSent ->
            // 语音消息已被发送：结束录音覆盖层，并按"发送成功才展开"展示对话。
            state.copy(
                surface = AssistantSurfaceState.EXPANDED_CONVERSATION,
                overlay = AssistantOverlay.NONE,
            )

        AssistantUiEvent.BackPressed ->
            if (state.overlay != AssistantOverlay.NONE) {
                // 返回键的最高优先级是退出当前临时模式，而不是收起或结束助理。
                state.copy(overlay = AssistantOverlay.NONE)
            } else {
                AssistantUiState.Terminated
            }

        // 会话被系统隐藏或结束：界面不再渲染，重新出现要等下一次 SessionShown。
        AssistantUiEvent.SessionHidden -> AssistantUiState.Terminated
    }

/**
 * 记录展开起点：只在已经展开且尚未记录时写入一次。
 *
 * 宿主在观察到 [AssistantUiState.Visible.surface] 变为
 * [AssistantSurfaceState.EXPANDED_CONVERSATION] 后调用；重复调用不会刷新时间，
 * 保证"展开多久"从第一次展开算起。
 */
fun AssistantUiState.Visible.recordExpandedAt(now: Long): AssistantUiState.Visible =
    if (surface == AssistantSurfaceState.EXPANDED_CONVERSATION && expandedSince == null) {
        copy(expandedSince = now)
    } else {
        this
    }
