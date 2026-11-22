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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
                    AnimatedContent(
                        targetState = expanded,
                        transitionSpec = {
                            AppTransitions.enter(reduceMotion) togetherWith AppTransitions.exit(reduceMotion)
                        },
                        label = "shared-bounds-test",
                    ) { isExpanded ->
                        ProvideSharedVisibilityScope {
                            if (!isExpanded) {
                                Box(
                                    Modifier
                                        .size(100.dp)
                                        .m3SharedBounds(
                                            key = SharedTransitionKeys.expandableCard("card-1"),
                                            clipShape = RoundedCornerShape(12.dp),
                                        )
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
                                        .size(220.dp)
                                        .m3SharedBounds(
                                            key = SharedTransitionKeys.expandableCard("card-1"),
                                            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                                            clipShape = RoundedCornerShape(20.dp),
                                        )
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                ) {
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

        compose.onNodeWithText("CollapsedCard").assertIsDisplayed()
        compose.runOnIdle { expanded = true }
        compose.waitForIdle()
        compose.onNodeWithText("ExpandedCard").assertIsDisplayed()
    }

    @Test
    fun supportsNativePredictiveBackMatchesApi34Boundary() {
        assertFalse(supportsNativePredictiveBack(Build.VERSION_CODES.S))
        assertFalse(supportsNativePredictiveBack(Build.VERSION_CODES.TIRAMISU))
        assertTrue(supportsNativePredictiveBack(Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        assertTrue(supportsNativePredictiveBack(35))
    }

    @Test
    fun rememberPredictiveBackProgressTracksCancelSpringReboundAndCompletionOnApi34() {
        var progressValue = -1f
        var backCalls = 0
        lateinit var dispatcher: OnBackPressedDispatcher

        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                val progress by rememberPredictiveBackProgress(
                    enabled = true,
                    onBack = { backCalls++ },
                    sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                )
                progressValue = progress
                Box(
                    Modifier
                        .size(200.dp)
                        .predictiveBackSlideTransform(progress = progress, reduced = false),
                ) {
                    Text("PredictivePanel", modifier = Modifier.testTag("predictive-panel"))
                }
            }
        }

        // 手势开始与推进：应同步跟踪进度，且触发向左的图层滑移
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(40f, 100f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        assertEquals(0.5f, progressValue, 0.01f)
        val shiftedX = compose.onNodeWithTag("predictive-panel").fetchSemanticsNode().positionInRoot.x
        assertTrue("预测性返回向左偏移时 positionInRoot.x 应小于 0: $shiftedX", shiftedX < 0f)

        // 手势取消：应在 NonCancellable 中弹簧回弹复原且不调用 onBack
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(32)
        assertTrue("取消后应平滑回弹而不是瞬跳: $progressValue", progressValue in 0.01f..0.49f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0f, progressValue, 0.001f)
        assertEquals(0, backCalls)

        // 完整确认返回：触发 onBack 并复位进度
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 100f, 0.8f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        compose.waitForIdle()
        assertEquals(1, backCalls)
        assertEquals(0f, progressValue, 0.001f)
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
                        .predictiveBackSlideTransform(progress = 0.6f, reduced = true),
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
    fun slideTransformReturnsSameModifierWhenReducedAndClampsProgress() {
        val base = Modifier.padding(4.dp)
        assertSame(base, base.predictiveBackSlideTransform(progress = 0.5f, reduced = true))
        assertSame(base, base.predictiveBackSlideTransform(progress = -0.2f, reduced = false))
        assertNotEquals(base, base.predictiveBackSlideTransform(progress = 1.5f, reduced = false))
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
