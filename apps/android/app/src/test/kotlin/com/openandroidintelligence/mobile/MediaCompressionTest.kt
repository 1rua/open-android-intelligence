package com.openandroidintelligence.mobile

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.mobile.util.ContentResolverExtensions
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.fakes.BaseCursor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 验证 F8 图片多模态与压缩：采样下采样、等比缩放至 1920px、WebP 压缩（体积 < 1MB）、
 * 小图防拉伸、极端宽高比保护以及损坏流优雅降级。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaCompressionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun registerTestImage(
        uriString: String,
        width: Int,
        height: Int,
        mimeType: String = "image/png",
    ): Uri {
        val uri = Uri.parse(uriString)
        val resolver = context.contentResolver
        val shadowResolver = Shadows.shadowOf(resolver)

        // 生成真实位图字节流
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val baos = ByteArrayOutputStream()
        val format = when {
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> Bitmap.CompressFormat.JPEG
            else -> Bitmap.CompressFormat.PNG
        }
        bitmap.compress(format, 90, baos)
        val bytes = baos.toByteArray()
        bitmap.recycle()

        // 注册到 ShadowContentResolver
        shadowResolver.registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        shadowResolver.setCursor(
            uri,
            object : BaseCursor() {
                override fun moveToFirst() = true
                override fun getColumnIndex(columnName: String) =
                    if (columnName == OpenableColumns.DISPLAY_NAME) 0 else -1
                override fun getString(columnIndex: Int) = "test_image.${mimeType.substringAfter('/')}"
                override fun close() = Unit
            },
        )
        return uri
    }

    @Test
    fun test_t1_F8_02_Image_Sampling_And_Scaling_Large_Image() {
        // 超大图缩放至1920px且采样下采样有效降低内存
        val resolver = context.contentResolver
        val uri = registerTestImage("content://test.picker/large_image", 4000, 3000, "image/jpeg")

        val (compressedBytes, mimeType) = ContentResolverExtensions.compressImageUri(resolver, uri)

        assertEquals("image/webp", mimeType)
        assertTrue("压缩输出不得为空", compressedBytes.isNotEmpty())
        assertTrue("压缩大小必须小于 1MB", compressedBytes.size <= 1024 * 1024)
        assertTrue("压缩大小必须严格小于 4MB WebRTC 帧阈值", compressedBytes.size < 4 * 1024 * 1024)

        // 解码验证等比缩放后的尺寸
        val decoded = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size)
        assertNotNull("压缩后的 WebP 必须可重新解码", decoded)
        val maxSide = maxOf(decoded.width, decoded.height)
        assertTrue("最大边不得超过 1920px (当前: $maxSide)", maxSide <= 1920)
        decoded.recycle()
    }

    @Test
    fun test_t1_F8_03_WebP_Compression_Under_1MB_Threshold() {
        // WebP压缩体积必须控制在1MB以内且低于4MB阈值
        val resolver = context.contentResolver
        val uri = registerTestImage("content://test.picker/standard_photo", 2560, 1440, "image/png")

        val (compressedBytes, mimeType) = ContentResolverExtensions.compressImageUri(resolver, uri)

        assertEquals("image/webp", mimeType)
        assertTrue("体积必须小于等于 1MB (实际: ${compressedBytes.size} bytes)", compressedBytes.size <= 1024 * 1024)
        assertTrue("体积必须严格小于 4MB WebRTC 限制", compressedBytes.size < 4 * 1024 * 1024)
    }

    @Test
    fun test_t2_F8_01_Small_Image_Not_Upscaled() {
        // 小于1920px的小图保持原尺寸不被拉伸放大
        val resolver = context.contentResolver
        val originalWidth = 800
        val originalHeight = 600
        val uri = registerTestImage("content://test.picker/small_photo", originalWidth, originalHeight, "image/png")

        val (compressedBytes, mimeType) = ContentResolverExtensions.compressImageUri(resolver, uri)

        assertEquals("image/webp", mimeType)
        val decoded = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size)
        assertNotNull(decoded)
        assertEquals("宽度不应被拉伸", originalWidth, decoded.width)
        assertEquals("高度不应被拉伸", originalHeight, decoded.height)
        decoded.recycle()
    }

    @Test
    fun test_t2_F8_02_Extreme_Aspect_Ratio_Panoramic_Wide() {
        // 极端宽高比长图缩放保护不产生0像素
        val resolver = context.contentResolver
        val uri = registerTestImage("content://test.picker/panoramic_wide", 6000, 100, "image/png")

        val (compressedBytes, mimeType) = ContentResolverExtensions.compressImageUri(resolver, uri)

        assertEquals("image/webp", mimeType)
        val decoded = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size)
        assertNotNull(decoded)
        assertTrue("宽度缩放后必须大于 0", decoded.width > 0)
        assertTrue("高度缩放后必须大于 0", decoded.height > 0)
        assertTrue("长边不得超过 1920px", maxOf(decoded.width, decoded.height) <= 1920)
        decoded.recycle()
    }

    @Test
    fun test_t2_F8_02_Extreme_Aspect_Ratio_Panoramic_Tall() {
        // 极端竖长图缩放保护不产生0像素
        val resolver = context.contentResolver
        val uri = registerTestImage("content://test.picker/panoramic_tall", 100, 6000, "image/png")

        val (compressedBytes, mimeType) = ContentResolverExtensions.compressImageUri(resolver, uri)

        assertEquals("image/webp", mimeType)
        val decoded = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size)
        assertNotNull(decoded)
        assertTrue("宽度缩放后必须大于 0", decoded.width > 0)
        assertTrue("高度缩放后必须大于 0", decoded.height > 0)
        assertTrue("长边不得超过 1920px", maxOf(decoded.width, decoded.height) <= 1920)
        decoded.recycle()
    }

    @Test
    fun test_t2_F8_03_Corrupted_Image_Fallback() {
        // 损坏流与不可解码字节安全降级不崩溃
        val resolver = context.contentResolver
        val uri = Uri.parse("content://test.picker/corrupted_image")
        val shadowResolver = Shadows.shadowOf(resolver)

        val corruptedBytes = "NOT_A_VALID_IMAGE_DATA_123456789".toByteArray()
        shadowResolver.registerInputStreamSupplier(uri) { ByteArrayInputStream(corruptedBytes) }
        shadowResolver.setCursor(
            uri,
            object : BaseCursor() {
                override fun moveToFirst() = true
                override fun getColumnIndex(columnName: String) =
                    if (columnName == OpenableColumns.DISPLAY_NAME) 0 else -1
                override fun getString(columnIndex: Int) = "corrupted.jpg"
                override fun close() = Unit
            },
        )

        // 1. 空输入流边界测试：rawWidth <= 0 时安全退回空字节流
        val emptyUri = Uri.parse("content://test.picker/empty_image")
        shadowResolver.registerInputStreamSupplier(emptyUri) { ByteArrayInputStream(ByteArray(0)) }
        val (emptyBytes, _) = ContentResolverExtensions.compressImageUri(resolver, emptyUri)
        assertEquals(0, emptyBytes.size)

        // 2. 通过 resolveAttachment 遇到损坏或非标准图片流时，降级机制保护不发生非受检崩溃
        val selection = ContentResolverExtensions.resolveAttachment(resolver, uri)
        assertNotNull(selection)
        assertEquals("image/jpeg", selection.mediaType)
        val readBytes = selection.contentSource.openStream().use { it.readBytes() }
        assertTrue("读取的数据流不得为空", readBytes.isNotEmpty())
    }

    @Test
    fun test_t2_F8_04_High_Megapixel_48MP_Stress() {
        // 4800万像素超高分辨率图片采样降采样且控制在1MB以内
        val resolver = context.contentResolver
        val uri = registerTestImage("content://test.picker/camera_48mp", 8000, 6000, "image/jpeg")

        val (compressedBytes, mimeType) = ContentResolverExtensions.compressImageUri(resolver, uri)

        assertEquals("image/webp", mimeType)
        assertTrue("48MP 压缩后必须小于 1MB", compressedBytes.size <= 1024 * 1024)
        assertTrue("48MP 压缩后必须严格小于 4MB", compressedBytes.size < 4 * 1024 * 1024)

        val decoded = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size)
        assertNotNull(decoded)
        assertTrue("解码后最大边必须被缩放到 <= 1920px (当前: ${maxOf(decoded.width, decoded.height)})",
            maxOf(decoded.width, decoded.height) <= 1920)
        decoded.recycle()
    }

    @Test
    fun test_resolveAttachment_Applies_Compression_Pipeline() {
        // resolveAttachment对图片附件自动应用压缩管道，并将 mediaType 对齐为 image/webp
        val resolver = context.contentResolver
        val uri = registerTestImage("content://test.picker/photo_with_preview", 3000, 2000, "image/jpeg")

        val selection = ContentResolverExtensions.resolveAttachment(resolver, uri)

        assertEquals("image/webp", selection.mediaType)
        // 验证读取数据流时已自动被 compressImageUri 压缩且体积/尺寸符合阈值
        val readBytes = selection.contentSource.openStream().use { it.readBytes() }
        assertTrue("压缩后的流不得为空", readBytes.isNotEmpty())
        assertTrue("压缩后的流体积应小于 1MB", readBytes.size <= 1024 * 1024)
        assertTrue("压缩后的流体积应严格小于 4MB", readBytes.size < 4 * 1024 * 1024)
        val decoded = BitmapFactory.decodeByteArray(readBytes, 0, readBytes.size)
        assertNotNull(decoded)
        assertTrue("长边必须缩放至 <= 1920px", maxOf(decoded.width, decoded.height) <= 1920)
        decoded.recycle()
    }

    @Test
    fun test_resolveAttachment_Corrupted_Png_Preserves_Original_MimeType() {
        val resolver = context.contentResolver
        val uri = Uri.parse("content://test.picker/corrupted_png")
        val shadowResolver = Shadows.shadowOf(resolver)
        val corruptedBytes = "CORRUPTED_PNG_PAYLOAD".toByteArray()
        shadowResolver.registerInputStreamSupplier(uri) { ByteArrayInputStream(corruptedBytes) }
        shadowResolver.setCursor(
            uri,
            object : BaseCursor() {
                override fun moveToFirst() = true
                override fun getColumnIndex(columnName: String) =
                    if (columnName == OpenableColumns.DISPLAY_NAME) 0 else -1
                override fun getString(columnIndex: Int) = "broken.png"
                override fun close() = Unit
            },
        )

        val selection = ContentResolverExtensions.resolveAttachment(resolver, uri)

        assertEquals("损坏的 PNG 不得误标为 image/webp 或 image/jpeg", "image/png", selection.mediaType)
        assertArrayEquals(corruptedBytes, selection.contentSource.openStream().use { it.readBytes() })
    }

    @Test
    fun test_resolveAttachment_NonImage_Preserves_Original_MimeType_And_Bytes() {
        val resolver = context.contentResolver
        val uri = Uri.parse("content://test.picker/report_pdf")
        val shadowResolver = Shadows.shadowOf(resolver)
        val pdfBytes = "%PDF-1.4\n%fake-pdf".toByteArray()
        shadowResolver.registerInputStreamSupplier(uri) { ByteArrayInputStream(pdfBytes) }
        shadowResolver.setCursor(
            uri,
            object : BaseCursor() {
                override fun moveToFirst() = true
                override fun getColumnIndex(columnName: String) =
                    if (columnName == OpenableColumns.DISPLAY_NAME) 0 else -1
                override fun getString(columnIndex: Int) = "report.pdf"
                override fun close() = Unit
            },
        )

        val selection = ContentResolverExtensions.resolveAttachment(resolver, uri)

        assertEquals("application/pdf", selection.mediaType)
        assertArrayEquals(pdfBytes, selection.contentSource.openStream().use { it.readBytes() })
    }
}
