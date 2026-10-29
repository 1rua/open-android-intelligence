package com.openandroidintelligence.gateway.agents

import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.JsonFields
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeEventSyncClientTest {
    private val agent=CoreAgent("hermes","synthetic","hermes",false,"account",AgentCapabilities(eventSync=true))
    private val requests=mutableListOf<WireRequest>()
    private var response="""{"events":[],"cursor":"opaque_cursor-0","hasMore":false}"""
    private val transport=object:GatewayByteTransport {
        override suspend fun execute(request:WireRequest):WireResponse {
            requests+=request
            return WireResponse(200,listOf(RawHeader("Content-Type","application/json")),response.toByteArray())
        }
        override fun eventStream(request:WireRequest)=emptyFlow<ByteArray>()
    }
    private val client get()=AgentSessionClient(GatewayHttpClient(GatewayProfile("account","phone","login","https://core.example",accessToken="synthetic"),transport,{ByteArray(64)},InMemoryEventCursorStore()))

    @Test fun offlineReplayKeepsTheOpaqueCursorAndCanonicalSignedQuery()=runBlocking {
        val page=client.events(agent,"chat",cursor="opaque_cursor-0",waitMs=25000)
        assertEquals("opaque_cursor-0",page.cursor)
        assertEquals(AgentSessionClient.ROOT+"/hermes/sessions/chat/events?cursor=opaque_cursor-0&limit=128&waitMs=25000",requests.single().target)
        assertTrue(requests.single().headers.any {it.name=="Authorization"})
        response="""{"cursor":"opaque_cursor-0"}"""
        assertEquals(page.cursor,client.ackEvents(agent,"chat",page.cursor))
        assertEquals("POST",requests.last().method)
        assertEquals("""{"cursor":"opaque_cursor-0"}""",requests.last().body.decodeToString())
    }
    @Test fun snapshotsDecodePublicApprovalsAndRawEnvelopes()=runBlocking {
        response="""{"events":[],"cursor":"high_water","hasMore":false,"approvals":[{"type":"approval_resolved","approval":{"approvalId":"ap_1","conversationId":"chat","decision":"once","decidedAt":1}}]}"""
        val page=client.events(agent,"chat",snapshot=true)
        assertEquals("approval_resolved",page.approvals!!.single().type)
        assertEquals("ap_1",JsonFields.string(page.approvals!!.single().approval,"approvalId"))
        assertTrue(requests.single().target.endsWith("/events?limit=128&snapshot=true"))
    }
    @Test fun missingCapabilityAndInvalidCursorsNeverIssueRequests()=runBlocking {
        assertEquals("UNSUPPORTED",runCatching {client.events(agent.copy(capabilities=AgentCapabilities()),"chat")}.exceptionOrNull()?.message)
        for(cursor in listOf("","has=padding","has/slash","has+plus")) {
            assertNotNull(runCatching {client.events(agent,"chat",cursor=cursor)}.exceptionOrNull())
        }
        assertTrue(requests.isEmpty())
    }
    @Test fun malformedPagesCannotAdvanceAConsumer()=runBlocking {
        for(body in listOf("""{"events":[],"cursor":"ok"}""","""{"events":[],"cursor":"bad=","hasMore":false}""","""{"events":[{"eventId":"evt","eventType":"conversation.approval.requested"}],"cursor":"ok","hasMore":false}""")) {
            response=body
            assertNotNull(runCatching {client.events(agent,"chat")}.exceptionOrNull())
        }
    }
}
