package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.mobile.conversations.EncryptedAttachmentRecovery
import com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror
import kotlinx.coroutines.runBlocking
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EncryptedRemoteAttachmentRecoveryTest {
    @Test fun `原上传身份与未知阶段经过真实加密存储重开仍保持且账号隔离`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val key = object : AesGcmKeyProvider {
            override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 73 }, "AES")
            override fun delete() = Unit
        }
        val scope = ConversationScope("remote_upload", "endpoint", "account", "install")
        val upload = RemoteAttachmentUpload("完整授权作用域", "original-session", "original-upload", "connection", 3,
            RemoteAttachmentUploadPhase.COMMIT_UNKNOWN, 3, "hash")
        val record = RecoveredAttachmentDraft(AttachmentDraft(AttachmentDraftId("draft"), "file.txt", "text/plain", 3,
            "sha256:hash", AttachmentState.OUTCOME_UNKNOWN), StagedAttachmentContent("staged", 3, "hash"), null, remoteUpload = upload)
        EncryptedAttachmentRecovery(context, scope, key).save(listOf(record))
        val restored = EncryptedAttachmentRecovery(context, scope, key).load().single()
        assertEquals(upload, restored.remoteUpload)
        assertEquals(record.content, restored.content)
        assertTrue(EncryptedAttachmentRecovery(context, scope.copy(accountId = "other"), key).load().isEmpty())
        val confirmed = record.copy(remoteUpload = upload.copy(phase = RemoteAttachmentUploadPhase.CONFIRMED, path = "./file.txt", name = "file.txt"))
        EncryptedAttachmentRecovery(context, scope, key).save(listOf(confirmed))
        assertEquals(confirmed.remoteUpload, EncryptedAttachmentRecovery(context, scope, key).load().single().remoteUpload)
    }
    @Test fun `未知附件未发送事实合并后可重开读取且发送边界空值必须消费旧许可`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val key = object : AesGcmKeyProvider {
            override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 75 }, "AES")
            override fun delete() = Unit
        }
        val scope = ConversationScope("remote_unknown_merge", "endpoint", "account", "install")
        val atom = LocalSendAtom(ConversationId("session"), ClientMessageId("message"), listOf(MessagePart.Text("正文")), 1,
            submissionState = LocalSubmissionState.OUTCOME_UNKNOWN,
            submissionErrorCode = "REMOTE_ATTACHMENT_MESSAGE_NOT_SENT:REMOTE_ATTACHMENT_OUTCOME_UNKNOWN")
        val first = EncryptedConversationMirror(context, scope, key)
        first.saveSendAtoms("session", listOf(atom))
        first.saveSendAtoms("session", listOf(atom.copy(submissionErrorCode = "REMOTE_ATTACHMENT_MESSAGE_NOT_SENT:RPC_TIMEOUT")))
        first.closeStore()
        val reopened = EncryptedConversationMirror(context, scope, key)
        assertEquals("REMOTE_ATTACHMENT_MESSAGE_NOT_SENT:RPC_TIMEOUT", reopened.loadSendAtoms("session").single().submissionErrorCode)
        reopened.saveSendAtoms("session", listOf(atom.copy(submissionErrorCode = null)))
        reopened.closeStore()
        val consumed = EncryptedConversationMirror(context, scope, key)
        assertNull("发送边界的空值不能被旧许可复活", consumed.loadSendAtoms("session").single().submissionErrorCode)
        consumed.saveSendAtoms("session", listOf(atom.copy(submissionErrorCode = "REMOTE_SUBMISSION_NOT_SENT")))
        assertNull("普通未知状态不能带上安全重发许可", consumed.loadSendAtoms("session").single().submissionErrorCode)
        consumed.closeStore()
    }

}
