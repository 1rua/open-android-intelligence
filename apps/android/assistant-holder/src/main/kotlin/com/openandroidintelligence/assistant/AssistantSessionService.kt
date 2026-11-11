package com.openandroidintelligence.assistant

import android.content.res.Configuration
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import android.os.Handler
import android.os.Looper
import com.openandroidintelligence.conversation.assistant.ASSISTANT_HANDOFF_RESULT_TIMEOUT_MILLIS
import com.openandroidintelligence.conversation.assistant.AssistantHandoffDto
import com.openandroidintelligence.conversation.assistant.AssistantHandoffState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistantSession(this)
}

/**
 * 系统助理会话：把 App 内的助理界面搬到"其他 App 之上"。
 *
 * 窗口策略（与界面契约一一对应）：
 * - **常驻形态**：宽 `MATCH_PARENT`、`Gravity.BOTTOM`、背景透明。窗口不是卡片，
 *   卡片由 Compose 自己画；窗口只负责给出"底部一条"的可用区域。
 * - **高度由测量驱动**：Compose 根节点把自身高度回传 [onContentHeightChanged]，
 *   这里用 `window.setLayout(widthPx, heightPx)` 精确对齐。界面变高（展开会话）→ 窗口
 *   跟着变高；界面变矮（回到紧凑输入）→ 窗口跟着变矮。界面永远是唯一的尺寸真相。
 * - **圈选态**：临时铺满屏幕（`MATCH_PARENT × MATCH_PARENT`、`TOP|START`），这样系统
 *   截图能按真实比例铺满；退出圈选立刻回到测量驱动的高度。
 * - **键盘**：`SOFT_INPUT_ADJUST_NOTHING` + `setDecorFitsSystemWindows(false)`，
 *   系统不替我们缩放窗口，键盘留白由 Compose 的 `imePadding()` 承担，避免两套 inset
 *   互相打架（窗口被系统缩一次、内容又让一次）。
 *
 * 生命周期与数据：
 * - `onShow` 建 Compose 宿主并绑定主进程桥接，`onHide` 立即释放桥接、销毁组合；
 * - 所有数据来自主进程桥接，没有连接就显示未连接引导；
 * - 返回键：定格截图态直接结束会话（空草稿时）；已有圈选附件则先退回紧凑输入栏，
 *   以便把刚圈的内容发出去。其余覆盖层先退出、再结束会话。返回键**不会**中断生成，
 *   也不会折叠对话。
 */
internal class AssistantSession(service: AssistantSessionService) : VoiceInteractionSession(service) {
    private var host: AssistantComposeHost? = null
    private var client: AssistantBridgeClient? = null
    /**
     * 系统截图：**Compose 可观察状态**。
     *
     * 界面必须能跟着换帧。系统可能在定格态再投递一张新截图，此时旧帧会被回收；
     * 若界面读的是普通字段，新帧到达后组合不会重建，仍引用已回收的旧位图
     * （随后退回普通输入栏，而窗口仍停在全屏）。
     */
    private val screenshot = androidx.compose.runtime.mutableStateOf<Bitmap?>(null)

    /** 界面形态与覆盖层：唯一的状态真相，界面与窗口都读它。 */
    private val uiState = AssistantSessionUiController().also { controller ->
        // 窗口几何由状态观察单点驱动：只要覆盖层不再是定格截图，窗口就必须回到测量驱动。
        // 文字发送、语音发送、返回键、系统重新截图都会清掉覆盖层，逐个调用点补一定会漏。
        controller.bindOnStateChanged { state -> syncWindowWithOverlay(state) }
    }

    /** 圈选用的系统截图；非 null 表示当前处于全屏圈选态。 */
    private val selectionState = mutableStateOf<AssistantScreenSelection?>(null)

    /**
     * 定格截图态：系统截图到达后窗口铺满，界面变成「系统截图 + 直接画圈」。
     *
     * 与 [selectionState] 分开：后者是**裁剪页**（有「确认选区」步骤），前者是唤起后的
     * 默认形态。窗口是否全屏只由 [immersiveLayout] 决定，两者共用同一条判断。
     */
    private val frozenFullscreen = mutableStateOf(false)

    /**
     * 会话级本地预览存储。
     *
     * 由会话持有而不是只在组合里 `remember`：拍照回调在组合之外触发，必须在位图被
     * 编码回收**之前**把缩略图写进去。界面复用同一个实例，`adoptPendingImage` 才能把
     * 本进程的预览改挂到主进程回传的权威附件 ID 上。
     */
    private val previews = AssistantLocalPreviews()

    /** 最近一次由 Compose 测量出的内容高度（像素）；0 表示还没有测量结果。 */
    private var measuredContentHeightPx = 0

    /**
     * 用户是否已经在本进程的输入栏里敲过字（含尚未同步到主进程的按键）。
     *
     * 由客户端在派发 `draft` 命令时回调置位；会话结束时清空。
     */
    @Volatile private var userHasStartedTyping = false

    /**
     * 附件来源卡片是否展开（渲染在会话窗口内，不新建 Dialog 窗口）。
     *
     * 用 Compose 状态而不是普通字段：它直接驱动界面可见性，写入发生在主线程。
     */
    /**
     * 上滑交接手势进行中：窗口临时铺满。
     *
     * 紧凑态窗口只有输入栏高（实测 420px）。手指一旦上移出窗口边界，系统就不再向
     * 本窗口派发后续 MOVE —— 实测位移只累计到 63px 就停，阈值永远达不到，
     * 表现正是「有动画但进不去主 App」。铺满后整段手势都在窗口内，松手再按结果决定收回。
     */
    private val dragExpanded = mutableStateOf(false)

    private val attachmentSheetOpen = mutableStateOf(false)

    /** 系统选择器结果的会话侧强引用；[onHide]/[onDestroy] 时清空。 */
    private var pendingPickerCallback: ((List<Uri>) -> Unit)? = null

    /**
     * 拍照结果的会话侧强引用（仅「拍照」来源会登记）。
     *
     * 与 [pendingPickerCallback] 同理：broker 只持弱引用，会话不留一份的话位图回调
     * 会在 Activity 返回前被回收，表现为「拍完照回到助手什么都没发生」。
     */
    private var pendingCaptureCallback: ((android.graphics.Bitmap) -> Unit)? = null

