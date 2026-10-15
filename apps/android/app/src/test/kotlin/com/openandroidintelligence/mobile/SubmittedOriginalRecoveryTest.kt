package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.attachment.AttachmentSubmissionGate
import com.openandroidintelligence.conversation.data.GatewayAttachmentDraftCoordinator
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.encrypted.store.*
import com.openandroidintelligence.gateway.attachments.*
import com.openandroidintelligence.gateway.http.GatewayRequestBody
import com.openandroidintelligence.mobile.conversations.EncryptedAttachmentRecovery
import com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 上传协议使用夹具，原件、恢复映射和上传暂存均使用生产文件存储。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SubmittedOriginalRecoveryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val secret = SecretKeySpec(ByteArray(32) { 18 }, "AES")
    private val keys = object : AesGcmKeyProvider { override fun getOrCreate() = secret; override fun delete() = Unit }
    private val account = ConversationScope("submitted", "https://gateway.example", "account", "installation")
    private val original = "原始发送附件，暂存过期后仍须保持字节".toByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }

    @Test fun aMessageWithoutAnAcknowledgementKeepsItsArchivedSourceAfterTheUploadStagingTtl() = runTest {
        val directory = File(context.noBackupFilesDir, "submitted-upload-stage")
        val staging = EncryptedAttachmentStagingStore(directory, secret, "submitted-stage")
        val recovery = EncryptedAttachmentRecovery(context, account, keys)
        val remote = UploadFixture(original, digest)
        val coordinator = GatewayAttachmentDraftCoordinator(AttachmentUploader(remote), AttachmentSubmissionGate { error("本测试不提交消息") },
            backgroundScope, staging, UnconfinedTestDispatcher(testScheduler), recovery)
        val draft = coordinator.prepare(selection())
        runCurrent()
        val media = EncryptedHistoryMediaCache(context, account, keyProvider = keys)
        coordinator.archiveSubmitted("conv", draft.id.value, media)
        val old = recovery.load().single().content.id
        assertEquals(listOf(draft.id.value), media.submittedDraftIds("conv", "att_old"))
        assertTrue(File(directory, "$old.stage").setLastModified(System.currentTimeMillis() - 25L * 60 * 60 * 1000))
        remote.expired = true
        val afterJanitor = EncryptedAttachmentStagingStore(directory, secret, "submitted-stage")
        assertFalse(File(directory, "$old.stage").exists())
        val reopenedMedia = EncryptedHistoryMediaCache(context, account, keyProvider = keys)
        assertArrayEquals(original, reopenedMedia.openOriginal("conv", "att_old").use { it.readBytes() })
        val reopened = GatewayAttachmentDraftCoordinator(AttachmentUploader(remote), AttachmentSubmissionGate { error("本测试不提交消息") },
            backgroundScope, afterJanitor, UnconfinedTestDispatcher(testScheduler), recovery)
        runCurrent()
        assertNull(reopened.remoteAttachmentId(draft.id.value))
        reopened.ensureSubmittedSource("conv", draft.id.value, reopenedMedia)
        assertEquals("只恢复本地源不能发出新上传", 1, remote.uploads.size)
        assertEquals(draft.id, reopened.restoredDrafts().single().id)
        assertNotEquals(old, recovery.load().single().content.id)
        val retried = reopened.retrySubmittedSource("conv", draft.id.value, reopenedMedia)
        assertEquals(draft.id, retried.id)
        assertEquals("att_new", reopened.remoteAttachmentId(draft.id.value))
        assertEquals(2, remote.uploads.size)
        remote.uploads.forEach { assertArrayEquals(original, it) }
        media.clearMedia()
    }

    @Test fun durableDraftOriginReconstructsAReleasedRecoveryMappingWithoutBorrowingAnotherConversation() = runTest {
        val media = EncryptedHistoryMediaCache(context, account, keyProvider = keys)
        media.archiveSubmitted("conv", "att_old", "original.txt", "text/plain", object : AttachmentContentSource {
            override fun openStream() = ByteArrayInputStream(original)
        }, original.size.toLong(), digest, "draft_stable")
        val recovery = EncryptedAttachmentRecovery(context, account, keys)
        val staging = EncryptedAttachmentStagingStore(File(context.noBackupFilesDir, "origin-restage"), secret, "origin-stage")
        val remote = UploadFixture(original, digest)
        val coordinator = GatewayAttachmentDraftCoordinator(AttachmentUploader(remote), AttachmentSubmissionGate { error("本测试不提交消息") },
            backgroundScope, staging, UnconfinedTestDispatcher(testScheduler), recovery)
        assertNotNull(runCatching { coordinator.ensureSubmittedSource("other", "draft_stable", media) }.exceptionOrNull())
        assertTrue(recovery.load().isEmpty())
        coordinator.ensureSubmittedSource("conv", "draft_stable", media)
        assertEquals("draft_stable", coordinator.restoredDrafts().single().id.value)
        assertArrayEquals(original, staging.openStream(recovery.load().single().content.id).use { it.readBytes() })
        assertTrue(remote.uploads.isEmpty())
        media.remove("conv", "att_old")
        assertTrue(media.submittedDraftIds("conv", "att_old").isEmpty())
        assertNull(media.submittedMetadata("conv", "draft_stable"))
        media.clearMedia()
    }
    private fun selection() = LocalAttachmentSelection("original.txt", "text/plain", object : AttachmentContentSource {
        override fun openStream() = ByteArrayInputStream(original)
    })
    private class UploadFixture(private val original: ByteArray, private val digest: String) : GatewayAttachmentTransport {
        var expired = false
        private var uploaded = false
        val uploads = mutableListOf<ByteArray>()
        override suspend fun create(request: AttachmentCreateRequest): String { uploaded = false; return if (expired) "att_new" else "att_old" }
        override suspend fun getStatus(attachmentId: String) = AttachmentRemoteStatusInfo(
            if (attachmentId == "att_old" && expired) AttachmentRemoteStatus.EXPIRED else if (uploaded) AttachmentRemoteStatus.UPLOADED else AttachmentRemoteStatus.STAGED,
            original.size.toLong(), digest)
        override suspend fun uploadContent(attachmentId: String, body: GatewayRequestBody, headers: Map<String, String>) {
            uploads += body.openStream().use { it.readBytes() }
        }
        override suspend fun commit(attachmentId: String) { uploaded = true }
    }
}
