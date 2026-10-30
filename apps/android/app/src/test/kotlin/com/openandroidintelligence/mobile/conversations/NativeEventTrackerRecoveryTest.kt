package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.gateway.agents.*
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NativeEventTrackerRecoveryTest {
    @Test fun coldEventSyncRestoresTheRealGenerationAndItsCancelRoute() = runTest {
        val h = Harness(this)
        h.repository.activeConversationChanged(); runCurrent()
        assertEquals("generation", h.repository.generationId.value)
        assertEquals(GenerationState.RUNNING, h.repository.generationState.value)
        assertEquals(CancelGenerationOutcome.CANCELLED, h.repository.cancelGeneration("chat", "generation", "cancel").outcome)
        assertTrue(h.requests.single { it.target.endsWith("/cancel") }.body.decodeToString().contains("generation"))
        assertTrue(h.requests.none { it.target.endsWith("/messages") })
    }

    @Test fun committedTerminalClearsTheUnknownBarrierAndAllowsTheNextSend() = runTest {
        val h = Harness(this)
        h.repository.activeConversationChanged(); runCurrent()
        h.terminal = true; h.nextPage.complete(Unit); advanceTimeBy(51); runCurrent()
        assertEquals(GenerationState.COMPLETED, h.repository.generationState.value)
        assertNull(h.repository.generationId.value)
        h.repository.submitMessage("chat", OutgoingMessage(ClientMessageId("next-client"), "next")); runCurrent()
        assertEquals(1, h.requests.count { it.target.endsWith("/messages") })
        assertTrue(h.requests.none { it.method == "POST" && it.body.decodeToString().contains("old prompt") })
    }

    @Test fun failedLocalCommitCannotPublishATerminalOrClearItsBarrier() = runTest {
        val h = Harness(this)
        h.repository.activeConversationChanged(); runCurrent()
        h.rejectCommit = true; h.terminal = true; h.nextPage.complete(Unit); advanceTimeBy(51); runCurrent()
        assertEquals(GenerationState.RUNNING, h.repository.generationState.value)
        assertEquals("generation", h.repository.generationId.value)
        h.rejectCommit = false
        h.repository.connectionRestored(); runCurrent()
        assertEquals(GenerationState.COMPLETED, h.repository.generationState.value)
        assertNull(h.repository.generationId.value)
    }

    @Test fun foregroundRecoveryOnlyReadsAndCannotRepostTheOriginalMessage() = runTest {
        val h = Harness(this)
        h.repository.activeConversationChanged(); runCurrent()
        repeat(4) { h.repository.connectionRestored(); runCurrent() }
        assertEquals(GenerationState.RUNNING, h.repository.generationState.value)
        assertTrue(h.requests.none { it.target.endsWith("/messages") })
        assertEquals(1, h.maximumObservers)
    }

    @Test fun eventSyncSettlesAnInterruptedPostAndReleasesItsActualUnknownSendBarrier() = runTest {
        val h = Harness(this)
        h.repository.submitMessage("chat", OutgoingMessage(ClientMessageId("client"), "old prompt")); runCurrent()
        assertEquals(1, h.requests.count { it.target.endsWith("/messages") })
        assertTrue(runCatching { h.repository.submitMessage("chat", OutgoingMessage(ClientMessageId("blocked"), "blocked")) }.isFailure)
        h.terminal = true; h.nextPage.complete(Unit); advanceTimeBy(51); runCurrent()
        assertEquals(GenerationState.COMPLETED, h.repository.generationState.value)
        h.repository.submitMessage("chat", OutgoingMessage(ClientMessageId("next-client"), "next")); runCurrent()
        assertEquals(2, h.requests.count { it.target.endsWith("/messages") })
        assertEquals(1, h.requests.count { it.target.endsWith("/messages") && it.body.decodeToString().contains("old prompt") })
    }

    @Test fun aStaleRunningReceiptCannotReopenACommittedTerminal() = runTest {
        val h = Harness(this)
        h.repository.activeConversationChanged(); runCurrent()
        h.terminal = true; h.nextPage.complete(Unit); advanceTimeBy(51); runCurrent()
        assertEquals(GenerationState.COMPLETED, h.repository.generationState.value)
        h.terminal = false
        h.repository.connectionRestored(); runCurrent()
        assertEquals(GenerationState.COMPLETED, h.repository.generationState.value)
        assertNull(h.repository.generationId.value)
    }

    @Test fun coldRecoverySelectsVerifiedRunningEvenWhenCompletedHistoryIsLast() = runTest {
        mixedReceiptScenario("running", liveFirst = true)
    }
    @Test fun coldRecoverySelectsVerifiedRunningEvenWhenCompletedHistoryIsFirst() = runTest {
        mixedReceiptScenario("running", liveFirst = false)
    }
    @Test fun coldRecoverySelectsVerifiedQueuedEvenWhenCompletedHistoryIsLast() = runTest {
        mixedReceiptScenario("queued", liveFirst = true)
    }
    @Test fun coldRecoverySelectsVerifiedQueuedEvenWhenCompletedHistoryIsFirst() = runTest {
        mixedReceiptScenario("queued", liveFirst = false)
    }
    @Test fun multipleActiveReceiptsRequireTheExplicitCurrentGenerationIdentity() = runTest {
        mixedReceiptScenario("running", liveFirst = false, otherActive = true, authoritative = "generation-live")
    }
    @Test fun ambiguousActiveReceiptsCannotInventACancelRouteOrAuthorizeAnotherPost() = runTest {
        mixedReceiptScenario("running", liveFirst = true, otherActive = true)
    }

    private suspend fun TestScope.mixedReceiptScenario(liveState: String, liveFirst: Boolean,
        otherActive: Boolean = false, authoritative: String? = null) {
        val requests = mutableListOf<WireRequest>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val history = listOf(
            """{"id":"user-live","role":"user","text":"live","clientMessageId":"client-live","generationId":"generation-live","createdAt":1}""",
            """{"id":"user-old","role":"user","text":"old","clientMessageId":"client-old","generationId":"generation-old","createdAt":9999999999999}""")
            .let { if (liveFirst) it else it.reversed() }.joinToString(",")
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                val body = when {
                    request.target.contains("snapshot=true") -> """{"events":[],"cursor":"mixed","hasMore":false,"approvals":[]}"""
                    request.target.endsWith("/events/ack") -> """{"cursor":"mixed"}"""
                    request.target.contains("/events?") -> awaitCancellation()
                    request.target.endsWith("/history") -> """{"messages":[$history]}"""
                    request.target.endsWith("/generation") -> if (authoritative == null) """{"generation":null}"""
                        else """{"generation":{"conversationId":"chat","generationId":"$authoritative","state":"running"}}"""
                    request.target.contains("/generation?") -> {
                        val suffix = if (request.target.contains("client-live")) "live" else "old"
                        val state = if (suffix == "live") liveState else if (otherActive) "queued" else "completed"
                        """{"generation":{"generationId":"generation-$suffix","conversationId":"chat","clientMessageId":"client-$suffix","userMessageId":"user-$suffix","state":"$state","revision":${if (suffix == "live") 3 else 99}}}"""
                    }
                    request.target.endsWith("/cancel") -> """{"session":{"outcome":"cancelled","generationId":"generation-live"}}"""
                    else -> error("Task POST is forbidden")
                }
                return WireResponse(200, listOf(RawHeader("Content-Type","application/json")), body.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val client = AgentSessionClient(GatewayHttpClient(GatewayProfile("account","phone","login","https://core.example",accessToken="synthetic"),
            transport,{ByteArray(64)},InMemoryEventCursorStore(),ioDispatcher=dispatcher))
        val agent = CoreAgent("hermes","test","hermes",true,"account",AgentCapabilities(history=true,send=true,cancel=true,generationRecovery=true,eventSync=true))
        val store = NativeEventSynchronizerTest.Storage().apply { cursor = null }
        val repository = CoreAgentConversationRepository(client,{agent},{"chat"},backgroundScope,dispatcher,storeForAgent={store})
        repository.activeConversationChanged(); runCurrent()
        if (otherActive && authoritative == null) {
            assertNull(repository.generationId.value)
            assertEquals(GenerationState.OUTCOME_UNKNOWN,repository.generationState.value)
            assertTrue(runCatching { repository.submitMessage("chat",OutgoingMessage(ClientMessageId("new"),"do not send")) }.isFailure)
            assertEquals(CancelGenerationOutcome.OUTCOME_UNKNOWN,repository.cancelGeneration("chat","generation-live","cancel").outcome)
        } else {
            assertEquals("generation-live",repository.generationId.value)
            assertEquals(if (liveState == "running") GenerationState.RUNNING else GenerationState.QUEUED,repository.generationState.value)
            assertEquals(CancelGenerationOutcome.CANCELLED,repository.cancelGeneration("chat","generation-live","cancel").outcome)
            assertTrue(requests.single { it.target.endsWith("/cancel") }.body.decodeToString().contains("generation-live"))
        }
        assertTrue(requests.none { it.target.endsWith("/messages") })
    }

    private class Harness(scope: TestScope) {
        val requests = mutableListOf<WireRequest>()
        val nextPage = CompletableDeferred<Unit>()
        var terminal = false
        var rejectCommit = false
        var observers = 0
        var maximumObservers = 0
        private var sentPage = false
        val storage = NativeEventSynchronizerTest.Storage().apply { cursor = null; identities += NativeRecoveryIdentity("client", "generation") }
        private val store = object : NativeConversationStore by storage {
            override suspend fun commitPage(conversationId: String, events: List<VerifiedConversationEvent>, approvals: List<NativeEventApproval>, cursor: String) {
                if (rejectCommit) throw IOException("synthetic save failure")
                storage.commitPage(conversationId, events, approvals, cursor)
            }
        }
        private val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        private val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                val body = when {
                    request.target.contains("snapshot=true") -> """{"events":[],"cursor":"base","hasMore":false,"approvals":[]}"""
                    request.target.endsWith("/events/ack") -> """{"cursor":"base"}"""
                    request.target.contains("/events?") -> {
                        observers++; maximumObservers = maxOf(maximumObservers, observers)
                        try {
                            if (sentPage && request.target.contains("waitMs=")) awaitCancellation()
                            if (!sentPage) nextPage.await()
                        } finally { observers-- }
                        val events = if (sentPage) "" else """{"eventId":"terminal","eventType":"conversation.message.status","payload":{"conversationId":"chat","messageId":"user","clientMessageId":"client","generationId":"generation","status":"completed","revision":7,"errorCode":null}}"""
                        sentPage = true
                        """{"events":[$events],"cursor":"base","hasMore":false}"""
                    }
                    request.target.endsWith("/history") -> """{"messages":[{"id":"user","role":"user","text":"old prompt","clientMessageId":"client","generationId":"generation"}]}"""
                    request.target.contains("/generation?") -> """{"generation":{"generationId":"generation","conversationId":"chat","clientMessageId":"client","userMessageId":"user","state":"${if (terminal) "completed" else "running"}","revision":${if (terminal) 7 else 1}}}"""
                    request.target.endsWith("/cancel") -> """{"session":{"generationId":"generation","outcome":"cancelled"}}"""
                    request.target.endsWith("/messages") -> if (request.body.decodeToString().contains("next-client"))
                        "event: message\ndata: {\"type\":\"generation\",\"generationId\":\"next-generation\",\"clientMessageId\":\"next-client\",\"userMessageId\":\"next-user\",\"state\":\"running\",\"revision\":1}\n\n"
                        else "event: message\ndata: {\"type\":\"generation\",\"generationId\":\"generation\",\"clientMessageId\":\"client\",\"userMessageId\":\"user\",\"state\":\"running\",\"revision\":1}\n\n"
                    else -> error("Unexpected request ${request.target}")
                }
                return WireResponse(200, listOf(RawHeader("Content-Type", if (request.target.endsWith("/messages")) "text/event-stream" else "application/json")), body.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        private val client = AgentSessionClient(GatewayHttpClient(GatewayProfile("account", "phone", "login", "https://core.example", accessToken = "synthetic"), transport, { ByteArray(64) }, InMemoryEventCursorStore(), ioDispatcher = dispatcher))
        private val agent = CoreAgent("hermes", "test", "hermes", true, "account", AgentCapabilities(history=true, send=true, stream=true, cancel=true, generationRecovery=true, eventSync=true))
        val repository = CoreAgentConversationRepository(client, {agent}, {"chat"}, scope.backgroundScope, dispatcher, storeForAgent={store})
    }
}
