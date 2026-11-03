package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.WorkbenchController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkbenchScrollAnchorTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: WorkbenchController? = null

    @After fun close() { controller?.close(); scope.cancel() }

    @Test fun switchingThreadsAndPrependingHistoryKeepTheSameMessageAndOffset() {
        val host = Host()
        val workbench = WorkbenchController(scope, host, object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) =
                AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        }, { ConversationScope("profile", "gateway", "account", "installation") })
        controller = workbench
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    WorkbenchScreen(workbench, "gateway", {}, {}, {}, {}, {}, onLogout = {})
                }
            }
        }
        openThread("a")
        timeline().performScrollToNode(hasTestTag("message-bubble:a-6"))
        timeline().performTouchInput {
            swipe(Offset(center.x, center.y + 60f), Offset(center.x, center.y - 60f), 300L)
        }
        val anchor = compose.onNodeWithTag("message-bubble:a-6")
        anchor.assertIsDisplayed()
        val before = anchor.getUnclippedBoundsInRoot().top.value
        compose.onNodeWithText("回到最新").assertIsDisplayed()

        openThread("b")
        compose.onNodeWithTag("message-bubble:b-12").assertIsDisplayed()
        openThread("a")

        anchor.assertIsDisplayed()
        assertEquals("切换回来应保留同一消息的像素偏移", before, anchor.getUnclippedBoundsInRoot().top.value, 0.5f)
        compose.onNodeWithText("回到最新").assertIsDisplayed()
        compose.runOnIdle {
            host.history["a"] = listOf(message("a", 0)) + host.history.getValue("a")
            workbench.onForegrounded()
        }
        compose.waitForIdle()
        anchor.assertIsDisplayed()
        assertEquals("同步前插历史不能按旧索引跳动", before, anchor.getUnclippedBoundsInRoot().top.value, 0.5f)
        compose.onNodeWithText("回到最新").performClick()
        compose.onNodeWithTag("message-bubble:a-12").assertIsDisplayed()
    }

    private fun openThread(id: String) {
        compose.onNodeWithContentDescription("打开会话抽屉").performClick()
        compose.onNodeWithTag("conversation:$id").performClick()
        compose.waitForIdle()
    }

    private fun timeline() = compose.onNode(hasScrollToIndexAction() and !hasTestTag("conversation-browser"))

    private class Host : ConversationRepository {
        val history = mutableMapOf(
            "a" to (1..12).map { message("a", it) },
            "b" to (1..12).map { message("b", it) },
        )
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(
            listOf(ConversationSummary(ConversationId("a"), "会话 A", 1L),
                ConversationSummary(ConversationId("b"), "会话 B", 2L)), null,
        )
        override suspend fun timeline(conversationId: String, page: PageRequest) =
            TimelinePage(history.getValue(conversationId), null)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
            Conversation(ConversationId("a"), "会话 A", 0L)
        override suspend fun submitMessage(message: OutgoingMessage) =
            MessageAcceptance("accepted", message.clientMessageId.value)
        override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId)
        override fun observeEvents(scope: ConversationScope) = MutableSharedFlow<VerifiedConversationEvent>()
        override suspend fun cancelGeneration(generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }

    private companion object {
        fun message(thread: String, index: Int) = TimelineMessage(
            id = "$thread-$index", sender = "user", timestamp = 10_000L + index,
            parts = listOf(MessagePart.Text("会话 $thread 的第 $index 条消息：" + "这段文字用于真实阅读位置验证。".repeat(35))),
            conversationId = ConversationId(thread),
        )
    }
}
