package com.openandroidintelligence.conversation.selection

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 选区下限的单位语义（C10 功能 bug 回归）。
 *
 * 文案宣称「至少 24dp」，实现就必须按 dp 换算：在 3x 屏幕（xxhdpi）上
 * 24dp = 72px。拖动 20dp 的短选区必须仍然不可确认，拖动 60dp 才可确认——
 * 如果实现把 24 直接当像素用（旧 bug），20dp = 60px 也会被误判为有效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenSelectionMinimumSizeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun screenshot(): ImageBitmap {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(47, 100, 92))
        return bitmap.asImageBitmap()
    }

    private fun showOverlay() {
        compose.setContent {
            MaterialTheme {
                ScreenSelectionOverlay(
                    onCropConfirmed = { _, _, _, _ -> },
                    onCancel = {},
                    screenshot = screenshot(),
                )
            }
        }
    }

    private fun dragSquare(distance: Dp) {
        val px = with(compose.density) { distance.toPx() }
        compose.onNodeWithContentDescription("屏幕圈选").performTouchInput {
            swipe(
                start = Offset(40f, 300f),
                end = Offset(40f + px, 300f + px),
                durationMillis = 200,
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun screenUsesDensityThree() {
        assertEquals(3f, compose.density.density, 0.001f)
    }

    @Test
    fun selectionBelowTwentyFourDpCannotBeConfirmed() {
        showOverlay()
        dragSquare(20.dp)
        compose.onNodeWithText("确认选区").assertIsNotEnabled()
    }

    @Test
    fun selectionAboveTwentyFourDpCanBeConfirmed() {
        showOverlay()
        dragSquare(60.dp)
        compose.onNodeWithText("确认选区").assertIsEnabled()
    }

    @Test
    fun capturingShowsIntermediateStateInsteadOfFailure() {
        compose.setContent {
            MaterialTheme {
                ScreenSelectionOverlay(
                    onCropConfirmed = { _, _, _, _ -> },
                    onCancel = {},
                    capturing = true,
                )
            }
        }
        compose.onNodeWithText("正在截取屏幕…").assertIsDisplayed()
        compose.onNodeWithText("系统没有提供可用的 Assist 截图，无法进行屏幕圈选。")
            .assertDoesNotExist()
    }
}
