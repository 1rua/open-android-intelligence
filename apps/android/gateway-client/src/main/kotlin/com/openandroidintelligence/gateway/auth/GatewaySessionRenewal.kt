package com.openandroidintelligence.gateway.auth

import com.openandroidintelligence.gateway.http.GatewayProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel

/** One shared, account-bound session snapshot for HTTP and both event channels. */
class GatewaySessionRenewal(
    initial: GatewayProfile,
    scope: CoroutineScope,
    private val renew: suspend (GatewayProfile) -> GatewayProfile,
) : AutoCloseable {
    private val lock = Any()
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val renewalScope = CoroutineScope(scope.coroutineContext + owner)
    @Volatile private var snapshot = initial
    private var closed = false
    private var inFlight: Deferred<GatewayProfile>? = null
    private var eventRecoveryMarker: Any? = null

    val current: GatewayProfile get() = snapshot

    internal fun requireScope(profile: GatewayProfile) {
        val current = snapshot
        require(profile.accountId == current.accountId && profile.deviceId == current.deviceId &&
            profile.gatewayBaseUrl == current.gatewayBaseUrl &&
            profile.pinnedSpkiSha256 == current.pinnedSpkiSha256) { "SESSION_RENEWAL_SCOPE_CHANGED" }
    }

    /** Late failures from an older snapshot reuse its completed rotation. */
    suspend fun refresh(rejected: GatewayProfile = current): GatewayProfile = refreshTask(rejected).await()

    private fun refreshTask(rejected: GatewayProfile): Deferred<GatewayProfile> {
        val pending = synchronized(lock) {
            check(!closed && owner.isActive) { "SESSION_RENEWAL_CLOSED" }
            requireScope(rejected)
            if (snapshot != rejected) return kotlinx.coroutines.CompletableDeferred(snapshot)
            inFlight ?: run {
                lateinit var created: Deferred<GatewayProfile>
                created = renewalScope.async(start = CoroutineStart.LAZY) {
                    try {
                        val rotated = renew(rejected)
                        requireScope(rotated)
                        require(rotated.sessionId.isNotBlank() && rotated.accessToken.isNotBlank()) { "SESSION_RENEWAL_INVALID" }
                        synchronized(lock) {
                            check(!closed && owner.isActive) { "SESSION_RENEWAL_CLOSED" }
                            snapshot = rotated
                        }
                        rotated
                    } finally {
                        synchronized(lock) {
                            if (inFlight === created) inFlight = null
                        }
                    }
                }
                inFlight = created
                created
            }
        }
        pending.start()
        return pending
    }

    /** All event clients share one recovery episode, independent of ordinary HTTP renewal. */
    internal suspend fun recoverEventSession(
        rejected: GatewayProfile,
        allowRefresh: Boolean,
        onFailure: () -> Unit,
    ): Boolean {
        val (task, marker) = synchronized(lock) {
            check(!closed && owner.isActive) { "SESSION_RENEWAL_CLOSED" }
            requireScope(rejected)
            if (snapshot != rejected) return true
            val existing = inFlight
            if (existing == null && (!allowRefresh || eventRecoveryMarker != null)) return false
            if (allowRefresh && eventRecoveryMarker == null) eventRecoveryMarker = Any()
            (existing ?: refreshTask(rejected)) to eventRecoveryMarker
        }
        task.invokeOnCompletion { cause ->
            if (cause != null) {
                synchronized(lock) {
                    if (marker != null && eventRecoveryMarker === marker) eventRecoveryMarker = null
                }
                onFailure()
            }
        }
        task.await()
        return true
    }

    /** A stale connection cannot replenish the replacement session's recovery budget. */
    internal fun recordEventActivity(profile: GatewayProfile): Boolean = synchronized(lock) {
        if (closed || !owner.isActive || inFlight != null || snapshot != profile) return false
        eventRecoveryMarker = null
        true
    }

    /** Synchronously fences publication before cancelling work owned by this session. */
    override fun close() {
        synchronized(lock) { closed = true }
        renewalScope.cancel()
    }
}
