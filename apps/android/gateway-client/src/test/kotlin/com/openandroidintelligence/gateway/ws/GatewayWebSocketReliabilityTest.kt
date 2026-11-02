package com.openandroidintelligence.gateway.ws

import com.openandroidintelligence.gateway.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GatewayWebSocketReliabilityTest {
    private val profile = GatewayProfile("account", "device", "session", "http://127.0.0.1:1234", accessToken = "test")

    private class ScriptedSocket(
        private val frames: ByteArray = byteArrayOf(),
        private val after: () -> Int = { -1 },
        private val frameByte: () -> Unit = {},
        private val headerByte: () -> Unit = {},
    ) : Socket() {
        val output = ByteArrayOutputStream()
        var closed = false
        override fun getOutputStream() = output
        override fun getInputStream(): InputStream = object : InputStream() {
            private val bytes by lazy {
                val key = Regex("Sec-WebSocket-Key: ([^\r\n]+)").find(output.toString("US-ASCII"))!!.groupValues[1]
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: ${GatewayWebSocketTransport.computeSecWebSocketAccept(key)}\r\n\r\n").toByteArray() + frames
            }
            private var offset = 0
            override fun read(): Int {
                val data = bytes
                if (offset < data.size) {
                    if (offset >= data.size - frames.size) frameByte() else headerByte()
                    return data[offset++].toInt() and 255
                }
                return after()
            }
        }
        override fun close() { closed = true }
    }

    @Test fun authenticatedUpgradeAndHeartbeatReportHealthWithoutBusinessEvents() = runBlocking {
        val socket = ScriptedSocket(byteArrayOf(0x89.toByte(), 1, 7))
        val signals = mutableListOf<GatewayWebSocketSignal>()
        var activities = 0
        val ws = GatewayWebSocketTransport(profile, { ByteArray(64) }, socketFactory = { _, _, _ -> socket })
        assertTrue(ws.events(null, { activities++ }, profile, signals::add).toList().isEmpty())
        assertTrue(signals.first() is GatewayWebSocketSignal.Ready)
        assertTrue(signals.any { it is GatewayWebSocketSignal.Activity })
        assertEquals(1, activities)
        assertTrue(socket.closed)
    }

    @Test fun businessFramesRequireAnEventIdentityAndTypeBeforeCommittingTheirCursor() {
        listOf("""{"id":"evt_valid","data":{}}""", """{"event":"gateway.notice","data":{}}""",
            """{"id":"evt_valid","event":false,"data":{}}""").forEach { payload ->
            assertTrue(runCatching { GatewayWebSocketTransport.parseWebSocketEvent(payload) }.exceptionOrNull()
                is GatewayWebSocketPayloadException)
        }
    }

    @Test fun malformedPayloadDoesNotDisappearBeforeALaterCursor() = runBlocking {
        val bad = "{broken".toByteArray()
        val socket = ScriptedSocket(byteArrayOf(0x81.toByte(), bad.size.toByte()) + bad)
        val ws = GatewayWebSocketTransport(profile, { ByteArray(64) }, socketFactory = { _, _, _ -> socket })
        val failure = runCatching { ws.events().toList() }.exceptionOrNull()
        assertTrue(failure is GatewayWebSocketPayloadException)
        assertEquals("WEBSOCKET_PAYLOAD_INVALID", failure?.message)
    }

    @Test fun cancellingAfterUpgradeClosesTheBlockedSocket() = runBlocking {
        val blocked = CountDownLatch(1)
        val released = CountDownLatch(1)
        val socket = object : Socket() {
            val output = ByteArrayOutputStream()
            override fun getOutputStream() = output
            override fun getInputStream(): InputStream {
                return object : InputStream() {
                    private val bytes by lazy {
                        val key = Regex("Sec-WebSocket-Key: ([^\r\n]+)").find(output.toString("US-ASCII"))!!.groupValues[1]
                        ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: ${GatewayWebSocketTransport.computeSecWebSocketAccept(key)}\r\n\r\n").toByteArray()
                    }
                    var offset = 0
                    override fun read(): Int {
                        if (offset < bytes.size) return bytes[offset++].toInt() and 255
                        blocked.countDown()
                        check(released.await(2, TimeUnit.SECONDS))
                        throw java.net.SocketException("closed")
                    }
                }
            }
            override fun close() { released.countDown() }
        }
        val ws = GatewayWebSocketTransport(profile, { ByteArray(64) }, socketFactory = { _, _, _ -> socket })
        val job = launch { ws.events().collect() }
        withContext(Dispatchers.IO) { assertTrue(blocked.await(2, TimeUnit.SECONDS)) }
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertEquals(0L, released.count)
    }

    @Test fun partialFrameBytesDoNotExtendTheSixtySecondActivityDeadline() = runBlocking {
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        val socket = ScriptedSocket(byteArrayOf(0x81.toByte(), 20, 1, 2, 3, 4), frameByte = { clock.addAndGet(15_000) })
        val signals = mutableListOf<GatewayWebSocketSignal>()
        val ws = GatewayWebSocketTransport(profile, { ByteArray(64) }, socketFactory = { _, _, _ -> socket },
            monotonicMillis = clock::get)
        val failure = runCatching { ws.events(null, {}, profile, signals::add).toList() }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().startsWith("WEBSOCKET_STREAM_STALLED"))
        assertFalse(signals.any { it is GatewayWebSocketSignal.Activity })
        assertTrue(socket.closed)
    }

    @Test fun aHalfOpenSocketFailsAtTheCompleteFrameDeadline() = runBlocking {
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        val socket = ScriptedSocket(after = { clock.set(60_000); throw java.net.SocketTimeoutException() })
        val ws = GatewayWebSocketTransport(profile, { ByteArray(64) }, socketFactory = { _, _, _ -> socket },
            monotonicMillis = clock::get)
        assertTrue(runCatching { ws.events().toList() }.exceptionOrNull()?.message.orEmpty().startsWith("WEBSOCKET_STREAM_STALLED"))
        assertTrue(socket.closed)
    }

    @Test fun closeCodeIsReportedAndPolicyRevocationIsTerminal() = runBlocking {
        val socket = ScriptedSocket(byteArrayOf(0x88.toByte(), 2, 3, 0xF0.toByte())) // 1008
        val signals = mutableListOf<GatewayWebSocketSignal>()
        val ws = GatewayWebSocketTransport(profile, { ByteArray(64) }, socketFactory = { _, _, _ -> socket })
        assertTrue(runCatching { ws.events(null, {}, profile, signals::add).toList() }.exceptionOrNull() is EventStreamForbiddenException)
        assertEquals(1008, signals.filterIsInstance<GatewayWebSocketSignal.Closed>().single().code)
    }

    @Test fun probeSignsItsOwnRouteAndRequiresAMatchingControlPong() = runBlocking {
        java.net.ServerSocket(0).use { server ->
            val peer = java.util.concurrent.CompletableFuture.supplyAsync {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val input = socket.getInputStream()
                    val lines = mutableListOf<String>()
                    fun line(): String {
                        val buffer = ByteArrayOutputStream()
                        while (true) {
                            val byte = input.read()
                            check(byte >= 0)
                            if (byte == 10) return buffer.toString("US-ASCII").removeSuffix("\r")
                            buffer.write(byte)
                        }
                    }
                    while (true) { val value = line(); if (value.isEmpty()) break; lines += value }
                    val key = lines.single { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
                    val output = socket.getOutputStream()
                    output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: ${GatewayWebSocketTransport.computeSecWebSocketAccept(key)}\r\n\r\n").toByteArray())
                    output.flush()
                    assertEquals(0x89, input.read())
                    val size = input.read()
                    assertEquals(0x80 or 32, size)
                    val mask = ByteArray(4)
                    val nonce = ByteArray(32)
                    java.io.DataInputStream(input).readFully(mask)
                    java.io.DataInputStream(input).readFully(nonce)
                    nonce.indices.forEach { nonce[it] = (nonce[it].toInt() xor mask[it % 4].toInt()).toByte() }
                    // An unsolicited Pong is insufficient; the following exact echo is the proof.
                    output.write(byteArrayOf(0x8A.toByte(), 1, 0))
                    output.write(byteArrayOf(0x8A.toByte(), nonce.size.toByte()) + nonce)
                    output.flush()
                    assertEquals(0x88, input.read())
                    val length = input.read() and 127
                    java.io.DataInputStream(input).readFully(ByteArray(4 + length))
                    assertEquals(-1, input.read())
                    lines
                }
            }
            val signed = mutableListOf<String>()
            val bound = profile.copy(gatewayBaseUrl = "http://127.0.0.1:${server.localPort}")
            val ws = GatewayWebSocketTransport(bound, { signed += it.toString(Charsets.UTF_8); ByteArray(64) })
            assertEquals(bound, ws.probe(bound))
            val lines = peer.get(2, TimeUnit.SECONDS)
            assertEquals("GET /open-android-intelligence/v2/events/ws/probe HTTP/1.1", lines.first())
            assertTrue(lines.contains("Authorization: Bearer test"))
            assertTrue(signed.single().contains("/open-android-intelligence/v2/events/ws/probe"))
            assertFalse(lines.any { it.contains("cursor=") })
        }
    }

    @Test fun wrongPongsCannotKeepAProbeAliveBeyondItsTotalBudget() = runBlocking {
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        val frames = ByteArrayOutputStream().apply { repeat(8) { write(byteArrayOf(0x8A.toByte(), 1, 0)) } }.toByteArray()
        val socket = ScriptedSocket(frames, frameByte = { clock.addAndGet(1_000) })
        val ws = GatewayWebSocketTransport(profile, { ByteArray(64) }, socketFactory = { _, _, _ -> socket },
            monotonicMillis = clock::get)
        assertEquals("WEBSOCKET_PROBE_TIMEOUT", runCatching { ws.probe(profile) }.exceptionOrNull()?.message)
        assertTrue(socket.closed)
    }


    @Test fun activeSessionExpiryAndRevocationKeepDifferentBoundariesForEventsAndProbes() = runBlocking {
        listOf(4001, 1008).forEach { code ->
            listOf(false, true).forEach { probe ->
                val frame = byteArrayOf(0x88.toByte(), 2, (code shr 8).toByte(), code.toByte())
                val socket = ScriptedSocket(frame)
                val ws = GatewayWebSocketTransport(profile, { ByteArray(64) }, socketFactory = { _, _, _ -> socket })
                val failure = runCatching {
                    if (probe) ws.probe(profile) else ws.events().toList()
                }.exceptionOrNull()
                if (code == 4001) {
                    assertTrue(failure is EventSessionRejectedException)
                    assertEquals(profile, (failure as EventSessionRejectedException).rejectedProfile)
                } else assertTrue(failure is EventStreamForbiddenException)
                assertTrue(socket.closed)
            }
        }
    }

    @Test fun activeExpiredSessionRenewsOnceAndResumesWithoutAnSseDowngrade() = kotlinx.coroutines.test.runTest {
        var sockets = 0
        var refreshes = 0
        val renewal = com.openandroidintelligence.gateway.auth.GatewaySessionRenewal(profile, backgroundScope) {
            refreshes++
            it.copy(sessionId = "renewed", accessToken = "new")
        }
        val ws = GatewayWebSocketTransport(profile, { ByteArray(64) }, socketFactory = { _, _, _ ->
            if (++sockets == 1) ScriptedSocket(byteArrayOf(0x88.toByte(), 2, 0x0F, 0xA1.toByte()))
            else {
                val event = "{\"id\":\"recovered\",\"event\":\"notice\",\"data\":{}}".toByteArray()
                ScriptedSocket(byteArrayOf(0x81.toByte(), event.size.toByte()) + event)
            }
        })
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = error("expiry must renew, not downgrade")
        }
        val client = GatewayHttpClient(profile, transport, { ByteArray(64) },
            com.openandroidintelligence.gateway.events.InMemoryEventCursorStore(), webSocketTransport = ws, sessionRenewal = renewal)
        assertEquals("recovered", client.events().take(1).toList().single().id)
        assertEquals(1, refreshes)
        assertEquals(2, sockets)
    }


    @Test fun partialHandshakeHeadersCannotDripPastTheTenSecondBudget() = runBlocking {
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        val socket = ScriptedSocket(headerByte = { clock.addAndGet(1_000) })
        val signals = mutableListOf<GatewayWebSocketSignal>()
        val ws = GatewayWebSocketTransport(profile, { ByteArray(64) }, socketFactory = { _, _, _ -> socket }, monotonicMillis = clock::get)
        val failure = runCatching { ws.events(null, {}, profile, signals::add).toList() }.exceptionOrNull()
        assertEquals("WEBSOCKET_HANDSHAKE_TIMEOUT", failure?.message)
        assertTrue(signals.isEmpty())
        assertTrue(socket.closed)
    }

}
