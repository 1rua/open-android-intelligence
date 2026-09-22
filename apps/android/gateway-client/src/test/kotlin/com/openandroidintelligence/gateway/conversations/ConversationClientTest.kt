package com.openandroidintelligence.gateway.conversations

import com.openandroidintelligence.gateway.events.EventCursorStore
import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.http.GatewayByteTransport
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.WireRequest
import com.openandroidintelligence.gateway.http.WireResponse
import com.openandroidintelligence.gateway.negotiation.GenerationCancelCapability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationClientTest {

    private class RecordingTransport : GatewayByteTransport {
        var lastRequest: WireRequest? = null
        var responseToReturn = WireResponse(
            status = 200,
            headers = listOf(RawHeader("content-type", "application/json")),
            body = """{"protocol":"2.0","data":{"message":{"messageId":"msg_123","conversationId":"conv_123","status":"accepted"}}}""".toByteArray(Charsets.UTF_8),
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

    @Test
    fun sendsChatV1TextAndAttachmentReferences() = runBlocking {
        val transport = RecordingTransport()
        val profile = GatewayProfile("acc_test", "dev_test", "sess_test", "https://gateway.example.com")
        val http = GatewayHttpClient(profile, transport, { ByteArray(64) }, MemoryCursorStore())
        val client = ConversationClient(http)

        val response = client.sendMessage("conv_123", "cmsg_01", "Hello assistant", listOf("att_01"))
        assertEquals("msg_123", response.messageId)
        assertEquals("conv_123", response.conversationId)
        val recorded = transport.lastRequest
        assertNotNull(recorded)
        assertEquals("POST", recorded?.method)
        assertEquals("/open-android-intelligence/v2/conversations/conv_123/messages", recorded?.target)
        val bodyStr = String(recorded!!.body, Charsets.UTF_8)
        assertTrue(bodyStr.contains("Hello assistant"))
        assertTrue(bodyStr.contains("clientMessageId"))
        assertTrue(bodyStr.contains("att_01"))
    }

    @Test
    fun updatesConversationTitleViaPatch() = runBlocking {
        val transport = RecordingTransport().apply {
            responseToReturn = WireResponse(
                status = 200,
                headers = listOf(RawHeader("content-type", "application/json")),
                body = """{"protocol":"2.0","data":{"conversation":{"conversationId":"conv_123","title":"新的对话标题"}}}""".toByteArray(Charsets.UTF_8),
            )
        }
        val profile = GatewayProfile("acc_test", "dev_test", "sess_test", "https://gateway.example.com")
        val http = GatewayHttpClient(profile, transport, { ByteArray(64) }, MemoryCursorStore())
        val client = ConversationClient(http)

        val success = client.updateConversationTitle("conv_123", "新的对话标题")
        assertTrue(success)
        val recorded = transport.lastRequest
        assertNotNull(recorded)
        assertEquals("PATCH", recorded?.method)
        assertEquals("/open-android-intelligence/v2/conversations/conv_123", recorded?.target)
        val bodyStr = String(recorded!!.body, Charsets.UTF_8)
        assertTrue(bodyStr.contains("新的对话标题"))
        assertTrue(bodyStr.contains("title"))
    }

    @Test
    fun parseIsoMillisSupportsVariousFormats() {
        val transport = RecordingTransport()
        val profile = GatewayProfile("acc_test", "dev_test", "sess_test", "https://gateway.example.com")
        val http = GatewayHttpClient(profile, transport, { ByteArray(64) }, MemoryCursorStore())
        val client = ConversationClient(http)

        // 1. Standard ISO with millis and Z
        val t1 = client.parseIsoMillis("2026-09-20T16:00:01.123Z")
        assertNotNull(t1)
        assertEquals(java.time.Instant.parse("2026-09-20T16:00:01.123Z").toEpochMilli(), t1)

        // 2. ISO without millis and with Z
        val t2 = client.parseIsoMillis("2026-09-20T16:00:01Z")
        assertNotNull(t2)
        assertEquals(java.time.Instant.parse("2026-09-20T16:00:01Z").toEpochMilli(), t2)

        // 3. With timezone offset
        val t3 = client.parseIsoMillis("2026-09-20T16:00:01+08:00")
        assertNotNull(t3)
        assertEquals(java.time.OffsetDateTime.parse("2026-09-20T16:00:01+08:00").toInstant().toEpochMilli(), t3)

        // 4. Space separated
        val t4 = client.parseIsoMillis("2026-09-20 16:00:01.500Z")
        assertNotNull(t4)

        // 5. Without timezone offset (local format)
        val t5 = client.parseIsoMillis("2026-09-20T16:00:01")
        assertNotNull(t5)

        // 6. Direct epoch timestamp string
        val t6 = client.parseIsoMillis("1789920001123")
        assertEquals(1789920001123L, t6)

        // 7. Invalid or blank returns null
        org.junit.Assert.assertNull(client.parseIsoMillis(null))
        org.junit.Assert.assertNull(client.parseIsoMillis(""))
        org.junit.Assert.assertNull(client.parseIsoMillis("not-a-date"))
    }

    @Test
    fun readTimelineFallsBackToCreatedAtWhenTimestampIsZero() = runBlocking {
        val wireBody = """
            {
              "protocol": "2.0",
              "data": {
                "messages": [
                  {
                    "messageId": "msg_user_1",
                    "sender": "user",
                    "text": "用户消息",
                    "timestamp": 0,
                    "createdAt": "2026-09-20T16:00:01.500Z"
                  },
                  {
                    "messageId": "msg_assistant_1",
                    "sender": "assistant",
                    "text": "助手回复",
                    "timestamp": 1789920005000,
                    "createdAt": "2026-09-20T16:00:05.000Z"
                  }
                ]
              }
            }
        """.trimIndent()
        val transport = RecordingTransport().apply {
            responseToReturn = WireResponse(
                status = 200,
                headers = listOf(RawHeader("content-type", "application/json")),
                body = wireBody.toByteArray(Charsets.UTF_8),
            )
        }
        val profile = GatewayProfile("acc_test", "dev_test", "sess_test", "https://gateway.example.com")
        val http = GatewayHttpClient(profile, transport, { ByteArray(64) }, MemoryCursorStore())
        val client = ConversationClient(http)

        val page = client.readTimeline("conv_123")
        assertEquals(2, page.messages.size)

        val userMsg = page.messages[0]
        val expectedUserTs = java.time.Instant.parse("2026-09-20T16:00:01.500Z").toEpochMilli()
        assertEquals("msg_user_1", userMsg.messageId)
        assertEquals(expectedUserTs, userMsg.timestamp)
        assertTrue("User message timestamp must be non-zero", userMsg.timestamp != null && userMsg.timestamp!! > 0L)

        val assistantMsg = page.messages[1]
        assertEquals("msg_assistant_1", assistantMsg.messageId)
        assertEquals(1789920005000L, assistantMsg.timestamp)
    }

    @Test
    fun cancelWithoutTheAgreedCapabilityReturnsUnsupportedAndSendsNothing() = runBlocking {
        val transport = RecordingTransport()
        val profile = GatewayProfile("acc_test", "dev_test", "sess_test", "https://gateway.example.com")
        val client = ConversationClient(GatewayHttpClient(profile, transport, { ByteArray(64) }, MemoryCursorStore()))

        // 契约 §4：客户端只能使用双方声明且对端确实实现了的能力。
        // 缺省（没有任何协商结果）时门是关着的。
        val outcome = client.cancelGeneration("conv_123", "gen_1", "req_1")

        assertEquals(ConversationClient.CANCEL_UNAVAILABLE, outcome)
        assertNull("未协商到 generation-cancel-v1 时绝不能发 HTTP 请求", transport.lastRequest)
    }

    @Test
    fun cancelWithTheAgreedCapabilitySendsTheCancelRequestAsBefore() = runBlocking {
        val transport = RecordingTransport().apply {
            responseToReturn = WireResponse(
                status = 200,
                headers = listOf(RawHeader("content-type", "application/json")),
                body = """{"protocol":"2.0","data":{"outcome":"CANCELLED"}}""".toByteArray(Charsets.UTF_8),
            )
        }
        val profile = GatewayProfile("acc_test", "dev_test", "sess_test", "https://gateway.example.com")
        val client = ConversationClient(GatewayHttpClient(profile, transport, { ByteArray(64) }, MemoryCursorStore()))

        val outcome = client.cancelGeneration(
            "conv_123",
            "gen_1",
            "req_1",
            capability = GenerationCancelCapability(agreed = true),
        )

        // 现状行为（发请求、解析闭集 outcome）被锁定为「仅在同意时」。
        assertEquals("CANCELLED", outcome)
        val recorded = transport.lastRequest
        assertNotNull(recorded)
        assertEquals("POST", recorded?.method)
        assertEquals(
            "/open-android-intelligence/v2/conversations/conv_123/generations/gen_1/cancel",
            recorded?.target,
        )
        assertTrue(String(recorded!!.body, Charsets.UTF_8).contains("req_1"))
    }
}
