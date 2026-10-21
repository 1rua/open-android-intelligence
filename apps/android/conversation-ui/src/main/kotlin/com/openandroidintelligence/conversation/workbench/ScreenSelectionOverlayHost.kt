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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import com.openandroidintelligence.capability.ScreenCapture
import com.openandroidintelligence.capability.ScreenCaptureSource
import com.openandroidintelligence.conversation.selection.ScreenSelectionOverlay
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 *
 * 两个状态边界：
 * - **采集中**：截图还没到，渲染「正在截取屏幕…」中间态，而不是先诬告一次失败；
 * - **解码/裁剪**：PNG 解码与裁剪都在 `Dispatchers.Default` 上执行，主线程不做整图
 *   解码；确认回调因此可能晚一帧到达，但绝不会在失败时伪造成功。
 *
 * **坐标契约：画布坐标 ≡ 帧坐标。** 画布尺寸只有一处来源——overlay 自己的
 * `onSizeChanged`（经 [ScreenSelectionOverlay.onCanvasSizeChanged] 上报），宿主
 * 不再二次测量、也不额外加 inset，避免两套坐标随窗口变化而漂移。
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
    var capturing by remember { mutableStateOf(screenCaptureSource != null) }
    // 确认是异步的（解码 + 重编码在后台线程）：不加锁时连点两次会启动两个 worker，
    // 同一个选区被添加两次。确认一次后立刻上锁，直到结果回来再解锁。
    var confirming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(screenCaptureSource) {
        // 只取一次：不轮询、不静默重试。任一环节失败都落到不可用态，
        // 由用户显式重开圈选，而不是让层自己在后台反复要截图。
        capturing = screenCaptureSource != null
        val resolved = resolveScreenFrame(screenCaptureSource)
        if (resolved == null) {
            frame = null
            screenshot = null
            capturing = false
            return@LaunchedEffect
        }
        val decoded = withContext(Dispatchers.Default) { resolved.toImageBitmap() }
        // 帧损坏或解码失败：同样降级为「没有截图」，绝不拿半张图冒充。
        frame = if (decoded == null) null else resolved
        screenshot = decoded
        capturing = false
    }

    // 单次确认：解码与重编码是异步的，连点会启动多个 worker，把同一个选区重复添加。
    // 这里用一个布尔锁把「确认中」串行化，结果回来（成功或降级）才解锁。
    val confirmOnce: ((() -> Unit)) -> Unit = { action ->
        if (!confirming) {
            confirming = true
            action()
        }
    }
    val releaseConfirmation: () -> Unit = { confirming = false }

    Box(modifier = Modifier.fillMaxSize()) {
        ScreenSelectionOverlay(
            onFreehandConfirmed = { points ->
                confirmOnce {
                    // 边界必须由点集算出：cropPngSelection 用矩形定位输出尺寸，
                    // 只给 pathPoints、边界留 0 时它会判 left >= right 直接返回 null，
                    // 自由圈选就永远提交不了。
                    confirmCrop(
                        scope = scope,
                        current = frame,
                        canvasSize = canvasSize,
                        left = points.minOfOrNull { it.x } ?: 0f,
                        top = points.minOfOrNull { it.y } ?: 0f,
                        right = points.maxOfOrNull { it.x } ?: 0f,
                        bottom = points.maxOfOrNull { it.y } ?: 0f,
                        pathPoints = points,
                        onUnavailable = {
                            frame = null
                            screenshot = null
                            releaseConfirmation()
                        },
                        onConfirmed = {
                            onConfirmCrop(it)
                            releaseConfirmation()
                        },
                    )
                }
            },
            onCropConfirmed = { left, top, right, bottom ->
                confirmOnce {
                    confirmCrop(
                        scope = scope,
                        current = frame,
                        canvasSize = canvasSize,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        onUnavailable = {
                            frame = null
                            screenshot = null
                            releaseConfirmation()
                        },
                        onConfirmed = {
                            onConfirmCrop(it)
                            releaseConfirmation()
                        },
                    )
                }
            },
            onCancel = onCancel,
            screenshot = screenshot,
            capturing = capturing,
            // 唯一画布尺寸来源：overlay 自己测量后上报。
            onCanvasSizeChanged = { canvasSize = it },
        )
    }
}

/**
 * 确认路径：默认线程上不裁图，任何失败都回到不可用态而不是崩溃或静默成功。
 *
 * 之前这里的防御分支直接 `error(...)`，一旦 overlay 行为变化就会让整个 App 崩在
 * 用户面前；采集/裁剪失败是可预期状态，必须降级成「没有可用截图」。
 */
private fun confirmCrop(
    scope: kotlinx.coroutines.CoroutineScope,
    current: ScreenCapture?,
    canvasSize: IntSize,
    onUnavailable: () -> Unit,
    onConfirmed: (ScreenSelectionCrop) -> Unit,
    left: Float = 0f,
    top: Float = 0f,
    right: Float = 0f,
    bottom: Float = 0f,
    pathPoints: List<Offset> = emptyList(),
) {
    if (current == null || canvasSize == IntSize.Zero) {
        onUnavailable()
        return
    }
    scope.launch {
        val crop = withContext(Dispatchers.Default) {
            cropPngSelection(
                sourcePngBytes = current.pngBytes(),
                sourceWidthPx = current.widthPixels,
                sourceHeightPx = current.heightPixels,
                canvasWidthPx = canvasSize.width.toFloat(),
                canvasHeightPx = canvasSize.height.toFloat(),
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                pathPoints = pathPoints,
            )
        }
        if (crop == null) {
            // 裁剪失败：绝不交付非用户所选的内容，也不静默当成功。
            // 回到不可用态并保持层打开，用户可以取消后重试。
            onUnavailable()
        } else {
            onConfirmed(ScreenSelectionCrop(crop))
        }
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
 *
 * 保持非 suspend：它是纯解码，调用方负责把它放到后台线程（见
 * [ScreenSelectionOverlayHost]），JVM 测试也可以直接验证解码语义。
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
    pathPoints: List<androidx.compose.ui.geometry.Offset> = emptyList(),
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
    var cropped = Bitmap.createBitmap(bitmap, x0, y0, x1 - x0, y1 - y0) ?: return null
    if (pathPoints.isNotEmpty()) {
        if (pathPoints.size !in 3..4096 || pathPoints.any { !it.x.isFinite() || !it.y.isFinite() } ||
            com.openandroidintelligence.conversation.selection.polygonArea(pathPoints) < 1f) return null
        val masked = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
        val path = android.graphics.Path().apply {
            val first = pathPoints.first()
            moveTo(first.x * scaleX - x0, first.y * scaleY - y0)
            pathPoints.drop(1).forEach { lineTo(it.x * scaleX - x0, it.y * scaleY - y0) }
            close()
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        val canvas = android.graphics.Canvas(masked)
        canvas.drawPath(path, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(cropped, 0f, 0f, paint)
        cropped = masked
    }
    val output = ByteArrayOutputStream()
    if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
    val bytes = output.toByteArray()
    if (bytes.isEmpty()) return null
    return bytes
}
