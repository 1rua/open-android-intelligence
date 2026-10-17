package com.openandroidintelligence.mobile

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ShortcutManager
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.events.GatewayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeConversationNotificationsTest {
    private lateinit var context: Application
    private lateinit var coordinator: NativeConversationNotifications
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val publications = CopyOnWriteArrayList<Pair<NativeConversationSession, Boolean>>()
    private var clock = System.currentTimeMillis()
    private var sequence = 0

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        replyCompletionPreferences(context, "profile", "account").edit().clear().commit()
        context.getSharedPreferences("agent-reply-alert-settings", 0).edit().clear().commit()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        coordinator = coordinator()
        coordinator.bind("profile", "account")
    }

    @After fun close() { scope.cancel() }

    @Test fun cumulativeDeltasReplacePreviewAndCoalesceBeforeOneFinalAlert() = runBlocking {
        accept(status("delivered", 1))
        accept(message("conversation.message.delta", "H", 1))
        accept(message("conversation.message.delta", "Hello", 2))
        accept(message("conversation.message.delta", "Hello world", 3))
        assertEquals("Hello world", coordinator.sessions.value.single().latestReply)
        delay(100)
        assertTrue("Token bursts must not create per-token notifications", publications.size <= 2)
        assertEquals("Hello world", publications.last().first.latestReply)
        assertTrue(publications.none { it.second })

        accept(message("conversation.message.completed", "Final answer", 4))
        accept(status("completed", 2))
        delay(100)
        assertEquals(1, publications.count { it.second })
        assertEquals("Final answer", publications.last().first.latestReply)
        assertEquals(NativeConversationStatus.COMPLETED, publications.last().first.status)
        assertFalse(publications.last().first.isRunning)

        val count = publications.size
        coordinator = coordinator()
        coordinator.bind("profile", "account")
        accept(message("conversation.message.delta", "old replay", 1))
        accept(status("completed", 2))
        delay(100)
        assertEquals("A replay cannot revive a finished/dismissed conversation notification", count, publications.size)
    }

    @Test fun olderRevisionsReplayAndPrivateOrToolMessagesCannotRegressPublicPreview() {
        val newest = message("conversation.message.delta", "Newest visible", 5)
        accept(newest)
        accept(message("conversation.message.delta", "Older", 2))
        accept(newest)
        accept(message("conversation.message.delta", "tool secret", 6, sender = "tool", messageId = "tool"))
        accept(message("conversation.message.delta", "private reasoning", 7, channel = "analysis", messageId = "analysis"))
        assertEquals("Newest visible", coordinator.sessions.value.single().latestReply)
        accept(message("conversation.message.completed", "Newest visible", 5))
        accept(status("completed", 2))
        assertEquals(NativeConversationStatus.COMPLETED, coordinator.sessions.value.single().status)
        assertEquals(1, publications.count { it.second })
    }

    @Test fun equalRevisionSnapshotsAndCompletionReplaceTheLatestPreview() {
        accept(status("delivered", 1))
        accept(message("conversation.message.delta", "H", 0))
        accept(message("conversation.message.delta", "Hello", 0))
        assertEquals("Hello", coordinator.sessions.value.single().latestReply)
        accept(message("conversation.message.completed", "Final answer", 0))
        accept(status("completed", 2))
        assertEquals("Final answer", publications.last().first.latestReply)
        assertEquals(NativeConversationStatus.COMPLETED, publications.last().first.status)
        assertEquals(1, publications.count { it.second })
    }

    @Test fun successfulTerminalStopsOngoingEvenWithoutBodyAndLaterBodyDoesNotReopenTask() = runBlocking {
        accept(status("delivered", 1))
        accept(status("completed", 2))
        assertFalse(coordinator.sessions.value.single().isRunning)
        assertEquals(NativeConversationStatus.COMPLETED, coordinator.sessions.value.single().status)
        assertTrue("No reply body yet means no final reply alert", publications.none { it.second })
        accept(message("conversation.message.completed", "Arrived after status", 1))
        assertEquals("Arrived after status", coordinator.sessions.value.single().latestReply)
        assertFalse(coordinator.sessions.value.single().isRunning)
        assertEquals(1, publications.count { it.second })
        delay(100)
        assertFalse(publications.last().first.isRunning)
    }

    @Test fun legacyUnknownStartStillEndsOngoingButDoesNotInventFinalReplyCorrelation() {
        accept(message("conversation.message.delta", "Legacy preview", 1, generation = null))
        assertTrue(coordinator.sessions.value.single().isRunning)
        accept(status("completed", 2, generation = null))
        assertFalse(coordinator.sessions.value.single().isRunning)
        assertTrue(publications.none { it.second })
    }

    @Test fun cancelAndFailureAreAccurateAndOldTaskCannotStopNextQueuedGeneration() {
        accept(status("delivered", 1, generation = "first"))
        accept(message("conversation.message.completed", "First reply", 1, generation = "first"))
        accept(status("queued", 1, generation = "next"))
        accept(status("completed", 2, generation = "first"))
        assertEquals(NativeConversationStatus.QUEUED, coordinator.sessions.value.single().status)
        assertEquals("next", coordinator.sessions.value.single().generationId)
        accept(status("delivered", 2, generation = "next"))
        accept(event("conversation.generation.cancelled", """{"conversationId":"conversation","generationId":"next"}"""))
        assertEquals(NativeConversationStatus.CANCELLED, coordinator.sessions.value.single().status)
        assertFalse(coordinator.sessions.value.single().isRunning)
        accept(status("failed", 1, generation = "failed"))
        assertEquals(NativeConversationStatus.FAILED, coordinator.sessions.value.single().status)
        assertFalse(coordinator.sessions.value.single().isRunning)
    }

    @Test fun statusRevisionsAreMonotonicAndAccountSwitchCancelsPendingPublications() = runBlocking {
        accept(status("delivered", 5))
        accept(message("conversation.message.delta", "Latest", 1))
        accept(status("completed", 2))
        assertTrue("Stale terminal status must not close a running task", coordinator.sessions.value.single().isRunning)
        coordinator.bind("other-profile", "other-account")
        val count = publications.size
        accept(message("conversation.message.delta", "Old account late frame", 2))
        delay(100)
        assertEquals(count, publications.size)
        assertTrue(coordinator.sessions.value.isEmpty())
    }

    @Test fun nativePublisherUpdatesStableConversationNotificationAndPublishesBubbleShortcut() {
        val settings = ReplyNotificationSettings(context)
        val publisher = AndroidReplyNotificationPublisher(context, settings)
        val target = ReplyNotificationTarget("profile", "account", "conversation")
        val running = NativeConversationSession(target, "first", "Visible conversation", NativeConversationStatus.RUNNING, "Intermediate reply", updatedAt = clock)
        publisher.update(running, false)
        val manager = context.getSystemService(NotificationManager::class.java)
        val first = manager.activeNotifications.single()
        assertTrue(first.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(first.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        val firstStyle = Notification.Builder.recoverBuilder(context, first.notification).style as Notification.MessagingStyle
        assertEquals("Intermediate reply", firstStyle.messages.single().text.toString())
        assertNotNull(first.notification.bubbleMetadata)
        val shortcutId = first.notification.shortcutId
        assertEquals(shortcutId, requireNotNull(first.notification.bubbleMetadata).shortcutId)
        val shortcut = context.getSystemService(ShortcutManager::class.java).dynamicShortcuts.single { it.id == shortcutId }
        assertEquals("Visible conversation", shortcut.shortLabel.toString())
        assertEquals(target, BubbleConversationActivity.targetFromIntent(shortcut.intent!!))
        assertTrue(org.robolectric.util.ReflectionHelpers.callInstanceMethod<Boolean>(shortcut, "isLongLived"))
        val persons = org.robolectric.util.ReflectionHelpers.callInstanceMethod<Array<android.app.Person>>(shortcut, "getPersons")
        assertEquals("Agent", persons.single().name.toString())
        assertEquals(setOf("android.shortcut.conversation"), shortcut.categories)
        assertEquals("Agent", firstStyle.messages.single().senderPerson!!.name.toString())

        publisher.update(running.copy(status = NativeConversationStatus.COMPLETED, latestReply = "Final reply"), true)
        val final = manager.activeNotifications.single()
        assertEquals(first.tag, final.tag)
        assertEquals(first.id, final.id)
        assertFalse(final.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Final reply", (Notification.Builder.recoverBuilder(context, final.notification).style as Notification.MessagingStyle).messages.single().text.toString())
        publisher.update(running.copy(generationId = "second"), false)
        assertEquals(1, manager.activeNotifications.size)

        settings.setEnabled(false)
        assertTrue(manager.activeNotifications.isEmpty())
        publisher.update(running, false)
        assertTrue(manager.activeNotifications.isEmpty())
        settings.setEnabled(true)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        publisher.update(running, false)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun permissionAndPreferenceRecoveryRepublishOnlyStillActiveWork() = runBlocking {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val settings = ReplyNotificationSettings(context)
        val publisher = AndroidReplyNotificationPublisher(context, settings)
        coordinator = NativeConversationNotifications(context, scope, publisher::update, now = { clock }, throttleMillis = 40)
        coordinator.bind("profile", "account")
        accept(status("queued", 1))
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(manager.activeNotifications.isEmpty())
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settings.refreshPermission()
        coordinator.refreshActive()
        assertEquals(1, manager.activeNotifications.size)
        settings.setEnabled(false)
        assertTrue(manager.activeNotifications.isEmpty())
        settings.setEnabled(true)
        coordinator.refreshActive()
        assertEquals(1, manager.activeNotifications.size)
        accept(status("completed", 2))
        settings.setEnabled(false)
        settings.setEnabled(true)
        coordinator.refreshActive()
        delay(100)
        assertTrue("Permission/preference recovery must not replay old terminal notifications", manager.activeNotifications.isEmpty())
    }

    private fun coordinator() = NativeConversationNotifications(context, scope, { session, alert -> publications += session to alert }, now = { clock }, throttleMillis = 40)
    private fun accept(event: GatewayEvent) = coordinator.accept("profile", "account", event)
    private fun message(type: String, text: String, revision: Long, generation: String? = "generation", sender: String = "assistant", messageId: String = "reply", channel: String? = null) = event(type,
        """{"conversationId":"conversation","messageId":"$messageId",${generationField(generation)}"sender":"$sender","parts":[{"type":"text","text":"$text"}],"revision":$revision${channel?.let { ",\"channel\":\"$it\"" }.orEmpty()}}""")
    private fun status(status: String, revision: Long, generation: String? = "generation") = event("conversation.message.status",
        """{"conversationId":"conversation","messageId":"user-${generation ?: "legacy"}","clientMessageId":"client-${generation ?: "legacy"}",${generationField(generation)}"status":"$status","revision":$revision,"errorCode":${if (status == "failed") "\"AGENT_UNAVAILABLE\"" else "null"}}""")
    private fun generationField(generation: String?) = generation?.let { "\"generationId\":\"$it\"," }.orEmpty()
    private fun event(type: String, payload: String): GatewayEvent {
        clock++
        return GatewayEvent("realtime-${++sequence}", type, """{"accountId":"account","occurredAt":"${Instant.ofEpochMilli(clock)}","payload":$payload}""")
    }
}