    /**
     * 选择器「已结束」回调的会话侧强引用。
     *
     * broker 只持弱引用：若会话不在这里留一份，这个 lambda 会在 Activity 返回前被回收，
     * 表现为「选完文件窗口不恢复、浮层再也不出现」。与 [pendingPickerCallback] 同理。
     */
    private var pendingPickerFinished: (() -> Unit)? = null

    /**
     * 会话窗口是否因为系统选择器而被临时停用（`setUiEnabled(false)`）。
     *
     * 停用后窗口不可见也不接收触摸，选择器可以正常点击；结果/取消/会话结束时必须恢复，
     * 否则下一次唤起会因为 `mUiEnabled=false` 而根本建不出窗口。
     */
    private var pickerSuspended = false

    /** 交接请求是否在等待主进程回执；同一时刻只允许一次。 */
    private var handoffRequested = false

    /** 正在等待的那一次请求的代次；回执必须带上同一个值才算本轮结果。 */
    private var pendingHandoffId: String? = null
    private var handoffWatchdog: Runnable? = null
    private var handoffJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val handoffScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 结束会话只执行一次：返回键与界面结束回调可能同时到达。 */
    private var closing = false

    /**
     * 当前这一轮展示是否已经开始。
     *
     * 系统首次展示时先建窗口（触发 [onCreateContentView] 绑定桥接）再回调 [onShow]，
     * 因此不能只凭 `client != null` 判断"这是上一次的残留"。`presented` 区分两者：
     * - `false` 且已有客户端 → 窗口刚刚为本次展示创建，直接沿用；
     * - `true` → 浮层仍可见时又收到一次唤起，必须换成全新会话。
     */
    private var presented = false

    override fun onCreateContentView(): View {
        val bridge = AssistantBridgeClient(context).also {
            it.onDraftDispatched = { text -> if (text.isNotEmpty()) userHasStartedTyping = true }
            client = it
        }
        val composeHost = AssistantComposeHost(context)
        host = composeHost
        // 测量高度回传链路：Compose 根节点 → 宿主 → 会话窗口。
        composeHost.onContentHeightChanged = { height -> applyMeasuredHeight(height) }
        // 窗口本身不画背景：卡片与卡片外的透明区域都由 Compose 决定。
        window?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // 键盘：窗口不缩放，留白交给 Compose 的 imePadding；系统栏由 Compose 的 inset 处理。
        window?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        window?.window?.setDecorFitsSystemWindows(false)
        composeHost.setContent {
            AssistantSessionContent(
                client = bridge,
                controller = uiState,
                previews = previews,
                selection = selectionState.value,
                // 系统会话没有第二个圈选入口：唤起后直接在定格截图上画圈。
                // 「本入口无系统截图」的说明路径只属于 ASSIST 兜底页（见 AssistantActivity）。
                onStartSelection = null,
                onFinishSelection = { finishScreenSelection() },
                onSelectionReady = { result -> submitSelection(result) },
                onOpenApp = { openMainApp() },
                // 定格截图：只有拿到系统截图**并且**状态机确实进入定格态时才铺满，
                // 两者由本类同步推进；窗口几何只看 `immersiveLayout()`。
                // 传 State 而不是快照：界面读 value 才会在新帧到达时重组。
                frozenScreenshot = frozenScreenshotState(),
                onRequestHandoff = { requestHandoff() },
                onDragWindowExpanded = { expanded -> setDragWindowExpanded(expanded) },
                // 「＋」先展开会话窗口内的附件来源卡片（与主 App 同一份内容），
                // 用户真正选了某一类来源后才启动对应的系统页面。
                onAddAttachment = { kind ->
                    if (kind == AssistantAttachmentKind.SHEET) setAttachmentSheetOpen(true)
                    else requestAttachmentPicker(kind)
                },
                attachmentSheetVisible = attachmentSheetOpen.value,
                onDismissAttachmentSheet = { setAttachmentSheetOpen(false) },
                onPickAttachmentSource = { source ->
                    setAttachmentSheetOpen(false)
                    requestAttachmentPicker(source.toPickerKind())
                },
                // 发送被接收：定格在状态机里已经结束，这里把窗口同步放回测量驱动。
                onExitFrozen = { exitFrozenSelection() },
                onExit = { requestExit() },
                // 高度回传走宿主：界面不直接依赖窗口，宿主只做转发。
                onContentHeightChanged = composeHost::reportContentHeight,
            )
        }
        return composeHost
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        AssistantDiagnostics.configure(context)
        // 系统是否请求了截图，是「为什么没有截图画圈」的第一个分叉点：
        // SHOW_WITH_SCREENSHOT=2 未置位时系统根本不会调用 onHandleScreenshot，
        // 我们只能提示「本次唤起未包含截图」，而不是等超时后说「系统未提供」。
        val wantsScreenshot = (showFlags and VoiceInteractionSession.SHOW_WITH_SCREENSHOT) != 0
        AssistantDiagnostics.screenshot(
            "request",
            "showFlags=$showFlags screenshotFlag=$wantsScreenshot args=${args?.keySet()?.joinToString(",")} presented=$presented",
        )
        // 一次 onShow 就是一次新的系统唤起。上一次 onHide 正常会释放桥接；但用户也可能
        // 在浮层仍可见时再次触发助理手势，此时旧客户端与旧会话都还在。只有那时才需要
        // 丢弃旧客户端重开；首次展示的客户端刚由 onCreateContentView 建好，必须沿用。
        if (presented) {
            client?.let(AssistantSessionLifecycle::leave)
            client = null
            host?.destroy()
            host = null
            // 上一轮的截图与选区只属于上一次唤起。系统本次可能还没交付新截图，
            // 若把它们留着，新会话就能圈选并提交上一轮的屏幕内容 —— 那既不是
            // 本次唤起时的屏幕，也从未得到用户对这一轮的确认。
            resetTransientState()
        }
        // 没有可用客户端（上一次 onHide 已释放）时，重建一套干净的宿主与桥接。
        if (client == null || host == null) setContentView(onCreateContentView())
        presented = true
        // 每次唤起都是新的一次会话：上一轮未完成的交接请求不能延续到这一轮。
        handoffRequested = false
        pendingHandoffId = null
        // 卡片是上一次唤起的临时状态：新一次唤起必须从收起状态开始。
        attachmentSheetOpen.value = false
        // 每次唤起都从紧凑输入开始：不继承上一次会话的展开态与覆盖层。
        uiState.dispatch(AssistantUiEvent.SessionShown)
        closing = false
        // 截图可能在本次 onShow 之前就已投递：唤起后仍处于初态时补一次进入定格。
        // 用户很快就开始打字时 canFreeze 会因草稿非空而拒绝，从而不打断输入。
        if (screenshot.value != null && canFreezeForSelection()) enterFrozenSelection()
        // 系统明确没有请求截图：不会再有 onHandleScreenshot，必须现在就说清楚，
        // 否则用户会一直等着画圈，或在超时后才看到一条误导性的「系统未提供截图」。
        if (!wantsScreenshot) {
            AssistantDiagnostics.screenshot("request", "系统未请求截图，无法圈选")
            client?.showAdvisory("本次唤起未包含系统截图，无法圈选；可直接输入文字")
        }
        applyWindowLayout()
        host?.resume()
        // 每次唤起都必然新建会话，不需要再发 `session.new`：`connect` 会为主进程
        // 建一个全新客户端，主进程随即 `openSession(client, UUID.randomUUID())`
        // （restoreLastThread=false），即新的会话身份与空的控制器。
        client?.let(AssistantSessionLifecycle::enter)
    }

