package com.openandroidintelligence.capability

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ScreenCaptureSource] 的 MediaProjection 编排实现。
 *
 * 这个类只做授权与会话的 fail-closed 决策，所有系统调用都经
 * [ScreenProjectionRuntime] seam 转发：单元测试用 fake 驱动决策矩阵，
 * 真实设备行为由薄壳 [MediaProjectionRuntime] 承担。
 *
 * 隐私红线在此逐条落地：
 * - 未授权 / 授权被拒 / 会话被系统回收 / 截帧失败 → [capture] 返回 null、
 *   [isAvailable] 为 false；没有重试、没有重建、没有缓存旧帧充数。
 * - 授权只能来自系统对话框（[createAuthorizationIntent] 必须由宿主前台
 *   Activity 发起）；本类不提供任何静默授权路径。
 */
class MediaProjectionScreenCaptureSource(
    private val runtime: ScreenProjectionRuntime,
) : ScreenCaptureSource {

    private val lock = Any()
    private var session: ProjectionDisplaySession? = null
    private var authorizationGranted: Boolean = false

    override val isAvailable: Boolean
        get() = synchronized(lock) {
            authorizationGranted && session?.isActive == true
        }

    override fun createAuthorizationIntent(): Intent? {
        if (!runtime.hasProjectionService()) return null
        return runtime.createAuthorizationIntent()
    }

    override fun onAuthorizationResult(resultCode: Int, resultData: Intent?): Boolean = synchronized(lock) {
        // 用户在系统对话框里拒绝，或宿主转交的结果残缺：明确回到未授权，
        // 并释放可能残留的旧会话，不留半个可用的采集通道。
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            authorizationGranted = false
            closeSessionLocked()
            return false
        }
        val opened = runtime.openSession(resultCode, resultData) ?: run {
            authorizationGranted = false
            closeSessionLocked()
            return false
        }
        // 重复授权（例如旧会话过期后用户重新授予）：先释放旧会话再接管，
        // VirtualDisplay/ImageReader 泄漏比少截一帧严重得多。
        closeSessionLocked()
        session = opened
        authorizationGranted = true
        return true
    }

    override suspend fun capture(): ScreenCapture? {
        val current = synchronized(lock) {
            if (!authorizationGranted) return null
            session
        } ?: return null
        if (!current.isActive) return null
        return withContext(Dispatchers.IO) { current.grabFrame() }
    }

    override fun release(): Unit = synchronized(lock) {
        authorizationGranted = false
        closeSessionLocked()
    }

    private fun closeSessionLocked() {
        session?.let { existing ->
            // 释放失败不能阻止后续状态归零，也不能冒泡打断调用方。
            runCatching { existing.close() }
        }
        session = null
    }
}

/**
 * 真实系统壳：把 MediaProjectionManager / MediaProjection / ImageReader /
 * VirtualDisplay 的调用收口在 [ScreenProjectionRuntime] seam 之后。
 *
 * 单元测试不覆盖这个类（依赖真实投屏），它被刻意保持为薄封装：没有状态、
 * 没有重试策略，所有失败都折返 null 交给编排层 fail-closed。
 */
class MediaProjectionRuntime(context: Context) : ScreenProjectionRuntime {

    private val appContext = context.applicationContext
    private val projectionManager: MediaProjectionManager? =
        appContext.getSystemService(MediaProjectionManager::class.java)

    override fun hasProjectionService(): Boolean = projectionManager != null

    override fun createAuthorizationIntent(): Intent? = projectionManager?.createScreenCaptureIntent()

    override fun openSession(resultCode: Int, resultData: Intent?): ProjectionDisplaySession? {
        val manager = projectionManager ?: return null
        if (resultData == null) return null
        val projection = try {
            // 前置条件：宿主已启动 foregroundServiceType="mediaProjection"
            // 的前台服务；缺失时系统抛 SecurityException，这里折返 null。
            manager.getMediaProjection(resultCode, resultData)
        } catch (_: SecurityException) {
            return null
        } catch (_: IllegalStateException) {
            return null
        }
        val screen = realScreenSize() ?: run {
            runCatching { projection.stop() }
            return null
        }
        return ImageReaderProjectionSession(projection, screen.width, screen.height, screen.densityDpi)
    }

    private data class RealScreen(val width: Int, val height: Int, val densityDpi: Int)

    private fun realScreenSize(): RealScreen? {
        val windowManager = appContext.getSystemService(WindowManager::class.java) ?: return null
        val bounds = runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull() ?: return null
        val densityDpi = appContext.resources.displayMetrics.densityDpi
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0 || height <= 0 || densityDpi <= 0) return null
        return RealScreen(width, height, densityDpi)
    }

    override fun close() {}
}

/**
 * [ProjectionDisplaySession] 的真实实现：VirtualDisplay 镜像到 RGBA_8888
 * 的 [ImageReader]，按需取帧编码为 PNG。[close] 依次释放 VirtualDisplay、
 * ImageReader 并停止 MediaProjection，三步各自容错，避免一步失败掩盖其余。
 */
private class ImageReaderProjectionSession(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    densityDpi: Int,
) : ProjectionDisplaySession {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    private val virtualDisplay: VirtualDisplay = projection.createVirtualDisplay(
        "open-android-intelligence-screen-capture",
        width,
        height,
        densityDpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        imageReader.surface,
        null,
        null,
    )

    /** 虚拟显示渲染出新帧的信号；grabFrame 在首次取空时有限等待一次。 */
    private val frameSignal = Semaphore(0)

    @Volatile
    private var active: Boolean = true

    init {
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    active = false
                }
            },
            mainHandler,
        )
        imageReader.setOnImageAvailableListener({ frameSignal.release() }, mainHandler)
    }

    override val isActive: Boolean
        get() = active

    override fun grabFrame(): ScreenCapture? {
        if (!active) return null
        var image: Image? = imageReader.acquireLatestImage()
        if (image == null) {
            // 虚拟显示建立后第一帧需要一点渲染时间；这是「等帧」，不是
            // 「失败重试」：只等待一次，超时按失败折返 null。
            if (!frameSignal.tryAcquire(FRAME_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
            image = imageReader.acquireLatestImage()
        }
        val frame = image ?: return null
        try {
            return encodePng(frame)
        } finally {
            frame.close()
        }
    }

    private fun encodePng(image: Image): ScreenCapture? {
        if (image.width <= 0 || image.height <= 0) return null
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer ?: return null
        val pixelStride = plane.pixelStride
        if (pixelStride <= 0) return null
        val rowStride = plane.rowStride
        // 行对齐（rowStride > width*4）是常态：先按行距建图，再裁掉 padding。
        val stridePixels = rowStride / pixelStride
        if (stridePixels < image.width) return null
        buffer.rewind()
        val strided = Bitmap.createBitmap(stridePixels, image.height, Bitmap.Config.ARGB_8888) ?: return null
        strided.copyPixelsFromBuffer(buffer)
        val bitmap = if (stridePixels == image.width) {
            strided
        } else {
            Bitmap.createBitmap(strided, 0, 0, image.width, image.height) ?: return null
        }
        val output = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
        val bytes = output.toByteArray()
        if (bytes.isEmpty()) return null
        return ScreenCapture.copyOf(bytes, image.width, image.height)
    }

    override fun close() {
        active = false
        runCatching { virtualDisplay.release() }
        runCatching { imageReader.close() }
        runCatching { projection.stop() }
    }

    private companion object {
        const val FRAME_WAIT_TIMEOUT_MS = 700L
    }
}
