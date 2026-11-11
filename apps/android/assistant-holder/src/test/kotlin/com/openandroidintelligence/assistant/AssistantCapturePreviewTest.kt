package com.openandroidintelligence.assistant

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 拍照预览的编码契约。
 *
 * 为什么必须有这一步：桥接 `attachScreen()` 编码后会回收位图，而主进程只回传附件
 * 元数据、不传字节。若不在交给桥接**之前**留下缩略图，输入栏与发送后的时间线都只能
 * 显示「预览不可用」——照片明明拍下来了，用户却看不到任何画面。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantCapturePreviewTest {

    private fun bitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    @Test fun aCapturedPhotoYieldsDecodablePreviewBytes() {
        val source = bitmap(1200, 900)

        val bytes = encodeCapturePreview(source, maxEdge = 720, quality = 85)

        assertNotNull("拍照必须产出预览字节", bytes)
        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes!!, 0, bytes.size)
        assertNotNull("预览字节必须能解码，否则缩略条会显示空白", decoded)
        assertEquals(720, maxOf(decoded!!.width, decoded.height))
    }

    /** 小图不放大：预览只需要可辨认，放大只会白占内存。 */
    @Test fun aSmallPhotoIsNotUpscaled() {
        val source = bitmap(320, 240)

        val bytes = requireNotNull(encodeCapturePreview(source, maxEdge = 720))

        val decoded = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        assertEquals(320, maxOf(decoded.width, decoded.height))
    }

    @Test fun aRecycledBitmapProducesNoPreviewInsteadOfThrowing() {
        val source = bitmap(400, 300).apply { recycle() }

        assertNull(encodeCapturePreview(source))
    }
}
