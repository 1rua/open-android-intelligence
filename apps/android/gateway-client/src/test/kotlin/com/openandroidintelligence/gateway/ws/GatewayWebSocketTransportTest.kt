package com.openandroidintelligence.gateway.ws

import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.http.GatewayProfile
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class GatewayWebSocketTransportTest {

    private fun testProfile(port: Int, pins: Set<String> = emptySet()) = GatewayProfile(
        accountId = "acc_test",
        deviceId = "dev_test",
        sessionId = "sess_test",
        gatewayBaseUrl = "http://127.0.0.1:$port",
        pinnedSpkiSha256 = pins,
        accessToken = "test_token",
    )

    private fun writeServerFrame(
        output: OutputStream,
        opcode: Int,
        payload: ByteArray,
        fin: Boolean = true,
    ) {
        val b0 = (if (fin) 0x80 else 0x00) or (opcode and 0x0F)
        output.write(b0)
        // Server frames are NOT masked
        if (payload.size <= 125) {
            output.write(payload.size)
        } else if (payload.size <= 65535) {
            output.write(126)
            output.write((payload.size shr 8) and 0xFF)
            output.write(payload.size and 0xFF)
        } else {
            output.write(127)
            for (i in 7 downTo 0) {
                output.write(((payload.size.toLong() shr (i * 8)) and 0xFF).toInt())
            }
        }
        output.write(payload)
        output.flush()
    }

    private fun readClientFrame(input: InputStream): Pair<Int, ByteArray> {
        val b0 = input.read()
        val opcode = b0 and 0x0F
        val b1 = input.read()
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            val h = input.read()
            val l = input.read()
            len = (((h and 0xFF) shl 8) or (l and 0xFF)).toLong()
        }
        val maskKey = ByteArray(4)
        if (masked) {
            var off = 0
            while (off < 4) {
                val r = input.read(maskKey, off, 4 - off)
                off += r
            }
        }
        val payload = ByteArray(len.toInt())
        var off = 0
        while (off < payload.size) {
            val r = input.read(payload, off, payload.size - off)
            off += r
        }
        if (masked) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }
        return opcode to payload
    }

    private fun readHttpHeaders(input: InputStream): Pair<String, Map<String, String>> {
        val headers = mutableMapOf<String, String>()
        val lineBuf = ByteArrayOutputStream()
        var requestLine: String? = null

        fun nextLine(): String {
            lineBuf.reset()
            var prev = -1
            while (true) {
                val b = input.read()
                if (b == -1 || b == '\n'.code) {
                    val bytes = lineBuf.toByteArray()
                    val len = if (prev == '\r'.code && bytes.isNotEmpty()) bytes.size - 1 else bytes.size
                    return String(bytes, 0, len, Charsets.US_ASCII)
                }
                lineBuf.write(b)
                prev = b
            }
        }

        requestLine = nextLine()
        while (true) {
            val line = nextLine()
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx != -1) {
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                headers[name] = value
            }
        }
        return (requestLine ?: "") to headers
    }

    @Test
    fun `handshake carries 9 authentication headers and websocket upgrade headers`() {
        val server = ServerSocket(0)
        val port = server.localPort

        val handshakeReceived = CountDownLatch(1)
        var receivedRequestLine = ""
        val receivedHeaders = mutableMapOf<String, String>()

        val serverThread = thread {
            val client = server.accept()
            val (reqLine, headers) = readHttpHeaders(client.getInputStream())
            receivedRequestLine = reqLine
            receivedHeaders.putAll(headers)

            // Send 101 response
            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"
            client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
            client.getOutputStream().flush()

            handshakeReceived.countDown()

            // Send one text frame
            val jsonEvent = "{\"id\":\"evt_10\",\"event\":\"test.event\",\"data\":\"hello ws\"}"
            writeServerFrame(client.getOutputStream(), 0x01, jsonEvent.toByteArray(Charsets.UTF_8))

            client.close()
            server.close()
        }

        val transport = GatewayWebSocketTransport(
            profile = testProfile(port),
            signer = { ByteArray(64) { 0x07 } },
        )

        val events = runBlocking {
            transport.events("cur_0").take(1).toList()
        }

        assertTrue(handshakeReceived.await(5, TimeUnit.SECONDS))
        serverThread.join(5000)

        // Verify request line
        assertTrue(receivedRequestLine.startsWith("GET /open-android-intelligence/v2/events?cursor=cur_0 HTTP/1.1"))

        // Verify upgrade headers
        assertTrue(receivedHeaders["upgrade"].equals("websocket", ignoreCase = true))
        assertTrue(receivedHeaders["connection"].equals("upgrade", ignoreCase = true))
        assertTrue(receivedHeaders.containsKey("sec-websocket-key"))
        assertEquals("13", receivedHeaders["sec-websocket-version"])

        // Verify 9 authentication singletons
        assertEquals("Bearer test_token", receivedHeaders["authorization"])
        assertEquals("2.0", receivedHeaders["x-open-android-intelligence-protocol"])
        assertEquals("acc_test", receivedHeaders["x-open-android-intelligence-account"])
        assertEquals("dev_test", receivedHeaders["x-open-android-intelligence-device"])
        assertEquals("sess_test", receivedHeaders["x-open-android-intelligence-session"])
        assertTrue(receivedHeaders.containsKey("x-open-android-intelligence-request-id"))
        assertTrue(receivedHeaders.containsKey("x-open-android-intelligence-timestamp"))
        assertTrue(receivedHeaders.containsKey("x-open-android-intelligence-nonce"))
        assertTrue(receivedHeaders.containsKey("x-open-android-intelligence-signature"))

        // Verify event was parsed and emitted
        assertEquals(1, events.size)
        assertEquals("evt_10", events[0].id)
        assertEquals("test.event", events[0].event)
        assertEquals("hello ws", events[0].data)
    }

    @Test
    fun `server ping triggers automatic masked pong reply`() {
        val server = ServerSocket(0)
        val port = server.localPort

        val pongReceived = CountDownLatch(1)
        var pongOpcode = -1
        var pongPayload = ByteArray(0)

        val serverThread = thread {
            val client = server.accept()
            readHttpHeaders(client.getInputStream())

            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n\r\n"
            client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
            client.getOutputStream().flush()

            // Send Ping with payload
            val pingBytes = "heartbeat_ping".toByteArray(Charsets.UTF_8)
            writeServerFrame(client.getOutputStream(), 0x09, pingBytes)

            // Read Pong from client
            val (opcode, payload) = readClientFrame(client.getInputStream())
            pongOpcode = opcode
            pongPayload = payload
            pongReceived.countDown()

            // Send closing text event
            val jsonEvent = "{\"id\":\"evt_after_ping\",\"event\":\"ok\",\"data\":\"{}\"}"
            writeServerFrame(client.getOutputStream(), 0x01, jsonEvent.toByteArray(Charsets.UTF_8))

            client.close()
            server.close()
        }

        val transport = GatewayWebSocketTransport(
            profile = testProfile(port),
            signer = { ByteArray(64) },
        )

        val events = runBlocking {
            transport.events().take(1).toList()
        }

        assertTrue(pongReceived.await(5, TimeUnit.SECONDS))
        serverThread.join(5000)

        assertEquals(0x0A, pongOpcode) // 0x0A is Pong
        assertEquals("heartbeat_ping", String(pongPayload, Charsets.UTF_8))
        assertEquals("evt_after_ping", events[0].id)
    }

    @Test
    fun `fragmented text frames are reassembled into a single event`() {
        val server = ServerSocket(0)
        val port = server.localPort

        val serverThread = thread {
            val client = server.accept()
            readHttpHeaders(client.getInputStream())

            val response = "HTTP/1.1 101 Switching Protocols\r\n\r\n"
            client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
            client.getOutputStream().flush()

            val fullText = "{\"id\":\"frag_1\",\"event\":\"msg\",\"data\":\"long_payload_text\"}"
            val part1 = fullText.substring(0, 15).toByteArray(Charsets.UTF_8)
            val part2 = fullText.substring(15).toByteArray(Charsets.UTF_8)

            // Part 1: Opcode Text, FIN = false
            writeServerFrame(client.getOutputStream(), 0x01, part1, fin = false)
            // Part 2: Opcode Continuation, FIN = true
            writeServerFrame(client.getOutputStream(), 0x00, part2, fin = true)

            client.close()
            server.close()
        }

        val transport = GatewayWebSocketTransport(
            profile = testProfile(port),
            signer = { ByteArray(64) },
        )

        val events = runBlocking {
            transport.events().take(1).toList()
        }

        serverThread.join(5000)

        assertEquals(1, events.size)
        assertEquals("frag_1", events[0].id)
        assertEquals("msg", events[0].event)
        assertEquals("long_payload_text", events[0].data)
    }

    @Test
    fun `plain ws connection with pins fails security classification`() {
        val profile = GatewayProfile(
            accountId = "acc_test",
            deviceId = "dev_test",
            sessionId = "sess_test",
            gatewayBaseUrl = "https://127.0.0.1:12345",
            pinnedSpkiSha256 = setOf("sha256:0000000000000000000000000000000000000000000000000000000000000000"),
            accessToken = "test_token",
        )
        val transport = GatewayWebSocketTransport(
            profile = profile,
            signer = { ByteArray(64) },
            socketFactory = { _, _, _ -> Socket() },
        )

        val result = runCatching {
            runBlocking {
                transport.events().toList()
            }
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("PIN_REQUIRES_HTTPS") == true)
    }

    @Test
    fun `handshake failure with status 404 throws IOException`() {
        val server = ServerSocket(0)
        val port = server.localPort

        val serverThread = thread {
            val client = server.accept()
            readHttpHeaders(client.getInputStream())

            val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
            client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
            client.getOutputStream().flush()

            client.close()
            server.close()
        }

        val transport = GatewayWebSocketTransport(
            profile = testProfile(port),
            signer = { ByteArray(64) },
        )

        val result = runCatching {
            runBlocking {
                transport.events().toList()
            }
        }

        serverThread.join(5000)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("WEBSOCKET_HANDSHAKE_FAILED") == true)
    }
}
