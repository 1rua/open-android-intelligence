package com.openandroidintelligence.conversation.data

import com.openandroidintelligence.conversation.attachment.AttachmentSubmissionGate
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.gateway.attachments.*
import com.openandroidintelligence.gateway.http.GatewayRequestBody
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentRecoveryTest {
    private val bytes="retained encrypted draft".toByteArray()
    private val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val content=StagedAttachmentContent("stage_retained",bytes.size.toLong(),digest)
    private val draft=AttachmentDraft(AttachmentDraftId("adft_restored"),"note.txt","text/plain",content.sizeBytes,digest,AttachmentState.VERIFIED)
    private val staging=object: LocalAttachmentStagingStore {
        override fun stage(selection: LocalAttachmentSelection,onBytesStaged: (Long)->Unit,isCancelled: ()->Boolean)=error("must retain original bytes")
        override fun openStream(stagedId: String)=ByteArrayInputStream(bytes)
        override fun delete(stagedId: String)=Unit
        override fun cleanupExpired(nowMillis: Long,maxAgeMillis: Long)=Unit
        override fun cleanup()=Unit
    }
    private class Recovery(var records: List<RecoveredAttachmentDraft>): AttachmentDraftRecoveryStore {
        override fun load()=records
        override fun save(records: List<RecoveredAttachmentDraft>) { this.records=records }
    }
    private fun recovery()=Recovery(listOf(RecoveredAttachmentDraft(draft,content,"att_old","att_client_old")))
    private fun TestScope.coordinator(transport: GatewayAttachmentTransport,recovery: Recovery)=GatewayAttachmentDraftCoordinator(
        AttachmentUploader(transport),AttachmentSubmissionGate { error("not a message submission") },
        backgroundScope,staging,UnconfinedTestDispatcher(testScheduler),recovery)

    @Test fun persistedRemoteIdIsUnavailableUntilItsAuthoritativeUploadedStatusReturns()=runTest {
        val status=CompletableDeferred<AttachmentRemoteStatusInfo>()
        var uploads=0
        val transport=object: GatewayAttachmentTransport {
            override suspend fun create(request: AttachmentCreateRequest)=error("live remote ID needs no new attempt")
            override suspend fun getStatus(attachmentId: String): AttachmentRemoteStatusInfo { assertEquals("att_old",attachmentId); return status.await() }
            override suspend fun uploadContent(attachmentId: String,body: GatewayRequestBody,headers: Map<String,String>) { uploads++ }
            override suspend fun commit(attachmentId: String)=Unit
        }
        val coordinator=coordinator(transport,recovery())
        assertEquals(AttachmentState.VERIFYING,coordinator.restoredDrafts().single().state)
        assertNull(coordinator.remoteAttachmentId(draft.id.value))
        runCurrent()
        status.complete(AttachmentRemoteStatusInfo(AttachmentRemoteStatus.UPLOADED,content.sizeBytes,digest))
        runCurrent()
        assertEquals(AttachmentState.VERIFIED,coordinator.observe(draft.id.value).first().state)
        assertEquals("att_old",coordinator.remoteAttachmentId(draft.id.value))
        assertEquals(0,uploads)
    }

    @Test fun expiredFailedAndMissingRemoteIdsRotateTheAttemptAndReuploadExactlyTheRetainedBytes()=runTest {
        for (oldStatus in listOf(AttachmentRemoteStatus.EXPIRED,AttachmentRemoteStatus.FAILED,null)) {
            val saved=recovery()
            val attempts=mutableListOf<String>();var uploaded=false;var uploads=0
            val transport=object: GatewayAttachmentTransport {
                override suspend fun create(request: AttachmentCreateRequest): String {
                    attempts+=request.clientAttachmentId
                    assertEquals(content.sizeBytes,request.sizeBytes);assertEquals(digest,request.sha256)
                    return "att_new"
                }
                override suspend fun getStatus(attachmentId: String): AttachmentRemoteStatusInfo {
                    if (attachmentId=="att_old" && oldStatus==null) throw IllegalStateException("ATTACHMENT_STATUS_FAILED:ATTACHMENT_EXPIRED")
                    return AttachmentRemoteStatusInfo(if (attachmentId=="att_old") oldStatus!! else if(uploaded) AttachmentRemoteStatus.UPLOADED else AttachmentRemoteStatus.STAGED,content.sizeBytes,digest)
                }
                override suspend fun uploadContent(attachmentId: String,body: GatewayRequestBody,headers: Map<String,String>) {
                    assertEquals("att_new",attachmentId);assertArrayEquals(bytes,body.openStream().use { it.readBytes() }); uploads++
                }
                override suspend fun commit(attachmentId: String) { uploaded=true }
            }
            val coordinator=coordinator(transport,saved);runCurrent()
            assertNull(coordinator.remoteAttachmentId(draft.id.value))
            assertEquals(AttachmentState.RETRYABLE_FAILURE,coordinator.observe(draft.id.value).first().state)
            assertNotEquals("att_client_old",saved.records.single().clientAttachmentId)
            coordinator.retry(draft.id.value);runCurrent()
            assertEquals("att_new",coordinator.remoteAttachmentId(draft.id.value))
            assertEquals(1,uploads)
            val reopened=coordinator(transport,saved);runCurrent()
            assertEquals("att_new",reopened.remoteAttachmentId(draft.id.value))
            assertEquals(1,uploads)
            assertEquals(1,attempts.size)
        }
    }

    @Test fun unreachableStatusKeepsTheOriginalAttemptAndNeverTreatsTheDraftAsVerified()=runTest {
        val saved=recovery();var creates=0
        val transport=object: GatewayAttachmentTransport {
            override suspend fun create(request: AttachmentCreateRequest): String { creates++;return "wrong" }
            override suspend fun getStatus(attachmentId: String): AttachmentRemoteStatusInfo=throw IOException("offline")
            override suspend fun uploadContent(attachmentId: String,body: GatewayRequestBody,headers: Map<String,String>)=Unit
            override suspend fun commit(attachmentId: String)=Unit
        }
        val coordinator=coordinator(transport,saved);runCurrent()
        coordinator.retry(draft.id.value);runCurrent()
        assertNull(coordinator.remoteAttachmentId(draft.id.value))
        assertEquals(0,creates)
        assertEquals("att_client_old",saved.records.single().clientAttachmentId)
    }

    @Test fun lostCommitAfterAnExpiredAttemptIsRecoveredAcrossRestartWithoutRepeatingTheUpload()=runTest {
        val saved=recovery();val attempts=mutableListOf<String>();var uploaded=false;var uploads=0
        val transport=object: GatewayAttachmentTransport {
            override suspend fun create(request: AttachmentCreateRequest): String { attempts+=request.clientAttachmentId;return "att_new" }
            override suspend fun getStatus(attachmentId: String)=AttachmentRemoteStatusInfo(
                if(attachmentId=="att_old") AttachmentRemoteStatus.EXPIRED else if(uploaded) AttachmentRemoteStatus.UPLOADED else AttachmentRemoteStatus.STAGED,
                content.sizeBytes,digest)
            override suspend fun uploadContent(attachmentId: String,body: GatewayRequestBody,headers: Map<String,String>) { uploads++ }
            override suspend fun commit(attachmentId: String) { uploaded=true;throw IOException("commit reply lost") }
        }
        val coordinator=coordinator(transport,saved);runCurrent()
        coordinator.retry(draft.id.value);runCurrent()
        assertEquals(AttachmentState.OUTCOME_UNKNOWN,coordinator.observe(draft.id.value).first().state)
        val reopened=coordinator(transport,saved)
        reopened.retry(draft.id.value);runCurrent()
        assertEquals("att_new",reopened.remoteAttachmentId(draft.id.value))
        assertEquals(1,uploads);assertEquals(2,attempts.size);assertEquals(attempts[0],attempts[1])
    }
}
