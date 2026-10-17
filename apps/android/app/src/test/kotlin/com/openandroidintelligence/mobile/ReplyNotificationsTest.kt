package com.openandroidintelligence.mobile

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.events.SseParser
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.robolectric.shadows.ShadowShortcutManager
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReplyNotificationsTest {
    private lateinit var context: Application
    private val posts = mutableListOf<Pair<ReplyNotificationTarget, String>>()
    private var clock = Instant.parse("2026-10-17T02:00:00Z").toEpochMilli()
    private var eventNumber = 0

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("completion-test", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("agent-reply-alert-settings", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun progressAndCompletedBodyWaitForSuccessfulGenerationThenReplayDoesNotAlertAgain() {
        val observer = observer()
        observer.accept(message("conversation.message.delta"))
        observer.accept(message("conversation.message.completed", id = "progress"))
        observer.accept(status("delivered"))
        assertTrue(posts.isEmpty())
        val finalReply = message("conversation.message.completed", id = "final")
        observer.accept(finalReply)
        assertTrue(posts.isEmpty())
        val success = status("completed")
        observer.accept(success)
        assertEquals(listOf(ReplyNotificationTarget("profile-a", "account-a", "conv-a") to "gen-a"), posts)

        // Neither an SSE replay nor a different event ID for the same generation
        // may revive a notification the user already dismissed.
        val restarted = observer()
        restarted.accept(finalReply)
        restarted.accept(success)
        restarted.accept(status("completed"))
        assertEquals(1, posts.size)
    }

    @Test fun finalStatusBeforeBodyWaitsAndPendingBodySurvivesProcessRecreation() {
        observer().accept(status("completed"))
        assertTrue(posts.isEmpty())
        observer().accept(message("conversation.message.completed"))
        assertEquals(1, posts.size)
    }

    @Test fun legacyRoundWaitsForTerminalStatusAndNextQueuedTaskCannotReuseItsReply() {
        val observer = observer()
        observer.accept(status("delivered", generation = null))
        observer.accept(message("conversation.message.completed", generation = null, id = "progress"))
        assertTrue(posts.isEmpty())
        observer.accept(message("conversation.message.completed", generation = null, id = "final"))
        observer.accept(status("completed", generation = null))
        assertEquals(1, posts.size)
        assertEquals("conv-a", posts.single().first.conversationId)
        assertTrue(posts.single().second.startsWith("legacy:"))
        observer.accept(status("queued", generation = null, userId = "next-user"))
        observer.accept(status("completed", generation = null, userId = "next-user"))
        assertEquals("New task must receive its own reply", 1, posts.size)
        val restarted = observer()
        restarted.accept(status("queued", generation = null))
        restarted.accept(message("conversation.message.completed", generation = null))
        restarted.accept(status("completed", generation = null))
        assertEquals("Legacy replay cannot revive an old completion", 1, posts.size)
    }

    @Test fun legacyNewUserResetsRoundAndUnobservedOrMixedGenerationDoesNotBorrowReply() {
        val observer = observer()
        observer.accept(message("conversation.message.completed", generation = null))
        observer.accept(status("completed", generation = null))
        assertTrue(posts.isEmpty())
        observer.accept(status("queued", generation = null, userId = "two"))
        observer.accept(message("conversation.message.completed", generation = null))
        observer.accept(status("queued", generation = null, userId = "three"))
        observer.accept(status("completed", generation = null, userId = "two"))
        observer.accept(status("completed", generation = null, userId = "three"))
        assertTrue(posts.isEmpty())
        observer.accept(status("queued", generation = null, userId = "four"))
        observer.accept(message("conversation.message.completed", generation = "strong"))
        observer.accept(status("completed", generation = null, userId = "four"))
        assertTrue(posts.isEmpty())
        observer.accept(status("completed", generation = "strong"))
        assertEquals(1, posts.size)
    }

    @Test fun failedOrCancelledLegacyRoundCannotLeakItsBodyIntoNextTask() {
        val observer = observer()
        observer.accept(status("delivered", generation = null, userId = "failed"))
        observer.accept(message("conversation.message.completed", generation = null))
        observer.accept(status("failed", generation = null, userId = "failed"))
        observer.accept(status("completed", generation = null, userId = "failed"))
        observer.accept(status("queued", generation = null, userId = "cancelled"))
        observer.accept(message("conversation.message.completed", generation = null))
        observer.accept(event("conversation.generation.cancelled", """{"conversationId":"conv-a"}"""))
        observer.accept(status("completed", generation = null, userId = "cancelled"))
        observer.accept(status("queued", generation = null, userId = "next"))
        observer.accept(status("completed", generation = null, userId = "next"))
        assertTrue(posts.isEmpty())
    }

    @Test fun conversationAndGenerationMustMatchAndOtherConversationRoutesCorrectly() {
        val observer = observer()
        observer.accept(message("conversation.message.completed", conversation = "conv-b"))
        observer.accept(status("completed"))
        observer.accept(status("completed", conversation = "conv-b", generation = "gen-b"))
        assertTrue(posts.isEmpty())
        observer.accept(status("completed", conversation = "conv-b"))
        assertEquals("conv-b", posts.single().first.conversationId)
    }

    @Test fun toolsErrorsCancellationMissingGenerationAndForeignAccountNeverNotify() {
        val observer = observer()
        observer.accept(message("conversation.message.completed", sender = "tool"))
        observer.accept(status("completed"))
        assertTrue(posts.isEmpty())
        observer.accept(message("conversation.message.completed", generation = "failed"))
        observer.accept(status("failed", generation = "failed"))
        observer.accept(status("completed", generation = "failed"))
        observer.accept(message("conversation.message.completed", generation = "cancelled"))
        observer.accept(event("conversation.generation.cancelled", """{"conversationId":"conv-a","generationId":"cancelled"}"""))
        observer.accept(status("completed", generation = "cancelled"))
        observer.accept(event("conversation.message.completed", """{"conversationId":"conv-a","messageId":"legacy","sender":"assistant","text":"reply"}"""))
        observer.accept(message("conversation.message.completed", generation = "foreign", account = "account-b"))
        observer.accept(status("completed", generation = "foreign"))
        assertTrue(posts.isEmpty())
    }

    @Test fun staleHistoryIsIgnoredAndPendingMetadataExpires() {
        val observer = observer()
        observer.accept(message("conversation.message.completed"))
        val originalClock = clock
        clock += 8L * 24 * 60 * 60 * 1000
        observer.accept(status("completed", generation = "new-gen"))
        assertFalse(context.getSharedPreferences("completion-test", Context.MODE_PRIVATE).all.keys.any { it.startsWith("reply.") })
        clock = originalClock - 10 * 60 * 1000
        observer.accept(message("conversation.message.completed", generation = "old"))
        observer.accept(status("completed", generation = "old"))
        assertTrue(posts.isEmpty())
    }

    @Test fun freshInstallPermissionIsAllowedBeforeTheFirstReplyCreatesAChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNull(manager.getNotificationChannel(REPLY_CHANNEL_ID))
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val settings = ReplyNotificationSettings(context)
        assertFalse(settings.allowed.value)

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settings.refreshPermission()
        assertTrue(settings.isAllowed())
        assertTrue(settings.allowed.value)
        assertTrue(settings.enabled.value)
        assertNull(manager.getNotificationChannel(REPLY_CHANNEL_ID))
    }

    @Test fun completionMetadataPruningIsBoundedDuringLongReplyAndStillExpiresOldRounds() {
        val preferences = CountingPreferences(context.getSharedPreferences("completion-test", Context.MODE_PRIVATE))
        val observer = ReplyCompletionObserver(preferences, "profile-a", "account-a", { _, _ -> }, { clock })
        repeat(500) { index ->
            observer.accept(message("conversation.message.completed", generation = "round-$index"))
        }
        assertTrue("A reply burst must not scan all stored rounds per message", preferences.allReads <= 1)

        clock += 8L * 24 * 60 * 60 * 1000
        observer.accept(status("delivered", generation = "new-round"))
        assertFalse(preferences.contains("reply." + replyDigest("conv-a\u0000round-0")))
        assertTrue(preferences.allReads <= 2)
    }

    @Test
    @Config(shadows = [CachedShortcutManagerShadow::class])
    fun accountRemovalFindsCachedAndPinnedShortcutsAfterDynamicEvictionAndRestart() {
        val settings = ReplyNotificationSettings(context)
        val publisher = AndroidReplyNotificationPublisher(context, settings)
        val manager = context.getSystemService(ShortcutManager::class.java)
        val shadow = Shadow.extract<CachedShortcutManagerShadow>(manager)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val ownTargets = listOf("dynamic", "cached", "pinned").map { ReplyNotificationTarget("profile-a", "account-a", it) }
        val otherTarget = ReplyNotificationTarget("profile-a", "other-account", "other-conversation")
        (ownTargets + otherTarget).forEach { publisher.post(it, "generation") }
        val shortcuts = manager.dynamicShortcuts.associateBy { BubbleConversationActivity.targetFromIntent(it.intent!!)!! }
        val cached = shortcuts.getValue(ownTargets[1])
        manager.removeDynamicShortcuts(listOf(cached.id))
        shadow.cachedShortcuts[cached.id] = cached
        val pinned = shortcuts.getValue(ownTargets[2])
        assertTrue(manager.requestPinShortcut(pinned, null))
        assertTrue(manager.dynamicShortcuts.none { it.id == cached.id || it.id == pinned.id })

        // System caches survive process recreation; the publisher's in-memory map does not.
        AndroidReplyNotificationPublisher(context, settings).eraseAccount("profile-a", "account-a")

        val ownIds = ownTargets.map { shortcuts.getValue(it).id }.toSet()
        assertEquals(ownIds, shadow.removedLongLivedIds.toSet())
        assertTrue(shadow.cachedShortcuts.isEmpty())
        assertEquals(listOf(shortcuts.getValue(otherTarget).id), manager.dynamicShortcuts.map { it.id })
        assertEquals(1, notificationManager.activeNotifications.size)
        assertEquals(otherTarget, ReplyNotificationTarget.from(shadowOf(notificationManager.activeNotifications.single().notification.contentIntent).savedIntent))
        assertTrue("A user-owned launcher pin must be disabled after account removal",
            runCatching { manager.createShortcutResultIntent(pinned) }.exceptionOrNull() is IllegalArgumentException)

        // Re-adding the account can publish the same conversation ID again.
        AndroidReplyNotificationPublisher(context, settings).post(ownTargets[2], "new-generation")
        assertNotNull(manager.createShortcutResultIntent(pinned))
    }

    @Test fun deniedPermissionConsumesCompletionAndDoesNotReplayAfterPermissionGranted() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val settings = ReplyNotificationSettings(context)
        val publisher = AndroidReplyNotificationPublisher(context, settings)
        val observer = observer(publisher::post)
        observer.accept(message("conversation.message.completed"))
        observer.accept(status("completed"))
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        observer(publisher::post).accept(status("completed"))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())

        observer.accept(message("conversation.message.completed", generation = "next"))
        observer.accept(status("completed", generation = "next"))
        assertEquals(1, shadowOf(manager).allNotifications.size)
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(REPLY_CHANNEL_ID, notification.channelId)
        assertNotEquals(0, notification.smallIcon.resId)
        assertTrue(notification.contentIntent.isImmutable)
        notification.contentIntent.send()
        assertEquals(ReplyNotificationTarget("profile-a", "account-a", "conv-a"), ReplyNotificationTarget.from(shadowOf(context).nextStartedActivity))
        manager.cancelAll()
        observer(publisher::post).accept(status("completed", generation = "next"))
        assertTrue("A dismissed alert stays dismissed after replay", shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun disabledPreferenceAndBlockedChannelPreventPosting() {
        val settings = ReplyNotificationSettings(context)
        val publisher = AndroidReplyNotificationPublisher(context, settings)
        val target = ReplyNotificationTarget("profile-a", "account-a", "conv-a")
        settings.setEnabled(false)
        publisher.post(target, "gen-a")
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        settings.setEnabled(true)
        manager.deleteNotificationChannel(REPLY_CHANNEL_ID)
        manager.createNotificationChannel(android.app.NotificationChannel(REPLY_CHANNEL_ID, "Blocked", NotificationManager.IMPORTANCE_NONE))
        publisher.post(target, "gen-b")
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertFalse(settings.isAllowed())
    }

    @Test fun bubbleShortcutContainsTheRealLocalUserAlongsideTheBotAndTargetsEmbeddedActivity() {
        val publisher = AndroidReplyNotificationPublisher(context, ReplyNotificationSettings(context))
        val target = ReplyNotificationTarget("profile-a", "account-a", "conv-a")
        publisher.post(target, "generation")
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        val style = android.app.Notification.Builder.recoverBuilder(context, notification).style as android.app.Notification.MessagingStyle
        val shortcut = context.getSystemService(ShortcutManager::class.java).dynamicShortcuts.single { it.id == notification.shortcutId }
        val participants = org.robolectric.util.ReflectionHelpers.callInstanceMethod<Array<android.app.Person>>(shortcut, "getPersons")
        assertEquals(2, participants.size)
        assertTrue("Android excludes only-bot shortcuts from conversation/bubble eligibility", participants.any { !it.isBot && it.key == style.user.key })
        assertTrue("The assistant must remain accurately identified as a bot", participants.any { it.isBot && it.key == style.messages.single().senderPerson!!.key })
        assertFalse(style.user.isBot)
        assertEquals(MainActivity::class.java.name, requireNotNull(shortcut.activity).className)
        assertEquals(BubbleConversationActivity::class.java.name, shortcut.intent!!.component!!.className)
        assertEquals(target, BubbleConversationActivity.targetFromIntent(shortcut.intent!!))
        assertEquals(shortcut.id, notification.bubbleMetadata!!.shortcutId)
        assertNull("Shortcut bubbles use the shortcut launch intent, not an app PendingIntent", notification.bubbleMetadata!!.intent)
        assertTrue("The separate normal notification tap remains immutable", notification.contentIntent.isImmutable)
        assertFalse(ReplyNotificationSettings(context).liveUpdatesSupported)
        assertNull(ReplyNotificationSettings(context).liveUpdatesSettingsIntent())
    }

    @Test fun authenticatedRuntimeReceivesSseAndPublishesThenTapOpensMatchingConversationOnly() {
        clock = System.currentTimeMillis()
        val gateway = LoopbackGatewayStub()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            gateway.respond("/open-android-intelligence/v2/negotiate", """{"protocol":"2.1","data":{"protocol":{"major":2,"minor":1},"features":{"auth":["password","refresh"],"messages":"chat-v1","attachments":"staged-sha256-v1","events":"sse-cursor-v1","deviceRequests":"risk-queue-v1","conversationUi":[]},"limits":{"attachmentTtlSeconds":3600,"eventRetentionSeconds":86400,"maxClockSkewSeconds":120},"gatewayIdentity":{"deploymentId":"dep_stub","tlsSpkiSha256":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}}""")
            gateway.respond("/open-android-intelligence/v2/sessions/password", """{"data":{"accountId":"account-a","deviceId":"device-a","sessionId":"session-a","accessToken":"token","refreshCredential":"refresh"}}""")
            gateway.respond("/open-android-intelligence/v2/conversations", """{"protocol":"2.1","data":{"conversations":[{"conversationId":"initial","title":"Initial","lastMessageAt":"2026-10-17T02:00:00Z"}]}}""")
            gateway.respond("/open-android-intelligence/v2/conversations/initial/messages", """{"protocol":"2.1","data":{"messages":[],"nextCursor":null,"snapshotRevision":1}}""")
            gateway.respond("/open-android-intelligence/v2/conversations/conv-a/messages", """{"protocol":"2.1","data":{"messages":[],"nextCursor":null,"snapshotRevision":1}}""")
            val frames = listOf(message("conversation.message.completed"), status("completed")).joinToString("") {
                "id: ${it.id}\nevent: ${it.event}\ndata: ${it.data}\n\n"
            }
            gateway.respond("/open-android-intelligence/v2/events", frames, contentType = "text/event-stream")
            val runtime = GatewayRuntime(context, scope,
                PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore(InMemoryAuditSink())),
                credentialStore = InMemoryCredentialStore(), deviceKeys = InMemoryDeviceKeySource(), localDocumentKeyProvider = TestDocumentKeys)
            runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
            val manager = context.getSystemService(NotificationManager::class.java)
            val deadline = System.currentTimeMillis() + 15_000
            while (shadowOf(manager).allNotifications.isEmpty() && System.currentTimeMillis() < deadline) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            assertEquals("Runtime's actual authenticated SSE callback must publish", 1, shadowOf(manager).allNotifications.size)
            shadowOf(manager).allNotifications.single().contentIntent.send()
            val target = requireNotNull(ReplyNotificationTarget.from(shadowOf(context).nextStartedActivity))
            assertFalse(runtime.openReplyNotification(target.copy(accountId = "other-account")))
            assertFalse(runtime.openReplyNotification(target.copy(profileId = "other-gateway-profile")))
            assertTrue(runtime.openReplyNotification(target))
            assertEquals("conv-a", runtime.controller.value!!.state.value.activeThreadId)
        } finally {
            scope.cancel()
            gateway.closed()
        }
    }

    private fun observer(post: (ReplyNotificationTarget, String) -> Unit = { target, generation -> posts += target to generation }) = ReplyCompletionObserver(
        context.getSharedPreferences("completion-test", Context.MODE_PRIVATE), "profile-a", "account-a", post, { clock },
    )

    private fun message(type: String, id: String = "reply", sender: String = "assistant", conversation: String = "conv-a", generation: String? = "gen-a", account: String = "account-a") = event(type,
        """{"conversationId":"$conversation","messageId":"$id",${generationField(generation)}"sender":"$sender","text":"Reply","parts":[{"type":"text","text":"Reply"}],"revision":1}""", account)

    private fun status(status: String, conversation: String = "conv-a", generation: String? = "gen-a", userId: String = "user") = event("conversation.message.status",
        """{"conversationId":"$conversation","messageId":"$userId","clientMessageId":"client-$userId",${generationField(generation)}"status":"$status","revision":2,"errorCode":${if (status == "failed") "\"AGENT_UNAVAILABLE\"" else "null"}}""")

    private fun generationField(generation: String?) = generation?.let { "\"generationId\":\"$it\"," }.orEmpty()

    private fun event(type: String, payload: String, account: String = "account-a"): GatewayEvent = SseParser().feed(
        "id: event-${++eventNumber}\nevent: $type\ndata: {\"accountId\":\"$account\",\"occurredAt\":\"${Instant.ofEpochMilli(clock)}\",\"payload\":$payload}\n\n",
    ).single()

    private class CountingPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        var allReads = 0
        override fun getAll(): Map<String, *> = delegate.all.also { allReads++ }
    }

    /** Robolectric 4.14 does not retain cached shortcuts or implement their removal. */
    @Implements(ShortcutManager::class)
    class CachedShortcutManagerShadow : ShadowShortcutManager() {
        val cachedShortcuts = mutableMapOf<String, ShortcutInfo>()
        val removedLongLivedIds = mutableListOf<String>()

        @Implementation(minSdk = 30)
        override fun getShortcuts(matchFlags: Int): List<ShortcutInfo> =
            (super.getShortcuts(matchFlags) + if (matchFlags and ShortcutManager.FLAG_MATCH_CACHED != 0) cachedShortcuts.values else emptyList()).distinctBy { it.id }

        @Implementation(minSdk = 30)
        override fun removeLongLivedShortcuts(shortcutIds: List<String>) {
            removedLongLivedIds.addAll(shortcutIds)
            shortcutIds.forEach(cachedShortcuts::remove)
        }
    }
}
