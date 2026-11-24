package com.openandroidintelligence.conversation.motion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.theme.Dimensions
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 转场动画与图标规范守卫测试。
 *
 * 通过公开 Jetpack Compose API 与 `AnimatedContent` 帧级渲染契约验证 Material 3 Expressive
 * 分层视差动效与紧凑设计规范（零内部反射依赖）：
 * 1. 页面导航与模态转场（`nav*`/`modal*`）在标准模式下全程保持 100% 不透明（无背景透色混合）；
 * 2. 底层背景页执行 28% 视差位移与 `0.96f` 纵深缩放，前景页全宽全速水平滑移且不额外缩放；
 * 3. 同级切换（`enter`/`exit`）保留双向水平位移、缩放与透明渐变；
 * 4. `reduceMotion = true` 时统一降级为无位移、无缩放的 150ms 线性交叉淡化；
 * 5. 视差常数、物理弹簧令牌与紧凑图标尺寸保持规范约束。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-160dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppTransitionsTest {

    @get:Rule
    val compose = createComposeRule()

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
        assertNotEquals(EnterTransition.None, standardEnter)
        assertNotEquals(standardEnter, reducedEnter)
        assertNotEquals(standardEnter, reverseEnter)

        val standardExit = AppTransitions.exit(reduced = false, forward = true)
        val reducedExit = AppTransitions.exit(reduced = true, forward = true)
        val reverseExit = AppTransitions.exit(reduced = false, forward = false)
        assertNotNull(standardExit)
        assertNotNull(reducedExit)
        assertNotNull(reverseExit)
        assertNotEquals(ExitTransition.None, standardExit)
        assertNotEquals(standardExit, reducedExit)
        assertNotEquals(standardExit, reverseExit)
    }

    @Test
    fun navTransitionsProduceValidTransitions() {
        val standardNavEnter = AppTransitions.navEnter(reduced = false)
        val reducedNavEnter = AppTransitions.navEnter(reduced = true)
        assertNotNull(standardNavEnter)
        assertNotNull(reducedNavEnter)
        assertNotEquals(EnterTransition.None, standardNavEnter)
        assertNotEquals(standardNavEnter, reducedNavEnter)

        val standardNavExit = AppTransitions.navExit(reduced = false)
        val reducedNavExit = AppTransitions.navExit(reduced = true)
        assertNotNull(standardNavExit)
        assertNotNull(reducedNavExit)
        assertNotEquals(ExitTransition.None, standardNavExit)
        assertNotEquals(standardNavExit, reducedNavExit)

        val standardNavPopEnter = AppTransitions.navPopEnter(reduced = false)
        val reducedNavPopEnter = AppTransitions.navPopEnter(reduced = true)
        assertNotNull(standardNavPopEnter)
        assertNotNull(reducedNavPopEnter)
        assertNotEquals(EnterTransition.None, standardNavPopEnter)
        assertNotEquals(standardNavPopEnter, reducedNavPopEnter)

        val standardNavPopExit = AppTransitions.navPopExit(reduced = false)
        val reducedNavPopExit = AppTransitions.navPopExit(reduced = true)
        assertNotNull(standardNavPopExit)
        assertNotNull(reducedNavPopExit)
        assertNotEquals(ExitTransition.None, standardNavPopExit)
        assertNotEquals(standardNavPopExit, reducedNavPopExit)
    }

    @Test
    fun modalTransitionsProduceValidTransitions() {
        val standardModalEnter = AppTransitions.modalEnter(reduced = false)
        val reducedModalEnter = AppTransitions.modalEnter(reduced = true)
        assertNotNull(standardModalEnter)
        assertNotNull(reducedModalEnter)
        assertNotEquals(EnterTransition.None, standardModalEnter)
        assertNotEquals(standardModalEnter, reducedModalEnter)

        val standardModalExit = AppTransitions.modalExit(reduced = false)
        val reducedModalExit = AppTransitions.modalExit(reduced = true)
        assertNotNull(standardModalExit)
        assertNotNull(reducedModalExit)
        assertNotEquals(ExitTransition.None, standardModalExit)
        assertNotEquals(standardModalExit, reducedModalExit)
    }

    @Test
    fun motionSpecsContractsMatchTransitionExpectations() {
        val defaultSpatial = MotionSpecs.spatialDefault(
            reduced = false,
            visibilityThreshold = AppTransitions.ScaleVisibilityThreshold,
        ) as SpringSpec<Float>
        assertEquals(MotionSpecs.SpatialDefaultSpring.dampingRatio, defaultSpatial.dampingRatio, 0.0001f)
        assertEquals(MotionSpecs.SpatialDefaultSpring.stiffness, defaultSpatial.stiffness, 0.0001f)
        assertEquals(AppTransitions.ScaleVisibilityThreshold, defaultSpatial.visibilityThreshold)

        val fastSpatial = MotionSpecs.spatialFast(
            reduced = false,
            visibilityThreshold = AppTransitions.ScaleVisibilityThreshold,
        ) as SpringSpec<Float>
        assertEquals(MotionSpecs.SpatialFastSpring.dampingRatio, fastSpatial.dampingRatio, 0.0001f)
        assertEquals(MotionSpecs.SpatialFastSpring.stiffness, fastSpatial.stiffness, 0.0001f)
        assertEquals(AppTransitions.ScaleVisibilityThreshold, fastSpatial.visibilityThreshold)

        val defaultEffects = MotionSpecs.effectsDefault<Float>(reduced = false) as SpringSpec<Float>
        assertEquals(MotionSpecs.EffectsDefaultSpring.dampingRatio, defaultEffects.dampingRatio, 0.0001f)
        assertEquals(MotionSpecs.EffectsDefaultSpring.stiffness, defaultEffects.stiffness, 0.0001f)

        val fastEffects = MotionSpecs.effectsFast<Float>(reduced = false) as SpringSpec<Float>
        assertEquals(MotionSpecs.EffectsFastSpring.dampingRatio, fastEffects.dampingRatio, 0.0001f)
        assertEquals(MotionSpecs.EffectsFastSpring.stiffness, fastEffects.stiffness, 0.0001f)

        val reducedSpec = MotionSpecs.fade<Float>(reduced = true) as TweenSpec<Float>
        assertEquals(MotionSpecs.ReducedMotionCrossfadeDuration, reducedSpec.durationMillis)
        assertEquals(LinearEasing, reducedSpec.easing)
    }

    @Test
    fun navForwardTransitionsKeepFullOpacityWithBackgroundParallaxAndDepthScale() {
        val harness = createTransitionHarness()
        val frame = harness.sample(
            enter = AppTransitions.navEnter(reduced = false),
            exit = AppTransitions.navExit(reduced = false),
            targetZIndex = 1f,
            frames = 3,
        )

        // 前景 navEnter：右侧全宽不缩放滑入
        assertTrue("navEnter 应从右侧滑入: x=${frame.enteringX}", frame.enteringX > 10f)
        assertEquals("navEnter 前景不得额外缩放", frame.fullWidthPx, frame.enteringWidthPx, 1f)

        // 背景 navExit：左侧 28% 视差 + 0.96f 纵深缩放
        assertTrue(
            "navExit 应向左视差位移且不超过 ParallaxOffsetFraction: x=${frame.exitingX}, width=${frame.fullWidthPx}",
            frame.exitingX < -1f && frame.exitingX >= -frame.fullWidthPx * (AppTransitions.ParallaxOffsetFraction + 0.02f),
        )
        assertTrue(
            "navExit 底层背景应包含纵深缩放: actualWidth=${frame.exitingWidthPx}, full=${frame.fullWidthPx}",
            frame.exitingWidthPx < frame.fullWidthPx - 1f &&
                frame.exitingWidthPx >= frame.fullWidthPx * AppTransitions.BackgroundDepthScale - 1f,
        )

        // 100% 不透明：暴露出的前景与背景像素均不得透出红色底板
        assertSolidPageColor(frame.enteringSampleColor, expectedBlue = true, label = "navEnter")
        assertSolidPageColor(frame.exitingSampleColor, expectedGreen = true, label = "navExit")
    }

    @Test
    fun navPopTransitionsRestoreBackgroundParallaxAndKeepFullOpacity() {
        val harness = createTransitionHarness()
        val frame = harness.sample(
            enter = AppTransitions.navPopEnter(reduced = false),
            exit = AppTransitions.navPopExit(reduced = false),
            targetZIndex = -1f,
            frames = 3,
        )

        // 底层背景 navPopEnter：从 -28% 视差与 0.96f 纵深缩放平滑复原
        assertTrue(
            "navPopEnter 应从左侧视差位滑回: x=${frame.enteringX}, width=${frame.fullWidthPx}",
            frame.enteringX < -1f && frame.enteringX >= -frame.fullWidthPx * (AppTransitions.ParallaxOffsetFraction + 0.02f),
        )
        assertTrue(
            "navPopEnter 底层背景应包含纵深缩放复原: actualWidth=${frame.enteringWidthPx}, full=${frame.fullWidthPx}",
            frame.enteringWidthPx < frame.fullWidthPx - 1f &&
                frame.enteringWidthPx >= frame.fullWidthPx * AppTransitions.BackgroundDepthScale - 1f,
        )

        // 前景 navPopExit：向右全速不缩放滑出
        assertTrue("navPopExit 应向右全速滑出: x=${frame.exitingX}", frame.exitingX > 10f)
        assertEquals("navPopExit 前景不得额外缩放", frame.fullWidthPx, frame.exitingWidthPx, 1f)

        assertSolidPageColor(frame.enteringSampleColor, expectedBlue = true, label = "navPopEnter")
        assertSolidPageColor(frame.exitingSampleColor, expectedGreen = true, label = "navPopExit")
    }

    @Test
    fun modalTransitionsSlideFromLeftWithoutScaleOrFade() {
        val harness = createTransitionHarness()
        val enterFrame = harness.sample(
            enter = AppTransitions.modalEnter(reduced = false),
            exit = ExitTransition.None,
            targetZIndex = 1f,
            frames = 3,
        )
        assertTrue("modalEnter 应从左侧滑入: x=${enterFrame.enteringX}", enterFrame.enteringX < -10f)
        assertEquals("modalEnter 不得包含额外缩放", enterFrame.fullWidthPx, enterFrame.enteringWidthPx, 1f)
        assertSolidPageColor(enterFrame.enteringSampleColor, expectedBlue = true, label = "modalEnter")

        val exitFrame = harness.sample(
            enter = EnterTransition.None,
            exit = AppTransitions.modalExit(reduced = false),
            targetZIndex = -1f,
            frames = 3,
        )
        assertTrue("modalExit 应向左滑出: x=${exitFrame.exitingX}", exitFrame.exitingX < -10f)
        assertEquals("modalExit 不得包含额外缩放", exitFrame.fullWidthPx, exitFrame.exitingWidthPx, 1f)
        assertSolidPageColor(exitFrame.exitingSampleColor, expectedGreen = true, label = "modalExit")
    }

    @Test
    fun siblingTransitionsCoordinateSlideScaleAndFadeInBothDirections() {
        val harness = createTransitionHarness()
        val forwardEnterFrame = harness.sample(
            enter = AppTransitions.enter(reduced = false, forward = true),
            exit = ExitTransition.None,
            targetZIndex = 1f,
            frames = 3,
        )
        assertTrue("正向 enter 应从右侧进入: x=${forwardEnterFrame.enteringX}", forwardEnterFrame.enteringX > 0.5f)
        assertTrue(
            "同级 enter 应包含缩放: width=${forwardEnterFrame.enteringWidthPx}, full=${forwardEnterFrame.fullWidthPx}",
            forwardEnterFrame.enteringWidthPx < forwardEnterFrame.fullWidthPx - 1f,
        )
        assertTrue(
            "同级 enter 早期帧应存在透明渐变（混入红色底板）: red=${forwardEnterFrame.enteringSampleColor.red}",
            forwardEnterFrame.enteringSampleColor.red > 0.05f,
        )

        val reverseEnterFrame = harness.sample(
            enter = AppTransitions.enter(reduced = false, forward = false),
            exit = ExitTransition.None,
            targetZIndex = 1f,
            frames = 3,
        )
        assertTrue("反向 enter 应从左侧进入: x=${reverseEnterFrame.enteringX}", reverseEnterFrame.enteringX < -0.5f)

        val forwardExitFrame = harness.sample(
            enter = EnterTransition.None,
            exit = AppTransitions.exit(reduced = false, forward = true),
            targetZIndex = -1f,
            frames = 3,
        )
        assertTrue("正向 exit 应向左侧离开: x=${forwardExitFrame.exitingX}", forwardExitFrame.exitingX < -0.5f)
        assertTrue(
            "同级 exit 应包含缩放: width=${forwardExitFrame.exitingWidthPx}, full=${forwardExitFrame.fullWidthPx}",
            forwardExitFrame.exitingWidthPx < forwardExitFrame.fullWidthPx - 1f,
        )
        assertTrue(
            "同级 exit 早期帧应存在透明渐变（混入红色底板）: red=${forwardExitFrame.exitingSampleColor.red}",
            forwardExitFrame.exitingSampleColor.red > 0.05f,
        )

        val reverseExitFrame = harness.sample(
            enter = EnterTransition.None,
            exit = AppTransitions.exit(reduced = false, forward = false),
            targetZIndex = -1f,
            frames = 3,
        )
        assertTrue("反向 exit 应向右侧离开: x=${reverseExitFrame.exitingX}", reverseExitFrame.exitingX > 0.5f)
    }

    @Test
    fun reducedMotionTransitionsStripSlideAndScaleAndUseReducedFade() {
        val expectedReducedEnter = fadeIn(MotionSpecs.fade(true))
        val expectedReducedExit = fadeOut(MotionSpecs.fade(true))

        val enterList = listOf(
            AppTransitions.enter(reduced = true),
            AppTransitions.enter(reduced = true, forward = false),
            AppTransitions.navEnter(reduced = true),
            AppTransitions.navPopEnter(reduced = true),
            AppTransitions.modalEnter(reduced = true),
        )
        val exitList = listOf(
            AppTransitions.exit(reduced = true),
            AppTransitions.exit(reduced = true, forward = false),
            AppTransitions.navExit(reduced = true),
            AppTransitions.navPopExit(reduced = true),
            AppTransitions.modalExit(reduced = true),
        )

        enterList.forEach { transition ->
            assertEquals("减少动态进入必须严格等价于 150ms 线性 fadeIn", expectedReducedEnter, transition)
        }
        exitList.forEach { transition ->
            assertEquals("减少动态退出必须严格等价于 150ms 线性 fadeOut", expectedReducedExit, transition)
        }

        // 真实运行帧校验：减少动态中段无位移、无缩放，且保留交叉淡化
        val harness = createTransitionHarness()
        enterList.zip(exitList).forEach { (enter, exit) ->
            val frame = harness.sample(
                enter = enter,
                exit = exit,
                targetZIndex = 1f,
                frames = 5,
            )
            assertEquals("减少动态下 enter 不得产生位移", 0f, frame.enteringX, 0.5f)
            assertEquals("减少动态下 exit 不得产生位移", 0f, frame.exitingX, 0.5f)
            assertEquals("减少动态下 enter 不得产生缩放", frame.fullWidthPx, frame.enteringWidthPx, 0.5f)
            assertEquals("减少动态下 exit 不得产生缩放", frame.fullWidthPx, frame.exitingWidthPx, 0.5f)
            assertTrue(
                "减少动态中段应处于半透明交叉淡化而非瞬间跳变: blue=${frame.enteringSampleColor.blue}",
                frame.enteringSampleColor.blue in 0.1f..0.9f,
            )
        }
    }

    private data class TransitionFrameSample(
        val fullWidthPx: Float,
        val enteringX: Float,
        val enteringWidthPx: Float,
        val exitingX: Float,
        val exitingWidthPx: Float,
        val enteringSampleColor: Color,
        val exitingSampleColor: Color,
    )

    private fun createTransitionHarness(): TransitionHarness = TransitionHarness().also { it.mount() }

    private inner class TransitionHarness {
        private var enterState by mutableStateOf(EnterTransition.None)
        private var exitState by mutableStateOf(ExitTransition.None)
        private var targetZIndexState by mutableStateOf(0f)
        private var drawEnteringState by mutableStateOf(true)
        private var drawExitingState by mutableStateOf(true)
        private var pageState by mutableIntStateOf(0)
        private var runEpoch by mutableIntStateOf(0)
        private lateinit var hostView: View
        private val pageCoordinates = mutableMapOf<Int, LayoutCoordinates>()

        fun mount() {
            compose.setContent {
                hostView = LocalView.current
                key(runEpoch) {
                    Box(
                        Modifier
                            .size(200.dp)
                            .background(BackdropRed)
                            .testTag("transition-host"),
                    ) {
                        AnimatedContent(
                            targetState = pageState,
                            transitionSpec = {
                                (enterState togetherWith exitState).apply {
                                    targetContentZIndex = targetZIndexState
                                }
                            },
                            label = "app-transitions-contract-test",
                        ) { target ->
                            val background = when {
                                target == 0 && drawExitingState -> PageGreen
                                target == 1 && drawEnteringState -> PageBlue
                                else -> Color.Transparent
                            }
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .onGloballyPositioned { coordinates ->
                                        pageCoordinates[target] = coordinates
                                    }
                                    .background(background)
                                    .testTag("transition-page-$target"),
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
        }

        fun sample(
            enter: EnterTransition,
            exit: ExitTransition,
            targetZIndex: Float,
            frames: Int,
        ): TransitionFrameSample {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle {
                pageCoordinates.clear()
                enterState = enter
                exitState = exit
                targetZIndexState = targetZIndex
                drawEnteringState = enter != EnterTransition.None
                drawExitingState = exit != ExitTransition.None
                pageState = 0
                runEpoch++
            }
            compose.waitForIdle()

            val hostBounds = compose.onNodeWithTag("transition-host").fetchSemanticsNode().boundsInRoot
            val fullWidthPx = hostBounds.width

            compose.mainClock.autoAdvance = false
            compose.runOnIdle { pageState = 1 }
            compose.waitForIdle()
            repeat(frames) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }

            val enteringCoords = pageCoordinates[1]?.takeIf { it.isAttached }
            val exitingCoords = pageCoordinates[0]?.takeIf { it.isAttached }
            assertNotNull("采样过渡帧时应存在进入页布局坐标 transition-page-1", enteringCoords)
            if (exit != ExitTransition.None) {
                assertNotNull("采样过渡帧时应存在退出页布局坐标 transition-page-0", exitingCoords)
            }

            val pixelMap = compose.runOnIdle {
                val bitmap = Bitmap.createBitmap(
                    ceil(fullWidthPx).toInt().coerceAtLeast(1),
                    ceil(hostBounds.height).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                val canvas = Canvas(bitmap)
                canvas.translate(-hostBounds.left, -hostBounds.top)
                hostView.draw(canvas)
                bitmap.asImageBitmap().toPixelMap()
            }
            compose.mainClock.autoAdvance = true

            val centerY = (hostBounds.height / 2f).roundToInt().coerceIn(0, pixelMap.height - 1)
            val (enteringLeft, enteringRight) = enteringCoords!!.unclippedHorizontalSpan(hostBounds.left)
            val exitingSpan = exitingCoords?.unclippedHorizontalSpan(hostBounds.left)

            val enteringSampleX = if (targetZIndex < 0f && exitingSpan != null && exitingSpan.first > 4f) {
                (exitingSpan.first / 2f).roundToInt()
            } else {
                val visibleLeft = enteringLeft.coerceAtLeast(0f)
                val visibleRight = enteringRight.coerceAtMost(fullWidthPx)
                ((visibleLeft + visibleRight) / 2f).roundToInt()
            }.coerceIn(0, pixelMap.width - 1)

            val exitingSampleX = if (exitingSpan != null) {
                val (exitingLeft, exitingRight) = exitingSpan
                if (targetZIndex > 0f && enteringLeft > 4f) {
                    (enteringLeft / 2f).roundToInt()
                } else if (targetZIndex > 0f && enteringRight < fullWidthPx - 4f) {
                    ((enteringRight + fullWidthPx) / 2f).roundToInt()
                } else if (
                    targetZIndex < 0f &&
                    drawEnteringState &&
                    enteringRight in exitingLeft..(exitingRight.coerceAtMost(fullWidthPx) - 4f)
                ) {
                    // 在未被另一页覆盖的露空区域采样，避免软件画布绘制 GraphicsLayer 时依赖绘制顺序
                    val exposedRight = exitingRight.coerceAtMost(fullWidthPx)
                    ((enteringRight + exposedRight) / 2f).roundToInt()
                } else {
                    val visibleLeft = exitingLeft.coerceAtLeast(0f)
                    val visibleRight = exitingRight.coerceAtMost(fullWidthPx)
                    ((visibleLeft + visibleRight) / 2f).roundToInt()
                }
            } else {
                (pixelMap.width / 2)
            }.coerceIn(0, pixelMap.width - 1)

            return TransitionFrameSample(
                fullWidthPx = fullWidthPx,
                enteringX = enteringLeft,
                enteringWidthPx = enteringRight - enteringLeft,
                exitingX = exitingSpan?.first ?: 0f,
                exitingWidthPx = exitingSpan?.let { (left, right) -> right - left } ?: fullWidthPx,
                enteringSampleColor = pixelMap[enteringSampleX, centerY],
                exitingSampleColor = pixelMap[exitingSampleX, centerY],
            )
        }

        private fun LayoutCoordinates.unclippedHorizontalSpan(hostLeft: Float): Pair<Float, Float> {
            val left = localToRoot(Offset.Zero).x - hostLeft
            val right = localToRoot(Offset(size.width.toFloat(), 0f)).x - hostLeft
            return left to right
        }
    }

    private fun assertSolidPageColor(
        color: Color,
        expectedBlue: Boolean = false,
        expectedGreen: Boolean = false,
        label: String,
    ) {
        assertTrue("$label 必须 100% 不透明，不得透出红色底板: color=$color", color.red < 0.02f)
        if (expectedBlue) {
            assertTrue("$label 应保持纯蓝前景色: color=$color", color.blue > 0.98f && color.green < 0.02f)
        }
        if (expectedGreen) {
            assertTrue("$label 应保持纯绿页色: color=$color", color.green > 0.98f && color.blue < 0.02f)
        }
    }

    private companion object {
        val BackdropRed = Color(0xFFFF0000)
        val PageGreen = Color(0xFF00FF00)
        val PageBlue = Color(0xFF0000FF)
    }
}
