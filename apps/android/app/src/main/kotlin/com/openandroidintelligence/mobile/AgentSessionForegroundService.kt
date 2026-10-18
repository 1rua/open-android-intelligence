package com.openandroidintelligence.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper

internal const val AGENT_SESSION_FOREGROUND_CHANNEL_ID = "agent-session-background"
internal const val AGENT_SESSION_FOREGROUND_NOTIFICATION_ID = 2401

/**
 * Raises the importance of the process that already owns the Gateway SSE.
 * The runtime owns credentials and connections, including recovery after system
 * recreation of an eligible ongoing task. Conversation bubbles and
 * Live Updates use separate notifications: an FGS notification cannot bubble.
 */
class AgentSessionForegroundService : Service() {
    private val recovery by lazy { AgentSessionRecoveryState(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val recoveryTimeout = Runnable {
        if (recovery.isRecovering) finishReceiving()
    }

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
        if (!recovery.isStartEligible) {
            finishReceiving()
            return START_NOT_STICKY
        }
        val recoveryDelay = if (intent == null) recovery.beginRecovery() else null
        if (intent == null && recoveryDelay == null) {
            finishReceiving()
            return START_NOT_STICKY
        }
        try {
            startForeground(AGENT_SESSION_FOREGROUND_NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: IllegalStateException) {
            finishReceiving()
            return START_NOT_STICKY
        } catch (_: SecurityException) {
            finishReceiving()
            return START_NOT_STICKY
        }
        if (recoveryDelay != null) {
            handler.removeCallbacks(recoveryTimeout)
            handler.postDelayed(recoveryTimeout, recoveryDelay)
            // Foreground first: loading credentials or opening sockets must not
            // consume Android's short startForeground deadline.
            try {
                (application as? OpenAndroidIntelligenceApplication)?.onBackgroundSessionRestarted()
            } catch (_: RuntimeException) {
                finishReceiving()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ dataSync services must stop promptly after their time budget.
        finishReceiving()
    }

    override fun onDestroy() {
        handler.removeCallbacks(recoveryTimeout)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } finally {
            super.onDestroy()
        }
    }

    private fun finishReceiving() {
        (application as? OpenAndroidIntelligenceApplication)?.onBackgroundSessionStopped()
        recovery.clear()
        handler.removeCallbacks(recoveryTimeout)
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
