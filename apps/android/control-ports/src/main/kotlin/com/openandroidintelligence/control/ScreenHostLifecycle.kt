package com.openandroidintelligence.control

data class ScreenHostStatus(
    val binding: ScreenAuthorizationBinding?,
    val local: ScreenLocalStatus?,
    val accessibilityError: String?,
    val captureError: String?,
    val capabilityErrors: Map<ScreenPermission, String?>,
)

/** Trusted, in-process host integration. None of these methods is a remote capability. */
interface ScreenHostLifecycle {
    val authorization: ScreenLocalAuthorization
    fun binding(): ScreenAuthorizationBinding?
    fun status(): ScreenHostStatus
    fun connect(accountId: String, pairingId: String, pairingGeneration: Long, localRevision: () -> Long)
    /** Registers one authenticated connection; the returned opaque generation distinguishes Cores. */
    fun registerConnection(accountId: String, pairingId: String, pairingGeneration: Long,
        localRevision: () -> Long): ScreenAuthorizationBinding
    /** Presentation only; selecting a chat never disconnects another registered connection. */
    fun selectConnection(binding: ScreenAuthorizationBinding)
    fun disconnectConnection(binding: ScreenAuthorizationBinding)
    fun disconnectAll()
    fun connectionChanged(live: Boolean)
    fun refreshAuthorization()
    fun disconnect()
    fun revoke()
    fun localSnapshot(): ScreenSnapshot
    fun localTarget(): ScreenTargetIdentity
    fun installConfirmation(requestId: String, parameterDigest: String, expiresAtEpochMs: Long)
    /** Request-scoped global policy decision; identity binding is not another capability policy. */
    fun installRequestAuthorization(
        requestId: String,
        permissions: Set<ScreenPermission>,
        expiresAtEpochMs: Long,
        parameterDigest: String,
        revalidate: () -> Unit,
    )
    /** Exact registered connection, supplied by its owning authenticated driver. */
    fun installRequestAuthorization(
        requestId: String,
        permissions: Set<ScreenPermission>,
        expiresAtEpochMs: Long,
        parameterDigest: String,
        binding: ScreenAuthorizationBinding,
        revalidate: () -> Unit,
    )
    /** CONTROL-only compatibility entry; does not show UI or grant Android permissions. */
    fun installControlAuthorization(
        requestId: String,
        parameterDigest: String,
        expiresAtEpochMs: Long,
        revalidate: () -> Unit,
    )
    /** Trusted driver heartbeat; never accepts authority from remote operation parameters. */
    fun heartbeatControl(requestId: String): Long
    fun heartbeatControl(requestId: String, binding: ScreenAuthorizationBinding): Long
    fun finishRequest(requestId: String)
    fun finishRequest(requestId: String, binding: ScreenAuthorizationBinding)
    fun cancelRequest(requestId: String)
    fun cancelRequest(requestId: String, binding: ScreenAuthorizationBinding)
    fun captureStopped()
}

/** Populated by the Android primitive bootstrap; absence means no provider, never implicit grant. */
object ScreenHostRegistry {
    /** Shared by every connection/Agent in this App process, independent of read/capture. */
    val controlLeases = ScreenControlLeaseManager()
    @Volatile var current: ScreenHostLifecycle? = null
}