    override fun onHide() {
        handoffJob?.cancel()
        handoffJob = null
        handoffWatchdog?.let(mainHandler::removeCallbacks)
        handoffWatchdog = null
        // 会话结束：本轮交接代次一并作废，下一次唤起必须从头开始。
        handoffRequested = false
        pendingHandoffId = null
        // 隐藏即会话结束：桥接立刻释放，宿主退回 CREATED，组合随视图解绑被回收。
        // 先置位再推进状态：SessionHidden 会走到"结束"分支，不能让它再触发一次 hide。
        // 退出只断开连接、不取消生成：已提交的任务由主进程继续。
        closing = true
        presented = false
        // 选择器仍打开时被系统收起：窗口必须恢复，下一次唤起才建得出界面。
        restoreSessionUi()
        uiState.dispatch(AssistantUiEvent.SessionHidden)
        client?.let(AssistantSessionLifecycle::leave)
        client = null
        host?.stop()
        userHasStartedTyping = false
        attachmentSheetOpen.value = false
        pendingPickerCallback = null
        pendingCaptureCallback = null
        pendingPickerFinished = null
        AssistantAttachmentPickerBroker.cancel()
        resetTransientState()
        super.onHide()
    }

    override fun onDestroy() {
        handoffJob?.cancel()
        handoffJob = null
        handoffWatchdog?.let(mainHandler::removeCallbacks)
        handoffWatchdog = null
        handoffRequested = false
        pendingHandoffId = null
        restoreSessionUi()
        client?.let(AssistantSessionLifecycle::leave)
        client = null
        pendingPickerCallback = null
        pendingCaptureCallback = null
        pendingPickerFinished = null
        AssistantAttachmentPickerBroker.cancel()
        resetTransientState()
        uiState.bindOnStateChanged(null)
        previews.close()
        host?.destroy()
        host = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转/分屏会改变可用尺寸：重算窗口宽度，避免内容按旧宽度停在屏幕外。
        applyWindowLayout()
    }

    override fun onBackPressed() {
        // 返回键先关最上层的临时界面，顺序与视觉层级一致：
        // 附件来源卡片 → 圈选/录音覆盖层 → 收起整个会话。
        // 卡片必须在这里拦下：否则一次返回会直接结束会话，尚未交接的草稿与附件
        // 随之被释放（规格 §203、§338 要求取消选择不丢草稿）。
        if (attachmentSheetOpen.value) {
            setAttachmentSheetOpen(false)
            return
        }
        // 定格截图态默认按返回即结束会话（见状态机）。**但已经圈出附件时例外**：
        // 直接结束会连同草稿与附件一起释放（规格 §338 要求取消不丢草稿），
        // 因此先退回紧凑输入栏，让用户仍能把刚圈的内容发出去。
        if (uiState.visible()?.overlay == AssistantOverlay.FROZEN_SCREENSHOT && hasDraftContent()) {
            exitFrozenSelection()
            return
        }
        // 定格截图态由状态机直接判定结束：它背后的紧凑输入栏没有任何圈选入口，
        // 只清覆盖层会让用户停在一个既不能画圈也不能输入的中间态（需要按两次返回）。
        // 其余覆盖层退出后仍有完整界面，保持两段式。
        uiState.dispatch(AssistantUiEvent.BackPressed)
        val visible = uiState.visible()
        if (visible != null) {
            // 还有可见界面：说明刚退出的是覆盖层，窗口回到测量驱动的底部形态。
            selectionState.value = null
            frozenFullscreen.value = false
            applyWindowLayout()
            return
        }
        // 状态机判定会话结束：清理临时态后收窗口。
        // 结束回调可能与这里的路径重合，requestExit 自己保证只执行一次。
        requestExit()
    }

    /**
     * 当前草稿里是否已经有用户内容（文字或附件）。
     *
     * 只有这时「返回 = 结束会话」才会丢东西；空草稿的定格态可以直接退出。
     */
    private fun hasDraftContent(): Boolean {
        val state = client?.state?.value ?: return false
        return state.draft.isNotBlank() || state.attachments.isNotEmpty()
    }

    /**
     * 结束会话：界面里的退出动作与返回键走同一条路径。
     *
     * 只负责收窗口，不动生成：正在输出的回复由主进程继续，用户回到 App 仍能看到结果。
     */
    private fun requestExit() {
        if (closing) return
        closing = true
        closeOverlayIfAny()
        hide()
    }

