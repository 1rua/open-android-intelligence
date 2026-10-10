package com.openandroidintelligence.conversation.data

import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.gateway.conversations.ConversationClient
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayGenerationReceiptTest {
    @Test fun completionBeforeBatchHttpReceiptCannotReviveTheGeneration() = runTest {
        assertTerminalPrecedesReceipt("completed")
    }

    @Test fun failureBeforeBatchHttpReceiptCannotReviveTheGeneration() = runTest {
        assertTerminalPrecedesReceipt("failed")
    }

    @Test fun cancellationBeforeBatchHttpReceiptCannotReviveTheGeneration() = runTest {
        assertTerminalPrecedesReceipt("cancelled")
    }

    @Test fun olderQueuedAndDeliveredEventsCannotReviveACompletedGeneration() = runTest {
        val fixture = Fixture(this)
        fixture.transport.batchReceipt.complete(Unit)
        fixture.repository.submitBatch("conv_a", batch())
        assertEquals("gen_received", fixture.repository.generationId.value)
        fixture.status("completed", 3)
        assertNull(fixture.repository.generationId.value)

        fixture.status("queued", 1)
        assertNull("旧 queued 事件不能复活已完成的生成", fixture.repository.generationId.value)
        fixture.status("delivered", 2)
        assertNull("旧 delivered 事件不能复活已完成的生成", fixture.repository.generationId.value)
        assertEquals(GenerationState.IDLE, fixture.repository.generationState.value)
        assertEquals(3, fixture.observed.size)
    }

    @Test fun delayedCurrentGenerationSnapshotCannotReviveItsCompletedIdentity() = runTest {
        val fixture = Fixture(this)
        fixture.transport.batchReceipt.complete(Unit)
        fixture.repository.submitBatch("conv_a", batch())
        fixture.status("delivered", 2)
        val staleSnapshot = async { fixture.client.currentGeneration("conv_a") }
        fixture.transport.snapshotStarted.await()
        assertTrue(fixture.transport.snapshotStarted.isCompleted)
        assertFalse(staleSnapshot.isCompleted)

        fixture.status("completed", 3)
        assertNull(fixture.repository.generationId.value)
        fixture.transport.snapshotReceipt.complete(Unit)
        fixture.repository.installCurrentGeneration("conv_a", staleSnapshot.await())

        assertNull("旧 currentGeneration 快照不能复活已经完成的明确身份", fixture.repository.generationId.value)
        assertEquals(GenerationState.IDLE, fixture.repository.generationState.value)
    }

    @Test fun terminalIdentityIsScopedToItsConversation() = runTest {
        val fixture = Fixture(this)
        fixture.transport.batchReceipt.complete(Unit)
        fixture.repository.submitBatch("conv_a", batch())
        fixture.status("completed", 3)
        fixture.activeConversation = "conv_b"
        fixture.repository.activeConversationChanged()
        fixture.repository.submitBatch("conv_b", batch("cb_other"))

        assertEquals("另一个会话的同名不透明身份仍按其真实回执跟踪", "gen_received", fixture.repository.generationId.value)
        assertEquals(GenerationState.QUEUED, fixture.repository.generationState.value)
    }

    private suspend fun TestScope.assertTerminalPrecedesReceipt(terminal: String) {
        val fixture = Fixture(this)
        val submission = async { fixture.repository.submitBatch("conv_a", batch()) }
        fixture.transport.batchStarted.await()
        assertTrue(fixture.transport.batchStarted.isCompleted)
        assertFalse(submission.isCompleted)
        fixture.status("queued", 1)
        assertEquals("gen_received", fixture.repository.generationId.value)
        if (terminal == "cancelled") fixture.cancelled() else fixture.status(terminal, 2)
        assertNull(fixture.repository.generationId.value)
        assertEquals(2, fixture.observed.size)

        fixture.transport.batchReceipt.complete(Unit)
        assertEquals("msg_cm_one", submission.await().memberIds.getValue("cm_one"))

        assertNull("终态先于 HTTP 回执时，迟到回执不能重新建立生成身份", fixture.repository.generationId.value)
        assertEquals(GenerationState.IDLE, fixture.repository.generationState.value)
    }

    private fun batch(id: String = "cb_one") = MessageBatch(id, listOf(OutgoingMessage(ClientMessageId("cm_one"), "原始提交")))

    private class Fixture(private val testScope: TestScope) {
        val transport = ControlledTransport()
        val client = ConversationClient(GatewayHttpClient(
            GatewayProfile("account", "device", "session", "https://gateway.example", accessToken = "test-token"),
            transport, { ByteArray(64) }, InMemoryEventCursorStore(),
        ))
        var activeConversation = "conv_a"
        val repository = GatewayConversationRepository(client, activeConversationId = { activeConversation })
        val observed = mutableListOf<VerifiedConversationEvent>()
        private var eventIndex = 0

        init {
            testScope.backgroundScope.launch(UnconfinedTestDispatcher(testScope.testScheduler)) {
                repository.observeEvents(ConversationScope("profile", "gateway", "account", "install")).collect { observed += it }
            }
        }

        fun status(state: String, revision: Long) = event("conversation.message.status", mapOf(
            "conversationId" to "conv_a", "generationId" to "gen_received", "messageId" to "msg_cm_one",
            "clientMessageId" to "cm_one", "status" to state, "revision" to revision,
            "errorCode" to if (state == "failed") "AGENT_UNAVAILABLE" else null,
        ))

        fun cancelled() = event("conversation.generation.cancelled", mapOf(
            "conversationId" to "conv_a", "generationId" to "gen_received",
        ))

        private fun event(name: String, payload: Map<String, Any?>) {
            val frame = "id: evt_generation_${++eventIndex}\nevent: $name\ndata: ${Json.canonical(Json.of(mapOf("payload" to payload)))}\n\n"
            transport.events.trySend(frame.toByteArray()).getOrThrow()
            testScope.runCurrent()
        }
    }

    private class ControlledTransport : GatewayByteTransport {
        val batchStarted = CompletableDeferred<Unit>()
        val batchReceipt = CompletableDeferred<Unit>()
        val snapshotStarted = CompletableDeferred<Unit>()
        val snapshotReceipt = CompletableDeferred<Unit>()
        val events = Channel<ByteArray>(Channel.UNLIMITED)

        override suspend fun execute(request: WireRequest): WireResponse {
            val data = if (request.method == "POST" && request.target.endsWith("/message-batches")) {
                val payload = JsonFields.obj(Json.parse(request.body.decodeToString()))!!
                val conversationId = JsonFields.string(payload, "clientConversationId")!!
                assertEquals("/open-android-intelligence/v2/conversations/$conversationId/message-batches", request.target)
                batchStarted.complete(Unit)
                batchReceipt.await()
                mapOf("batchId" to "batch_${JsonFields.string(payload, "clientBatchId")}", "status" to "accepted",
                    "generationId" to "gen_received", "members" to JsonFields.objects(payload, "members").map {
                        val clientId = JsonFields.string(it, "clientMessageId")!!
                        mapOf("clientMessageId" to clientId, "messageId" to "msg_$clientId")
                    })
            } else {
                assertEquals("GET", request.method)
                assertEquals("/open-android-intelligence/v2/conversations/conv_a/generations/current", request.target)
                val captured = mapOf("generation" to mapOf("conversationId" to "conv_a", "generationId" to "gen_received", "state" to "running"))
                snapshotStarted.complete(Unit)
                snapshotReceipt.await()
                captured
            }
            return WireResponse(200, emptyList(), Json.canonical(Json.of(mapOf("protocol" to "2.1", "data" to data))).toByteArray())
        }

        override fun eventStream(request: WireRequest): Flow<ByteArray> = events.receiveAsFlow()
    }
}
