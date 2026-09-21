package com.openandroidintelligence.gateway.approvals

import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayResponse
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.SignedGatewayRequest
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue

/**
 * The one decision a command-execution approval accepts (contract §7.2).
 *
 * The vocabulary is the host's own: `once | session | always | deny`. A client
 * never invents a fifth tier, and the two terminal outcomes the Gateway owns —
 * `timeout` and `withdrawn` — cannot be submitted, only received.
 */
enum class ApprovalDecision(val wireValue: String) {
    ONCE("once"),
    SESSION("session"),
    ALWAYS("always"),
    DENY("deny"),
    ;

    companion object {
        fun of(value: String?): ApprovalDecision? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * How a decision ended, as a closed set.
 *
 * `SUBMITTED` is the only outcome that means the Gateway took the decision;
 * everything else is a fact the UI has to show rather than a failure it may
 * retry blindly. `ALREADY_RESOLVED` in particular is not a local error: another
 * device may have answered the same card.
 */
enum class ApprovalDecisionOutcome {
    SUBMITTED,
    ALREADY_RESOLVED,
    EXPIRED,
    NOT_FOUND,
    UNSUPPORTED,
    FAILED,
}

data class ApprovalDecisionResult(
    val outcome: ApprovalDecisionOutcome,
    /** The tier the Gateway recorded, when it recorded one of the four tiers. */
    val decision: ApprovalDecision?,
    val httpStatus: Int,
    val errorCode: String? = null,
    /**
     * The decision exactly as the Gateway named it, including the two terminal
     * outcomes a client can never submit (`timeout`, `withdrawn`).
     *
     * Kept verbatim because a settled approval may have ended in a tier the
     * phone did not press: translating it into "unknown" would hide a fact the
     * Gateway stated plainly.
     */
    val rawDecision: String? = null,
) {
    val isSettled: Boolean get() = outcome == ApprovalDecisionOutcome.SUBMITTED ||
        outcome == ApprovalDecisionOutcome.ALREADY_RESOLVED ||
        outcome == ApprovalDecisionOutcome.EXPIRED
}

/**
 * `POST /approvals/{approvalId}/decisions` — one button press, one request.
 *
 * The approval id is the whole authority a client holds: the host's session key
 * and request id never appear on the wire, so no phone can name a session of its
 * choosing. The transport signs the request and binds an `Idempotency-Key` to
 * its request id, so one press is one write; a caller that fires two presses for
 * the same card without waiting is still protected by the Gateway's closed
 * decision, never by a local guess about which one arrived first.
 */
class ApprovalClient(private val http: GatewayHttpClient) {

    suspend fun submitDecision(
        approvalId: String,
        decision: ApprovalDecision,
    ): ApprovalDecisionResult {
        val payload = mapOf("decision" to decision.wireValue)
        val response = execute(
            approvalId = approvalId,
            body = Json.canonical(Json.of(payload)).toByteArray(Charsets.UTF_8),
        )
        return when {
            response.status in 200..299 -> {
                val approval = JsonFields.obj(
                    JsonFields.field(dataOf(response), "approval"),
                )
                val recorded = ApprovalDecision.of(JsonFields.string(approval, "decision"))
                // A 2xx without the decision it recorded is not a success the UI
                // may paint: the card has to know what actually happened.
                if (recorded == null) {
                    ApprovalDecisionResult(
                        outcome = ApprovalDecisionOutcome.FAILED,
                        decision = null,
                        httpStatus = response.status,
                    )
                } else {
                    ApprovalDecisionResult(
                        outcome = ApprovalDecisionOutcome.SUBMITTED,
                        decision = recorded,
                        httpStatus = response.status,
                    )
                }
            }
            response.status == 404 -> ApprovalDecisionResult(
                outcome = ApprovalDecisionOutcome.NOT_FOUND,
                decision = null,
                httpStatus = 404,
                errorCode = errorCodeOf(response),
            )
            response.status == 409 -> {
                val code = errorCodeOf(response)
                // The Gateway names the decision it already recorded, so the card
                // can show what won instead of leaving the user guessing.
                val recorded = recordedDecisionOf(response)
                when (code) {
                    "APPROVAL_ALREADY_RESOLVED" -> ApprovalDecisionResult(
                        outcome = ApprovalDecisionOutcome.ALREADY_RESOLVED,
                        decision = ApprovalDecision.of(recorded),
                        httpStatus = 409,
                        errorCode = "APPROVAL_ALREADY_RESOLVED",
                        rawDecision = recorded,
                    )
                    "APPROVAL_EXPIRED" -> ApprovalDecisionResult(
                        outcome = ApprovalDecisionOutcome.EXPIRED,
                        decision = ApprovalDecision.of(recorded),
                        httpStatus = 409,
                        errorCode = "APPROVAL_EXPIRED",
                        rawDecision = recorded,
                    )
                    else -> ApprovalDecisionResult(
                        outcome = ApprovalDecisionOutcome.FAILED,
                        decision = null,
                        httpStatus = 409,
                        errorCode = code,
                    )
                }
            }
            response.status == 400 || response.status == 406 -> ApprovalDecisionResult(
                outcome = ApprovalDecisionOutcome.UNSUPPORTED,
                decision = null,
                httpStatus = response.status,
                errorCode = errorCodeOf(response),
            )
            else -> ApprovalDecisionResult(
                outcome = ApprovalDecisionOutcome.FAILED,
                decision = null,
                httpStatus = response.status,
                errorCode = errorCodeOf(response),
            )
        }
    }

    private suspend fun execute(approvalId: String, body: ByteArray): GatewayResponse = http.execute(
        SignedGatewayRequest(
            method = "POST",
            target = "/open-android-intelligence/v2/approvals/$approvalId/decisions",
            body = body,
            headers = listOf(
                RawHeader("Content-Type", "application/json"),
                RawHeader("Accept", "application/json"),
            ),
        ),
    )

    private fun bodyOf(response: GatewayResponse): JsonValue.JObject? =
        runCatching { Json.parse(response.body.toString(Charsets.UTF_8)) }
            .getOrNull()
            ?.let { JsonFields.obj(it) }

    private fun dataOf(response: GatewayResponse): JsonValue.JObject? =
        JsonFields.obj(JsonFields.field(bodyOf(response), "data"))

    private fun errorCodeOf(response: GatewayResponse): String? =
        JsonFields.string(
            JsonFields.obj(JsonFields.field(bodyOf(response), "error")),
            "code",
        )

    /**
     * The decision the Gateway says it already recorded.
     *
     * It lives in `error.details.decision`; the top-level `error.decision` is
     * accepted too because the contract only fixes the field name, not the depth
     * a host chooses for its own error details.
     */
    private fun recordedDecisionOf(response: GatewayResponse): String? {
        val error = JsonFields.obj(JsonFields.field(bodyOf(response), "error"))
        val details = JsonFields.obj(JsonFields.field(error, "details"))
        return JsonFields.string(details, "decision") ?: JsonFields.string(error, "decision")
    }
}
