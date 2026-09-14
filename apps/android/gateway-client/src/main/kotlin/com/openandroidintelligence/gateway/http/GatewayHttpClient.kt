package com.openandroidintelligence.gateway.http

import com.openandroidintelligence.gateway.events.EventCursorStore
import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.events.SseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
     * Opens the event stream from the stored cursor.
     *
     * The cursor is only advanced for a fully framed event, so a disconnect in
     * the middle of a frame resumes from the previous complete one rather than
     * skipping the remainder.
     */
    fun events(): Flow<GatewayEvent> = flow {
        val storedCursor = cursorStore.load(profile.accountId)
        // A cursor that is not a wire ID would produce a target the Gateway
        // refuses as non-canonical; starting over replays retained events, which
        // the phone treats as idempotent upserts.
        val cursor = storedCursor?.takeIf { CURSOR_ALPHABET.matches(it) }
        val target = if (cursor == null) {
            EVENTS_TARGET
        } else {
            "$EVENTS_TARGET?cursor=$cursor"
        }

        val parser = SseParser { event ->
            event.id?.let { cursorStore.save(profile.accountId, it) }
        }

        // The stream is an authenticated request like any other: the signature
        // covers the canonical target including the cursor query, so nothing on
        // the path can move the phone's resume point.
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
            for (event in parser.feedBytes(chunk)) emit(event)
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
        val MUTATING_METHODS = setOf("POST", "PUT", "DELETE")

        /** The closed wire ID alphabet from contract §2, used for opaque cursors. */
        val CURSOR_ALPHABET = Regex("[A-Za-z0-9._~-]{1,128}")
    }
}
