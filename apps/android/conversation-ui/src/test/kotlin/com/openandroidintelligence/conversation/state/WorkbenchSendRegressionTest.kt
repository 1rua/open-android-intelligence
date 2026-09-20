package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
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

    @Test fun otherConversationTimelineUpsertDoesNotPolluteActiveTimeline() = runTest {
        val eventFlow = kotlinx.coroutines.flow.MutableSharedFlow<VerifiedConversationEvent>()
        var listConversationsCalls = 0
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = eventFlow
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest): ConversationPage {
                listConversationsCalls++
                return ConversationPage(emptyList(), null)
            }
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_1")
        advanceUntilIdle()

        controller.editDraft("hello from conv_1")
        controller.sendDraft()
        advanceUntilIdle()
        assertEquals(GenerationState.QUEUED, controller.state.value.generation)
        val initialListCalls = listConversationsCalls

        // Emit TimelineUpsert belonging to conv_2 (other conversation) with STREAMING
        eventFlow.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_other_1",
                occurredAt = 1000L,
                revision = 1L,
                message = TimelineMessage(
                    id = "msg_other_stream",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("Other conversation delta")),
                    timestamp = 1000L,
                    state = "STREAMING",
                    conversationId = ConversationId("conv_2"),
                ),
            ),
        )
        advanceUntilIdle()

        // 1. Current timeline should NOT contain msg_other_stream
        val entries1 = (controller.state.value.timeline as Loadable.Ready).value
        assertNull(entries1.find { it.key == "msg_other_stream" })
        assertFalse(entries1.any { it.text.contains("Other conversation delta") })
        // 2. Generation state must NOT transition to RUNNING!
        assertEquals(GenerationState.QUEUED, controller.state.value.generation)
        // 3. refreshThreads() should have been called
        assertTrue(listConversationsCalls > initialListCalls)

        // Emit TimelineUpsert belonging to conv_2 with CONFIRMED
        val beforeConfirmedCalls = listConversationsCalls
        eventFlow.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_other_2",
                occurredAt = 2000L,
                revision = 2L,
                message = TimelineMessage(
                    id = "msg_other_stream",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("Other conversation complete")),
                    timestamp = 2000L,
                    state = "CONFIRMED",
                    conversationId = ConversationId("conv_2"),
                ),
            ),
        )
        advanceUntilIdle()

        // Current timeline still does not contain other conversation message
        val entries2 = (controller.state.value.timeline as Loadable.Ready).value
        assertNull(entries2.find { it.key == "msg_other_stream" })
        // Generation state must NOT transition to COMPLETED!
        assertEquals(GenerationState.QUEUED, controller.state.value.generation)
        assertTrue(listConversationsCalls > beforeConfirmedCalls)
        coroutineContext.cancelChildren()
    }

    @Test fun activeConversationTimelineUpsertUpdatesImmediately() = runTest {
        val eventFlow = kotlinx.coroutines.flow.MutableSharedFlow<VerifiedConversationEvent>()
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = eventFlow
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_1")
        advanceUntilIdle()

        // Emit delta for active thread conv_1
        eventFlow.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_act_1",
                occurredAt = 1000L,
                revision = 1L,
                message = TimelineMessage(
                    id = "msg_act_stream",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("streaming message")),
                    timestamp = 1000L,
                    state = "STREAMING",
                    conversationId = ConversationId("conv_1"),
                ),
            ),
        )
        advanceUntilIdle()

        var entries = (controller.state.value.timeline as Loadable.Ready).value
        var entry = entries.find { it.key == "msg_act_stream" }
        assertNotNull(entry)
        assertTrue(entry!!.isStreaming)
        assertEquals("streaming message", entry.text)
        assertEquals(GenerationState.RUNNING, controller.state.value.generation)

        // Emit completed for active thread conv_1
        eventFlow.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_act_2",
                occurredAt = 1050L,
                revision = 2L,
                message = TimelineMessage(
                    id = "msg_act_stream",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("streaming message finalized")),
                    timestamp = 1000L,
                    state = "CONFIRMED",
                    conversationId = ConversationId("conv_1"),
                ),
            ),
        )
        advanceUntilIdle()

        entries = (controller.state.value.timeline as Loadable.Ready).value
        entry = entries.find { it.key == "msg_act_stream" }
        assertNotNull(entry)
        assertFalse(entry!!.isStreaming)
        assertEquals("streaming message finalized", entry.text)
        assertEquals(GenerationState.COMPLETED, controller.state.value.generation)
        coroutineContext.cancelChildren()
    }

    @Test fun observeThreadEventsRetriesOnException() = runTest {
        var attempts = 0
        val eventFlow = kotlinx.coroutines.flow.MutableSharedFlow<VerifiedConversationEvent>()
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = kotlinx.coroutines.flow.flow {
                attempts++
                if (attempts == 1) {
                    throw java.io.IOException("connection dropped")
                }
                emitAll(eventFlow)
            }
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_1")
        runCurrent()

        assertEquals(1, attempts)
        assertTrue(controller.state.value.notice.orEmpty().contains("EVENTS_FAILED"))

        // Advance by 1000ms delay to trigger retryWhen
        advanceTimeBy(1001L)
        runCurrent()
        assertEquals(2, attempts)

        // Verify that after reconnect, events are processed
        eventFlow.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_reconnected",
                occurredAt = 2000L,
                revision = 1L,
                message = TimelineMessage(
                    id = "msg_after_reconnect",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("reconnected ok")),
                    timestamp = 2000L,
                    state = "CONFIRMED",
                    conversationId = ConversationId("conv_1"),
                ),
            ),
        )
        advanceUntilIdle()

        val entries = (controller.state.value.timeline as Loadable.Ready).value
        assertNotNull(entries.find { it.key == "msg_after_reconnect" })
        coroutineContext.cancelChildren()
    }

    @Test fun stopGenerationWithoutGenerationIdSetsUnsupportedAndNotice() = runTest {
        val repository = object : RecordingRepository() {
            override val generationId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        }
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("hello assistant")
        controller.sendDraft()
        advanceUntilIdle()

        controller.stopGeneration()
        runCurrent()
        assertEquals(GenerationState.UNSUPPORTED, controller.state.value.generation)
        assertEquals("STOP_UNAVAILABLE:NO_GENERATION", controller.state.value.notice)
        coroutineContext.cancelChildren()
    }

    @Test fun outOfOrderTimelineUpsertLowerRevisionDoesNotOverwrite() = runTest {
        val eventFlow = kotlinx.coroutines.flow.MutableSharedFlow<VerifiedConversationEvent>()
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = eventFlow
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_1")
        advanceUntilIdle()

        // 1. Revision 2 arrives first (e.g. Completed message)
        eventFlow.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_2",
                occurredAt = 2000L,
                revision = 2L,
                message = TimelineMessage(
                    id = "msg_1",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("final completed text")),
                    timestamp = 2000L,
                    state = "CONFIRMED",
                    conversationId = ConversationId("conv_1"),
                ),
            ),
        )
        advanceUntilIdle()

        var entries = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals("final completed text", entries.find { it.key == "msg_1" }?.text)

        // 2. Out-of-order Revision 1 arrives later (delayed delta)
        eventFlow.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_1",
                occurredAt = 1000L,
                revision = 1L,
                message = TimelineMessage(
                    id = "msg_1",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("stale delta text")),
                    timestamp = 1000L,
                    state = "STREAMING",
                    conversationId = ConversationId("conv_1"),
                ),
            ),
        )
        advanceUntilIdle()

        // Content must NOT regress to stale delta text
        entries = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals("final completed text", entries.find { it.key == "msg_1" }?.text)
        coroutineContext.cancelChildren()
    }

    @Test fun messageAcceptedRequiresExactMatchAndNotBlankCorrelationId() = runTest {
        val eventFlow = kotlinx.coroutines.flow.MutableSharedFlow<VerifiedConversationEvent>()
        val submitGate = CompletableDeferred<Unit>()
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = eventFlow
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
            override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance {
                submitGate.await()
                return MessageAcceptance("msg_srv", message.clientMessageId.value)
            }
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_1")
        advanceUntilIdle()

        controller.editDraft("test message")
        controller.sendDraft()
        runCurrent()

        val pending = controller.state.value.pendingBatch
        assertTrue(pending.isNotEmpty())
        val localKey = pending.first().key
        val clientMsgId = localKey.removePrefix("local_")

        // Emit MessageAccepted with BLANK correlationId -> must NOT remove pending batch
        eventFlow.emit(
            VerifiedConversationEvent.MessageAccepted(
                eventId = "evt_acc_blank",
                occurredAt = 1000L,
                messageId = "msg_srv",
                correlationId = "",
                conversationId = ConversationId("conv_1"),
            )
        )
        advanceUntilIdle()
        assertEquals(pending.size, controller.state.value.pendingBatch.size)

        // Emit MessageAccepted with partial substring -> must NOT remove (due to exact match)
        eventFlow.emit(
            VerifiedConversationEvent.MessageAccepted(
                eventId = "evt_acc_partial",
                occurredAt = 1000L,
                messageId = "msg_srv",
                correlationId = clientMsgId.take(5),
                conversationId = ConversationId("conv_1"),
            )
        )
        advanceUntilIdle()
        assertEquals(pending.size, controller.state.value.pendingBatch.size)

        // Emit MessageAccepted with exact correlationId -> removed!
        eventFlow.emit(
            VerifiedConversationEvent.MessageAccepted(
                eventId = "evt_acc_exact",
                occurredAt = 1000L,
                messageId = "msg_srv",
                correlationId = clientMsgId,
                conversationId = ConversationId("conv_1"),
            )
        )
        advanceUntilIdle()
        assertTrue(controller.state.value.pendingBatch.isEmpty())
        submitGate.complete(Unit)
        coroutineContext.cancelChildren()
    }

    @Test fun unownedEventWithoutConversationIdIsIntercepted() = runTest {
        val eventFlow = kotlinx.coroutines.flow.MutableSharedFlow<VerifiedConversationEvent>()
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = eventFlow
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(
                listOf(
                    TimelineMessage(
                        id = "msg_1",
                        sender = "assistant",
                        parts = listOf(MessagePart.Text("original")),
                        timestamp = 1000L,
                        conversationId = ConversationId("conv_1"),
                    )
                ),
                null,
            )
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_1")
        advanceUntilIdle()

        var entries = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals(1, entries.size)

        // Emit TimelineTombstoned with null conversationId -> intercepted, must NOT delete msg_1
        eventFlow.emit(
            VerifiedConversationEvent.TimelineTombstoned(
                eventId = "evt_tomb_unowned",
                occurredAt = 2000L,
                messageId = "msg_1",
                revision = 1L,
                conversationId = null,
            )
        )
        advanceUntilIdle()

        entries = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals(1, entries.size)
        assertEquals("msg_1", entries.first().key)

        // Emit TimelineTombstoned with matching conversationId -> deleted
        eventFlow.emit(
            VerifiedConversationEvent.TimelineTombstoned(
                eventId = "evt_tomb_owned",
                occurredAt = 2000L,
                messageId = "msg_1",
                revision = 2L,
                conversationId = ConversationId("conv_1"),
            )
        )
        advanceUntilIdle()

        entries = (controller.state.value.timeline as Loadable.Ready).value
        assertTrue(entries.isEmpty())
        coroutineContext.cancelChildren()
    }

    @Test fun renderTimelineMergesPendingLocalBatchWithMirroredMessages() = runTest {
        val eventFlow = kotlinx.coroutines.flow.MutableSharedFlow<VerifiedConversationEvent>()
        val submitGate = CompletableDeferred<Unit>()
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = eventFlow
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
            override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance {
                submitGate.await()
                return MessageAcceptance("msg_srv", message.clientMessageId.value)
            }
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_1")
        advanceUntilIdle()

        controller.editDraft("my pending draft")
        controller.sendDraft()
        runCurrent()

        // User draft is in pendingBatch and timeline
        var entries = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals(1, entries.size)
        assertEquals("my pending draft", entries.first().text)

        // Assistant streaming arrives while user message is still in pendingBatch
        val streamTime = System.currentTimeMillis() + 100L
        eventFlow.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_stream",
                occurredAt = streamTime,
                revision = 1L,
                message = TimelineMessage(
                    id = "msg_assistant_stream",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("Assistant is replying...")),
                    timestamp = streamTime,
                    state = "STREAMING",
                    conversationId = ConversationId("conv_1"),
                ),
            )
        )
        runCurrent()

        // Both the user message (from pendingBatch) AND assistant streaming message are in timeline!
        entries = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals(2, entries.size)
        assertEquals("my pending draft", entries[0].text)
        assertEquals("Assistant is replying...", entries[1].text)
        submitGate.complete(Unit)
        coroutineContext.cancelChildren()
    }

    @Test fun switchingThreadsCancelsPreviousTimelineJob() = runTest {
        var conv1Loaded = false
        val repository = object : RecordingRepository() {
            override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage {
                if (conversationId == "conv_slow") {
                    delay(5000L)
                    conv1Loaded = true
                    return TimelinePage(
                        listOf(TimelineMessage("msg_slow", "assistant", listOf(MessagePart.Text("slow")), 100L)),
                        null,
                    )
                }
                return TimelinePage(
                    listOf(TimelineMessage("msg_fast", "assistant", listOf(MessagePart.Text("fast")), 200L)),
                    null,
                )
            }
        }
        val controller = controller(repository)
        runCurrent()

        // Open slow thread
        controller.openThread("conv_slow")
        runCurrent()

        // Immediately switch to fast thread before slow completes
        controller.openThread("conv_fast")
        advanceUntilIdle()

        assertEquals("conv_fast", controller.state.value.activeThreadId)
        val entries = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals(1, entries.size)
        assertEquals("msg_fast", entries.first().key)
        assertFalse(conv1Loaded)
        coroutineContext.cancelChildren()
    }

    @Test fun historicalAttachmentsDoesNotExceedCapacity() = runTest {
        val repository = RecordingRepository()
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_1")
        advanceUntilIdle()

        val field = WorkbenchController::class.java.getDeclaredField("historicalAttachments").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val map = field.get(controller) as MutableMap<String, TimelineAttachment>

        for (i in 1..40) {
            map["att_$i"] = TimelineAttachment("att_$i", "file_$i.png", "image/png")
        }

        assertTrue(map.size <= 30)
        assertFalse(map.containsKey("att_1"))
        assertTrue(map.containsKey("att_40"))
        coroutineContext.cancelChildren()
    }

    @Test fun anUnansweredSendIsReportedInsteadOfWaitingForever() = runTest {
        val repository = object : RecordingRepository() {
            var timelineCalls = 0
            override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage {
                timelineCalls++
                return TimelinePage(emptyList(), null)
            }
        }
        val controller = controller(
            repository,
            WorkbenchController.ReplyTimeouts(firstReplyMillis = 20_000L, giveUpMillis = 120_000L),
        )
        runCurrent()
        controller.openThread("conv_a")
        runCurrent()
        val beforeSend = repository.timelineCalls

        controller.editDraft("你好")
        controller.sendDraft()
        runCurrent()

        // First deadline: a reply produced while the stream was down is still
        // recoverable, so the timeline is pulled before anything is claimed.
        advanceTimeBy(20_000L)
        runCurrent()
        assertTrue("超时后应当重新拉取时间线补偿", repository.timelineCalls > beforeSend)

        // Final deadline: silence is reported rather than left as a spinner.
        advanceTimeBy(100_000L)
        runCurrent()
        assertTrue(
            "始终无回复必须明确提示，实际: ${controller.state.value.notice}",
            controller.state.value.notice.orEmpty().contains("REPLY_TIMEOUT"),
        )
        controller.cancel()
    }

    @Test fun anArrivingAssistantMessageDisarmsTheWatchdog() = runTest {
        val events = kotlinx.coroutines.flow.MutableSharedFlow<VerifiedConversationEvent>()
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = events
        }
        val controller = controller(
            repository,
            WorkbenchController.ReplyTimeouts(firstReplyMillis = 20_000L, giveUpMillis = 120_000L),
        )
        runCurrent()
        controller.openThread("conv_a")
        runCurrent()
        controller.editDraft("你好")
        controller.sendDraft()
        runCurrent()

        events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_1",
                occurredAt = 0L,
                revision = 0L,
                message = TimelineMessage(
                    id = "msg_assistant",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("正在处理")),
                    timestamp = 1000L,
                    state = "STREAMING",
                    conversationId = ConversationId("conv_a"),
                ),
            ),
        )
        runCurrent()

        advanceTimeBy(120_000L)
        runCurrent()
        assertNull("已经收到回复就不该再报超时", controller.state.value.notice)
        controller.cancel()
    }

    @Test fun streamingWithTempIdFollowedByConfirmedWithPermanentIdDoesNotDuplicate() = runTest {
        val events = kotlinx.coroutines.flow.MutableSharedFlow<VerifiedConversationEvent>()
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = events
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_1")
        runCurrent()

        // 1. Streaming arrives with temporary stream ID
        events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_1",
                occurredAt = 1000L,
                revision = 1L,
                message = TimelineMessage(
                    id = "stream_temp_123",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("你好！我是智能助手。")),
                    timestamp = 1000L,
                    state = "STREAMING",
                    conversationId = ConversationId("conv_1"),
                ),
            ),
        )
        runCurrent()

        var entries = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals("流式阶段应只有1条消息", 1, entries.size)
        assertTrue(entries.first().isStreaming)
        assertEquals("stream_temp_123", entries.first().key)

        // 2. Confirmed arrives with permanent message ID
        events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_2",
                occurredAt = 1020L,
                revision = 2L,
                message = TimelineMessage(
                    id = "msg_confirmed_456",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("你好！我是智能助手。")),
                    timestamp = 1000L,
                    state = "CONFIRMED",
                    conversationId = ConversationId("conv_1"),
                ),
            ),
        )
        runCurrent()

        entries = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals("确认后必须淘汰流式临时ID，且界面只显示1条回复，严禁双倍渲染", 1, entries.size)
        assertFalse("应当转为已确认状态", entries.first().isStreaming)
        assertEquals("应当保留确认ID", "msg_confirmed_456", entries.first().key)
        assertEquals("你好！我是智能助手。", entries.first().text)
        assertEquals(GenerationState.COMPLETED, controller.state.value.generation)
        controller.cancel()
    }

    @Test fun reloadTimelineAndStreamingEventRaceDoesNotDuplicateAssistantReply() = runTest {
        val events = kotlinx.coroutines.flow.MutableSharedFlow<VerifiedConversationEvent>()
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = events
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(
                listOf(
                    TimelineMessage(
                        id = "msg_server_1",
                        sender = "assistant",
                        parts = listOf(MessagePart.Text("执行结果如下：成功")),
                        timestamp = 1000L,
                        state = "CONFIRMED",
                        conversationId = ConversationId("conv_race"),
                    ),
                ),
                null,
            )
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_race")
        runCurrent()

        // Streaming event with a different ID arrives concurrently or just before/after reload
        events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_stream",
                occurredAt = 1000L,
                revision = 1L,
                message = TimelineMessage(
                    id = "stream_worker_chunk",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("执行结果如下：")),
                    timestamp = 1000L,
                    state = "STREAMING",
                    conversationId = ConversationId("conv_race"),
                ),
            ),
        )
        runCurrent()

        // Trigger reload (as would happen via watchdog or reconnect fallback)
        controller.retryTimeline()
        runCurrent()

        val entries = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals("接口补偿与流式事件并发时，必须合并去重，只保留1条回复", 1, entries.size)
        assertEquals("msg_server_1", entries.first().key)
        assertEquals("执行结果如下：成功", entries.first().text)
        assertFalse(entries.first().isStreaming)
        controller.cancel()
    }

    @Test fun reloadTimelinePullingConfirmedReplyDisarmsWatchdog() = runTest {
        var replyReady = false
        val repository = object : RecordingRepository() {
            override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage {
                return if (replyReady) {
                    TimelinePage(
                        listOf(
                            TimelineMessage(
                                id = "msg_confirmed_watchdog",
                                sender = "assistant",
                                parts = listOf(MessagePart.Text("补偿拉取成功获取回复")),
                                timestamp = System.currentTimeMillis() + 1000L,
                                state = "CONFIRMED",
                                conversationId = ConversationId("conv_wd"),
                            ),
                        ),
                        null,
                    )
                } else {
                    TimelinePage(emptyList(), null)
                }
            }
        }
        val controller = controller(
            repository,
            WorkbenchController.ReplyTimeouts(firstReplyMillis = 20_000L, giveUpMillis = 120_000L),
        )
        runCurrent()
        controller.openThread("conv_wd")
        runCurrent()

        controller.editDraft("请执行任务")
        controller.sendDraft()
        runCurrent()

        // Server finished reply in the background while stream was silent
        replyReady = true

        // First timeout triggers reloadTimeline at 20s
        advanceTimeBy(20_000L)
        runCurrent()

        val entries = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals(2, entries.size) // 1 user + 1 assistant
        assertEquals("补偿拉取成功获取回复", entries.last().text)

        // Advance past giveUpMillis (120s)
        advanceTimeBy(100_000L)
        runCurrent()

        // Watchdog should have been disarmed by reloadTimeline, no timeout error!
        assertNull("reloadTimeline 既然已拉取到确认回复，看门狗必须解除，不得报超时", controller.state.value.notice)
        controller.cancel()
    }

    @Test fun delayedStreamingChunkDoesNotReintroduceDuplicateAfterConfirmedReply() = runTest {
        val events = kotlinx.coroutines.flow.MutableSharedFlow<VerifiedConversationEvent>()
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = events
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_delayed")
        runCurrent()

        // Confirmed message already arrived
        events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_conf",
                occurredAt = 1010L,
                revision = 10L,
                message = TimelineMessage(
                    id = "msg_final",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("完整处理结果")),
                    timestamp = 1000L,
                    state = "CONFIRMED",
                    conversationId = ConversationId("conv_delayed"),
                ),
            ),
        )
        runCurrent()

        // A delayed in-flight streaming chunk arrives AFTER confirmed message
        events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_delayed_chunk",
                occurredAt = 1005L,
                revision = 5L,
                message = TimelineMessage(
                    id = "stream_chunk_delayed",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("完整")),
                    timestamp = 1000L,
                    state = "STREAMING",
                    conversationId = ConversationId("conv_delayed"),
                ),
            ),
        )
        runCurrent()

        val entries = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals("迟到的流式分块不得重新插入已经确认的消息前，保持唯一回复", 1, entries.size)
        assertEquals("msg_final", entries.first().key)
        assertEquals("完整处理结果", entries.first().text)
        assertFalse(entries.first().isStreaming)
        controller.cancel()
    }

    @Test fun confirmedEventFollowedByReloadWithDifferentIdDoesNotDuplicateReply() = runTest {
        val events = kotlinx.coroutines.flow.MutableSharedFlow<VerifiedConversationEvent>()
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = events
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(
                listOf(
                    TimelineMessage(
                        id = "msg_db_persisted_id",
                        sender = "assistant",
                        parts = listOf(MessagePart.Text("你好！有什么我可以帮你的？")),
                        timestamp = 1000L,
                        state = "CONFIRMED",
                        conversationId = ConversationId("conv_dup_test"),
                    ),
                ),
                null,
            )
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_dup_test")
        runCurrent()

        // Confirmed event arrived via WebSocket/SSE with an ephemeral or transport ID
        events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_transport_1",
                occurredAt = 1000L,
                revision = 1L,
                message = TimelineMessage(
                    id = "msg_transport_ephemeral_id",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("你好！有什么我可以帮你的？")),
                    timestamp = 1000L,
                    state = "CONFIRMED",
                    conversationId = ConversationId("conv_dup_test"),
                ),
            ),
        )
        runCurrent()

        // Now reloadTimeline runs (watchdog fallback, reconnect sync, or user refresh)
        controller.retryTimeline()
        runCurrent()

        val entries = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals("即使服务端事件与历史拉取的 confirmed 消息 ID 不同，内容相同也绝不能在界面双倍渲染", 1, entries.size)
        assertEquals("你好！有什么我可以帮你的？", entries.first().text)
        controller.cancel()
    }

    @Test fun streamingReplyAfterToolExecutionConfirmedMessageIsPreserved() = runTest {
        val events = kotlinx.coroutines.flow.MutableSharedFlow<VerifiedConversationEvent>()
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = events
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_tool")
        runCurrent()

        // 1. Tool execution confirmation message arrived first
        events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_tool",
                occurredAt = 1000L,
                revision = 1L,
                message = TimelineMessage(
                    id = "msg_tool_step",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("正在查询天气信息...")),
                    timestamp = 1000L,
                    state = "CONFIRMED",
                    conversationId = ConversationId("conv_tool"),
                ),
            ),
        )
        runCurrent()

        // 2. Final reply starts streaming with DIFFERENT content
        events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_final_stream",
                occurredAt = 1005L,
                revision = 2L,
                message = TimelineMessage(
                    id = "stream_final_reply",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("今天北京晴天，气温20度。")),
                    timestamp = 1005L,
                    state = "STREAMING",
                    conversationId = ConversationId("conv_tool"),
                ),
            ),
        )
        runCurrent()

        val entries = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals("前置确认消息之后的独立流式消息不得被误伤过滤，应同时展示工具执行结果与当前流式内容", 2, entries.size)
        assertEquals("msg_tool_step", entries[0].key)
        assertEquals("正在查询天气信息...", entries[0].text)
        assertEquals("stream_final_reply", entries[1].key)
        assertEquals("今天北京晴天，气温20度。", entries[1].text)
        assertTrue(entries[1].isStreaming)
        controller.cancel()
    }

    @Test fun timelineWithZeroTimestampsPreservesChronologicalOrderAndDoesNotGroupAtTop() = runTest {
        val repository = object : RecordingRepository() {
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(
                listOf(
                    TimelineMessage(id = "msg_u1", sender = "user", parts = listOf(MessagePart.Text("/new")), timestamp = 1000L),
                    TimelineMessage(id = "msg_a1", sender = "assistant", parts = listOf(MessagePart.Text("Session started")), timestamp = 2000L),
                    TimelineMessage(id = "msg_u2", sender = "user", parts = listOf(MessagePart.Text("你好")), timestamp = 0L),
                ),
                null,
            )
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_zero_ts")
        advanceUntilIdle()

        val entries = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals(3, entries.size)
        assertEquals("msg_u1", entries[0].key)
        assertEquals("msg_a1", entries[1].key)
        assertEquals("msg_u2", entries[2].key)
    }

    @Test fun timelineWithIdenticalTimestampsPreservesStableArrivalOrder() = runTest {
        val repository = object : RecordingRepository() {
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(
                listOf(
                    TimelineMessage(id = "msg_u1", sender = "user", parts = listOf(MessagePart.Text("msg1")), timestamp = 1789892646000L),
                    TimelineMessage(id = "msg_u2", sender = "user", parts = listOf(MessagePart.Text("msg2")), timestamp = 1789892646000L),
                    TimelineMessage(id = "msg_a1", sender = "assistant", parts = listOf(MessagePart.Text("reply")), timestamp = 1789892646000L),
                ),
                null,
            )
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_identical_ts")
        advanceUntilIdle()

        val entries = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals(3, entries.size)
        assertEquals("msg_u1", entries[0].key)
        assertEquals("msg_u2", entries[1].key)
        assertEquals("msg_a1", entries[2].key)
    }

    @Test fun reloadTimelineWithHistoricalConfirmedRepliesDoesNotKillCurrentStreamingReply() = runTest {
        val events = kotlinx.coroutines.flow.MutableSharedFlow<VerifiedConversationEvent>()
        val historicalMessages = listOf(
            TimelineMessage(
                id = "msg_turn1_user",
                sender = "user",
                parts = listOf(MessagePart.Text("第一轮问题")),
                timestamp = 1000L,
                state = "CONFIRMED",
                conversationId = ConversationId("conv_multi_turn"),
            ),
            TimelineMessage(
                id = "msg_turn1_assistant",
                sender = "assistant",
                parts = listOf(MessagePart.Text("第一轮助手的已确认回复")),
                timestamp = 1500L,
                state = "CONFIRMED",
                conversationId = ConversationId("conv_multi_turn"),
            ),
        )
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = events
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(historicalMessages, null)
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_multi_turn")
        runCurrent()

        // Verify Turn 1 is loaded
        val initialEntries = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals(2, initialEntries.size)

        // User asks Turn 2 question
        val turn2UserTs = 5000L
        events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_turn2_user",
                occurredAt = turn2UserTs,
                revision = 1L,
                message = TimelineMessage(
                    id = "msg_turn2_user",
                    sender = "user",
                    parts = listOf(MessagePart.Text("第二轮新问题")),
                    timestamp = turn2UserTs,
                    state = "CONFIRMED",
                    conversationId = ConversationId("conv_multi_turn"),
                ),
            ),
        )
        runCurrent()

        // Turn 2 assistant reply starts STREAMING
        val turn2StreamTs = 5100L
        events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_turn2_stream",
                occurredAt = turn2StreamTs,
                revision = 2L,
                message = TimelineMessage(
                    id = "stream_turn2_reply",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("正在生成第二轮的深度思考内容...")),
                    timestamp = turn2StreamTs,
                    state = "STREAMING",
                    conversationId = ConversationId("conv_multi_turn"),
                ),
            ),
        )
        runCurrent()

        assertEquals("流式消息到达时状态必须为 RUNNING", GenerationState.RUNNING, controller.state.value.generation)
        val preReloadEntries = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals(4, preReloadEntries.size)
        assertTrue("第二轮流式消息必须存在", preReloadEntries.any { it.key == "stream_turn2_reply" && it.isStreaming })

        // Trigger reloadTimeline (as happens on watchdog fallback, snapshot invalidation, or reconnection)
        controller.retryTimeline()
        runCurrent()

        // Check that Turn 2 streaming message was NOT wiped out by Turn 1 historical confirmed message!
        val postReloadEntries = (controller.state.value.timeline as Loadable.Ready).value
        val streamingEntry = postReloadEntries.find { it.key == "stream_turn2_reply" }
        assertNotNull("历史消息拉取绝不能误杀当前活跃轮次的流式回复", streamingEntry)
        assertTrue(streamingEntry!!.isStreaming)
        assertEquals("正在生成第二轮的深度思考内容...", streamingEntry.text)
        assertEquals("流式消息未被误杀，生成状态应保持 RUNNING 而不是提前变为 COMPLETED", GenerationState.RUNNING, controller.state.value.generation)
        controller.cancel()
    }

    @Test fun streamingReplyStartingWithPreviousConfirmedStepTextIsNotPruned() = runTest {
        val events = kotlinx.coroutines.flow.MutableSharedFlow<VerifiedConversationEvent>()
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = events
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_prefix")
        runCurrent()

        // User message
        events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_u",
                occurredAt = 1000L,
                revision = 1L,
                message = TimelineMessage(id = "msg_u", sender = "user", parts = listOf(MessagePart.Text("查询数据")), timestamp = 1000L),
            ),
        )
        runCurrent()

        // 1. Confirmed step message: "Step 1: 查询成功"
        events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_step1",
                occurredAt = 1010L,
                revision = 2L,
                message = TimelineMessage(
                    id = "msg_step1",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("Step 1: 查询成功")),
                    timestamp = 1010L,
                    state = "CONFIRMED",
                    conversationId = ConversationId("conv_prefix"),
                ),
            ),
        )
        runCurrent()

        // 2. Next streaming message starts with the previous text as prefix:
        // "Step 1: 查询成功，正在执行 Step 2..." (stream.text.startsWith(confirmed.text) is true)
        events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_step2_stream",
                occurredAt = 1020L,
                revision = 3L,
                message = TimelineMessage(
                    id = "stream_step2",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("Step 1: 查询成功，正在执行 Step 2...")),
                    timestamp = 1020L,
                    state = "STREAMING",
                    conversationId = ConversationId("conv_prefix"),
                ),
            ),
        )
        runCurrent()

        val entries = (controller.state.value.timeline as Loadable.Ready).value
        val streamingEntry = entries.find { it.key == "stream_step2" }
        assertNotNull("后续流式文本以已确认步骤为前缀时，绝不能被危险前缀匹配误删", streamingEntry)
        assertEquals("Step 1: 查询成功，正在执行 Step 2...", streamingEntry!!.text)
        assertTrue(streamingEntry.isStreaming)
        controller.cancel()
    }

    @Test fun identicalAssistantReplyInSubsequentTurnWithin60sIsNotPruned() = runTest {
        val events = kotlinx.coroutines.flow.MutableSharedFlow<VerifiedConversationEvent>()
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = events
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_multi_turn")
        runCurrent()

        // Turn 1: User asks
        events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_u1",
                occurredAt = 1000L,
                revision = 1L,
                message = TimelineMessage(id = "msg_u1", sender = "user", parts = listOf(MessagePart.Text("你好")), timestamp = 1000L),
            ),
        )
        runCurrent()

        // Turn 1: Assistant replies "好的" (confirmed)
        events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_a1",
                occurredAt = 1010L,
                revision = 2L,
                message = TimelineMessage(
                    id = "msg_a1",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("好的")),
                    timestamp = 1010L,
                    state = "CONFIRMED",
                    conversationId = ConversationId("conv_multi_turn"),
                ),
            ),
        )
        runCurrent()

        // Turn 2: User asks "在吗"
        events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_u2",
                occurredAt = 1020L,
                revision = 3L,
                message = TimelineMessage(id = "msg_u2", sender = "user", parts = listOf(MessagePart.Text("在吗")), timestamp = 1020L),
            ),
        )
        runCurrent()

        // Turn 2: Assistant replies "好的" (confirmed, within 60s of previous reply)
        events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_a2",
                occurredAt = 1030L,
                revision = 4L,
                message = TimelineMessage(
                    id = "msg_a2",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("好的")),
                    timestamp = 1030L,
                    state = "CONFIRMED",
                    conversationId = ConversationId("conv_multi_turn"),
                ),
            ),
        )
        runCurrent()

        val entries = (controller.state.value.timeline as Loadable.Ready).value
        val a1Entry = entries.find { it.key == "msg_a1" }
        val a2Entry = entries.find { it.key == "msg_a2" }
        assertNotNull("第 1 轮的助手回复 msg_a1 绝不能在第 2 轮收到相同内容时被误杀删除", a1Entry)
        assertNotNull("第 2 轮的助手回复 msg_a2 必须正常显示", a2Entry)
        assertEquals(4, entries.size)
        controller.cancel()
    }

    @Test fun oldConfirmedEventWhileQueuedDoesNotPrematurelyCompleteGeneration() = runTest {
        val events = kotlinx.coroutines.flow.MutableSharedFlow<VerifiedConversationEvent>()
        val repository = object : RecordingRepository() {
            override fun observeEvents(scope: ConversationScope) = events
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        }
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_queued_guard")
        runCurrent()

        // User edits draft and sends
        controller.editDraft("新问题")
        controller.sendDraft()
        runCurrent()

        assertEquals("发送后状态应为 QUEUED", GenerationState.QUEUED, controller.state.value.generation)

        // Late confirmed event from a historical turn arrives (timestamp 500L, before user's question)
        events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_historical",
                occurredAt = 500L,
                revision = 1L,
                message = TimelineMessage(
                    id = "msg_historical",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("历史旧回复")),
                    timestamp = 500L,
                    state = "CONFIRMED",
                    conversationId = ConversationId("conv_queued_guard"),
                ),
            ),
        )
        runCurrent()

        assertEquals(
            "收到历史轮次的 confirmed 消息绝不能将当前处于 QUEUED 态的新轮次误置为 COMPLETED",
            GenerationState.QUEUED,
            controller.state.value.generation,
        )
        controller.cancel()
    }

    private fun TestScope.controller(
        repository: RecordingRepository,
        replyTimeouts: WorkbenchController.ReplyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
    ) = WorkbenchController(
        this, repository,
        object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        },
        { ConversationScope("profile", "gateway", "account", "install") },
        // A virtual clock would run the reply watchdog's real minutes instantly,
        // so the watchdog is off here and covered by its own tests instead.
        replyTimeouts = replyTimeouts,
    )

    private open class RecordingRepository : ConversationRepository, GenerationTracker {
        var creates = 0
        var failCreate = false
        val sent = mutableListOf<OutgoingMessage>()
        override val generationId = kotlinx.coroutines.flow.MutableStateFlow<String?>("gen_test_id")
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
        override suspend fun cancelGeneration(generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.CANCELLED, "已停止生成")
    }
}
