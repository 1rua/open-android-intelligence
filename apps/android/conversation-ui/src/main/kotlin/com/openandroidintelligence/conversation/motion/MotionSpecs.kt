package com.openandroidintelligence.conversation.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlin.math.abs

/**
 * 全 App 唯一动效令牌。
 *
 * 结构性形变（尺寸、位置）用无过冲弹簧，避免容器抖动；手势释放时保留瞬时初速度；
 * 容器进入/离开与展开/折叠统一使用 M3 的 Emphasized 曲线。
 * 界面禁止自己手写时长与曲线，一律走这里的令牌。
 */
object MotionSpecs {
    const val Fast = 150
    const val Standard = 300
    const val Enter = 400
    const val Exit = 200
    const val Emphasized = 500
    const val ReducedMotionCrossfadeDuration = Fast

    /** M3 Emphasized：进入/展开的主曲线（先缓启动、后平滑减速）。 */
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    /** M3 Emphasized Decelerate：元素进入屏幕时使用。 */
    val EmphasizedDecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    /** M3 Emphasized Accelerate：元素离开屏幕时使用。 */
    val EmphasizedAccelerateEasing = CubicBezierEasing(0.3f, 0.0f, 1.0f, 0.0f)

    val MomentumSpring = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = 400f)
    val SheetSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 400f)
    val ReducedMotionSpec = tween<Float>(Fast, easing = LinearEasing)

    /** 结构性形变（尺寸、位置）：无过冲弹簧。 */
    fun <T> spatial(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 400f)

    /** 淡入淡出：减少动态时退化为极短淡化。 */
    fun <T> fade(reduced: Boolean): FiniteAnimationSpec<T> =
        tween(if (reduced) Fast else Exit, easing = LinearEasing)

    /** 展开/折叠等容器高度变化：Emphasized 曲线；减少动态时退化为短淡化。 */
    fun <T> emphasized(reduced: Boolean): FiniteAnimationSpec<T> =
        tween(if (reduced) ReducedMotionCrossfadeDuration else Emphasized, easing = if (reduced) LinearEasing else EmphasizedEasing)

    fun rubberband(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float {
        if (dimension <= 0f) return 0f
        return (overshoot * dimension * constant) / (dimension + constant * abs(overshoot))
    }

    fun project(initialVelocityPxPerSec: Float, decelerationRate: Float = 0.998f): Float =
        (initialVelocityPxPerSec / 1000f) * decelerationRate / (1f - decelerationRate)
}
