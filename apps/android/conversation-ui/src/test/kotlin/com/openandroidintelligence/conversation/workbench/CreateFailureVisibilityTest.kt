package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.WorkbenchController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class CreateFailureVisibilityTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun close() { scope.cancel() }

    @Test fun failedCreationRetainsAnExplanationAfterTheProgressCardDisappears() = scenario(
        "AGENT_OFFLINE", "当前 Agent 已离线，请等待恢复连接后重试。")

    @Test fun uncertainCreationRetainsTheExistingUnknownOutcomeExplanation() = scenario(
        "OUTCOME_UNKNOWN", "操作结果尚未确认。请刷新核实，避免重复提交。")

    private fun scenario(errorCode: String, expectedText: String) {
        val failure = CompletableDeferred<Unit>()
        val repository = object : ConversationRepository {
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(listOf(ConversationSummary(ConversationId("source"), "原会话", 1)), null)
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
            override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation {
                failure.await()
                error(errorCode)
            }
            override suspend fun submitMessage(message: OutgoingMessage) = MessageAcceptance("unused", message.clientMessageId.value)
            override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId)
            override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
            override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        }
        val controller = WorkbenchController(scope, repository, object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        }, { ConversationScope("p", "g", "a", "i") }, replyTimeouts = WorkbenchController.ReplyTimeouts(enabled=false))
        compose.setContent { MaterialTheme { WorkbenchScreen(controller, "gateway", {}, {}, {}, {}, {}, onLogout = {}) } }
        compose.runOnIdle { controller.openThread("source") }
        compose.onNodeWithContentDescription("新建对话").performClick()
        compose.runOnIdle { assertTrue(controller.state.value.creatingThread) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { failure.complete(Unit) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithText("正在创建对话…").assertDoesNotExist()
        compose.runOnIdle {
            println("失败300ms后: active=${controller.state.value.activeThreadId}, creating=${controller.state.value.creatingThread}, notice=${controller.state.value.notice}")
            assertFalse(controller.state.value.creatingThread)
            assertEquals("source", controller.state.value.activeThreadId)
        }
        compose.onNodeWithText(expectedText).assertExists()
    }
}
