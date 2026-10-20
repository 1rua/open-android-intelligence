package com.openandroidintelligence.assistant

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 助理界面的轻量 Compose 宿主。
 *
 * `VoiceInteractionSession` 的 content view 和普通 Activity 都不是
 * `ComponentActivity`，没有现成的 `ViewTreeLifecycleOwner` / `ViewTreeSavedStateRegistryOwner`；
 * 直接把 ComposeView 塞进窗口会因为找不到 owner 而崩溃或无法使用 `rememberSaveable`。
 * 这里用最少的样板把两者补齐：宿主自己就是 LifecycleOwner 与 SavedStateRegistryOwner，
 * 并把它们挂到自身的 ViewTree 上，供 Compose 从父链向上查找。
 *
 * 生命周期由会话显式推进（`onShow` → RESUMED，`onHide` → CREATED，`onDestroy` → DESTROYED）：
 * 助理窗口的显示/隐藏不经过 Activity 的 onStart/onStop，宿主必须自己表达这个状态。
 * 组合本身用 [ViewCompositionStrategy.DisposeOnDetachedFromWindow]：窗口移除视图时立即
 * 释放整棵组合，不在进程里留下看不见的界面与协程。
 */
internal class AssistantComposeHost(context: Context) :
    FrameLayout(context),
    LifecycleOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val composeView = ComposeView(context)

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    init {
        // 先 attach/restore，再进入 CREATED：编排顺序与 ComponentActivity 一致，
        // 否则 rememberSaveable 在首次组合时会拿不到可用的注册表。
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        this.setViewTreeLifecycleOwner(this)
        this.setViewTreeSavedStateRegistryOwner(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    /** 安装界面；重复调用会替换旧内容（组合被回收）。 */
    fun setContent(content: @Composable () -> Unit) {
        composeView.setContent(content)
    }

    fun resume() {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun destroy() {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
