package com.openandroidintelligence.assistant

import android.content.res.Configuration
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
 * - 返回键：有覆盖层（圈选）先退出覆盖层，否则结束会话（`hide()`）。返回键**不会**
 *   中断生成，也不会折叠对话。
 */
internal class AssistantSession(service: AssistantSessionService) : VoiceInteractionSession(service) {
    private var host: AssistantComposeHost? = null
    private var client: AssistantBridgeClient? = null
    private var screenshot: Bitmap? = null

    /** 界面形态与覆盖层：唯一的状态真相，界面与窗口都读它。 */
    private val uiState = AssistantSessionUiController()

    /** 圈选用的系统截图；非 null 表示当前处于全屏圈选态。 */
    private val selectionState = mutableStateOf<AssistantScreenSelection?>(null)

    /** 最近一次由 Compose 测量出的内容高度（像素）；0 表示还没有测量结果。 */
    private var measuredContentHeightPx = 0

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
        val bridge = AssistantBridgeClient(context).also { client = it }
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
            val previews = rememberAssistantLocalPreviews(sessionKey = SESSION_PREVIEWS_KEY)
            AssistantSessionContent(
                client = bridge,
                controller = uiState,
                previews = previews,
                selection = selectionState.value,
                onStartSelection = { beginScreenSelection() },
                onFinishSelection = { finishScreenSelection() },
                onSelectionReady = { result -> submitSelection(result) },
                onOpenApp = { openMainApp() },
                onExit = { requestExit() },
                // 高度回传走宿主：界面不直接依赖窗口，宿主只做转发。
                onContentHeightChanged = composeHost::reportContentHeight,
            )
        }
        return composeHost
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
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
        // 每次唤起都从紧凑输入开始：不继承上一次会话的展开态与覆盖层。
        uiState.dispatch(AssistantUiEvent.SessionShown)
        closing = false
        applyWindowLayout()
        host?.resume()
        // 每次唤起都必然新建会话，不需要再发 `session.new`：`connect` 会为主进程
        // 建一个全新客户端，主进程随即 `openSession(client, UUID.randomUUID())`
        // （restoreLastThread=false），即新的会话身份与空的控制器。
        client?.let(AssistantSessionLifecycle::enter)
    }

    override fun onHide() {
        // 隐藏即会话结束：桥接立刻释放，宿主退回 CREATED，组合随视图解绑被回收。
        // 先置位再推进状态：SessionHidden 会走到"结束"分支，不能让它再触发一次 hide。
        // 退出只断开连接、不取消生成：已提交的任务由主进程继续。
        closing = true
        presented = false
        uiState.dispatch(AssistantUiEvent.SessionHidden)
        client?.let(AssistantSessionLifecycle::leave)
        client = null
        host?.stop()
        resetTransientState()
        super.onHide()
    }

    override fun onDestroy() {
        client?.let(AssistantSessionLifecycle::leave)
        client = null
        resetTransientState()
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
        // 返回键先退出覆盖层（圈选），没有覆盖层就把整个会话收掉；
        // 无论哪条路径都不碰生成：正在输出的回复由主进程继续，界面没有"就地中断"。
        uiState.dispatch(AssistantUiEvent.BackPressed)
        val visible = uiState.visible()
        if (visible != null) {
            // 还有可见界面：说明刚退出的是覆盖层，窗口回到测量驱动的底部形态。
            selectionState.value = null
            applyWindowLayout()
            return
        }
        // 状态机判定会话结束：清理临时态后收窗口。
        // 结束回调可能与这里的路径重合，requestExit 自己保证只执行一次。
        requestExit()
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
        // 系统每次唤起最多给一张截图；新的一张取代旧的，旧图立即释放。
        recycleScreenshot()
        if (selectionState.value != null) {
            // 新截图到达说明上一次圈选已经失效：覆盖层同步退出，避免展示旧帧。
            selectionState.value = null
            uiState.dispatch(AssistantUiEvent.SelectionCancelled)
        }
        val usable = bitmap?.takeIf { it.width > 0 && it.height > 0 }
            ?.takeIf { it.width.toLong() * it.height <= MAX_SCREENSHOT_PIXELS }
        screenshot = usable?.let { runCatching { it.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() }
    }

    /** 一次会话结束时的临时状态：圈选与截图都不跨会话保留。 */
    private fun resetTransientState() {
        selectionState.value = null
        recycleScreenshot()
        measuredContentHeightPx = 0
    }

    private fun recycleScreenshot() {
        screenshot?.recycle()
        screenshot = null
    }

    /**
     * Compose 测量出的内容高度：窗口高度以此为唯一来源。
     *
     * 去重后再 `setLayout`，避免同一高度的重复回调触发无意义的窗口重排
     * （每次 setLayout 都会让系统重新测量，重复调用会造成可见抖动）。
     */
    private fun applyMeasuredHeight(heightPx: Int) {
        if (heightPx <= 0 || heightPx == measuredContentHeightPx) return
        if (selectionState.value != null) {
            // 圈选期间窗口固定全屏：此时的高度回调只记录，等退出圈选再应用。
            measuredContentHeightPx = heightPx
            return
        }
        measuredContentHeightPx = heightPx
        applyWindowLayout()
    }

    private fun applyWindowLayout() {
        val dialogWindow = window?.window ?: return
        if (selectionState.value != null) {
            // 圈选态：临时铺满屏幕，让系统截图按真实比例显示。
            dialogWindow.setGravity(Gravity.TOP or Gravity.START)
            dialogWindow.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            return
        }
        // 常规态：整宽贴底，高度取界面测量值；还没有测量结果时先给一个最小高度，
        // 等第一帧 onSizeChanged 到达就立即校正（不会把窗口停在 0 高）。
        dialogWindow.setGravity(Gravity.BOTTOM)
        dialogWindow.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            measuredContentHeightPx.coerceAtLeast(MIN_WINDOW_HEIGHT_PX),
        )
    }

    /** 屏幕圈选：把 window 临时铺满全屏，让系统截图按真实比例显示。 */
    private fun beginScreenSelection() {
        val source = screenshot
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
        if (openAssistantMainApp(context)) {
            hide()
        } else {
            client?.showAdvisory("无法打开 App，请从启动器打开")
        }
    }

    private companion object {
        /** 首帧测量到达前的最小窗口高度：保证输入栏可见，测量后立即校正。 */
        const val MIN_WINDOW_HEIGHT_PX = 1

        /** 超过约 400 万像素的截图不接收：内存与裁剪成本都不值得。 */
        const val MAX_SCREENSHOT_PIXELS = 4_000_000L

        /** 本地缩略图存储只属于一次会话；同一会话内重建组合沿用同一个实例。 */
        const val SESSION_PREVIEWS_KEY = "assistant-session"
    }
}
