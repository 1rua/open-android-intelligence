package com.openandroidintelligence.conversation.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.LayoutDirection
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

    @Test
    fun expressiveCardUsesMd3DisabledColorsWhenDisabled() {
        val scheme = lightColorScheme()
        lateinit var rootView: View
        var resolvedContentColor = Color.Unspecified

        compose.setContent {
            MaterialTheme(colorScheme = scheme) {
                rootView = LocalView.current
                Box(modifier = Modifier.background(scheme.surface)) {
                    M3ExpressiveCard(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier
                            .size(width = 160.dp, height = 72.dp)
                            .testTag("disabled-card"),
                    ) {
                        resolvedContentColor = LocalContentColor.current
                        Spacer(modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("disabled-card").assertIsNotEnabled()
        assertColor(
            "Disabled M3ExpressiveCard uses 0.38 onSurface content color",
            scheme.onSurface.copy(alpha = 0.38f),
            resolvedContentColor,
        )
        val renderedContainer = capturePixel("disabled-card", rootView)
        assertColor(
            "Disabled M3ExpressiveCard uses 0.12 onSurface container color",
            scheme.onSurface.copy(alpha = 0.12f).compositeOver(scheme.surface),
            renderedContainer,
        )
    }

    @Test
    fun expandableCardWithoutCallbackExposesNoButtonSemantics() {
        val scheme = lightColorScheme()
        var headerContentColor = Color.Unspecified

        compose.setContent {
            MaterialTheme(colorScheme = scheme) {
                M3ExpandableExpressiveCard(
                    expanded = false,
                    onExpandedChange = null,
                    enabled = false,
                    modifier = Modifier.testTag("readonly-expandable"),
                    header = {
                        headerContentColor = LocalContentColor.current
                        Text("ReadOnlyHeader")
                    },
                    expandedContent = { Text("HiddenBody") },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("ReadOnlyHeader")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
        assertColor(
            "Read-only M3ExpandableExpressiveCard keeps full onSurface content color even when enabled=false",
            scheme.onSurface,
            headerContentColor,
        )
    }

    @Test
    fun expandableCardDisabledUsesMd3DisabledColors() {
        val scheme = lightColorScheme()
        lateinit var rootView: View
        var headerContentColor = Color.Unspecified
        var expandToggles = 0

        compose.setContent {
            MaterialTheme(colorScheme = scheme) {
                rootView = LocalView.current
                Box(modifier = Modifier.background(scheme.surface)) {
                    M3ExpandableExpressiveCard(
                        expanded = false,
                        onExpandedChange = { expandToggles++ },
                        enabled = false,
                        modifier = Modifier
                            .width(180.dp)
                            .testTag("disabled-expandable"),
                        header = {
                            headerContentColor = LocalContentColor.current
                            Text(
                                text = "DisabledHeader",
                                color = Color.Transparent,
                            )
                        },
                        expandedContent = { Text("HiddenBody") },
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("DisabledHeader")
            .assertIsNotEnabled()
            .performClick()
        assertEquals(0, expandToggles)
        compose.onNodeWithText("HiddenBody").assertDoesNotExist()
        assertColor(
            "Disabled M3ExpandableExpressiveCard uses 0.38 onSurface content color",
            scheme.onSurface.copy(alpha = 0.38f),
            headerContentColor,
        )
        val renderedContainer = capturePixel("disabled-expandable", rootView)
        assertColor(
            "Disabled M3ExpandableExpressiveCard uses 0.12 onSurface container color",
            scheme.onSurface.copy(alpha = 0.12f).compositeOver(scheme.surface),
            renderedContainer,
        )
    }

    @Test
    fun cardAndButtonIndicationObserveComponentContentColorInsteadOfOuterScope() {
        val scheme = lightColorScheme()
        var cardObserved = Color.Unspecified
        var buttonObserved = Color.Unspecified
        val cardIndication = object : IndicationNodeFactory {
            override fun create(interactionSource: InteractionSource): DelegatableNode =
                object : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
                    override fun ContentDrawScope.draw() {
                        cardObserved = currentValueOf(LocalContentColor)
                        drawContent()
                    }
                }

            override fun equals(other: Any?): Boolean = this === other
            override fun hashCode(): Int = System.identityHashCode(this)
        }
        val buttonIndication = object : IndicationNodeFactory {
            override fun create(interactionSource: InteractionSource): DelegatableNode =
                object : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
                    override fun ContentDrawScope.draw() {
                        buttonObserved = currentValueOf(LocalContentColor)
                        drawContent()
                    }
                }

            override fun equals(other: Any?): Boolean = this === other
            override fun hashCode(): Int = System.identityHashCode(this)
        }

        lateinit var rootView: View
        compose.setContent {
            rootView = LocalView.current
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalContentColor provides Color.Magenta) {
                    Column {
                        CompositionLocalProvider(LocalIndication provides cardIndication) {
                            M3ExpressiveCard(
                                onClick = {},
                                containerColor = scheme.primary,
                                contentColor = scheme.onPrimary,
                                modifier = Modifier.testTag("indication-card"),
                            ) {
                                Text("Card")
                            }
                        }
                        CompositionLocalProvider(LocalIndication provides buttonIndication) {
                            M3MorphingButton(
                                onClick = {},
                                colors = M3MorphingButtonDefaults.primaryColors(),
                                modifier = Modifier.testTag("indication-button"),
                            ) {
                                Text("Btn")
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        captureBitmap("indication-card", rootView)
        captureBitmap("indication-button", rootView)
        assertColor("Card LocalIndication receives component contentColor (onPrimary)", scheme.onPrimary, cardObserved)
        assertColor("Button LocalIndication receives component contentColor (onPrimary)", scheme.onPrimary, buttonObserved)
    }

    @Test
    fun expandableCardDisabledDoesNotDimExpandedBodyAndReadOnlyIgnoresStateDescription() {
        val scheme = lightColorScheme()
        var disabledHeaderColor = Color.Unspecified
        var disabledBodyColor = Color.Unspecified

        compose.setContent {
            MaterialTheme(colorScheme = scheme) {
                Column {
                    M3ExpandableExpressiveCard(
                        expanded = true,
                        onExpandedChange = {},
                        enabled = false,
                        header = {
                            disabledHeaderColor = LocalContentColor.current
                            Text("DisabledHeader")
                        },
                        expandedContent = {
                            disabledBodyColor = LocalContentColor.current
                            Text("ExpandedBodyKeepFullAlpha")
                        },
                    )
                    M3ExpandableExpressiveCard(
                        expanded = false,
                        onExpandedChange = null,
                        toggleStateDescription = "Collapsed",
                        header = { Text("ReadOnlyWithStateDesc") },
                        expandedContent = { Text("HiddenBody") },
                    )
                }
            }
        }
        compose.waitForIdle()

        assertColor(
            "Disabled expandable header uses 0.38 onSurface",
            scheme.onSurface.copy(alpha = 0.38f),
            disabledHeaderColor,
        )
        assertColor(
            "Expanded body keeps full contentColor even when header toggle is disabled",
            scheme.onSurface,
            disabledBodyColor,
        )
        compose.onNodeWithText("ReadOnlyWithStateDesc")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun morphingIconButtonSupportsSingleFixedWidthAndAvoidsDuplicateOnClickLabel() {
        var morphed by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Row {
                    M3MorphingButton(
                        onClick = { morphed = !morphed },
                        morphed = morphed,
                        restingWidth = 96.dp,
                        morphedWidth = null,
                        modifier = Modifier.testTag("single-width-btn"),
                    ) { isMorphed ->
                        Text(if (isMorphed) "Expanded Wide Content Label" else "Go")
                    }
                    M3MorphingIconButton(
                        onClick = {},
                        icon = Icons.Default.ArrowUpward,
                        contentDescription = "SendMessage",
                        modifier = Modifier.testTag("no-dup-label-icon"),
                    )
                }
            }
        }
        compose.waitForIdle()

        val widthBtn = compose.onNodeWithTag("single-width-btn")
        widthBtn.assertWidthIsEqualTo(96.dp)
        widthBtn.performClick()
        compose.waitForIdle()
        val morphedWidth = widthBtn.fetchSemanticsNode().boundsInRoot.width
        val restPx = with(compose.density) { 96.dp.toPx() }
        assertTrue(
            "Morphed content width should expand beyond restingWidth ($restPx -> $morphedWidth)",
            morphedWidth > restPx,
        )

        val iconNode = compose.onNodeWithTag("no-dup-label-icon").fetchSemanticsNode()
        assertEquals(listOf("SendMessage"), iconNode.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(null, iconNode.config.getOrNull(SemanticsActions.OnClick)?.label)
    }

    @Test
    fun horizontalDisclosureRevealAnimatesLeftToRightWithoutReflowAndMirrorsInRtl() {
        val backdrop = Color(0xFFFF0000)
        val cardColor = Color(0xFF0000FF)
        lateinit var rootView: View
        var childRecompositions = 0

        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                MaterialTheme {
                    rootView = LocalView.current
                    Box(Modifier.size(width = 200.dp, height = 48.dp).background(backdrop).testTag("reveal-host-ltr")) {
                        HorizontalDisclosureReveal(
                            animateEntrance = true,
                            cornerRadius = 0.dp,
                            modifier = Modifier.size(width = 200.dp, height = 48.dp).testTag("reveal-ltr"),
                        ) {
                            SideEffect { childRecompositions++ }
                            Box(Modifier.size(width = 200.dp, height = 48.dp).background(cardColor))
                        }
                    }
                }
            }
        }
        // 推进 4 帧（前 2 帧完成首组合与启动时间戳登记，后 2 帧推进约 32ms，弹簧处于 ~21%-36% 展开区间）：
        // 左侧已显现卡片色，右侧尚未揭示仍透出红色底板，且首帧测量宽度稳定
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        val earlyBounds = compose.onNodeWithTag("reveal-ltr").fetchSemanticsNode().boundsInRoot
        val expectedWidthPx = with(compose.density) { 200.dp.toPx() }
        assertEquals("水平揭示首帧测量宽度必须稳定，避免内部文本重排", expectedWidthPx, earlyBounds.width, 1f)
        val earlyMap = captureBitmap("reveal-host-ltr", rootView)
        val leftPixel = earlyMap[(earlyMap.width * 0.1f).toInt(), earlyMap.height / 2]
        val rightPixel = earlyMap[(earlyMap.width * 0.9f).toInt(), earlyMap.height / 2]
        assertTrue("LTR 早段左侧应已显露出蓝色卡片: left=$leftPixel", leftPixel.blue > 0.25f)
        assertTrue("LTR 早段右侧应仍被裁切保留红色底板: right=$rightPixel", rightPixel.red > 0.95f && rightPixel.blue < 0.05f)
        val recompositionsBeforeSettle = childRecompositions

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("水平揭示逐帧仅在绘制层生效，不得触发子树重组", recompositionsBeforeSettle, childRecompositions)
        val settledMap = captureBitmap("reveal-host-ltr", rootView)
        assertColor("LTR 收敛后右侧应完全显现", cardColor, settledMap[(settledMap.width * 0.9f).toInt(), settledMap.height / 2])
    }

    @Test
    fun horizontalDisclosureRevealMirrorsRightToLeftInRtlLayout() {
        val backdrop = Color(0xFFFF0000)
        val cardColor = Color(0xFF0000FF)
        lateinit var rootView: View

        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalMotionPolicy provides MotionPolicy(reduceMotion = false),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                MaterialTheme {
                    rootView = LocalView.current
                    Box(Modifier.size(width = 200.dp, height = 48.dp).background(backdrop).testTag("reveal-host-rtl")) {
                        HorizontalDisclosureReveal(
                            animateEntrance = true,
                            cornerRadius = 0.dp,
                            modifier = Modifier.size(width = 200.dp, height = 48.dp),
                        ) {
                            Box(Modifier.size(width = 200.dp, height = 48.dp).background(cardColor))
                        }
                    }
                }
            }
        }
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        val earlyMap = captureBitmap("reveal-host-rtl", rootView)
        val leftPixel = earlyMap[(earlyMap.width * 0.1f).toInt(), earlyMap.height / 2]
        val rightPixel = earlyMap[(earlyMap.width * 0.9f).toInt(), earlyMap.height / 2]
        assertTrue("RTL 早段右侧应先显露蓝色卡片: right=$rightPixel", rightPixel.blue > 0.25f)
        assertTrue("RTL 早段左侧应仍被裁切保留红色底板: left=$leftPixel", leftPixel.red > 0.95f && leftPixel.blue < 0.05f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test
    fun horizontalDisclosureRevealDegradesToNoDisplacementFadeInReduceMotionAndSkipsWhenDisabled() {
        val backdrop = Color(0xFFFF0000)
        val cardColor = Color(0xFF0000FF)
        lateinit var rootView: View

        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = true)) {
                MaterialTheme {
                    rootView = LocalView.current
                    Column {
                        Box(Modifier.size(width = 200.dp, height = 48.dp).background(backdrop).testTag("reveal-reduced-host")) {
                            HorizontalDisclosureReveal(
                                animateEntrance = true,
                                cornerRadius = 0.dp,
                                modifier = Modifier.size(width = 200.dp, height = 48.dp),
                            ) {
                                Box(Modifier.size(width = 200.dp, height = 48.dp).background(cardColor))
                            }
                        }
                        Box(Modifier.size(width = 200.dp, height = 48.dp).background(backdrop).testTag("reveal-disabled-host")) {
                            HorizontalDisclosureReveal(
                                animateEntrance = false,
                                cornerRadius = 0.dp,
                                modifier = Modifier.size(width = 200.dp, height = 48.dp),
                            ) {
                                Box(Modifier.size(width = 200.dp, height = 48.dp).background(cardColor))
                            }
                        }
                    }
                }
            }
        }
        // 首帧未推进前，animateEntrance=false 必须已完整不透明呈现
        val disabledMap = captureBitmap("reveal-disabled-host", rootView)
        assertColor("animateEntrance=false 左侧应立即完整呈现", cardColor, disabledMap[(disabledMap.width * 0.1f).toInt(), disabledMap.height / 2])
        assertColor("animateEntrance=false 右侧应立即完整呈现", cardColor, disabledMap[(disabledMap.width * 0.9f).toInt(), disabledMap.height / 2])

        // 推进 5 帧（~80ms，150ms 线性淡化中段）：减少动态模式下左右两侧透明度一致，无任何水平裁切
        repeat(5) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        val midReducedMap = captureBitmap("reveal-reduced-host", rootView)
        val midLeft = midReducedMap[(midReducedMap.width * 0.1f).toInt(), midReducedMap.height / 2]
        val midRight = midReducedMap[(midReducedMap.width * 0.9f).toInt(), midReducedMap.height / 2]
        assertTrue("减少动态中段左侧应处于线性半透明淡入: $midLeft", midLeft.blue in 0.15f..0.85f)
        assertTrue("减少动态中段右侧应处于线性半透明淡入: $midRight", midRight.blue in 0.15f..0.85f)
        assertEquals("减少动态模式下左右两端不得存在水平裁切差值", midLeft.blue, midRight.blue, 0.03f)

        // 推进超过 150ms 后完整收敛为卡片色
        compose.mainClock.advanceTimeBy(120)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val endReducedMap = captureBitmap("reveal-reduced-host", rootView)
        assertColor("减少动态 150ms 后应完全淡入", cardColor, endReducedMap[(endReducedMap.width * 0.9f).toInt(), endReducedMap.height / 2])
        compose.mainClock.autoAdvance = true
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
