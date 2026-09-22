package com.openandroidintelligence.conversation.workbench

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.openandroidintelligence.capability.ScreenCapture
import com.openandroidintelligence.capability.ScreenCaptureSource
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 圈选层接线的决策逻辑测试（不含 Compose 组合部分）。
 *
 * 三个降级判定与裁剪映射是纯函数 / 值对象行为：截图解析入口
 * [resolveScreenFrame] 对「来源缺失、来源不可用、采集失败、帧损坏」
 * 一律落到 null，由 overlay 渲染不可用态（基线 B6 的行为不变）；
 * 裁剪把 overlay 画布坐标如实映射回截图像素。
 *
 * Bitmap/PNG 编解码经 Robolectric 的原生图形栈运行，故用
 * RobolectricTestRunner。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenSelectionWiringLogicTest {

    private fun pngFrame(width: Int = 200, height: Int = 100): ScreenCapture {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.argb(255, 47, 100, 92))
        val output = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        return ScreenCapture.copyOf(output.toByteArray(), width, height)
    }

    // ------------------------------------------------------------------
    // resolveScreenFrame：降级矩阵
    // ------------------------------------------------------------------

    @Test
    fun resolve_withoutSource_returnsNull() = runBlocking {
        assertNull(resolveScreenFrame(null))
    }

    @Test
    fun resolve_unavailableSource_returnsNullWithoutCapturing() = runBlocking {
        val source = RecordingSource(available = false, frame = pngFrame())
        assertNull(resolveScreenFrame(source))
        assertEquals(0, source.captureCalls)
    }

    @Test
    fun resolve_failedCapture_returnsNull() = runBlocking {
        val source = RecordingSource(available = true, frame = null)
        assertNull(resolveScreenFrame(source))
        assertEquals(1, source.captureCalls)
    }

    @Test
    fun resolve_availableSource_returnsFrame() = runBlocking {
        val frame = pngFrame()
        val source = RecordingSource(available = true, frame = frame)
        assertEquals(frame, resolveScreenFrame(source))
    }

    // ------------------------------------------------------------------
    // toImageBitmap：损坏的帧不能冒充截图
    // ------------------------------------------------------------------

    @Test
    fun decode_validFrame_returnsImageBitmap() = runBlocking {
        val bitmap = resolveScreenFrame(RecordingSource(true, pngFrame()))?.toImageBitmap()
        assertNotNull(bitmap)
        assertTrue(bitmap!!.width > 0)
        assertTrue(bitmap.height > 0)
    }

    @Test
    fun decode_corruptedFrame_returnsNull() {
        // 元数据声称 8x6，字节却不是合法 PNG：解码结果（Robolectric 下是
        // 占位图）与元数据不一致，必须判为损坏而不是冒充截图。
        val corrupted = ScreenCapture.copyOf(byteArrayOf(1, 2, 3, 4), 8, 6)
        assertNull(corrupted.toImageBitmap())
    }

    // ------------------------------------------------------------------
    // cropPngSelection：画布坐标 → 截图像素
    // ------------------------------------------------------------------

    @Test
    fun crop_mapsCanvasSelectionOntoSourcePixels() {
        // 画布 100x50，原图 200x100 → 2x 放大；选区 (20,10)-(60,30) 应得 (40,20)-(120,60)
        val sourcePng = pngFrame(width = 200, height = 100)
        val crop = cropPngSelection(
            sourcePngBytes = sourcePng.pngBytes(),
            sourceWidthPx = 200,
            sourceHeightPx = 100,
            canvasWidthPx = 100f,
            canvasHeightPx = 50f,
            left = 20f, top = 10f, right = 60f, bottom = 30f,
        ) ?: throw AssertionError("合法选区必须裁剪成功")

        val cropped = BitmapFactory.decodeByteArray(crop, 0, crop.size)
        assertNotNull(cropped)
        assertEquals(80, cropped.width)
        assertEquals(40, cropped.height)
    }

    @Test
    fun crop_fullCanvasSelection_returnsFullImage() {
        val sourcePng = pngFrame(width = 64, height = 32)
        val crop = cropPngSelection(
            sourcePngBytes = sourcePng.pngBytes(),
            sourceWidthPx = 64,
            sourceHeightPx = 32,
            canvasWidthPx = 64f,
            canvasHeightPx = 32f,
            left = 0f, top = 0f, right = 64f, bottom = 32f,
        ) ?: throw AssertionError("全画布选区必须裁剪成功")
        val cropped = BitmapFactory.decodeByteArray(crop, 0, crop.size)
        assertEquals(64, cropped.width)
        assertEquals(32, cropped.height)
    }

    @Test
    fun crop_clampsSelectionToImageBounds() {
        val sourcePng = pngFrame(width = 40, height = 40)
        val crop = cropPngSelection(
            sourcePngBytes = sourcePng.pngBytes(),
            sourceWidthPx = 40,
            sourceHeightPx = 40,
            canvasWidthPx = 40f,
            canvasHeightPx = 40f,
            left = -30f, top = -30f, right = 90f, bottom = 90f,
        ) ?: throw AssertionError("越界选区应被钳制后裁剪，而不是失败")
        val cropped = BitmapFactory.decodeByteArray(crop, 0, crop.size)
        assertEquals(40, cropped.width)
        assertEquals(40, cropped.height)
    }

    @Test
    fun crop_zeroCanvas_returnsNull() {
        val sourcePng = pngFrame()
        assertNull(
            cropPngSelection(
                sourcePng.pngBytes(), sourceWidthPx = 200, sourceHeightPx = 100,
                canvasWidthPx = 0f, canvasHeightPx = 0f,
                left = 1f, top = 1f, right = 10f, bottom = 10f,
            ),
        )
    }

    @Test
    fun crop_degenerateSelection_returnsNull() {
        val sourcePng = pngFrame()
        assertNull(
            cropPngSelection(
                sourcePng.pngBytes(), sourceWidthPx = 200, sourceHeightPx = 100,
                canvasWidthPx = 100f, canvasHeightPx = 50f,
                left = 30f, top = 10f, right = 30f, bottom = 30f,
            ),
        )
    }

    @Test
    fun crop_corruptedPng_returnsNull() {
        // 元数据声称 8x6，字节不是合法 PNG：解码结果与元数据不符即判损坏。
        val corrupted = ScreenCapture.copyOf(byteArrayOf(9, 8, 7), 8, 6)
        assertNull(
            cropPngSelection(
                corrupted.pngBytes(), sourceWidthPx = 8, sourceHeightPx = 6,
                canvasWidthPx = 100f, canvasHeightPx = 50f,
                left = 1f, top = 1f, right = 10f, bottom = 10f,
            ),
        )
    }

    @Test
    fun crop_metadataMismatch_returnsNull() {
        // 合法 PNG 但与声称的元数据不符：数据已不可信，拒绝裁剪。
        val frame = pngFrame(width = 60, height = 30)
        assertNull(
            cropPngSelection(
                frame.pngBytes(), sourceWidthPx = 120, sourceHeightPx = 60,
                canvasWidthPx = 100f, canvasHeightPx = 50f,
                left = 1f, top = 1f, right = 10f, bottom = 10f,
            ),
        )
    }

    // ------------------------------------------------------------------
    // ScreenSelectionCrop：产物是 owned copy，外部改写不回传
    // ------------------------------------------------------------------

    @Test
    fun crop_productHandsOutDefensiveCopy() {
        val sourcePng = pngFrame(width = 40, height = 40)
        val product = cropPngSelection(
            sourcePng.pngBytes(), sourceWidthPx = 40, sourceHeightPx = 40,
            canvasWidthPx = 40f, canvasHeightPx = 40f,
            left = 0f, top = 0f, right = 20f, bottom = 20f,
        )?.let(::ScreenSelectionCrop) ?: throw AssertionError("裁剪必须成功")

        val handed = product.pngBytes()
        handed[0] = 0x7F
        assertFalse(handed.contentEquals(product.pngBytes()))
    }

    /** 记录行为的 fake 采集源：不驱动真实投屏，只验证来源被如实用到。 */
    private class RecordingSource(
        private val available: Boolean,
        private val frame: ScreenCapture?,
    ) : ScreenCaptureSource {
        var captureCalls: Int = 0
            private set

        override val isAvailable: Boolean get() = available

        override fun createAuthorizationIntent(): Intent? = null

        override fun onAuthorizationResult(resultCode: Int, resultData: Intent?): Boolean = false

        override suspend fun capture(): ScreenCapture? {
            captureCalls++
            return frame
        }

        override fun release() {}
    }
}
