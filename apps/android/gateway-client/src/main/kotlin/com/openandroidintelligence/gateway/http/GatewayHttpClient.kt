package com.openandroidintelligence.gateway.http

import com.openandroidintelligence.gateway.auth.GatewaySessionRenewal
import com.openandroidintelligence.gateway.auth.GatewayAuthException
import com.openandroidintelligence.gateway.auth.SessionRecoveryPausedException
import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import com.openandroidintelligence.gateway.negotiation.NegotiationHttpException
import com.openandroidintelligence.gateway.events.EventCursorStore
import com.openandroidintelligence.gateway.events.EventStreamStatus
import com.openandroidintelligence.gateway.events.EventStreamStatusSink
import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.events.SseParser
import com.openandroidintelligence.gateway.ws.GatewayWebSocketTransport
import com.openandroidintelligence.gateway.ws.GatewayWebSocketSignal
import com.openandroidintelligence.gateway.ws.GatewayWebSocketHandshakeException
import com.openandroidintelligence.gateway.ws.GatewayWebSocketPayloadException
import com.openandroidintelligence.gateway.ws.GatewayWebSocketClosedException
import com.openandroidintelligence.gateway.ws.GatewayWebSocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    /** 显式围栏在真实连接前持久保存身份；未提供时保留原传输入口与装饰器。 */
    val beforeDispatch: (suspend () -> Unit)? = null,
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
    /** Diagnostic threshold only. Recoverable network failures keep retrying. */
    private val maxConsecutiveFailures: Int = 6,
    private val handlePlatformEvent: suspend (GatewayEvent) -> Boolean = { false },
    private val sessionRenewal: GatewaySessionRenewal? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val jitterFn: (Long) -> Long = GatewayEventRetry::jitter,
    private val probeDelayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    init {
        if (sessionRenewal != null) {
            sessionRenewal.requireScope(profile)
            webSocketTransport?.bindSessionRenewal(sessionRenewal)
        }
    }

    private fun sessionProfile(): GatewayProfile = sessionRenewal?.current ?: profile

    /** 只读凭据代次；仅用于拒绝后的恢复栅栏，不包含访问令牌。 */
    fun credentialVersion(): String = sessionProfile().sessionId

    // A healthy sibling stream cannot repeatedly replenish a broken client's
    // authentication attempts. This fence also survives Flow re-collection.
    private val eventRecoveryOwner = Any()
    private val localEventRecoveryMarker = java.util.concurrent.atomic.AtomicReference<Any?>(null)
    private val failedEventSession = java.util.concurrent.atomic.AtomicReference<Pair<GatewayProfile, Throwable>?>(null)

    private data class HealthRequest(val reason: String)
    private class EventOwner(val id: Long) {
        var status: EventStreamStatus? = null
        val healthChecks = Channel<HealthRequest>(Channel.CONFLATED)
        val policyChanges = Channel<Unit>(Channel.CONFLATED)
        var lastWakeAt: Long? = null
    }
    private class CursorRecoveryAttempt(val client: GatewayHttpClient, val owner: EventOwner) :
        kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<CursorRecoveryAttempt>
    }
    private val eventOwnerLock = Any()
    private val activeEventOwner = java.util.concurrent.atomic.AtomicReference<EventOwner?>(null)
    @Volatile private var appForeground = false

    /** 生命周期只更新连接策略，不取消会话或创建新的订阅。 */
    fun setAppForeground(visible: Boolean) {
        appForeground = visible
        webSocketTransport?.setAppForeground(visible)
        activeEventOwner.get()?.policyChanges?.trySend(Unit)
    }

    fun currentEventGeneration(): Long? = activeEventOwner.get()?.id

    /** 合并显式恢复信号；健康流先验证活动，退避中的流可提前恢复一次。 */
    fun requestEventHealthCheck(reason: String) = synchronized(eventOwnerLock) {
        val owner = activeEventOwner.get() ?: return@synchronized
        val now = monotonicMillis()
        if (owner.lastWakeAt?.let { now - it < HEALTH_WAKE_COOLDOWN_MILLIS } == true) return@synchronized
        owner.lastWakeAt = now
        if (webSocketTransport?.requestHealthCheck() != true)
            owner.healthChecks.trySend(HealthRequest(reason.take(64)))
    }

    private fun requireEventOwner(owner: EventOwner) {
        if (activeEventOwner.get() !== owner) throw CancellationException("EVENT_STREAM_OWNER_REPLACED")
    }

    private fun reportEventStatus(owner: EventOwner, status: EventStreamStatus) = synchronized(eventOwnerLock) {
        if (activeEventOwner.get() === owner) {
            if (owner.status != status) GatewayLog.connectionEvent("connection.state_changed", owner.id,
                reason = "platform_events", result = status.name,
                fields = mapOf("previous" to owner.status?.name))
            owner.status = status
            statusSink?.report(status)
        }
    }

    private fun rememberEventFailure(owner: EventOwner, snapshot: GatewayProfile, failure: Throwable) = synchronized(eventOwnerLock) {
        if (activeEventOwner.get() === owner && sessionProfile() == snapshot) failedEventSession.set(snapshot to failure)
    }

    private fun recordEventActivity(snapshot: GatewayProfile, owner: EventOwner) = synchronized(eventOwnerLock) {
        if (activeEventOwner.get() !== owner || snapshot != sessionProfile()) return@synchronized
        if (sessionRenewal?.recordEventActivity(snapshot, eventRecoveryOwner) == true) localEventRecoveryMarker.set(null)
    }

    private suspend fun recoverEventAuthentication(rejected: GatewayProfile, owner: EventOwner): Boolean {
        requireEventOwner(owner)
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
            requireEventOwner(owner)
            rememberEventFailure(owner, rejected, paused)
            reportEventStatus(owner, EventStreamStatus.FAILED)
            throw paused
        }
        requireEventOwner(owner)
        if (!recovered) rememberEventFailure(owner, rejected, EventSessionRejectedException(rejected))
        return recovered || sessionProfile() != rejected
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
    private suspend fun rebuildCursor(owner: EventOwner) {
        requireEventOwner(owner)
        val recover = cursorRecovery ?: throw EventCursorExpiredException()
        val baseline = withContext(CursorRecoveryAttempt(this, owner)) { recover() }
        require(CURSOR_ALPHABET.matches(baseline)) { "SNAPSHOT_BASELINE_INVALID" }
        requireEventOwner(owner)
        try {
            cursorStore.save(profile.accountId, baseline)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            throw EventDeliveryFailed(failure)
        }
        synchronized(this) { deliveredEventIds.clear() }
        reportEventStatus(owner, EventStreamStatus.RECONNECTING)
    }

    private val deliveredEventIds = LinkedHashSet<String>()

    @Synchronized private fun wasDelivered(eventId: String?) = !eventId.isNullOrBlank() && eventId in deliveredEventIds

    private suspend fun FlowCollector<GatewayEvent>.deliverEvent(event: GatewayEvent, snapshot: GatewayProfile, owner: EventOwner) {
        requireEventOwner(owner)
        if (wasDelivered(event.id)) return
        try {
            if (!handlePlatformEvent(event)) {
                if (snapshot == sessionProfile()) recordEventActivity(snapshot, owner)
                emit(event)
            }
            if (event.event == "conversation.message.completed" || event.event == "conversation.message.delta") runCatching {
                val envelope = com.openandroidintelligence.gateway.schema.JsonFields.obj(com.openandroidintelligence.gateway.schema.Json.parse(event.data))
                val payload = com.openandroidintelligence.gateway.schema.JsonFields.obj(com.openandroidintelligence.gateway.schema.JsonFields.field(envelope, "payload")) ?: envelope
                com.openandroidintelligence.gateway.diagnostics.GatewayLog.protocolEvidence(event.event!!, mapOf(
                    "correlationId" to com.openandroidintelligence.gateway.schema.JsonFields.string(envelope, "correlationId"),
                    "conversationId" to com.openandroidintelligence.gateway.schema.JsonFields.string(payload, "conversationId"),
                    "messageId" to com.openandroidintelligence.gateway.schema.JsonFields.string(payload, "messageId"),
                    "sender" to com.openandroidintelligence.gateway.schema.JsonFields.string(payload, "sender"),
                ))
            }
            // Cancellation, reducer and persistence failures leave it replayable.
            requireEventOwner(owner)
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
        val recovery = currentCoroutineContext()[CursorRecoveryAttempt]?.takeIf {
            it.client === this && request.method == "GET" && request.target == SNAPSHOT_TARGET
        }
        var snapshot = sessionProfile()
        var response = executeOnce(replayable, snapshot)
        if (response.status == 401 && sessionRenewal != null) {
            if (recovery == null) {
                snapshot = sessionRenewal.refresh(snapshot)
            } else {
                if (!recoverEventAuthentication(snapshot, recovery.owner)) throw EventSessionRejectedException(snapshot, httpStatus = 401)
                snapshot = sessionProfile()
            }
            response = executeOnce(replayable, snapshot)
        }
        if (recovery != null) {
            requireEventOwner(recovery.owner)
            when {
                response.status == 401 -> throw EventSessionRejectedException(snapshot, httpStatus = 401)
                response.status == 403 -> throw EventStreamForbiddenException(httpStatus = 403)
                response.status in 500..599 || response.status in setOf(408, 429) -> throw EventSnapshotRequestException(response.status)
                response.status !in 200..299 -> throw IllegalStateException("EVENT_SNAPSHOT_HTTP_FAILED:${response.status}")
            }
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
            val beforeDispatch = request.beforeDispatch
            val response = if (beforeDispatch == null) transport.execute(wire)
                else transport.execute(wire, beforeDispatch)
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
     * Uses WebSocket first; unavailable upgrades or recoverable setup errors allow SSE.
     * Network interruptions retry with bounded jitter and the latest committed cursor.
     * Healthy SSE periodically probes WebSocket recovery without a second event subscription.
     * Authentication, certificate identity and local delivery failures keep their own boundaries.
     */
    fun events(autoReconnect: Boolean = true): Flow<GatewayEvent> = flow {
        val owner = EventOwner(EVENT_OWNER_IDS.incrementAndGet())
        synchronized(eventOwnerLock) { activeEventOwner.set(owner) }
        try {
            failedEventSession.get()?.takeIf { it.first == sessionProfile() }?.let {
                reportEventStatus(owner, EventStreamStatus.FAILED)
                throw it.second
            }
            val reconnect = GatewayEventRetry(GatewayEventRetry.RECONNECT_MILLIS, jitterFn)
            val probes = GatewayEventRetry(GatewayEventRetry.PROBE_MILLIS, jitterFn)
            var preferWebSocket = webSocketTransport != null
            var inactiveWebSocketFailures = 0
            var promotingWebSocket = false
            var consecutiveFailures = 0
            var attempt = 0L
            var cursorRecoveryPending = false
            var rejectedSession: GatewayProfile? = null
            reportEventStatus(owner, EventStreamStatus.CONNECTING)
            while (currentCoroutineContext().isActive) {
                requireEventOwner(owner)
                val snapshot = sessionProfile()
                val health = AttemptHealth(owner, snapshot, attempt + 1)
                val usingWebSocket = preferWebSocket && webSocketTransport != null
                val recoveringSession = rejectedSession
                val rebuildingCursor = recoveringSession == null && cursorRecoveryPending
                val transportKind = when {
                    recoveringSession != null -> "RENEWAL"
                    rebuildingCursor -> "SNAPSHOT"
                    usingWebSocket -> "WS"
                    else -> "SSE"
                }
                attempt++
                if (attempt > 1) GatewayLog.connectionEvent("connection.reconnect_started", owner.id,
                    reason = "transport_retry", attempt = attempt, result = "started", fields = mapOf("transport" to transportKind))
                GatewayLog.d(TAG, "event attempt ownerID=${owner.id} attempt=$attempt transport=$transportKind monotonicAt=${monotonicMillis()}")
                try {
                    if (recoveringSession != null) {
                        if (!recoverEventAuthentication(recoveringSession, owner)) throw EventSessionRejectedException(recoveringSession)
                        rejectedSession = null
                        reportEventStatus(owner, EventStreamStatus.RECONNECTING)
                        continue
                    }
                    if (rebuildingCursor) {
                        rebuildCursor(owner)
                        cursorRecoveryPending = false
                        continue
                    }
                    if (preferWebSocket && webSocketTransport != null) {
                        webSocketTransport.events(
                            committedCursor(),
                            { health.activity(authentication = true) },
                            snapshot,
                            { signal ->
                                when (signal) {
                                    is GatewayWebSocketSignal.Ready -> health.ready()
                                    is GatewayWebSocketSignal.Activity -> health.activity()
                                    is GatewayWebSocketSignal.Closed -> health.closed(signal.code)
                                }
                            },
                        ).collect { event ->
                            health.activity()
                            deliverEvent(event, snapshot, owner)
                        }
                    } else if (collectSseAttempt(owner, snapshot, health, probes, autoReconnect)) {
                        // The probe socket is closed and the SSE collector has joined before loading the next cursor.
                        preferWebSocket = true
                        promotingWebSocket = true
                        reportEventStatus(owner, EventStreamStatus.CONNECTING)
                        continue
                    }
                    if (!autoReconnect) return@flow
                    if (usingWebSocket) throw GatewayWebSocketClosedException(health.closeCode ?: 1006)
                    throw java.io.IOException("EVENT_STREAM_CLOSED")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    requireEventOwner(owner)
                    if (failure is EventDeliveryFailed) {
                        reportEventStatus(owner, EventStreamStatus.FAILED)
                        throw requireNotNull(failure.cause)
                    }
                    if (failure is EventSessionRejectedException) {
                        logEventFailure(owner, attempt, transportKind, failure, health, null, "authentication")
                        if (recoveringSession != null || sessionRenewal == null) {
                            reportEventStatus(owner, EventStreamStatus.FAILED)
                            throw failure
                        }
                        rejectedSession = failure.rejectedProfile ?: snapshot
                        reportEventStatus(owner, EventStreamStatus.RECONNECTING)
                        continue
                    }
                    // TLS extends IOException. Test identity and refresh outcome ambiguity before retry decisions.
                    if (GatewayConnectionSecurity.isSecurityFailure(failure) || failure is EventStreamForbiddenException ||
                        failure is SessionRecoveryPausedException || GatewayAuthException.credentialRefused(failure) ||
                        failure is NegotiationHttpException && failure.credentialRefused) {
                        if (failure is EventStreamForbiddenException && snapshot != sessionProfile()) continue
                        rememberEventFailure(owner, snapshot, failure)
                        logEventFailure(owner, attempt, transportKind, failure, health, null, "terminal")
                        reportEventStatus(owner, EventStreamStatus.FAILED)
                        throw failure
                    }
                    if (failure is EventCursorExpiredException) {
                        if (rebuildingCursor || cursorRecovery == null) {
                            reportEventStatus(owner, EventStreamStatus.FAILED)
                            if (cursorRecovery == null) cursorStore.clear(profile.accountId)
                            throw failure
                        }
                        cursorRecoveryPending = true
                        reportEventStatus(owner, EventStreamStatus.RECONNECTING)
                        continue
                    }
                    if (!recoverableEventFailure(failure)) {
                        reportEventStatus(owner, EventStreamStatus.FAILED)
                        logEventFailure(owner, attempt, transportKind, failure, health, null, "terminal")
                        throw failure
                    }
                    val businessWebSocketAttempt = recoveringSession == null && !rebuildingCursor && usingWebSocket
                    if (businessWebSocketAttempt) {
                        // Upgrade readiness is not activity. A quiet stream with
                        // real heartbeats is healthy; repeated upgraded sockets
                        // without a complete frame must yield to working SSE.
                        inactiveWebSocketFailures = if (health.hadActivity) 0 else
                            (inactiveWebSocketFailures + 1).coerceAtMost(2)
                        if (health.wasStable) probes.reset()
                    }
                    val upgradeCanFallback = failure is GatewayWebSocketHandshakeException &&
                        (failure.upgradeUnsupported || failure.status in 500..599 || failure.status in setOf(408, 429))
                    val handshakeNetworkFailure = failure !is GatewayWebSocketHandshakeException &&
                        failure !is GatewayWebSocketPayloadException && !health.isReady
                    val inactiveUpgradedStream = autoReconnect && health.isReady && !health.hadActivity &&
                        failure !is GatewayWebSocketPayloadException && (promotingWebSocket || inactiveWebSocketFailures >= 2)
                    if (businessWebSocketAttempt && (upgradeCanFallback || handshakeNetworkFailure || inactiveUpgradedStream)) {
                        // The failed formal WS has closed before SSE starts;
                        // neither readiness nor a successful probe commits a cursor.
                        preferWebSocket = false
                        promotingWebSocket = false
                        inactiveWebSocketFailures = 0
                        reportEventStatus(owner, EventStreamStatus.RECONNECTING)
                        logEventFailure(owner, attempt, "WS", failure, health, null, "fallback")
                        continue
                    }
                    if (businessWebSocketAttempt && health.hadActivity) promotingWebSocket = false
                    if (!autoReconnect) {
                        reportEventStatus(owner, EventStreamStatus.FAILED)
                        if (failure is GatewayWebSocketPayloadException || recoveringSession != null || rebuildingCursor) throw failure
                        return@flow
                    }
                    if (recoveringSession == null && !rebuildingCursor && !preferWebSocket) preferWebSocket = webSocketTransport != null
                    if (health.wasStable) {
                        reconnect.reset()
                        consecutiveFailures = 0
                    }
                    if (consecutiveFailures < Int.MAX_VALUE) consecutiveFailures++
                    reportEventStatus(owner, EventStreamStatus.RECONNECTING)
                    if (maxConsecutiveFailures > 0 && consecutiveFailures >= maxConsecutiveFailures) {
                        if (consecutiveFailures == maxConsecutiveFailures) GatewayLog.w(TAG, "event retry threshold ownerID=${owner.id} count=$consecutiveFailures")
                    }
                    val nextDelay = reconnect.nextDelayMillis()
                    GatewayLog.connectionEvent("connection.reconnect_scheduled", owner.id, reason = "transport_retry",
                        attempt = attempt, result = "scheduled", fields = mapOf("delayMs" to nextDelay))
                    logEventFailure(owner, attempt, transportKind, failure, health, nextDelay, "retry")
                    coroutineScope {
                        val waiting = async { delayFn(nextDelay) }
                        try {
                            kotlinx.coroutines.selects.select<Unit> {
                                waiting.onAwait { }
                                owner.healthChecks.onReceive { }
                            }
                        } finally { waiting.cancelAndJoin() }
                    }
                }
            }
        } finally {
            synchronized(eventOwnerLock) { activeEventOwner.compareAndSet(owner, null) }
            owner.healthChecks.close()
            owner.policyChanges.close()
        }
    }

    private fun recoverableEventFailure(failure: Throwable): Boolean = when (failure) {
        is SessionRecoveryPausedException -> false
        is NegotiationHttpException -> failure.retryable
        is GatewayAuthException -> !GatewayAuthException.credentialRefused(failure) && failure.retryable == true &&
            (failure.httpStatus in 500..599 || failure.httpStatus in setOf(408, 429))
        is java.nio.file.FileSystemException, is java.io.FileNotFoundException -> false
        is java.io.IOException -> !GatewayConnectionSecurity.isSecurityFailure(failure)
        else -> false
    }

    private fun logEventFailure(
        owner: EventOwner,
        attempt: Long,
        transport: String,
        failure: Throwable,
        health: AttemptHealth,
        nextDelay: Long?,
        action: String,
    ) {
        if (activeEventOwner.get() !== owner) return
        val now = monotonicMillis()
        val stage = when (failure) {
            is NegotiationHttpException -> "NEGOTIATION"
            is GatewayAuthException -> "RENEWAL"
            is EventSnapshotRequestException -> "SNAPSHOT"
            is GatewayWebSocketHandshakeException -> "HANDSHAKE"
            is GatewayWebSocketClosedException -> "CLOSE"
            is GatewayWebSocketPayloadException -> "PAYLOAD"
            is GatewayWebSocketTimeoutException -> failure.stage.name
            is EventStreamTimeoutException -> failure.stage.name
            is EventSessionRejectedException, is EventStreamForbiddenException -> "AUTH"
            is java.io.EOFException -> "INCOMPLETE_FRAME"
            is TimeoutCancellationException -> "PROBE_TIMEOUT"
            else -> when {
                transport == "SNAPSHOT" || transport == "RENEWAL" -> transport
                health.isReady -> "STREAM"
                else -> "HANDSHAKE"
            }
        }
        val status = when (failure) {
            is NegotiationHttpException -> failure.httpStatus
            is GatewayWebSocketHandshakeException -> failure.status
            is EventSnapshotRequestException -> failure.status
            is GatewayAuthException -> failure.httpStatus
            is EventSessionRejectedException -> failure.httpStatus
            is EventStreamForbiddenException -> failure.httpStatus
            else -> null
        }
        val close = when (failure) {
            is GatewayWebSocketClosedException -> failure.closeCode
            is EventSessionRejectedException -> failure.closeCode
            is EventStreamForbiddenException -> failure.closeCode
            else -> null
        }
        val lastHeartbeat = health.lastHeartbeatAt.get().takeIf { it >= 0 }
        GatewayLog.connectionEvent("connection.reconnect_failed", owner.id, reason = action, attempt = attempt,
            result = failure.javaClass.simpleName, fields = mapOf("transport" to transport, "status" to status,
                "closeCode" to close, "delayMs" to nextDelay))
        if (failure is GatewayWebSocketTimeoutException || failure is EventStreamTimeoutException)
            GatewayLog.connectionEvent("connection.heartbeat_timeout", owner.id, reason = stage, attempt = attempt,
                result = "timeout", fields = mapOf("transport" to transport, "idleMs" to now - health.lastCompleteAt.get()))
        GatewayLog.w(TAG, "event failure action=$action transport=$transport ownerID=${owner.id} attempt=$attempt " +
            "stage=$stage failure=${failure.javaClass.simpleName} status=$status closeCode=$close nextDelayMillis=$nextDelay " +
            "monotonicAt=$now activityElapsedMillis=${now - health.lastCompleteAt.get()} heartbeatElapsedMillis=${lastHeartbeat?.let { now - it }}")
    }

    private fun committedCursor(): String? = try {
        cursorStore.load(profile.accountId)?.takeIf { CURSOR_ALPHABET.matches(it) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        throw EventDeliveryFailed(failure)
    }

    private inner class AttemptHealth(private val owner: EventOwner, private val snapshot: GatewayProfile, private val attempt: Long) {
        private val liveSince = java.util.concurrent.atomic.AtomicLong(-1)
        val lastCompleteAt = java.util.concurrent.atomic.AtomicLong(monotonicMillis())
        val lastHeartbeatAt = java.util.concurrent.atomic.AtomicLong(-1)
        val activityVersion = MutableStateFlow(0L)
        val delivering = MutableStateFlow(false)
        @Volatile var isReady = false
            private set
        @Volatile var hadActivity = false
            private set
        @Volatile var wasStable = false
            private set
        @Volatile var closeCode: Int? = null
            private set

        fun closed(code: Int) { if (activeEventOwner.get() === owner) closeCode = code }

        fun ready() {
            if (activeEventOwner.get() !== owner) return
            if (!isReady && attempt > 1) {
                GatewayLog.connectionEvent("connection.reconnect_succeeded", owner.id, reason = "authenticated_stream",
                    attempt = attempt, result = "live")
                GatewayLog.connectionEvent("connection.subscription_restored", owner.id, reason = "committed_cursor",
                    attempt = attempt, result = "subscribed")
            }
            isReady = true
            liveSince.compareAndSet(-1, monotonicMillis())
            if (snapshot == sessionProfile()) reportEventStatus(owner, EventStreamStatus.LIVE)
        }

        fun activity(authentication: Boolean = false) {
            if (activeEventOwner.get() !== owner) return
            val now = monotonicMillis()
            hadActivity = true
            lastCompleteAt.set(now)
            activityVersion.value += 1
            owner.policyChanges.trySend(Unit)
            ready()
            if (now - liveSince.get() >= STABLE_CONNECTION_MILLIS) wasStable = true
            if (authentication) {
                lastHeartbeatAt.set(now)
                if (snapshot == sessionProfile()) recordEventActivity(snapshot, owner)
            }
        }
    }

    private sealed interface SseItem {
        data class Event(val event: GatewayEvent, val committed: CompletableDeferred<Unit>) : SseItem
        data class Finished(val failure: Throwable?) : SseItem
        data class Upgrade(val snapshot: GatewayProfile) : SseItem
        data class ProbeRejected(val snapshot: GatewayProfile, val failure: Throwable) : SseItem
    }

    /** The producer waits for each downstream commit, preserving Flow delivery and cursor transaction order. */
    private suspend fun FlowCollector<GatewayEvent>.collectSseAttempt(
        owner: EventOwner,
        snapshot: GatewayProfile,
        health: AttemptHealth,
        probes: GatewayEventRetry,
        autoReconnect: Boolean,
    ): Boolean = coroutineScope {
        val items = Channel<SseItem>(Channel.RENDEZVOUS)
        val healthy = CompletableDeferred<Unit>()
        val collector = this@collectSseAttempt
        val sse = launch {
            try {
                val activity = { health.activity(); healthy.complete(Unit); Unit }
                val parser = SseParser().also { it.onHeartbeat = {
                    health.activity(authentication = true)
                    healthy.complete(Unit)
                } }
                val cursor = committedCursor()
                val target = if (cursor == null) EVENTS_TARGET else "$EVENTS_TARGET?cursor=$cursor"
                val input = signedInput(snapshot, "GET", target, ByteArray(0))
                val headers = RawHeaders.validate(listOf(RawHeader("Accept", "text/event-stream"), RawHeader("Cache-Control", "no-store")) +
                    authenticationHeaders(snapshot, input, signatureOf(input), "GET"))
                transport.eventStream(WireRequest(input.method, input.target, headers, ByteArray(0))).collect { chunk ->
                    val events = parser.feedBytes(chunk)
                    if (events.isNotEmpty()) activity()
                    for (event in events) {
                        val committed = CompletableDeferred<Unit>()
                        health.delivering.value = true
                        try {
                            items.send(SseItem.Event(event, committed))
                            committed.await()
                        } finally {
                            health.lastCompleteAt.set(monotonicMillis())
                            health.delivering.value = false
                        }
                    }
                }
                items.send(SseItem.Finished(null))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                items.send(SseItem.Finished(failure))
            }
        }
        val watchdog = launch {
            while (isActive) {
                if (health.delivering.value) health.delivering.first { !it }
                val idleBudget = if (appForeground) transport.eventHeartbeatMillis()
                    ?.times(3)?.coerceIn(EVENT_HEALTH_CHECK_MILLIS, SSE_COMPLETE_FRAME_TIMEOUT_MILLIS)
                    ?: SSE_COMPLETE_FRAME_TIMEOUT_MILLIS else SSE_COMPLETE_FRAME_TIMEOUT_MILLIS
                val remaining = idleBudget - (monotonicMillis() - health.lastCompleteAt.get())
                if (remaining <= 0) {
                    items.send(SseItem.Finished(EventStreamTimeoutException(EventStreamTimeoutStage.IDLE)))
                    break
                }
                withTimeoutOrNull(remaining) { owner.policyChanges.receive() }
            }
        }
        val validation = launch {
            for (request in owner.healthChecks) {
                if (health.delivering.value) continue
                val observed = health.activityVersion.value
                val active = withTimeoutOrNull(EVENT_HEALTH_CHECK_MILLIS) {
                    health.activityVersion.first { it != observed }
                    true
                } == true
                if (!active && !health.delivering.value) {
                    GatewayLog.connectionEvent("connection.heartbeat_timeout", owner.id,
                        reason = request.reason, result = "validation_timeout", fields = mapOf("scope" to "transport", "foreground" to appForeground))
                    items.send(SseItem.Finished(EventStreamTimeoutException(EventStreamTimeoutStage.IDLE)))
                    break
                }
            }
        }
        val probe = if (autoReconnect && webSocketTransport != null) launch {
            healthy.await()
            var probeAttempt = 0L
            var probeDelay = probes.nextDelayMillis()
            while (isActive) {
                probeDelayFn(probeDelay)
                probeAttempt++
                requireEventOwner(owner)
                var attempted = sessionProfile()
                try {
                    val proved = withTimeout(GatewayWebSocketTransport.PROBE_TIMEOUT_MILLIS.toLong()) {
                        while (true) {
                            try {
                                return@withTimeout webSocketTransport.probe(attempted)
                            } catch (rejected: EventSessionRejectedException) {
                                if (!recoverEventAuthentication(rejected.rejectedProfile ?: attempted, owner)) throw rejected
                                attempted = sessionProfile()
                            }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        attempted
                    }
                    if (proved == attempted && proved == sessionProfile() && activeEventOwner.get() === owner) {
                        items.send(SseItem.Upgrade(proved))
                        break
                    }
                } catch (failure: Throwable) {
                    if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                    currentCoroutineContext().ensureActive()
                    if (activeEventOwner.get() !== owner) return@launch
                    if (sessionProfile() != attempted) continue
                    if (GatewayConnectionSecurity.isSecurityFailure(failure) || failure is EventStreamForbiddenException ||
                        failure is EventSessionRejectedException || failure is SessionRecoveryPausedException) {
                        logEventFailure(owner, probeAttempt, "WS_PROBE", failure, health, null, "terminal")
                        items.send(SseItem.ProbeRejected(attempted, failure))
                        break
                    }
                    probeDelay = probes.nextDelayMillis()
                    logEventFailure(owner, probeAttempt, "WS_PROBE", failure, health, probeDelay, "probe-deferred")
                }
            }
        } else null
        try {
            while (true) {
                requireEventOwner(owner)
                when (val item = items.receive()) {
                    is SseItem.Event -> {
                        collector.deliverEvent(item.event, snapshot, owner)
                        item.committed.complete(Unit)
                    }
                    is SseItem.Finished -> {
                        item.failure?.let { throw it }
                        return@coroutineScope false
                    }
                    is SseItem.Upgrade -> if (item.snapshot == sessionProfile()) {
                        // No formal WS event socket can overlap an SSE callback or cursor save.
                        sse.cancelAndJoin()
                        return@coroutineScope true
                    }
                    is SseItem.ProbeRejected -> if (item.snapshot == sessionProfile()) throw item.failure
                }
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } finally {
            sse.cancel()
            watchdog.cancel()
            validation.cancel()
            probe?.cancel()
            withContext(NonCancellable) {
                sse.join()
                watchdog.join()
                validation.join()
                probe?.join()
            }
            items.close()
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
        private const val EVENT_HEALTH_CHECK_MILLIS = 3_000L
        private const val HEALTH_WAKE_COOLDOWN_MILLIS = 5_000L
        const val PROTOCOL_HEADER = "2.1"
        const val EVENTS_TARGET = "/open-android-intelligence/v2/events"
        private const val SNAPSHOT_TARGET = "/open-android-intelligence/v2/sync/snapshot"
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
        private val EVENT_OWNER_IDS = java.util.concurrent.atomic.AtomicLong()
        private const val STABLE_CONNECTION_MILLIS = 60_000L
        private const val SSE_COMPLETE_FRAME_TIMEOUT_MILLIS = 60_000L
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
