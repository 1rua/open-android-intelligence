package com.openandroidintelligence.conversation.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset

/**
 * 页面级与组件级转场令牌。
 *
 * 遵循 Material 3 Expressive 弹簧物理系统与 Shared Axis 设计规范：
 * - 非减少动态下，进场位移与缩放使用 [MotionSpecs.spatialDefault]，透明度使用临界阻尼
 *   [MotionSpecs.effectsDefault]；退场位移与缩放使用更轻快的 [MotionSpecs.spatialFast]，
 *   透明度使用 [MotionSpecs.effectsFast]，保证退场节奏快于进场且支持任意时刻手势打断；
 * - 用户开启「减少动态」或系统动画时长缩放为 0 时统一降级为极短的无位移交叉淡化。
 */
object AppTransitions {

    /**
     * 基础页面前进/后退进入过渡（例如登录页 ↔ 工作台）。
     */
    fun enter(reduced: Boolean, forward: Boolean = true): EnterTransition =
        sharedAxisEnter(
            reduced = reduced,
            initialOffsetX = { full -> (if (forward) 1 else -1) * full / 6 },
            initialScale = 0.95f,
        )

    /**
     * 基础页面前进/后退退出过渡。
     */
    fun exit(reduced: Boolean, forward: Boolean = true): ExitTransition =
        sharedAxisExit(
            reduced = reduced,
            targetOffsetX = { full -> (if (forward) -1 else 1) * full / 6 },
            targetScale = 0.96f,
        )

    /**
     * NavHost 路由推进进入动画（打开子页面）：右侧滑入 + 临界阻尼淡入 + 微缩放展开。
     */
    fun navEnter(reduced: Boolean): EnterTransition =
        sharedAxisEnter(
            reduced = reduced,
            initialOffsetX = { full -> (full * 0.22f).toInt() },
            initialScale = 0.94f,
        )

    /**
     * NavHost 路由推进退出动画（当前页面退至背景）：左侧轻微位移 + 快速淡出 + 略微缩小。
     */
    fun navExit(reduced: Boolean): ExitTransition =
        sharedAxisExit(
            reduced = reduced,
            targetOffsetX = { full -> (-full * 0.15f).toInt() },
            targetScale = 0.96f,
        )

    /**
     * NavHost 路由出栈返回进入动画（上一层页面恢复到前台）：从左侧滑回 + 柔和淡入 + 缩放复原。
     */
    fun navPopEnter(reduced: Boolean): EnterTransition =
        sharedAxisEnter(
            reduced = reduced,
            initialOffsetX = { full -> (-full * 0.15f).toInt() },
            initialScale = 0.96f,
        )

    /**
     * NavHost 路由出栈返回退出动画（当前子页面向右滑出并关闭）。
     */
    fun navPopExit(reduced: Boolean): ExitTransition =
        sharedAxisExit(
            reduced = reduced,
            targetOffsetX = { full -> (full * 0.22f).toInt() },
            targetScale = 0.94f,
        )

    /**
     * 模态层/全屏设置面板打开动画：从左向右（+X）滑入。
     * 起点：translationX = -full（屏幕左侧外部）
     * 终点：translationX = 0
     */
    fun modalEnter(reduced: Boolean): EnterTransition = when {
        reduced -> fadeIn(MotionSpecs.fade(true))
        else -> slideInHorizontally(
            animationSpec = MotionSpecs.spatialDefault(
                reduced = false,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            ),
            initialOffsetX = { full -> -full },
        ) + fadeIn(
            animationSpec = MotionSpecs.effectsDefault(false),
            initialAlpha = 0.0f,
        )
    }

    /**
     * 模态层/全屏设置面板关闭动画：向左（-X）滑出屏幕。
     * 起点：translationX = 0
     * 终点：translationX = -full（屏幕左侧外部）
     */
    fun modalExit(reduced: Boolean): ExitTransition = when {
        reduced -> fadeOut(MotionSpecs.fade(true))
        else -> slideOutHorizontally(
            animationSpec = MotionSpecs.spatialFast(
                reduced = false,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            ),
            targetOffsetX = { full -> -full },
        ) + fadeOut(
            animationSpec = MotionSpecs.effectsFast(false),
            targetAlpha = 0.0f,
        )
    }

    private fun sharedAxisEnter(
        reduced: Boolean,
        initialOffsetX: (fullWidth: Int) -> Int,
        initialScale: Float,
    ): EnterTransition = when {
        reduced -> fadeIn(MotionSpecs.fade(true))
        else -> slideInHorizontally(
            animationSpec = MotionSpecs.spatialDefault(
                reduced = false,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            ),
            initialOffsetX = initialOffsetX,
        ) + fadeIn(
            animationSpec = MotionSpecs.effectsDefault(false),
            initialAlpha = 0.0f,
        ) + scaleIn(
            animationSpec = MotionSpecs.spatialDefault(false),
            initialScale = initialScale,
        )
    }

    private fun sharedAxisExit(
        reduced: Boolean,
        targetOffsetX: (fullWidth: Int) -> Int,
        targetScale: Float,
    ): ExitTransition = when {
        reduced -> fadeOut(MotionSpecs.fade(true))
        else -> slideOutHorizontally(
            animationSpec = MotionSpecs.spatialFast(
                reduced = false,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            ),
            targetOffsetX = targetOffsetX,
        ) + fadeOut(
            animationSpec = MotionSpecs.effectsFast(false),
            targetAlpha = 0.0f,
        ) + scaleOut(
            animationSpec = MotionSpecs.spatialFast(false),
            targetScale = targetScale,
        )
    }
}
