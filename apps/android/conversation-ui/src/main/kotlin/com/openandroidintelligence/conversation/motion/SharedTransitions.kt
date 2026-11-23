@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.openandroidintelligence.conversation.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.isReduced

/** 共享元素过渡宿主作用域；未挂载宿主时返回 null，Modifier 退化为安全无操作。 */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** 当前可见性/转场子树对应的 [AnimatedVisibilityScope]，供叶子节点安全读取。 */
val LocalAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * 全 App 共享元素稳定 Key 命名空间。
 *
 * 所有 Key 均采用英文静态前缀，避免多语言文案变更或字符串碰撞引发错误配对。
 */
object SharedTransitionKeys {
    fun rootSurface(id: String = "workspace"): String = "root_surface:$id"

    fun loginCard(id: String = "form"): String = "login_card:$id"

    fun settingsCategoryCard(route: String): String = "settings_category_card:$route"

    fun composerContainer(): String = "composer_container"

    fun composerAction(id: String = "primary"): String = "composer_action:$id"

    fun expandableCard(id: String): String = "expandable_card:$id"

    fun morphingButton(id: String): String = "morphing_button:$id"
}

/**
 * 提供共享元素容器并向子树注入 [LocalSharedTransitionScope]。
 */
@Composable
fun AppSharedTransitionHost(
    modifier: Modifier = Modifier,
    content: @Composable SharedTransitionScope.() -> Unit,
) {
    SharedTransitionLayout(modifier = modifier) {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            content()
        }
    }
}

/**
 * 确保当前子树存在 [SharedTransitionScope]；若上层已提供则复用，避免重复嵌套隔离作用域。
 */
@Composable
fun EnsureSharedTransitionHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (LocalSharedTransitionScope.current != null) {
        content()
    } else {
        AppSharedTransitionHost(modifier = modifier) {
            content()
        }
    }
}

/**
 * 将当前 [AnimatedVisibilityScope]（含 `AnimatedContentScope` 与 `NavHost` 目标页）
 * 暴露给深层子组件，用于绑定 [m3SharedBounds] / [m3SharedElement]。
 */
@Composable
fun AnimatedVisibilityScope.ProvideSharedVisibilityScope(
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
        content()
    }
}

/**
 * 为共享边界/共享元素生成 M3 弹簧物理 [BoundsTransform]。
 *
 * 默认采用 Expressive 空间弹簧并配置 [Rect.VisibilityThreshold]，在保持弹性质感的同时
 * 确保亚像素级收敛；开启减少动态时退化为 `snap()`。
 */
fun MotionSpecs.boundsTransform(
    reduced: Boolean,
    expressive: Boolean = true,
): BoundsTransform = BoundsTransform { _, _ ->
    spatialDefault(
        reduced = reduced,
        expressive = expressive,
        visibilityThreshold = Rect.VisibilityThreshold,
    )
}

/**
 * 安全挂接 M3 共享边界（Container Transform）修饰符。
 *
 * 当用户开启减少动态，或当前未处于有效的共享宿主/可见性作用域内时，安全返回原 [Modifier]；
 * 传入 [clipShape] 时使用 `OverlayClip(clipShape)`，避免过渡叠加层出现直角闪现。
 */
@Composable
fun Modifier.m3SharedBounds(
    key: Any,
    sharedTransitionScope: SharedTransitionScope? = LocalSharedTransitionScope.current,
    animatedVisibilityScope: AnimatedVisibilityScope? = LocalAnimatedVisibilityScope.current,
    expressive: Boolean = true,
    resizeMode: SharedTransitionScope.ResizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(
        ContentScale.Fit,
        Alignment.Center,
    ),
    clipShape: Shape? = null,
    zIndexInOverlay: Float = 0f,
    renderInOverlayDuringTransition: Boolean = true,
): Modifier = applySharedTransition(
    key = key,
    sharedTransitionScope = sharedTransitionScope,
    animatedVisibilityScope = animatedVisibilityScope,
    expressive = expressive,
) { base, state, visibility, boundsSpec ->
    val enterTransition = fadeIn(MotionSpecs.effectsDefault(false))
    val exitTransition = fadeOut(MotionSpecs.effectsFast(false))
    if (clipShape != null) {
        base.sharedBounds(
            sharedContentState = state,
            animatedVisibilityScope = visibility,
            enter = enterTransition,
            exit = exitTransition,
            boundsTransform = boundsSpec,
            resizeMode = resizeMode,
            renderInOverlayDuringTransition = renderInOverlayDuringTransition,
            zIndexInOverlay = zIndexInOverlay,
            clipInOverlayDuringTransition = OverlayClip(clipShape),
        )
    } else {
        base.sharedBounds(
            sharedContentState = state,
            animatedVisibilityScope = visibility,
            enter = enterTransition,
            exit = exitTransition,
            boundsTransform = boundsSpec,
            resizeMode = resizeMode,
            renderInOverlayDuringTransition = renderInOverlayDuringTransition,
            zIndexInOverlay = zIndexInOverlay,
        )
    }
}

/**
 * 安全挂接 M3 共享元素（Shared Element）修饰符。
 *
 * 用于头像、图标、按钮等无需重测内容边界的共享实体；在减少动态或缺少作用域时安全退化。
 */
@Composable
fun Modifier.m3SharedElement(
    key: Any,
    sharedTransitionScope: SharedTransitionScope? = LocalSharedTransitionScope.current,
    animatedVisibilityScope: AnimatedVisibilityScope? = LocalAnimatedVisibilityScope.current,
    expressive: Boolean = true,
    clipShape: Shape? = null,
    zIndexInOverlay: Float = 0f,
    renderInOverlayDuringTransition: Boolean = true,
): Modifier = applySharedTransition(
    key = key,
    sharedTransitionScope = sharedTransitionScope,
    animatedVisibilityScope = animatedVisibilityScope,
    expressive = expressive,
) { base, state, visibility, boundsSpec ->
    if (clipShape != null) {
        base.sharedElement(
            state = state,
            animatedVisibilityScope = visibility,
            boundsTransform = boundsSpec,
            renderInOverlayDuringTransition = renderInOverlayDuringTransition,
            zIndexInOverlay = zIndexInOverlay,
            clipInOverlayDuringTransition = OverlayClip(clipShape),
        )
    } else {
        base.sharedElement(
            state = state,
            animatedVisibilityScope = visibility,
            boundsTransform = boundsSpec,
            renderInOverlayDuringTransition = renderInOverlayDuringTransition,
            zIndexInOverlay = zIndexInOverlay,
        )
    }
}

@Composable
private fun Modifier.applySharedTransition(
    key: Any,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    expressive: Boolean,
    apply: SharedTransitionScope.(
        base: Modifier,
        state: SharedTransitionScope.SharedContentState,
        visibility: AnimatedVisibilityScope,
        boundsSpec: BoundsTransform,
    ) -> Modifier,
): Modifier {
    val reduced = LocalMotionPolicy.current.isReduced
    if (reduced || sharedTransitionScope == null || animatedVisibilityScope == null) {
        return this
    }
    val boundsSpec = MotionSpecs.boundsTransform(reduced = false, expressive = expressive)
    return with(sharedTransitionScope) {
        val state = rememberSharedContentState(key = key)
        apply(this@applySharedTransition, state, animatedVisibilityScope, boundsSpec)
    }
}
