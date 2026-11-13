package com.openandroidintelligence.gateway.agents

import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeOperationTraceTest {
    @Test fun messageAndRecoveryShareATraceWithoutChangingHttpRequestIdentityOrLoggingText() = runBlocking {
        val h = Harness()
        val oldEnabled = GatewayLog.protocolEvidenceEnabled
        val oldSink = GatewayLog.sink
        val logs = mutableListOf<String>()
        GatewayLog.protocolEvidenceEnabled = true
        GatewayLog.sink = { _, line -> logs += line }
        try {
            h.client.send(h.agent, "chat", "never-log-this-message", clientMessageId="client", onDelta={})
            h.client.generation(h.agent, "chat", "client", "generation")
            val traces = h.requests.map { RawHeaders.single(it.headers, "traceparent")!! }
            assertEquals(1, traces.map { it.split('-')[1] }.distinct().size)
            assertNotEquals(traces[0].split('-')[2], traces[1].split('-')[2])
            val post = h.requests.first()
            assertEquals(RawHeaders.single(post.headers, "X-Open-Android-Intelligence-Request-Id"), RawHeaders.single(post.headers,"Idempotency-Key"))
            assertTrue(logs.none { it.contains("never-log-this-message") || it.contains("synthetic-token") })
            val phases = logs.mapNotNull { runCatching { JsonFields.obj(Json.parse(it)) }.getOrNull() }
            assertTrue(phases.any { JsonFields.string(it, "type") == "http.dispatched" })
            assertTrue(phases.any { JsonFields.string(it, "type") == "http.headers_received" })
            val bootIds = phases.map { requireNotNull(JsonFields.string(it, "bootId")) }.distinct()
            assertEquals(1, bootIds.size)
            java.util.UUID.fromString(bootIds.single())
            assertFalse(logs.any { it.contains("traceparent") })
            val clock = phases.map { requireNotNull(JsonFields.long(it, "monotonicNs")) }
            assertEquals(clock.sorted(), clock)
        } finally { GatewayLog.sink = oldSink; GatewayLog.protocolEvidenceEnabled = oldEnabled }
    }

    @Test fun callersCannotOverrideTheProcessIdentityOrMonotonicClock() {
        val oldEnabled = GatewayLog.protocolEvidenceEnabled
        val oldSink = GatewayLog.sink
        var logged = ""
        GatewayLog.protocolEvidenceEnabled = true
        GatewayLog.sink = { _, line -> logged = line }
        try {
            val before = System.nanoTime()
            GatewayLog.protocolEvidence("test", mapOf("bootId" to "forged", "monotonicNs" to -1))
            val value = requireNotNull(JsonFields.obj(Json.parse(logged)))
            assertNotEquals("forged", JsonFields.string(value, "bootId"))
            assertTrue(requireNotNull(JsonFields.long(value, "monotonicNs")) >= before)
        } finally { GatewayLog.sink = oldSink; GatewayLog.protocolEvidenceEnabled = oldEnabled }
    }

    @Test fun tracingIsOptionalAndDoesNotChangeTheCapabilityGate() = runBlocking {
        val h = Harness(trace = false)
        h.client.send(h.agent, "chat", "test", clientMessageId="client", onDelta={})
        assertNull(RawHeaders.single(h.requests.single().headers, "traceparent"))
        val before = h.requests.size
        assertEquals("UNSUPPORTED", (runCatching {
            h.client.operation(h.agent.copy(capabilities=h.agent.capabilities.copy(operationRecovery=false)), "create")
        }.exceptionOrNull() as AgentSessionException).code)
        assertEquals(before, h.requests.size)
    }

    @Test fun operationRecoveryRemainsReadableWhileTheAgentIsOffline() = runBlocking {
        val h = Harness()
        val result = h.client.operation(h.agent.copy(online=false), "create")
        assertEquals("resolved", result.state)
        assertEquals("authoritative-session", result.session!!.id)
        assertEquals("GET", h.requests.single().method)
    }

    @Test fun aResolvedOperationPreservesTheOriginalExplicitErrorCode() = runBlocking {
        val h = Harness(operationBody = """{"state":"resolved","receipt":{"error":{"code":"SESSION_REVOKED"}}}""")
        val failure = runCatching { h.client.operation(h.agent, "create") }.exceptionOrNull()
        assertTrue(failure is AgentSessionException)
        assertEquals("SESSION_REVOKED", (failure as AgentSessionException).code)
        assertEquals(listOf("GET"), h.requests.map { it.method })
    }

    private class Harness(trace: Boolean = true,
        val operationBody: String = """{"state":"resolved","receipt":{"session":{"id":"authoritative-session"}}}""") {
        val requests = mutableListOf<WireRequest>()
        val agent = CoreAgent("hermes", "test", "hermes", true, "account", AgentCapabilities(send=true, stream=true, generationRecovery=true, operationRecovery=true))
        private val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                val body = when {
                    request.target.contains("/operations/") -> operationBody
                    request.target.contains("/generation?") -> """{"generation":{"generationId":"generation","conversationId":"chat","clientMessageId":"client","userMessageId":"user","state":"completed","revision":7}}"""
                    else -> "event: message\ndata: {\"type\":\"done\",\"outcome\":\"completed\",\"generationId\":\"generation\"}\n\n"
                }
                return WireResponse(200, listOf(RawHeader("Content-Type", if(request.method == "POST") "text/event-stream" else "application/json")), body.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val client = AgentSessionClient(GatewayHttpClient(GatewayProfile("account","phone","login","https://core.example",accessToken="synthetic-token"),transport,{ByteArray(64)},InMemoryEventCursorStore()), traceEnabled=trace)
    }
}
