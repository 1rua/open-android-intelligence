package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.conversations.ConversationClient
import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.GatewayByteTransport
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.WireRequest
import com.openandroidintelligence.gateway.http.WireResponse
import com.openandroidintelligence.gateway.schema.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationConversationReconcilerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var notifications: NativeConversationNotifications
    private lateinit var reconciler: NotificationConversationReconciler
    private val transport = Transport()
    private val publications = mutableListOf<Pair<NativeConversationSession, Boolean>>()
    private var clock = System.currentTimeMillis()
    private var sequence = 0

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        replyCompletionPreferences(context, "profile", "account").edit().clear().commit()
        notifications = NativeConversationNotifications(context, scope, { session, alert -> publications += session to alert },
            now = { clock }, throttleMillis = 60_000)
        notifications.bind("profile", "account")
        val client = ConversationClient(GatewayHttpClient(GatewayProfile("account", "device", "session", "https://gateway.example"),
            transport, { ByteArray(64) }, InMemoryEventCursorStore()))
        reconciler = NotificationConversationReconciler(notifications, client)
    }

    @After fun close() { scope.cancel() }

    @Test fun drainsEveryTimelinePageBeforeApplyingIdleAndCorrelatesByUserMessageIdentity() = runBlocking {
        status("delivered", 1)
        transport.pages[null] = page(listOf(wire("old-user", "user", "Old question", clock - 1_000),
            wire("old-reply", "assistant", "Old reply", clock - 900)), "next")
        transport.pages["next"] = page(listOf(wire("user-generation", "user", "Current question", clock - 800),
            wire("reply", "assistant", "Full final", clock - 700)))
        reconciler.reconcileActive(force = true)
        assertEquals(listOf<String?>(null, "next"), transport.timelineCursors)
        assertEquals(2, transport.generationReads)
        assertEquals(NativeConversationStatus.ENDED, notifications.sessions.value.single().status)
        assertEquals("Full final", notifications.sessions.value.single().latestReply)
        assertTrue("Snapshot recovery cannot announce successful completion", publications.none { it.second })
    }

    @Test fun stateFirstTerminalKeepsPollingForLateLongFinalAndStopsAfterABoundedWindow() = runBlocking {
        status("delivered", 1)
        status("completed", 2)
        transport.pages[null] = page(listOf(wire("user-generation", "user", "Question", clock)))
        reconciler.reconcileActive()
        assertFalse("Terminal retries back off between reads", reconciler.hasPendingReconciliation())
        clock += 5_001L
        assertTrue("An empty terminal snapshot must not consume late-body recovery", reconciler.hasPendingReconciliation())
        assertEquals("", notifications.sessions.value.single().latestReply)
        val finalText = "A complete answer. ".repeat(1_000)
        transport.pages[null] = page(listOf(wire("user-generation", "user", "Question", clock), wire("reply", "assistant", finalText, clock + 1)))
        reconciler.reconcileActive()
        assertEquals(finalText, notifications.sessions.value.single().latestReply)
        assertEquals(NativeConversationStatus.COMPLETED, publications.last().first.status)
        assertEquals(finalText, publications.last().first.latestReply)
        assertTrue(publications.none { it.second })
        clock += 120_001L
        assertFalse(reconciler.hasPendingReconciliation())
        val reads = transport.timelineCursors.size
        reconciler.reconcileActive(force = true)
        assertEquals(reads, transport.timelineCursors.size)
    }

    @Test fun healthyStreamsAvoidHistoryReadsAndStaleTasksAreRateLimitedUntilReconnect() = runBlocking {
        status("delivered", 1)
        transport.generation = { "generation" to "running" }
        assertFalse(reconciler.hasPendingReconciliation())
        reconciler.reconcileActive()
        assertEquals(0, transport.generationReads)
        clock += 15_001L
        assertTrue(reconciler.hasPendingReconciliation())
        reconciler.reconcileActive()
        assertEquals(2, transport.generationReads)
        assertFalse(reconciler.hasPendingReconciliation())
        clock += 5_001L
        reconciler.reconcileActive()
        assertEquals("Five-second runtime ticks must not reread long history", 2, transport.generationReads)
        assistant("conversation.message.delta", "Healthy stream resumes", 1)
        clock += 14_999L
        assertFalse(reconciler.hasPendingReconciliation())
        notifications.disconnect()
        assertTrue("A disconnect requests recovery without waiting for the stale interval", reconciler.hasPendingReconciliation())
        reconciler.reconcileActive()
        assertEquals(4, transport.generationReads)
    }

    @Test fun eventDuringSnapshotReadPreventsAnIdleResponseFromOverwritingNewerWork() = runBlocking {
        status("delivered", 1)
        transport.pages[null] = page(listOf(wire("user-generation", "user", "Question", clock), wire("reply", "assistant", "Stale final", clock)))
        transport.onTimeline = { assistant("conversation.message.delta", "New event preview", 5) }
        reconciler.reconcileActive(force = true)
        val session = notifications.sessions.value.single()
        assertEquals(NativeConversationStatus.RUNNING, session.status)
        assertEquals("New event preview", session.latestReply)
        assertTrue(publications.none { it.second })
    }

    @Test fun generationChangeAcrossTimelineReadDiscardsTheWholeSnapshot() = runBlocking {
        status("delivered", 1)
        transport.generation = { if (it == 1) "generation" to "running" else "new-generation" to "running" }
        transport.pages[null] = page(listOf(wire("reply", "assistant", "Unstable body", clock + 1)))
        reconciler.reconcileActive(force = true)
        assertEquals("generation", notifications.sessions.value.single().generationId)
        assertEquals("", notifications.sessions.value.single().latestReply)
    }

    @Test fun stableNewGenerationClearsTheOldPreview() = runBlocking {
        status("delivered", 1)
        assistant("conversation.message.delta", "Old preview", 1)
        transport.generation = { "next" to "running" }
        reconciler.reconcileActive(force = true)
        val session = notifications.sessions.value.single()
        assertEquals("next", session.generationId)
        assertEquals("", session.latestReply)
        assertEquals(NativeConversationStatus.RUNNING, session.status)
    }

    @Test fun adoptedGenerationUsesItsExactNewUserAnchorForAFinalBodyThatPredatesRecovery() = runBlocking {
        status("delivered", 1)
        assistant("conversation.message.delta", "Old generation preview", 1)
        transport.generation = { "next" to "running" }
        transport.pages[null] = page(listOf(wire("user-generation", "user", "Old question", clock - 2_000),
            wire("reply", "assistant", "Old generation preview", clock - 1_500),
            wire("user-next", "user", "New question", clock - 1_000),
            wire("reply-next", "assistant", "New final already persisted", clock - 500)))
        reconciler.reconcileActive(force = true)
        assertEquals("next", notifications.sessions.value.single().generationId)
        assertEquals("New final already persisted", notifications.sessions.value.single().latestReply)
        transport.generation = { null }
        clock += 15_001L
        reconciler.reconcileActive()
        assertEquals(NativeConversationStatus.ENDED, notifications.sessions.value.single().status)
        assertEquals("New final already persisted", notifications.sessions.value.single().latestReply)
        assertTrue(publications.none { it.second })
    }

    @Test fun multipleUnseenUsersCannotBeGuessedAsTheNewGenerationOwner() = runBlocking {
        status("delivered", 1)
        assistant("conversation.message.delta", "Old preview", 1)
        transport.generation = { "next" to "running" }
        transport.pages[null] = page(listOf(wire("user-generation", "user", "Old question", clock - 2_000),
            wire("reply", "assistant", "Old preview", clock - 1_500),
            wire("unseen-one", "user", "First unseen question", clock - 1_000),
            wire("first-unseen-reply", "assistant", "Uncorrelated reply", clock - 500),
            wire("unseen-two", "user", "Second unseen question", clock - 100)))
        reconciler.reconcileActive(force = true)
        assertEquals("next", notifications.sessions.value.single().generationId)
        assertEquals("", notifications.sessions.value.single().latestReply)
    }

    @Test fun staleShorterSnapshotCannotReplaceAnAlreadyCompletedBodyAtTheSameRevision() = runBlocking {
        status("delivered", 1)
        assistant("conversation.message.completed", "Complete final body already observed", 1)
        transport.pages[null] = page(listOf(wire("user-generation", "user", "Question", clock - 1_000),
            wire("reply", "assistant", "Short old body", clock - 500)))
        reconciler.reconcileActive(force = true)
        assertEquals("Complete final body already observed", notifications.sessions.value.single().latestReply)
    }

    @Test fun anUnsupportedCurrentGenerationEndpointDoesNotDeleteTheConversation() = runBlocking {
        status("delivered", 1)
        transport.generationStatus = 404
        reconciler.reconcileActive(force = true)
        assertTrue(notifications.sessions.value.single().isRunning)
        assertEquals(0, transport.timelineCursors.size)
    }

    @Test fun idleReceiptRecoversTheLatestBodyEvenWhenItsCompletedFrameWasLost() = runBlocking {
        status("delivered", 1)
        val streaming = wire("reply", "assistant", "Latest persisted body", clock + 1) + ("state" to "STREAMING")
        transport.pages[null] = page(listOf(wire("user-generation", "user", "Question", clock), streaming))
        reconciler.reconcileActive(force = true)
        assertEquals(NativeConversationStatus.ENDED, notifications.sessions.value.single().status)
        assertEquals("Latest persisted body", notifications.sessions.value.single().latestReply)
        assertTrue(publications.none { it.second })
    }

    @Test fun incompleteOrChangingTimelineCannotEndTheTask() = runBlocking {
        status("delivered", 1)
        transport.pages[null] = page(emptyList(), "missing")
        reconciler.reconcileActive(force = true)
        assertTrue(notifications.sessions.value.single().isRunning)
        assertFalse(reconciler.hasPendingReconciliation())
        transport.pages["missing"] = page(listOf(wire("reply", "assistant", "New page", clock + 1)), revision = 2)
        reconciler.reconcileActive(force = true)
        assertTrue(notifications.sessions.value.single().isRunning)
        assertEquals("", notifications.sessions.value.single().latestReply)
    }

    @Test fun aLoopingCursorCannotBeMistakenForTheEndOfTheTimeline() = runBlocking {
        status("delivered", 1)
        transport.pages[null] = page(emptyList(), "loop")
        transport.pages["loop"] = page(emptyList(), "loop")
        reconciler.reconcileActive(force = true)
        assertEquals(NativeConversationStatus.RUNNING, notifications.sessions.value.single().status)
        assertEquals(2, transport.timelineCursors.size)
    }

    @Test fun withoutAnExactIdentityOnlyMessagesAfterTheKnownStartCanBeUsed() = runBlocking {
        assistant("conversation.message.delta", "Current partial", 1)
        transport.pages[null] = page(listOf(wire("old-reply", "assistant", "Old final", clock - 1_000)))
        reconciler.reconcileActive(force = true)
        assertEquals("Current partial", notifications.sessions.value.single().latestReply)
        assertEquals(NativeConversationStatus.ENDED, notifications.sessions.value.single().status)
    }

    @Test fun recoveredIdleThenAuthoritativeSuccessRefinesTheLabelWithoutANewAlarm() = runBlocking {
        status("delivered", 1)
        reconciler.reconcileActive(force = true)
        assertEquals(NativeConversationStatus.ENDED, notifications.sessions.value.single().status)
        status("completed", 2)
        assistant("conversation.message.completed", "Late confirmed answer", 1)
        assertEquals(NativeConversationStatus.COMPLETED, notifications.sessions.value.single().status)
        assertEquals("Late confirmed answer", publications.last().first.latestReply)
        assertTrue("Reconnect and snapshot recovery must stay silent", publications.none { it.second })
    }

    @Test fun recoveredIdleThenFailureRefinesTheLabelWithoutANewAlarm() = runBlocking {
        status("delivered", 1)
        reconciler.reconcileActive(force = true)
        status("failed", 2)
        assertEquals(NativeConversationStatus.FAILED, notifications.sessions.value.single().status)
        assertTrue(publications.none { it.second })
    }

    private fun status(state: String, revision: Long) = accept("conversation.message.status", mapOf(
        "conversationId" to "conversation", "messageId" to "user-generation", "clientMessageId" to "client-generation",
        "generationId" to "generation", "status" to state, "revision" to revision,
        "errorCode" to if (state == "failed") "AGENT_UNAVAILABLE" else null))

    private fun assistant(type: String, text: String, revision: Long) = accept(type, mapOf(
        "conversationId" to "conversation", "messageId" to "reply", "generationId" to "generation", "sender" to "assistant",
        "parts" to listOf(mapOf("type" to "text", "text" to text)), "revision" to revision))

    private fun accept(type: String, payload: Map<String, Any?>) {
        clock++
        val body = Json.canonical(Json.of(mapOf("accountId" to "account", "occurredAt" to Instant.ofEpochMilli(clock).toString(), "payload" to payload)))
        notifications.accept("profile", "account", GatewayEvent("reconcile-${++sequence}", type, body))
    }

    private fun wire(id: String, sender: String, text: String, timestamp: Long) = mapOf(
        "messageId" to id, "sender" to sender, "parts" to listOf(mapOf("type" to "text", "text" to text)),
        "timestamp" to timestamp, "state" to "CONFIRMED", "contentRevision" to 1)

    private fun page(messages: List<Map<String, Any>>, next: String? = null, revision: Long = 1) = Json.canonical(Json.of(
        mapOf("protocol" to "2.1", "data" to mapOf("messages" to messages, "nextCursor" to next, "snapshotRevision" to revision))))

    private inner class Transport : GatewayByteTransport {
        val pages = mutableMapOf<String?, String>()
        val timelineCursors = mutableListOf<String?>()
        var generationReads = 0
        var generationStatus = 200
        var generation: (Int) -> Pair<String, String>? = { null }
        var onTimeline: () -> Unit = {}
        override suspend fun execute(request: WireRequest): WireResponse {
            val body = if (request.target.endsWith("/generations/current")) {
                if (generationStatus != 200) return WireResponse(generationStatus, emptyList(), ByteArray(0))
                val current = generation(++generationReads)
                Json.canonical(Json.of(mapOf("protocol" to "2.1", "data" to mapOf("generation" to current?.let {
                    mapOf("conversationId" to "conversation", "generationId" to it.first, "state" to it.second)
                }))))
            } else {
                val cursor = Regex("[?&]cursor=([^&]+)").find(request.target)?.groupValues?.get(1)
                timelineCursors += cursor
                onTimeline()
                pages[cursor] ?: if (pages.isEmpty()) page(emptyList()) else return WireResponse(500, emptyList(), ByteArray(0))
            }
            return WireResponse(200, emptyList(), body.toByteArray())
        }
        override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
    }
}
