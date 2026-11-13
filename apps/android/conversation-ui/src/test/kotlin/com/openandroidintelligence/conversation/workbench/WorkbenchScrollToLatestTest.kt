package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import org.junit.Assert.assertTrue
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
class WorkbenchScrollToLatestTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 4)

    @After fun close() { scope.cancel() }

    @Test fun scrollingWithinALongLatestReplyKeepsTheButtonHidden() {
        openConversation()
        timeline().performTouchInput { swipeUp(durationMillis = 500) }

        val viewport = timeline().fetchSemanticsNode().boundsInRoot
        val reply = compose.onNodeWithTag("message-bubble:latest").getUnclippedBoundsInRoot()
        val viewportDp = with(compose.density) { viewport.top.toDp() to viewport.bottom.toDp() }
        assertTrue("The latest reply has scrolled above the viewport", reply.top < viewportDp.first)
        assertTrue("The latest reply still extends below the viewport", reply.bottom > viewportDp.second)
        compose.onNodeWithText("回到最新").assertDoesNotExist()

        timeline().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.onNodeWithText("最新回复结尾").assertIsDisplayed()
        compose.onNodeWithText("回到最新").assertDoesNotExist()
    }

    @Test fun earlierMessagesShowTheButtonAndClickingItReachesTheLatestReply() {
        openConversation()
        browseEarlierMessages()
        compose.onNodeWithTag("message-bubble:latest").assertDoesNotExist()
        compose.onNodeWithText("回到最新").assertIsDisplayed().performClick()

        compose.onNodeWithText("最新回复开头").assertIsDisplayed()
        compose.onNodeWithText("回到最新").assertDoesNotExist()
    }

    @Test fun theButtonHidesAsSoonAsTheLatestReplyEntersTheViewport() {
        openConversation()
        browseEarlierMessages()
        compose.onNodeWithText("回到最新").assertIsDisplayed()
        timeline().performScrollToIndex(HistoryCount + 1)
        val height = timeline().fetchSemanticsNode().boundsInRoot.height
        timeline().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -height / 2f) }

        val viewport = timeline().fetchSemanticsNode().boundsInRoot
        val reply = compose.onNodeWithTag("message-bubble:latest").getUnclippedBoundsInRoot()
        val replyTop = with(compose.density) { reply.top.toPx() }
        assertTrue("Earlier content remains above the latest reply", replyTop > viewport.top)
        assertTrue("The latest reply has entered the viewport", replyTop < viewport.bottom)
        compose.onNodeWithText("回到最新").assertDoesNotExist()
    }

    @Test fun reachingTheEndResumesFollowingInTheOpenedConversation() {
        openConversation()
        timeline().performTouchInput { swipeUp(durationMillis = 500) }
        timeline().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.onNodeWithText("最新回复结尾").assertIsDisplayed()

        compose.runOnIdle {
            events.tryEmit(VerifiedConversationEvent.TimelineUpsert(
                eventId = "next-reply",
                occurredAt = 2000L,
                revision = 1L,
                message = TimelineMessage(
                    id = "next",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("后续回复\n\n" + "继续回答\n\n".repeat(4) + "后续回复结尾")),
                    timestamp = HistoryCount + 2L,
                    conversationId = ConversationId("thread"),
                ),
            ))
        }

        compose.onNodeWithText("后续回复结尾").assertIsDisplayed()
        compose.onNodeWithText("回到最新").assertDoesNotExist()
    }

    @Test fun foregroundResynchronizationKeepsTheReadingPosition() {
        val controller = openConversation()
        browseEarlierMessages()
        compose.onNodeWithTag("message-bubble:latest").assertDoesNotExist()
        val before = timeline().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

        compose.runOnIdle { controller.onForegrounded() }
        compose.waitForIdle()

        assertEquals("补同步不能把用户拉回底部", before,
            timeline().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.001f)
        compose.onNodeWithTag("message-bubble:latest").assertDoesNotExist()
        compose.onNodeWithText("回到最新").assertIsDisplayed()
    }

    private fun timeline() = compose.onNode(hasScrollToIndexAction() and !hasTestTag("conversation-browser"))

    private fun browseEarlierMessages() {
        timeline().performScrollToIndex(1)
        timeline().performTouchInput { swipeUp(durationMillis = 500) }
    }

    private fun openConversation(): WorkbenchController {
        val history = (1..HistoryCount).map { index ->
            TimelineMessage(
                id = "history-$index",
                sender = "user",
                parts = listOf(MessagePart.Text("历史消息 $index：" + "历史内容。".repeat(120))),
                timestamp = index.toLong(),
            )
        }
        val latest = TimelineMessage(
            id = "latest",
            sender = "assistant",
            parts = listOf(MessagePart.Text(
                "最新回复开头\n\n" + (1..60).joinToString("\n\n") { "最新回复第 $it 段" } + "\n\n最新回复结尾",
            )),
            timestamp = HistoryCount + 1L,
        )
        val gateway = object : ConversationRepository {
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
                ConversationPage(emptyList(), null)
            override suspend fun timeline(conversationId: String, page: PageRequest) =
                TimelinePage(history + latest, null)
            override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
                Conversation(ConversationId("thread"), "会话", 0)
            override suspend fun submitMessage(message: OutgoingMessage) =
                MessageAcceptance("message", message.clientMessageId.value)
            override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId)
            override fun observeEvents(scope: ConversationScope) = events
            override suspend fun cancelGeneration(generationId: String, requestId: String) =
                CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        }
        val controller = WorkbenchController(
            scope,
            gateway,
            object : AgentCommandCatalogRepository {
                override suspend fun get(gatewayId: String, languageCode: String) =
                    AgentCommandCatalog(CatalogVersion("v1"), emptyList())
            },
            { ConversationScope("p", "g", "a", "i") },
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    WorkbenchScreen(controller, "gateway", {}, {}, {}, {}, {}, onLogout = {})
                }
            }
        }
        compose.runOnIdle { controller.openThread("thread") }
        compose.onNodeWithText("最新回复开头").assertIsDisplayed()
        return controller
    }

    private companion object {
        const val HistoryCount = 8
    }
}
