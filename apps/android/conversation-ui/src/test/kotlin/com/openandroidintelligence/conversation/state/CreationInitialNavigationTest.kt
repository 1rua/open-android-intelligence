package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreationInitialNavigationTest {
    @Test fun successfulCreationStillOpensWhenInitialHistoryReturnsWhileCreating() = scenario(historyFirst = true)
    @Test fun successfulCreationStillOpensWhenTheCreateReceiptArrivesBeforeInitialHistory() = scenario(historyFirst = false)
    @Test fun realUserNavigationStillWinsWhileCreationAndInitialHistoryArePending() = scenario(historyFirst = true, navigate = true)

    private fun scenario(historyFirst: Boolean, navigate: Boolean = false) = runTest {
        val initialHistory = CompletableDeferred<Unit>()
        val creation = CompletableDeferred<Unit>()
        var created = false
        val repository = object : ConversationRepository {
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest): ConversationPage {
                initialHistory.await()
                val old = ConversationSummary(ConversationId("source"), "原会话", 1)
                val fresh = ConversationSummary(ConversationId("created"), "新会话", 2)
                return ConversationPage(if (created) listOf(fresh, old) else listOf(old), null)
            }
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
            override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation {
                creation.await(); created = true
                return Conversation(ConversationId("created"), "新会话", 2)
            }
            override suspend fun submitMessage(message: OutgoingMessage) = MessageAcceptance("unused", message.clientMessageId.value)
            override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId)
            override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
            override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        }
        val controller = WorkbenchController(workbenchTestScope(), repository, object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        }, { ConversationScope("p", "g", "a", "i") }, replyTimeouts = WorkbenchController.ReplyTimeouts(enabled=false))
        try {
            runCurrent()
            assertNull(controller.state.value.activeThreadId)
            controller.createThread(); runCurrent()
            assertTrue(controller.state.value.creatingThread)
            assertEquals(controller.state.value.creationSourceThreadId, controller.state.value.activeThreadId)
            if (navigate) controller.openThread("chosen-by-user")
            if (historyFirst) {
                initialHistory.complete(Unit); runCurrent()
                if (!navigate) {
                    assertTrue(controller.state.value.creatingThread)
                    assertEquals("创建进度仍属于当前空白页", controller.state.value.creationSourceThreadId, controller.state.value.activeThreadId)
                }
                creation.complete(Unit); runCurrent()
            } else {
                creation.complete(Unit); runCurrent()
                initialHistory.complete(Unit); runCurrent()
            }
            println("创建成功后: active=${controller.state.value.activeThreadId}, creating=${controller.state.value.creatingThread}, notice=${controller.state.value.notice}")
            assertEquals("后台历史恢复不能冒充用户导航", if (navigate) "chosen-by-user" else "created", controller.state.value.activeThreadId)
        } finally { controller.close() }
    }
}
