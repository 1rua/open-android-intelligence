package com.openandroidintelligence.conversation.motion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.components.ExpandableRegion
import com.openandroidintelligence.conversation.components.bouncyClickable
import com.openandroidintelligence.conversation.theme.OpenAndroidIntelligenceTheme
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.test.onAllNodesWithTag

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedTransitionsAndPredictiveBackTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sharedTransitionKeysProvideStableUniqueEnglishKeys() {
        val keys = setOf(
            SharedTransitionKeys.rootSurface(),
            SharedTransitionKeys.rootSurface("login"),
            SharedTransitionKeys.loginCard(),
            SharedTransitionKeys.loginCard("oauth"),
            SharedTransitionKeys.settingsCategoryCard("appearance"),
            SharedTransitionKeys.composerContainer(),
            SharedTransitionKeys.composerAction(),
            SharedTransitionKeys.composerAction("send-stop"),
            SharedTransitionKeys.expandableCard("thought-1"),
            SharedTransitionKeys.morphingButton("primary-submit"),
        )
        assertEquals(10, keys.size)
        assertTrue(SharedTransitionKeys.settingsCategoryCard("gateway").startsWith("settings_category_card:"))
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Test
    fun boundsTransformUsesExpressiveOrStandardSpringAndSnapsWhenReduced() {
        val from = Rect(0f, 0f, 100f, 100f)
        val to = Rect(20f, 20f, 220f, 220f)

        val expressive = MotionSpecs.boundsTransform(reduced = false, expressive = true).transform(from, to)
        assertTrue(expressive is SpringSpec<Rect>)
        val expressiveSpring = expressive as SpringSpec<Rect>
        assertEquals(MotionSpecs.ExpressiveSpatialDefaultSpring.dampingRatio, expressiveSpring.dampingRatio, 0.0001f)
        assertEquals(MotionSpecs.ExpressiveSpatialDefaultSpring.stiffness, expressiveSpring.stiffness, 0.0001f)
        assertEquals(Rect.VisibilityThreshold, expressiveSpring.visibilityThreshold)

        val standard = MotionSpecs.boundsTransform(reduced = false, expressive = false).transform(from, to)
        assertTrue(standard is SpringSpec<Rect>)
        val standardSpring = standard as SpringSpec<Rect>
        assertEquals(MotionSpecs.SpatialDefaultSpring.dampingRatio, standardSpring.dampingRatio, 0.0001f)
        assertEquals(MotionSpecs.SpatialDefaultSpring.stiffness, standardSpring.stiffness, 0.0001f)

        val reduced = MotionSpecs.boundsTransform(reduced = true, expressive = true).transform(from, to)
        assertTrue(reduced is SnapSpec<Rect>)
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Test
    fun m3SharedBoundsAndElementDegradeSafelyWithoutHostScope() {
        compose.setContent {
            OpenAndroidIntelligenceTheme(reduceMotion = false) {
                Box(
                    Modifier
                        .size(120.dp)
                        .m3SharedBounds(key = SharedTransitionKeys.loginCard(), clipShape = RoundedCornerShape(16.dp))
                        .m3SharedElement(key = SharedTransitionKeys.morphingButton("save")),
                ) {
                    Text("NoHostFallback")
                }
            }
        }
        compose.onNodeWithText("NoHostFallback").assertIsDisplayed()
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Test
    fun ensureSharedTransitionHostReusesOuterScopeWithoutDoubleNesting() {
        var outerScope: SharedTransitionScope? = null
        var innerScope: SharedTransitionScope? = null

        compose.setContent {
            AppSharedTransitionHost {
                outerScope = LocalSharedTransitionScope.current
                EnsureSharedTransitionHost {
                    innerScope = LocalSharedTransitionScope.current
                    Text("NestedHost")
                }
            }
        }
        compose.waitForIdle()
        assertNotNull(outerScope)
        assertSame("外层已提供作用域时不应重复创建新 SharedTransitionLayout", outerScope, innerScope)
    }

    @Test
    fun sharedBoundsAndElementTransitionInStandardMode() {
        verifySharedBoundsAndElementTransition(reduceMotion = false)
    }

    @Test
    fun sharedBoundsAndElementTransitionInReducedMode() {
        verifySharedBoundsAndElementTransition(reduceMotion = true)
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    private fun verifySharedBoundsAndElementTransition(reduceMotion: Boolean) {
        var expanded by mutableStateOf(false)
        compose.setContent {
            OpenAndroidIntelligenceTheme(reduceMotion = reduceMotion) {
                EnsureSharedTransitionHost {
                    Box(Modifier.size(260.dp)) {
                        AnimatedContent(
                            targetState = expanded,
                            transitionSpec = {
                                (fadeIn(MotionSpecs.fade(reduceMotion)) togetherWith fadeOut(MotionSpecs.fade(reduceMotion))).using(null)
                            },
                            label = "shared-bounds-test",
                        ) { isExpanded ->
                            ProvideSharedVisibilityScope {
                                if (!isExpanded) {
                                    Box(
                                        Modifier
                                            .m3SharedBounds(
                                                key = SharedTransitionKeys.expandableCard("card-1"),
                                                clipShape = RoundedCornerShape(12.dp),
                                            )
                                            .size(100.dp)
                                            .background(MaterialTheme.colorScheme.surfaceContainer),
                                    ) {
                                        Text(
                                            "CollapsedCard",
                                            modifier = Modifier.m3SharedElement(
                                                key = SharedTransitionKeys.morphingButton("icon-1"),
                                                clipShape = RoundedCornerShape(8.dp),
                                            ),
                                        )
                                    }
                                } else {
                                    Box(
                                        Modifier
                                            .m3SharedBounds(
                                                key = SharedTransitionKeys.expandableCard("card-1"),
                                                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                                                clipShape = RoundedCornerShape(20.dp),
                                            )
                                            .size(220.dp)
                                            .background(MaterialTheme.colorScheme.secondaryContainer),
                                    ) {
                                        Box(Modifier.fillMaxSize().testTag("shared-card-expanded")) {
                                            Text(
                                                "ExpandedCard",
                                                modifier = Modifier.m3SharedElement(
                                                    key = SharedTransitionKeys.morphingButton("icon-1"),
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("CollapsedCard").assertIsDisplayed()
        val startPx = with(compose.density) { 100.dp.toPx() }
        val endPx = with(compose.density) { 220.dp.toPx() }

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { expanded = true }
        compose.waitForIdle()
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        if (!reduceMotion) {
            val midWidth = compose.onNodeWithTag("shared-card-expanded").fetchSemanticsNode().boundsInRoot.width
            assertTrue(
                "标准模式下共享边界中段宽度应处于起止之间 ($startPx..$endPx), actual=$midWidth",
                midWidth in (startPx + 1f)..(endPx - 1f),
            )
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("ExpandedCard").assertIsDisplayed()
        assertEquals(endPx, compose.onNodeWithTag("shared-card-expanded").fetchSemanticsNode().boundsInRoot.width, 1f)
    }

    @Test
    fun navEnterProducesMidTransitionHorizontalSlideOnlyInStandardMode() {
        var reduceMotion by mutableStateOf(false)
        var page by mutableStateOf(0)
        compose.setContent {
            OpenAndroidIntelligenceTheme(reduceMotion = reduceMotion) {
                key(reduceMotion) {
                    AnimatedContent(
                        targetState = page,
                        transitionSpec = {
                            AppTransitions.navEnter(reduceMotion) togetherWith AppTransitions.navExit(reduceMotion)
                        },
                        label = "nav-enter-clock-test",
                    ) { target ->
                        Box(Modifier.size(240.dp).testTag("nav-page-$target")) {
                            Text("Page $target")
                        }
                    }
                }
            }
        }

        fun sampleMidFrameOffset(reduced: Boolean): Float {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle {
                reduceMotion = reduced
                page = 0
            }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { page = 1 }
            compose.waitForIdle()
            repeat(3) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            val left = compose.onNodeWithTag("nav-page-1").fetchSemanticsNode().positionInRoot.x
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            return left
        }

        val standardMidX = sampleMidFrameOffset(reduced = false)
        assertTrue("标准态 navEnter 中段应仍在右侧滑入中: $standardMidX", standardMidX > 10f)
        val reducedMidX = sampleMidFrameOffset(reduced = true)
        assertEquals(0f, reducedMidX, 0.5f)
    }

    @Test
    fun supportsNativePredictiveBackMatchesApi34Boundary() {
        assertFalse(supportsNativePredictiveBack(Build.VERSION_CODES.S))
        assertFalse(supportsNativePredictiveBack(Build.VERSION_CODES.TIRAMISU))
        assertTrue(supportsNativePredictiveBack(Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        assertTrue(supportsNativePredictiveBack(35))
    }

    @Test
    fun rememberPredictiveBackStateTracksEdgeDirectionReboundZeroRecompositionAndCompletion() {
        var latestState = PredictiveBackState()
        var backCalls = 0
        var recomposeCount = 0
        lateinit var dispatcher: OnBackPressedDispatcher

        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                val backState = rememberPredictiveBackState(
                    enabled = true,
                    onBack = { backCalls++ },
                    sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                )
                SideEffect { recomposeCount++ }
                Box(
                    Modifier
                        .size(200.dp)
                        .predictiveBackSlideTransform(
                            stateProvider = {
                                latestState = backState.value
                                backState.value
                            },
                            reduced = false,
                        ),
                ) {
                    Text("PredictivePanel", modifier = Modifier.testTag("predictive-panel"))
                }
            }
        }
        compose.waitForIdle()
        val initialRecompose = recomposeCount

        // 左边缘起滑应向右平移，右边缘起滑应向左平移，且不触发父组合体逐帧重组
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(40f, 100f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        assertEquals(0.5f, latestState.progress, 0.01f)
        val leftEdgeX = compose.onNodeWithTag("predictive-panel").fetchSemanticsNode().positionInRoot.x
        assertTrue("左边缘侧滑时 positionInRoot.x 应大于 0: $leftEdgeX", leftEdgeX > 0f)
        assertEquals("惰性读取进度不应触发宿主组合体重新组合", initialRecompose, recomposeCount)

        // 取消后临界阻尼平滑回弹复原（全程 >= 0f），不触发 onBack
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(32)
        assertTrue("取消后应平滑回弹而不是瞬跳: ${latestState.progress}", latestState.progress in 0.01f..0.49f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0f, latestState.progress, 0.001f)
        assertEquals(0, backCalls)
        assertEquals(initialRecompose, recomposeCount)

        // 右边缘起滑应向左平移并支持正常完成复位
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(100f, 100f, 0f, BackEventCompat.EDGE_RIGHT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(20f, 100f, 0.6f, BackEventCompat.EDGE_RIGHT))
        }
        compose.waitForIdle()
        val rightEdgeX = compose.onNodeWithTag("predictive-panel").fetchSemanticsNode().positionInRoot.x
        assertTrue("右边缘侧滑时 positionInRoot.x 应小于 0: $rightEdgeX", rightEdgeX < 0f)
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, backCalls)
        assertEquals(0f, latestState.progress, 0.001f)
    }

    @Test
    fun rememberPredictiveBackStateCancelsReboundWithoutClobberingNextGesture() {
        var latestProgress = 0f
        var recomposeCount = 0
        lateinit var dispatcher: OnBackPressedDispatcher

        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                val backState = rememberPredictiveBackState(
                    enabled = true,
                    onBack = {},
                    sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                )
                SideEffect { recomposeCount++ }
                Box(
                    Modifier
                        .size(200.dp)
                        .predictiveBackSlideTransform(
                            stateProvider = {
                                latestProgress = backState.value.progress
                                backState.value
                            },
                            reduced = false,
                        ),
                ) {
                    Text("RacePanel", modifier = Modifier.testTag("race-panel"))
                }
            }
        }
        compose.waitForIdle()
        val initialRecompose = recomposeCount

        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(40f, 100f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()

        // 在第 1 次取消回弹中途（32ms）立即发起第 2 次手势推进到 0.7f
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(70f, 100f, 0.7f, BackEventCompat.EDGE_LEFT))
        }
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            assertEquals("第 2 次手势进度不得被第 1 次手势取消清理覆盖", 0.7f, latestProgress, 0.01f)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(initialRecompose, recomposeCount)
    }

    @Test
    fun predictiveBackCompatHandlerReportsEdgeAndStateOnApi34() {
        var latestState = PredictiveBackState()
        var backCalls = 0
        lateinit var dispatcher: OnBackPressedDispatcher

        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                PredictiveBackCompatHandler(
                    enabled = true,
                    onProgressChange = { latestState = it },
                    onBack = { backCalls++ },
                    sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                )
                Box(Modifier.size(120.dp)) { Text("CompatHandler") }
            }
        }

        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_RIGHT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(40f, 100f, 0.4f, BackEventCompat.EDGE_RIGHT))
        }
        compose.waitForIdle()
        assertTrue(latestState.inProgress)
        assertEquals(0.4f, latestState.progress, 0.01f)
        assertEquals(BackEventCompat.EDGE_RIGHT, latestState.swipeEdge)

        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertFalse(latestState.inProgress)
        assertEquals(0f, latestState.progress, 0.001f)
        assertEquals(0, backCalls)

        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        compose.waitForIdle()
        assertEquals(1, backCalls)
    }

    @Test
    fun predictiveBackCompatHandlerResetsProgressAfterCompletion() {
        var latestState by mutableStateOf(PredictiveBackState())
        var backCalls = 0
        lateinit var dispatcher: OnBackPressedDispatcher

        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                PredictiveBackCompatHandler(
                    enabled = true,
                    onProgressChange = { latestState = it },
                    onBack = { backCalls++ },
                    sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                )
                Box(Modifier.size(120.dp)) { Text("ResetAfterComplete") }
            }
        }

        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(75f, 100f, 0.75f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        assertTrue(latestState.inProgress)
        assertEquals(0.75f, latestState.progress, 0.01f)

        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, backCalls)
        assertFalse(latestState.inProgress)
        assertEquals(0f, latestState.progress, 0.001f)
    }

    @Test
    fun predictiveBackStaysZeroWhenDurationScaleIsZeroOnApi34() {
        var progressWhenReduced = -1f
        var reducedBackCalls = 0
        lateinit var dispatcher: OnBackPressedDispatcher

        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CompositionLocalProvider(
                LocalMotionPolicy provides MotionPolicy(reduceMotion = false, durationScale = 0f),
            ) {
                val progress by rememberPredictiveBackProgress(
                    enabled = true,
                    onBack = { reducedBackCalls++ },
                    sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                )
                progressWhenReduced = progress
                Box(
                    Modifier
                        .size(200.dp)
                        .predictiveBackSlideTransform(progressProvider = { 0.6f }, reduced = true),
                ) {
                    Text("ReducedPanel", modifier = Modifier.testTag("reduced-panel"))
                }
            }
        }

        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(60f, 100f, 0.6f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        assertEquals(0f, progressWhenReduced, 0.001f)
        assertEquals(0f, compose.onNodeWithTag("reduced-panel").fetchSemanticsNode().positionInRoot.x, 0.5f)
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, reducedBackCalls)
    }

    @Test
    fun predictiveBackFallsBackToLegacyBackHandlerOnApi33() {
        var legacyBackCalls = 0
        var legacyProgress = -1f
        lateinit var dispatcher: OnBackPressedDispatcher

        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                val progress by rememberPredictiveBackProgress(
                    enabled = true,
                    onBack = { legacyBackCalls++ },
                    sdkInt = Build.VERSION_CODES.TIRAMISU,
                )
                legacyProgress = progress
                Box(Modifier.size(120.dp)) { Text("LegacyBack") }
            }
        }
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, legacyBackCalls)
        assertEquals(0f, legacyProgress, 0.001f)
    }

    @Test
    fun slideTransformReturnsSameModifierWhenReducedAndFollowsRtlDirection() {
        val base = Modifier.padding(4.dp)
        assertSame(base, base.predictiveBackSlideTransform(progressProvider = { 0.5f }, reduced = true))
        assertNotEquals(base, base.predictiveBackSlideTransform(progressProvider = { -0.2f }, reduced = false))
        assertNotEquals(base, base.predictiveBackSlideTransform(progressProvider = { 1.5f }, reduced = false))
        assertEquals(1f, resolvePredictiveBackDirection(BackEventCompat.EDGE_LEFT, LayoutDirection.Rtl))
        assertEquals(-1f, resolvePredictiveBackDirection(BackEventCompat.EDGE_RIGHT, LayoutDirection.Ltr))
        assertEquals(1f, resolvePredictiveBackDirection(-1, LayoutDirection.Ltr))
        assertEquals(-1f, resolvePredictiveBackDirection(-1, LayoutDirection.Rtl))

        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(
                    Modifier
                        .size(200.dp)
                        .predictiveBackSlideTransform(progressProvider = { 0.5f }, reduced = false)
                        .testTag("rtl-panel"),
                ) {
                    Text("RtlPanel")
                }
            }
        }
        val rtlX = compose.onNodeWithTag("rtl-panel").fetchSemanticsNode().positionInRoot.x
        assertTrue("RTL 无特定边缘时应向左负向位移: $rtlX", rtlX < 0f)
    }

    @Test
    fun expandableRegionAndBouncyClickableWorkWithMotionPolicy() {
        var expanded by mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            OpenAndroidIntelligenceTheme(reduceMotion = false) {
                Column {
                    Box(
                        Modifier
                            .size(48.dp)
                            .testTag("bouncy-target")
                            .bouncyClickable(onClick = { clicks++ }),
                    ) {
                        Text("Trigger")
                    }
                    ExpandableRegion(expanded = expanded) {
                        Text("ExpandedBody")
                    }
                }
            }
        }
        compose.onNodeWithText("ExpandedBody").assertDoesNotExist()
        compose.onNodeWithTag("bouncy-target").performClick()
        assertEquals(1, clicks)
        compose.runOnIdle { expanded = true }
        compose.waitForIdle()
        compose.onNodeWithText("ExpandedBody").assertIsDisplayed()
    }

    @Test
    fun fullscreenPredictiveSlideOverlayTracksParallaxZeroRecompositionAndCommitsWithoutBounceBack() {
        var overlayOpen by mutableStateOf(true)
        var dismissCalls = 0
        var bgRecomposeCount = 0
        var fgRecomposeCount = 0
        lateinit var dispatcher: OnBackPressedDispatcher

        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            OpenAndroidIntelligenceTheme(reduceMotion = false) {
                Box(Modifier.size(240.dp)) {
                    val controller = rememberPredictiveOverlayBackController(
                        overlayOpen = overlayOpen,
                        enabled = overlayOpen,
                        reduced = false,
                        onDismiss = {
                            dismissCalls++
                            overlayOpen = false
                        },
                        sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                    )
                    FullscreenPredictiveSlideOverlay(
                        controller = controller,
                        reduced = false,
                        modifier = Modifier.fillMaxSize(),
                        overlayTestTag = "phone-fullscreen-settings-container",
                        backgroundContent = {
                            SideEffect { bgRecomposeCount++ }
                            Box(Modifier.fillMaxSize().testTag("overlay-background")) {
                                Text("OverlayBg")
                            }
                        },
                        overlayContent = {
                            SideEffect { fgRecomposeCount++ }
                            Box(Modifier.fillMaxSize().testTag("overlay-foreground")) {
                                Text("OverlayFg")
                            }
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        val fullWidthPx = with(compose.density) { 240.dp.toPx() }
        val initBgRecompose = bgRecomposeCount
        val initFgRecompose = fgRecomposeCount

        // 左边缘起滑推进至 0.5f：前景右移约 +0.5w，背景向左做 28% 视差与缩放，且全程零重组
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 100f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()

        val fgDraggedX = compose.onNodeWithTag("overlay-foreground").fetchSemanticsNode().positionInRoot.x
        val bgDraggedX = compose.onNodeWithTag("overlay-background").fetchSemanticsNode().positionInRoot.x
        assertEquals(fullWidthPx * 0.5f, fgDraggedX, 2f)
        assertTrue("左边缘拖拽时背景应向左视差退后: bgX=$bgDraggedX", bgDraggedX < -fullWidthPx * 0.05f)
        assertEquals("拖拽期间背景不得重组", initBgRecompose, bgRecomposeCount)
        assertEquals("拖拽期间前景不得重组", initFgRecompose, fgRecomposeCount)

        // 松手提交返回：必须在松手位置继续滑出，绝不弹回 x = 0
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(16)
        compose.waitForIdle()
        val afterCommitX = compose.onNodeWithTag("overlay-foreground").fetchSemanticsNode().positionInRoot.x
        assertTrue(
            "提交返回后前景必须继续从拖出位置滑出而非弹回原点: dragged=$fgDraggedX, afterCommit=$afterCommitX",
            afterCommitX >= fgDraggedX - 1f && afterCommitX > fullWidthPx * 0.45f,
        )

        // 退出动画完成后彻底卸载覆盖层，背景复原到原点
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(1, dismissCalls)
        assertTrue(
            compose.onAllNodesWithTag("phone-fullscreen-settings-container").fetchSemanticsNodes().isEmpty(),
        )
        assertEquals(0f, compose.onNodeWithTag("overlay-background").fetchSemanticsNode().positionInRoot.x, 0.5f)
    }

    @Test
    fun fullscreenPredictiveSlideOverlayTracksRightEdgeDirectionAndReboundsOnCancel() {
        var dismissCalls = 0
        var bgRecomposeCount = 0
        var fgRecomposeCount = 0
        lateinit var dispatcher: OnBackPressedDispatcher

        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            OpenAndroidIntelligenceTheme(reduceMotion = false) {
                Box(Modifier.size(240.dp)) {
                    val controller = rememberPredictiveOverlayBackController(
                        overlayOpen = true,
                        enabled = true,
                        reduced = false,
                        onDismiss = { dismissCalls++ },
                        sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                    )
                    FullscreenPredictiveSlideOverlay(
                        controller = controller,
                        reduced = false,
                        modifier = Modifier.fillMaxSize(),
                        overlayTestTag = "phone-fullscreen-settings-container",
                        backgroundContent = {
                            SideEffect { bgRecomposeCount++ }
                            Box(Modifier.fillMaxSize().testTag("overlay-background")) {
                                Text("OverlayBg")
                            }
                        },
                        overlayContent = {
                            SideEffect { fgRecomposeCount++ }
                            Box(Modifier.fillMaxSize().testTag("overlay-foreground")) {
                                Text("OverlayFg")
                            }
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        val fullWidthPx = with(compose.density) { 240.dp.toPx() }
        val initialOpenBgX = compose.onNodeWithTag("overlay-background").fetchSemanticsNode().positionInRoot.x
        val initBgRecompose = bgRecomposeCount
        val initFgRecompose = fgRecomposeCount

        // 右边缘起滑推进至 0.5f：前景向左负向位移，背景向右靠近原点并保持视差区间
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(240f, 100f, 0f, BackEventCompat.EDGE_RIGHT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 100f, 0.5f, BackEventCompat.EDGE_RIGHT))
        }
        compose.waitForIdle()

        val fgRightDraggedX = compose.onNodeWithTag("overlay-foreground").fetchSemanticsNode().positionInRoot.x
        val bgRightDraggedX = compose.onNodeWithTag("overlay-background").fetchSemanticsNode().positionInRoot.x
        assertEquals(-fullWidthPx * 0.5f, fgRightDraggedX, 2f)
        assertTrue("右边缘拖拽时背景应保留在视差正偏移区 (0..$initialOpenBgX): bgX=$bgRightDraggedX", bgRightDraggedX in 1f..(initialOpenBgX - 1f))
        assertEquals(initBgRecompose, bgRecomposeCount)
        assertEquals(initFgRecompose, fgRecomposeCount)

        // 半途取消手势：临界阻尼平滑回弹复原，不触发 onDismiss 也不卸载
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(32)
        val midReboundX = compose.onNodeWithTag("overlay-foreground").fetchSemanticsNode().positionInRoot.x
        assertTrue(
            "取消后应处于平滑回弹中间位置 (${fgRightDraggedX}..0): actual=$midReboundX",
            midReboundX > fgRightDraggedX + 1f && midReboundX < -1f,
        )
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0, dismissCalls)
        compose.onNodeWithTag("phone-fullscreen-settings-container").assertIsDisplayed()
        assertEquals(0f, compose.onNodeWithTag("overlay-foreground").fetchSemanticsNode().positionInRoot.x, 0.5f)
        assertEquals(initialOpenBgX, compose.onNodeWithTag("overlay-background").fetchSemanticsNode().positionInRoot.x, 0.5f)
    }

    @Test
    fun fullscreenPredictiveSlideOverlayDegradesTo150msCrossfadeWhenReduced() {
        var overlayOpen by mutableStateOf(true)
        var dismissCalls = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        lateinit var capturedController: PredictiveOverlayBackController

        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            OpenAndroidIntelligenceTheme(reduceMotion = true) {
                Box(Modifier.size(240.dp)) {
                    val controller = rememberPredictiveOverlayBackController(
                        overlayOpen = overlayOpen,
                        enabled = overlayOpen,
                        reduced = true,
                        onDismiss = {
                            dismissCalls++
                            overlayOpen = false
                        },
                        sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                    )
                    capturedController = controller
                    FullscreenPredictiveSlideOverlay(
                        controller = controller,
                        reduced = true,
                        modifier = Modifier.fillMaxSize(),
                        overlayTestTag = "phone-fullscreen-settings-container",
                        backgroundContent = {
                            Box(Modifier.fillMaxSize().testTag("overlay-background")) {
                                Text("OverlayBg")
                            }
                        },
                        overlayContent = {
                            Box(Modifier.fillMaxSize().testTag("overlay-foreground")) {
                                Text("OverlayFg")
                            }
                        },
                    )
                }
            }
        }
        // 初始即处于打开态时不得在挂载首帧重复动画
        compose.waitForIdle()
        compose.onNodeWithTag("phone-fullscreen-settings-container").assertIsDisplayed()
        assertEquals(1f, capturedController.openProgress.value, 0.001f)
        assertEquals(0f, compose.onNodeWithTag("overlay-foreground").fetchSemanticsNode().positionInRoot.x, 0.5f)

        // 减少动态下手势拖动不产生横向位移与背景视差
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 100f, 0.6f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        assertEquals(0f, compose.onNodeWithTag("overlay-foreground").fetchSemanticsNode().positionInRoot.x, 0.5f)
        assertEquals(0f, compose.onNodeWithTag("overlay-background").fetchSemanticsNode().positionInRoot.x, 0.5f)

        // 提交关闭：执行 150ms 线性淡出（45ms 时仍保持挂载无位移，超过 150ms 后彻底卸载）
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(45)
        compose.waitForIdle()
        assertEquals(1, dismissCalls)
        assertEquals(
            1,
            compose.onAllNodesWithTag("phone-fullscreen-settings-container").fetchSemanticsNodes().size,
        )
        assertTrue(
            "减少动态关闭中段应处于 150ms 线性淡出: ${capturedController.openProgress.value}",
            capturedController.openProgress.value in 0.05f..0.95f,
        )
        assertEquals(0f, compose.onNodeWithTag("overlay-foreground").fetchSemanticsNode().positionInRoot.x, 0.5f)

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue(
            compose.onAllNodesWithTag("phone-fullscreen-settings-container").fetchSemanticsNodes().isEmpty(),
        )

        // 重新打开：走 150ms 无位移线性淡入（先让 LaunchedEffect 挂载覆盖层再推进 45ms 采样）
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { overlayOpen = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(45)
        compose.waitForIdle()
        assertEquals(
            1,
            compose.onAllNodesWithTag("phone-fullscreen-settings-container").fetchSemanticsNodes().size,
        )
        assertTrue(
            "减少动态打开中段应处于 150ms 线性淡入: ${capturedController.openProgress.value}",
            capturedController.openProgress.value in 0.05f..0.95f,
        )
        assertEquals(0f, compose.onNodeWithTag("overlay-foreground").fetchSemanticsNode().positionInRoot.x, 0.5f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("phone-fullscreen-settings-container").assertIsDisplayed()
        assertEquals(1f, capturedController.openProgress.value, 0.001f)
    }

    @Test
    fun navPageTransitionDecorationDegradesSafelyAndAppliesEffectsSpecs() {
        var page by mutableStateOf(0)
        var reducedDecorationSame = false
        var noScopeDecorationSame = false
        val probeBase = Modifier.padding(2.dp)

        compose.setContent {
            OpenAndroidIntelligenceTheme(reduceMotion = false) {
                reducedDecorationSame = probeBase.navPageTransitionDecoration(
                    roleProvider = { NavPageTransitionRole.FOREGROUND },
                    reduced = true,
                ) === probeBase
                noScopeDecorationSame = probeBase.navPageTransitionDecoration(
                    roleProvider = { NavPageTransitionRole.BACKGROUND },
                    reduced = false,
                ) === probeBase

                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        (AppTransitions.navEnter(false) togetherWith AppTransitions.navExit(false)).apply {
                            targetContentZIndex = 1f
                        }
                    },
                    label = "nav-decoration-test",
                ) { target ->
                    ProvideSharedVisibilityScope {
                        val role = if (target == page) NavPageTransitionRole.FOREGROUND else NavPageTransitionRole.BACKGROUND
                        Box(
                            Modifier
                                .size(200.dp)
                                .background(MaterialTheme.colorScheme.surface)
                                .navPageTransitionDecoration(
                                    roleProvider = { role },
                                    reduced = false,
                                ),
                        ) {
                            Text("DecoratedPage $target")
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue(reducedDecorationSame)
        assertTrue(noScopeDecorationSame)
        compose.onNodeWithText("DecoratedPage 0").assertIsDisplayed()

        compose.runOnIdle { page = 1 }
        compose.waitForIdle()
        compose.onNodeWithText("DecoratedPage 1").assertIsDisplayed()
    }

    @Test
    fun fullscreenOverlayConsumesBackgroundPointerReboundsBlockedCommitAndHonorsNullTagAndBridgeReduced() {
        var backgroundClicks = 0
        var dismissAttempts = 0
        var bridgeReduced by mutableStateOf(false)
        lateinit var dispatcher: OnBackPressedDispatcher
        lateinit var capturedController: PredictiveOverlayBackController

        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            OpenAndroidIntelligenceTheme(reduceMotion = false) {
                Box(Modifier.size(240.dp)) {
                    val controller = rememberPredictiveOverlayBackController(
                        overlayOpen = true,
                        enabled = false,
                        reduced = false,
                        onDismiss = {},
                        sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                    )
                    capturedController = controller
                    // 未传 overlayTestTag 时默认值为 null，不自动挂接默认 tag
                    FullscreenPredictiveSlideOverlay(
                        controller = controller,
                        reduced = false,
                        modifier = Modifier.fillMaxSize(),
                        backgroundContent = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .clickable { backgroundClicks++ },
                            ) {
                                Text("BgButton")
                            }
                        },
                        overlayContent = {
                            PredictiveBackOverlayBridge(
                                controller = controller,
                                enabled = true,
                                onDismiss = { dismissAttempts++ },
                                reduced = bridgeReduced,
                                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                            )
                            Box(Modifier.fillMaxSize().testTag("overlay-blank-surface")) {
                                Text("OverlayContent")
                            }
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("phone-fullscreen-settings-container").fetchSemanticsNodes().isEmpty())

        // 1. 验证覆盖层空白区彻底拦截触摸事件，不穿透到底层背景点击事件
        compose.onNodeWithTag("overlay-blank-surface").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals("覆盖层未消费区域必须拦截点穿事件", 0, backgroundClicks)

        // 2. 验证宿主未改变 overlayOpen 时，提交手势经过 3 帧窗口后仍能平滑回弹至全开态
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 100f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        assertEquals(0.5f, capturedController.openProgress.value, 0.02f)
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, dismissAttempts)
        assertEquals(1f, capturedController.openProgress.value, 0.001f)
        assertEquals(0f, compose.onNodeWithTag("overlay-blank-surface").fetchSemanticsNode().positionInRoot.x, 0.5f)

        // 3. 验证显式向 Bridge 传入 reduced = true 时，即使外层政策为 false 也不发生拖动位移
        compose.runOnIdle { bridgeReduced = true }
        compose.waitForIdle()
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 100f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        assertEquals(1f, capturedController.openProgress.value, 0.001f)
        assertEquals(0f, compose.onNodeWithTag("overlay-blank-surface").fetchSemanticsNode().positionInRoot.x, 0.5f)
    }

    @Test
    fun fullscreenOverlayAllowsInnerVerticalScrollAndJitteryClicksWithoutLeakingToBackground() {
        var bgClicks = 0
        var innerClicks = 0
        lateinit var innerScrollState: ScrollState
        lateinit var bgScrollState: ScrollState

        compose.setContent {
            OpenAndroidIntelligenceTheme(reduceMotion = false) {
                Box(Modifier.size(240.dp)) {
                    val controller = rememberPredictiveOverlayBackController(
                        overlayOpen = true,
                        enabled = true,
                        reduced = false,
                        onDismiss = {},
                        sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                    )
                    FullscreenPredictiveSlideOverlay(
                        controller = controller,
                        reduced = false,
                        modifier = Modifier.fillMaxSize(),
                        backgroundContent = {
                            bgScrollState = rememberScrollState()
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(bgScrollState)
                                    .testTag("bg-scroll"),
                            ) {
                                repeat(10) { idx ->
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .clickable { bgClicks++ },
                                    ) {
                                        Text("BgRow-$idx")
                                    }
                                }
                            }
                        },
                        overlayContent = {
                            innerScrollState = rememberScrollState()
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(innerScrollState)
                                    .testTag("overlay-scroll"),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .clickable { innerClicks++ }
                                        .testTag("inner-button"),
                                ) {
                                    Text("InnerButton")
                                }
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("overlay-blank"),
                                ) {
                                    Text("OverlayBlank")
                                }
                                repeat(10) { idx ->
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(48.dp),
                                    ) {
                                        Text("InnerRow-$idx")
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
        compose.waitForIdle()

        // 1. 内层带微抖动点击应稳定触发，不受外层 pointerInput 干扰
        compose.onNodeWithTag("inner-button").performTouchInput {
            down(center)
            advanceEventTime(16)
            moveBy(Offset(2f, 2f))
            advanceEventTime(16)
            up()
        }
        compose.waitForIdle()
        assertEquals("微抖动点击应正常触发内层回调", 1, innerClicks)

        // 2. 内层小步增量竖向拖动应顺畅滚动，且不穿透到底层背景
        compose.onNodeWithTag("overlay-scroll").performTouchInput {
            down(center)
            repeat(5) {
                advanceEventTime(16)
                moveBy(Offset(0f, -20f))
            }
            up()
        }
        compose.waitForIdle()
        assertTrue("内层应顺畅滚动: innerScroll=${innerScrollState.value}", innerScrollState.value > 0)
        assertEquals("覆盖层滚动不得穿透到底层背景", 0, bgScrollState.value)

        // 3. 覆盖层空白区域点击与滑动均不得穿透到底层背景
        compose.onNodeWithTag("overlay-blank").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals("覆盖层空白区域点击不得穿透到底层", 0, bgClicks)
        assertEquals("覆盖层操作后底层滚动仍保持为 0", 0, bgScrollState.value)
    }

    @Test
    fun fullscreenOverlaySkipsBackgroundDrawWhenFullySettledAndResumesOnDrag() {
        var bgDrawCount = 0
        var overlayTick by mutableIntStateOf(0)
        lateinit var dispatcher: OnBackPressedDispatcher
        lateinit var hostView: View

        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            hostView = LocalView.current
            OpenAndroidIntelligenceTheme(reduceMotion = false) {
                Box(Modifier.size(240.dp)) {
                    val controller = rememberPredictiveOverlayBackController(
                        overlayOpen = true,
                        enabled = true,
                        reduced = false,
                        onDismiss = {},
                        sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                    )
                    FullscreenPredictiveSlideOverlay(
                        controller = controller,
                        reduced = false,
                        modifier = Modifier.fillMaxSize(),
                        backgroundContent = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .drawBehind { bgDrawCount++ },
                            ) {
                                Text("OverlayBg")
                            }
                        },
                        overlayContent = {
                            Box(Modifier.fillMaxSize()) {
                                Text("OverlayFg-$overlayTick")
                            }
                        },
                    )
                }
            }
        }
        fun triggerDraw() {
            compose.runOnIdle {
                val bitmap = Bitmap.createBitmap(
                    hostView.width.coerceAtLeast(1),
                    hostView.height.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                hostView.draw(Canvas(bitmap))
            }
        }
        compose.waitForIdle()
        triggerDraw()
        val settledBgDraws = bgDrawCount
        assertEquals("覆盖层全开遮挡时底层背景应完全跳过绘制", 0, settledBgDraws)

        // 覆盖层完全遮挡且静止时，触发前景内容更新并重绘不得连带重绘底层背景
        compose.runOnIdle { overlayTick = 1 }
        compose.waitForIdle()
        triggerDraw()
        assertEquals("覆盖层全开遮挡时前景重绘不得触发底层背景绘制", settledBgDraws, bgDrawCount)

        // 派发预测性返回露出底层后，底层背景立即恢复绘制
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(96f, 100f, 0.4f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        triggerDraw()
        assertTrue("预测返回露出底层后应恢复背景绘制: settled=$settledBgDraws, afterDrag=$bgDrawCount", bgDrawCount > settledBgDraws)
    }

    @Test
    fun predictiveBackSlideTransformZeroRelayoutDuringDragAndZeroLayerAtRest() {
        var progress by mutableFloatStateOf(0f)
        var layoutPlacements = 0

        compose.setContent {
            Box(
                Modifier
                    .size(200.dp)
                    .predictiveBackSlideTransform(progressProvider = { progress }, reduced = false)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            layoutPlacements++
                            placeable.place(0, 0)
                        }
                    }
                    .testTag("slide-node"),
            ) {
                Text("SlideContent")
            }
        }
        compose.waitForIdle()
        val fullWidthPx = with(compose.density) { 200.dp.toPx() }
        assertEquals(0f, compose.onNodeWithTag("slide-node").fetchSemanticsNode().positionInRoot.x, 0.5f)
        val settledPlacements = layoutPlacements
        val defaultScaleReduction = 0.04f

        // 0f -> 0.2f：首次进入拖动，触发图层挂载并开始位移
        compose.runOnIdle { progress = 0.2f }
        compose.waitForIdle()
        val expectedX02 = fullWidthPx * 0.2f + fullWidthPx * (0.2f * defaultScaleReduction) / 2f
        val afterFirstMoveX = compose.onNodeWithTag("slide-node").fetchSemanticsNode().positionInRoot.x
        assertEquals(expectedX02, afterFirstMoveX, 2f)
        val afterEnterDragPlacements = layoutPlacements

        // 连续推进 0.4f -> 0.7f -> 0.9f：图层开关恒为 true，子内容必须零额外重排
        listOf(0.4f, 0.7f, 0.9f).forEach { nextProgress ->
            compose.runOnIdle { progress = nextProgress }
            compose.waitForIdle()
            val expectedX = fullWidthPx * nextProgress + fullWidthPx * (nextProgress * defaultScaleReduction) / 2f
            assertEquals(
                expectedX,
                compose.onNodeWithTag("slide-node").fetchSemanticsNode().positionInRoot.x,
                2f,
            )
            assertEquals(
                "预测返回连续拖动到 $nextProgress 时子布局不应额外重排",
                afterEnterDragPlacements,
                layoutPlacements,
            )
        }
        assertTrue(settledPlacements >= 1)
    }

    @Test
    fun fullscreenOverlayIsolatesAccessibilityAndFocusWhenFullyOccluded() {
        var overlayOpen by mutableStateOf(false)
        var bgRecomposeCount = 0
        var bgFocused = false
        val bgFocusRequester = FocusRequester()
        lateinit var dispatcher: OnBackPressedDispatcher

        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            OpenAndroidIntelligenceTheme(reduceMotion = false) {
                Box(Modifier.size(240.dp)) {
                    val controller = rememberPredictiveOverlayBackController(
                        overlayOpen = overlayOpen,
                        enabled = overlayOpen,
                        reduced = false,
                        onDismiss = { overlayOpen = false },
                        sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                    )
                    FullscreenPredictiveSlideOverlay(
                        controller = controller,
                        reduced = false,
                        modifier = Modifier.fillMaxSize(),
                        backgroundContent = {
                            SideEffect { bgRecomposeCount++ }
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .testTag("bg-a11y-root"),
                            ) {
                                Box(
                                    Modifier
                                        .size(48.dp)
                                        .testTag("bg-focus-target")
                                        .focusRequester(bgFocusRequester)
                                        .onFocusChanged { bgFocused = it.isFocused }
                                        .focusable(),
                                )
                            }
                        },
                        overlayContent = {
                            Box(Modifier.fillMaxSize().testTag("overlay-fg")) {
                                Text("OverlayFg")
                            }
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        val hasInvisibleToUser = SemanticsMatcher.keyIsDefined(SemanticsProperties.InvisibleToUser)

        // 覆盖层关闭时底层可见且可获焦
        val bgWrapper = compose.onNodeWithTag("bg-a11y-root", useUnmergedTree = true).onParent()
        bgWrapper.assert(hasInvisibleToUser.not())
        compose.runOnIdle { bgFocusRequester.requestFocus() }
        compose.waitForIdle()
        assertTrue(bgFocused)

        // 打开覆盖层并稳定到全遮挡态：自动清除底层焦点，背景宿主节点打上 InvisibleToUser 隔离（不擦除子 testTag），且再次请求焦点被取消
        compose.runOnIdle { overlayOpen = true }
        compose.waitForIdle()
        assertFalse("覆盖层全遮挡后应清除底层已持有焦点", bgFocused)
        bgWrapper.assert(hasInvisibleToUser)
        compose.onNodeWithTag("bg-a11y-root", useUnmergedTree = true).assertExists()
        compose.runOnIdle { bgFocusRequester.requestFocus() }
        compose.waitForIdle()
        assertFalse("覆盖层全遮挡时底层不得再次获焦", bgFocused)

        val recomposeAtSettledOpen = bgRecomposeCount
        // 预测性返回拖出 0.4f 露出底层：解除无障碍隐藏且底层可重新获焦，同时全程零背景重组
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(96f, 100f, 0.4f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        assertEquals("露出底层切换隔离态期间不得触发背景重组", recomposeAtSettledOpen, bgRecomposeCount)
        bgWrapper.assert(hasInvisibleToUser.not())
        compose.runOnIdle { bgFocusRequester.requestFocus() }
        compose.waitForIdle()
        assertTrue("覆盖层部分拉回露出底层后应恢复底层获焦能力", bgFocused)
    }
}
