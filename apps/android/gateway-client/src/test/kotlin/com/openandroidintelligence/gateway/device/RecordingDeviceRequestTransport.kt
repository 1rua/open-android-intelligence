package com.openandroidintelligence.gateway.device

/** Records claim/result traffic so the client can be proven without a Gateway. */
class RecordingDeviceRequestTransport : DeviceRequestTransport {

    val calls = mutableListOf<String>()

    var lastResultBody: Map<String, Any?>? = null
        private set

    private val claimIds = LinkedHashMap<String, String>()

    override suspend fun claim(requestId: String, grantRevision: Int): ClaimReceipt {
        calls += "claim"
        // Idempotent: the same requestId always yields the same claimId.
        val claimId = claimIds.getOrPut(requestId) { "claim-${claimIds.size + 1}" }
        return ClaimReceipt(
            claimId = claimId,
            requestId = requestId,
            accountId = "acct-1",
            deviceId = "dev-1",
            pairingGeneration = 3,
            grantRevision = grantRevision,
        )
    }

    override suspend fun submitResult(requestId: String, body: Map<String, Any?>) {
        calls += "submitResult"
        lastResultBody = body
    }
}
