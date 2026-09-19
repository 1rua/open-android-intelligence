package com.openandroidintelligence.gateway.http

import com.openandroidintelligence.gateway.events.EventCursorStore
import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.events.SseParser
import com.openandroidintelligence.gateway.ws.GatewayWebSocketTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

data class GatewayProfile(
    val accountId: String,
    val deviceId: String,
    val sessionId: String,
    val gatewayBaseUrl: String,
    /** Protocol `sha256:<lowercase hex>` SPKI pins. Empty means system trust only. */
    val pinnedSpkiSha256: Set<String> = emptySet(),
    /** Bearer token issued by the login or refresh endpoint. */
    val accessToken: String = "",
) {
    init {
        val endpoint = GatewayEndpoint.parse(gatewayBaseUrl)
        require(endpoint != null) { "gateway base url must be http or https with a host" }
        // A pin is a claim about a TLS certificate. Refusing it on a plaintext
        // address here keeps the invariant at the type boundary instead of
        // leaving it to whichever transport happens to open the socket.
        require(endpoint.isTls || pinnedSpkiSha256.isEmpty()) {
            "gateway pins require an https base url"
        }
    }
}

data class SignedGatewayRequest(
    val method: String,
    val target: String,
    val body: ByteArray = ByteArray(0),
    val headers: List<RawHeader> = emptyList(),
)

data class GatewayResponse(
    val status: Int,
    val headers: List<RawHeader>,
    val body: ByteArray,
)

/**
 * The app's Gateway client.
 *
 * Requests are signed over the canonical target actually sent and the exact body
 * bytes, and response headers are validated from the raw list before use, so a
 * duplicated or folded singleton cannot resolve to whichever value the parser
 * happened to keep.
 *
 * The authenticated header set follows contract §6.1: one bearer Authorization,
 * the protocol, account, device and session headers, and the request-id,
 * timestamp, nonce and signature headers — nine singleton headers plus the
 * Idempotency-Key on every mutating request, bound to the same request id.
 */