    override fun onHandleScreenshot(bitmap: Bitmap?) {
        // 新的一张取代旧的。**先发布新帧、再释放旧帧**：界面可能正在绘制旧图，
        // 先回收会让它读到已回收的位图。新帧写入可观察状态后，组合会换到新图，
        // 旧帧此时已经没有读者。
        val previous = screenshot.value
        if (selectionState.value != null) {
            // 新截图到达说明上一次圈选已经失效：覆盖层同步退出，避免展示旧帧。
            selectionState.value = null
            uiState.dispatch(AssistantUiEvent.SelectionCancelled)
        }
        val prepared = prepareScreenshot(bitmap)
        screenshot.value = prepared.bitmap
        // 降采样/转换会产出新位图：系统那份原件已经没人要，及时释放。
        if (bitmap != null && bitmap !== prepared.bitmap && bitmap !== previous &&
            !bitmap.isRecycled
        ) {
            bitmap.recycle()
        }
        if (previous !== prepared.bitmap && previous?.isRecycled == false) previous.recycle()
        // 已经在定格态时系统又投递了一张：旧帧上的轨迹与选区都失效。
        // 这里**重新进入定格**而不是退出后不管：系统只投递一次截图、界面上也没有第二个
        // 圈选入口，退出后用户就再也回不到可画圈的状态了。
        //
        // 换帧成功时不弹任何提示：定格态本身就是「直接画圈」的界面，轨迹由新的截图实例
        // 自动清空，用户接着画就行。多弹一张说明卡会盖住内容，且需求从未要求过这条文案。
        if (uiState.visible()?.overlay == AssistantOverlay.FROZEN_SCREENSHOT) {
            if (prepared.bitmap == null) {
                exitFrozenSelection()
                client?.showAdvisory(prepared.failureMessage)
            }
            return
        }
        // 截图是异步到达的：只有会话仍在「刚唤起、未开始输入」的初态才进入定格，
        // 用户已经在打字/发消息时强行全屏会打断他们正在做的事。此时保持现状、不打断，
        // 也不额外提示——用户正在输入，不需要一份「这次不进入圈选」的说明。
        if (prepared.bitmap != null) {
            if (canFreezeForSelection()) enterFrozenSelection()
            return
        }
        uiState.dispatch(AssistantUiEvent.ScreenshotUnavailable)
        client?.showAdvisory(prepared.failureMessage)
    }

    /** 一次截图准备的结果：可用位图，或带具体原因的失败文案。 */
    private class PreparedScreenshot(val bitmap: Bitmap?, val failureMessage: String)

    /**
     * 校验并归一化系统投递的截图。
     *
     * 与旧实现的关键差别：**像素数不再直接判死**。1440×3120（4.49M）、1344×2992（4.02M）
     * 这类常见旗舰分辨率都超过旧上限，旧代码把它们与「系统没给截图」混成同一条提示，
     * 用户看到「系统未提供截图」却明明开着屏幕分析。现在超限按比例降采样——
     * 圈选的坐标换算本来就按实际位图尺寸做，缩放不影响正确性。
     *
     * 每一步失败都带自己的原因，不再统一落到「系统未提供截图」。
     */
    private fun prepareScreenshot(bitmap: Bitmap?): PreparedScreenshot {
        if (bitmap == null) {
            AssistantDiagnostics.screenshot("callback", "bitmap=null（系统未投递或采集失败）")
            return PreparedScreenshot(null, "系统未提供截图，无法圈选；可直接输入文字")
        }
        AssistantDiagnostics.screenshot(
            "callback",
            "size=${bitmap.width}x${bitmap.height} config=${bitmap.config} recycled=${bitmap.isRecycled}",
        )
        if (bitmap.isRecycled) {
            AssistantDiagnostics.screenshot("validate", "recycled")
            return PreparedScreenshot(null, "截图已失效，请重新唤起助手后再圈选")
        }
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            AssistantDiagnostics.screenshot("validate", "invalid-size")
            return PreparedScreenshot(null, "截图尺寸无效，请重新唤起助手后再圈选")
        }
        val pixels = bitmap.width.toLong() * bitmap.height
        val scaled = if (pixels > MAX_SCREENSHOT_PIXELS) {
            // 只缩不放；失败时如实说明是「缩小失败」而不是「系统没给」。
            AssistantDiagnostics.screenshot("validate", "pixels=$pixels over=$MAX_SCREENSHOT_PIXELS 降采样")
            scaleScreenshot(bitmap)
                ?: run {
                    AssistantDiagnostics.screenshot("validate", "scale-failed")
                    return PreparedScreenshot(null, "截图过大且无法缩小，请重新唤起助手后再圈选")
                }
        } else {
            bitmap
        }
        AssistantDiagnostics.screenshot("validate", "ready=${scaled.width}x${scaled.height}")
        // 系统投递到应用侧时已是软件位图（跨 Binder 只接受 N32/565/4444/A8），
        // 这里通常不需要再复制一份；只有硬件位图或可变性不符时才复制。
        val normalized = if (scaled.config == Bitmap.Config.ARGB_8888 && !scaled.isMutable) {
            scaled
        } else {
            runCatching { scaled.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                ?: run {
                    AssistantDiagnostics.screenshot("validate", "copy-failed config=${scaled.config}")
                    return PreparedScreenshot(null, "截图无法转换，请重新唤起助手后再圈选")
                }
        }
        return PreparedScreenshot(normalized, "")
    }

    /** 把超限截图按比例缩到 [MAX_SCREENSHOT_PIXELS] 以内；失败返回 null。 */
    private fun scaleScreenshot(source: Bitmap): Bitmap? = runCatching {
        val ratio = kotlin.math.sqrt(MAX_SCREENSHOT_PIXELS.toDouble() / (source.width.toLong() * source.height))
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(source, width, height, true)
    }.getOrNull()

    /**
     * 是否可以在本次唤起的初态进入定格：紧凑输入、无覆盖层、用户还没有开始输入。
     *
     * 「还没有开始输入」同时看**主进程回传的草稿**与**本进程输入栏尚未同步的本地编辑**：
     * 客户端把每次按键都发给主进程，但状态帧回来有延迟；只看回传值会在用户刚开始打字
     * 的那几帧里误判成「还在初态」。
     */
    private fun canFreezeForSelection(): Boolean {
        val visible = uiState.visible() ?: return false
        if (visible.overlay != AssistantOverlay.NONE) return false
        if (visible.surface != AssistantSurfaceState.COMPACT_INPUT) return false
        return !userHasStartedTyping && client?.state?.value?.draft.isNullOrBlank()
    }

