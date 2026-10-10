package com.openandroidintelligence.gateway.conversations

import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 恢复查询只能把明确有效的空结果解释为未执行。 */
class QueryMessageEvidenceTest {
    private fun client(data: Any, status: Int = 200): ConversationClient {
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest) = WireResponse(status, emptyList(),
                Json.canonical(Json.of(mapOf("protocol" to "2.1", (if (status == 200) "data" else "error") to data))).toByteArray())
            override fun eventStream(request: WireRequest): Flow<ByteArray> = emptyFlow()
        }
        return ConversationClient(GatewayHttpClient(
            GatewayProfile("acct", "dev", "sess", "https://gateway.example"),
            transport, { ByteArray(64) }, InMemoryEventCursorStore(),
        ))
    }

    private fun assertQueryFailure(data: Any) = runBlocking {
        val failure = runCatching { client(data).queryMessage("conv_a", "cm_original") }.exceptionOrNull()
        assertTrue("错误查询必须抛出稳定错误，不能变成明确不存在：$data", failure?.message?.startsWith("MESSAGE_QUERY_FAILED:") == true)
    }

    @Test fun missingOrMalformedResultCollectionsAreQueryFailures() {
        listOf(emptyMap<String, Any>(), mapOf("messages" to "wrong"), mapOf("messages" to null),
            mapOf("messages" to listOf("wrong")), mapOf("message" to "wrong")).forEach(::assertQueryFailure)
    }

    @Test fun resultsWithoutAValidRemoteIdentityAreQueryFailures() {
        listOf(emptyMap<String, Any>(), mapOf("messageId" to ""), mapOf("messageId" to 12)).forEach { raw ->
            assertQueryFailure(mapOf("messages" to listOf(raw)))
            assertQueryFailure(mapOf("message" to raw))
        }
    }

    @Test fun multipleResultsCannotConfirmOneOriginalIdentity() {
        assertQueryFailure(mapOf("messages" to listOf(mapOf("messageId" to "msg_one"), mapOf("messageId" to "msg_two"))))
    }

    @Test fun anUnrelatedClientIdentityCannotConfirmTheOriginalSend() {
        assertQueryFailure(mapOf("messages" to listOf(mapOf("messageId" to "msg_one", "clientMessageId" to "cm_other"))))
    }

    @Test fun explicitlyEmptyResultsRemainAbsent() = runBlocking {
        assertNull(client(mapOf("messages" to emptyList<Any>())).queryMessage("conv_a", "cm_original"))
        assertNull(client(mapOf("message" to null)).queryMessage("conv_a", "cm_original"))
    }

    @Test fun aKnownMessageKeepsItsAuthoritativeIdentityAndContent() = runBlocking {
        val row = client(mapOf("messages" to listOf(mapOf(
            "messageId" to "msg_one", "clientMessageId" to "cm_original", "sender" to "user",
            "timestamp" to 1000, "state" to "queued", "parts" to listOf(mapOf("type" to "text", "text" to "原消息")),
        )))).queryMessage("conv_a", "cm_original")!!
        assertEquals("msg_one", row.messageId)
        assertEquals("cm_original", row.clientMessageId)
        assertEquals("原消息", (row.parts.single() as MessagePart.Text).text)
    }

    @Test fun rejectedQueriesKeepTheStableErrorWithoutPrivateDetails() = runBlocking {
        val failure = runCatching { client(mapOf("code" to "INTERNAL_ERROR", "message" to "private-detail"), 400)
            .queryMessage("conv_a", "cm_original") }.exceptionOrNull()
        assertEquals("MESSAGE_QUERY_FAILED:INTERNAL_ERROR", failure?.message)
        assertFalse(failure?.message.orEmpty().contains("private-detail"))
    }
}
