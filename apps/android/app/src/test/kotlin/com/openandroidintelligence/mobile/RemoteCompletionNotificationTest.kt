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
import com.openandroidintelligence.mobile.conversations.nativeNotificationFrame
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
            nativeNotificationFrame("account", "endpoint", event)?.let { notifications.accept("profile", "account", it) }
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
}
