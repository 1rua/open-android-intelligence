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

    @Test fun runningTaskKeepsBubbleConversationAndSeparateEligibleIndeterminateLiveUpdate() {
        assertEquals(36, Build.VERSION.SDK_INT)
        publisher.update(session(NativeConversationStatus.RUNNING, "Latest public reply"), false)
        val conversation = manager.activeNotifications.single { it.notification.channelId == REPLY_CHANNEL_ID }.notification
        val live = manager.activeNotifications.single { it.notification.channelId == LIVE_UPDATE_CHANNEL_ID }.notification
        assertTrue(Notification.Builder.recoverBuilder(context, conversation).style is Notification.MessagingStyle)
        assertNotNull(conversation.bubbleMetadata)
        val shortcut = context.getSystemService(ShortcutManager::class.java).dynamicShortcuts.single { it.id == conversation.shortcutId }
        val participants = org.robolectric.util.ReflectionHelpers.callInstanceMethod<Array<android.app.Person>>(shortcut, "getPersons")
        val conversationStyle = Notification.Builder.recoverBuilder(context, conversation).style as Notification.MessagingStyle
        assertTrue("API 36 must also include the real user for conversation eligibility", participants.any { !it.isBot && it.key == conversationStyle.user.key })
        assertTrue(participants.any { it.isBot })
        val progress = Notification.Builder.recoverBuilder(context, live).style as Notification.ProgressStyle
        assertTrue(progress.isProgressIndeterminate)
        assertTrue(progress.progressSegments.isEmpty())
        assertTrue(progress.progressPoints.isEmpty())
        assertTrue("Use the real framework eligibility predicate", live.hasPromotableCharacteristics())
        assertTrue(live.extras.getBoolean(REQUEST_PROMOTED_ONGOING))
        assertEquals("处理中", live.shortCriticalText)
        assertEquals("Latest public reply", live.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(live.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(live.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals("Only the system may assign actual promotion", 0, live.flags and Notification.FLAG_PROMOTED_ONGOING)
        assertNull(live.bubbleMetadata)
        assertNull(live.shortcutId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(LIVE_UPDATE_CHANNEL_ID).importance)
        assertNull(manager.getNotificationChannel(LIVE_UPDATE_CHANNEL_ID).sound)

        publisher.update(session(NativeConversationStatus.RUNNING, "New snapshot"), false)
        assertEquals(2, manager.activeNotifications.size)
        assertEquals("New snapshot", manager.activeNotifications.single { it.notification.channelId == LIVE_UPDATE_CHANNEL_ID }.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test fun terminalAndUnknownRemoteStateRemoveLiveCardWhileKeepingConversation() {
        listOf(NativeConversationStatus.COMPLETED, NativeConversationStatus.FAILED, NativeConversationStatus.CANCELLED, NativeConversationStatus.DISCONNECTED).forEach { outcome ->
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
        assertEquals(4, manager.activeNotifications.size)
        publisher.cancel(target)
        assertEquals(2, manager.activeNotifications.size)
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
        assertTrue(manager.activeNotifications.any { it.notification.channelId == REPLY_CHANNEL_ID })
        managerShadow.promotedAllowed = true
        settings.refreshPermission()
        publisher.update(session(NativeConversationStatus.RUNNING), false)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settings.refreshPermission()
        assertFalse(settings.liveUpdatesAllowed.value)
        assertTrue(manager.activeNotifications.none { it.notification.channelId == LIVE_UPDATE_CHANNEL_ID })
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

    private fun session(status: NativeConversationStatus, text: String = "") = NativeConversationSession(target, "generation", "任务会话", status, text, updatedAt = System.currentTimeMillis())
}

@Implements(NotificationManager::class)
class LiveUpdateNotificationManagerShadow : ShadowNotificationManager() {
    var promotedAllowed: Boolean = true
    @Implementation(minSdk = 36)
    protected fun canPostPromotedNotifications(): Boolean = promotedAllowed
}
