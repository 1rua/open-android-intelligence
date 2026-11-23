package com.openandroidintelligence.conversation.motion

import android.os.Build
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
