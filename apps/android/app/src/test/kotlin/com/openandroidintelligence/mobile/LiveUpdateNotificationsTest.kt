package com.openandroidintelligence.mobile

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ShortcutManager
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.robolectric.shadows.ShadowNotificationManager

/** Exercise the actual API 36 framework rather than faking SDK_INT on an old jar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [LiveUpdateNotificationManagerShadow::class])
class LiveUpdateNotificationsTest {
    private lateinit var context: Application
    private lateinit var settings: ReplyNotificationSettings
    private lateinit var publisher: AndroidReplyNotificationPublisher
    private lateinit var manager: NotificationManager
    private lateinit var managerShadow: LiveUpdateNotificationManagerShadow
    private val target = ReplyNotificationTarget("profile", "account", "conversation")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("agent-reply-alert-settings", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager = context.getSystemService(NotificationManager::class.java)
        managerShadow = Shadow.extract(manager)
        settings = ReplyNotificationSettings(context)
        publisher = AndroidReplyNotificationPublisher(context, settings)
    }

    @Test fun runningTaskUsesOneEligibleNativeLiveUpdateWhileKeepingConversationShortcutAndAction() {
        assertEquals(36, Build.VERSION.SDK_INT)
        publisher.update(session(NativeConversationStatus.RUNNING, "Latest public reply"), false)
        val live = manager.activeNotifications.single().notification
        assertEquals(LIVE_UPDATE_CHANNEL_ID, live.channelId)
        val shortcut = context.getSystemService(ShortcutManager::class.java).dynamicShortcuts.single()
        assertEquals(target, BubbleConversationActivity.targetFromIntent(shortcut.intent!!))
        val participants = org.robolectric.util.ReflectionHelpers.callInstanceMethod<Array<android.app.Person>>(shortcut, "getPersons")
        assertTrue("API 36 shortcuts must include the real user", participants.any { !it.isBot })
        assertTrue(participants.any { it.isBot })
        assertEquals(target, BubbleConversationActivity.targetFromIntent(shadowOf(live.actions.single().actionIntent).savedIntent))
        val progress = Notification.Builder.recoverBuilder(context, live).style as Notification.ProgressStyle
        assertTrue(progress.isProgressIndeterminate)
        assertTrue(progress.progressSegments.isEmpty())
        assertTrue(progress.progressPoints.isEmpty())
        assertTrue("Use the real framework eligibility predicate", live.hasPromotableCharacteristics())
        assertTrue(live.extras.getBoolean(REQUEST_PROMOTED_ONGOING))
        assertEquals(context.getString(com.openandroidintelligence.ui.resources.R.string.notif_status_running), live.shortCriticalText)
        assertEquals("Latest public reply", live.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(live.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(live.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals("Only the system may assign actual promotion", 0, live.flags and Notification.FLAG_PROMOTED_ONGOING)
        assertNull(live.bubbleMetadata)
        assertNull(live.shortcutId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(LIVE_UPDATE_CHANNEL_ID).importance)
        assertNull(manager.getNotificationChannel(LIVE_UPDATE_CHANNEL_ID).sound)

        publisher.update(session(NativeConversationStatus.RUNNING, "New snapshot"), false)
        assertEquals(1, manager.activeNotifications.size)
        assertEquals("New snapshot", manager.activeNotifications.single { it.notification.channelId == LIVE_UPDATE_CHANNEL_ID }.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test fun aUserSelectedBubbleKeepsOnlyASilentShadeSuppressedConversationCompanion() {
        managerShadow.bubblePreferenceSetting = NotificationManager.BUBBLE_PREFERENCE_ALL
        publisher.update(session(NativeConversationStatus.RUNNING, "Public preview"), true)
        assertEquals(2, manager.activeNotifications.size)
        val conversation = manager.activeNotifications.single { it.notification.channelId == REPLY_CHANNEL_ID }.notification
        assertTrue(Notification.Builder.recoverBuilder(context, conversation).style is Notification.MessagingStyle)
        assertTrue(conversation.bubbleMetadata!!.isNotificationSuppressed)
        assertTrue(conversation.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        managerShadow.bubblePreferenceSetting = NotificationManager.BUBBLE_PREFERENCE_NONE
        publisher.update(session(NativeConversationStatus.RUNNING, "Next preview"), false)
        assertEquals(LIVE_UPDATE_CHANNEL_ID, manager.activeNotifications.single().notification.channelId)
    }

    @Test fun longStreamedRepliesAdvanceTheirVisibleTailAndTheTerminalCardKeepsTheEnding() {
        val prefix = "😀".repeat(6_000)
        val first = session(NativeConversationStatus.RUNNING, prefix + "First visible tail")
        publisher.update(first, false)
        val firstText = manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        publisher.update(first.copy(latestReply = prefix + "Second visible tail"), false)
        val secondText = manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertNotEquals(firstText, secondText)
        assertTrue(secondText.endsWith("Second visible tail"))
        assertEquals(RUNNING_REPLY_EXCERPT_CODE_POINTS, secondText.codePointCount(0, secondText.length))
        val completed = first.copy(status = NativeConversationStatus.COMPLETED, latestReply = "Answer opening. " + prefix + "Final answer ending.")
        publisher.update(completed, true)
        val notification = manager.activeNotifications.single().notification
        val style = Notification.Builder.recoverBuilder(context, notification).style as Notification.MessagingStyle
        val finalText = style.messages.single().text.toString()
        assertTrue(finalText.startsWith("Answer opening. "))
        val hint = context.getString(com.openandroidintelligence.ui.resources.R.string.notif_reply_excerpt_full_hint)
        assertTrue("Final excerpt tail: ${finalText.takeLast(120)}", finalText.contains("Final answer ending.\n\n$hint"))
        assertTrue(finalText.length < 1_024)
        assertTrue("The full snapshot remains available to the app", completed.latestReply.length > 5_120)
    }

    @Test fun terminalAndUnknownRemoteStateRemoveLiveCardWhileKeepingConversation() {
        listOf(NativeConversationStatus.COMPLETED, NativeConversationStatus.FAILED, NativeConversationStatus.CANCELLED, NativeConversationStatus.ENDED, NativeConversationStatus.DISCONNECTED).forEach { outcome ->
            publisher.update(session(NativeConversationStatus.RUNNING, "Working"), false)
            assertTrue(manager.activeNotifications.any { it.notification.channelId == LIVE_UPDATE_CHANNEL_ID })
            publisher.update(session(outcome, "Latest reply"), outcome == NativeConversationStatus.COMPLETED)
            assertTrue(manager.activeNotifications.none { it.notification.channelId == LIVE_UPDATE_CHANNEL_ID })
            val remaining = manager.activeNotifications.single().notification
            assertEquals(REPLY_CHANNEL_ID, remaining.channelId)
            assertTrue(Notification.Builder.recoverBuilder(context, remaining).style is Notification.MessagingStyle)
        }
    }

    @Test fun cancelAndAccountErasureRemoveOnlyTheirOwnLiveAndConversationCards() {
        val other = target.copy(accountId = "other-account")
        publisher.update(session(NativeConversationStatus.RUNNING, "Own"), false)
        publisher.update(session(NativeConversationStatus.RUNNING, "Other").copy(target = other), false)
        assertEquals(2, manager.activeNotifications.size)
        publisher.cancel(target)
        assertEquals(1, manager.activeNotifications.size)
        assertTrue(manager.activeNotifications.any { it.tag == liveUpdateTag(other) })
        publisher.eraseAccount(other.profileId, other.accountId)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun preferenceAndNotificationOrPromotionRevocationCleanLiveState() {
        publisher.update(session(NativeConversationStatus.RUNNING), false)
        settings.setEnabled(false)
        assertTrue(manager.activeNotifications.isEmpty())
        publisher.update(session(NativeConversationStatus.RUNNING), false)
        assertTrue(manager.activeNotifications.isEmpty())
        settings.setEnabled(true)
        publisher.update(session(NativeConversationStatus.RUNNING), false)
        assertTrue(manager.activeNotifications.any { it.notification.channelId == LIVE_UPDATE_CHANNEL_ID })

        managerShadow.promotedAllowed = false
        settings.refreshPermission()
        assertFalse(settings.liveUpdatesAllowed.value)
        assertTrue(manager.activeNotifications.none { it.notification.channelId == LIVE_UPDATE_CHANNEL_ID })
        publisher.update(session(NativeConversationStatus.RUNNING), false)
        assertTrue(manager.activeNotifications.any { it.notification.channelId == REPLY_CHANNEL_ID })
        managerShadow.promotedAllowed = true
        settings.refreshPermission()
        publisher.update(session(NativeConversationStatus.RUNNING), false)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settings.refreshPermission()
        assertFalse(settings.liveUpdatesAllowed.value)
        assertTrue(manager.activeNotifications.none { it.notification.channelId == LIVE_UPDATE_CHANNEL_ID })
    }

    @Test fun deletingAConversationRemovesBothItsLiveUpdateAndShortcutRouting() {
        publisher.update(session(NativeConversationStatus.RUNNING), false)
        assertEquals(1, context.getSystemService(ShortcutManager::class.java).dynamicShortcuts.size)
        publisher.forget(target)
        assertTrue(manager.activeNotifications.isEmpty())
        assertTrue(context.getSystemService(ShortcutManager::class.java).dynamicShortcuts.isEmpty())
    }

    @Test fun minorSdkCompatibilityEncodesRealLegacyAndModernPromotionRequirements() {
        val legacy = LiveUpdateNotifications.Api36.configurePromotion(Notification.Builder(context, LIVE_UPDATE_CHANNEL_ID).setSmallIcon(R.drawable.ic_agent_reply), 0).build()
        assertTrue(legacy.extras.getBoolean(Notification.EXTRA_COLORIZED))
        assertTrue(legacy.extras.getBoolean(REQUEST_PROMOTED_ONGOING))
        val modern = LiveUpdateNotifications.Api36.configurePromotion(Notification.Builder(context, LIVE_UPDATE_CHANNEL_ID).setSmallIcon(R.drawable.ic_agent_reply), 1).build()
        assertFalse(modern.extras.getBoolean(Notification.EXTRA_COLORIZED))
        assertTrue(modern.extras.getBoolean(REQUEST_PROMOTED_ONGOING))
        val expectedMinor = Build.VERSION.SDK_INT_FULL % 100_000
        assertEquals(expectedMinor, Build.getMinorSdkVersion(Build.VERSION.SDK_INT_FULL))
    }

    @Test fun liveUpdateSettingsRouteUsesTheActualSdk36SystemActionAndPackage() {
        assertTrue(settings.liveUpdatesSupported)
        assertTrue(settings.canPostLiveUpdates())
        val intent = requireNotNull(settings.liveUpdatesSettingsIntent())
        assertEquals(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        managerShadow.promotedAllowed = false
        assertFalse(settings.canPostLiveUpdates())
    }

    @Test fun endToEndCompletionExitsLiveUpdateAndPostsSwipeableReplyNotificationWithoutStuckPlaceholder() =
        withWiredCoordinator(title = "架构联调会话") { coordinator, emit, _ ->
            emit("conversation.message.status", """{"conversationId":"conversation","messageId":"u1","clientMessageId":"c1","generationId":"gen-e2e-1","status":"delivered","revision":1}""", null)
            coordinator.requestReconciliation()
            val staleRequest = coordinator.reconciliationRequests().single()
            assertEquals(listOf(LIVE_UPDATE_CHANNEL_ID), manager.activeNotifications.map { it.notification.channelId })

            emit("conversation.message.delta", """{"conversationId":"conversation","messageId":"r1","generationId":"gen-e2e-1","sender":"assistant","parts":[{"type":"text","text":"正在组织最终结论..."}],"revision":1}""", null)
            assertEquals(listOf(LIVE_UPDATE_CHANNEL_ID), manager.activeNotifications.map { it.notification.channelId })

            emit("conversation.message.completed", """{"conversationId":"conversation","messageId":"r1","generationId":"gen-e2e-1","sender":"assistant","parts":[{"type":"text","text":"完整最终答复"}],"revision":2}""", null)
            emit("conversation.message.status", """{"conversationId":"conversation","messageId":"u1","clientMessageId":"c1","generationId":"gen-e2e-1","status":"completed","revision":3}""", null)

            assertTrue("终态后常驻 Live Update 必须彻底退出", manager.activeNotifications.none { it.notification.channelId == LIVE_UPDATE_CHANNEL_ID })
            val replyNotif = manager.activeNotifications.single { it.notification.channelId == REPLY_CHANNEL_ID }.notification
            assertEquals(0, replyNotif.flags and Notification.FLAG_ONGOING_EVENT)
            assertTrue(replyNotif.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertNotNull(replyNotif.contentIntent)
            val style = Notification.Builder.recoverBuilder(context, replyNotif).style as Notification.MessagingStyle
            assertEquals("完整最终答复", style.messages.single().text.toString())
            val placeholder = context.getString(com.openandroidintelligence.ui.resources.R.string.notif_live_update_preview_running)
            assertNotEquals(placeholder, style.messages.single().text.toString())

            // 终态后滞后 reconcile 或重连回放绝不复活 Live Update
            assertFalse(coordinator.reconcile(staleRequest, "gen-e2e-1" to "running", emptyList()))
            emit("conversation.message.delta", """{"conversationId":"conversation","messageId":"r1","generationId":"gen-e2e-1","sender":"assistant","parts":[{"type":"text","text":"重放旧碎片"}],"revision":1}""", null)
            assertTrue(manager.activeNotifications.none { it.notification.channelId == LIVE_UPDATE_CHANNEL_ID })
            assertEquals(listOf(REPLY_CHANNEL_ID), manager.activeNotifications.map { it.notification.channelId })
        }

    @Test fun endToEndMissingAndMismatchedGenerationIdsExitLiveUpdateWithoutPlaceholder() =
        withWiredCoordinator(title = "无ID兼容会话") { _, emit, advanceClock ->
            // 场景 1：无 generationId + messageId 在 status 前后变化
            emit("conversation.message.status", """{"conversationId":"conversation","messageId":"tmp-u1","clientMessageId":"cid-1","status":"delivered","revision":1}""", null)
            emit("conversation.message.delta", """{"conversationId":"conversation","messageId":"r1","sender":"assistant","parts":[{"type":"text","text":"草稿"}],"revision":1}""", null)
            emit("conversation.message.completed", """{"conversationId":"conversation","messageId":"r1","sender":"assistant","parts":[{"type":"text","text":"缺失ID完整回复"}],"revision":2}""", null)
            emit("conversation.message.status", """{"conversationId":"conversation","messageId":"srv-u1","clientMessageId":"cid-1","status":"completed","revision":3}""", null)

            assertTrue(manager.activeNotifications.none { it.notification.channelId == LIVE_UPDATE_CHANNEL_ID })
            val firstReply = manager.activeNotifications.single { it.notification.channelId == REPLY_CHANNEL_ID }.notification
            val firstStyle = Notification.Builder.recoverBuilder(context, firstReply).style as Notification.MessagingStyle
            assertEquals("缺失ID完整回复", firstStyle.messages.single().text.toString())

            // 场景 2：status(COMPLETED) 先到、completed 后到，且无正文时不闪现空占位普通通知
            advanceClock(100)
            emit("conversation.message.status", """{"conversationId":"conversation","messageId":"u2","clientMessageId":"cid-2","generationId":"req-2","status":"delivered","revision":10}""", null)
            assertEquals(listOf(LIVE_UPDATE_CHANNEL_ID), manager.activeNotifications.map { it.notification.channelId })
            emit("conversation.message.status", """{"conversationId":"conversation","messageId":"u2","clientMessageId":"cid-2","generationId":"turn-2","status":"completed","revision":11}""", null)
            assertTrue("终态早于正文时必须立刻撤下 Live Update 且不闪现空占位通知", manager.activeNotifications.isEmpty())

            emit("conversation.message.completed", """{"conversationId":"conversation","messageId":"r2","generationId":"turn-2","sender":"assistant","parts":[{"type":"text","text":"先终态后正文的跨ID回复"}],"revision":12}""", "req-2")
            assertTrue(manager.activeNotifications.none { it.notification.channelId == LIVE_UPDATE_CHANNEL_ID })
            val secondReply = manager.activeNotifications.single { it.notification.channelId == REPLY_CHANNEL_ID }.notification
            val secondStyle = Notification.Builder.recoverBuilder(context, secondReply).style as Notification.MessagingStyle
            assertEquals("先终态后正文的跨ID回复", secondStyle.messages.single().text.toString())

            // 场景 3：已有流式文本时 status(COMPLETED) 先到、completed 后到，中期回落通知必须静默，不触发双提醒
            advanceClock(100)
            emit("conversation.message.status", """{"conversationId":"conversation","messageId":"u3","clientMessageId":"cid-3","generationId":"req-3","status":"delivered","revision":20}""", null)
            emit("conversation.message.delta", """{"conversationId":"conversation","messageId":"r3","generationId":"req-3","sender":"assistant","parts":[{"type":"text","text":"半截流式内容"}],"revision":20}""", null)
            emit("conversation.message.status", """{"conversationId":"conversation","messageId":"u3","clientMessageId":"cid-3","generationId":"req-3","status":"completed","revision":21}""", null)
            val interim = manager.activeNotifications.single { it.notification.channelId == REPLY_CHANNEL_ID }.notification
            assertTrue("终态早于终稿时中期普通通知必须带静默标记", interim.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertEquals("终态早于终稿时首帖普通通知也需静音组行为，避免在正式终稿前先响一次", Notification.GROUP_ALERT_SUMMARY, interim.groupAlertBehavior)

            emit("conversation.message.completed", """{"conversationId":"conversation","messageId":"r3","generationId":"turn-3","sender":"assistant","parts":[{"type":"text","text":"跨ID最终答复正文"}],"revision":22}""", null)
            val thirdReply = manager.activeNotifications.single { it.notification.channelId == REPLY_CHANNEL_ID }.notification
            assertEquals("最终答复到达时只响一次正式提醒", 0, thirdReply.flags and Notification.FLAG_ONLY_ALERT_ONCE)
            assertNull("正式终稿发布时取消静默分组限制以允许正常响铃提醒", thirdReply.group)
            val thirdStyle = Notification.Builder.recoverBuilder(context, thirdReply).style as Notification.MessagingStyle
            assertEquals("跨ID最终答复正文", thirdStyle.messages.single().text.toString())
        }

    @Test fun endToEndConsecutiveTurnsRestartLiveUpdateAndExitCleanly() =
        withWiredCoordinator(title = "多轮追问会话") { coordinator, emit, advanceClock ->
            // 第一轮完成
            emit("conversation.message.status", """{"conversationId":"conversation","messageId":"u1","clientMessageId":"c1","generationId":"g1","status":"delivered","revision":1}""", null)
            emit("conversation.message.completed", """{"conversationId":"conversation","messageId":"r1","generationId":"g1","sender":"assistant","parts":[{"type":"text","text":"第一轮完成"}],"revision":2}""", null)
            emit("conversation.message.status", """{"conversationId":"conversation","messageId":"u1","clientMessageId":"c1","generationId":"g1","status":"completed","revision":3}""", null)
            assertEquals(listOf(REPLY_CHANNEL_ID), manager.activeNotifications.map { it.notification.channelId })

            // 第二轮重新拉起 Live Update，并互斥撤下上一轮普通回复通知
            advanceClock(100)
            emit("conversation.message.status", """{"conversationId":"conversation","messageId":"u2","clientMessageId":"c2","generationId":"g2","status":"queued","revision":10}""", null)
            assertEquals(listOf(LIVE_UPDATE_CHANNEL_ID), manager.activeNotifications.map { it.notification.channelId })

            // 断连态下同样退出 Live Update 且回落普通通知必须可滑动关闭（非 ongoing）
            coordinator.disconnect()
            assertTrue(manager.activeNotifications.none { it.notification.channelId == LIVE_UPDATE_CHANNEL_ID })
            val discNotif = manager.activeNotifications.single { it.notification.channelId == REPLY_CHANNEL_ID }.notification
            assertEquals("断连兜底通知不能锁死为不可划除常驻", 0, discNotif.flags and Notification.FLAG_ONGOING_EVENT)
            assertTrue(discNotif.flags and Notification.FLAG_AUTO_CANCEL != 0)

            // 恢复流式并在第二轮完成后再次干净退出
            emit("conversation.message.completed", """{"conversationId":"conversation","messageId":"r2","generationId":"g2","sender":"assistant","parts":[{"type":"text","text":"第二轮完成"}],"revision":11}""", null)
            emit("conversation.message.status", """{"conversationId":"conversation","messageId":"u2","clientMessageId":"c2","generationId":"g2","status":"completed","revision":12}""", null)
            assertEquals(listOf(REPLY_CHANNEL_ID), manager.activeNotifications.map { it.notification.channelId })
            val finalStyle = Notification.Builder.recoverBuilder(context, manager.activeNotifications.single().notification).style as Notification.MessagingStyle
            assertEquals("第二轮完成", finalStyle.messages.single().text.toString())
        }

    @Test fun endToEndForegroundSuppressionAndBackgroundHandoffAreMutuallyExclusive() {
        var inForeground = true
        withWiredCoordinator(title = "前后台互斥会话", suppress = { inForeground }) { _, emit, advanceClock ->
            // 前台可见时，进行中和完成均不得显示任何 Live Update 或普通回复通知
            emit("conversation.message.status", """{"conversationId":"conversation","messageId":"u1","clientMessageId":"c1","generationId":"g-fg","status":"delivered","revision":1}""", null)
            assertTrue(manager.activeNotifications.isEmpty())
            emit("conversation.message.completed", """{"conversationId":"conversation","messageId":"r1","generationId":"g-fg","sender":"assistant","parts":[{"type":"text","text":"前台已读"}],"revision":2}""", null)
            emit("conversation.message.status", """{"conversationId":"conversation","messageId":"u1","clientMessageId":"c1","generationId":"g-fg","status":"completed","revision":3}""", null)
            assertTrue(manager.activeNotifications.isEmpty())

            // 后台发起轮次 -> 显示 Live Update；完成瞬间切回前台 -> 必须清理 Live Update 且不发普通通知
            advanceClock(100)
            inForeground = false
            emit("conversation.message.status", """{"conversationId":"conversation","messageId":"u2","clientMessageId":"c2","generationId":"g-bg-to-fg","status":"queued","revision":10}""", null)
            assertEquals(listOf(LIVE_UPDATE_CHANNEL_ID), manager.activeNotifications.map { it.notification.channelId })

            inForeground = true
            emit("conversation.message.completed", """{"conversationId":"conversation","messageId":"r2","generationId":"g-bg-to-fg","sender":"assistant","parts":[{"type":"text","text":"切回前台时完成"}],"revision":11}""", null)
            emit("conversation.message.status", """{"conversationId":"conversation","messageId":"u2","clientMessageId":"c2","generationId":"g-bg-to-fg","status":"completed","revision":12}""", null)
            assertTrue("切回前台完成时常驻 Live Update 也必须退出", manager.activeNotifications.isEmpty())
        }
    }

    private fun withWiredCoordinator(
        title: String,
        suppress: () -> Boolean = { false },
        block: (
            coordinator: NativeConversationNotifications,
            emit: (type: String, payload: String, resolvedGenerationId: String?) -> Unit,
            advanceClock: (Long) -> Unit,
        ) -> Unit,
    ) {
        replyCompletionPreferences(context, "profile", "account").edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var clock = System.currentTimeMillis()
        var seq = 0
        val wiredPublisher = AndroidReplyNotificationPublisher(context, settings, suppress = { suppress() })
        val coordinator = NativeConversationNotifications(
            context = context,
            scope = scope,
            publish = wiredPublisher::update,
            titleFor = { title },
            now = { clock },
            throttleMillis = 0L,
            cancel = wiredPublisher::cancel,
            forget = wiredPublisher::forget,
        )
        try {
            coordinator.bind("profile", "account")
            block(
                coordinator,
                { type, payload, resolvedGenerationId ->
                    clock += 10
                    val fact = requireNotNull(notificationFact("profile", "account", "lu-e2e-${++seq}", clock, type, payload))
                    coordinator.accept(fact.copy(resolvedGenerationId = resolvedGenerationId))
                },
                { delta -> clock += delta },
            )
        } finally {
            scope.cancel()
        }
    }

    private fun session(status: NativeConversationStatus, text: String = "") = NativeConversationSession(target, "generation", "任务会话", status, text, updatedAt = System.currentTimeMillis())
}

@Implements(NotificationManager::class)
class LiveUpdateNotificationManagerShadow : ShadowNotificationManager() {
    var promotedAllowed: Boolean = true
    var bubblePreferenceSetting: Int = NotificationManager.BUBBLE_PREFERENCE_NONE
    @Implementation(minSdk = 36)
    protected fun canPostPromotedNotifications(): Boolean = promotedAllowed
    @Implementation(minSdk = 31)
    protected fun getBubblePreference(): Int = bubblePreferenceSetting
}
