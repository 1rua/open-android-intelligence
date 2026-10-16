package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchCloseSubmissionTest {
    @Test fun closingAnOrdinaryRequestCommitsUnknownBeforeClosingItsStoreAndQueriesTheOriginalIdentity() = runTest {
        assertClosingInFlight(voice=false)
    }
    @Test fun closingAVoiceRequestKeepsItsIdentityAndSourceRecoverable() = runTest {
        assertClosingInFlight(voice=true)
    }
    private suspend fun TestScope.assertClosingInFlight(voice:Boolean) {
        val store=Store()
        val repository=Repository()
        val uploads=Uploads()
        val controller=controller(repository,store,uploads)
        runCurrent()
        val voiceJob=if(voice) async { controller.sendVoiceAttachment(LocalAttachmentSelection("voice.m4a","audio/mp4",
            AttachmentContentSource { ByteArrayInputStream(byteArrayOf(1,2,3)) }),"conv_a") } else null
        if(!voice) { controller.editDraft("请求已发出但回执未到"); controller.sendDraft() }
        runCurrent()
        val submitted=repository.messages.single()
        assertEquals(LocalSubmissionState.PREPARED,store.loadSendAtoms("conv_a").single().submissionState)
        val closing=async { controller.closeAndAwait() }
        runCurrent(); closing.await(); voiceJob?.await()
        assertEquals(LocalSubmissionState.OUTCOME_UNKNOWN,store.loadSendAtoms("conv_a").single().submissionState)
        assertEquals(listOf(LocalSubmissionState.OUTCOME_UNKNOWN),store.statesAtClose)
        assertTrue(uploads.discarded.isEmpty())
        assertEquals(submitted.clientMessageId,store.loadSendAtoms("conv_a").single().clientMessageId)
        store.closed=false
        repository.block=false
        repository.accepted[submitted.clientMessageId.value]=TimelineMessage("accepted_original","user",emptyList(),1,
            conversationId=ConversationId("conv_a"),clientMessageId=submitted.clientMessageId)
        val restarted=controller(repository,store,uploads)
        runCurrent()
        assertTrue(restarted.state.value.hasRecoverableSubmissions)
        if (voice) assertTrue("The independent recording must not restore into the shared composer",restarted.state.value.attachments.isEmpty())
        restarted.retryPendingSubmissions(); runCurrent()
        assertEquals(listOf(submitted.clientMessageId),repository.queries)
        assertEquals("Recovery must query before any new POST",1,repository.messages.size)
        assertEquals(LocalSubmissionState.ACCEPTED,store.loadSendAtoms("conv_a").single().submissionState)
        restarted.closeAndAwait()
    }

    @Test fun closingABatchCommitsEveryUnacceptedMemberWithoutDowngradingAnAcceptedOne() = runTest {
        val store=Store()
        val repository=Repository()
        val controller=controller(repository,store,Uploads(),batches=true)
        runCurrent()
        controller.editDraft("第一个成员"); controller.sendDraft(); runCurrent()
        controller.editDraft("第二个成员"); controller.sendDraft(); runCurrent()
        advanceTimeBy(1501); runCurrent()
        val batch=repository.batches.single()
        val first=batch.messages.first()
        repository.events.emit(VerifiedConversationEvent.MessageStatus("accepted_first",2,ConversationId("conv_a"),"accepted_first",
            first.clientMessageId,AgentMessageStatus.DELIVERED,1,null)); runCurrent()
        val closing=async { controller.closeAndAwait() }; runCurrent(); closing.await()
        val atoms=store.loadSendAtoms("conv_a")
        assertEquals(setOf(LocalSubmissionState.ACCEPTED,LocalSubmissionState.OUTCOME_UNKNOWN),atoms.map { it.submissionState }.toSet())
        assertEquals(LocalSubmissionState.ACCEPTED,atoms.single { it.clientMessageId==first.clientMessageId }.submissionState)
        assertEquals(batch.messages.map { it.clientMessageId }.toSet(),atoms.map { it.clientMessageId }.toSet())
        assertEquals(setOf(batch.batchId),atoms.map { it.batchId }.toSet())
        assertEquals(atoms.map { it.submissionState },store.statesAtClose)
    }

    @Test fun queryingVoiceReadinessDoesNotStageAndTracksTheSelectedThreadAndPendingRequest() = runTest {
        val store=Store()
        val repository=Repository()
        val uploads=Uploads()
        val controller=controller(repository,store,uploads)
        runCurrent()
        assertTrue(controller.canSendVoiceAttachment("conv_a"))
        assertFalse(controller.canSendVoiceAttachment("conv_b"))
        assertEquals(0,uploads.prepares)
        controller.editDraft("在途文字"); controller.sendDraft(); runCurrent()
        assertFalse(controller.canSendVoiceAttachment("conv_a"))
        controller.closeAndAwait()
        assertFalse(controller.canSendVoiceAttachment("conv_a"))
        assertEquals(0,uploads.prepares)
    }

    private fun TestScope.controller(repository:Repository,store:Store,uploads:Uploads,batches:Boolean=false)=WorkbenchController(
        workbenchTestScope(),repository,object:AgentCommandCatalogRepository {
            override suspend fun get(gatewayId:String,languageCode:String)=AgentCommandCatalog(CatalogVersion("test"),emptyList())
        },{ConversationScope("p","g","a","i")},attachmentCoordinator=uploads,persistence=store,
        persistenceDispatcher=UnconfinedTestDispatcher(testScheduler),supportsMessageBatches=batches,
        replyTimeouts=WorkbenchController.ReplyTimeouts(enabled=false))
    private class Store:AwaitableWorkbenchPersistence {
        var closed=false
        var checkpoint:WorkbenchCheckpoint?=null
        val atoms=linkedMapOf<String,List<LocalSendAtom>>()
        var statesAtClose=emptyList<LocalSubmissionState>()
        override fun load()=checkpoint
        override fun save(checkpoint:WorkbenchCheckpoint) { check(!closed); this.checkpoint=checkpoint }
        override suspend fun saveCheckpoint(checkpoint:WorkbenchCheckpoint)=save(checkpoint)
        override fun loadSendAtoms(conversationId:String)=atoms[conversationId].orEmpty()
        override fun saveSendAtoms(conversationId:String,atoms:List<LocalSendAtom>) { check(!closed); this.atoms[conversationId]=atoms.toList() }
        override suspend fun saveSendAtomsDurably(conversationId:String,atoms:List<LocalSendAtom>)=saveSendAtoms(conversationId,atoms)
        override suspend fun loadTimeline(conversationId:String)=TimelinePage(emptyList(),null)
        override suspend fun loadKnownMediaMessages()=emptyList<TimelineMessage>()
        override suspend fun flush() { check(!closed) }
        override suspend fun closeStore() { statesAtClose=atoms.values.flatten().map { it.submissionState }; closed=true }
    }
    private class Repository:ConversationRepository,MessageOutcomeQuery {
        var block=true
        val messages=mutableListOf<OutgoingMessage>()
        val batches=mutableListOf<MessageBatch>()
        val accepted=linkedMapOf<String,TimelineMessage>()
        val queries=mutableListOf<ClientMessageId>()
        val events=MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity=16)
        override suspend fun queryMessage(conversationId:String,clientMessageId:ClientMessageId):TimelineMessage? { queries+=clientMessageId; return accepted[clientMessageId.value] }
        override suspend fun listConversations(scope:ConversationScope,page:PageRequest)=ConversationPage(listOf(ConversationSummary(ConversationId("conv_a"),"A",1)),null)
        override suspend fun createConversation(scope:ConversationScope,clientConversationId:String)=error("unused")
        override suspend fun timeline(conversationId:String,page:PageRequest)=TimelinePage(emptyList(),null)
        override suspend fun submitMessage(message:OutgoingMessage):MessageAcceptance { messages+=message; if(block) awaitCancellation(); return MessageAcceptance("accepted_original",message.clientMessageId.value) }
        override suspend fun submitBatch(batch:MessageBatch):BatchAcceptance { batches+=batch; if(block) awaitCancellation(); return BatchAcceptance(batch.batchId,batch.messages.associate { it.clientMessageId.value to "accepted_${it.clientMessageId.value}" }) }
        override fun observeEvents(scope:ConversationScope)=events
        override suspend fun cancelGeneration(generationId:String,requestId:String)=CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
    private class Uploads:AttachmentDraftCoordinator {
        val discarded=mutableListOf<String>()
        var prepares=0
        override suspend fun prepare(selection:LocalAttachmentSelection):AttachmentDraft {
            prepares++
            return AttachmentDraft(AttachmentDraftId("voice"),selection.filename,selection.mediaType,3,"0".repeat(64),AttachmentState.VERIFIED)
        }
        override fun observe(draftId:String)=flowOf(AttachmentDraftState(AttachmentDraftId(draftId),AttachmentState.VERIFIED))
        override fun remoteAttachmentId(draftId:String)="remote_voice"
        override suspend fun armSubmission(draftId:String,revision:Long)=error("unused")
        override suspend fun cancelSubmission(intentId:String)=CancelSubmissionResult(true)
        override fun retry(draftId:String)=Unit
        override suspend fun discard(draftId:String) { discarded+=draftId }
        override suspend fun releaseAfterSubmit(draftId:String)=Unit
    }
}
