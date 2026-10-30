package com.openandroidintelligence.gateway.http

import com.openandroidintelligence.gateway.auth.GatewaySessionRenewal
import com.openandroidintelligence.gateway.auth.SessionRecoveryPausedException
import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import com.openandroidintelligence.gateway.events.EventCursorStore
import com.openandroidintelligence.gateway.events.EventStreamStatus
import com.openandroidintelligence.gateway.events.EventStreamStatusSink
import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.events.SseParser
import com.openandroidintelligence.gateway.ws.GatewayWebSocketTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
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
    val streamBody: GatewayRequestBody? = null,
    val requestId: String? = null,
    /** Rechecked at every transport attempt, including a replay after login renewal. */
    val beforeSend: () -> Unit = {},
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
    private val handlePlatformEvent: suspend (GatewayEvent) -> Boolean = { false },
    private val sessionRenewal: GatewaySessionRenewal? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        if (sessionRenewal != null) {
            sessionRenewal.requireScope(profile)
            webSocketTransport?.bindSessionRenewal(sessionRenewal)
        }
    }

    private fun sessionProfile(): GatewayProfile = sessionRenewal?.current ?: profile

    // A healthy sibling stream cannot repeatedly replenish a broken client's
    // authentication attempts. This fence also survives Flow re-collection.
    private val eventRecoveryOwner = Any()
    private val localEventRecoveryMarker = java.util.concurrent.atomic.AtomicReference<Any?>(null)
    private val failedEventSession = java.util.concurrent.atomic.AtomicReference<Pair<GatewayProfile, Throwable>?>(null)

    private fun recordEventActivity(snapshot: GatewayProfile) {
        if (sessionRenewal?.recordEventActivity(snapshot, eventRecoveryOwner) == true) localEventRecoveryMarker.set(null)
    }

    private suspend fun recoverEventAuthentication(rejected: GatewayProfile): Boolean {
        val renewal = sessionRenewal ?: return false
        val marker = Any()
        val allowRefresh = localEventRecoveryMarker.compareAndSet(null, marker)
        val attemptedMarker = if (allowRefresh) marker else localEventRecoveryMarker.get()
        val recovered = try {
            renewal.recoverEventSession(rejected, eventRecoveryOwner, allowRefresh) {
                // Completion belongs to the shared operation, not its cancellable
                // waiter. A later operation's fence must survive an older failure.
                if (attemptedMarker != null) localEventRecoveryMarker.compareAndSet(attemptedMarker, null)
            }
        } catch (paused: SessionRecoveryPausedException) {
            failedEventSession.set(rejected to paused)
            statusSink?.report(EventStreamStatus.FAILED)
            throw paused
        }
        if (!recovered) failedEventSession.set(rejected to EventSessionRejectedException(rejected))
        return recovered
    }

    /**
     * Event ids this client has already handed to a collector.
     *
     * The stream reconnects by itself and is re-subscribed whenever a screen
     * reopens, so a Gateway backlog replay can offer the same frame more than
     * once. Remembering the ids on the client — instead of inside one
     * collection — is what makes delivery idempotent across those boundaries:
     * a completed delivery can be dropped at the network edge.
     *
     * Two consequences the callers must know:
     * - collector application and cursor persistence must both succeed before
     *   an id is remembered. Cancellation or either failure allows replay, so
     *   collectors must tolerate an event applied before a failed cursor save;
     * - it assumes a **single concurrent collector** per client, which is how
     *   the app drives it. The independent platform and conversation streams
     *   use separate clients and cursors.
     */
    @Volatile private var cursorRecovery: (suspend () -> String)? = null
    fun setCursorRecovery(recover: suspend () -> String) { cursorRecovery = recover }
    private suspend fun rebuildCursor() {
        val recover = cursorRecovery ?: run {
            cursorStore.clear(profile.accountId)
            throw EventCursorExpiredException()
        }
        val baseline = recover()
        require(CURSOR_ALPHABET.matches(baseline)) { "SNAPSHOT_BASELINE_INVALID" }
        cursorStore.save(profile.accountId,baseline)
        synchronized(this) { deliveredEventIds.clear() }
        statusSink?.report(EventStreamStatus.RECONNECTING)
    }

    private val deliveredEventIds = LinkedHashSet<String>()

    @Synchronized private fun wasDelivered(eventId: String?) = !eventId.isNullOrBlank() && eventId in deliveredEventIds

    private suspend fun FlowCollector<GatewayEvent>.deliverEvent(event: GatewayEvent, snapshot: GatewayProfile) {
        if (wasDelivered(event.id)) return
        try {
            if (!handlePlatformEvent(event)) {
                recordEventActivity(snapshot)
                emit(event)
            }
            if (event.event == "conversation.message.completed" || event.event == "conversation.message.delta") runCatching {
                val envelope = com.openandroidintelligence.gateway.schema.JsonFields.obj(com.openandroidintelligence.gateway.schema.Json.parse(event.data))
                val payload = com.openandroidintelligence.gateway.schema.JsonFields.obj(com.openandroidintelligence.gateway.schema.JsonFields.field(envelope, "payload")) ?: envelope
                com.openandroidintelligence.gateway.diagnostics.GatewayLog.protocolEvidence(event.event!!, mapOf(
                    "eventId" to event.id,
                    "correlationId" to com.openandroidintelligence.gateway.schema.JsonFields.string(envelope, "correlationId"),
                    "conversationId" to com.openandroidintelligence.gateway.schema.JsonFields.string(payload, "conversationId"),
                    "messageId" to com.openandroidintelligence.gateway.schema.JsonFields.string(payload, "messageId"),
                    "sender" to com.openandroidintelligence.gateway.schema.JsonFields.string(payload, "sender"),
                ))
            }
            // Cancellation, reducer and persistence failures leave it replayable.
            event.id?.takeIf { it.isNotBlank() }?.let { cursorStore.save(profile.accountId, it) }
            markEventDelivered(event.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cause: Throwable) {
            throw EventDeliveryFailed(cause)
        }
    }

    private class EventDeliveryFailed(cause: Throwable) : RuntimeException(cause)

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

    /** 可选诊断头：同账号、同逻辑操作跨请求保留 traceId，每次 HTTP 产生独立 span。 */
    internal fun operationTraceParent(logicalId: String): String {
        val trace = java.security.MessageDigest.getInstance("SHA-256")
            .digest((profile.accountId + "\u0000" + logicalId).toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
        val span = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        return "00-$trace-$span-00"
    }

    private fun transportPhase(phase: String, request: WireRequest, status: Int? = null) {
        com.openandroidintelligence.gateway.diagnostics.GatewayLog.protocolEvidence(phase, mapOf(
            "requestId" to RawHeaders.single(request.headers, "X-Open-Android-Intelligence-Request-Id"),
            "traceId" to RawHeaders.single(request.headers, "traceparent")?.split('-')?.getOrNull(1),
            "spanId" to RawHeaders.single(request.headers, "traceparent")?.split('-')?.getOrNull(2),
            "method" to request.method, "status" to status))
    }

    suspend fun execute(request: SignedGatewayRequest): GatewayResponse {
        val replayable = request.copy(requestId = request.requestId ?: newRequestId())
        var snapshot = sessionProfile()
        var response = executeOnce(replayable, snapshot)
        if (response.status == 401 && sessionRenewal != null) {
            snapshot = sessionRenewal.refresh(snapshot)
            response = executeOnce(replayable, snapshot)
        }
        return response
    }

    private suspend fun executeOnce(request: SignedGatewayRequest, snapshot: GatewayProfile): GatewayResponse {
        val wire = signedWireRequest(request, snapshot)
        transportPhase("http.io_queued", wire)
        return withContext(ioDispatcher) {
            transportPhase("http.io_dequeued", wire)
            request.beforeSend()
            transportPhase("http.dispatched", wire)
            val response = transport.execute(wire)
            transportPhase("http.response_received", wire, response.status)
            GatewayResponse(response.status, RawHeaders.validate(response.headers), response.body)
        }
    }

    /** Validate raw headers first; close a rejected stream before retrying the request. */
    suspend fun <T> consumeResponse(
        request: SignedGatewayRequest,
        consume: suspend (Int, List<RawHeader>, java.io.InputStream) -> T,
    ): T {
        val replayable = request.copy(requestId = request.requestId ?: newRequestId())
        var snapshot = sessionProfile()
        var canRecover = sessionRenewal != null
        while (true) {
            val wire = signedWireRequest(replayable, snapshot)
            transportPhase("http.io_queued", wire)
            val result = withContext(ioDispatcher) {
                transportPhase("http.io_dequeued", wire)
                replayable.beforeSend()
                transportPhase("http.dispatched", wire)
                transport.consumeResponse(wire) { status, rawHeaders, stream ->
                    transportPhase("http.headers_received", wire, status)
                    val headers = RawHeaders.validate(rawHeaders)
                    if (status == 401 && canRecover) ResponseAttempt.Rejected
                    else ResponseAttempt.Consumed(consume(status, headers, stream))
                }
            }
            when (result) {
                is ResponseAttempt.Consumed -> return result.value
                ResponseAttempt.Rejected -> {
                    snapshot = requireNotNull(sessionRenewal).refresh(snapshot)
                    canRecover = false
                }
            }
        }
    }

    private sealed interface ResponseAttempt<out T> {
        data class Consumed<T>(val value: T) : ResponseAttempt<T>
        data object Rejected : ResponseAttempt<Nothing>
    }

    private fun signedWireRequest(request: SignedGatewayRequest, snapshot: GatewayProfile): WireRequest {
        val validatedHeaders = RawHeaders.validate(request.headers)
        require(request.streamBody == null || request.body.isEmpty()) { "REQUEST_BODY_INVALID:multiple-bodies" }
        val input = request.streamBody?.let { body ->
            signedInput(snapshot, request.method, request.target, ByteArray(0), body.sha256Hex, request.requestId)
        } ?: signedInput(snapshot, request.method, request.target, request.body, requestId = request.requestId)
        val headers = validatedHeaders + authenticationHeaders(snapshot, input, signatureOf(input), request.method)
        return WireRequest(input.method, input.target, headers, request.body, request.streamBody)
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
        failedEventSession.get()?.takeIf { it.first == sessionProfile() }?.let { failed ->
            // Re-collecting a Flow or switching networks cannot make a rejected
            // credential valid. A newly published session permits another try.
            statusSink?.report(EventStreamStatus.FAILED)
            throw failed.second
        }
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
                val attemptedProfile = sessionProfile()
                try {
                    val stream = if (sessionRenewal == null) webSocketTransport.events(cursor)
                    else webSocketTransport.events(cursor, { recordEventActivity(attemptedProfile) }, attemptedProfile)
                    stream.collect { event ->
                        if (!receivedWsEventInAttempt) {
                            GatewayLog.d(TAG, "event stream live over websocket")
                        }
                        receivedAnyEventInAttempt = true
                        receivedWsEventInAttempt = true
                        backoffMillis = 1000L
                        consecutiveFailures = 0
                        statusSink?.report(EventStreamStatus.LIVE)

                        deliverEvent(event, attemptedProfile)
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
                    if (e is EventSessionRejectedException) {
                        if (recoverEventAuthentication(e.rejectedProfile ?: attemptedProfile)) {
                            statusSink?.report(EventStreamStatus.RECONNECTING)
                            continue
                        }
                        statusSink?.report(EventStreamStatus.FAILED)
                        throw e
                    }
                    if (e is EventDeliveryFailed) {
                        statusSink?.report(EventStreamStatus.FAILED)
                        throw requireNotNull(e.cause)
                    }
                    if (e is EventCursorExpiredException) {
                        rebuildCursor()
                        continue
                    }
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
                val attemptedProfile = sessionProfile()
                try {
                    val sseStoredCursor = cursorStore.load(profile.accountId)
                    val sseCursor = sseStoredCursor?.takeIf { CURSOR_ALPHABET.matches(it) }
                    val target = if (sseCursor == null) {
                        EVENTS_TARGET
                    } else {
                        "$EVENTS_TARGET?cursor=$sseCursor"
                    }

                    val parser = SseParser().also { parser ->
                        parser.onHeartbeat = {
                            if (!receivedAnyEventInAttempt) GatewayLog.d(TAG, "event stream live over sse heartbeat")
                            receivedAnyEventInAttempt = true
                            backoffMillis = 1000L
                            consecutiveFailures = 0
                            statusSink?.report(EventStreamStatus.LIVE)
                            recordEventActivity(attemptedProfile)
                        }
                    }

                    val headers = RawHeaders.validate(
                        listOf(
                            RawHeader("Accept", "text/event-stream"),
                            RawHeader("Cache-Control", "no-store"),
                        ),
                    )
                    val input = signedInput(attemptedProfile, "GET", target, ByteArray(0))
                    val streamHeaders = headers + authenticationHeaders(attemptedProfile, input, signatureOf(input), "GET")

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
                            deliverEvent(event, attemptedProfile)
                        }
                    }
                    // 持续订阅遇到正常 EOF 也代表连接中断；空流不能绕过失败上限，
                    // 已收过事件的流也不能在等待重连时继续显示 LIVE。
                    if (autoReconnect && currentCoroutineContext().isActive) {
                        streamFailed = true
                        lastFailure = java.io.IOException("EVENT_STREAM_CLOSED")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (e is EventSessionRejectedException) {
                        if (recoverEventAuthentication(e.rejectedProfile ?: attemptedProfile)) {
                            statusSink?.report(EventStreamStatus.RECONNECTING)
                            continue
                        }
                        statusSink?.report(EventStreamStatus.FAILED)
                        throw e
                    }
                    if (e is EventDeliveryFailed) {
                        statusSink?.report(EventStreamStatus.FAILED)
                        throw requireNotNull(e.cause)
                    }
                    if (e is EventCursorExpiredException) {
                        rebuildCursor()
                        continue
                    }
                    streamFailed = true
                    lastFailure = e
                    GatewayLog.w(TAG, "sse attempt failed: ${e.message}")
                }
            }

            // A round that delivered data proves a channel works. The socket
            // ending afterwards is a reconnect, not a downgrade worth keeping,
            // so ask for the preferred channel again: a WebSocket that was only
            // briefly unreachable must not be abandoned in favour of SSE for
            // the rest of the session.
            if (receivedAnyEventInAttempt) {
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

    internal fun signedInput(
        method: String,
        target: String,
        body: ByteArray,
        bodySha256Hex: String? = null,
        requestId: String? = null,
    ): SignedRequestInput = signedInput(sessionProfile(), method, target, body, bodySha256Hex, requestId)

    internal fun signatureOf(input: SignedRequestInput): String =
        signatureOf(signer, input)

    internal fun authenticationHeaders(
        input: SignedRequestInput,
        signatureBase64Url: String,
        method: String,
    ): List<RawHeader> = authenticationHeaders(sessionProfile(), input, signatureBase64Url, method)

    companion object {
        const val PROTOCOL_HEADER = "2.1"
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
            bodySha256Hex: String? = null,
            requestId: String? = null,
        ): SignedRequestInput =
            SignedRequestInput(
                method = method,
                target = target,
                accountId = profile.accountId,
                deviceId = profile.deviceId,
                sessionId = profile.sessionId,
                requestId = requestId?.also { require(CURSOR_ALPHABET.matches(it)) { "REQUEST_ID_INVALID" } } ?: newRequestId(),
                // Millisecond precision: the wire format is fixed at three fractional
                // digits and the Gateway refuses anything else.
                timestamp = RequestSigner.formatTimestamp(
                    java.time.Instant.ofEpochMilli(java.time.Instant.now().toEpochMilli()),
                ),
                nonce = newNonce(),
                body = body,
                bodySha256Hex = bodySha256Hex,
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
