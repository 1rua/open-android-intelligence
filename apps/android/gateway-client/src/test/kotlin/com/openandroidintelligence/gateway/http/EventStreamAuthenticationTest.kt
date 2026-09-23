package com.openandroidintelligence.gateway.http

import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EventStreamAuthenticationTest {
    @Test fun eventsUseTheSameNineSingletonAuthenticationHeadersAndSignedResumeTarget() = runBlocking {
        var wire: WireRequest? = null
        val signed = mutableListOf<ByteArray>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unexpected regular request")
            override fun eventStream(request: WireRequest): Flow<ByteArray> {
                wire = request
                return flowOf("id: evt_8\nevent: gateway.notice\ndata: {}\n\n".toByteArray())
            }
        }
        val cursors = InMemoryEventCursorStore().apply { save("account_test", "cur_7") }
        val client = GatewayHttpClient(
            GatewayProfile("account_test", "device_test", "session_test", "https://gateway.example", accessToken = "test-bearer"),
            transport, { preimage -> signed += preimage; ByteArray(64) { 1 } }, cursors,
        )
        val events = client.events(autoReconnect = false).toList()
        val request = requireNotNull(wire)
        assertEquals("GET", request.method)
        assertEquals("/open-android-intelligence/v2/events?cursor=cur_7", request.target)
        val headers = request.headers.groupBy { it.name.lowercase() }
        val authentication = listOf("authorization", "protocol", "account", "device", "session", "request-id", "timestamp", "nonce", "signature")
            .map { if (it == "authorization") it else "x-open-android-intelligence-$it" }
        authentication.forEach { name -> assertEquals("missing or repeated $name", 1, headers[name]?.size ?: 0) }
        assertEquals("Bearer test-bearer", headers["authorization"]!!.single().value)
        assertEquals("2.1", headers["x-open-android-intelligence-protocol"]!!.single().value)
        assertFalse(headers.containsKey("idempotency-key"))
        assertEquals(1, signed.size)
        assertTrue(signed.single().toString(Charsets.UTF_8).contains(request.target))
        assertEquals(1, events.size)
        assertEquals("evt_8", cursors.load("account_test"))
    }
}
