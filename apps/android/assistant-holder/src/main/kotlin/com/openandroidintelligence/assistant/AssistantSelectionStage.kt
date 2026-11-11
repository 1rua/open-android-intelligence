package com.openandroidintelligence.assistant

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import com.openandroidintelligence.conversation.selection.ScreenSelectionOverlay
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Compose 测试钩子：按稳定标签断言根节点。 */
internal object AssistantSelectionStageTags {
    const val ROOT = "assistant_selection_stage"
}

/**
 * 确认后的圈选产物：用户所选区域的真实 PNG 字节 + 本进程的预览定位键。
 *
 * 为什么带 `localKey`：桥接协议只传附件身份与元数据，不传字节。助理进程自己裁出的
 * 图片必须留在本进程的 [AssistantLocalPreviews] 里，界面才按这个键取回缩略图。
 * 字节用 owned copy 语义暴露（[pngBytes] 每次返回新副本），宿主可以安全地把它交给
 * 发送管道，也可以在发送前清零自己的副本而不影响预览。
 */
internal class AssistantSelectionResult internal constructor(
    val localKey: String,
    private val png: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
) {
    /** PNG 字节数；宿主可据此判断是否超过发送管道上限。 */
    val byteSize: Int get() = png.size

    /** 每次返回新的副本；下游改写返回值不影响产物本体。 */
    fun pngBytes(): ByteArray = png.copyOf()

    /** 直接写入输出流而不复制整份字节。 */
    fun writePngTo(output: OutputStream) = output.write(png)
}

/**
 * 全屏圈选舞台：截图供给 → 圈选 → 裁剪 → PNG 附件。
 *
 * 复用 App 内同一个 [ScreenSelectionOverlay]，坐标契约完全一致：
 * **画布坐标 ≡ 帧坐标**。画布尺寸只来自 overlay 自己的 `onSizeChanged`（经
 * `onCanvasSizeChanged` 上报），本层不再二次测量、也不加额外 inset，因此拖动坐标
 * 可以按 `截图像素 / 画布像素` 的比例直接换算回原始帧（见 [cropAssistantSelection]）。
 *
 * 流程边界：
 * 1. **截图只做输入**：[screenshot] 为 null 时渲染 overlay 自带的不可用/采集中间态，
 *    绝不伪造一张截图；[capturing] 为 true 时显示“正在截取屏幕…”。
 * 2. **确认后才裁剪**：拖动只产生草稿选区，用户按下“确认选区”才做事；
 *    自由圈选与矩形圈选走各自的裁剪函数，只保留用户框住/圈住的像素。
 * 3. **裁剪与编码在后台线程**：整图 PNG 编码不应压在主线程上；结果回到主线程后
 *    才写入 [previews] 并回调宿主。
 * 4. **失败不冒充提交**：裁剪返回 null（选区退化、位图已回收、画布未测量）或编码
 *    失败时保持圈选层打开，并通过 [onCropRejected] 让宿主给出提示，绝不回调
 *    [onSelectionReady]。
 * 5. **并发确认只允许一次**：编码期间再次按下确认不会启动第二个 worker，避免同一个
 *    选区被添加两次。
 *
 * 所有权：本层不回收调用方的 [screenshot]（它属于会话）；裁剪出的临时位图在编码后
 * 立即回收，不会把第二份屏幕内容留在内存里。
 *
 * @param screenshot 系统提供的 Assist 截图；本层只读，不接管生命周期。
 * @param previews 会话级本地缩略图存储；宿主在会话开始时创建，本层只写入。
 * @param localKey 写入 [previews] 与回传给宿主的本地定位键（协议里的 `localKey`）。
 * @param onSelectionReady 确认并成功生成 PNG 后回调；宿主负责发送并关闭舞台。
 * @param onCancel 用户取消圈选。
 * @param capturing 截图仍在采集/解码：渲染中间态而不是失败文案。
 * @param onCropRejected 选区无效或编码失败；舞台保持打开，用户可以重新圈选。
 */
