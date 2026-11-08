package com.openandroidintelligence.assistant

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * 未设为默认数字助理时的 ASSIST 兜底入口。
 *
 * 这里不是"另一套界面"：它和系统助理会话渲染同一棵 [AssistantSessionRoot]，只是外壳
 * 换成全屏透明 Activity。系统截图只由默认助理会话提供，因此本入口的圈选入口只给出
 * 明确说明，不伪造截图能力。
 */
class AssistantActivity : Activity() {
    private var host: AssistantComposeHost? = null
    private var client: AssistantBridgeClient? = null
    private var previews: AssistantLocalPreviews? = null
    private var controller: AssistantSessionUiController? = null

    /** 初值文本：只用于首帧种子，用户改过之后不再回写。 */
    private var initialText: String? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // 与主 App 一致地铺满系统栏：助理表面自己按 inset 内缩，不会压到状态栏/手势条。
        // API 35 起系统强制 edge-to-edge，这个调用在新系统上是幂等的空操作。
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        // 兜底入口同样要"浮在别的 App 之上"：窗口透明 + 系统遮罩，
        // 背景由 Compose 只画一层半透明 scrim，用户仍能看见原来的界面。
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.55f)
        showFor(intent, state)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) showFor(intent)
    }

    override fun onStart() {
        super.onStart()
        host?.resume()
    }

    override fun onStop() {
        host?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        releaseUi()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 旋转/进程回收后保留初值文本：它是用户可见内容，不能悄悄丢。
        initialText?.let { outState.putString(STATE_INITIAL_TEXT, it) }
    }

    private fun showFor(intent: Intent?, savedInstanceState: Bundle? = null) {
        releaseUi()
        val bridge = AssistantBridgeClient(this).also { client = it }
        val composeHost = AssistantComposeHost(this).also { host = it }
        val localPreviews = AssistantLocalPreviews().also { previews = it }
        val seed = intent
            ?.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.toString()
            ?.take(MAX_ASSISTANT_DRAFT_CHARS)
            ?: savedInstanceState?.getString(STATE_INITIAL_TEXT)
        initialText = seed
        // 兜底页没有系统唤起，直接给"展开会话"：进来就能看到对话并输入。
        val sessionUi = AssistantSessionUiController(
            AssistantUiState.Visible(surface = AssistantSurfaceState.EXPANDED_CONVERSATION),
        ).also { controller = it }
        composeHost.setContent {
            AssistantSessionContent(
                client = bridge,
                controller = sessionUi,
                previews = localPreviews,
                initialText = seed,
                onStartSelection = {
                    bridge.showAdvisory(
                        "当前入口没有系统截图。把本 App 设为默认数字助理后，才能在其他 App 之上圈选屏幕。",
                    )
                },
                onOpenApp = {
                    if (openAssistantMainApp(this)) finish()
                },
                // 返回键语义与系统会话一致：结束这个入口。
                onExit = { finish() },
                // 兜底页不是系统唤起：保持传入的"展开会话"初值。
                resetOnEntry = false,
                onResolvedAppearance = { dark -> applySystemBarAppearance(dark) },
            )
        }
        setContentView(composeHost)
        // 必须在 setContentView 之后：PhoneWindow.getInsetsController() 会去取 DecorView，
        // 装饰视图还没创建时它内部是 null，提前调用会直接 NPE（真机已复现）。
        // 先用兜底偏好设一次，桥接状态到达后由内容层的回调按真实主题覆盖。
        applySystemBarAppearance(readFallbackAppearance(this).darkTheme(this))
        composeHost.resume()
        bridge.connect()
    }

    private fun releaseUi() {
        previews?.close()
        previews = null
        client?.dispose()
        client = null
        host?.destroy()
        host = null
        controller = null
    }

    /**
     * 系统栏图标明暗必须跟**最终渲染的深浅色**一致。
     *
     * 用户可能在 App 里强制了与系统相反的深浅色：只看系统 night 模式会出现
     * 「浅色助理表面 + 浅色状态栏图标」这类不可读组合，所以由内容层把解析结果回调过来。
     */
    private fun applySystemBarAppearance(darkTheme: Boolean) {
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (darkTheme) 0 else mask, mask)
    }

    private companion object {
        const val STATE_INITIAL_TEXT = "assistant_initial_text"
    }
}
