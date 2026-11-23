package com.openandroidintelligence.conversation.motion

import androidx.compose.animation.core.CubicBezierEasing
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MotionPhysicsTest {
    @Test
    fun rubberbandResistsProgressively() {
        val smallOvershoot = MotionSpecs.rubberband(100f, 1000f)
        val largeOvershoot = MotionSpecs.rubberband(500f, 1000f)

        assertTrue(smallOvershoot < 100f)
        assertTrue(largeOvershoot < 500f)
        assertTrue(smallOvershoot / 100f > largeOvershoot / 500f, "Resistance must increase with overshoot")
    }

    @Test
    fun momentumProjectionProjectsForward() {
        val projected = MotionSpecs.project(1000f)
        assertTrue(projected > 0f)
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
    fun m3ExpressiveSpringConstantsAndEasingsMatchSpecification() {
        assertEquals(CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f), MotionSpecs.EmphasizedAccelerateEasing)

        // Standard Spatial
        assertEquals(0.9f, MotionSpecs.SpatialFastSpring.dampingRatio)
        assertEquals(1400f, MotionSpecs.SpatialFastSpring.stiffness)
        assertEquals(0.9f, MotionSpecs.SpatialDefaultSpring.dampingRatio)
        assertEquals(700f, MotionSpecs.SpatialDefaultSpring.stiffness)
        assertEquals(0.9f, MotionSpecs.SpatialSlowSpring.dampingRatio)
        assertEquals(300f, MotionSpecs.SpatialSlowSpring.stiffness)

        // Expressive Spatial
        assertEquals(0.6f, MotionSpecs.ExpressiveSpatialFastSpring.dampingRatio)
        assertEquals(800f, MotionSpecs.ExpressiveSpatialFastSpring.stiffness)
        assertEquals(0.8f, MotionSpecs.ExpressiveSpatialDefaultSpring.dampingRatio)
        assertEquals(380f, MotionSpecs.ExpressiveSpatialDefaultSpring.stiffness)
        assertEquals(0.8f, MotionSpecs.ExpressiveSpatialSlowSpring.dampingRatio)
        assertEquals(200f, MotionSpecs.ExpressiveSpatialSlowSpring.stiffness)

        // Effects（临界阻尼 1.0f 严禁过冲）
        assertEquals(Spring.DampingRatioNoBouncy, MotionSpecs.EffectsFastSpring.dampingRatio)
        assertEquals(3800f, MotionSpecs.EffectsFastSpring.stiffness)
        assertEquals(Spring.DampingRatioNoBouncy, MotionSpecs.EffectsDefaultSpring.dampingRatio)
        assertEquals(1600f, MotionSpecs.EffectsDefaultSpring.stiffness)
        assertEquals(Spring.DampingRatioNoBouncy, MotionSpecs.EffectsSlowSpring.dampingRatio)
        assertEquals(800f, MotionSpecs.EffectsSlowSpring.stiffness)

        // Press 与兼容别名
        assertEquals(0.75f, MotionSpecs.PressSpring.dampingRatio)
        assertEquals(1200f, MotionSpecs.PressSpring.stiffness)
        assertEquals(0.97f, MotionSpecs.PressScaleDefault)
        assertEquals(0.95f, MotionSpecs.PressScaleCompact)
        assertEquals(MotionSpecs.SpatialDefaultSpring, MotionSpecs.SpatialSpring)
        assertEquals(MotionSpecs.SpatialFastSpring, MotionSpecs.SnappySpring)
        assertEquals(Spring.DampingRatioNoBouncy, MotionSpecs.MorphSpring.dampingRatio)
        assertEquals(700f, MotionSpecs.MorphSpring.stiffness)
    }

    @Test
    fun spatialAndEffectsFactoriesHonorReducedAndExpressiveAndThreshold() {
        val fastStandard = assertIs<SpringSpec<Dp>>(
            MotionSpecs.spatialFast(reduced = false, expressive = false, visibilityThreshold = Dp.VisibilityThreshold),
        )
        assertEquals(0.9f, fastStandard.dampingRatio)
        assertEquals(1400f, fastStandard.stiffness)
        assertEquals(Dp.VisibilityThreshold, fastStandard.visibilityThreshold)

        val fastExpressive = assertIs<SpringSpec<Float>>(
            MotionSpecs.spatialFast<Float>(reduced = false, expressive = true),
        )
        assertEquals(0.6f, fastExpressive.dampingRatio)
        assertEquals(800f, fastExpressive.stiffness)

        val defaultExpressive = assertIs<SpringSpec<Rect>>(
            MotionSpecs.spatialDefault(reduced = false, expressive = true, visibilityThreshold = Rect.VisibilityThreshold),
        )
        assertEquals(0.8f, defaultExpressive.dampingRatio)
        assertEquals(380f, defaultExpressive.stiffness)
        assertEquals(Rect.VisibilityThreshold, defaultExpressive.visibilityThreshold)

        val slowExpressive = assertIs<SpringSpec<Float>>(
            MotionSpecs.spatialSlow<Float>(reduced = false, expressive = true),
        )
        assertEquals(0.8f, slowExpressive.dampingRatio)
        assertEquals(200f, slowExpressive.stiffness)

        assertIs<SnapSpec<Float>>(MotionSpecs.spatialFast<Float>(reduced = true))
        assertIs<SnapSpec<Float>>(MotionSpecs.spatialDefault<Float>(reduced = true, expressive = true))
        assertIs<SnapSpec<Float>>(MotionSpecs.spatialSlow<Float>(reduced = true, expressive = true))

        val effectsFast = assertIs<SpringSpec<Float>>(MotionSpecs.effectsFast<Float>(reduced = false))
        assertEquals(Spring.DampingRatioNoBouncy, effectsFast.dampingRatio)
        assertEquals(3800f, effectsFast.stiffness)

        val effectsDefault = assertIs<SpringSpec<Float>>(MotionSpecs.effectsDefault<Float>(reduced = false))
        assertEquals(Spring.DampingRatioNoBouncy, effectsDefault.dampingRatio)
        assertEquals(1600f, effectsDefault.stiffness)

        val effectsSlow = assertIs<SpringSpec<Float>>(MotionSpecs.effectsSlow<Float>(reduced = false))
        assertEquals(Spring.DampingRatioNoBouncy, effectsSlow.dampingRatio)
        assertEquals(800f, effectsSlow.stiffness)

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
    fun legacyFactoriesUpgradeToSpringsWithSafeReducedFallbacks() {
        val spatial = assertIs<SpringSpec<Float>>(MotionSpecs.spatial<Float>(reduced = false))
        assertEquals(MotionSpecs.SpatialDefaultSpring.dampingRatio, spatial.dampingRatio)
        assertEquals(MotionSpecs.SpatialDefaultSpring.stiffness, spatial.stiffness)
        assertIs<SnapSpec<Float>>(MotionSpecs.spatial<Float>(reduced = true))

        val fade = assertIs<SpringSpec<Float>>(MotionSpecs.fade<Float>(reduced = false))
        assertEquals(MotionSpecs.EffectsDefaultSpring.dampingRatio, fade.dampingRatio)
        assertEquals(MotionSpecs.EffectsDefaultSpring.stiffness, fade.stiffness)

        val emphasized = assertIs<SpringSpec<Float>>(MotionSpecs.emphasized<Float>(reduced = false))
        assertEquals(Spring.DampingRatioNoBouncy, emphasized.dampingRatio)
        assertEquals(700f, emphasized.stiffness)
        assertIs<SnapSpec<Float>>(MotionSpecs.emphasized<Float>(reduced = true))

        val morph = assertIs<SpringSpec<Float>>(MotionSpecs.morph<Float>(reduced = false))
        assertEquals(Spring.DampingRatioNoBouncy, morph.dampingRatio)
        assertEquals(700f, morph.stiffness)
        assertIs<SnapSpec<Float>>(MotionSpecs.morph<Float>(reduced = true))

        val snappy = assertIs<SpringSpec<Float>>(MotionSpecs.snappy<Float>(reduced = false))
        assertEquals(MotionSpecs.SpatialFastSpring.dampingRatio, snappy.dampingRatio)
        assertEquals(MotionSpecs.SpatialFastSpring.stiffness, snappy.stiffness)
        assertIs<SnapSpec<Float>>(MotionSpecs.snappy<Float>(reduced = true))

        val press = assertIs<SpringSpec<Float>>(MotionSpecs.press<Float>(reduced = false))
        assertEquals(MotionSpecs.PressSpring.dampingRatio, press.dampingRatio)
        assertEquals(MotionSpecs.PressSpring.stiffness, press.stiffness)
        assertIs<SnapSpec<Float>>(MotionSpecs.press<Float>(reduced = true))
    }
}
