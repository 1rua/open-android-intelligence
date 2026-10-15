package com.openandroidintelligence.gateway.http

import kotlinx.coroutines.flow.Flow
import java.io.InputStream

data class WireRequest(
    val method: String,
    val target: String,
    val headers: List<RawHeader> = emptyList(),
    val body: ByteArray = ByteArray(0),
    val streamBody: GatewayRequestBody? = null,
)

data class WireResponse(
    val status: Int,
    val headers: List<RawHeader>,
    val body: ByteArray,
)

/**
 * The byte-level transport boundary.
 *
 * Everything above it works in signed requests and parsed envelopes; everything
 * below it owns raw header bytes, framing and TLS. Keeping them separate is what
 * lets the framing and header rules be tested for real instead of against a
 * mock of the HTTP client.
 */
interface GatewayByteTransport {
    suspend fun execute(request: WireRequest): WireResponse

    /** 字节实现的兼容入口；生产 HTTP 实现直接消费网络流，关闭责任属于传输层。 */
    suspend fun <T> consumeResponse(
        request: WireRequest,
        consume: suspend (Int, List<RawHeader>, InputStream) -> T,
    ): T {
        val response = execute(request)
        return response.body.inputStream().use { consume(response.status, response.headers, it) }
    }

    fun eventStream(request: WireRequest): Flow<ByteArray>
}
