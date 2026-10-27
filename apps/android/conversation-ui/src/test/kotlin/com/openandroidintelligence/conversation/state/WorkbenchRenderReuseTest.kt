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
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) = Conversation(ConversationId("conv_new"), "新对话", 1)
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
