package com.openandroidintelligence.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.ui.geometry.Offset
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 把圈选层里的画布坐标换算回截图像素并裁出附件。
 *
 * 坐标契约与 `conversation-ui` 的圈选层一致：**画布坐标 ≡ 帧坐标**，截图被拉伸铺满
 * 画布，所以只要按 `截图像素 / 画布像素` 的比例换算就能拿回原始帧的选区。这里不做
 * 任何二次 inset，避免两套坐标随窗口尺寸漂移。
 *
 * 返回 null 表示选区无效（非有限值、面积过小、位图已回收），调用方必须保持圈选层
 * 打开让用户重选，不能把"没裁出来"当成"已经提交"。
 */
internal fun cropAssistantSelection(
    source: Bitmap,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    canvasWidth: Float,
    canvasHeight: Float,
): Bitmap? {
    if (source.isRecycled) return null
    if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
    val bounds = scaleToSource(source, canvasWidth, canvasHeight) ?: return null
    // 左/上取整、右/下取整到像素边界：四舍五入会丢边，floor/ceil 只会多裁不到 1 像素。
    val x = floor(left * bounds.scaleX).toInt().coerceIn(0, source.width - 1)
    val y = floor(top * bounds.scaleY).toInt().coerceIn(0, source.height - 1)
    val rightEdge = ceil(right * bounds.scaleX).toInt().coerceIn(x + 1, source.width)
    val bottomEdge = ceil(bottom * bounds.scaleY).toInt().coerceIn(y + 1, source.height)
    val width = rightEdge - x
    val height = bottomEdge - y
    if (width < MINIMUM_CROP_PIXELS || height < MINIMUM_CROP_PIXELS) return null
    return runCatching { Bitmap.createBitmap(source, x, y, width, height) }.getOrNull()
}

/**
 * 自由圈选：按路径蒙版裁出选区（与旧面板的圈选手势语义一致）。
 *
 * 只保留路径内部的像素，其余透明；点集不足或面积过小返回 null。
 */
internal fun cropAssistantFreehand(
    source: Bitmap,
    points: List<Offset>,
    canvasWidth: Float,
    canvasHeight: Float,
): Bitmap? {
    if (source.isRecycled || points.size < 3) return null
    val bounds = scaleToSource(source, canvasWidth, canvasHeight) ?: return null
    val scaled = points.map { Offset(it.x * bounds.scaleX, it.y * bounds.scaleY) }
        .filter { it.x.isFinite() && it.y.isFinite() }
    if (scaled.size < 3) return null
    val left = floor(scaled.minOf { it.x }).toInt().coerceIn(0, source.width - 1)
    val top = floor(scaled.minOf { it.y }).toInt().coerceIn(0, source.height - 1)
    val right = ceil(scaled.maxOf { it.x }).toInt().coerceIn(left + 1, source.width)
    val bottom = ceil(scaled.maxOf { it.y }).toInt().coerceIn(top + 1, source.height)
    val width = right - left
    val height = bottom - top
    if (width < MINIMUM_CROP_PIXELS || height < MINIMUM_CROP_PIXELS) return null
    return runCatching {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val mask = Path().apply {
            scaled.forEachIndexed { index, point ->
                val x = point.x - left
                val y = point.y - top
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        canvas.drawPath(mask, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawBitmap(
            source,
            -left.toFloat(),
            -top.toFloat(),
            Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) },
        )
        output
    }.getOrNull()
}

/** 选区下限：两个方向都不足 4 像素的裁剪没有可用信息。 */
private const val MINIMUM_CROP_PIXELS = 4

private class SourceScale(val scaleX: Float, val scaleY: Float)

private fun scaleToSource(source: Bitmap, canvasWidth: Float, canvasHeight: Float): SourceScale? {
    if (!canvasWidth.isFinite() || !canvasHeight.isFinite()) return null
    if (canvasWidth <= 0f || canvasHeight <= 0f) return null
    if (source.width <= 0 || source.height <= 0) return null
    val scaleX = source.width / canvasWidth
    val scaleY = source.height / canvasHeight
    if (!scaleX.isFinite() || !scaleY.isFinite() || scaleX <= 0f || scaleY <= 0f) return null
    return SourceScale(scaleX, scaleY)
}
