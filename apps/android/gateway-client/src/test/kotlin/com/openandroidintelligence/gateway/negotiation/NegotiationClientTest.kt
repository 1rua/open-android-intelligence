package com.openandroidintelligence.gateway.negotiation

import com.openandroidintelligence.gateway.http.GatewayResponse
import com.openandroidintelligence.gateway.http.SignedGatewayRequest
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.SchemaContractHash
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NegotiationClientTest {

    @Test
    fun readsNegotiationFieldsFromTheProtocolDataEnvelope() = runBlocking {
        var sent: SignedGatewayRequest? = null
        val client = NegotiationClient(
            execute = { request ->
                sent = request
                GatewayResponse(
                    status = 200,
                    headers = emptyList(),
                    body = """
                        {
                          "requestId":"req-1",
                          "correlationId":"cor-1",
                          "protocol":"2.1",
                          "data":{
                            "protocol":{"major":2,"minor":1},
                            "features":{
                              "messages":"chat-v1",
                              "attachments":"staged-sha256-v1",
                              "events":"sse-cursor-v1",
                              "deviceRequests":"risk-queue-v1"
                            },
                            "limits":{
                              "attachmentTtlSeconds":3600,
                              "eventRetentionSeconds":86400
                            },
                            "gatewayIdentity":{
                              "deploymentId":"deploy-1",
                              "tlsSpkiSha256":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                            }
                          }
                        }
                    """.trimIndent().toByteArray(),
                )
            },
            installationId = "install-1",
            appVersion = "2.1.0",
            platformApi = 35,
        )

        val result = client.negotiate("neg-1")

        assertEquals(2, result.protocolMajor)
        assertEquals(1, result.protocolMinor)
        assertEquals("deploy-1", result.deploymentId)
        assertEquals(
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            result.tlsSpkiSha256,
        )
        val requestPayload = JsonFields.obj(Json.parse(checkNotNull(sent).body.decodeToString()))!!
        val offeredProtocol = JsonFields.obj(JsonFields.field(requestPayload, "protocol"))
        assertEquals(1, JsonFields.int(offeredProtocol, "minor"))
        val clientInfo = JsonFields.obj(JsonFields.field(requestPayload, "client"))
        assertEquals("2.1.0", JsonFields.string(clientInfo, "appVersion"))
        assertEquals(SchemaContractHash.CORE, JsonFields.string(JsonFields.obj(JsonFields.field(requestPayload, "schemaHashes")), "core"))
    }

    @Test
    fun rejectsAResponseEnvelopeFromThePreviousProtocolMinor() = runBlocking {
        val client = NegotiationClient(
            execute = { _ ->
                GatewayResponse(
                    status = 200,
                    headers = emptyList(),
                    body = """{"protocol":"2.0","data":{"protocol":{"major":2,"minor":1}}}""".toByteArray(),
                )
            },
            installationId = "install-1",
            appVersion = "2.1.0",
            platformApi = 35,
        )

        val failure = runCatching { client.negotiate("neg-1") }.exceptionOrNull()

        assertEquals("NEGOTIATION_FAILED:invalid-envelope", failure?.message)
    }

    @Test
    fun theCancelGateReflectsDeclarationAndAgreementNotEitherAlone() {
        // ① 网关同意 + 客户端声明过：门禁开。
        val agreed = result(conversationUi = listOf("generation-cancel-v1"))
        assertTrue("双方同意的能力位必须放行", agreed.generationCancelAgreed)
        assertTrue("generation-cancel-v1" in agreed.agreedConversationUi)

        // ② 网关同意但客户端从未声明（如 conversation-mirror-v1）：不得使用。
        val undeclared = result(conversationUi = listOf("conversation-mirror-v1", "generation-cancel-v1"))
        assertTrue("客户端从未声明的能力不得进入同意集合", "conversation-mirror-v1" !in undeclared.agreedConversationUi)

        // ③ 客户端声明了但网关没同意：门禁关。
        val refused = result(conversationUi = emptyList())
        assertTrue("网关没同意的能力位不得放行", !refused.generationCancelAgreed)
    }

    @Test
    fun theClientDeclarationStaysTheSingleSourceOfTruth() {
        // 协商请求携带的声明与门禁用的声明必须是同一份，否则门禁会放行
        // 客户端根本没实现的能力。
        assertTrue(
            DECLARED_CONVERSATION_UI_FEATURES.contains("generation-cancel-v1"),
        )
    }

    private fun result(conversationUi: List<String>): NegotiationResult = NegotiationResult(
        negotiationId = "neg-1",
        protocolMajor = 2,
        protocolMinor = 1,
        deploymentId = null,
        tlsSpkiSha256 = null,
        messages = "chat-v1",
        attachments = "staged-sha256-v1",
        events = "sse-cursor-v1",
        deviceRequests = "risk-queue-v1",
        conversationUi = conversationUi,
    )
}
