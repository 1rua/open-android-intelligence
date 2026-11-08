package com.openandroidintelligence.conversation.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlin.math.abs
import kotlin.math.min

/**
 * 全 App 唯一动效令牌。
 *
 * 三条原则：
 * 1. **结构性形变（尺寸、位置）用无过冲弹簧**：容器不能在布局变化时抖动；手势释放时
 *    保留瞬时初速度（[MomentumSpring] / [SnappySpring]）。
 * 2. **进入/离开与展开/折叠默认使用 M3 Emphasized 曲线**：不是每个组件自己选一条缓动；
 *    只有需要「弹出」手感的浮层进场（如助理输入栏）才用 [EntranceSpring]，且只作用于
 *    `graphicsLayer`，不参与布局测量。
 * 3. **界面禁止手写时长与曲线**：一律引用这里的常量，减少动态通过 `reduced` 分支降级。
 *
 * 时长刻度（对齐 M3 motion tokens）：
 * `Instant(90)` 按压反馈 · `Fast(150)` 交叉淡化/小构件状态 · `Standard(250)` 主题与
 * 中构件 · `Exit(200)`/`Enter(400)` 页面转场 · `Emphasized(500)` 大容器展开。
 */
object MotionSpecs {
    // ===== 时长 =====
    /** 90ms：指针按下即反馈，松手立即回收。 */
    const val Instant = 90
    /** 150ms：图标交换、小构件状态切换。 */
    const val Fast = 150
    /** 250ms：主题换色、中等容器形变。 */
    const val Standard = 250
    /** 400ms：页面进入。 */
    const val Enter = 400
    /** 200ms：页面离开。离开必须比进入快，用户才不会觉得"卡住"。 */
    const val Exit = 200
    /** 500ms：大容器展开（助理栏、底部弹层内容）。 */
    const val Emphasized = 500
    /** 列表错峰的单步延迟。 */
    const val StaggerStep = 40
    /** 列表错峰的延迟上限，避免长列表末尾等太久。 */
    const val StaggerMax = 240
    const val ReducedMotionCrossfadeDuration = Fast

    // ===== 缓动 =====
    /** M3 Emphasized：进入/展开的主曲线（先缓启动、后平滑减速）。 */
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    /** M3 Emphasized Decelerate：元素进入屏幕时使用。 */
    val EmphasizedDecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    /** M3 Emphasized Accelerate：元素离开屏幕时使用。 */
    val EmphasizedAccelerateEasing = CubicBezierEasing(0.3f, 0.0f, 1.0f, 0.0f)
    /** M3 Standard：状态之间的中性过渡（主题换色、色调变化）。 */
    val StandardEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    /** M3 Linear Out Slow In：按压等「进入」反馈。 */
    val LinearOutSlowInEasing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    /** M3 Fast Out Slow In：尺寸变化。 */
    val FastOutSlowInEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    // ===== 弹簧 =====
    /** 手势释放 / 拖拽吸附：保留初速度，允许轻微过冲。 */
    val MomentumSpring = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = 400f)
    /** 底部弹层 / 抽屉的过渡。 */
    val SheetSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 400f)
    /** 默认空间形变：接近临界阻尼，几乎不弹。 */
    val SpatialSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 380f)
    /** 容器形变（助理栏 ↔ 停靠球、附件条高度）：临界阻尼，绝不回弹。 */
    val MorphSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 520f)
    /** 轻快吸附（按钮宽度、图标位移）：响应更快但同样不过冲。 */
    val SnappySpring = spring<Float>(dampingRatio = 0.85f, stiffness = 900f)
    /**
     * 助理输入栏进场：带轻微过冲的弹出弹簧。
     *
     * 只用于 `graphicsLayer` 的位移/缩放/透明度，不参与布局测量，因此过冲不会让
     * 容器抖动；0.72 的阻尼足以形成「弹到位」的手感，又不会来回晃。
     */
    val EntranceSpring = spring<Float>(dampingRatio = 0.72f, stiffness = 380f)
    /** 输入栏展开成输入态：柔和、无过冲，让卡片「长高」而不跳动。 */
    val ExpandSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 360f)
    /** 输入栏收起：与展开同路径但更快，用户不会觉得「卡住」。 */
    val CollapseSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 800f)
    val ReducedMotionSpec = tween<Float>(Fast, easing = LinearEasing)

    /** 结构性形变（尺寸、位置）：无过冲弹簧。 */
    fun <T> spatial(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 400f)

    /** 淡入淡出：减少动态时退化为极短淡化。 */
    fun <T> fade(reduced: Boolean): FiniteAnimationSpec<T> =
        tween(if (reduced) Fast else Exit, easing = LinearEasing)

    /** 展开/折叠等容器高度变化：Emphasized 曲线；减少动态时退化为短淡化。 */
    fun <T> emphasized(reduced: Boolean): FiniteAnimationSpec<T> =
        tween(
            if (reduced) ReducedMotionCrossfadeDuration else Emphasized,
            easing = if (reduced) LinearEasing else EmphasizedEasing,
        )

    /** 容器形变：临界阻尼弹簧；减少动态直接落到目标值。 */
    fun <T> morph(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 520f)

    /** 轻快吸附：减少动态直接落到目标值。 */
    fun <T> snappy(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.85f, stiffness = 900f)

    /**
     * 进场：带轻微过冲的非线性弹簧；减少动态时直接落位，不做位移与缩放。
     *
     * 只应作用在 `graphicsLayer` 的位移/缩放/透明度上 —— 过冲若参与布局测量会让
     * 容器抖动。
     */
    fun <T> entrance(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) {
            snap()
        } else {
            spring(
                dampingRatio = EntranceSpring.dampingRatio,
                stiffness = EntranceSpring.stiffness,
            )
        }

    /** 展开成输入态：柔和、无过冲的尺寸过渡；减少动态直接落位。 */
    fun <T> expand(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) {
            snap()
        } else {
            spring(dampingRatio = ExpandSpring.dampingRatio, stiffness = ExpandSpring.stiffness)
        }

    /** 收起：与展开同路径但更快；减少动态直接落位。 */
    fun <T> collapse(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) {
            snap()
        } else {
            spring(dampingRatio = CollapseSpring.dampingRatio, stiffness = CollapseSpring.stiffness)
        }

    /** 按压反馈：90ms 收放；减少动态时不做缩放。 */
    fun <T> press(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(Instant, easing = LinearOutSlowInEasing)

    /** 列表错峰：第 [index] 项的延迟时长（毫秒）。 */
    fun staggerDelayMillis(index: Int, reduced: Boolean): Int =
        if (reduced) 0 else min(index.coerceAtLeast(0) * StaggerStep, StaggerMax)

    /**
     * 列表项进入：淡入 + 上浮。延迟按 [index] 错峰，而不是所有项一起出现。
     * 减少动态时只做一次短淡化。
     */
    fun <T> staggerEnter(index: Int, reduced: Boolean): FiniteAnimationSpec<T> = tween(
        durationMillis = if (reduced) ReducedMotionCrossfadeDuration else Fast + StaggerStep,
        delayMillis = staggerDelayMillis(index, reduced),
        easing = if (reduced) LinearEasing else EmphasizedDecelerateEasing,
    )

    fun rubberband(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float {
        if (dimension <= 0f) return 0f
        return (overshoot * dimension * constant) / (dimension + constant * abs(overshoot))
    }

    fun project(initialVelocityPxPerSec: Float, decelerationRate: Float = 0.998f): Float =
        (initialVelocityPxPerSec / 1000f) * decelerationRate / (1f - decelerationRate)
}
