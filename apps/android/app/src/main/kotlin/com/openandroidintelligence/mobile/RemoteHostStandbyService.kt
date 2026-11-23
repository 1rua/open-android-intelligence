package com.openandroidintelligence.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.openandroidintelligence.ui.resources.R as UiR
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

internal interface RemoteStandbyOwner {
    suspend fun restoreHostStandby(): StateFlow<Boolean>
}

internal enum class StandbyStart { REQUESTED, RESTRICTED, PERMISSION_DENIED }

/** 可见窗口刚恢复时系统可能仍拒绝启动。只在同一可见授权内再次请求，后台与退出会取消等待。 */
internal suspend fun Flow<Boolean>.startVisibleHostStandby(request: () -> StandbyStart) {
    distinctUntilChanged().collectLatest { eligible ->
        if (eligible && request() == StandbyStart.RESTRICTED) {
            delay(1_000)
            request()
        }
    }
}

/** 服务只承载已登记 Host 的信令。每个控机请求仍由手机内核单独授权。 */
class RemoteHostStandbyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = getString(UiR.string.remote_host_role)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, title, NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_agent_reply)
            .setContentTitle(title).setContentIntent(open).setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (_: IllegalStateException) { return unavailable(startId) }
          catch (_: SecurityException) { return unavailable(startId) }
        // 系统黏性重建时先按时提交前台通知，再从当前加密凭据恢复；退出或收到终态会结束服务。
        observing?.cancel()
        observing = scope.launch {
            try {
                val standby = withContext(Dispatchers.IO) { (application as RemoteStandbyOwner).restoreHostStandby() }
                standby.first { eligible -> !eligible }
                stopSelfResult(startId)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { unavailable(startId) }
        }
        return START_STICKY
    }

    private fun unavailable(startId: Int): Int {
        Log.w(TAG, "STANDBY_UNAVAILABLE")
        stopSelfResult(startId)
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        internal const val CHANNEL = "remote-host-standby"
        internal const val NOTIFICATION_ID = 2402
        private const val TAG = "OAI-Virtual-Host"
        internal fun requestStart(context: Context): StandbyStart {
            return try {
                ContextCompat.startForegroundService(context, Intent(context, RemoteHostStandbyService::class.java))
                StandbyStart.REQUESTED
            } catch (_: IllegalStateException) {
                Log.w(TAG, "STANDBY_START_RESTRICTED")
                StandbyStart.RESTRICTED
            } catch (_: SecurityException) {
                Log.w(TAG, "STANDBY_PERMISSION_DENIED")
                StandbyStart.PERMISSION_DENIED
            }
        }
    }
}

/** 系统完成解锁启动或覆盖安装后，为仍有 Host 授权的账号恢复前台待命服务。 */
class RemoteHostBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? RemoteStandbyOwner ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (app.restoreHostStandby().value) RemoteHostStandbyService.requestStart(context)
            } catch (_: Exception) { Log.w("OAI-Virtual-Host", "BOOT_STANDBY_UNAVAILABLE") }
            finally { pending.finish() }
        }
    }
}
