package com.openandroidintelligence.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

internal const val AGENT_SESSION_FOREGROUND_CHANNEL_ID = "agent-session-background"
internal const val AGENT_SESSION_FOREGROUND_NOTIFICATION_ID = 2401

/**
 * Raises the importance of the process that already owns the Gateway SSE.
 * It holds no credentials and creates no connection. Conversation bubbles and
 * Live Updates use separate notifications: an FGS notification cannot bubble.
 */
class AgentSessionForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(AGENT_SESSION_FOREGROUND_CHANNEL_ID, "Agent 后台会话", NotificationManager.IMPORTANCE_LOW).apply {
                description = "仅在 Agent 任务运行期间接收会话回复"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(AGENT_SESSION_FOREGROUND_NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: IllegalStateException) {
            finishReceiving()
        } catch (_: SecurityException) {
            finishReceiving()
        }
        // System recreation cannot silently resume work after task completion,
        // logout or a dataSync timeout. A visible task must request a new start.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ dataSync services must stop promptly after their time budget.
        finishReceiving()
    }

    override fun onDestroy() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } finally {
            super.onDestroy()
        }
    }

    private fun finishReceiving() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } finally {
            stopSelf()
        }
    }

    private fun notification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            AGENT_SESSION_FOREGROUND_NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, AGENT_SESSION_FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent_reply)
            .setContentTitle("Agent 任务处理中")
            .setContentText("正在后台接收会话回复")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setDefaults(0)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
            .build()
    }
}
