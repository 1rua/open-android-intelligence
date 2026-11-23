package com.openandroidintelligence.conversation.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.theme.Dimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转场动画与图标规范守卫测试。
 *
 * 确保遵循 Material 3 Expressive 物理动效与紧凑设计规范：
 * 1. 各种转场在标准模式与 reduceMotion 模式下均正确生成 Transition 对象；
 * 2. 标准模式下进场采用 SpatialDefault + EffectsDefault 弹簧，退场采用 SpatialFast + EffectsFast 弹簧；
 * 3. reduceMotion 模式下所有位移和缩放取消，统一降级为 150ms 线性淡化；
 * 4. 紧凑图标与容器令牌尺寸保持规范约束（32dp/18dp/48dp）。
 */
class AppTransitionsTest {

    @Test
    fun compactIconDimensionsConformToSpec() {
        assertEquals("列表前置图标容器直径应紧凑为 32dp", 32.dp, Dimensions.LeadingIconContainer)
        assertEquals("次级图标尺寸应紧凑为 18dp", 18.dp, Dimensions.SmallIcon)
        assertEquals("悬浮球直径应紧凑为 48dp", 48.dp, Dimensions.DockedBall)
        assertEquals("品牌标识高度应紧凑为 48dp", 48.dp, Dimensions.BrandMark)
        assertEquals("标准图标尺寸应为 24dp", 24.dp, Dimensions.Icon)
    }

    @Test
    fun baseTransitionsProduceValidTransitions() {
        val standardEnter = AppTransitions.enter(reduced = false, forward = true)
        val reducedEnter = AppTransitions.enter(reduced = true, forward = true)
        val reverseEnter = AppTransitions.enter(reduced = false, forward = false)
        assertNotNull(standardEnter)
        assertNotNull(reducedEnter)
        assertNotNull(reverseEnter)
        assertNotEquals(standardEnter, reducedEnter)
        assertNotEquals(standardEnter, reverseEnter)

        val standardExit = AppTransitions.exit(reduced = false, forward = true)
        val reducedExit = AppTransitions.exit(reduced = true, forward = true)
        val reverseExit = AppTransitions.exit(reduced = false, forward = false)
        assertNotNull(standardExit)
        assertNotNull(reducedExit)
        assertNotNull(reverseExit)
        assertNotEquals(standardExit, reducedExit)
        assertNotEquals(standardExit, reverseExit)
    }

    @Test
    fun navTransitionsProduceValidTransitions() {
        val standardNavEnter = AppTransitions.navEnter(reduced = false)
        val reducedNavEnter = AppTransitions.navEnter(reduced = true)
        assertNotNull(standardNavEnter)
        assertNotNull(reducedNavEnter)
        assertNotEquals(standardNavEnter, reducedNavEnter)

        val standardNavExit = AppTransitions.navExit(reduced = false)
        val reducedNavExit = AppTransitions.navExit(reduced = true)
        assertNotNull(standardNavExit)
        assertNotNull(reducedNavExit)
        assertNotEquals(standardNavExit, reducedNavExit)

        val standardNavPopEnter = AppTransitions.navPopEnter(reduced = false)
        val reducedNavPopEnter = AppTransitions.navPopEnter(reduced = true)
        assertNotNull(standardNavPopEnter)
        assertNotNull(reducedNavPopEnter)
        assertNotEquals(standardNavPopEnter, reducedNavPopEnter)

        val standardNavPopExit = AppTransitions.navPopExit(reduced = false)
        val reducedNavPopExit = AppTransitions.navPopExit(reduced = true)
        assertNotNull(standardNavPopExit)
        assertNotNull(reducedNavPopExit)
        assertNotEquals(standardNavPopExit, reducedNavPopExit)
    }

    @Test
    fun modalTransitionsProduceValidTransitions() {
        val standardModalEnter = AppTransitions.modalEnter(reduced = false)
        val reducedModalEnter = AppTransitions.modalEnter(reduced = true)
        assertNotNull(standardModalEnter)
        assertNotNull(reducedModalEnter)
        assertNotEquals(standardModalEnter, reducedModalEnter)

        val standardModalExit = AppTransitions.modalExit(reduced = false)
        val reducedModalExit = AppTransitions.modalExit(reduced = true)
        assertNotNull(standardModalExit)
        assertNotNull(reducedModalExit)
        assertNotEquals(standardModalExit, reducedModalExit)
    }

