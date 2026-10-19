package com.openandroidintelligence.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi

internal const val LIVE_UPDATE_CHANNEL_ID = "agent-live-task-updates"
// Public opt-in extra added in SDK 36.1. Keep compiling against the 36.0 SDK.
internal const val REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

/** The primary ongoing task surface on devices that support promoted notifications. */
internal class LiveUpdateNotifications(
    private val context: Context,
    private val settings: ReplyNotificationSettings,
) {
    fun update(session: NativeConversationSession): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(LIVE_UPDATE_CHANNEL_ID, "Agent 实时任务状态", NotificationManager.IMPORTANCE_LOW).apply {
            description = "进行中的 Agent 任务状态，可在系统支持的 Live Updates 界面显示"
            setShowBadge(false)
            setSound(null, null)
        })
        // A disconnected stream cannot attest that a remote task is still running.
        val active = session.status == NativeConversationStatus.QUEUED || session.status == NativeConversationStatus.RUNNING
        if (!active || !settings.enabled.value || !settings.canPostLiveUpdates()) {
            cancel(session.target)
            return false
        }
        try {
            val notification = Api36.build(context, session)
            if (!notification.hasPromotableCharacteristics()) {
                cancel(session.target)
                return false
            }
            manager.notify(liveUpdateTag(session.target), 0, notification)
            return true
        } catch (_: SecurityException) {
            cancel(session.target)
            settings.refreshPermission()
            return false
        }
    }

    fun cancel(target: ReplyNotificationTarget) {
        context.getSystemService(NotificationManager::class.java).cancel(liveUpdateTag(target), 0)
    }

    @RequiresApi(36)
    internal object Api36 {
        fun build(context: Context, session: NativeConversationSession): Notification {
            val queued = session.status == NativeConversationStatus.QUEUED
            val heading = if (queued) "Agent 任务排队中" else "Agent 正在执行任务"
            val preview = notificationReplyExcerpt(session.latestReply, isRunning = true)
                .ifBlank { if (queued) "等待 Agent 开始处理" else "任务进行中，等待新的公开回复" }
            val pending = PendingIntent.getActivity(context, 0, session.target.intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val conversationWindow = PendingIntent.getActivity(context, 0, session.target.bubbleIntent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = Notification.Builder(context, LIVE_UPDATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_agent_reply)
                .setContentTitle(heading)
                .setContentText(preview)
                .setSubText(session.title)
                .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
                .setShortCriticalText(if (queued) "排队中" else "处理中")
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(pending)
                .addAction(Notification.Action.Builder(null, "打开会话窗口", conversationWindow).build())
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .addExtras(Bundle().apply { putString("reply-account", replyAccountGroup(session.target.profileId, session.target.accountId)) })
            // SDK 36 omits @SdkIntFull on this runtime field even though it uses
            // the same encoding as the release constants accepted by the method.
            @SuppressLint("WrongConstant")
            val minor = Build.getMinorSdkVersion(Build.VERSION.SDK_INT_FULL)
            configurePromotion(builder, minor)
            val notification = builder.build()
            if (notification.hasPromotableCharacteristics()) return notification
            // Some OEMs backport the newer eligibility rules without changing the
            // minor SDK. Ask the real framework, never set FLAG_PROMOTED_ONGOING.
            return builder.setColorized(minor != 0).build()
        }

        // SDK 36.0 checks the colorization request, not FGS/signature colorization
        // permission; 36.1 replaces that requirement with the explicit opt-in extra.
        fun configurePromotion(builder: Notification.Builder, minorSdk: Int): Notification.Builder = builder
            .setColorized(minorSdk == 0)
            .addExtras(Bundle().apply { putBoolean(REQUEST_PROMOTED_ONGOING, true) })
    }
}

internal fun liveUpdateTag(target: ReplyNotificationTarget) = "agent-live-" + replyDigest("${target.profileId}\u0000${target.accountId}\u0000${target.conversationId}")

internal fun cancelLiveUpdateNotifications(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.activeNotifications.filter { it.notification.channelId == LIVE_UPDATE_CHANNEL_ID }.forEach { manager.cancel(it.tag, it.id) }
}
