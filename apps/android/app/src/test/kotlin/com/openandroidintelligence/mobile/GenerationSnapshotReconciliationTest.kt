package com.openandroidintelligence.mobile

import com.openandroidintelligence.conversation.data.GatewayConversationRepository
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.ports.ConversationRepository
import com.openandroidintelligence.conversation.ports.TimelinePage
import com.openandroidintelligence.conversation.ports.completeTimeline
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

    @Test fun anEmptyGenerationSnapshotSettlesHistoricalProgressBeyondTheFirstPage() = runTest {
        val fixture=Fixture().apply { transport.paginated=true }
        val timeline=fixture.repository.completeTimeline("conv_a")
        assertEquals(setOf("msg_new","msg_old"),timeline.messages.map { it.id }.toSet())
        assertTrue(timeline.messages.all { it.state == "CONFIRMED" })
        assertNull(fixture.upstream.generationId.value)
    }

    @Test fun aLaterEmptyReceiptAlsoSettlesProgressAccumulatedFromAnEarlierRunningPage() = runTest {
        val fixture=Fixture().apply { transport.paginated=true }
        fixture.transport.onSnapshotRead={ request ->
            fixture.transport.snapshotBody=if (request == 1)
                """{"protocol":"2.1","data":{"generation":{"conversationId":"conv_a","generationId":"gen_old","state":"running"}}}"""
            else """{"protocol":"2.1","data":{"generation":null}}"""
        }
        val timeline=fixture.repository.completeTimeline("conv_a")
        assertEquals(2,timeline.messages.size)
        assertTrue(timeline.messages.all { it.state == "CONFIRMED" })
        assertNull(fixture.upstream.generationId.value)
    }

    @Test fun aNewGenerationAfterTheFinalPageInvalidatesItsIdleReceiptBeforeCombiningEarlierPages() = runTest {
        val fixture=Fixture().apply { transport.paginated=true }
        fixture.transport.onSnapshotRead={ request ->
            fixture.transport.snapshotBody=if (request == 1)
                """{"protocol":"2.1","data":{"generation":{"conversationId":"conv_a","generationId":"gen_old","state":"running"}}}"""
            else """{"protocol":"2.1","data":{"generation":null}}"""
        }
        val racingRepository=object : ConversationRepository by fixture.repository {
            override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage {
                val result=fixture.repository.timeline(conversationId,page)
                if (page.cursor != null) fixture.upstream.installCurrentGeneration("conv_a","gen_new" to "running")
                return result
            }
        }
        val timeline=racingRepository.completeTimeline("conv_a")
        assertEquals("STREAMING",timeline.messages.single { it.id == "msg_new" }.state)
        assertEquals("gen_new",fixture.upstream.generationId.value)
    }

    @Test fun aGenerationArrivingWhileALaterPageLoadsCannotBeClearedByAnEmptySnapshot() = runTest {
        val fixture=Fixture().apply { transport.paginated=true }
        fixture.transport.onTimelineRead={ page ->
            if (page == 2) fixture.upstream.installCurrentGeneration("conv_a","gen_new" to "running")
        }
        val timeline=fixture.repository.completeTimeline("conv_a")
        assertEquals("STREAMING",timeline.messages.single { it.id == "msg_old" }.state)
        assertEquals("gen_new",fixture.upstream.generationId.value)
    }

    @Test fun aGenerationArrivingDuringALaterEmptySnapshotKeepsItsProgress() = runTest {
        val fixture=Fixture().apply { transport.paginated=true }
        fixture.transport.onSnapshotRead={ request ->
            if (request == 2) fixture.upstream.installCurrentGeneration("conv_a","gen_new" to "running")
        }
        val timeline=fixture.repository.completeTimeline("conv_a")
        assertEquals("STREAMING",timeline.messages.single { it.id == "msg_old" }.state)
        assertEquals("gen_new",fixture.upstream.generationId.value)
    }

    @Test fun anUnnegotiatedGatewayCannotSettleAnyPageOfHistoricalProgress() = runTest {
        val fixture=Fixture(negotiated=false).apply { transport.paginated=true }
        val timeline=fixture.repository.completeTimeline("conv_a")
        assertEquals(2,timeline.messages.size)
        assertTrue(timeline.messages.all { it.state == "STREAMING" })
        assertEquals(0,fixture.transport.snapshotRequests)
    }

    private class Fixture(negotiated: Boolean = true) {
        val transport=Transport()
        val client=ConversationClient(GatewayHttpClient(
            GatewayProfile("account","device","session","https://gateway.example",accessToken="token"),
            transport,{ByteArray(64)},InMemoryEventCursorStore()))
        val upstream=GatewayConversationRepository(client,activeConversationId={"conv_a"})
        val repository=CapabilityGatedConversationRepository(upstream,client,GenerationCancelCapability(negotiated),{"conv_a"})
    }
    private class Transport : GatewayByteTransport {
        val snapshotStarted=CompletableDeferred<Unit>()
        var snapshotGate:CompletableDeferred<Unit>?=null
        var snapshotBody="""{"protocol":"2.1","data":{"generation":null}}"""
        var paginated=false
        var snapshotRequests=0
        var onTimelineRead: (Int) -> Unit = {}
        var onSnapshotRead: (Int) -> Unit = {}
        override suspend fun execute(request:WireRequest):WireResponse {
            val body=if(request.target.endsWith("/generations/current")) {
                onSnapshotRead(++snapshotRequests)
                snapshotStarted.complete(Unit)
                snapshotGate?.await()
                snapshotBody
            } else {
                assertTrue(request.target.startsWith("/open-android-intelligence/v2/conversations/conv_a/messages"))
                val page=if(request.target.contains("cursor=older")) 2 else 1
                onTimelineRead(page)
                val firstPage=paginated && page == 1
                val id=if(firstPage) "msg_new" else "msg_old"
                val nextCursor=if(firstPage) "\"older\"" else "null"
                """{"protocol":"2.1","data":{"messages":[{"messageId":"$id","sender":"assistant","state":"STREAMING","text":"旧的进度","timestamp":$page}],"nextCursor":$nextCursor,"snapshotRevision":1}}"""
            }
            return WireResponse(200,emptyList(),body.toByteArray())
        }
        override fun eventStream(request:WireRequest)=emptyFlow<ByteArray>()
    }
}
