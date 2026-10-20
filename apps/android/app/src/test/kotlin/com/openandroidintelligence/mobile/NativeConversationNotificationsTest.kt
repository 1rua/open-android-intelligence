package com.openandroidintelligence.mobile

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ShortcutManager
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.ports.ConversationPage
import com.openandroidintelligence.conversation.ports.ConversationSummary
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
@Config(sdk = [31, 32, 33, 34, 35, 36], application = Application::class)
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
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
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

    @Test fun terminalStatusFlushesThrottledPreviewAndAnEarlierTimestampedFinalBodyStillArrives() {
        coordinator = NativeConversationNotifications(context, scope, { session, alert -> publications += session to alert },
            now = { clock }, throttleMillis = 60_000)
        coordinator.bind("profile", "account")
        accept(status("delivered", 1))
        val finalBody = message("conversation.message.completed", "Final authoritative body", 2)
        accept(message("conversation.message.delta", "Buffered partial", 1))
        accept(status("completed", 2))
        assertEquals("A state-first terminal must bypass the streaming throttle", NativeConversationStatus.COMPLETED, publications.last().first.status)
        assertEquals("Buffered partial", publications.last().first.latestReply)
        assertTrue(publications.none { it.second })

        accept(finalBody)
        assertEquals("Final authoritative body", publications.last().first.latestReply)
        assertFalse(publications.last().first.isRunning)
        assertEquals(1, publications.count { it.second })
        accept(finalBody)
        assertEquals("Replayed final body must not ring again", 1, publications.count { it.second })
    }

    @Test fun oldGenerationFinalCannotReplaceTheNextGenerationsPreview() {
        accept(status("delivered", 1, generation = "old"))
        accept(message("conversation.message.delta", "Old partial", 1, generation = "old"))
        accept(status("completed", 2, generation = "old"))
        accept(status("delivered", 1, generation = "new"))
        assertEquals("A generation starts with its own empty preview", "", coordinator.sessions.value.single().latestReply)
        accept(message("conversation.message.delta", "New preview", 1, generation = "new", messageId = "new-reply"))
        accept(message("conversation.message.completed", "Old final", 2, generation = "old"))
        val current = coordinator.sessions.value.single()
        assertEquals("new", current.generationId)
        assertEquals(NativeConversationStatus.RUNNING, current.status)
        assertEquals("New preview", current.latestReply)
        assertEquals("New preview", publications.last().first.latestReply)
    }

    @Test fun conversationDeletionCancelsWorkAndFencesAStaleSnapshotAndLaterReplay() {
        val forgotten = mutableListOf<ReplyNotificationTarget>()
        coordinator = NativeConversationNotifications(context, scope, { session, alert -> publications += session to alert },
            now = { clock }, forget = { forgotten += it })
        coordinator.bind("profile", "account")
        accept(status("delivered", 1))
        coordinator.requestReconciliation()
        val request = coordinator.reconciliationRequests().single()
        accept(event("conversation.deleted", """{"conversationId":"conversation"}"""))
        assertEquals(listOf(request.session.target), forgotten)
        assertFalse(coordinator.reconcile(request, null, emptyList()))
        assertFalse(coordinator.hasPendingReconciliation())
        assertTrue(coordinator.sessions.value.isEmpty())
        val count = publications.size
        coordinator = coordinator()
        coordinator.bind("profile", "account")
        accept(status("queued", 2))
        accept(message("conversation.message.delta", "Deleted replay", 3))
        assertTrue(coordinator.sessions.value.isEmpty())
        assertEquals(count, publications.size)
    }

    @Test fun deletedFlagOnCanonicalConversationUpdateAlsoRetiresTheTarget() {
        val forgotten = mutableListOf<ReplyNotificationTarget>()
        coordinator = NativeConversationNotifications(context, scope, { _, _ -> }, now = { clock }, forget = { forgotten += it })
        coordinator.bind("profile", "account")
        accept(status("delivered", 1))
        accept(event("conversation.updated", """{"conversationId":"conversation","deleted":true}"""))
        assertEquals(1, forgotten.size)
        assertTrue(coordinator.sessions.value.isEmpty())
        assertFalse(coordinator.hasPendingReconciliation())
    }

    @Test fun authoritativePresenceRecoveryRetiresMissingTargetsAndRejectsStaleListingResults() {
        val forgotten = mutableListOf<ReplyNotificationTarget>()
        coordinator = NativeConversationNotifications(context, scope, { _, _ -> }, now = { clock }, forget = { forgotten += it })
        coordinator.bind("profile", "account")
        accept(status("delivered", 1))
        val stale = coordinator.capturePresenceReconciliation()
        accept(message("conversation.message.delta", "A newer event", 1))
        coordinator.retireMissingTargets(stale, presencePage())
        assertTrue("An event during the listing invalidates its absence receipt", coordinator.sessions.value.single().isRunning)
        coordinator.retireMissingTargets(coordinator.capturePresenceReconciliation(), presencePage("conversation"))
        assertTrue(forgotten.isEmpty())
        coordinator.disconnect()
        coordinator.retireMissingTargets(coordinator.capturePresenceReconciliation(), presencePage())
        assertEquals(1, forgotten.size)
        assertTrue(coordinator.sessions.value.isEmpty())
        assertFalse(coordinator.hasPendingReconciliation())
        accept(status("queued", 2))
        assertTrue("Missed deletion recovery also persists the replay fence", coordinator.sessions.value.isEmpty())
    }

    @Test fun cachedMissingPresenceKeepsTheShortcutAndDoesNotPersistDeletionBeforeAuthoritativeRecovery() {
        val publisher = AndroidReplyNotificationPublisher(context, ReplyNotificationSettings(context))
        fun freshCoordinator() = NativeConversationNotifications(context, scope, publisher::update,
            now = { clock }, throttleMillis = 0, cancel = publisher::cancel, forget = publisher::forget)
        coordinator = freshCoordinator()
        coordinator.bind("profile", "account")
        accept(status("delivered", 1))
        val manager = context.getSystemService(NotificationManager::class.java)
        val shortcuts = context.getSystemService(ShortcutManager::class.java)
        val shortcutId = manager.activeNotifications.single().notification.shortcutId
        val preferences = replyCompletionPreferences(context, "profile", "account")
        val deletedKey = "realtime.deleted." + replyDigest("conversation")

        coordinator.retireMissingTargets(coordinator.capturePresenceReconciliation(),
            presencePage("cached-other-conversation", authoritative = false))
        assertTrue(coordinator.sessions.value.single().isRunning)
        assertEquals(1, manager.activeNotifications.size)
        assertTrue("A cached absence must keep the bubble shortcut routable",
            shortcuts.dynamicShortcuts.any { it.id == shortcutId && it.isEnabled })
        assertFalse("A cached absence must not install a durable replay fence", preferences.contains(deletedKey))

        coordinator.retireMissingTargets(coordinator.capturePresenceReconciliation(), presencePage("conversation"))
        coordinator.leave()
        coordinator = freshCoordinator()
        coordinator.bind("profile", "account")
        accept(status("delivered", 1, generation = "after-reconnect"))
        assertEquals("after-reconnect", coordinator.sessions.value.single().generationId)
        assertTrue(shortcuts.dynamicShortcuts.any { it.id == shortcutId && it.isEnabled })

        coordinator.retireMissingTargets(coordinator.capturePresenceReconciliation(), presencePage())
        assertTrue(coordinator.sessions.value.isEmpty())
        assertTrue(manager.activeNotifications.isEmpty())
        assertTrue("Confirmed deletion must remove the bubble shortcut", shortcuts.dynamicShortcuts.none { it.id == shortcutId })
        assertTrue(preferences.contains(deletedKey))
        coordinator = freshCoordinator()
        coordinator.bind("profile", "account")
        accept(status("delivered", 1, generation = "after-deletion"))
        assertTrue("Confirmed deletion must stay fenced across recreation", coordinator.sessions.value.isEmpty())
    }

    @Test fun anAuthoritativeFirstPageCannotRetireTargetsBeforePaginationCompletes() {
        val forgotten = mutableListOf<ReplyNotificationTarget>()
        coordinator = NativeConversationNotifications(context, scope, { _, _ -> }, now = { clock }, forget = { forgotten += it })
        coordinator.bind("profile", "account")
        accept(status("delivered", 1))
        coordinator.retireMissingTargets(coordinator.capturePresenceReconciliation(), presencePage(nextCursor = "next"))
        assertTrue(coordinator.sessions.value.single().isRunning)
        assertTrue(forgotten.isEmpty())
        assertFalse(replyCompletionPreferences(context, "profile", "account")
            .contains("realtime.deleted." + replyDigest("conversation")))
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

    @Test fun completionPreservesQueuedOrRunningNextGenerationInBothBodyStatusOrders() {
        listOf(false, true).forEach { statusBeforeBody ->
            listOf("queued", "delivered").forEach { nextStatus ->
                coordinator.leave()
                replyCompletionPreferences(context, "profile", "account").edit().clear().commit()
                coordinator = coordinator()
                coordinator.bind("profile", "account")
                publications.clear()
                accept(status("delivered", 1, generation = "first"))
                if (statusBeforeBody) accept(status("completed", 2, generation = "first"))
                else accept(message("conversation.message.completed", "First final", 1, generation = "first"))
                accept(status(nextStatus, 1, generation = "next"))
                if (statusBeforeBody) accept(message("conversation.message.completed", "First final", 1, generation = "first"))
                else accept(status("completed", 2, generation = "first"))

                val remaining = coordinator.sessions.value.single()
                assertEquals("Completion must retain the remaining generation", "next", remaining.generationId)
                assertEquals(if (nextStatus == "queued") NativeConversationStatus.QUEUED else NativeConversationStatus.RUNNING, remaining.status)
                assertEquals(NativeConversationStatus.COMPLETED, remaining.lastOutcome)
                assertTrue(remaining.isRunning)
                assertEquals(1, publications.count { it.second })
                accept(status("delivered", 2, generation = "next"))
                accept(event("conversation.generation.cancelled", """{"conversationId":"conversation","generationId":"next"}"""))
                assertFalse(coordinator.sessions.value.single().isRunning)
                assertEquals(NativeConversationStatus.CANCELLED, coordinator.sessions.value.single().status)
            }
        }
    }

    @Test fun firstFrameInitializesCutoffBeforeRejectingOldQueuedDeltaOrTerminal() {
        val firstFrames = listOf(
            status("queued", 1),
            message("conversation.message.delta", "Historical reply", 1),
            status("completed", 2),
            status("failed", 2),
        )
        firstFrames.forEach { frame ->
            val preferences = replyCompletionPreferences(context, "profile", "account")
            preferences.edit().clear().commit()
            coordinator = coordinator()
            coordinator.bind("profile", "account")
            publications.clear()
            val staleBody = frame.data.replace(Regex("\"occurredAt\":\"[^\"]+\""), "\"occurredAt\":\"${Instant.ofEpochMilli(clock - 600_000)}\"")
            accept(frame.copy(data = staleBody))
            assertTrue("The first historical ${frame.event} must not publish", publications.isEmpty())
            assertTrue(coordinator.sessions.value.isEmpty())
            assertEquals(clock, preferences.getLong("first-enabled-at", 0L))
        }
    }

    @Test fun recoverableDisconnectResumesButExplicitLeaveAndAccountSwitchCancelOngoing() = runBlocking {
        val cancelled = mutableListOf<ReplyNotificationTarget>()
        coordinator = NativeConversationNotifications(context, scope, { session, alert -> publications += session to alert },
            cancel = { cancelled += it }, now = { clock }, throttleMillis = 40)
        coordinator.bind("profile", "account")
        accept(status("delivered", 1))
        accept(message("conversation.message.delta", "First snapshot", 1))
        val target = coordinator.sessions.value.single().target
        coordinator.disconnect()
        assertEquals(NativeConversationStatus.DISCONNECTED, coordinator.sessions.value.single().status)
        assertTrue(coordinator.sessions.value.single().isRunning)
        assertTrue(cancelled.isEmpty())
        accept(message("conversation.message.delta", "Resumed snapshot", 2))
        assertEquals(NativeConversationStatus.RUNNING, coordinator.sessions.value.single().status)
        assertEquals("Resumed snapshot", coordinator.sessions.value.single().latestReply)

        coordinator.leave()
        assertEquals(listOf(target), cancelled)
        assertTrue(coordinator.sessions.value.isEmpty())
        val countAfterLeave = publications.size
        coordinator.refreshActive()
        accept(message("conversation.message.delta", "Late old frame", 3))
        delay(100)
        assertEquals(countAfterLeave, publications.size)

        coordinator.bind("profile", "account")
        accept(message("conversation.message.delta", "Running again", 4))
        coordinator.bind("other-profile", "other-account")
        assertEquals(listOf(target, target), cancelled)
        assertTrue(coordinator.sessions.value.isEmpty())
        accept(message("conversation.message.delta", "Old account", 5))
        delay(100)
        assertTrue(coordinator.sessions.value.isEmpty())
    }

    @Test fun leavingRetainsFinishedNotificationsWhileRemovingOnlyOngoingTargets() {
        val cancelled = mutableListOf<ReplyNotificationTarget>()
        coordinator = NativeConversationNotifications(context, scope, { session, alert -> publications += session to alert },
            cancel = { cancelled += it }, now = { clock })
        coordinator.bind("profile", "account")
        accept(message("conversation.message.completed", "Final", 1))
        accept(status("completed", 1))
        assertFalse(coordinator.sessions.value.single().isRunning)
        coordinator.leave()
        assertTrue(cancelled.isEmpty())
    }

    @Test fun longReplyBatchesMetadataAndBoundsFullScansWhileKeepingTerminalReplayFence() {
        val delegate = replyCompletionPreferences(context, "profile", "account")
        val preferences = RealtimeCountingPreferences(delegate)
        coordinator = NativeConversationNotifications(context, scope, { session, alert -> publications += session to alert },
            now = { clock }, throttleMillis = 60_000, metadataFlushMillis = 60_000,
            preferencesFor = { _, _ -> preferences })
        coordinator.bind("profile", "account")
        repeat(1_000) { revision -> accept(message("conversation.message.delta", "Snapshot $revision", revision.toLong())) }
        assertEquals("Snapshot 999", coordinator.sessions.value.single().latestReply)
        assertEquals("One initial native cleanup scan, not one scan per delta", 1, preferences.allReads)
        assertEquals("No per-token preference writes", 0, preferences.applies)

        accept(message("conversation.message.completed", "Final snapshot", 1_000))
        accept(status("completed", 1))
        assertTrue("Native and observer maintenance must both be bounded", preferences.allReads <= 2)
        assertTrue("Streaming writes must be batched before terminal durability", preferences.applies <= 2)
        assertEquals(1, publications.count { it.second })
        val count = publications.size
        coordinator = coordinator()
        coordinator.bind("profile", "account")
        accept(message("conversation.message.delta", "Old replay", 1_000))
        accept(status("completed", 2))
        assertEquals("A new process must honor the durable terminal fence", count, publications.size)
    }

    @Test fun periodicMaintenanceExpiresOldEventAndRevisionMetadata() {
        val preferences = RealtimeCountingPreferences(replyCompletionPreferences(context, "profile", "account"))
        val expiredAt = clock - 8L * 24 * 60 * 60 * 1_000
        preferences.edit().putLong("realtime.event.expired", expiredAt)
            .putLong("realtime.revisionTime.expired", expiredAt).putLong("realtime.revision.expired", 5).commit()
        coordinator = NativeConversationNotifications(context, scope, { _, _ -> }, now = { clock }, preferencesFor = { _, _ -> preferences })
        coordinator.bind("profile", "account")
        accept(message("conversation.message.delta", "Current", 1))
        assertFalse(preferences.contains("realtime.event.expired"))
        assertFalse(preferences.contains("realtime.revision.expired"))
        assertEquals(1, preferences.allReads)
        repeat(100) { index -> accept(message("conversation.message.delta", "Current $index", index + 2L)) }
        assertEquals(1, preferences.allReads)
        clock += 31L * 60 * 1_000
        accept(message("conversation.message.delta", "Later", 200))
        assertEquals(2, preferences.allReads)
    }

    @Test fun failureAndCancellationPersistBufferedGuardsBeforeTerminalPublishCanCrash() {
        listOf(NativeConversationStatus.FAILED, NativeConversationStatus.CANCELLED).forEach { outcome ->
            val preferences = replyCompletionPreferences(context, "profile", "account")
            preferences.edit().clear().commit()
            val originalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val crash = IllegalStateException("Simulated process death after terminal publication")
            val terminalSnapshots = mutableListOf<Map<String, *>>()
            val delta = message("conversation.message.delta", "Before terminal", 1)
            val terminal = if (outcome == NativeConversationStatus.FAILED) status("failed", 1)
                else event("conversation.generation.cancelled", """{"conversationId":"conversation","generationId":"generation"}""")
            val original = NativeConversationNotifications(context, originalScope, { session, _ ->
                if (session.status == outcome) {
                    terminalSnapshots += preferences.all
                    throw crash
                }
            }, now = { clock }, throttleMillis = 60_000, metadataFlushMillis = 60_000)
            try {
                original.bind("profile", "account")
                original.accept("profile", "account", delta)
                assertFalse("The delta must still be buffered before the terminal", preferences.contains("realtime.event." + replyDigest(delta.id!!)))
                try {
                    original.accept("profile", "account", terminal)
                    fail("Terminal publication must simulate process death before accept's trailing metadata flush")
                } catch (failure: IllegalStateException) {
                    assertSame(crash, failure)
                }
            } finally {
                // Pending jobs from the old process must not repair its disk state.
                originalScope.cancel()
            }

            val snapshotAtPublication = terminalSnapshots.single()
            assertTrue(snapshotAtPublication.containsKey("realtime.event." + replyDigest(delta.id!!)))
            assertTrue(snapshotAtPublication.containsKey("realtime.event." + replyDigest(terminal.id!!)))
            assertEquals(1L, snapshotAtPublication["realtime.revision." + replyDigest("body\u0000conversation\u0000reply")])
            assertTrue(snapshotAtPublication.containsKey("realtime.terminal." + replyDigest("conversation\u0000generation")))

            val replayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val replayPublications = mutableListOf<NativeConversationSession>()
            val restarted = NativeConversationNotifications(context, replayScope, { session, _ -> replayPublications += session },
                now = { clock }, throttleMillis = 60_000, metadataFlushMillis = 60_000)
            try {
                restarted.bind("profile", "account")
                restarted.accept("profile", "account", delta)
                restarted.accept("profile", "account", terminal)
                assertTrue("A completed terminal must not replay a running notification after a crash", replayPublications.none { it.isRunning })
                assertTrue(restarted.sessions.value.none { it.isRunning })
            } finally {
                replayScope.cancel()
            }
        }
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

    @Test @Config(sdk = [34, 35]) fun nativePublisherUpdatesStableConversationNotificationAndPublishesBubbleShortcut() {
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
        assertEquals(2, persons.size)
        assertEquals("Agent", persons.single { it.isBot }.name.toString())
        val localUser = persons.single { !it.isBot }
        assertEquals("我", localUser.name.toString())
        assertEquals(firstStyle.user.key, localUser.key)
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

    @Test fun legacyConversationPublisherShowsAdvancingLongTailsAndAReadableFinalExcerpt() {
        val publisher = AndroidReplyNotificationPublisher(context, ReplyNotificationSettings(context))
        val target = ReplyNotificationTarget("profile", "account", "conversation")
        val prefix = "A".repeat(6_000)
        val running = NativeConversationSession(target, "generation", "Conversation", NativeConversationStatus.RUNNING,
            prefix + "First stream ending", updatedAt = clock)
        val manager = context.getSystemService(NotificationManager::class.java)
        fun text(): String = (Notification.Builder.recoverBuilder(context, manager.activeNotifications.single().notification).style as Notification.MessagingStyle)
            .messages.single().text.toString()
        publisher.update(running, false)
        val first = text()
        publisher.update(running.copy(latestReply = prefix + "Later stream ending"), false)
        assertNotEquals(first, text())
        assertTrue(text().endsWith("Later stream ending"))
        publisher.update(running.copy(status = NativeConversationStatus.COMPLETED, latestReply = "Opening line. " + prefix + "Final complete ending"), true)
        assertTrue(text().startsWith("Opening line. "))
        assertTrue("Final excerpt tail: ${text().takeLast(120)}", text().contains("Final complete ending\n\n$COMPLETE_REPLY_HINT"))
        assertEquals(TERMINAL_REPLY_EXCERPT_CODE_POINTS, text().codePointCount(0, text().length))
    }

    @Test fun permissionAndPreferenceRecoveryRepublishOnlyStillActiveWork() = runBlocking {
        val runtimePermissionRequired = android.os.Build.VERSION.SDK_INT >= 33
        if (runtimePermissionRequired) shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val settings = ReplyNotificationSettings(context)
        val publisher = AndroidReplyNotificationPublisher(context, settings)
        coordinator = NativeConversationNotifications(context, scope, publisher::update, now = { clock }, throttleMillis = 40)
        coordinator.bind("profile", "account")
        accept(status("queued", 1))
        val manager = context.getSystemService(NotificationManager::class.java)
        if (runtimePermissionRequired) {
            assertTrue(manager.activeNotifications.isEmpty())
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            settings.refreshPermission()
            coordinator.refreshActive()
        }
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
    private fun presencePage(vararg ids: String, authoritative: Boolean = true, nextCursor: String? = null) =
        ConversationPage(ids.map { ConversationSummary(ConversationId(it), it, clock) }, nextCursor, authoritative = authoritative)
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

    private class RealtimeCountingPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        var allReads = 0
        var applies = 0
        override fun getAll(): MutableMap<String, *> { allReads++; return delegate.all }
        override fun edit(): SharedPreferences.Editor = CountingEditor(delegate.edit())
        private inner class CountingEditor(private val editor: SharedPreferences.Editor) : SharedPreferences.Editor by editor {
            override fun putLong(key: String, value: Long): SharedPreferences.Editor { editor.putLong(key, value); return this }
            override fun remove(key: String): SharedPreferences.Editor { editor.remove(key); return this }
            override fun apply() { applies++; editor.apply() }
        }
    }
}
