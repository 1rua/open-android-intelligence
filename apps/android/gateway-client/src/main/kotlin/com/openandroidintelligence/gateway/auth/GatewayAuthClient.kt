package com.openandroidintelligence.gateway.auth

import com.openandroidintelligence.gateway.http.GatewayByteTransport
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayResponse
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
    val pairingGeneration: Int = 1,
    val grantRevision: Int = 1,
)

/**
 * Password login, refresh rotation and session termination over the real
 * Gateway transport (HTTPS, or plaintext HTTP for a gateway the user reached
 * deliberately — see [com.openandroidintelligence.gateway.http.TransportSecurity]).
 *
 * These endpoints run before a signed session exists, so they ride the plain
 * [GatewayByteTransport]: negotiate and login carry no signature, and logout
 * presents the bearer token the login already returned. The password exists
 * only inside [loginWithPassword] and is scrubbed before returning, mirroring
 * [GatewaySessionManager]'s rules.
 *
 * The transport is the byte-level boundary rather than the concrete socket
 * client, so the error contract below can be pinned against a stubbed Gateway
 * instead of only against a live one.
 */
class GatewayAuthClient(
    private val transport: GatewayByteTransport,
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

    suspend fun loginWithInvite(negotiationId:String,accountId:String,code:String,displayName:String,
        devicePublicKeyBase64Url:String,sign:(ByteArray)->ByteArray):SessionCredentials = withContext(Dispatchers.IO) {
        require(code.matches(Regex("[A-F0-9]{16}"))) { "INVITE_INVALID" }
        val challenge=postJson("/open-android-intelligence/v2/sessions/invite/challenge",mapOf("accountId" to accountId,"negotiationId" to negotiationId,"code" to code,
            "installation" to mapOf("installationId" to installationId,"displayName" to displayName,"devicePublicKey" to devicePublicKeyBase64Url)))
        val facts=JsonFields.obj(JsonFields.field(challenge,"data")) ?: error("INVITE_CHALLENGE_INVALID")
        check(facts.fields.map { it.first }.toSet() == setOf("challengeId","accountId","negotiationId","installationId","devicePublicKey","nonce","expiresAt"))
        check(JsonFields.string(facts,"accountId")==accountId && JsonFields.string(facts,"negotiationId")==negotiationId &&
            JsonFields.string(facts,"installationId")==installationId && JsonFields.string(facts,"devicePublicKey")==devicePublicKeyBase64Url) { "INVITE_SCOPE_MISMATCH" }
        val expiry=java.time.Instant.parse(JsonFields.string(facts,"expiresAt") ?: error("INVITE_CHALLENGE_INVALID"))
        check(expiry.isAfter(java.time.Instant.now()) && expiry.isBefore(java.time.Instant.now().plusSeconds(120))) { "INVITE_CHALLENGE_EXPIRED" }
        val signature=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sign(("OPEN_ANDROID_INTELLIGENCE_PAIRING_V1\n"+Json.canonical(facts)).toByteArray()))
        parseSessionCredentials(postJson("/open-android-intelligence/v2/pairings/exchange",mapOf("accountId" to accountId,"negotiationId" to negotiationId,
            "challengeId" to (JsonFields.string(facts,"challengeId") ?: error("INVITE_CHALLENGE_INVALID")),"signature" to signature)),"INVITE_FAILED")
    }

    suspend fun loginWithDeviceKey(negotiationId:String,accountId:String,deviceId:String,sign:(ByteArray)->ByteArray):SessionCredentials = withContext(Dispatchers.IO) {
        val response=postJson("/open-android-intelligence/v2/sessions/device/challenge",mapOf("accountId" to accountId,"negotiationId" to negotiationId,"installationId" to installationId,"deviceId" to deviceId))
        val facts=JsonFields.obj(JsonFields.field(response,"data")) ?: error("DEVICE_CHALLENGE_INVALID")
        check(facts.fields.map { it.first }.toSet()==setOf("challenge","accountId","negotiationId","installationId","deviceId","nonce","expiresAt")) { "DEVICE_CHALLENGE_INVALID" }
        check(JsonFields.string(facts,"accountId")==accountId && JsonFields.string(facts,"negotiationId")==negotiationId && JsonFields.string(facts,"installationId")==installationId && JsonFields.string(facts,"deviceId")==deviceId) { "DEVICE_CHALLENGE_SCOPE_MISMATCH" }
        val expiry=java.time.Instant.parse(JsonFields.string(facts,"expiresAt") ?: error("DEVICE_CHALLENGE_INVALID"))
        check(expiry.isAfter(java.time.Instant.now()) && expiry.isBefore(java.time.Instant.now().plusSeconds(120))) { "DEVICE_CHALLENGE_EXPIRED" }
        val signature=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sign(("OPEN_ANDROID_INTELLIGENCE_DEVICE_SESSION_V1\n"+Json.canonical(facts)).toByteArray()))
        parseSessionCredentials(postJson("/open-android-intelligence/v2/sessions/device",mapOf("accountId" to accountId,"negotiationId" to negotiationId,"installationId" to installationId,"deviceId" to deviceId,"challenge" to JsonFields.string(facts,"challenge"),"signature" to signature)),"DEVICE_LOGIN_FAILED")
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
                        RawHeader("X-Open-Android-Intelligence-Protocol", GatewayHttpClient.PROTOCOL_HEADER),
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

    /**
     * Contract §2: the failure reason is `error.code` on the envelope, not a
     * top-level `errorCode`.
     *
     * Reading the wrong field is not a cosmetic bug: it degrades every refusal
     * to `AUTHENTICATION_FAILED:401`, so the user can no longer tell a wrong
     * password from a revoked session, and the host loses the structured code it
     * needs to decide whether local key material still has a future.
     */
    private fun authError(prefix: String, response: WireResponse): GatewayAuthException {
        val envelope = runCatching { Json.parse(String(response.body, Charsets.UTF_8)) }.getOrNull()
        val error = JsonFields.obj(JsonFields.field(JsonFields.obj(envelope), "error"))
        // A code is only usable when it looks like one: the contract's codes are
        // upper-case tokens, so anything else is a malformed response rather
        // than a value worth branching on.
        val code = JsonFields.string(error, "code")?.takeIf { CODE_PATTERN.matches(it) }
        return GatewayAuthException(
            operation = prefix,
            code = code,
            httpStatus = response.status,
            retryable = JsonFields.bool(error, "retryable"),
        )
    }

    private fun newNegotiationId(): String =
        "neg_" + java.util.UUID.randomUUID().toString().replace("-", "")

    private fun SignedGatewayRequest.toWire() = WireRequest(
        method = method,
        target = target,
        headers = headers,
        body = body,
    )

    private companion object {
        /** Contract §14 codes are upper-case tokens; anything else is malformed. */
        val CODE_PATTERN = Regex("[A-Z0-9_]+")
    }
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
        pairingGeneration = JsonFields.int(result, "pairingGeneration") ?: 1,
        grantRevision = JsonFields.int(result, "grantRevision") ?: 1,
    )
}
