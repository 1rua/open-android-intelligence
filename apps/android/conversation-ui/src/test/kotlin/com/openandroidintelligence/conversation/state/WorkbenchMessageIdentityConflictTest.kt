package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 回归现场崩溃：`java.lang.IllegalStateException: MESSAGE_ID_CONFLICT`
 * （`LocalSendAtom.accepted` → `WorkbenchController.applySendEvent`）。
 *
 * 触发条件：本地受理时原子绑定的是客户端占位 id，而宿主在最终回复的 `MessageStatus`
 * 里用服务端 id 报告同一条 prompt。事件投影必须容忍这种身份分叉。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchMessageIdentityConflictTest {

    @Test
    fun statusWithDivergentServerIdSettlesThePromptInsteadOfCrashing() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("查看设备状态")
        controller.sendDraft()
        runCurrent()
        val prompt = rows(controller).first { it.isUser }
        // 假仓库受理时返回的 id 与客户端 id 不同，正是现场的分叉形态。
        assertNotEquals(prompt.clientMessageId?.value, prompt.key)

        repository.generationId.value = "gen_a"
        repository.events.emit(
            VerifiedConversationEvent.MessageStatus(
                "status_1", 4, ConversationId("conv_a"), "server_side_id",
                prompt.clientMessageId!!, AgentMessageStatus.COMPLETED, 3, null, "gen_a",
            ),
        )
        runCurrent()

        val rows = rows(controller)
        assertEquals(1, rows.count { it.isUser })
        assertEquals(AgentMessageStatus.COMPLETED, rows.first { it.isUser }.messageStatus)
        controller.close()
    }

    @Test
    fun acceptedEventPromotesTheProvisionalIdentityBeforeTheTerminalStatus() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("查看设备状态")
        controller.sendDraft()
        runCurrent()
        val prompt = rows(controller).first { it.isUser }
        val clientId = prompt.clientMessageId!!

        // 宿主报出真实 userMessageId：本地身份应升级，随后的终态事件不再分叉。
        repository.events.emit(
            VerifiedConversationEvent.MessageAccepted("accepted_1", 2, "server_side_id", clientId.value, ConversationId("conv_a")),
        )
        runCurrent()
        repository.generationId.value = "gen_a"
        repository.events.emit(
            VerifiedConversationEvent.MessageStatus(
                "status_1", 4, ConversationId("conv_a"), "server_side_id",
                clientId, AgentMessageStatus.COMPLETED, 3, null, "gen_a",
            ),
        )
        runCurrent()

        val rows = rows(controller)
        assertEquals(1, rows.count { it.isUser })
        assertEquals(AgentMessageStatus.COMPLETED, rows.first { it.isUser }.messageStatus)
        controller.close()
    }

    private fun rows(controller: WorkbenchController) = (controller.state.value.timeline as Loadable.Ready).value

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
        // 与 CoreAgentConversationRepository 现场行为一致：受理返回的 id 不是客户端 id。
        override suspend fun submitMessage(message: OutgoingMessage) = MessageAcceptance("server_side_id", message.clientMessageId.value)
        override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(
            batch.batchId, batch.messages.associate { it.clientMessageId.value to "server_side_id" },
        )
        override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> = events
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        override suspend fun cancelGeneration(conversationId: String, generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
