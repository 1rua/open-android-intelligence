package com.openandroidintelligence.gateway.conversations

import com.openandroidintelligence.gateway.events.EventCursorStore
import com.openandroidintelligence.gateway.events.GatewayEvent
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
}
