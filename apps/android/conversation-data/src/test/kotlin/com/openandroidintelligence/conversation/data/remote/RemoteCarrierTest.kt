package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcChannel
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcConnectionScope
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcEvent
import com.openandroidintelligence.gateway.schema.JsonValue
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteCarrierTest {
    private class Channel : RemoteRpcChannel {
        override val scope = RemoteRpcConnectionScope("https://remote.example", "account", "host", "client", "connection", 1)
        override val capabilities = setOf("harness.remote.v3", "codex.appserver.v1", "agent.acp.v1", "agent.acp.hermes.v1")
        override val events = MutableSharedFlow<RemoteRpcEvent>()
        val calls = mutableListOf<Pair<String, JsonValue.JObject>>()
        var result: suspend (String, JsonValue.JObject) -> JsonValue = { _, _ -> obj() }
        override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue {
            calls += method to params
            return result(method, params)
        }
        override suspend fun close() = Unit
    }

    @Test fun `ACP保持后端和原生会话身份且不借用Harness调用`() = runTest {
        val channel = Channel()
        val carrier = AcpSessionCarrier(channel, "hermes")
        channel.result = { _, request -> when (request.text("method")) {
            "dsh/workspaceList" -> JsonValue.JArray(listOf(obj("path" to "/work", "title" to "工作区")))
            "dsh/sessionList" -> obj("items" to listOf(obj("conversationId" to "hermes:真实会话", "title" to "会话")))
            else -> obj("accepted" to true)
        } }
        val workspace = carrier.workspaces().single()
        assertEquals("hermes", workspace.endpoint.key.backendId.value)
        assertEquals("hermes:真实会话", carrier.sessions(workspace, PageRequest()).items.single().id)
        carrier.prompt("hermes:真实会话", "request", listOf(obj("type" to "text", "text" to "消息")))
        assertTrue(channel.calls.all { it.first == "agent.acp.call" })
        assertEquals("hermes", channel.calls.last().second.at("params")?.text("backend"))
    }

    @Test fun `打开流前注册事件并在首帧后关闭同一通道的订阅`() = runTest {
        val channel = Channel()
        channel.result = { method, request ->
            if (method == "codex.app.stream.open") channel.events.emit(RemoteRpcEvent("codex.app.frame",
                obj("streamId" to request.requiredText("streamId"), "frame" to obj("method" to "turn/started"))))
            obj()
        }
        val read = async { CodexSessionCarrier(channel).follow("thread:原始").first { it.text("method") != null } }
        runCurrent()
        assertEquals("turn/started", read.await().text("method"))
        assertEquals(listOf("codex.app.stream.open", "codex.app.stream.close"), channel.calls.map { it.first })
        assertEquals("thread:原始", channel.calls.first().second.text("threadId"))
    }

    @Test fun `历史读取失败不会转换为空历史`() = runTest {
        val channel = Channel()
        channel.result = { _, _ -> throw RemoteSessionException("HOST_BUSY") }
        try {
            AcpSessionCarrier(channel, "hermes").history("same", PageRequest())
            fail("应保留宿主错误")
        } catch (failure: RemoteSessionException) { assertEquals("HOST_BUSY", failure.code) }
    }

    @Test fun `transfer校验块顺序与规范Base64并最终关闭`() = runTest {
        val channel = Channel()
        var id = ""
        channel.result = { method, request -> when {
            method.endsWith("transfer.open") -> { id = request.requiredText("transferId"); obj() }
            method.endsWith("transfer.commit") -> obj("kind" to "chunked", "transferId" to id, "totalBytes" to 2, "totalChunks" to 1)
            method.endsWith("transfer.read") -> obj("transferId" to id, "index" to 0, "data" to Base64.getEncoder().encodeToString("{}".toByteArray()))
            else -> obj()
        } }
        val result = RemoteCarrierWire(channel, "agent.acp").call(obj("method" to "dsh/sessionHistory", "params" to obj()), transfer = true)
        assertEquals(obj(), result)
        assertEquals("agent.acp.transfer.close", channel.calls.last().first)
        channel.result = { method, _ -> when {
            method.endsWith("transfer.commit") -> obj("kind" to "chunked", "transferId" to id, "totalBytes" to 2, "totalChunks" to 1)
            method.endsWith("transfer.read") -> obj("transferId" to id, "index" to 1, "data" to "e30=")
            else -> obj()
        } }
        try { RemoteCarrierWire(channel, "agent.acp").readTransfer(obj("kind" to "chunked", "transferId" to id, "totalBytes" to 2, "totalChunks" to 1)); fail("应拒绝错序") }
        catch (failure: RemoteSessionException) { assertEquals("REMOTE_INVALID_TRANSFER", failure.code) }
    }
}
