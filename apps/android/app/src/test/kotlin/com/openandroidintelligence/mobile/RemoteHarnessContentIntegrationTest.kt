package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.StreamHealth
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.data.remote.RemoteWorkspace
import com.openandroidintelligence.conversation.data.remote.RemoteConversationRepository
import com.openandroidintelligence.conversation.data.remote.CodexSessionCarrier
import com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror
import com.openandroidintelligence.mobile.conversations.MirroredConversationRepository
import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.remote.auth.RemoteLoginResult
import com.openandroidintelligence.gateway.remote.authproduction.RemoteActiveAccount
import com.openandroidintelligence.gateway.remote.identity.RemoteRole
import com.openandroidintelligence.gateway.remote.network.*
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.remote.runtime.RemoteAccountConnectionState
import com.openandroidintelligence.gateway.remote.runtime.RemoteHostConnectionState
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.plugins.thirdPartyOnlyHost
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 从认证后的 RPC 帧穿过真实载体、仓储、镜像、工作台和通知，不绕过生产投影。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemoteHarnessContentIntegrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val root = SupervisorJob()
    private val scope = CoroutineScope(root + Dispatchers.Unconfined)
    private val connection = Account()
    private lateinit var runtime: RemoteRuntime
    private val native = "opaque-thread-identity"
    private val local get() = RemoteLocalIds.encode(native)
    private val key = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 61 }, "AES")
        override fun delete() = Unit
    }
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val audit = AndroidAuditStore(InMemoryAuditSink())
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
        val trust = DeveloperTrustMode()
        val host = thirdPartyOnlyHost(context, grants, trust, NativePluginLoader(trust), "remote-content-tests")
        host.attach(PluginKernel(HostEnvelope(host.hostCapabilities), PhoneLimits(host.phoneCapabilities), host.runtimes,
            audit, trust, host.nativeLoader, host.selector, grants::currentKernelGrant,
            mediate = host::mediate, authorizeInvocation = host::authorizeKernelInvocation))
        runtime = RemoteRuntime(context, scope, host, grants, { _, _, _ -> false }, { connection }, key)
    }
    @After fun close() = runBlocking {
        runtime.logout().join()
        root.cancelAndJoin()
        Dispatchers.resetMain()
    }
    private fun obj(vararg pairs: Pair<String, Any?>) = Json.of(linkedMapOf(*pairs)) as JsonValue.JObject
    private suspend fun ready(): WorkbenchController {
        runtime.restoreSessionIfAvailable().join()
        val workspace = RemoteWorkspace(AgentEndpoint(EndpointKey(RemoteServiceOrigin.fromCanonicalOrigin("https://delivery.example"),
            "account", "host", BackendId("harness"), "workspace"), "工作区"), "/workspace")
        runtime.select(workspace).join()
        val controller = requireNotNull(runtime.controller.value)
        withTimeout(5_000) { controller.state.first { it.activeThreadId == local && (it.timeline is Loadable.Ready || it.timeline is Loadable.Empty) } }
        while (connection.rpc.historyReads.tryReceive().isSuccess) Unit
        return controller
    }

    private fun rows(controller: WorkbenchController) = (controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty()
    private suspend fun send(controller: WorkbenchController) {
        controller.editDraft("你好")
        controller.sendDraft()
        val requestId = withTimeout(5_000) { connection.rpc.prompts.receive() }
        connection.rpc.event(1, "turn/start", obj("turn" to 1))
        connection.rpc.event(2, "user/message", obj("id" to "user", "role" to "user", "turn" to 1,
            "source" to obj("kind" to "user", "rpcId" to requestId), "content" to listOf(obj("type" to "text", "text" to "你好"))))
    }
    private suspend fun awaitReply(controller: WorkbenchController, text: String) {
        assertNotNull("应继续显示正常正文，当前状态=${controller.state.value}", withTimeoutOrNull(2_000) {
            controller.state.first { rows(controller).any { !it.isUser && it.text == text } }
        })
    }
    private fun assertNoFailure(controller: WorkbenchController) {
        assertNotEquals(com.openandroidintelligence.conversation.model.GenerationState.FAILED, controller.state.value.generation)
        assertNotEquals(StreamHealth.FAILED, controller.state.value.streamHealth)
        assertFalse(rows(controller).any { it.messageStatus == AgentMessageStatus.FAILED })
    }
    @Test fun `Harness未知状态与跨片段系统提醒不阻止后续正文和正常终态`(): Unit = runBlocking {
        val controller = ready()
        send(controller)
        listOf("thinking", "tool", "meta", "status").forEach { connection.rpc.chunk("unknown", it, "") }
        connection.rpc.frame(obj("type" to "assistant-stream", "frame" to obj("type" to "start", "attemptId" to "answer", "turn" to 1)))
        listOf("<system-", "reminder>Instructions from ~/.dsh/AGENTS.md", "</system-reminder>").forEach {
            connection.rpc.chunk("answer", "text-delta", it)
            assertFalse(rows(controller).any { row -> !row.isUser && row.text.isNotBlank() })
            assertFalse(runtime.nativeConversationNotifications.sessions.value.any { row -> row.latestReply?.contains("Instructions") == true })
        }
        connection.rpc.chunk("answer", "text-delta", "你好")
        awaitReply(controller, "你好")
        connection.rpc.chunk("answer", "text-delta", "，请问有什么需要？")
        awaitReply(controller, "你好，请问有什么需要？")
        connection.rpc.event(3, "turn/end", obj("turn" to 1, "reason" to obj("kind" to "completed")))
        withTimeout(2_000) { controller.state.first { it.generation == com.openandroidintelligence.conversation.model.GenerationState.COMPLETED } }
        connection.rpc.completeFollow()
        assertNoFailure(controller)
        withTimeout(5_000) {
            runtime.nativeConversationNotifications.sessions.first { sessions ->
                sessions.any { it.latestReply == "你好，请问有什么需要？" }
            }
        }
    }
    @Test fun `Harness开场活动尝试快照经真实载体仓储镜像接续正文`() = runBlocking {
        val controller = ready()
        send(controller)
        connection.rpc.frame(obj("type" to "snapshot", "cursor" to 2, "records" to emptyList<Any>(),
            "assistantStream" to obj("revision" to 1, "activeAttempt" to obj("attemptId" to "active",
                "startedAfterSeq" to 2, "turn" to 1, "step" to 1, "nextIndex" to 0, "stream" to emptyList<Any>()))))
        connection.rpc.chunk("active", "text-delta", "恢复后的回答")
        awaitReply(controller, "恢复后的回答")
        assertNoFailure(controller)
    }
    @Test fun `Harness真实远端失败仍明确显示生成失败`() = runBlocking {
        val controller = ready()
        send(controller)
        connection.rpc.event(3, "turn/end", obj("turn" to 1, "reason" to obj("kind" to "error", "error" to obj("code" to "MODEL_FAILED", "message" to "受控模型失败"))))
        withTimeout(2_000) { controller.state.first { state ->
            state.generation == com.openandroidintelligence.conversation.model.GenerationState.FAILED &&
                rows(controller).any { it.messageStatus == AgentMessageStatus.FAILED }
        } }
        assertEquals(com.openandroidintelligence.conversation.model.GenerationState.FAILED, controller.state.value.generation)
        assertTrue(rows(controller).any { it.messageStatus == AgentMessageStatus.FAILED })
    }
    private inner class Account : RemoteAccountConnection {
        override val account = MutableStateFlow<RemoteActiveAccount?>(null)
        override val state = MutableStateFlow(RemoteAccountConnectionState.READY)
        override val hostState = MutableStateFlow(RemoteHostConnectionState.ONLINE)
        val rpc = Rpc()
        override suspend fun login(origin: String, email: String, password: String): RemoteLoginResult = error("测试只恢复账号")
        override suspend fun restore() = RemoteActiveAccount("https://delivery.example", "account", 1).also { account.value = it }
        override suspend fun listHosts() = listOf(RemoteHostDevice("host", "电脑", "linux", "membership", true))
        override suspend fun connect(hostDeviceId: String): RemoteRpcChannel = rpc
        override fun authenticatedPeer(connectionId: String) = AuthenticatedRemotePeer(rpc.scope, "sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64), RemoteRole.CLIENT)
        override suspend fun logout() { account.value = null }
    }
    private inner class Rpc : RemoteRpcChannel {
        override val scope = RemoteRpcConnectionScope("https://delivery.example", "account", "host", "client", "connection", 1)
        override val capabilities = setOf("harness.remote.v3")
        override val events = MutableSharedFlow<RemoteRpcEvent>()
        val prompts = Channel<String>(Channel.UNLIMITED)
        val historyReads = Channel<Unit>(Channel.UNLIMITED)
        private val streams = mutableMapOf<String, String>()
        suspend fun frame(value: JsonValue) {
            events.emit(RemoteRpcEvent("harness.remote.frame", obj("streamId" to requireNotNull(streams["session/follow"]), "value" to value)))
        }
        suspend fun event(seq: Int, type: String, data: JsonValue) = frame(obj("type" to "event", "event" to obj(
            "seq" to seq, "time" to System.currentTimeMillis(), "type" to type, "data" to data)))
        suspend fun chunk(attempt: String, type: String, text: String) = frame(obj("type" to "assistant-stream",
            "frame" to obj("type" to "chunk", "attemptId" to attempt, "chunk" to obj("type" to type, "index" to 0, "text" to text))))
        suspend fun completeFollow() {
            events.emit(RemoteRpcEvent("harness.remote.stream.closed", obj("streamId" to requireNotNull(streams["session/follow"]), "reason" to "completed")))
        }
        override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue {
            val endpoint = JsonFields.string(params, "endpoint")
            if (method == "harness.remote.stream.open") {
                val id = requireNotNull(JsonFields.string(params, "streamId"))
                streams[requireNotNull(endpoint)] = id
                if (endpoint == "workspace/follow") events.emit(RemoteRpcEvent("harness.remote.frame", obj("streamId" to id,
                    "value" to obj("type" to "baseline", "value" to obj("items" to listOf(obj("workspaceId" to "workspace", "title" to "工作区",
                        "path" to "/workspace", "sessionIds" to listOf(native))))))))
                return obj()
            }
            if (method == "harness.remote.stream.close") return obj()
            assertEquals("harness.remote.call", method)
            return when (endpoint) {
                "session/list" -> obj("items" to listOf(obj("sessionId" to native, "title" to "已有会话标题")))
                "session/page" -> { historyReads.trySend(Unit); obj("cursor" to 0, "records" to emptyList<Any>(), "hasMore" to false) }
                "session/prompt" -> {
                    val payload = requireNotNull(JsonFields.obj(params.fields.firstOrNull { it.first == "payload" }?.second))
                    val args = requireNotNull(JsonFields.obj(payload.fields.firstOrNull { it.first == "args" }?.second))
                    val request = requireNotNull(JsonFields.obj(args.fields.firstOrNull { it.first == "request" }?.second))
                    prompts.trySend(requireNotNull(JsonFields.string(request, "requestId")))
                    obj("accepted" to true)
                }
                "session/models" -> obj("groups" to emptyList<Any>())
                "commands/list" -> obj("items" to emptyList<Any>())
                else -> obj()
            }
        }
        override suspend fun close() = Unit
    }
}
