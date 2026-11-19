package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.data.remote.*
import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.mobile.conversations.nativeNotificationInput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 实时回复穿过仓储、通知桥和完成围栏，历史正文不能冒充本轮新提醒。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemoteCompletionNotificationTest {
    private fun obj(vararg values: Pair<String, Any?>) = Json.of(linkedMapOf(*values)) as JsonValue.JObject
    @Test fun `真实ACP回复完成只提醒一次且加载历史不触发提醒`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val publications = mutableListOf<Pair<NativeConversationSession, Boolean>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val native = "session:opaque"
        val local = RemoteLocalIds.encode(native)
        val workspace = RemoteWorkspace(AgentEndpoint(EndpointKey(RemoteServiceOrigin.fromCanonicalOrigin("https://remote.example"),
            "account", "host", BackendId("hermes"), "workspace"), "工作区"), "/workspace")
        val preferences = context.getSharedPreferences("remote-notification-integration-test", 0)
        preferences.edit().clear().commit()
        val notifications = NativeConversationNotifications(context, scope, { session, alert -> publications += session to alert },
            throttleMillis = 0, preferencesFor = { _, _ -> preferences })
        notifications.bind("profile", "account")
        val frames = MutableSharedFlow<JsonValue>()
        val response = CompletableDeferred<JsonValue>()
        val carrier = object : RemoteSessionCarrier {
            override val channel = object : RemoteRpcChannel {
                override val scope = RemoteRpcConnectionScope("https://remote.example", "account", "host", "client", "connection", 1)
                override val capabilities = setOf("agent.acp.v1", "agent.acp.hermes.v1")
                override val events = emptyFlow<RemoteRpcEvent>()
                override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue = error("不允许绕过载体")
                override suspend fun close() = Unit
            }
            override val backend = "hermes"
            override val capabilities = setOf(SessionCapability.SEND, SessionCapability.STREAM)
            override suspend fun workspaces() = listOf(workspace)
            override suspend fun sessions(workspace: RemoteWorkspace, page: PageRequest) = RemoteSessionPage(emptyList())
            override suspend fun create(workspace: RemoteWorkspace): RemoteSessionRow = error("不应创建会话")
            override suspend fun history(sessionId: String, page: PageRequest) = RemoteHistoryPage(emptyList())
            override fun follow(sessionId: String): Flow<JsonValue> = flow { emit(obj("type" to "remote-stream-ready")); emitAll(frames) }
            override suspend fun prompt(sessionId: String, requestId: String, content: List<JsonValue>, model: RemoteModelSelection?) = response.await()
            override suspend fun interrupt(sessionId: String, generationId: String): JsonValue = error("不应中断")
            override suspend fun respond(sessionId: String, handle: String, allow: Boolean): JsonValue = error("不应审批")
            override suspend fun models(sessionId: String): RemoteModelCatalog = error("不应查询模型")
            override suspend fun selectModel(sessionId: String, selection: RemoteModelSelection) = selection
        }
        val repository = RemoteConversationRepository(carrier, workspace, scope, { local }, onEvent = { _, event ->
            nativeNotificationInput("profile", "account", "endpoint", event)?.let(notifications::accept)
        })
        try {
            repository.timeline(local, PageRequest())
            frames.emit(obj("type" to "snapshot", "cursor" to 1, "records" to listOf(obj("type" to "event", "event" to obj(
                "seq" to 1, "time" to System.currentTimeMillis(), "type" to "assistant/message", "data" to obj("message" to obj("id" to "old",
                    "content" to listOf(obj("type" to "text", "text" to "历史回答")))))))))
            assertTrue("历史快照不能当成实时提醒", notifications.sessions.value.isEmpty())
            repository.submitMessage(local, OutgoingMessage(ClientMessageId("request"), "消息"))
            frames.emit(obj("method" to "session/update", "params" to obj("sessionId" to native,
                "update" to obj("sessionUpdate" to "agent_message_chunk", "content" to obj("type" to "text", "text" to "真实新回答")))))
            frames.emit(obj("method" to "session/update", "params" to obj("sessionId" to native,
                "update" to obj("sessionUpdate" to "prompt_completed", "stopReason" to "end_turn"))))
            response.complete(obj("accepted" to true, "stopReason" to "in_progress"))
            withTimeout(5_000) { while (publications.none { it.second }) delay(10) }
            assertEquals(1, publications.count { it.second })
            assertEquals("真实新回答", publications.last().first.latestReply)
            assertEquals(NativeConversationStatus.COMPLETED, publications.last().first.status)
        } finally { repository.close(); scope.cancel() }
    }

    @Test fun `Harness工作流先turnStart建立别名且prompt回执省略turnId时正常结束`() = runBlocking {
        verifyBackendCompletion(
            backend = "harness",
            capability = "agent.harness.v1",
            prefName = "remote-harness-completion-test",
        ) { repo, local, frames, promptResponse, _, publications ->
            repo.submitMessage(local, OutgoingMessage(ClientMessageId("req-harness"), "开始工作流"))
            frames.emit(obj("type" to "event", "event" to obj("seq" to 1, "type" to "turn/start", "data" to obj("turnId" to "harness-turn-1"))))
            promptResponse.complete(obj("accepted" to true))
            frames.emit(obj("type" to "event", "event" to obj(
                "seq" to 2,
                "type" to "assistant/message",
                "data" to obj("turnId" to "harness-turn-1", "message" to obj("id" to "msg-h1", "content" to listOf(obj("type" to "text", "text" to "Harness最终回复")))),
            )))
            frames.emit(obj("type" to "event", "event" to obj("seq" to 3, "type" to "turn/end", "data" to obj("turnId" to "harness-turn-1", "reason" to obj("kind" to "done")))))
            withTimeout(5_000) { while (publications.none { it.second }) delay(10) }
            assertEquals(1, publications.count { it.second })
            assertEquals("Harness最终回复", publications.last().first.latestReply)
            assertEquals(NativeConversationStatus.COMPLETED, publications.last().first.status)
        }
    }

    @Test fun `Codex非本机发起的外来轮次完成后仍发送终态并退出LiveUpdate`() = runBlocking {
        verifyBackendCompletion(
            backend = "codex",
            capability = "agent.codex.v1",
            prefName = "remote-codex-completion-test",
        ) { _, _, frames, _, native, publications ->
            frames.emit(obj("method" to "turn/started", "params" to obj("threadId" to native, "turn" to obj("id" to "codex-ext-1"))))
            frames.emit(obj("method" to "item/completed", "params" to obj(
                "threadId" to native,
                "turnId" to "codex-ext-1",
                "item" to obj("id" to "codex-msg-1", "type" to "agentMessage", "text" to "Codex外来轮次最终回复"),
            )))
            frames.emit(obj("method" to "turn/completed", "params" to obj(
                "threadId" to native,
                "turn" to obj("id" to "codex-ext-1", "status" to "completed"),
            )))
            withTimeout(5_000) { while (publications.none { it.second }) delay(10) }
            assertEquals(1, publications.count { it.second })
            assertEquals("Codex外来轮次最终回复", publications.last().first.latestReply)
            assertEquals(NativeConversationStatus.COMPLETED, publications.last().first.status)
        }
    }

    @Test fun `ACP回复缺少generationId时promptCompleted仍正确终结通知`() = runBlocking {
        verifyBackendCompletion(
            backend = "hermes",
            capability = "agent.acp.v1",
            prefName = "remote-acp-null-gen-test",
        ) { repo, local, frames, promptResponse, native, publications ->
            repo.submitMessage(local, OutgoingMessage(ClientMessageId("req-acp-null"), "无ID提问"))
            promptResponse.complete(obj("accepted" to true))
            frames.emit(obj("method" to "session/update", "params" to obj("sessionId" to native,
                "update" to obj("sessionUpdate" to "agent_message_chunk", "content" to obj("type" to "text", "text" to "ACP无generationId回复")))))
            frames.emit(obj("method" to "session/update", "params" to obj("sessionId" to native,
                "update" to obj("sessionUpdate" to "prompt_completed", "stopReason" to "end_turn"))))
            withTimeout(5_000) { while (publications.none { it.second }) delay(10) }
            assertEquals(1, publications.count { it.second })
            assertEquals("ACP无generationId回复", publications.last().first.latestReply)
            assertEquals(NativeConversationStatus.COMPLETED, publications.last().first.status)
        }
    }

    private suspend fun verifyBackendCompletion(
        backend: String,
        capability: String,
        prefName: String,
        block: suspend (
            repository: RemoteConversationRepository,
            local: String,
            frames: MutableSharedFlow<JsonValue>,
            promptResponse: CompletableDeferred<JsonValue>,
            native: String,
            publications: MutableList<Pair<NativeConversationSession, Boolean>>,
        ) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val publications = mutableListOf<Pair<NativeConversationSession, Boolean>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val native = "session:$backend-opaque"
        val local = RemoteLocalIds.encode(native)
        val workspace = RemoteWorkspace(AgentEndpoint(EndpointKey(RemoteServiceOrigin.fromCanonicalOrigin("https://remote.example"),
            "account", "host", BackendId(backend), "workspace"), "工作区"), "/workspace")
        val preferences = context.getSharedPreferences(prefName, 0)
        preferences.edit().clear().commit()
        val notifications = NativeConversationNotifications(context, scope, { session, alert -> publications += session to alert },
            throttleMillis = 0, preferencesFor = { _, _ -> preferences })
        notifications.bind("profile", "account")
        val frames = MutableSharedFlow<JsonValue>()
        val response = CompletableDeferred<JsonValue>()
        val carrier = object : RemoteSessionCarrier {
            override val channel = object : RemoteRpcChannel {
                override val scope = RemoteRpcConnectionScope("https://remote.example", "account", "host", "client", "connection", 1)
                override val capabilities = setOf(capability)
                override val events = emptyFlow<RemoteRpcEvent>()
                override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue = error("不允许绕过载体")
                override suspend fun close() = Unit
            }
            override val backend = backend
            override val capabilities = setOf(SessionCapability.SEND, SessionCapability.STREAM)
            override suspend fun workspaces() = listOf(workspace)
            override suspend fun sessions(workspace: RemoteWorkspace, page: PageRequest) = RemoteSessionPage(emptyList())
            override suspend fun create(workspace: RemoteWorkspace): RemoteSessionRow = error("不应创建会话")
            override suspend fun history(sessionId: String, page: PageRequest) = RemoteHistoryPage(emptyList())
            override fun follow(sessionId: String): Flow<JsonValue> = flow { emit(obj("type" to "remote-stream-ready")); emitAll(frames) }
            override suspend fun prompt(sessionId: String, requestId: String, content: List<JsonValue>, model: RemoteModelSelection?) = response.await()
            override suspend fun interrupt(sessionId: String, generationId: String): JsonValue = error("不应中断")
            override suspend fun respond(sessionId: String, handle: String, allow: Boolean): JsonValue = error("不应审批")
            override suspend fun models(sessionId: String): RemoteModelCatalog = error("不应查询模型")
            override suspend fun selectModel(sessionId: String, selection: RemoteModelSelection) = selection
        }
        val repository = RemoteConversationRepository(carrier, workspace, scope, { local }, onEvent = { _, event ->
            nativeNotificationInput("profile", "account", "endpoint", event)?.let(notifications::accept)
        })
        try {
            repository.timeline(local, PageRequest())
            block(repository, local, frames, response, native, publications)
        } finally {
            repository.close()
            scope.cancel()
        }
    }
}
