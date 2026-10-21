package com.openandroidintelligence.conversation.workbench

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.openandroidintelligence.conversation.motion.MotionSpecs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 原图链路的硬边界。
 *
 * 集中在一处而不是散落在控制器与界面：读取上限决定「多大的文件会被拒绝」，
 * 采样上限决定「解码后占多少内存」，两者必须一起成立才不会让一张超大的
 * 相机原图把进程拖垮。数值来自规格 3.4 / 4.6。
 */
internal object OriginalImagePolicy {
    /** 单张原图的读取上限：24MB。超过直接判失败，不把内存吃满。 */
    const val MaxBytes = 24 * 1024 * 1024
    /** 展示用最长边：2048px。横竖哪个方向更长就限制哪个。 */
    const val MaxEdgePx = 2048
    /** 控制器压缩原图时的 JPEG 质量。 */
    const val JpegQuality = 85
    /** 最多缓存几张已加载原图。 */
    const val CacheEntries = 3
}

/**
 * 大图预览的几何计算。
 *
 * 这里只放纯函数：缩放钳制、平移边界、双击目标与手势释放后的终点。手势与动画
 * 由调用方完成（Compose 的 Animatable 不可以在单元测试里跑），因此本文件可以
 * 用普通 JVM 测试覆盖全部边界条件。
 */
internal object ImageViewerGeometry {
    /** 最小缩放：1x 表示整图完整装进视口。 */
    const val MinScale = 1f
    /** 最大缩放：5x。 */
    const val MaxScale = 5f
    /** 双击放大目标：2.5x。 */
    const val DoubleTapScale = 2.5f

    /** 判定「当前已经放大」的容差，避免浮点误差让双击在 1x 与 2.5x 之间抖动。 */
    private const val ZoomedThreshold = 0.01f

    /** 缩放永远落在 1x–5x；NaN/Infinity 视为 1x，绝不把界面算成不可见。 */
    fun clampScale(scale: Float): Float =
        if (scale.isFinite()) scale.coerceIn(MinScale, MaxScale) else MinScale

    /**
     * 图片按 `ContentScale.Fit` 装进视口后的实际显示尺寸。
     *
     * 平移边界必须基于这个尺寸而不是视口：图片未放大时四周是留白，允许平移
     * 就会把图片推进看不见的地方。
     */
    fun fittedContentSize(viewport: Size, intrinsic: Size): Size {
        if (viewport.width <= 0f || viewport.height <= 0f) return Size.Zero
        if (intrinsic.width <= 0f || intrinsic.height <= 0f) return Size.Zero
        val ratio = min(viewport.width / intrinsic.width, viewport.height / intrinsic.height)
        return Size(intrinsic.width * ratio, intrinsic.height * ratio)
    }

