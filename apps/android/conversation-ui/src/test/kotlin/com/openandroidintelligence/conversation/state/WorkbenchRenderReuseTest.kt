package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 验证时间线展示行渲染复用：无附件消息行在流式输出期间未变化的行直接复用旧实例，
 * 避免每收到一帧增量就全量重建全部历史条目。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchRenderReuseTest {

    @Test
    fun unchangedAssistantRowsAreReusedAcrossStreamingFrames() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        runCurrent()
        repository.generationId.value = "gen_a"
        repository.events.emit(stream("m1", "gen_a", "已经完成的回答", state = "CONFIRMED"))
        runCurrent()
        repository.events.emit(stream("m2", "gen_a", "正在流式"))
        runCurrent()
        val before = rows(controller).associateBy { it.key }

        repository.events.emit(stream("m2", "gen_a", "正在流式输出更多"))
        runCurrent()
        val after = rows(controller).associateBy { it.key }

        // 未变化的历史行复用同一 TimelineEntry 实例，流式帧不再重建整段历史。
        assertSame(before.getValue("m1"), after.getValue("m1"))
        assertNotSame(before.getValue("m2"), after.getValue("m2"))
        assertEquals("正在流式输出更多", after.getValue("m2").text)
        controller.close()
    }

    @Test
    fun 普通正文终态投影只更新一次且后续流式帧复用同一实例() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        try {
            runCurrent()
            repository.generationId.value = "gen_a"
            repository.generationState.value = GenerationState.RUNNING
            repository.events.emit(stream("m1", "gen_a", "上一轮完整回答", state = "CONFIRMED"))
            runCurrent()
            val running = rows(controller).single { it.key == "m1" }
            repository.generationState.value = GenerationState.COMPLETED
            runCurrent()
            val terminal = rows(controller).single { it.key == "m1" }
            assertNotSame("首次明确终态必须发布新投影", running, terminal)
            assertEquals(GenerationState.COMPLETED, terminal.executionGenerationState)
            repository.generationId.value = "gen_b"
            repository.generationState.value = GenerationState.RUNNING
            repository.events.emit(stream("m2", "gen_b", "新轮次流式"))
            runCurrent()
            assertSame("新轮次到来不重建旧终态正文", terminal, rows(controller).single { it.key == "m1" })
            repository.events.emit(stream("m2", "gen_b", "新轮次流式新增"))
            runCurrent()
            assertSame("新轮次每帧追加不重建旧终态正文", terminal, rows(controller).single { it.key == "m1" })
        } finally { controller.close() }
    }

    @Test
    fun 实时正文后的相同修订历史确认保留来源且重新进入会话为快照() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        runCurrent()
        val live = stream("m", "gen_a", "实时内容").copy(
            revision = 7, presentationOrigin = StreamPresentationOrigin.LIVE,
        )
        repository.events.emit(live)
        runCurrent()
        assertEquals(StreamPresentationMetadata(StreamPresentationOrigin.LIVE, 7), rows(controller).single().presentation)
        repository.events.emit(live.copy(eventId = "history_confirm", presentationOrigin = StreamPresentationOrigin.SNAPSHOT))
        runCurrent()
        assertEquals(StreamPresentationMetadata(StreamPresentationOrigin.LIVE, 7), rows(controller).single().presentation)
        repository.events.emit(live.copy(eventId = "history_append", revision = 8,
            message = live.message.copy(parts = listOf(MessagePart.Text("实时内容同步增加"))),
            presentationOrigin = StreamPresentationOrigin.SNAPSHOT))
        runCurrent()
        assertEquals(StreamPresentationMetadata(StreamPresentationOrigin.SNAPSHOT, 8), rows(controller).single().presentation)
        controller.openThread("conv_other")
        runCurrent()
        controller.openThread("conv_a")
        runCurrent()
        assertEquals(StreamPresentationOrigin.SNAPSHOT, rows(controller).single().presentation?.origin)
        controller.close()
    }

    private fun rows(controller: WorkbenchController) = (controller.state.value.timeline as Loadable.Ready).value

    private var eventNumber = 0

    private fun stream(id: String, generation: String?, text: String, state: String = "STREAMING") = VerifiedConversationEvent.TimelineUpsert(
        "event_${++eventNumber}_$id", 2, 1,
        TimelineMessage(id, "assistant", listOf(MessagePart.Text(text)), 2, state,
            ConversationId("conv_a"), generationId = generation),
    )

    private fun TestScope.controller(repository: Repository) = WorkbenchController(
        workbenchTestScope(), repository, object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        }, { ConversationScope("p", "g", "a", "i") },
        persistenceDispatcher = StandardTestDispatcher(testScheduler),
        onActiveThreadChanged = { repository.generationId.value = null; repository.generationState.value = GenerationState.IDLE },
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
    )

    private class Repository : ConversationRepository, GenerationTracker, MessageOutcomeQuery {
        override suspend fun queryMessage(conversationId: String, clientMessageId: ClientMessageId): TimelineMessage? = null
        override val generationId = MutableStateFlow<String?>(null)
        override val generationState = MutableStateFlow(GenerationState.IDLE)
        val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 16)
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(
            listOf(ConversationSummary(ConversationId("conv_a"), "A", 1)), null,
        )
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) = Conversation(ConversationId("conv_new"), "", 1)
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        override suspend fun submitMessage(message: OutgoingMessage) = MessageAcceptance("message", message.clientMessageId.value)
        override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(
            batch.batchId, batch.messages.associate { it.clientMessageId.value to "message_${it.clientMessageId.value}" },
        )
        override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> = events
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        override suspend fun cancelGeneration(conversationId: String, generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
