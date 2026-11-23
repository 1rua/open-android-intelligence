package com.openandroidintelligence.conversation.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlin.math.abs
import kotlin.math.min

/**
 * 全 App 唯一动效令牌。
 *
 * 三条原则：
 * 1. **首选物理弹簧驱动（Spatial / Effects）**：
 *    - Spatial（位移、尺寸、缩放、圆角形变）使用 Standard 或 Expressive 空间弹簧；
 *      直接参与布局测量的尺寸变化优先采用无过冲或高阻尼弹簧，避免下方列表抖动。
 *    - Effects（透明度、颜色过渡）一律使用临界阻尼（`dampingRatio = 1.0f`），严禁过冲或闪烁。
 * 2. **固定节拍缓动严格对齐 M3 Easing & Duration Tokens**：仅用于不适用弹簧的时间驱动过渡
 *    或列表错峰延迟。
 * 3. **界面禁止手写时长、缓动或弹簧参数**：一律引用这里的常量与工厂方法，减少动态通过
 *    `reduced` 分支统一降级为 `snap()`（空间）或 150ms 线性淡化（效果）。
 */
object MotionSpecs {
    // ===== 时长 =====
    /** 90ms：按压反馈参考基准时长。 */
    const val Instant = 90
    /** 150ms：图标交换、小构件状态切换及减少动态交叉淡化基准时长。 */
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
    val EmphasizedAccelerateEasing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    /** M3 Standard：状态之间的中性过渡（主题换色、色调变化）。 */
    val StandardEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    /** M3 Linear Out Slow In：按压等「进入」反馈。 */
    val LinearOutSlowInEasing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    /** M3 Fast Out Slow In：尺寸变化。 */
    val FastOutSlowInEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    // ===== M3 Standard Spatial 弹簧（实用型，几乎无回弹） =====
    /** 快速空间变换：按钮圆角形变、开关滑块、小图标位移、页面退场。 */
    val SpatialFastSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 1400f)
    /** 默认空间变换：卡片展开/收起、常规页面转场、局部布局过渡。 */
    val SpatialDefaultSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 700f)
    /** 慢速空间变换：全屏级大容器展开或跨屏空间过渡。 */
    val SpatialSlowSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 300f)

    // ===== M3 Expressive Spatial 弹簧（表现型，带柔和弹性过冲） =====
    /** 表现型快速空间弹簧：微交互、选中指示、快捷操作弹开。 */
    val ExpressiveSpatialFastSpring = spring<Float>(dampingRatio = 0.6f, stiffness = 800f)
    /** 表现型默认空间弹簧：共享元素边界变换、引导层展开、手势吸附。 */
    val ExpressiveSpatialDefaultSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 380f)
    /** 表现型慢速空间弹簧：整屏沉浸式容器变换。 */
    val ExpressiveSpatialSlowSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 200f)

    // ===== M3 Effects 弹簧（临界阻尼 1.0f，严禁过冲） =====
    /** 快速效果弹簧：按压状态层、按钮颜色切换、快速淡出。 */
    val EffectsFastSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 3800f)
    /** 默认效果弹簧：页面内容淡入淡出、主题色平滑切换。 */
    val EffectsDefaultSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f)
    /** 慢速效果弹簧：背景遮罩（Scrim）、大面积内容显现。 */
    val EffectsSlowSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 800f)

    // ===== 按压反馈弹簧与缩放比例 =====
    /** 按压微缩放弹簧：紧实干脆，松手快速回弹。 */
    val PressSpring = spring<Float>(dampingRatio = 0.75f, stiffness = 1200f)
    /** 默认按压缩放比例（卡片、列表项、常规控件）。 */
    const val PressScaleDefault = 0.97f
    /** 紧凑按压缩放比例（按钮、图标按钮等紧凑触控目标）。 */
    const val PressScaleCompact = 0.95f

    // ===== 兼容既有常量（对齐 Standard / 临界阻尼参数） =====
    /** 手势释放 / 拖拽吸附：保留初速度，允许轻微过冲。 */
    val MomentumSpring = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = 400f)
    /** 底部弹层 / 抽屉的过渡。 */
    val SheetSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 400f)
    /** 默认空间形变别名，对齐 [SpatialDefaultSpring]。 */
    val SpatialSpring = SpatialDefaultSpring
    /** 容器形变（助理栏 ↔ 停靠球、附件条高度、设置项胶囊）：临界阻尼，绝不回弹。 */
    val MorphSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f)
    /** 轻快吸附（按钮宽度、图标位移），对齐 [SpatialFastSpring]。 */
    val SnappySpring = SpatialFastSpring
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
    /** 兼容保留的固定时长淡化常量；新代码请优先使用 [reducedFade]。 */
    @Deprecated(
        message = "请改用 MotionSpecs.reducedFade()",
        replaceWith = ReplaceWith("reducedFade()"),
    )
    val ReducedMotionSpec = tween<Float>(Fast, easing = LinearEasing)

    // ===== 泛型 Spring 工厂 =====

    /** 快速空间弹簧；减少动态时退化为 `snap()`。 */
    fun <T> spatialFast(
        reduced: Boolean,
        expressive: Boolean = false,
        visibilityThreshold: T? = null,
    ): FiniteAnimationSpec<T> = springSpecFrom(
        reduced = reduced,
        template = if (expressive) ExpressiveSpatialFastSpring else SpatialFastSpring,
        visibilityThreshold = visibilityThreshold,
    )

    /** 默认空间弹簧；减少动态时退化为 `snap()`。 */
    fun <T> spatialDefault(
        reduced: Boolean,
        expressive: Boolean = false,
        visibilityThreshold: T? = null,
    ): FiniteAnimationSpec<T> = springSpecFrom(
        reduced = reduced,
        template = if (expressive) ExpressiveSpatialDefaultSpring else SpatialDefaultSpring,
        visibilityThreshold = visibilityThreshold,
    )

    /** 慢速空间弹簧；减少动态时退化为 `snap()`。 */
    fun <T> spatialSlow(
        reduced: Boolean,
        expressive: Boolean = false,
        visibilityThreshold: T? = null,
    ): FiniteAnimationSpec<T> = springSpecFrom(
        reduced = reduced,
        template = if (expressive) ExpressiveSpatialSlowSpring else SpatialSlowSpring,
        visibilityThreshold = visibilityThreshold,
    )

    private fun <T> effectsSpecFrom(
        reduced: Boolean,
        template: SpringSpec<Float>,
        visibilityThreshold: T?,
    ): FiniteAnimationSpec<T> = if (reduced) {
        reducedFade()
    } else {
        springSpecFrom(
            reduced = false,
            template = template,
            visibilityThreshold = visibilityThreshold,
        )
    }

    /** 快速效果弹簧（临界阻尼 1.0f）；减少动态时退化为 150ms 线性淡化。 */
    fun <T> effectsFast(
        reduced: Boolean,
        visibilityThreshold: T? = null,
    ): FiniteAnimationSpec<T> = effectsSpecFrom(
        reduced = reduced,
        template = EffectsFastSpring,
        visibilityThreshold = visibilityThreshold,
    )

    /** 默认效果弹簧（临界阻尼 1.0f）；减少动态时退化为 150ms 线性淡化。 */
    fun <T> effectsDefault(
        reduced: Boolean,
        visibilityThreshold: T? = null,
    ): FiniteAnimationSpec<T> = effectsSpecFrom(
        reduced = reduced,
        template = EffectsDefaultSpring,
        visibilityThreshold = visibilityThreshold,
    )

    /** 慢速效果弹簧（临界阻尼 1.0f）；减少动态时退化为 150ms 线性淡化。 */
    fun <T> effectsSlow(
        reduced: Boolean,
        visibilityThreshold: T? = null,
    ): FiniteAnimationSpec<T> = effectsSpecFrom(
        reduced = reduced,
        template = EffectsSlowSpring,
        visibilityThreshold = visibilityThreshold,
    )

    /** 减少动态专用线性淡化规格（150ms LinearEasing）。 */
    fun <T> reducedFade(): FiniteAnimationSpec<T> =
        tween(ReducedMotionCrossfadeDuration, easing = LinearEasing)

    // ===== 既有工厂升级 =====

    /** 结构性形变（尺寸、位置）：对齐 [spatialDefault]。 */
    fun <T> spatial(reduced: Boolean): FiniteAnimationSpec<T> =
        spatialDefault(reduced, expressive = false)

    /** 淡入淡出：非减少动态使用临界阻尼 [effectsDefault]，减少动态退化为 150ms 线性淡化。 */
    fun <T> fade(reduced: Boolean): FiniteAnimationSpec<T> =
        effectsDefault(reduced)

    /** 展开/折叠等容器高度变化：临界阻尼不抖动，减少动态直接落位。 */
    fun <T> emphasized(reduced: Boolean): FiniteAnimationSpec<T> =
        morph(reduced)

    /** 容器形变：临界阻尼弹簧（700f）；减少动态直接落到目标值。 */
    fun <T> morph(reduced: Boolean): FiniteAnimationSpec<T> =
        springSpecFrom(reduced = reduced, template = MorphSpring, visibilityThreshold = null)

    /** 轻快吸附：对齐 [spatialFast]；减少动态直接落到目标值。 */
    fun <T> snappy(reduced: Boolean): FiniteAnimationSpec<T> =
        spatialFast(reduced, expressive = false)

    /**
     * 进场：带轻微过冲的非线性弹簧；减少动态时直接落位，不做位移与缩放。
     *
     * 只应作用在 `graphicsLayer` 的位移/缩放/透明度上 —— 过冲若参与布局测量会让
     * 容器抖动。
     */
    fun <T> entrance(reduced: Boolean): FiniteAnimationSpec<T> =
        springSpecFrom(reduced = reduced, template = EntranceSpring, visibilityThreshold = null)

    /** 展开成输入态：柔和、无过冲的尺寸过渡；减少动态直接落位。 */
    fun <T> expand(reduced: Boolean): FiniteAnimationSpec<T> =
        springSpecFrom(reduced = reduced, template = ExpandSpring, visibilityThreshold = null)

    /** 收起：与展开同路径但更快；减少动态直接落位。 */
    fun <T> collapse(reduced: Boolean): FiniteAnimationSpec<T> =
        springSpecFrom(reduced = reduced, template = CollapseSpring, visibilityThreshold = null)

    /** 按压反馈：物理弹簧微缩回弹；减少动态时直接落位不做缩放。 */
    fun <T> press(reduced: Boolean): FiniteAnimationSpec<T> =
        springSpecFrom(reduced = reduced, template = PressSpring, visibilityThreshold = null)

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

    private fun <T> springSpecFrom(
        reduced: Boolean,
        template: SpringSpec<Float>,
        visibilityThreshold: T?,
    ): FiniteAnimationSpec<T> = if (reduced) {
        snap()
    } else {
        spring(
            dampingRatio = template.dampingRatio,
            stiffness = template.stiffness,
            visibilityThreshold = visibilityThreshold,
        )
    }
}
