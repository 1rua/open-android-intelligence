package com.openandroidintelligence.control

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** In-process control ownership. A caller cannot acquire this through remote parameters. */
class ScreenControlLease internal constructor(
    val requestId: String,
    private val check: () -> Unit,
    private val renew: () -> Long,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ScreenControlLease>
    @Volatile internal var dispatchStarted = false
        private set

    /** Called only by the native backend at its last guard before a system effect. */
    fun beforeSystemEffect() {
        ensureCurrent()
        dispatchStarted = true
    }

    /** Rechecks ownership, expiry and local authorization immediately before a system effect. */
    fun ensureCurrent() = check()

    /** Returns remaining milliseconds; an expired or cancelled lease can never be renewed. */
    fun heartbeat(): Long = renew()
}

/**
 * One device-wide FIFO control queue. Reading/capturing the screen does not acquire it.
 *
 * The standard Mutex orders suspended contenders. Each wait is bounded by both the
 * request deadline and MAX_QUEUE_WAIT_MS. Ownership is held until the action unwinds,
 * including cancellation cleanup; cancelling an uncooperative action never unlocks a
 * second controller underneath it. No action is retried here.
 *
 * [revalidate] must read current trusted authorization/connection state, not a cached
 * admission decision. It runs before admission, after waiting, on heartbeat and through
 * the lease's final-dispatch guard. ScreenBackend additionally validates screen revision.
 */
