package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchSendRegressionTest {
    @Test fun firstSendCreatesOneConversationAndSendsTheOriginalDraft() = runTest {
        val repository = RecordingRepository()
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("1111")
        controller.sendDraft()
        controller.sendDraft()
        advanceUntilIdle()
        assertEquals(1, repository.creates)
        assertEquals(listOf("1111"), repository.sent.map { it.text })
        assertEquals("conv_created", controller.state.value.activeThreadId)
    }

    @Test fun failedConversationCreationKeepsTheDraftForRetry() = runTest {
        val repository = RecordingRepository().apply { failCreate = true }
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("保留这条消息")
        controller.sendDraft()
        advanceUntilIdle()
        assertEquals("保留这条消息", controller.state.value.draft)
        assertTrue(repository.sent.isEmpty())
        assertTrue(controller.state.value.notice.orEmpty().contains("CREATE_FAILED"))
    }

    private fun TestScope.controller(repository: RecordingRepository) = WorkbenchController(
        this, repository,
        object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        },
        { ConversationScope("profile", "gateway", "account", "install") },
    )

    private class RecordingRepository : ConversationRepository {
        var creates = 0
        var failCreate = false
        val sent = mutableListOf<OutgoingMessage>()
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(emptyList(), null)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation {
            creates++
            if (failCreate) error("CREATE_FAILED:offline")
            return Conversation(ConversationId("conv_created"), "新对话", 0)
        }
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance {
            sent += batch.messages
            return BatchAcceptance(batch.batchId, listOf("msg_server"))
        }
        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance {
            sent += message
            return MessageAcceptance("msg_server", message.clientMessageId.value)
        }
        override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
