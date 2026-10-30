package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.gateway.agents.*
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NativeRecoveryDeliverySemanticsTest {
    @Test fun recoveredRunningSteeringCannotBeRetargetedToALaterGeneration() = runTest {
        val h = Harness(this)
        h.restore()
        h.repository.submitMessage("A", h.message("followup", MessageDeliveryMode.QUEUE))
        val result = h.repository.steerMessage("A", OutgoingMessage(ClientMessageId("steer"), "/approve once", delivery=MessageDeliveryMode.STEER))
        assertEquals("generation-A", result.generationId)
        assertEquals("generation-A", h.posts("/steer").single().jsonString("generationId"))
        assertEquals("/approve once", h.posts("/steer").single().jsonString("text"))
        assertTrue(h.posts("/messages").isEmpty())
        assertEquals(listOf("followup"),h.repository.pendingDeliveries("A").map { it.clientMessageId.value })
    }

    @Test fun recoveredSteeringRejectsAMismatchedHostReceipt() = runTest {
        val h = Harness(this).apply { steerReceipt = "generation-later" }
        h.restore()
        assertTrue(runCatching { h.repository.steerMessage("A", h.message("steer", MessageDeliveryMode.STEER)) }.isFailure)
        assertEquals("generation-A", h.posts("/steer").single().jsonString("generationId"))
    }

    @Test fun unknownReceiptCannotAuthorizeSteering() = runTest {
        val h = Harness(this).apply { states["original"] = "unknown" }
        h.restore()
        assertTrue(runCatching { h.repository.steerMessage("A", h.message("steer", MessageDeliveryMode.STEER)) }.isFailure)
        assertTrue(h.posts("/steer").isEmpty())
    }

    @Test fun queuedReceiptCannotAuthorizeSteering() = runTest {
        val h = Harness(this).apply { states["original"] = "queued" }
        h.restore()
        assertTrue(runCatching { h.repository.steerMessage("A", h.message("steer", MessageDeliveryMode.STEER)) }.isFailure)
        assertTrue(h.posts("/steer").isEmpty())
    }

    @Test fun recoveredRunningAcceptsQueueLocallyWithoutPosting() = runTest { queueWhileRecovered("running") }
    @Test fun recoveredQueuedAcceptsQueueLocallyWithoutPosting() = runTest { queueWhileRecovered("queued") }
    private suspend fun TestScope.queueWhileRecovered(state: String) {
        val h = Harness(this).apply { states["original"] = state }
        h.restore()
        val accepted = h.repository.submitMessage("A", h.message("followup", MessageDeliveryMode.QUEUE))
        assertEquals(MessageDeliveryMode.QUEUE, accepted.delivery)
        assertNull(accepted.generationId)
        assertEquals(listOf("followup"), h.repository.pendingDeliveries("A").map { it.clientMessageId.value })
        assertTrue(h.posts("/messages").isEmpty())
    }

    @Test fun realUnknownStillBlocksQueueWithoutPosting() = runTest {
        val h = Harness(this).apply { states["original"] = "unknown" }
        h.restore()
        assertTrue(runCatching { h.repository.submitMessage("A", h.message("followup", MessageDeliveryMode.QUEUE)) }.isFailure)
        assertTrue(h.posts("/messages").isEmpty())
        assertTrue(h.repository.pendingDeliveries("A").isEmpty())
    }

    @Test fun switchingAwayFromARecoveredTurnStillSettlesItAndDrainsOnlyItsQueuedFollowup() = runTest {
        val h = Harness(this)
        h.restore()
        h.repository.submitMessage("A", h.message("followup", MessageDeliveryMode.QUEUE))
        h.selected = "B"; h.repository.activeConversationChanged(); runCurrent()
        h.finishOriginal(); advanceTimeBy(51); runCurrent()
        assertEquals("后台队列必须继续且只发一次", listOf("followup"), h.posts("/messages").map { it.jsonString("clientMessageId") })
        assertTrue(h.posts("/messages").all { it.target.contains("/sessions/A/") })
        assertEquals(GenerationState.IDLE,h.repository.generationState.value)
        assertNull(h.repository.generationId.value)
        assertTrue(h.commits.filterIsInstance<VerifiedConversationEvent.MessageStatus>().any {
            it.conversationId.value == "A" && it.clientMessageId.value == "original" && it.status == AgentMessageStatus.COMPLETED
        })
    }

    @Test fun aLiveReaderThatFailsAfterNavigationStillRecoversItsOwnTurnAndQueue() = runTest {
        val h = Harness(this).apply { holdOriginal = true }
        h.repository.submitMessage("A",h.message("original")); runCurrent()
        h.repository.submitMessage("A",h.message("followup",MessageDeliveryMode.QUEUE))
        h.selected = "B"; h.repository.activeConversationChanged(); runCurrent()
        h.finishOriginal(); h.releaseOriginal.complete(Unit); advanceTimeBy(51); runCurrent()
        assertEquals(listOf("original","followup"),h.posts("/messages").map { it.jsonString("clientMessageId") })
        assertTrue(h.requests.any { it.target.contains("/sessions/A/generation?") && it.target.contains("clientMessageId=original") })
        assertTrue(h.requests.none { it.target.contains("/sessions/B/generation?") && it.target.contains("clientMessageId=original") })
        assertEquals(GenerationState.IDLE,h.repository.generationState.value)
        assertNull(h.repository.generationId.value)
    }

    @Test fun unselectedTerminalCannotReleaseItsQueueBeforeItsOwnStoreCommitSucceeds() = runTest {
        val h = Harness(this)
        h.restore()
        h.repository.submitMessage("A",h.message("followup",MessageDeliveryMode.QUEUE))
        h.selected = "B"; h.repository.activeConversationChanged(); runCurrent()
        h.rejectCommit = true; h.finishOriginal(); advanceTimeBy(51); runCurrent()
        assertTrue(h.posts("/messages").isEmpty())
        h.rejectCommit = false
        h.repository.connectionRestored(); advanceTimeBy(301); runCurrent()
        assertEquals(listOf("followup"),h.posts("/messages").map { it.jsonString("clientMessageId") })
        assertEquals(GenerationState.IDLE,h.repository.generationState.value)
    }

    @Test fun allAmbiguousGenerationsMustCommitTerminalBeforeTheBarrierCanClear() = runTest {
        val h = ambiguousHarness()
        h.states.keys.toList().forEach { h.states[it] = "completed"; h.revisions[it] = 7 }
        h.repository.connectionRestored(); runCurrent()
        assertEquals(GenerationState.IDLE,h.repository.generationState.value)
        assertNull(h.repository.generationId.value)
        h.repository.submitMessage("A",h.message("after")); runCurrent()
        assertEquals(listOf("after"),h.posts("/messages").map { it.jsonString("clientMessageId") })
        assertEquals(setOf("original","other"),h.commits.filterIsInstance<VerifiedConversationEvent.MessageStatus>()
            .filter { it.status == AgentMessageStatus.COMPLETED }.map { it.clientMessageId.value }.toSet().intersect(setOf("original","other")))
    }

    @Test fun aMissingAmbiguousGenerationCannotBeTreatedAsItsTerminal() = runTest {
        val h = ambiguousHarness()
        h.states["original"] = "completed"; h.revisions["original"] = 7
        h.states.remove("other"); h.historyVisible = false
        h.repository.connectionRestored(); runCurrent()
        assertEquals(GenerationState.OUTCOME_UNKNOWN,h.repository.generationState.value)
        assertTrue(runCatching { h.repository.submitMessage("A",h.message("blocked")) }.isFailure)
        assertTrue(h.posts("/messages").isEmpty())
        h.states["other"] = "cancelled"; h.revisions["other"] = 7
        h.repository.connectionRestored(); runCurrent()
        assertEquals(GenerationState.IDLE,h.repository.generationState.value)
    }

    @Test fun emptyHistoryAndMissingReceiptsCannotForgetTheAmbiguousIdentitySet() = runTest {
        val h = ambiguousHarness()
        h.states.clear(); h.historyVisible = false
        h.repository.connectionRestored(); runCurrent()
        assertEquals(GenerationState.OUTCOME_UNKNOWN,h.repository.generationState.value)
        assertTrue(runCatching { h.repository.steerMessage("A",h.message("blocked",MessageDeliveryMode.STEER)) }.isFailure)
        assertTrue(h.posts("/steer").isEmpty())
        h.states["original"] = "completed"; h.revisions["original"] = 7
        h.states["other"] = "completed"; h.revisions["other"] = 7
        h.repository.connectionRestored(); runCurrent()
        assertEquals(GenerationState.IDLE,h.repository.generationState.value)
        assertTrue(h.requests.any { it.target.contains("/generation?") && it.target.contains("clientMessageId=other") })
    }

    @Test fun failedCommitOfAllAmbiguousTerminalsKeepsBothIdentitiesAndTheirBarrier() = runTest {
        val h = ambiguousHarness()
        h.states.keys.toList().forEach { h.states[it] = "completed"; h.revisions[it] = 7 }
        h.rejectCommit = true
        h.repository.connectionRestored(); runCurrent()
        assertEquals(GenerationState.OUTCOME_UNKNOWN,h.repository.generationState.value)
        assertTrue(runCatching { h.repository.submitMessage("A",h.message("blocked",MessageDeliveryMode.QUEUE)) }.isFailure)
        assertTrue(h.posts("/messages").isEmpty())
        h.rejectCommit = false
        h.repository.connectionRestored(); runCurrent()
        assertEquals(GenerationState.IDLE,h.repository.generationState.value)
    }

    private fun TestScope.ambiguousHarness(): Harness = Harness(this).apply {
        states["other"] = "queued"
        restore()
        assertEquals(GenerationState.OUTCOME_UNKNOWN,repository.generationState.value)
        assertNull(repository.generationId.value)
    }

    private class Harness(val test: TestScope) {
        var selected = "A"
        var historyVisible = true
        val eventChanged = CompletableDeferred<Unit>()
        var deliveredTerminal = false
        var rejectCommit = false
        var steerReceipt: String? = null
        var holdOriginal = false
        val releaseOriginal = CompletableDeferred<Unit>()
        val receiptChanged = CompletableDeferred<Unit>()
        val states = linkedMapOf("original" to "running")
        val revisions = mutableMapOf("original" to 1L)
        val requests = mutableListOf<WireRequest>()
        val commits = mutableListOf<VerifiedConversationEvent>()
        val dispatcher = UnconfinedTestDispatcher(test.testScheduler)
        private val memory = NativeEventSynchronizerTest.Storage().apply { cursor = null }
        val store = object : NativeConversationStore by memory {
            override suspend fun recoveryIdentities(conversationId: String) = emptyList<NativeRecoveryIdentity>()
            override suspend fun commitEvents(events: List<VerifiedConversationEvent>) {
                if (rejectCommit) throw IOException("synthetic save failure")
                commits += events
                memory.commitEvents(events)
            }
            override suspend fun commitPage(conversationId: String, events: List<VerifiedConversationEvent>, approvals: List<NativeEventApproval>, cursor: String) {
                if (rejectCommit) throw IOException("synthetic save failure")
                commits += events
                memory.commitPage(conversationId, events, approvals, cursor)
            }
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                val conversation = request.target.substringAfter("/sessions/").substringBefore('/').substringBefore('?')
                val body = when {
                    request.target.contains("snapshot=true") -> """{"events":[],"cursor":"page_$conversation","hasMore":false,"approvals":[]}"""
                    request.target.endsWith("/events/ack") -> """{"cursor":"page_$conversation"}"""
                    request.target.contains("/events?") -> {
                        if (request.target.contains("waitMs=")) {
                            if (conversation == "A" && !deliveredTerminal) eventChanged.await() else awaitCancellation()
                        }
                        val events = if (conversation == "A" && eventChanged.isCompleted && !deliveredTerminal) {
                            deliveredTerminal = true
                            """{"eventId":"terminal-A","eventType":"conversation.message.status","payload":{"conversationId":"A","messageId":"user-original","clientMessageId":"original","generationId":"generation-A","status":"completed","revision":7,"errorCode":null}}"""
                        } else ""
                        """{"events":[$events],"cursor":"page_$conversation","hasMore":false}"""
                    }
                    request.target.endsWith("/history") -> {
                        val rows = if (conversation == "A" && historyVisible) states.keys.joinToString(",") { client ->
                            """{"id":"user-$client","role":"user","text":"$client","clientMessageId":"$client","generationId":"${generation(client)}"}"""
                        } else ""
                        """{"messages":[$rows]}"""
                    }
                    request.target.endsWith("/generation") -> """{"generation":null}"""
                    request.target.contains("/generation?") -> {
                        val id = request.target.substringAfter("clientMessageId=").substringBefore('&')
                        if (id !in states) return WireResponse(404,emptyList(),"""{"error":{"code":"GENERATION_NOT_FOUND"}}""".toByteArray())
                        if (request.target.contains("waitMs=") && states[id] in setOf("running", "queued")) receiptChanged.await()
                        """{"generation":{"generationId":"${generation(id)}","conversationId":"$conversation","clientMessageId":"$id","userMessageId":"user-$id","state":"${states.getValue(id)}","revision":${revisions[id] ?: 1}}}"""
                    }
                    request.target.endsWith("/steer") -> {
                        val generation = steerReceipt ?: request.jsonString("generationId") ?: "generation-later"
                        """{"steering":{"generationId":"$generation","outcome":"accepted"}}"""
                    }
                    request.target.endsWith("/messages") -> {
                        val id = requireNotNull(request.jsonString("clientMessageId"))
                        if (id == "original" && holdOriginal) releaseOriginal.await()
                        val generation = generation(id)
                        val first = "event: message\ndata: {\"type\":\"generation\",\"generationId\":\"$generation\",\"clientMessageId\":\"$id\",\"userMessageId\":\"user-$id\",\"state\":\"running\",\"revision\":1}\n\n"
                        if (id == "original") first else first + "event: message\ndata: {\"type\":\"done\",\"generationId\":\"$generation\",\"outcome\":\"completed\",\"revision\":9}\n\n"
                    }
                    else -> error("Unexpected request ${request.target}")
                }
                return WireResponse(200,listOf(RawHeader("Content-Type",if(request.target.endsWith("/messages")) "text/event-stream" else "application/json")),body.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        private val client = AgentSessionClient(GatewayHttpClient(GatewayProfile("account","phone","login","https://core.example",accessToken="synthetic"),
            transport,{ByteArray(64)},InMemoryEventCursorStore(),ioDispatcher=dispatcher))
        val agent = CoreAgent("hermes","test","hermes",true,"account",AgentCapabilities(history=true,send=true,stream=true,steer=true,generationRecovery=true,eventSync=true))
        val repository = CoreAgentConversationRepository(client,{agent},{selected},test.backgroundScope,dispatcher,storeForAgent={store})
        fun message(id: String, delivery: MessageDeliveryMode = MessageDeliveryMode.NORMAL) = OutgoingMessage(ClientMessageId(id),id,delivery=delivery)
        fun generation(id: String) = if (id == "original") "generation-A" else "generation-$id"
        fun restore() { repository.activeConversationChanged() }
        fun finishOriginal() { states["original"] = "completed"; revisions["original"] = 7; receiptChanged.complete(Unit); eventChanged.complete(Unit) }
        fun posts(suffix: String) = requests.filter { it.method == "POST" && it.target.endsWith(suffix) }
    }
}

private fun WireRequest.jsonString(key: String): String? = JsonFields.string(JsonFields.obj(Json.parse(body.decodeToString())),key)
