package com.openandroidintelligence.conversation.workbench

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkbenchComposerEpochTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sent = mutableListOf<OutgoingMessage>()
    private var controller: WorkbenchController? = null

    @After fun close() { controller?.close(); scope.cancel() }

    @Test fun sendAndImmediateTypingKeepTheNextDraftInTheRealController() {
        val workbench = openConversation()
        editor().performTextInput("first")
        val before = workbench.state.value.draftEpoch
        val insert = editor().fetchSemanticsNode()
            .config[SemanticsActions.InsertTextAtCursor].action ?: error("输入动作不可用")
        val send = compose.onNodeWithContentDescription("发送").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action ?: error("发送动作不可用")

        compose.runOnIdle { send(); insert(AnnotatedString("second")) }

        compose.runOnIdle {
            assertEquals("first", sent.single().text)
            assertTrue(workbench.state.value.draftEpoch > before)
            assertEquals("second", workbench.state.value.draft)
        }
        editor().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("second")),
        ).assertIsFocused()
    }

    private fun editor() = compose.onNode(
        hasSetTextAction() and !hasAnyAncestor(hasTestTag("conversation-browser")),
    )

    private fun openConversation(): WorkbenchController {
        val host = object : ConversationRepository {
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
                ConversationPage(emptyList(), null)
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(
                listOf(TimelineMessage("initial", "assistant", listOf(MessagePart.Text("欢迎")), 1L)), null,
            )
            override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
                Conversation(ConversationId("thread"), "会话", 0L)
            override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance {
                sent += message
                return MessageAcceptance("accepted-${sent.size}", message.clientMessageId.value)
            }
            override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId)
            override fun observeEvents(scope: ConversationScope) = MutableSharedFlow<VerifiedConversationEvent>()
            override suspend fun cancelGeneration(generationId: String, requestId: String) =
                CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        }
        val result = WorkbenchController(scope, host, object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) =
                AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        }, { ConversationScope("profile", "gateway", "account", "installation") })
        controller = result
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    WorkbenchScreen(result, "gateway", {}, {}, {}, {}, {}, onLogout = {})
                }
            }
        }
        compose.runOnIdle { result.openThread("thread") }
        compose.onNodeWithText("欢迎").assertIsDisplayed()
        return result
    }
}
