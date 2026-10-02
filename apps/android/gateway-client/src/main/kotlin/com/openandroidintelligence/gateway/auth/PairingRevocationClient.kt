package com.openandroidintelligence.gateway.auth

import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.SignedGatewayRequest
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields

/** Local secrets may be removed only after the complete device revocation receipt. */
class PairingRevocationClient(private val http: GatewayHttpClient) {
    suspend fun revoke(deviceId: String) {
        require(Regex("[A-Za-z0-9._~-]{1,128}").matches(deviceId)) { "UNPAIR_FAILED:invalid-device" }
        val response = http.execute(SignedGatewayRequest("DELETE", "/open-android-intelligence/v2/pairings/current"))
        val envelope = JsonFields.obj(Json.parse(response.body.decodeToString()))
            ?: error("UNPAIR_FAILED:invalid-receipt")
        if (response.status !in 200..299) {
            val failure = JsonFields.obj(JsonFields.field(envelope, "error"))
            error("UNPAIR_FAILED:${JsonFields.string(failure, "code") ?: "remote-refusal"}")
        }
        check(JsonFields.string(envelope, "protocol") == "2.1") { "UNPAIR_FAILED:invalid-protocol" }
        val data = JsonFields.obj(JsonFields.field(envelope, "data"))
            ?: error("UNPAIR_FAILED:missing-receipt")
        val flags = setOf("deviceKeysRevoked", "refreshRevoked", "grantsRevoked", "deviceRequestsRevoked", "unconfirmedAttachmentsRevoked", "sessionsRevoked")
        check(data.fields.map { it.first }.let { it.size == flags.size + 1 && it.toSet() == flags + "deviceId" }) {
            "UNPAIR_FAILED:invalid-receipt"
        }
        check(JsonFields.string(data, "deviceId") == deviceId && flags.all { JsonFields.bool(data, it) == true }) {
            "UNPAIR_FAILED:incomplete-revocation"
        }
    }
}
