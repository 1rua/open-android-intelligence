package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchActiveWorkLifecycleTest {
    @Test fun a_send_remains_owned_when_the_thread_changes_before_any_coroutine_or_native_frame_runs() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("first conversation work")
        controller.sendDraft()
        assertTrue(controller.hasActiveWork.value)
        controller.openThread("b")
        assertTrue("Queued ownership is synchronous and cannot be conflated with the new UI state", controller.hasActiveWork.value)
        runCurrent()
        assertEquals("a", repository.messages.single().first)
        assertEquals(GenerationState.IDLE, controller.state.value.generation)

        repository.acceptance.complete(Unit)
        runCurrent()
        val message = repository.messages.single().second
        repository.events.emit(VerifiedConversationEvent.MessageStatus("old-round", 10, ConversationId("a"), "old-server",
            ClientMessageId("old-client"), AgentMessageStatus.COMPLETED, 1, null, "old-generation"))
        runCurrent()
        assertTrue(controller.hasActiveWork.value)
        repository.events.emit(VerifiedConversationEvent.TimelineUpsert("middle-reply", 11, 1,
            TimelineMessage("assistant", "assistant", listOf(MessagePart.Text("An intermediate completed reply")), 11,
                state = "CONFIRMED", conversationId = ConversationId("a"), generationId = "generation")))
        runCurrent()
        assertTrue("An assistant message is not the user's task terminal status", controller.hasActiveWork.value)
        repository.events.emit(status(message, AgentMessageStatus.COMPLETED))
        runCurrent()
        assertFalse(controller.hasActiveWork.value)
        assertEquals("b", controller.state.value.activeThreadId)
        controller.close()
    }

    @Test fun exact_cancellation_before_http_acceptance_cannot_be_lost_or_revived_by_the_receipt() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("work"); controller.sendDraft(); runCurrent()
        repository.events.emit(VerifiedConversationEvent.GenerationCancelled("cancel", 1, "other", ConversationId("a")))
        runCurrent()
        assertTrue(controller.hasActiveWork.value)
        repository.events.emit(VerifiedConversationEvent.GenerationCancelled("own-cancel", 2, "generation", ConversationId("a")))
        runCurrent()
        assertTrue("The pending request is still owned until its receipt identifies the cancelled generation", controller.hasActiveWork.value)
        repository.acceptance.complete(Unit)
        runCurrent()
        assertFalse(controller.hasActiveWork.value)
        controller.close()
    }

    @Test fun a_known_send_failure_and_explicit_controller_close_release_ownership() = runTest {
        val repository = Repository().apply { failure = IllegalArgumentException("request rejected") }
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("rejected"); controller.sendDraft(); runCurrent()
        assertTrue(controller.hasActiveWork.value)
        repository.acceptance.complete(Unit)
        runCurrent()
        assertFalse(controller.hasActiveWork.value)
        repository.failure = null
        controller.editDraft("new task"); controller.sendDraft()
        assertTrue(controller.hasActiveWork.value)
        controller.close()
        assertFalse(controller.hasActiveWork.value)
    }

    @Test fun completion_before_http_acceptance_remains_terminal_when_the_receipt_arrives() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("fast task"); controller.sendDraft(); runCurrent()
        val message = repository.messages.single().second
        repository.events.emit(status(message, AgentMessageStatus.COMPLETED))
        runCurrent()
        assertFalse(controller.hasActiveWork.value)
        repository.acceptance.complete(Unit)
        runCurrent()
        assertFalse(controller.hasActiveWork.value)
        controller.close()
    }

    private fun TestScope.controller(repository: Repository) = WorkbenchController(workbenchTestScope(), repository,
        object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("test"), emptyList())
        }, { ConversationScope("profile", "gateway", "account", "installation") },
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false))

    private fun status(message: OutgoingMessage, status: AgentMessageStatus) = VerifiedConversationEvent.MessageStatus(
        "terminal-${message.clientMessageId.value}", 20, ConversationId("a"), "server-${message.clientMessageId.value}",
        message.clientMessageId, status, 1, null, "generation")

    private class Repository : ConversationRepository {
        val acceptance = CompletableDeferred<Unit>()
        val messages = mutableListOf<Pair<String, OutgoingMessage>>()
        val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 16)
        var failure: Exception? = null
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(
            listOf(ConversationSummary(ConversationId("a"), "A", 1)), null)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation = error("unused")
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance = error("conversation required")
        override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
            messages += conversationId to message
            acceptance.await()
            failure?.let { throw it }
            return MessageAcceptance("server-${message.clientMessageId.value}", message.clientMessageId.value, "generation")
        }
        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance = error("unused")
        override fun observeEvents(scope: ConversationScope) = events
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
