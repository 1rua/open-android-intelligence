package com.openandroidintelligence.gateway.http

import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import com.openandroidintelligence.gateway.events.EventCursorStore
import com.openandroidintelligence.gateway.events.EventStreamStatus
import com.openandroidintelligence.gateway.events.EventStreamStatusSink
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
    /** Where the stream reports its health. Absent means nobody is watching. */
    private val statusSink: EventStreamStatusSink? = null,
    /**
     * How many consecutive dead rounds before the stream stops retrying and
     * fails loudly. Retrying forever is what made a dropped reply look like an
     * app that never answered: nothing above could tell "reconnecting" from
     * "working".
     */
    private val maxConsecutiveFailures: Int = 6,
) {

    /**
     * Event ids this client has already handed to a collector.
     *
     * The stream reconnects by itself and is re-subscribed whenever a screen
     * reopens, so a Gateway backlog replay can offer the same frame more than
     * once. Remembering the ids on the client — instead of inside one
     * collection — is what makes delivery idempotent across those boundaries:
     * a replay is dropped at the network edge rather than being filtered again
     * by every layer above it.
     *
     * Two consequences the callers must know:
     * - delivery is **at most once per client instance**: an event is recorded
     *   before it is emitted, so a collector cancelled mid-emit loses it. The
     *   loss is compensated by the timeline pull a caller performs when the
     *   stream reports a break;
     * - it assumes a **single concurrent collector** per client, which is how
     *   the app drives it (one account-wide subscription). A second collector
     *   would be starved rather than served, so use one client per stream.
     */
    private val deliveredEventIds = LinkedHashSet<String>()

    /**
     * True when this event id has not been delivered yet.
     *
     * A blank id is never tracked: the Gateway uses un-ided frames for notices
     * and heartbeats, and dropping them would silently break the channel. The
     * critical section is a set insertion, so it stays safe to call from the
     * main dispatcher.
     */
    @Synchronized
    private fun markEventDelivered(eventId: String?): Boolean {
        if (eventId.isNullOrBlank()) return true
        if (!deliveredEventIds.add(eventId)) return false
        if (deliveredEventIds.size > MAX_TRACKED_EVENT_IDS) {
            val iterator = deliveredEventIds.iterator()
            repeat(MAX_TRACKED_EVENT_IDS / 2) {
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        return true
    }

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
        var consecutiveFailures = 0

        statusSink?.report(EventStreamStatus.CONNECTING)
        GatewayLog.d(TAG, "event stream start autoReconnect=$autoReconnect")

        while (currentCoroutineContext().isActive) {
            val storedCursor = cursorStore.load(profile.accountId)
            val cursor = storedCursor?.takeIf { CURSOR_ALPHABET.matches(it) }
            var receivedAnyEventInAttempt = false
            var receivedWsEventInAttempt = false
            var streamFailed = false
            var lastFailure: Throwable? = null

            GatewayLog.d(TAG, "event stream attempt cursor=$cursor viaWs=$preferWebSocket")

            // 1. Try WebSocket first if preferred and available
            if (preferWebSocket && webSocketTransport != null) {
                try {
                    webSocketTransport.events(cursor).collect { event ->
                        if (!receivedWsEventInAttempt) {
                            GatewayLog.d(TAG, "event stream live over websocket")
                        }
                        receivedAnyEventInAttempt = true
                        receivedWsEventInAttempt = true
                        backoffMillis = 1000L
                        consecutiveFailures = 0
                        statusSink?.report(EventStreamStatus.LIVE)

                        val eventId = event.id
                        if (!eventId.isNullOrBlank()) {
                            cursorStore.save(profile.accountId, eventId)
                            if (!markEventDelivered(eventId)) {
                                GatewayLog.d(TAG, "skipping duplicate ws event id=$eventId")
                                return@collect
                            }
                        }
                        emit(event)
                    }
                    if (currentCoroutineContext().isActive) {
                        streamFailed = true
                        if (!receivedWsEventInAttempt) {
                            preferWebSocket = false
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    streamFailed = true
                    lastFailure = e
                    if (!receivedWsEventInAttempt) {
                        preferWebSocket = false
                    }
                    GatewayLog.w(TAG, "websocket attempt failed: ${e.message}")
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
                        val eventId = event.id
                        if (!eventId.isNullOrBlank()) {
                            cursorStore.save(profile.accountId, eventId)
                        }
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
                            if (!receivedAnyEventInAttempt) {
                                GatewayLog.d(TAG, "event stream live over sse")
                            }
                            receivedAnyEventInAttempt = true
                            backoffMillis = 1000L
                            consecutiveFailures = 0
                            statusSink?.report(EventStreamStatus.LIVE)
                        }
                        for (event in parsedEvents) {
                            val eventId = event.id
                            if (!markEventDelivered(eventId)) {
                                GatewayLog.d(TAG, "skipping duplicate sse event id=$eventId")
                                continue
                            }
                            GatewayLog.d(TAG, "event ${event.event} id=${event.id}")
                            emit(event)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    streamFailed = true
                    lastFailure = e
                    GatewayLog.w(TAG, "sse attempt failed: ${e.message}")
                }
            }

            if (receivedWsEventInAttempt) {
                preferWebSocket = (webSocketTransport != null)
            }

            // A round counts as failed only when both channels failed: a
            // WebSocket that is unsupported and hands over to SSE is a normal
            // downgrade, not an outage.
            if (streamFailed) {
                consecutiveFailures++
                GatewayLog.w(TAG, "event stream round failed $consecutiveFailures/$maxConsecutiveFailures")
                // Stop retrying instead of looping forever: a stream that never
                // recovers has to reach the user as a failure, because silence
                // is indistinguishable from an app that forgot to answer.
                if (autoReconnect && consecutiveFailures >= maxConsecutiveFailures) {
                    statusSink?.report(EventStreamStatus.FAILED)
                    throw lastFailure ?: java.io.IOException("EVENT_STREAM_FAILED:retries-exhausted")
                }
            }

            // If reconnect is disabled:
            if (!autoReconnect) {
                if (streamFailed) statusSink?.report(EventStreamStatus.FAILED)
                break
            }

            if (streamFailed) {
                statusSink?.report(EventStreamStatus.RECONNECTING)
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
                    kotlinx.coroutines.yield()
                    if (currentCoroutineContext().isActive) {
                        delayFn(backoffMillis)
                    }
                    backoffMillis = 1000L
                    preferWebSocket = (webSocketTransport != null)
                }
            }
        }
    }

    internal fun signedInput(method: String, target: String, body: ByteArray): SignedRequestInput =
        signedInput(profile, method, target, body)

    internal fun signatureOf(input: SignedRequestInput): String =
        signatureOf(signer, input)

    internal fun authenticationHeaders(
        input: SignedRequestInput,
        signatureBase64Url: String,
        method: String,
    ): List<RawHeader> = authenticationHeaders(profile, input, signatureBase64Url, method)

    companion object {
        const val PROTOCOL_HEADER = "2.0"
        const val EVENTS_TARGET = "/open-android-intelligence/v2/events"
        /**
         * How many event ids stay remembered for replay suppression.
         *
         * A reconnect resumes from the stored cursor, so the replay distance is
         * the events produced while the previous socket was dead — far below
         * this window in practice. Evicting the oldest half on overflow keeps a
         * long session bounded, and the case it would miss (more than this many
         * events replayed in one reconnect) is a backlog the caller re-pulls as
         * a timeline snapshot anyway.
         */
        private const val MAX_TRACKED_EVENT_IDS = 4096
        private const val TAG = "GatewayEvents"
        val MUTATING_METHODS = setOf("POST", "PUT", "DELETE", "PATCH")

        /** The closed wire ID alphabet from contract §2, used for opaque cursors. */
        val CURSOR_ALPHABET = Regex("[A-Za-z0-9._~-]{1,128}")

        internal fun signedInput(
            profile: GatewayProfile,
            method: String,
            target: String,
            body: ByteArray,
        ): SignedRequestInput =
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

        internal fun signatureOf(signer: (ByteArray) -> ByteArray, input: SignedRequestInput): String =
            java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signer(RequestSigner.preimage(input)))

        internal fun authenticationHeaders(
            profile: GatewayProfile,
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
    }
}
