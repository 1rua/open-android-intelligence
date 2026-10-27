package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 回归两个现场问题：
 *
 * 1. 收到回复后，回复下方偶尔多出一条"上一轮消息"，切会话后消失；
 * 2. 原生会话下"等待 Agent 接收"必须等到首个 token，网关的 delivered 到不了工作台。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchDuplicateAndDeliveryTest {

    @Test
    fun aPlatformUserRowWithAnotherIdCollapsesIntoTheAcceptedRow() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("在吗在吗")
        controller.sendDraft()
        runCurrent()
        val accepted = rows(controller).first { it.isUser }

        repository.generationId.value = "gen_a"
        // 回复用真实时钟且晚于发送时刻：这样"平台行时间戳更晚"才能体现排序影响。
        repository.events.emit(stream("reply", "gen_a", "在的", timestamp = accepted.timestamp + 60_000L))
        runCurrent()
        // 同一条 prompt 又以上报端 id 到达，且时间戳更晚：不能变成回复下方的第二条气泡。
        repository.events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                "user_platform", 9, 2,
                TimelineMessage(
                    "user_from_platform", "user", listOf(MessagePart.Text("在吗在吗")),
                    accepted.timestamp + 5_000L, "CONFIRMED", ConversationId("conv_a"),
                    clientMessageId = accepted.clientMessageId,
                ),
            ),
        )
        runCurrent()

        val rendered = rows(controller)
        assertEquals(1, rendered.count { it.isUser })
        val user = rendered.first { it.isUser }
        val reply = rendered.first { it.text == "在的" }
        // 只保留一行，并沿用发送时刻：不会再跑到回复下方。
        assertEquals(accepted.clientMessageId, user.clientMessageId)
        assertTrue("用户消息必须仍在回复之上", user.timestamp < reply.timestamp)
        controller.close()
    }

    @Test
    fun duplicateUserRowsAreCollapsedEvenWhenNotAdjacent() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        runCurrent()
        repository.generationId.value = "gen_a"
        repository.events.emit(stream("reply", "gen_a", "回答"))
        runCurrent()
        val clientId = ClientMessageId("cm_repeated")
        repository.events.emit(userUpsert("u_early", clientId, "重复的问题", 100L))
        runCurrent()
        repository.events.emit(userUpsert("u_late", clientId, "重复的问题", 900_000L))
        runCurrent()

        assertEquals(1, rows(controller).count { it.isUser })
        controller.close()
    }

    @Test
    fun gatewayDeliveredEventPromotesThePromptWithoutBlockingTheTerminalStatus() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("查看设备状态")
        controller.sendDraft()
        runCurrent()
        val prompt = rows(controller).first { it.isUser }
        assertEquals(AgentMessageStatus.QUEUED, prompt.messageStatus)

        repository.generationId.value = "gen_a"
        controller.onGatewayDeliveryStatus(
            VerifiedConversationEvent.MessageStatus(
                "gw_delivered", 5, ConversationId("conv_a"), "server_side_id",
                prompt.clientMessageId!!, AgentMessageStatus.DELIVERED, 1, null, "gen_a",
            ),
        )
        runCurrent()
        assertEquals(AgentMessageStatus.DELIVERED, rows(controller).first { it.isUser }.messageStatus)

        // 平台事件不写 statusRevision，会话流的终态（revision 1）仍必须生效。
        repository.events.emit(
            VerifiedConversationEvent.MessageStatus(
                "native_completed", 6, ConversationId("conv_a"), prompt.key,
                prompt.clientMessageId!!, AgentMessageStatus.COMPLETED, 1, null, "gen_a",
            ),
        )
        runCurrent()
        assertEquals(AgentMessageStatus.COMPLETED, rows(controller).first { it.isUser }.messageStatus)
        controller.close()
    }

    private fun userUpsert(id: String, clientId: ClientMessageId, text: String, timestamp: Long) =
        VerifiedConversationEvent.TimelineUpsert(
            "evt_$id", timestamp, 1,
            TimelineMessage(
                id, "user", listOf(MessagePart.Text(text)), timestamp, "CONFIRMED",
                ConversationId("conv_a"), clientMessageId = clientId,
            ),
        )

    private fun rows(controller: WorkbenchController) = (controller.state.value.timeline as Loadable.Ready).value

    private var eventNumber = 0

    private fun stream(
        id: String,
        generation: String?,
        text: String,
        state: String = "STREAMING",
        timestamp: Long = 2L,
    ) = VerifiedConversationEvent.TimelineUpsert(
        "event_${++eventNumber}_$id", timestamp, 1,
        TimelineMessage(id, "assistant", listOf(MessagePart.Text(text)), timestamp, state,
            ConversationId("conv_a"), generationId = generation),
    )

    private fun TestScope.controller(repository: Repository) = WorkbenchController(
        workbenchTestScope(), repository, object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) =
                AgentCommandCatalog(CatalogVersion("v1"), emptyList())
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
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
            Conversation(ConversationId("conv_new"), "新对话", 1)
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        override suspend fun submitMessage(message: OutgoingMessage) =
            MessageAcceptance("server_side_id", message.clientMessageId.value)
        override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(
            batch.batchId, batch.messages.associate { it.clientMessageId.value to "server_side_id" },
        )
        override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> = events
        override suspend fun cancelGeneration(generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        override suspend fun cancelGeneration(conversationId: String, generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