    /** 缩放后可平移的最大位移（相对视口中心）。内容仍小于视口时只能居中。 */
    fun maxOffset(viewport: Size, content: Size, scale: Float): Offset {
        if (viewport.width <= 0f || viewport.height <= 0f) return Offset.Zero
        val current = clampScale(scale)
        val maxX = ((content.width * current - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((content.height * current - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(maxX, maxY)
    }

    /** 把平移钳制进 [maxOffset] 给出的范围；越界不再是「可能出现在别处」的状态。 */
    fun clampOffset(offset: Offset, viewport: Size, content: Size, scale: Float): Offset {
        if (viewport.width <= 0f || viewport.height <= 0f) return Offset.Zero
        val limit = maxOffset(viewport, content, scale)
        return Offset(
            offset.x.coerceIn(-limit.x, limit.x),
            offset.y.coerceIn(-limit.y, limit.y),
        )
    }

    /**
     * 双击的目标缩放：已经放大就回到 1x，否则放大到 2.5x。
     *
     * 目标值本身与减少动态无关；减少动态只改变到达目标的动画（直接吸附）。
     */
    fun doubleTapTarget(currentScale: Float): Float =
        if (clampScale(currentScale) > MinScale + ZoomedThreshold) MinScale else DoubleTapScale

    /**
     * 捏合缩放时的平移补偿：让捏合中心下的那一点在缩放前后停在原处。
     *
     * 不补偿的话，双指缩放会始终围绕视口中心发生，用户按住头部放大时头部会跑掉。
     */
    fun zoomedOffset(
        currentOffset: Offset,
        centroid: Offset,
        viewportCenter: Offset,
        scaleFactor: Float,
    ): Offset {
        if (!scaleFactor.isFinite()) return currentOffset
        val focus = centroid - viewportCenter
        return currentOffset * scaleFactor + focus * (1f - scaleFactor)
    }

    /**
     * 拖拽释放后的目标偏移。
     *
     * 正常模式用 [MotionSpecs.project] 把释放瞬间的初速度换算成 decay 距离，再钳制
     * 回边界，形成「甩出去、减速、贴边」的惯性；减少动态时完全不投影速度，直接
     * 吸附到当前合法的边界内，避免任何持续运动。
     */
    fun releaseTarget(
        offset: Offset,
        velocity: Offset,
        viewport: Size,
        content: Size,
        scale: Float,
        reduceMotion: Boolean,
    ): Offset {
        val clamped = clampOffset(offset, viewport, content, scale)
        if (reduceMotion) return clamped
        val projected = Offset(
            clamped.x + MotionSpecs.project(velocity.x),
            clamped.y + MotionSpecs.project(velocity.y),
        )
        return clampOffset(projected, viewport, content, scale)
    }
}

/** 缩略图/原图在内存里允许的最长边对应的采样倍率（2 的幂）。 */
internal fun sampleSizeFor(width: Int, height: Int, maxEdgePx: Int): Int {
    if (width <= 0 || height <= 0 || maxEdgePx <= 0) return 1
    var sample = 1
    while (width / sample > maxEdgePx || height / sample > maxEdgePx) {
        // 上限保护：即使外部传入病态尺寸也不会死循环。
        if (sample > 1 shl 20) break
        sample *= 2
    }
    return sample
}

/** 等比缩到最长边不超过 [maxEdgePx]；小于上限时保持原尺寸，不放大。 */
internal fun scaledSize(width: Int, height: Int, maxEdgePx: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 0 to 0
    val longest = max(width, height)
    if (longest <= maxEdgePx) return width to height
    val ratio = maxEdgePx.toFloat() / longest
    return max(1, (width * ratio).roundToInt()) to max(1, (height * ratio).roundToInt())
}

/**
 * 在 IO 线程解码一张展示用图片。
 *
 * 先读 bounds 再采样：直接解码一张 8000px 的原图会先分配几百 MB，随后才谈得上
 * 缩小。优先 `ImageDecoder`（它会应用 EXIF 方向，手机竖拍的照片才不会横着），
 * 失败或不可用时回退 `BitmapFactory`。任何异常都返回 null，由调用方显示
 * 「预览不可用」而不是崩溃或空白。
 */
internal suspend fun decodeImagePreview(
    bytes: ByteArray?,
    maxEdgePx: Int = OriginalImagePolicy.MaxEdgePx,
): ImageBitmap? {
    if (bytes == null || bytes.isEmpty()) return null
    return withContext(Dispatchers.IO) {
        runCatching { decodeBitmap(bytes, maxEdgePx)?.asImageBitmap() }.getOrNull()
    }
}

/**
 * 控制器专用：把原图字节解码、缩到最长边 [maxEdgePx]，再压缩成 JPEG [quality]。
 *
 * 这样缓存里存的是「已到展示尺寸的压缩结果」，而不是原始几十 MB 的相机文件；
 * 界面每次打开大图只需再解码一次小图。解码失败返回 null，控制器据此置 FAILED。
 */
internal fun compressOriginalImageForViewer(
    bytes: ByteArray,
    maxEdgePx: Int = OriginalImagePolicy.MaxEdgePx,
    quality: Int = OriginalImagePolicy.JpegQuality,
): ByteArray? {
    val bitmap = runCatching { decodeBitmap(bytes, maxEdgePx) }.getOrNull() ?: return null
    return try {
        ByteArrayOutputStream().use { output ->
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                output.toByteArray().takeIf { it.isNotEmpty() }
            } else {
                null
            }
        }
    } finally {
        bitmap.recycle()
    }
}

/**
 * 读取原图字节，超过 [limit] 立刻失败。
 *
 * 不能先 `readBytes()` 再检查长度：24MB 的上限存在的意义就是不把超限文件完整
 * 读进内存。这里边读边计数，越界即抛 `ORIGINAL_TOO_LARGE`。
 */
internal fun readOriginalBytes(
    stream: InputStream,
    limit: Int = OriginalImagePolicy.MaxBytes,
): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DefaultReadBufferSize)
    var total = 0
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > limit) throw IOException("ORIGINAL_TOO_LARGE")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

/**
 * 按需解码且只在字节变化时重新解码。
 *
 * 解码失败会保留 null：调用方据此显示「原图暂不可用，当前为预览图」之类的
 * 明确文案，而不是无限转圈或画一块空白。
 */
@Composable
internal fun rememberDecodedPreview(
    bytes: ByteArray?,
    maxEdgePx: Int = OriginalImagePolicy.MaxEdgePx,
): ImageBitmap? {
    var decoded by remember(bytes) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bytes, maxEdgePx) {
        decoded = decodeImagePreview(bytes, maxEdgePx)
    }
    return decoded
}

private const val DefaultReadBufferSize = 64 * 1024

private fun readBounds(bytes: ByteArray): BitmapFactory.Options? = runCatching {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    options.takeIf { it.outWidth > 0 && it.outHeight > 0 }
}.getOrNull()

private fun decodeBitmap(bytes: ByteArray, maxEdgePx: Int): Bitmap? {
    val bounds = readBounds(bytes)
    val width = bounds?.outWidth ?: 0
    val height = bounds?.outHeight ?: 0
    return decodeWithImageDecoder(bytes, maxEdgePx, width, height)
        ?: decodeWithBitmapFactory(bytes, maxEdgePx, width, height)
}

private fun decodeWithImageDecoder(bytes: ByteArray, maxEdgePx: Int, width: Int, height: Int): Bitmap? =
    runCatching {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // 软件位图：展示用图片不需要 GPU 纹理，也避免在某些设备上把大图锁进显存。
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val sourceWidth = if (width > 0) width else info.size.width
            val sourceHeight = if (height > 0) height else info.size.height
            val (targetWidth, targetHeight) = scaledSize(sourceWidth, sourceHeight, maxEdgePx)
            if (targetWidth > 0 && targetHeight > 0 &&
                (targetWidth != sourceWidth || targetHeight != sourceHeight)
            ) {
                decoder.setTargetSize(targetWidth, targetHeight)
            }
        }
    }.getOrNull()

private fun decodeWithBitmapFactory(bytes: ByteArray, maxEdgePx: Int, width: Int, height: Int): Bitmap? =
    runCatching {
        val sampleSize = if (width > 0 && height > 0) sampleSizeFor(width, height, maxEdgePx) else 1
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null
        val (targetWidth, targetHeight) = scaledSize(decoded.width, decoded.height, maxEdgePx)
        if (targetWidth == decoded.width && targetHeight == decoded.height) {
            decoded
        } else {
            // 采样只能是 2 的幂，最后一步用精确缩放补足到 2048px 以内。
            Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true).also {
                if (it !== decoded) decoded.recycle()
            }
        }
    }.getOrNull()
