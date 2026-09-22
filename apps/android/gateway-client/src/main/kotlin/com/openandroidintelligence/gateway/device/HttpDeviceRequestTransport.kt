package com.openandroidintelligence.gateway.device

import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.SignedGatewayRequest
import com.openandroidintelligence.gateway.http.requireData
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue

/**
 * Contract §10's device-request endpoints over the signed Gateway client.
 *
 * Both calls ride [GatewayHttpClient], so they carry the session's signature and
 * the `Idempotency-Key` bound to the request id: `claim` is idempotent by route
 * and key (a retry returns the same receipt), and `result` is idempotent so a
 * reply lost on the way back cannot become a second terminal state.
 *
 * The phone never authors identity here. The receipt is read field by field from
 * `data` and a receipt naming a different request than the route is refused
 * instead of being cached, because the client would otherwise submit the result
 * to whichever request the receipt names.
 */
class HttpDeviceRequestTransport(
    private val http: GatewayHttpClient,
) : DeviceRequestTransport {

    override suspend fun claim(requestId: String, grantRevision: Int): ClaimReceipt {
        val response = http.execute(
            SignedGatewayRequest(
                method = "POST",
                target = "/open-android-intelligence/v2/device-requests/$requestId/claim",
                headers = JSON_HEADERS,
                // The contract fixes the receipt, not the claim request body; the
                // grant revision the phone is asking under is the one fact the
                // Gateway needs from it, and the route already names the request.
                body = Json.canonical(Json.of(mapOf("grantRevision" to grantRevision)))
                    .toByteArray(Charsets.UTF_8),
            ),
        )
        val data = response.requireData("DEVICE_CLAIM_FAILED")
        val receipt = ClaimReceipt(
            claimId = required(data, "claimId"),
            requestId = required(data, "requestId"),
            accountId = required(data, "accountId"),
            deviceId = required(data, "deviceId"),
            pairingGeneration = requiredInt(data, "pairingGeneration"),
            grantRevision = requiredInt(data, "grantRevision"),
        )
        if (receipt.requestId != requestId) {
            throw IllegalStateException("DEVICE_CLAIM_FAILED:receipt-mismatch")
        }
        return receipt
    }

    override suspend fun submitResult(requestId: String, body: Map<String, Any?>) {
        val response = http.execute(
            SignedGatewayRequest(
                method = "POST",
                target = "/open-android-intelligence/v2/device-requests/$requestId/result",
                headers = JSON_HEADERS,
                body = Json.canonical(Json.of(body)).toByteArray(Charsets.UTF_8),
            ),
        )
        response.requireData("DEVICE_RESULT_FAILED")
    }

    private fun required(data: JsonValue.JObject, name: String): String =
        JsonFields.string(data, name)?.takeIf { WIRE_ID.matches(it) }
            ?: throw IllegalStateException("DEVICE_CLAIM_FAILED:malformed")

    private fun requiredInt(data: JsonValue.JObject, name: String): Int =
        JsonFields.int(data, name)
            ?: throw IllegalStateException("DEVICE_CLAIM_FAILED:malformed")

    private companion object {
        /** Contract §2's wire ID alphabet, applied to every receipt field. */
        val WIRE_ID = Regex("[A-Za-z0-9._~-]{1,128}")
        val JSON_HEADERS = listOf(
            RawHeader("Content-Type", "application/json"),
            RawHeader("Accept", "application/json"),
        )
    }
}
