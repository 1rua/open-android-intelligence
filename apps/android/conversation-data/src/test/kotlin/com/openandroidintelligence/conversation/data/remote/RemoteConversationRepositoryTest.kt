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
    private fun key(workspace: String, backend: String = "hermes") = EndpointKey(RemoteServiceOrigin.fromCanonicalOrigin("https://remote.example"), "account", "host", BackendId(backend), workspace)
    private class Carrier(workspace: RemoteWorkspace) : RemoteSessionCarrier {
        override val channel = object : RemoteRpcChannel {
            override val scope = RemoteRpcConnectionScope("https://remote.example", "account", "host", "client", "connection", 1)
            override val capabilities = setOf("agent.acp.v1", "agent.acp.hermes.v1")
            override val events = emptyFlow<RemoteRpcEvent>()
            override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long) = obj()
            override suspend fun close() = Unit
        }
        override val backend = workspace.endpoint.key.backendId.value
        override val capabilities = setOf(SessionCapability.LIST, SessionCapability.HISTORY, SessionCapability.CREATE, SessionCapability.SEND)
        val frames = MutableSharedFlow<JsonValue>()
        var followFrames: (String) -> Flow<JsonValue> = { frames }
        val prompts = mutableListOf<Pair<String, List<JsonValue>>>()
        var promptResult: suspend () -> JsonValue = { obj("accepted" to true, "stopReason" to "in_progress") }
        var historyRead: suspend () -> RemoteHistoryPage = { RemoteHistoryPage(listOf(obj("type" to "event", "event" to obj(
            "seq" to 0, "type" to "assistant/message", "data" to obj("message" to obj("id" to "message", "content" to listOf(obj("type" to "text", "text" to "历史")))))))) }
        private val row = workspace
        override suspend fun workspaces() = listOf(row)
        override suspend fun sessions(workspace: RemoteWorkspace, page: PageRequest) = RemoteSessionPage(listOf(RemoteSessionRow("hermes:相同/session", "会话", 0, 1)))
        override suspend fun create(workspace: RemoteWorkspace) = RemoteSessionRow("hermes:相同/session", "", 0, 0)
        override suspend fun history(sessionId: String, page: PageRequest): RemoteHistoryPage { assertEquals("hermes:相同/session", sessionId); return historyRead() }
        override fun follow(sessionId: String): Flow<JsonValue> = flow { emit(obj("type" to "remote-stream-ready")); emitAll(followFrames(sessionId)) }
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
        try { second.queryMessage(local, ClientMessageId("unknown")); fail("投影缺失不是权威未执行") }
        catch (failure: java.io.IOException) { assertEquals("REMOTE_MESSAGE_QUERY_UNAVAILABLE", failure.message) }
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
    @Test fun `无序号实时流之后的迟到历史不能覆盖当前正文`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("method" to "item/agentMessage/delta", "params" to obj(
            "threadId" to "hermes:相同/session", "itemId" to "same-message", "delta" to "实时正文")))
        projection.accept(obj("type" to "event", "event" to obj("seq" to 2, "type" to "assistant/message",
            "data" to obj("message" to obj("id" to "same-message", "content" to listOf(obj("type" to "text", "text" to "旧历史")))))), history = true)
        assertEquals("实时正文", (projection.rows.getValue("same-message").parts.single() as MessagePart.Text).value)
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
    @Test fun `其他客户端的任务结束后按顺序自动发送排队消息`() = runTest {
        val workspace = RemoteWorkspace(AgentEndpoint(key("first", "codex"), "一"), "/first")
        val carrier = Carrier(workspace).apply { promptResult = { obj("turn" to obj("id" to "turn-${prompts.size}")) } }
        val repo = RemoteConversationRepository(carrier, workspace, backgroundScope, { local })
        repo.timeline(local, PageRequest()); runCurrent()
        carrier.frames.emit(obj("method" to "turn/started", "params" to obj(
            "threadId" to "hermes:相同/session", "turn" to obj("id" to "other-client-turn"))))
        runCurrent()
        assertEquals(GenerationState.RUNNING, repo.generationState.value)
        repo.submitMessage(local, OutgoingMessage(ClientMessageId("queued-first"), "排队一", delivery = MessageDeliveryMode.QUEUE))
        repo.submitMessage(local, OutgoingMessage(ClientMessageId("queued-second"), "排队二", delivery = MessageDeliveryMode.QUEUE))
        runCurrent()
        assertTrue(carrier.prompts.isEmpty())
        assertEquals(listOf("queued-first", "queued-second"), repo.pendingDeliveries(local).map { it.clientMessageId.value })

        val completed = obj("method" to "turn/completed", "params" to obj("threadId" to "hermes:相同/session",
            "turn" to obj("id" to "other-client-turn", "status" to "completed")))
        carrier.frames.emit(completed); runCurrent()
        assertEquals(listOf("排队一"), carrier.prompts.map { it.second.single().text("text") })
        assertEquals(listOf("queued-second"), repo.pendingDeliveries(local).map { it.clientMessageId.value })
        assertEquals(GenerationState.RUNNING, repo.generationState.value)

        carrier.frames.emit(completed); runCurrent()
        assertEquals(1, carrier.prompts.size)
        carrier.frames.emit(obj("method" to "turn/completed", "params" to obj("threadId" to "hermes:相同/session",
            "turn" to obj("id" to "turn-1", "status" to "completed"))))
        runCurrent()
        assertEquals(listOf("排队一", "排队二"), carrier.prompts.map { it.second.single().text("text") })
        assertTrue(repo.pendingDeliveries(local).isEmpty())
        repo.close()
    }
    @Test fun `本地任务结算后其他客户端的任务结束仍会发送排队消息`() = runTest {
        val workspace = RemoteWorkspace(AgentEndpoint(key("first", "codex"), "一"), "/first")
        val carrier = Carrier(workspace).apply { promptResult = { obj("turn" to obj("id" to "turn-${prompts.size}")) } }
        val repo = RemoteConversationRepository(carrier, workspace, backgroundScope, { local })
        repo.submitMessage(local, OutgoingMessage(ClientMessageId("previous-local"), "先前消息")); runCurrent()
        carrier.frames.emit(obj("method" to "turn/completed", "params" to obj("threadId" to "hermes:相同/session",
            "turn" to obj("id" to "turn-1", "status" to "completed"))))
        runCurrent()
        assertEquals(GenerationState.COMPLETED, repo.generationState.value)

        carrier.frames.emit(obj("method" to "turn/started", "params" to obj(
            "threadId" to "hermes:相同/session", "turn" to obj("id" to "other-client-turn"))))
        runCurrent()
        repo.submitMessage(local, OutgoingMessage(ClientMessageId("queued"), "排队消息", delivery = MessageDeliveryMode.QUEUE))
        runCurrent()
        assertEquals(1, carrier.prompts.size)
        carrier.frames.emit(obj("method" to "turn/completed", "params" to obj("threadId" to "hermes:相同/session",
            "turn" to obj("id" to "other-client-turn", "status" to "completed"))))
        runCurrent()
        assertEquals(listOf("先前消息", "排队消息"), carrier.prompts.map { it.second.single().text("text") })
        assertTrue(repo.pendingDeliveries(local).isEmpty())
        repo.close()
    }
    @Test fun `任务终态先到且队首等待附件时迟到回执不能越过队首发送`() = runTest {
        val workspace = RemoteWorkspace(AgentEndpoint(key("first", "codex"), "一"), "/first")
        val previousResponse = CompletableDeferred<JsonValue>()
        val attachmentStarted = CompletableDeferred<Unit>()
        val attachmentReady = CompletableDeferred<List<JsonValue>>()
        val carrier = Carrier(workspace).apply {
            promptResult = {
                if (prompts.size == 1) previousResponse.await()
                else obj("turn" to obj("id" to "turn-${prompts.size}"))
            }
        }
        val repo = RemoteConversationRepository(carrier, workspace, backgroundScope, { local }, attachmentContent = { id, draft ->
            assertEquals(local, id)
            assertEquals("draft", draft)
            attachmentStarted.complete(Unit)
            attachmentReady.await()
        })
        try {
            repo.submitMessage(local, OutgoingMessage(ClientMessageId("previous"), "原任务")); runCurrent()
            carrier.frames.emit(obj("method" to "turn/started", "params" to obj(
                "threadId" to "hermes:相同/session", "turn" to obj("id" to "previous-turn"))))
            runCurrent()
            repo.submitMessage(local, OutgoingMessage(ClientMessageId("queued-attachment"), "排队附件", listOf("draft"), delivery = MessageDeliveryMode.QUEUE))
            repo.submitMessage(local, OutgoingMessage(ClientMessageId("queued-text"), "排队文本", delivery = MessageDeliveryMode.QUEUE))
            carrier.frames.emit(obj("method" to "turn/completed", "params" to obj("threadId" to "hermes:相同/session",
                "turn" to obj("id" to "previous-turn", "status" to "completed"))))
            runCurrent()
            assertTrue(attachmentStarted.isCompleted)
            assertEquals(1, carrier.prompts.size)

            previousResponse.complete(obj("turn" to obj("id" to "previous-turn")))
            runCurrent()
            assertEquals("队首附件准备完成前，迟到回执不能发送下一条消息", listOf("原任务"),
                carrier.prompts.map { it.second.first().text("text") })
            assertEquals(listOf("queued-text"), repo.pendingDeliveries(local).map { it.clientMessageId.value })

            attachmentReady.complete(listOf(obj("type" to "resource_link", "uri" to "file:attachment")))
            runCurrent()
            assertEquals(listOf("原任务", "排队附件"), carrier.prompts.map { it.second.first().text("text") })
            assertEquals(listOf("queued-text"), repo.pendingDeliveries(local).map { it.clientMessageId.value })
            carrier.frames.emit(obj("method" to "turn/completed", "params" to obj("threadId" to "hermes:相同/session",
                "turn" to obj("id" to "turn-2", "status" to "completed"))))
            runCurrent()
            assertEquals(listOf("原任务", "排队附件", "排队文本"), carrier.prompts.map { it.second.first().text("text") })
            assertTrue(repo.pendingDeliveries(local).isEmpty())
        } finally {
            attachmentReady.complete(emptyList())
            repo.close()
        }
    }
    @Test fun `一个会话队首等待附件不阻塞另一个会话的队列发送`() = runTest {
        val workspace = RemoteWorkspace(AgentEndpoint(key("first", "codex"), "一"), "/first")
        val otherNative = "codex:另一个/session"
        val other = RemoteLocalIds.encode(otherNative)
        val firstFrames = MutableSharedFlow<JsonValue>()
        val otherFrames = MutableSharedFlow<JsonValue>()
        val attachmentStarted = CompletableDeferred<Unit>()
        val attachmentReady = CompletableDeferred<List<JsonValue>>()
        val carrier = Carrier(workspace).apply {
            followFrames = { if (it == otherNative) otherFrames else firstFrames }
            promptResult = { obj("turn" to obj("id" to "turn-${prompts.size}")) }
        }
        val repo = RemoteConversationRepository(carrier, workspace, backgroundScope, { local }, attachmentContent = { id, draft ->
            assertEquals(local, id)
            assertEquals("draft", draft)
            attachmentStarted.complete(Unit)
            attachmentReady.await()
        })
        try {
            repo.submitMessage(local, OutgoingMessage(ClientMessageId("first-running"), "会话一任务")); runCurrent()
            repo.submitMessage(other, OutgoingMessage(ClientMessageId("other-running"), "会话二任务")); runCurrent()
            repo.submitMessage(local, OutgoingMessage(ClientMessageId("first-queued"), "会话一附件", listOf("draft"), delivery = MessageDeliveryMode.QUEUE))
            repo.submitMessage(other, OutgoingMessage(ClientMessageId("other-queued"), "会话二排队", delivery = MessageDeliveryMode.QUEUE))

            firstFrames.emit(obj("method" to "turn/completed", "params" to obj("threadId" to "hermes:相同/session",
                "turn" to obj("id" to "turn-1", "status" to "completed"))))
            runCurrent()
            assertTrue(attachmentStarted.isCompleted)
            assertEquals(2, carrier.prompts.size)
            otherFrames.emit(obj("method" to "turn/completed", "params" to obj("threadId" to otherNative,
                "turn" to obj("id" to "turn-2", "status" to "completed"))))
            runCurrent()
            assertEquals(listOf("会话一任务", "会话二任务", "会话二排队"),
                carrier.prompts.map { it.second.first().text("text") })
            assertEquals(otherNative, carrier.prompts.last().first)
            assertTrue(repo.pendingDeliveries(other).isEmpty())

            attachmentReady.complete(listOf(obj("type" to "resource_link", "uri" to "file:attachment")))
            runCurrent()
            assertEquals(listOf("会话一任务", "会话二任务", "会话二排队", "会话一附件"),
                carrier.prompts.map { it.second.first().text("text") })
            assertEquals("hermes:相同/session", carrier.prompts.last().first)
        } finally {
            attachmentReady.complete(emptyList())
            repo.close()
        }
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

    @Test fun `Codex真实终态先于发送回执仍结算批次全部成员`() = runTest {
        val workspace = RemoteWorkspace(AgentEndpoint(key("first"), "一"), "/first")
        val response = CompletableDeferred<JsonValue>()
        val carrier = Carrier(workspace).apply { promptResult = { response.await() } }
        val observed = mutableListOf<VerifiedConversationEvent>()
        val repo = RemoteConversationRepository(carrier, workspace, backgroundScope, { local }, onEvent = { _, event -> observed += event })
        repo.submitBatch(local, MessageBatch("batch", listOf(OutgoingMessage(ClientMessageId("first"), "一"), OutgoingMessage(ClientMessageId("second"), "二"))))
        runCurrent()
        carrier.frames.emit(obj("method" to "turn/started", "params" to obj("threadId" to "hermes:相同/session", "turn" to obj("id" to "T"))))
        carrier.frames.emit(obj("method" to "item/agentMessage/delta", "params" to obj("threadId" to "hermes:相同/session", "itemId" to "reply", "delta" to "答案")))
        carrier.frames.emit(obj("method" to "turn/completed", "params" to obj("threadId" to "hermes:相同/session", "turn" to obj("id" to "T", "status" to "completed"))))
        runCurrent()
        response.complete(obj("turn" to obj("id" to "T")))
        runCurrent()
        val completed = observed.filterIsInstance<VerifiedConversationEvent.MessageStatus>().filter { it.status == AgentMessageStatus.COMPLETED }
        assertEquals(setOf("first", "second"), completed.map { it.clientMessageId.value }.toSet())
        assertEquals(GenerationState.COMPLETED, repo.generationState.value)
        assertTrue(completed.all { it.occurredAt > 0 })
        assertTrue(observed.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().all { it.occurredAt > 0 && it.message.timestamp > 0 })
        repo.close()
    }

    @Test fun `ACP终态先到受理回执不能恢复为运行中`() = runTest {
        val workspace = RemoteWorkspace(AgentEndpoint(key("first"), "一"), "/first")
        val response = CompletableDeferred<JsonValue>()
        val carrier = Carrier(workspace).apply { promptResult = { response.await() } }
        val observed = mutableListOf<VerifiedConversationEvent>()
        val repo = RemoteConversationRepository(carrier, workspace, backgroundScope, { local }, onEvent = { _, event -> observed += event })
        repo.submitMessage(local, OutgoingMessage(ClientMessageId("request"), "一")); runCurrent()
        carrier.frames.emit(obj("method" to "session/update", "params" to obj("sessionId" to "hermes:相同/session",
            "update" to obj("sessionUpdate" to "agent_message_chunk", "content" to obj("type" to "text", "text" to "答案")))))
        carrier.frames.emit(obj("method" to "session/update", "params" to obj("sessionId" to "hermes:相同/session",
            "update" to obj("sessionUpdate" to "prompt_completed", "stopReason" to "end_turn"))))
        runCurrent()
        response.complete(obj("accepted" to true, "stopReason" to "in_progress")); runCurrent()
        assertEquals(GenerationState.COMPLETED, repo.generationState.value)
        assertEquals(1, observed.filterIsInstance<VerifiedConversationEvent.MessageStatus>().count { it.status == AgentMessageStatus.COMPLETED })
        repo.close()
    }

    @Test fun `ACP终态先到且回执补充turn身份时仍结算为终态`() = runTest {
        val workspace = RemoteWorkspace(AgentEndpoint(key("first"), "一"), "/first")
        val response = CompletableDeferred<JsonValue>()
        val carrier = Carrier(workspace).apply { promptResult = { response.await() } }
        val observed = mutableListOf<VerifiedConversationEvent>()
        val repo = RemoteConversationRepository(carrier, workspace, backgroundScope, { local }, onEvent = { _, event -> observed += event })
        repo.submitMessage(local, OutgoingMessage(ClientMessageId("request"), "一")); runCurrent()
        carrier.frames.emit(obj("method" to "session/update", "params" to obj("sessionId" to "hermes:相同/session",
            "update" to obj("sessionUpdate" to "prompt_completed", "stopReason" to "end_turn"))))
        runCurrent()
        response.complete(obj("accepted" to true, "turn" to obj("id" to "native-turn"), "stopReason" to "in_progress")); runCurrent()
        assertEquals(GenerationState.COMPLETED, repo.generationState.value)
        assertEquals(1, observed.filterIsInstance<VerifiedConversationEvent.MessageStatus>().count { it.status == AgentMessageStatus.COMPLETED })
        repo.close()
    }

    @Test fun `Codex用户回执发布被流背压挂起时不会与终态事件乱序穿插`() = runTest {
        val workspace = RemoteWorkspace(AgentEndpoint(key("first", "codex"), "一"), "/first")
        val response = CompletableDeferred<JsonValue>()
        val carrier = Carrier(workspace).apply { promptResult = { response.await() } }
        val observed = mutableListOf<VerifiedConversationEvent>()
        val repo = RemoteConversationRepository(carrier, workspace, backgroundScope, { local }, onEvent = { _, event -> observed += event })
        val collectorPaused = CompletableDeferred<Unit>()
        val collectJob = backgroundScope.launch {
            repo.observeEvents(scope).collect { event ->
                if (event is VerifiedConversationEvent.TimelineUpsert && event.message.id == "user-race") {
                    collectorPaused.await()
                }
            }
        }
        runCurrent()

        repo.submitMessage(local, OutgoingMessage(ClientMessageId("client-race"), "测试文本"))
        runCurrent()
        // 触发原生用户回声，让订阅方在背压门控处暂停，确保后续 events.emit(MessageAccepted) 直接挂起。
        carrier.frames.emit(obj("method" to "item/completed", "params" to obj(
            "threadId" to "hermes:相同/session", "turnId" to "turn-race",
            "item" to obj("id" to "user-race", "type" to "userMessage", "content" to listOf(obj("type" to "text", "text" to "测试文本"))))))
        runCurrent()

        // 此时 response.complete 会在 publish(MessageAccepted) 的 events.emit 挂起（持有同会话 sessionLocks）。
        response.complete(obj("turn" to obj("id" to "turn-race")))
        runCurrent()
        val completedFrameJob = backgroundScope.launch {
            carrier.frames.emit(obj("method" to "turn/completed", "params" to obj(
                "threadId" to "hermes:相同/session", "turn" to obj("id" to "turn-race", "status" to "completed"))))
        }
        runCurrent()

        collectorPaused.complete(Unit)
        runCurrent()
        completedFrameJob.join()

        val acceptedIndex = observed.indexOfFirst { it is VerifiedConversationEvent.MessageAccepted && it.correlationId == "client-race" }
        val userIdentityIndex = observed.indexOfFirst {
            it is VerifiedConversationEvent.TimelineUpsert && it.message.id == "user-race" && it.message.clientMessageId?.value == "client-race"
        }
        val completedIndex = observed.indexOfFirst {
            it is VerifiedConversationEvent.MessageStatus && it.clientMessageId.value == "client-race" && it.status == AgentMessageStatus.COMPLETED
        }
        assertTrue(acceptedIndex >= 0)
        assertTrue(userIdentityIndex > acceptedIndex)
        assertTrue(completedIndex > userIdentityIndex)
        collectJob.cancel()
        repo.close()
    }

    @Test fun `Harness先收到turnStart建立别名后prompt省略turnId不报错且正常完成`() = runTest {
        val workspace = RemoteWorkspace(AgentEndpoint(key("first", "harness"), "一"), "/first")
        val response = CompletableDeferred<JsonValue>()
        val carrier = Carrier(workspace).apply { promptResult = { response.await() } }
        val observed = mutableListOf<VerifiedConversationEvent>()
        val repo = RemoteConversationRepository(carrier, workspace, backgroundScope, { local }, onEvent = { _, event -> observed += event })
        repo.timeline(local, PageRequest()); runCurrent()

        repo.submitMessage(local, OutgoingMessage(ClientMessageId("client-harness"), "执行"))
        runCurrent()
        // turn/start 在 prompt 返回之前到达，已建立 request:* -> turn-harness 别名
        carrier.frames.emit(obj("type" to "event", "event" to obj("seq" to 1, "type" to "turn/start", "data" to obj("turnId" to "turn-harness"))))
        runCurrent()
        // prompt 回执不再带 turn.id，应按已建别名解析为 turn-harness 而非抛 REMOTE_GENERATION_MISMATCH
        response.complete(obj("accepted" to true))
        runCurrent()

        assertEquals(GenerationState.RUNNING, repo.generationState.value)
        carrier.frames.emit(obj("type" to "event", "event" to obj("seq" to 2, "type" to "turn/end", "data" to obj("turnId" to "turn-harness", "reason" to obj("kind" to "done")))))
        runCurrent()
        assertEquals(GenerationState.COMPLETED, repo.generationState.value)
        assertTrue(observed.any { it is VerifiedConversationEvent.MessageStatus && it.status == AgentMessageStatus.COMPLETED })
        repo.close()
    }

    @Test fun `Codex第1页快照显示无活跃轮次时触发onTimelineSnapshot回调以便收口卡住通知`() = runTest {
        val workspace = RemoteWorkspace(AgentEndpoint(key("first", "codex"), "一"), "/first")
        val carrier = Carrier(workspace).apply {
            historyRead = { RemoteHistoryPage(listOf(obj("id" to "assistant-1", "sender" to "assistant", "text" to "后端已完成")), revision = 5L) }
        }
        var snapshotCalls = 0
        var lastGenerationId: String? = "initial"
        val repo = RemoteConversationRepository(
            carrier,
            workspace,
            backgroundScope,
            { local },
            onTimelineSnapshot = { _, _, generationId, _ ->
                snapshotCalls++
                lastGenerationId = generationId
            },
        )
        // 即使本地投影仍卡在 RUNNING，只要权威快照返回 activeGenerationId=null 就必须触发收口回调
        repo.timeline(local, PageRequest()); runCurrent()
        assertEquals(1, snapshotCalls)
        carrier.frames.emit(obj("method" to "turn/started", "params" to obj("threadId" to "hermes:相同/session", "turn" to obj("id" to "stuck-turn"))))
        runCurrent()
        assertEquals(GenerationState.RUNNING, repo.generationState.value)
        repo.timeline(local, PageRequest()); runCurrent()
        assertEquals(2, snapshotCalls)
        assertNull(lastGenerationId)
        repo.close()
    }
}
