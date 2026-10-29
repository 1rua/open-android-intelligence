package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.gateway.agents.*
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeEventSynchronizerTest {
    @Test fun commitFailureNeverAcksAndTheSamePageCanBeRecovered() = runTest {
        val h = Harness().apply { store.failCommit = true }
        assertTrue(runCatching { h.sync().synchronize() }.exceptionOrNull()?.cause is IOException)
        assertTrue(h.acks.isEmpty())
        assertEquals("base", h.store.cursor)
        h.sync().synchronize()
        assertEquals(listOf("next"), h.acks)
        assertTrue(h.published.any { it is VerifiedConversationEvent.TimelineUpsert })
        assertTrue(h.requests.none { it.target.endsWith("/messages") })
        assertTrue(h.log.indexOf("commit") < h.log.indexOf("ack:next"))
    }

    @Test fun aLostAckResponseRetriesTheDurableCursorAfterOwnerRecreation() = runTest {
        val h = Harness().apply { failAck = true }
        assertTrue(runCatching { h.sync().synchronize() }.exceptionOrNull()?.cause is IOException)
        assertEquals("next", h.store.pending)
        assertEquals("base", h.store.cursor)
        h.sync().synchronize()
        assertEquals(listOf("next", "next"), h.acks.take(2))
        assertNull(h.store.pending)
        assertEquals("next", h.store.cursor)
        assertTrue(h.requests.none { it.target.endsWith("/messages") })
    }

    @Test fun expiredReplayBootstrapsBeforeHistoryAndKeepsFollowingEvents() = runTest {
        val h = Harness().apply { expired = true }
        val sync = h.sync()
        sync.synchronize()
        assertTrue(h.log.indexOf("snapshot") < h.log.indexOf("history"))
        assertEquals("high_water", h.store.cursor)
        sync.synchronize()
        assertTrue(h.requests.any { it.target.contains("cursor=high_water") })
        assertEquals("next", h.store.cursor)
    }

    @Test fun resolvedApprovalCannotReopenFromAnOlderRequest() = runTest {
        val h = Harness().apply { store.cursor = null; snapshotApprovals = listOf(approval("approval_resolved")) }
        val sync = h.sync()
        sync.synchronize()
        h.raw = """{"eventId":"old","eventType":"conversation.approval.requested","payload":${Json.canonical(approval("approval_requested").approval)}}"""
        h.approvalPublications.clear()
        sync.synchronize()
        assertEquals(listOf("approval_resolved"), h.approvalPublications.map { it.type })
    }

    @Test fun malformedOrForeignCriticalApprovalNeverGetsAcked() = runTest {
        for (payload in listOf("""{"approvalId":"ap","conversationId":"other"}""", """{"approvalId":"ap","conversationId":"chat"}""")) {
            val h = Harness().apply { raw = """{"eventId":"bad","eventType":"conversation.approval.requested","payload":$payload}""" }
            assertNotNull(runCatching { h.sync().synchronize() }.exceptionOrNull())
            assertTrue(h.acks.isEmpty())
        }
    }

    @Test fun ownerCancellationBeforeCommitCannotAcknowledgeOrWriteAnotherStore() = runTest {
        val h = Harness()
        val gate = CompletableDeferred<Unit>()
        h.historyGate = gate
        val oldOwner = launch(UnconfinedTestDispatcher(testScheduler)) { h.sync().synchronize() }
        runCurrent()
        oldOwner.cancelAndJoin()
        gate.complete(Unit)
        assertTrue(h.acks.isEmpty())
        assertEquals("base",h.store.cursor)
        val newOwner = Harness()
        newOwner.sync().synchronize()
        assertEquals("next",newOwner.store.cursor)
        assertEquals("base",h.store.cursor)
    }

    @Test fun anOlderPromptLifecycleIsAppliedEvenWhenHistoryHasAlreadyMovedToANewerTurn()=runTest {
        val h=Harness().apply {raw="""{"eventId":"failed-old","eventType":"conversation.message.status","payload":{"conversationId":"chat","messageId":"old-user","clientMessageId":"old-client","generationId":"old-generation","status":"failed","revision":7,"errorCode":"AGENT_REQUEST_FAILED"}}"""}
        h.sync().synchronize()
        val status=h.store.events.filterIsInstance<VerifiedConversationEvent.MessageStatus>().single()
        assertEquals("old-client",status.clientMessageId.value)
        assertEquals(AgentMessageStatus.FAILED,status.status)
        assertEquals(7L,status.revision)
        assertEquals(listOf("next"),h.acks)
    }

    @Test fun unknownCriticalEventsCannotBeHiddenByAHistoryRefreshAndAcked()=runTest {
        val h=Harness().apply {raw="""{"eventId":"unknown","eventType":"conversation.message.future-critical","payload":{"conversationId":"chat"}}"""}
        assertNotNull(runCatching {h.sync().synchronize()}.exceptionOrNull())
        assertTrue(h.acks.isEmpty())
    }

    private fun approval(type:String) = NativeEventApproval(type, JsonFields.obj(Json.parse(if(type=="approval_requested")
        """{"approvalId":"ap","conversationId":"chat","command":"synthetic","reason":"test","requestedAt":1,"expiresAt":9999999999999,"options":[{"choice":"once","label":"once"}]}"""
        else """{"approvalId":"ap","conversationId":"chat","decision":"once","decidedAt":2}"""))!!)

    private inner class Harness {
        val requests=mutableListOf<WireRequest>()
        val acks=mutableListOf<String>()
        val log=mutableListOf<String>()
        val store=Storage(log)
        val published=mutableListOf<VerifiedConversationEvent>()
        val approvalPublications=mutableListOf<NativeEventApproval>()
        var failAck=false
        var expired=false
        var historyGate:CompletableDeferred<Unit>?=null
        var snapshotApprovals=emptyList<NativeEventApproval>()
        var raw="""{"eventId":"e1","eventType":"conversation.message.completed","payload":{"conversationId":"chat","messageId":"reply"}}"""
        val agent=CoreAgent("hermes","synthetic","hermes",false,"account",AgentCapabilities(history=true,generationRecovery=true,eventSync=true))
        private val transport=object:GatewayByteTransport {
            override suspend fun execute(request:WireRequest):WireResponse {
                requests+=request
                val body=when {
                    request.target.endsWith("/events/ack") -> {
                        val cursor=JsonFields.string(JsonFields.obj(Json.parse(request.body.decodeToString())),"cursor")!!
                        acks+=cursor;log+="ack:$cursor"
                        if(failAck){failAck=false;throw IOException("lost ack response")}
                        """{"cursor":"$cursor"}"""
                    }
                    request.target.contains("snapshot=true") -> {
                        log+="snapshot"
                        val approvals=Json.canonical(Json.of(snapshotApprovals.map { mapOf("type" to it.type,"approval" to it.approval) }))
                        """{"events":[],"cursor":"high_water","hasMore":false,"approvals":$approvals}"""
                    }
                    expired -> {expired=false;return WireResponse(409,emptyList(),"""{"error":{"code":"CURSOR_EXPIRED"}}""".toByteArray())}
                    else -> """{"events":[$raw],"cursor":"next","hasMore":false}"""
                }
                return WireResponse(200,listOf(RawHeader("Content-Type","application/json")),body.toByteArray())
            }
            override fun eventStream(request:WireRequest)=emptyFlow<ByteArray>()
        }
        private val client=AgentSessionClient(GatewayHttpClient(GatewayProfile("account","phone","login","https://core.example",accessToken="synthetic"),transport,{ByteArray(64)},InMemoryEventCursorStore()))
        fun sync()=NativeEventSynchronizer(client,agent,"chat",store,refresh={
            log+="history";historyGate?.await()
            listOf(VerifiedConversationEvent.TimelineUpsert("history-reply",1,1,TimelineMessage("reply","assistant",listOf(MessagePart.Text("final")),1,conversationId=ConversationId("chat"))))
        },publish={published+=it},publishApproval={approvalPublications+=it})
    }

    internal class Storage(private val log:MutableList<String> = mutableListOf()):NativeConversationStore {
        var cursor:String?="base"
        var pending:String?=null
        var failCommit=false
        val events=mutableListOf<VerifiedConversationEvent>()
        private val approvals=mutableListOf<NativeEventApproval>()
        val identities=mutableListOf<NativeRecoveryIdentity>()
        override suspend fun recoveryIdentities(conversationId:String)=identities.toList()
        override suspend fun saveConversations(page:ConversationPage)=Unit
        override suspend fun commitEvents(events:List<VerifiedConversationEvent>){
            if(failCommit){failCommit=false;throw IOException("synthetic commit failed")}
            this.events+=events
        }
        override suspend fun commitApprovals(conversationId:String,approvals:List<NativeEventApproval>){
            for(item in approvals) {
                val id=JsonFields.string(item.approval,"approvalId")
                if(item.type=="approval_requested" && this.approvals.any { it.type=="approval_resolved" && JsonFields.string(it.approval,"approvalId")==id })continue
                this.approvals.removeAll { it.type==item.type && JsonFields.string(it.approval,"approvalId")==id }
                this.approvals+=item
            }
        }
        override suspend fun loadApprovals(conversationId:String)=approvals.toList()
        override suspend fun pendingAck(conversationId:String)=pending
        override suspend fun eventCursor(conversationId:String)=cursor
        override suspend fun commitPage(conversationId:String,events:List<VerifiedConversationEvent>,approvals:List<NativeEventApproval>,cursor:String){
            commitEvents(events)
            commitApprovals(conversationId,approvals)
            log+="commit";pending=cursor
        }
        override suspend fun confirmAck(conversationId:String,cursor:String){check(pending==cursor);this.cursor=cursor;pending=null}
    }
}
