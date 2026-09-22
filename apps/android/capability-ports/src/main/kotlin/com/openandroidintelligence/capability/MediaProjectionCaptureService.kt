package com.openandroidintelligence.capability

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * 屏幕采集的前台服务壳（Android 14+ 对 MediaProjection 的硬性要求）。
 *
 * 它只承担一件事：让系统在投影会话活跃期间看见一个
 * `foregroundServiceType="mediaProjection"` 的前台服务。这个服务不接触
 * 像素数据、不持有授权票据、也没有任何启动投影的逻辑——授权与采集仍在
 * [ScreenCaptureSource] 的编排里。常驻通知是刻意的：屏幕采集必须是用户
 * 可感知的行为，不存在「静默采集」这个合法形态。
 *
 * 宿主装配顺序（也写在 README 与回执里）：
 * 1. 宿主 Activity 用 [MediaProjectionScreenCaptureSource.createAuthorizationIntent]
 *    发起系统授权对话框；
 * 2. 授权返回 `RESULT_OK` 后，先 [start] 本服务（startForegroundService），
 *    再把结果转交 [MediaProjectionScreenCaptureSource.onAuthorizationResult]
 *    ——getMediaProjection 要求 mediaProjection 型前台服务已在运行；
 * 3. 调用 [ScreenCaptureSource.release] 或会话被系统回收后，用 [stop] 撤销
 *    前台状态。
 *
 * Manifest 声明（service 全类名 + foregroundServiceType + 所需权限）由宿主
 * 的 AndroidManifest 合并，见回执中的精确片段。
 */
class MediaProjectionCaptureService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        // 投影会话的生命周期由 ScreenCaptureSource 管理；这里没有任何
        // 逻辑需要重建服务，被系统回收即随会话一起结束。
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "屏幕圈选采集", NotificationManager.IMPORTANCE_LOW).apply {
                description = "屏幕圈选依赖系统投屏授权，采集期间显示此常驻通知。"
            },
        )
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕圈选采集中")
            .setContentText("正在使用你授予的系统投屏权限采集屏幕截图。")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "screen_capture_projection"
        private const val NOTIFICATION_ID = 1001

        /** 宿主在授权对话框返回 RESULT_OK 之后、转交授权结果之前调用。 */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, MediaProjectionCaptureService::class.java))
        }

        /** 采集源 release 或会话被系统回收之后调用，撤销前台状态。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, MediaProjectionCaptureService::class.java))
        }
    }
}
