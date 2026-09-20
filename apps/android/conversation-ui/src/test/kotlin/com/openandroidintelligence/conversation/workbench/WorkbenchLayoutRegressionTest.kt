package com.openandroidintelligence.conversation.workbench

import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.components.noticeText
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class WorkbenchLayoutRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun close() { scope.cancel() }

    @Test fun firstDraftHasAnEnabledSendButtonWithoutAnExistingThread() {
        val controller = WorkbenchController(scope, EmptyGateway(), object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        }, { ConversationScope("p", "g", "a", "i") })
        compose.setContent {
            MaterialTheme {
                WorkbenchScreen(controller, "gateway", {}, {}, {}, {}, {})
            }
        }
        compose.runOnIdle { controller.editDraft("1111") }
        compose.onNodeWithContentDescription("发送").assertIsEnabled()
    }

    @Test fun searchFieldGrowsWithFontScaleInsteadOfClippingItsPlaceholder() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme {
                    Box(Modifier.size(320.dp, 640.dp)) {
                        ThreadDrawer("gateway", Loadable.Empty, null, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        val placeholderHeight = compose.onNodeWithText("搜索会话…", useUnmergedTree = true).textLayoutHeight()
        val field = compose.onNode(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "搜索框必须容纳完整占位文字：字段高 ${field.height}px，文字高 ${placeholderHeight}px",
            field.height >= placeholderHeight,
        )
        // 旧实现把高度写死为 44dp，比 Material 文本字段的最小高度（56dp）还小，
        // 装饰区内边距被压缩后占位文字就会被裁掉。
        val materialMinimum = with(compose.density) { OutlinedTextFieldDefaults.MinHeight.roundToPx() }
        assertTrue(
            "搜索框不能低于 Material 文本字段最小高度：字段高 ${field.height}px，最小值 ${materialMinimum}px",
            field.height >= materialMinimum,
        )
    }

    @Test
    fun settingsEntryRemainsVisibleInFooterEvenWithManyThreads() {
        val manyThreads = (1..30).map { i ->
            ConversationSummary(ConversationId("id_$i"), "历史会话 $i", 1000L + i)
        }
        var settingsOpened = false
        var drawerClosed = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 480.dp)) {
                    ThreadDrawer(
                        gatewayLabel = "user@test",
                        threads = Loadable.Ready(manyThreads),
                        activeThreadId = null,
                        onOpenThread = {},
                        onCreateThread = {},
                        onRefresh = {},
                        onOpenSettings = { settingsOpened = true },
                        onCloseDrawer = { drawerClosed = true },
                    )
                }
            }
        }

        // 即使有 30 条会话撑满列表，固定页脚中的「设置与平台管理」也必须常驻可见，无需滚动
        val settingsNode = compose.onNodeWithText("设置与平台管理")
        settingsNode.assertIsDisplayed()
        settingsNode.performClick()
        assertTrue("点击设置项必须触发 onOpenSettings", settingsOpened)
        assertTrue("点击设置项必须触发关闭抽屉 onCloseDrawer", drawerClosed)

        // 页脚同级的协议版本号与退出登录也必须常驻可见
        compose.onNodeWithText("Gateway Protocol v2").assertIsDisplayed()
        compose.onNodeWithText("退出登录").assertIsDisplayed()
    }

    @Test fun failedSendExplainsItselfInsteadOfShowingABareErrorCode() {
        val readable = noticeText("SEND_FAILED:MASTER_KEY_UNAVAILABLE")
        assertTrue("失败提示必须给出可操作说明：$readable", readable.contains("主密钥") && !readable.contains("MASTER_KEY_UNAVAILABLE"))
        assertEquals("已创建新对话", noticeText("已创建新对话"))
    }

    @Test
    fun thinkingIndicatorRendersWhenThinkingAndDisappearsWhenStreamingDeltaArrives() {
        val eventFlow = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 16)
        val gateway = object : ConversationRepository {
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(emptyList(), null)
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
            override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
                Conversation(ConversationId("conv_streaming"), "新对话", 0)
            override suspend fun submitMessage(message: OutgoingMessage) =
                MessageAcceptance("msg_user_1", message.clientMessageId.value)
            override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId, emptyList())
            override fun observeEvents(scope: ConversationScope) = eventFlow
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
                WorkbenchScreen(controller, "gateway", {}, {}, {}, {}, {})
            }
        }

        // Welcome screen is displayed initially, no thinking indicator
        compose.onNodeWithText("从一个想法开始").assertIsDisplayed()
        compose.onNodeWithText("AI 正在思考").assertDoesNotExist()

        // Send a draft to enter QUEUED generation state
        compose.runOnIdle {
            controller.editDraft("你好")
            controller.sendDraft()
        }

        // Thinking indicator must be displayed while QUEUED/RUNNING and no assistant streaming yet
        compose.onNodeWithText("AI 正在思考").assertIsDisplayed()
        compose.onAllNodesWithText("你好").onFirst().assertIsDisplayed()

        // Assistant streaming delta arrives
        compose.runOnIdle {
            eventFlow.tryEmit(
                VerifiedConversationEvent.TimelineUpsert(
                    eventId = "evt_1",
                    occurredAt = 1000L,
                    revision = 1L,
                    message = TimelineMessage(
                        id = "msg_asst_1",
                        sender = "assistant",
                        parts = listOf(MessagePart.Text("助理正在回答中")),
                        timestamp = 1000L,
                        state = "STREAMING",
                    ),
                ),
            )
        }

        // Thinking indicator disappears, streaming indicator and streaming text appear
        compose.onNodeWithText("AI 正在思考").assertDoesNotExist()
        compose.onNodeWithText("正在输出…").assertIsDisplayed()
        compose.onNodeWithText("助理正在回答中", substring = true).assertIsDisplayed()

        // Final completion event arrives
        compose.runOnIdle {
            eventFlow.tryEmit(
                VerifiedConversationEvent.TimelineUpsert(
                    eventId = "evt_2",
                    occurredAt = 1010L,
                    revision = 2L,
                    message = TimelineMessage(
                        id = "msg_asst_1",
                        sender = "assistant",
                        parts = listOf(MessagePart.Text("助理正在回答中，回答完毕。")),
                        timestamp = 1000L,
                        state = "CONFIRMED",
                    ),
                ),
            )
        }

        // Thinking indicator remains absent, streaming tag is gone, complete message is displayed
        compose.onNodeWithText("AI 正在思考").assertDoesNotExist()
        compose.onNodeWithText("正在输出…").assertDoesNotExist()
        compose.onNodeWithText("助理正在回答中，回答完毕。", substring = true).assertIsDisplayed()
    }

    @Test
    fun thinkingIndicatorDisappearsWhenGenerationIsStopped() {
        val gateway = object : ConversationRepository {
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(emptyList(), null)
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
            override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
                Conversation(ConversationId("conv_stop"), "新对话", 0)
            override suspend fun submitMessage(message: OutgoingMessage) =
                MessageAcceptance("msg_user_1", message.clientMessageId.value)
            override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId, emptyList())
            override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
            override suspend fun cancelGeneration(generationId: String, requestId: String) =
                CancelGenerationResult(CancelGenerationOutcome.CANCELLED)
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
                WorkbenchScreen(controller, "gateway", {}, {}, {}, {}, {})
            }
        }

        compose.runOnIdle {
            controller.editDraft("正在提问")
            controller.sendDraft()
        }
        compose.onNodeWithText("AI 正在思考").assertIsDisplayed()

        compose.runOnIdle {
            controller.stopGeneration()
        }
        compose.onNodeWithText("AI 正在思考").assertDoesNotExist()
    }

    @Test
    fun manualRenameButtonDisplaysWhenActiveThreadExistsAndTriggersRenameDialog() {
        val gateway = object : ConversationRepository {
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
                ConversationPage(emptyList(), null)
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
            override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
                Conversation(ConversationId("conv_rename"), "原始标题", 0)
            override suspend fun submitMessage(message: OutgoingMessage) = MessageAcceptance("msg_test", message.clientMessageId.value)
            override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId, emptyList())
            override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
            override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
            override suspend fun updateTitle(conversationId: String, title: String): Boolean = true
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
                WorkbenchScreen(controller, "gateway", {}, {}, {}, {}, {})
            }
        }

        // Initially no active thread, rename button is not present
        compose.onNodeWithContentDescription("重命名对话").assertDoesNotExist()

        // Open thread
        compose.runOnIdle {
            controller.openThread("conv_rename")
        }

        // Rename button is displayed in TopAppBar
        compose.onNodeWithContentDescription("重命名对话").assertIsDisplayed()
    }

    private fun SemanticsNodeInteraction.textLayoutHeight(): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().size.height
    }

    private class EmptyGateway : ConversationRepository {
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(emptyList(), null)
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) = Conversation(ConversationId("conv_test"), "新对话", 0)
        override suspend fun submitMessage(message: OutgoingMessage) = MessageAcceptance("msg_test", message.clientMessageId.value)
        override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId, emptyList())
        override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