    /**
     * 进入定格：状态机与窗口几何同时推进，避免「窗口全屏但界面以为在紧凑态」。
     *
     * `mutableStateOf` 的写入发生在组合外，必须回到 UI 线程才能被观察；
     * 系统回调本身就是主线程，这里只做防御性收敛。
     */
    @android.annotation.SuppressLint("MainThread")
    private fun enterFrozenSelection() {
        frozenFullscreen.value = true
        uiState.dispatch(AssistantUiEvent.ScreenshotAvailable)
        if (uiState.visible()?.overlay != AssistantOverlay.FROZEN_SCREENSHOT) {
            // 状态机拒绝了（已展开/已有别的覆盖层）：不要留下「窗口全屏但界面紧凑」。
            frozenFullscreen.value = false
        }
        applyWindowLayout()
    }

    /**
     * 退出定格：窗口回到测量驱动；界面仍保持紧凑输入（草稿与附件都保留）。
     *
     * `applyMeasuredHeight` 有去重：退出后高度可能与进入前相同，不再触发重排，
     * 因此这里必须主动调用一次 [applyWindowLayout]。
     */
    private fun exitFrozenSelection() {
        if (!frozenFullscreen.value) return
        frozenFullscreen.value = false
        if (uiState.visible()?.overlay == AssistantOverlay.FROZEN_SCREENSHOT) {
            uiState.dispatch(AssistantUiEvent.ScreenshotUnavailable)
        }
        applyWindowLayout()
    }

    /**
     * 状态观察单点：覆盖层不再是定格截图时，窗口同步回到测量驱动。
     *
     * 语音发送（`VoiceSent`）会把覆盖层清成 `NONE`，但它走的是录音回调，不经过
     * `onExitFrozen`。如果窗口几何只由显式调用推进，语音发送后窗口会留在
     * `MATCH_PARENT × MATCH_PARENT` 的透明全屏上，吞掉下方 App 的触摸；用户再按返回时
     * 覆盖层已经是 `NONE`，直接被判成结束会话。挂在状态转移点上，所有路径自动一致。
     */
    private fun syncWindowWithOverlay(state: AssistantUiState) {
        if (!frozenFullscreen.value) return
        val overlay = (state as? AssistantUiState.Visible)?.overlay
        if (overlay == AssistantOverlay.FROZEN_SCREENSHOT) return
        frozenFullscreen.value = false
        applyWindowLayout()
    }

    /** 一次会话结束时的临时状态：圈选与截图都不跨会话保留。 */
    private fun resetTransientState() {
        selectionState.value = null
        frozenFullscreen.value = false
        // 拖拽铺满与附件卡片都是**本次唤起**的临时状态：会话结束时必须复位，
        // 否则下一次唤起会继承全屏窗口/展开的卡片。
        dragExpanded.value = false
        attachmentSheetOpen.value = false
        // 预览字节属于本次会话：结束即清空，避免上一次唤起的屏幕内容留在内存里。
        previews.clear()
        recycleScreenshot()
        measuredContentHeightPx = 0
    }

    private fun recycleScreenshot() {
        screenshot.value?.recycle()
        screenshot.value = null
    }

    /**
     * Compose 测量出的内容高度：窗口高度以此为唯一来源。
     *
     * 去重后再 `setLayout`，避免同一高度的重复回调触发无意义的窗口重排
     * （每次 setLayout 都会让系统重新测量，重复调用会造成可见抖动）。
     */
    private fun applyMeasuredHeight(heightPx: Int) {
        if (heightPx <= 0 || heightPx == measuredContentHeightPx) return
        if (immersiveLayout()) {
            // 圈选/定格期间窗口固定全屏：此时的高度回调只记录，等退出全屏再应用。
            measuredContentHeightPx = heightPx
            return
        }
        measuredContentHeightPx = heightPx
        applyWindowLayout()
    }

    /**
     * 当前应当渲染定格截图的位图。
     *
     * 只认状态机里的 [AssistantOverlay.FROZEN_SCREENSHOT]：渲染分支由它决定，窗口几何由
     * [immersiveLayout] 决定，两者在同一处（[enterFrozenSelection]/[exitFrozenSelection]）
     * 同步推进。截图不可用（已回收）时返回 null，界面退回紧凑输入而不画假背景。
     */
    private fun frozenScreenshot(): Bitmap? {
        if (uiState.visible()?.overlay != AssistantOverlay.FROZEN_SCREENSHOT) return null
        return screenshot.value?.takeIf { !it.isRecycled }
    }

    /**
     * 交给界面的定格截图**状态**（不是快照）。
     *
     * 界面在组合期读 `value`，因此系统在定格态换帧时会重组并换到新图；
     * 传快照会让界面一直引用已被回收的旧位图。
     */
    private fun frozenScreenshotState(): androidx.compose.runtime.State<Bitmap?> =
        androidx.compose.runtime.derivedStateOf {
            if (uiState.visible()?.overlay != AssistantOverlay.FROZEN_SCREENSHOT) null
            else screenshot.value?.takeIf { !it.isRecycled }
        }

    /** 是否处于全屏临时形态（裁剪页或定格截图）。窗口几何的唯一判据。 */
    private fun immersiveLayout(): Boolean =
        selectionState.value != null || frozenFullscreen.value || dragExpanded.value ||
            // 附件来源卡片是模态层（带遮罩），内容比紧凑窗口高得多：
            // 不铺满的话卡片下半截会被窗口下沿裁掉，用户只能看到第一项。
            attachmentSheetOpen.value

