package com.openandroidintelligence.conversation.data

import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.gateway.attachments.*
import com.openandroidintelligence.gateway.conversations.ConversationClient
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Wire shapes from Gateway Protocol v2 and both shipped Gateway adapters. */
class GatewayWireRegressionTest {
    private val scope = ConversationScope("profile", "gateway", "account", "install")

    @Test fun createsAndListsConversationsFromTheGatewayEnvelope() = runBlocking {
        val transport = ContractTransport()
        val repository = GatewayConversationRepository(ConversationClient(http(transport))) { "conv_server" }
        val conversation = repository.createConversation(scope, "cconv_local")
        assertEquals("conv_server", conversation.id.value)
        val listed = repository.listConversations(scope, PageRequest())
        assertEquals(listOf("conv_server"), listed.conversations.map { it.id.value })
        assertEquals("/open-android-intelligence/v2/conversations", transport.requests.last().target)
    }

    @Test fun sendsTextAndVerifiedAttachmentsUsingTheChatV1Request() = runBlocking {
        val transport = ContractTransport()
        val repository = GatewayConversationRepository(ConversationClient(http(transport))) { "conv_server" }
        val accepted = repository.submitMessage(OutgoingMessage(ClientMessageId("cmsg_local"), "检查附件", listOf("att_server")))
        assertEquals("msg_server", accepted.messageId)
        val payload = JsonFields.obj(Json.parse(String(transport.requests.single().body)))!!
        assertEquals(setOf("clientMessageId", "text", "attachments"), payload.fields.map { it.first }.toSet())
        assertEquals("cmsg_local", JsonFields.string(payload, "clientMessageId"))
        assertEquals("检查附件", JsonFields.string(payload, "text"))
        assertEquals("att_server", JsonFields.string(JsonFields.objects(payload, "attachments").single(), "attachmentId"))
    }

    @Test fun uploadsExactBytesThroughAllThreeSignedRequests() = runBlocking {
        val transport = ContractTransport()
        val uploader = AttachmentUploader(HttpAttachmentTransport(http(transport)))
        val bytes = byteArrayOf(0, 0x7b, 0x7d, 0x2b, 0x25, 0xff.toByte(), 0x80.toByte(), 0xc3.toByte(), 0xa9.toByte())
        val id = uploader.upload(SelectedAttachment("图片.bin", "application/octet-stream", bytes))
        assertEquals("att_server", id)
        assertEquals(listOf("POST", "PUT", "POST"), transport.requests.map { it.method })
        assertArrayEquals(bytes, transport.requests[1].body)
        assertEquals(bytes.size.toString(), transport.requests[1].headers.single { it.name == "Content-Length" }.value)
        assertEquals("/open-android-intelligence/v2/attachments/att_server/commit", transport.requests.last().target)
        transport.requests.forEach { request ->
            assertEquals(request.headers.single { it.name.endsWith("Request-Id") }.value,
                request.headers.single { it.name == "Idempotency-Key" }.value)
        }
    }

    private fun http(transport: GatewayByteTransport) = GatewayHttpClient(
        GatewayProfile("account", "device", "session", "https://gateway.example", accessToken = "test-token"),
        transport, { ByteArray(64) }, InMemoryEventCursorStore(),
    )

    private class ContractTransport : GatewayByteTransport {
        val requests = mutableListOf<WireRequest>()
        override suspend fun execute(request: WireRequest): WireResponse {
            requests += request
            val path = request.target.removePrefix("/open-android-intelligence/v2")
            val data = when (request.method to path) {
                "POST" to "/conversations" -> """{"conversation":{"conversationId":"conv_server","title":"新对话"}}"""
                "GET" to "/conversations" -> """{"conversations":[{"conversationId":"conv_server","title":"新对话"}]}"""
                "POST" to "/conversations/conv_server/messages" -> {
                    val body = JsonFields.obj(Json.parse(String(request.body)))!!
                    if (JsonFields.string(body, "clientMessageId") == null) {
                        return WireResponse(400, emptyList(), """{"error":{"code":"SCHEMA_INVALID"}}""".toByteArray())
                    }
                    """{"message":{"messageId":"msg_server","conversationId":"conv_server","status":"accepted"}}"""
                }
                "POST" to "/attachments" -> """{"attachment":{"attachmentId":"att_server","state":"created"}}"""
                "PUT" to "/attachments/att_server/content" -> """{"attachment":{"attachmentId":"att_server","state":"uploading"}}"""
                "POST" to "/attachments/att_server/commit" -> """{"attachment":{"attachmentId":"att_server","state":"verified"}}"""
                else -> return WireResponse(400, emptyList(), """{"error":{"code":"SCHEMA_INVALID"}}""".toByteArray())
            }
            return WireResponse(200, emptyList(), """{"requestId":"req_server","correlationId":"cor_server","protocol":"2.0","data":$data}""".toByteArray())
        }
        override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
    }
}
