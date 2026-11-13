package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.ports.CancelGenerationOutcome
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import com.openandroidintelligence.gateway.agents.*
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativeGenerationScopeTest {
    @Test fun identicalConversationAndGenerationIdsInDifferentAgentsCannotRedirectCancellation() = runTest {
        val idle = CompletableDeferred<Unit>()
        val requests = mutableListOf<WireRequest>()
        val agentA = CoreAgent("agent-a", "A", "hermes", true, "account",
            AgentCapabilities(history=true, send=true, cancel=true, generationRecovery=true, eventSync=true))
        var selected = agentA
        val stores = listOf("agent-a", "agent-b").associateWith {
            NativeEventSynchronizerTest.Storage().apply { cursor = null }
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                val agent = if (request.target.contains("/agent-a/")) "agent-a" else "agent-b"
                val body = when {
                    request.target.contains("snapshot=true") -> """{"events":[],"cursor":"baseline","hasMore":false,"approvals":[]}"""
                    request.target.contains("/events?") -> { idle.await(); error("cancelled") }
                    request.target.endsWith("/events/ack") -> """{"cursor":"baseline"}"""
                    request.target.endsWith("/history") -> """{"messages":[{"id":"user-$agent","role":"user","text":"synthetic","clientMessageId":"client-$agent","generationId":"shared-gen","contentRevision":1}]}"""
                    request.target.contains("/generation?") -> """{"generation":{"conversationId":"chat","generationId":"shared-gen","clientMessageId":"client-$agent","userMessageId":"user-$agent","state":"running","revision":1}}"""
                    request.target.endsWith("/cancel") -> """{"session":{"generationId":"shared-gen","outcome":"cancelled"}}"""
                    else -> error("Unexpected request")
                }
                return WireResponse(200, listOf(RawHeader("Content-Type", "application/json")), body.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = AgentSessionClient(GatewayHttpClient(GatewayProfile("account", "phone", "login", "https://core.example", accessToken="synthetic"),
            transport, { ByteArray(64) }, InMemoryEventCursorStore(), ioDispatcher=dispatcher))
        val repository = CoreAgentConversationRepository(client, { selected }, { "chat" }, backgroundScope, dispatcher,
            storeForAgent={ stores.getValue(it.id) }, nativeRetryJitter={ it })
        repository.activeConversationChanged(); runCurrent()
        selected = agentA.copy(id="agent-b")
        repository.activeConversationChanged(); runCurrent()
        selected = agentA
        repository.activeConversationChanged(); runCurrent()
        assertEquals(CancelGenerationOutcome.CANCELLED, repository.cancelGeneration("chat", "shared-gen", "cancel-a").outcome)
        val cancel = requests.single { it.target.endsWith("/cancel") }
        assertTrue(cancel.target.contains("/agent-a/sessions/chat/"))
        assertTrue(requests.none { it.target.endsWith("/messages") })
    }


    @Test fun oldGenerationSelectionCheckCannotOverwriteTheNewAgentsIdleState(): Unit = runBlocking {
        val h = ProjectionRaceHarness(pauseSelectionCheck = true)
        val projectionFinished = CountDownLatch(1)
        val projectionFailure = AtomicReference<Throwable?>(null)
        var selectionThread: Thread? = null
        try {
            h.repository.submitMessage("chat", OutgoingMessage(ClientMessageId("client-a"), "synthetic"))
            assertTrue("The old generation must reach its selection check",
                h.oldSelectionRead.await(3, TimeUnit.SECONDS))
            h.selected.set(h.agentA.copy(id = "agent-b", kind = "codex"))
            val refresh = thread(name = "native-selection-projection") {
                try {
                    h.repository.activeConversationChanged()
                } catch (failure: Throwable) {
                    projectionFailure.set(failure)
                } finally {
                    projectionFinished.countDown()
                }
            }
            selectionThread = refresh
            // Without the projection lock, finish publishing B's IDLE before A resumes.
            // With the lock, B must be allowed to wait for A; release A before joining B.
            awaitProjectionOrLockWait(refresh, projectionFinished)
            h.releaseOldSelection.countDown()
            assertTrue("The new selection projection must finish after the old check is released",
                projectionFinished.await(3, TimeUnit.SECONDS))
            projectionFailure.get()?.let { throw AssertionError("Selection projection failed", it) }
            assertTrue("The old generation frame must finish before checking the new selection",
                h.generationApplied.await(3, TimeUnit.SECONDS))

            assertEquals(CancelGenerationOutcome.CANCELLED,
                h.repository.cancelGeneration("chat", "generation-a", "cancel-a").outcome)
            assertEquals("A late cancellation must still target the original host", 1, h.cancelRequests.size)
            assertEquals("/open-android-intelligence/v2/agents/agent-a/sessions/chat/cancel",
                h.cancelRequests.single().target)
            assertEquals("The old selection check must not publish RUNNING over agent B",
                GenerationState.IDLE, h.repository.generationState.value)
            assertNull("Agent B must not inherit agent A's generation", h.repository.generationId.value)
        } finally {
            h.close()
            selectionThread?.join(3_000)
        }
    }

    @Test fun generationIdObserverCanSwitchAgentsWithoutALaterRunningWrite(): Unit = runBlocking {
        val h = ProjectionRaceHarness(pauseSelectionCheck = false)
        val switched = CompletableDeferred<Unit>()
        val observer = h.parent.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            assertEquals("generation-a", h.repository.generationId.filterNotNull().first())
            h.selected.set(h.agentA.copy(id = "agent-b", kind = "codex"))
            h.repository.activeConversationChanged()
            switched.complete(Unit)
        }
        try {
            h.repository.submitMessage("chat", OutgoingMessage(ClientMessageId("client-a"), "synthetic"))
            withTimeout(3_000) { switched.await() }
            assertTrue("The entire old frame, including writes after generationId, must finish",
                h.generationApplied.await(3, TimeUnit.SECONDS))
            assertEquals("Publishing generationId must not leave an old RUNNING write pending",
                GenerationState.IDLE, h.repository.generationState.value)
            assertNull(h.repository.generationId.value)
        } finally {
            observer.cancel()
            h.close()
        }
    }

    @Test fun generationStateObserverCanSwitchAgentsWithoutALaterGenerationIdWrite(): Unit = runBlocking {
        val h = ProjectionRaceHarness(pauseSelectionCheck = false)
        val switched = CompletableDeferred<Unit>()
        val observer = h.parent.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            h.repository.generationState.first { it == GenerationState.RUNNING }
            h.selected.set(h.agentA.copy(id = "agent-b", kind = "codex"))
            h.repository.activeConversationChanged()
            switched.complete(Unit)
        }
        try {
            h.repository.submitMessage("chat", OutgoingMessage(ClientMessageId("client-a"), "synthetic"))
            withTimeout(3_000) { switched.await() }
            assertTrue("The entire old frame, including writes after generationState, must finish",
                h.generationApplied.await(3, TimeUnit.SECONDS))
            assertEquals("The reentrant selection must keep agent B idle",
                GenerationState.IDLE, h.repository.generationState.value)
            assertNull("Publishing generationState must not leave an old generationId write pending",
                h.repository.generationId.value)
        } finally {
            observer.cancel()
            h.close()
        }
    }

    private fun awaitProjectionOrLockWait(selectionThread: Thread, finished: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (finished.count != 0L) {
            // A monitor wait in activeConversationChanged is the fixed implementation's
            // equivalent of the unlocked implementation having finished its projection.
            if (selectionThread.state == Thread.State.BLOCKED && selectionThread.stackTrace.any {
                    it.className == CoreAgentConversationRepository::class.java.name &&
                        it.methodName == "activeConversationChanged"
                }) return
            check(System.nanoTime() < deadline) { "Selection neither finished nor waited for the projection lock" }
            Thread.yield()
        }
    }

    private class ProjectionRaceHarness(pauseSelectionCheck: Boolean) {
        val agentA = CoreAgent("agent-a", "A", "hermes", true, "account",
            AgentCapabilities(history = true, send = true, stream = true, cancel = true))
        val selected = AtomicReference(agentA)
        val oldSelectionRead = CountDownLatch(1)
        val releaseOldSelection = CountDownLatch(1)
        val generationApplied = CountDownLatch(1)
        val cancelRequests = mutableListOf<WireRequest>()
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val pauseNextSelection = AtomicBoolean(false)
        private val releaseReader = CountDownLatch(1)
        private val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                assertEquals("POST", request.method)
                assertEquals("/open-android-intelligence/v2/agents/agent-a/sessions/chat/cancel", request.target)
                assertEquals("""{"generationId":"generation-a"}""", request.body.decodeToString())
                cancelRequests += request
                return WireResponse(200, listOf(RawHeader("Content-Type", "application/json")),
                    """{"session":{"generationId":"generation-a","outcome":"cancelled"}}""".toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
            override suspend fun <T> consumeResponse(request: WireRequest,
                consume: suspend (Int, List<RawHeader>, InputStream) -> T): T {
                assertEquals("/open-android-intelligence/v2/agents/agent-a/sessions/chat/messages", request.target)
                val prefix = ByteArrayInputStream(
                    "event: message\ndata: {\"type\":\"generation\",\"generationId\":\"generation-a\"}\n\n".toByteArray())
                val suffix = ByteArrayInputStream(
                    "event: message\ndata: {\"type\":\"done\",\"generationId\":\"generation-a\",\"outcome\":\"completed\"}\n\n".toByteArray())
                pauseNextSelection.set(pauseSelectionCheck)
                val input = object : InputStream() {
                    override fun read(): Int = error("Buffered read required")
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        if (length == 0) return 0
                        if (prefix.available() > 0) return prefix.read(bytes, offset, length)
                        // The decoder asks for the next chunk only after the generation
                        // callback has returned, including both StateFlow writes.
                        generationApplied.countDown()
                        check(releaseReader.await(3, TimeUnit.SECONDS)) { "Synthetic reader was not released" }
                        return suffix.read(bytes, offset, length)
                    }
                }
                return input.use { consume(200, listOf(RawHeader("Content-Type", "text/event-stream")), it) }
            }
        }
        private val client = AgentSessionClient(GatewayHttpClient(
            GatewayProfile("account", "phone", "login", "https://core.example", accessToken = "synthetic"),
            transport, { ByteArray(64) }, InMemoryEventCursorStore(), ioDispatcher = Dispatchers.IO))
        val repository = CoreAgentConversationRepository(client, currentAgent = {
            val snapshot = selected.get()
            if (pauseNextSelection.compareAndSet(true, false)) {
                oldSelectionRead.countDown()
                check(releaseOldSelection.await(3, TimeUnit.SECONDS)) { "Old selection snapshot was not released" }
            }
            snapshot
        }, activeConversationId = { "chat" }, parentScope = parent)

        suspend fun close() {
            parent.cancel()
            releaseOldSelection.countDown()
            releaseReader.countDown()
            withTimeout(3_000) { parent.coroutineContext.job.join() }
        }
    }
}
