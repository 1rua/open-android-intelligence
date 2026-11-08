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

    @Test fun cancellationDuringAckKeepsTheCommittedPageForTheNextOwner() = runTest {
        val h = Harness().apply { ackGate = CompletableDeferred() }
        val owner = launch(UnconfinedTestDispatcher(testScheduler)) { h.sync().synchronize() }
        h.ackStarted.await()
        assertEquals("next", h.store.pending)
        assertEquals("base", h.store.cursor)
        assertTrue(h.store.events.isNotEmpty())
        owner.cancelAndJoin()
        assertEquals("next", h.store.pending)
        assertEquals("base", h.store.cursor)
        val readsBefore = h.requests.count { it.target.contains("/events?") }
        h.ackGate = null
        h.sync().synchronize()
        assertEquals(listOf("next", "next"), h.acks)
        assertEquals(readsBefore, h.requests.count { it.target.contains("/events?") })
        assertEquals("next", h.store.cursor)
        assertNull(h.store.pending)
        assertTrue(h.requests.none { it.target.endsWith("/messages") })
    }

    @Test fun localAckConfirmationFailureKeepsThePendingCursorAndNamesLocalStorage() = runTest {
        val h = Harness().apply { store.failConfirm = true }
        val failure = runCatching { h.sync().synchronize() }.exceptionOrNull()
        assertTrue(failure is NativeEventSyncFailure)
        assertEquals("LOCAL_SAVE_FAILED", (failure as NativeEventSyncFailure).stage)
        assertEquals("next", h.store.pending)
        assertEquals("base", h.store.cursor)
        h.sync().synchronize()
        assertEquals(listOf("next", "next"), h.acks)
        assertEquals("next", h.store.cursor)
        assertNull(h.store.pending)
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

    @Test fun aPageCannotBeAcknowledgedWhileItsMessageRevisionIsMissingFromHistory() = runTest {
        for (missing in listOf(false, true)) {
            val h = Harness().apply {
                raw = """{"eventId":"completed-7","eventType":"conversation.message.completed","payload":{"conversationId":"chat","messageId":"reply","generationId":"gen","revision":7}}"""
                historyRevision = 6
                historyMissing = missing
            }
            val sync = h.sync()
            assertTrue(runCatching { sync.synchronize() }.exceptionOrNull() is NativeHistoryBehind)
            assertTrue(h.acks.isEmpty())
            assertEquals("base", h.store.cursor)
            assertTrue(h.store.events.isEmpty())
            h.historyRevision = 7
            h.historyMissing = false
            sync.synchronize()
            assertEquals(listOf("next"), h.acks)
            assertEquals(1, h.requests.count { it.target.contains("/events?") })
            assertEquals(7L, h.store.events.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().single().revision)
        }
    }

    @Test fun hostBodyFramesArePublishedOnceForTheNotificationLayerWhileHistoryStaysSynthetic() = runTest {
        val h = Harness()
        h.sync().synchronize()
        val saved = h.store.events.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().map { it.eventId }
        assertEquals("权威历史只落盘一次", listOf("history-reply"), saved)
        val published = h.published.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>()
        assertEquals("历史快照之后必须再发布宿主自己发出的正文帧", listOf("history-reply", "e1"), published.map { it.eventId })
        // 提醒层按 eventId 前缀过滤手机合成的事件；宿主帧必须保持原身份。
        assertTrue(published.last().eventId.isNotBlank())
        assertTrue(published.last().message.id == "reply")
    }

    @Test fun deletedHistoryWithoutAContentRevisionCoversOldBodyEventsAndSyncsTheFollowingPage() = runTest {
        for (type in listOf("conversation.message.delta", "conversation.message.completed")) {
            val h = Harness().apply {
                raw = bodyEvent(type)
                historyMessage = deletedHistoryMessage()
                pageHasMore = true
            }
            val sync = h.sync()
            assertTrue(sync.synchronize())
            assertEquals(listOf("next"), h.acks)
            assertEquals("next", h.store.cursor)
            assertNull(h.store.pending)
            val deleted = h.store.events.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().single()
            assertTrue(deleted.message.isDeleted)
            assertTrue(deleted.message.parts.isEmpty())
            assertNull(deleted.message.contentRevision)
            assertEquals(listOf(deleted), h.published)
            assertTrue(h.log.indexOf("commit") < h.log.indexOf("ack:next"))

            h.raw = bodyEvent(messageId = "following", generationId = "following-gen", revision = 1)
            h.historyMessage = TimelineMessage("following", "assistant", listOf(MessagePart.Text("new reply")), 2,
                conversationId = ConversationId("chat"), generationId = "following-gen", contentRevision = 1)
            h.pageCursor = "following"
            h.pageHasMore = false
            assertFalse(sync.synchronize())
            assertTrue(h.requests.any { it.target.contains("cursor=next") })
            assertEquals(listOf("next", "following"), h.acks)
            assertEquals("following", h.store.cursor)
            val saved = h.store.events.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>()
            assertEquals(listOf("reply", "following"), saved.map { it.message.id })
            assertEquals("new reply", (saved.last().message.parts.single() as MessagePart.Text).value)
            assertTrue(h.published.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>()
                .filter { it.message.id == "reply" }.all { it.message.isDeleted && it.message.parts.isEmpty() })
        }
    }

    @Test fun aDeletedHistoryPageIsNotAcknowledgedUntilTheDeletionIsPersisted() = runTest {
        val h = Harness().apply {
            raw = bodyEvent()
            historyMessage = deletedHistoryMessage()
            store.failCommit = true
        }
        val sync = h.sync()
        val failure = runCatching { sync.synchronize() }.exceptionOrNull()
        assertTrue(failure is NativeEventSyncFailure)
        assertEquals("LOCAL_SAVE_FAILED", (failure as NativeEventSyncFailure).stage)
        assertTrue(failure.cause is IOException)
        assertTrue(h.acks.isEmpty())
        assertEquals("base", h.store.cursor)
        assertNull(h.store.pending)
        assertTrue(h.store.events.isEmpty())
        assertTrue(h.published.isEmpty())

        // 即使服务端已有后页，也必须先保存并确认内存中保留的原页。
        h.raw = bodyEvent(messageId = "following")
        h.pageCursor = "following"
        sync.synchronize()
        assertEquals(1, h.requests.count { it.target.contains("/events?") })
        assertEquals(listOf("next"), h.acks)
        assertEquals("next", h.store.cursor)
        assertNull(h.store.pending)
        val saved = h.store.events.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().single().message
        assertTrue(saved.isDeleted)
        assertTrue(saved.parts.isEmpty())
        assertNull(saved.contentRevision)
        assertTrue(h.log.indexOf("commit") < h.log.indexOf("ack:next"))
    }

    @Test fun aLostAckForDeletedHistoryRetriesTheSavedCursorWithoutRestoringOldContent() = runTest {
        val h = Harness().apply {
            raw = bodyEvent()
            historyMessage = deletedHistoryMessage()
            failAck = true
        }
        val failure = runCatching { h.sync().synchronize() }.exceptionOrNull()
        assertTrue(failure is NativeEventSyncFailure)
        assertEquals("ACK_FAILED", (failure as NativeEventSyncFailure).stage)
        assertEquals("next", h.store.pending)
        assertEquals("base", h.store.cursor)
        val reads = h.requests.count { it.target.contains("/events?") }
        val refreshes = h.log.count { it == "history" }
        h.historyMessage = null
        h.historyRevision = 7
        h.pageCursor = "following"
        h.sync().synchronize()
        assertEquals(listOf("next", "next"), h.acks)
        assertEquals("next", h.store.cursor)
        assertNull(h.store.pending)
        assertEquals(reads, h.requests.count { it.target.contains("/events?") })
        assertEquals(refreshes, h.log.count { it == "history" })
        val saved = h.store.events.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().single().message
        assertTrue(saved.isDeleted)
        assertTrue(saved.parts.isEmpty())
        assertEquals(h.store.events, h.published)
    }

    @Test fun aDeletedHistoryMessageMustStillMatchTheMessageSessionAndGeneration() = runTest {
        val deleted = deletedHistoryMessage()
        for (message in listOf(deleted.copy(id = "other-message"),
            deleted.copy(conversationId = ConversationId("other-session")), deleted.copy(conversationId = null),
            deleted.copy(generationId = "other-generation"))) {
            val h = Harness().apply { raw = bodyEvent(); historyMessage = message }
            assertTrue(runCatching { h.sync().synchronize() }.exceptionOrNull() is NativeHistoryBehind)
            assertTrue(h.acks.isEmpty())
            assertEquals("base", h.store.cursor)
            assertNull(h.store.pending)
            assertTrue(h.store.events.isEmpty())
            assertTrue(h.published.isEmpty())
        }
    }

    @Test fun deletedHistoryCannotHideABodyEventFromAnotherSession() = runTest {
        val h = Harness().apply {
            raw = bodyEvent(conversationId = "other-session")
            historyMessage = deletedHistoryMessage()
        }
        val failure = runCatching { h.sync().synchronize() }.exceptionOrNull()
        assertTrue(failure is AgentSessionException)
        assertEquals("NATIVE_EVENT_SCOPE_MISMATCH", (failure as AgentSessionException).code)
        assertFalse(h.log.contains("history"))
        assertTrue(h.acks.isEmpty())
        assertEquals("base", h.store.cursor)
        assertTrue(h.store.events.isEmpty())
    }

    @Test fun aDeletedHistoryMessageCannotBypassAnAgentOwnerChangeBeforeCommit() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness().apply {
            raw = bodyEvent()
            historyMessage = deletedHistoryMessage()
            historyGate = gate
        }
        var ownsAgent = true
        var rejectedOwnerChecks = 0
        val sync = h.sync(checkOwner = {
            if (!ownsAgent) {
                rejectedOwnerChecks++
                throw CancellationException("owner changed")
            }
        })
        val task = launch(UnconfinedTestDispatcher(testScheduler)) { sync.synchronize() }
        h.historyStarted.await()
        ownsAgent = false
        gate.complete(Unit)
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(1, rejectedOwnerChecks)
        assertTrue(h.acks.isEmpty())
        assertEquals("base", h.store.cursor)
        assertNull(h.store.pending)
        assertTrue(h.store.events.isEmpty())
        assertTrue(h.published.isEmpty())
    }

    @Test fun emptyNonDeletedHistoryWithoutAContentRevisionCannotCoverABodyEvent() = runTest {
        for (state in listOf("CONFIRMED", "STREAMING", "FAILED")) {
            val h = Harness().apply {
                raw = bodyEvent()
                historyMessage = deletedHistoryMessage().copy(state = state)
            }
            val sync = h.sync()
            assertTrue(runCatching { sync.synchronize() }.exceptionOrNull() is NativeHistoryBehind)
            assertTrue(h.acks.isEmpty())
            assertEquals("base", h.store.cursor)
            assertTrue(h.store.events.isEmpty())
            h.historyMessage = h.historyMessage!!.copy(contentRevision = 7)
            sync.synchronize()
            assertEquals(listOf("next"), h.acks)
            assertEquals(1, h.requests.count { it.target.contains("/events?") })
        }
    }

    @Test fun aRevisionedDeletionCannotCoverANewerBodyRevision() = runTest {
        val h = Harness().apply {
            raw = bodyEvent()
            historyMessage = deletedHistoryMessage().copy(contentRevision = 6)
        }
        assertTrue(runCatching { h.sync().synchronize() }.exceptionOrNull() is NativeHistoryBehind)
        assertTrue(h.acks.isEmpty())
        assertEquals("base", h.store.cursor)
        assertTrue(h.store.events.isEmpty())
    }

    @Test fun generationLifecycleRevisionIsNeverComparedWithMessageContentRevision() = runTest {
        val h = Harness().apply {
            raw = """{"eventId":"status-100","eventType":"conversation.message.status","payload":{"conversationId":"chat","messageId":"user","clientMessageId":"client","generationId":"gen","status":"completed","revision":100,"errorCode":null}}"""
            historyRevision = 1
        }
        h.sync().synchronize()
        assertEquals(listOf("next"), h.acks)
        assertEquals(1L, h.store.events.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().single().revision)
    }

    private fun bodyEvent(type: String = "conversation.message.completed", messageId: String = "reply",
        conversationId: String = "chat", generationId: String = "gen", revision: Long = 7) =
        """{"eventId":"body-$messageId-$revision","eventType":"$type","payload":{"conversationId":"$conversationId","messageId":"$messageId","generationId":"$generationId","revision":$revision,"sender":"assistant","parts":[{"type":"text","text":"stale body"}],"text":"stale body","timestamp":1}}"""

    private fun deletedHistoryMessage() = TimelineMessage("reply", "assistant", emptyList(), 1, state = "DELETED",
        conversationId = ConversationId("chat"), generationId = "gen")

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
        var ackGate:CompletableDeferred<Unit>?=null
        val ackStarted=CompletableDeferred<Unit>()
        var expired=false
        var historyGate:CompletableDeferred<Unit>?=null
        val historyStarted = CompletableDeferred<Unit>()
        var historyRevision = 1L
        var historyMissing = false
        var historyMessage: TimelineMessage? = null
        var pageCursor = "next"
        var pageHasMore = false
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
                        ackStarted.complete(Unit)
                        ackGate?.await()
                        if(failAck){failAck=false;throw IOException("lost ack response")}
                        """{"cursor":"$cursor"}"""
                    }
                    request.target.contains("snapshot=true") -> {
                        log+="snapshot"
                        val approvals=Json.canonical(Json.of(snapshotApprovals.map { mapOf("type" to it.type,"approval" to it.approval) }))
                        """{"events":[],"cursor":"high_water","hasMore":false,"approvals":$approvals}"""
                    }
                    expired -> {expired=false;return WireResponse(409,emptyList(),"""{"error":{"code":"CURSOR_EXPIRED"}}""".toByteArray())}
                    else -> """{"events":[$raw],"cursor":"$pageCursor","hasMore":$pageHasMore}"""
                }
                return WireResponse(200,listOf(RawHeader("Content-Type","application/json")),body.toByteArray())
            }
            override fun eventStream(request:WireRequest)=emptyFlow<ByteArray>()
        }
        private val client=AgentSessionClient(GatewayHttpClient(GatewayProfile("account","phone","login","https://core.example",accessToken="synthetic"),transport,{ByteArray(64)},InMemoryEventCursorStore()))
        fun sync(checkOwner: () -> Unit = {})=NativeEventSynchronizer(client,agent,"chat",store,refresh={
            log+="history";historyStarted.complete(Unit);historyGate?.await()
            val message = historyMessage ?: TimelineMessage("reply","assistant",listOf(MessagePart.Text("final")),1,
                conversationId=ConversationId("chat"),generationId="gen",contentRevision=historyRevision)
            if (historyMissing) emptyList() else listOf(VerifiedConversationEvent.TimelineUpsert(
                "history-${message.id}",1,message.contentRevision ?: 0,message))
        },publish={published+=it},publishApproval={approvalPublications+=it},checkOwner=checkOwner)
    }

    internal class Storage(private val log:MutableList<String> = mutableListOf()):NativeConversationStore {
        val creations = mutableMapOf<String, NativeCreationReceipt>()
        override suspend fun creation(requestId: String) = creations[requestId]
        override suspend fun commitCreation(requestId: String, receipt: NativeCreationReceipt) { creations[requestId] = receipt }
        var cursor:String?="base"
        var pending:String?=null
        var failCommit=false
        var failConfirm=false
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
        override suspend fun confirmAck(conversationId:String,cursor:String){
            if(failConfirm){failConfirm=false;throw IOException("synthetic local ACK confirmation failed")}
            check(pending==cursor);this.cursor=cursor;pending=null
        }
    }
}
