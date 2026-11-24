package com.openandroidintelligence.conversation.motion

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import com.openandroidintelligence.ui.design.MotionPolicy
import com.openandroidintelligence.ui.design.isReduced
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 物理动效系统行为契约测试。
 *
 * 验证弹簧物理特性、橡皮筋阻尼、惯性投影衰减与 Reduce Motion 降级行为，
 * 不对具体刚度/阻尼/缩放字面常量做脆弱硬编码绑定，以便在不破坏契约的前提下自由调优曲线手感。
 */
class MotionPhysicsTest {
    @Test
    fun rubberbandResistsProgressivelyAndHandlesBoundaries() {
        val smallOvershoot = MotionSpecs.rubberband(100f, 1000f)
        val largeOvershoot = MotionSpecs.rubberband(500f, 1000f)
        val negativeOvershoot = MotionSpecs.rubberband(-100f, 1000f)

        assertTrue(smallOvershoot > 0f && smallOvershoot < 100f)
        assertTrue(largeOvershoot > smallOvershoot && largeOvershoot < 500f)
        assertTrue(smallOvershoot / 100f > largeOvershoot / 500f, "Resistance must increase with overshoot")
        assertEquals(-smallOvershoot, negativeOvershoot, 0.0001f)
        assertEquals(0f, MotionSpecs.rubberband(100f, 0f))
        assertEquals(0f, MotionSpecs.rubberband(100f, -10f))
    }

    @Test
    fun momentumProjectionDecaysWithLowerDecelerationRateAndScalesWithVelocity() {
        val slowVelocity = MotionSpecs.project(1000f)
        val fastVelocity = MotionSpecs.project(2000f)
        val backwardVelocity = MotionSpecs.project(-1000f)
        val higherFriction = MotionSpecs.project(1000f, decelerationRate = 0.990f)
        val lowerFriction = MotionSpecs.project(1000f, decelerationRate = 0.998f)

        assertTrue(slowVelocity > 0f)
        assertTrue(fastVelocity > slowVelocity, "Higher initial velocity must project further")
        assertTrue(backwardVelocity < 0f, "Negative velocity must project backward")
        assertEquals(0f, MotionSpecs.project(0f))
        assertTrue(higherFriction < lowerFriction, "Lower decelerationRate must decay faster and project shorter")
        assertTrue(abs(slowVelocity + backwardVelocity) < 0.0001f)
    }

    @Test
    fun motionPolicyIsReducedCoversSwitchAndDurationScale() {
        assertFalse(MotionPolicy(reduceMotion = false, durationScale = 1f).isReduced)
        assertFalse(MotionPolicy(reduceMotion = false, durationScale = 0.5f).isReduced)
        assertTrue(MotionPolicy(reduceMotion = true, durationScale = 1f).isReduced)
        assertTrue(MotionPolicy(reduceMotion = false, durationScale = 0f).isReduced)
        assertTrue(MotionPolicy(reduceMotion = false, durationScale = -0.5f).isReduced)
    }

    @Test
    fun springTokensMaintainRelativePacingAndDampingInvariants() {
        // 各家族内部必须满足 Fast > Default > Slow 的刚度递增序
        assertTrue(MotionSpecs.SpatialFastSpring.stiffness > MotionSpecs.SpatialDefaultSpring.stiffness)
        assertTrue(MotionSpecs.SpatialDefaultSpring.stiffness > MotionSpecs.SpatialSlowSpring.stiffness)

        assertTrue(MotionSpecs.ExpressiveSpatialFastSpring.stiffness > MotionSpecs.ExpressiveSpatialDefaultSpring.stiffness)
        assertTrue(MotionSpecs.ExpressiveSpatialDefaultSpring.stiffness > MotionSpecs.ExpressiveSpatialSlowSpring.stiffness)

        assertTrue(MotionSpecs.EffectsFastSpring.stiffness > MotionSpecs.EffectsDefaultSpring.stiffness)
        assertTrue(MotionSpecs.EffectsDefaultSpring.stiffness > MotionSpecs.EffectsSlowSpring.stiffness)

        // Expressive 空间弹簧具备更柔和的阻尼弹性，Standard 保持更收敛
        assertTrue(MotionSpecs.ExpressiveSpatialDefaultSpring.dampingRatio in 0.1f..1.0f)
        assertTrue(MotionSpecs.ExpressiveSpatialDefaultSpring.dampingRatio < MotionSpecs.SpatialDefaultSpring.dampingRatio)

        // 模态退场平稳弹簧较进场略快而不及极速瞬移，且保持高阻尼收敛
        assertTrue(MotionSpecs.ModalExitSpring.dampingRatio >= 0.85f)
        assertTrue(MotionSpecs.ModalExitSpring.stiffness > MotionSpecs.ExpressiveSpatialDefaultSpring.stiffness)
        assertTrue(MotionSpecs.ModalExitSpring.stiffness < MotionSpecs.SpatialFastSpring.stiffness)

        // Effects 与参与布局测量的弹簧必须为临界阻尼（>= 1.0f），防止闪烁或布局抖动
        listOf(
            MotionSpecs.EffectsFastSpring,
            MotionSpecs.EffectsDefaultSpring,
            MotionSpecs.EffectsSlowSpring,
            MotionSpecs.MorphSpring,
            MotionSpecs.ExpandSpring,
            MotionSpecs.CollapseSpring,
            MotionSpecs.SheetSpring,
        ).forEach { spring ->
            assertTrue(spring.stiffness > 0f)
            assertTrue(
                spring.dampingRatio >= Spring.DampingRatioNoBouncy,
                "Effects/layout spring must be critically damped to avoid overshoot",
            )
        }

        // 按压缩放处于合理收缩区间，紧凑控件缩放不大于常规控件
        assertTrue(MotionSpecs.PressScaleDefault in 0.8f..0.999f)
        assertTrue(MotionSpecs.PressScaleCompact in 0.8f..0.999f)
        assertTrue(MotionSpecs.PressScaleCompact <= MotionSpecs.PressScaleDefault)
    }

