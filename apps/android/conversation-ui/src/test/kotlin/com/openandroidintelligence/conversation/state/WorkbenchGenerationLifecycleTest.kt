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
    @Test fun confirmedCancellationHidesFailureReceiptsInEitherArrivalOrderAndPreservesOutput() = runTest {
        for (failureFirst in listOf(false, true)) {
            val repository = Repository()
            val controller = controller(repository)
            runCurrent()
            repository.generationId.value = "gen_a"
            repository.events.emit(VerifiedConversationEvent.TimelineUpsert("question", 1, 1,
                TimelineMessage("msg_question", "user", listOf(MessagePart.Text("问题")), 1, "DELIVERED",
                    ConversationId("conv_a"), clientMessageId = ClientMessageId("cm_question")))); runCurrent()
            repository.events.emit(stream("progress", "gen_a", "保留这段回复")); runCurrent()
            controller.stopGeneration(); runCurrent()
            if (failureFirst) {
                repository.events.emit(terminal("gen_a", AgentMessageStatus.FAILED)); runCurrent()
                assertEquals(GenerationState.CANCEL_REQUESTED, controller.state.value.generation)
                assertFalse(controller.state.value.notice.orEmpty().startsWith("AGENT_MESSAGE_FAILED:"))
                assertFalse(rows(controller).any { it.messageStatus == AgentMessageStatus.FAILED })
            }
            repository.cancelResult.complete(CancelGenerationResult(CancelGenerationOutcome.CANCELLED)); runCurrent()
            if (!failureFirst) { repository.events.emit(terminal("gen_a", AgentMessageStatus.FAILED)); runCurrent() }
            assertEquals(GenerationState.CANCELLED, controller.state.value.generation)
            assertFalse(controller.state.value.notice.orEmpty().startsWith("AGENT_MESSAGE_FAILED:"))
            assertTrue(rows(controller).any { it.text == "保留这段回复" && !it.isStreaming })
            assertFalse(rows(controller).any { it.messageStatus == AgentMessageStatus.FAILED })
            repository.generationState.value = GenerationState.FAILED; runCurrent()
            assertEquals(GenerationState.CANCELLED, controller.state.value.generation)
            repository.generationId.value = "gen_b"
            repository.events.emit(terminal("gen_b", AgentMessageStatus.FAILED).copy(
                eventId = "real_failure", revision = 3,
            )); runCurrent()
            assertEquals(GenerationState.FAILED, controller.state.value.generation)
            assertTrue(controller.state.value.notice.orEmpty().startsWith("AGENT_MESSAGE_FAILED:"))
            assertTrue(rows(controller).any { it.isUser && it.messageStatus == AgentMessageStatus.FAILED })
            controller.editDraft("继续发送")
            assertTrue(controller.state.value.canSend)
            controller.close()
        }
    }

    @Test fun aFailureReceiptWithoutGenerationUsesItsEarlierMessageAssociation() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        runCurrent()
        repository.generationId.value = "gen_a"
        repository.events.emit(terminal("gen_a", AgentMessageStatus.DELIVERED)); runCurrent()
        controller.stopGeneration(); runCurrent()
        repository.cancelResult.complete(CancelGenerationResult(CancelGenerationOutcome.CANCELLED)); runCurrent()
        repository.events.emit(terminal(null, AgentMessageStatus.FAILED).copy(eventId = "legacy_failure", revision = 3)); runCurrent()
        assertEquals(GenerationState.CANCELLED, controller.state.value.generation)
        assertNull(controller.state.value.notice)
        controller.close()
    }

    @Test fun anUnconfirmedStopDoesNotPermanentlyHideARealFailure() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        runCurrent()
        repository.generationId.value = "gen_a"
        repository.events.emit(VerifiedConversationEvent.TimelineUpsert("question", 1, 1,
            TimelineMessage("msg_question", "user", listOf(MessagePart.Text("问题")), 1, "DELIVERED",
                ConversationId("conv_a"), clientMessageId = ClientMessageId("cm_question")))); runCurrent()
        controller.stopGeneration(); runCurrent()
        repository.events.emit(terminal("gen_a", AgentMessageStatus.FAILED)); runCurrent()
        assertFalse(rows(controller).any { it.messageStatus == AgentMessageStatus.FAILED })
        repository.cancelResult.complete(CancelGenerationResult(CancelGenerationOutcome.OUTCOME_UNKNOWN)); runCurrent()
        assertTrue(rows(controller).any { it.messageStatus == AgentMessageStatus.FAILED })
        controller.close()
    }

    @Test fun cancellationEventClearsAnEarlierFailureAndLateFailureCannotRestoreIt() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        runCurrent()
        repository.generationId.value = "gen_a"
        repository.events.emit(stream("progress", "gen_a", "部分内容")); runCurrent()
        repository.events.emit(terminal("gen_a", AgentMessageStatus.FAILED)); runCurrent()
        repository.events.emit(VerifiedConversationEvent.GenerationCancelled("cancel", 4, "gen_a", ConversationId("conv_a"))); runCurrent()
        repository.events.emit(terminal("gen_a", AgentMessageStatus.FAILED).copy(eventId = "late_failure", revision = 4)); runCurrent()
        assertEquals(GenerationState.CANCELLED, controller.state.value.generation)
        assertNull(controller.state.value.notice)
        assertTrue(rows(controller).any { it.text == "部分内容" && !it.isStreaming })
        controller.close()
    }

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

    @Test fun successfulStopStillSettlesTheSameGenerationWhenALateDeltaArrivesBeforeItsReceipt() = runTest {
        for (outcome in listOf(CancelGenerationOutcome.CANCELLED,CancelGenerationOutcome.ALREADY_COMPLETED)) {
            val repository=Repository()
            val controller=controller(repository)
            runCurrent()
            repository.generationId.value="gen_a"
            controller.stopGeneration(); runCurrent()
            // The HTTP wrapper clears the tracker before returning the authoritative acknowledgement.
            repository.generationId.value=null
            repository.events.emit(stream("late_a","gen_a","同轮迟到的输出")); runCurrent()
            repository.cancelResult.complete(CancelGenerationResult(outcome)); runCurrent()
            assertEquals(if (outcome==CancelGenerationOutcome.CANCELLED) GenerationState.CANCELLED else GenerationState.COMPLETED,controller.state.value.generation)
            assertFalse(rows(controller).any { it.isStreaming })
            repository.events.emit(stream("after_ack_a","gen_a","回执后的过时输出")); runCurrent()
            assertFalse(rows(controller).any { it.isStreaming || it.text=="回执后的过时输出" })
            controller.close()
        }
    }

    @Test fun aStopReceiptDoesNotCloseAnUnacknowledgedNewLocalSubmission() = runTest {
        val repository=Repository().apply { sendGate=CompletableDeferred() }
        val controller=controller(repository)
        runCurrent()
        repository.generationId.value="gen_a"
        controller.stopGeneration(); runCurrent()
        repository.generationId.value=null
        controller.editDraft("下一轮问题")
        controller.sendDraft(); runCurrent()
        assertEquals(GenerationState.QUEUED,controller.state.value.generation)
        repository.cancelResult.complete(CancelGenerationResult(CancelGenerationOutcome.CANCELLED)); runCurrent()
        assertEquals(GenerationState.QUEUED,controller.state.value.generation)
        repository.sendGate!!.complete(Unit); runCurrent()
    }

    @Test fun aStopReceiptDoesNotOverwriteANewerRemoteGenerationAfterItsTrackerWasCleared() = runTest {
        val repository=Repository()
        val controller=controller(repository)
        runCurrent()
        repository.generationId.value="gen_a"
        controller.stopGeneration(); runCurrent()
        repository.generationId.value=null
        repository.events.emit(stream("progress_b","gen_b","下一轮输出")); runCurrent()
        repository.events.emit(terminal("gen_b",AgentMessageStatus.COMPLETED)); runCurrent()
        assertEquals(GenerationState.COMPLETED,controller.state.value.generation)
        repository.cancelResult.complete(CancelGenerationResult(CancelGenerationOutcome.CANCELLED)); runCurrent()
        assertEquals("An acknowledgement for A must not replace B's terminal outcome",GenerationState.COMPLETED,controller.state.value.generation)
    }

    @Test fun aStopReceiptDoesNotOverwriteAnExplicitOrdinaryMessageRetry() = runTest {
        assertStopDoesNotOverwriteRetry(batch=false)
    }

    @Test fun aStopReceiptDoesNotOverwriteAnExplicitBatchRetry() = runTest {
        assertStopDoesNotOverwriteRetry(batch=true)
    }

    private suspend fun TestScope.assertStopDoesNotOverwriteRetry(batch: Boolean) {
        val repository=Repository().apply { submitFailure=IllegalStateException("SEND_MESSAGE_FAILED:400") }
        val controller=controller(repository,batches=batch)
        runCurrent()
        controller.editDraft("先前失败的消息")
        controller.sendDraft(); advanceTimeBy(1501); runCurrent()
        assertTrue(controller.state.value.hasRecoverableSubmissions)
        repository.submitFailure=null
        repository.sendGate=CompletableDeferred()
        repository.generationId.value="gen_a"
        repository.generationState.value=GenerationState.RUNNING
        runCurrent()
        controller.stopGeneration(); runCurrent()
        repository.generationId.value=null
        controller.retryPendingSubmissions(); runCurrent()
        val awaitingRetry=controller.state.value.generation
        repository.cancelResult.complete(CancelGenerationResult(CancelGenerationOutcome.CANCELLED)); runCurrent()
        assertEquals("The stop receipt belongs to A, not the retry waiting for its own receipt",awaitingRetry,controller.state.value.generation)
        assertNotEquals(GenerationState.CANCELLED,controller.state.value.generation)
        repository.sendGate!!.complete(Unit); runCurrent()
        controller.close()
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

    @Test fun legacyTerminalBlocksLateUnidentifiedProgressButAllowsTheNextLocalTurn() = runTest {
        for (status in listOf(AgentMessageStatus.COMPLETED,AgentMessageStatus.FAILED)) {
            val repository=Repository()
            val controller=controller(repository)
            runCurrent()
            repository.events.emit(stream("legacy_first",null,"旧轮部分输出")); runCurrent()
            repository.events.emit(terminal(null,status)); runCurrent()
            val terminalState=controller.state.value.generation
            repository.events.emit(stream("legacy_first",null,"旧轮追加输出").copy(eventId="late_same_id",revision=2)); runCurrent()
            repository.events.emit(stream("legacy_late_new_id",null,"旧轮另一迟到分片")); runCurrent()
            assertEquals(terminalState,controller.state.value.generation)
            assertFalse(rows(controller).any { it.isStreaming })
            controller.editDraft("明确开始下一轮"); controller.sendDraft(); runCurrent()
            repository.events.emit(stream("legacy_next_turn",null,"新轮合法输出")); runCurrent()
            assertEquals(GenerationState.RUNNING,controller.state.value.generation)
            assertTrue(rows(controller).any { it.text=="新轮合法输出" && it.isStreaming })
            repository.events.emit(stream("legacy_first",null,"新轮开始后重放旧消息").copy(eventId="late_old_turn",revision=3)); runCurrent()
            assertFalse(rows(controller).any { it.text=="新轮开始后重放旧消息" })
            controller.close()
        }
    }

    @Test fun legacyTerminalBarrierIsThreadScopedAndANewerRemoteUserMessageCanOpenTheNextTurn() = runTest {
        val repository=Repository()
        val controller=controller(repository)
        runCurrent()
        repository.events.emit(stream("old_a",null,"A旧输出")); runCurrent()
        repository.events.emit(terminal(null,AgentMessageStatus.COMPLETED)); runCurrent()
        controller.openThread("conv_b"); runCurrent()
        val b=stream("new_b",null,"B合法输出")
        repository.events.emit(b.copy(message=b.message.copy(conversationId=ConversationId("conv_b")))); runCurrent()
        assertTrue(rows(controller).any { it.text=="B合法输出" && it.isStreaming })
        controller.openThread("conv_a"); runCurrent()
        repository.events.emit(stream("replayed_a",null,"A切回后旧输出")); runCurrent()
        assertFalse((controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty().any { it.isStreaming })
        repository.events.emit(VerifiedConversationEvent.TimelineUpsert("remote_user",10,1,
            TimelineMessage("remote_user","user",listOf(MessagePart.Text("远端新问题")),10,conversationId=ConversationId("conv_a")))); runCurrent()
        repository.events.emit(stream("remote_answer",null,"远端新轮输出").let { it.copy(message=it.message.copy(timestamp=11)) }); runCurrent()
        assertTrue(rows(controller).any { it.text=="远端新轮输出" && it.isStreaming })
    }

    private fun rows(controller:WorkbenchController)=(controller.state.value.timeline as Loadable.Ready).value
    private fun stream(id:String,generation:String?,text:String,state:String="STREAMING")=VerifiedConversationEvent.TimelineUpsert(
        "event_$id",2,1,TimelineMessage(id,"assistant",listOf(MessagePart.Text(text)),2,state,ConversationId("conv_a"),generationId=generation))
    private fun terminal(generation:String?,status:AgentMessageStatus)=VerifiedConversationEvent.MessageStatus(
        "terminal_$generation",3,ConversationId("conv_a"),"msg_question",ClientMessageId("cm_question"),status,2,
        if(status==AgentMessageStatus.FAILED) AgentMessageErrorCode.AGENT_UNAVAILABLE else null,generation)
    private fun TestScope.controller(repository:Repository,batches:Boolean=false)=WorkbenchController(
        workbenchTestScope(),repository,object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId:String,languageCode:String)=AgentCommandCatalog(CatalogVersion("v1"),emptyList())
        },{ ConversationScope("p","g","a","i") },
        supportsMessageBatches=batches,
        onActiveThreadChanged={ repository.generationId.value=null; repository.generationState.value=GenerationState.IDLE },
        replyTimeouts=WorkbenchController.ReplyTimeouts(enabled=false))

    private class Repository : ConversationRepository,GenerationTracker,MessageOutcomeQuery {
        override suspend fun queryMessage(conversationId:String,clientMessageId:ClientMessageId):TimelineMessage?=null
        override val generationId=MutableStateFlow<String?>(null)
        override val generationState=MutableStateFlow(GenerationState.IDLE)
        val events=MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity=16)
        val cancelResult=CompletableDeferred<CancelGenerationResult>()
        val cancellations=mutableListOf<Pair<String,String>>()
        var sendGate:CompletableDeferred<Unit>?=null
        var submitFailure:Throwable?=null
        override suspend fun listConversations(scope:ConversationScope,page:PageRequest)=ConversationPage(
            listOf(ConversationSummary(ConversationId("conv_a"),"A",1)),null)
        override suspend fun createConversation(scope:ConversationScope,clientConversationId:String)=Conversation(ConversationId("conv_new"),"新对话",1)
        override suspend fun timeline(conversationId:String,page:PageRequest)=TimelinePage(emptyList(),null)
        override suspend fun submitMessage(message:OutgoingMessage):MessageAcceptance {
            sendGate?.await()
            submitFailure?.let { throw it }
            return MessageAcceptance("message",message.clientMessageId.value)
        }
        override suspend fun submitBatch(batch:MessageBatch):BatchAcceptance {
            sendGate?.await()
            submitFailure?.let { throw it }
            return BatchAcceptance(batch.batchId,batch.messages.associate { it.clientMessageId.value to "message_${it.clientMessageId.value}" })
        }
        override fun observeEvents(scope:ConversationScope):Flow<VerifiedConversationEvent> = events
        override suspend fun cancelGeneration(generationId:String,requestId:String):CancelGenerationResult=error("unscoped cancellation")
        override suspend fun cancelGeneration(conversationId:String,generationId:String,requestId:String):CancelGenerationResult {
            cancellations+=conversationId to generationId
            return cancelResult.await()
        }
    }
}
