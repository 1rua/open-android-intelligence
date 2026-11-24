package com.openandroidintelligence.conversation.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsItemMorphTest {
    @get:Rule
    val compose = createComposeRule()
    private lateinit var contentView: View

    @Test
    fun lightThemePressMorphsContinuouslyAndClipsRipple() = verifyPress(lightColorScheme(), "浅色")

    @Test
    fun darkThemePressMorphsContinuouslyAndClipsRipple() = verifyPress(darkColorScheme(), "深色")

    private fun verifyPress(scheme: ColorScheme, name: String) {
        var clicks = 0
        compose.setContent {
            Fixture(scheme) {
                SettingsNavigationItem("外观与动效", onClick = { clicks++ }, modifier = Modifier.testTag("item"))
            }
        }
        val tapTimeout = ViewConfiguration.getTapTimeout().toLong().coerceAtLeast(32L)
        val resting = capture("$name-默认")
        compose.mainClock.autoAdvance = false
        val row = compose.onNodeWithTag("item")
        row.performTouchInput { down(Offset(12f, center.y)) }
        compose.mainClock.advanceTimeBy(tapTimeout / 2)
        val debouncing = capture("$name-消抖中")
        assertColor("消抖窗口内按下不得提前触发胶囊形变", resting[resting.width / 2, 8], debouncing[debouncing.width / 2, 8])

        compose.mainClock.advanceTimeBy(tapTimeout + 48)
        val moving = capture("$name-形变中")
        assertTrue("跨过消抖窗口后必须出现可见的背景过渡", moving[moving.width / 2, 8] != resting[resting.width / 2, 8])
        compose.mainClock.advanceTimeBy(1000)
        val pressed = capture("$name-长按")
        assertTrue("动画中间帧不能直接跳到最终胶囊", moving[12, 12] != pressed[12, 12])
        assertColor("胶囊角落不能被背景或波纹填成矩形", resting[4, 4], pressed[4, 4])
        assertEquals("长按期间不得提前触发点击", 0, clicks)
        row.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1200)
        assertEquals("松手后点击只触发一次", 1, clicks)
        val released = capture("$name-松开")
        assertColor("松手后恢复默认背景", resting[resting.width / 2, 8], released[released.width / 2, 8])
    }

    @Test
    fun quickCancelWithinTapTimeoutDoesNotTriggerMorph() {
        val scheme = lightColorScheme()
        var clicks = 0
        compose.setContent {
            Fixture(scheme) {
                SettingsNavigationItem("外观与动效", onClick = { clicks++ }, modifier = Modifier.testTag("item"))
            }
        }
        val tapTimeout = ViewConfiguration.getTapTimeout().toLong().coerceAtLeast(32L)
        val resting = capture("消抖取消-默认")
        compose.mainClock.autoAdvance = false
        val row = compose.onNodeWithTag("item")
        row.performTouchInput { down(Offset(12f, center.y)) }
        compose.mainClock.advanceTimeBy(tapTimeout / 2)
        row.performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(tapTimeout + 200)
        val cancelled = capture("消抖取消-后")
        assertEquals("取消手势不得触发点击", 0, clicks)
        assertColor("消抖窗口内取消应全程保持默认背景", resting[resting.width / 2, 8], cancelled[cancelled.width / 2, 8])
    }

    @Test
    fun verticalDragInsideScrollableContainerDoesNotTriggerMorphOrClick() {
        val scheme = lightColorScheme()
        var clicks = 0
        lateinit var scrollState: ScrollState
        compose.setContent {
            MaterialTheme(colorScheme = scheme, shapes = Shapes(medium = RoundedCornerShape(4.dp))) {
                contentView = LocalView.current
                scrollState = rememberScrollState()
                Box(Modifier.width(320.dp).height(240.dp).background(scheme.background)) {
                    Column(
                        Modifier
                            .width(320.dp)
                            .height(240.dp)
                            .verticalScroll(scrollState)
                            .testTag("scroll-container"),
                    ) {
                        SettingsSectionCard(contentPadding = PaddingValues(8.dp)) {
                            repeat(10) { index ->
                                SettingsNavigationItem(
                                    headline = "设置条目 $index",
                                    onClick = { clicks++ },
                                    modifier = Modifier.testTag("item-$index"),
                                )
                            }
                        }
                    }
                }
            }
        }
        val tapTimeout = ViewConfiguration.getTapTimeout().toLong().coerceAtLeast(32L)
        val resting = capture("滑动-默认", tag = "item-1")
        compose.mainClock.autoAdvance = false
        val row = compose.onNodeWithTag("item-1")
        row.performTouchInput {
            down(center)
            advanceEventTime(16)
            moveBy(Offset(0f, -12f))
            advanceEventTime(16)
            moveBy(Offset(0f, -20f))
        }
        compose.mainClock.advanceTimeBy(tapTimeout + 120)
        assertTrue("纵向拖动应产生真实滚动位移", scrollState.value > 0)
        assertEquals("滚动手势不得触发条目点击", 0, clicks)
        val whileScrolling = capture("滑动-未形变", tag = "item-1")
        assertColor("滑动途中列表项不得误触发胶囊形变", resting[resting.width / 2, 8], whileScrolling[whileScrolling.width / 2, 8])
        row.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(200)
        assertEquals("滑动松手后不得触发点击", 0, clicks)
    }

    @Test
    fun quickTapWithinTapTimeoutStillTriggersClickAndMorphFeedback() {
        val scheme = lightColorScheme()
        var clicks = 0
        compose.setContent {
            Fixture(scheme) {
                SettingsNavigationItem("外观与动效", onClick = { clicks++ }, modifier = Modifier.testTag("item"))
            }
        }
        val resting = capture("快点-默认")
        compose.mainClock.autoAdvance = false
        val row = compose.onNodeWithTag("item")
        row.performTouchInput { down(Offset(12f, center.y)) }
        compose.mainClock.advanceTimeBy(24)
        row.performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("消抖窗口内短按抬手必须立即触发点击", 1, clicks)
        compose.mainClock.advanceTimeBy(64)
        val pulsing = capture("快点-脉冲")
        assertTrue("短按点击后应补发可见形变脉冲反馈", pulsing[pulsing.width / 2, 8] != resting[resting.width / 2, 8])
        assertColor("短按脉冲期间胶囊角落保持裁剪", resting[4, 4], pulsing[4, 4])
        compose.mainClock.advanceTimeBy(1200)
        val settled = capture("快点-复原")
        assertColor("脉冲结束后恢复默认背景", resting[resting.width / 2, 8], settled[settled.width / 2, 8])
    }

    @Test
    fun switchPressIsClippedAndCancellationDoesNotToggle() {
        val scheme = lightColorScheme()
        var changes = 0
        compose.setContent {
            Fixture(scheme) {
                SettingsSwitchItem("系统动态取色", checked = false, onCheckedChange = { changes++ }, modifier = Modifier.testTag("item"))
            }
        }
        val resting = capture("开关-默认")
        compose.mainClock.autoAdvance = false
        val row = compose.onNodeWithTag("item")
        row.performTouchInput { down(Offset(12f, center.y)) }
        compose.mainClock.advanceTimeBy(1200)
        val pressed = capture("开关-长按")
        assertTrue("整行开关的按下状态也必须高亮", pressed[pressed.width / 2, 8] != resting[resting.width / 2, 8])
        assertColor("开关行波纹不能越过胶囊角落", resting[4, 4], pressed[4, 4])
        row.performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(1200)
        val cancelled = capture("开关-取消")
        assertEquals("取消手势不得切换开关", 0, changes)
        assertColor("取消后恢复默认背景", resting[resting.width / 2, 8], cancelled[cancelled.width / 2, 8])
    }

    @Test
    fun selectionUsesSecondaryContainerAndRestoresThemeCorners() {
        val selected = mutableStateOf(false)
        val scheme = darkColorScheme()
        compose.setContent {
            Fixture(scheme) {
                SettingsListItem("当前 Agent", selected = selected.value, onClick = {}, modifier = Modifier.testTag("item"))
            }
        }
        val resting = capture("选中-默认")
        compose.runOnIdle { selected.value = true }
        val active = capture("选中-胶囊")
        assertColor("选中条目使用主题次级容器色", scheme.secondaryContainer, active[active.width / 2, 8])
        assertColor("选中胶囊的角落保持裁剪", resting[4, 4], active[4, 4])
        assertColor("默认的小圆角内侧会在胶囊态让出背景", scheme.surfaceContainer, active[4, 8])
        compose.runOnIdle { selected.value = false }
        val unselected = capture("选中-恢复")
        assertColor("取消选中后恢复默认背景", resting[resting.width / 2, 8], unselected[unselected.width / 2, 8])
    }

    @Test
    fun reducedMotionStillShowsClippedPressedShape() {
        val scheme = lightColorScheme()
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = true)) {
                Fixture(scheme) {
                    SettingsNavigationItem("外观与动效", onClick = {}, modifier = Modifier.testTag("item"))
                }
            }
        }
        val tapTimeout = ViewConfiguration.getTapTimeout().toLong().coerceAtLeast(32L)
        val resting = capture("减少动态-默认")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("item").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(tapTimeout + 32)
        val pressed = capture("减少动态-按下")
        assertTrue("减少动态仍提供高亮反馈", pressed[pressed.width / 2, 8] != resting[resting.width / 2, 8])
        assertColor("减少动态时也必须裁剪胶囊角落", resting[4, 4], pressed[4, 4])
        compose.onNodeWithTag("item").performTouchInput { cancel() }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun reusedItemOutsideSettingsKeepsItsOriginalSelectedBackground() {
        val scheme = lightColorScheme()
        compose.setContent {
            MaterialTheme(colorScheme = scheme) {
                contentView = LocalView.current
                SettingsListItem("附件库", selected = true, onClick = {}, modifier = Modifier.width(320.dp).testTag("item"))
            }
        }
        val image = capture("非设置-原样")
        assertColor("分组外复用条目保留既有背景", scheme.surfaceContainerHighest, image[4, 4])
    }

    @Composable
    private fun Fixture(scheme: ColorScheme, content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = scheme, shapes = Shapes(medium = RoundedCornerShape(4.dp))) {
            contentView = LocalView.current
            Box(Modifier.width(320.dp).background(scheme.background)) {
                SettingsSectionCard(contentPadding = PaddingValues(8.dp)) { content() }
            }
        }
    }

    private fun capture(name: String, tag: String = "item"): PixelMap {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        // Robolectric 没有真实窗口帧回调，直接绘制 View 树，仍包含 Compose 的裁剪与波纹。
        val image = compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            contentView.draw(canvas)
            bitmap.asImageBitmap()
        }
        val file = File("build/reports/settings-item-morph/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return image.toPixelMap()
    }

    @Test
    fun secondPressDuringQuickTapPulseResetsHighlightDuringDebounce() {
        val scheme = lightColorScheme()
        var clicks = 0
        compose.setContent {
            Fixture(scheme) {
                SettingsNavigationItem("外观与动效", onClick = { clicks++ }, modifier = Modifier.testTag("item"))
            }
        }
        val tapTimeout = ViewConfiguration.getTapTimeout().toLong().coerceAtLeast(32L)
        val resting = capture("连按消抖-默认")
        compose.mainClock.autoAdvance = false
        val row = compose.onNodeWithTag("item")
        // 首次短按抬手后进入 90ms 脉冲窗口
        row.performTouchInput { down(Offset(12f, center.y)) }
        compose.mainClock.advanceTimeBy(24)
        row.performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(48)
        assertEquals(1, clicks)
        val firstPulse = capture("连按消抖-首击脉冲")
        assertTrue(firstPulse[firstPulse.width / 2, 8] != resting[resting.width / 2, 8])

        // 脉冲尚未结束时再次按下准备滑动：必须立刻复位按下高亮并在消抖窗内保持收敛而非锁死胶囊
        row.performTouchInput { down(Offset(12f, center.y)) }
        compose.mainClock.advanceTimeBy(tapTimeout / 2)
        val duringSecondDebounce = capture("连按消抖-二次等待")
        // 回弹结束前取消手势：不会锁死，最终平稳恢复静止背景
        row.performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(1200)
        val afterCancel = capture("连按消抖-取消后")
        assertColor("脉冲期二次按压取消后应回到默认背景", resting[resting.width / 2, 8], afterCancel[afterCancel.width / 2, 8])
        assertTrue("二次消抖等待期背景不得停留在满格胶囊高亮", duringSecondDebounce[duringSecondDebounce.width / 2, 8] != scheme.secondaryContainer)
    }

    @Test
    fun clickDisablingItemImmediatelyStillShowsPulseMorphFeedback() {
        val scheme = lightColorScheme()
        var enabled by mutableStateOf(true)
        var clicks = 0
        compose.setContent {
            Fixture(scheme) {
                SettingsNavigationItem(
                    headline = "刷新主机",
                    enabled = enabled,
                    onClick = {
                        clicks++
                        enabled = false
                    },
                    modifier = Modifier.testTag("item"),
                )
            }
        }
        val resting = capture("点击禁用-默认")
        compose.mainClock.autoAdvance = false
        val row = compose.onNodeWithTag("item")
        row.performTouchInput { down(Offset(12f, center.y)) }
        compose.mainClock.advanceTimeBy(24)
        row.performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1, clicks)
        assertTrue(!enabled)
        // 80ms 仍处于 90ms 脉冲窗口内，形变进度已超过 0.5（圆角 > 16px > 13.7px），此时 (4, 4) 与 (1, 1) 均位于圆角外侧
        compose.mainClock.advanceTimeBy(80)
        val pulsing = capture("点击禁用-脉冲中")
        assertTrue("点击当帧同步禁用条目后仍应播完脉冲形变而非瞬间截断", pulsing[pulsing.width / 2, 8] != resting[resting.width / 2, 8])
        assertColor("脉冲期静息与过渡圆角角落保持裁剪", resting[1, 1], pulsing[1, 1])
        assertColor("脉冲期胶囊角落保持裁剪", resting[4, 4], pulsing[4, 4])
        compose.mainClock.advanceTimeBy(1200)
        val settled = capture("点击禁用-复原")
        assertColor("脉冲与水波纹结束后回到静止背景", resting[resting.width / 2, 8], settled[settled.width / 2, 8])
    }

    @Test
    fun reducedMotionReleaseKeepsRippleTailClippedToRoundedCorners() {
        val scheme = lightColorScheme()
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = true)) {
                MaterialTheme(colorScheme = scheme, shapes = Shapes(medium = RoundedCornerShape(16.dp))) {
                    contentView = LocalView.current
                    Box(Modifier.width(320.dp).background(scheme.background)) {
                        SettingsSectionCard(contentPadding = PaddingValues(8.dp)) {
                            SettingsNavigationItem("外观与动效", onClick = {}, modifier = Modifier.testTag("item"))
                        }
                    }
                }
            }
        }
        val tapTimeout = ViewConfiguration.getTapTimeout().toLong().coerceAtLeast(32L)
        val resting = capture("减少动态尾段-默认")
        compose.mainClock.autoAdvance = false
        val row = compose.onNodeWithTag("item")
        row.performTouchInput { down(Offset(12f, center.y)) }
        compose.mainClock.advanceTimeBy(tapTimeout + 48)
        row.performTouchInput { up() }
        // 刚松手进入水波纹淡出尾段（48ms）：减少动态下即使形变分数立刻归零，仍应沿 16dp 静息圆角裁剪波纹
        compose.mainClock.advanceTimeBy(48)
        val fading = capture("减少动态尾段-淡出中")
        assertColor("减少动态松手波纹尾段期间圆角外像素不得染成矩形波纹", resting[1, 1], fading[1, 1])
        compose.mainClock.advanceTimeBy(600)
        val settled = capture("减少动态尾段-收敛")
        assertColor("尾段结束后恢复静止背景", resting[resting.width / 2, 8], settled[settled.width / 2, 8])
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun focusAndHoverHighlightsStayClippedToRestingCorners() {
        val scheme = lightColorScheme()
        lateinit var visualSource: MutableInteractionSource
        compose.setContent {
            MaterialTheme(colorScheme = scheme, shapes = Shapes(medium = RoundedCornerShape(16.dp))) {
                contentView = LocalView.current
                val clickSource = remember { MutableInteractionSource() }
                visualSource = clickSource
                val feedback = rememberSettingsItemPressFeedback(clickSource = clickSource, enabled = true)
                val morph = settingsItemMorphModifier(
                    highlighted = feedback.pressed.value,
                    pressed = feedback.pressed.value,
                    scaleOnPress = false,
                    visualInteractionSource = feedback.visualInteractionSource,
                    clipActive = feedback.clipActive,
                )
                Box(Modifier.width(320.dp).background(scheme.background)) {
                    SettingsSectionCard(contentPadding = PaddingValues(8.dp)) {
                        Box(
                            modifier = Modifier
                                .width(304.dp)
                                .height(56.dp)
                                .testTag("morph-box")
                                .then(morph)
                                .clickable(interactionSource = clickSource, indication = null, onClick = {}),
                        )
                    }
                }
            }
        }
        val resting = capture("焦点悬停-默认", tag = "morph-box")

        // 发送 FocusInteraction.Focus 后，角落 (1,1) 应被静息 16dp 圆角裁剪
        compose.runOnIdle { visualSource.tryEmit(FocusInteraction.Focus()) }
        compose.waitForIdle()
        val focused = capture("焦点悬停-焦点态", tag = "morph-box")
        assertTrue("Focus 态中心区域应呈现状态层高亮", focused[focused.width / 2, focused.height / 2] != resting[resting.width / 2, resting.height / 2])
        assertColor("Focus 状态层必须被静息圆角裁剪", resting[1, 1], focused[1, 1])

        // 切为 HoverInteraction.Enter 后同样保持静息圆角裁剪
        compose.onNodeWithTag("morph-box").performMouseInput { enter(center) }
        compose.waitForIdle()
        val hovered = capture("焦点悬停-悬停态", tag = "morph-box")
        assertColor("Hover 状态层必须被静息圆角裁剪", resting[1, 1], hovered[1, 1])
    }

    @Test
    fun disabledSwitchItemDoesNotTriggerMorphOnTouch() {
        val scheme = lightColorScheme()
        var changes = 0
        compose.setContent {
            Fixture(scheme) {
                SettingsSwitchItem(
                    headline = "系统动态取色",
                    checked = false,
                    enabled = false,
                    onCheckedChange = { changes++ },
                    modifier = Modifier.testTag("item"),
                )
            }
        }
        val resting = capture("禁用开关-默认")
        compose.mainClock.autoAdvance = false
        val row = compose.onNodeWithTag("item")
        row.performTouchInput { down(Offset(12f, center.y)) }
        compose.mainClock.advanceTimeBy(300)
        val pressed = capture("禁用开关-按下")
        assertColor("禁用开关按压不得触发胶囊形变", resting[resting.width / 2, 8], pressed[pressed.width / 2, 8])
        row.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(300)
        assertEquals("禁用开关不得回调切换", 0, changes)
    }

    private fun assertColor(message: String, expected: Color, actual: Color) {
        assertEquals(message, expected.red, actual.red, 0.02f)
        assertEquals(message, expected.green, actual.green, 0.02f)
        assertEquals(message, expected.blue, actual.blue, 0.02f)
    }
}
