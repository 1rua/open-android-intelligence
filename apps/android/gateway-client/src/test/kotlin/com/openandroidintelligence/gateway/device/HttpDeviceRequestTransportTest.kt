package com.openandroidintelligence.gateway.device

import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.GatewayByteTransport
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.WireRequest
import com.openandroidintelligence.gateway.http.WireResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract §10: claim first, result second, both over the signed Gateway client.
 *
 * The shape asserted here is the one the Gateway verifies against its own receipt
 * record — the two endpoints and the three result fields are not a convention the
 * phone may vary.
 */
class HttpDeviceRequestTransportTest {

    private class RecordingTransport : GatewayByteTransport {
        val requests = mutableListOf<WireRequest>()
        var responder: () -> WireResponse = { error("no response staged") }

        override suspend fun execute(request: WireRequest): WireResponse {
            requests += request
            return responder()
        }

        override fun eventStream(request: WireRequest): Flow<ByteArray> = emptyFlow()
    }

    private fun json(status: Int, body: String): WireResponse = WireResponse(
        status = status,
        headers = listOf(RawHeader("content-type", "application/json")),
        body = body.toByteArray(Charsets.UTF_8),
    )

    private fun transport(recording: RecordingTransport): HttpDeviceRequestTransport =
        HttpDeviceRequestTransport(
            GatewayHttpClient(
                profile = GatewayProfile("acct_1", "dev_1", "sess_1", "https://gateway.example.com"),
                transport = recording,
                signer = { ByteArray(64) },
                cursorStore = InMemoryEventCursorStore(),
            ),
        )

    private fun headersOf(request: WireRequest): Map<String, String> =
        request.headers.associate { it.name to it.value }

    @Test
    fun claimIsPostedToTheClaimRouteAndTheReceiptIsReadFromData() = runBlocking {
        val recording = RecordingTransport().apply {
            responder = {
                json(
                    200,
                    """{"protocol":"2.1","data":{"claimId":"claim_1","requestId":"dev_req_1","accountId":"acct_1","deviceId":"dev_1","pairingGeneration":3,"grantRevision":7}}""",
                )
            }
        }

        val receipt = transport(recording).claim("dev_req_1", 7)

        assertEquals(ClaimReceipt("claim_1", "dev_req_1", "acct_1", "dev_1", 3, 7), receipt)
        val request = recording.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/open-android-intelligence/v2/device-requests/dev_req_1/claim", request.target)
        assertEquals("""{"grantRevision":7}""", String(request.body, Charsets.UTF_8))
    }

    @Test
    fun theClaimRidesTheSignedClientWithAnIdempotencyKey() = runBlocking {
        val recording = RecordingTransport().apply {
            responder = {
                json(
                    200,
                    """{"protocol":"2.1","data":{"claimId":"claim_1","requestId":"dev_req_1","accountId":"acct_1","deviceId":"dev_1","pairingGeneration":3,"grantRevision":7}}""",
                )
            }
        }

        transport(recording).claim("dev_req_1", 7)

        val headers = headersOf(recording.requests.single())
        assertTrue("claim 必须走签名客户端", headers.containsKey("Authorization"))
        assertTrue(headers.containsKey("X-Open-Android-Intelligence-Signature"))
        assertEquals(
            "同一 claim 幂等重试必须绑定同一个 request id",
            headers["X-Open-Android-Intelligence-Request-Id"],
            headers["Idempotency-Key"],
        )
    }

    @Test
    fun aReceiptThatNamesAnotherRequestFailsClosed() = runBlocking {
        val recording = RecordingTransport().apply {
            responder = {
                json(
                    200,
                    """{"protocol":"2.1","data":{"claimId":"claim_1","requestId":"dev_req_other","accountId":"acct_1","deviceId":"dev_1","pairingGeneration":3,"grantRevision":7}}""",
                )
            }
        }

        val failure = runCatching { transport(recording).claim("dev_req_1", 7) }.exceptionOrNull()

        assertTrue(
            "receipt 与 route 不一致时不能把结果发到另一个 request 上",
            failure?.message == "DEVICE_CLAIM_FAILED:receipt-mismatch",
        )
    }

    @Test
    fun aReceiptMissingABindingFieldIsRefused() = runBlocking {
        val recording = RecordingTransport().apply {
            responder = {
                json(
                    200,
                    """{"protocol":"2.1","data":{"claimId":"claim_1","requestId":"dev_req_1","accountId":"acct_1","pairingGeneration":3,"grantRevision":7}}""",
                )
            }
        }

        val failure = runCatching { transport(recording).claim("dev_req_1", 7) }.exceptionOrNull()

        assertEquals("DEVICE_CLAIM_FAILED:malformed", failure?.message)
    }

    @Test
    fun aRefusedClaimSurfacesTheGatewayErrorCode() = runBlocking {
        val recording = RecordingTransport().apply {
            responder = {
                json(
                    409,
                    """{"protocol":"2.1","error":{"code":"GRANT_STALE","message":"stale","retryable":false,"retryAfterSeconds":null,"details":{}}}""",
                )
            }
        }

        val failure = runCatching { transport(recording).claim("dev_req_1", 7) }.exceptionOrNull()

        assertEquals("DEVICE_CLAIM_FAILED:GRANT_STALE", failure?.message)
    }

    @Test
    fun theResultIsPostedAsTheThreeFieldBody() = runBlocking {
        val recording = RecordingTransport().apply {
            responder = { json(200, """{"protocol":"2.1","data":{"requestId":"dev_req_1"}}""") }
        }

        transport(recording).submitResult(
            "dev_req_1",
            mapOf(
                "claimId" to "claim_1",
                "grantRevision" to 7,
                "result" to mapOf("outcome" to "succeeded", "data" to mapOf("count" to 1)),
            ),
        )

        val request = recording.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/open-android-intelligence/v2/device-requests/dev_req_1/result", request.target)
        // RFC 8785 sorts keys, so data precedes outcome inside result.
        assertEquals(
            """{"claimId":"claim_1","grantRevision":7,"result":{"data":{"count":1},"outcome":"succeeded"}}""",
            String(request.body, Charsets.UTF_8),
        )
        val headers = headersOf(request)
        assertTrue(headers.containsKey("Authorization"))
        assertTrue(headers.containsKey("Idempotency-Key"))
    }

    @Test
    fun aRefusedResultSubmissionSurfacesTheGatewayErrorCode() = runBlocking {
        val recording = RecordingTransport().apply {
            responder = {
                json(
                    409,
                    """{"protocol":"2.1","error":{"code":"OUTCOME_UNKNOWN","message":"lost","retryable":true,"retryAfterSeconds":null,"details":{}}}""",
                )
            }
        }

        val failure = runCatching {
            transport(recording).submitResult(
                "dev_req_1",
                mapOf("claimId" to "claim_1", "grantRevision" to 7, "result" to mapOf("outcome" to "outcome_unknown")),
            )
        }.exceptionOrNull()

        assertEquals("DEVICE_RESULT_FAILED:OUTCOME_UNKNOWN", failure?.message)
    }
}
