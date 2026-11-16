package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
class RemoteSessionDeliveryIntegrationTest {
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
        val host = thirdPartyOnlyHost(context, grants, trust, NativePluginLoader(trust), "remote-delivery-tests")
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
            "account", "host", BackendId("codex"), "workspace"), "工作区"), "/workspace")
        runtime.select(workspace).join()
        val controller = requireNotNull(runtime.controller.value)
        withTimeout(5_000) { controller.state.first { it.activeThreadId == local && (it.timeline is Loadable.Ready || it.timeline is Loadable.Empty) } }
        while (connection.rpc.historyReads.tryReceive().isSuccess) Unit
        return controller
    }
    private fun rows(controller: WorkbenchController) = (controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty()
    private fun assertSettledUserBeforeReply(controller: WorkbenchController, userId: String, replyId: String) {
        val currentRows = rows(controller)
        val user = currentRows.single { it.key == userId && it.isUser }
        val clientId = requireNotNull(user.clientMessageId)
        val replyIndex = currentRows.indexOfFirst { it.key == replyId && !it.isUser }
        assertTrue("对应的 Assistant 回复必须存在", replyIndex >= 0)
        assertTrue("用户输入必须位于所属回复之前", currentRows.indexOf(user) < replyIndex)
        assertFalse("确认原生用户身份后对应发送成员不应残留待接收占位", controller.state.value.pendingBatch.any {
            it.key.removePrefix("local_") == clientId.value
        })
    }
    private suspend fun awaitReply(controller: WorkbenchController, text: String) {
        val observed = withTimeoutOrNull(5_000) { controller.state.first { state ->
            assertFalse("正文消费者不能因身份异常中止：${state.notice}", state.notice?.startsWith("EVENT_APPLY_FAILED:") == true)
            (state.timeline as? Loadable.Ready)?.value.orEmpty().any { !it.isUser && it.text == text }
        } }
        assertNotNull("未收到实时正文：active=${controller.state.value.activeThreadId}, notice=${controller.state.value.notice}, " +
            "health=${controller.state.value.streamHealth}, generation=${controller.state.value.generation}, " +
            "rows=${rows(controller).map { Triple(it.key, it.text, it.generationId) }}, " +
            "notifications=${runtime.nativeConversationNotifications.sessions.value.map { Triple(it.target.conversationId, it.latestReply, it.status) }}, " +
            "rpc=${connection.rpc.calls}, sent=${connection.rpc.sentFrames}, listeners=${connection.rpc.events.subscriptionCount.value}", observed)
    }
    @Test fun `Codex同一原生流更新当前会话与通知且通知使用目录标题`() = runBlocking {
        val controller = ready()
        controller.editDraft("在吗")
        controller.sendDraft()
        val prompt = withTimeout(5_000) { connection.rpc.prompts.receive() }
        connection.rpc.frame("turn/started", obj("turn" to obj("id" to "turn-a")))
        connection.rpc.frame("item/started", obj("turnId" to "turn-a", "item" to obj("id" to "user-a", "type" to "userMessage",
            "content" to listOf(obj("type" to "text", "text" to "在吗")))))
        connection.rpc.frame("item/agentMessage/delta", obj("turnId" to "turn-a", "itemId" to "reply-a", "delta" to "实时回答"))
        awaitReply(controller, "实时回答")
        connection.rpc.frame("item/agentMessage/delta", obj("turnId" to "turn-a", "itemId" to "reply-a", "delta" to "继续"))
        awaitReply(controller, "实时回答继续")
        val notification = withTimeout(5_000) { runtime.nativeConversationNotifications.sessions.first { it.any { row -> row.latestReply == "实时回答继续" } } }.single()
        assertEquals(local, notification.target.conversationId)
        assertEquals("已有会话标题", notification.title)
        prompt.complete(obj("turn" to obj("id" to "turn-a")))
        connection.rpc.frame("turn/completed", obj("turn" to obj("id" to "turn-a", "status" to "completed")))
        withTimeout(5_000) { controller.state.first { it.generation == com.openandroidintelligence.conversation.model.GenerationState.COMPLETED } }
        assertEquals("实时回答继续", rows(controller).single { !it.isUser }.text)
        assertSettledUserBeforeReply(controller, "user-a", "reply-a")
        assertFalse(controller.state.value.notice?.contains("SNAPSHOT_BASELINE_INVALID") == true)
    }
    @Test fun `Codex用户回声先于回执仍回并原稿且相同文本的不同发送分别保留`() = runBlocking {
        val controller = ready()
        repeat(2) { index ->
            controller.editDraft("在吗")
            controller.sendDraft()
            val prompt = withTimeout(5_000) { connection.rpc.prompts.receive() }
            val turn = "turn-$index"
            connection.rpc.frame("turn/started", obj("turn" to obj("id" to turn)))
            connection.rpc.frame("item/started", obj("turnId" to turn, "item" to obj("id" to "user-$index", "type" to "userMessage",
                "content" to listOf(obj("type" to "text", "text" to "在吗")))))
            prompt.complete(obj("turn" to obj("id" to turn)))
            connection.rpc.frame("item/agentMessage/delta", obj("turnId" to turn, "itemId" to "reply-$index", "delta" to "回答$index"))
            awaitReply(controller, "回答$index")
            connection.rpc.frame("turn/completed", obj("turn" to obj("id" to turn, "status" to "completed")))
            withTimeout(5_000) { controller.state.first { it.generation == com.openandroidintelligence.conversation.model.GenerationState.COMPLETED } }
            val currentRows = rows(controller)
            assertEquals("远端回声和本地原稿必须属于同一次发送", index + 1, currentRows.count { it.isUser })
            assertSettledUserBeforeReply(controller, "user-$index", "reply-$index")
        }
        assertEquals(setOf("user-0", "user-1"), rows(controller).filter { it.isUser }.map { it.key }.toSet())
        assertEquals(2, rows(controller).filter { it.isUser }.mapNotNull { it.clientMessageId }.distinct().size)
    }
    @Test fun `Codex回执先于回声且历史不回传客户端身份时仍只显示一条用户消息`() = runBlocking {
        val controller = ready()
        controller.editDraft("重复历史验证")
        controller.sendDraft()
        val prompt = withTimeout(5_000) { connection.rpc.prompts.receive() }
        prompt.complete(obj("turn" to obj("id" to "ack-turn")))
        withTimeout(5_000) { controller.state.first { state ->
            (state.timeline as? Loadable.Ready)?.value.orEmpty().any { it.isUser && it.generationId == "ack-turn" }
        } }
        connection.rpc.frame("turn/started", obj("turn" to obj("id" to "ack-turn")))
        connection.rpc.frame("item/started", obj("turnId" to "ack-turn", "item" to obj("id" to "ack-user", "type" to "userMessage",
            "content" to listOf(obj("type" to "text", "text" to "重复历史验证")))))
        connection.rpc.frame("item/agentMessage/delta", obj("turnId" to "ack-turn", "itemId" to "ack-reply", "delta" to "回执之后的回答"))
        awaitReply(controller, "回执之后的回答")
        connection.rpc.frame("turn/completed", obj("turn" to obj("id" to "ack-turn", "status" to "completed")))
        withTimeout(5_000) { controller.state.first { it.generation == com.openandroidintelligence.conversation.model.GenerationState.COMPLETED } }
        val user = rows(controller).single { it.isUser }
        assertEquals("ack-user", user.key)
        assertNotNull(user.clientMessageId)
        connection.rpc.historyRecords = listOf(obj("type" to "event", "event" to obj("seq" to 1, "time" to System.currentTimeMillis(),
            "type" to "user/message", "data" to obj("id" to "ack-user", "source" to obj("kind" to "user"),
                "content" to listOf(obj("type" to "text", "text" to "重复历史验证"))))),
            obj("type" to "event", "event" to obj("seq" to 2, "time" to System.currentTimeMillis(), "type" to "assistant/message",
                "data" to obj("message" to obj("id" to "history-watermark", "content" to listOf(obj("type" to "text", "text" to "历史水位")))))))
        controller.retryTimeline()
        withTimeout(5_000) { connection.rpc.historyReads.receive() }
        awaitReply(controller, "历史水位")
        assertEquals(1, rows(controller).count { it.isUser })
        assertEquals(user.clientMessageId, rows(controller).single { it.isUser }.clientMessageId)
        assertSettledUserBeforeReply(controller, "ack-user", "ack-reply")
        val owner = controller.localConversationScope
        val reopened = EncryptedConversationMirror(context, owner, key)
        try {
            assertEquals("ack-user", reopened.loadSendAtoms(local).single().messageId)
            assertEquals(1, reopened.mergeTimeline(local, reopened.timeline(local)!!).messages.count { it.sender == "user" })
        } finally { reopened.closeStore() }
    }
    // 已知验收缺口：同轮 STEER 仍有远端回声与本地原子两行；唯一性实测为期望 2、实际 3。
    // 此测试只锁定保守身份边界，不能作为 STEER 唯一性已修复的证据；详见本轮验收文档。
    @Test fun `Codex同轮引导缺少请求关联时保留独立发送且不能冒认普通用户身份`() = runBlocking {
        val controller = ready()
        controller.editDraft("初始问题")
        controller.sendDraft()
        val prompt = withTimeout(5_000) { connection.rpc.prompts.receive() }
        connection.rpc.frame("turn/started", obj("turn" to obj("id" to "steer-turn")))
        connection.rpc.frame("item/started", obj("turnId" to "steer-turn", "item" to obj("id" to "normal-user", "type" to "userMessage",
            "content" to listOf(obj("type" to "text", "text" to "初始问题")))))
        prompt.complete(obj("turn" to obj("id" to "steer-turn")))
        connection.rpc.frame("item/agentMessage/delta", obj("turnId" to "steer-turn", "itemId" to "normal-reply", "delta" to "继续执行"))
        awaitReply(controller, "继续执行")
        val normalClientId = requireNotNull(rows(controller).single { it.key == "normal-user" }.clientMessageId)
        controller.editDraft("保留当前任务并纠正方向")
        controller.sendDraft(MessageDeliveryMode.STEER)
        val steer = withTimeout(5_000) { connection.rpc.steers.receive() }
        val localSteer = rows(controller).single { it.isUser && it.localPlaceholder && it.clientMessageId != normalClientId }
        val steerClientId = requireNotNull(localSteer.clientMessageId)
        connection.rpc.frame("item/started", obj("turnId" to "steer-turn", "item" to obj("id" to "steer-user", "type" to "userMessage",
            "content" to listOf(obj("type" to "text", "text" to "保留当前任务并纠正方向")))))
        steer.complete(obj("turnId" to "steer-turn"))
        connection.rpc.frame("item/agentMessage/delta", obj("turnId" to "steer-turn", "itemId" to "steer-reply", "delta" to "采用新方向"))
        awaitReply(controller, "采用新方向")
        val currentRows = rows(controller)
        assertEquals("后到的同轮引导不能改绑普通发送的原生用户身份", normalClientId,
            currentRows.single { it.key == "normal-user" }.clientMessageId)
        assertNull("只有同轮 turnId 的回声不能冒认任一客户端发送", currentRows.single { it.key == "steer-user" }.clientMessageId)
        assertTrue("尚无权威回并身份时必须保留用户自己的独立发送原子", currentRows.any {
            it.isUser && it.localPlaceholder && it.clientMessageId == steerClientId
        })
        val reopened = EncryptedConversationMirror(context, controller.localConversationScope, key)
        try {
            val atoms = reopened.loadSendAtoms(local)
            assertEquals("normal-user", atoms.single { it.clientMessageId == normalClientId }.messageId)
            assertTrue("未关联的 STEER 回声不能产生将本地原子绑定到服务器行的错误受理事实",
                atoms.single { it.clientMessageId == steerClientId }.messageId in setOf(null, steerClientId.value))
        } finally { reopened.closeStore() }
    }
    @Test fun `切换会话后的迟到Codex回执只升级原会话用户身份`() = runBlocking {
        val controller = ready()
        controller.editDraft("原会话消息")
        controller.sendDraft()
        val prompt = withTimeout(5_000) { connection.rpc.prompts.receive() }
        connection.rpc.frame("turn/started", obj("turn" to obj("id" to "late-turn")))
        connection.rpc.frame("item/started", obj("turnId" to "late-turn", "item" to obj("id" to "late-user", "type" to "userMessage",
            "content" to listOf(obj("type" to "text", "text" to "原会话消息")))))
        val other = RemoteLocalIds.encode("other-thread")
        controller.openThread(other)
        withTimeout(5_000) { controller.state.first { it.activeThreadId == other && (it.timeline is Loadable.Empty || it.timeline is Loadable.Ready) } }
        prompt.complete(obj("turn" to obj("id" to "late-turn")))
        connection.rpc.frame("item/agentMessage/delta", obj("turnId" to "late-turn", "itemId" to "late-reply", "delta" to "原会话迟到回答"))
        withTimeout(5_000) { runtime.nativeConversationNotifications.sessions.first { sessions -> sessions.any { it.latestReply == "原会话迟到回答" } } }
        assertEquals(other, controller.state.value.activeThreadId)
        assertTrue("旧回执不得借当前会话归属写入用户行", rows(controller).isEmpty())
        controller.openThread(local)
        withTimeout(5_000) { controller.state.first { state ->
            state.activeThreadId == local && (state.timeline as? Loadable.Ready)?.value.orEmpty().any { it.key == "late-user" && it.clientMessageId != null }
        } }
        awaitReply(controller, "原会话迟到回答")
        assertEquals(1, rows(controller).count { it.isUser })
        assertSettledUserBeforeReply(controller, "late-user", "late-reply")
    }
    @Test fun `生产Codex事件的不透明游标不能中断镜像后的正文订阅`() = runBlocking {
        val owner = ConversationScope("source-profile", "source-endpoint", "account", "source-install")
        val workspace = RemoteWorkspace(AgentEndpoint(EndpointKey(RemoteServiceOrigin.fromCanonicalOrigin("https://delivery.example"),
            "account", "host", BackendId("codex"), "workspace"), "工作区"), "/workspace")
        val mirror = EncryptedConversationMirror(context, owner, key)
        val repository = RemoteConversationRepository(CodexSessionCarrier(connection.rpc), workspace, scope, { local },
            onEvent = { _, event -> mirror.commitNativeEvents(listOf(event)) })
        val source = MirroredConversationRepository(repository, mirror)
        val received = scope.async(start = CoroutineStart.UNDISPATCHED) {
            source.observeEvents(owner).first { it is VerifiedConversationEvent.TimelineUpsert }
        }
        try {
            repository.timeline(local, PageRequest())
            connection.rpc.frame("turn/started", obj("turn" to obj("id" to "source-turn")))
            connection.rpc.frame("item/agentMessage/delta", obj("turnId" to "source-turn", "itemId" to "source-reply", "delta" to "源订阅回答"))
            val event = withTimeout(5_000) { received.await() } as VerifiedConversationEvent.TimelineUpsert
            assertTrue("合法事件身份由生产投影生成，不应继承旧线协议字母表限制", event.eventId.contains(':'))
            assertEquals(event.eventId, mirror.loadCursor(owner.accountId))
        } finally {
            received.cancelAndJoin()
            repository.close()
            mirror.closeStore()
        }
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
        override val capabilities = setOf("codex.appserver.v1")
        override val events = MutableSharedFlow<RemoteRpcEvent>()
        val prompts = Channel<CompletableDeferred<JsonValue>>(Channel.UNLIMITED)
        val steers = Channel<CompletableDeferred<JsonValue>>(Channel.UNLIMITED)
        val historyReads = Channel<Unit>(Channel.UNLIMITED)
        var historyRecords: List<JsonValue> = emptyList()
        private val streamIds = mutableMapOf<String, String>()
        val calls = mutableListOf<String>()
        val sentFrames = mutableListOf<String>()
        private val transfers = mutableMapOf<String, MutableList<ByteArray>>()
        suspend fun frame(method: String, params: JsonValue.JObject) {
            sentFrames += method
            val fields = params.fields + ("threadId" to JsonValue.JString(native))
            events.emit(RemoteRpcEvent("codex.app.frame", obj("streamId" to requireNotNull(streamIds[native]),
                "frame" to obj("method" to method, "params" to JsonValue.JObject(fields)))))
        }
        override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue {
            calls += method + ":" + JsonFields.string(params, "method").orEmpty()
            fun text(value: JsonValue.JObject, name: String) = JsonFields.string(value, name) ?: error("缺少$name")
            when (method) {
                "codex.app.stream.open" -> { streamIds[text(params, "threadId")] = text(params, "streamId"); return obj() }
                "codex.app.stream.close", "codex.app.transfer.close" -> return obj()
                "codex.app.transfer.open" -> { transfers[text(params, "transferId")] = mutableListOf(); return obj() }
                "codex.app.transfer.chunk" -> { transfers.getValue(text(params, "transferId")) += Base64.getDecoder().decode(text(params, "data")); return obj() }
                "codex.app.transfer.commit" -> {
                    val bytes = transfers.getValue(text(params, "transferId")).fold(byteArrayOf()) { all, part -> all + part }
                    val envelope = JsonFields.obj(Json.parse(bytes.decodeToString())) ?: error("传输包无效")
                    return obj("kind" to "inline", "response" to call(envelope))
                }
            }
            assertEquals("codex.app.call", method)
            return call(params)
        }
        private suspend fun call(envelope: JsonValue.JObject): JsonValue = when (JsonFields.string(envelope, "method")) {
            "project/list" -> obj("data" to listOf(obj("id" to "workspace", "name" to "工作区", "roots" to listOf(obj("path" to "/workspace")))))
            "thread/list" -> obj("data" to listOf(obj("id" to native, "name" to "已有会话标题", "cwd" to "/workspace"),
                obj("id" to "other-thread", "name" to "另一个会话", "cwd" to "/workspace")))
            "thread/read", "thread/resume" -> obj("thread" to obj("id" to native))
            "dsh/sessionHistory" -> {
                historyReads.trySend(Unit)
                obj("cursor" to historyRecords.size, "records" to historyRecords, "hasMore" to false)
            }
            "model/list" -> obj("data" to emptyList<Any>())
            "turn/start" -> CompletableDeferred<JsonValue>().also { prompts.send(it) }.await()
            "turn/steer" -> CompletableDeferred<JsonValue>().also { steers.send(it) }.await()
            else -> obj()
        }
        override suspend fun close() = Unit
    }
}