    private fun applyWindowLayout() {
        val dialogWindow = window?.window ?: return
        if (immersiveLayout()) {
            // 圈选/定格态：临时铺满屏幕，让系统截图按真实比例显示。
            dialogWindow.setGravity(Gravity.TOP or Gravity.START)
            dialogWindow.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            return
        }
        // 常规态：整宽贴底，高度取界面测量值；还没有测量结果时先给一个最小高度，
        // 等第一帧 onSizeChanged 到达就立即校正（不会把窗口停在 0 高）。
        // 再夹到屏幕高度：测量值本身不该把窗口顶出屏幕（横屏/分屏/异常 inset 时会发生）。
        val screenHeightPx = context.resources.displayMetrics.heightPixels
        val boundedHeight = measuredContentHeightPx
            .coerceAtLeast(MIN_WINDOW_HEIGHT_PX)
            .let { if (screenHeightPx > 0) it.coerceAtMost(screenHeightPx) else it }
        dialogWindow.setGravity(Gravity.BOTTOM)
        dialogWindow.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            boundedHeight,
        )
    }

    /** 屏幕圈选：把 window 临时铺满全屏，让系统截图按真实比例显示。 */
    private fun beginScreenSelection() {
        val source = screenshot.value
        if (source == null || source.isRecycled) {
            client?.showAdvisory("系统没有提供截图，请重新唤起助理后再圈选")
            return
        }
        selectionState.value = AssistantScreenSelection(source)
        uiState.dispatch(AssistantUiEvent.SelectionRequested)
        applyWindowLayout()
    }

    private fun finishScreenSelection() {
        if (selectionState.value == null) return
        selectionState.value = null
        // 覆盖层关掉；对话形态不受影响（确认与取消对状态机是同一件事）。
        uiState.dispatch(AssistantUiEvent.SelectionCancelled)
        // 截图保留到下一次唤起或会话结束，方便用户取消后重新圈选。
        applyWindowLayout()
    }

    /**
     * 圈选产物 → 附件。
     *
     * 舞台已经把 PNG 写进本进程的 [AssistantLocalPreviews]（时间线据此显示缩略图）；
     * 主进程需要的是位图本身，因此在这里解码后交给桥接层。解码失败要如实提示，
     * 不能把"没解出来"当成已提交。
     */
    private fun submitSelection(result: AssistantSelectionResult) {
        val bitmap = runCatching {
            android.graphics.BitmapFactory.decodeByteArray(result.pngBytes(), 0, result.byteSize)
        }.getOrNull()
        if (bitmap == null) {
            client?.showAdvisory("圈选图片无法读取，请重新圈选")
            return
        }
        client?.attachScreen(bitmap)
    }

    private fun closeOverlayIfAny() {
        if (selectionState.value != null) {
            selectionState.value = null
        }
    }

    private fun openMainApp() {
        if (launchMainActivity(context, handoffIntent = null, session = this)) {
            hide()
        } else {
            client?.showAdvisory("无法打开 App，请从启动器打开")
        }
    }

    /**
     * 上滑交接：先把会话 + 草稿 + 附件交给主进程，**拿到 ACCEPTED 后**才打开主 App 并关闭浮层。
     *
     * 严格模式：命令送不出去、回执被拒或超时都留在浮层，并把原因说清楚。草稿与附件
     * 在失败时原样保留 —— 这是「先迁移成功再关闭」的全部意义。
     */
    private fun requestHandoff() {
        val bridge = client ?: run {
            client?.showAdvisory(AssistantHandoffMessages.NOT_CONNECTED)
            // 早退也必须收回拖拽铺满的窗口：否则透明全屏会一直拦住下方 App 的触摸。
            setDragWindowExpanded(false)
            return
        }
        // 每次上滑都换一个新的请求代次：上一轮留下的终态不能再被当成本轮结果。
        val handoffId = java.util.UUID.randomUUID().toString()
        AssistantDiagnostics.handoff("request", "handoffId=$handoffId connected=${bridge.state.value.connected}")
        if (!bridge.requestHandoff(handoffId)) {
            AssistantDiagnostics.handoff("request", "命令未送达")
            setDragWindowExpanded(false)
            bridge.showAdvisory(AssistantHandoffMessages.NOT_CONNECTED)
            return
        }
        // 回执随下一次状态帧下发；等待期间不再接受第二次请求，避免重复迁移。
        handoffRequested = true
        pendingHandoffId = handoffId
        handoffWatchdog?.let(mainHandler::removeCallbacks)
        handoffWatchdog = Runnable {
            if (!handoffRequested) return@Runnable
            handoffRequested = false
            pendingHandoffId = null
            AssistantDiagnostics.handoff("watchdog", "超时未收到回执")
            setDragWindowExpanded(false)
            client?.showAdvisory(AssistantHandoffMessages.TIMEOUT)
        }.also { mainHandler.postDelayed(it, HANDOFF_TIMEOUT_MILLIS) }
        observeHandoffResult(bridge, handoffId)
    }

    /**
     * 观察状态帧里的交接结果；ACCEPTED 才打开主 App，REJECTED 立即提示并复位。
     *
     * **只认本轮代次**：`StateFlow` 在订阅或请求后会立即重放当前值，上一轮残留在帧里的
     * REJECTED 会在新一轮刚开始时被重放一次，把本轮判成失败，随后真正的 ACCEPTED 又被
     * `handoffRequested == false` 挡掉——主进程已经接管了草稿，浮层却留在失败状态。
     * 旧主进程不认识 `handoffId`（回执里为空）时退回「只看终态」的宽容策略。
     */
    private fun observeHandoffResult(bridge: AssistantBridgeClient, handoffId: String) {
        handoffJob?.cancel()
        handoffJob = handoffScope.launch {
            bridge.state.collect { state ->
                if (!handoffRequested || pendingHandoffId != handoffId) return@collect
                val handoff = state.handoff ?: return@collect
                if (handoff.handoffId.isNotEmpty() && handoff.handoffId != handoffId) return@collect
                when (AssistantHandoffState.of(handoff.state)) {
                    AssistantHandoffState.ACCEPTED -> {
                        AssistantDiagnostics.handoff(
                            "receipt",
                            "ACCEPTED conversation=${handoff.conversationId} agent=${handoff.agentId}",
                        )
                        handoffRequested = false
                        pendingHandoffId = null
                        handoffWatchdog?.let(mainHandler::removeCallbacks)
                        handoffWatchdog = null
                        setDragWindowExpanded(false)
                        proceedWithAcceptedHandoff(bridge, handoff)
                    }
                    AssistantHandoffState.REJECTED -> {
                        AssistantDiagnostics.handoff("receipt", "REJECTED ${handoff.reason}")
                        handoffRequested = false
                        pendingHandoffId = null
                        handoffWatchdog?.let(mainHandler::removeCallbacks)
                        handoffWatchdog = null
                        // 交接失败：窗口必须收回，否则浮层会停在满屏挡住下方 App。
                        setDragWindowExpanded(false)
                        bridge.showAdvisory(handoff.reason.ifBlank { AssistantHandoffMessages.REJECTED })
                    }
                    AssistantHandoffState.PENDING, null -> Unit
                }
            }
        }
    }

    /**
     * 主进程已接管：打开主 App 的对应会话，然后才关闭浮层。
     *
     * 打开失败（后台启动被系统拒绝等）时留在浮层并说明 —— 数据已经安全地在主进程里，
     * 但用户不该在什么都没看到的情况下被关掉界面。
     */
    private fun proceedWithAcceptedHandoff(bridge: AssistantBridgeClient, handoff: AssistantHandoffDto) {
        // 交接成功即全部附件都已迁移：主进程会等未就绪附件完成，不会再有「跳过」。
        // 仍保留服务端计数作兜底展示（旧主进程可能给出非零值），但不伪造成功。
        val skipped = if (handoff.skippedAttachments > 0) {
            "，另有 ${handoff.skippedAttachments} 个附件未能带入"
        } else {
            ""
        }
        val link = AssistantHandoffLink.from(
            profileId = handoff.profileId,
            accountId = handoff.accountId,
            conversationId = handoff.conversationId,
            agentId = handoff.agentId,
            sessionToken = bridge.state.value.sessionId.orEmpty(),
        )
        if (launchMainActivity(context, link?.toIntent(context), session = this)) {
            if (skipped.isNotEmpty()) bridge.showAdvisory("已在 App 中打开$skipped")
            hide()
        } else {
            bridge.showAdvisory("交接已完成，但无法打开 App，请从启动器打开$skipped")
        }
    }

    /**
     * 点击「＋」菜单里的某一类入口：请求选择器 Activity 打开对应的系统选择器
     * （会话本身没有 `ActivityResultRegistry`）。
     *
     * 选中结果经 [AssistantAttachmentPickerBroker] 回到这里：只做「去重 → 按剩余槽位截断 →
     * 交给主进程」，本进程不读字节、不解析 MIME。
     *
     * [kind] 必须一路传到 Activity：照片/视频走 Photo Picker 多选，文档走 SAF。
     * 早先无论点什么都只开 Photo Picker，「文档」这类现有附件类型在浮层里不可达。
     *
     * 窗口必须先停用：助手浮层是常驻在其他窗口之上的 `VoiceInteractionSession`，紧凑态
     * 占着屏幕底部、定格态铺满全屏。选择器虽然在上层，浮层窗口没有让出触摸区域时，
     * 选择器底部的 `Done` 落在浮层范围内会被吞掉（真机已复现「连点两次毫无反应」）。
     * `setUiEnabled(false)` 会隐藏会话窗口并把输入焦点交还给上层 Activity；选择器结果
     * 或取消回来后再恢复。
     */
    private fun requestAttachmentPicker(kind: AssistantAttachmentKind) {
        val bridge = client ?: return
        // 回调必须由会话强引用：broker 只持弱引用，单靠它登记会在 Activity 返回前
        // 被回收，表现为「选完文件没有任何反应」。
        val callback: (List<Uri>) -> Unit = { uris ->
            // 回调可能在 Activity 结果线程上：统一回到主线程再发命令。
            mainHandler.post { deliverPickedAttachments(bridge, uris.map(Uri::toString)) }
        }
        pendingPickerCallback = callback
        // 拍照来源拿到的是位图：走既有的「编码 → 屏幕附件」通道，与圈选共用同一条
        // 已验证的路径（含大小上限、管道看门狗与失败提示），不另开一套图片上传。
        val captured: ((android.graphics.Bitmap) -> Unit)? =
            if (kind == AssistantAttachmentKind.CAPTURE) { bitmap ->
                mainHandler.post { attachCapturedPhoto(bridge, bitmap) }
            } else {
                null
            }
        pendingCaptureCallback = captured
        // broker 只持弱引用：会话必须自己强引用「已结束」回调，否则它会在 Activity
        // 返回前被回收，窗口停在不可见状态。
        val finished: () -> Unit = { mainHandler.post { restoreSessionUi() } }
        pendingPickerFinished = finished
        AssistantAttachmentPickerBroker.register(
            callback = callback,
            // 有结果与取消走同一条恢复路径：窗口不能因为用户按了返回就永久不可见。
            onFinished = finished,
            onCaptured = captured,
        )
        // 先停用再启动：窗口在启动瞬间仍在最上层，晚一步就会吞掉选择器的第一次点击。
        suspendSessionUiForPicker()
        // `startAssistantActivity` 没有返回值：只能以「没有抛出异常」为准，
        // 真正的失败（回来时没有 URI）由选择器自身的取消路径如实表达。
        try {
            startAssistantActivity(AssistantAttachmentPickerActivity.intent(context, kind))
        } catch (_: Exception) {
            AssistantAttachmentPickerBroker.cancel()
            restoreSessionUi()
            bridge.showAdvisory("无法打开文件选择器，请稍后再试")
        }
    }

    /**
     * 拍照结果 → 附件草稿。
     *
     * 与原圈选路径保持一致：**先把缩略图写进本进程的 [AssistantLocalPreviews] 并登记为
     * 待认领**，再把位图交给桥接编码上传。桥接在编码后会回收位图，而主进程只回传附件
     * 元数据、不传字节——不在回收前留下预览，输入栏与发送后的时间线就只能显示
     * 「预览不可用」。等状态帧带回权威附件 ID 时，`adoptPendingImage` 会把预览改挂过去。
     */
    private fun attachCapturedPhoto(bridge: AssistantBridgeClient, bitmap: android.graphics.Bitmap) {
        if (bitmap.isRecycled) return
        val localKey = CAPTURE_PREVIEW_KEY
        if (encodeCapturePreview(bitmap)?.let { previews.put(localKey, it) } == true) {
            // 只登记**真的存下了预览**的键：没有预览却登记，会让后一张图被误认领。
            previews.markPendingImage(localKey)
        }
        bridge.attachScreen(bitmap, filename = captureFilename())
    }

    /** 拍照附件的展示文件名：带时间戳，避免同一次会话里多张照片互相覆盖。 */
    private fun captureFilename(): String =
        "assistant-photo-${System.currentTimeMillis()}.png"

    /**
     * 上滑手势开始/结束时的窗口铺满与收回。
     *
     * 交接被拒绝或超时后必须收回：否则浮层会永久停在满屏，挡住下方的 App。
     */
    private fun setDragWindowExpanded(expanded: Boolean) {
        if (dragExpanded.value == expanded) return
        dragExpanded.value = expanded
        applyWindowLayout()
    }

    /**
     * 展开/收起附件来源卡片，并同步窗口几何。
     *
     * 卡片是模态层且比紧凑窗口高：展开时窗口必须铺满，否则卡片下半截被下沿裁掉；
     * 收起后立刻回到测量驱动的高度。重复调用是空操作，不产生多余重排。
     */
    private fun setAttachmentSheetOpen(open: Boolean) {
        if (attachmentSheetOpen.value == open) return
        attachmentSheetOpen.value = open
        applyWindowLayout()
    }


    /** 选择器期间让出窗口；重复调用是空操作，避免与结果回调互相覆盖。 */
    private fun suspendSessionUiForPicker() {
        if (pickerSuspended) return
        pickerSuspended = true
        setUiEnabled(false)
    }

    /** 选择器结束（结果/取消/失败）后恢复窗口；只恢复自己停用过的那一次。 */
    private fun restoreSessionUi() {
        if (!pickerSuspended) return
        pickerSuspended = false
        setUiEnabled(true)
    }

    /** 选择结果 → 主进程附件草稿：去重、按剩余槽位截断，超出部分如实提示。 */
    private fun deliverPickedAttachments(bridge: AssistantBridgeClient, uris: List<String>) {
        val distinct = uris.distinct()
        if (distinct.isEmpty()) return
        val remaining = (com.openandroidintelligence.conversation.model.MAX_COMPOSER_ATTACHMENTS -
            bridge.state.value.attachments.size).coerceAtLeast(0)
        if (remaining == 0) {
            bridge.showAdvisory("每条消息最多添加 ${com.openandroidintelligence.conversation.model.MAX_COMPOSER_ATTACHMENTS} 个附件，请先移除或发送")
            return
        }
        val accepted = distinct.take(remaining)
        if (!bridge.attachFiles(accepted)) {
            bridge.showAdvisory("附件未能加入草稿，请重新选择")
            return
        }
        if (distinct.size > accepted.size) {
            bridge.showAdvisory("附件已达上限，只加入了前 ${accepted.size} 个")
        }
    }

    private companion object {
        /** 首帧测量到达前的最小窗口高度：保证输入栏可见，测量后立即校正。 */
        const val MIN_WINDOW_HEIGHT_PX = 1

        /** 超过约 400 万像素的截图不接收：内存与裁剪成本都不值得。 */
        const val MAX_SCREENSHOT_PIXELS = 4_000_000L

        /** 等待主进程交接回执的上限：必须大于服务端等待附件就绪的上限（3 秒），
         *  否则浮层会先判超时，用户看到的就不是真实原因。 */
        const val HANDOFF_TIMEOUT_MILLIS = ASSISTANT_HANDOFF_RESULT_TIMEOUT_MILLIS
    }
}

