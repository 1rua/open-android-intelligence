package com.openandroidintelligence.gateway.auth

import com.openandroidintelligence.gateway.http.GatewayByteTransport
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.WireRequest
import com.openandroidintelligence.gateway.http.WireResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract §2: a failure response carries `error.code`, never a top-level
 * `errorCode`. A client that degrades to the HTTP status turns every login
 * refusal into the same unreadable "401", so the code the Gateway named is the
 * one the user has to see.
 */
class GatewayAuthErrorTest {

    private class StubTransport(private val response: WireResponse) : GatewayByteTransport {
        var lastRequest: WireRequest? = null
        override suspend fun execute(request: WireRequest): WireResponse {
            lastRequest = request
            return response
        }

        override fun eventStream(request: WireRequest): Flow<ByteArray> = emptyFlow()
    }

    private fun json(status: Int, body: String): WireResponse = WireResponse(
        status = status,
        headers = listOf(RawHeader("content-type", "application/json")),
        body = body.toByteArray(Charsets.UTF_8),
    )

    private fun client(response: WireResponse) = GatewayAuthClient(
        transport = StubTransport(response),
        installationId = "install-1",
        appVersion = "2.1.0",
        platformApi = 35,
    )

    private fun login(response: WireResponse): Throwable? = runBlocking {
        runCatching {
            client(response).loginWithPassword(
                negotiationId = "neg_1",
                username = "user",
                password = "secret".toCharArray(),
                displayName = "Phone",
                devicePublicKeyBase64Url = "key",
            )
        }.exceptionOrNull()
    }

    @Test
    fun loginFailureReportsTheGatewayErrorCodeInsteadOfTheHttpStatus() {
        val failure = login(
            json(
                401,
                """{"requestId":"req-1","correlationId":"cor-1","protocol":"2.1","error":{"code":"AUTHENTICATION_FAILED","message":"bad password","retryable":false,"retryAfterSeconds":null,"details":{}}}""",
            ),
        )

        assertEquals("AUTHENTICATION_FAILED:AUTHENTICATION_FAILED", failure?.message)
    }

    @Test
    fun aFailureWithoutAReadableEnvelopeStillFallsBackToTheHttpStatus() {
        val failure = login(
            WireResponse(
                status = 503,
                headers = emptyList(),
                body = "gateway is down".toByteArray(Charsets.UTF_8),
            ),
        )

        assertEquals("AUTHENTICATION_FAILED:503", failure?.message)
    }

    @Test
    fun theFailureKeepsTheCodeStatusAndRetryabilityAsStructure() {
        val cause = login(
            json(
                401,
                """{"protocol":"2.1","error":{"code":"AUTHENTICATION_FAILED","message":"no","retryable":false,"retryAfterSeconds":null,"details":{}}}""",
            ),
        )

        val failure = cause as? GatewayAuthException
        assertNotNull("失败必须携带结构化 reason，而不是只有一个字符串", failure)
        assertEquals("AUTHENTICATION_FAILED", failure!!.code)
        assertEquals(401, failure.httpStatus)
        assertEquals(false, failure.retryable)
    }

    @Test
    fun aRefreshReuseIsReadFromTheStructuredCodeNotFromTheMessageShape() = runBlocking {
        val transport = StubTransport(
            json(
                409,
                """{"protocol":"2.1","error":{"code":"REFRESH_REUSED","message":"replay","retryable":false,"retryAfterSeconds":null,"details":{}}}""",
            ),
        )
        val cause = runCatching {
            GatewayAuthClient(transport, "install-1", "2.1.0", 35).refresh(
                accountId = "account-1",
                deviceId = "device-1",
                negotiationId = "neg_1",
                refreshCredential = "old".toByteArray(),
            )
        }.exceptionOrNull()

        assertTrue(
            "REFRESH_REUSED 必须仍被识别为「凭据已废」，不得因消息形状变化而失效",
            GatewayAuthException.credentialRefused(cause),
        )
    }

    @Test
    fun aRefusedCredentialIsRecognisedByStatusAndByCode() {
        val statusRefusal = login(
            json(403, """{"protocol":"2.1","error":{"code":"FORBIDDEN_BY_POLICY","retryable":false,"details":{}}}"""),
        )
        val codeRefusal = login(
            json(400, """{"protocol":"2.1","error":{"code":"SESSION_REVOKED","retryable":false,"details":{}}}"""),
        )

        assertTrue(
            "403 即使 code 不是认证语义，也仍是拒绝",
            GatewayAuthException.credentialRefused(statusRefusal),
        )
        assertTrue(
            "结构化 code 说凭据已废，即使形状里没有 401/403",
            GatewayAuthException.credentialRefused(codeRefusal),
        )
    }

    @Test
    fun anOutageMissingAccountOrWrappedFailureNeverWipesTheCredential() {
        val outage = login(WireResponse(503, emptyList(), "gateway is down".toByteArray(Charsets.UTF_8)))
        val missing = login(
            json(404, """{"protocol":"2.1","error":{"code":"ACCOUNT_NOT_FOUND","retryable":false,"details":{}}}"""),
        )
        val wrapped = IllegalStateException("refresh path", login(json(500, """{"protocol":"2.1","error":{"code":"INTERNAL_ERROR","retryable":true,"details":{}}}""")))

        assertFalse(GatewayAuthException.credentialRefused(outage))
        assertFalse(GatewayAuthException.credentialRefused(missing))
        assertFalse(
            "包装后的失败同样要看结构，不能因为外层消息不含数字就当作未拒绝",
            GatewayAuthException.credentialRefused(wrapped),
        )
    }

    @Test
    fun aMalformedCodeIsNotBranchable() {
        val cause = login(
            json(401, """{"protocol":"2.1","error":{"code":"not a code","retryable":false,"details":{}}}"""),
        )

        val failure = cause as? GatewayAuthException
        assertEquals("畸形 code 不能进入分支", null, failure?.code)
        assertEquals("AUTHENTICATION_FAILED:401", failure?.message)
    }

    @Test
    fun logoutUsesTheCurrentProtocolHeader() = runBlocking {
        val transport = StubTransport(json(200, "{}"))
        GatewayAuthClient(transport, "install-1", "2.1.0", 35).logout(
            accessToken = "token",
            accountId = "account-1",
            deviceId = "device-1",
            sessionId = "session-1",
            revokeRefresh = true,
        )

        assertEquals(
            "2.1",
            transport.lastRequest?.headers?.single { it.name == "X-Open-Android-Intelligence-Protocol" }?.value,
        )
    }
}
