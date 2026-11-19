package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.ports.AgentCommand
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
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
        first.timeline(firstId, PageRequest())
        second.timeline(secondId, PageRequest())
        assertEquals(emptyList<AgentCommand>(), second.commandCatalog.get("second", "zh-CN", secondId).commands)

        channel.announce("session-1", "session-1", "single")
        channel.announce("session-1", "session-2", "wrong-session")
        runCurrent()
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

    @Test fun `未提供目录能力的载体诚实返回空且不探测写入方法`() = runTest {
        val channel = Channel()
        val workspace = workspace("codex-workspace", "codex")
        val local = RemoteLocalIds.encode("thread-id")
        val repo = RemoteConversationRepository(CodexSessionCarrier(channel), workspace, backgroundScope, { local })
        assertEquals(emptyList<AgentCommand>(), repo.commandCatalog.get("codex", "zh-CN", local).commands)
        assertEquals(emptyList<String>(), channel.calls)
        repo.close()
    }

    private fun workspace(name: String, backend: String = "hermes") = RemoteWorkspace(AgentEndpoint(EndpointKey(
        RemoteServiceOrigin.fromCanonicalOrigin("https://remote.example"), "account", "host", BackendId(backend), name,
    ), name, EndpointAvailability.AVAILABLE), "/$name")

    private class Channel : RemoteRpcChannel {
        override val scope = RemoteRpcConnectionScope("https://remote.example", "account", "host", "client", "connection", 1)
        override val capabilities = setOf("agent.acp.v1", "agent.acp.hermes.v1", "codex.appserver.v1")
        override val events = MutableSharedFlow<RemoteRpcEvent>()
        val calls = mutableListOf<String>()

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
                "agent.acp.transfer.commit" -> obj("kind" to "inline", "response" to obj("events" to emptyList<Any>()))
                else -> obj()
            }
        }

        override suspend fun close() = Unit
    }
}