    @Test
    fun exitTransitionsCoordinatedWithSpringExitSpecs() {
        listOf(
            AppTransitions.exit(reduced = false) to true,
            AppTransitions.navExit(reduced = false) to true,
            AppTransitions.navPopExit(reduced = false) to true,
            AppTransitions.modalExit(reduced = false) to false,
        ).forEach { (exitTransition, expectsScale) ->
            val data = extractTransitionData(exitTransition)
            val fade = extractField(data, "fade")
            val slide = extractField(data, "slide")
            assertNotNull("fade must not be null", fade)
            assertNotNull("slide must not be null", slide)

            val fadeSpec = extractAnimationSpec(fade!!) as SpringSpec<*>
            assertEquals(MotionSpecs.EffectsFastSpring.dampingRatio, fadeSpec.dampingRatio, 0.0001f)
            assertEquals(MotionSpecs.EffectsFastSpring.stiffness, fadeSpec.stiffness, 0.0001f)

            val slideSpec = extractAnimationSpec(slide!!) as SpringSpec<*>
            assertEquals(MotionSpecs.SpatialFastSpring.dampingRatio, slideSpec.dampingRatio, 0.0001f)
            assertEquals(MotionSpecs.SpatialFastSpring.stiffness, slideSpec.stiffness, 0.0001f)

            val scale = extractField(data, "scale")
            if (expectsScale) {
                assertNotNull("scale must not be null", scale)
                val scaleSpec = extractAnimationSpec(scale!!) as SpringSpec<*>
                assertEquals(MotionSpecs.SpatialFastSpring.dampingRatio, scaleSpec.dampingRatio, 0.0001f)
                assertEquals(MotionSpecs.SpatialFastSpring.stiffness, scaleSpec.stiffness, 0.0001f)
            } else {
                assertNull("modalExit should not include scale", scale)
            }
        }
    }

    @Test
    fun enterTransitionsCoordinatedWithSpringEnterSpecs() {
        listOf(
            AppTransitions.enter(reduced = false) to true,
            AppTransitions.navEnter(reduced = false) to true,
            AppTransitions.navPopEnter(reduced = false) to true,
            AppTransitions.modalEnter(reduced = false) to false,
        ).forEach { (enterTransition, expectsScale) ->
            val data = extractTransitionData(enterTransition)
            val fade = extractField(data, "fade")
            val slide = extractField(data, "slide")
            assertNotNull("fade must not be null", fade)
            assertNotNull("slide must not be null", slide)

            val fadeSpec = extractAnimationSpec(fade!!) as SpringSpec<*>
            assertEquals(MotionSpecs.EffectsDefaultSpring.dampingRatio, fadeSpec.dampingRatio, 0.0001f)
            assertEquals(MotionSpecs.EffectsDefaultSpring.stiffness, fadeSpec.stiffness, 0.0001f)

            val slideSpec = extractAnimationSpec(slide!!) as SpringSpec<*>
            assertEquals(MotionSpecs.SpatialDefaultSpring.dampingRatio, slideSpec.dampingRatio, 0.0001f)
            assertEquals(MotionSpecs.SpatialDefaultSpring.stiffness, slideSpec.stiffness, 0.0001f)

            val scale = extractField(data, "scale")
            if (expectsScale) {
                assertNotNull("scale must not be null", scale)
                val scaleSpec = extractAnimationSpec(scale!!) as SpringSpec<*>
                assertEquals(MotionSpecs.SpatialDefaultSpring.dampingRatio, scaleSpec.dampingRatio, 0.0001f)
                assertEquals(MotionSpecs.SpatialDefaultSpring.stiffness, scaleSpec.stiffness, 0.0001f)
            } else {
                assertNull("modalEnter should not include scale", scale)
            }
        }
    }

    @Test
    fun reducedMotionTransitionsStripSlideAndScaleAndUseReducedFade() {
        val enterList = listOf(
            AppTransitions.enter(reduced = true),
            AppTransitions.navEnter(reduced = true),
            AppTransitions.navPopEnter(reduced = true),
            AppTransitions.modalEnter(reduced = true),
        )
        val exitList = listOf(
            AppTransitions.exit(reduced = true),
            AppTransitions.navExit(reduced = true),
            AppTransitions.navPopExit(reduced = true),
            AppTransitions.modalExit(reduced = true),
        )
        (enterList + exitList).forEach { transition ->
            val data = extractTransitionData(transition)
            assertNull("减少动态下不得包含 slide", extractField(data, "slide"))
            assertNull("减少动态下不得包含 scale", extractField(data, "scale"))
            val fade = extractField(data, "fade")
            assertNotNull("减少动态下仍应保留短淡化", fade)
            val spec = extractAnimationSpec(fade!!)
            assertTrue(spec is TweenSpec<*>)
            val tween = spec as TweenSpec<*>
            assertEquals(MotionSpecs.ReducedMotionCrossfadeDuration, tween.durationMillis)
            assertEquals(LinearEasing, tween.easing)
        }
    }

    private fun extractTransitionData(transition: Any): Any {
        assertTrue(transition is EnterTransition || transition is ExitTransition)
        return extractField(transition, "data")!!
    }

    private fun extractAnimationSpec(specOwner: Any): Any =
        extractField(specOwner, "animationSpec")!!

    private fun extractField(owner: Any, fieldName: String): Any? =
        owner.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(owner)
}
