package com.openandroidintelligence.gateway.ws

import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.events.SseParser
import com.openandroidintelligence.gateway.http.GatewayConnectionSecurity
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Pure Kotlin RFC 6455 WebSocket transport for Gateway event streaming.
 *
 * Owned within gateway-client (networkOwnerModules): uses standard java.net.Socket
 * and javax.net.ssl.SSLSocket with zero external weight dependencies.
 *
 * Supports ws:// and wss:// with certificate classification / SPKI pin verification,
 * authenticated HTTP 101 Upgrade handshakes carrying the 9 contract singletons,
 * RFC 6455 frame decoding, automatic Pong reply to incoming Pings, and emission of
 * parsed GatewayEvents over Flow<GatewayEvent>.
 */
open class GatewayWebSocketTransport(
    private val profile: GatewayProfile,
    private val signer: (ByteArray) -> ByteArray,
    private val socketFactory: ((host: String, port: Int, isTls: Boolean) -> Socket)? = null,
    private val verifyAcceptHeader: Boolean = true,
) {
    private val secureRandom = SecureRandom()

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    open fun events(cursor: String? = null): Flow<GatewayEvent> = flow {
        val uri = URI(profile.gatewayBaseUrl)
        val scheme = uri.scheme?.lowercase() ?: "http"
        val isTls = scheme == "wss" || scheme == "https"
        val host = uri.host ?: error("GATEWAY_ENDPOINT_INVALID: missing host in ${profile.gatewayBaseUrl}")
        val port = if (uri.port != -1) uri.port else if (isTls) 443 else 80
        val formattedHost = if (host.contains(':') && !(host.startsWith('[') && host.endsWith(']'))) "[$host]" else host
        val hostHeader = if ((isTls && port == 443) || (!isTls && port == 80)) formattedHost else "$formattedHost:$port"

        val target = if (cursor == null) {
            EVENTS_TARGET
        } else {
            "$EVENTS_TARGET?cursor=${URLEncoder.encode(cursor, "UTF-8")}"
        }

        val socket: Socket = if (socketFactory != null) {
            socketFactory.invoke(host, port, isTls)
        } else if (isTls) {
            val plainSocket = Socket()
            try {
                plainSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
                val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(plainSocket, host, port, true) as SSLSocket
                val params = sslSocket.sslParameters ?: SSLParameters()
                params.endpointIdentificationAlgorithm = "HTTPS"
                sslSocket.sslParameters = params
                sslSocket
            } catch (t: Throwable) {
                runCatching { plainSocket.close() }
                throw t
            }
        } else {
            val plainSocket = Socket()
            try {
                plainSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
                plainSocket
            } catch (t: Throwable) {
                runCatching { plainSocket.close() }
                throw t
            }
        }
        socket.soTimeout = READ_TIMEOUT_MILLIS

        val job = currentCoroutineContext()[Job]
        val cancelHandle = job?.invokeOnCompletion(onCancelling = true) { runCatching { socket.close() } }

        try {
            // Verify TLS / pins
            GatewayConnectionSecurity.classify(socket, profile.pinnedSpkiSha256)

            val outputStream = socket.getOutputStream()
            val inputStream = socket.getInputStream()

            // Build authenticated handshake
            try {
                performHandshake(outputStream, inputStream, hostHeader, target)
            } catch (e: java.net.SocketException) {
                if (!currentCoroutineContext().isActive || socket.isClosed) return@flow
                throw e
            } catch (e: IOException) {
                if (!currentCoroutineContext().isActive || socket.isClosed) return@flow
                throw e
            }

            // RFC 6455 frame decode loop
            val messageBuffer = ByteArrayOutputStream()
            var currentOpcode = -1

            while (currentCoroutineContext().isActive) {
                val frame = try {
                    readFrame(inputStream) ?: break
                } catch (e: java.net.SocketTimeoutException) {
                    GatewayLog.w(TAG, "websocket stalled after ${READ_TIMEOUT_MILLIS}ms")
                    throw IOException("WEBSOCKET_STREAM_STALLED: no bytes received for ${READ_TIMEOUT_MILLIS}ms", e)
                } catch (e: java.net.SocketException) {
                    if (!currentCoroutineContext().isActive || socket.isClosed) break
                    GatewayLog.w(TAG, "websocket socket error: ${e.message}")
                    throw e
                } catch (e: IOException) {
                    if (!currentCoroutineContext().isActive || socket.isClosed) break
                    GatewayLog.w(TAG, "websocket io error: ${e.message}")
                    throw e
                }
                when (frame.opcode) {
                    OPCODE_PING -> {
                        sendPong(outputStream, frame.payload)
                    }
                    OPCODE_PONG -> {
                        // Heartbeat response, no action required
                    }
                    OPCODE_CLOSE -> {
                        runCatching { sendClose(outputStream) }
                        break
                    }
                    OPCODE_TEXT, OPCODE_BINARY -> {
                        if (currentOpcode != -1) {
                            throw IOException("WEBSOCKET_PROTOCOL_ERROR: received new data frame before completing fragmented message")
                        }
                        if (messageBuffer.size().toLong() + frame.payload.size.toLong() > MAX_PAYLOAD_BYTES) {
                            throw IOException("WEBSOCKET_BUFFER_OVERFLOW: frame size exceeds max payload bytes")
                        }
                        messageBuffer.write(frame.payload)
                        if (frame.fin) {
                            val text = messageBuffer.toString(Charsets.UTF_8.name())
                            parseWebSocketEvent(text)?.let { emit(it) }
                            messageBuffer.reset()
                            currentOpcode = -1
                        } else {
                            currentOpcode = frame.opcode
                        }
                    }
                    OPCODE_CONTINUATION -> {
                        if (currentOpcode == -1) {
                            throw IOException("WEBSOCKET_PROTOCOL_ERROR: received continuation frame without an active message")
                        }
                        if (messageBuffer.size().toLong() + frame.payload.size.toLong() > MAX_PAYLOAD_BYTES) {
                            throw IOException("WEBSOCKET_BUFFER_OVERFLOW: frame size exceeds max payload bytes")
                        }
                        messageBuffer.write(frame.payload)
                        if (frame.fin) {
                            val text = messageBuffer.toString(Charsets.UTF_8.name())
                            parseWebSocketEvent(text)?.let { emit(it) }
                            messageBuffer.reset()
                            currentOpcode = -1
                        }
                    }
                    else -> {
                        throw IOException("WEBSOCKET_PROTOCOL_ERROR: unknown or unsupported opcode ${frame.opcode}")
                    }
                }
            }
        } finally {
            cancelHandle?.dispose()
            runCatching { socket.close() }
        }
    }.flowOn(Dispatchers.IO)

    private fun performHandshake(
        output: OutputStream,
        input: InputStream,
        hostHeader: String,
        target: String,
    ) {
        val signedInput = GatewayHttpClient.signedInput(profile, "GET", target, ByteArray(0))
        val signatureBase64Url = GatewayHttpClient.signatureOf(signer, signedInput)
        val authHeaders = GatewayHttpClient.authenticationHeaders(profile, signedInput, signatureBase64Url, "GET")

        val keyBytes = ByteArray(16)
        secureRandom.nextBytes(keyBytes)
        val secWebSocketKey = Base64.getEncoder().encodeToString(keyBytes)

        val requestBuilder = StringBuilder()
        requestBuilder.append("GET $target HTTP/1.1\r\n")
        requestBuilder.append("Host: $hostHeader\r\n")
        requestBuilder.append("Upgrade: websocket\r\n")
        requestBuilder.append("Connection: Upgrade\r\n")
        requestBuilder.append("Sec-WebSocket-Key: $secWebSocketKey\r\n")
        requestBuilder.append("Sec-WebSocket-Version: 13\r\n")
        for (header in authHeaders) {
            requestBuilder.append("${header.name}: ${header.value}\r\n")
        }
        requestBuilder.append("\r\n")

        output.write(requestBuilder.toString().toByteArray(Charsets.US_ASCII))
        output.flush()

        val statusLine = readLine(input)
            ?: throw IOException("WEBSOCKET_HANDSHAKE_FAILED: unexpected end of stream")
        if (!statusLine.contains(" 101 ") && !statusLine.endsWith(" 101")) {
            throw IOException("WEBSOCKET_HANDSHAKE_FAILED: $statusLine")
        }

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colonIndex = line.indexOf(':')
            if (colonIndex != -1) {
                val name = line.substring(0, colonIndex).trim().lowercase()
                val value = line.substring(colonIndex + 1).trim()
                val existing = headers[name]
                headers[name] = if (existing == null) value else "$existing, $value"
            }
        }

        if (verifyAcceptHeader) {
            val upgrade = headers["upgrade"]
            if (upgrade == null || !upgrade.equals("websocket", ignoreCase = true)) {
                throw IOException("WEBSOCKET_HANDSHAKE_FAILED: missing or invalid Upgrade header: $upgrade")
            }
            val conn = headers["connection"]
            if (conn == null || !conn.split(',').any { it.trim().equals("upgrade", ignoreCase = true) }) {
                throw IOException("WEBSOCKET_HANDSHAKE_FAILED: missing or invalid Connection header: $conn")
            }
            val expectedAccept = computeSecWebSocketAccept(secWebSocketKey)
            val actualAccept = headers["sec-websocket-accept"]
            if (actualAccept != expectedAccept) {
                throw IOException("WEBSOCKET_HANDSHAKE_FAILED: Sec-WebSocket-Accept mismatch: expected $expectedAccept but got $actualAccept")
            }
        }
    }

    private fun readFrame(input: InputStream): WebSocketFrame? {
        try {
            val b0 = input.read()
            if (b0 == -1) GatewayLog.d(TAG, "websocket closed by peer")
            if (b0 == -1) return null
            if ((b0 and 0x70) != 0) {
                throw IOException("WEBSOCKET_PROTOCOL_ERROR: RSV bits must be 0")
            }
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F

            val b1 = input.read()
            if (b1 == -1) throw EOFException("Unexpected EOF reading WebSocket frame header")
            val masked = (b1 and 0x80) != 0
            if (masked) {
                throw IOException("WEBSOCKET_PROTOCOL_ERROR: server must not mask frames")
            }
            var payloadLen = (b1 and 0x7F).toLong()

            if (payloadLen == 126L) {
                val b2 = input.read()
                val b3 = input.read()
                if (b2 == -1 || b3 == -1) throw EOFException("Unexpected EOF reading 16-bit payload length")
                payloadLen = (((b2 and 0xFF) shl 8) or (b3 and 0xFF)).toLong()
            } else if (payloadLen == 127L) {
                var len = 0L
                for (i in 0 until 8) {
                    val b = input.read()
                    if (b == -1) throw EOFException("Unexpected EOF reading 64-bit payload length")
                    len = (len shl 8) or (b.toLong() and 0xFFL)
                }
                payloadLen = len
            }

            if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_BYTES) {
                throw IOException("WebSocket frame payload length invalid: $payloadLen bytes")
            }

            if (opcode >= 0x08) {
                if (!fin) {
                    throw IOException("WEBSOCKET_PROTOCOL_ERROR: control frames must not be fragmented")
                }
                if (payloadLen > 125) {
                    throw IOException("WEBSOCKET_PROTOCOL_ERROR: control frame payload exceeds 125 bytes: $payloadLen")
                }
            }

            val payload = ByteArray(payloadLen.toInt())
            readFully(input, payload)

            return WebSocketFrame(fin, opcode, payload)
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("WEBSOCKET_STREAM_STALLED: no bytes received for ${READ_TIMEOUT_MILLIS}ms", e)
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count == -1) throw EOFException("Unexpected EOF reading WebSocket payload")
            offset += count
        }
    }

    private fun sendPong(output: OutputStream, payload: ByteArray) {
        sendFrame(output, opcode = OPCODE_PONG, payload = payload)
    }

    private fun sendClose(output: OutputStream) {
        sendFrame(output, opcode = OPCODE_CLOSE, payload = ByteArray(0))
    }

    private fun sendFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        val b0 = 0x80 or (opcode and 0x0F)
        output.write(b0)

        val maskBit = 0x80
        val maskKey = ByteArray(4)
        secureRandom.nextBytes(maskKey)

        if (payload.size <= 125) {
            output.write(maskBit or payload.size)
        } else if (payload.size <= 65535) {
            output.write(maskBit or 126)
            output.write((payload.size shr 8) and 0xFF)
            output.write(payload.size and 0xFF)
        } else {
            output.write(maskBit or 127)
            for (i in 7 downTo 0) {
                output.write(((payload.size.toLong() shr (i * 8)) and 0xFF).toInt())
            }
        }

        output.write(maskKey)
        val masked = ByteArray(payload.size)
        for (i in payload.indices) {
            masked[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        }
        output.write(masked)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var last = -1
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (out.size() == 0) return null
                break
            }
            if (b == '\n'.code) {
                val bytes = out.toByteArray()
                val len = if (last == '\r'.code && bytes.isNotEmpty()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.US_ASCII)
            }
            out.write(b)
            if (out.size() > MAX_HEADER_LINE_BYTES) {
                throw IOException("WEBSOCKET_HANDSHAKE_FAILED: header line exceeds $MAX_HEADER_LINE_BYTES bytes")
            }
            last = b
        }
        val bytes = out.toByteArray()
        val len = if (last == '\r'.code && bytes.isNotEmpty()) bytes.size - 1 else bytes.size
        return String(bytes, 0, len, Charsets.US_ASCII)
    }

    companion object {
        const val PROTOCOL_HEADER = "2.0"
        /**
         * The upgrade target the Gateway serves WebSockets on.
         *
         * The plain `/events` target is the SSE route: asking it to upgrade
         * never yields a 101, so the channel used to fail and silently fall
         * back on every single attempt.
         */
        const val EVENTS_TARGET = "/open-android-intelligence/v2/events/ws"
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        /**
         * Longest silence tolerated on an open socket. The Gateway heartbeats
         * every 15s, so this is one heartbeat plus slack: a half-open socket is
         * detected in seconds instead of after 45s of silence.
         */
        const val READ_TIMEOUT_MILLIS = 20_000
        const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
        const val MAX_HEADER_LINE_BYTES = 8192
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val TAG = "GatewayWs"

        const val OPCODE_CONTINUATION = 0x00
        const val OPCODE_TEXT = 0x01
        const val OPCODE_BINARY = 0x02
        const val OPCODE_CLOSE = 0x08
        const val OPCODE_PING = 0x09
        const val OPCODE_PONG = 0x0A

        fun computeSecWebSocketAccept(secWebSocketKey: String): String {
            val sha1 = MessageDigest.getInstance("SHA-1")
            val digest = sha1.digest((secWebSocketKey + WEBSOCKET_GUID).toByteArray(Charsets.US_ASCII))
            return Base64.getEncoder().encodeToString(digest)
        }

        fun parseWebSocketEvent(text: String): GatewayEvent? {
            val trimmed = text.trim()
            if (trimmed.startsWith("{")) {
                return runCatching {
                    val json = JsonFields.obj(Json.parse(trimmed)) ?: return null
                    val id = JsonFields.string(json, "id")
                    val event = JsonFields.string(json, "event")
                    val dataValue = JsonFields.field(json, "data")
                    val data = when (dataValue) {
                        is JsonValue.JString -> dataValue.value
                        is JsonValue.JObject, is JsonValue.JArray -> Json.canonical(dataValue)
                        is JsonValue.JNumber -> dataValue.raw
                        is JsonValue.JBool -> dataValue.value.toString()
                        is JsonValue.JNull, null -> ""
                    }
                    GatewayEvent(id = id, event = event, data = data)
                }.getOrNull()
            }
            var parsedEvent: GatewayEvent? = null
            val parser = SseParser { parsedEvent = it }
            val sseInput = if (trimmed.endsWith("\n\n")) trimmed else "$trimmed\n\n"
            parser.feed(sseInput)
            return parsedEvent
        }
    }

    private class WebSocketFrame(
        val fin: Boolean,
        val opcode: Int,
        val payload: ByteArray,
    )
}
