package com.openandroidintelligence.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.pm.PackageManager
import android.content.LocusId
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.openandroidintelligence.conversation.data.GatewayEventDecoder
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.time.Instant

/** App reply alerts are independent of notification collection and pairing grants. */
class ReplyNotificationSettings(private val context: Context) {
    private val preferences = context.getSharedPreferences("agent-reply-alert-settings", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(preferences.getBoolean("enabled", true))
    val enabled = _enabled.asStateFlow()
    private val _allowed = MutableStateFlow(isAllowed())
    val allowed = _allowed.asStateFlow()
    private val _liveUpdatesAllowed = MutableStateFlow(canPostLiveUpdates())
    val liveUpdatesAllowed = _liveUpdatesAllowed.asStateFlow()
    val liveUpdatesSupported: Boolean get() = Build.VERSION.SDK_INT >= 36
    val permissionRequested: Boolean get() = preferences.getBoolean("permission-requested", false)

    fun setEnabled(enabled: Boolean) {
        check(preferences.edit().putBoolean("enabled", enabled).commit())
        _enabled.value = enabled
        if (!enabled) context.getSystemService(NotificationManager::class.java).let { manager ->
            manager.activeNotifications.filter { it.notification.channelId in setOf(REPLY_CHANNEL_ID, LIVE_UPDATE_CHANNEL_ID) }.forEach { manager.cancel(it.tag, it.id) }
        }
    }

    fun markPermissionRequested() {
        check(preferences.edit().putBoolean("permission-requested", true).commit())
    }

    fun refreshPermission() {
        _allowed.value = isAllowed()
        _liveUpdatesAllowed.value = canPostLiveUpdates()
        if (!enabled.value || !_liveUpdatesAllowed.value) cancelLiveUpdateNotifications(context)
    }

    fun canPostLiveUpdates(): Boolean {
        if (Build.VERSION.SDK_INT < 36 || !isAllowed()) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(LIVE_UPDATE_CHANNEL_ID)
        return (channel == null || channel.importance > NotificationManager.IMPORTANCE_MIN) && manager.canPostPromotedNotifications()
    }

    fun liveUpdatesSettingsIntent(): Intent? = if (Build.VERSION.SDK_INT >= 36) {
        Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else null

    fun isAllowed(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        // A missing channel on a fresh install has not been blocked by the user.
        val channel = manager.getNotificationChannel(REPLY_CHANNEL_ID)
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            manager.areNotificationsEnabled() && (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
    }
}

internal const val REPLY_CHANNEL_ID = "agent-task-replies"
private const val REPLY_ACTION = "com.openandroidintelligence.mobile.OPEN_AGENT_REPLY"

internal data class ReplyNotificationTarget(val profileId: String, val accountId: String, val conversationId: String) {
    fun bubbleIntent(context: Context): Intent = BubbleConversationActivity.intent(context, this)
    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(REPLY_ACTION)
        .setData(Uri.Builder().scheme("oai-reply").authority("conversation")
            .appendPath(profileId).appendPath(accountId).appendPath(conversationId).build())
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    companion object {
        fun from(intent: Intent): ReplyNotificationTarget? {
            val uri = intent.data ?: return null
            if (intent.action != REPLY_ACTION || uri.scheme != "oai-reply" || uri.host != "conversation") return null
            val parts = uri.pathSegments
            if (parts.size != 3 || parts.any { it.isBlank() }) return null
            return ReplyNotificationTarget(parts[0], parts[1], parts[2])
        }
    }
}

internal class AndroidReplyNotificationPublisher(
    private val context: Context,
    private val settings: ReplyNotificationSettings,
) {
    private val publishedShortcuts = mutableMapOf<String, String>()
    private val liveUpdates = LiveUpdateNotifications(context, settings)
    fun post(target: ReplyNotificationTarget, generationId: String) {
        update(NativeConversationSession(target, generationId, status = NativeConversationStatus.COMPLETED, updatedAt = System.currentTimeMillis()), true)
    }

    fun cancel(target: ReplyNotificationTarget) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationTag(target), 0)
        liveUpdates.cancel(target)
    }

    fun forget(target: ReplyNotificationTarget) {
        cancel(target)
        val shortcutId = "agent-conversation-${notificationTag(target)}"
        val shortcuts = context.getSystemService(ShortcutManager::class.java)
        // Launcher pins belong to the user, so invalidate their routing too.
        try {
            shortcuts.disableShortcuts(listOf(shortcutId))
            shortcuts.removeDynamicShortcuts(listOf(shortcutId))
            shortcuts.removeLongLivedShortcuts(listOf(shortcutId))
        } catch (_: RuntimeException) { /* Shortcut support can be unavailable on this device. */ }
        publishedShortcuts.remove(shortcutId)
    }

    fun update(session: NativeConversationSession, alert: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(REPLY_CHANNEL_ID, "Agent 任务回复", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Agent 处理中实时显示公开回复，并在任务结束时提醒"; setAllowBubbles(true) })
        val hasLiveUpdate = liveUpdates.update(session)
        if (!settings.enabled.value || !settings.isAllowed()) return
        val target = session.target
        val tag = notificationTag(target)
        val shortcutId = "agent-conversation-$tag"
        val icon = Icon.createWithResource(context, R.drawable.ic_agent_bubble)
        val participantKey = replyDigest("${target.profileId}\u0000${target.accountId}")
        val localUser = Person.Builder().setName("我").setKey("user-$participantKey").setBot(false).build()
        val agent = Person.Builder().setName("Agent").setKey("agent-$participantKey")
            .setBot(true).setIcon(icon).build()
        val bubbleIntent = target.bubbleIntent(context)
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        val accountGroup = replyAccountGroup(target.profileId, target.accountId)
        val shortcut = ShortcutInfo.Builder(context, shortcutId).setShortLabel(session.title)
            .setActivity(android.content.ComponentName(context, MainActivity::class.java))
            .setCategories(setOf("android.shortcut.conversation"))
            .setLongLived(true).setPersons(arrayOf(localUser, agent)).setIcon(icon)
            .setExtras(android.os.PersistableBundle().apply { putString("reply-account", accountGroup) })
            .setLocusId(LocusId(shortcutId)).setIntent(bubbleIntent).build()
        // A device may disable shortcuts/bubbles. The notification still works.
        val keepBubble = hasLiveUpdate && userSelectedBubble(manager, shortcutId, tag)
        val bubbleMetadata = try {
            if (publishedShortcuts[shortcutId] != session.title) {
                shortcutManager.enableShortcuts(listOf(shortcutId))
                shortcutManager.pushDynamicShortcut(shortcut)
                publishedShortcuts[shortcutId] = session.title
            }
            Notification.BubbleMetadata.Builder(shortcutId).setDesiredHeight(640)
                .setSuppressNotification(keepBubble).build()
        } catch (_: RuntimeException) { null }
        if (hasLiveUpdate && (!keepBubble || bubbleMetadata == null)) {
            manager.cancel(tag, 0)
            return
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, target.intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val heading = if (session.isRunning && session.lastOutcome != null) when (session.lastOutcome) {
            NativeConversationStatus.COMPLETED -> "Agent 已回复，仍有任务处理中"
            NativeConversationStatus.FAILED -> "部分任务失败，仍有任务处理中"
            NativeConversationStatus.CANCELLED -> "部分任务已取消，仍有任务处理中"
            else -> "Agent 正在处理"
        } else when (session.status) {
            NativeConversationStatus.QUEUED -> "Agent 任务排队中"
            NativeConversationStatus.RUNNING -> "Agent 正在处理"
            NativeConversationStatus.COMPLETED -> "Agent 任务已完成"
            NativeConversationStatus.FAILED -> "Agent 任务失败"
            NativeConversationStatus.CANCELLED -> "Agent 任务已取消"
            NativeConversationStatus.ENDED -> "任务已结束"
            NativeConversationStatus.DISCONNECTED -> "Gateway 已断开，任务状态待同步"
        }
        val preview = notificationReplyExcerpt(session.latestReply, session.isRunning).ifBlank { when (session.status) {
            NativeConversationStatus.QUEUED -> "等待 Agent 开始处理"
            NativeConversationStatus.RUNNING -> "等待 Agent 回复"
            NativeConversationStatus.COMPLETED -> "已收到回复，点击查看会话"
            NativeConversationStatus.FAILED -> "任务未完成，请查看会话中的错误信息"
            NativeConversationStatus.CANCELLED -> "任务已取消"
            NativeConversationStatus.ENDED -> "任务已结束，点击查看会话"
            NativeConversationStatus.DISCONNECTED -> "重新连接后同步任务状态"
        } }
        val style = Notification.MessagingStyle(localUser)
            .setConversationTitle(session.title).addMessage(preview, session.updatedAt, agent)
        val notification = Notification.Builder(context, REPLY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent_reply)
            .setContentTitle(heading)
            .setContentText(preview)
            .setSubText(heading)
            .setStyle(style)
            .setShortcutId(shortcutId)
            .setLocusId(LocusId(shortcutId))
            .setBubbleMetadata(bubbleMetadata)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .addExtras(android.os.Bundle().apply { putString("reply-account", accountGroup) })
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(session.isRunning)
            .setAutoCancel(!session.isRunning)
            .setOnlyAlertOnce(hasLiveUpdate || !alert)
            .setContentIntent(pendingIntent)
            .addAction(Notification.Action.Builder(null, "打开会话窗口",
                PendingIntent.getActivity(context, 0, bubbleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
            .build()
        // Permission may be revoked between checking it and the Binder call.
        try { manager.notify(tag, 0, notification) } catch (_: SecurityException) { settings.refreshPermission() }
    }

    fun eraseAccount(profileId: String, accountId: String) {
        check(replyCompletionPreferences(context, profileId, accountId).edit().clear().commit())
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.notification.extras.getString("reply-account") == replyAccountGroup(profileId, accountId) }
            .forEach { manager.cancel(it.tag, it.id) }
        val shortcuts = context.getSystemService(ShortcutManager::class.java)
        // Evicted long-lived conversations can survive only in the system cache
        // or a launcher pin, independently of this publisher's process lifetime.
        val ids = shortcuts.getShortcuts(ShortcutManager.FLAG_MATCH_DYNAMIC or ShortcutManager.FLAG_MATCH_CACHED or ShortcutManager.FLAG_MATCH_PINNED)
            .filter { it.extras?.getString("reply-account") == replyAccountGroup(profileId, accountId) }.map { it.id }.distinct()
        if (ids.isNotEmpty()) {
            // User-owned launcher pins cannot be deleted by the app; disable them.
            shortcuts.disableShortcuts(ids)
            shortcuts.removeDynamicShortcuts(ids)
            shortcuts.removeLongLivedShortcuts(ids)
        }
        ids.forEach(publishedShortcuts::remove)
    }

    private fun notificationTag(target: ReplyNotificationTarget) =
        replyDigest("${target.profileId}\u0000${target.accountId}\u0000${target.conversationId}")

    private fun userSelectedBubble(manager: NotificationManager, shortcutId: String, tag: String): Boolean {
        when (manager.bubblePreference) {
            NotificationManager.BUBBLE_PREFERENCE_NONE -> return false
            NotificationManager.BUBBLE_PREFERENCE_ALL -> return true
        }
        val channel = manager.getNotificationChannel(REPLY_CHANNEL_ID, shortcutId)
        if (channel?.conversationId == shortcutId && channel.canBubble()) return true
        // FLAG_BUBBLE is assigned by the system after applying the user's choice.
        return manager.activeNotifications.any { it.tag == tag && it.notification.flags and Notification.FLAG_BUBBLE != 0 }
    }
}

/**
 * A completed assistant message can be progress, not the final task reply (§7).
 * Wait for both an assistant body and that generation's successful user-message
 * status. Store only routing metadata, never reply text. Both pending state and
 * consumed generations survive cursor replay and process recreation.
 */
internal class ReplyCompletionObserver(
    private val preferences: SharedPreferences,
    private val profileId: String,
    private val accountId: String,
    private val post: (ReplyNotificationTarget, String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val firstEnabledAt = preferences.getLong("first-enabled-at", 0L).takeIf { it > 0L }
        ?: now().also { check(preferences.edit().putLong("first-enabled-at", it).commit()) }
    private var nextPruneAt = Long.MIN_VALUE

    @Synchronized fun accept(event: GatewayEvent) {
        if (event.event !in REPLY_EVENTS) return
        val body = runCatching { JsonFields.obj(Json.parse(event.data)) }.getOrNull() ?: return
        if (JsonFields.string(body, "accountId")?.let { it != accountId } == true) return
        val occurredAt = JsonFields.string(body, "occurredAt")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return
        // Initial login / an expired cursor must not announce old task history.
        if (occurredAt < firstEnabledAt - CLOCK_SKEW_MILLIS || occurredAt < now() - RETENTION_MILLIS) return
        pruneExpired()
        val payload = JsonFields.obj(JsonFields.field(body, "payload")) ?: return
        val conversationId = JsonFields.string(payload, "conversationId")?.takeIf { it.isNotBlank() } ?: return
        val generationId = JsonFields.string(payload, "generationId")?.takeIf { it.isNotBlank() }
        val decoded = runCatching { GatewayEventDecoder.decode(event) }.getOrNull() ?: return
        if (generationId == null) {
            acceptLegacy(event, decoded, payload, conversationId, occurredAt)
            return
        }
        // A generation-bearing event must never borrow a legacy round's body.
        clearLegacy(conversationId, occurredAt)
        val key = replyDigest("$conversationId\u0000$generationId")
        if (preferences.contains("done.$key")) return
        when (event.event) {
            "conversation.message.completed" -> {
                if (!isPublicAssistantPayload(payload) || !hasAssistantReply(decoded, JsonFields.string(payload, "sender"))) return
                check(preferences.edit().putLong("reply.$key", occurredAt).commit())
            }
            "conversation.message.status" -> {
                val status = decoded as? VerifiedConversationEvent.MessageStatus ?: return
                when (status.status) {
                    AgentMessageStatus.COMPLETED -> check(preferences.edit().putLong("success.$key", occurredAt).commit())
                    AgentMessageStatus.FAILED -> { finish(key, occurredAt); return }
                    else -> return
                }
            }
            "conversation.generation.cancelled" -> { finish(key, occurredAt); return }
        }
        if (preferences.contains("reply.$key") && preferences.contains("success.$key")) {
            // Persist before posting: a replay must not revive a dismissed alert.
            // A denied permission consumes this completion instead of queuing old alerts.
            finish(key, occurredAt)
            post(ReplyNotificationTarget(profileId, accountId, conversationId), generationId)
        }
    }

    /** Older gateways omit generationId. Only an observed queued/delivered user
     * message can begin such a round; the ordered stream and single active
     * generation per conversation then give its assistant body a safe owner. */
    private fun acceptLegacy(
        event: GatewayEvent,
        decoded: VerifiedConversationEvent,
        payload: com.openandroidintelligence.gateway.schema.JsonValue.JObject,
        conversationId: String,
        occurredAt: Long,
    ) {
        val conversationKey = replyDigest(conversationId)
        val activeKey = "legacy.current.$conversationKey"
        when (event.event) {
            "conversation.message.status" -> {
                val status = decoded as? VerifiedConversationEvent.MessageStatus ?: return
                val round = replyDigest("legacy\u0000$conversationId\u0000${status.messageId}\u0000${status.clientMessageId.value}")
                if (preferences.contains("done.$round")) return
                when (status.status) {
                    AgentMessageStatus.QUEUED, AgentMessageStatus.DELIVERED -> {
                        if (preferences.getString(activeKey, null) != round) {
                            clearLegacy(conversationId, occurredAt)
                            check(preferences.edit().putString(activeKey, round).putLong("legacy.started.$conversationKey", occurredAt).commit())
                        }
                    }
                    AgentMessageStatus.COMPLETED, AgentMessageStatus.FAILED -> {
                        val active = preferences.getString(activeKey, null)
                        val hasReply = active == round && preferences.contains("reply.$round")
                        finish(round, occurredAt)
                        if (active == round) removeLegacy(conversationKey)
                        if (status.status == AgentMessageStatus.COMPLETED && hasReply) {
                            post(ReplyNotificationTarget(profileId, accountId, conversationId), "legacy:$round")
                        }
                    }
                }
            }
            "conversation.message.completed" -> {
                if (!isPublicAssistantPayload(payload) || !hasAssistantReply(decoded, JsonFields.string(payload, "sender"))) return
                val round = preferences.getString(activeKey, null) ?: return
                check(preferences.edit().putLong("reply.$round", occurredAt).commit())
            }
            "conversation.generation.cancelled" -> clearLegacy(conversationId, occurredAt)
        }
    }

    private fun hasAssistantReply(decoded: VerifiedConversationEvent, sender: String?): Boolean {
        val message = (decoded as? VerifiedConversationEvent.TimelineUpsert)?.message ?: return false
        return sender == "assistant" && message.id.isNotBlank() && message.parts.any {
            it is MessagePart.Attachment || it is MessagePart.Text && it.value.isNotBlank()
        }
    }

    private fun clearLegacy(conversationId: String, occurredAt: Long) {
        val conversationKey = replyDigest(conversationId)
        val round = preferences.getString("legacy.current.$conversationKey", null) ?: return
        finish(round, occurredAt)
        removeLegacy(conversationKey)
    }

    private fun removeLegacy(conversationKey: String) {
        check(preferences.edit().remove("legacy.current.$conversationKey").remove("legacy.started.$conversationKey").commit())
    }

    private fun finish(key: String, occurredAt: Long) {
        val edit = preferences.edit().putLong("done.$key", occurredAt).remove("reply.$key").remove("success.$key")
        check(edit.commit())
    }

    private fun pruneExpired() {
        val currentTime = now()
        if (currentTime < nextPruneAt) return
        nextPruneAt = currentTime + PRUNE_INTERVAL_MILLIS
        val expired = preferences.all.filter { (name, value) ->
            !name.startsWith("realtime.") && name != "first-enabled-at" && value is Long && value < currentTime - RETENTION_MILLIS
        }.keys
        if (expired.isNotEmpty()) {
            val edit = preferences.edit()
            expired.forEach { key ->
                edit.remove(key)
                if (key.startsWith("legacy.started.")) edit.remove("legacy.current." + key.removePrefix("legacy.started."))
            }
            check(edit.commit())
        }
    }

    companion object {
        private const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val CLOCK_SKEW_MILLIS = 5L * 60 * 1000
        private const val PRUNE_INTERVAL_MILLIS = 30L * 60 * 1000
        private val REPLY_EVENTS = setOf("conversation.message.completed", "conversation.message.status", "conversation.generation.cancelled")
    }
}

internal fun replyDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun replyCompletionPreferences(context: Context, profileId: String, accountId: String): SharedPreferences =
    context.getSharedPreferences("agent-reply-completions-" + replyDigest("$profileId\u0000$accountId"), Context.MODE_PRIVATE)

internal fun replyAccountGroup(profileId: String, accountId: String) = "agent-replies." + replyDigest("$profileId\u0000$accountId")

internal fun isPublicAssistantPayload(payload: JsonValue.JObject): Boolean =
    JsonFields.string(payload, "sender") == "assistant" &&
        JsonFields.string(payload, "channel") in setOf(null, "final", "commentary") &&
        JsonFields.string(payload, "visibility") in setOf(null, "public") &&
        JsonFields.bool(payload, "isPrivate") != true