@Composable
internal fun AssistantSelectionStage(
    screenshot: Bitmap?,
    previews: AssistantLocalPreviews,
    localKey: String,
    onSelectionReady: (AssistantSelectionResult) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    capturing: Boolean = false,
    onCropRejected: () -> Unit = {},
) {
    // 已回收的位图不能再解码/裁剪：按“没有截图”处理，而不是让 overlay 拿到一张假图。
    val source = screenshot?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
    val image = remember(source) { source?.asImageBitmap() }
    var canvasSize by remember(source) { mutableStateOf(IntSize.Zero) }
    // 编码是异步的：确认一次后上锁，结果回来（成功或失败）才解锁，
    // 否则连点两次会启动两个 worker，把同一个选区重复添加到草稿里。
    var confirming by remember(source) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 回调与存储可能在编码期间被宿主替换（换会话会换掉 previews 实例）；
    // 用 updatedState 保证结果写到“当下”的宿主，而不是旧的组合闭包。
    val emitReady by rememberUpdatedState(onSelectionReady)
    val emitRejected by rememberUpdatedState(onCropRejected)
    val currentPreviews by rememberUpdatedState(previews)
    val currentLocalKey by rememberUpdatedState(localKey)

    /** 统一确认路径：裁剪 + PNG 编码在后台线程，结果回到主线程再交付。 */
    fun confirm(build: (Bitmap, Float, Float) -> AssistantSelectionResult?) {
        if (confirming) return
        val bitmap = source ?: return
        // 画布尺寸在按下时刻读取：之后即使 overlay 重新测量，这一次确认也使用同一个坐标系。
        val canvasWidth = canvasSize.width.toFloat()
        val canvasHeight = canvasSize.height.toFloat()
        confirming = true
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                build(bitmap, canvasWidth, canvasHeight)
            }
            confirming = false
            if (result != null) emitReady(result) else emitRejected()
        }
    }

    ScreenSelectionOverlay(
        onCropConfirmed = { left, top, right, bottom ->
            confirm { bitmap, canvasWidth, canvasHeight ->
                createAssistantSelection(
                    source = bitmap,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    localKey = currentLocalKey,
                    previews = currentPreviews,
                )
            }
        },
        onFreehandConfirmed = { points ->
            confirm { bitmap, canvasWidth, canvasHeight ->
                createAssistantFreehandSelection(
                    source = bitmap,
                    points = points,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    localKey = currentLocalKey,
                    previews = currentPreviews,
                )
            }
        },
        onCancel = onCancel,
        screenshot = image,
        capturing = capturing,
        onCanvasSizeChanged = { canvasSize = it },
        modifier = modifier
            .fillMaxSize()
            .testTag(AssistantSelectionStageTags.ROOT),
    )
}

/**
 * 矩形圈选 → PNG 附件：裁剪、编码并写入本地预览。
 *
 * 独立成纯函数是为了让坐标换算与“无效选区不产出附件”能在 JVM 测试里直接钉死；
 * 组合层只负责线程切换与回调。返回 null 表示没有任何可用产物，调用方必须保持
 * 圈选层打开，不能把“没裁出来”当成已提交。
 */
internal fun createAssistantSelection(
    source: Bitmap,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    canvasWidth: Float,
    canvasHeight: Float,
    localKey: String,
    previews: AssistantLocalPreviews,
): AssistantSelectionResult? {
    val cropped = cropAssistantSelection(
        source = source,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
    ) ?: return null
    return cropped.toSelectionResult(localKey = localKey, previews = previews)
}

/**
 * 自由圈选 → PNG 附件：只保留路径内部的像素，其余透明。
 *
 * 点集不足三个或面积过小时裁剪函数返回 null，与矩形圈选一样按“未提交”处理。
 */
internal fun createAssistantFreehandSelection(
    source: Bitmap,
    points: List<Offset>,
    canvasWidth: Float,
    canvasHeight: Float,
    localKey: String,
    previews: AssistantLocalPreviews,
): AssistantSelectionResult? {
    val cropped = cropAssistantFreehand(
        source = source,
        points = points,
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
    ) ?: return null
    return cropped.toSelectionResult(localKey = localKey, previews = previews)
}

/**
 * 把裁剪结果编码成 PNG 并写入本地预览；临时位图在编码后立即回收。
 *
 * 预览写入失败（键为空、会话已结束、单张超出存储预算）**不影响交付**：宿主仍然可以
 * 发送这份字节，只是这一条附件没有本地缩略图，界面如实显示“预览不可用”而不是伪造
 * 一张图。编码失败才返回 null，调用方据此拒绝这次确认。
 */
private fun Bitmap.toSelectionResult(
    localKey: String,
    previews: AssistantLocalPreviews,
): AssistantSelectionResult? {
    val width = width
    val height = height
    val bytes = try {
        ByteArrayOutputStream().use { output ->
            if (compress(Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray() else null
        }
    } catch (_: Exception) {
        null
    } finally {
        // 裁剪函数返回的永远是独立位图（整图选区也会复制一份），这里可以安全回收，
        // 不会碰到调用方仍持有的原始截图。
        if (!isRecycled) recycle()
    }
    if (bytes == null || bytes.isEmpty()) return null
    previews.put(localKey, bytes)
    return AssistantSelectionResult(
        localKey = localKey,
        png = bytes,
        widthPx = width,
        heightPx = height,
    )
}
