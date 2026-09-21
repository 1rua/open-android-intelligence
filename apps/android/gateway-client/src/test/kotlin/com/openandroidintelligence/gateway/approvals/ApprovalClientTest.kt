package com.openandroidintelligence.gateway.approvals

import com.openandroidintelligence.gateway.events.EventCursorStore
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One press is one signed `POST` to the approval's own endpoint.
 *
 * The decision never travels as a chat message, and the approval id is the only
 * authority on the wire: these tests pin both, plus the closed outcome set the
 * UI is allowed to show.
 */
class ApprovalClientTest {

    private class RecordingTransport : GatewayByteTransport {
        var lastRequest: WireRequest? = null
        var responseToReturn = WireResponse(
            status = 200,
            headers = listOf(RawHeader("content-type", "application/json")),
            body = """{"protocol":"2.0","data":{"approval":{"approvalId":"apr_1","conversationId":"conv_1","decision":"once"}}}""".toByteArray(Charsets.UTF_8),
        )

        override suspend fun execute(request: WireRequest): WireResponse {
            lastRequest = request
            return responseToReturn
        }

        override fun eventStream(request: WireRequest): Flow<ByteArray> = emptyFlow()
    }

    private class MemoryCursorStore : EventCursorStore {
        private val map = mutableMapOf<String, String>()
        override fun load(accountId: String): String? = map[accountId]
        override fun save(accountId: String, cursor: String) { map[accountId] = cursor }
        override fun clear(accountId: String) { map.remove(accountId) }
    }

    private fun client(transport: RecordingTransport): ApprovalClient {
        val profile = GatewayProfile("acc_test", "dev_test", "sess_test", "https://gateway.example.com")
        return ApprovalClient(
            GatewayHttpClient(profile, transport, { ByteArray(64) }, MemoryCursorStore()),
        )
    }

    private fun json(status: Int, body: String): WireResponse = WireResponse(
        status = status,
        headers = listOf(RawHeader("content-type", "application/json")),
        body = body.toByteArray(Charsets.UTF_8),
    )

    @Test
    fun postsTheDecisionToTheApprovalEndpointAndNothingElse() = runBlocking {
        val transport = RecordingTransport()
        val result = client(transport).submitDecision("apr_1", ApprovalDecision.ONCE)

        assertEquals(ApprovalDecisionOutcome.SUBMITTED, result.outcome)
        assertEquals(ApprovalDecision.ONCE, result.decision)
        val recorded = transport.lastRequest
        assertNotNull(recorded)
        assertEquals("POST", recorded?.method)
        assertEquals("/open-android-intelligence/v2/approvals/apr_1/decisions", recorded?.target)
        assertEquals("""{"decision":"once"}""", String(recorded!!.body, Charsets.UTF_8))
    }

    @Test
    fun aMutatingDecisionCarriesAnIdempotencyKeyBoundToItsRequestId() = runBlocking {
        val transport = RecordingTransport()
        client(transport).submitDecision("apr_1", ApprovalDecision.DENY)

        val headers = transport.lastRequest?.headers ?: emptyList()
        val idempotency = headers.firstOrNull { it.name.equals("Idempotency-Key", ignoreCase = true) }
        val requestId = headers.firstOrNull { it.name.equals("X-Open-Android-Intelligence-Request-Id", ignoreCase = true) }
        assertNotNull("决策必须可幂等重放", idempotency)
        assertNotNull(requestId)
        assertEquals(requestId?.value, idempotency?.value)
    }

    @Test
    fun aSuccessWithoutTheRecordedDecisionIsNotPaintedAsSuccess() = runBlocking {
        val transport = RecordingTransport().apply {
            responseToReturn = json(200, """{"protocol":"2.0","data":{"approval":{"approvalId":"apr_1"}}}""")
        }

        val result = client(transport).submitDecision("apr_1", ApprovalDecision.ALWAYS)

        assertEquals(ApprovalDecisionOutcome.FAILED, result.outcome)
        assertNull(result.decision)
    }

