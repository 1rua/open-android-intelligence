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

    override suspend fun execute(request: WireRequest): WireResponse = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val connection = open(request)
        try {
            if (request.body.isNotEmpty()) {
                connection.doOutput = true
            }
            // Establish the connection and its identity before any sensitive
            // request body is written to the socket.
            connection.connect()
            GatewayConnectionSecurity.classify(connection, profile.pinnedSpkiSha256)
            if (request.body.isNotEmpty()) {
                connection.outputStream.use { stream -> stream.write(request.body) }
                connection.outputStream.use { stream ->
                    stream.write(request.body)
                }
            }
            val status = connection.responseCode
            val headers = readHeaders(connection)
            WireResponse(status = status, headers = headers, body = readBody(connection, status))
            val body = readBody(connection, status)
            WireResponse(status, headers, body)
        } finally {
            connection.disconnect()
        }
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
        val connection = open(request, readTimeoutMillis = 0)
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
                while (true) {
                    val read = stream.read(buffer)
                while (currentCoroutineContext().isActive) {
                    val read = try {
                        stream.read(buffer)
                    } catch (e: java.net.SocketTimeoutException) {
                        throw IOException("EVENT_STREAM_STALLED: no bytes received for ${SSE_IDLE_TIMEOUT_MILLIS}ms", e)
                    } catch (e: java.net.SocketException) {
                        if (!currentCoroutineContext().isActive) break
                        throw e
                    } catch (e: IOException) {
                        if (!currentCoroutineContext().isActive) break
                        throw e
                    }
                    if (read == -1) break
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
        connection.readTimeout = readTimeoutMillis
        connection.requestMethod = request.method
        connection.doInput = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-store")
        for (header in request.headers) {
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

    private companion object {
    companion object {
        const val SSE_IDLE_TIMEOUT_MILLIS = 45_000
        const val EVENT_CHUNK_BYTES = 8 * 1024
        const val BODY_CHUNK_BYTES = 16 * 1024
    }
}
