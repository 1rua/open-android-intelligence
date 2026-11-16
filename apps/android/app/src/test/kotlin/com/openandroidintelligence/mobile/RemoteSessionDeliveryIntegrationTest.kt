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
    @Test fun `Codex同轮引导按正文原地回并且不捏造服务端发送身份`() = runBlocking {
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
        assertEquals("普通发送与引导各显示一次，匹配回声应吸收本地占位", 2, currentRows.count { it.isUser })
        assertEquals(steerClientId, currentRows.single { it.key == "steer-user" }.clientMessageId)
        assertFalse(currentRows.single { it.key == "steer-user" }.localPlaceholder)
        val reopened = EncryptedConversationMirror(context, controller.localConversationScope, key)
        try {
            val atoms = reopened.loadSendAtoms(local)
            assertEquals("normal-user", atoms.single { it.clientMessageId == normalClientId }.messageId)
            assertNull("正文对应仅用于界面，原始远端消息不能被补造客户端发送身份",
                reopened.timeline(local)!!.messages.single { it.id == "steer-user" }.clientMessageId)
            assertEquals(2, reopened.mergeTimeline(local, reopened.timeline(local)!!).messages.count { it.sender == "user" })
            assertTrue("正文回并不能产生将本地原子绑定到服务器行的协议受理事实",
                atoms.single { it.clientMessageId == steerClientId }.messageId in setOf(null, steerClientId.value))
        } finally { reopened.closeStore() }
    }
    @Test fun `Codex引导回执先到仍保留原稿且同文新回声逐条消费`() = runBlocking {
        val controller = ready()
        connection.rpc.frame("turn/started", obj("turn" to obj("id" to "same-turn")))
        connection.rpc.frame("item/agentMessage/delta", obj("turnId" to "same-turn", "itemId" to "initial-barrier", "delta" to "引导前仍在执行"))
        awaitReply(controller, "引导前仍在执行")
        // 发送前已有相同正文，即使时间来自未来也必须保留，不能吃掉本次原稿。
        connection.rpc.historyRecords = listOf(userHistory("old-same", "重复引导", 1, Long.MAX_VALUE))
        controller.retryTimeline()
        withTimeout(5_000) { controller.state.first { state ->
            (state.timeline as? Loadable.Ready)?.value.orEmpty().any { it.key == "old-same" }
        } }
        val clientIds = mutableListOf<com.openandroidintelligence.conversation.model.ClientMessageId>()
        repeat(2) { index ->
            controller.editDraft("重复引导")
            controller.sendDraft(MessageDeliveryMode.STEER)
            val ack = withTimeoutOrNull(5_000) { connection.rpc.steers.receive() }
            assertNotNull("第${index + 1}次引导未进入载体：notice=${controller.state.value.notice}, composer=${controller.state.value.composer}, generation=${controller.state.value.generation}, draft=${controller.state.value.draft}, calls=${connection.rpc.calls}", ack)
            clientIds += requireNotNull(rows(controller).last { it.isUser && it.localPlaceholder }.clientMessageId)
            requireNotNull(ack).complete(obj("turnId" to "same-turn"))
            withTimeout(5_000) { controller.state.first { it.notice == "STEERED:CURRENT_TURN" } }
            assertEquals("ACK 没有正文，必须继续显示每次本地原稿", index + 2, rows(controller).count { it.isUser })
        }
        assertEquals("每次明确发送都必须使用独立身份", 2, clientIds.distinct().size)
        assertEquals(2, connection.rpc.calls.count { it == "codex.app.call:turn/steer" })
        connection.rpc.frame("item/started", obj("turnId" to "same-turn", "item" to obj("id" to "echo-one", "type" to "userMessage",
            "content" to listOf(obj("type" to "text", "text" to "重复引导")))))
        connection.rpc.frame("item/agentMessage/delta", obj("turnId" to "same-turn", "itemId" to "barrier-one", "delta" to "第一回声已处理"))
        awaitReply(controller, "第一回声已处理")
        assertEquals("第一条回声只消费一个原稿", 3, rows(controller).count { it.isUser })
        assertEquals(1, rows(controller).count { it.isUser && it.localPlaceholder })
        // 第二个回声只通过历史追赶到达；Host 时间落后手机，不能按手机墙钟拒绝。
        connection.rpc.historyRecords = listOf(userHistory("old-same", "重复引导", 1, Long.MAX_VALUE),
            userHistory("echo-one", "重复引导", 2, 1), userHistory("echo-two", "重复引导", 3, 1),
            obj("type" to "event", "event" to obj("seq" to 4, "time" to 2, "type" to "assistant/message", "data" to obj("message" to obj(
                "id" to "history-steer-barrier", "content" to listOf(obj("type" to "text", "text" to "历史引导已追赶")))))))
        controller.retryTimeline()
        awaitReply(controller, "历史引导已追赶")
        val finalRows = rows(controller)
        assertEquals(setOf("old-same", "echo-one", "echo-two"), finalRows.filter { it.isUser }.map { it.key }.toSet())
        assertTrue("同文发送按本地发送顺序保持原位", finalRows.indexOfFirst { it.key == "echo-one" } <
            finalRows.indexOfFirst { it.key == "echo-two" })
        assertTrue("本地原稿位置须在历史追赶的对应助手之前", finalRows.indexOfFirst { it.key == "echo-two" } <
            finalRows.indexOfFirst { it.key == "history-steer-barrier" })
        assertTrue("发送后新出现的同轮助手仍位于两次引导之后", finalRows.indexOfFirst { it.key == "echo-two" } <
            finalRows.indexOfFirst { it.key == "barrier-one" })
        assertTrue("发送前已存在的同轮助手保持在引导之前", finalRows.indexOfFirst { it.key == "initial-barrier" } <
            finalRows.indexOfFirst { it.key == "echo-one" })
        assertEquals(clientIds.toSet(), rows(controller).filter { it.isUser }.mapNotNull { it.clientMessageId }.toSet())
        val reopened = EncryptedConversationMirror(context, controller.localConversationScope, key)
        try {
            val restored = reopened.mergeTimeline(local, reopened.timeline(local)!!).messages.filter { it.sender == "user" }
            assertEquals(3, restored.size)
            assertEquals(clientIds.toSet(), restored.mapNotNull { it.presentationClientMessageId }.toSet())
            assertTrue("UI 正文匹配不应升级为服务端受理身份", reopened.loadSendAtoms(local).all { it.messageId == null || it.messageId == it.clientMessageId.value })
            assertTrue(reopened.timeline(local)!!.messages.filter { it.sender == "user" }.all { it.clientMessageId == null })
        } finally { reopened.closeStore() }
    }
    @Test fun `Codex同轮引导不能把迟到旧助手移出原来的问答段`() = runBlocking {
        delayedOldHistoryKeepsQuestionAnswerBeforeSteering(changeGeneration = false)
    }
    @Test fun `Codex切换轮次后引导仍不能把迟到旧助手移到回声之后`() = runBlocking {
        delayedOldHistoryKeepsQuestionAnswerBeforeSteering(changeGeneration = true)
    }
    private suspend fun delayedOldHistoryKeepsQuestionAnswerBeforeSteering(changeGeneration: Boolean) {
        val controller = ready()
        connection.rpc.frame("turn/started", obj("turn" to obj("id" to "order-g1")))
        connection.rpc.frame("item/agentMessage/delta", obj("turnId" to "order-g1", "itemId" to "order-initial", "delta" to "发送前已有助手"))
        awaitReply(controller, "发送前已有助手")
        controller.editDraft("当前引导输入")
        controller.sendDraft(MessageDeliveryMode.STEER)
        val ack = withTimeout(5_000) { connection.rpc.steers.receive() }
        ack.complete(obj("turnId" to "order-g1"))
        withTimeout(5_000) { controller.state.first { it.notice == "STEERED:CURRENT_TURN" } }
        connection.rpc.frame("item/started", obj("turnId" to "order-g1", "item" to obj("id" to "order-echo", "type" to "userMessage",
            "content" to listOf(obj("type" to "text", "text" to "当前引导输入")))))
        withTimeout(5_000) { controller.state.first { state ->
            (state.timeline as? Loadable.Ready)?.value.orEmpty().any { it.key == "order-echo" && !it.localPlaceholder }
        } }
        if (changeGeneration) {
            connection.rpc.frame("turn/started", obj("turn" to obj("id" to "order-g2")))
            connection.rpc.frame("item/agentMessage/delta", obj("turnId" to "order-g2", "itemId" to "order-next", "delta" to "下一轮仍在执行"))
            awaitReply(controller, "下一轮仍在执行")
        }
        // 同一原生历史页明确把旧问答放在回声之前；手机时间晚于这批记录。
        connection.rpc.historyRecords = listOf(userHistory("order-old-user", "与当前引导不同的旧问题", 1, 1),
            obj("type" to "event", "event" to obj("seq" to 2, "time" to 2, "type" to "assistant/message", "data" to obj("message" to obj(
                "id" to "order-old-answer", "content" to listOf(obj("type" to "text", "text" to "旧问题的答案")))))),
            userHistory("order-echo", "当前引导输入", 3, 3))
        controller.retryTimeline()
        awaitReply(controller, "旧问题的答案")
        val expected = listOf("order-old-user", "order-old-answer", "order-initial", "order-echo")
        val actual = rows(controller).filter { it.key in expected }.map { it.key }
        assertEquals("迟到历史中的旧问答不能被展示回并拆开；changeGeneration=$changeGeneration，rows=" +
            rows(controller).map { listOf(it.key, it.generationId, it.timestamp, it.localPlaceholder) }, expected, actual)
    }
    private fun userHistory(id: String, text: String, sequence: Int, time: Long) = obj("type" to "event", "event" to obj(
        "seq" to sequence, "time" to time, "type" to "user/message", "data" to obj("id" to id, "source" to obj("kind" to "user"),
            "content" to listOf(obj("type" to "text", "text" to text)))))
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
