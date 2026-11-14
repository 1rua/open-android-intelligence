package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConversationRepositoryTest {
    private val local = RemoteLocalIds.encode("hermes:相同/session")
    private val scope = ConversationScope("profile", "endpoint", "account", "install")
    private fun key(workspace: String) = EndpointKey(RemoteServiceOrigin.fromCanonicalOrigin("https://remote.example"), "account", "host", BackendId("hermes"), workspace)
    private class Carrier(workspace: RemoteWorkspace) : RemoteSessionCarrier {
        override val channel = object : RemoteRpcChannel {
            override val scope = RemoteRpcConnectionScope("https://remote.example", "account", "host", "client", "connection", 1)
            override val capabilities = setOf("agent.acp.v1", "agent.acp.hermes.v1")
            override val events = emptyFlow<RemoteRpcEvent>()
            override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long) = obj()
            override suspend fun close() = Unit
        }
        override val backend = "hermes"
        override val capabilities = setOf(SessionCapability.LIST, SessionCapability.HISTORY, SessionCapability.CREATE, SessionCapability.SEND)
        val frames = MutableSharedFlow<JsonValue>()
        val prompts = mutableListOf<Pair<String, List<JsonValue>>>()
        var promptResult: suspend () -> JsonValue = { obj("accepted" to true, "stopReason" to "in_progress") }
        var historyRead: suspend () -> RemoteHistoryPage = { RemoteHistoryPage(listOf(obj("type" to "event", "event" to obj(
            "seq" to 0, "type" to "assistant/message", "data" to obj("message" to obj("id" to "message", "content" to listOf(obj("type" to "text", "text" to "历史")))))))) }
        private val row = workspace
        override suspend fun workspaces() = listOf(row)
        override suspend fun sessions(workspace: RemoteWorkspace, page: PageRequest) = RemoteSessionPage(listOf(RemoteSessionRow("hermes:相同/session", "会话", 0, 1)))
        override suspend fun create(workspace: RemoteWorkspace) = RemoteSessionRow("hermes:相同/session", "", 0, 0)
        override suspend fun history(sessionId: String, page: PageRequest): RemoteHistoryPage { assertEquals("hermes:相同/session", sessionId); return historyRead() }
        override fun follow(sessionId: String): Flow<JsonValue> = flow { emit(obj("type" to "remote-stream-ready")); emitAll(frames) }
        override suspend fun prompt(sessionId: String, requestId: String, content: List<JsonValue>, model: RemoteModelSelection?): JsonValue {
            prompts += sessionId to content; return promptResult()
        }
        override suspend fun interrupt(sessionId: String, generationId: String) = obj("accepted" to true)
        override suspend fun respond(sessionId: String, handle: String, allow: Boolean) = obj()
        override suspend fun models(sessionId: String) = RemoteModelCatalog(emptyList(), emptyMap())
        override suspend fun selectModel(sessionId: String, selection: RemoteModelSelection) = selection
    }
    @Test fun `原生不透明身份仅在界面可逆编码且不同端点不会共享投影`() = runTest {
        val firstWorkspace = RemoteWorkspace(AgentEndpoint(key("first"), "一"), "/first")
        val secondWorkspace = RemoteWorkspace(AgentEndpoint(key("second"), "二"), "/second")
        val firstCarrier = Carrier(firstWorkspace)
        val secondCarrier = Carrier(secondWorkspace)
        val first = RemoteConversationRepository(firstCarrier, firstWorkspace, backgroundScope, { local })
        val second = RemoteConversationRepository(secondCarrier, secondWorkspace, backgroundScope, { local })
        assertEquals(local, first.listConversations(scope, PageRequest()).conversations.single().id.value)
        first.timeline(local, PageRequest())
        assertNull(second.queryMessage(local, ClientMessageId("unknown")))
        assertNotEquals(first.workspace.endpoint.key, second.workspace.endpoint.key)
        assertEquals("hermes:相同/session", RemoteLocalIds.decode(local))
        first.close(); second.close()
    }
    @Test fun `关闭旧端点会停止旧订阅且旧帧不能进入新端点`() = runTest {
        val workspace = RemoteWorkspace(AgentEndpoint(key("first"), "一"), "/first")
        val carrier = Carrier(workspace)
        val observed = mutableListOf<VerifiedConversationEvent>()
        val repo = RemoteConversationRepository(carrier, workspace, backgroundScope, { local }, onEvent = { _, event -> observed += event })
        repo.timeline(local, PageRequest()); runCurrent()
        repo.close()
        carrier.frames.emit(obj("method" to "session/update", "params" to obj("sessionId" to "hermes:相同/session",
            "update" to obj("sessionUpdate" to "agent_message_chunk", "content" to obj("type" to "text", "text" to "迟到")))))
        assertTrue(observed.isEmpty())
        try { repo.timeline(local, PageRequest()); fail("旧实例不能继续读取") } catch (_: IllegalStateException) { }
    }
    @Test fun `prompt受理后未知响应保留原身份且不自动重发`() = runTest {
        val workspace = RemoteWorkspace(AgentEndpoint(key("first"), "一"), "/first")
        val carrier = Carrier(workspace).apply { promptResult = { throw RemoteRpcException("RPC_TIMEOUT", RemoteDeliveryOutcome.UNKNOWN) } }
        val observed = mutableListOf<VerifiedConversationEvent>()
        val repo = RemoteConversationRepository(carrier, workspace, backgroundScope, { local }, onEvent = { _, event -> observed += event })
        repo.submitMessage(local, OutgoingMessage(ClientMessageId("original"), "消息")); runCurrent()
        assertEquals(1, carrier.prompts.size)
        assertEquals(GenerationState.OUTCOME_UNKNOWN, repo.generationState.value)
        assertTrue(observed.any { it is VerifiedConversationEvent.PendingDeliveryUpdated && it.delivery.state == PendingDeliveryState.OUTCOME_UNKNOWN })
        assertFalse(observed.any { it is VerifiedConversationEvent.MessageStatus && it.status == AgentMessageStatus.FAILED })
        repo.close()
    }
    @Test fun `晚到历史不能覆盖已应用的更高序号正文`() {
        val projection = RemoteTimelineProjection(local)
        fun row(seq: Int, text: String) = obj("type" to "event", "event" to obj("seq" to seq, "type" to "assistant/message",
            "data" to obj("message" to obj("id" to "same-message", "content" to listOf(obj("type" to "text", "text" to text))))))
        projection.accept(row(8, "新正文"))
        projection.accept(row(2, "旧正文"), history = true)
        assertEquals("新正文", (projection.rows.getValue("same-message").parts.single() as MessagePart.Text).value)
    }
    @Test fun `ACP受理in_progress保持运行直到真实prompt_completed`() = runTest {
        val workspace = RemoteWorkspace(AgentEndpoint(key("first"), "一"), "/first")
        val carrier = Carrier(workspace)
        val repo = RemoteConversationRepository(carrier, workspace, backgroundScope, { local })
        repo.submitMessage(local, OutgoingMessage(ClientMessageId("request"), "消息")); runCurrent()
        assertEquals(GenerationState.RUNNING, repo.generationState.value)
        carrier.frames.emit(obj("method" to "session/update", "params" to obj("sessionId" to "hermes:相同/session",
            "update" to obj("sessionUpdate" to "prompt_completed", "stopReason" to "end_turn"))))
        runCurrent()
        assertEquals(GenerationState.COMPLETED, repo.generationState.value)
        repo.close()
    }
    @Test fun `连续普通发送的旧任务终态不能完成较新请求`() = runTest {
        val workspace = RemoteWorkspace(AgentEndpoint(key("first"), "一"), "/first")
        val carrier = Carrier(workspace)
        var calls = 0
        carrier.promptResult = { obj("turn" to obj("id" to if (++calls == 1) "turn-first" else "turn-second")) }
        val observed = mutableListOf<VerifiedConversationEvent>()
        val repo = RemoteConversationRepository(carrier, workspace, backgroundScope, { local }, onEvent = { _, event -> observed += event })
        repo.submitMessage(local, OutgoingMessage(ClientMessageId("first"), "一")); runCurrent()
        repo.submitMessage(local, OutgoingMessage(ClientMessageId("second"), "二")); runCurrent()
        carrier.frames.emit(obj("method" to "turn/started", "params" to obj("threadId" to "hermes:相同/session", "turn" to obj("id" to "turn-second"))))
        carrier.frames.emit(obj("method" to "turn/completed", "params" to obj("threadId" to "hermes:相同/session", "turn" to obj("id" to "turn-first", "status" to "completed"))))
        runCurrent()
        assertEquals(listOf("first"), observed.filterIsInstance<VerifiedConversationEvent.MessageStatus>().filter { it.status == AgentMessageStatus.COMPLETED }.map { it.clientMessageId.value })
        assertEquals(GenerationState.RUNNING, repo.generationState.value)
        assertEquals("turn-second", repo.generationId.value)
        carrier.frames.emit(obj("method" to "turn/completed", "params" to obj("threadId" to "hermes:相同/session", "turn" to obj("id" to "turn-second", "status" to "completed"))))
        runCurrent()
        assertEquals(listOf("first", "second"), observed.filterIsInstance<VerifiedConversationEvent.MessageStatus>().filter { it.status == AgentMessageStatus.COMPLETED }.map { it.clientMessageId.value })
        repo.close()
    }
    @Test fun `合并批次的每个成员都收到同一真实任务终态`() = runTest {
        val workspace = RemoteWorkspace(AgentEndpoint(key("first"), "一"), "/first")
        val carrier = Carrier(workspace).apply { promptResult = { obj("turn" to obj("id" to "turn")) } }
        val observed = mutableListOf<VerifiedConversationEvent>()
        val repo = RemoteConversationRepository(carrier, workspace, backgroundScope, { local }, onEvent = { _, event -> observed += event })
        repo.submitBatch(local, MessageBatch("batch", listOf(OutgoingMessage(ClientMessageId("one"), "一"), OutgoingMessage(ClientMessageId("two"), "二"))))
        runCurrent()
        assertEquals(1, carrier.prompts.size)
        carrier.frames.emit(obj("method" to "turn/completed", "params" to obj("threadId" to "hermes:相同/session", "turn" to obj("id" to "turn", "status" to "completed"))))
        runCurrent()
        assertEquals(setOf("one", "two"), observed.filterIsInstance<VerifiedConversationEvent.MessageStatus>().filter { it.status == AgentMessageStatus.COMPLETED }.map { it.clientMessageId.value }.toSet())
        repo.close()
    }
    @Test fun `不透明标识拒绝非UTF8字符而不替换为同名问号`() {
        assertThrows(IllegalArgumentException::class.java) { RemoteLocalIds.encode("坏\uD800标识") }
        val invalid = "remote_" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0xc0.toByte(), 0xaf.toByte()))
        assertThrows(IllegalArgumentException::class.java) { RemoteLocalIds.decode(invalid) }
        assertNotEquals(RemoteLocalIds.encode("?"), RemoteLocalIds.encode("？"))
    }
    @Test fun `时间单位由各后端协议确定不能按数值大小猜测`() {
        assertEquals(5L, millis(obj("time" to 5), "time"))
        assertEquals(5_000L, secondsMillis(obj("time" to 5), "time"))
    }
}
