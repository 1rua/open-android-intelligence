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
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import com.openandroidintelligence.ui.resources.R as UiR

/** App reply alerts are independent of notification collection and pairing grants. */
class ReplyNotificationSettings(
    private val context: Context,
    private val preferences: SharedPreferences = context.getSharedPreferences("agent-reply-alert-settings", Context.MODE_PRIVATE),
) {
    private val _enabled = MutableStateFlow(preferences.getBoolean("enabled", true))
    val enabled = _enabled.asStateFlow()
    private val _allowed = MutableStateFlow(isAllowed())
    val allowed = _allowed.asStateFlow()
    private val _liveUpdatesAllowed = MutableStateFlow(canPostLiveUpdates())
    val liveUpdatesAllowed = _liveUpdatesAllowed.asStateFlow()
    val liveUpdatesSupported: Boolean get() = Build.VERSION.SDK_INT >= 36
    val postNotificationsPermissionRequired: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val permissionRequested: Boolean get() = preferences.getBoolean("permission-requested", false)

    fun shouldRequestPermission(): Boolean = postNotificationsPermissionRequired && !permissionRequested &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    /** Publish a preference only once the existing durable owner accepted it. */
    fun setEnabled(enabled: Boolean): Boolean {
        if (!preferences.edit().putBoolean("enabled", enabled).commitOrLog(TAG)) return false
        _enabled.value = enabled
        if (!enabled) context.getSystemService(NotificationManager::class.java).let { manager ->
            manager.activeNotifications.filter { it.notification.channelId in setOf(REPLY_CHANNEL_ID, LIVE_UPDATE_CHANNEL_ID) }.forEach { manager.cancel(it.tag, it.id) }
        }
        return true
    }

    fun markPermissionRequested() {
        preferences.edit().putBoolean("permission-requested", true).commitOrLog(TAG)
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
        val runtimePermissionGranted = !postNotificationsPermissionRequired ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return runtimePermissionGranted &&
            manager.areNotificationsEnabled() && (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
    }
}

internal const val REPLY_CHANNEL_ID = "agent-task-replies"
private const val REPLY_ACTION = "com.openandroidintelligence.mobile.OPEN_AGENT_REPLY"
private const val TAG = "ReplyNotifications"

/**
 * 一条回复提醒指向的账号、Agent 与会话。
 *
 * [agentId] 只有原生 `/agents` 会话才有：同一个账号下不同 Agent 的会话 ID 各自
 * 独立，不带上 Agent 就会把两个 Agent 的同名会话当成同一个通知身份。平台旧帧
 * 没有 Agent 概念，保持为 null，路由和摘要算法都与历史版本一致。
 */
internal data class ReplyNotificationTarget(
    val profileId: String,
    val accountId: String,
    val conversationId: String,
    val agentId: String? = null,
) {
    fun bubbleIntent(context: Context): Intent = BubbleConversationActivity.intent(context, this)
    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(REPLY_ACTION)
        .setData(Uri.Builder().scheme("oai-reply").authority("conversation")
            .appendPath(profileId).appendPath(accountId).appendPath(conversationId)
            .also { builder -> agentId?.let(builder::appendPath) }.build())
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /**
     * 会话在账号内的唯一身份。
     *
     * 平台旧链路没有 Agent 维度，保持纯会话 ID，历史围栏的摘要才不变；原生会话
     * 加上 Agent，避免同一个会话 ID 在另一个 Agent 下命中别人的去重记录。
     */
    internal fun identityKey(): String = if (agentId == null) conversationId else "$conversationId\u0000$agentId"

    companion object {
        fun from(intent: Intent): ReplyNotificationTarget? {
            val uri = intent.data ?: return null
            if (intent.action != REPLY_ACTION || uri.scheme != "oai-reply" || uri.host != "conversation") return null
            val parts = uri.pathSegments
            // 3 段是平台旧链路，4 段是原生 Agent 会话；其它形状一律不认。
            if (parts.size !in 3..4 || parts.any { it.isBlank() }) return null
            return ReplyNotificationTarget(parts[0], parts[1], parts[2], parts.getOrNull(3))
        }
    }
}

internal class AndroidReplyNotificationPublisher(
    private val context: Context,
    private val settings: ReplyNotificationSettings,
    /**
     * 会话是否正被系统助理界面展示。
     *
     * 助理界面已经把这轮回复放在用户眼前时，再弹通知或气泡只是重复打扰；
     * 这里只抑制通知，不改变任何订阅或投递状态——界面离开后下一帧会重新发布。
     */
    private val suppress: (ReplyNotificationTarget) -> Boolean = { false },
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
        manager.createNotificationChannel(NotificationChannel(REPLY_CHANNEL_ID, context.getString(UiR.string.notif_channel_reply_name), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = context.getString(UiR.string.notif_channel_reply_desc); setAllowBubbles(true) })
        // 助理界面已展示这条会话：撤回已发布的通知，也不再以气泡重复打扰用户。
        if (suppress(session.target)) {
            cancel(session.target)
            return
        }
        val isTerminal = !session.isRunning
        val activeForLiveUpdate = session.status == NativeConversationStatus.QUEUED || session.status == NativeConversationStatus.RUNNING
        if (!activeForLiveUpdate) {
            // 终态或断连时先彻底退出常驻 Live Update 进度通知，再决定是否发布普通回复通知。
            liveUpdates.cancel(session.target)
        }
        val hasLiveUpdate = if (activeForLiveUpdate) liveUpdates.update(session) else false
        if (!settings.enabled.value || !settings.isAllowed()) return
        val target = session.target
        val tag = notificationTag(target)
        if (!alert && session.status == NativeConversationStatus.COMPLETED && session.latestReply.isBlank()) {
            // 终态早于正文到达时先撤下 Live Update 且不闪现空占位通知，等待正文补齐后统一提醒。
            manager.cancel(tag, 0)
            return
        }
        // 底层保留空标题，系统快捷方式和通知展示必须使用非空的本地化名称。
        val title = session.title.ifBlank { context.getString(UiR.string.notif_conv_default_title) }
        val shortcutId = "agent-conversation-$tag"
        val icon = Icon.createWithResource(context, R.drawable.ic_agent_bubble)
        val participantKey = replyDigest("${target.profileId}\u0000${target.accountId}")
        val localUser = Person.Builder().setName(context.getString(UiR.string.notif_person_local_user)).setKey("user-$participantKey").setBot(false).build()
        val agent = Person.Builder().setName("Agent").setKey("agent-$participantKey")
            .setBot(true).setIcon(icon).build()
        val bubbleIntent = target.bubbleIntent(context)
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        val accountGroup = replyAccountGroup(target.profileId, target.accountId)
        val shortcut = ShortcutInfo.Builder(context, shortcutId).setShortLabel(title)
            .setActivity(android.content.ComponentName(context, MainActivity::class.java))
            .setCategories(setOf("android.shortcut.conversation"))
            .setLongLived(true).setPersons(arrayOf(localUser, agent)).setIcon(icon)
            .setExtras(android.os.PersistableBundle().apply { putString("reply-account", accountGroup) })
            .setLocusId(LocusId(shortcutId)).setIntent(bubbleIntent).build()
        // A device may disable shortcuts/bubbles. The notification still works.
        val keepBubble = hasLiveUpdate && userSelectedBubble(manager, shortcutId, tag)
        val bubbleMetadata = try {
            if (publishedShortcuts[shortcutId] != title) {
                shortcutManager.enableShortcuts(listOf(shortcutId))
                shortcutManager.pushDynamicShortcut(shortcut)
                publishedShortcuts[shortcutId] = title
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
            NativeConversationStatus.COMPLETED -> context.getString(UiR.string.notif_reply_heading_replied_running)
            NativeConversationStatus.FAILED -> context.getString(UiR.string.notif_reply_heading_partial_failed)
            NativeConversationStatus.CANCELLED -> context.getString(UiR.string.notif_reply_heading_partial_cancelled)
            else -> context.getString(UiR.string.notif_reply_heading_processing)
        } else when (session.status) {
            NativeConversationStatus.QUEUED -> context.getString(UiR.string.notif_reply_heading_queued)
            NativeConversationStatus.RUNNING -> context.getString(UiR.string.notif_reply_heading_running)
            NativeConversationStatus.COMPLETED -> context.getString(UiR.string.notif_reply_heading_completed)
            NativeConversationStatus.FAILED -> context.getString(UiR.string.notif_reply_heading_failed)
            NativeConversationStatus.CANCELLED -> context.getString(UiR.string.notif_reply_heading_cancelled)
            NativeConversationStatus.ENDED -> context.getString(UiR.string.notif_reply_heading_ended)
            NativeConversationStatus.DISCONNECTED -> context.getString(UiR.string.notif_reply_heading_disconnected)
        }
        val preview = notificationReplyExcerpt(session.latestReply, session.isRunning, context.getString(UiR.string.notif_reply_excerpt_full_hint)).ifBlank { when (session.status) {
            NativeConversationStatus.QUEUED -> context.getString(UiR.string.notif_reply_preview_waiting_start)
            NativeConversationStatus.RUNNING -> context.getString(UiR.string.notif_reply_preview_waiting_reply)
            NativeConversationStatus.COMPLETED -> context.getString(UiR.string.notif_reply_preview_received)
            NativeConversationStatus.FAILED -> context.getString(UiR.string.notif_reply_preview_error)
            NativeConversationStatus.CANCELLED -> context.getString(UiR.string.notif_reply_preview_cancelled)
            NativeConversationStatus.ENDED -> context.getString(UiR.string.notif_reply_preview_ended)
            NativeConversationStatus.DISCONNECTED -> context.getString(UiR.string.notif_reply_preview_reconnecting)
        } }
        val style = Notification.MessagingStyle(localUser)
            .setConversationTitle(title).addMessage(preview, session.updatedAt, agent)
        val contentTitle = if (isTerminal) title else heading
        val notification = Notification.Builder(context, REPLY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent_reply)
            .setContentTitle(contentTitle)
            .setContentText(preview)
            .setSubText(heading)
            .setStyle(style)
            .setShortcutId(shortcutId)
            .setLocusId(LocusId(shortcutId))
            .setBubbleMetadata(bubbleMetadata)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .addExtras(android.os.Bundle().apply { putString("reply-account", accountGroup) })
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(activeForLiveUpdate)
            .setAutoCancel(!activeForLiveUpdate)
            .setOnlyAlertOnce(hasLiveUpdate || !alert)
            .apply {
                if (hasLiveUpdate || !alert) {
                    setGroup("silent-$tag")
                    setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                }
            }
            .setContentIntent(pendingIntent)
            .addAction(Notification.Action.Builder(null, context.getString(UiR.string.notif_action_open_conversation),
                PendingIntent.getActivity(context, 0, bubbleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
            .build()
        // Permission may be revoked between checking it and the Binder call.
        try { manager.notify(tag, 0, notification) } catch (_: SecurityException) { settings.refreshPermission() }
    }

    fun eraseAccount(profileId: String, accountId: String) {
        replyCompletionPreferences(context, profileId, accountId).edit().clear().commitOrLog(TAG)
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
        replyDigest(listOfNotNull(target.profileId, target.accountId, target.conversationId, target.agentId).joinToString("\u0000"))

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
    internal val profileId: String,
    internal val accountId: String,
    private val post: (ReplyNotificationTarget, String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val firstEnabledAt = preferences.getLong("first-enabled-at", 0L).takeIf { it > 0L }
        ?: now().also { preferences.edit().putLong("first-enabled-at", it).commitOrLog(TAG) }
    private var nextPruneAt = Long.MIN_VALUE

    @Synchronized fun accept(input: NativeNotificationInput) {
        if (input.target.profileId != profileId || input.target.accountId != accountId) return
        if (input.kind !in REPLY_EVENTS) return
        val occurredAt = input.occurredAt
        // Initial login / an expired cursor must not announce old task history.
        if (occurredAt < firstEnabledAt - CLOCK_SKEW_MILLIS || occurredAt < now() - RETENTION_MILLIS) return
        pruneExpired()
        val conversationId = input.target.conversationId
        val identity = input.target.identityKey()
        val generationId = input.generationId
        val decoded = input.event
        if (generationId == null) {
            acceptLegacy(input, conversationId, identity, occurredAt)
            return
        }
        // A generation-bearing event must never borrow a legacy round's body.
        clearLegacy(conversationId, identity, occurredAt)
        val key = replyDigest("$identity\u0000$generationId")
        if (preferences.contains("done.$key")) return
        when (input.kind) {
            NativeNotificationKind.REPLY_COMPLETED -> {
                if (!hasAssistantReply(decoded)) return
                preferences.edit().putLong("reply.$key", occurredAt).commitOrLog(TAG)
            }
            NativeNotificationKind.MESSAGE_STATUS -> {
                val status = decoded as? VerifiedConversationEvent.MessageStatus ?: return
                when (status.status) {
                    AgentMessageStatus.COMPLETED -> preferences.edit().putLong("success.$key", occurredAt).commitOrLog(TAG)
                    AgentMessageStatus.FAILED -> { finish(key, occurredAt); return }
                    else -> return
                }
            }
            NativeNotificationKind.GENERATION_CANCELLED -> { finish(key, occurredAt); return }
            else -> return
        }
        if (preferences.contains("reply.$key") && preferences.contains("success.$key")) {
            // Persist before posting: a replay must not revive a dismissed alert.
            // A denied permission consumes this completion instead of queuing old alerts.
            finish(key, occurredAt)
            post(input.target, generationId)
        }
    }

    /** Older gateways omit generationId. Only an observed queued/delivered user
     * message can begin such a round; the ordered stream and single active
     * generation per conversation then give its assistant body a safe owner. */
    private fun acceptLegacy(
        input: NativeNotificationInput,
        conversationId: String,
        identity: String,
        occurredAt: Long,
    ) {
        val decoded = input.event
        val conversationKey = replyDigest(identity)
        val activeKey = "legacy.current.$conversationKey"
        val awaitingKey = "legacy.awaiting.$conversationKey"
        when (input.kind) {
            NativeNotificationKind.MESSAGE_STATUS -> {
                val status = decoded as? VerifiedConversationEvent.MessageStatus ?: return
                val digests = legacyCompatibleStatusDigests(identity, status)
                val round = digests.first()
                val upgradedLegacyHit = digests.drop(1).any { preferences.contains("done.$it") }
                if (upgradedLegacyHit && !preferences.contains("done.$round")) {
                    preferences.edit().putLong("done.$round", occurredAt).commitOrLog(TAG)
                }
                if (preferences.contains("done.$round") || upgradedLegacyHit) {
                    // 回放的排队/投递帧不能让先前已完成但未见正文的旧轮次继续挂起接管回放正文。
                    if (status.status in setOf(AgentMessageStatus.QUEUED, AgentMessageStatus.DELIVERED)) {
                        clearAwaitingLegacy(conversationKey, occurredAt)
                    }
                    return
                }
                when (status.status) {
                    AgentMessageStatus.QUEUED, AgentMessageStatus.DELIVERED -> {
                        // 若同轮次因终态先到而处于等待正文槽位，重复到达的旧排队/投递帧必须忽略，保留等待槽位。
                        if (preferences.getString(awaitingKey, null) == round) return
                        if (preferences.getString(activeKey, null) != round) {
                            clearLegacy(conversationId, identity, occurredAt)
                            preferences.edit().putString(activeKey, round).putLong("legacy.started.$conversationKey", occurredAt).commitOrLog(TAG)
                        }
                    }
                    AgentMessageStatus.COMPLETED -> {
                        val active = preferences.getString(activeKey, null)
                        val hasReply = active == round && preferences.contains("reply.$round")
                        if (hasReply) {
                            finish(round, occurredAt)
                            removeLegacy(conversationKey)
                            post(input.target, "legacy:$round")
                        } else if (active == round) {
                            // 终态先于正文到达时转入等待正文槽位，保留同轮次正文匹配能力，同时脱离活跃队列。
                            preferences.edit()
                                .remove(activeKey)
                                .putString(awaitingKey, round)
                                .putLong("success.$round", occurredAt)
                                .commitOrLog(TAG)
                        } else {
                            finish(round, occurredAt)
                        }
                    }
                    AgentMessageStatus.FAILED -> {
                        val active = preferences.getString(activeKey, null)
                        val awaiting = preferences.getString(awaitingKey, null)
                        finish(round, occurredAt)
                        if (active == round || awaiting == round) removeLegacy(conversationKey)
                    }
                }
            }
            NativeNotificationKind.REPLY_COMPLETED -> {
                if (!hasAssistantReply(decoded)) return
                val round = preferences.getString(activeKey, null) ?: preferences.getString(awaitingKey, null) ?: return
                if (preferences.contains("done.$round")) return
                preferences.edit().putLong("reply.$round", occurredAt).commitOrLog(TAG)
                if (preferences.contains("success.$round")) {
                    finish(round, occurredAt)
                    removeLegacy(conversationKey)
                    post(input.target, "legacy:$round")
                }
            }
            NativeNotificationKind.GENERATION_CANCELLED -> clearLegacy(conversationId, identity, occurredAt)
            else -> Unit
        }
    }

    private fun hasAssistantReply(decoded: VerifiedConversationEvent): Boolean {
        val message = (decoded as? VerifiedConversationEvent.TimelineUpsert)?.message ?: return false
        return message.sender == "assistant" && message.id.isNotBlank() && message.parts.any {
            it is MessagePart.Attachment || it is MessagePart.Text && it.value.isNotBlank()
        }
    }

    private fun clearLegacy(conversationId: String, identity: String, occurredAt: Long) {
        val conversationKey = replyDigest(identity)
        preferences.getString("legacy.current.$conversationKey", null)?.let { finish(it, occurredAt) }
        preferences.getString("legacy.awaiting.$conversationKey", null)?.let { finish(it, occurredAt) }
        removeLegacy(conversationKey)
    }

    private fun clearAwaitingLegacy(conversationKey: String, occurredAt: Long) {
        val awaiting = preferences.getString("legacy.awaiting.$conversationKey", null) ?: return
        finish(awaiting, occurredAt)
        preferences.edit().remove("legacy.awaiting.$conversationKey").commitOrLog(TAG)
    }

    private fun removeLegacy(conversationKey: String) {
        preferences.edit()
            .remove("legacy.current.$conversationKey")
            .remove("legacy.awaiting.$conversationKey")
            .remove("legacy.started.$conversationKey")
            .commitOrLog(TAG)
    }

    private fun finish(key: String, occurredAt: Long) {
        val edit = preferences.edit().putLong("done.$key", occurredAt).remove("reply.$key").remove("success.$key")
        edit.commitOrLog(TAG)
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
                if (key.startsWith("legacy.started.")) {
                    val suffix = key.removePrefix("legacy.started.")
                    edit.remove("legacy.current.$suffix")
                    edit.remove("legacy.awaiting.$suffix")
                }
            }
            edit.commitOrLog(TAG)
        }
    }

    companion object {
        private const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val CLOCK_SKEW_MILLIS = 5L * 60 * 1000
        private const val PRUNE_INTERVAL_MILLIS = 30L * 60 * 1000
        private val REPLY_EVENTS = setOf(NativeNotificationKind.REPLY_COMPLETED, NativeNotificationKind.MESSAGE_STATUS, NativeNotificationKind.GENERATION_CANCELLED)
    }
}

/**
 * 通知链路上的偏好写入不能把进程带走：commit 失败只意味着这次围栏没有落盘，
 * 下一次事件仍然可以重新判定，因此只记录日志并返回失败。
 */
internal fun SharedPreferences.Editor.commitOrLog(tag: String): Boolean {
    val committed = runCatching { commit() }.getOrElse { failure ->
        android.util.Log.w(tag, "SharedPreferences commit threw", failure)
        false
    }
    if (!committed) android.util.Log.w(tag, "SharedPreferences commit returned false")
    return committed
}

internal fun legacyStatusDigest(identity: String, status: VerifiedConversationEvent.MessageStatus): String {
    val stableUserKey = status.clientMessageId.value.takeIf { it.isNotBlank() } ?: status.messageId
    return replyDigest("legacy\u0000$identity\u0000$stableUserKey")
}

/** 兼容升级前基于 messageId+clientMessageId 生成的旧版 legacy 完成围栏摘要，避免重放重复提醒。 */
internal fun legacyCompatibleStatusDigests(
    identity: String,
    status: VerifiedConversationEvent.MessageStatus,
): List<String> {
    val current = legacyStatusDigest(identity, status)
    val previous = replyDigest("legacy\u0000$identity\u0000${status.messageId}\u0000${status.clientMessageId.value}")
    return if (previous == current) listOf(current) else listOf(current, previous)
}

internal fun replyDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun replyCompletionPreferences(context: Context, profileId: String, accountId: String): SharedPreferences =
    context.getSharedPreferences("agent-reply-completions-" + replyDigest("$profileId\u0000$accountId"), Context.MODE_PRIVATE)

internal fun replyAccountGroup(profileId: String, accountId: String) = "agent-replies." + replyDigest("$profileId\u0000$accountId")
