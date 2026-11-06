package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.LocalSendAtom
import com.openandroidintelligence.conversation.model.LocalSubmissionState
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.ports.AgentCommandCatalogRepository
import com.openandroidintelligence.conversation.ports.CancelGenerationOutcome
import com.openandroidintelligence.conversation.ports.CancelGenerationResult
import com.openandroidintelligence.conversation.ports.Conversation
import com.openandroidintelligence.conversation.ports.ConversationPage
import com.openandroidintelligence.conversation.ports.ConversationRepository
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.ports.MessageAcceptance
import com.openandroidintelligence.conversation.ports.MessageBatch
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.ports.TimelinePage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.conversation.state.SavedSubmission
import com.openandroidintelligence.conversation.state.WorkbenchCheckpoint
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.state.WorkbenchPersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class WorkbenchRecoveredSubmissionPromptTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: WorkbenchController? = null

    @After fun close() {
        controller?.close()
        scope.cancel()
    }

    @Test fun recoveredUnknownSubmissionDoesNotShowQueryPrompt() {
        val thread = ConversationSummary(ConversationId("thread"), "会话", 1L)
        val persistence = UnknownSubmissionPersistence(thread)
        val workbench = WorkbenchController(
            scope,
            EmptyGateway(thread),
            object : AgentCommandCatalogRepository {
                override suspend fun get(gatewayId: String, languageCode: String) =
                    AgentCommandCatalog(CatalogVersion("test"), emptyList())
            },
            { ConversationScope("profile", "gateway", "account", "install") },
            persistence = persistence,
            replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
        )
        controller = workbench

        compose.setContent {
            MaterialTheme {
                WorkbenchScreen(workbench, "gateway", {}, {}, {}, {}, {}, onLogout = {})
            }
        }
        compose.waitForIdle()

        assertTrue("测试必须处于有待恢复提交的状态", workbench.state.value.hasRecoverableSubmissions)
        compose.onNodeWithText("查询未确认消息").assertDoesNotExist()
    }

    private class UnknownSubmissionPersistence(private val thread: ConversationSummary) : WorkbenchPersistence {
        private val atom = LocalSendAtom(
            conversationId = thread.id,
            clientMessageId = ClientMessageId("client_old"),
            parts = listOf(MessagePart.Text("已断流的旧消息")),
            timestamp = 1L,
            submissionState = LocalSubmissionState.OUTCOME_UNKNOWN,
        )
        private var checkpoint = WorkbenchCheckpoint(
            threads = listOf(thread),
            threadId = thread.id.value,
            title = thread.title,
            messages = emptyList(),
            revisions = emptyMap(),
            draft = "",
            draftRevision = 1L,
            attachments = emptyList(),
            submission = SavedSubmission("已断流的旧消息", emptyList(), 1L, thread.id.value, "client_old"),
            renamedThreads = emptySet(),
            eventIds = emptySet(),
            draftExplicit = true,
        )

        override fun load() = checkpoint
        override fun save(checkpoint: WorkbenchCheckpoint) {
            this.checkpoint = checkpoint
        }
        override fun loadSendAtoms(conversationId: String) =
            if (conversationId == thread.id.value) listOf(atom) else emptyList()
        override fun saveSendAtoms(conversationId: String, atoms: List<LocalSendAtom>) = Unit
    }

    private class EmptyGateway(private val thread: ConversationSummary) : ConversationRepository {
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
            ConversationPage(listOf(thread), null)
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
            Conversation(ConversationId("new-thread"), "新对话", 0L)
        override suspend fun submitMessage(message: OutgoingMessage) =
            MessageAcceptance("message", message.clientMessageId.value)
        override suspend fun submitBatch(batch: MessageBatch) = com.openandroidintelligence.conversation.ports.BatchAcceptance(batch.batchId)
        override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
        override suspend fun cancelGeneration(generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
