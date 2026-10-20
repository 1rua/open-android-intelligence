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
import androidx.compose.runtime.mutableStateOf
import kotlin.math.roundToInt

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistantSession(this)
}

/**
 * 系统助理会话：把 App 内的 M3 助理界面搬到"其他 App 之上"。
 *
 * 生命周期与窗口策略：
 * - `onShow` 新建 Compose 宿主并绑定主进程桥接，`onHide` 立即 dispose 桥接、销毁组合
 *   （组合策略是 `DisposeOnDetachedFromWindow`，窗口移除后不残留协程与界面）；
 * - 停靠态窗口缩成 72dp 圆球并贴右下（长按可换到左侧），展开态窗口精确等于卡片尺寸，
 *   窗口背景透明、圆角与卡片由 Compose 绘制；
 * - 圈选态临时占满屏幕，让系统截图按真实比例铺满后再确认裁剪。
 *
 * 这里不做任何"只在 App 内可用"的假设：所有数据仍来自主进程桥接，
 * 没有连接就显示未连接引导。
 */
internal class AssistantSession(service: AssistantSessionService) : VoiceInteractionSession(service) {
    private var host: AssistantComposeHost? = null
    private var client: AssistantBridgeClient? = null
    private var screenshot: Bitmap? = null

    private var docked = true
    private var dockOnLeft = false
    private var selecting = false

    /** Compose 读取的会话状态；写入只在主线程回调里发生。 */
    private val expandedState = mutableStateOf(false)
    private val selectionState = mutableStateOf<AssistantScreenSelection?>(null)

    override fun onCreateContentView(): View {
        val bridge = AssistantBridgeClient(context).also { client = it }
        val composeHost = AssistantComposeHost(context)
        host = composeHost
        // 窗口本身不画背景：圆角卡片与卡片外的透明区域都由 Compose 决定。
        window?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        composeHost.setContent {
            AssistantSessionContent(
                client = bridge,
                mode = AssistantHostMode.VOICE_SESSION,
                expanded = expandedState.value,
                onExpandedChange = { setDocked(!it) },
                onClose = { hide() },
                onOpenApp = { openMainApp() },
                onDock = { setDocked(true) },
                onMoveDockEdge = { toggleDockEdge() },
                onStartSelection = { beginScreenSelection() },
                selection = selectionState.value,
                onFinishSelection = { finishScreenSelection() },
            )
        }
        return composeHost
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (client == null) {
            // 上一次 onHide 已经释放桥接，界面组合也随之解绑；整体重建一套干净的宿主。
            host?.destroy()
            host = null
            setContentView(onCreateContentView())
            setDocked(false)
        }
        host?.resume()
        client?.connect()
    }

    override fun onHide() {
        // 隐藏即会话结束：桥接立刻释放，宿主退回 CREATED，组合随视图解绑被回收。
        client?.dispose()
        client = null
        host?.stop()
        resetTransientState()
        super.onHide()
    }

    override fun onDestroy() {
        client?.dispose()
        client = null
        resetTransientState()
        host?.destroy()
        host = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转/分屏会改变可用尺寸：重算窗口，避免卡片按旧宽度停在屏幕外。
        applyWindowLayout()
    }

    override fun onBackPressed() {
        when {
            selecting -> finishScreenSelection()
            !docked -> setDocked(true)
            else -> super.onBackPressed()
        }
    }