    @Test
    fun motionFactoriesHonorThresholdsExpressiveIntentAndReducedFallbacks() {
        val fastStandard = assertIs<SpringSpec<Dp>>(
            MotionSpecs.spatialFast(reduced = false, expressive = false, visibilityThreshold = Dp.VisibilityThreshold),
        )
        assertEquals(Dp.VisibilityThreshold, fastStandard.visibilityThreshold)

        val defaultExpressive = assertIs<SpringSpec<Rect>>(
            MotionSpecs.spatialDefault(reduced = false, expressive = true, visibilityThreshold = Rect.VisibilityThreshold),
        )
        assertEquals(Rect.VisibilityThreshold, defaultExpressive.visibilityThreshold)
        val defaultStandard = assertIs<SpringSpec<Float>>(
            MotionSpecs.spatialDefault<Float>(reduced = false, expressive = false),
        )
        assertTrue(defaultExpressive.dampingRatio < defaultStandard.dampingRatio)

        assertIs<SpringSpec<Float>>(MotionSpecs.spatialSlow<Float>(reduced = false, expressive = true))
        assertIs<SpringSpec<Float>>(MotionSpecs.modalExit<Float>(reduced = false))

        // 非减少动态下的 Effects 工厂必须临界阻尼
        listOf(
            MotionSpecs.effectsFast<Float>(reduced = false),
            MotionSpecs.effectsDefault<Float>(reduced = false),
            MotionSpecs.effectsSlow<Float>(reduced = false),
            MotionSpecs.fade<Float>(reduced = false),
        ).forEach { spec ->
            val spring = assertIs<SpringSpec<Float>>(spec)
            assertTrue(spring.dampingRatio >= Spring.DampingRatioNoBouncy)
        }

        // 减少动态下：空间/形变/按压全部退化为 SnapSpec
        listOf(
            MotionSpecs.spatialFast<Float>(reduced = true),
            MotionSpecs.spatialDefault<Float>(reduced = true, expressive = true),
            MotionSpecs.spatialSlow<Float>(reduced = true, expressive = true),
            MotionSpecs.modalExit<Float>(reduced = true),
            MotionSpecs.spatial<Float>(reduced = true),
            MotionSpecs.emphasized<Float>(reduced = true),
            MotionSpecs.morph<Float>(reduced = true),
            MotionSpecs.snappy<Float>(reduced = true),
            MotionSpecs.entrance<Float>(reduced = true),
            MotionSpecs.expand<Float>(reduced = true),
            MotionSpecs.collapse<Float>(reduced = true),
            MotionSpecs.press<Float>(reduced = true),
        ).forEach { spec ->
            assertIs<SnapSpec<Float>>(spec)
        }

        // 减少动态下：Effects 与淡入淡出退化为线性 150ms TweenSpec
        listOf(
            MotionSpecs.effectsFast<Float>(reduced = true),
            MotionSpecs.effectsDefault<Float>(reduced = true),
            MotionSpecs.effectsSlow<Float>(reduced = true),
            MotionSpecs.reducedFade<Float>(),
            MotionSpecs.fade<Float>(reduced = true),
        ).forEach { spec ->
            val tween = assertIs<TweenSpec<Float>>(spec)
            assertEquals(MotionSpecs.ReducedMotionCrossfadeDuration, tween.durationMillis)
            assertEquals(LinearEasing, tween.easing)
        }
    }

    @Test
    fun staggerDelayAndEnterRespectBoundsAndReducedMotion() {
        assertEquals(0, MotionSpecs.staggerDelayMillis(index = -1, reduced = false))
        assertTrue(MotionSpecs.staggerDelayMillis(2, reduced = false) >= MotionSpecs.staggerDelayMillis(1, reduced = false))
        assertEquals(MotionSpecs.StaggerMax, MotionSpecs.staggerDelayMillis(100, reduced = false))
        assertEquals(0, MotionSpecs.staggerDelayMillis(5, reduced = true))

        val reducedEnter = assertIs<TweenSpec<Float>>(MotionSpecs.staggerEnter<Float>(index = 3, reduced = true))
        assertEquals(0, reducedEnter.delay)
        assertEquals(LinearEasing, reducedEnter.easing)
    }
}
