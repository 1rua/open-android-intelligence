package com.openandroidintelligence.screen

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.openandroidintelligence.control.ScreenDisplay
import com.openandroidintelligence.control.ScreenSnapshot
import com.openandroidintelligence.control.ScreenBounds

/** 原生帧的像素坐标、取帧时刻与所有权，不能从远端参数伪造。 */
internal class SomFrame(
    val bitmap: Bitmap,
    val capturedAtUptimeMs: Long,
    val display: ScreenDisplay,
    val capturedBounds: ScreenBounds,
    val overlayExcluded: Boolean,
) : AutoCloseable {
    override fun close() { if (!bitmap.isRecycled) bitmap.recycle() }
}

internal fun interface SomFrameSource {
    suspend fun capture(snapshot: ScreenSnapshot): SomFrame
}

internal class AndroidSomFrameSource(private val context: Context) : SomFrameSource {
    override suspend fun capture(snapshot: ScreenSnapshot): SomFrame =
        AndroidAccessibilityScreenshotRuntime(context,
            snapshot.activeWindowId.takeIf { Build.VERSION.SDK_INT >= 34 }).takeSomFrame(snapshot)
}
