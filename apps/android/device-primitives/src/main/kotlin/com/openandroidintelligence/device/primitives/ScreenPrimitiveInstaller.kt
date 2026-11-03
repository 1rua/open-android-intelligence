package com.openandroidintelligence.device.primitives

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.openandroidintelligence.capability.*
import com.openandroidintelligence.control.ScreenHostRegistry
import com.openandroidintelligence.control.ScreenOperationException
import com.openandroidintelligence.kernel.KernelPrimitiveRegistry
import com.openandroidintelligence.screen.AndroidAccessibilityScreenshotRuntime
import com.openandroidintelligence.screen.AndroidScreenBackend
import com.openandroidintelligence.screen.ScreenAccessibilityConnection
import java.io.ByteArrayOutputStream

/** Registers real Android providers without exposing collectors on the App's compile-time surface. */
internal object ScreenPrimitiveInstaller {
    fun install(context: Context) {
        val app = context.applicationContext
        val projection = MediaProjectionScreenCaptureSource(MediaProjectionRuntime(app,
            onSystemStopped = {
                RemoteScreenCaptureRegistry.stopForeground(app)
                ScreenHostRegistry.current?.captureStopped()
            }))
        // Agent 远程截图优先走无障碍按需截图：用户只需在系统设置里开启一次，
        // 之后高频截图不再反复弹系统投屏确认。MediaProjection 仍作为需要
        // 明确用户同意的备用方案保留，两者是同一来源内的两条路径，
        // 不是两套授权体系。
        val source = AccessibilityFirstScreenCaptureSource(
            accessibility = AndroidAccessibilityScreenshotRuntime(app),
            projection = projection,
            accessibilityScreenshotSupported = { Build.VERSION.SDK_INT >= Build.VERSION_CODES.R },
            deviceLocked = { app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true },
        )
        RemoteScreenCaptureRegistry.install(source)
        val runtime = RemoteScreenRuntime(AndroidScreenBackend(app), source,
            stopProjection = { source.release(); RemoteScreenCaptureRegistry.stopForeground(app) },
            captureReadiness = { captureReadiness(source) },
            resize = ::resize)
        ScreenHostRegistry.current = runtime
        runtime.providers.forEach(KernelPrimitiveRegistry::register)
        ScreenAccessibilityConnection.addDisconnectListener(runtime::revoke)
    }

    /**
     * 截图能力的「发布态」判定：只回答「现在能不能捕获」这一个事实。
     *
     * 这里**不能**把「尚未申请授权」当成不可恢复的阻塞。返回的诊断码让远端
     * 与界面能区分四种处境，各自引导不同：
     * - 无障碍服务已连接 → 就绪（不需要每次同意）；
     * - 开过但当前没连上 → 服务断连，去无障碍设置确认；
     * - 从未开启 → 尚未授权，属于正常起点；
     * - 系统限速 / 受保护窗口 → 暂时状态，稍后重试。
     *
     * 返回 null 表示就绪；返回稳定原因码表示当前不可用及其具体类别。
     */
    private fun captureReadiness(source: AccessibilityFirstScreenCaptureSource): String? {
        if (source.isAvailable) return null
        return source.diagnose()?.code ?: ScreenCaptureFailure.NOT_AUTHORIZED.code
    }

    private fun resize(frame: ScreenCapture, maxWidth: Int?): ScreenCapture {
        if (maxWidth == null || frame.widthPixels <= maxWidth) return frame
        val bytes = frame.pngBytes()
        val options = BitmapFactory.Options().apply {
            var sample = 1
            while (frame.widthPixels / (sample * 2) >= maxWidth) sample *= 2
            inSampleSize = sample
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw ScreenOperationException("SCREEN_CAPTURE_ENCODING_FAILED")
        var scaled: Bitmap? = null
        try {
            val height = (frame.heightPixels.toLong() * maxWidth / frame.widthPixels).toInt().coerceAtLeast(1)
            scaled = Bitmap.createScaledBitmap(decoded, maxWidth, height, true)
            val output = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) throw ScreenOperationException("SCREEN_CAPTURE_ENCODING_FAILED")
            return ScreenCapture.copyOf(output.toByteArray(), maxWidth, height)
        } finally {
            if (scaled !== decoded) scaled?.recycle()
            decoded.recycle()
        }
    }
}