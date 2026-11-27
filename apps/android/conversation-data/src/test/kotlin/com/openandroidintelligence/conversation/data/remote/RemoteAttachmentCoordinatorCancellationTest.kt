package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.schema.JsonValue
import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** 取消上传只终止原端点的上传；仍保留本机草稿供明确重试。 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteAttachmentCoordinatorCancellationTest {
    private class Recovery : AttachmentDraftRecoveryStore {
        var records = listOf(RecoveredAttachmentDraft(
            AttachmentDraft(AttachmentDraftId("draft"), "file.txt", "text/plain", 3, "sha256:hash", AttachmentState.VERIFIED),
            StagedAttachmentContent("staged", 3, "hash"), null))
        override fun load() = records
        override fun save(records: List<RecoveredAttachmentDraft>) { this.records = records }
    }
    private class Staging : LocalAttachmentStagingStore {
        val deleted = mutableListOf<String>()
        override fun stage(selection: LocalAttachmentSelection, onBytesStaged: (Long) -> Unit, isCancelled: () -> Boolean): StagedAttachmentContent = error("只读取恢复草稿")
        override fun openStream(stagedId: String) = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        override fun delete(stagedId: String) { deleted += stagedId }
        override fun cleanupExpired(nowMillis: Long, maxAgeMillis: Long) = Unit
        override fun cleanup() = Unit
    }
    private class Channel(private val waitChunk: Boolean, var loseCommit: Boolean = false, val waitCommit: Boolean = false) : RemoteRpcChannel {
        override val scope = RemoteRpcConnectionScope("https://remote.example", "account", "host", "client", "connection", 1)
        override val capabilities = setOf("workspace.upload.v1", "harness.remote.v3")
        override val events = emptyFlow<RemoteRpcEvent>()
        val chunkStarted = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val ids = mutableListOf<String>()
        val commitStarted = CompletableDeferred<Unit>()
        val completed = mutableMapOf<String, JsonValue>()
        var publications = 0
        override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue {
            calls += method
            val upload = params.requiredText("uploadId")
            ids += upload
            return when (method) {
                "workspace.upload.open" -> obj("uploadId" to upload, "chunkBytes" to 3)
                "workspace.upload.chunk" -> {
                    chunkStarted.complete(Unit)
                    if (waitChunk) awaitCancellation()
                    obj("uploadId" to upload, "receivedBytes" to 3)
                }
                "workspace.upload.commit" -> {
                    val result = completed.getOrPut(upload) {
                        publications++
                        obj("bytes" to 3, "name" to "file.txt", "path" to "./file.txt")
                    }
                    commitStarted.complete(Unit)
                    if (waitCommit) awaitCancellation()
                    if (loseCommit) throw RemoteRpcException("RPC_TIMEOUT", RemoteDeliveryOutcome.UNKNOWN)
                    result
                }
                "workspace.upload.abort" -> obj()
                else -> error("不允许其它请求")
            }
        }
        override suspend fun close() = Unit
    }
    @Test fun `取消原端点上传会真实abort且另端点上传不受影响`() = runTest {
        val oldChannel = Channel(true)
        val newChannel = Channel(false)
        val oldRecovery = Recovery()
        val newRecovery = Recovery()
        val oldStaging = Staging()
        val nextStaging = Staging()
        val old = RemoteAttachmentCoordinator(HarnessSessionCarrier(oldChannel), backgroundScope, oldStaging, oldRecovery)
        val next = RemoteAttachmentCoordinator(HarnessSessionCarrier(newChannel), backgroundScope, nextStaging, newRecovery)
        val upload = backgroundScope.async { old.promptContent(RemoteLocalIds.encode("same"), "draft") }
        oldChannel.chunkStarted.await()
        upload.cancelAndJoin()
        assertEquals(1, oldChannel.calls.count { it == "workspace.upload.abort" })
        assertFalse(oldChannel.calls.contains("workspace.upload.commit"))
        val content = next.promptContent(RemoteLocalIds.encode("same"), "draft")
        assertEquals("./file.txt", content.single().text("text"))
        assertFalse(newChannel.calls.contains("workspace.upload.abort"))
        assertTrue(oldStaging.deleted.isEmpty())
        assertEquals("draft", oldRecovery.records.single().draft.id.value)
        assertEquals("draft", newRecovery.records.single().draft.id.value)
    }
    @Test fun `丢弃只回收当前协调器草稿及加密恢复记录`() = runTest {
        val oldRecovery = Recovery()
        val nextRecovery = Recovery()
        val oldStaging = Staging()
        val nextStaging = Staging()
        val old = RemoteAttachmentCoordinator(HarnessSessionCarrier(Channel(false)), backgroundScope, oldStaging, oldRecovery)
        val next = RemoteAttachmentCoordinator(HarnessSessionCarrier(Channel(false)), backgroundScope, nextStaging, nextRecovery)
        old.discard("draft")
        assertEquals(listOf("staged"), oldStaging.deleted)
        assertTrue(oldRecovery.records.isEmpty())
        assertEquals("draft", next.restoredDrafts().single().id.value)
        assertTrue(nextStaging.deleted.isEmpty())
        assertEquals(1, nextRecovery.records.size)
    }
    @Test fun `commit已发布但回执丢失只重查原上传且确认后重复使用原文件`() = runTest {
        val channel = Channel(false, loseCommit = true)
        val recovery = Recovery()
        val coordinator = RemoteAttachmentCoordinator(HarnessSessionCarrier(channel), backgroundScope, Staging(), recovery)
        val session = RemoteLocalIds.encode("same")
        try { coordinator.promptContent(session, "draft"); fail("回执丢失必须未知") } catch (_: java.io.IOException) { }
        val original = recovery.records.single().remoteUpload!!
        assertEquals(RemoteAttachmentUploadPhase.COMMIT_UNKNOWN, original.phase)
        assertEquals(AttachmentState.OUTCOME_UNKNOWN, coordinator.restoredDrafts().single().state)
        assertFalse(channel.calls.contains("workspace.upload.abort"))
        channel.loseCommit = false
        assertEquals("./file.txt", coordinator.promptContent(session, "draft").single().text("text"))
        coordinator.promptContent(session, "draft")
        assertEquals(1, channel.publications)
        assertEquals(1, channel.calls.count { it == "workspace.upload.open" })
        assertEquals(2, channel.calls.count { it == "workspace.upload.commit" })
        assertEquals(setOf(original.uploadId), channel.ids.toSet())
        assertEquals(RemoteAttachmentUploadPhase.CONFIRMED, recovery.records.single().remoteUpload!!.phase)
        val restarted = RemoteAttachmentCoordinator(HarnessSessionCarrier(channel), backgroundScope, Staging(), recovery)
        restarted.promptContent(session, "draft")
        assertEquals(2, channel.calls.count { it == "workspace.upload.commit" })
        try { restarted.promptContent(RemoteLocalIds.encode("other"), "draft"); fail("不能跨会话复用") } catch (_: java.io.IOException) { }
        assertEquals(1, channel.publications)
    }

    @Test fun `commit后取消及进程重启保留原上传身份且不得重新创建或abort`() = runTest {
        val channel = Channel(false, waitCommit = true)
        val recovery = Recovery()
        val coordinator = RemoteAttachmentCoordinator(HarnessSessionCarrier(channel), backgroundScope, Staging(), recovery)
        val session = RemoteLocalIds.encode("same")
        val upload = backgroundScope.async { coordinator.promptContent(session, "draft") }
        channel.commitStarted.await()
        upload.cancelAndJoin()
        val original = recovery.records.single().remoteUpload!!
        assertEquals(RemoteAttachmentUploadPhase.COMMIT_UNKNOWN, original.phase)
        assertFalse(channel.calls.contains("workspace.upload.abort"))
        val restarted = RemoteAttachmentCoordinator(HarnessSessionCarrier(channel), backgroundScope, Staging(), recovery)
        assertEquals(AttachmentState.OUTCOME_UNKNOWN, restarted.restoredDrafts().single().state)
        try { restarted.promptContent(session, "draft"); fail("重启不能重放旧 peer 的 commit") } catch (_: java.io.IOException) { }
        restarted.retry("draft"); runCurrent()
        assertEquals(AttachmentState.OUTCOME_UNKNOWN, restarted.restoredDrafts().single().state)
        assertEquals(original, recovery.records.single().remoteUpload)
        assertEquals(1, channel.calls.count { it == "workspace.upload.open" })
        assertEquals(1, channel.calls.count { it == "workspace.upload.commit" })
        assertEquals(1, channel.publications)
    }

}
