package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.data.remote.*
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.conversation.state.*
import com.openandroidintelligence.encrypted.store.*
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.mobile.conversations.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteAttachmentMessageRecoveryTest {
    private fun obj(vararg fields: Pair<String, Any?>) = Json.of(linkedMapOf(*fields)) as JsonValue.JObject
    private class Channel : RemoteRpcChannel {
        override val scope = RemoteRpcConnectionScope("https://remote.example", "account", "host", "client", "connection", 1)
        override val capabilities = setOf("workspace.upload.v1")
        override val events = emptyFlow<RemoteRpcEvent>()
        var commits = 0
        var opens = 0
        var publications = 0
        val secondCommit = CompletableDeferred<Unit>()
        val ids = mutableSetOf<String>()
        private val completed = mutableSetOf<String>()
        override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue {
            val id = JsonFields.string(params, "uploadId")!!
            ids += id
            fun result(vararg fields: Pair<String, Any?>) = Json.of(linkedMapOf(*fields)) as JsonValue.JObject
            return when (method) {
                "workspace.upload.open" -> { opens++; result("uploadId" to id, "chunkBytes" to 3) }
                "workspace.upload.chunk" -> result("uploadId" to id, "receivedBytes" to 3)
                "workspace.upload.commit" -> {
                    commits++
                    if (commits == 2) secondCommit.complete(Unit)
                    if (completed.add(id)) publications++
                    if (commits <= 2) throw RemoteRpcException("RPC_TIMEOUT", RemoteDeliveryOutcome.UNKNOWN)
                    result("bytes" to 3, "name" to "file.txt", "path" to "./file.txt")
                }
                else -> error("未知 commit 不允许 abort 或其他方法：$method")
            }
        }
        override suspend fun close() = Unit
    }

    @Test fun `连续两次附件回执丢失仍保留消息未发送事实且第三次原commit恢复后只发一次prompt`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val key = object : AesGcmKeyProvider {
            override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 74 }, "AES")
            override fun delete() = Unit
        }
        val localScope = ConversationScope("attachment_message_retry", "endpoint", "account", "install")
        val local = RemoteLocalIds.encode("session")
        val channel = Channel()
        val endpoint = EndpointKey(RemoteServiceOrigin.fromCanonicalOrigin(channel.scope.origin), "account", "host", BackendId("hermes"), "workspace")
        val workspace = RemoteWorkspace(AgentEndpoint(endpoint, "工作区"), "/workspace")
        var prompts = 0
        val firstPrompt = CompletableDeferred<Unit>()
        val safePrompt = CompletableDeferred<Unit>()
        val carrier = object : RemoteSessionCarrier {
            override val channel = channel
            override val backend = "hermes"
            override val capabilities = setOf(SessionCapability.SEND, SessionCapability.HISTORY, SessionCapability.LIST)
            override suspend fun workspaces() = listOf(workspace)
            override suspend fun sessions(workspace: RemoteWorkspace, page: PageRequest) = RemoteSessionPage(listOf(RemoteSessionRow("session", "会话", 0, 0)))
            override suspend fun create(workspace: RemoteWorkspace) = RemoteSessionRow("session", "会话", 0, 0)
            override suspend fun history(sessionId: String, page: PageRequest) = RemoteHistoryPage(emptyList())
            override fun follow(sessionId: String): Flow<JsonValue> = flow { emit(obj("type" to "remote-stream-ready")); awaitCancellation() }
            override suspend fun prompt(sessionId: String, requestId: String, content: List<JsonValue>, model: RemoteModelSelection?): JsonValue {
                val disk = EncryptedConversationMirror(context, localScope, key, UnconfinedTestDispatcher(testScheduler))
                val durable = disk.loadSendAtoms(local).single { it.clientMessageId.value == requestId }
                disk.closeStore()
                assertEquals(LocalSubmissionState.OUTCOME_UNKNOWN, durable.submissionState)
                assertNull(durable.submissionErrorCode)
                prompts++
                if (requestId == "safe-retry") {
                    safePrompt.complete(Unit)
                    throw CancellationException("本次已执行后进程中断")
                }
                firstPrompt.complete(Unit)
                return obj("accepted" to true)
            }
            override suspend fun interrupt(sessionId: String, generationId: String) = obj()
            override suspend fun respond(sessionId: String, handle: String, allow: Boolean) = obj()
            override suspend fun models(sessionId: String) = RemoteModelCatalog(emptyList(), emptyMap())
            override suspend fun selectModel(sessionId: String, selection: RemoteModelSelection) = selection
        }
        val staging = EncryptedAttachmentStagingStore(File(context.noBackupFilesDir, "unknown-retry-staging"), key.getOrCreate(), "scope", persistent = true)
        val staged = staging.stage(LocalAttachmentSelection("file.txt", "text/plain", AttachmentContentSource { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }))
        val draft = AttachmentDraft(AttachmentDraftId("draft"), "file.txt", "text/plain", staged.sizeBytes, "sha256:${staged.sha256Hex}", AttachmentState.VERIFIED)
        val recovery = EncryptedAttachmentRecovery(context, localScope, key)
        recovery.save(listOf(RecoveredAttachmentDraft(draft, staged, null)))
        val coordinator = RemoteAttachmentCoordinator(carrier, backgroundScope, staging, recovery)
        val repository = RemoteConversationRepository(carrier, workspace, backgroundScope, { local }, coordinator::promptContent)
        val message = OutgoingMessage(ClientMessageId("original-message"), "附件只传一次", listOf("draft"))
        val firstError = try { repository.submitMessage(local, message); error("必须响应丢失") } catch (failure: IOException) { failure.message!! }
        assertTrue(firstError.startsWith("REMOTE_ATTACHMENT_MESSAGE_NOT_SENT:"))
        assertEquals(0, prompts)
        fun openMirror() = EncryptedConversationMirror(context, localScope, key, UnconfinedTestDispatcher(testScheduler))
        var mirror = openMirror()
        mirror.saveSendAtoms(local, listOf(LocalSendAtom(ConversationId(local), message.clientMessageId,
            listOf(MessagePart.Text(message.text), MessagePart.Attachment(draft.id, draft.filename, draft.mediaType,
                sizeBytes = draft.sizeBytes, sha256 = draft.sha256)), 1,
            submissionState = LocalSubmissionState.OUTCOME_UNKNOWN, submissionErrorCode = firstError)))
        var media = EncryptedHistoryMediaCache(context, localScope, keyProvider = key)
        coordinator.archiveSubmitted(local, "draft", media)
        fun controller(source: ConversationRepository = repository) = WorkbenchController(this, source, object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("test"), emptyList())
        }, { localScope }, attachmentCoordinator = coordinator, persistence = mirror,
            persistenceDispatcher = UnconfinedTestDispatcher(testScheduler), media = media,
            replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false))
        val first = controller()
        runCurrent(); first.openThread(local); runCurrent()
        val beforeRetry = "online=${first.state.value.isOnline},hydrated=${first.state.value.historyHydrated},active=${first.state.value.activeThreadId},recoverable=${first.state.value.hasRecoverableSubmissions},notice=${first.state.value.notice},atoms=${mirror.loadSendAtoms(local)},drafts=${coordinator.restoredDrafts()},upload=${recovery.load().map { it.remoteUpload }}"
        first.retryPendingSubmissions(); runCurrent()
        // 历史原件查询会进入真实 IO；等待真实载体检查点，不用虚拟调度次数代替完成事实。
        withContext(Dispatchers.Default) { withTimeout(5_000) { channel.secondCommit.await() } }
        runCurrent()
        assertEquals("before={$beforeRetry}; after={online=${first.state.value.isOnline},hydrated=${first.state.value.historyHydrated},notice=${first.state.value.notice},atoms=${mirror.loadSendAtoms(local)},drafts=${coordinator.restoredDrafts()},upload=${recovery.load().map { it.remoteUpload }}}", 2, channel.commits)
        assertEquals(0, prompts)
        assertTrue(mirror.loadSendAtoms(local).single().submissionErrorCode!!.startsWith("REMOTE_ATTACHMENT_MESSAGE_NOT_SENT:"))
        first.closeAndAwait(); runCurrent()
        // 正常关闭会退休镜像存储；界面重建须从相同加密目录重新打开，原上传连接仍在。
        mirror = openMirror()
        media = EncryptedHistoryMediaCache(context, localScope, keyProvider = key)
        val restored = controller()
        runCurrent(); restored.openThread(local); runCurrent()
        restored.retryPendingSubmissions(); runCurrent()
        withContext(Dispatchers.Default) { withTimeout(5_000) { firstPrompt.await() } }
        runCurrent()
        assertEquals(3, channel.commits)
        assertEquals(1, channel.opens)
        assertEquals(1, channel.publications)
        assertEquals(1, channel.ids.size)
        assertEquals(1, prompts)
        restored.closeAndAwait()
        mirror = openMirror()
        media = EncryptedHistoryMediaCache(context, localScope, keyProvider = key)
        // 直接从加密发送原子发起安全重试；真实 prompt 入口重开存储验证许可已消费。
        mirror.saveSendAtoms(local, listOf(LocalSendAtom(ConversationId(local), ClientMessageId("safe-retry"),
            listOf(MessagePart.Text("安全许可只用一次")), 2, submissionState = LocalSubmissionState.FAILED,
            submissionErrorCode = "REMOTE_SUBMISSION_NOT_SENT")))
        val safe = controller()
        runCurrent(); safe.openThread(local); runCurrent()
        safe.retryPendingSubmissions(); runCurrent()
        withContext(Dispatchers.Default) { withTimeout(5_000) { safePrompt.await() } }
        runCurrent()
        assertEquals(2, prompts)
        safe.closeAndAwait(); repository.close()
        mirror = openMirror()
        media = EncryptedHistoryMediaCache(context, localScope, keyProvider = key)
        val restartedRepository = RemoteConversationRepository(carrier, workspace, backgroundScope, { local }, coordinator::promptContent)
        val restarted = controller(restartedRepository)
        runCurrent(); restarted.openThread(local); runCurrent()
        restarted.retryPendingSubmissions(); runCurrent()
        assertEquals("执行后重建不能再次消费旧的未发送许可", 2, prompts)
        restarted.closeAndAwait(); restartedRepository.close(); mirror.closeStore()
    }
}
