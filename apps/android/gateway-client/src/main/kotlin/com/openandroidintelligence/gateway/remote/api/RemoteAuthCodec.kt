package com.openandroidintelligence.gateway.remote.api

import com.openandroidintelligence.gateway.remote.identity.RemoteAccountAuthorizationToken
import com.openandroidintelligence.gateway.remote.identity.RemoteDeviceId
import com.openandroidintelligence.gateway.remote.identity.RemoteRole
import com.openandroidintelligence.gateway.remote.identity.RemoteStaticIdentity
import com.openandroidintelligence.gateway.remote.protocol.RemoteAccountCodec
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue

data class RemoteAuthRequest(
    val path: String,
    val body: JsonValue.JObject,
    val bearerToken: String? = null,
)

/** 固定上游 HTTP 请求的纯编解码；不执行网络，也不持久化短期 account token。 */
object RemoteAuthCodec {
    fun accountLogin(email: String, password: CharArray): RemoteAuthRequest {
        require(email.isNotBlank()) { "REMOTE_LOGIN_ACCOUNT_REQUIRED" }
        require(password.isNotEmpty()) { "REMOTE_LOGIN_PASSWORD_REQUIRED" }
        return RemoteAuthRequest(
            path = "/api/v1/auth/login",
            body = Json.of(mapOf("email" to email.trim(), "password" to String(password))) as JsonValue.JObject,
        )
    }

    fun registerAccountRole(identity: RemoteStaticIdentity, role: RemoteRole, accountToken: RemoteAccountAuthorizationToken): RemoteAuthRequest =
        register("/api/v1/devices/register", identity, role, accountToken.value)

    fun registerOwnedRole(identity: RemoteStaticIdentity, role: RemoteRole, authorizingAccessToken: String): RemoteAuthRequest =
        register("/api/v1/devices/register-owned-role", identity, role, authorizingAccessToken)

    fun refresh(deviceId: RemoteDeviceId, refreshToken: String): RemoteAuthRequest {
        require(refreshToken.length >= 16) { "REMOTE_REFRESH_TOKEN_INVALID" }
        return RemoteAuthRequest(
            path = "/api/v1/auth/refresh",
            body = Json.of(mapOf("deviceId" to deviceId.value, "refreshToken" to refreshToken)) as JsonValue.JObject,
        )
    }

    fun parseTokenPair(value: JsonValue) = RemoteAccountCodec.parseTokenPair(value)

    private fun register(path: String, identity: RemoteStaticIdentity, role: RemoteRole, bearer: String): RemoteAuthRequest {
        require(bearer.length >= 16) { "REMOTE_AUTHORIZATION_TOKEN_INVALID" }
        return RemoteAuthRequest(path, Json.of(mapOf(
            "v" to 1,
            "device" to mapOf(
                "deviceId" to identity.deviceId.value,
                "name" to identity.deviceId.value,
                "role" to role.wireValue,
                "platform" to "android",
                "identityKey" to java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(identity.publicKey),
                "clientVersion" to "oai-android",
            ),
        )) as JsonValue.JObject, bearer)
    }
}
