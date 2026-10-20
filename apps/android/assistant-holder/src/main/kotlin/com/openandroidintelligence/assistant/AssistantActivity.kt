package com.openandroidintelligence.assistant

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * 未设为默认数字助理时的 ASSIST 兜底入口。
 *
 * 这里不是"另一套界面"：它用同一个 Compose 宿主、同一份桥接状态与同一批组件，
 * 只是外壳换成 App 内的 `AssistantSurface` 全屏页。系统截图只由默认助理会话提供，
 * 因此本入口的圈选入口只给出明确说明，不伪造截图能力。
 */
class AssistantActivity : Activity() {
    private var host: AssistantComposeHost? = null
    private var client: AssistantBridgeClient? = null

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
        showFor(intent)
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

    private fun showFor(intent: Intent?) {
        releaseUi()
        val bridge = AssistantBridgeClient(this).also { client = it }
        val composeHost = AssistantComposeHost(this).also { host = it }
        val initialText = intent
            ?.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.toString()
            ?.take(MAX_ASSISTANT_DRAFT_CHARS)
        composeHost.setContent {
            AssistantSessionContent(
                client = bridge,
                mode = AssistantHostMode.ASSIST_ACTIVITY,
                // 兜底页没有"收起成球"的上下文（背后没有其他 App），始终展开。
                expanded = true,
                onExpandedChange = {},
                onClose = { finish() },
                onOpenApp = {
                    if (openAssistantMainApp(this)) finish()
                },
                onStartSelection = {
                    bridge.showAdvisory(
                        "当前入口没有系统截图。把本 App 设为默认数字助理后，才能在其他 App 之上圈选屏幕。",
                    )
                },
                initialText = initialText,
            )
        }
        setContentView(composeHost)
        // 必须在 setContentView 之后：PhoneWindow.getInsetsController() 会去取 DecorView，
        // 装饰视图还没创建时它内部是 null，提前调用会直接 NPE（真机已复现）。
        applySystemBarAppearance()
        composeHost.resume()
        bridge.connect()
    }

    private fun releaseUi() {
        client?.dispose()
        client = null
        host?.destroy()
        host = null
    }

    /** 全屏页跟随系统深浅色：浅色纸面上用深色状态栏图标，避免看不见系统图标。 */
    private fun applySystemBarAppearance() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (night) 0 else mask, mask)
    }
}
