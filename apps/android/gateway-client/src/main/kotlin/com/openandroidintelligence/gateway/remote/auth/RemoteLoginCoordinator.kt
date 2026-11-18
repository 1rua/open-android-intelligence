package com.openandroidintelligence.gateway.remote.auth

import com.openandroidintelligence.gateway.remote.identity.RemoteAccountAuthorizationToken
import com.openandroidintelligence.gateway.remote.identity.RemoteIdentityScope
import com.openandroidintelligence.gateway.remote.identity.RemoteRole
import com.openandroidintelligence.gateway.remote.identity.RemoteRoleCredential
import com.openandroidintelligence.gateway.remote.identity.RemoteStaticIdentity
import com.openandroidintelligence.gateway.remote.identity.RemoteTokenPair
import kotlinx.coroutines.CancellationException

enum class RemoteRoleRegistrationState { REGISTERED, FAILED }

data class RemoteRoleRegistration(
    val role: RemoteRole,
    val state: RemoteRoleRegistrationState,
    val credential: RemoteRoleCredential? = null,
    val errorCode: String? = null,
)

data class RemoteLoginResult(
    val accountId: String,
    val client: RemoteRoleRegistration,
    val host: RemoteRoleRegistration,
)

interface RemoteRoleRegistrar {
    suspend fun registerAccountRole(
        scope: RemoteIdentityScope,
        identity: RemoteStaticIdentity,
        accountToken: RemoteAccountAuthorizationToken,
    ): RemoteRoleCredential
}

/**
 * 登录只在内存中持有短期 account token，并独立提交 Client/Host 注册。
 * 一侧失败只留下该角色的失败状态，不能覆盖另一侧已保存的凭据。
 */
class RemoteLoginCoordinator(
    private val store: RemoteIdentityStore,
    private val registrar: RemoteRoleRegistrar,
) {
    suspend fun registerRoles(
        origin: String,
        accountId: String,
        accountToken: RemoteAccountAuthorizationToken,
        clientIdentity: RemoteStaticIdentity,
        hostIdentity: RemoteStaticIdentity,
    ): RemoteLoginResult {
        require(clientIdentity.deviceId != hostIdentity.deviceId && !clientIdentity.publicKey.contentEquals(hostIdentity.publicKey)) {
            "REMOTE_ROLE_IDENTITIES_MUST_DIFFER"
        }
        val ticket = try { store.beginRegistration(origin, accountId) }
        catch (_: RemoteCredentialCleanupException) {
            return RemoteLoginResult(accountId, failed(RemoteRole.CLIENT, "REMOTE_CREDENTIAL_CLEANUP_FAILED"),
                failed(RemoteRole.HOST, "REMOTE_CREDENTIAL_CLEANUP_FAILED"))
        }
        val client = registerOne(ticket, RemoteIdentityScope(origin, accountId, RemoteRole.CLIENT), clientIdentity, accountToken)
        val host = if (client.errorCode == "REMOTE_REGISTRATION_SUPERSEDED") failed(RemoteRole.HOST, "REMOTE_REGISTRATION_SUPERSEDED")
            else registerOne(ticket, RemoteIdentityScope(origin, accountId, RemoteRole.HOST), hostIdentity, accountToken)
        try { store.requireRegistrationCurrent(ticket) }
        catch (_: RemoteRegistrationSupersededException) {
            return RemoteLoginResult(accountId, failed(RemoteRole.CLIENT, "REMOTE_REGISTRATION_SUPERSEDED"),
                failed(RemoteRole.HOST, "REMOTE_REGISTRATION_SUPERSEDED"))
        }
        return RemoteLoginResult(accountId, client, host)
    }

    private suspend fun registerOne(
        ticket: RemoteRegistrationTicket,
        scope: RemoteIdentityScope,
        identity: RemoteStaticIdentity,
        accountToken: RemoteAccountAuthorizationToken,
    ): RemoteRoleRegistration {
        val role = scope.role
        return try {
            store.requireRegistrationCurrent(ticket)
            val credential = registrar.registerAccountRole(scope, identity, accountToken)
            store.requireRegistrationCurrent(ticket)
            if (!identity.sameIdentity(credential.identity)) {
                return failed(role, "REMOTE_REGISTERED_IDENTITY_MISMATCH")
            }
            val saved = store.commitRegistration(ticket, scope, credential)
            RemoteRoleRegistration(role, RemoteRoleRegistrationState.REGISTERED, saved)
        } catch (_: RemoteRegistrationSupersededException) {
            failed(role, "REMOTE_REGISTRATION_SUPERSEDED")
        } catch (_: RemoteRegistrationPeerPinMismatchException) {
            failed(role, "REMOTE_PEER_PIN_MISMATCH")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed(role, "REMOTE_ROLE_REGISTRATION_FAILED")
        }
    }

    private fun failed(role: RemoteRole, code: String) = RemoteRoleRegistration(role, RemoteRoleRegistrationState.FAILED, errorCode = code)
}

sealed interface RemoteTokenRotationResult {
    data class Rotated(val credential: RemoteRoleCredential) : RemoteTokenRotationResult
    data class Failed(val code: String) : RemoteTokenRotationResult
}

fun interface RemoteTokenRefresher {
    suspend fun refresh(scope: RemoteIdentityScope, deviceId: String, refreshToken: String): RemoteTokenPair
}

class RemoteTokenRotator(
    private val store: RemoteIdentityStore,
    private val refresher: RemoteTokenRefresher,
) {
    suspend fun rotate(scope: RemoteIdentityScope): RemoteTokenRotationResult {
        val current = try { store.load(scope) }
        catch (failure: RemoteIdentityLockedException) { return RemoteTokenRotationResult.Failed(failure.message!!) }
        catch (failure: RemoteIdentityCorruptedException) { return RemoteTokenRotationResult.Failed(failure.message!!) }
            ?: return RemoteTokenRotationResult.Failed("REMOTE_CREDENTIAL_MISSING")
        val nextTokens = try {
            refresher.refresh(scope, current.identity.deviceId.value, current.tokens.refreshToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return RemoteTokenRotationResult.Failed("REMOTE_REFRESH_FAILED")
        }
        val next = current.copy(tokens = nextTokens)
        return try {
            val saved = store.rotateTokens(scope, current, next)
            if (saved != null) {
                RemoteTokenRotationResult.Rotated(saved)
            } else {
                RemoteTokenRotationResult.Failed("REMOTE_REFRESH_CONFLICT")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RemoteTokenRotationResult.Failed("REMOTE_REFRESH_PERSISTENCE_FAILED")
        }
    }
}