    override fun onHandleScreenshot(bitmap: Bitmap?) {
        // 系统每次唤起最多给一张截图；新的一张取代旧的，旧图立即释放。
        recycleScreenshot()
        if (selecting && selectionState.value != null) {
            selecting = false
            selectionState.value = null
        }
        val usable = bitmap?.takeIf { it.width > 0 && it.height > 0 }
            ?.takeIf { it.width.toLong() * it.height <= MAX_SCREENSHOT_PIXELS }
        screenshot = usable?.let { runCatching { it.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() }
        applyWindowLayout()
    }

    /** 一次会话结束时的临时状态：圈选、截图与窗口形态都不跨会话保留。 */
    private fun resetTransientState() {
        selecting = false
        selectionState.value = null
        recycleScreenshot()
        docked = true
        expandedState.value = false
    }

    private fun recycleScreenshot() {
        screenshot?.recycle()
        screenshot = null
    }

    /**
     * 展开/收起：切换 Compose 状态与窗口尺寸。
     *
     * 窗口边界由系统窗口管理器决定，无法逐帧插值，所以"连续"体现在内容层的入场动画上；
     * 卡片本身的形变由 `AssistantSurface` 在兜底页承担。
     */
    private fun setDocked(value: Boolean) {
        if (value && selecting) {
            finishScreenSelection()
        }
        docked = value
        expandedState.value = !value
        applyWindowLayout()
    }

    /** 停靠球长按换边：只改窗口 gravity，不重建界面。 */
    private fun toggleDockEdge() {
        dockOnLeft = !dockOnLeft
        applyWindowLayout()
    }

    private fun applyWindowLayout() {
        val dialogWindow = window?.window ?: return
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        fun dp(value: Int): Int = (value * density).roundToInt()

        when {
            selecting -> {
                dialogWindow.setGravity(Gravity.TOP or Gravity.START)
                dialogWindow.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }

            docked -> {
                dialogWindow.setGravity(
                    (if (dockOnLeft) Gravity.START else Gravity.END) or Gravity.BOTTOM,
                )
                dialogWindow.setLayout(dp(DOCKED_WINDOW_DP), dp(DOCKED_WINDOW_DP))
            }

            else -> {
                dialogWindow.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                dialogWindow.setLayout(expandedWidth(metrics.widthPixels, density), expandedHeight(metrics.heightPixels))
            }
        }
    }

    private fun expandedWidth(screenWidthPx: Int, density: Float): Int {
        val horizontalMargin = (EXPANDED_SIDE_MARGIN_DP * density).roundToInt() * 2
        val maxWidth = (MAX_EXPANDED_WIDTH_DP * density).roundToInt()
        return (screenWidthPx - horizontalMargin).coerceIn(1, maxWidth)
    }

    private fun expandedHeight(screenHeightPx: Int): Int =
        (screenHeightPx * EXPANDED_SCREEN_FRACTION).roundToInt().coerceAtLeast(1)

    /** 屏幕圈选：把 window 临时铺满全屏，让系统截图按真实比例显示。 */
    private fun beginScreenSelection() {
        val source = screenshot
        if (source == null || source.isRecycled) {
            client?.showAdvisory("系统没有提供截图，请重新唤起助理后再圈选")
            return
        }
        if (docked) {
            // 圈选只在展开态有意义：先把窗口恢复成卡片，再进入全屏圈选。
            docked = false
            expandedState.value = true
        }
        selecting = true
        selectionState.value = AssistantScreenSelection(source)
        applyWindowLayout()
    }

    private fun finishScreenSelection() {
        if (!selecting) return
        selecting = false
        selectionState.value = null
        // 截图保留到下一次唤起或会话结束，方便用户取消后重新圈选。
        applyWindowLayout()
    }

    private fun openMainApp() {
        if (openAssistantMainApp(context)) {
            hide()
        } else {
            client?.showAdvisory("无法打开 App，请从启动器打开")
        }
    }

    private companion object {
        const val DOCKED_WINDOW_DP = 72
        const val EXPANDED_SIDE_MARGIN_DP = 16
        const val MAX_EXPANDED_WIDTH_DP = 760
        /** 展开态高度上限：不超过可用窗口高度的 60%（规格 §9.1）。 */
        const val EXPANDED_SCREEN_FRACTION = 0.6f
        /** 超过约 400 万像素的截图不接收：内存与裁剪成本都不值得。 */
        const val MAX_SCREENSHOT_PIXELS = 4_000_000L
    }
}
