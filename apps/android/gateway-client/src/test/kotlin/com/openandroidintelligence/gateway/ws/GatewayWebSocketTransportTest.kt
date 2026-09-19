package com.openandroidintelligence.gateway.ws

import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.http.GatewayProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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

    @Test
    fun `coroutine cancellation immediately closes underlying socket`() {
        val server = ServerSocket(0)
        val port = server.localPort

        val connectedLatch = CountDownLatch(1)
        val disconnectedLatch = CountDownLatch(1)

        val serverThread = thread {
            try {
                val client = server.accept()
                readHttpHeaders(client.getInputStream())

                val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n\r\n"
                client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                client.getOutputStream().flush()

                connectedLatch.countDown()

                try {
                    val b = client.getInputStream().read()
                    if (b == -1) {
                        disconnectedLatch.countDown()
                    }
                } catch (e: Exception) {
                    disconnectedLatch.countDown()
                } finally {
                    client.close()
                }
            } catch (e: Exception) {
                // Ignore
            } finally {
                server.close()
            }
        }

        val transport = GatewayWebSocketTransport(
            profile = testProfile(port),
            signer = { ByteArray(64) },
        )

        runBlocking {
            val job = launch(Dispatchers.IO) {
                transport.events().collect { }
            }
            assertTrue(connectedLatch.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue("Socket should be closed upon cancellation", disconnectedLatch.await(5, TimeUnit.SECONDS))
        }

        serverThread.join(5000)
    }

    @Test
    fun `ipv6 host is wrapped in brackets for http host header`() {
        val server = ServerSocket(0)
        val port = server.localPort

        val handshakeReceived = CountDownLatch(1)
        var receivedHost = ""

        val serverThread = thread {
            val client = server.accept()
            val (_, headers) = readHttpHeaders(client.getInputStream())
            receivedHost = headers["host"] ?: ""

            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n\r\n"
            client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
            client.getOutputStream().flush()
            handshakeReceived.countDown()

            val jsonEvent = "{\"id\":\"evt_ipv6\",\"event\":\"test\",\"data\":\"\"}"
            writeServerFrame(client.getOutputStream(), 0x01, jsonEvent.toByteArray(Charsets.UTF_8))

            client.close()
            server.close()
        }

        val profile = GatewayProfile(
            accountId = "acc_test",
            deviceId = "dev_test",
            sessionId = "sess_test",
            gatewayBaseUrl = "http://[::1]:$port",
            accessToken = "test_token",
        )
        val transport = GatewayWebSocketTransport(
            profile = profile,
            signer = { ByteArray(64) },
            socketFactory = { _, _, _ -> Socket("127.0.0.1", port) },
        )

        val events = runBlocking {
            transport.events().take(1).toList()
        }

        assertTrue(handshakeReceived.await(5, TimeUnit.SECONDS))
        serverThread.join(5000)

        assertEquals("[::1]:$port", receivedHost)
        assertEquals(1, events.size)
    }

    @Test
    fun `cursor query parameter is url encoded in target and signed`() {
        val server = ServerSocket(0)
        val port = server.localPort

        val handshakeReceived = CountDownLatch(1)
        var receivedRequestLine = ""

        val serverThread = thread {
            val client = server.accept()
            val (reqLine, _) = readHttpHeaders(client.getInputStream())
            receivedRequestLine = reqLine

            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n\r\n"
            client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
            client.getOutputStream().flush()
            handshakeReceived.countDown()

            val jsonEvent = "{\"id\":\"evt_1\",\"event\":\"test\",\"data\":\"\"}"
            writeServerFrame(client.getOutputStream(), 0x01, jsonEvent.toByteArray(Charsets.UTF_8))

            client.close()
            server.close()
        }

        val transport = GatewayWebSocketTransport(
            profile = testProfile(port),
            signer = { ByteArray(64) },
        )

        val cursor = "cur:100/v1"
        val events = runBlocking {
            transport.events(cursor).take(1).toList()
        }

        assertTrue(handshakeReceived.await(5, TimeUnit.SECONDS))
        serverThread.join(5000)

        assertTrue(receivedRequestLine.startsWith("GET /open-android-intelligence/v2/events?cursor=cur%3A100%2Fv1 HTTP/1.1"))
        assertEquals(1, events.size)
    }

    @Test
    fun `verifyAcceptHeader true succeeds with valid accept header`() {
        val server = ServerSocket(0)
        val port = server.localPort

        val serverThread = thread {
            val client = server.accept()
            val (_, headers) = readHttpHeaders(client.getInputStream())
            val clientKey = headers["sec-websocket-key"] ?: ""
            val expectedAccept = GatewayWebSocketTransport.computeSecWebSocketAccept(clientKey)

            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $expectedAccept\r\n\r\n"
            client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
            client.getOutputStream().flush()

            val jsonEvent = "{\"id\":\"evt_ok\",\"event\":\"msg\",\"data\":\"hello\"}"
            writeServerFrame(client.getOutputStream(), 0x01, jsonEvent.toByteArray(Charsets.UTF_8))

            client.close()
            server.close()
        }

        val transport = GatewayWebSocketTransport(
            profile = testProfile(port),
            signer = { ByteArray(64) },
            verifyAcceptHeader = true,
        )

        val events = runBlocking {
            transport.events().take(1).toList()
        }

        serverThread.join(5000)
        assertEquals(1, events.size)
        assertEquals("evt_ok", events[0].id)
    }

    @Test
    fun `verifyAcceptHeader true fails when upgrade header is missing or invalid`() {
        val server = ServerSocket(0)
        val port = server.localPort

        val serverThread = thread {
            val client = server.accept()
            val (_, headers) = readHttpHeaders(client.getInputStream())
            val clientKey = headers["sec-websocket-key"] ?: ""
            val expectedAccept = GatewayWebSocketTransport.computeSecWebSocketAccept(clientKey)

            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: wrong-proto\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $expectedAccept\r\n\r\n"
            client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
            client.getOutputStream().flush()

            client.close()
            server.close()
        }

        val transport = GatewayWebSocketTransport(
            profile = testProfile(port),
            signer = { ByteArray(64) },
            verifyAcceptHeader = true,
        )

        val result = runCatching {
            runBlocking {
                transport.events().toList()
            }
        }

        serverThread.join(5000)
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue("Expected upgrade header error, got: $msg", msg.contains("missing or invalid Upgrade header"))
    }

    @Test
    fun `verifyAcceptHeader true fails when accept header does not match key digest`() {
        val server = ServerSocket(0)
        val port = server.localPort

        val serverThread = thread {
            val client = server.accept()
            readHttpHeaders(client.getInputStream())

            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: invalidAcceptValue=\r\n\r\n"
            client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
            client.getOutputStream().flush()

            client.close()
            server.close()
        }

        val transport = GatewayWebSocketTransport(
            profile = testProfile(port),
            signer = { ByteArray(64) },
            verifyAcceptHeader = true,
        )

        val result = runCatching {
            runBlocking {
                transport.events().toList()
            }
        }

        serverThread.join(5000)
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue("Expected Sec-WebSocket-Accept mismatch error, got: $msg", msg.contains("Sec-WebSocket-Accept mismatch"))
    }

    @Test
    fun `readFrame rejects frame with non-zero rsv bits`() {
        val server = ServerSocket(0)
        val port = server.localPort

        val serverThread = thread {
            val client = server.accept()
            readHttpHeaders(client.getInputStream())

            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n\r\n"
            client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
            client.getOutputStream().flush()

            // Frame with RSV1 set (0x40): b0 = 0xC1 (FIN=1, RSV1=1, Opcode=1)
            val output = client.getOutputStream()
            output.write(0xC1)
            output.write(0x00)
            output.flush()

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
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue("Expected RSV bits error, got: $msg", msg.contains("WEBSOCKET_PROTOCOL_ERROR: RSV bits must be 0"))
    }

    @Test
    fun `readFrame rejects 64-bit payload length overflow or negative length`() {
        val server = ServerSocket(0)
        val port = server.localPort

        val serverThread = thread {
            val client = server.accept()
            readHttpHeaders(client.getInputStream())

            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n\r\n"
            client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
            client.getOutputStream().flush()

            // 64-bit payload length with MSB set (negative signed Long)
            val output = client.getOutputStream()
            output.write(0x81) // FIN=1, Opcode=1
            output.write(127)  // 64-bit length
            output.write(0x80) // MSB = 1
            for (i in 0 until 7) {
                output.write(0x00)
            }
            output.flush()

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
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue("Expected payload length invalid error, got: $msg", msg.contains("WebSocket frame payload length invalid"))
    }

    @Test
    fun `sendPong truncates payload exceeding 125 bytes`() {
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

            // Ping with 150 bytes payload (> 125)
            val oversizedPayload = ByteArray(150) { it.toByte() }
            writeServerFrame(client.getOutputStream(), 0x09, oversizedPayload)

            val (opcode, payload) = readClientFrame(client.getInputStream())
            pongOpcode = opcode
            pongPayload = payload
            pongReceived.countDown()

            val jsonEvent = "{\"id\":\"evt_done\",\"event\":\"ok\",\"data\":\"{}\"}"
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

        assertEquals(0x0A, pongOpcode)
        assertEquals(125, pongPayload.size)
        for (i in 0 until 125) {
            assertEquals(i.toByte(), pongPayload[i])
        }
        assertEquals("evt_done", events[0].id)
    }
}
