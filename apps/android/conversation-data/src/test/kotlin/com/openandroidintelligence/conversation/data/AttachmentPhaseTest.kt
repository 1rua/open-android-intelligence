package com.openandroidintelligence.conversation.data

import com.openandroidintelligence.conversation.attachment.AttachmentSubmissionGate
import com.openandroidintelligence.conversation.model.AttachmentState
import com.openandroidintelligence.conversation.ports.AttachmentContentSource
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.conversation.ports.LocalAttachmentStagingStore
import com.openandroidintelligence.conversation.ports.StagedAttachmentContent
import com.openandroidintelligence.gateway.attachments.*
import com.openandroidintelligence.gateway.http.GatewayRequestBody
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentPhaseTest {
    @Test fun uploadAndVerificationRemainVisibleWhileTheirRealRequestsAreInFlight() = runTest {
        val finishUpload = CompletableDeferred<Unit>()
        val finishCommit = CompletableDeferred<Unit>()
        val transport = object : GatewayAttachmentTransport {
            private lateinit var created: AttachmentCreateRequest
            override suspend fun create(request: AttachmentCreateRequest): String {
                created = request
                return "attachment_remote"
            }
            override suspend fun getStatus(attachmentId: String) =
                AttachmentRemoteStatusInfo(AttachmentRemoteStatus.STAGED, created.sizeBytes, created.sha256)
            override suspend fun uploadContent(attachmentId: String, body: GatewayRequestBody, headers: Map<String, String>) { finishUpload.await() }
            override suspend fun commit(attachmentId: String) { finishCommit.await() }
        }
        val staging = TestStagingStore()
        val coordinator = coordinator(transport, backgroundScope, staging)
        val draft = coordinator.prepare(selection("note.txt", "text/plain", "hello"))
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
        coordinator.releaseAfterSubmit(draft.id.value)
        assertTrue(staging.deleted.contains(staging.lastStagedId))
    }
    @Test fun aLostCommitResponseIsUnknownAndRetryQueriesStatusBeforeAnyRepeatedContentPut() = runTest {
        var remoteState = AttachmentRemoteStatus.STAGED
        var contentUploads = 0
        var loseCommitResponse = true
        val clientAttachmentIds = mutableListOf<String>()
        val transport = object : GatewayAttachmentTransport {
            private lateinit var created: AttachmentCreateRequest
            override suspend fun create(request: AttachmentCreateRequest): String {
                created = request
                clientAttachmentIds += request.clientAttachmentId
                return "attachment_remote"
            }
            override suspend fun getStatus(attachmentId: String) =
                AttachmentRemoteStatusInfo(remoteState, created.sizeBytes, created.sha256)
            override suspend fun uploadContent(attachmentId: String, body: GatewayRequestBody, headers: Map<String, String>) {
                contentUploads++
                remoteState = AttachmentRemoteStatus.STAGED
            }
            override suspend fun commit(attachmentId: String) {
                remoteState = AttachmentRemoteStatus.UPLOADED
                if (loseCommitResponse) {
                    loseCommitResponse = false
                    throw java.io.IOException("response lost")
                }
            }
        }
        val coordinator = coordinator(transport, backgroundScope)
        val draft = coordinator.prepare(selection("note.txt", "text/plain", "hello"))
        runCurrent()
        assertEquals(AttachmentState.OUTCOME_UNKNOWN, coordinator.observe(draft.id.value).first().state)
        assertEquals(null, coordinator.remoteAttachmentId(draft.id.value))

        coordinator.retry(draft.id.value)
        runCurrent()
        assertEquals(AttachmentState.VERIFIED, coordinator.observe(draft.id.value).first().state)
        assertEquals("attachment_remote", coordinator.remoteAttachmentId(draft.id.value))
        assertEquals(1, contentUploads)
        assertEquals(clientAttachmentIds.first(), clientAttachmentIds.last())
    }

    @Test fun discardingAnAttachmentCancelsUploadAndRemovesItsEncryptedStage() = runTest {
        val finishUpload = CompletableDeferred<Unit>()
        val transport = object : GatewayAttachmentTransport {
            private lateinit var created: AttachmentCreateRequest
            override suspend fun create(request: AttachmentCreateRequest): String {
                created = request
                return "attachment_remote"
            }
            override suspend fun getStatus(attachmentId: String) =
                AttachmentRemoteStatusInfo(AttachmentRemoteStatus.STAGED, created.sizeBytes, created.sha256)
            override suspend fun uploadContent(attachmentId: String, body: GatewayRequestBody, headers: Map<String, String>) {
                finishUpload.await()
            }
            override suspend fun commit(attachmentId: String) = Unit
        }
        val staging = TestStagingStore()
        val coordinator = coordinator(transport, backgroundScope, staging)
        val draft = coordinator.prepare(selection("photo.png", "image/png", "payload"))
        runCurrent()

        coordinator.discard(draft.id.value)
        assertTrue(staging.deleted.contains(staging.lastStagedId))
        finishUpload.cancel()
    }

    private fun TestScope.coordinator(
        transport: GatewayAttachmentTransport,
        scope: kotlinx.coroutines.CoroutineScope,
        staging: TestStagingStore = TestStagingStore(),
    ) = GatewayAttachmentDraftCoordinator(
        AttachmentUploader(transport),
        AttachmentSubmissionGate { error("this test does not submit a message") },
        scope,
        staging,
        UnconfinedTestDispatcher(testScheduler),
    )

    private fun selection(filename: String, mediaType: String, content: String) = LocalAttachmentSelection(
        filename,
        mediaType,
        AttachmentContentSource { ByteArrayInputStream(content.toByteArray()) },
    )

    private class TestStagingStore : LocalAttachmentStagingStore {
        private val contents = mutableMapOf<String, ByteArray>()
        val deleted = mutableSetOf<String>()
        var lastStagedId: String? = null

        override fun stage(selection: LocalAttachmentSelection, onBytesStaged: (Long) -> Unit, isCancelled: () -> Boolean): StagedAttachmentContent {
            val bytes = selection.contentSource.openStream().use { it.readBytes() }
            if (isCancelled()) throw java.util.concurrent.CancellationException()
            val id = "stage_${contents.size + 1}"
            lastStagedId = id
            contents[id] = bytes
            onBytesStaged(bytes.size.toLong())
            return StagedAttachmentContent(id, bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        }

        override fun openStream(stagedId: String): InputStream =
            ByteArrayInputStream(contents[stagedId] ?: error("missing stage"))

        override fun delete(stagedId: String) {
            deleted += stagedId
            contents.remove(stagedId)
        }

        override fun cleanupExpired(nowMillis: Long, maxAgeMillis: Long) = Unit

        override fun cleanup() {
            contents.clear()
        }
    }

}
