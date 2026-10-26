package com.openandroidintelligence.capability

import android.content.Context

/**
 * Remote capture has its own system consent session. It never borrows the
 * conversation attachment picker or its projection result. A future foreground
 * Activity can use this source's existing authorization Interface.
 */
object RemoteScreenCaptureRegistry {
    private const val REMOTE_OWNER = "remote-screen-control"
    @Volatile var source: ScreenCaptureSource? = null
        internal set

    fun install(source: ScreenCaptureSource) {
        check(this.source == null || this.source === source) { "SCREEN_CAPTURE_ALREADY_INSTALLED" }
        this.source = source
    }

    /** 仅在前台 Activity 获得新的系统投影授权后调用，等待就绪后再转交结果。 */
    fun startForeground(context: Context, onReady: () -> Unit) {
        MediaProjectionCaptureService.start(context, REMOTE_OWNER, onReady)
    }

    fun stopForeground(context: Context) {
        MediaProjectionCaptureService.stop(context, REMOTE_OWNER)
    }
}
