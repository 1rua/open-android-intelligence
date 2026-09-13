package com.openandroidintelligence.gateway.http

import com.openandroidintelligence.gateway.events.EventCursorStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The event stream is an authenticated request, not an open channel.
 *
 * Contract §9 makes the query cursor the only resume authority and §6.1 requires
 * the nine singleton headers on every authenticated request, so these tests pin
 * the two properties a proxy could otherwise exploit: the signature covers the
 * target *including* the cursor, and no mutating header leaks onto the GET.
 */
class GatewayEventStreamTest {

    private class RecordingTransport(private val chunks: List<ByteArray>) : GatewayByteTransport {
        var lastRequest: WireRequest? = null

        override suspend fun execute(request: WireRequest): WireResponse =
            error("the event stream must not use the request/response transport")

        override fun eventStream(request: WireRequest): Flow<ByteArray> {
            lastRequest = request
            return chunks.asFlow()
        }
    }

    private class MemoryCursorStore : EventCursorStore {
        private val cursors = mutableMapOf<String, String>()

        override fun load(accountId: String): String? = cursors[accountId]

        override fun save(accountId: String, cursor: String) {
            cursors[accountId] = cursor
        }

        override fun clear(accountId: String) {
            cursors.remove(accountId)
        }

        fun seed(accountId: String, cursor: String) {
            cursors[accountId] = cursor
        }
    }

    private fun profile() = GatewayProfile(
        accountId = "acc_test",
        deviceId = "dev_test",
        sessionId = "sess_test",
        gatewayBaseUrl = "https://gateway.example.com",
        accessToken = "access_test",
    )

    private fun header(request: WireRequest, name: String): String? =
        request.headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    private val completedFrame = (
        "id: evt_01\n" +
            "event: conversation.message.completed\n" +
            "data: {\"correlationId\":\"corr_01\",\"occurredAt\":\"2026-09-13T00:00:00.000Z\"," +
            "\"payload\":{\"messageId\":\"msg_01\"}}\n" +
            "\n"
        ).toByteArray(Charsets.UTF_8)

    private val authenticatedHeaderNames = listOf(
        "Authorization",
        "X-Open-Android-Intelligence-Protocol",
        "X-Open-Android-Intelligence-Account",
        "X-Open-Android-Intelligence-Device",
        "X-Open-Android-Intelligence-Session",
        "X-Open-Android-Intelligence-Request-Id",
        "X-Open-Android-Intelligence-Timestamp",
        "X-Open-Android-Intelligence-Nonce",
        "X-Open-Android-Intelligence-Signature",
    )

    @Test
    fun `event stream carries the authenticated header set and no idempotency key`() = runBlocking {
        val transport = RecordingTransport(listOf(completedFrame))
        val client = GatewayHttpClient(profile(), transport, { ByteArray(64) }, MemoryCursorStore())

        client.events().toList()

        val request = requireNotNull(transport.lastRequest)
        assertEquals("GET", request.method)
        assertEquals("/open-android-intelligence/v2/events", request.target)
        for (name in authenticatedHeaderNames) {
            assertTrue("missing $name", header(request, name) != null)
        }
        assertEquals("text/event-stream", header(request, "Accept"))
        assertEquals("Bearer access_test", header(request, "Authorization"))
        assertFalse(request.headers.any { it.name.equals("Idempotency-Key", ignoreCase = true) })
    }

    @Test
    fun `signature preimage covers the cursor target and the request identity headers`() = runBlocking {
        var signedBytes: ByteArray? = null
        val transport = RecordingTransport(listOf(completedFrame))
        val cursorStore = MemoryCursorStore().apply { seed("acc_test", "evt_00") }
        val client = GatewayHttpClient(
            profile(), transport,
            { preimage -> signedBytes = preimage; ByteArray(64) },
            cursorStore,
        )

        client.events().toList()

        val request = requireNotNull(transport.lastRequest)
        assertEquals("/open-android-intelligence/v2/events?cursor=evt_00", request.target)
        val preimage = String(requireNotNull(signedBytes), Charsets.UTF_8)
        assertTrue(preimage.contains("/open-android-intelligence/v2/events?cursor=evt_00"))
        assertTrue(
            preimage.contains(requireNotNull(header(request, "X-Open-Android-Intelligence-Request-Id"))),
        )
        assertTrue(preimage.contains(requireNotNull(header(request, "X-Open-Android-Intelligence-Nonce"))))
    }

    @Test
    fun `complete frames advance the stored cursor`() = runBlocking {
        val transport = RecordingTransport(listOf(completedFrame))
        val cursorStore = MemoryCursorStore()
        val client = GatewayHttpClient(profile(), transport, { ByteArray(64) }, cursorStore)

        val events = client.events().toList()

        assertEquals(1, events.size)
        assertEquals("evt_01", events.single().id)
        assertEquals("conversation.message.completed", events.single().event)
        assertEquals("evt_01", cursorStore.load("acc_test"))
    }

    @Test
    fun `a stored cursor that is not a wire id is not sent`() = runBlocking {
        val transport = RecordingTransport(listOf(completedFrame))
        val cursorStore = MemoryCursorStore().apply { seed("acc_test", "not a wire id") }
        val client = GatewayHttpClient(profile(), transport, { ByteArray(64) }, cursorStore)

        client.events().toList()

        // The Gateway refuses a non-canonical target, so a corrupt local cursor
        // restarts from the retained window (events are idempotent upserts)
        // instead of producing an unexplainable verification failure.
        assertEquals("/open-android-intelligence/v2/events", requireNotNull(transport.lastRequest).target)
    }
}
