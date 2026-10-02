package com.openandroidintelligence.gateway.auth

import com.openandroidintelligence.gateway.account.AccountProfile
import com.openandroidintelligence.gateway.account.AccountProfileStore

data class Session(
    val profileId: String,
    val refreshCredential: ByteArray,
)

/**
 * The outbound port the session manager uses to talk to a Gateway. It is a
 * port rather than an HTTP client so the session lifecycle can be proven
 * without a network stack; the HTTPS/SSE implementation arrives in Task 9.
 *
 * Every method receives the secret as an argument and returns a fresh
 * credential. Nothing here is allowed to retain it.
 */
interface GatewayAuthTransport {
    fun loginWithPassword(profile: AccountProfile, password: CharArray): ByteArray

    fun refresh(profile: AccountProfile, credential: ByteArray): ByteArray

    fun unpair(profile: AccountProfile, credential: ByteArray?)
}

/**
 * Owns the lifecycle of a Gateway session for one local profile.
 *
 * Rules enforced here:
 * - a password exists only for the duration of a single exchange and is
 *   scrubbed before the call returns;
 * - the refresh credential rotates on every resume, so a replayed old value
 *   stops working;
 * - logging out drops the credential but keeps the profile, while removing the
 *   account deletes the profile and its secrets;
 * - unpairing revokes the device remotely and deletes local key material.
 */
class GatewaySessionManager(
    private val profileStore: AccountProfileStore,
    private val credentialStore: GatewayCredentialStore,
    private val transport: GatewayAuthTransport,
) {

    fun loginWithPassword(profileId: String, password: CharArray): Session {
        val profile = requireProfile(profileId)
        require(password.isNotEmpty()) { "password must not be empty" }
        return try {
            val credential = transport.loginWithPassword(profile, password)
            credentialStore.saveRefresh(profileId, credential)
            Session(profileId, credential.copyOf())
        } finally {
            password.fill('\u0000')
        }
    }

    fun resume(profileId: String): Session {
        val profile = requireProfile(profileId)
        val current = credentialStore.loadRefresh(profileId)
            ?: error("NO_REFRESH_CREDENTIAL:$profileId")
        val rotated = transport.refresh(profile, current)
        credentialStore.saveRefresh(profileId, rotated)
        return Session(profileId, rotated.copyOf())
    }

    fun logout(profileId: String) {
        requireProfile(profileId)
        credentialStore.clearRefresh(profileId)
    }

    fun removeLocalAccount(profileId: String) {
        requireProfile(profileId)
        credentialStore.clearRefresh(profileId)
        credentialStore.clearDeviceKey(profileId)
        profileStore.delete(profileId)
    }

    fun unpair(profileId: String) {
        val profile = requireProfile(profileId)
        val credential = credentialStore.loadRefresh(profileId)
        transport.unpair(profile, credential)
        credentialStore.clearRefresh(profileId)
        credentialStore.clearDeviceKey(profileId)
    }

    private fun requireProfile(profileId: String): AccountProfile =
        profileStore.find(profileId) ?: error("UNKNOWN_PROFILE:$profileId")
}
