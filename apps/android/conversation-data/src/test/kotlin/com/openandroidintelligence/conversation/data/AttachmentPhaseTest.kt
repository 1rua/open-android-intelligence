package com.openandroidintelligence.conversation.data

import com.openandroidintelligence.conversation.attachment.AttachmentSubmissionGate
import com.openandroidintelligence.conversation.model.AttachmentState
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.gateway.attachments.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentPhaseTest {
    @Test fun uploadAndVerificationRemainVisibleWhileTheirRealRequestsAreInFlight() = runTest {
        val finishUpload = CompletableDeferred<Unit>()
        val finishCommit = CompletableDeferred<Unit>()
        val transport = object : GatewayAttachmentTransport {
            override suspend fun create(request: AttachmentCreateRequest) = "attachment_remote"
            override suspend fun uploadContent(attachmentId: String, content: ByteArray, headers: Map<String, String>) { finishUpload.await() }
            override suspend fun commit(attachmentId: String) { finishCommit.await() }
        }
        val coordinator = GatewayAttachmentDraftCoordinator(AttachmentUploader(transport),
            AttachmentSubmissionGate { error("this test does not submit a message") }, backgroundScope)
        val draft = coordinator.prepare(LocalAttachmentSelection("note.txt", "text/plain", "hello".toByteArray()))
        runCurrent()
        assertEquals(AttachmentState.UPLOADING, coordinator.observe(draft.id.value).first().state)
        finishUpload.complete(Unit)
        runCurrent()
        assertEquals(AttachmentState.VERIFYING, coordinator.observe(draft.id.value).first().state)
        assertEquals(null, coordinator.remoteAttachmentId(draft.id.value))
        finishCommit.complete(Unit)
        runCurrent()
        assertEquals(AttachmentState.VERIFIED, coordinator.observe(draft.id.value).first().state)
        assertEquals("attachment_remote", coordinator.remoteAttachmentId(draft.id.value))
    }
    @Test fun aLostCommitResponseIsUnknownAndMustNotOfferBlindRetry() = runTest {
        val transport = object : GatewayAttachmentTransport {
            override suspend fun create(request: AttachmentCreateRequest) = "attachment_remote"
            override suspend fun uploadContent(attachmentId: String, content: ByteArray, headers: Map<String, String>) = Unit
            override suspend fun commit(attachmentId: String) { throw java.io.IOException("response lost") }
        }
        val coordinator = GatewayAttachmentDraftCoordinator(AttachmentUploader(transport),
            AttachmentSubmissionGate { error("this test does not submit a message") }, backgroundScope)
        val draft = coordinator.prepare(LocalAttachmentSelection("note.txt", "text/plain", "hello".toByteArray()))
        runCurrent()
        assertEquals(AttachmentState.OUTCOME_UNKNOWN, coordinator.observe(draft.id.value).first().state)
        assertEquals(null, coordinator.remoteAttachmentId(draft.id.value))
    }

}
