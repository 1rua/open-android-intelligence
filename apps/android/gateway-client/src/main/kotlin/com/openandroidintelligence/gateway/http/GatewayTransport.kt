package com.openandroidintelligence.gateway.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
            }
            val status = connection.responseCode
            val headers = readHeaders(connection)
            WireResponse(status = status, headers = headers, body = readBody(connection, status))
        } finally {
            connection.disconnect()
        }
    }

    override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
        val connection = open(request)
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
                    if (read == -1) break
                    if (read > 0) emit(buffer.copyOf(read))
                }
            }
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun open(request: WireRequest): HttpURLConnection {
        val connection = factory.open(URL(endpoint.baseUrl.trimEnd('/') + request.target))
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
        const val EVENT_CHUNK_BYTES = 8 * 1024
        const val BODY_CHUNK_BYTES = 16 * 1024
    }
}
