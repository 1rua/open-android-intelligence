package com.openandroidintelligence.mobile

import com.openandroidintelligence.conversation.data.remote.CodexSessionCarrier
import com.openandroidintelligence.conversation.data.remote.RemoteConversationRepository
import com.openandroidintelligence.conversation.data.remote.RemoteWorkspace
import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.StreamHealth
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.ports.AgentCommandCatalogRepository
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.ConversationRepository
import com.openandroidintelligence.conversation.ports.ConversationOrganization
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.conversation.remote.AgentEndpoint
import com.openandroidintelligence.conversation.remote.BackendId
import com.openandroidintelligence.conversation.remote.EndpointKey
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.conversation.remote.RemoteServiceOrigin
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcChannel
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcConnectionScope
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcEvent
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 复用 RemoteRuntime 的本机组织装配，真实控制器、仓储和 Codex carrier 只替换认证后的 RPC 边界。 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNewSessionNavigationTest {
    @Test fun `新建会话流就绪时目录尚未发布不能闪回旧会话`() = 场景()
    @Test fun `新会话进入权威目录后再次缺席仍按原有语义离开`() = 场景("目录收录")
    @Test fun `尚未进入目录的新会话明确删除后不能被创建回执补回`() = 场景("明确删除")
    @Test fun `连续创建期间用户切回旧会话后迟到回执不能抢回导航`() = 场景("连续创建")
    @Test fun `远端明确删除尚未收录的新会话后目录刷新不能复活`() = 场景("远端删除")

    private fun 场景(后续: String = "") = runTest {
        val wire = CodexDirectory()
        val workspace = RemoteWorkspace(AgentEndpoint(EndpointKey(
            RemoteServiceOrigin.fromCanonicalOrigin(wire.scope.origin), wire.scope.accountId,
            wire.scope.hostDeviceId, BackendId("codex"), "work"), "工作区"), "/work")
        val selected = AtomicReference<String?>(null)
        val repository = RemoteConversationRepository(CodexSessionCarrier(wire), workspace, backgroundScope, selected::get)
        val directoryEvents = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 1)
        // 与 RemoteRuntime 相同：会话由原生后端拥有，文件夹组织由本机单独提供。
        val history = object : ConversationRepository by repository {
            override suspend fun readOrganization() = ConversationOrganization()
            override fun observeEvents(scope: ConversationScope) = merge(repository.observeEvents(scope), directoryEvents)
            override suspend fun deleteConversation(conversationId: String) {
                val native = RemoteLocalIds.decode(conversationId)
                wire.published.remove(native)
                wire.calls += "delete:$native"
            }
        }
        val navigation = mutableListOf<String?>()
        val controller = WorkbenchController(backgroundScope, history, object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        }, { ConversationScope("profile", "endpoint", "account", "install") },
            supportsConversationManagement = true, streamHealthSource = repository,
            onActiveThreadChanged = { selected.set(it); navigation += it; repository.activeConversationChanged() },
            replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false))
        try {
            controller.state.first { it.activeThreadId == RemoteLocalIds.encode("old") && it.streamHealth == StreamHealth.LIVE }
            runCurrent()
            assertEquals(RemoteLocalIds.encode("old"), controller.state.value.activeThreadId)

            controller.createThread()
            runCurrent()

            assertTrue("测试必须先收到新会话创建回执并进入新会话", RemoteLocalIds.encode("new") in navigation)
            val openedNew = wire.calls.indexOf("follow:new")
            assertTrue("新会话实际打开了原生流", openedNew >= 0)
            assertTrue("由真实流就绪触发打开之后的目录同步", wire.calls.drop(openedNew + 1).contains("list"))
            assertEquals(StreamHealth.LIVE, controller.state.value.streamHealth)
            assertEquals("目录落盘晚于创建回执不能改变当前导航", RemoteLocalIds.encode("new"), controller.state.value.activeThreadId)
            assertTrue("创建回执仍须保留在会话列表", (controller.state.value.threads as Loadable.Ready).value.any { it.id.value == RemoteLocalIds.encode("new") })
            when (后续) {
                "目录收录" -> {
                    wire.published += "new"
                    controller.refreshThreads(); runCurrent()
                    assertEquals(RemoteLocalIds.encode("new"), controller.state.value.activeThreadId)
                    wire.published -= "new"
                    controller.refreshThreads(); runCurrent()
                    assertEquals("目录已收录后，创建回执不再永久覆盖后续缺席事实", RemoteLocalIds.encode("old"), controller.state.value.activeThreadId)
                }
                "明确删除" -> {
                    controller.deleteThread(RemoteLocalIds.encode("new")); runCurrent()
                    assertTrue("删除必须执行仓储操作", "delete:new" in wire.calls)
                    assertEquals(RemoteLocalIds.encode("old"), controller.state.value.activeThreadId)
                    controller.refreshThreads(); runCurrent()
                    assertFalse((controller.state.value.threads as Loadable.Ready).value.any { it.id.value == RemoteLocalIds.encode("new") })
                }
                "远端删除" -> {
                    directoryEvents.emit(VerifiedConversationEvent.ConversationsChanged("remote-delete", 1,
                        ConversationId(RemoteLocalIds.encode("new")), deleted = true))
                    runCurrent()
                    assertEquals(RemoteLocalIds.encode("old"), controller.state.value.activeThreadId)
                    controller.refreshThreads(); runCurrent()
                    assertFalse((controller.state.value.threads as Loadable.Ready).value.any { it.id.value == RemoteLocalIds.encode("new") })
                }
                "连续创建" -> {
                    val receipt = CompletableDeferred<Unit>()
                    wire.createGate = receipt
                    controller.createThread(); runCurrent()
                    controller.openThread(RemoteLocalIds.encode("old")); runCurrent()
                    receipt.complete(Unit); runCurrent()
                    assertEquals("用户选择优先于迟到的创建回执", RemoteLocalIds.encode("old"), controller.state.value.activeThreadId)
                    val ids = (controller.state.value.threads as Loadable.Ready).value.map { it.id.value }
                    assertTrue(ids.containsAll(listOf(RemoteLocalIds.encode("new"), RemoteLocalIds.encode("new-2"))))
                    assertEquals(2, wire.calls.count { it.startsWith("create:") })
                }
            }
        } finally {
            controller.close()
            repository.close()
        }
    }

    private class CodexDirectory : RemoteRpcChannel {
        override val scope = RemoteRpcConnectionScope("https://remote.example", "account", "host", "client", "connection", 1)
        override val capabilities = setOf("codex.appserver.v1")
        override val events = MutableSharedFlow<RemoteRpcEvent>()
        val calls = mutableListOf<String>()
        val published = linkedSetOf("old")
        var createGate: CompletableDeferred<Unit>? = null
        private var creations = 0
        override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue = when (method) {
            "codex.app.stream.open" -> objectValue().also { calls += "follow:${JsonFields.string(params, "threadId")}" }
            "codex.app.stream.close", "codex.app.transfer.open", "codex.app.transfer.chunk", "codex.app.transfer.close" -> objectValue()
            "codex.app.transfer.commit" -> objectValue("kind" to "inline", "response" to objectValue(
                "cursor" to 0, "records" to emptyList<Any>(), "hasMore" to false))
            "codex.app.call" -> when (JsonFields.string(params, "method")) {
                // 固定上游 Codex 会话可先创建且可用，尚未持久化时 thread/list 仍只有旧目录。
                "thread/list" -> objectValue("data" to published.map(::thread)).also { calls += "list" }
                "thread/start" -> {
                    val id = if (++creations == 1) "new" else "new-$creations"
                    calls += "create:$id"
                    createGate?.await()
                    objectValue("thread" to thread(id))
                }
                else -> error("夹具未声明的 Codex 调用")
            }
            else -> error("夹具未声明的 RPC 调用")
        }
        override suspend fun close() = Unit
        private fun thread(id: String) = objectValue("id" to id, "name" to if (id == "new") "新会话" else "旧会话",
            "cwd" to "/work", "createdAt" to 1, "updatedAt" to 1)
        private fun objectValue(vararg fields: Pair<String, Any?>) = Json.of(mapOf(*fields)) as JsonValue.JObject
    }
}
