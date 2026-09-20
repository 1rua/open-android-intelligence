package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchTitleRegressionTest {

    @Test
    fun firstMessageGeneratesTitleAndCallsRepositoryUpdate() = runTest {
        val repository = TitleRecordingRepository()
        val controller = createController(repository)
        runCurrent()

        controller.editDraft("如何理解量子纠缠？\n这是一个物理学问题")
        controller.sendDraft()
        advanceUntilIdle()

        assertEquals("如何理解量子纠缠？", controller.state.value.activeThreadTitle)
        assertEquals("conv_test", repository.lastUpdatedConversationId)
        assertEquals("如何理解量子纠缠？", repository.lastUpdatedTitle)
        controller.cancel()
    }

    @Test
    fun manualUserRenameProtectsAgainstAgentTitleUpdatedEvents() = runTest {
        val eventFlow = MutableSharedFlow<VerifiedConversationEvent>()
        val repository = TitleRecordingRepository(eventFlow)
        val controller = createController(repository)
        runCurrent()

        controller.openThread("conv_test")
        advanceUntilIdle()

        // User manually renames thread
        controller.renameActiveThread("用户自定义会话名称")
        advanceUntilIdle()

        assertTrue(controller.isCurrentThreadUserRenamed)
        assertEquals("用户自定义会话名称", controller.state.value.activeThreadTitle)
        assertEquals("用户自定义会话名称", repository.lastUpdatedTitle)

        // Agent suggests a title update via event stream
        eventFlow.emit(
            VerifiedConversationEvent.TitleUpdated(
                eventId = "evt_1",
                occurredAt = 1000L,
                conversationId = ConversationId("conv_test"),
                newTitle = "Agent建议的标题",
            )
        )
        advanceUntilIdle()

        // User manual rename must NOT be overwritten
        assertEquals("用户自定义会话名称", controller.state.value.activeThreadTitle)
        controller.cancel()
    }

    @Test
    fun agentTitleUpdatedEventIsAppliedWhenNotUserRenamed() = runTest {
        val eventFlow = MutableSharedFlow<VerifiedConversationEvent>()
        val repository = TitleRecordingRepository(eventFlow)
        val controller = createController(repository)
        runCurrent()

        controller.openThread("conv_test")
        advanceUntilIdle()

        assertFalse(controller.isCurrentThreadUserRenamed)

        // Agent broadcasts new title
        eventFlow.emit(
            VerifiedConversationEvent.TitleUpdated(
                eventId = "evt_2",
                occurredAt = 2000L,
                conversationId = ConversationId("conv_test"),
                newTitle = "量子计算导论",
            )
        )
        advanceUntilIdle()

        // Title is updated because user hasn't renamed
        assertEquals("量子计算导论", controller.state.value.activeThreadTitle)
        controller.cancel()
    }

    @Test
    fun newCommandRetainsDefaultTitle() = runTest {
        val repository = TitleRecordingRepository()
        val controller = createController(repository)
        runCurrent()

        controller.editDraft("/new")
        controller.sendDraft()
        advanceUntilIdle()

        assertEquals("新对话", controller.state.value.activeThreadTitle)
        assertNull(repository.lastUpdatedTitle)
        controller.cancel()
    }

    /**
     * A rename the Gateway refused must not stay on screen: otherwise the phone
     * shows a title the Gateway never stored, which is exactly how the PATCH
     * rejection used to look like a working feature.
     */
    @Test
    fun manualRenameRevertsAndExplainsWhenGatewayRejectsIt() = runTest {
        val repository = TitleRecordingRepository(acceptsTitleUpdate = false)
        val controller = createController(repository)
        runCurrent()

        controller.openThread("conv_test")
        advanceUntilIdle()
        val originalTitle = controller.state.value.activeThreadTitle

        controller.renameActiveThread("用户自定义会话名称")
        advanceUntilIdle()

        assertEquals(1, repository.titleUpdates)
        assertEquals("用户自定义会话名称", repository.lastUpdatedTitle)
        assertEquals(originalTitle, controller.state.value.activeThreadTitle)
        assertFalse(controller.isCurrentThreadUserRenamed)
        assertEquals(
            "CONVERSATION_RENAME_FAILED:CONVERSATION_RENAME_REJECTED",
            controller.state.value.notice,
        )
        controller.cancel()
    }

    /** The automatic title is only kept when the Gateway actually stored it. */
    @Test
    fun automaticTitleRevertsWhenGatewayRejectsIt() = runTest {
        val repository = TitleRecordingRepository(acceptsTitleUpdate = false)
        val controller = createController(repository)
        runCurrent()

        controller.openThread("conv_test")
        advanceUntilIdle()
        controller.editDraft("如何理解量子纠缠？")
        controller.sendDraft()
        advanceUntilIdle()

        assertEquals(1, repository.titleUpdates)
        assertEquals("如何理解量子纠缠？", repository.lastUpdatedTitle)
        assertEquals("新对话", controller.state.value.activeThreadTitle)
        controller.cancel()
    }

    private fun TestScope.createController(repository: TitleRecordingRepository) = WorkbenchController(
        scope = this,
        repository = repository,
        catalogRepository = object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) =
                AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        },
        scopeFactory = { ConversationScope("p1", "gw1", "acc1", "inst1") },
        // The reply watchdog sleeps in real minutes; a virtual clock would fire
        // it immediately and change the state these title cases assert on.
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
    )

    private class TitleRecordingRepository(
        private val events: MutableSharedFlow<VerifiedConversationEvent> = MutableSharedFlow(),
        private val acceptsTitleUpdate: Boolean = true,
    ) : ConversationRepository {
        var lastUpdatedConversationId: String? = null
        var lastUpdatedTitle: String? = null
        var titleUpdates = 0

        override suspend fun listConversations(scope: ConversationScope, page: PageRequest): ConversationPage =
            ConversationPage(listOf(ConversationSummary(ConversationId("conv_test"), "新对话", 100L)), null)

        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation =
            Conversation(ConversationId("conv_test"), "新对话", 100L)

        override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage =
            TimelinePage(emptyList(), null)

        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance =
            BatchAcceptance(batch.batchId, batch.messages.associate { it.clientMessageId.value to "msg_ack" })

        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance =
            MessageAcceptance("msg_1", message.clientMessageId.value)

        override fun observeEvents(scope: ConversationScope) = events

        override suspend fun cancelGeneration(generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)

        override suspend fun updateTitle(conversationId: String, title: String): Boolean {
            lastUpdatedConversationId = conversationId
            lastUpdatedTitle = title
            titleUpdates++
            return acceptsTitleUpdate
        }
    }
}
