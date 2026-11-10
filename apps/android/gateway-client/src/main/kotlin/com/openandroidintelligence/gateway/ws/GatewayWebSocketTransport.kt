package com.openandroidintelligence.gateway.ws

import com.openandroidintelligence.gateway.auth.GatewaySessionRenewal
import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.events.SseParser
import com.openandroidintelligence.gateway.http.EventCursorExpiredException
import com.openandroidintelligence.gateway.http.EventSessionRejectedException
import com.openandroidintelligence.gateway.http.EventStreamForbiddenException
import com.openandroidintelligence.gateway.http.GatewayConnectionSecurity
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Transport health is independent of business delivery and never fabricates a GatewayEvent. */
sealed interface GatewayWebSocketSignal {
    val atMillis: Long
    data class Ready(override val atMillis: Long) : GatewayWebSocketSignal
    data class Activity(override val atMillis: Long) : GatewayWebSocketSignal
    data class Closed(val code: Int, override val atMillis: Long) : GatewayWebSocketSignal
}

class GatewayWebSocketHandshakeException(val status: Int) : IOException("WEBSOCKET_HANDSHAKE_FAILED:$status") {
    val upgradeUnsupported: Boolean get() = status in setOf(404, 405, 426, 501)
}
class GatewayWebSocketPayloadException : IOException("WEBSOCKET_PAYLOAD_INVALID")
class GatewayWebSocketClosedException(val closeCode: Int) : IOException("WEBSOCKET_CLOSED:$closeCode")
enum class GatewayWebSocketTimeoutStage { HANDSHAKE, IDLE, PROBE }
class GatewayWebSocketTimeoutException(val stage: GatewayWebSocketTimeoutStage, idleMillis: Long) : IOException(
    when (stage) {
        GatewayWebSocketTimeoutStage.HANDSHAKE -> "WEBSOCKET_HANDSHAKE_TIMEOUT"
        GatewayWebSocketTimeoutStage.IDLE -> "WEBSOCKET_STREAM_STALLED: no complete frame received for ${idleMillis}ms"
        GatewayWebSocketTimeoutStage.PROBE -> "WEBSOCKET_PROBE_TIMEOUT"
    },
)

