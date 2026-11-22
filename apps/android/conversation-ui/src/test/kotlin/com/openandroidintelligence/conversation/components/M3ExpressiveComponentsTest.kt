package com.openandroidintelligence.conversation.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.motion.AppSharedTransitionHost
import com.openandroidintelligence.conversation.motion.ProvideSharedVisibilityScope
import com.openandroidintelligence.conversation.motion.SharedTransitionKeys
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class M3ExpressiveComponentsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun expressiveCardClickDisabledGatingTouchTargetAndSemantics() {
        var clicks = 0
        var enabled by mutableStateOf(true)

        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Column {
                    M3ExpressiveCard(
                        modifier = Modifier.testTag("static-card"),
                        onClick = null,
                    ) {
                        Text("Static Card")
                    }
                    M3ExpressiveCard(
                        modifier = Modifier.testTag("action-card"),
                        onClick = { clicks++ },
                        enabled = enabled,
                        onClickLabel = "Open item",
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Box(Modifier.size(8.dp))
                    }
                }
            }
        }

        // 无 onClick 时不得挂载 clickable 语义
        compose.onNodeWithTag("static-card")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))

        val actionCard = compose.onNodeWithTag("action-card")
        actionCard.assertIsEnabled()
        actionCard.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
        )
        actionCard.assert(
            SemanticsMatcher("OnClick label matches") { node ->
                node.config[SemanticsActions.OnClick].label == "Open item"
            },
        )
        actionCard.assertWidthIsAtLeast(48.dp)
        actionCard.assertHeightIsAtLeast(48.dp)

        actionCard.performClick()
        assertEquals(1, clicks)

        compose.runOnIdle { enabled = false }
        actionCard.assertIsNotEnabled()
        actionCard.performClick()
        assertEquals("Disabled card must block click callbacks", 1, clicks)
    }

    @Test
    fun expressiveCardSelectedStateAndPressMorphWithOvershootClamp() {
        var selected by mutableStateOf(false)
        val scheme = lightColorScheme()
        lateinit var rootView: View

        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                MaterialTheme(colorScheme = scheme) {
                    rootView = LocalView.current
                    Box(Modifier.width(240.dp).background(scheme.background)) {
                        M3ExpressiveCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("morph-card"),
                            onClick = {},
                            selected = selected,
                            restingCornerRadius = 0.dp,
                            pressedCornerRadius = AppRadius.Large,
                            activeCornerRadius = AppRadius.Large,
                        ) {
                            Text("Selectable Card")
                        }
                    }
                }
            }
        }

        val restingCenter = capturePixel("morph-card", rootView)
        assertColor("Resting card uses surfaceContainer", scheme.surfaceContainer, restingCenter)

        // 按下触发微缩放与圆角弹簧插值，0dp 起步也不应因过冲崩溃
        val cardNode = compose.onNodeWithTag("morph-card")
        cardNode.performTouchInput { down(center) }
        compose.waitForIdle()
        cardNode.performTouchInput { up() }
        compose.waitForIdle()

        compose.runOnIdle { selected = true }
        compose.waitForIdle()
        val selectedCenter = capturePixel("morph-card", rootView)
        assertColor("Selected card uses secondaryContainer", scheme.secondaryContainer, selectedCenter)
    }

    @Test
    fun expressiveCardRespectsReduceMotionAndDurationScaleZero() {
        var selected by mutableStateOf(false)
        var clicks = 0
        val scheme = darkColorScheme()
        lateinit var rootView: View

        compose.setContent {
            CompositionLocalProvider(
                LocalMotionPolicy provides MotionPolicy(reduceMotion = false, durationScale = 0f),
            ) {
                MaterialTheme(colorScheme = scheme) {
                    rootView = LocalView.current
                    Box(Modifier.width(240.dp).background(scheme.background)) {
                        M3ExpressiveCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("reduced-card"),
                            onClick = { clicks++ },
                            selected = selected,
                            restingCornerRadius = 0.dp,
                            activeCornerRadius = AppRadius.Large,
                        ) {
                            Text("Reduced Card")
                        }
                    }
                }
            }
        }

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = true }
        // 逐帧推进并等待 Looper 排空（重组 -> LaunchedEffect 启动 snap() -> 应用 24dp 圆角），48ms 仍远早于弹簧收敛
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        // 空间属性（圆角）在 reduced 模式下使用 snap()，立即切至选中圆角露出的背景色
        val cornerPixel = capturePixelAt("reduced-card", rootView, x = 2, y = 2)
        assertColor(
            "durationScale=0f must snap corner radius immediately",
            scheme.background,
            cornerPixel,
        )
        // 效果属性（颜色）在 reduced 模式下走 150ms reducedFade()，推进 160ms 后应完整抵达目标色
        compose.mainClock.advanceTimeBy(160)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val centerPixel = capturePixel("reduced-card", rootView)
        assertColor(
            "durationScale=0f must finish reducedFade to selected color within 160ms",
            scheme.secondaryContainer,
            centerPixel,
        )
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("reduced-card").performClick()
        assertEquals(1, clicks)
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Test
    fun expandableCardIsolatesHeaderClicksAndSupportsSelectionInBody() {
        var expanded by mutableStateOf(false)
        var innerActionCount = 0

        compose.setContent {
            MaterialTheme {
                AppSharedTransitionHost {
                    AnimatedVisibility(visible = true) {
                        ProvideSharedVisibilityScope {
                            M3ExpandableExpressiveCard(
                                expanded = expanded,
                                onExpandedChange = { expanded = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("expandable-card"),
                                collapsedCornerRadius = 0.dp,
                                expandedCornerRadius = AppRadius.Medium,
                                sharedBoundsKey = SharedTransitionKeys.expandableCard("unit-test"),
                                toggleClickLabel = "Toggle details",
                                toggleStateDescription = if (expanded) "Expanded" else "Collapsed",
                                header = {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp)
                                            .testTag("expandable-header"),
                                    ) {
                                        Text("Card Header")
                                    }
                                },
                                expandedContent = {
                                    Column(Modifier.padding(12.dp)) {
                                        SelectionContainer {
                                            Text("Selectable Body")
                                        }
                                        Button(
                                            onClick = { innerActionCount++ },
                                            modifier = Modifier.testTag("inner-copy-action"),
                                        ) {
                                            Text("Copy")
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        val headerNode = compose.onNodeWithText("Card Header")
        headerNode.assertHeightIsAtLeast(48.dp)
        headerNode.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
        )
        headerNode.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"),
        )
        compose.onNodeWithText("Selectable Body").assertDoesNotExist()

        headerNode.performClick()
        compose.waitForIdle()
        assertTrue(expanded)
        headerNode.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"),
        )
        compose.onNodeWithText("Selectable Body").assertIsDisplayed()

        // 展开区内的按钮点击不得误触折叠头部
        compose.onNodeWithTag("inner-copy-action").performClick()
        compose.waitForIdle()
        assertEquals(1, innerActionCount)
        assertTrue("Inner body click must not collapse card", expanded)

        headerNode.performClick()
        compose.waitForIdle()
        assertFalse(expanded)
        compose.onNodeWithText("Selectable Body").assertDoesNotExist()
    }

    @Test
    fun expandableCardDisabledGatingAndReduceMotion() {
        var expanded by mutableStateOf(false)

        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = true)) {
                MaterialTheme {
                    M3ExpandableExpressiveCard(
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        enabled = false,
                        header = {
                            Text("Locked Header", modifier = Modifier.padding(12.dp))
                        },
                        expandedContent = {
                            Text("Hidden Body")
                        },
                    )
                }
            }
        }

        compose.onNodeWithText("Locked Header")
            .assertIsNotEnabled()
            .performClick()
        assertFalse("Disabled expandable card must ignore clicks", expanded)
        compose.onNodeWithText("Hidden Body").assertDoesNotExist()
    }

    @Test
    fun morphingButtonWidthCornerClampColorsAndDisabledGating() {
        var morphed by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        var clicks = 0

        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                M3MorphingButton(
                    onClick = { clicks++ },
                    modifier = Modifier.testTag("morph-btn"),
                    morphed = morphed,
                    enabled = enabled,
                    restingWidth = 48.dp,
                    morphedWidth = 64.dp,
                    restingCornerPercent = 50f,
                    morphedCornerPercent = 0f,
                    pressedCornerPercent = 0f,
                    colors = M3MorphingButtonDefaults.sendStopColors(),
                    onClickLabel = "Toggle action",
                ) { isMorphed ->
                    Text(if (isMorphed) "Stop" else "Go")
                }
            }
        }

        val button = compose.onNodeWithTag("morph-btn")
        button.assertIsEnabled()
        button.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        button.assertWidthIsEqualTo(48.dp)
        button.assertHeightIsAtLeast(48.dp)

        button.performClick()
        assertEquals(1, clicks)

        // 按下并取消后切换至 0f 圆角时，coerceIn(0f, 50f) 保证不崩溃且宽度到达目标值
        button.performTouchInput { down(center) }
        compose.waitForIdle()
        button.performTouchInput { cancel() }
        compose.runOnIdle { morphed = true }
        compose.waitForIdle()
        button.assertWidthIsEqualTo(64.dp)
        compose.onNodeWithText("Stop").assertIsDisplayed()

        compose.runOnIdle { enabled = false }
        button.assertIsNotEnabled()
        button.performClick()
        assertEquals("Disabled morphing button must block clicks", 1, clicks)
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Test
    fun morphingIconButtonSwapsIconAndDegradesInReduceMotion() {
        var morphed by mutableStateOf(false)
        var clicks = 0

        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = true)) {
                MaterialTheme {
                    AppSharedTransitionHost {
                        AnimatedVisibility(visible = true) {
                            ProvideSharedVisibilityScope {
                                M3MorphingIconButton(
                                    onClick = {
                                        clicks++
                                        morphed = !morphed
                                    },
                                    icon = Icons.Default.ArrowUpward,
                                    contentDescription = "Send",
                                    morphed = morphed,
                                    morphedIcon = Icons.Default.Stop,
                                    morphedContentDescription = "Stop",
                                    restingWidth = 48.dp,
                                    morphedWidth = 56.dp,
                                    colors = M3MorphingButtonDefaults.tonalColors(),
                                    sharedBoundsKey = SharedTransitionKeys.morphingButton("test-icon"),
                                    modifier = Modifier.testTag("morph-icon-btn"),
                                )
                            }
                        }
                    }
                }
            }
        }

        val node = compose.onNodeWithTag("morph-icon-btn")
        node.assertWidthIsEqualTo(48.dp)
        node.assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()

        node.performClick()
        compose.waitForIdle()

        assertEquals(1, clicks)
        node.assertWidthIsEqualTo(56.dp)
        compose.onNodeWithContentDescription("Stop").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertDoesNotExist()
    }

    @Test
    fun morphingButtonAutoWidthSupportsContentResize() {
        var morphed by mutableStateOf(false)

        compose.setContent {
            MaterialTheme {
                M3MorphingButton(
                    onClick = { morphed = !morphed },
                    modifier = Modifier.testTag("auto-morph-btn"),
                    morphed = morphed,
                    restingWidth = null,
                    colors = M3MorphingButtonDefaults.primaryColors(),
                ) { isMorphed ->
                    Text(if (isMorphed) "Expanded Action Label" else "Go")
                }
            }
        }

        val button = compose.onNodeWithTag("auto-morph-btn")
        val initialWidth = button.fetchSemanticsNode().boundsInRoot.width
        button.performClick()
        compose.waitForIdle()
        val expandedWidth = button.fetchSemanticsNode().boundsInRoot.width
        assertTrue(
            "Auto-width morphing button should grow for wider content ($initialWidth -> $expandedWidth)",
            expandedWidth > initialWidth,
        )
    }

    @Test
    fun expandableCardCentersHeaderVerticallyInsideTouchTarget() {
        compose.setContent {
            MaterialTheme {
                M3ExpandableExpressiveCard(
                    expanded = false,
                    onExpandedChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("center-card"),
                    header = {
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                text = "Centered Header",
                                modifier = Modifier.testTag("center-text"),
                            )
                        }
                    },
                    expandedContent = {},
                )
            }
        }

        val cardBounds = compose.onNodeWithTag("center-card").fetchSemanticsNode().boundsInRoot
        val textBounds = compose.onNodeWithTag("center-text", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val verticalOffset = kotlin.math.abs(textBounds.center.y - cardBounds.center.y)
        assertTrue(
            "Expandable card header should be vertically centered within 48dp touch target (offset=$verticalOffset)",
            verticalOffset <= 2f,
        )
    }

    @Test
    fun expressiveCardResolvesTonalElevationBeforeColorAnimation() {
        val scheme = darkColorScheme()
        val elevatedSurface = scheme.surfaceColorAtElevation(8.dp)
        var selected by mutableStateOf(false)
        lateinit var rootView: View

        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                MaterialTheme(colorScheme = scheme) {
                    rootView = LocalView.current
                    Box(Modifier.width(240.dp).background(scheme.background)) {
                        M3ExpressiveCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("tonal-card"),
                            onClick = {},
                            selected = selected,
                            containerColor = scheme.surface,
                            selectedContainerColor = scheme.secondaryContainer,
                            tonalElevation = 8.dp,
                        ) {
                            Text("Tonal Card")
                        }
                    }
                }
            }
        }

        val resting = capturePixel("tonal-card", rootView)
        assertColor("Resting tonal surface card uses surfaceColorAtElevation", elevatedSurface, resting)

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = true }
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        val midTransition = capturePixel("tonal-card", rootView)
        assertTrue(
            "Mid-transition tonal card must stay >= elevatedSurface (${elevatedSurface.red}) without dropping to flat surface (${scheme.surface.red}), actual=${midTransition.red}",
            midTransition.red >= elevatedSurface.red - 0.02f,
        )
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val settled = capturePixel("tonal-card", rootView)
        assertColor("Selected tonal card settles to secondaryContainer", scheme.secondaryContainer, settled)
    }

    private fun capturePixel(tag: String, rootView: View): Color {
        val map = captureBitmap(tag, rootView)
        return map[map.width / 2, map.height / 2]
    }

    private fun capturePixelAt(tag: String, rootView: View, x: Int, y: Int): Color {
        val map = captureBitmap(tag, rootView)
        return map[x.coerceIn(0, map.width - 1), y.coerceIn(0, map.height - 1)]
    }

    private fun captureBitmap(tag: String, rootView: View): PixelMap {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(
                bounds.width.toInt().coerceAtLeast(1),
                bounds.height.toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            rootView.draw(canvas)
            bitmap.asImageBitmap()
        }.toPixelMap()
    }

    private fun assertColor(message: String, expected: Color, actual: Color) {
        assertEquals(message, expected.red, actual.red, 0.03f)
        assertEquals(message, expected.green, actual.green, 0.03f)
        assertEquals(message, expected.blue, actual.blue, 0.03f)
    }
}
