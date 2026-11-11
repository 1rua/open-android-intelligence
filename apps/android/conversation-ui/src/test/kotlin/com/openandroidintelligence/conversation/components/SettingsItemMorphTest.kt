package com.openandroidintelligence.conversation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
        val resting = capture("$name-默认")
        compose.mainClock.autoAdvance = false
        val row = compose.onNodeWithTag("item")
        row.performTouchInput { down(Offset(12f, center.y)) }
        compose.mainClock.advanceTimeBy(64)
        val moving = capture("$name-形变中")
        assertTrue("按下后必须出现可见的背景过渡", moving[moving.width / 2, 8] != resting[resting.width / 2, 8])
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
        val resting = capture("减少动态-默认")
        compose.onNodeWithTag("item").performTouchInput { down(center) }
        val pressed = capture("减少动态-按下")
        assertTrue("减少动态仍提供高亮反馈", pressed[pressed.width / 2, 8] != resting[resting.width / 2, 8])
        assertColor("减少动态时也必须裁剪胶囊角落", resting[4, 4], pressed[4, 4])
        compose.onNodeWithTag("item").performTouchInput { cancel() }
    }

    @Test
    fun reusedItemOutsideSettingsKeepsItsOriginalSelectedBackground() {
        val scheme = lightColorScheme()
        compose.setContent {
            MaterialTheme(colorScheme = scheme) {
                SettingsListItem("附件库", selected = true, onClick = {}, modifier = Modifier.width(320.dp).testTag("item"))
            }
        }
        val image = capture("非设置-原样")
        assertColor("分组外复用条目保留既有背景", scheme.surfaceContainerHighest, image[4, 4])
    }

    @Composable
    private fun Fixture(scheme: ColorScheme, content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = scheme, shapes = Shapes(medium = RoundedCornerShape(4.dp))) {
            Box(Modifier.width(320.dp).background(scheme.background)) {
                SettingsSectionCard(contentPadding = PaddingValues(8.dp)) { content() }
            }
        }
    }

    private fun capture(name: String): PixelMap {
        val image = compose.onNodeWithTag("item").captureToImage()
        val file = File("build/reports/settings-item-morph/$name.png")
        file.parentFile.mkdirs()
        file.outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return image.toPixelMap()
    }

    private fun assertColor(message: String, expected: Color, actual: Color) {
        assertEquals(message, expected.red, actual.red, 0.02f)
        assertEquals(message, expected.green, actual.green, 0.02f)
        assertEquals(message, expected.blue, actual.blue, 0.02f)
    }
}
