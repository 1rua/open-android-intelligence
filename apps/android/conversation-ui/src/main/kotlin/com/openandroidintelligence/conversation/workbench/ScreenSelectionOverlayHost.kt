package com.openandroidintelligence.conversation.workbench

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.openandroidintelligence.capability.ScreenCapture
import com.openandroidintelligence.capability.ScreenCaptureSource
import com.openandroidintelligence.conversation.selection.ScreenSelectionOverlay
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 圈选层的截图供给。
 *
 * 规格契约（docs/superpowers/specs/2026-09-12-app-material-ui-and-motion.md
 * 的圈选画框条目）：必须有外部真实截图，否则明确不可用；确认后才能回调。
 *
 * 截图只能来自外部真实采集源 [ScreenCaptureSource]（MediaProjection 经系统
 * 授权后产生，见 capability-ports）。没有来源、来源不可用、采集失败或解码
 * 失败时，一律以 `screenshot = null` 渲染 overlay 的不可用态——overlay 在该
 * 状态下不显示确认按钮、不触发确认回调（基线 B6 行为，见
 * ScreenSelectionOverlay 的契约），这里不重复实现任何降级 UI。
 */
@Composable
internal fun ScreenSelectionOverlayHost(
    screenCaptureSource: ScreenCaptureSource?,
    onCancel: () -> Unit,
    onConfirmCrop: (ScreenSelectionCrop) -> Unit,
) {
    var frame by remember { mutableStateOf<ScreenCapture?>(null) }
    var screenshot by remember { mutableStateOf<ImageBitmap?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(screenCaptureSource) {
        // 只取一次：不轮询、不静默重试。任一环节失败都落到不可用态，
        // 由用户显式重开圈选，而不是让层自己在后台反复要截图。
        val resolved = resolveScreenFrame(screenCaptureSource)
        frame = resolved
        screenshot = resolved?.toImageBitmap()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it },
    ) {
        ScreenSelectionOverlay(
            onCropConfirmed = { left, top, right, bottom ->
                val current = frame
                val frameBytes = current?.pngBytes()
                // fail-closed 防御（保留自原实现）：overlay 只在有截图时才
                // 会触发确认回调，此分支理论上不可达；保留它是为了防止未来
                // overlay 行为变化后，把「没有截图」静默交给下游。
                if (current == null || frameBytes == null || screenshot == null) {
                    error("SCREENSHOT_SOURCE_UNAVAILABLE")
                }
                val crop = cropPngSelection(
                    sourcePngBytes = frameBytes,
                    sourceWidthPx = current.widthPixels,
                    sourceHeightPx = current.heightPixels,
                    canvasWidthPx = canvasSize.width.toFloat(),
                    canvasHeightPx = canvasSize.height.toFloat(),
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                )
                if (crop == null) {
                    // 裁剪失败：绝不交付非用户所选的内容，也不静默当成功。
                    // 回到不可用态并保持层打开，用户可以取消后重试。
                    frame = null
                    screenshot = null
                } else {
                    onConfirmCrop(ScreenSelectionCrop(crop))
                }
            },
            onCancel = onCancel,
            screenshot = screenshot,
        )
    }
}

/** 确认后的圈选产物：用户所选区域的真实 PNG 字节（owned copy）。 */
class ScreenSelectionCrop internal constructor(private val png: ByteArray) {
    /** 每次返回新的副本；下游改写返回值不影响产物本体。 */
    fun pngBytes(): ByteArray = png.copyOf()

    /** Writes the owned selection bytes without making another full-size copy. */
    fun writePngTo(output: OutputStream) = output.write(png)
}

/**
 * 圈选截图的降级判定：来源缺失、来源不可用、采集失败都返回 null。
 * 不可用时绝不触发采集（可用性查询优先，避免无谓地向采集端要帧）。
 */
internal suspend fun resolveScreenFrame(source: ScreenCaptureSource?): ScreenCapture? {
    if (source == null) return null
    if (!source.isAvailable) return null
    return source.capture()
}

/**
 * 把采集帧解码为 [ImageBitmap]；PNG 损坏（解码失败）或解码尺寸与帧元数据
 * 不符时返回 null（调用方按无截图降级），绝不拿半张图或占位图冒充截图。
 */
internal fun ScreenCapture.toImageBitmap(): ImageBitmap? {
    val bytes = pngBytes()
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    // 帧元数据（采集端记录的宽高）与实际解码结果必须一致；不一致说明
    // 像素数据已损坏，按无截图降级。
    if (bitmap.width != widthPixels || bitmap.height != heightPixels) return null
    return bitmap.asImageBitmap()
}

/**
 * 把 overlay 画布坐标系里的选区如实映射回截图像素并裁出 PNG。
 *
 * overlay 把截图拉伸铺满画布，因此回调的坐标是画布坐标；按
 * `原图尺寸 / 画布尺寸` 的比例换算并钳制到图像边界。[sourceWidthPx]/
 * [sourceHeightPx] 是帧元数据，与解码结果不一致说明源数据损坏，返回
 * null。任何失败（画布为零、选区退化、PNG 损坏）都返回 null，由调用方
 * 明确降级。
 */
internal fun cropPngSelection(
    sourcePngBytes: ByteArray,
    sourceWidthPx: Int,
    sourceHeightPx: Int,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
): ByteArray? {
    if (canvasWidthPx <= 0f || canvasHeightPx <= 0f) return null
    if (left >= right || top >= bottom) return null
    val bitmap = BitmapFactory.decodeByteArray(sourcePngBytes, 0, sourcePngBytes.size) ?: return null
    if (bitmap.width != sourceWidthPx || bitmap.height != sourceHeightPx) return null
    val scaleX = bitmap.width / canvasWidthPx
    val scaleY = bitmap.height / canvasHeightPx
    val x0 = floor(left * scaleX).toInt().coerceIn(0, bitmap.width - 1)
    val y0 = floor(top * scaleY).toInt().coerceIn(0, bitmap.height - 1)
    val x1 = ceil(right * scaleX).toInt().coerceIn(x0 + 1, bitmap.width)
    val y1 = ceil(bottom * scaleY).toInt().coerceIn(y0 + 1, bitmap.height)
    val cropped = Bitmap.createBitmap(bitmap, x0, y0, x1 - x0, y1 - y0) ?: return null
    val output = ByteArrayOutputStream()
    if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
    val bytes = output.toByteArray()
    if (bytes.isEmpty()) return null
    return bytes
}
