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
 * 确保遵循 Material 3 Expressive 分层视差动效与紧凑设计规范：
 * 1. 页面导航与模态转场（`nav*`/`modal*`）在标准模式下均不含全页淡化（`fade == null`），
 *    采用 100% 不透明分层视差位移与纵深缩放；
 * 2. 同级切换（`enter`/`exit`）保留 `Spatial` + `Effects` 弹簧组合；
 * 3. `reduceMotion = true` 时全部取消位移与缩放，统一降级为 150ms 线性交叉淡化；
 * 4. 视差常数与紧凑图标尺寸令牌保持规范约束。
 */
class AppTransitionsTest {

    @Test
    fun compactIconDimensionsAndParallaxTokensConformToSpec() {
        assertEquals("列表前置图标容器直径应紧凑为 32dp", 32.dp, Dimensions.LeadingIconContainer)
        assertEquals("次级图标尺寸应紧凑为 18dp", 18.dp, Dimensions.SmallIcon)
        assertEquals("悬浮球直径应紧凑为 48dp", 48.dp, Dimensions.DockedBall)
        assertEquals("品牌标识高度应紧凑为 48dp", 48.dp, Dimensions.BrandMark)
        assertEquals("标准图标尺寸应为 24dp", 24.dp, Dimensions.Icon)

        assertEquals(0.28f, AppTransitions.ParallaxOffsetFraction, 0.0001f)
        assertEquals(0.96f, AppTransitions.BackgroundDepthScale, 0.0001f)
        assertEquals(0.16f, AppTransitions.BackgroundScrimMaxAlpha, 0.0001f)
        assertEquals(0.24f, AppTransitions.EdgeShadowMaxAlpha, 0.0001f)
        assertEquals(20.dp, AppTransitions.EdgeShadowWidth)
        assertEquals(0.0005f, AppTransitions.ScaleVisibilityThreshold, 0.00001f)
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
        // 同级切换保留 fade + slide + scale
        val siblingData = extractTransitionData(AppTransitions.exit(reduced = false))
        val siblingFade = extractField(siblingData, "fade")
        val siblingSlide = extractField(siblingData, "slide")
        val siblingScale = extractField(siblingData, "scale")
        assertNotNull("enter/exit sibling fade must not be null", siblingFade)
        assertNotNull("enter/exit sibling slide must not be null", siblingSlide)
        assertNotNull("enter/exit sibling scale must not be null", siblingScale)

        val siblingFadeSpec = extractAnimationSpec(siblingFade!!) as SpringSpec<*>
        assertEquals(MotionSpecs.EffectsFastSpring.dampingRatio, siblingFadeSpec.dampingRatio, 0.0001f)
        assertEquals(MotionSpecs.EffectsFastSpring.stiffness, siblingFadeSpec.stiffness, 0.0001f)

        val siblingSlideSpec = extractAnimationSpec(siblingSlide!!) as SpringSpec<*>
        assertEquals(MotionSpecs.SpatialFastSpring.dampingRatio, siblingSlideSpec.dampingRatio, 0.0001f)
        assertEquals(MotionSpecs.SpatialFastSpring.stiffness, siblingSlideSpec.stiffness, 0.0001f)

        val siblingScaleSpec = extractAnimationSpec(siblingScale!!) as SpringSpec<*>
        assertEquals(MotionSpecs.SpatialFastSpring.dampingRatio, siblingScaleSpec.dampingRatio, 0.0001f)
        assertEquals(MotionSpecs.SpatialFastSpring.stiffness, siblingScaleSpec.stiffness, 0.0001f)

        // 分层导航与模态退出必须保持 100% 不透明（fade == null）
        listOf(
            AppTransitions.navExit(reduced = false) to true,
            AppTransitions.navPopExit(reduced = false) to false,
            AppTransitions.modalExit(reduced = false) to false,
        ).forEach { (exitTransition, expectsScale) ->
            val data = extractTransitionData(exitTransition)
            assertNull("分层导航/模态退出不得包含透明 fade", extractField(data, "fade"))
            val slide = extractField(data, "slide")
            assertNotNull("slide must not be null", slide)
            val slideSpec = extractAnimationSpec(slide!!) as SpringSpec<*>
            assertEquals(MotionSpecs.SpatialFastSpring.dampingRatio, slideSpec.dampingRatio, 0.0001f)
            assertEquals(MotionSpecs.SpatialFastSpring.stiffness, slideSpec.stiffness, 0.0001f)

            val scale = extractField(data, "scale")
            if (expectsScale) {
                assertNotNull("navExit 底层背景必须包含纵深缩放", scale)
                val scaleSpec = extractAnimationSpec(scale!!) as SpringSpec<*>
                assertEquals(MotionSpecs.SpatialFastSpring.dampingRatio, scaleSpec.dampingRatio, 0.0001f)
                assertEquals(MotionSpecs.SpatialFastSpring.stiffness, scaleSpec.stiffness, 0.0001f)
                assertEquals(AppTransitions.ScaleVisibilityThreshold, scaleSpec.visibilityThreshold)
            } else {
                assertNull("前景退出不得包含额外 scale", scale)
            }
        }
    }

