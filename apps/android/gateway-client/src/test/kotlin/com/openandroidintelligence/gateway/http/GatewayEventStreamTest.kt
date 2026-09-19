package com.openandroidintelligence.gateway.http

import com.openandroidintelligence.gateway.events.EventCursorStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

        client.events(autoReconnect = false).toList()

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

        client.events(autoReconnect = false).toList()

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

        val events = client.events(autoReconnect = false).toList()

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

        client.events(autoReconnect = false).toList()

        // The Gateway refuses a non-canonical target, so a corrupt local cursor
        // restarts from the retained window (events are idempotent upserts)
        // instead of producing an unexplainable verification failure.
        assertEquals("/open-android-intelligence/v2/events", requireNotNull(transport.lastRequest).target)
    }

    @Test
    fun `websocket failure falls back gracefully to sse stream`() = runBlocking {
        val transport = RecordingTransport(listOf(completedFrame))
        val cursorStore = MemoryCursorStore().apply { seed("acc_test", "cur_start") }
        val failingWs = object : com.openandroidintelligence.gateway.ws.GatewayWebSocketTransport(profile(), { ByteArray(64) }) {
            override fun events(cursor: String?): Flow<com.openandroidintelligence.gateway.events.GatewayEvent> = flow {
                throw java.io.IOException("WEBSOCKET_HANDSHAKE_FAILED: 404 Not Found")
            }
        }
        val client = GatewayHttpClient(
            profile = profile(),
            transport = transport,
            signer = { ByteArray(64) },
            cursorStore = cursorStore,
            webSocketTransport = failingWs,
        )

        val events = client.events(autoReconnect = false).toList()

        assertEquals(1, events.size)
        assertEquals("evt_01", events[0].id)
        assertEquals("/open-android-intelligence/v2/events?cursor=cur_start", requireNotNull(transport.lastRequest).target)
        assertEquals("evt_01", cursorStore.load("acc_test"))
    }

    @Test
    fun `websocket success emits events without calling sse`() = runBlocking {
        val transport = RecordingTransport(listOf(completedFrame))
        val cursorStore = MemoryCursorStore().apply { seed("acc_test", "cur_start") }
        val wsEvent = com.openandroidintelligence.gateway.events.GatewayEvent("ws_evt_1", "test.event", "{}")
        val successfulWs = object : com.openandroidintelligence.gateway.ws.GatewayWebSocketTransport(profile(), { ByteArray(64) }) {
            override fun events(cursor: String?): Flow<com.openandroidintelligence.gateway.events.GatewayEvent> = flow {
                emit(wsEvent)
            }
        }
        val client = GatewayHttpClient(
            profile = profile(),
            transport = transport,
            signer = { ByteArray(64) },
            cursorStore = cursorStore,
            webSocketTransport = successfulWs,
        )

        val events = client.events(autoReconnect = false).toList()

        assertEquals(1, events.size)
        assertEquals("ws_evt_1", events[0].id)
        org.junit.Assert.assertNull("SSE transport must not be called when WebSocket succeeds", transport.lastRequest)
        assertEquals("ws_evt_1", cursorStore.load("acc_test"))
    }

    @Test
    fun `reconnect mechanism uses exponential backoff and updated cursor on failure`() = runBlocking {
        val recordedDelays = mutableListOf<Long>()
        val requestedTargets = mutableListOf<String>()
        var attemptCount = 0

        val failingThenSucceedingTransport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                attemptCount++
                requestedTargets += request.target
                if (attemptCount == 1) {
                    emit("id: evt_01\nevent: notice\ndata: {}\n\n".toByteArray(Charsets.UTF_8))
                    throw java.io.IOException("Connection reset by peer")
                } else {
                    emit("id: evt_02\nevent: notice\ndata: {}\n\n".toByteArray(Charsets.UTF_8))
                }
            }
        }

        val cursorStore = MemoryCursorStore().apply { seed("acc_test", "cur_0") }
        val client = GatewayHttpClient(
            profile = profile(),
            transport = failingThenSucceedingTransport,
            signer = { ByteArray(64) },
            cursorStore = cursorStore,
            webSocketTransport = null,
            delayFn = { recordedDelays += it },
        )

        val events = client.events().take(2).toList()

        assertEquals(2, events.size)
        assertEquals("evt_01", events[0].id)
        assertEquals("evt_02", events[1].id)
        assertEquals("evt_02", cursorStore.load("acc_test"))

        assertEquals(listOf(1000L), recordedDelays)
        assertEquals("/open-android-intelligence/v2/events?cursor=cur_0", requestedTargets[0])
        assertEquals("/open-android-intelligence/v2/events?cursor=evt_01", requestedTargets[1])
    }

    @Test
    fun `exponential backoff progression reaches 2s and 5s`() = runBlocking {
        val recordedDelays = mutableListOf<Long>()
        var attempts = 0
        val multiFailTransport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                attempts++
                if (attempts < 4) {
                    throw java.io.IOException("Temporary network failure $attempts")
                }
                emit("id: final_evt\nevent: notice\ndata: {}\n\n".toByteArray(Charsets.UTF_8))
            }
        }

        val client = GatewayHttpClient(
            profile = profile(),
            transport = multiFailTransport,
            signer = { ByteArray(64) },
            cursorStore = MemoryCursorStore(),
            webSocketTransport = null,
            delayFn = { recordedDelays += it },
        )

        val events = client.events().take(1).toList()

        assertEquals(1, events.size)
        assertEquals("final_evt", events[0].id)
        assertEquals(listOf(1000L, 2000L, 5000L), recordedDelays)
    }

    @Test
    fun `clean stream disconnect reconnects when autoReconnect is true`() = runBlocking {
        var connectCount = 0
        val finiteTransport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                connectCount++
                emit("id: evt_$connectCount\nevent: notice\ndata: {}\n\n".toByteArray(Charsets.UTF_8))
            }
        }
        val client = GatewayHttpClient(
            profile = profile(),
            transport = finiteTransport,
            signer = { ByteArray(64) },
            cursorStore = MemoryCursorStore(),
            webSocketTransport = null,
            delayFn = { /* no delay */ },
        )

        val events = client.events(autoReconnect = true).take(2).toList()

        assertEquals(2, events.size)
        assertTrue("Expected reconnect on clean disconnect", connectCount >= 2)
        assertEquals("evt_1", events[0].id)
        assertEquals("evt_2", events[1].id)
    }

    @Test
    fun `clean stream disconnect terminates when autoReconnect is false`() = runBlocking {
        var connectCount = 0
        val finiteTransport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                connectCount++
                emit("id: evt_$connectCount\nevent: notice\ndata: {}\n\n".toByteArray(Charsets.UTF_8))
            }
        }
        val client = GatewayHttpClient(
            profile = profile(),
            transport = finiteTransport,
            signer = { ByteArray(64) },
            cursorStore = MemoryCursorStore(),
            webSocketTransport = null,
        )

        val events = client.events(autoReconnect = false).toList()

        assertEquals(1, events.size)
        assertEquals(1, connectCount)
        assertEquals("evt_1", events[0].id)
    }

    /**
     * Retrying forever is what made a dropped reply look like an app that never
     * answered: nothing above the transport could tell "reconnecting" from
     * "working". A stream that never recovers now fails loudly.
     */
    @Test
    fun `a stream that never recovers fails instead of retrying forever`() = runBlocking {
        val recordedDelays = mutableListOf<Long>()
        val alwaysFailing = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                throw java.io.IOException("Connection reset by peer")
            }
        }
        val sink = com.openandroidintelligence.gateway.events.EventStreamStatusSink()
        val client = GatewayHttpClient(
            profile = profile(),
            transport = alwaysFailing,
            signer = { ByteArray(64) },
            cursorStore = MemoryCursorStore(),
            webSocketTransport = null,
            delayFn = { recordedDelays += it },
            statusSink = sink,
            maxConsecutiveFailures = 3,
        )

        // Bounded so a regression shows up as a failed assertion instead of a
        // build that hangs: retrying forever was the original defect.
        val result = withTimeoutOrNull(5_000L) { runCatching { client.events().toList() } }

        assertNotNull("事件流必须给出结论，不能永远重试下去", result)
        assertTrue("必须向上抛出异常，否则上层永远看不到故障", result!!.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Connection reset by peer"))
        // Three dead rounds: two backoffs, then the third one gives up.
        assertEquals(listOf(1000L, 2000L), recordedDelays)
        assertEquals(
            com.openandroidintelligence.gateway.events.EventStreamStatus.FAILED,
            sink.status.value,
        )
    }

    @Test
    fun `the stream reports live on the first event and reconnecting after a break`() = runBlocking {
        var attempt = 0
        val flaky = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                attempt++
                if (attempt == 1) {
                    emit("id: evt_1\nevent: notice\ndata: {}\n\n".toByteArray(Charsets.UTF_8))
                    throw java.io.IOException("Connection reset by peer")
                }
                emit("id: evt_2\nevent: notice\ndata: {}\n\n".toByteArray(Charsets.UTF_8))
            }
        }
        val sink = com.openandroidintelligence.gateway.events.EventStreamStatusSink()
        val client = GatewayHttpClient(
            profile = profile(),
            transport = flaky,
            signer = { ByteArray(64) },
            cursorStore = MemoryCursorStore(),
            webSocketTransport = null,
            delayFn = { /* no delay */ },
            statusSink = sink,
        )

        val events = client.events().take(2).toList()

        assertEquals(2, events.size)
        // A stream that recovered must not leave the user looking at a failure.
        assertEquals(
            com.openandroidintelligence.gateway.events.EventStreamStatus.LIVE,
            sink.status.value,
        )
    }
}
