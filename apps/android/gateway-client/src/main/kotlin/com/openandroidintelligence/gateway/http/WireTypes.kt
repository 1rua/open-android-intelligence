package com.openandroidintelligence.gateway.http

import kotlinx.coroutines.flow.Flow

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

    fun eventStream(request: WireRequest): Flow<ByteArray>
}
