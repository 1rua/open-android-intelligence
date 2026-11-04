package com.openandroidintelligence.mobile.push

import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.kernel.SmsScopeMatcher
import com.openandroidintelligence.kernel.SmsScopeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Installation-owned push authority. Blocking capture/configuration methods must run on IO.
 * No passive-read grant is consulted. A fresh process always starts logged out; composition
 * must report the actual login state and supply an authenticated transport before delivery.
 */
class DevicePushRuntime(
    private val store: EncryptedPushStateStore,
    private val systemAccess: PushSystemAccess,
    private val clock: PushClock = SystemPushClock,
    private val attemptTimeoutMs: Long = 30_000,
) {
    private val lock = Any()
    private val drainLock = Mutex()
    private val connectionDrainLocks = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Mutex>()
    private var state = store.load()
    private var loggedIn = false
    private var loginEpoch = 0L
    private var healthy = true
    private val startedElapsed = clock.elapsedMs()
    private val startedEpoch = maxOf(clock.epochMs(), state.observedAtEpochMs)
    private val active = mutableSetOf<ActiveAttempt>()

    /** Process-local hook: composition enqueues unique Android system work, never starts a poll loop. */
    @Volatile var onWorkAvailable: (() -> Unit)? = null
    /** Cleanup metadata is already durable when this lock-free callback fires. */
    @Volatile var onRevocationAvailable: (() -> Unit)? = null
    @Volatile var revocationCallbackFailed: Boolean = false
        private set
    @Volatile var schedulingFailed: Boolean = false
        private set

    init {
        require(attemptTimeoutMs in 1..120_000)
        synchronized(lock) {
            // Keep the original event deadline on a clock rollback. A restart is not
            // revocation or proof of expiry; the persisted anchor and monotonic runtime
            // clock keep advancing without inventing a replacement retention deadline.
            maintainLocked()
        }
    }

    fun snapshot(): PushSnapshot = synchronized(lock) {
        checkHealthy()
        snapshotLocked()
    }

    fun configure(configuration: PushConfiguration, expectedConfiguration: PushConfiguration? = null): PushSnapshot {
        val cancellations: List<ActiveAttempt>
        val notifyCleanup: Boolean
        val snapshot: PushSnapshot
        synchronized(lock) {
            checkHealthy()
            check(expectedConfiguration == null || expectedConfiguration == state.configuration) { "PUSH_CONFIGURATION_CHANGED" }
            val nextConfig = configuration.frozen()
            val now = now()
            val remoteRevocations = mutableListOf<Pair<PushEndpoint, String>>()
            var revoked = 0L
            val events = state.events.mapNotNull { queued ->
                val allowed = permitsQueued(queued, nextConfig)
                val endpoints = endpoints(nextConfig, queued.event.kind)
                val targets = queued.targets.filterKeys { allowed && it in endpoints }
                remoteRevocations += (queued.targets.keys - targets.keys).map { it to queued.event.eventId }
                revoked += queued.targets.size - targets.size
                if (targets.isEmpty()) null else queued.copy(
                    retentionDeadlineEpochMs = minOf(
                        queued.retentionDeadlineEpochMs,
                        queued.event.capturedAtEpochMs + nextConfig.retentionHours * PUSH_HOUR_MS,
                    ),
                    targets = targets,
                )
            }
            var next = state.copy(configuration = nextConfig, events = events, revokedDeliveries = state.revokedDeliveries + revoked,
                smsCursor = state.smsCursor,
                revocations = mergeRevocations(state.revocations, remoteRevocations))
            next = prune(next, now)
            // Never silently evict unacknowledged data to save a smaller capacity setting.
            require(next.events.size <= nextConfig.maxEvents && queueBudgetSize(next) <= nextConfig.maxQueueBytes) {
                "PUSH_CAPACITY_BELOW_CURRENT_USAGE"
            }
            if (next != state) commit(next)
            cancellations = active.filter { !authorized(it.delivery, it.loginEpoch) }
            snapshot = snapshotLocked()
            notifyCleanup = state.revocations.isNotEmpty()
        }
        cancel(cancellations)
        if (notifyCleanup) notifyRevocations()
        wakeWork()
        return snapshot
    }

    /** Bounded HTTP batches; available even with no events, no enabled push, or no login. */
    fun pendingRevocations(): List<PushRevocation> = synchronized(lock) {
        checkHealthy()
        state.revocations.flatMap { (endpoint, ids) ->
            ids.toList().chunked(MAX_PUSH_REVOCATION_BATCH).map { PushRevocation(endpoint, it.toSet()) }
        }
    }

    /** Call only after the authenticated Core confirms this exact batch. Concurrent additions stay pending. */
    fun acknowledgeRevocation(revocation: PushRevocation): Boolean = synchronized(lock) {
        checkHealthy()
        val batch = revocation.frozen()
        val current = state.revocations[batch.endpoint] ?: return@synchronized false
        val remaining = current - batch.eventIds
        if (remaining.size == current.size) return@synchronized false
        val next = state.revocations.toMutableMap()
        if (remaining.isEmpty()) next.remove(batch.endpoint) else next[batch.endpoint] = remaining
        commit(state.copy(revocations = next, observedAtEpochMs = now()))
        true
    }

    /** Call on every authentication/session ownership change, including a true -> true switch. */
    fun onLoginStateChanged(hasLoggedInAccount: Boolean) {
        val cancellations = synchronized(lock) {
            loggedIn = hasLoggedInAccount
            loginEpoch += 1
            val attempts = active.toList()
            maintainLocked()
            attempts
        }
        cancel(cancellations)
        if (hasLoggedInAccount) wakeWork()
    }

    fun isCollectionAllowed(kind: PushKind): Boolean = synchronized(lock) {
        healthy && loggedIn && systemAccess.isGranted(kind) && when (kind) {
            PushKind.NOTIFICATION -> state.configuration.notifications.let { it.enabled && it.endpoints.isNotEmpty() && it.scope.packageIds.isNotEmpty() }
            PushKind.SMS -> state.configuration.sms.let { it.enabled && it.endpoints.isNotEmpty() &&
                SmsScopeMatcher.hasConditions(it.scope.mode, it.scope.senderAddresses, it.scope.keywords) }
        }
    }

    fun onNotification(notification: PushNotification): PushCaptureResult = synchronized(lock) {
        checkHealthy()
        if (!loggedIn) return@synchronized PushCaptureResult(PushCaptureStatus.LOGGED_OUT)
        if (!systemAccess.isGranted(PushKind.NOTIFICATION)) return@synchronized PushCaptureResult(PushCaptureStatus.DISABLED)
        val settings = state.configuration.notifications
        if (!settings.enabled || settings.endpoints.isEmpty()) return@synchronized PushCaptureResult(PushCaptureStatus.DISABLED)
        if (notification.packageName !in settings.scope.packageIds) return@synchronized PushCaptureResult(PushCaptureStatus.OUT_OF_SCOPE)
        val record = linkedMapOf<String, Any?>(
            "notificationKey" to notification.notificationKey,
            "packageName" to notification.packageName,
            "postedAtEpochMs" to notification.postedAtEpochMs,
            "appLabel" to notification.appLabel,
            "channelId" to notification.channelId,
        )
        if (settings.scope.includeContent) {
            record["title"] = notification.title
            record["body"] = notification.body
        }
        // Changes to a notification get distinct IDs; reconnecting the same OS record does not.
        enqueue(PushKind.NOTIFICATION, notification.postedAtEpochMs, listOf(
            notification.packageName, notification.notificationKey, notification.postedAtEpochMs, notification.title, notification.body,
        ), record, settings.endpoints)
    }.also { if (it.status == PushCaptureStatus.ENQUEUED) wakeWork() }

    fun onSms(sms: PushSms, expectedScope: SmsPushScope? = null): PushCaptureResult = synchronized(lock) {
        checkHealthy()
        if (!loggedIn) return@synchronized PushCaptureResult(PushCaptureStatus.LOGGED_OUT)
        if (!systemAccess.isGranted(PushKind.SMS)) return@synchronized PushCaptureResult(PushCaptureStatus.DISABLED)
        val settings = state.configuration.sms
        if (expectedScope != null && expectedScope != settings.scope)
            return@synchronized PushCaptureResult(PushCaptureStatus.OUT_OF_SCOPE)
        if (!settings.enabled || settings.endpoints.isEmpty()) return@synchronized PushCaptureResult(PushCaptureStatus.DISABLED)
        if (!SmsScopeMatcher.matches(settings.scope.mode, settings.scope.senderAddresses, settings.scope.keywords,
                sms.senderAddress, sms.body, sms.matchedKeywords)) {
            return@synchronized PushCaptureResult(PushCaptureStatus.OUT_OF_SCOPE)
        }
        val record = linkedMapOf<String, Any?>(
            "providerId" to sms.providerId,
            "senderAddress" to sms.senderAddress,
            "receivedAtEpochMs" to sms.receivedAtEpochMs,
            "threadId" to sms.threadId,
            "subscriptionId" to sms.subscriptionId,
        )
        if (settings.scope.includeContent) record["body"] = sms.body
        val proof = if (settings.scope.mode == SmsScopeMode.KEYWORDS)
            SmsScopeMatcher.matchingKeywords(sms.body, settings.scope.keywords) + sms.matchedKeywords.filter { matched ->
                settings.scope.keywords.any { it.equals(matched, ignoreCase = true) }
            } else emptySet()
        enqueue(PushKind.SMS, sms.receivedAtEpochMs, listOf(sms.providerId, sms.receivedAtEpochMs, sms.subscriptionId),
            record, settings.endpoints, PushSmsCursor(sms.providerId, sms.receivedAtEpochMs), proof)
    }.also { if (it.status == PushCaptureStatus.ENQUEUED) wakeWork() }

    fun smsCursor(): PushSmsCursor? = synchronized(lock) { checkHealthy(); state.smsCursor }

    /** A rejected native row has no payload, but its scan position must still be durable. */
    fun advanceSmsCursor(cursor: PushSmsCursor, expectedScope: SmsPushScope): Boolean = synchronized(lock) {
        checkHealthy()
        if (state.configuration.sms.scope != expectedScope || !isCollectionAllowed(PushKind.SMS)) return@synchronized false
        if (state.smsCursor == null || cursor > state.smsCursor!!) commit(state.copy(smsCursor = cursor))
        true
    }

    /** Safe while offline or logged out; expires content without opening a connection. */
    fun maintain(): PushSnapshot = synchronized(lock) {
        checkHealthy()
        maintainLocked()
        snapshotLocked()
    }

    /** One bounded drain, with no internal polling loop or implicit authentication fallback. */
    suspend fun deliver(
        transport: PushTransport,
        maxDeliveries: Int = 64,
        endpointAvailable: (PushEndpoint) -> Boolean = { true },
        connectionScope: Pair<String, String>? = null,
    ): PushDeliveryResult = (connectionScope?.let {
        connectionDrainLocks.computeIfAbsent(it) { Mutex() }
    } ?: drainLock).withLock {
        require(maxDeliveries in 1..512)
        val candidates = synchronized(lock) {
            checkHealthy()
            maintainLocked()
            if (!loggedIn) return@withLock deliveryResult(0, 0, 0, 0, paused = true)
            state.events.asSequence()
                .flatMap { queued -> queued.targets.keys.asSequence().map { PushDelivery(queued.event, it) } }
                .filter { endpointAvailable(it.endpoint) && (connectionScope == null ||
                    it.endpoint.coreInstanceId == connectionScope.first && it.endpoint.accountScope == connectionScope.second) }
                .take(maxDeliveries).toList()
        }
        var attempted = 0
        var accepted = 0
        var acknowledged = 0
        var failures = 0
        for (delivery in candidates) {
            if (!currentCoroutineContext().isActive) throw CancellationException()
            val outcome = supervisorScope {
                val epoch = synchronized(lock) { loginEpoch }
                val task = async(start = CoroutineStart.LAZY) {
                    withTimeout(attemptTimeoutMs) { attempt(transport, delivery, epoch) }
                }
                val attempt = ActiveAttempt(delivery, epoch, task)
                val registered = synchronized(lock) {
                    if (authorized(delivery, epoch)) {
                        advanceDeliveryLocked(delivery)
                        active += attempt
                        true
                    } else false
                }
                if (!registered) {
                    task.cancel()
                    return@supervisorScope AttemptOutcome.SKIPPED
                }
                attempted += 1
                try {
                    task.await()
                } catch (failure: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw failure
                    AttemptOutcome.RETRY
                } catch (_: Exception) {
                    AttemptOutcome.RETRY
                } finally { synchronized(lock) { active -= attempt } }
            }
            when (outcome) {
                AttemptOutcome.ACCEPTED -> accepted += 1
                AttemptOutcome.ACKNOWLEDGED -> acknowledged += 1
                AttemptOutcome.RETRY -> failures += 1
                AttemptOutcome.SKIPPED -> Unit
            }
        }
        synchronized(lock) { deliveryResult(attempted, accepted, acknowledged, failures, paused = !loggedIn) }
    }

    private fun advanceDeliveryLocked(delivery: PushDelivery) {
        val queued = state.events.first { it.event.eventId == delivery.event.eventId }
        if (state.events.last() == queued && queued.targets.keys.last() == delivery.endpoint) return
        val targets = queued.targets.toMutableMap()
        val receipt = targets.remove(delivery.endpoint)
        targets[delivery.endpoint] = receipt
        // The codec preserves list/map order. Persist progress before HTTP so retries,
        // unavailable routes and interrupted batches yield without losing any receipt.
        commit(state.copy(
            events = state.events.filter { it.event.eventId != delivery.event.eventId } + queued.copy(targets = targets),
            observedAtEpochMs = now(),
        ))
    }

    private suspend fun attempt(transport: PushTransport, delivery: PushDelivery, epoch: Long): AttemptOutcome {
        when (transport.verifyEndpoint(delivery.endpoint)) {
            // Remote discovery describes reachability, not the user's local policy. Even a
            // replaced generation keeps its exact queued targets until user removal or expiry.
            EndpointVerification.UNAVAILABLE, EndpointVerification.REVOKED -> return AttemptOutcome.RETRY
            EndpointVerification.VALID -> Unit
        }
        val stillAuthorized = { synchronized(lock) { authorized(delivery, epoch) } }
        if (!stillAuthorized()) return AttemptOutcome.SKIPPED
        val pendingId = synchronized(lock) { state.events.firstOrNull { it.event.eventId == delivery.event.eventId }?.targets?.get(delivery.endpoint) }
        val receipt = if (pendingId == null) transport.send(delivery, stillAuthorized)
            else transport.poll(delivery, pendingId, stillAuthorized)
        if (receipt.endpoint != delivery.endpoint || receipt.eventId != delivery.event.eventId ||
            receipt.deliveryId.isBlank() || receipt.deliveryId.toByteArray().size > 512 ||
            (pendingId != null && receipt.deliveryId != pendingId)
        ) return AttemptOutcome.RETRY
        return synchronized(lock) {
            if (!authorized(delivery, epoch)) return@synchronized AttemptOutcome.SKIPPED
            val queued = state.events.first { it.event.eventId == delivery.event.eventId }
            val targets = queued.targets.toMutableMap()
            when (receipt.state) {
                PushReceiptState.CONSUMED -> targets.remove(delivery.endpoint)
                PushReceiptState.ACCEPTED -> targets[delivery.endpoint] = receipt.deliveryId
                PushReceiptState.MISSING -> targets[delivery.endpoint] = null
                PushReceiptState.RETRY -> return@synchronized AttemptOutcome.RETRY
            }
            val events = state.events.mapNotNull {
                if (it.event.eventId != delivery.event.eventId) it
                else if (targets.isEmpty()) null else it.copy(targets = targets)
            }
            commit(state.copy(events = events, observedAtEpochMs = now()))
            when (receipt.state) {
                PushReceiptState.CONSUMED -> AttemptOutcome.ACKNOWLEDGED
                PushReceiptState.ACCEPTED -> AttemptOutcome.ACCEPTED
                else -> AttemptOutcome.RETRY
            }
        }
    }

    private fun enqueue(
        kind: PushKind,
        sourceAt: Long,
        sourceIdentity: List<Any?>,
        record: Map<String, Any?>,
        endpoints: Set<PushEndpoint>,
        smsCursor: PushSmsCursor? = null,
        smsMatchedKeywords: Set<String> = emptySet(),
    ): PushCaptureResult {
        maintainLocked()
        val now = now()
        val ttl = state.configuration.retentionHours * PUSH_HOUR_MS
        if (sourceAt > now + 5 * 60_000 || sourceAt <= now - ttl) return PushCaptureResult(PushCaptureStatus.OUT_OF_SCOPE)
        val id = "push_" + Json.sha256(Json.of(listOf(state.installationId, kind.name, sourceIdentity))).removePrefix("sha256:")
        val nextCursor = if (smsCursor != null && (state.smsCursor == null || smsCursor > state.smsCursor!!)) smsCursor else state.smsCursor
        if (id in state.seen) {
            if (nextCursor != state.smsCursor) commit(state.copy(smsCursor = nextCursor))
            return PushCaptureResult(PushCaptureStatus.DUPLICATE, id)
        }
        val payload = Json.canonical(Json.of(mapOf("kind" to kind.name.lowercase(), "record" to record)))
        if (payload.toByteArray().size > MAX_PUSH_EVENT_BYTES) return PushCaptureResult(PushCaptureStatus.TOO_LARGE, id)
        val expiry = minOf(now + ttl, sourceAt + ttl)
        val event = QueuedPushEvent(PushEvent(id, kind, now, expiry, payload), endpoints.associateWith { null },
            smsMatchedKeywords = smsMatchedKeywords.toSet())
        val next = state.copy(events = state.events + event, seen = state.seen + (id to expiry), smsCursor = nextCursor)
        if (next.events.size > state.configuration.maxEvents || next.seen.size > MAX_PUSH_SEEN_EVENTS ||
            queueBudgetSize(next) > state.configuration.maxQueueBytes || !hasRevocationCapacity(next)
        ) {
            commit(state.copy(capacityRejections = state.capacityRejections + 1))
            return PushCaptureResult(PushCaptureStatus.QUEUE_FULL, id)
        }
        commit(next)
        return PushCaptureResult(PushCaptureStatus.ENQUEUED, id)
    }

    private fun maintainLocked() {
        val next = prune(state, now())
        if (next != state) commit(next)
    }

    private fun prune(value: PushStoredState, now: Long): PushStoredState {
        val events = value.events.filter { it.retentionDeadlineEpochMs > now }
        return value.copy(
            observedAtEpochMs = now,
            events = events,
            seen = value.seen.filterValues { it > now },
            expiredEvents = value.expiredEvents + value.events.size - events.size,
            revocations = mergeRevocations(value.revocations, value.events.filter { it.retentionDeadlineEpochMs <= now }.flatMap { queued ->
                queued.targets.keys.map { it to queued.event.eventId }
            }),
        )
    }

    private fun authorized(delivery: PushDelivery, epoch: Long): Boolean = healthy && loggedIn && epoch == loginEpoch &&
        systemAccess.isGranted(delivery.event.kind) &&
        now() < delivery.event.expiresAtEpochMs && delivery.endpoint in endpoints(state.configuration, delivery.event.kind) &&
        state.events.any { it.event.eventId == delivery.event.eventId && delivery.endpoint in it.targets &&
            it.retentionDeadlineEpochMs > now() && permitsQueued(it, state.configuration) }

    private fun permitsQueued(queued: QueuedPushEvent, config: PushConfiguration): Boolean {
        val payload = JsonFields.obj(Json.parse(queued.event.payloadJson)) ?: throw PushStateUnavailable()
        val record = JsonFields.obj(JsonFields.field(payload, "record")) ?: throw PushStateUnavailable()
        return when (queued.event.kind) {
            PushKind.NOTIFICATION -> config.notifications.let { settings ->
                settings.enabled && JsonFields.string(record, "packageName") in settings.scope.packageIds &&
                    (settings.scope.includeContent || (JsonFields.field(record, "title") == null && JsonFields.field(record, "body") == null))
            }
            PushKind.SMS -> config.sms.let { settings ->
                settings.enabled && SmsScopeMatcher.matches(settings.scope.mode, settings.scope.senderAddresses, settings.scope.keywords,
                    JsonFields.string(record, "senderAddress"), JsonFields.string(record, "body"), queued.smsMatchedKeywords) &&
                    (settings.scope.includeContent || JsonFields.field(record, "body") == null)
            }
        }
    }

    private fun endpoints(config: PushConfiguration, kind: PushKind) = when (kind) {
        PushKind.NOTIFICATION -> if (config.notifications.enabled) config.notifications.endpoints else emptySet()
        PushKind.SMS -> if (config.sms.enabled) config.sms.endpoints else emptySet()
    }

    private fun now() = maxOf(clock.epochMs(), state.observedAtEpochMs,
        startedEpoch + (clock.elapsedMs() - startedElapsed).coerceAtLeast(0))

    // Reserve receipt space before admission, so HTTP acceptance cannot overflow the queue.
    private fun receiptReserve(value: PushStoredState): Int = value.events.sumOf { queued ->
        queued.targets.values.sumOf { id -> if (id == null) 516 else 512 - id.toByteArray().size }
    }

    // Cleanup metadata has an independent bounded reserve; disabling cannot be rejected because
    // the user chose a smaller body queue. Admission reserves enough space to revoke every target.
    private fun queueBudgetSize(value: PushStoredState): Int = encodedSize(value.copy(revocations = emptyMap())) + receiptReserve(value)

    private fun hasRevocationCapacity(value: PushStoredState): Boolean {
        val all = mergeRevocations(value.revocations, value.events.flatMap { queued ->
            queued.targets.keys.map { it to queued.event.eventId }
        })
        if (all.size > MAX_PUSH_REVOCATION_ROUTES || all.values.sumOf { it.size } > MAX_PUSH_REVOCATION_IDS) return false
        return encodedSize(value.copy(revocations = all)) + receiptReserve(value) <= MAX_PUSH_STATE_BYTES
    }

    private fun mergeRevocations(
        existing: Map<PushEndpoint, Set<String>>,
        additions: List<Pair<PushEndpoint, String>>,
    ): Map<PushEndpoint, Set<String>> {
        if (additions.isEmpty()) return existing
        val result = existing.mapValuesTo(linkedMapOf()) { it.value.toMutableSet() }
        additions.forEach { (endpoint, eventId) -> result.getOrPut(endpoint) { linkedSetOf() }.add(eventId) }
        return result.mapValues { it.value.toSet() }
    }

    private fun encodedSize(value: PushStoredState): Int {
        val bytes = PushStateCodec.encode(value)
        return try { bytes.size } finally { bytes.fill(0) }
    }

    private fun commit(next: PushStoredState) {
        try { state = store.save(state.revision, next) } catch (failure: Exception) {
            healthy = false
            loggedIn = false
            cancel(active.toList())
            throw failure
        }
    }

    private fun snapshotLocked() = PushSnapshot(
        state.configuration.frozen(), state.revision, loggedIn, state.events.size,
        state.events.sumOf { it.targets.size }, encodedSize(state), state.expiredEvents,
        state.revokedDeliveries, state.capacityRejections, state.revocations.values.sumOf { it.size },
    )

    private fun deliveryResult(attempted: Int, accepted: Int, acknowledged: Int, failures: Int, paused: Boolean) =
        PushDeliveryResult(attempted, accepted, acknowledged, state.events.sumOf { it.targets.size }, failures, paused)

    private fun cancel(attempts: List<ActiveAttempt>) {
        attempts.forEach { it.job.cancel() }
    }

    private fun notifyRevocations() {
        val notify = onRevocationAvailable ?: return
        try { notify(); revocationCallbackFailed = false } catch (_: Exception) { revocationCallbackFailed = true }
    }

    private fun wakeWork() {
        val wake = onWorkAvailable ?: return
        try { wake(); schedulingFailed = false } catch (_: Exception) { schedulingFailed = true }
    }

    private fun checkHealthy() { if (!healthy) throw PushStateUnavailable() }
    private data class ActiveAttempt(val delivery: PushDelivery, val loginEpoch: Long, val job: Job)
    private enum class AttemptOutcome { SKIPPED, ACCEPTED, ACKNOWLEDGED, RETRY }
}
