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

    @Test fun anotherReaderCannotReviveAnIdenticalCompletedReplyAfterDismissal() {
        val publisher = AndroidReplyNotificationPublisher(context, ReplyNotificationSettings(context))
        coordinator = NativeConversationNotifications(context, scope, { session, alert ->
            publications += session to alert
            publisher.update(session, alert)
        }, now = { clock }, throttleMillis = 0)
        coordinator.bind("profile", "account")
        accept(status("delivered", 1))
        accept(message("conversation.message.completed", "同轮最终回复", 1))
        accept(status("completed", 2))
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications.single()
        manager.cancel(notification.tag, notification.id)
        val count = publications.size

        // 发送流和重连事件页可用不同 eventId 转发同一份已完成正文。
        accept(message("conversation.message.completed", "同轮最终回复", 1))

        assertTrue("已处理的通知不能被另一读取者的相同终稿重新发布", manager.activeNotifications.isEmpty())
        assertEquals(count, publications.size)
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
            accept(requireNotNull(frame).at(clock - 600_000))
            assertTrue("首条历史事实不能发布通知", publications.isEmpty())
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
                original.accept(requireNotNull(delta))
                assertFalse("The delta must still be buffered before the terminal", preferences.contains("realtime.event." + replyDigest(requireNotNull(delta).eventId)))
                try {
                    original.accept(requireNotNull(terminal))
                    fail("Terminal publication must simulate process death before accept's trailing metadata flush")
                } catch (failure: IllegalStateException) {
                    assertSame(crash, failure)
                }
            } finally {
                // Pending jobs from the old process must not repair its disk state.
                originalScope.cancel()
            }

            val snapshotAtPublication = terminalSnapshots.single()
            assertTrue(snapshotAtPublication.containsKey("realtime.event." + replyDigest(requireNotNull(delta).eventId)))
            assertTrue(snapshotAtPublication.containsKey("realtime.event." + replyDigest(requireNotNull(terminal).eventId)))
            assertEquals(1L, snapshotAtPublication["realtime.revision." + replyDigest("body\u0000conversation\u0000reply")])
            assertTrue(snapshotAtPublication.containsKey("realtime.terminal." + replyDigest("conversation\u0000generation")))

            val replayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val replayPublications = mutableListOf<NativeConversationSession>()
            val restarted = NativeConversationNotifications(context, replayScope, { session, _ -> replayPublications += session },
                now = { clock }, throttleMillis = 60_000, metadataFlushMillis = 60_000)
            try {
                restarted.bind("profile", "account")
                restarted.accept(requireNotNull(delta))
                restarted.accept(requireNotNull(terminal))
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

    @Test @Config(sdk = [34]) fun nativePublisherUpdatesStableConversationNotificationAndPublishesBubbleShortcut() {
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
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            publisher.update(running, false)
            assertTrue(manager.activeNotifications.isEmpty())
        } else {
            publisher.update(running, false)
            assertEquals(1, manager.activeNotifications.size)
        }
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
        val hint = context.getString(com.openandroidintelligence.ui.resources.R.string.notif_reply_excerpt_full_hint)
        assertTrue("Final excerpt tail: ${text().takeLast(120)}", text().contains("Final complete ending\n\n$hint"))
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

    /**
     * 修订围栏必须带 Agent。
     *
     * 两个 Agent 的同名会话完全可能用同一个消息 ID；只按会话比较会让后一家
     * 更高的修订被前一家判成过期，正文与终态都被丢掉，提醒永远不来。
     */
    @Test fun anEqualOrHigherRevisionUnderAnotherAgentIsNotStale() = runBlocking {
        accept(agentMessage("hermes", "reply-1", "Hermes 回答", 5))
        accept(agentStatus("hermes", "user-1", "completed", 5))
        assertEquals(NativeConversationStatus.COMPLETED, coordinator.sessions.value.single().status)

        // Codex 用同一个会话与消息 ID，但修订从 1 开始：不能被 hermes 的 5 判成过期。
        accept(agentMessage("codex", "reply-1", "Codex 回答", 1))
        val codex = coordinator.sessions.value.first { it.target.agentId == "codex" }
        assertEquals("Codex 回答", codex.latestReply)
        accept(agentStatus("codex", "user-1", "completed", 1))
        val finished = coordinator.sessions.value.first { it.target.agentId == "codex" }
        assertEquals(NativeConversationStatus.COMPLETED, finished.status)
        assertEquals("Codex 回答", finished.latestReply)
    }

    @Test fun `类型化输入隔离同名端点和重放事件并保留附件提醒`() {
        val time = clock
        val first = notificationReply(time, endpoint = "host-a", eventId = "same-body")
        val second = notificationReply(time, endpoint = "host-b", eventId = "same-body",
            parts = listOf(com.openandroidintelligence.conversation.model.MessagePart.Attachment(
                com.openandroidintelligence.conversation.model.AttachmentDraftId("attachment"), "报告.pdf", "application/pdf", 12, null)))
        coordinator.accept(first)
        coordinator.accept(notificationStatus(time, endpoint = "host-a", eventId = "same-status"))
        coordinator.accept(second)
        coordinator.accept(notificationStatus(time, endpoint = "host-b", eventId = "same-status"))
        assertEquals(2, publications.count { it.second })
        assertEquals(setOf("host-a", "host-b"), publications.filter { it.second }.map { it.first.target.agentId }.toSet())
        assertEquals(context.getString(com.openandroidintelligence.ui.resources.R.string.notif_reply_attachment_placeholder),
            coordinator.sessions.value.single { it.target.agentId == "host-b" }.latestReply)
        val beforeReplay = publications.size
        coordinator = coordinator()
        coordinator.bind("profile", "account")
        coordinator.accept(first)
        coordinator.accept(second)
        coordinator.accept(notificationStatus(time, endpoint = "host-a"))
        coordinator.accept(notificationStatus(time, endpoint = "host-b"))
        assertEquals(beforeReplay, publications.size)
    }

    @Test fun `类型化输入不借用其他账号端点和轮次的正文并遵守删除围栏`() {
        val time = clock
        coordinator.accept(notificationReply(time, account = "other-account"))
        coordinator.accept(notificationReply(time, profile = "other-profile"))
        assertTrue(coordinator.sessions.value.isEmpty())
        coordinator.accept(notificationReply(time, generation = "old"))
        coordinator.accept(notificationStatus(time + 2, generation = "new"))
        assertTrue(publications.none { it.second })
        coordinator.accept(notificationReply(time + 1, generation = "new"))
        assertEquals(1, publications.count { it.second })
        val delete = com.openandroidintelligence.mobile.conversations.nativeNotificationInput("profile", "account", "endpoint",
            com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.ConversationsChanged("delete", time + 3,
                ConversationId("conversation"), deleted = true))!!
        coordinator.accept(delete)
        assertTrue(coordinator.sessions.value.isEmpty())
        val count = publications.size
        coordinator = coordinator()
        coordinator.bind("profile", "account")
        coordinator.accept(notificationReply(time + 4, generation = "later"))
        coordinator.accept(notificationStatus(time + 5, generation = "later"))
        assertEquals(count, publications.size)
        assertTrue(coordinator.sessions.value.isEmpty())
    }

    @Test fun `领域快照补正文只确认结束不能冒充成功提醒`() {
        coordinator.accept(notificationStatus(clock, status = com.openandroidintelligence.conversation.ports.AgentMessageStatus.DELIVERED))
        coordinator.requestReconciliation()
        val request = coordinator.reconciliationRequests().single()
        val body = (notificationReply(clock, text = "快照最终正文").event as
            com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.TimelineUpsert).message
        assertTrue(coordinator.reconcile(request, null, listOf(body)))
        assertEquals("快照最终正文", coordinator.sessions.value.single().latestReply)
        assertEquals(NativeConversationStatus.ENDED, coordinator.sessions.value.single().status)
        assertTrue("读取历史终稿不能补发成功通知", publications.none { it.second })
    }

    @Test fun `领域附件快照沿用占位与读取期间的代次围栏`() {
        coordinator.accept(notificationStatus(clock, status = com.openandroidintelligence.conversation.ports.AgentMessageStatus.DELIVERED))
        coordinator.requestReconciliation()
        val oldRequest = coordinator.reconciliationRequests().single()
        coordinator.accept(notificationReply(clock + 1, text = "更新的实时正文", completed = false))
        val attachment = (notificationReply(clock, parts = listOf(
            com.openandroidintelligence.conversation.model.MessagePart.Attachment(
                com.openandroidintelligence.conversation.model.AttachmentDraftId("snapshot-file"), "报告.pdf", "application/pdf", 5, null)
        )).event as com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.TimelineUpsert).message
        assertFalse("读取期间的事件更新使旧快照失效", coordinator.reconcile(oldRequest, null, listOf(attachment)))
        assertEquals("更新的实时正文", coordinator.sessions.value.single().latestReply)
        coordinator.requestReconciliation()
        assertTrue(coordinator.reconcile(coordinator.reconciliationRequests().single(), null,
            listOf(attachment.copy(timestamp = clock + 2, contentRevision = 2))))
        assertEquals(context.getString(com.openandroidintelligence.ui.resources.R.string.notif_reply_attachment_placeholder),
            coordinator.sessions.value.single().latestReply)
        assertTrue(publications.none { it.second })
    }

    // ==========================================
    // Feature 4: 实时通知生命周期与真实标题 (Tier 1 & Tier 2)
    // ==========================================

    @Test
    fun t1_F4_01_Completed_Cancels_Live_Update_And_Posts_Reply_Notification() {
        val settings = ReplyNotificationSettings(context)
        val publisher = AndroidReplyNotificationPublisher(context, settings)
        val target = ReplyNotificationTarget("profile", "account", "conversation-test-f4-01")
        val manager = context.getSystemService(NotificationManager::class.java)

        // 1. 运行态：设置 FLAG_ONGOING_EVENT
        val running = NativeConversationSession(target, "gen-1", "真实会话任务", NativeConversationStatus.RUNNING, "正在执行任务...", updatedAt = clock)
        publisher.update(running, false)
        val runningNotif = manager.activeNotifications.single()
        assertTrue("运行态必须包含常驻标志", runningNotif.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)

        // 2. 完成态：收到 COMPLETED，必须清除常驻通知并弹出可滑动清除完成通知
        val completed = NativeConversationSession(target, "gen-1", "真实会话任务", NativeConversationStatus.COMPLETED, "任务顺利完成！", updatedAt = clock)
        publisher.update(completed, true)
        val completedNotif = manager.activeNotifications.single()
        assertEquals("完成通知必须投递在 REPLY_CHANNEL_ID", REPLY_CHANNEL_ID, completedNotif.notification.channelId)
        assertEquals("完成态通知绝不能标记为 ongoing", 0, completedNotif.notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue("完成态通知必须设置 autoCancel（可滑动清除）", completedNotif.notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val style = Notification.Builder.recoverBuilder(context, completedNotif.notification).style as Notification.MessagingStyle
        assertEquals("真实会话任务", style.conversationTitle.toString())
    }

    @Test
    fun t1_F4_02_Error_Cancels_Live_Update_And_Posts_Reply_Notification() {
        val settings = ReplyNotificationSettings(context)
        val publisher = AndroidReplyNotificationPublisher(context, settings)
        val target = ReplyNotificationTarget("profile", "account", "conversation-test-f4-02")
        val manager = context.getSystemService(NotificationManager::class.java)

        val running = NativeConversationSession(target, "gen-2", "执行失败测试", NativeConversationStatus.RUNNING, "处理中...", updatedAt = clock)
        publisher.update(running, false)
        assertTrue(manager.activeNotifications.single().notification.flags and Notification.FLAG_ONGOING_EVENT != 0)

        // 报错态 FAILED：常驻通知取消，弹出非 ongoing 错误提醒
        val failed = NativeConversationSession(target, "gen-2", "执行失败测试", NativeConversationStatus.FAILED, "发生异常，执行中断", updatedAt = clock)
        publisher.update(failed, true)
        val failedNotif = manager.activeNotifications.single()
        assertEquals("失败通知必须投递在 REPLY_CHANNEL_ID", REPLY_CHANNEL_ID, failedNotif.notification.channelId)
        assertEquals("失败态通知绝不能标记为 ongoing", 0, failedNotif.notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue("失败态通知必须设置 autoCancel", failedNotif.notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun t1_F4_03_Cancelled_Cancels_Live_Update_And_Posts_Reply_Notification() {
        val settings = ReplyNotificationSettings(context)
        val publisher = AndroidReplyNotificationPublisher(context, settings)
        val target = ReplyNotificationTarget("profile", "account", "conversation-test-f4-03")
        val manager = context.getSystemService(NotificationManager::class.java)

        val running = NativeConversationSession(target, "gen-3", "用户打断测试", NativeConversationStatus.RUNNING, "正在生成...", updatedAt = clock)
        publisher.update(running, false)
        assertTrue(manager.activeNotifications.single().notification.flags and Notification.FLAG_ONGOING_EVENT != 0)

        // 打断态 CANCELLED：常驻通知取消，弹出非 ongoing 打断提醒
        val cancelled = NativeConversationSession(target, "gen-3", "用户打断测试", NativeConversationStatus.CANCELLED, "已由用户取消", updatedAt = clock)
        publisher.update(cancelled, true)
        val cancelledNotif = manager.activeNotifications.single()
        assertEquals(REPLY_CHANNEL_ID, cancelledNotif.notification.channelId)
        assertEquals(0, cancelledNotif.notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue(cancelledNotif.notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun t1_F4_05_Real_Title_In_Notification() {
        val realTitle = "端到端系统架构设计方案"
        coordinator = NativeConversationNotifications(
            context = context,
            scope = scope,
            publish = { session, alert -> publications += session to alert },
            titleFor = { realTitle },
            now = { clock },
            throttleMillis = 0,
        )
        coordinator.bind("profile", "account")

        accept(message("conversation.message.delta", "方案前缀", 1))
        val currentSession = coordinator.sessions.value.single()
        assertEquals("通知会话标题必须采用真实标题", realTitle, currentSession.title)
        assertFalse("标题绝不可包含编码ID", currentSession.title.contains("conversation"))

        accept(message("conversation.message.completed", "最终答案", 2))
        accept(status("completed", 3))
        assertEquals(realTitle, publications.last().first.title)
    }

    @Test
    fun t2_F4_01_Title_Fallback_Does_Not_Leak_Conversation_Id() {
        // 当 titleFor 返回 null 时，必须回退到本地化默认标题，绝不可截取并泄露会话 ID
        val customTargetId = "base64EncodedOpaqueId123456"
        val customInput = notificationFact(
            "profile",
            "account",
            "fact-1",
            clock,
            "conversation.message.delta",
            """{"conversationId":"$customTargetId","messageId":"msg-1","sender":"assistant","parts":[{"type":"text","text":"你好"}],"revision":1}""",
        )
        coordinator = NativeConversationNotifications(
            context = context,
            scope = scope,
            publish = { session, alert -> publications += session to alert },
            titleFor = { null },
            now = { clock },
            throttleMillis = 0,
        )
        coordinator.bind("profile", "account")
        accept(customInput)

        val session = coordinator.sessions.value.single()
        assertFalse("回退标题绝不可包含会话ID的后12位", session.title.contains(customTargetId.takeLast(12)))
        assertFalse("回退标题绝不可包含会话ID", session.title.contains(customTargetId))
        val defaultTitle = context.getString(com.openandroidintelligence.ui.resources.R.string.notif_conv_default_title)
        assertEquals("未获取到标题时应回退为本地化默认标题", defaultTitle, session.title)
    }

    @Test
    fun t2_F4_02_Title_With_Special_Characters_And_Emoji() {
        val specialTitle = "🔥 架构设计 & PR #42 [v2.0] / (Release) 🎯"
        coordinator = NativeConversationNotifications(
            context = context,
            scope = scope,
            publish = { session, alert -> publications += session to alert },
            titleFor = { specialTitle },
            now = { clock },
            throttleMillis = 0,
        )
        coordinator.bind("profile", "account")

        accept(message("conversation.message.completed", "完成", 1))
        accept(status("completed", 2))
        assertEquals(specialTitle, coordinator.sessions.value.single().title)
    }

    @Test
    fun t2_F4_03_Consecutive_Generations_Transition_Lifecycle() {
        val settings = ReplyNotificationSettings(context)
        val publisher = AndroidReplyNotificationPublisher(context, settings)
        val target = ReplyNotificationTarget("profile", "account", "multi-turn-target")
        val manager = context.getSystemService(NotificationManager::class.java)

        // Turn 1: RUNNING -> COMPLETED
        publisher.update(NativeConversationSession(target, "turn-1", "第一轮", NativeConversationStatus.RUNNING, "思考中", updatedAt = clock), false)
        assertTrue(manager.activeNotifications.single().notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        publisher.update(NativeConversationSession(target, "turn-1", "第一轮", NativeConversationStatus.COMPLETED, "回答1", updatedAt = clock), true)
        assertEquals(0, manager.activeNotifications.single().notification.flags and Notification.FLAG_ONGOING_EVENT)

        // Turn 2: RUNNING -> FAILED
        publisher.update(NativeConversationSession(target, "turn-2", "第二轮", NativeConversationStatus.RUNNING, "再次生成", updatedAt = clock + 1), false)
        assertTrue(manager.activeNotifications.single().notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        publisher.update(NativeConversationSession(target, "turn-2", "第二轮", NativeConversationStatus.FAILED, "执行失败", updatedAt = clock + 2), true)
        assertEquals(0, manager.activeNotifications.single().notification.flags and Notification.FLAG_ONGOING_EVENT)

        // Turn 3: RUNNING -> CANCELLED
        publisher.update(NativeConversationSession(target, "turn-3", "第三轮", NativeConversationStatus.RUNNING, "第三次生成", updatedAt = clock + 3), false)
        assertTrue(manager.activeNotifications.single().notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        publisher.update(NativeConversationSession(target, "turn-3", "第三轮", NativeConversationStatus.CANCELLED, "打断", updatedAt = clock + 4), true)
        assertEquals(0, manager.activeNotifications.single().notification.flags and Notification.FLAG_ONGOING_EVENT)
    }

    @Test fun t3_01_DualEventStandardAndOutOfOrderCompleteCleanlyWithoutStuckRunning() {
        coordinator = coordinator()
        coordinator.bind("profile", "account")
        val target = ReplyNotificationTarget("profile", "account", "conversation")

        // 标准双事件顺序：delta -> completed -> status(COMPLETED)
        accept(status("delivered", 1, "gen-std"))
        clock += 50
        accept(message("conversation.message.delta", "流式生成中...", 1, "gen-std"))
        assertEquals(NativeConversationStatus.RUNNING, coordinator.sessions.value.single().status)

        clock += 50
        accept(message("conversation.message.completed", "标准顺序最终回复", 2, "gen-std"))
        clock += 10
        accept(status("completed", 3, "gen-std"))

        val afterStd = coordinator.sessions.value.single()
        assertEquals(NativeConversationStatus.COMPLETED, afterStd.status)
        assertEquals("标准顺序最终回复", afterStd.latestReply)
        val stdAlerts = publications.filter { it.first.target == target && it.first.status == NativeConversationStatus.COMPLETED && it.second }
        assertEquals(1, stdAlerts.size)
        assertEquals("标准顺序最终回复", stdAlerts.single().first.latestReply)

        // 乱序到达：status(COMPLETED) 先到，completed 后到
        publications.clear()
        clock += 100
        accept(status("delivered", 10, "gen-ooo"))
        clock += 50
        accept(status("completed", 11, "gen-ooo"))
        clock += 10
        accept(message("conversation.message.completed", "乱序完整最终回复", 12, "gen-ooo"))

        val afterOoo = coordinator.sessions.value.single()
        assertEquals(NativeConversationStatus.COMPLETED, afterOoo.status)
        assertEquals("乱序完整最终回复", afterOoo.latestReply)
        val oooAlerts = publications.filter { it.first.target == target && it.first.status == NativeConversationStatus.COMPLETED && it.second }
        assertEquals(1, oooAlerts.size)
        assertEquals("乱序完整最终回复", oooAlerts.single().first.latestReply)
    }

    @Test fun t3_02_MissingOrMismatchedGenerationIdsNeverLeaveGhostEmptyRounds() {
        coordinator = NativeConversationNotifications(
            context,
            scope,
            { session, alert -> publications += session to alert },
            now = { clock },
            throttleMillis = 0,
        )
        coordinator.bind("profile", "account")

        // 场景 A：后端完全省略 generationId，且 DELIVERED 与 COMPLETED 携带的后端 messageId 不同（通过 clientMessageId 对齐）
        accept(status("delivered", 1, generation = null, messageId = "tmp-u1", clientMessageId = "cid-missing"))
        accept(message("conversation.message.delta", "流式碎片", 1, generation = null))
        accept(message("conversation.message.completed", "缺失generationId的最终回复", 2, generation = null))
        accept(status("completed", 3, generation = null, messageId = "srv-u1", clientMessageId = "cid-missing"))

        val afterMissing = coordinator.sessions.value.single()
        assertEquals(NativeConversationStatus.COMPLETED, afterMissing.status)
        assertEquals("缺失generationId的最终回复", afterMissing.latestReply)
        assertEquals(1, publications.count { it.first.status == NativeConversationStatus.COMPLETED && it.second })

        // 场景 B：仅回复缺失 generationId（status 带 gen-only，reply 省略 generationId）
        publications.clear()
        clock += 50
        accept(status("delivered", 10, generation = "gen-only", messageId = "u2", clientMessageId = "cid-half"))
        accept(message("conversation.message.completed", "单边省略generationId最终回复", 11, generation = null))
        accept(status("completed", 12, generation = "gen-only", messageId = "u2", clientMessageId = "cid-half"))

        val afterHalfMissing = coordinator.sessions.value.single()
        assertEquals(NativeConversationStatus.COMPLETED, afterHalfMissing.status)
        assertEquals("单边省略generationId最终回复", afterHalfMissing.latestReply)
        assertEquals(1, publications.count { it.first.status == NativeConversationStatus.COMPLETED && it.second })

        // 场景 C：两类事件携带不同的 generationId（如本地预生成 req-turn 与服务端 turn-real）
        publications.clear()
        clock += 50
        accept(status("queued", 20, generation = "req-turn", messageId = "u3", clientMessageId = "cid-mismatch"))
        accept(message("conversation.message.delta", "跨ID流式片段", 21, generation = "turn-real")
            ?.copy(resolvedGenerationId = "req-turn"))
        accept(message("conversation.message.completed", "跨generationId最终回复", 22, generation = "turn-real")
            ?.copy(resolvedGenerationId = "req-turn"))
        accept(status("completed", 23, generation = "turn-real", messageId = "srv-u3", clientMessageId = "cid-mismatch"))

        val afterMismatch = coordinator.sessions.value.single()
        assertEquals(NativeConversationStatus.COMPLETED, afterMismatch.status)
        assertEquals("跨generationId最终回复", afterMismatch.latestReply)
        assertEquals(1, publications.count { it.first.status == NativeConversationStatus.COMPLETED && it.second })

        // 场景 D：无 resolvedGenerationId 提示时，queued(req-raw) -> completed(turn-raw) -> status(completed, req-raw)
        publications.clear()
        clock += 50
        accept(status("delivered", 30, generation = "req-raw", messageId = "u4", clientMessageId = "cid-raw"))
        accept(message("conversation.message.completed", "未带别名的跨ID最终回复", 31, generation = "turn-raw"))
        accept(status("completed", 32, generation = "req-raw", messageId = "srv-u4", clientMessageId = "cid-raw"))

        val afterRawMismatch = coordinator.sessions.value.single()
        assertEquals(NativeConversationStatus.COMPLETED, afterRawMismatch.status)
        assertEquals("未带别名的跨ID最终回复", afterRawMismatch.latestReply)
        assertEquals(1, publications.count { it.first.status == NativeConversationStatus.COMPLETED && it.second })
    }

    @Test fun t3_03_TerminalBarrierBlocksLaggingReconcileAndReconnectReplay() {
        coordinator = NativeConversationNotifications(
            context,
            scope,
            { session, alert -> publications += session to alert },
            now = { clock },
            throttleMillis = 0,
        )
        coordinator.bind("profile", "account")

        // 先在 RUNNING 状态抓取一个尚未过期的 reconcile 请求
        accept(status("queued", 1, generation = "gen-barrier", messageId = "u1", clientMessageId = "cid-barrier"))
        coordinator.requestReconciliation()
        val staleRequest = coordinator.reconciliationRequests().single()

        // 对话完成并持久化终态
        clock += 50
        accept(message("conversation.message.completed", "已终结的最终答案", 2, generation = "gen-barrier"))
        accept(status("completed", 3, generation = "gen-barrier", messageId = "u1", clientMessageId = "cid-barrier"))
        assertEquals(NativeConversationStatus.COMPLETED, coordinator.sessions.value.single().status)
        val completedPubCount = publications.size

        // 1. 滞后的 reconcile 带着 running 快照返回：必须被单调终态屏障拦截
        assertFalse(coordinator.reconcile(staleRequest, "gen-barrier" to "running", emptyList()))
        assertEquals(NativeConversationStatus.COMPLETED, coordinator.sessions.value.single().status)
        assertEquals("已终结的最终答案", coordinator.sessions.value.single().latestReply)
        assertEquals(completedPubCount, publications.size)

        // 2. 重连回放同一轮次的旧事件（delta / queued / delivered）：绝不可复活进度态或清空回复
        clock += 50
        accept(message("conversation.message.delta", "旧流式重放", 1, generation = "gen-barrier"))
        accept(status("queued", 1, generation = "gen-barrier", messageId = "u1", clientMessageId = "cid-barrier"))
        accept(status("delivered", 2, generation = "gen-barrier", messageId = "u1", clientMessageId = "cid-barrier"))
        assertEquals(NativeConversationStatus.COMPLETED, coordinator.sessions.value.single().status)
        assertEquals("已终结的最终答案", coordinator.sessions.value.single().latestReply)
        assertEquals(completedPubCount, publications.size)

        // 3. 即使期间断线标记也不会把已终结会话拉回 DISCONNECTED，且重连后未知旧 running 快照不复活已终结会话
        coordinator.disconnect()
        assertEquals(NativeConversationStatus.COMPLETED, coordinator.sessions.value.single().status)
    }

    @Test fun t3_04_DisconnectionFollowedByLaggingReconcileCannotResurrectTerminalRound() {
        coordinator = NativeConversationNotifications(
            context,
            scope,
            { session, alert -> publications += session to alert },
            now = { clock },
            throttleMillis = 0,
        )
        coordinator.bind("profile", "account")

        // 先启动轮次，断线后收到 DISCONNECTED 态下的 reconcile 请求，接着终态补齐
        accept(status("queued", 1, generation = "gen-disc", messageId = "u1", clientMessageId = "cid-disc"))
        coordinator.disconnect()
        assertEquals(NativeConversationStatus.DISCONNECTED, coordinator.sessions.value.single().status)

        // 轮次已拿到终态消息与完成事件（比如恢复窗口内到达）
        accept(message("conversation.message.completed", "断线前完成的回答", 2, generation = "gen-disc"))
        accept(status("completed", 3, generation = "gen-disc", messageId = "u1", clientMessageId = "cid-disc"))
        assertEquals(NativeConversationStatus.COMPLETED, coordinator.sessions.value.single().status)

        // 若构造对同一轮次仍声称 running 的滞后快照请求，也绝不可覆盖终态
        val fakeLaggingRequest = NotificationReconciliationRequest(
            session = coordinator.sessions.value.single().copy(status = NativeConversationStatus.DISCONNECTED),
            revision = 999L,
        )
        assertFalse(coordinator.reconcile(fakeLaggingRequest, "gen-disc" to "running", emptyList()))
        assertEquals(NativeConversationStatus.COMPLETED, coordinator.sessions.value.single().status)
        assertEquals("断线前完成的回答", coordinator.sessions.value.single().latestReply)
    }

    @Test fun t3_05_ConsecutiveTurnsCleanlyRestartAndExitWithoutLeakingPreviousReply() {
        coordinator = NativeConversationNotifications(
            context,
            scope,
            { session, alert -> publications += session to alert },
            now = { clock },
            throttleMillis = 0,
        )
        coordinator.bind("profile", "account")

        // 第 1 轮完成
        accept(status("delivered", 1, "gen-turn-1", messageId = "u1", clientMessageId = "cid-1"))
        accept(message("conversation.message.completed", "第一轮结果", 2, "gen-turn-1", messageId = "r1"))
        accept(status("completed", 3, "gen-turn-1", messageId = "u1", clientMessageId = "cid-1"))
        assertEquals(NativeConversationStatus.COMPLETED, coordinator.sessions.value.single().status)
        assertEquals("第一轮结果", coordinator.sessions.value.single().latestReply)

        // 同一会话发起第 2 轮提问：必须以新轮次重新进入 QUEUED/RUNNING 且清空上一轮文本残留
        clock += 100
        accept(status("queued", 10, "gen-turn-2", messageId = "u2", clientMessageId = "cid-2"))
        val turn2Queued = coordinator.sessions.value.single()
        assertEquals(NativeConversationStatus.QUEUED, turn2Queued.status)
        assertEquals("gen-turn-2", turn2Queued.generationId)
        assertEquals("", turn2Queued.latestReply)

        // 第 2 轮流式与终态
        accept(message("conversation.message.delta", "第二轮流式", 11, "gen-turn-2", messageId = "r2"))
        assertEquals(NativeConversationStatus.RUNNING, coordinator.sessions.value.single().status)
        assertEquals("第二轮流式", coordinator.sessions.value.single().latestReply)

        accept(message("conversation.message.completed", "第二轮最终答案", 12, "gen-turn-2", messageId = "r2"))
        accept(status("completed", 13, "gen-turn-2", messageId = "u2", clientMessageId = "cid-2"))
        val turn2Completed = coordinator.sessions.value.single()
        assertEquals(NativeConversationStatus.COMPLETED, turn2Completed.status)
        assertEquals("第二轮最终答案", turn2Completed.latestReply)
        assertEquals(2, publications.count { it.first.status == NativeConversationStatus.COMPLETED && it.second })
    }

    @Test fun t3_06_CrossKeyDeltaThenStateFirstCompletedDoesNotResurrectRunningWhenFinalBodyArrives() {
        coordinator = NativeConversationNotifications(
            context,
            scope,
            { session, alert -> publications += session to alert },
            now = { clock },
            throttleMillis = 0,
        )
        coordinator.bind("profile", "account")

        // 1. status(delivered, req) -> delta(req) -> status(completed, req) -> completed(turn)
        accept(status("delivered", 1, generation = "req-cross", messageId = "u1", clientMessageId = "cid-cross"))
        accept(message("conversation.message.delta", "流式半截文本", 1, generation = "req-cross", messageId = "r-cross"))
        accept(status("completed", 2, generation = "req-cross", messageId = "u1", clientMessageId = "cid-cross"))
        assertEquals(NativeConversationStatus.COMPLETED, coordinator.sessions.value.single().status)

        accept(message("conversation.message.completed", "迟到不同key最终回复", 3, generation = "turn-cross", messageId = "r-cross"))
        val afterCross = coordinator.sessions.value.single()
        assertEquals("跨key终稿绝不能复活成 RUNNING", NativeConversationStatus.COMPLETED, afterCross.status)
        assertFalse(afterCross.isRunning)
        assertEquals("迟到不同key最终回复", afterCross.latestReply)
        assertEquals(1, publications.count { it.first.status == NativeConversationStatus.COMPLETED && it.second })

        // 2. delta(req2) -> completed(turn2,同messageId) -> status(completed, req2)
        publications.clear()
        clock += 50
        accept(message("conversation.message.delta", "无状态流式片段", 10, generation = "req-same-msg", messageId = "r-same"))
        accept(message("conversation.message.completed", "同消息跨key最终正文", 11, generation = "turn-same-msg", messageId = "r-same"))
        accept(status("completed", 12, generation = "req-same-msg", messageId = "u2", clientMessageId = "cid-same-msg"))
        val afterSameMsg = coordinator.sessions.value.single()
        assertEquals(NativeConversationStatus.COMPLETED, afterSameMsg.status)
        assertEquals("同消息跨key最终正文", afterSameMsg.latestReply)
        assertEquals(1, publications.count { it.first.status == NativeConversationStatus.COMPLETED && it.second })
    }

    @Test fun t3_07_ClockSkewAndSameMillisecondConcurrentRoundArePreserved() {
        coordinator = NativeConversationNotifications(
            context,
            scope,
            { session, alert -> publications += session to alert },
            now = { clock },
            throttleMillis = 0,
        )
        coordinator.bind("profile", "account")

        // 第 1 轮在本地较大时间戳完成
        val serverBase = clock - 1_000L
        accept(notificationFact("profile", "account", "ev-clk-1", clock, "conversation.message.status",
            """{"conversationId":"conversation","messageId":"u1","clientMessageId":"c1","generationId":"g1","status":"delivered","revision":1}"""))
        accept(notificationFact("profile", "account", "ev-clk-2", clock + 50, "conversation.message.completed",
            """{"conversationId":"conversation","messageId":"r1","generationId":"g1","sender":"assistant","parts":[{"type":"text","text":"轮1结果"}],"revision":2}"""))
        accept(notificationFact("profile", "account", "ev-clk-3", clock + 100, "conversation.message.status",
            """{"conversationId":"conversation","messageId":"u1","clientMessageId":"c1","generationId":"g1","status":"completed","revision":3}"""))

        // 第 2 轮来自远端服务端较早时钟的新轮次，绝不能被 staleUnknownRound / staleReopen 误丢
        accept(notificationFact("profile", "account", "ev-clk-4", serverBase, "conversation.message.status",
            """{"conversationId":"conversation","messageId":"u2","clientMessageId":"c2","generationId":"g2-skew","status":"queued","revision":1}"""))
        assertEquals(NativeConversationStatus.QUEUED, coordinator.sessions.value.single().status)
        assertEquals("g2-skew", coordinator.sessions.value.single().generationId)

        // 同毫秒并发新轮次在旧轮完成时绝不能被 withRemainingRounds 误删
        val concurrentTs = clock + 200
        accept(notificationFact("profile", "account", "ev-clk-4b", concurrentTs, "conversation.message.delta",
            """{"conversationId":"conversation","messageId":"r2","generationId":"g2-skew","sender":"assistant","parts":[{"type":"text","text":"轮2进行中"}],"revision":1}"""))
        accept(notificationFact("profile", "account", "ev-clk-5", concurrentTs, "conversation.message.delta",
            """{"conversationId":"conversation","messageId":"r3","generationId":"g3-same-ms","sender":"assistant","parts":[{"type":"text","text":"同毫秒并发正文"}],"revision":1}"""))
        accept(notificationFact("profile", "account", "ev-clk-6", concurrentTs, "conversation.message.status",
            """{"conversationId":"conversation","messageId":"u2","clientMessageId":"c2","generationId":"g2-skew","status":"completed","revision":2}"""))
        val remaining = coordinator.sessions.value.single()
        assertEquals("g3-same-ms", remaining.generationId)
        assertEquals(NativeConversationStatus.RUNNING, remaining.status)
        assertEquals("同毫秒并发正文", remaining.latestReply)
    }

    @Test fun t3_08_EmptyPreviousCompletionDoesNotSwallowNewTurnStartingWithBodyAndUpgradedFenceHolds() {
        val prefs = replyCompletionPreferences(context, "profile", "account")
        val target = ReplyNotificationTarget("profile", "account", "conversation")
        val legacyOldDigest = replyDigest("legacy\u0000${target.identityKey()}\u0000u-legacy-old\u0000cid-legacy-old")
        prefs.edit().putLong("realtime.terminal.$legacyOldDigest", clock - 1_000L).commit()

        coordinator = NativeConversationNotifications(
            context,
            scope,
            { session, alert -> publications += session to alert },
            now = { clock },
            throttleMillis = 0,
        )
        coordinator.bind("profile", "account")

        // 1. 升级前已终结的 legacy 轮次在重放时不得重新进入 RUNNING
        accept(status("delivered", 1, generation = null, messageId = "u-legacy-old", clientMessageId = "cid-legacy-old"))
        assertTrue(coordinator.sessions.value.isEmpty())

        // 2. 第 1 轮以无正文终态结束，随后第 2 轮直接先来 REPLY_COMPLETED 再来 status(COMPLETED)：不得并入第 1 轮
        accept(status("delivered", 2, generation = "turn-empty", messageId = "u1", clientMessageId = "cid-1"))
        accept(status("completed", 3, generation = "turn-empty", messageId = "u1", clientMessageId = "cid-1"))
        assertEquals(NativeConversationStatus.COMPLETED, coordinator.sessions.value.single().status)

        clock += 50
        accept(message("conversation.message.completed", "新轮次直达正文", 4, generation = "turn-new", messageId = "r-new"))
        assertEquals("turn-new", coordinator.sessions.value.single().generationId)
        accept(status("completed", 5, generation = "turn-new", messageId = "u2", clientMessageId = "cid-2"))
        val finalSession = coordinator.sessions.value.single()
        assertEquals("turn-new", finalSession.generationId)
        assertEquals(NativeConversationStatus.COMPLETED, finalSession.status)
        assertEquals("新轮次直达正文", finalSession.latestReply)
        assertEquals(1, publications.count { it.first.generationId == "turn-new" && it.second })
    }

    @Test fun unmatchedLegacyCompletionStaysOnOwnRoundAndDoesNotCloseActiveLegacyTurn() {
        val target = ReplyNotificationTarget("profile", "account", "conversation")
        coordinator = NativeConversationNotifications(
            context,
            scope,
            { session, alert -> publications += session to alert },
            now = { clock },
            throttleMillis = 0,
        )
        coordinator.bind("profile", "account")

        // 先发起 Turn B（尚未流式输出正文）
        accept(status("queued", 10, generation = null, messageId = "user-B", clientMessageId = "client-B"))
        assertTrue(coordinator.sessions.value.single().isRunning)

        // Turn A 迟到的未匹配 COMPLETED（无 generationId 且消息标识完全不属于 B）绝不能关闭 B 或落盘 B 的终态围栏
        accept(status("completed", 11, generation = null, messageId = "user-A", clientMessageId = "client-A"))
        val afterTurnA = coordinator.sessions.value.single()
        assertTrue("未匹配的旧轮次 COMPLETED 不得终止当前活跃 Turn B", afterTurnA.isRunning)
        assertEquals(0, publications.count { it.second })

        // 再发带明确正文与 clientMessageId 的 Turn B-2，验证有正文与不同 clientMessageId 场景同样不被 Turn A-2 提前终结
        accept(status("delivered", 20, generation = null, messageId = "user-B2", clientMessageId = "client-B2"))
        accept(message("conversation.message.delta", "Turn B2 流式思考", 21, generation = null))
        accept(status("completed", 22, generation = null, messageId = "user-A2", clientMessageId = "client-A2"))
        val stillRunningB2 = coordinator.sessions.value.single()
        assertTrue(stillRunningB2.isRunning)
        assertEquals("Turn B2 流式思考", stillRunningB2.latestReply)
        assertEquals(0, publications.count { it.second })

        // Turn B2 自身完成正文与匹配 COMPLETED 到达后，应干净退出并触发 1 次通知
        accept(message("conversation.message.completed", "Turn B2 最终回复", 23, generation = null))
        accept(status("completed", 24, generation = null, messageId = "user-B2", clientMessageId = "client-B2"))
        val done = coordinator.sessions.value.single()
        assertFalse(done.isRunning)
        assertEquals(NativeConversationStatus.COMPLETED, done.status)
        assertEquals("Turn B2 最终回复", done.latestReply)
        assertEquals(1, publications.count { it.first.status == NativeConversationStatus.COMPLETED && it.second })
        assertTrue(replyCompletionPreferences(context, "profile", "account").contains(
            "realtime.terminal." + replyDigest("legacy\u0000${target.identityKey()}\u0000client-B2")))
    }

    @Test fun reconciliationRequestsFilterByAgentBeforeBatchAndUnknownActivityPreservesRunning() {
        coordinator = NativeConversationNotifications(
            context,
            scope,
            { session, alert -> publications += session to alert },
            now = { clock },
            throttleMillis = 0,
        )
        coordinator.bind("profile", "account")

        // 先在其他端点制造 10 个已到期的 RUNNING 会话（超过单批 8 条上限），再到选中端点创建 1 个会话
        for (i in 1..10) {
            accept(notificationFact("profile", "account", "other-ev-$i", clock, "conversation.message.status",
                """{"agentId":"other-agent","conversationId":"conv-other-$i","messageId":"u-$i","clientMessageId":"c-$i","generationId":"g-$i","status":"delivered","revision":1,"errorCode":null}"""))
        }
        accept(notificationFact("profile", "account", "sel-ev", clock, "conversation.message.status",
            """{"agentId":"selected-agent","conversationId":"conv-selected","messageId":"u-sel","clientMessageId":"c-sel","generationId":"g-sel","status":"delivered","revision":1,"errorCode":null}"""))

        clock += 20_000L
        val selectedBatch = coordinator.reconciliationRequests("selected-agent")
        assertEquals(1, selectedBatch.size)
        assertEquals("conv-selected", selectedBatch.single().session.target.conversationId)

        // 其他端点的配额/下次重试时间未被 selected-agent 的批次消耗
        val otherBatch = coordinator.reconciliationRequests("other-agent")
        assertEquals(8, otherBatch.size)

        // 当快照缺少活跃轮次元数据（activityKnown=false）且仅传 null 时，进行中会话必须保持 RUNNING 且不落盘终态围栏
        val req = selectedBatch.single()
        assertTrue(coordinator.reconcile(req, null, emptyList(), activityKnown = false))
        val selectedSession = coordinator.sessions.value.first { it.target.conversationId == "conv-selected" }
        assertTrue(selectedSession.isRunning)
        assertEquals(NativeConversationStatus.RUNNING, selectedSession.status)
        val durable = replyDigest("${selectedSession.target.identityKey()}\u0000g-sel")
        assertFalse(replyCompletionPreferences(context, "profile", "account").contains("realtime.terminal.$durable"))
    }

    private fun agentMessage(agent: String, messageId: String, text: String, revision: Long) = event(
        "conversation.message.completed",
        """{"agentId":"$agent","conversationId":"conversation","messageId":"$messageId","generationId":"generation","sender":"assistant","parts":[{"type":"text","text":"$text"}],"revision":$revision}""",
    )

    private fun agentStatus(agent: String, messageId: String, status: String, revision: Long) = event(
        "conversation.message.status",
        """{"agentId":"$agent","conversationId":"conversation","messageId":"$messageId","clientMessageId":"client-$agent","generationId":"generation","status":"$status","revision":$revision,"errorCode":null}""",
    )

    private fun coordinator() = NativeConversationNotifications(context, scope, { session, alert -> publications += session to alert }, now = { clock }, throttleMillis = 40)
    private fun presencePage(vararg ids: String, authoritative: Boolean = true, nextCursor: String? = null) =
        ConversationPage(ids.map { ConversationSummary(ConversationId(it), it, clock) }, nextCursor, authoritative = authoritative)
    private fun accept(input: NativeNotificationInput?) { input?.let(coordinator::accept) }
    private fun message(type: String, text: String, revision: Long, generation: String? = "generation", sender: String = "assistant", messageId: String = "reply", channel: String? = null) = event(type,
        """{"conversationId":"conversation","messageId":"$messageId",${generationField(generation)}"sender":"$sender","parts":[{"type":"text","text":"$text"}],"revision":$revision${channel?.let { ",\"channel\":\"$it\"" }.orEmpty()}}""")
    private fun status(
        status: String,
        revision: Long,
        generation: String? = "generation",
        messageId: String = "user-${generation ?: "legacy"}",
        clientMessageId: String = "client-${generation ?: "legacy"}",
    ) = event("conversation.message.status",
        """{"conversationId":"conversation","messageId":"$messageId","clientMessageId":"$clientMessageId",${generationField(generation)}"status":"$status","revision":$revision,"errorCode":${if (status == "failed") "\"AGENT_UNAVAILABLE\"" else "null"}}""")
    private fun generationField(generation: String?) = generation?.let { "\"generationId\":\"$it\"," }.orEmpty()
    private fun event(type: String, payload: String): NativeNotificationInput? {
        clock++
        return notificationFact("profile", "account", "realtime-${++sequence}", clock, type, payload)
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
