package com.openandroidintelligence.device.primitives

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.openandroidintelligence.capability.*
import com.openandroidintelligence.control.ScreenHostRegistry
import com.openandroidintelligence.control.ScreenOperationException
import com.openandroidintelligence.kernel.KernelPrimitiveRegistry
import com.openandroidintelligence.screen.AndroidScreenBackend
import com.openandroidintelligence.screen.ScreenAccessibilityConnection
import java.io.ByteArrayOutputStream

/** Registers real Android providers without exposing collectors on the App's compile-time surface. */
internal object ScreenPrimitiveInstaller {
    fun install(context: Context) {
        val app = context.applicationContext
        val source = MediaProjectionScreenCaptureSource(MediaProjectionRuntime(app,
            onSystemStopped = {
                RemoteScreenCaptureRegistry.stopForeground(app)
                ScreenHostRegistry.current?.captureStopped()
            }))
        RemoteScreenCaptureRegistry.install(source)
        val runtime = RemoteScreenRuntime(AndroidScreenBackend(app), source,
            stopProjection = { source.release(); RemoteScreenCaptureRegistry.stopForeground(app) },
            captureReadiness = {
                if (app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) "SCREEN_DEVICE_LOCKED" else null
            }, resize = ::resize)
        ScreenHostRegistry.current = runtime
        runtime.providers.forEach(KernelPrimitiveRegistry::register)
        ScreenAccessibilityConnection.addDisconnectListener(runtime::revoke)
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
