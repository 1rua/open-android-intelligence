package com.openandroidintelligence.gateway.conversations

import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BatchAcceptanceEvidenceTest {
    private fun client(data: Any): ConversationClient {
        val transport = object: GatewayByteTransport {
            override suspend fun execute(request: WireRequest) = WireResponse(200,emptyList(),
                Json.canonical(Json.of(mapOf("protocol" to "2.1","data" to data))).toByteArray())
            override fun eventStream(request: WireRequest): Flow<ByteArray> = emptyFlow()
        }
        return ConversationClient(GatewayHttpClient(GatewayProfile("acct","dev","sess","https://gateway.example"),
            transport,{ByteArray(64)},InMemoryEventCursorStore()))
    }
    private fun batch(count: Int) = MessageBatchRequest("cb_evidence",clientConversationId="cc_evidence",joinMode="newline-v1",
        members=(1..count).map { MessageBatchRequest.BatchMember("cm_$it","text_$it") })

    @Test fun singleAndMultiMemberBatchesEmitOnlyAcknowledgedIdsAndTheAggregateLeaderCorrelation() = runBlocking {
        val previousSink=GatewayLog.sink; val previousEnabled=GatewayLog.protocolEvidenceEnabled
        val evidence=mutableListOf<JsonValue.JObject>()
        GatewayLog.protocolEvidenceEnabled=true
        GatewayLog.sink={tag,line -> if (tag=="OaiE2E") evidence += JsonFields.obj(Json.parse(line))!! }
        try {
            for (count in listOf(1,2)) {
                evidence.clear()
                val request=batch(count)
                val receipt=mapOf("batchId" to "batch_remote","status" to "accepted","generationId" to "gen_remote",
                    "members" to request.members.reversed().map { mapOf("clientMessageId" to it.clientMessageId,
                        "messageId" to "msg_"+it.clientMessageId) })
                val accepted=client(receipt).submitBatch("conv_remote",request)
                assertEquals(count,evidence.size)
                request.members.forEachIndexed { index,member ->
                    val row=evidence[index]
                    assertEquals(accepted.memberIds[member.clientMessageId],JsonFields.string(row,"messageId"))
                    assertEquals("msg_cm_1",JsonFields.string(row,"replyCorrelationId"))
                    assertEquals("conv_remote",JsonFields.string(row,"conversationId"))
                    assertEquals(MessageDigest.getInstance("SHA-256").digest(member.text.toByteArray())
                        .joinToString("") { "%02x".format(it) },JsonFields.string(row,"textSha256"))
                    assertFalse(Json.canonical(row).contains(member.text))
                }
            }
        } finally { GatewayLog.sink=previousSink;GatewayLog.protocolEvidenceEnabled=previousEnabled }
    }

    @Test fun rejectedOrUnrelatedReceiptsNeverEmitAcceptanceEvidence() = runBlocking {
        val previousSink=GatewayLog.sink;val previousEnabled=GatewayLog.protocolEvidenceEnabled
        val evidence=mutableListOf<String>()
        GatewayLog.protocolEvidenceEnabled=true
        GatewayLog.sink={tag,line -> if(tag=="OaiE2E") evidence+=line }
        try {
            val good=mapOf("batchId" to "batch_remote","status" to "accepted",
                "members" to listOf(mapOf("clientMessageId" to "cm_1","messageId" to "msg_1")))
            for (bad in listOf(good+("status" to "failed"),good- "status",good+("batchId" to ""),
                good+("members" to listOf(mapOf("clientMessageId" to "cm_other","messageId" to "msg_other"))),
                good+("members" to listOf(mapOf("clientMessageId" to "cm_1","messageId" to ""))))) {
                assertNotNull(runCatching { client(bad).submitBatch("conv_remote",batch(1)) }.exceptionOrNull())
                assertTrue(evidence.isEmpty())
            }
        } finally { GatewayLog.sink=previousSink;GatewayLog.protocolEvidenceEnabled=previousEnabled }
    }
}
