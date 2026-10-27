package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 prompt 状态推进：助手开始输出后，先前处于 QUEUED 的已受理本地 prompt 自动推进为 DELIVERED。
 *
 * 原生会话下工作台收不到网关的 conversation.message.status，user 气泡状态只来自本地发送原子；
 * 若不在首个助手帧到达时推进，气泡会整轮停在"等待 Agent 接收"。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchPromptDeliveryTest {

    @Test
    fun streamingReplyPromotesThePromptToDelivered() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("查看设备状态")
        controller.sendDraft()
        runCurrent()
        val prompt = rows(controller).first { it.isUser }
        assertEquals(AgentMessageStatus.QUEUED, prompt.messageStatus)

        repository.generationId.value = "gen_a"
        repository.events.emit(stream("progress", "gen_a", "正在读取"))
        runCurrent()
        // 助手已经开始输出 => 这条 prompt 已被 Agent 接收。
        assertEquals(AgentMessageStatus.DELIVERED, rows(controller).first { it.isUser }.messageStatus)
        assertFalse(rows(controller).first { it.isUser }.pendingAcceptance)

        repository.events.emit(status(prompt, AgentMessageStatus.COMPLETED))
        runCurrent()
        // 本地推进不写 statusRevision，服务端终态仍按 revision 生效。
        assertEquals(AgentMessageStatus.COMPLETED, rows(controller).first { it.isUser }.messageStatus)
        controller.close()
    }

    @Test
    fun alreadySettledPromptIsNotDowngradedByALaterReplyFrame() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("查看设备状态")
        controller.sendDraft()
        runCurrent()
        val prompt = rows(controller).first { it.isUser }
        repository.generationId.value = "gen_a"
        repository.events.emit(status(prompt, AgentMessageStatus.COMPLETED))
        runCurrent()
        assertEquals(AgentMessageStatus.COMPLETED, rows(controller).first { it.isUser }.messageStatus)

        // 同一轮迟到的确认帧会再次触发推进尝试：已结算的 prompt 不能被降级。
        repository.events.emit(stream("late", "gen_a", "迟到的确认帧", state = "CONFIRMED"))
        runCurrent()
        assertEquals(AgentMessageStatus.COMPLETED, rows(controller).first { it.isUser }.messageStatus)
        controller.close()
    }

    private fun rows(controller: WorkbenchController) = (controller.state.value.timeline as Loadable.Ready).value

    private var eventNumber = 0

    private fun stream(id: String, generation: String?, text: String, state: String = "STREAMING") = VerifiedConversationEvent.TimelineUpsert(
        "event_${++eventNumber}_$id", 2, 1,
        TimelineMessage(id, "assistant", listOf(MessagePart.Text(text)), 2, state,
            ConversationId("conv_a"), generationId = generation),
    )

    /** 终态事件必须带真实的消息身份：sendDraft 生成的 clientMessageId 与 fixture 不同。 */
    private fun status(row: TimelineEntry, status: AgentMessageStatus, generation: String? = "gen_a") = VerifiedConversationEvent.MessageStatus(
        "status_${++eventNumber}_${row.key}", 4, ConversationId("conv_a"), row.key,
        row.clientMessageId ?: ClientMessageId("cm_${row.key}"), status, 3,
        if (status == AgentMessageStatus.FAILED) AgentMessageErrorCode.AGENT_UNAVAILABLE else null, generation,
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
