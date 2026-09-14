package com.openandroidintelligence.gateway.auth

import com.openandroidintelligence.gateway.http.GatewayResponse
import com.openandroidintelligence.gateway.http.GatewayTransport
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.SignedGatewayRequest
import com.openandroidintelligence.gateway.http.WireRequest
import com.openandroidintelligence.gateway.http.WireResponse
import com.openandroidintelligence.gateway.negotiation.NegotiationClient
import com.openandroidintelligence.gateway.negotiation.NegotiationResult
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the app needs to build an authenticated client after login. */
data class SessionCredentials(
    val accountId: String,
    val deviceId: String,
    val sessionId: String,
    val accessToken: String,
    val refreshCredential: ByteArray,
    val pairingSummary: String?,
)

/**
 * Password login, refresh rotation and session termination over the real
 * Gateway transport (HTTPS, or plaintext HTTP for a gateway the user reached
 * deliberately — see [com.openandroidintelligence.gateway.http.TransportSecurity]).
 *
 * These endpoints run before a signed session exists, so they ride the plain
 * [GatewayTransport]: negotiate and login carry no signature, and logout
 * presents the bearer token the login already returned. The password exists
 * only inside [loginWithPassword] and is scrubbed before returning, mirroring
 * [GatewaySessionManager]'s rules.
 */
class GatewayAuthClient(
    private val transport: GatewayTransport,
    private val installationId: String,
    private val appVersion: String,
    private val platformApi: Int,
) {
    private val negotiation = NegotiationClient(
        execute = { request ->
            val wire = transport.execute(request.toWire())
            GatewayResponse(status = wire.status, headers = wire.headers, body = wire.body)
        },
        installationId = installationId,
        appVersion = appVersion,
        platformApi = platformApi,
    )

    suspend fun negotiate(negotiationId: String): NegotiationResult =
        withContext(Dispatchers.IO) { negotiation.negotiate(negotiationId) }

    suspend fun loginWithPassword(
        negotiationId: String,
        username: String,
        password: CharArray,
        displayName: String,
        devicePublicKeyBase64Url: String,
    ): SessionCredentials = withContext(Dispatchers.IO) {
        require(username.isNotBlank()) { "AUTH_INVALID:username" }
        require(password.isNotEmpty()) { "AUTH_INVALID:password" }
        try {
            val payload = mapOf(
                "negotiationId" to negotiationId,
                "username" to username,
                "password" to String(password),
                "installation" to mapOf(
                    "installationId" to installationId,
                    "displayName" to displayName,
                    "devicePublicKey" to devicePublicKeyBase64Url,
                ),
            )
            val body = postJson("/open-android-intelligence/v2/sessions/password", payload)
            parseSessionCredentials(body, "LOGIN_FAILED")
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * Rotates the session from a refresh credential.
     *
     * Every field `session.schema.json` makes mandatory is sent: a refresh
     * that omits the account, device or negotiation it is bound to is not a
     * shorter request, it is a schema-invalid one the Gateway must reject.
     */
    suspend fun refresh(
        accountId: String,
        deviceId: String,
        negotiationId: String,
        refreshCredential: ByteArray,
    ): SessionCredentials = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "negotiationId" to negotiationId,
            "accountId" to accountId,
            "installationId" to installationId,
            "deviceId" to deviceId,
            "refreshCredential" to String(refreshCredential, Charsets.ISO_8859_1),
        )
        val body = postJson("/open-android-intelligence/v2/sessions/refresh", payload)
        parseSessionCredentials(body, "REFRESH_FAILED")
    }

    suspend fun logout(
        accessToken: String,
        accountId: String,
        deviceId: String,
        sessionId: String,
        revokeRefresh: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            val response = transport.execute(
                WireRequest(
                    method = "DELETE",
                    target = "/open-android-intelligence/v2/sessions/current?revokeRefresh=$revokeRefresh",
                    headers = listOf(
                        RawHeader("Authorization", "Bearer $accessToken"),
                        // The Gateway has no way to name the session from the
                        // bearer token alone; the phone states the identity it
                        // is terminating so the server can verify it.
                        RawHeader("X-Open-Android-Intelligence-Protocol", "2.0"),
                        RawHeader("X-Open-Android-Intelligence-Account", accountId),
                        RawHeader("X-Open-Android-Intelligence-Device", deviceId),
                        RawHeader("X-Open-Android-Intelligence-Session", sessionId),
                        RawHeader("Accept", "application/json"),
                    ),
                ),
            )
            if (response.status !in 200..299) {
                throw IllegalStateException("LOGOUT_FAILED:${response.status}")
            }
        }
    }

    private suspend fun postJson(target: String, payload: Map<String, Any?>): JsonValue.JObject {
        val response = transport.execute(
            WireRequest(
                method = "POST",
                target = target,
                headers = listOf(
                    RawHeader("Content-Type", "application/json"),
                    RawHeader("Accept", "application/json"),
                ),
                body = Json.canonical(Json.of(payload)).toByteArray(Charsets.UTF_8),
            ),
        )
        if (response.status !in 200..299) {
            throw authError("AUTHENTICATION_FAILED", response)
        }
        return JsonFields.obj(
            runCatching { Json.parse(String(response.body, Charsets.UTF_8)) }.getOrNull(),
        ) ?: throw IllegalStateException("AUTHENTICATION_FAILED:malformed")
    }

    private suspend fun authError(prefix: String, response: WireResponse): IllegalStateException {
        val body = runCatching { Json.parse(String(response.body, Charsets.UTF_8)) }.getOrNull()
        val code = JsonFields.string(JsonFields.obj(body), "errorCode")
        return IllegalStateException("$prefix:${code ?: response.status}")
    }

    private fun newNegotiationId(): String =
        "neg_" + java.util.UUID.randomUUID().toString().replace("-", "")

    private fun SignedGatewayRequest.toWire() = WireRequest(
        method = method,
        target = target,
        headers = headers,
        body = body,
    )
}

internal fun parseSessionCredentials(body: JsonValue.JObject, prefix: String): SessionCredentials {
    val result = JsonFields.obj(JsonFields.field(body, "data")) ?: body
    val accountId = JsonFields.string(result, "accountId")
        ?: throw IllegalStateException("$prefix:missing-account")
    val deviceId = JsonFields.string(result, "deviceId")
        ?: throw IllegalStateException("$prefix:missing-device")
    val sessionId = JsonFields.string(result, "sessionId")
        ?: throw IllegalStateException("$prefix:missing-session")
    val accessToken = JsonFields.string(result, "accessToken")
        ?: JsonFields.string(result, "token")
        ?: throw IllegalStateException("$prefix:missing-token")
    val refresh = (JsonFields.field(result, "refreshCredential") as? JsonValue.JString)
        ?.value?.toByteArray(Charsets.ISO_8859_1)
        ?: throw IllegalStateException("$prefix:missing-refresh")
    return SessionCredentials(
        accountId = accountId,
        deviceId = deviceId,
        sessionId = sessionId,
        accessToken = accessToken,
        refreshCredential = refresh,
        pairingSummary = JsonFields.string(result, "pairingSummary"),
    )
}
