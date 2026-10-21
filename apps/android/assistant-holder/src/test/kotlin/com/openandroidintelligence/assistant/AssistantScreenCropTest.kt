package com.openandroidintelligence.assistant

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 圈选裁剪的坐标回归。
 *
 * 约定：圈选层画布坐标 ≡ 帧坐标，截图被拉伸铺满画布，所以裁剪必须按
 * `截图像素 / 画布像素` 的比例换算。算错一个方向就会把用户选的区域裁偏，
 * 这类错误只能在纯逻辑层拦住，实机上很难发现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantScreenCropTest {
    private fun source(width: Int = 100, height: Int = 200): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

    @Test fun rectangularSelectionIsScaledBackToSourcePixels() {
        val bitmap = source()
        val cropped = cropAssistantSelection(
            source = bitmap,
            left = 10f,
            top = 10f,
            right = 30f,
            bottom = 40f,
            canvasWidth = 50f,
            canvasHeight = 100f,
        )

        assertNotNull(cropped)
        cropped!!
        assertEquals(40, cropped.width)
        assertEquals(60, cropped.height)
        assertEquals(Color.BLUE, cropped.getPixel(20, 30))
        cropped.recycle()
        bitmap.recycle()
    }

    @Test fun tinySelectionIsRejectedInsteadOfReturningAOnePixelAttachment() {
        val bitmap = source()
        val cropped = cropAssistantSelection(
            source = bitmap,
            left = 0f,
            top = 0f,
            right = 1f,
            bottom = 1f,
            canvasWidth = 100f,
            canvasHeight = 200f,
        )

        assertNull(cropped)
        bitmap.recycle()
    }

    @Test fun unmeasuredCanvasOrNonFiniteSelectionNeverProducesABitmap() {
        val bitmap = source()
        assertNull(
            cropAssistantSelection(bitmap, 0f, 0f, 10f, 10f, canvasWidth = 0f, canvasHeight = 0f),
        )
        assertNull(
            cropAssistantSelection(bitmap, Float.NaN, 0f, 10f, 10f, 50f, 100f),
        )
        bitmap.recycle()
    }

    @Test fun freehandSelectionKeepsOnlyThePathInterior() {
        val bitmap = source(width = 100, height = 100)
        val cropped = cropAssistantFreehand(
            source = bitmap,
            points = listOf(Offset(10f, 10f), Offset(80f, 10f), Offset(10f, 80f)),
            canvasWidth = 100f,
            canvasHeight = 100f,
        )

        assertNotNull(cropped)
        cropped!!
        assertEquals(70, cropped.width)
        assertEquals(70, cropped.height)
        // 三角内部保留原图，外部被蒙版抠掉。
        assertTrue(Color.alpha(cropped.getPixel(2, 2)) > 0)
        assertEquals(0, Color.alpha(cropped.getPixel(60, 60)))
        cropped.recycle()
        bitmap.recycle()
    }
}
