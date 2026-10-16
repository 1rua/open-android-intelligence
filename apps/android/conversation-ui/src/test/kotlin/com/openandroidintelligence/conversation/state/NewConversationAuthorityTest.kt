package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NewConversationAuthorityTest {
    @Test fun newEntryCreatesThroughGatewayWithoutSendingCommandEvenWithoutSlashCapability() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        advanceUntilIdle()
        controller.editDraft("保留草稿")
        controller.createThread()
        advanceUntilIdle()
        assertEquals(1, repository.creations.size)
        assertTrue(repository.creations.single().startsWith("cconv_"))
        assertTrue(repository.messages.isEmpty())
        assertEquals("conv_created",controller.state.value.activeThreadId)
        assertEquals("Gateway新会话",controller.state.value.activeThreadTitle)
        assertEquals("保留草稿",controller.state.value.draft)
        assertFalse(controller.state.value.creatingThread)
        controller.editDraft("新问题")
        controller.sendDraft()
        advanceUntilIdle()
        assertEquals("conv_created",repository.messages.single().first)
    }

    @Test fun emptyAccountCanCreateWithoutASourceConversation() = runTest {
        val repository = Repository(emptyAccount=true)
        val controller = controller(repository)
        advanceUntilIdle()
        controller.createThread()
        advanceUntilIdle()
        assertEquals("conv_created",controller.state.value.activeThreadId)
        assertEquals(1,repository.creations.size)
        assertTrue(repository.messages.isEmpty())
    }

    @Test fun creationFailureKeepsSourceThreadAndDraft() = runTest {
        val repository = Repository().apply { createFailure=true }
        val controller = controller(repository)
        advanceUntilIdle()
        controller.editDraft("草稿")
        controller.createThread()
        advanceUntilIdle()
        assertEquals("conv_source",controller.state.value.activeThreadId)
        assertEquals("草稿",controller.state.value.draft)
        assertFalse(controller.state.value.creatingThread)
        assertTrue(controller.state.value.notice.orEmpty().contains("CONVERSATION_CREATE_FAILED"))
        assertTrue(repository.messages.isEmpty())
    }

    @Test fun duplicateTapAndLateResponseCannotHijackNavigation() = runTest {
        val repository = Repository().apply { createGate=CompletableDeferred() }
        val controller = controller(repository)
        advanceUntilIdle()
        controller.createThread()
        controller.createThread()
        runCurrent()
        assertEquals(1,repository.creations.size)
        assertTrue(controller.state.value.creatingThread)
        controller.openThread("conv_other")
        repository.createGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("conv_other",controller.state.value.activeThreadId)
        assertFalse(controller.state.value.creatingThread)
    }

    @Test fun aSendDuringCreationWaitsForTheGatewayNamedDestination() = runTest {
        val repository = Repository().apply { createGate=CompletableDeferred() }
        val controller = controller(repository)
        advanceUntilIdle()
        controller.createThread()
        runCurrent()
        controller.editDraft("保留这条问题")
        controller.sendDraft()
        runCurrent()
        assertTrue(repository.messages.isEmpty())
        assertEquals("保留这条问题",controller.state.value.draft)
        assertTrue(controller.state.value.notice.orEmpty().contains("CONVERSATION_CREATING"))
        repository.createGate!!.complete(Unit)
        advanceUntilIdle()
        controller.sendDraft()
        advanceUntilIdle()
        assertEquals("conv_created",repository.messages.single().first)
    }

    @Test fun cancellingCreationDoesNotSwitchOnALateHttpResponse() = runTest {
        val repository = Repository().apply { createGate=CompletableDeferred() }
        val controller = controller(repository)
        advanceUntilIdle()
        controller.createThread()
        runCurrent()
        controller.cancelThreadCreation()
        repository.createGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("conv_source",controller.state.value.activeThreadId)
        assertFalse(controller.state.value.creatingThread)
    }

    @Test fun explicitlyTypedNewIsStillAnOutgoingUserCommand() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        advanceUntilIdle()
        controller.editDraft("/new")
        controller.sendDraft()
        advanceUntilIdle()
        assertEquals("/new",repository.messages.single().second.text)
        assertTrue(repository.creations.isEmpty())
        repository.events.emit(VerifiedConversationEvent.CommandResult("result",1,"/new",
            CommandOutcome.CREATED_CONVERSATION,ConversationId("conv_source"),null,ConversationId("conv_created")))
        advanceUntilIdle()
        assertEquals("conv_created",controller.state.value.activeThreadId)
    }

    private fun TestScope.controller(repository: Repository) = WorkbenchController(
        scope=workbenchTestScope(),repository=repository,
        catalogRepository=object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId:String,languageCode:String)=AgentCommandCatalog(CatalogVersion("v1"),emptyList())
        },scopeFactory={ ConversationScope("p","g","a","i") },
        supportsAgentCommandNew=false,replyTimeouts=WorkbenchController.ReplyTimeouts(enabled=false),
    )

    private class Repository(val emptyAccount:Boolean=false) : ConversationRepository {
        val creations=mutableListOf<String>()
        val messages=mutableListOf<Pair<String,OutgoingMessage>>()
        val events=MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity=16)
        var createGate:CompletableDeferred<Unit>?=null
        var createFailure=false
        override suspend fun listConversations(scope:ConversationScope,page:PageRequest)=ConversationPage(
            if(emptyAccount) emptyList() else listOf(ConversationSummary(ConversationId("conv_source"),"来源",1)),null)
        override suspend fun createConversation(scope:ConversationScope,clientConversationId:String):Conversation {
            creations+=clientConversationId
            createGate?.await()
            if(createFailure) error("CREATE_FAILED")
            return Conversation(ConversationId("conv_created"),"Gateway新会话",2)
        }
        override suspend fun timeline(conversationId:String,page:PageRequest)=TimelinePage(emptyList(),null)
        override suspend fun submitMessage(message:OutgoingMessage)=submitMessage("conv_source",message)
        override suspend fun submitMessage(conversationId:String,message:OutgoingMessage):MessageAcceptance {
            messages+=conversationId to message
            return MessageAcceptance("msg_accepted",message.clientMessageId.value)
        }
        override suspend fun submitBatch(batch:MessageBatch)=BatchAcceptance(batch.batchId,emptyMap())
        override fun observeEvents(scope:ConversationScope):Flow<VerifiedConversationEvent> = events
        override suspend fun cancelGeneration(generationId:String,requestId:String)=CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