class ScreenControlLeaseManager(
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    companion object {
        const val INITIAL_LEASE_MS = 30_000L
        const val MAX_LEASE_MS = 5 * 60_000L
        const val MAX_QUEUE_WAIT_MS = 5 * 60_000L
        const val MAX_REQUESTS = 64
    }

    private class Entry(
        val requestId: String,
        val expiresAtEpochMs: Long,
        val admittedAt: TimeMark,
        val requestLifetimeMs: Long,
        val job: Job,
        val revalidate: () -> Unit,
    ) {
        var acquiredAt: TimeMark? = null
        var acquiredAtEpochMs = 0L
        var renewedAt: TimeMark? = null
        var renewedAtEpochMs = 0L
        var stopCode: String? = null
    }

    private class LeaseCancelled(val code: String) : CancellationException(code)
    private val ownership = Mutex()
    private val state = Any()
    private val entries = mutableMapOf<String, Entry>()
    private var active: Entry? = null
    private var revocation = 0L
    private var lastRevocationCode = "SCREEN_CONTROL_REVOKED"

    suspend fun <T> withLease(
        requestId: String,
        expiresAtEpochMs: Long,
        revalidate: () -> Unit,
        action: suspend (ScreenControlLease) -> T,
    ): T {
        require(requestId.isNotBlank())
        // Nesting would deadlock or accidentally grant a second owner to the same action.
        if (currentCoroutineContext()[ScreenControlLease] != null) {
            throw ScreenOperationException("SCREEN_CONTROL_LEASE_NESTED")
        }
        var heldLease: ScreenControlLease? = null
        try {
            return coroutineScope {
                currentCoroutineContext().ensureActive()
                val admittedRevision = synchronized(state) { revocation }
                revalidate()
                val now = clock()
                require(now >= 0L)
                if (expiresAtEpochMs <= now) throw ScreenOperationException("SCREEN_REQUEST_EXPIRED")
                val entry = Entry(requestId, expiresAtEpochMs, timeSource.markNow(),
                    expiresAtEpochMs - now, checkNotNull(currentCoroutineContext()[Job]), revalidate)
                synchronized(state) {
                    if (admittedRevision != revocation) throwFailure(lastRevocationCode)
                    if (requestId in entries) throw ScreenOperationException("SCREEN_REQUEST_IN_PROGRESS")
                    if (entries.size >= MAX_REQUESTS) throw ScreenOperationException("SCREEN_CONTROL_QUEUE_FULL")
                    entries[requestId] = entry
                }
                var locked = false
                var expiryJob: Job? = null
                try {
                    try {
                        withTimeout(minOf(MAX_QUEUE_WAIT_MS, requestRemaining(entry)).coerceAtLeast(1L)) {
                            ownership.lock(entry)
                            locked = true
                        }
                    } catch (_: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        throw ScreenOperationException(if (requestRemaining(entry) <= 0L)
                            "SCREEN_REQUEST_EXPIRED" else "SCREEN_CONTROL_QUEUE_TIMEOUT")
                    }
                    synchronized(state) {
                        checkPending(entry)
                        entry.acquiredAt = timeSource.markNow()
                        entry.acquiredAtEpochMs = clock()
                        entry.renewedAt = timeSource.markNow()
                        entry.renewedAtEpochMs = clock()
                        active = entry
                    }
                    val lease = ScreenControlLease(requestId, { ensureCurrent(entry) }, { heartbeat(entry) })
                    heldLease = lease
                    expiryJob = launch {
                        while (true) {
                            val remaining = synchronized(state) { remaining(entry) }
                            if (remaining <= 0L) {
                                stop(entry, expiryCode(entry))
                                break
                            }
                            delay(remaining)
                        }
                    }
                    withContext(lease) {
                        lease.ensureCurrent()
                        action(lease).also { lease.ensureCurrent() }
                    }
                } finally {
                    expiryJob?.cancel()
                    synchronized(state) {
                        if (active === entry) active = null
                        entries.remove(requestId, entry)
                    }
                    if (locked) ownership.unlock(entry)
                }
            }
        } catch (cancelled: LeaseCancelled) {
            if (heldLease?.dispatchStarted == true) throw ScreenOperationException("SCREEN_ACTION_OUTCOME_UNKNOWN")
            throwFailure(cancelled.code)
        } catch (failure: Exception) {
            if (heldLease?.dispatchStarted == true &&
                (failure is ScreenAuthorizationException ||
                    failure is ScreenOperationException && failure.code in setOf(
                        "SCREEN_REQUEST_EXPIRED", "SCREEN_CONTROL_LEASE_EXPIRED", "SCREEN_CONTROL_LEASE_INACTIVE", "SCREEN_CONTROL_CANCELLED"))) {
                throw ScreenOperationException("SCREEN_ACTION_OUTCOME_UNKNOWN")
            }
            throw failure
        }
    }

    /** Trusted driver heartbeat; queued requests cannot extend the current owner's lease. */
    fun heartbeat(requestId: String): Long = heartbeat(synchronized(state) {
        entries[requestId] ?: throw ScreenOperationException("SCREEN_CONTROL_LEASE_INACTIVE")
    })

    fun cancel(requestId: String) {
        val entry = synchronized(state) { entries[requestId] } ?: return
        stop(entry, "SCREEN_CONTROL_CANCELLED")
    }

    /** Emergency stop invalidates both the owner and every already queued request. */
    fun revokeAll() = stopAll("SCREEN_CONTROL_REVOKED")

    /** Call on loss/timeout of the authenticated control connection, never to renew it. */
    fun connectionTimedOut() = stopAll("SCREEN_CONNECTION_TIMED_OUT")

    /** One connection's request must not cancel a different connection's owner/waiters. */
    fun connectionTimedOut(requestId: String) {
        val entry = synchronized(state) { entries[requestId] } ?: return
        stop(entry, "SCREEN_CONNECTION_TIMED_OUT")
    }

    private fun stopAll(code: String) {
        val stopped = synchronized(state) {
            revocation++
            lastRevocationCode = code
            entries.values.toList().onEach { if (it.stopCode == null) it.stopCode = code }
        }
        stopped.forEach { it.job.cancel(LeaseCancelled(checkNotNull(it.stopCode))) }
    }

    private fun stop(entry: Entry, code: String) {
        val reason = synchronized(state) {
            if (entries[entry.requestId] !== entry) return
            if (entry.stopCode == null) entry.stopCode = code
            checkNotNull(entry.stopCode)
        }
        entry.job.cancel(LeaseCancelled(reason))
    }

    private fun checkPending(entry: Entry) {
        entry.stopCode?.let { throwFailure(it) }
        entry.job.ensureActive()
        if (requestRemaining(entry) <= 0L) throw ScreenOperationException("SCREEN_REQUEST_EXPIRED")
    }

    private fun checkActive(entry: Entry) {
        checkPending(entry)
        if (active !== entry) throw ScreenOperationException("SCREEN_CONTROL_LEASE_INACTIVE")
        if (remaining(entry) <= 0L) throw ScreenOperationException(expiryCode(entry))
    }

    private fun ensureCurrent(entry: Entry) {
        synchronized(state) { checkActive(entry) }
        entry.revalidate()
        // A revoke or deadline may race with revalidation itself.
        synchronized(state) { checkActive(entry) }
    }

    private fun heartbeat(entry: Entry): Long {
        ensureCurrent(entry)
        return synchronized(state) {
            checkActive(entry)
            entry.renewedAt = timeSource.markNow()
            entry.renewedAtEpochMs = clock()
            remaining(entry)
        }
    }

    private fun requestRemaining(entry: Entry): Long = minOf(
        entry.requestLifetimeMs - entry.admittedAt.elapsedNow().inWholeMilliseconds,
        entry.expiresAtEpochMs - clock(),
    )

    private fun remaining(entry: Entry): Long {
        val acquired = entry.acquiredAt ?: return requestRemaining(entry)
        val renewed = checkNotNull(entry.renewedAt)
        return minOf(requestRemaining(entry),
            MAX_LEASE_MS - acquired.elapsedNow().inWholeMilliseconds,
            MAX_LEASE_MS - (clock() - entry.acquiredAtEpochMs).coerceAtLeast(0L),
            INITIAL_LEASE_MS - renewed.elapsedNow().inWholeMilliseconds,
            // Also fail closed across Android sleep, when JVM nanoTime may be paused.
            INITIAL_LEASE_MS - (clock() - entry.renewedAtEpochMs).coerceAtLeast(0L))
    }

    private fun throwFailure(code: String): Nothing {
        if (code == "SCREEN_CONTROL_REVOKED" || code == "SCREEN_CONNECTION_TIMED_OUT") {
            throw ScreenAuthorizationException(code)
        }
        throw ScreenOperationException(code)
    }

    private fun expiryCode(entry: Entry) = if (requestRemaining(entry) <= 0L)
        "SCREEN_REQUEST_EXPIRED" else "SCREEN_CONTROL_LEASE_EXPIRED"
}
