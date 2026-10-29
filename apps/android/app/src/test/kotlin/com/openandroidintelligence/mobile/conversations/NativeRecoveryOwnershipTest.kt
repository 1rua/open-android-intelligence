package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import com.openandroidintelligence.gateway.agents.AgentCapabilities
import com.openandroidintelligence.gateway.agents.AgentSessionClient
import com.openandroidintelligence.gateway.agents.CoreAgent
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.GatewayByteTransport
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.WireRequest
import com.openandroidintelligence.gateway.http.WireResponse
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class NativeRecoveryOwnershipTest {
    @Test
    fun queuedFollowupKeepsTheAgentThatOriginallyAcceptedItsQueue() = runTest {
        val firstFinished = CompletableDeferred<Unit>()
        val posts = mutableListOf<String>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("stream only")
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
            override suspend fun <T> consumeResponse(request: WireRequest,
                consume: suspend (Int, List<RawHeader>, InputStream) -> T): T {
                posts += request.target
                if (posts.size == 1) firstFinished.await()
                val body = "event: message\ndata: {\"type\":\"done\",\"outcome\":\"completed\"}\n\n"
                return consume(200, listOf(RawHeader("Content-Type", "text/event-stream")), ByteArrayInputStream(body.toByteArray()))
            }
        }
        val original = recoveryAgent.copy(capabilities = recoveryAgent.capabilities.copy(generationRecovery = false))
        var selected = original
        val repository = CoreAgentConversationRepository(client(transport), { selected }, { "chat" },
            backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repository.submitMessage("chat", OutgoingMessage(ClientMessageId("client_first"), "first synthetic prompt"))
        runCurrent()
        repository.submitMessage("chat", OutgoingMessage(ClientMessageId("client_second"), "queued synthetic prompt",
            delivery = com.openandroidintelligence.conversation.ports.MessageDeliveryMode.QUEUE))
        selected = original.copy(id = "other-agent")
        repository.activeConversationChanged()
        firstFinished.complete(Unit)
        runCurrent()
        assertEquals(2, posts.size)
        assertTrue("A queued request must retain its originating Agent even when another same-named session is selected",
            posts.all { it.startsWith("${AgentSessionClient.ROOT}/${original.id}/sessions/chat/") })
    }

    @Test
    fun fastQueuedCompletionGetsItsOwnDurableReconciliation() = runTest {
        val firstFinished = CompletableDeferred<Unit>()
        val queriedClients = mutableListOf<String>()
        var posts = 0
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                val body = when {
                    request.target.contains("/generation?") -> {
                        val clientId = if (request.target.contains("clientMessageId=client_second")) "client_second" else "client_first"
                        queriedClients += clientId
                        """{"generation":{"generationId":"gen_$clientId","conversationId":"chat","clientMessageId":"$clientId","userMessageId":"user_$clientId","state":"completed","revision":7}}"""
                    }
                    request.target.endsWith("/history") -> """{"messages":[]}"""
                    else -> error("Unexpected synthetic request ${request.target}")
                }
                return WireResponse(200, listOf(RawHeader("Content-Type", "application/json")), body.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
            override suspend fun <T> consumeResponse(request: WireRequest,
                consume: suspend (Int, List<RawHeader>, InputStream) -> T): T {
                val index = ++posts
                if (index == 1) firstFinished.await()
                val clientId = if (index == 1) "client_first" else "client_second"
                val events = listOf(
                    """{"type":"generation","generationId":"gen_$clientId","userMessageId":"user_$clientId","clientMessageId":"$clientId"}""",
                    """{"type":"done","outcome":"completed","generationId":"gen_$clientId"}""",
                ).joinToString("") { "event: message\ndata: $it\n\n" }
                return consume(200, listOf(RawHeader("Content-Type", "text/event-stream")), ByteArrayInputStream(events.toByteArray()))
            }
        }
        // Deliberately execute a newly dispatched task inline. This makes the
        // next completed turn request recovery before the previous stack unwinds.
        val inlineDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
        }
        val repository = CoreAgentConversationRepository(client(transport), { recoveryAgent }, { "chat" },
            backgroundScope, inlineDispatcher)
        repository.submitMessage("chat", OutgoingMessage(ClientMessageId("client_first"), "first synthetic prompt"))
        runCurrent()
        repository.submitMessage("chat", OutgoingMessage(ClientMessageId("client_second"), "queued synthetic prompt",
            delivery = com.openandroidintelligence.conversation.ports.MessageDeliveryMode.QUEUE))
        firstFinished.complete(Unit)
        runCurrent()
        assertEquals(2, posts)
        assertEquals("The previous recovery owner must relinquish its slot before dispatching the next turn",
            listOf("client_first", "client_second"), queriedClients)
    }

    private val recoveryAgent = CoreAgent("hermes", "synthetic", "hermes", true, "account",
        AgentCapabilities(list = true, history = true, send = true, stream = true, generationRecovery = true))
    private fun client(transport: GatewayByteTransport) = AgentSessionClient(GatewayHttpClient(
        GatewayProfile("account", "device", "session", "https://core.example", accessToken = "access"),
        transport, { ByteArray(64) }, InMemoryEventCursorStore(), ioDispatcher = Dispatchers.Unconfined,
    ))

    @Test
    fun olderClientReceiptCannotCloseAnAlreadyRunningNewGeneration() = runBlocking {
        val newStreamWaiting = CountDownLatch(1)
        val releaseNewStream = CountDownLatch(1)
        val posts = AtomicInteger()
        val generationFrame = "event: message\ndata: {\"type\":\"generation\",\"generationId\":\"gen_new\",\"userMessageId\":\"user_new\",\"clientMessageId\":\"client_new\"}\n\n".toByteArray()
        val stream = object : InputStream() {
            private var position = 0
            override fun read(): Int {
                val byte = ByteArray(1)
                return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (position < generationFrame.size) {
                    val count = minOf(length, generationFrame.size - position)
                    generationFrame.copyInto(bytes, offset, position, position + count)
                    position += count
                    return count
                }
                // Entering the next read proves the real decoder has applied gen_new.
                newStreamWaiting.countDown()
                check(releaseNewStream.await(5, TimeUnit.SECONDS)) { "synthetic stream owner was not released" }
                return -1
            }
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                val body = when {
                    request.target.contains("/generation?") && request.target.contains("clientMessageId=client_old") ->
                        """{"generation":{"generationId":"gen_old","conversationId":"chat","clientMessageId":"client_old","userMessageId":"user_old","state":"completed","revision":7}}"""
                    request.target.endsWith("/history") ->
                        """{"messages":[{"id":"user_old","role":"user","text":"synthetic old prompt","clientMessageId":"client_old","generationId":"gen_old","createdAt":"1"}]}"""
                    else -> error("Unexpected synthetic request ${request.method} ${request.target}")
                }
                return WireResponse(200, listOf(RawHeader("Content-Type", "application/json")), body.toByteArray())
            }
            override suspend fun <T> consumeResponse(request: WireRequest,
                consume: suspend (Int, List<RawHeader>, InputStream) -> T): T {
                check(request.method == "POST")
                posts.incrementAndGet()
                return consume(200, listOf(RawHeader("Content-Type", "text/event-stream")), stream)
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val http = GatewayHttpClient(
            GatewayProfile("account", "device", "session", "https://core.example", accessToken = "access"),
            transport, { ByteArray(64) }, InMemoryEventCursorStore(), ioDispatcher = Dispatchers.IO,
        )
        val agent = CoreAgent("hermes", "synthetic", "hermes", true, "account",
            AgentCapabilities(list = true, history = true, send = true, stream = true, generationRecovery = true))
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = CoreAgentConversationRepository(AgentSessionClient(http), { agent }, { "chat" },
            owner, Dispatchers.Unconfined)
        try {
            repository.submitMessage("chat", OutgoingMessage(ClientMessageId("client_new"), "synthetic new prompt"))
            assertTrue(withContext(Dispatchers.IO) { newStreamWaiting.await(3, TimeUnit.SECONDS) })
            assertEquals("gen_new", repository.generationId.value)
            assertEquals(GenerationState.RUNNING, repository.generationState.value)

            assertNotNull(withTimeout(3000) { repository.queryMessage("chat", ClientMessageId("client_old")) })
            val overwritten = withTimeoutOrNull(1000) {
                repository.generationState.first { it != GenerationState.RUNNING }
            }
            assertNull("An older accepted client can be reconciled without replacing the running generation owner", overwritten)
            assertEquals("gen_new", repository.generationId.value)
            assertEquals(1, posts.get())
        } finally {
            releaseNewStream.countDown()
            owner.cancel()
        }
    }
}
