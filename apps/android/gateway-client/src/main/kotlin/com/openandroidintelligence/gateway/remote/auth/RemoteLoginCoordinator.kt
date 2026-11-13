package com.openandroidintelligence.gateway.remote.auth

import com.openandroidintelligence.gateway.remote.identity.RemoteAccountAuthorizationToken
import com.openandroidintelligence.gateway.remote.identity.RemoteIdentityScope
import com.openandroidintelligence.gateway.remote.identity.RemoteRole
import com.openandroidintelligence.gateway.remote.identity.RemoteRoleCredential
import com.openandroidintelligence.gateway.remote.identity.RemoteStaticIdentity
import com.openandroidintelligence.gateway.remote.identity.RemoteTokenPair

enum class RemoteRoleRegistrationState { NOT_STARTED, REGISTERING, REGISTERED, FAILED }

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
        val client = registerOne(RemoteIdentityScope(origin, accountId, RemoteRole.CLIENT), clientIdentity, accountToken)
        val host = registerOne(RemoteIdentityScope(origin, accountId, RemoteRole.HOST), hostIdentity, accountToken)
        return RemoteLoginResult(accountId, client, host)
    }

    private suspend fun registerOne(
        scope: RemoteIdentityScope,
        identity: RemoteStaticIdentity,
        accountToken: RemoteAccountAuthorizationToken,
    ): RemoteRoleRegistration {
        val role = scope.role
        return try {
            val credential = registrar.registerAccountRole(scope, identity, accountToken)
            store.save(scope, credential)
            RemoteRoleRegistration(role, RemoteRoleRegistrationState.REGISTERED, credential)
        } catch (failure: Exception) {
            RemoteRoleRegistration(role, RemoteRoleRegistrationState.FAILED, errorCode = failure.message ?: "REMOTE_ROLE_REGISTRATION_FAILED")
        }
    }
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
        } catch (failure: Exception) {
            return RemoteTokenRotationResult.Failed(failure.message ?: "REMOTE_REFRESH_FAILED")
        }
        val next = current.copy(tokens = nextTokens)
        return try {
            if (store.rotateTokens(scope, current.tokens.refreshToken, next)) {
                RemoteTokenRotationResult.Rotated(next)
            } else {
                RemoteTokenRotationResult.Failed("REMOTE_REFRESH_CONFLICT")
            }
        } catch (failure: Exception) {
            RemoteTokenRotationResult.Failed(failure.message ?: "REMOTE_REFRESH_PERSISTENCE_FAILED")
        }
    }
}
