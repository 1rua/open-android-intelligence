package com.openandroidintelligence.conversation.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * 页面级转场令牌。
 *
 * 对应 Navigation-Compose 的 shared-axis Z 语义：前进时新页从右侧轻微滑入并淡入，
 * 旧页向左侧淡出；返回时反向。全部基于 tween + M3 Emphasized 曲线，
 * 不使用任何生硬的瞬间切换。用户开启「减少动态」时退化为短促交叉淡化。
 */
object AppTransitions {

    fun enter(reduced: Boolean, forward: Boolean = true): EnterTransition = when {
        reduced -> fadeIn(MotionSpecs.fade(reduced))
        else -> slideInHorizontally(
            animationSpec = tween(MotionSpecs.Enter, easing = MotionSpecs.EmphasizedDecelerateEasing),
            initialOffsetX = { full -> (if (forward) 1 else -1) * full / 6 },
        ) + fadeIn(
            animationSpec = tween(MotionSpecs.Enter, easing = MotionSpecs.EmphasizedDecelerateEasing),
            initialAlpha = 0.2f,
        )
    }

    fun exit(reduced: Boolean, forward: Boolean = true): ExitTransition = when {
        reduced -> fadeOut(MotionSpecs.fade(reduced))
        else -> slideOutHorizontally(
            animationSpec = tween(MotionSpecs.Exit, easing = MotionSpecs.EmphasizedAccelerateEasing),
            targetOffsetX = { full -> (if (forward) -1 else 1) * full / 6 },
        ) + fadeOut(
            animationSpec = tween(MotionSpecs.Exit, easing = MotionSpecs.EmphasizedAccelerateEasing),
            targetAlpha = 0.2f,
        )
    }
}
