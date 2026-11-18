package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** 故障发生在真实公开 call 封装之后：服务端执行成功，客户端收不到结果。 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RemoteUnknownWireRecoveryTest {
    private class Channel : RemoteRpcChannel {
        override val scope = RemoteRpcConnectionScope("https://remote.example", "account", "host", "client", "connection", 1)
        override val capabilities = setOf("codex.appserver.v1", "agent.acp.v1", "agent.acp.hermes.v1")
        override val events = MutableSharedFlow<RemoteRpcEvent>()
        var executions = 0
        val prompts = mutableListOf<JsonValue>()
        override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue {
            if (method.endsWith(".stream.open") || method.endsWith(".stream.close")) return obj()
            val nativeMethod = params.text("method")
            if (nativeMethod == "thread/resume") return obj()
            if (nativeMethod == "turn/start" || nativeMethod == "session/prompt") {
                prompts += params
                executions++
                throw RemoteRpcException("RPC_TIMEOUT", RemoteDeliveryOutcome.UNKNOWN)
            }
            error("意外公开调用：$method/$nativeMethod")
        }
        override suspend fun close() = Unit
    }
    @Test fun `Codex与ACP真实wire响应丢失不能用投影缺失判断未执行`() = runTest {
        for (backend in listOf("codex", "hermes")) {
            val channel = Channel()
            val carrier = if (backend == "codex") CodexSessionCarrier(channel) else AcpSessionCarrier(channel, backend)
            val endpoint = EndpointKey(RemoteServiceOrigin.fromCanonicalOrigin(channel.scope.origin), "account", "host", BackendId(backend), "workspace")
            val workspace = RemoteWorkspace(AgentEndpoint(endpoint, "工作区"), "/workspace")
            val local = RemoteLocalIds.encode("native-session")
            val original = ClientMessageId("original-request")
            val first = RemoteConversationRepository(carrier, workspace, backgroundScope, { local })
            first.submitMessage(local, OutgoingMessage(original, "执行一次")); runCurrent()
            assertEquals(1, channel.executions)
            assertEquals(GenerationState.OUTCOME_UNKNOWN, first.generationState.value)
            try { first.queryMessage(local, original); fail("不能把未知当未执行") } catch (_: java.io.IOException) { }
            first.close()
            val reopened = RemoteConversationRepository(carrier, workspace, backgroundScope, { local })
            try { reopened.queryMessage(local, original); fail("重连空投影也不是权威否定") } catch (_: java.io.IOException) { }
            assertEquals(1, channel.executions)
            // 当前公开 Codex/ACP prompt 均没有客户端请求身份字段，不能伪造其幂等能力。
            assertNull(channel.prompts.single().at("params")?.text("clientRequestId"))
            reopened.close()
        }
    }
}
