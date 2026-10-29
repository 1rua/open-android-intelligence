package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.gateway.agents.*
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeEventOwnerTest {
    @Test fun switchingAgentsDuringSnapshotCancelsTheOldAckAndTheNewOwnerOnlyReadsItsSession() = runTest {
        val oldHistoryStarted = CompletableDeferred<Unit>()
        val holdOldHistory = CompletableDeferred<Unit>()
        val idle = CompletableDeferred<Unit>()
        val requests = mutableListOf<WireRequest>()
        val transport = object:GatewayByteTransport {
            override suspend fun execute(request:WireRequest):WireResponse {
                requests += request
                val body = when {
                    request.target.contains("/events?") && request.target.contains("snapshot=true") ->
                        """{"events":[],"cursor":"high_water","hasMore":false,"approvals":[]}"""
                    request.target.endsWith("/events/ack") -> """{"cursor":"high_water"}"""
                    request.target.contains("/events?") -> {idle.await();error("cancelled observer")}
                    request.target.contains("/agent-a/") && request.target.endsWith("/history") -> {
                        oldHistoryStarted.complete(Unit);holdOldHistory.await();"""{"messages":[]}"""
                    }
                    request.target.endsWith("/history") -> """{"messages":[{"id":"reply-b","role":"assistant","text":"only-b","contentRevision":1}]}"""
                    else -> error("No message POST is allowed during recovery")
                }
                return WireResponse(200,listOf(RawHeader("Content-Type","application/json")),body.toByteArray())
            }
            override fun eventStream(request:WireRequest)=emptyFlow<ByteArray>()
        }
        val dispatcher=UnconfinedTestDispatcher(testScheduler)
        val client=AgentSessionClient(GatewayHttpClient(GatewayProfile("account","phone","login","https://core.example",accessToken="synthetic"),
            transport,{ByteArray(64)},InMemoryEventCursorStore(),ioDispatcher=dispatcher))
        val original=CoreAgent("agent-a","synthetic","hermes",false,"account",AgentCapabilities(history=true,eventSync=true))
        var selected=original
        val stores=listOf("agent-a","agent-b").associateWith {NativeEventSynchronizerTest.Storage().apply {cursor=null}}
        val repository=CoreAgentConversationRepository(client,{selected},{"chat"},backgroundScope,dispatcher,storeForAgent={stores.getValue(it.id)})
        repository.activeConversationChanged()
        oldHistoryStarted.await()
        selected=original.copy(id="agent-b")
        repository.activeConversationChanged()
        runCurrent()
        holdOldHistory.complete(Unit)
        runCurrent()
        assertTrue(requests.none {it.target.contains("/agent-a/") && it.target.endsWith("/events/ack")})
        assertTrue(requests.any {it.target.contains("/agent-b/") && it.target.endsWith("/events/ack")})
        assertNull(stores.getValue("agent-a").cursor)
        assertEquals("high_water",stores.getValue("agent-b").cursor)
        assertTrue(stores.getValue("agent-b").events.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().all {it.message.id=="reply-b"})
        assertTrue(requests.none {it.target.endsWith("/messages")})
    }

    @Test fun ackRetriesAreBoundedAndReconnectionResumesThePendingCursor()=runTest {
        var failAck=true
        var ackAttempts=0
        val idle=CompletableDeferred<Unit>()
        val transport=object:GatewayByteTransport {
            override suspend fun execute(request:WireRequest):WireResponse {
                val body=when {
                    request.target.endsWith("/events/ack") -> {
                        ackAttempts++
                        if(failAck)throw java.io.IOException("synthetic ack outage")
                        """{"cursor":"high_water"}"""
                    }
                    request.target.contains("snapshot=true") -> """{"events":[],"cursor":"high_water","hasMore":false,"approvals":[]}"""
                    request.target.endsWith("/history") -> """{"messages":[]}"""
                    else -> {idle.await();error("cancelled observer")}
                }
                return WireResponse(200,listOf(RawHeader("Content-Type","application/json")),body.toByteArray())
            }
            override fun eventStream(request:WireRequest)=emptyFlow<ByteArray>()
        }
        val dispatcher=UnconfinedTestDispatcher(testScheduler)
        val client=AgentSessionClient(GatewayHttpClient(GatewayProfile("account","phone","login","https://core.example",accessToken="synthetic"),
            transport,{ByteArray(64)},InMemoryEventCursorStore(),ioDispatcher=dispatcher))
        val agent=CoreAgent("hermes","synthetic","hermes",false,"account",AgentCapabilities(history=true,eventSync=true))
        val store=NativeEventSynchronizerTest.Storage().apply {cursor=null}
        val repository=CoreAgentConversationRepository(client,{agent},{"chat"},backgroundScope,dispatcher,storeForAgent={store})
        repository.activeConversationChanged()
        advanceTimeBy(20000);runCurrent()
        assertEquals(6,ackAttempts)
        assertEquals("high_water",store.pending)
        assertEquals(StreamHealth.FAILED,repository.streamHealth.first())
        assertTrue(repository.streamFailure.first().orEmpty().contains("ACK_FAILED:IOException"))
        failAck=false
        repository.connectionRestored();runCurrent()
        assertEquals(7,ackAttempts)
        assertNull(store.pending)
        assertEquals("high_water",store.cursor)
    }

    @Test fun nativeHistoryAndSteeringOriginalsDoNotRequireAcceptedGenerationReceipts()=runTest {
        historyReceiptScenario(false)
    }

    @Test fun snapshotRestoresEveryBoundOfflineTurnBeforeAckInsteadOfOnlyTheLastUser()=runTest {
        historyReceiptScenario(true)
    }

    private suspend fun TestScope.historyReceiptScenario(boundTurns:Boolean) {
        val requests=mutableListOf<WireRequest>()
        val idle=CompletableDeferred<Unit>()
        val dispatcher=UnconfinedTestDispatcher(testScheduler)
        val transport=object:GatewayByteTransport {
            override suspend fun execute(request:WireRequest):WireResponse {
                requests+=request
                val body=when {
                    request.target.contains("snapshot=true") -> """{"events":[],"cursor":"high_water","hasMore":false,"approvals":[]}"""
                    request.target.endsWith("/events/ack") -> """{"cursor":"high_water"}"""
                    request.target.contains("/events?") -> {idle.await();error("cancelled observer")}
                    request.target.endsWith("/history") -> if(boundTurns)
                        """{"messages":[{"id":"old-user-1","role":"user","text":"first accepted","clientMessageId":"old-client-1","generationId":"gen-1"},{"id":"old-user-2","role":"user","text":"second accepted","clientMessageId":"old-client-2","generationId":"gen-2"},{"id":"steering-original","role":"user","text":"preserve steering","clientMessageId":"steering-client"}]}"""
                        else """{"messages":[{"id":"native-original","role":"user","text":"old native input","clientMessageId":"native-client"},{"id":"steering-original","role":"user","text":"preserve steering","clientMessageId":"steering-client","generationId":"unbound-steering-generation"}]}"""
                    request.target.contains("/generation?") -> {
                        if(!boundTurns || request.target.contains("steering-client") || request.target.contains("native-client"))
                            return WireResponse(404,emptyList(),"""{"error":{"code":"GENERATION_NOT_FOUND"}}""".toByteArray())
                        val second=request.target.contains("old-client-2")
                        val n=if(second) 2 else 1
                        """{"generation":{"generationId":"gen-$n","conversationId":"chat","clientMessageId":"old-client-$n","userMessageId":"old-user-$n","state":"${if(second) "failed" else "completed"}","revision":7}}"""
                    }
                    else -> error("Unexpected request; task POST is forbidden")
                }
                return WireResponse(200,listOf(RawHeader("Content-Type","application/json")),body.toByteArray())
            }
            override fun eventStream(request:WireRequest)=emptyFlow<ByteArray>()
        }
        val client=AgentSessionClient(GatewayHttpClient(GatewayProfile("account","phone","login","https://core.example",accessToken="synthetic"),
            transport,{ByteArray(64)},InMemoryEventCursorStore(),ioDispatcher=dispatcher))
        val agent=CoreAgent("hermes","synthetic","hermes",false,"account",AgentCapabilities(history=true,generationRecovery=true,eventSync=true))
        val store=NativeEventSynchronizerTest.Storage().apply {cursor=null}
        val repo=CoreAgentConversationRepository(client,{agent},{"chat"},backgroundScope,dispatcher,storeForAgent={store})
        repo.activeConversationChanged();runCurrent()
        assertEquals("非 ledger 原稿不能阻止本机落盘与 ACK", "high_water",store.cursor)
        assertTrue(store.events.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().any {
            it.message.id=="steering-original" && it.message.parts.filterIsInstance<MessagePart.Text>().single().value=="preserve steering" })
        val statuses=store.events.filterIsInstance<VerifiedConversationEvent.MessageStatus>()
        if(boundTurns) {
            assertEquals(setOf("old-client-1","old-client-2"),statuses.map {it.clientMessageId.value}.toSet())
            assertTrue(statuses.any {it.status==AgentMessageStatus.COMPLETED})
            assertTrue(statuses.any {it.status==AgentMessageStatus.FAILED})
        } else assertTrue("无回执不能推断完成",statuses.isEmpty())
        assertTrue(requests.none {it.target.endsWith("/messages")})
        assertTrue(requests.none {it.target.contains("clientMessageId=native-client")})
    }

    @Test fun anOldCoreWithoutEventSyncKeepsGenerationRecoveryAndNeverCallsTheNewEndpoints()=runTest {
        val requests=mutableListOf<WireRequest>()
        val transport=object:GatewayByteTransport {
            override suspend fun execute(request:WireRequest):WireResponse {
                requests+=request
                return WireResponse(200,emptyList(),"""{"messages":[]}""".toByteArray())
            }
            override fun eventStream(request:WireRequest)=emptyFlow<ByteArray>()
        }
        val dispatcher=UnconfinedTestDispatcher(testScheduler)
        val client=AgentSessionClient(GatewayHttpClient(GatewayProfile("account","phone","login","https://core.example",accessToken="synthetic"),
            transport,{ByteArray(64)},InMemoryEventCursorStore(),ioDispatcher=dispatcher))
        val agent=CoreAgent("old-core","synthetic","hermes",false,"account",AgentCapabilities(history=true,generationRecovery=true))
        val repository=CoreAgentConversationRepository(client,{agent},{"chat"},backgroundScope,dispatcher,
            storeForAgent={NativeEventSynchronizerTest.Storage()})
        repository.activeConversationChanged()
        repository.connectionRestored()
        repository.timeline("chat",PageRequest())
        runCurrent()
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all {it.target.endsWith("/history")})
    }
}
