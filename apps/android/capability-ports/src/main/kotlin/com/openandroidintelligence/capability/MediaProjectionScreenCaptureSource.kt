package com.openandroidintelligence.capability

import android.app.Activity
import android.content.Context
import android.content.ComponentCallbacks
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
    private var generation: Long = 0

    override val isAvailable: Boolean
        get() = synchronized(lock) {
            authorizationGranted && session?.isActive == true
        }

    override fun createAuthorizationIntent(): Intent? {
        return try {
            if (!runtime.hasProjectionService()) null else runtime.createAuthorizationIntent()
        } catch (_: RuntimeException) {
            null
        }
    }

    override fun onAuthorizationResult(resultCode: Int, resultData: Intent?): Boolean = synchronized(lock) {
        authorizationGranted = false
        generation++
        // Android 14+ 的授权票据只允许建立一个 VirtualDisplay；先关闭旧
        // 会话，也避免旧会话的清理回调误停后来建立的前台服务。
        closeSessionLocked()
        // 用户在系统对话框里拒绝，或宿主转交的结果残缺：明确回到未授权，
        // 并释放可能残留的旧会话，不留半个可用的采集通道。
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            return false
        }
        val opened = try { runtime.openSession(resultCode, resultData) } catch (_: RuntimeException) { null }
            ?: return false
        if (!opened.isActive) {
            runCatching { opened.close() }
            return false
        }
        // 重复授权（例如旧会话过期后用户重新授予）：先释放旧会话再接管，
        // VirtualDisplay/ImageReader 泄漏比少截一帧严重得多。
        session = opened
        authorizationGranted = true
        return true
    }

    override suspend fun capture(): ScreenCapture? {
        val (current, capturedGeneration) = synchronized(lock) {
            if (!authorizationGranted) return null
            (session ?: return null) to generation
        }
        if (!current.isActive) {
            invalidate(current)
            return null
        }
        val frame = try {
            withContext(Dispatchers.IO) {
                ensureActive()
                current.grabFrame().also { ensureActive() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            invalidate(current)
            return null
        }
        // 截帧期间可能撤销授权、切换会话或被系统停止；迟到的帧不得交付。
        return synchronized(lock) {
            if (authorizationGranted && generation == capturedGeneration && session === current && current.isActive) frame
            else null
        }
    }

    override fun release(): Unit = synchronized(lock) {
        authorizationGranted = false
        generation++
        closeSessionLocked()
    }

    private fun invalidate(current: ProjectionDisplaySession) = synchronized(lock) {
        if (session === current) {
            authorizationGranted = false
            generation++
            closeSessionLocked()
        }
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
class MediaProjectionRuntime(
    context: Context,
    onSystemStopped: (() -> Unit)? = null,
) : ScreenProjectionRuntime {

    private val appContext = context.applicationContext
    private val stopped = onSystemStopped ?: { MediaProjectionCaptureService.stop(appContext) }
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
        val session = ImageReaderProjectionSession.open(projection, screen.width, screen.height, screen.densityDpi, stopped)
            ?: return null
        return try {
            // API 31–33 没有 onCapturedContentResize；配置变化仍更新同一
            // VirtualDisplay，避免旋转后一直使用旧屏幕尺寸。
            if (Build.VERSION.SDK_INT < 34) {
                session.watchConfiguration(appContext) { realScreenSize()?.let { it.width to it.height } }
            }
            session
        } catch (_: RuntimeException) {
            session.close()
            null
        }
    }

    private data class RealScreen(val width: Int, val height: Int, val densityDpi: Int)

    private fun realScreenSize(): RealScreen? {
        val windowManager = runCatching { appContext.getSystemService(WindowManager::class.java) }.getOrNull() ?: return null
        val bounds = runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull() ?: return null
        val densityDpi = runCatching { appContext.resources.displayMetrics.densityDpi }.getOrNull() ?: return null
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
private class ImageReaderProjectionSession private constructor(
    private val projection: MediaProjection,
    private val densityDpi: Int,
    private val onSystemStopped: () -> Unit,
) : ProjectionDisplaySession {

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var configurationCleanup: (() -> Unit)? = null
    private var contentGeneration: Long = 0

    /** 虚拟显示渲染出新帧的信号；grabFrame 在首次取空时有限等待一次。 */
    private val frameSignal = Semaphore(0)

    @Volatile
    private var active: Boolean = false

    private var closed: Boolean = false
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            val stopped = dispose(stopProjection = false)
            if (stopped) runCatching { onSystemStopped() }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            // 同一个授权只建立一个 VirtualDisplay；旋转/应用共享窗口改变时
            // 更新已有显示和 Surface，禁止复用授权重新 createVirtualDisplay。
            resize(width, height)
        }
    }

    fun watchConfiguration(context: Context, size: () -> Pair<Int, Int>?) = synchronized(lock) {
        if (!active || closed) return@synchronized
        val listener = object : ComponentCallbacks {
            override fun onConfigurationChanged(configuration: Configuration) {
                size()?.let { resize(it.first, it.second) }
            }
            override fun onLowMemory() {}
        }
        context.registerComponentCallbacks(listener)
        configurationCleanup = { context.unregisterComponentCallbacks(listener) }
    }

    private fun resize(width: Int, height: Int) {
        var notifyStopped = false
        synchronized(lock) {
            if (!active || closed) return@synchronized
            if (!validSize(width, height)) {
                notifyStopped = dispose(stopProjection = true)
                return@synchronized
            }
            val existing = imageReader ?: return@synchronized
            if (existing.width == width && existing.height == height) return@synchronized
            var replacement: ImageReader? = null
            try {
                replacement = newReader(width, height)
                virtualDisplay?.resize(width, height, densityDpi)
                virtualDisplay?.surface = replacement.surface
                imageReader = replacement
                contentGeneration++
                existing.close()
                frameSignal.drainPermits()
            } catch (_: RuntimeException) {
                runCatching { replacement?.close() }
                notifyStopped = dispose(stopProjection = true)
            }
        }
        // 外部清理可能重新进入 Source / 本地授权记录；禁止在session锁内回调。
        if (notifyStopped) runCatching { onSystemStopped() }
    }

    private fun initialize(width: Int, height: Int) = synchronized(lock) {
        require(validSize(width, height))
        // 必须早于 createVirtualDisplay；targetSdk 34+ 会拒绝无回调的投影。
        projection.registerCallback(callback, mainHandler)
        imageReader = newReader(width, height)
        virtualDisplay = projection.createVirtualDisplay(
            "open-android-intelligence-screen-capture",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            checkNotNull(imageReader).surface,
            null, mainHandler,
        )
        active = true
    }

    private fun newReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({
                // 信号只表示至少有一帧；不按显示刷新率无限累积许可。
                if (frameSignal.availablePermits() == 0) frameSignal.release()
            }, mainHandler)
        }

    private data class CapturedFrame(val image: Image, val generation: Long)

    private fun acquireFrame(): CapturedFrame? = synchronized(lock) {
        if (!active || closed) null else imageReader?.acquireLatestImage()?.let { CapturedFrame(it, contentGeneration) }
    }

    override val isActive: Boolean
        get() = active

    override fun grabFrame(): ScreenCapture? {
        if (!active) return null
        var image = acquireFrame()
        if (image == null) {
            // 虚拟显示建立后第一帧需要一点渲染时间；这是「等帧」，不是
            // 「失败重试」：只等待一次，超时按失败折返 null。
            val available = try {
                frameSignal.tryAcquire(FRAME_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            if (!available || !active) return null
            image = acquireFrame()
        }
        val frame = image ?: return null
        try {
            val encoded = encodePng(frame.image)
            return synchronized(lock) { encoded.takeIf { active && contentGeneration == frame.generation } }
        } finally {
            frame.image.close()
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
        if (stridePixels.toLong() * image.height > MAX_CAPTURE_PIXELS) return null
        val strided = Bitmap.createBitmap(stridePixels, image.height, Bitmap.Config.ARGB_8888)
        var bitmap: Bitmap? = null
        try {
            strided.copyPixelsFromBuffer(buffer)
            bitmap = if (stridePixels == image.width) strided
                else Bitmap.createBitmap(strided, 0, 0, image.width, image.height)
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
            val bytes = output.toByteArray()
            if (bytes.isEmpty()) return null
            return ScreenCapture.copyOf(bytes, image.width, image.height)
        } finally {
            if (bitmap !== strided) bitmap?.recycle()
            strided.recycle()
        }
    }

    override fun close() { dispose(stopProjection = true) }

    private fun dispose(stopProjection: Boolean): Boolean = synchronized(lock) {
        if (closed) return false
        closed = true
        active = false
        frameSignal.release()
        runCatching { configurationCleanup?.invoke() }
        configurationCleanup = null
        runCatching { projection.unregisterCallback(callback) }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        if (stopProjection) runCatching { projection.stop() }
        true
    }

    companion object {
        const val FRAME_WAIT_TIMEOUT_MS = 700L
        const val MAX_CAPTURE_PIXELS = 16_777_216L

        fun validSize(width: Int, height: Int): Boolean =
            width > 0 && height > 0 && width.toLong() * height <= MAX_CAPTURE_PIXELS

        fun open(
            projection: MediaProjection, width: Int, height: Int, densityDpi: Int,
            onSystemStopped: () -> Unit,
        ): ImageReaderProjectionSession? {
            val session = ImageReaderProjectionSession(projection, densityDpi, onSystemStopped)
            return try {
                session.initialize(width, height)
                session
            } catch (_: RuntimeException) {
                session.close()
                null
            }
        }
    }
}