    @Test
    fun anAlreadyResolvedApprovalReportsTheDecisionThatWon() = runBlocking {
        val transport = RecordingTransport().apply {
            responseToReturn = json(
                409,
                """{"protocol":"2.0","error":{"code":"APPROVAL_ALREADY_RESOLVED","message":"already","retryable":false,"retryAfterSeconds":null,"details":{"approvalId":"apr_1","decision":"deny"}}}""",
            )
        }

        val result = client(transport).submitDecision("apr_1", ApprovalDecision.ONCE)

        assertEquals(ApprovalDecisionOutcome.ALREADY_RESOLVED, result.outcome)
        assertEquals("APPROVAL_ALREADY_RESOLVED", result.errorCode)
        assertEquals(ApprovalDecision.DENY, result.decision)
        assertTrue("已落定的卡片不得再回到可点击", result.isSettled)
    }

    @Test
    fun anExpiredApprovalIsReportedAsAFactNotATransportFailure() = runBlocking {
        val transport = RecordingTransport().apply {
            responseToReturn = json(
                409,
                """{"protocol":"2.0","error":{"code":"APPROVAL_EXPIRED","message":"expired","retryable":false,"retryAfterSeconds":null,"details":{"approvalId":"apr_1","decision":"timeout"}}}""",
            )
        }

        val result = client(transport).submitDecision("apr_1", ApprovalDecision.ONCE)

        assertEquals(ApprovalDecisionOutcome.EXPIRED, result.outcome)
        assertTrue(result.isSettled)
        // `timeout` is not a tier a client may press, so it stays out of
        // `decision` — but it is still what the Gateway recorded, verbatim.
        assertNull(result.decision)
        assertEquals("timeout", result.rawDecision)
    }

    @Test
    fun aWithdrawnApprovalKeepsTheVerdictTheGatewayNamed() = runBlocking {
        val transport = RecordingTransport().apply {
            responseToReturn = json(
                409,
                """{"protocol":"2.0","error":{"code":"APPROVAL_EXPIRED","message":"expired","retryable":false,"retryAfterSeconds":null,"details":{"approvalId":"apr_1","decision":"withdrawn"}}}""",
            )
        }

        val result = client(transport).submitDecision("apr_1", ApprovalDecision.ONCE)

        assertEquals(ApprovalDecisionOutcome.EXPIRED, result.outcome)
        assertEquals("withdrawn", result.rawDecision)
    }

    @Test
    fun anUnknownApprovalAndAnUnsupportingGatewayStayDistinct() = runBlocking {
        val missing = RecordingTransport().apply {
            responseToReturn = json(
                404,
                """{"protocol":"2.0","error":{"code":"APPROVAL_NOT_FOUND","message":"missing","retryable":false,"retryAfterSeconds":null,"details":{}}}""",
            )
        }
        val notFound = client(missing).submitDecision("apr_gone", ApprovalDecision.ONCE)
        assertEquals(ApprovalDecisionOutcome.NOT_FOUND, notFound.outcome)

        val unsupporting = RecordingTransport().apply { responseToReturn = json(400, """{"protocol":"2.0","error":{"code":"SCHEMA_INVALID","message":"no","retryable":false,"retryAfterSeconds":null,"details":{}}}""") }
        val unsupported = client(unsupporting).submitDecision("apr_1", ApprovalDecision.ONCE)
        assertEquals(ApprovalDecisionOutcome.UNSUPPORTED, unsupported.outcome)
    }

    @Test
    fun everyTierKeepsTheHostVocabulary() {
        assertEquals("once", ApprovalDecision.ONCE.wireValue)
        assertEquals("session", ApprovalDecision.SESSION.wireValue)
        assertEquals("always", ApprovalDecision.ALWAYS.wireValue)
        assertEquals("deny", ApprovalDecision.DENY.wireValue)
        // The two outcomes the Gateway owns can never be submitted.
        assertNull(ApprovalDecision.of("timeout"))
        assertNull(ApprovalDecision.of("withdrawn"))
    }
}
