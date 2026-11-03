package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.workbench.ExecutionStep
import com.openandroidintelligence.conversation.workbench.TimelineDisplayItem
import com.openandroidintelligence.conversation.workbench.TimelineDisplayModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchNativeExecutionEventsTest {
    @Test fun offlineCompletedHistoryDoesNotReopenAnUnfinishedToolStep() = runTest {
        val call = step("old-call", 1, NativeExecutionStatus.RUNNING).copy(sourceMessageId = "old-tool", occurredAt = 150L)
        val host = Host().apply {
            generationId.value = null
            generationState.value = GenerationState.IDLE
            history = listOf(
                TimelineMessage("user-a", "user", listOf(MessagePart.Text("历史问题")), 100, state = "completed",
                    conversationId = ConversationId("a"), clientMessageId = ClientMessageId("client-a"), generationId = "old"),
                TimelineMessage("old-tool", "assistant", emptyList(), 150, conversationId = ConversationId("a"),
                    generationId = "old", executionSteps = listOf(call)),
            )
        }
        val controller = controller(host)
        try {
            runCurrent(); controller.openThread("a"); runCurrent()
            val run = runs(controller).single()
            assertFalse("历史原提问已有权威终态，不能因工具缺结果重新运行", run.isStreaming)
            assertEquals(NativeExecutionStatus.UNKNOWN, (run.steps.single() as ExecutionStep.ToolCall).block.executionStatus)
        } finally { controller.close() }
    }

    @Test fun resultWithoutOptionalSourceFieldsKeepsTheOriginalCallPosition() = runTest {
        val host = Host()
        val controller = controller(host)
        try {
            runCurrent(); controller.openThread("a"); runCurrent()
            val call = step("call", 1, NativeExecutionStatus.RUNNING).copy(sourceMessageId = "source", occurredAt = 150L)
            host.events.emit(update("g", 1, call)); runCurrent()
            host.events.emit(VerifiedConversationEvent.TimelineUpsert("steer", 200, 1,
                TimelineMessage("steer", "user", listOf(MessagePart.Text("继续")), 200, conversationId = ConversationId("a"),
                    clientMessageId = ClientMessageId("steer"), generationId = "g")))
            runCurrent()
            val key = rows(controller).single { it.executionFact }.key
            host.events.emit(update("g", 2, step("call", 2))); runCurrent()
            val fact = rows(controller).single { it.executionFact }
            assertEquals(key, fact.key)
            assertEquals("source", fact.executionSteps.single().sourceMessageId)
            assertEquals(150L, fact.executionSteps.single().occurredAt)
            assertEquals("result", fact.executionSteps.single().output)
            assertTrue(rows(controller).indexOf(fact) < rows(controller).indexOfFirst { it.key == "steer" })
        } finally { controller.close() }
    }

    @Test fun failedGenerationRemainsFailedWhenAnotherGenerationStarts() = runTest {
        val host = Host()
        val controller = controller(host)
        try {
            runCurrent(); controller.openThread("a"); runCurrent()
            host.events.emit(update("g", 1, step("failed", 1, NativeExecutionStatus.FAILED))); runCurrent()
            host.generationState.value = GenerationState.FAILED; runCurrent()
            host.generationId.value = "new-generation"
            host.generationState.value = GenerationState.RUNNING; runCurrent()
            assertEquals(GenerationState.FAILED, rows(controller).single { it.executionFact }.executionGenerationState)
            assertEquals(GenerationState.RUNNING, controller.state.value.generation)
        } finally { controller.close() }
    }

    @Test fun terminalReceiptForSteeringInputCannotFinishTheOriginalGeneration() = runTest {
        val host = Host()
        val controller = controller(host)
        try {
            runCurrent(); controller.openThread("a"); runCurrent()
            controller.editDraft("继续原任务")
            controller.sendDraft(MessageDeliveryMode.STEER); runCurrent()
            val input = rows(controller).single { it.text == "继续原任务" }
            host.events.emit(update("g", 1, step("before", 1, NativeExecutionStatus.RUNNING)))
            host.events.emit(VerifiedConversationEvent.MessageStatus("steering-delivered", 200, ConversationId("a"),
                "steering-native", requireNotNull(input.clientMessageId), AgentMessageStatus.COMPLETED, 1, null, "g"))
            runCurrent()
            assertEquals(GenerationState.RUNNING, controller.state.value.generation)
            assertEquals(GenerationState.RUNNING, rows(controller).single { it.executionFact }.executionGenerationState)
        } finally { controller.close() }
    }

    @Test fun splitLiveExecutionSegmentsShareOnlyTheirOwnGenerationLifecycle() = runTest {
        val host = Host()
        val controller = controller(host)
        try {
            runCurrent(); controller.openThread("a"); runCurrent()
            val before = step("before", 1).copy(sourceMessageId = "source-before", occurredAt = 150L)
            val after = step("after", 2, NativeExecutionStatus.RUNNING).copy(sourceMessageId = "source-after", occurredAt = 250L)
            host.events.emit(update("g", 1, before)); runCurrent()
            host.events.emit(VerifiedConversationEvent.TimelineUpsert("steer", 200, 1,
                TimelineMessage("steer", "user", listOf(MessagePart.Text("继续")), 200, conversationId = ConversationId("a"),
                    clientMessageId = ClientMessageId("steer"), generationId = "g")))
            host.events.emit(update("g", 2, before, after)); runCurrent()
            assertEquals(listOf(GenerationState.RUNNING, GenerationState.RUNNING),
                rows(controller).filter { it.executionFact }.map { it.executionGenerationState })
            val keys = rows(controller).filter { it.executionFact }.map { it.key }
            host.generationState.value = GenerationState.COMPLETED; runCurrent()
            assertEquals(listOf(GenerationState.COMPLETED, GenerationState.COMPLETED),
                rows(controller).filter { it.executionFact }.map { it.executionGenerationState })
            host.generationId.value = "new-generation"
            host.generationState.value = GenerationState.RUNNING; runCurrent()
            assertEquals(keys, rows(controller).filter { it.executionFact }.map { it.key })
            assertEquals(listOf(GenerationState.COMPLETED, GenerationState.COMPLETED),
                rows(controller).filter { it.executionFact }.map { it.executionGenerationState })
        } finally { controller.close() }
    }

    @Test fun liveResultUpdatesTheExistingHistoricalToolWithoutADuplicateCardOrPositionChange() = runTest {
        val host = Host()
        val controller = controller(host)
        try {
            runCurrent(); controller.openThread("a"); runCurrent()
            val call = step("call", 1, NativeExecutionStatus.RUNNING).copy(sourceMessageId = "tool-source", occurredAt = 150L)
            host.events.emit(VerifiedConversationEvent.TimelineUpsert("tool-history", 150, 1,
                TimelineMessage("tool-source", "assistant", emptyList(), 150, conversationId = ConversationId("a"),
                    generationId = "g", executionSteps = listOf(call))))
            host.events.emit(update("g", 2, call.copy(revision = 2, output = "completed result", status = NativeExecutionStatus.COMPLETED)))
            runCurrent()
            val tool = runs(controller).single().steps.single() as ExecutionStep.ToolCall
            assertEquals("completed result", tool.block.output)
            assertEquals(NativeExecutionStatus.COMPLETED, tool.block.executionStatus)
            assertEquals(1, rows(controller).count { it.executionSteps.any { step -> step.stepId == "call" } })
            assertEquals("tool-source", rows(controller).last().key)
        } finally { controller.close() }
    }

    @Test fun liveToolSourcesStayOnTheirOwnSidesOfSteeringAcrossSnapshots() = runTest {
        val host = Host()
        val controller = controller(host)
        try {
            runCurrent(); controller.openThread("a"); runCurrent()
            val before = step("before", 1).copy(sourceMessageId = "tool-before", occurredAt = 150L)
            val after = step("after", 2, NativeExecutionStatus.RUNNING).copy(sourceMessageId = "tool-after", occurredAt = 250L)
            host.events.emit(update("g", 1, before)); runCurrent()
            host.events.emit(VerifiedConversationEvent.TimelineUpsert("steering-user", 200, 1,
                TimelineMessage("steering-user", "user", listOf(MessagePart.Text("继续检查")), 200,
                    conversationId = ConversationId("a"), clientMessageId = ClientMessageId("steering-client"), generationId = "g")))
            host.events.emit(update("g", 2, before, after)); runCurrent()
            val timeline = rows(controller)
            assertEquals(listOf("before", "steering-user", "after"), timeline.drop(1).map {
                if (it.executionFact) it.executionSteps.joinToString(",") { step -> step.stepId } else it.key
            })
            val keys = timeline.filter { it.executionFact }.map { it.key }
            host.events.emit(update("g", 3, before, after.copy(revision = 3, status = NativeExecutionStatus.COMPLETED, output = "finished")))
            runCurrent()
            assertEquals(keys, rows(controller).filter { it.executionFact }.map { it.key })
            assertEquals("finished", rows(controller).last().executionSteps.single().output)
        } finally { controller.close() }
    }

    @Test fun liveStepsProduceOneCardBeforeTheRealAnswerWithoutPersistingAFakeMessage() = runTest {
        val host = Host()
        val storage = MemoryPersistence()
        val controller = controller(host, storage)
        try {
            runCurrent(); controller.openThread("a"); runCurrent()
            host.events.emit(update("g", 1, step("one", 1, NativeExecutionStatus.RUNNING)))
            runCurrent()
            val fact = rows(controller).single { it.executionFact }
            assertEquals("system", fact.sender)
            assertEquals("g", fact.generationId)
            assertTrue("A tool event cannot create an Assistant message identity", rows(controller).none { it.sender == "assistant" })
            assertTrue("View-only execution rows cannot enter authoritative message persistence",
                storage.checkpoint?.messages.orEmpty().none { it.executionSteps.isNotEmpty() })
            assertTrue(runs(controller).single().isStreaming)

            host.events.emit(VerifiedConversationEvent.TimelineUpsert("answer-event", 200, 1,
                TimelineMessage("real-answer", "assistant", listOf(MessagePart.Text("真实答案")), 200,
                    generationId = "g", conversationId = ConversationId("a"), contentRevision = 1)))
            host.events.emit(update("g", 2, step("one", 2), step("two", 2)))
            runCurrent()
            val display = TimelineDisplayModel().build(rows(controller), controller.state.value.generation)
            assertEquals(1, display.filterIsInstance<TimelineDisplayItem.Execution>().size)
            assertEquals(2, runs(controller).single().stepCount)
            val answer = display.indexOfFirst { it is TimelineDisplayItem.Single && it.entry.key == "real-answer" }
            val execution = display.indexOfFirst { it is TimelineDisplayItem.Execution }
            assertTrue(execution >= 0 && execution < answer)
            assertEquals(listOf("real-answer"), rows(controller).filter { it.sender == "assistant" }.map { it.key })
        } finally { controller.close() }
    }

    @Test fun executionVersionsDoNotSuppressContentAndLateSnapshotsCannotReopenFinishedSteps() = runTest {
        val host = Host()
        val controller = controller(host)
        try {
            runCurrent(); controller.openThread("a"); runCurrent()
            host.events.emit(update("g", 20, step("one", 20)))
            host.events.emit(VerifiedConversationEvent.TimelineUpsert("answer-content-one", 200, 1,
                TimelineMessage("answer", "assistant", listOf(MessagePart.Text("正文版本一")), 200,
                    generationId = "g", conversationId = ConversationId("a"), contentRevision = 1)))
            host.events.emit(update("g", 19, step("one", 19, NativeExecutionStatus.RUNNING)))
            runCurrent()
            assertEquals("正文版本一", rows(controller).single { it.key == "answer" }.text)
            val step = runs(controller).single().steps.single() as ExecutionStep.ToolCall
            assertEquals(NativeExecutionStatus.COMPLETED, step.block.executionStatus)
            assertTrue("原任务仍执行；已完成的步骤不能结束整个任务", runs(controller).single().isStreaming)
            assertFalse((runs(controller).single().steps.single() as ExecutionStep.ToolCall).entryIsStreaming)
        } finally { controller.close() }
    }

    @Test fun stepsKeepTheirConversationAndPromptAnchorAcrossThreadSwitches() = runTest {
        val host = Host()
        val controller = controller(host)
        try {
            runCurrent(); controller.openThread("a"); runCurrent()
            controller.openThread("b"); runCurrent()
            host.events.emit(update("old", 1, step("one", 1, NativeExecutionStatus.RUNNING)))
            runCurrent()
            assertTrue("A's tool facts cannot enter B", rows(controller).none { it.executionFact })
            controller.openThread("a"); runCurrent()
            host.events.emit(VerifiedConversationEvent.TimelineUpsert("next-user", 200, 1,
                TimelineMessage("user-next", "user", listOf(MessagePart.Text("下一轮问题")), 200,
                    conversationId = ConversationId("a"), clientMessageId = ClientMessageId("next"))))
            runCurrent()
            val timeline = rows(controller)
            assertEquals("old", timeline.single { it.executionFact }.generationId)
            assertTrue("A late observation keeps the exact original prompt anchor",
                timeline.indexOfFirst { it.executionFact } < timeline.indexOfFirst { it.key == "user-next" })
        } finally { controller.close() }
    }

    @Test fun terminalGenerationShowsUnfinishedStepsAsUnknownWithoutClosingANewerRun() = runTest {
        val host = Host()
        val controller = controller(host)
        try {
            runCurrent(); controller.openThread("a"); runCurrent()
            host.events.emit(update("old", 1, step("one", 1, NativeExecutionStatus.RUNNING)))
            runCurrent()
            host.generationId.value = "new"
            host.generationState.value = GenerationState.RUNNING
            runCurrent()
            host.events.emit(VerifiedConversationEvent.MessageStatus("old-terminal", 300, ConversationId("a"),
                "user-a", ClientMessageId("client-a"), AgentMessageStatus.COMPLETED, 2, null, "old"))
            runCurrent()
            val oldRun = runs(controller).single()
            assertFalse(oldRun.isStreaming)
            assertEquals(NativeExecutionStatus.UNKNOWN, (oldRun.steps.single() as ExecutionStep.ToolCall).block.executionStatus)
            assertEquals(GenerationState.RUNNING, controller.state.value.generation)
            assertEquals("new", host.generationId.value)
            host.events.emit(update("old", 2, step("one", 2)))
            runCurrent()
            assertEquals(NativeExecutionStatus.COMPLETED,
                (runs(controller).single().steps.single() as ExecutionStep.ToolCall).block.executionStatus)
            assertEquals(GenerationState.RUNNING, controller.state.value.generation)
        } finally { controller.close() }
    }

    @Test fun newerSnapshotsCannotReassignThePromptOrReopenACompletedStep() = runTest {
        val host = Host()
        val controller = controller(host)
        try {
            runCurrent(); controller.openThread("a"); runCurrent()
            host.events.emit(update("g", 1, step("one", 1)))
            host.events.emit(update("g", 2, step("one", 2, NativeExecutionStatus.RUNNING)))
            host.events.emit(update("g", 3, step("other", 3)).copy(clientMessageId = "another-client"))
            runCurrent()
            val run = runs(controller).single()
            assertEquals(1, run.stepCount)
            assertEquals(NativeExecutionStatus.COMPLETED,
                (run.steps.single() as ExecutionStep.ToolCall).block.executionStatus)
            assertEquals("client-a", rows(controller).single { it.executionFact }.clientMessageId?.value)
            assertTrue("原任务仍执行；完成步骤不等于任务完成", run.isStreaming)
            assertFalse((run.steps.single() as ExecutionStep.ToolCall).entryIsStreaming)
        } finally { controller.close() }
    }

    @Test fun terminalArrivingBeforeStepsKeepsLateStartsUnknownAndDeletionClearsFacts() = runTest {
        val host = Host()
        val controller = controller(host)
        try {
            runCurrent(); controller.openThread("a"); runCurrent()
            host.events.emit(VerifiedConversationEvent.MessageStatus("done-before-tools", 300, ConversationId("a"),
                "user-a", ClientMessageId("client-a"), AgentMessageStatus.COMPLETED, 2, null, "old"))
            host.events.emit(update("old", 1, step("one", 1, NativeExecutionStatus.RUNNING)))
            runCurrent()
            assertEquals(NativeExecutionStatus.UNKNOWN,
                (runs(controller).single().steps.single() as ExecutionStep.ToolCall).block.executionStatus)
            host.events.emit(VerifiedConversationEvent.ConversationsChanged("delete-a", 400, ConversationId("a"), deleted = true))
            runCurrent()
            assertTrue(rows(controller).none { it.executionFact })
        } finally { controller.close() }
    }

    private fun step(id: String, revision: Long, status: NativeExecutionStatus = NativeExecutionStatus.COMPLETED) =
        NativeExecutionStep(id, "step-$id-$revision", "read_file", "{}", if (status == NativeExecutionStatus.COMPLETED) "result" else null,
            status, revision)

    private fun update(generation: String, revision: Long, vararg steps: NativeExecutionStep) =
        VerifiedConversationEvent.ExecutionStepsUpdated("execution-$generation-$revision", 300, ConversationId("a"),
            generation, revision, steps.toList(), "client-a", "user-a")

    private fun rows(controller: WorkbenchController) = (controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty()
    private fun runs(controller: WorkbenchController) = TimelineDisplayModel().build(rows(controller), controller.state.value.generation)
        .filterIsInstance<TimelineDisplayItem.Execution>().map { it.run }

    private fun TestScope.controller(host: Host, storage: WorkbenchPersistence? = null) = WorkbenchController(
        this, host, object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("test"), emptyList())
        }, { ConversationScope("profile", "gateway", "account", "install") },
        persistence = storage, persistenceDispatcher = UnconfinedTestDispatcher(testScheduler), authoritativeMessageIds = true,
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
    )

    private class Host : ConversationRepository, GenerationTracker {
        var history: List<TimelineMessage>? = null
        val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 32)
        override val generationId = MutableStateFlow<String?>("g")
        override val generationState = MutableStateFlow(GenerationState.RUNNING)
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(
            listOf("a", "b").map { ConversationSummary(ConversationId(it), it, 0) }, null)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) = error("not creating")
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(history ?: listOf(
            TimelineMessage("user-$conversationId", "user", listOf(MessagePart.Text("问题")), 100,
                conversationId = ConversationId(conversationId), clientMessageId = ClientMessageId("client-$conversationId"))), null)
        override suspend fun steerMessage(conversationId: String, message: OutgoingMessage) =
            MessageAcceptance(message.clientMessageId.value, message.clientMessageId.value, "g", MessageDeliveryMode.STEER, authoritative = false)
        override suspend fun submitMessage(message: OutgoingMessage) = error("tool observations cannot send messages")
        override suspend fun submitBatch(batch: MessageBatch) = error("tool observations cannot send batches")
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        override fun observeEvents(scope: ConversationScope) = events
    }

    private class MemoryPersistence : WorkbenchPersistence {
        var checkpoint: WorkbenchCheckpoint? = null
        private val atoms = mutableMapOf<String, List<LocalSendAtom>>()
        override fun load() = checkpoint
        override fun save(checkpoint: WorkbenchCheckpoint) { this.checkpoint = checkpoint }
        override fun loadSendAtoms(conversationId: String) = atoms[conversationId].orEmpty()
        override fun saveSendAtoms(conversationId: String, atoms: List<LocalSendAtom>) { this.atoms[conversationId] = atoms }
    }
}