    @Test
    fun enterTransitionsCoordinatedWithSpringEnterSpecs() {
        // 同级切换保留 fade + slide + scale
        val siblingData = extractTransitionData(AppTransitions.enter(reduced = false))
        val siblingFade = extractField(siblingData, "fade")
        val siblingSlide = extractField(siblingData, "slide")
        val siblingScale = extractField(siblingData, "scale")
        assertNotNull("enter sibling fade must not be null", siblingFade)
        assertNotNull("enter sibling slide must not be null", siblingSlide)
        assertNotNull("enter sibling scale must not be null", siblingScale)

        val siblingFadeSpec = extractAnimationSpec(siblingFade!!) as SpringSpec<*>
        assertEquals(MotionSpecs.EffectsDefaultSpring.dampingRatio, siblingFadeSpec.dampingRatio, 0.0001f)
        assertEquals(MotionSpecs.EffectsDefaultSpring.stiffness, siblingFadeSpec.stiffness, 0.0001f)

        val siblingSlideSpec = extractAnimationSpec(siblingSlide!!) as SpringSpec<*>
        assertEquals(MotionSpecs.SpatialDefaultSpring.dampingRatio, siblingSlideSpec.dampingRatio, 0.0001f)
        assertEquals(MotionSpecs.SpatialDefaultSpring.stiffness, siblingSlideSpec.stiffness, 0.0001f)

        val siblingScaleSpec = extractAnimationSpec(siblingScale!!) as SpringSpec<*>
        assertEquals(MotionSpecs.SpatialDefaultSpring.dampingRatio, siblingScaleSpec.dampingRatio, 0.0001f)
        assertEquals(MotionSpecs.SpatialDefaultSpring.stiffness, siblingScaleSpec.stiffness, 0.0001f)

        // 分层导航与模态进入必须保持 100% 不透明（fade == null）
        listOf(
            AppTransitions.navEnter(reduced = false) to false,
            AppTransitions.navPopEnter(reduced = false) to true,
            AppTransitions.modalEnter(reduced = false) to false,
        ).forEach { (enterTransition, expectsScale) ->
            val data = extractTransitionData(enterTransition)
            assertNull("分层导航/模态进入不得包含透明 fade", extractField(data, "fade"))
            val slide = extractField(data, "slide")
            assertNotNull("slide must not be null", slide)
            val slideSpec = extractAnimationSpec(slide!!) as SpringSpec<*>
            assertEquals(MotionSpecs.SpatialDefaultSpring.dampingRatio, slideSpec.dampingRatio, 0.0001f)
            assertEquals(MotionSpecs.SpatialDefaultSpring.stiffness, slideSpec.stiffness, 0.0001f)

            val scale = extractField(data, "scale")
            if (expectsScale) {
                assertNotNull("navPopEnter 底层背景必须包含纵深缩放复原", scale)
                val scaleSpec = extractAnimationSpec(scale!!) as SpringSpec<*>
                assertEquals(MotionSpecs.SpatialDefaultSpring.dampingRatio, scaleSpec.dampingRatio, 0.0001f)
                assertEquals(MotionSpecs.SpatialDefaultSpring.stiffness, scaleSpec.stiffness, 0.0001f)
                assertEquals(AppTransitions.ScaleVisibilityThreshold, scaleSpec.visibilityThreshold)
            } else {
                assertNull("前景进入不得包含额外 scale", scale)
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
