package com.openandroidintelligence.mobile.assistant

import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.ports.AgentCommandCatalogRepository
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import com.openandroidintelligence.conversation.ports.CancelGenerationOutcome
import com.openandroidintelligence.conversation.ports.CancelGenerationResult
import com.openandroidintelligence.conversation.model.CatalogVersion
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
import com.openandroidintelligence.conversation.state.WorkbenchController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 助手会话释放的在途语义。
 *
 * 规格 §9.2 / §9.3：**关闭助理界面不取消**已被 Gateway 接受的消息或生成。
 * 工作区是这条语义的执行者——界面离开时若会话还有在途工作，只能摘掉界面的
 * 认领（detached），等真实终态到达再关闭控制器；没有在途工作时才立即关闭。
 */
class AssistantConversationWorkspaceTest {

    @Test fun releasingAnIdleSessionClosesItImmediately() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val repository = Repository()
        val workspace = workspace(scope, repository)
        try {
            val controller = workspace.open("session-idle")
            assertTrue(controller.state.value.isOnline)

            workspace.release("session-idle")
            yield()

            assertFalse("idle session must be closed on release", controller.state.value.isOnline)
            assertFalse(workspace.hasActiveWork.value)
        } finally {
            workspace.close()
            job.cancel()
        }
    }

    @Test fun releasingASessionWithAnAcceptedSendDefersCloseUntilTheTerminalStatus() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val repository = Repository()
        val workspace = workspace(scope, repository)
        try {
            val controller = workspace.open("session-busy")
            controller.editDraft("关闭浮层不应取消这条生成")
            controller.sendDraft()
            assertTrue("the send is owned as soon as it is queued", controller.hasActiveWork.value)

            // 界面离开：不能关闭控制器，否则会取消主进程仍在等待的提交。
            workspace.release("session-busy")
            yield()
            assertTrue("an in-flight send keeps the controller open", controller.state.value.isOnline)
            assertTrue(workspace.hasActiveWork.value)
            assertFalse("release must not request cancellation", repository.cancelled)

            repository.acceptance.complete(Unit)
            yield()
            val message = repository.messages.single().second
            repository.events.emit(
                VerifiedConversationEvent.MessageStatus(
                    eventId = "terminal",
                    occurredAt = 2L,
                    conversationId = ConversationId("conversation"),
                    messageId = "server-message",
                    clientMessageId = message.clientMessageId,
                    status = AgentMessageStatus.COMPLETED,
                    revision = 2,
                    errorCode = null,
                    generationId = "generation-1",
                ),
            )
            yield()

            assertFalse("the real terminal status releases the detached session", controller.state.value.isOnline)
            assertFalse(workspace.hasActiveWork.value)
            assertFalse(repository.cancelled)
        } finally {
            workspace.close()
            job.cancel()
        }
    }

    private fun workspace(scope: CoroutineScope, repository: Repository) =
        AssistantConversationWorkspace(
            profileId = "profile-a",
            accountId = "account-a",
            agentId = "agent-a",
            scope = scope,
            create = { repository.controller(scope) },
        )

    private fun Repository.controller(scope: CoroutineScope) = WorkbenchController(
        scope = scope,
        repository = this,
        catalogRepository = object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) =
                AgentCommandCatalog(CatalogVersion("test"), emptyList())
        },
        scopeFactory = { ConversationScope("profile-a", "gateway", "account-a", "installation") },
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
        mediaDispatcher = Dispatchers.Unconfined,
    )

    /** 最小会话仓储：一次发送挂在 [acceptance] 上，直到测试显式放行。 */
    private class Repository : ConversationRepository {
        val acceptance = CompletableDeferred<Unit>()
        val messages = mutableListOf<Pair<String, OutgoingMessage>>()
        val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 16)
        var cancelled = false

        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
            ConversationPage(listOf(ConversationSummary(ConversationId("conversation"), "对话", 1)), null)

        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation =
            error("unused")

        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)

        override suspend fun submitBatch(batch: MessageBatch) = error("unused")

        override suspend fun submitMessage(message: OutgoingMessage) = error("conversation identity required")

        override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
            messages += conversationId to message
            acceptance.await()
            return MessageAcceptance("server-message", message.clientMessageId.value, "generation-1")
        }

        override fun observeEvents(scope: ConversationScope) = events

        override suspend fun cancelGeneration(generationId: String, requestId: String): CancelGenerationResult {
            cancelled = true
            return CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        }
    }
}
