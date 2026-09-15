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
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.components.noticeText
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import kotlinx.coroutines.*
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

    @Test fun failedSendExplainsItselfInsteadOfShowingABareErrorCode() {
        val readable = noticeText("SEND_FAILED:MASTER_KEY_UNAVAILABLE")
        assertTrue("失败提示必须给出可操作说明：$readable", readable.contains("主密钥") && !readable.contains("MASTER_KEY_UNAVAILABLE"))
        assertEquals("已创建新对话", noticeText("已创建新对话"))
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
