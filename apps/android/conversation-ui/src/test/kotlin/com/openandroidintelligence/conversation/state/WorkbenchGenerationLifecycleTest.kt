package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchGenerationLifecycleTest {
    @Test fun stopUsesTheCapturedConversationAndDoesNotWriteItsResultIntoAnotherThread() = runTest {
        val repository=Repository()
        val controller=controller(repository)
        advanceUntilIdle()
        repository.generationId.value="gen_a"
        repository.generationState.value=GenerationState.RUNNING
        runCurrent()
        controller.stopGeneration()
        controller.openThread("conv_b")
        runCurrent()
        assertEquals(listOf("conv_a" to "gen_a"),repository.cancellations)
        repository.cancelResult.complete(CancelGenerationResult(CancelGenerationOutcome.CANCELLED,"stopped A"))
        advanceUntilIdle()
        assertEquals("conv_b",controller.state.value.activeThreadId)
        assertEquals(GenerationState.IDLE,controller.state.value.generation)
        assertNull(controller.state.value.notice)
    }

    @Test fun aStopResponseCannotOverwriteANewerGenerationInTheSameThread() = runTest {
        val repository=Repository()
        val controller=controller(repository)
        advanceUntilIdle()
        repository.generationId.value="gen_a"
        controller.stopGeneration()
        runCurrent()
        repository.generationId.value="gen_b"
        repository.generationState.value=GenerationState.RUNNING
        runCurrent()
        repository.cancelResult.complete(CancelGenerationResult(CancelGenerationOutcome.CANCELLED))
        advanceUntilIdle()
        assertEquals(GenerationState.RUNNING,controller.state.value.generation)
    }

    @Test fun unknownCancellationKeepsProgressAndReportsUncertainty() = runTest {
        val repository=Repository()
        val controller=controller(repository)
        advanceUntilIdle()
        repository.generationId.value="gen_a"
        repository.events.emit(stream("progress","gen_a","正在执行"))
        runCurrent()
        controller.stopGeneration()
        repository.cancelResult.complete(CancelGenerationResult(CancelGenerationOutcome.OUTCOME_UNKNOWN,"STOP_UNCONFIRMED"))
        advanceUntilIdle()
        assertEquals(GenerationState.OUTCOME_UNKNOWN,controller.state.value.generation)
        assertTrue(rows(controller).single().isStreaming)
        assertEquals("STOP_UNCONFIRMED",controller.state.value.notice)
    }

    @Test fun completionClosesProgressEvenWhenFinalContentHasADifferentIdAndText() = runTest {
        val repository=Repository()
        val controller=controller(repository)
        advanceUntilIdle()
        repository.generationId.value="gen_a"
        repository.events.emit(stream("progress","gen_a","正在查找资料"))
        repository.events.emit(stream("answer","gen_a","最终结论","CONFIRMED"))
        runCurrent()
        assertEquals("单条assistant完成不代表整个run完成",GenerationState.RUNNING,controller.state.value.generation)
        repository.generationId.value=null
        repository.events.emit(terminal("gen_a",AgentMessageStatus.COMPLETED))
        advanceUntilIdle()
        assertEquals(GenerationState.COMPLETED,controller.state.value.generation)
        assertFalse(rows(controller).any { it.isStreaming })
        assertEquals(listOf("最终结论"),rows(controller).filterNot { it.isUser }.map { it.text })
        repository.events.emit(stream("late_delta","gen_a","迟到分片"))
        advanceUntilIdle()
        assertFalse(rows(controller).any { it.isStreaming || it.text=="迟到分片" })
    }

    @Test fun oldGenerationCompletionDoesNotClearTheNextGenerationProgress() = runTest {
        val repository=Repository()
        val controller=controller(repository)
        advanceUntilIdle()
        repository.generationId.value="gen_b"
        repository.events.emit(stream("progress_b","gen_b","B还在运行"))
        runCurrent()
        repository.events.emit(terminal("gen_a",AgentMessageStatus.COMPLETED))
        advanceUntilIdle()
        assertEquals(GenerationState.RUNNING,controller.state.value.generation)
        assertTrue(rows(controller).single().isStreaming)
    }

    @Test fun failureAndCancellationBothCloseTheMatchingProgress() = runTest {
        for (cancelled in listOf(false,true)) {
            val repository=Repository()
            val controller=controller(repository)
            advanceUntilIdle()
            repository.generationId.value="gen_a"
            repository.events.emit(stream("progress","gen_a","部分回答"))
            runCurrent()
            repository.generationId.value=null
            repository.events.emit(if(cancelled) VerifiedConversationEvent.GenerationCancelled("cancelled",4,"gen_a",ConversationId("conv_a"))
                else terminal("gen_a",AgentMessageStatus.FAILED))
            advanceUntilIdle()
            assertEquals(if(cancelled) GenerationState.CANCELLED else GenerationState.FAILED,controller.state.value.generation)
            assertFalse(rows(controller).any { it.isStreaming })
            controller.close()
        }
    }

    private fun rows(controller:WorkbenchController)=(controller.state.value.timeline as Loadable.Ready).value
    private fun stream(id:String,generation:String,text:String,state:String="STREAMING")=VerifiedConversationEvent.TimelineUpsert(
        "event_$id",2,1,TimelineMessage(id,"assistant",listOf(MessagePart.Text(text)),2,state,ConversationId("conv_a"),generationId=generation))
    private fun terminal(generation:String,status:AgentMessageStatus)=VerifiedConversationEvent.MessageStatus(
        "terminal_$generation",3,ConversationId("conv_a"),"msg_question",ClientMessageId("cm_question"),status,2,
        if(status==AgentMessageStatus.FAILED) AgentMessageErrorCode.AGENT_UNAVAILABLE else null,generation)
    private fun TestScope.controller(repository:Repository)=WorkbenchController(
        workbenchTestScope(),repository,object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId:String,languageCode:String)=AgentCommandCatalog(CatalogVersion("v1"),emptyList())
        },{ ConversationScope("p","g","a","i") },
        onActiveThreadChanged={ repository.generationId.value=null; repository.generationState.value=GenerationState.IDLE },
        replyTimeouts=WorkbenchController.ReplyTimeouts(enabled=false))

    private class Repository : ConversationRepository,GenerationTracker {
        override val generationId=MutableStateFlow<String?>(null)
        override val generationState=MutableStateFlow(GenerationState.IDLE)
        val events=MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity=16)
        val cancelResult=CompletableDeferred<CancelGenerationResult>()
        val cancellations=mutableListOf<Pair<String,String>>()
        override suspend fun listConversations(scope:ConversationScope,page:PageRequest)=ConversationPage(
            listOf(ConversationSummary(ConversationId("conv_a"),"A",1)),null)
        override suspend fun createConversation(scope:ConversationScope,clientConversationId:String)=Conversation(ConversationId("conv_new"),"新对话",1)
        override suspend fun timeline(conversationId:String,page:PageRequest)=TimelinePage(emptyList(),null)
        override suspend fun submitMessage(message:OutgoingMessage)=MessageAcceptance("message",message.clientMessageId.value)
        override suspend fun submitBatch(batch:MessageBatch)=BatchAcceptance(batch.batchId,emptyMap())
        override fun observeEvents(scope:ConversationScope):Flow<VerifiedConversationEvent> = events
        override suspend fun cancelGeneration(generationId:String,requestId:String):CancelGenerationResult=error("unscoped cancellation")
        override suspend fun cancelGeneration(conversationId:String,generationId:String,requestId:String):CancelGenerationResult {
            cancellations+=conversationId to generationId
            return cancelResult.await()
        }
    }
}
