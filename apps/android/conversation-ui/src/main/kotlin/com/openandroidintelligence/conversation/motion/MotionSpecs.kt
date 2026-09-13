package com.openandroidintelligence.conversation.motion

import androidx.compose.animation.core.*
import kotlin.math.abs

/** 全 App 唯一动效令牌；结构用无过冲弹簧，释放吸附保留速度。 */
object MotionSpecs {
    const val Fast = 150
    const val Standard = 300
    const val Enter = 400
    const val Exit = 200
    const val Emphasized = 500
    const val ReducedMotionCrossfadeDuration = Fast
    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val StandardSpring = spring<Float>(dampingRatio = 1f, stiffness = 400f)
    val MomentumSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 400f)
    val SheetSpring = spring<Float>(dampingRatio = 1f, stiffness = 400f)
    val ReducedMotionSpec = tween<Float>(Fast, easing = LinearEasing)

    fun <T> spatial(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 400f)
    fun <T> fade(reduced: Boolean): FiniteAnimationSpec<T> =
        tween(if (reduced) Fast else Exit, easing = LinearEasing)

    fun rubberband(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float {
        if (dimension <= 0f) return 0f
        return (overshoot * dimension * constant) / (dimension + constant * abs(overshoot))
    }
    fun project(initialVelocityPxPerSec: Float, decelerationRate: Float = 0.998f): Float =
        (initialVelocityPxPerSec / 1000f) * decelerationRate / (1f - decelerationRate)
}
