package com.openandroidintelligence.capability

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Remote capture has its own system consent session. It never borrows the
 * conversation attachment picker or its projection result. A future foreground
 * Activity can use this source's existing authorization Interface.
 */
object RemoteScreenCaptureRegistry {
    private const val REMOTE_OWNER = "remote-screen-control"
    private val _availabilityRevision = MutableStateFlow(0L)

    /** 屏幕采集来源安装、授权结果回传或投屏停止时递增，供 UI 响应式刷新授权状态。 */
    val availabilityRevision: StateFlow<Long> get() = _availabilityRevision

    @Volatile var source: ScreenCaptureSource? = null
        internal set

    fun notifyAvailabilityChanged() {
        _availabilityRevision.update { it + 1L }
    }

    fun install(source: ScreenCaptureSource) {
        check(this.source == null || this.source === source) { "SCREEN_CAPTURE_ALREADY_INSTALLED" }
        this.source = source
        notifyAvailabilityChanged()
    }

    /** 仅在前台 Activity 获得新的系统投影授权后调用，等待就绪后再转交结果。 */
    fun startForeground(context: Context, onReady: () -> Unit) {
        MediaProjectionCaptureService.start(context, REMOTE_OWNER, onReady)
    }

    fun stopForeground(context: Context) {
        MediaProjectionCaptureService.stop(context, REMOTE_OWNER)
        notifyAvailabilityChanged()
    }
}
