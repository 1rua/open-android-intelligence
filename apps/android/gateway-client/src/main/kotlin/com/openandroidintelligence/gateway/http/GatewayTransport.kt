package com.openandroidintelligence.gateway.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

import com.openandroidintelligence.gateway.diagnostics.GatewayLog

/**
 * The app's real Gateway transport: HTTPS or plaintext HTTP, plus SSE.
 *
 * This is the only place that turns a [WireRequest] into bytes on a socket. It
 * owns three rules that must not leak upwards:
 *
 * - every connection is opened through [GatewayConnectionFactory] and classified
 *   by [GatewayConnectionSecurity] before any request body or response byte is
 *   trusted, so a pinned profile cannot be downgraded to a plaintext one;
 * - pins are verified against the certificates the connection actually
 *   negotiated, before any response byte is trusted;
 * - the SSE stream emits raw byte chunks and never frames them, so cursor
 *   advancement stays the job of the parser that sees complete frames.
 */
class GatewayTransport(
    private val profile: GatewayProfile,
    private val factory: GatewayConnectionFactory = GatewayConnectionFactory(),
    private val maximumResponseBytes:Int = 32*1024*1024,
    /** Recheck the caller's lifecycle authority on IO, immediately before opening a connection. */
    private val beforeConnect: () -> Unit = {},
    private val onHttpResponse: (Int) -> Unit = {},
    private val onHttpFailure: (Throwable) -> Unit = {},
) : GatewayByteTransport {

    private val endpoint: GatewayEndpoint = GatewayEndpoint.parse(profile.gatewayBaseUrl)
        ?: error("GATEWAY_ENDPOINT_INVALID: ${profile.gatewayBaseUrl}")

    private fun phase(request: WireRequest, mode: String, stage: String) {
        GatewayLog.protocolEvidence("transport.$stage", mapOf("mode" to mode,
            "requestId" to RawHeaders.single(request.headers, "X-Open-Android-Intelligence-Request-Id"),
            "traceId" to RawHeaders.single(request.headers, "traceparent")?.split('-')?.getOrNull(1)))
    }

    private suspend fun <T> withTransportIo(request: WireRequest, mode: String, block: suspend () -> T): T {
        phase(request, mode, "io_queued")
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            phase(request, mode, "io_dequeued")
            try {
                block()
            } catch (failure: Throwable) {
                // Disconnecting a cancelled read must preserve the Job's cancellation cause.
                currentCoroutineContext().ensureActive()
                if (failure is IOException || GatewayConnectionSecurity.isSecurityFailure(failure)) reportFailure(failure)
                throw failure
            }
        }
    }

    private fun reportResponse(status: Int) { runCatching { onHttpResponse(status) } }

    private fun reportFailure(failure: Throwable) {
        if (failure is EventSessionRejectedException || failure is EventStreamForbiddenException || failure is EventCursorExpiredException) return
        runCatching { onHttpFailure(failure) }
    }

    private fun observedStream(request: WireRequest, mode: String, input: InputStream): InputStream =
        object : java.io.FilterInputStream(input) {
            private var observed = false
            private fun received(count: Int) {
                if (!observed && count > 0) { observed = true; phase(request, mode, "first_byte") }
            }
            override fun read(): Int = super.read().also { if (it >= 0) received(1) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length).also(::received)
        }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    override suspend fun <T> consumeResponse(
        request: WireRequest,
        consume: suspend (Int, List<RawHeader>, InputStream) -> T,
    ): T = withTransportIo(request, "consumeResponse") {
        val connection = open(request)
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true) {
            connection.disconnect()
        }
        try {
            require(request.streamBody == null) { "RESPONSE_STREAM_REQUIRES_BUFFERED_REQUEST" }
            if (request.body.isNotEmpty()) {
                connection.setFixedLengthStreamingMode(request.body.size)
                connection.doOutput = true
            }
            phase(request, "consumeResponse", "connect_started")
            connection.connect()
            GatewayConnectionSecurity.classify(connection, profile.pinnedSpkiSha256)
            phase(request, "consumeResponse", "connect_ready")
            if (request.body.isNotEmpty()) {
                phase(request, "consumeResponse", "write_started")
                connection.outputStream.use { it.write(request.body) }
                phase(request, "consumeResponse", "write_completed")
            }
            val status = connection.responseCode
            reportResponse(status)
            phase(request, "consumeResponse", "response_headers")
            val headers = RawHeaders.validate(readHeaders(connection))
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                ?: java.io.ByteArrayInputStream(ByteArray(0))
            observedStream(request, "consumeResponse", stream).use { consume(status, headers, it) }
        } finally {
            cancelHandle?.dispose()
            connection.disconnect()
        }
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    override suspend fun execute(request: WireRequest): WireResponse = withTransportIo(request, "execute") {
        val connection = open(request)
        val job = currentCoroutineContext()[Job]
        val cancelHandle = job?.invokeOnCompletion(onCancelling = true) {
            runCatching { connection.disconnect() }
        }
        try {
            val streamBody = request.streamBody
            require(streamBody == null || request.body.isEmpty()) { "REQUEST_BODY_INVALID:multiple-bodies" }
            if (streamBody != null) {
                validateStreamHeaders(request.headers, streamBody)
                connection.setFixedLengthStreamingMode(streamBody.contentLength)
            } else if (request.body.isNotEmpty()) {
                // Buffered HttpURLConnection POSTs may be transparently resent
                // after EOF. Refresh rotation must be retried only by its journal.
                connection.setFixedLengthStreamingMode(request.body.size)
            }
            if (request.body.isNotEmpty() || streamBody != null) {
                connection.doOutput = true
            }
            // Establish the connection and its identity before any sensitive
            // request body is written to the socket.
            phase(request, "execute", "connect_started")
            connection.connect()
            GatewayConnectionSecurity.classify(connection, profile.pinnedSpkiSha256)
            phase(request, "execute", "connect_ready")
            if (request.body.isNotEmpty() || streamBody != null) phase(request, "execute", "write_started")
            if (streamBody != null) {
                writeStreamBody(connection, streamBody)
            } else if (request.body.isNotEmpty()) {
                connection.outputStream.use { stream ->
                    stream.write(request.body)
                }
            }
            if (request.body.isNotEmpty() || streamBody != null) phase(request, "execute", "write_completed")
            val status = connection.responseCode
            reportResponse(status)
            phase(request, "execute", "response_headers")
            val headers = readHeaders(connection)
            val body = readBody(connection, status, request)
            WireResponse(status, headers, body)
        } finally {
            cancelHandle?.dispose()
            connection.disconnect()
        }
    }

    private fun validateStreamHeaders(headers: List<RawHeader>, body: GatewayRequestBody) {
        val lengths = headers.filter { it.name.equals("Content-Length", ignoreCase = true) }
        require(lengths.size == 1 && lengths.single().value == body.contentLength.toString()) {
            "REQUEST_BODY_INVALID:content-length"
        }
        val digests = headers.filter { it.name.equals("Digest", ignoreCase = true) }
        val expectedDigest = "sha-256=" + Base64.getEncoder().encodeToString(body.sha256Hex.hexBytes())
        require(digests.size == 1 && digests.single().value == expectedDigest) {
            "REQUEST_BODY_INVALID:digest"
        }
    }

    // HttpURLConnection's read timeout does not bound a blocked request write.
    // Apply the same idle budget to each socket write, so progressing large
    // uploads may run longer than one budget while stalled sockets are closed.
    // Local source reads keep their own error semantics.
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun <T> withRequestWriteIdleTimeout(
        connection: HttpURLConnection,
        block: () -> T,
    ): T {
        val timeoutMillis = connection.readTimeout
        if (timeoutMillis <= 0) return block()

        val timedOut = AtomicBoolean(false)
        try {
            return kotlinx.coroutines.withTimeout(timeoutMillis.toLong()) {
                val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true) { cause ->
                    if (cause != null) {
                        if (cause is kotlinx.coroutines.TimeoutCancellationException) timedOut.set(true)
                        runCatching { connection.disconnect() }
                    }
                }
                try {
                    block()
                } finally {
                    cancelHandle?.dispose()
                }
            }
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            if (timedOut.get() || failure is kotlinx.coroutines.TimeoutCancellationException) {
                throw SocketTimeoutException("REQUEST_WRITE_TIMEOUT").also { it.initCause(failure) }
            }
            throw failure
        }
    }

    private suspend fun writeStreamBody(connection: HttpURLConnection, body: GatewayRequestBody) {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        val buffer = ByteArray(STREAM_CHUNK_BYTES)
        withRequestWriteIdleTimeout(connection) { connection.outputStream }.use { output ->
            body.openStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (read == 0) {
                        val single = input.read()
                        if (single == -1) break
                        if (written >= body.contentLength) throw IOException("REQUEST_BODY_LENGTH_MISMATCH")
                        val singleByte = single.toByte()
                        digest.update(singleByte)
                        withRequestWriteIdleTimeout(connection) { output.write(single) }
                        written++
                        body.reportBytesWritten(written)
                        continue
                    }
                    if (written > body.contentLength - read) throw IOException("REQUEST_BODY_LENGTH_MISMATCH")
                    digest.update(buffer, 0, read)
                    withRequestWriteIdleTimeout(connection) { output.write(buffer, 0, read) }
                    written += read
                    body.reportBytesWritten(written)
                }
                withRequestWriteIdleTimeout(connection) { output.flush() }
            }
        }
        if (written != body.contentLength) throw IOException("REQUEST_BODY_LENGTH_MISMATCH")
        val actualDigest = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualDigest != body.sha256Hex) throw IOException("REQUEST_BODY_DIGEST_MISMATCH")
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
        phase(request, "eventStream", "io_dequeued")
        GatewayLog.d(TAG, "sse open")
        val connection = try {
            open(request, readTimeoutMillis = SSE_HANDSHAKE_TIMEOUT_MILLIS)
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            if (failure is IOException || GatewayConnectionSecurity.isSecurityFailure(failure)) reportFailure(failure)
            throw failure
        }
        connection.connectTimeout = SSE_HANDSHAKE_TIMEOUT_MILLIS
        val job = currentCoroutineContext()[Job]
        val cancelHandle = job?.invokeOnCompletion(onCancelling = true) {
            runCatching { connection.disconnect() }
        }
        try {
            phase(request, "eventStream", "connect_started")
            val status = sseHandshake(connection) {
                connection.connect()
                GatewayConnectionSecurity.classify(connection, profile.pinnedSpkiSha256)
                phase(request, "eventStream", "connect_ready")
                connection.responseCode.also(::reportResponse)
            }
            connection.readTimeout = SSE_IDLE_TIMEOUT_MILLIS
            phase(request, "eventStream", "response_headers")
            if (status !in 200..299) {
                if (status == 410) throw EventCursorExpiredException()
                if (status == 401) throw EventSessionRejectedException()
                if (status == 403) throw EventStreamForbiddenException()
                throw IOException("EVENT_STREAM_FAILED:$status")
            }
            val contentType = connection.getHeaderField("Content-Type").orEmpty()
            if (!contentType.contains("text/event-stream")) {
                throw IOException("EVENT_STREAM_FAILED:unexpected-content-type")
            }
            observedStream(request, "eventStream", connection.inputStream).use { stream ->
                val buffer = ByteArray(EVENT_CHUNK_BYTES)
                while (currentCoroutineContext().isActive) {
                    val read = try {
                        stream.read(buffer)
                    } catch (e: java.net.SocketTimeoutException) {
                        // Half-open connection: the Gateway's heartbeat is every
                        // 15s, so no byte for this long means the socket is dead
                        // even though nobody sent a reset.
                        GatewayLog.w(TAG, "sse stalled after ${SSE_IDLE_TIMEOUT_MILLIS}ms")
                        throw EventStreamTimeoutException(EventStreamTimeoutStage.IDLE)
                    } catch (e: java.net.SocketException) {
                        currentCoroutineContext().ensureActive()
                        GatewayLog.w(TAG, "sse socket error")
                        throw e
                    } catch (e: IOException) {
                        currentCoroutineContext().ensureActive()
                        GatewayLog.w(TAG, "sse io error")
                        throw e
                    }
                    if (read == -1) {
                        GatewayLog.d(TAG, "sse ended by server")
                        break
                    }
                    if (read > 0) emit(buffer.copyOf(read))
                }
            }
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            if (failure is IOException || GatewayConnectionSecurity.isSecurityFailure(failure)) reportFailure(failure)
            throw failure
        } finally {
            cancelHandle?.dispose()
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO).onStart { phase(request, "eventStream", "io_queued") }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun <T> sseHandshake(connection: HttpURLConnection, block: () -> T): T {
        try {
            return kotlinx.coroutines.withTimeout(SSE_HANDSHAKE_TIMEOUT_MILLIS.toLong()) {
                val handle = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true) {
                    runCatching { connection.disconnect() }
                }
                try { block() } finally { handle?.dispose() }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw EventStreamTimeoutException(EventStreamTimeoutStage.HANDSHAKE)
        }
    }

    private fun open(
        request: WireRequest,
        readTimeoutMillis: Int = GatewayConnectionFactory.READ_TIMEOUT_MILLIS,
    ): HttpURLConnection {
        val url = URL(endpoint.baseUrl.trimEnd('/') + request.target)
        beforeConnect()
        val connection = factory.open(
            url,
            readTimeoutMillis = readTimeoutMillis,
        )
        connection.requestMethod = request.method
        connection.doInput = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-store")
        for (header in request.headers) {
            if (request.streamBody != null && header.name.equals("Content-Length", ignoreCase = true)) continue
            connection.setRequestProperty(header.name, header.value)
        }
        return connection
    }

    private fun readHeaders(connection: HttpURLConnection): List<RawHeader> {
        val headers = mutableListOf<RawHeader>()
        for ((name, values) in connection.headerFields) {
            // A null key carries the HTTP status line; it is not a header.
            if (name == null) continue
            for (value in values) headers += RawHeader(name, value)
        }
        return headers
    }

    private fun readBody(connection: HttpURLConnection, status: Int, request: WireRequest): ByteArray {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            ?: return ByteArray(0)
        require(maximumResponseBytes >= 0)
        if (connection.contentLengthLong > maximumResponseBytes) throw java.io.IOException("RESPONSE_TOO_LARGE")
        val out = ByteArrayOutputStream()
        observedStream(request, "execute", stream).use { input ->
            val buffer = ByteArray(BODY_CHUNK_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (out.size().toLong()+read>maximumResponseBytes) throw java.io.IOException("RESPONSE_TOO_LARGE")
                if (read > 0) out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    companion object {
        /** Complete SSE frames are independently bounded by the HTTP event owner. */
        const val SSE_IDLE_TIMEOUT_MILLIS = 60_000
        const val SSE_HANDSHAKE_TIMEOUT_MILLIS = 10_000
        const val EVENT_CHUNK_BYTES = 8 * 1024
        const val BODY_CHUNK_BYTES = 16 * 1024
        const val STREAM_CHUNK_BYTES = 64 * 1024
        private const val SHA256_HEX_BYTES = 64
        private const val TAG = "GatewaySse"

        private fun String.hexBytes(): ByteArray {
            require(length == SHA256_HEX_BYTES) { "REQUEST_BODY_INVALID:digest" }
            return ByteArray(length / 2) { index ->
                substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
