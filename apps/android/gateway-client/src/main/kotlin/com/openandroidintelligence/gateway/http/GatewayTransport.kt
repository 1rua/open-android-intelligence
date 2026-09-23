package com.openandroidintelligence.gateway.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

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
) : GatewayByteTransport {

    private val endpoint: GatewayEndpoint = GatewayEndpoint.parse(profile.gatewayBaseUrl)
        ?: error("GATEWAY_ENDPOINT_INVALID: ${profile.gatewayBaseUrl}")

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    override suspend fun execute(request: WireRequest): WireResponse = kotlinx.coroutines.withContext(Dispatchers.IO) {
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
            }
            if (request.body.isNotEmpty() || streamBody != null) {
                connection.doOutput = true
            }
            // Establish the connection and its identity before any sensitive
            // request body is written to the socket.
            connection.connect()
            GatewayConnectionSecurity.classify(connection, profile.pinnedSpkiSha256)
            if (streamBody != null) {
                writeStreamBody(connection, streamBody)
            } else if (request.body.isNotEmpty()) {
                connection.outputStream.use { stream ->
                    stream.write(request.body)
                }
            }
            val status = connection.responseCode
            val headers = readHeaders(connection)
            val body = readBody(connection, status)
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

    private fun writeStreamBody(connection: HttpURLConnection, body: GatewayRequestBody) {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        val buffer = ByteArray(STREAM_CHUNK_BYTES)
        connection.outputStream.use { output ->
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
                        output.write(single)
                        written++
                        body.reportBytesWritten(written)
                        continue
                    }
                    if (written > body.contentLength - read) throw IOException("REQUEST_BODY_LENGTH_MISMATCH")
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    written += read
                    body.reportBytesWritten(written)
                }
            }
        }
        if (written != body.contentLength) throw IOException("REQUEST_BODY_LENGTH_MISMATCH")
        val actualDigest = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualDigest != body.sha256Hex) throw IOException("REQUEST_BODY_DIGEST_MISMATCH")
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
        GatewayLog.d(TAG, "sse open ${request.method} ${request.target}")
        val connection = open(request, readTimeoutMillis = SSE_IDLE_TIMEOUT_MILLIS)
        val job = currentCoroutineContext()[Job]
        val cancelHandle = job?.invokeOnCompletion(onCancelling = true) {
            runCatching { connection.disconnect() }
        }
        try {
            connection.connect()
            GatewayConnectionSecurity.classify(connection, profile.pinnedSpkiSha256)
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("EVENT_STREAM_FAILED:$status")
            }
            val contentType = connection.getHeaderField("Content-Type").orEmpty()
            if (!contentType.contains("text/event-stream")) {
                throw IOException("EVENT_STREAM_FAILED:unexpected-content-type:$contentType")
            }
            connection.inputStream.use { stream ->
                val buffer = ByteArray(EVENT_CHUNK_BYTES)
                while (currentCoroutineContext().isActive) {
                    val read = try {
                        stream.read(buffer)
                    } catch (e: java.net.SocketTimeoutException) {
                        // Half-open connection: the Gateway's heartbeat is every
                        // 15s, so no byte for this long means the socket is dead
                        // even though nobody sent a reset.
                        GatewayLog.w(TAG, "sse stalled after ${SSE_IDLE_TIMEOUT_MILLIS}ms")
                        throw IOException("EVENT_STREAM_STALLED: no bytes received for ${SSE_IDLE_TIMEOUT_MILLIS}ms", e)
                    } catch (e: java.net.SocketException) {
                        if (!currentCoroutineContext().isActive || e.message?.contains("closed", ignoreCase = true) == true) break
                        GatewayLog.w(TAG, "sse socket error: ${e.message}")
                        throw e
                    } catch (e: IOException) {
                        if (!currentCoroutineContext().isActive || e.message?.contains("closed", ignoreCase = true) == true) break
                        GatewayLog.w(TAG, "sse io error: ${e.message}")
                        throw e
                    }
                    if (read == -1) {
                        GatewayLog.d(TAG, "sse ended by server")
                        break
                    }
                    if (read > 0) emit(buffer.copyOf(read))
                }
            }
        } finally {
            cancelHandle?.dispose()
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun open(
        request: WireRequest,
        readTimeoutMillis: Int = GatewayConnectionFactory.READ_TIMEOUT_MILLIS,
    ): HttpURLConnection {
        val connection = factory.open(
            URL(endpoint.baseUrl.trimEnd('/') + request.target),
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

    private fun readBody(connection: HttpURLConnection, status: Int): ByteArray {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            ?: return ByteArray(0)
        val out = ByteArrayOutputStream()
        stream.use { input ->
            val buffer = ByteArray(BODY_CHUNK_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (read > 0) out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    companion object {
        /**
         * Longest silence tolerated on an open SSE stream.
         *
         * The Gateway heartbeats every 15s, so this is one heartbeat plus
         * slack: long enough to survive a slow network, short enough that a
         * half-open socket is noticed in seconds rather than the better part of
         * a minute, which is how a reply used to disappear without a trace.
         */
        const val SSE_IDLE_TIMEOUT_MILLIS = 20_000
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
