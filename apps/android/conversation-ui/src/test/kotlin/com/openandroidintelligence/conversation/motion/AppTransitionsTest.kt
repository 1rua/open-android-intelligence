package com.openandroidintelligence.conversation.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.theme.Dimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转场动画与图标规范守卫测试。
 *
 * 确保遵循 Material 3 与 rikka-hub 等紧凑设计规范：
 * 1. 各种转场在标准模式与 reduceMotion 模式下均正确生成 Transition 对象；
 * 2. reduceMotion 模式与标准模式产生不同的动效契约（无位移缩放，降级为温和淡化）；
 * 3. 紧凑图标与容器令牌尺寸保持规范约束（32dp/18dp/48dp）。
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
    fun exitTransitionsCoordinatedWithExitSpec() {
        listOf(
            AppTransitions.exit(reduced = false),
            AppTransitions.navExit(reduced = false),
            AppTransitions.navPopExit(reduced = false),
            AppTransitions.modalExit(reduced = false),
        ).forEach { exitTransition ->
            val dataField = exitTransition.javaClass.getDeclaredField("data").apply { isAccessible = true }
            val data = dataField.get(exitTransition)
            val dataClass = data.javaClass
            val fade = dataClass.getDeclaredField("fade").apply { isAccessible = true }.get(data)
            val slide = dataClass.getDeclaredField("slide").apply { isAccessible = true }.get(data)
            val scale = dataClass.getDeclaredField("scale").apply { isAccessible = true }.get(data)

            assertNotNull("fade must not be null", fade)
            assertNotNull("slide must not be null", slide)
            assertNotNull("scale must not be null", scale)

            fun extractDuration(specOwner: Any): Int {
                val spec = specOwner.javaClass.getDeclaredField("animationSpec").apply { isAccessible = true }.get(specOwner)
                val duration = spec.javaClass.getDeclaredField("durationMillis").apply { isAccessible = true }.get(spec)
                return duration as Int
            }

            assertEquals("Fade spec duration should match MotionSpecs.Exit", MotionSpecs.Exit, extractDuration(fade!!))
            assertEquals("Slide spec duration should match MotionSpecs.Exit", MotionSpecs.Exit, extractDuration(slide!!))
            assertEquals("Scale spec duration should match MotionSpecs.Exit", MotionSpecs.Exit, extractDuration(scale!!))
        }
    }

    @Test
    fun enterTransitionsCoordinatedWithEnterSpec() {
        listOf(
            AppTransitions.enter(reduced = false),
            AppTransitions.navEnter(reduced = false),
            AppTransitions.navPopEnter(reduced = false),
            AppTransitions.modalEnter(reduced = false),
        ).forEach { enterTransition ->
            val dataField = enterTransition.javaClass.getDeclaredField("data").apply { isAccessible = true }
            val data = dataField.get(enterTransition)
            val dataClass = data.javaClass
            val fade = dataClass.getDeclaredField("fade").apply { isAccessible = true }.get(data)
            val slide = dataClass.getDeclaredField("slide").apply { isAccessible = true }.get(data)
            val scale = dataClass.getDeclaredField("scale").apply { isAccessible = true }.get(data)

            assertNotNull("fade must not be null", fade)
            assertNotNull("slide must not be null", slide)
            assertNotNull("scale must not be null", scale)

            fun extractDuration(specOwner: Any): Int {
                val spec = specOwner.javaClass.getDeclaredField("animationSpec").apply { isAccessible = true }.get(specOwner)
                val duration = spec.javaClass.getDeclaredField("durationMillis").apply { isAccessible = true }.get(spec)
                return duration as Int
            }

            assertEquals("Fade spec duration should match MotionSpecs.Enter", MotionSpecs.Enter, extractDuration(fade!!))
            assertEquals("Slide spec duration should match MotionSpecs.Enter", MotionSpecs.Enter, extractDuration(slide!!))
            assertEquals("Scale spec duration should match MotionSpecs.Enter", MotionSpecs.Enter, extractDuration(scale!!))
        }
    }
}

