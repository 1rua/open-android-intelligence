package com.openandroidintelligence.mobile

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class BubbleAttachmentDraftCoordinatorTest {
    @Test fun bubbleDraftsUseTheSharedUploaderWithoutAdoptingOrDeletingMainDrafts() = runBlocking {
        val delegate = Uploads()
        val mainDraft = delegate.prepare(selection("main.pdf"))
        val bubble = BubbleAttachmentDraftCoordinator(delegate)
        val anotherBubble = BubbleAttachmentDraftCoordinator(delegate)
        assertTrue(bubble.restoredDrafts().isEmpty())

        val draft = bubble.prepare(selection("bubble.pdf"))
        assertFalse("bubble staging must not be restored into the main composer after restart", delegate.selections.last().recoverAfterRestart)
        assertEquals(listOf(draft.id), bubble.restoredDrafts().map { it.id })
        assertTrue(anotherBubble.restoredDrafts().isEmpty())
        bubble.discard(mainDraft.id.value)
        assertNotNull(delegate.drafts[mainDraft.id.value])

        delegate.set(draft.id.value, AttachmentState.RETRYABLE_FAILURE)
        bubble.retry(draft.id.value)
        assertEquals(listOf(draft.id.value), delegate.retries)
        assertEquals(AttachmentState.VERIFIED, bubble.observe(draft.id.value).first().state)
        assertEquals("remote_${draft.id.value}", bubble.remoteAttachmentId(draft.id.value))

        bubble.releaseAfterSubmit(mainDraft.id.value)
        assertTrue(delegate.released.isEmpty())
        bubble.releaseAfterSubmit(draft.id.value)
        assertEquals(listOf(draft.id.value), delegate.released)
        assertTrue(bubble.restoredDrafts().isEmpty())
        assertNotNull(delegate.drafts[mainDraft.id.value])
        bubble.close(this).join()
        assertTrue("closing cannot discard an attachment that already moved into message history", delegate.discarded.isEmpty())
    }

    @Test fun attachmentOnlyBubbleSendWaitsForVerificationAndUsesTheRemoteIdInTheSameConversation() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val uploads = Uploads()
        val host = Host()
        val main = controller(scope, host, uploads)
        val bubble = controller(scope, host, BubbleAttachmentDraftCoordinator(uploads))
        try {
            main.openThread("main")
            main.editDraft("主窗口草稿")
            main.addAttachment(selection("main.pdf"))
            bubble.openThread("historical-thread")
            bubble.addAttachment(selection("bubble.pdf"))
            val mainDraft = main.state.value.attachments.single()
            val draft = bubble.state.value.attachments.single()
            assertTrue("an attachment is enough to submit an otherwise empty composer", bubble.state.value.canSend)
            bubble.sendDraft()
            yield()
            assertTrue("a local selection must not be sent before upload verification", host.sent.isEmpty())

            uploads.set(draft.id.value, AttachmentState.RETRYABLE_FAILURE)
            bubble.retryAttachment(draft.id.value)
            yield()
            assertEquals(1, host.sent.size)
            val (conversationId, message) = host.sent.single()
            assertEquals("historical-thread", conversationId)
            assertEquals("", message.text)
            assertEquals(listOf("remote_${draft.id.value}"), message.attachmentIds)
            assertEquals(listOf(draft.id.value), uploads.released)
            assertTrue(bubble.state.value.attachments.isEmpty())
            assertEquals("main", main.state.value.activeThreadId)
            assertEquals("主窗口草稿", main.state.value.draft)
            assertEquals(listOf(mainDraft.id), main.state.value.attachments.map { it.id })

            val clientId = message.clientMessageId
            host.events.emit(VerifiedConversationEvent.MessageStatus("completed", 10, ConversationId(conversationId),
                "accepted", clientId, AgentMessageStatus.COMPLETED, 1, null))
            yield()
            val row = (bubble.state.value.timeline as Loadable.Ready).value.single { it.isUser }
            assertEquals(AgentMessageStatus.COMPLETED, row.messageStatus)
            assertEquals("remote_${draft.id.value}", row.attachments.single().draftId)
        } finally {
            bubble.close()
            main.close()
            job.cancel()
        }
    }

    @Test fun removingABubbleDraftDiscardsOnlyItsOwnSharedUpload() = runBlocking {
        val delegate = Uploads()
        val main = delegate.prepare(selection("main.pdf"))
        val bubble = BubbleAttachmentDraftCoordinator(delegate)
        val own = bubble.prepare(selection("bubble.pdf"))
        bubble.discard(own.id.value)
        assertEquals(listOf(own.id.value), delegate.discarded)
        assertEquals(listOf(main.id), delegate.restoredDrafts().map { it.id })
        assertTrue(bubble.restoredDrafts().isEmpty())
    }

    @Test fun finalControllerReleaseReclaimsUnsentUploadsAndSessionTeardownDoesTheSame() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val uploads = Uploads()
        val mainDraft = uploads.prepare(selection("main.pdf"))
        val coordinators = mutableListOf<BubbleAttachmentDraftCoordinator>()
        val workspace = BubbleConversationWorkspace("profile", "account") { id ->
            val attachments = BubbleAttachmentDraftCoordinator(uploads).also { coordinators += it }
            controller(scope, Host(), attachments).also { it.openThread(id) }
        }
        try {
            val first = workspace.open(ReplyNotificationTarget("profile", "account", "first"))!!
            first.addAttachment(selection("first.pdf"))
            val firstDraft = first.state.value.attachments.single().id.value
            assertTrue(workspace.release(first))
            coordinators.first().close(scope).join()
            assertEquals(listOf(firstDraft), uploads.discarded)

            val second = workspace.open(ReplyNotificationTarget("profile", "account", "second"))!!
            second.addAttachment(selection("second.pdf"))
            val secondDraft = second.state.value.attachments.single().id.value
            workspace.close()
            coordinators.last().close(scope).join()
            workspace.close()
            first.close()
            second.close()
            assertEquals(listOf(firstDraft, secondDraft), uploads.discarded)
            assertEquals(listOf(mainDraft.id), uploads.restoredDrafts().map { it.id })
        } finally { workspace.close(); job.cancel() }
    }

    @Test fun closingAnAlreadyCancelledSessionStillReclaimsItsBubbleUploadsExactlyOnce() = runBlocking {
        val uploads = Uploads()
        val bubble = BubbleAttachmentDraftCoordinator(uploads)
        val draft = bubble.prepare(selection("bubble.pdf"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.cancel()
        val cleanup = bubble.close(scope)
        cleanup.join()
        assertSame(cleanup, bubble.close(scope))
        assertEquals(listOf(draft.id.value), uploads.discarded)
        assertTrue(uploads.restoredDrafts().isEmpty())
    }

    @Test fun closingWhilePreparationCompletesReclaimsTheLateDraft() = runBlocking {
        val uploads = Uploads().apply { prepared = CompletableDeferred() }
        val bubble = BubbleAttachmentDraftCoordinator(uploads)
        val preparing = async { bubble.prepare(selection("late.pdf")) }
        yield()
        bubble.close(this).join()
        uploads.prepared!!.complete(Unit)
        try {
            preparing.await()
            fail("a closed bubble must reject the late selection")
        } catch (_: CancellationException) { }
        assertEquals(1, uploads.discarded.size)
        assertTrue(uploads.restoredDrafts().isEmpty())
    }

    private fun controller(scope: CoroutineScope, host: Host, uploads: AttachmentDraftCoordinator) = WorkbenchController(
        scope, host,
        object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("test"), emptyList())
        },
        { ConversationScope("profile", "gateway", "account", "install") },
        attachmentCoordinator = uploads,
        onClosed = { (uploads as? BubbleAttachmentDraftCoordinator)?.close(scope) },
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
        mediaDispatcher = Dispatchers.Unconfined,
    )

    private fun selection(name: String) = LocalAttachmentSelection(name, "application/pdf",
        AttachmentContentSource { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })

    private class Uploads : AttachmentDraftCoordinator {
        val selections = mutableListOf<LocalAttachmentSelection>()
        val drafts = linkedMapOf<String, AttachmentDraft>()
        private val states = mutableMapOf<String, MutableStateFlow<AttachmentDraftState>>()
        val retries = mutableListOf<String>()
        val released = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        private var nextId = 0
        var prepared: CompletableDeferred<Unit>? = null

        override fun restoredDrafts() = drafts.values.toList()
        override suspend fun prepare(selection: LocalAttachmentSelection): AttachmentDraft {
            selections += selection
            val id = "draft_${++nextId}"
            val draft = AttachmentDraft(AttachmentDraftId(id), selection.filename, selection.mediaType, 3,
                "0".repeat(64), AttachmentState.UPLOADING).also {
                drafts[id] = it
                states[id] = MutableStateFlow(AttachmentDraftState(it.id, it.state))
            }
            prepared?.await()
            return draft
        }
        fun set(id: String, state: AttachmentState) {
            drafts[id] = drafts.getValue(id).copy(state = state)
            states.getValue(id).value = AttachmentDraftState(AttachmentDraftId(id), state)
        }
        override fun observe(draftId: String) = states.getValue(draftId)
        override fun retry(draftId: String) { retries += draftId; set(draftId, AttachmentState.VERIFIED) }
        override fun remoteAttachmentId(draftId: String) = "remote_$draftId".takeIf { drafts[draftId]?.state == AttachmentState.VERIFIED }
        override suspend fun discard(draftId: String) { discarded += draftId; drafts.remove(draftId) }
        override suspend fun releaseAfterSubmit(draftId: String) { released += draftId; drafts.remove(draftId) }
        override suspend fun armSubmission(draftId: String, revision: Long): PendingSubmissionIntent = error("Workbench owns submission")
        override suspend fun cancelSubmission(intentId: String) = CancelSubmissionResult(true)
    }

    private class Host : ConversationRepository {
        val sent = mutableListOf<Pair<String, OutgoingMessage>>()
        val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 8)
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(emptyList(), null)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation = error("Unexpected create")
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance = error("Attachment sends cannot batch")
        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance = error("Conversation required")
        override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
            sent += conversationId to message
            return MessageAcceptance("accepted", message.clientMessageId.value)
        }
        override fun observeEvents(scope: ConversationScope) = events
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
