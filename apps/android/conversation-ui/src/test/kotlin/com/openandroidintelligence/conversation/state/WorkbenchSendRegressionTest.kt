package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchSendRegressionTest {
    @Test fun firstSendCreatesOneConversationAndSendsTheOriginalDraft() = runTest {
        val repository = RecordingRepository()
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("1111")
        controller.sendDraft()
        controller.sendDraft()
        advanceUntilIdle()
        assertEquals(1, repository.creates)
        assertEquals(listOf("1111"), repository.sent.map { it.text })
        assertEquals("conv_created", controller.state.value.activeThreadId)
    }

    @Test fun failedConversationCreationKeepsTheDraftForRetry() = runTest {
        val repository = RecordingRepository().apply { failCreate = true }
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("保留这条消息")
        controller.sendDraft()
        advanceUntilIdle()
        assertEquals("保留这条消息", controller.state.value.draft)
        assertTrue(repository.sent.isEmpty())
        assertTrue(controller.state.value.notice.orEmpty().contains("CREATE_FAILED"))
    }

    @Test fun sendDraftImmediatelyTransitionsToQueuedGeneration() = runTest {
        val repository = RecordingRepository()
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("hello assistant")
        controller.sendDraft()
        // Check state immediately after sendDraft
        assertEquals(GenerationState.QUEUED, controller.state.value.generation)
        advanceUntilIdle()
    }

    @Test fun stopGenerationCancelsInFlightGenerationAndStreamingMessages() = runTest {
        val repository = RecordingRepository()
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("hello assistant")
        controller.sendDraft()
        advanceUntilIdle()
        assertEquals(GenerationState.QUEUED, controller.state.value.generation)

        // Stop generation
        controller.stopGeneration()
        runCurrent()
        assertEquals(GenerationState.CANCELLED, controller.state.value.generation)
        assertEquals("已停止生成", controller.state.value.notice)
    }

    @Test fun streamingTimelineEventSetsIsStreamingOnEntry() = runTest {
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(
                listOf(
                    TimelineMessage(
                        id = "msg_stream",
                        sender = "assistant",
                        parts = listOf(MessagePart.Text("partial content")),
                        timestamp = 1000L,
                        state = "STREAMING",
                    ),
                ),
                null,
            )
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_created")
        advanceUntilIdle()

        val entries = (controller.state.value.timeline as Loadable.Ready).value
        val streamingEntry = entries.find { it.key == "msg_stream" }
        assertNotNull(streamingEntry)
        assertTrue(streamingEntry!!.isStreaming)
        assertEquals("partial content", streamingEntry.text)

        // Cancel via stopGeneration
        controller.stopGeneration()
        runCurrent()
        val updatedEntries = (controller.state.value.timeline as Loadable.Ready).value
        val cancelledEntry = updatedEntries.find { it.key == "msg_stream" }
        assertNotNull(cancelledEntry)
        assertFalse(cancelledEntry!!.isStreaming)
    }

    @Test fun streamingTimelineUpsertEventsTransitionState() = runTest {
        val eventFlow = kotlinx.coroutines.flow.MutableSharedFlow<VerifiedConversationEvent>()
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = eventFlow
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_1")
        advanceUntilIdle()

        // 1. First streaming delta arrives
        eventFlow.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_1",
                occurredAt = 1000L,
                revision = 1L,
                message = TimelineMessage(
                    id = "msg_stream_1",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("Thinking and typing...")),
                    timestamp = 1000L,
                    state = "STREAMING",
                ),
            ),
        )
        advanceUntilIdle()

        var entries = (controller.state.value.timeline as Loadable.Ready).value
        var entry = entries.find { it.key == "msg_stream_1" }
        assertNotNull(entry)
        assertTrue(entry!!.isStreaming)
        assertEquals("Thinking and typing...", entry.text)
        assertEquals(GenerationState.RUNNING, controller.state.value.generation)

        // 2. Next delta updates text
        eventFlow.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_2",
                occurredAt = 1010L,
                revision = 2L,
                message = TimelineMessage(
                    id = "msg_stream_1",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("Thinking and typing... done!")),
                    timestamp = 1000L,
                    state = "STREAMING",
                ),
            ),
        )
        advanceUntilIdle()

        entries = (controller.state.value.timeline as Loadable.Ready).value
        entry = entries.find { it.key == "msg_stream_1" }
        assertNotNull(entry)
        assertTrue(entry!!.isStreaming)
        assertEquals("Thinking and typing... done!", entry.text)
        assertEquals(GenerationState.RUNNING, controller.state.value.generation)

        // 3. Completed event finalizes streaming
        eventFlow.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_3",
                occurredAt = 1020L,
                revision = 3L,
                message = TimelineMessage(
                    id = "msg_stream_1",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("Thinking and typing... done!")),
                    timestamp = 1000L,
                    state = "CONFIRMED",
                ),
            ),
        )
        advanceUntilIdle()

        entries = (controller.state.value.timeline as Loadable.Ready).value
        entry = entries.find { it.key == "msg_stream_1" }
        assertNotNull(entry)
        assertFalse(entry!!.isStreaming)
        assertEquals(GenerationState.COMPLETED, controller.state.value.generation)
        coroutineContext.cancelChildren()
    }

    @Test fun openThreadResetsGenerationToIdleWhenNotStreaming() = runTest {
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
            override suspend fun timeline(conversationId: String, page: PageRequest) = when (conversationId) {
                "thread_streaming" -> TimelinePage(
                    listOf(
                        TimelineMessage(
                            id = "msg_stream",
                            sender = "assistant",
                            parts = listOf(MessagePart.Text("working...")),
                            timestamp = 1000L,
                            state = "STREAMING",
                        ),
                    ),
                    null,
                )
                else -> TimelinePage(
                    listOf(
                        TimelineMessage(
                            id = "msg_done",
                            sender = "assistant",
                            parts = listOf(MessagePart.Text("all done")),
                            timestamp = 2000L,
                            state = "CONFIRMED",
                        ),
                    ),
                    null,
                )
            }
        }
        val controller = controller(repository)
        runCurrent()

        controller.openThread("thread_streaming")
        advanceUntilIdle()
        assertEquals(GenerationState.RUNNING, controller.state.value.generation)

        // Switch to finished thread
        controller.openThread("thread_finished")
        advanceUntilIdle()
        assertEquals(GenerationState.IDLE, controller.state.value.generation)
    }

    private fun TestScope.controller(repository: RecordingRepository) = WorkbenchController(
        this, repository,
        object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        },
        { ConversationScope("profile", "gateway", "account", "install") },
    )

    private open class RecordingRepository : ConversationRepository {
        var creates = 0
        var failCreate = false
        val sent = mutableListOf<OutgoingMessage>()
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(emptyList(), null)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation {
            creates++
            if (failCreate) error("CREATE_FAILED:offline")
            return Conversation(ConversationId("conv_created"), "新对话", 0)
        }
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance {
            sent += batch.messages
            return BatchAcceptance(batch.batchId, listOf("msg_server"))
        }
        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance {
            sent += message
            return MessageAcceptance("msg_server", message.clientMessageId.value)
        }
        override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
