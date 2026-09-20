package com.openandroidintelligence.conversation.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically

/**
 * 页面级与组件级转场令牌。
 *
 * 遵循 Material 3 Shared Axis 与 Google Jetpack Compose 等成熟开源 UI 库的设计风格：
 * - 页面前进导航时采用「右侧位移 + 渐进淡入 + 柔和缩放」，退出时采用「左侧轻微位移 + 淡出 + 略微缩小」；
 * - 页面回退导航时采用反向镜像动效；
 * - 模态对话框与全屏设置/助理采用「底部优雅上浮 + 弹性缩放 + 材质渐变」打开动效，彻底告别单调生硬的简单渐入渐出；
 * - 用户开启「减少动态」无障碍选项时统一降级为极短的无位移交叉淡化。
 */
object AppTransitions {

    /**
     * 基础页面前进/后退进入过渡（例如登录页 ↔ 工作台）。
     */
    fun enter(reduced: Boolean, forward: Boolean = true): EnterTransition = when {
        reduced -> fadeIn(MotionSpecs.fade(reduced))
        else -> slideInHorizontally(
            animationSpec = tween(MotionSpecs.Enter, easing = MotionSpecs.EmphasizedDecelerateEasing),
            initialOffsetX = { full -> (if (forward) 1 else -1) * full / 6 },
        ) + fadeIn(
            animationSpec = tween(MotionSpecs.Enter, easing = MotionSpecs.EmphasizedDecelerateEasing),
            initialAlpha = 0.0f,
        ) + scaleIn(
            animationSpec = tween(MotionSpecs.Enter, easing = MotionSpecs.EmphasizedDecelerateEasing),
            initialScale = 0.95f,
        )
    }

    /**
     * 基础页面前进/后退退出过渡。
     */
    fun exit(reduced: Boolean, forward: Boolean = true): ExitTransition = when {
        reduced -> fadeOut(MotionSpecs.fade(reduced))
        else -> slideOutHorizontally(
            animationSpec = tween(MotionSpecs.Exit, easing = MotionSpecs.EmphasizedAccelerateEasing),
            targetOffsetX = { full -> (if (forward) -1 else 1) * full / 6 },
        ) + fadeOut(
            animationSpec = tween(MotionSpecs.Exit, easing = MotionSpecs.EmphasizedAccelerateEasing),
            targetAlpha = 0.0f,
        ) + scaleOut(
            animationSpec = tween(MotionSpecs.Exit, easing = MotionSpecs.EmphasizedAccelerateEasing),
            targetScale = 0.96f,
        )
    }

    /**
     * NavHost 路由推进进入动画（打开子页面）：右侧滑入 + 优雅淡入 + 微缩放展开。
     */
    fun navEnter(reduced: Boolean): EnterTransition = when {
        reduced -> fadeIn(MotionSpecs.fade(reduced))
        else -> slideInHorizontally(
            animationSpec = tween(MotionSpecs.Enter, easing = MotionSpecs.EmphasizedDecelerateEasing),
            initialOffsetX = { full -> (full * 0.22f).toInt() },
        ) + fadeIn(
            animationSpec = tween(MotionSpecs.Enter, easing = MotionSpecs.EmphasizedDecelerateEasing),
            initialAlpha = 0.0f,
        ) + scaleIn(
            animationSpec = tween(MotionSpecs.Enter, easing = MotionSpecs.EmphasizedDecelerateEasing),
            initialScale = 0.94f,
        )
    }

    /**
     * NavHost 路由推进退出动画（当前页面退至背景）：左侧轻微位移 + 渐近淡出 + 略微缩小。
     */
    fun navExit(reduced: Boolean): ExitTransition = when {
        reduced -> fadeOut(MotionSpecs.fade(reduced))
        else -> slideOutHorizontally(
            animationSpec = tween(MotionSpecs.Exit, easing = MotionSpecs.EmphasizedAccelerateEasing),
            targetOffsetX = { full -> (-full * 0.15f).toInt() },
        ) + fadeOut(
            animationSpec = tween(MotionSpecs.Exit, easing = MotionSpecs.EmphasizedAccelerateEasing),
            targetAlpha = 0.0f,
        ) + scaleOut(
            animationSpec = tween(MotionSpecs.Exit, easing = MotionSpecs.EmphasizedAccelerateEasing),
            targetScale = 0.96f,
        )
    }

