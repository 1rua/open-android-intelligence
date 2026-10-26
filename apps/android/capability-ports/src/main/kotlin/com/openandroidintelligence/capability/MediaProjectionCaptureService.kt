package com.openandroidintelligence.capability

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import java.util.UUID

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
 * 2. 授权返回 `RESULT_OK` 后调用 [start]（startForegroundService），**并等待
 *    [start] 传入的就绪回调**——`startForegroundService` 只是排队请求，
 *    `getMediaProjection` 要求服务已经完成 `startForeground()`，在回调到来前
 *    转交授权结果必然以 SecurityException 失败（targetSdk 34+ 硬性要求）；
 * 3. 授权被拒或会话建立失败时，宿主必须用 [stop] 撤销前台状态；
 *    调用 [ScreenCaptureSource.release] 或会话被系统回收后同理。
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
        val owner = intent?.getStringExtra(EXTRA_OWNER) ?: return START_NOT_STICKY
        val generation = intent.getStringExtra(EXTRA_GENERATION) ?: return START_NOT_STICKY
        // 已撤销的排队启动请求不能重新开启前台状态或转交过期授权。
        if (!ownership.matches(owner, generation)) return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        // startForegroundService 只是排队请求；getMediaProjection 要求
        // mediaProjection 型前台服务**已经完成** startForeground。所以这里
        // 必须在本方法内同步通知等待者「服务就绪」，宿主才能转交授权结果，
        // 否则授权必然以 SecurityException 失败（targetSdk 34+ 硬性要求）。
        ownership.ready(owner, generation)?.invoke()
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
        private const val EXTRA_OWNER = "projection_owner"
        private const val EXTRA_GENERATION = "projection_owner_generation"
        const val LOCAL_OWNER = "local-screen-selection"

        private val ownership = ProjectionForegroundOwnership()
        private val lifecycleLock = Any()

        /**
         * 宿主在授权对话框返回 RESULT_OK 之后调用；[onForegroundReady] 会在
         * 服务真正进入前台状态后的主线程被回调，届时转交授权结果才是安全的。
         */
        fun start(context: Context, owner: String = LOCAL_OWNER, onForegroundReady: (() -> Unit)? = null) {
            synchronized(lifecycleLock) {
                val generation = ownership.acquire(owner, onForegroundReady)
                try {
                    context.startForegroundService(Intent(context, MediaProjectionCaptureService::class.java)
                        .putExtra(EXTRA_OWNER, owner).putExtra(EXTRA_GENERATION, generation))
                } catch (failure: RuntimeException) {
                    ownership.release(owner)
                    throw failure
                }
            }
        }

        /** 采集源 release 或会话被系统回收之后调用，撤销前台状态。 */
        fun stop(context: Context, owner: String = LOCAL_OWNER) {
            // 系统前台服务由本地圈选与远程截图共享；任何一方撤销只释放
            // 自己的来源，仍有另一方使用时不停止整个服务。
            synchronized(lifecycleLock) {
                if (ownership.release(owner)) context.stopService(Intent(context, MediaProjectionCaptureService::class.java))
            }
        }
    }
}

/** 同进程资源所有权，便于直接验证不相互停止和过期回调保护。 */
internal class ProjectionForegroundOwnership {
    private data class Owner(val generation: String, val onReady: (() -> Unit)?)
    private val listeners = mutableMapOf<String, Owner>()

    @Synchronized fun acquire(owner: String, onReady: (() -> Unit)?): String {
        require(owner.isNotBlank())
        val generation = UUID.randomUUID().toString()
        listeners[owner] = Owner(generation, onReady)
        return generation
    }

    @Synchronized fun contains(owner: String): Boolean = listeners.containsKey(owner)

    @Synchronized fun matches(owner: String, generation: String): Boolean = listeners[owner]?.generation == generation

    @Synchronized fun ready(owner: String, generation: String): (() -> Unit)? {
        val current = listeners[owner]?.takeIf { it.generation == generation } ?: return null
        listeners[owner] = current.copy(onReady = null)
        return current.onReady
    }

    /** true 表示本次释放了最后一个有效所有者，可以停止前台服务。 */
    @Synchronized fun release(owner: String): Boolean {
        if (!listeners.containsKey(owner)) return false
        listeners.remove(owner)
        return listeners.isEmpty()
    }
}
