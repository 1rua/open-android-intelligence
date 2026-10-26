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
    fun connectionChanged(live: Boolean)
    fun refreshAuthorization()
    fun disconnect()
    fun revoke()
    fun localSnapshot(): ScreenSnapshot
    fun localTarget(): ScreenTargetIdentity
    fun installConfirmation(requestId: String, parameterDigest: String, expiresAtEpochMs: Long)
    fun finishRequest(requestId: String)
    fun cancelRequest(requestId: String)
    fun captureStopped()
}

/** Populated by the Android primitive bootstrap; absence means no provider, never implicit grant. */
object ScreenHostRegistry {
    @Volatile var current: ScreenHostLifecycle? = null
}