    /**
     * NavHost 路由出栈返回进入动画（上一层页面恢复到前台）：从左侧滑回 + 柔和淡入 + 缩放复原。
     */
    fun navPopEnter(reduced: Boolean): EnterTransition = when {
        reduced -> fadeIn(MotionSpecs.fade(reduced))
        else -> slideInHorizontally(
            animationSpec = tween(MotionSpecs.Enter, easing = MotionSpecs.EmphasizedDecelerateEasing),
            initialOffsetX = { full -> (-full * 0.15f).toInt() },
        ) + fadeIn(
            animationSpec = tween(MotionSpecs.Enter, easing = MotionSpecs.EmphasizedDecelerateEasing),
            initialAlpha = 0.0f,
        ) + scaleIn(
            animationSpec = tween(MotionSpecs.Enter, easing = MotionSpecs.EmphasizedDecelerateEasing),
            initialScale = 0.96f,
        )
    }

    /**
     * NavHost 路由出栈返回退出动画（当前子页面向右滑出并关闭）。
     */
    fun navPopExit(reduced: Boolean): ExitTransition = when {
        reduced -> fadeOut(MotionSpecs.fade(reduced))
        else -> slideOutHorizontally(
            animationSpec = tween(MotionSpecs.Exit, easing = MotionSpecs.EmphasizedAccelerateEasing),
            targetOffsetX = { full -> (full * 0.22f).toInt() },
        ) + fadeOut(
            animationSpec = tween(MotionSpecs.Exit, easing = MotionSpecs.EmphasizedAccelerateEasing),
            targetAlpha = 0.0f,
        ) + scaleOut(
            animationSpec = tween(MotionSpecs.Exit, easing = MotionSpecs.EmphasizedAccelerateEasing),
            targetScale = 0.94f,
        )
    }

    /**
     * 模态层/全屏设置面板打开动画：从底部柔和上浮 + 弹性微缩放 + 材质渐变展开。
     */
    fun modalEnter(reduced: Boolean): EnterTransition = when {
        reduced -> fadeIn(MotionSpecs.fade(reduced))
        else -> slideInVertically(
            animationSpec = tween(MotionSpecs.Enter, easing = MotionSpecs.EmphasizedDecelerateEasing),
            initialOffsetY = { full -> (full * 0.14f).toInt() },
        ) + fadeIn(
            animationSpec = tween(MotionSpecs.Enter, easing = MotionSpecs.EmphasizedDecelerateEasing),
            initialAlpha = 0.0f,
        ) + scaleIn(
            animationSpec = tween(MotionSpecs.Enter, easing = MotionSpecs.EmphasizedDecelerateEasing),
            initialScale = 0.95f,
        )
    }

    /**
     * 模态层/全屏设置面板关闭动画：平滑下沉 + 淡出 + 紧凑收起。
     */
    fun modalExit(reduced: Boolean): ExitTransition = when {
        reduced -> fadeOut(MotionSpecs.fade(reduced))
        else -> slideOutVertically(
            animationSpec = tween(MotionSpecs.Exit, easing = MotionSpecs.EmphasizedAccelerateEasing),
            targetOffsetY = { full -> (full * 0.10f).toInt() },
        ) + fadeOut(
            animationSpec = tween(MotionSpecs.Exit, easing = MotionSpecs.EmphasizedAccelerateEasing),
            targetAlpha = 0.0f,
        ) + scaleOut(
            animationSpec = tween(MotionSpecs.Exit, easing = MotionSpecs.EmphasizedAccelerateEasing),
            targetScale = 0.96f,
        )
    }
}
