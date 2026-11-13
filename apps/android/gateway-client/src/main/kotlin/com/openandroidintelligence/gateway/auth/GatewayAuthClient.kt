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

/** An authenticated, short-lived Core challenge; its signed facts are never reconstructed. */
class AccountLoginChallenge internal constructor(
    internal val facts: JsonValue.JObject,
    val challengeId: String,
    val accountId: String,
    val deviceId: String?,
    val expiresAt: java.time.Instant,
    val methods: Set<String>,
    val enrollmentRequired: Boolean,
)

/** Sensitive enrollment material is held only until the user completes or cancels the flow. */
class AccountMfaEnrollment(val enrollmentId: String, val provisioningUri: String, val secret: String?)

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

    /** Only call after the server explicitly agrees to X-OAI-Account-Auth: v1. */
    suspend fun beginAccountLogin(
        negotiationId: String,
        username: String,
        password: CharArray,
        displayName: String,
        devicePublicKeyBase64Url: String,
        accountId: String? = null,
        deviceId: String? = null,
    ): AccountLoginChallenge = withContext(Dispatchers.IO) {
        try {
            require(username.isNotBlank() && password.isNotEmpty()) { "AUTH_INVALID" }
            require((accountId == null) == (deviceId == null)) { "ACCOUNT_BINDING_INVALID" }
            val payload = mutableMapOf<String, Any?>(
                "negotiationId" to negotiationId, "username" to username, "password" to String(password),
                "installation" to mapOf("installationId" to installationId, "displayName" to displayName,
                    "devicePublicKey" to devicePublicKeyBase64Url),
            )
            if (accountId != null && deviceId != null) {
                payload["accountId"] = accountId
                payload["deviceId"] = deviceId
            }
            val response = postJson("/open-android-intelligence/v2/sessions/password/challenge", payload)
            val facts = JsonFields.obj(JsonFields.field(response, "data")) ?: error("ACCOUNT_CHALLENGE_INVALID")
            val required = setOf("challengeId", "accountId", "negotiationId", "installationId", "purpose",
                "expiresAt", "nonce", "methods", "enrollmentRequired")
            val keys = facts.fields.map { it.first }
            check(keys.size == keys.toSet().size && keys.containsAll(required) &&
                keys.all { it in required || it == "deviceId" }) { "ACCOUNT_CHALLENGE_INVALID" }
            fun identifier(name: String): String = JsonFields.string(facts, name)
                ?.takeIf { GatewayHttpClient.CURSOR_ALPHABET.matches(it) }
                ?: error("ACCOUNT_CHALLENGE_INVALID")
            val challengeId = identifier("challengeId")
            val issuedAccount = identifier("accountId")
            val issuedDevice = if ("deviceId" in keys) identifier("deviceId") else null
            check(JsonFields.string(facts, "negotiationId") == negotiationId &&
                JsonFields.string(facts, "installationId") == installationId &&
                JsonFields.string(facts, "purpose") == "android-login" &&
                (accountId == null || accountId == issuedAccount) &&
                issuedDevice == deviceId) { "ACCOUNT_CHALLENGE_SCOPE_MISMATCH" }
            check(!JsonFields.string(facts, "nonce").isNullOrBlank()) { "ACCOUNT_CHALLENGE_INVALID" }
            val expiry = runCatching { java.time.Instant.parse(JsonFields.string(facts, "expiresAt")) }
                .getOrElse { error("ACCOUNT_CHALLENGE_INVALID") }
            check(expiry.isAfter(java.time.Instant.now())) { "ACCOUNT_CHALLENGE_EXPIRED" }
            val methods = JsonFields.array(JsonFields.field(facts, "methods"))?.items?.map {
                (it as? JsonValue.JString)?.value ?: error("ACCOUNT_CHALLENGE_INVALID")
            } ?: error("ACCOUNT_CHALLENGE_INVALID")
            check(methods.distinct().size == methods.size &&
                (methods.isEmpty() || methods == listOf("totp", "recovery-code"))) { "ACCOUNT_CHALLENGE_INVALID" }
            val enrollment = JsonFields.bool(facts, "enrollmentRequired") ?: error("ACCOUNT_CHALLENGE_INVALID")
            check(!enrollment || methods.isNotEmpty()) { "ACCOUNT_CHALLENGE_INVALID" }
            AccountLoginChallenge(facts, challengeId, issuedAccount, issuedDevice, expiry, methods.toSet(), enrollment)
        } finally {
            password.fill('\u0000')
        }
    }

    suspend fun beginMfaEnrollment(challenge: AccountLoginChallenge): AccountMfaEnrollment = withContext(Dispatchers.IO) {
        challenge.requireCurrent()
        check(challenge.enrollmentRequired) { "MFA_ENROLLMENT_NOT_REQUIRED" }
        val response = postJson("/open-android-intelligence/v2/sessions/mfa/enroll", mapOf("challengeId" to challenge.challengeId))
        val data = JsonFields.obj(JsonFields.field(response, "data")) ?: error("MFA_ENROLLMENT_INVALID")
        val id = JsonFields.string(data, "enrollmentId")?.takeIf { GatewayHttpClient.CURSOR_ALPHABET.matches(it) }
            ?: error("MFA_ENROLLMENT_INVALID")
        val uri = JsonFields.string(data, "provisioningUri")?.takeIf {
            runCatching { java.net.URI(it).let { parsed -> parsed.scheme == "otpauth" && parsed.host == "totp" } }.getOrDefault(false)
        } ?: error("MFA_ENROLLMENT_INVALID")
        AccountMfaEnrollment(id, uri, JsonFields.string(data, "secret"))
    }

    suspend fun confirmMfaEnrollment(challenge: AccountLoginChallenge, enrollmentId: String, otp: CharArray): List<String> = withContext(Dispatchers.IO) {
        try {
            challenge.requireCurrent()
            require(challenge.enrollmentRequired && otp.isNotEmpty()) { "MFA_CONFIRM_INVALID" }
            val response = postJson("/open-android-intelligence/v2/sessions/mfa/confirm", mapOf(
                "challengeId" to challenge.challengeId, "enrollmentId" to enrollmentId, "otp" to String(otp)))
            val data = JsonFields.obj(JsonFields.field(response, "data")) ?: error("MFA_CONFIRM_INVALID")
            val codes = JsonFields.array(JsonFields.field(data, "recoveryCodes"))?.items?.map {
                (it as? JsonValue.JString)?.value?.takeIf(String::isNotBlank) ?: error("MFA_CONFIRM_INVALID")
            } ?: error("MFA_CONFIRM_INVALID")
            check(codes.isNotEmpty() && codes.distinct().size == codes.size) { "MFA_CONFIRM_INVALID" }
            codes
        } finally { otp.fill('\u0000') }
    }

    suspend fun completeAccountLogin(
        challenge: AccountLoginChallenge,
        sign: (ByteArray) -> ByteArray,
        otp: CharArray? = null,
        recoveryCode: CharArray? = null,
    ): SessionCredentials = withContext(Dispatchers.IO) {
        try {
            challenge.requireCurrent()
            require(otp == null || recoveryCode == null) { "MFA_PROOF_INVALID" }
            require((otp == null || otp.isNotEmpty()) && (recoveryCode == null || recoveryCode.isNotEmpty())) { "MFA_PROOF_INVALID" }
            require(challenge.methods.isNotEmpty() || (otp == null && recoveryCode == null)) { "MFA_PROOF_INVALID" }
            require(challenge.enrollmentRequired || challenge.methods.isEmpty() || otp != null || recoveryCode != null) { "MFA_PROOF_REQUIRED" }
            require(!challenge.enrollmentRequired || (otp == null && recoveryCode == null)) { "MFA_PROOF_INVALID" }
            val preimage = ("OPEN_ANDROID_INTELLIGENCE_ACCOUNT_LOGIN_V1\n" + Json.canonical(challenge.facts)).toByteArray(Charsets.UTF_8)
            val signature = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sign(preimage))
            val payload = mutableMapOf<String, Any?>("challengeId" to challenge.challengeId,
                "negotiationId" to JsonFields.string(challenge.facts, "negotiationId"), "signature" to signature)
            otp?.let { payload["otp"] = String(it) }
            recoveryCode?.let { payload["recoveryCode"] = String(it) }
            val session = parseSessionCredentials(postJson("/open-android-intelligence/v2/sessions/password/complete", payload), "LOGIN_FAILED")
            check(session.accountId == challenge.accountId && (challenge.deviceId == null || session.deviceId == challenge.deviceId)) {
                session.refreshCredential.fill(0)
                "ACCOUNT_SESSION_SCOPE_MISMATCH"
            }
            session
        } finally {
            otp?.fill('\u0000')
            recoveryCode?.fill('\u0000')
        }
    }

    private fun AccountLoginChallenge.requireCurrent() {
        check(expiresAt.isAfter(java.time.Instant.now())) { "ACCOUNT_CHALLENGE_EXPIRED" }
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
        requestId: String? = null,
    ): SessionCredentials = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "negotiationId" to negotiationId,
            "accountId" to accountId,
            "installationId" to installationId,
            "deviceId" to deviceId,
            "refreshCredential" to String(refreshCredential, Charsets.ISO_8859_1),
        )
        requestId?.let { require(GatewayHttpClient.CURSOR_ALPHABET.matches(it)) { "REQUEST_ID_INVALID" } }
        val body = postJson("/open-android-intelligence/v2/sessions/refresh", payload,
            requestId?.let { listOf(RawHeader("Idempotency-Key", it)) }.orEmpty())
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
                throw authError("LOGOUT_FAILED", response)
            }
        }
    }

    private suspend fun postJson(target: String, payload: Map<String, Any?>, extraHeaders: List<RawHeader> = emptyList()): JsonValue.JObject {
        val response = transport.execute(
            WireRequest(
                method = "POST",
                target = target,
                headers = listOf(
                    RawHeader("Content-Type", "application/json"),
                    RawHeader("Accept", "application/json"),
                ) + extraHeaders,
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