class GatewayHttpClient(
    private val profile: GatewayProfile,
    private val transport: GatewayByteTransport,
    private val signer: (ByteArray) -> ByteArray,
    private val cursorStore: EventCursorStore,
    private val webSocketTransport: GatewayWebSocketTransport? = if (transport is GatewayTransport) GatewayWebSocketTransport(profile, signer) else null,
    private val delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {

    suspend fun execute(request: SignedGatewayRequest): GatewayResponse {
        val validatedHeaders = RawHeaders.validate(request.headers)
        val input = signedInput(request.method, request.target, request.body)
        val headers = validatedHeaders + authenticationHeaders(input, signatureOf(input), request.method)

        return withContext(Dispatchers.IO) {
            val response = transport.execute(
                WireRequest(input.method, input.target, headers, request.body),
            )
            GatewayResponse(response.status, RawHeaders.validate(response.headers), response.body)
        }
    }

    /**
     * Opens the event stream from the stored cursor with dual-channel support and auto-reconnect.
     *
     * The cursor is only advanced for a fully framed event, so a disconnect in
     * the middle of a frame resumes from the previous complete one rather than
     * skipping the remainder.
     * Tries WebSocket first; if it fails or the server does not support it, automatically
     * falls back to the SSE stream. On network disconnect or error, reconnects with exponential
     * backoff (1s, 2s, 5s...) carrying the newest cursor, ensuring the stream stays alive.
     */
    fun events(autoReconnect: Boolean = true): Flow<GatewayEvent> = flow {
        var backoffMillis = 1000L
        val maxBackoffMillis = 60_000L
        var preferWebSocket = (webSocketTransport != null)

        while (currentCoroutineContext().isActive) {
            val storedCursor = cursorStore.load(profile.accountId)
            val cursor = storedCursor?.takeIf { CURSOR_ALPHABET.matches(it) }
            var receivedAnyEventInAttempt = false
            var receivedWsEventInAttempt = false
            var streamFailed = false

            // 1. Try WebSocket first if preferred and available
            if (preferWebSocket && webSocketTransport != null) {
                try {
                    webSocketTransport.events(cursor).collect { event ->
                        receivedAnyEventInAttempt = true
                        receivedWsEventInAttempt = true
                        backoffMillis = 1000L
                        event.id?.let { cursorStore.save(profile.accountId, it) }
                        emit(event)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    streamFailed = true
                    if (!receivedWsEventInAttempt) {
                        preferWebSocket = false
                    }
                }
            }

            // 2. Fallback to SSE stream if WebSocket not preferred or failed to connect
            if (!preferWebSocket || (!receivedAnyEventInAttempt && streamFailed)) {
                streamFailed = false
                try {
                    val sseStoredCursor = cursorStore.load(profile.accountId)
                    val sseCursor = sseStoredCursor?.takeIf { CURSOR_ALPHABET.matches(it) }
                    val target = if (sseCursor == null) {
                        EVENTS_TARGET
                    } else {
                        "$EVENTS_TARGET?cursor=$sseCursor"
                    }

                    val parser = SseParser { event ->
                        event.id?.let { cursorStore.save(profile.accountId, it) }
                    }

                    val headers = RawHeaders.validate(
                        listOf(
                            RawHeader("Accept", "text/event-stream"),
                            RawHeader("Cache-Control", "no-store"),
                        ),
                    )
                    val input = signedInput("GET", target, ByteArray(0))
                    val streamHeaders = headers + authenticationHeaders(input, signatureOf(input), "GET")

                    transport.eventStream(
                        WireRequest(input.method, input.target, streamHeaders, ByteArray(0)),
                    ).collect { chunk ->
                        val parsedEvents = parser.feedBytes(chunk)
                        if (parsedEvents.isNotEmpty()) {
                            receivedAnyEventInAttempt = true
                            backoffMillis = 1000L
                        }
                        for (event in parsedEvents) emit(event)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    streamFailed = true
                }
            }

            if (receivedWsEventInAttempt) {
                preferWebSocket = (webSocketTransport != null)
            }

            // If reconnect is disabled:
            if (!autoReconnect) {
                break
            }

            // Exponential backoff before reconnecting on failure: 1s, 2s, 5s...
            if (currentCoroutineContext().isActive) {
                if (streamFailed) {
                    delayFn(backoffMillis)
                    backoffMillis = when (backoffMillis) {
                        1000L -> 2000L
                        2000L -> 5000L
                        else -> minOf(backoffMillis * 2, maxBackoffMillis)
                    }
                } else {
                    backoffMillis = 1000L
                    kotlinx.coroutines.yield()
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun signedInput(method: String, target: String, body: ByteArray): SignedRequestInput =
        SignedRequestInput(
            method = method,
            target = target,
            accountId = profile.accountId,
            deviceId = profile.deviceId,
            sessionId = profile.sessionId,
            requestId = newRequestId(),
            // Millisecond precision: the wire format is fixed at three fractional
            // digits and the Gateway refuses anything else.
            timestamp = RequestSigner.formatTimestamp(
                java.time.Instant.ofEpochMilli(java.time.Instant.now().toEpochMilli()),
            ),
            nonce = newNonce(),
            body = body,
        )

    private fun signatureOf(input: SignedRequestInput): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(signer(RequestSigner.preimage(input)))

    private fun authenticationHeaders(
        input: SignedRequestInput,
        signatureBase64Url: String,
        method: String,
    ): List<RawHeader> = buildList {
        add(RawHeader("Authorization", "Bearer ${profile.accessToken}"))
        add(RawHeader("X-Open-Android-Intelligence-Protocol", PROTOCOL_HEADER))
        add(RawHeader("X-Open-Android-Intelligence-Account", profile.accountId))
        add(RawHeader("X-Open-Android-Intelligence-Device", profile.deviceId))
        add(RawHeader("X-Open-Android-Intelligence-Session", profile.sessionId))
        add(RawHeader("X-Open-Android-Intelligence-Request-Id", input.requestId))
        add(RawHeader("X-Open-Android-Intelligence-Timestamp", input.timestamp))
        add(RawHeader("X-Open-Android-Intelligence-Nonce", input.nonce))
        add(RawHeader("X-Open-Android-Intelligence-Signature", signatureBase64Url))
        if (method in MUTATING_METHODS) {
            add(RawHeader("Idempotency-Key", input.requestId))
        }
    }

    private fun newRequestId(): String =
        "req" + java.util.UUID.randomUUID().toString().replace("-", "").take(20)

    private fun newNonce(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val PROTOCOL_HEADER = "2.0"
        const val EVENTS_TARGET = "/open-android-intelligence/v2/events"
        val MUTATING_METHODS = setOf("POST", "PUT", "DELETE", "PATCH")

        /** The closed wire ID alphabet from contract §2, used for opaque cursors. */
        val CURSOR_ALPHABET = Regex("[A-Za-z0-9._~-]{1,128}")
    }
}
