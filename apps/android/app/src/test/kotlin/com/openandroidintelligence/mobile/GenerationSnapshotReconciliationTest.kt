package com.openandroidintelligence.mobile

import com.openandroidintelligence.conversation.data.GatewayConversationRepository
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.gateway.conversations.ConversationClient
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.negotiation.GenerationCancelCapability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GenerationSnapshotReconciliationTest {
    @Test fun authoritativeNoActiveGenerationFinalizesHistoricalProgressWhenTerminalSseWasLost() = runTest {
        val fixture=Fixture()
        val page=fixture.repository.timeline("conv_a",PageRequest())
        assertEquals("CONFIRMED",page.messages.single().state)
        assertNull(fixture.upstream.generationId.value)
    }

    @Test fun aStaleEmptySnapshotCannotFinalizeProgressAfterANewGenerationArrives() = runTest {
        val fixture=Fixture().apply { transport.snapshotGate=CompletableDeferred() }
        val page=async { fixture.repository.timeline("conv_a",PageRequest()) }
        fixture.transport.snapshotStarted.await()
        fixture.upstream.installCurrentGeneration("conv_a","gen_new" to "running")
        fixture.transport.snapshotGate!!.complete(Unit)
        assertEquals("STREAMING",page.await().messages.single().state)
        assertEquals("gen_new",fixture.upstream.generationId.value)
    }

    @Test fun anUnknownGenerationKeepsHistoricalProgressUnsettled() = runTest {
        val fixture=Fixture().apply { transport.snapshotBody="""{"protocol":"2.1","data":{"generation":{"conversationId":"conv_a","generationId":"gen_unknown","state":"unknown"}}}""" }
        val page=fixture.repository.timeline("conv_a",PageRequest())
        assertEquals("STREAMING",page.messages.single().state)
        assertEquals("gen_unknown",fixture.upstream.generationId.value)
    }

    @Test fun malformedCurrentGenerationCannotBeInterpretedAsANoActiveGenerationReceipt() = runTest {
        val fixture=Fixture().apply { transport.snapshotBody="""{"protocol":"2.1","data":{}}""" }
        val failure=runCatching { fixture.repository.timeline("conv_a",PageRequest()) }.exceptionOrNull()
        assertNotNull(failure)
    }

    private class Fixture {
        val transport=Transport()
        val client=ConversationClient(GatewayHttpClient(
            GatewayProfile("account","device","session","https://gateway.example",accessToken="token"),
            transport,{ByteArray(64)},InMemoryEventCursorStore()))
        val upstream=GatewayConversationRepository(client,activeConversationId={"conv_a"})
        val repository=CapabilityGatedConversationRepository(upstream,client,GenerationCancelCapability(true),{"conv_a"})
    }
    private class Transport : GatewayByteTransport {
        val snapshotStarted=CompletableDeferred<Unit>()
        var snapshotGate:CompletableDeferred<Unit>?=null
        var snapshotBody="""{"protocol":"2.1","data":{"generation":null}}"""
        override suspend fun execute(request:WireRequest):WireResponse {
            val body=if(request.target.endsWith("/generations/current")) {
                snapshotStarted.complete(Unit)
                snapshotGate?.await()
                snapshotBody
            } else {
                assertTrue(request.target.startsWith("/open-android-intelligence/v2/conversations/conv_a/messages"))
                """{"protocol":"2.1","data":{"messages":[{"messageId":"msg_old","sender":"assistant","state":"STREAMING","text":"旧的进度","timestamp":1}],"nextCursor":null,"snapshotRevision":1}}"""
            }
            return WireResponse(200,emptyList(),body.toByteArray())
        }
        override fun eventStream(request:WireRequest)=emptyFlow<ByteArray>()
    }
}
