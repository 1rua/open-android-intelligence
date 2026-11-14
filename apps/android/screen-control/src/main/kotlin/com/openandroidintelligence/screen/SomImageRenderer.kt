package com.openandroidintelligence.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.openandroidintelligence.control.*
import com.openandroidintelligence.control.remote.ControlRequestException
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class SomRenderedImage(val bytes: ByteArray, val mapping: List<Map<String, Any>>)

/** 单次帧与节点共用同一坐标系；尺寸/旋转不匹配时拒绝，不猜缩放或默认屏幕大小。 */
internal object SomImageRenderer {
    suspend fun render(snapshot: ScreenSnapshot, frame: SomFrame, localRegions: List<ScreenBounds>): SomRenderedImage {
        val bounds = frame.capturedBounds
        val bitmap = frame.bitmap
        val expected = if (frame.overlayExcluded) snapshot.windows.singleOrNull { it.id == snapshot.activeWindowId }?.bounds
            else ScreenBounds(0, 0, snapshot.display.widthPixels, snapshot.display.heightPixels)
        if (snapshot.truncated || frame.display != snapshot.display || expected != bounds ||
            bitmap.width != bounds.right - bounds.left || bitmap.height != bounds.bottom - bounds.top ||
            bitmap.width <= 0 || bitmap.height <= 0) throw ControlRequestException("SCREEN_CAPTURE_GEOMETRY_MISMATCH")
        if (localRegions.any { it.left <= bounds.left && it.top <= bounds.top && it.right >= bounds.right && it.bottom >= bounds.bottom })
            throw ControlRequestException("SCREEN_LOCAL_UI_COVERS_CAPTURE")
        val software = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: throw ControlRequestException("SCREEN_CAPTURE_ENCODING_FAILED")
        try {
            val canvas = Canvas(software)
            val redaction = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL; isAntiAlias = false }
            val passwords = snapshot.nodes.filter { it.password && (!frame.overlayExcluded || it.windowId == snapshot.activeWindowId) }.map { it.bounds }
            val masks = localRegions + passwords
            fun overlaps(a: ScreenBounds, b: ScreenBounds) = a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
            fun mask(region: ScreenBounds) {
                canvas.drawRect((region.left - bounds.left).coerceIn(0, software.width).toFloat(),
                    (region.top - bounds.top).coerceIn(0, software.height).toFloat(),
                    (region.right - bounds.left).coerceIn(0, software.width).toFloat(),
                    (region.bottom - bounds.top).coerceIn(0, software.height).toFloat(), redaction)
            }
            val mapping = mutableListOf<Map<String, Any>>()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 3f; textSize = 24f }
            snapshot.nodes.filter { it.windowId == snapshot.activeWindowId && !it.password && it.enabled &&
                (it.clickable || it.editable || it.scrollable || it.longClickable) }.forEach { node ->
                val b = node.bounds
                if (b.left >= bounds.left && b.top >= bounds.top && b.right <= bounds.right && b.bottom <= bounds.bottom &&
                    b.right > b.left && b.bottom > b.top && masks.none { overlaps(b, it) }) {
                    val label = mapping.size + 1
                    canvas.drawRect((b.left - bounds.left).toFloat(), (b.top - bounds.top).toFloat(),
                        (b.right - bounds.left).toFloat(), (b.bottom - bounds.top).toFloat(), paint)
                    canvas.drawText(label.toString(), (b.left - bounds.left).toFloat(),
                        minOf(software.height.toFloat(), (b.top - bounds.top + 24).toFloat()), paint)
                    mapping += mapOf("label" to label, "nodeId" to node.nodeId)
                }
            }
            // 密码与本机窗口最后覆盖，标号或描边不得重新绘制到保护像素上。
            masks.forEach(::mask)
            currentCoroutineContext().ensureActive()
            val bytes = ByteArrayOutputStream().use { out ->
                if (!software.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)) throw ControlRequestException("SCREEN_CAPTURE_ENCODING_FAILED")
                out.toByteArray()
            }
            try {
                currentCoroutineContext().ensureActive()
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw ControlRequestException("SCREEN_CAPTURE_ENCODING_FAILED")
                try { if (decoded.width != bitmap.width || decoded.height != bitmap.height) throw ControlRequestException("SCREEN_CAPTURE_GEOMETRY_MISMATCH") }
                finally { decoded.recycle() }
                return SomRenderedImage(bytes, mapping)
            } catch (failure: Throwable) { bytes.fill(0); throw failure }
        } finally { software.recycle() }
    }
}
