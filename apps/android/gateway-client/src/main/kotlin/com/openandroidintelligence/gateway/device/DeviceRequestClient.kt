package com.openandroidintelligence.gateway.device

/**
 * What the server returned when the device claimed a request.
 *
 * Android must not choose or rewrite any of these fields; the Gateway verifies
 * every binding against its own receipt record.
 */
data class ClaimReceipt(
    val claimId: String,
    val requestId: String,
    val accountId: String,
    val deviceId: String,
    val pairingGeneration: Int,
    val grantRevision: Int,
)

sealed class DeviceRequestResult {
    data class Succeeded(val payload: Map<String, Any?>) : DeviceRequestResult()
    data class Failed(val payload: Map<String, Any?>) : DeviceRequestResult()
    data class Denied(val payload: Map<String, Any?>) : DeviceRequestResult()
    data class Cancelled(val payload: Map<String, Any?>) : DeviceRequestResult()
    object OutcomeUnknown : DeviceRequestResult()
}

/**
 * The two calls contract §10 allows a device to make.
 *
 * Both are `suspend` because a real transport owns a socket: a synchronous
 * signature would force the HTTP implementation to block a thread (or the main
 * dispatcher) for the whole round trip.
 */
interface DeviceRequestTransport {
    suspend fun claim(requestId: String, grantRevision: Int): ClaimReceipt

    suspend fun submitResult(requestId: String, body: Map<String, Any?>)
}

/**
 * Claim-then-result device request execution.
 *
 * The device must hold a server-issued receipt before it performs any side
 * effect, and the result may carry only what the receipt supplied. Anything
 * else would let the device write its own identity into the outcome.
 */
class DeviceRequestClient(private val transport: DeviceRequestTransport) {

    private val claimsByRequest = LinkedHashMap<String, ClaimReceipt>()
    private val issuedByClaimId = LinkedHashMap<String, ClaimReceipt>()

    suspend fun claim(requestId: String, grantRevision: Int): ClaimReceipt {
        val receipt = transport.claim(requestId, grantRevision)
        // Idempotent re-claim returns the same receipt; recording it again is
        // therefore a no-op rather than a conflict.
        claimsByRequest[requestId] = receipt
        issuedByClaimId[receipt.claimId] = receipt
        return receipt
    }

    suspend fun submitResult(claim: ClaimReceipt, result: DeviceRequestResult) {
        // A receipt is only usable if this client actually obtained it; a
        // hand-built one is not merely mismatched, it was never issued.
        val issued = issuedByClaimId[claim.claimId]
            ?: throw IllegalArgumentException("NOT_CLAIMED:${claim.claimId}")
        if (issued != claim) {
            throw IllegalArgumentException("RECEIPT_BINDING_MISMATCH:${claim.claimId}")
        }
        if (claimsByRequest[claim.requestId] != claim) {
            throw IllegalArgumentException("RECEIPT_BINDING_MISMATCH:${claim.requestId}")
        }

        transport.submitResult(
            claim.requestId,
            mapOf(
                "claimId" to claim.claimId,
                "grantRevision" to claim.grantRevision,
                "result" to resultBody(result),
            ),
        )
    }

    /**
     * Maps a stream event onto the local state, without ever inventing an
     * outcome. `device.request.cancel.requested` is an intent: only an unclaimed
     * pending request may go straight to `cancelled`, a claimed one waits for a
     * trusted result.
     */
    fun stateAfterEvent(eventName: String, wasClaimed: Boolean): String? = when (eventName) {
        "device.request.cancel.requested" -> if (wasClaimed) "cancel_requested" else "cancelled"
        else -> null
    }

    /**
     * Contract §10 and driver decision D3: `result` is the object
     * `{ outcome, data? }`, not a name paired with a sibling `payload`.
     *
     * `outcome_unknown` is the one outcome that may not carry `data`: it says the
     * real terminal state could not be established, so attaching a payload would
     * dress a guess up as a verified result.
     */
    private fun resultBody(result: DeviceRequestResult): Map<String, Any?> {
        val body = mutableMapOf<String, Any?>("outcome" to outcomeName(result))
        dataOf(result)?.let { data -> body["data"] = data }
        return body
    }

    /**
     * The wire names are the state names, without the `result_` prefix the event
     * vocabulary uses: a result submission states the outcome, while
     * `device.request.result.*` is the event the Gateway derives from it.
     */
    private fun outcomeName(result: DeviceRequestResult): String = when (result) {
        is DeviceRequestResult.Succeeded -> "succeeded"
        is DeviceRequestResult.Failed -> "failed"
        is DeviceRequestResult.Denied -> "denied"
        is DeviceRequestResult.Cancelled -> "cancelled"
        DeviceRequestResult.OutcomeUnknown -> "outcome_unknown"
    }

    private fun dataOf(result: DeviceRequestResult): Map<String, Any?>? = when (result) {
        is DeviceRequestResult.Succeeded -> result.payload
        is DeviceRequestResult.Failed -> result.payload
        is DeviceRequestResult.Denied -> result.payload
        is DeviceRequestResult.Cancelled -> result.payload
        DeviceRequestResult.OutcomeUnknown -> null
    }
}
