package com.openandroidintelligence.gateway.agents

import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.GatewayByteTransport
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.GatewayTransport
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.WireRequest
import com.openandroidintelligence.gateway.http.WireResponse
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AgentSessionClientTest {
    private val agent = CoreAgent("codex", "Codex", "codex", true, "deployment",
        AgentCapabilities(list = true, history = true, create = true, send = true, stream = true))

    @Test fun discoveryUsesTheAuthenticatedCoreAndMissingCapabilitiesStayDisabled() = runBlocking {
        val transport = RecordingTransport("""{"agents":[{"id":"codex","name":"Codex","kind":"codex","online":false,"sessionScope":"deployment","capabilities":{"list":true,"history":true}}]}""")
        val agents = client(transport).agents()
        assertEquals("deployment", agents.single().sessionScope)
        assertFalse(agents.single().online)
        assertFalse(agents.single().capabilities.send)
        assertEquals(AgentSessionClient.ROOT, transport.requests.single().target)
        assertTrue(transport.requests.single().headers.any { it.name == "Authorization" && it.value == "Bearer access" })
    }

    @Test fun readOnlyAdaptersRejectMutationsBeforeSendingAnyRequest() = runBlocking {
        val transport = RecordingTransport("{}")
        val client = client(transport)
        val readOnly = agent.copy(capabilities = AgentCapabilities(list = true, history = true))
        val actions = listOf<suspend () -> Unit>(
            { client.create(readOnly); Unit }, { client.rename(readOnly, "native", "title") },
            { client.archive(readOnly, "native") }, { client.delete(readOnly, "native") },
            { client.cancel(readOnly, "native") }, { client.send(readOnly, "native", "hello") {} },
        )
        actions.forEach { assertEquals("UNSUPPORTED", runCatching { it() }.exceptionOrNull()?.message) }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun historyPreservesNativeIdentityAndNeverAddsAnAccountToThePayload() = runBlocking {
        val transport = RecordingTransport("""{"messages":[{"id":"native-message","role":"assistant","text":"terminal history","createdAt":"2026-10-25T01:00:00Z"}]}""")
        val messages = client(transport).history(agent, "native session 中文")
        assertEquals("native-message", messages.single().id)
        assertEquals("terminal history", messages.single().text)
        assertEquals(AgentSessionClient.ROOT + "/codex/sessions/native%20session%20%E4%B8%AD%E6%96%87/history", transport.requests.single().target)
        assertTrue(transport.requests.single().body.isEmpty())
        assertEquals("AGENT_IDENTIFIER_INVALID", runCatching { client(transport).history(agent, "../foreign") }.exceptionOrNull()?.message)
        assertEquals(1, transport.requests.size)
    }

    @Test fun anIncompleteSseStreamReportsAnUnknownOutcomeWithoutRetrying() = runBlocking {
        val transport = RecordingTransport("event: message\ndata: {\"type\":\"delta\",\"text\":\"partial\"}\n\n", "text/event-stream")
        val partial = StringBuilder()
        val failure = runCatching { client(transport).send(agent, "native", "hello") { partial.append(it) } }.exceptionOrNull()
        assertEquals("partial", partial.toString())
        assertEquals("OUTCOME_UNKNOWN", failure?.message)
        assertEquals(1, transport.requests.size)
        val request = transport.requests.single()
        assertEquals("""{"text":"hello"}""", request.body.decodeToString())
        assertEquals(request.headers.single { it.name == "Idempotency-Key" }.value,
            request.headers.single { it.name == "X-Open-Android-Intelligence-Request-Id" }.value)
    }

    @Test fun signedPostStreamsTheFirstDeltaBeforeTheServerFinishesItsResponse(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            val firstDeltaConsumed = CompletableFuture<Unit>()
            val serverDone = CompletableFuture<String>()
            thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val input = socket.getInputStream()
                        val headerBytes = ArrayList<Byte>()
                        while (true) {
                            val byte = input.read()
                            check(byte >= 0)
                            headerBytes += byte.toByte()
                            if (headerBytes.size >= 4 && headerBytes.takeLast(4) == listOf(13, 10, 13, 10).map(Int::toByte)) break
                        }
                        val headers = headerBytes.toByteArray().decodeToString()
                        check(headers.startsWith("POST ${AgentSessionClient.ROOT}/codex/sessions/native/messages HTTP/1.1"))
                        val length = Regex("(?im)^content-length: (\\d+)").find(headers)!!.groupValues[1].toInt()
                        val body = input.readNBytes(length).decodeToString()
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\nevent: message\ndata: {\"type\":\"delta\",\"text\":\"你好\"}\n\n".toByteArray())
                        output.flush()
                        firstDeltaConsumed.get(10, TimeUnit.SECONDS)
                        output.write("event: message\ndata: {\"type\":\"done\"}\n\n".toByteArray())
                        output.flush()
                        serverDone.complete(body)
                    }
                } catch (failure: Throwable) { serverDone.completeExceptionally(failure) }
            }
            val profile = GatewayProfile("account", "device", "session", "http://127.0.0.1:${server.localPort}", accessToken = "access")
            val client = AgentSessionClient(GatewayHttpClient(profile, GatewayTransport(profile), { ByteArray(64) }, InMemoryEventCursorStore()))
            val output = StringBuilder()
            client.send(agent, "native", "hello") { output.append(it); firstDeltaConsumed.complete(Unit) }
            assertEquals("你好", output.toString())
            assertEquals("""{"text":"hello"}""", serverDone.get(10, TimeUnit.SECONDS))
        }
    }

    @Test fun aReadFailureAfterNativeOutputPreservesThePartialReplyAndNeverResends() = runBlocking {
        var sends = 0
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
            override suspend fun <T> consumeResponse(request: WireRequest,
                consume: suspend (Int, List<RawHeader>, InputStream) -> T): T {
                sends++
                val partial = ByteArrayInputStream("event: message\ndata: {\"type\":\"delta\",\"text\":\"partial\"}\n\n".toByteArray())
                val interrupted = object : InputStream() {
                    override fun read(): Int = partial.read().also { if (it < 0) throw IOException("connection interrupted") }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        partial.read(buffer, offset, length).also { if (it < 0) throw IOException("connection interrupted") }
                }
                return consume(200, listOf(RawHeader("Content-Type", "text/event-stream")), interrupted)
            }
        }
        val partial = StringBuilder()
        val failure = runCatching { client(transport).send(agent, "native", "hello") { partial.append(it) } }.exceptionOrNull()
        assertEquals("OUTCOME_UNKNOWN", failure?.message)
        assertEquals("partial", partial.toString())
        assertEquals(1, sends)
    }

    @Test fun cancellingTheCallerIsNotConvertedIntoAnUnknownOutcome() = runBlocking {
        val cancelled = CancellationException("account closed")
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = throw cancelled
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val failure = runCatching { client(transport).send(agent, "native", "hello") {} }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("account closed", failure?.message)
    }

    private fun client(transport: GatewayByteTransport) = AgentSessionClient(GatewayHttpClient(
        GatewayProfile("account", "device", "session", "https://core.example", accessToken = "access"),
        transport, { ByteArray(64) }, InMemoryEventCursorStore()))

    private class RecordingTransport(private val body: String, private val contentType: String = "application/json") : GatewayByteTransport {
        val requests = mutableListOf<WireRequest>()
        override suspend fun execute(request: WireRequest): WireResponse {
            requests += request
            return WireResponse(200, listOf(RawHeader("Content-Type", contentType)), body.toByteArray())
        }
        override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
    }
}