/**
 * 卡片里选中的来源 → 选择器 Activity 的类别。
 *
 * 「拍照」走系统相机（返回位图，经既有屏幕附件通道交给主进程）；相册走 Photo Picker
 * 多选；文档走 SAF。三者与主 App 的附件入口一一对应，不做任何静默降级。
 */
private fun com.openandroidintelligence.conversation.workbench.ComposerAttachmentSource.toPickerKind(): AssistantAttachmentKind =
    when (this) {
        com.openandroidintelligence.conversation.workbench.ComposerAttachmentSource.CAMERA ->
            AssistantAttachmentKind.CAPTURE
        com.openandroidintelligence.conversation.workbench.ComposerAttachmentSource.GALLERY ->
            AssistantAttachmentKind.MEDIA
        com.openandroidintelligence.conversation.workbench.ComposerAttachmentSource.DOCUMENT ->
            AssistantAttachmentKind.DOCUMENT
    }

/**
 * 把拍照位图编码成缩略条用的预览字节。
 *
 * 桥接在编码后会回收位图，主进程又只回传附件元数据、不传字节——所以预览必须在
 * 交给桥接**之前**留下。先按最长边缩到 [CAPTURE_PREVIEW_MAX_EDGE]，再以 JPEG 压缩：
 * 预览只用于缩略条，几百像素足够，也避免被 `AssistantLocalPreviews` 的 4MB 总预算拒掉。
 *
 * @return 可直接写入 `AssistantLocalPreviews` 的字节；已回收或编码失败时返回 null
 *   （界面按媒体类型显示图标，不伪造预览）。
 */
internal fun encodeCapturePreview(
    source: android.graphics.Bitmap,
    maxEdge: Int = CAPTURE_PREVIEW_MAX_EDGE,
    quality: Int = CAPTURE_PREVIEW_QUALITY,
): ByteArray? {
    if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
    val longest = maxOf(source.width, source.height)
    val scaled = if (longest <= maxEdge) {
        source
    } else {
        val ratio = maxEdge.toFloat() / longest
        runCatching {
            android.graphics.Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        }.getOrNull() ?: return null
    }
    return try {
        val output = java.io.ByteArrayOutputStream()
        if (scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, output)) {
            output.toByteArray()
        } else {
            null
        }
    } finally {
        // 只在缩放产生了新位图时释放，调用方的原图仍要交给桥接。
        if (scaled !== source && !scaled.isRecycled) scaled.recycle()
    }
}

/** 拍照预览在本进程的临时键；主进程回传权威附件 ID 后由此改挂。 */
internal const val CAPTURE_PREVIEW_KEY = "assistant-capture-preview"

/** 拍照预览的 JPEG 质量与最长边。 */
internal const val CAPTURE_PREVIEW_QUALITY = 85
internal const val CAPTURE_PREVIEW_MAX_EDGE = 720
