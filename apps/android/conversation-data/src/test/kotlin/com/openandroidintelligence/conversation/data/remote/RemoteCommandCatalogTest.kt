package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.ports.AgentCommand
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.remote.AgentEndpoint
import com.openandroidintelligence.conversation.remote.BackendId
import com.openandroidintelligence.conversation.remote.EndpointAvailability
import com.openandroidintelligence.conversation.remote.EndpointKey
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.conversation.remote.RemoteServiceOrigin
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcChannel
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcConnectionScope
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcEvent
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteCommandCatalogTest {
    @Test fun `解析带版本的目录和真实参数提示并拒绝缺失数组的响应`() {
        val decoded = RemoteCommandCatalog.decode(obj("catalogVersion" to "rev-3", "commands" to listOf(
            obj("invocation" to "/find_entry", "description" to "查找", "argumentHint" to "<名称>"),
            obj("command" to "/scan", "description" to "扫描"),
            obj("name" to "scan"),
            obj("name" to "/不合法 名称"),
        )))
        assertEquals("rev-3", decoded.version.value)
        assertEquals(listOf(AgentCommand("/find_entry", "查找", "<名称>"), AgentCommand("/scan", "扫描")), decoded.commands)
        assertEquals(emptyList<AgentCommand>(), RemoteCommandCatalog.decode(obj("items" to emptyList<Any>())).commands)
        val failure = assertThrows(RemoteSessionException::class.java) { RemoteCommandCatalog.decode(obj()) }
        assertEquals("REMOTE_INVALID_COMMAND_CATALOG", failure.code)
    }

    @Test fun `ACP目录公告按原生会话进入各自仓储并接受显式空列表`() = runTest {
        val channel = Channel()
        val firstId = RemoteLocalIds.encode("session-1")
        val secondId = RemoteLocalIds.encode("session-2")
        val first = RemoteConversationRepository(AcpSessionCarrier(channel, "hermes"), workspace("first"), backgroundScope, { firstId })
        val second = RemoteConversationRepository(AcpSessionCarrier(channel, "hermes"), workspace("second"), backgroundScope, { secondId })
        channel.sessionsByPath["/first"] = listOf("session-1")
        channel.sessionsByPath["/second"] = listOf("session-2")
        first.listConversations(conversationScope, PageRequest())
        second.listConversations(conversationScope, PageRequest())
        first.timeline(firstId, PageRequest())
        second.timeline(secondId, PageRequest())
        assertEquals(emptyList<AgentCommand>(), second.commandCatalog.get("second", "zh-CN", secondId).commands)

        channel.announce("session-1", "session-1", "single")
        channel.announce("session-1", "session-2", "wrong-session")
        channel.announce("unknown", "unknown", "outside-workspace")
        runCurrent()
        assertEquals(emptyList<AgentCommand>(), first.commandCatalog.get("first", "zh-CN", RemoteLocalIds.encode("unknown")).commands)
        assertEquals(emptyList<AgentCommand>(), second.commandCatalog.get("second", "zh-CN", firstId).commands)
        assertEquals(listOf(AgentCommand("/single", "说明", "<值>")), first.commandCatalog.get("first", "zh-CN", firstId).commands)
        assertEquals(emptyList<AgentCommand>(), second.commandCatalog.get("second", "zh-CN", secondId).commands)

        channel.announce("session-2", "session-2", "second")
        channel.announce("session-1", "session-1")
        runCurrent()
        assertEquals(emptyList<AgentCommand>(), first.commandCatalog.get("first", "zh-CN", firstId).commands)
        assertEquals("/second", second.commandCatalog.get("second", "zh-CN", secondId).commands.single().command)
        first.close()
        second.close()
    }

    @Test fun `ACP创建响应前的唯一公告在两轮各自可见并由后到公告覆盖`() = runTest {
        val channel = Channel()
        val repo = RemoteConversationRepository(AcpSessionCarrier(channel, "hermes"), workspace("work"), backgroundScope, { null })
        repeat(2) { round ->
            val nativeId = "agent:session-$round"
            channel.onNew = { request ->
                assertEquals("/work", request.text("cwd"))
                assertEquals("hermes", request.text("backend"))
                channel.announce(nativeId, nativeId, "early-$round")
                obj("sessionId" to nativeId)
            }
            val beforeCreate = channel.calls.size
            val created = repo.createConversation(conversationScope, "new-$round").id.value
            assertEquals(RemoteLocalIds.encode(nativeId), created)
            val initial = repo.commandCatalog.get("work", "zh-CN", created)
            assertEquals("/early-$round", initial.commands.singleOrNull()?.command)
            assertEquals(initial, repo.commandCatalog.observe("work", created).first())
            assertEquals(listOf("agent.acp.call"), channel.calls.drop(beforeCreate))
            assertEquals(round + 1, channel.events.subscriptionCount.value)

            repo.timeline(created, PageRequest())
            assertEquals("/early-$round", repo.commandCatalog.get("work", "zh-CN", created).commands.singleOrNull()?.command)
            if (round == 0) channel.announce(nativeId, nativeId, "live-$round")
            else channel.announce(nativeId, nativeId)
            runCurrent()
            val expected = if (round == 0) listOf("/live-0") else emptyList()
            assertEquals(expected, repo.commandCatalog.get("work", "zh-CN", created).commands.map { it.command })
        }
        assertEquals("/live-0", repo.commandCatalog.get("work", "zh-CN", RemoteLocalIds.encode("agent:session-0")).commands.single().command)
        repo.close()
    }

    @Test fun `创建返回后且订阅前的公告可作为唯一目录或替换早期目录`() = runTest {
        val channel = Channel()
        val repo = RemoteConversationRepository(AcpSessionCarrier(channel, "hermes"), workspace("work"), backgroundScope, { null })
        repeat(2) { round ->
            val sessionId = "gap-$round"
            channel.onNew = {
                if (round == 1) channel.announce(sessionId, sessionId, "early")
                obj("sessionId" to sessionId)
            }
            val created = repo.createConversation(conversationScope, "new-$round").id.value
            assertEquals(if (round == 0) emptyList() else listOf("/early"),
                repo.commandCatalog.get("work", "zh-CN", created).commands.map { it.command })

            channel.announce(sessionId, sessionId, "between-$round")
            runCurrent()
            assertEquals("/between-$round", repo.commandCatalog.get("work", "zh-CN", created).commands.singleOrNull()?.command)
            assertEquals(List(round + 1) { "agent.acp.call" }, channel.calls)
        }
        repo.close()
    }

    @Test fun `并行创建只接纳当前连接上与各工作区创建结果一致的公告`() = runTest {
        val channel = Channel()
        val otherConnection = Channel(channel.scope.copy(connectionId = "other-connection", generation = 2))
        val carrier = AcpSessionCarrier(channel, "hermes")
        val first = RemoteConversationRepository(carrier, workspace("first"), backgroundScope, { null })
        val second = RemoteConversationRepository(carrier, workspace("second"), backgroundScope, { null })
        val firstResponse = CompletableDeferred<JsonValue>()
        val secondResponse = CompletableDeferred<JsonValue>()
        channel.onNew = { request ->
            when (request.text("cwd")) {
                "/first" -> firstResponse.await()
                "/second" -> secondResponse.await()
                else -> error("未知的测试工作区")
            }
        }
        val firstCreate = async { first.createConversation(conversationScope, "first-local") }
        val secondCreate = async { second.createConversation(conversationScope, "second-local") }
        runCurrent()
        val firstId = RemoteLocalIds.encode("first-id")
        val secondId = RemoteLocalIds.encode("second-id")
        channel.announce("first-id", "first-id", "first-catalog")
        channel.announce("second-id", "first-id", "wrong-stream")
        channel.announce("second-id", "second-id", "second-catalog")
        otherConnection.announce("second-id", "second-id", "wrong-peer")
        assertEquals(emptyList<AgentCommand>(), first.commandCatalog.get("first", "zh-CN", firstId).commands)
        firstResponse.complete(obj("sessionId" to "first-id"))
        secondResponse.complete(obj("sessionId" to "second-id"))
        assertEquals(firstId, firstCreate.await().id.value)
        assertEquals(secondId, secondCreate.await().id.value)
        assertEquals("/first-catalog", first.commandCatalog.get("first", "zh-CN", firstId).commands.singleOrNull()?.command)
        assertEquals("/second-catalog", second.commandCatalog.get("second", "zh-CN", secondId).commands.singleOrNull()?.command)
        assertEquals(emptyList<AgentCommand>(), first.commandCatalog.get("first", "zh-CN", secondId).commands)
        assertEquals(emptyList<AgentCommand>(), second.commandCatalog.get("second", "zh-CN", firstId).commands)
        assertEquals(listOf("agent.acp.call", "agent.acp.call"), channel.calls)
        assertEquals(2, channel.events.subscriptionCount.value)
        first.close()
        second.close()
        assertEquals(0, channel.events.subscriptionCount.value)
    }

    @Test fun `实时订阅先发布目录时迟到的创建结果保留实时目录`() = runTest {
        val channel = Channel()
        val repo = RemoteConversationRepository(AcpSessionCarrier(channel, "hermes"), workspace("work"), backgroundScope, { null })
        val sessionId = "session-race"
        val localId = RemoteLocalIds.encode(sessionId)
        val response = CompletableDeferred<JsonValue>()
        channel.onNew = {
            channel.announce(sessionId, sessionId, "early")
            response.await()
        }
        val creation = async { repo.createConversation(conversationScope, "new") }
        runCurrent()
        channel.sessionsByPath["/work"] = listOf(sessionId)
        repo.listConversations(conversationScope, PageRequest())
        channel.announce(sessionId, sessionId, "live")
        runCurrent()
        assertEquals("/live", repo.commandCatalog.get("work", "zh-CN", localId).commands.singleOrNull()?.command)
        response.complete(obj("sessionId" to sessionId))
        assertEquals(localId, creation.await().id.value)
        assertEquals("/live", repo.commandCatalog.get("work", "zh-CN", localId).commands.singleOrNull()?.command)
        repo.close()
    }

    @Test fun `创建失败后下次创建不继承未确认的目录`() = runTest {
        val channel = Channel()
        val repo = RemoteConversationRepository(AcpSessionCarrier(channel, "hermes"), workspace("work"), backgroundScope, { null })
        val sessionId = "session-retry"
        channel.onNew = {
            channel.announce(sessionId, sessionId, "unconfirmed")
            throw RemoteRpcException("CREATE_REJECTED")
        }
        val failure = runCatching { repo.createConversation(conversationScope, "failed") }.exceptionOrNull()
        assertEquals("CREATE_REJECTED", (failure as? RemoteRpcException)?.code)
        assertEquals(1, channel.events.subscriptionCount.value)
        channel.announce(sessionId, sessionId, "outside-create")

        channel.onNew = { obj("sessionId" to sessionId) }
        val created = repo.createConversation(conversationScope, "new").id.value
        assertEquals(RemoteLocalIds.encode(sessionId), created)
        assertEquals(emptyList<AgentCommand>(), repo.commandCatalog.get("work", "zh-CN", created).commands)
        assertEquals(listOf("agent.acp.call", "agent.acp.call"), channel.calls)
        repo.close()
    }

    @Test fun `未提供目录能力的载体诚实返回空且不探测写入方法`() = runTest {
        val channel = Channel()
        val workspace = workspace("codex-workspace", "codex")
        val local = RemoteLocalIds.encode("thread-id")
        val repo = RemoteConversationRepository(CodexSessionCarrier(channel), workspace, backgroundScope, { local })
        assertEquals(emptyList<AgentCommand>(), repo.commandCatalog.get("codex", "zh-CN", local).commands)
        assertEquals(emptyList<String>(), channel.calls)
        repo.close()
    }

    private val conversationScope = ConversationScope("profile", "gateway", "account", "install")

    private fun workspace(name: String, backend: String = "hermes") = RemoteWorkspace(AgentEndpoint(EndpointKey(
        RemoteServiceOrigin.fromCanonicalOrigin("https://remote.example"), "account", "host", BackendId(backend), name,
    ), name, EndpointAvailability.AVAILABLE), "/$name")

    private class Channel(
        override val scope: RemoteRpcConnectionScope = RemoteRpcConnectionScope("https://remote.example", "account", "host", "client", "connection", 1),
    ) : RemoteRpcChannel {
        override val capabilities = setOf("agent.acp.v1", "agent.acp.hermes.v1", "codex.appserver.v1")
        override val events = MutableSharedFlow<RemoteRpcEvent>(extraBufferCapacity = 16)
        val calls = mutableListOf<String>()
        val sessionsByPath = mutableMapOf<String, List<String>>()
        var onNew: suspend (JsonValue) -> JsonValue = { error("未配置会话创建响应") }

        suspend fun announce(streamSession: String, frameSession: String, vararg commands: String) {
            val update = obj("sessionUpdate" to "available_commands_update", "availableCommands" to commands.map {
                obj("name" to it, "description" to "说明", "input" to obj("hint" to "<值>"))
            })
            events.emit(RemoteRpcEvent("agent.acp.frame", obj("streamId" to "session:$streamSession", "frame" to obj(
                "method" to "session/update", "params" to obj("sessionId" to frameSession, "update" to update),
            ))))
        }

        override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue {
            calls += method
            return when (method) {
                "agent.acp.call" -> {
                    val args = params.at("params") ?: error("缺少 ACP 请求参数")
                    when (params.text("method")) {
                        "session/new" -> onNew(args)
                        "dsh/sessionList" -> obj("items" to sessionsByPath[args.text("path")].orEmpty().map {
                            obj("conversationId" to it)
                        })
                        else -> error("非预期的 ACP 方法")
                    }
                }
                "agent.acp.transfer.commit" -> obj("kind" to "inline", "response" to obj("events" to emptyList<Any>()))
                else -> obj()
            }
        }

        override suspend fun close() = Unit
    }
}
