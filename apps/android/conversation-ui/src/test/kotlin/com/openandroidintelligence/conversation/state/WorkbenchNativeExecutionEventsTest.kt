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
            assertFalse(runs(controller).single().isStreaming)
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
            assertFalse(run.isStreaming)
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
        val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 32)
        override val generationId = MutableStateFlow<String?>("g")
        override val generationState = MutableStateFlow(GenerationState.RUNNING)
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(
            listOf("a", "b").map { ConversationSummary(ConversationId(it), it, 0) }, null)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) = error("not creating")
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(listOf(
            TimelineMessage("user-$conversationId", "user", listOf(MessagePart.Text("问题")), 100,
                conversationId = ConversationId(conversationId), clientMessageId = ClientMessageId("client-$conversationId"))), null)
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