/** RFC 6455 event delivery and recovery probes share the same TLS/authenticated socket path. */
open class GatewayWebSocketTransport(
    private val profile: GatewayProfile,
    private val signer: (ByteArray) -> ByteArray,
    /** Test boundary; a supplied factory returns a socket ready for TLS classification. */
    private val socketFactory: ((host: String, port: Int, isTls: Boolean) -> Socket)? = null,
    private val verifyAcceptHeader: Boolean = true,
    private var sessionRenewal: GatewaySessionRenewal? = null,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val handshakeTimeoutMillis: Long = CONNECT_TIMEOUT_MILLIS.toLong(),
    private val idleTimeoutMillis: Long = READ_TIMEOUT_MILLIS.toLong(),
    private val probeTimeoutMillis: Long = PROBE_TIMEOUT_MILLIS.toLong(),
    private val foregroundPingIntervalMillis: Long = 5_000,
    private val healthCheckTimeoutMillis: Long = 3_000,
) {
    init {
        sessionRenewal?.requireScope(profile)
        require(handshakeTimeoutMillis > 0 && idleTimeoutMillis > 0 && probeTimeoutMillis > 0 &&
            foregroundPingIntervalMillis > 0 && healthCheckTimeoutMillis > 0)
    }

    @Volatile private var appForeground = false
    private val formalConnection = AtomicReference<Connection?>(null)

    /** 前台使用原连接的控制帧验证双向可达；后台保留原有低频失活预算。 */
    fun setAppForeground(visible: Boolean) {
        val changed = appForeground != visible
        appForeground = visible
        if (changed && visible) requestHealthCheck()
    }

    fun requestHealthCheck(): Boolean = formalConnection.get()?.checks?.trySend(Unit)?.isSuccess == true

    internal fun bindSessionRenewal(renewal: GatewaySessionRenewal) {
        renewal.requireScope(profile)
        check(sessionRenewal == null || sessionRenewal === renewal) { "WEBSOCKET_SESSION_OWNER_CHANGED" }
        sessionRenewal = renewal
    }

    private val secureRandom = SecureRandom()

    // Keep both existing entry points. Owners that need readiness use the typed overload.
    open fun events(cursor: String? = null): Flow<GatewayEvent> = events(cursor, {})

    open fun events(cursor: String?, onActivity: () -> Unit, sessionSnapshot: GatewayProfile? = null): Flow<GatewayEvent> =
        events(cursor, onActivity, sessionSnapshot, {})

    open fun events(
        cursor: String?,
        onActivity: () -> Unit,
        sessionSnapshot: GatewayProfile?,
        onSignal: (GatewayWebSocketSignal) -> Unit,
    ): Flow<GatewayEvent> = flow {
        val snapshot = checkedSnapshot(sessionSnapshot)
        val target = if (cursor == null) EVENTS_TARGET else "$EVENTS_TARGET?cursor=${URLEncoder.encode(cursor, "UTF-8")}"
        withConnection(snapshot, target, probe = false) { connection ->
            onSignal(GatewayWebSocketSignal.Ready(monotonicMillis()))
            val message = ByteArrayOutputStream()
            var currentOpcode = -1
            while (currentCoroutineContext().isActive) {
                val frame = readFrame(connection.input)
                if (frame == null) {
                    onSignal(GatewayWebSocketSignal.Closed(1006, monotonicMillis()))
                    if (currentOpcode != -1) throw EOFException("WEBSOCKET_MESSAGE_INCOMPLETE")
                    break
                }
                when (frame.opcode) {
                    OPCODE_PING -> sendFrame(connection.output, OPCODE_PONG, frame.payload)
                    OPCODE_PONG -> connection.pongs.trySend(frame.payload)
                    OPCODE_CLOSE -> {
                        val code = closeCode(frame.payload)
                        onSignal(GatewayWebSocketSignal.Closed(code, monotonicMillis()))
                        rejectClose(code, snapshot)
                        runCatching { sendFrame(connection.output, OPCODE_CLOSE, frame.payload) }
                        if (code != 1000 && code != 1005) throw GatewayWebSocketClosedException(code)
                        break
                    }
                    OPCODE_TEXT, OPCODE_BINARY, OPCODE_CONTINUATION -> {
                        if (frame.opcode == OPCODE_CONTINUATION) {
                            if (currentOpcode == -1) throw IOException("WEBSOCKET_PROTOCOL_ERROR: received continuation frame without an active message")
                        } else {
                            if (currentOpcode != -1) throw IOException("WEBSOCKET_PROTOCOL_ERROR: received new data frame before completing fragmented message")
                            connection.deadline.messageStarted()
                        }
                        if (message.size().toLong() + frame.payload.size > MAX_PAYLOAD_BYTES) {
                            throw IOException("WEBSOCKET_BUFFER_OVERFLOW")
                        }
                        message.write(frame.payload)
                        if (frame.fin) {
                            // Parse before activity or delivery: malformed messages cannot become silent cursor gaps.
                            val event = parseWebSocketEvent(decodeUtf8(message.toByteArray()))
                            message.reset()
                            currentOpcode = -1
                            connection.deadline.messageCompleted()
                            connection.deadline.activity()
                            // Preserve the legacy callback's heartbeat semantics for the renewal fence.
                            if (event == null) onActivity()
                            onSignal(GatewayWebSocketSignal.Activity(monotonicMillis()))
                            if (event != null) {
                                connection.delivering = true
                                try { emit(event) } finally {
                                    connection.deadline.activity()
                                    connection.delivering = false
                                }
                            }
                            continue
                        }
                        if (frame.opcode != OPCODE_CONTINUATION) currentOpcode = frame.opcode
                    }
                }
                // Only a fully decoded, valid frame extends idle time; a fragmented message has its own deadline.
                connection.deadline.activity()
                if (frame.opcode == OPCODE_PING || frame.opcode == OPCODE_PONG) onActivity()
                onSignal(GatewayWebSocketSignal.Activity(monotonicMillis()))
            }
        }
    }.flowOn(Dispatchers.IO)

    /** No event subscription, cursor or business payload: only our control Ping's exact Pong proves recovery. */
    open suspend fun probe(sessionSnapshot: GatewayProfile? = null): GatewayProfile = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val snapshot = checkedSnapshot(sessionSnapshot)
        withConnection(snapshot, PROBE_TARGET, probe = true) { connection ->
            val nonce = ByteArray(32).also(secureRandom::nextBytes)
            sendFrame(connection.output, OPCODE_PING, nonce)
            while (true) {
                currentCoroutineContext().ensureActive()
                val frame = readFrame(connection.input) ?: throw GatewayWebSocketClosedException(1006)
                when (frame.opcode) {
                    OPCODE_PING -> sendFrame(connection.output, OPCODE_PONG, frame.payload)
                    OPCODE_PONG -> if (MessageDigest.isEqual(nonce, frame.payload)) break
                    OPCODE_CLOSE -> {
                        val code = closeCode(frame.payload)
                        rejectClose(code, snapshot)
                        throw GatewayWebSocketClosedException(code)
                    }
                    else -> throw IOException("WEBSOCKET_PROBE_UNEXPECTED_DATA")
                }
            }
            runCatching { sendFrame(connection.output, OPCODE_CLOSE, byteArrayOf(3, 0xE8.toByte())) }
        }
        // withConnection has closed the probe before its successful result reaches the SSE owner.
        snapshot
    }

    private fun checkedSnapshot(supplied: GatewayProfile?): GatewayProfile {
        val snapshot = supplied ?: sessionRenewal?.current ?: profile
        require(snapshot.accountId == profile.accountId && snapshot.deviceId == profile.deviceId &&
            snapshot.gatewayBaseUrl == profile.gatewayBaseUrl && snapshot.pinnedSpkiSha256 == profile.pinnedSpkiSha256) {
            "WEBSOCKET_SESSION_SCOPE_CHANGED"
        }
        return snapshot
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun <T> withConnection(
        snapshot: GatewayProfile,
        target: String,
        probe: Boolean,
        consume: suspend (Connection) -> T,
    ): T = coroutineScope {
        val context = currentCoroutineContext()
        context.ensureActive()
        val uri = URI(snapshot.gatewayBaseUrl.trim())
        val isTls = uri.scheme.equals("https", true)
        val host = requireNotNull(uri.host) { "GATEWAY_ENDPOINT_INVALID" }
        val port = if (uri.port != -1) uri.port else if (isTls) 443 else 80
        val hostName = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        val hostHeader = if (port == if (isTls) 443 else 80) hostName else "$hostName:$port"
        val socketRef = AtomicReference<Socket?>()
        val healthFailure = AtomicReference<IOException?>()
        var connection: Connection? = null
        var healthJob: Job? = null
        val deadline = Deadline(probe)
        // Register before creating/connecting the default socket, including the TCP and TLS handshake windows.
        val cancelHandle = context[Job]?.invokeOnCompletion(onCancelling = true) { runCatching { socketRef.get()?.close() } }
        fun install(socket: Socket): Socket {
            socketRef.set(socket)
            if (!context.isActive) runCatching { socket.close() }
            context.ensureActive()
            deadline.check()
            return socket
        }
        val watchdog = launch(Dispatchers.Default) {
            while (isActive) {
                val remaining = deadline.remaining()
                if (remaining <= 0) {
                    runCatching { socketRef.get()?.close() }
                    break
                }
                delay(remaining)
            }
        }
        try {
            val socket = if (socketFactory != null) {
                install(socketFactory.invoke(host, port, isTls))
            } else {
                val plain = install(Socket())
                plain.connect(InetSocketAddress(host, port), deadline.readTimeout())
                if (isTls) {
                    val ssl = install((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true)) as SSLSocket
                    ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    ssl
                } else plain
            }
            socket.soTimeout = deadline.readTimeout()
            GatewayConnectionSecurity.classify(socket, snapshot.pinnedSpkiSha256)
            val input = object : FilterInputStream(socket.getInputStream()) {
                private fun <R> bounded(read: () -> R): R {
                    context.ensureActive()
                    socket.soTimeout = deadline.readTimeout()
                    val value = try { read() } catch (_: SocketTimeoutException) { throw deadline.timeout() }
                    deadline.check()
                    return value
                }
                override fun read(): Int = bounded { super.read() }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = bounded { `in`.read(buffer, offset, length) }
            }
            val output = socket.getOutputStream()
            performHandshake(snapshot, output, input, hostHeader, target)
            deadline.ready()
            context.ensureActive()
            val ready = Connection(input, output, deadline)
            connection = ready
            if (!probe) {
                formalConnection.set(ready)
                if (appForeground) ready.checks.trySend(Unit)
                healthJob = launch(Dispatchers.IO) {
                    while (isActive) {
                        val requested = if (appForeground) {
                            withTimeoutOrNull(foregroundPingIntervalMillis) { ready.checks.receive(); true } == true
                        } else { ready.checks.receive(); true }
                        if (!requested && !appForeground) continue
                        if (ready.delivering) continue
                        val nonce = ByteArray(32).also(secureRandom::nextBytes)
                        // 先启动独立超时器，避免写入被半开连接无限阻塞。
                        val timeout = launch(Dispatchers.Default) {
                            delay(healthCheckTimeoutMillis)
                            if (!ready.delivering) {
                                healthFailure.compareAndSet(null, GatewayWebSocketTimeoutException(
                                    GatewayWebSocketTimeoutStage.IDLE, healthCheckTimeoutMillis))
                                runCatching { socket.close() }
                            }
                        }
                        try {
                            sendFrame(output, OPCODE_PING, nonce)
                            val proved = withTimeoutOrNull(healthCheckTimeoutMillis) {
                                while (!MessageDigest.isEqual(nonce, ready.pongs.receive())) Unit
                                true
                            } == true
                            if (!proved) {
                                if (ready.delivering) continue
                                healthFailure.compareAndSet(null, GatewayWebSocketTimeoutException(
                                    GatewayWebSocketTimeoutStage.IDLE, healthCheckTimeoutMillis))
                                runCatching { socket.close() }
                                break
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (failure: IOException) {
                            healthFailure.compareAndSet(null, failure)
                            runCatching { socket.close() }
                            break
                        } finally { timeout.cancel() }
                    }
                }
            }
            val result = consume(ready)
            healthFailure.get()?.let { throw it }
            result
        } catch (failure: IOException) {
            context.ensureActive()
            if (!GatewayConnectionSecurity.isSecurityFailure(failure) && failure !is EventSessionRejectedException &&
                failure !is EventStreamForbiddenException) healthFailure.get()?.let { throw it }
            if (!GatewayConnectionSecurity.isSecurityFailure(failure) && deadline.remaining() <= 0) throw deadline.timeout()
            throw failure
        } finally {
            connection?.let {
                formalConnection.compareAndSet(it, null)
                healthJob?.cancel()
                it.checks.close()
                it.pongs.close()
            }
            runCatching { socketRef.get()?.close() }
            cancelHandle?.dispose()
            watchdog.cancel()
        }
    }

    /** Absolute monotonic deadlines also bound byte drips, fragmented messages and blocked writes. */
    private inner class Deadline(private val probe: Boolean) {
        private val started = monotonicMillis()
        @Volatile private var handshaking = true
        @Volatile private var idleAt = started + handshakeTimeoutMillis
        @Volatile private var messageAt = Long.MAX_VALUE
        private val totalAt = if (probe) started + probeTimeoutMillis else Long.MAX_VALUE
        fun remaining(): Long = minOf(idleAt, messageAt, totalAt) - monotonicMillis()
        fun timeout(): GatewayWebSocketTimeoutException = GatewayWebSocketTimeoutException(
            when {
                probe -> GatewayWebSocketTimeoutStage.PROBE
                handshaking -> GatewayWebSocketTimeoutStage.HANDSHAKE
                else -> GatewayWebSocketTimeoutStage.IDLE
            }, idleTimeoutMillis,
        )
        fun check() { if (remaining() <= 0) throw timeout() }
        fun readTimeout(): Int { check(); return remaining().coerceIn(1, Int.MAX_VALUE.toLong()).toInt() }
        fun ready() { handshaking = false; idleAt = monotonicMillis() + idleTimeoutMillis }
        fun activity() { check(); idleAt = monotonicMillis() + idleTimeoutMillis }
        fun messageStarted() { messageAt = monotonicMillis() + idleTimeoutMillis }
        fun messageCompleted() { check(); messageAt = Long.MAX_VALUE }
    }

    private inner class Connection(val input: InputStream, val output: OutputStream, val deadline: Deadline) {
        @Volatile var delivering = false
        val checks = Channel<Unit>(Channel.CONFLATED)
        val pongs = Channel<ByteArray>(Channel.CONFLATED)
    }

    private fun performHandshake(snapshot: GatewayProfile, output: OutputStream, input: InputStream, host: String, target: String) {
        val signed = GatewayHttpClient.signedInput(snapshot, "GET", target, ByteArray(0))
        val auth = GatewayHttpClient.authenticationHeaders(snapshot, signed, GatewayHttpClient.signatureOf(signer, signed), "GET")
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also(secureRandom::nextBytes))
        val request = buildString {
            append("GET $target HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n")
            auth.forEach { append("${it.name}: ${it.value}\r\n") }
            append("\r\n")
        }
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()
        val statusLine = readLine(input) ?: throw IOException("WEBSOCKET_HANDSHAKE_FAILED:unexpected-eof")
        val status = Regex("HTTP/1\\.[01] ([0-9]{3})(?: .*|$)").matchEntire(statusLine)?.groupValues?.get(1)?.toInt()
            ?: throw IOException("WEBSOCKET_HANDSHAKE_FAILED:invalid-status")
        when (status) {
            101 -> Unit
            401 -> throw EventSessionRejectedException(snapshot, httpStatus = 401)
            403 -> throw EventStreamForbiddenException(httpStatus = 403)
            410 -> throw EventCursorExpiredException()
            else -> throw GatewayWebSocketHandshakeException(status)
        }
        val headers = mutableMapOf<String, String>()
        var bytes = statusLine.length
        while (true) {
            val line = readLine(input) ?: throw IOException("WEBSOCKET_HANDSHAKE_FAILED:incomplete-headers")
            if (line.isEmpty()) break
            bytes += line.length
            if (bytes > MAX_HEADER_BYTES) throw IOException("WEBSOCKET_HANDSHAKE_FAILED:headers-too-large")
            val colon = line.indexOf(':')
            if (colon <= 0) throw IOException("WEBSOCKET_HANDSHAKE_FAILED:invalid-header")
            val name = line.substring(0, colon).trim().lowercase(java.util.Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            headers[name] = headers[name]?.let { "$it, $value" } ?: value
        }
        if (verifyAcceptHeader) {
            if (!headers["upgrade"].equals("websocket", true)) throw IOException("WEBSOCKET_HANDSHAKE_FAILED: missing or invalid Upgrade header")
            if (headers["connection"]?.split(',')?.any { it.trim().equals("upgrade", true) } != true) {
                throw IOException("WEBSOCKET_HANDSHAKE_FAILED: missing or invalid Connection header")
            }
            if (headers["sec-websocket-accept"] != computeSecWebSocketAccept(key)) throw IOException("WEBSOCKET_HANDSHAKE_FAILED:accept-mismatch")
        }
    }

    private fun readFrame(input: InputStream): WebSocketFrame? {
        val first = input.read()
        if (first == -1) return null
        if (first and 0x70 != 0) throw IOException("WEBSOCKET_PROTOCOL_ERROR: RSV bits must be 0")
        val fin = first and 0x80 != 0
        val opcode = first and 15
        if (opcode !in setOf(OPCODE_CONTINUATION, OPCODE_TEXT, OPCODE_BINARY, OPCODE_CLOSE, OPCODE_PING, OPCODE_PONG)) {
            throw IOException("WEBSOCKET_PROTOCOL_ERROR: unknown or unsupported opcode $opcode")
        }
        fun next(): Int = input.read().takeIf { it >= 0 } ?: throw EOFException("WEBSOCKET_FRAME_INCOMPLETE")
        val second = next()
        if (second and 0x80 != 0) throw IOException("WEBSOCKET_PROTOCOL_ERROR: server must not mask frames")
        var length = (second and 127).toLong()
        if (length == 126L) {
            length = ((next() shl 8) or next()).toLong()
            if (length < 126) throw IOException("WEBSOCKET_PROTOCOL_ERROR: noncanonical-length")
        } else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or next().toLong() }
            if (length <= 65535) throw IOException("WEBSOCKET_PROTOCOL_ERROR: invalid-length")
        }
        if (length < 0 || length > MAX_PAYLOAD_BYTES) throw IOException("WEBSOCKET_FRAME_TOO_LARGE")
        if (opcode >= OPCODE_CLOSE) {
            if (!fin) throw IOException("WEBSOCKET_PROTOCOL_ERROR: control frames must not be fragmented")
            if (length > 125) throw IOException("WEBSOCKET_PROTOCOL_ERROR: control frame payload exceeds 125 bytes")
        }
        val payload = ByteArray(length.toInt())
        var offset = 0
        while (offset < payload.size) {
            val count = input.read(payload, offset, payload.size - offset)
            if (count < 0) throw EOFException("WEBSOCKET_FRAME_INCOMPLETE")
            if (count == 0) { payload[offset++] = next().toByte() } else offset += count
        }
        return WebSocketFrame(fin, opcode, payload)
    }

    private fun closeCode(payload: ByteArray): Int {
        if (payload.isEmpty()) return 1005
        if (payload.size == 1) throw IOException("WEBSOCKET_PROTOCOL_ERROR:invalid-close")
        val code = ((payload[0].toInt() and 255) shl 8) or (payload[1].toInt() and 255)
        if ((code !in 1000..1014 || code in setOf(1004, 1005, 1006)) && code !in 3000..4999) {
            throw IOException("WEBSOCKET_PROTOCOL_ERROR:invalid-close-code")
        }
        decodeUtf8(payload.copyOfRange(2, payload.size))
        return code
    }

    private fun rejectClose(code: Int, snapshot: GatewayProfile) {
        if (code in setOf(1008, 4003, 4403)) throw EventStreamForbiddenException(closeCode = code)
        if (code in setOf(4001, 4401)) throw EventSessionRejectedException(snapshot, closeCode = code)
    }

    private fun sendFrame(output: OutputStream, opcode: Int, payload: ByteArray) = synchronized(output) {
        output.write(0x80 or opcode)
        val mask = ByteArray(4).also(secureRandom::nextBytes)
        when {
            payload.size <= 125 -> output.write(0x80 or payload.size)
            payload.size <= 65535 -> {
                output.write(0x80 or 126)
                output.write(payload.size shr 8)
                output.write(payload.size and 255)
            }
            else -> {
                output.write(0x80 or 127)
                for (index in 7 downTo 0) output.write((payload.size.toLong() shr (index * 8)).toInt() and 255)
            }
        }
        output.write(mask)
        output.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) {
                if (out.size() == 0) return null
                throw IOException("WEBSOCKET_HANDSHAKE_FAILED:incomplete-line")
            }
            if (byte == '\n'.code) return out.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
            out.write(byte)
            if (out.size() > MAX_HEADER_LINE_BYTES) throw IOException("WEBSOCKET_HANDSHAKE_FAILED: header line exceeds $MAX_HEADER_LINE_BYTES bytes")
        }
    }

    companion object {
        const val PROTOCOL_HEADER = "2.1"
        const val EVENTS_TARGET = "/open-android-intelligence/v2/events/ws"
        const val PROBE_TARGET = "/open-android-intelligence/v2/events/ws/probe"
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 60_000
        const val PROBE_TIMEOUT_MILLIS = 10_000
        const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
        const val MAX_HEADER_LINE_BYTES = 8192
        private const val MAX_HEADER_BYTES = 64 * 1024
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OPCODE_CONTINUATION = 0
        const val OPCODE_TEXT = 1
        const val OPCODE_BINARY = 2
        const val OPCODE_CLOSE = 8
        const val OPCODE_PING = 9
        const val OPCODE_PONG = 10

        fun computeSecWebSocketAccept(secWebSocketKey: String): String = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((secWebSocketKey + WEBSOCKET_GUID).toByteArray(Charsets.US_ASCII)),
        )

        private fun decodeUtf8(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { throw GatewayWebSocketPayloadException() }

        /** Nullable only for a real SSE comment heartbeat. Invalid payloads always fail the connection. */
        fun parseWebSocketEvent(text: String): GatewayEvent? {
            try {
                val trimmed = text.trim()
                if (trimmed.startsWith("{")) {
                    val json = JsonFields.obj(Json.parse(trimmed)) ?: throw GatewayWebSocketPayloadException()
                    val id = JsonFields.string(json, "id")?.takeIf { GatewayHttpClient.CURSOR_ALPHABET.matches(it) }
                        ?: throw GatewayWebSocketPayloadException()
                    val event = JsonFields.string(json, "event")?.takeIf { it.isNotBlank() }
                        ?: throw GatewayWebSocketPayloadException()
                    val dataValue = JsonFields.field(json, "data") ?: throw GatewayWebSocketPayloadException()
                    val data = when (dataValue) {
                        is JsonValue.JString -> dataValue.value
                        is JsonValue.JObject, is JsonValue.JArray -> Json.canonical(dataValue)
                        is JsonValue.JNumber -> dataValue.raw
                        is JsonValue.JBool -> dataValue.value.toString()
                        is JsonValue.JNull -> ""
                    }
                    return GatewayEvent(id, event, data)
                }
                var heartbeat = false
                val parser = SseParser().also { it.onHeartbeat = { heartbeat = true } }
                val events = parser.feed("$trimmed\n\n")
                if (events.size == 1) return events.single()
                if (events.isEmpty() && heartbeat && trimmed.lineSequence().all { it.isBlank() || it.startsWith(':') }) return null
                throw GatewayWebSocketPayloadException()
            } catch (_: Exception) {
                // Do not retain JSON exceptions: their messages may contain the payload.
                throw GatewayWebSocketPayloadException()
            }
        }
    }

    private data class WebSocketFrame(val fin: Boolean, val opcode: Int, val payload: ByteArray)
}
