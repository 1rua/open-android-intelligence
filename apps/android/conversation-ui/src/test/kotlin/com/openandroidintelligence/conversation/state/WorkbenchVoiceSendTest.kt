package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchVoiceSendTest {
    @Test fun voiceWaitsForStagingAndVerificationAndPreservesExistingDraftAndAttachment() = runTest {
        val uploads = Uploads()
        val repository = Repository()
        val controller = controller(repository, uploads)
        runCurrent()
        controller.openThread("thread_a"); runCurrent()
        controller.editDraft("尚未准备发送的文字")
        controller.addAttachment(selection("notes.txt", "text/plain")); runCurrent()
        val existing = controller.state.value.attachments.single()
        uploads.stageGate = CompletableDeferred()
        val voice = async { controller.sendVoiceAttachment(selection(), "thread_a") }
        runCurrent()
        assertTrue(repository.sent.isEmpty())
        assertFalse(voice.isCompleted)
        uploads.stageGate!!.complete(Unit); runCurrent()
        assertTrue(repository.sent.isEmpty())
        assertFalse(voice.isCompleted)
        uploads.completeVoice(AttachmentState.VERIFIED); runCurrent()
        assertTrue(voice.await())
        assertEquals("", repository.sent.single().second.text)
        assertEquals("thread_a", repository.sent.single().first)
        assertEquals(listOf("remote_voice"), repository.sent.single().second.attachmentIds)
        assertEquals("尚未准备发送的文字", controller.state.value.draft)
        assertEquals(listOf(existing.id), controller.state.value.attachments.map { it.id })
        assertEquals(listOf("voice"), uploads.released)
        controller.cancel()
    }

    @Test fun switchingThreadsDuringUploadDiscardsVoiceAndCannotSendToEitherThread() = runTest {
        val uploads = Uploads()
        val repository = Repository()
        val controller = controller(repository, uploads)
        runCurrent(); controller.openThread("thread_a"); runCurrent()
        val voice = async { controller.sendVoiceAttachment(selection(), "thread_a") }
        runCurrent()
        controller.openThread("thread_b"); runCurrent()
        uploads.completeVoice(AttachmentState.VERIFIED); runCurrent()
        assertFalse(voice.await())
        assertTrue(repository.sent.isEmpty())
        assertEquals("thread_b", controller.state.value.activeThreadId)
        assertTrue(controller.state.value.attachments.isEmpty())
        assertEquals(listOf("voice"), uploads.discarded)
        controller.cancel()
    }

    @Test fun cancellingUploadDeletesTheVoiceDraftWithoutTouchingText() = runTest {
        val uploads = Uploads()
        val repository = Repository()
        val controller = controller(repository, uploads)
        runCurrent(); controller.openThread("thread_a"); runCurrent()
        controller.editDraft("保留")
        val voice = async { controller.sendVoiceAttachment(selection(), "thread_a") }
        runCurrent(); voice.cancelAndJoin()
        assertEquals(listOf("voice"), uploads.discarded)
        assertTrue(repository.sent.isEmpty())
        assertEquals("保留", controller.state.value.draft)
        assertTrue(controller.state.value.attachments.isEmpty())
        controller.cancel()
    }

    @Test fun stoppingWaitDuringSubmissionPreservesUnknownAudioSourceOutsideComposer() = runTest {
        val uploads = Uploads()
        val repository = Repository().apply { submitGate = CompletableDeferred() }
        val controller = controller(repository, uploads)
        runCurrent(); controller.openThread("thread_a"); runCurrent()
        controller.editDraft("保留")
        val voice = async { controller.sendVoiceAttachment(selection(), "thread_a") }
        runCurrent(); uploads.completeVoice(AttachmentState.VERIFIED); runCurrent()
        assertEquals(1, repository.sent.size)
        assertFalse(voice.isCompleted)
        voice.cancelAndJoin(); runCurrent()
        assertEquals("保留", controller.state.value.draft)
        assertTrue(controller.state.value.attachments.isEmpty())
        assertTrue("An uncertain POST without an archive must retain its staged original", uploads.discarded.isEmpty())
        assertEquals(GenerationState.OUTCOME_UNKNOWN, controller.state.value.generation)
        assertTrue(controller.state.value.notice.orEmpty().contains("OUTCOME_UNKNOWN"))
        // The already-issued request may have landed; stopping the wait must never retry it.
        assertEquals(1, repository.sent.size)
        controller.cancel()
    }

    @Test fun failedUploadDoesNotSendOrContaminateComposer() = runTest {
        val uploads = Uploads()
        val repository = Repository()
        val controller = controller(repository, uploads)
        runCurrent(); controller.openThread("thread_a"); runCurrent()
        controller.editDraft("保留")
        val voice = async { controller.sendVoiceAttachment(selection(), "thread_a") }
        runCurrent(); uploads.completeVoice(AttachmentState.TERMINAL_FAILURE); runCurrent()
        assertFalse(voice.await())
        assertTrue(repository.sent.isEmpty())
        assertEquals("保留", controller.state.value.draft)
        assertTrue(controller.state.value.attachments.isEmpty())
        assertEquals(listOf("voice"), uploads.discarded)
        assertTrue(controller.state.value.notice.orEmpty().contains("VOICE_SEND_FAILED"))
        controller.cancel()
    }

    @Test fun closedControllerCannotSendAnUploadThatFinishesLater() = runTest {
        val uploads = Uploads()
        val repository = Repository()
        val controller = controller(repository, uploads)
        runCurrent(); controller.openThread("thread_a"); runCurrent()
        val voice = async { controller.sendVoiceAttachment(selection(), "thread_a") }
        runCurrent(); controller.cancel()
        uploads.completeVoice(AttachmentState.VERIFIED); runCurrent()
        assertFalse(voice.await())
        assertTrue(repository.sent.isEmpty())
        assertEquals(listOf("voice"), uploads.discarded)
    }

    @Test fun switchingDuringArchiveCannotPostOrLeaveVerifiedVoiceInTheNextComposer() = runTest {
        val uploads = Uploads().apply { archiveGate=CompletableDeferred() }
        val repository = Repository()
        val media = object : HistoricalMediaPort {
            override suspend fun metadata(conversationId:String,attachmentId:String):HistoricalMediaMetadata=error("unused")
            override suspend fun retain(metadata:HistoricalMediaMetadata)=Unit
            override fun isRetained(attachmentId:String)=false
            override fun preview(attachmentId:String):ByteArray?=null
            override fun remove(attachmentId:String)=Unit
        }
        val controller = controller(repository,uploads,media)
        runCurrent(); controller.openThread("thread_a"); runCurrent()
        controller.editDraft("保留文字")
        val voice = async { controller.sendVoiceAttachment(selection(),"thread_a") }
        runCurrent(); uploads.completeVoice(AttachmentState.VERIFIED); runCurrent()
        assertTrue(uploads.archiveStarted.isCompleted)
        assertTrue(repository.sent.isEmpty())
        controller.openThread("thread_b"); runCurrent()
        uploads.archiveGate!!.complete(Unit); runCurrent()
        assertFalse(voice.await())
        assertTrue(repository.sent.isEmpty())
        assertEquals("thread_b",controller.state.value.activeThreadId)
        assertEquals("保留文字",controller.state.value.draft)
        assertTrue(controller.state.value.attachments.isEmpty())
        assertEquals(listOf("voice"),uploads.discarded)
        controller.openThread("thread_a"); runCurrent()
        val timeline=controller.state.value.timeline
        assertTrue(timeline is Loadable.Empty || (timeline is Loadable.Ready && timeline.value.isEmpty()))
        controller.cancel()
    }

    @Test fun generationInProgressRejectsVoiceBeforeStaging() = runTest {
        val uploads = Uploads()
        val repository = Repository()
        val controller = controller(repository, uploads)
        runCurrent(); controller.openThread("thread_a"); runCurrent()
        controller.editDraft("发送文字"); controller.sendDraft(); runCurrent()
        assertEquals(GenerationState.QUEUED, controller.state.value.generation)
        assertFalse(controller.sendVoiceAttachment(selection(), "thread_a"))
        assertEquals(0, uploads.voicePrepares)
        assertEquals(1, repository.sent.size)
        controller.cancel()
    }

    @Test fun unknownPreviousSubmissionRejectsAnotherVoiceBeforeStaging() = runTest {
        val uploads = Uploads()
        val repository = Repository().apply { submitGate = CompletableDeferred() }
        val controller = controller(repository, uploads)
        runCurrent(); controller.openThread("thread_a"); runCurrent()
        val first = async { controller.sendVoiceAttachment(selection(), "thread_a") }
        runCurrent(); uploads.completeVoice(AttachmentState.VERIFIED); runCurrent()
        first.cancelAndJoin(); runCurrent()
        assertEquals(GenerationState.OUTCOME_UNKNOWN, controller.state.value.generation)
        assertFalse(controller.sendVoiceAttachment(selection("second.m4a"), "thread_a"))
        assertEquals("Unresolved sends must block recording staging and duplicate submission", 1, uploads.voicePrepares)
        assertEquals(1, repository.sent.size)
        assertTrue(uploads.discarded.isEmpty())
        controller.cancel()
    }

    @Test fun definitiveMessageRejectionRemovesOnlyVoiceBeforeSendingThePreservedDraft() = runTest {
        val uploads=Uploads()
        val repository=Repository().apply { submitFailure=IllegalStateException("SEND_MESSAGE_FAILED:400") }
        val controller=controller(repository,uploads)
        runCurrent(); controller.openThread("thread_a"); runCurrent()
        controller.editDraft("保留的文字")
        controller.addAttachment(selection("notes.txt","text/plain")); runCurrent()
        val existing=controller.state.value.attachments.single().id
        val voice=async { controller.sendVoiceAttachment(selection(),"thread_a") }
        runCurrent(); uploads.completeVoice(AttachmentState.VERIFIED); runCurrent()
        assertFalse(voice.await())
        assertEquals("保留的文字",controller.state.value.draft)
        assertEquals("A rejected recording must leave the original composer attachments intact",listOf(existing),controller.state.value.attachments.map { it.id })
        assertTrue("Keep the failed recording's original for an explicit recovery",uploads.discarded.isEmpty())
        assertTrue(controller.state.value.hasRecoverableSubmissions)
        val rejectedVoice=repository.sent.single().second
        repository.submitFailure=null
        controller.sendDraft(); runCurrent()
        assertEquals("保留的文字",repository.sent.last().second.text)
        assertEquals(listOf("remote_existing"),repository.sent.last().second.attachmentIds)
        controller.editDraft("稍后再发的草稿")
        controller.addAttachment(selection("later.txt","text/plain")); runCurrent()
        val preservedAttachments=controller.state.value.attachments.map { it.id }
        controller.retryPendingSubmissions(); runCurrent()
        assertEquals(rejectedVoice.clientMessageId,repository.sent.last().second.clientMessageId)
        assertEquals(listOf("remote_voice"),repository.sent.last().second.attachmentIds)
        assertEquals("",repository.sent.last().second.text)
        assertEquals("稍后再发的草稿",controller.state.value.draft)
        assertEquals(preservedAttachments,controller.state.value.attachments.map { it.id })
        assertFalse(controller.state.value.hasRecoverableSubmissions)
        controller.cancel()
    }

    @Test fun lostVoiceMessageReceiptRetainsRecoverySourceOutsideThePreservedComposer() = runTest {
        val uploads=Uploads()
        val repository=Repository().apply { submitFailure=java.io.IOException("response lost") }
        val controller=controller(repository,uploads)
        runCurrent(); controller.openThread("thread_a"); runCurrent()
        controller.editDraft("保留的文字")
        controller.addAttachment(selection("notes.txt","text/plain")); runCurrent()
        val existing=controller.state.value.attachments.single().id
        val voice=async { controller.sendVoiceAttachment(selection(),"thread_a") }
        runCurrent(); uploads.completeVoice(AttachmentState.VERIFIED); runCurrent()
        assertFalse(voice.await())
        assertEquals("保留的文字",controller.state.value.draft)
        assertEquals(listOf(existing),controller.state.value.attachments.map { it.id })
        assertTrue("An unknown POST still needs its staged original",uploads.discarded.isEmpty())
        assertEquals(GenerationState.OUTCOME_UNKNOWN,controller.state.value.generation)
        assertTrue(controller.state.value.hasRecoverableSubmissions)
        controller.cancel()
    }

    @Test fun pendingCreationBlocksVoiceOnlyInItsSourceThread() = runTest {
        val uploads=Uploads()
        val repository=Repository().apply { createGate=CompletableDeferred() }
        val controller=controller(repository,uploads)
        runCurrent(); controller.openThread("thread_a"); runCurrent()
        controller.createThread(); runCurrent()
        assertFalse(controller.sendVoiceAttachment(selection(),"thread_a"))
        assertEquals(0,uploads.voicePrepares)
        controller.openThread("thread_b"); runCurrent()
        val voice=async { controller.sendVoiceAttachment(selection(),"thread_b") }
        runCurrent(); uploads.completeVoice(AttachmentState.VERIFIED); runCurrent()
        assertTrue(voice.await())
        assertEquals("thread_b",repository.sent.single().first)
        repository.createGate!!.complete(Unit); runCurrent()
        assertEquals("thread_b",controller.state.value.activeThreadId)
        controller.cancel()
    }

    @Test fun voiceCanBeTheFirstMessageWithoutAnExistingThread() = runTest {
        val uploads = Uploads()
        val repository = Repository()
        val controller = controller(repository, uploads)
        runCurrent()
        val voice = async { controller.sendVoiceAttachment(selection(), null) }
        runCurrent(); uploads.completeVoice(AttachmentState.VERIFIED); runCurrent()
        assertTrue(voice.await())
        assertEquals("created", repository.sent.single().first)
        assertEquals(listOf("remote_voice"), repository.sent.single().second.attachmentIds)
        controller.cancel()
    }

    private fun TestScope.controller(repository: Repository, uploads: Uploads, media: HistoricalMediaPort? = null) = WorkbenchController(
        workbenchTestScope(), repository,
        object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        },
        { ConversationScope("profile", "gateway", "account", "install") },
        attachmentCoordinator = uploads,
        media = media,
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
    )

    private fun selection(name: String = "voice_test.m4a", mime: String = "audio/mp4") =
        LocalAttachmentSelection(name, mime, AttachmentContentSource { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })

    private class Uploads : AttachmentDraftCoordinator {
        var voicePrepares = 0
        var stageGate: CompletableDeferred<Unit>? = null
        var archiveGate: CompletableDeferred<Unit>? = null
        val archiveStarted = CompletableDeferred<Unit>()
        override suspend fun archiveSubmitted(conversationId:String,draftId:String,media:HistoricalMediaPort) {
            archiveStarted.complete(Unit)
            archiveGate?.await()
        }
        val discarded = mutableListOf<String>()
        val released = mutableListOf<String>()
        private val voiceState = MutableStateFlow(AttachmentDraftState(AttachmentDraftId("voice"), AttachmentState.UPLOADING))
        override suspend fun prepare(selection: LocalAttachmentSelection): AttachmentDraft {
            val voice = selection.mediaType.startsWith("audio/")
            if (voice) { voicePrepares++; stageGate?.await() }
            val bytes = selection.contentSource.openStream().use { it.readBytes() }
            return AttachmentDraft(AttachmentDraftId(if (voice) "voice" else "existing"), selection.filename,
                selection.mediaType, bytes.size.toLong(), "0".repeat(64),
                if (voice) AttachmentState.UPLOADING else AttachmentState.VERIFIED)
        }
        fun completeVoice(state: AttachmentState) { voiceState.value = AttachmentDraftState(AttachmentDraftId("voice"), state, errorMessage = if (state == AttachmentState.TERMINAL_FAILURE) "UPLOAD_REJECTED" else null) }
        override fun observe(draftId: String) = if (draftId == "voice") voiceState else MutableStateFlow(AttachmentDraftState(AttachmentDraftId(draftId), AttachmentState.VERIFIED))
        override suspend fun armSubmission(draftId: String, revision: Long) = PendingSubmissionIntent(SubmitIntentId("intent"), ClientMessageId("client"), revision, "")
        override suspend fun cancelSubmission(intentId: String) = CancelSubmissionResult(true)
        override fun retry(draftId: String) = Unit
        override suspend fun discard(draftId: String) { discarded += draftId }
        override suspend fun releaseAfterSubmit(draftId: String) { released += draftId }
        override fun remoteAttachmentId(draftId: String) = "remote_$draftId"
    }

    private class Repository : ConversationRepository, MessageOutcomeQuery {
        override suspend fun queryMessage(conversationId:String,clientMessageId:ClientMessageId):TimelineMessage?=null
        val sent = mutableListOf<Pair<String, OutgoingMessage>>()
        var submitGate: CompletableDeferred<Unit>? = null
        var submitFailure: Throwable? = null
        var createGate: CompletableDeferred<Unit>? = null
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(emptyList(), null)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation {
            createGate?.await()
            return Conversation(ConversationId("created"), "新对话", 0)
        }
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance = error("UNSCOPED_SEND")
        override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
            sent += conversationId to message
            submitGate?.await()
            submitFailure?.let { throw it }
            return MessageAcceptance("message_${sent.size}", message.clientMessageId.value)
        }
        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance = error("UNEXPECTED_BATCH")
        override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.CANCELLED)
    }
}
