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
class RemoteGenerationCancellationTest {
    private val nativeSession = "thread:原会话"
    private val local = RemoteLocalIds.encode(nativeSession)
    private class Channel : RemoteRpcChannel {
        override val scope = RemoteRpcConnectionScope("https://remote.example", "account", "host", "client", "connection", 1)
        override val capabilities = setOf("codex.appserver.v1", "agent.acp.v1", "agent.acp.hermes.v1", "harness.remote.v3")
        override val events = MutableSharedFlow<RemoteRpcEvent>()
        val calls = mutableListOf<Pair<String, JsonValue.JObject>>()
        var prompt: suspend () -> JsonValue = { obj("turn" to obj("id" to "G1")) }
        var streamId = ""
        val streamIds = mutableMapOf<String, String>()
        override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue {
            calls += method to params
            if (method.endsWith(".stream.open")) {
                streamIds[method.removeSuffix(".stream.open")] = params.requiredText("streamId")
                if (method == "codex.app.stream.open") streamId = params.requiredText("streamId")
            }
            return if (params.text("method") == "turn/start") prompt() else obj()
        }
        override suspend fun close() = Unit
        fun interrupts() = calls.filter { it.second.text("method") == "turn/interrupt" }
    }
    private fun TestScope.repository(channel: Channel, backend: String = "codex", active: () -> String? = { local }): RemoteConversationRepository {
        val carrier = when (backend) {
            "harness" -> HarnessSessionCarrier(channel)
            "hermes" -> AcpSessionCarrier(channel, backend)
            else -> CodexSessionCarrier(channel)
        }
        val key = EndpointKey(RemoteServiceOrigin.fromCanonicalOrigin(channel.scope.origin), "account", "host", BackendId(backend), "workspace")
        return RemoteConversationRepository(carrier, RemoteWorkspace(AgentEndpoint(key, "工作区"), "/work"), backgroundScope, active)
    }
    private suspend fun TestScope.frame(channel: Channel, method: String, id: String, status: String? = null) {
        channel.events.emit(RemoteRpcEvent("codex.app.frame", obj("streamId" to channel.streamId,
            "frame" to obj("method" to method, "params" to obj("threadId" to nativeSession, "turn" to obj("id" to id, "status" to status))))))
        runCurrent()
    }
    private suspend fun stale(block: suspend () -> Unit) {
        try { block(); fail("旧轮次必须在发出远端调用前拒绝") }
        catch (failure: RemoteSessionException) { assertEquals("STALE_GENERATION", failure.code) }
    }

    @Test fun `真实仓储读取活动会话也在发布锁内且迟到旧会话不能覆盖新选择`() = runTest {
        val active = java.util.concurrent.atomic.AtomicReference(local)
        val read = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val attempted = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val repo = repository(Channel(), active = {
            val id = active.get()
            if (calls.incrementAndGet() == 1) {
                read.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            id
        })
        val first = kotlin.concurrent.thread {
            try { repo.activeConversationChanged() } catch (cause: Throwable) { failure.set(cause) }
        }
        assertTrue(read.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val next = RemoteLocalIds.encode("thread:下一会话")
        active.set(next)
        val second = kotlin.concurrent.thread {
            try { attempted.countDown(); repo.activeConversationChanged() } catch (cause: Throwable) { failure.set(cause) }
        }
        try {
            assertTrue(attempted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            awaitPublicationBlocked(second)
            assertEquals("活动会话读取本身也必须串行", 1, calls.get())
        } finally { release.countDown(); first.join(5_000); second.join(5_000) }
        assertFalse(first.isAlive); assertFalse(second.isAlive)
        failure.get()?.let { throw it }
        assertEquals(next, repo.generationSnapshot.value.conversationId)
        repo.close()
        assertEquals(GenerationSnapshot(null, GenerationState.IDLE), repo.generationSnapshot.value)
        repo.activeConversationChanged()
        assertNull(repo.generationSnapshot.value.conversationId)
    }

    @Test fun `捕获G1后延迟启动的取消不能中断已经开始的G2`() = runTest {
        val channel = Channel()
        val repo = repository(channel)
        repo.submitMessage(local, OutgoingMessage(ClientMessageId("first"), "一")); runCurrent()
        val captured = repo.generationId.value!!
        val dispatch = CompletableDeferred<Unit>()
        val cancel = async { dispatch.await(); stale { repo.cancelGeneration(local, captured, "cancel-first") } }
        frame(channel, "turn/completed", "G1", "completed")
        channel.prompt = { obj("turn" to obj("id" to "G2")) }
        repo.submitMessage(local, OutgoingMessage(ClientMessageId("second"), "二")); runCurrent()
        dispatch.complete(Unit); cancel.await()
        assertTrue(channel.interrupts().isEmpty())
        assertEquals("G2", repo.generationId.value)
        assertEquals(GenerationState.RUNNING, repo.generationState.value)
        repo.cancelGeneration(local, "G2", "cancel-second")
        val wire = channel.interrupts().single().second.at("params")!!
        assertEquals(nativeSession, wire.text("threadId"))
        assertEquals("G2", wire.text("turnId"))
        repo.close()
    }

    @Test fun `提交临时身份升级后仍只向Codex发送同轮真实turnId`() = runTest {
        val channel = Channel()
        val repo = repository(channel)
        val accepted = repo.submitMessage(local, OutgoingMessage(ClientMessageId("first"), "一"))
        runCurrent()
        repo.cancelGeneration(local, accepted.generationId!!, "cancel")
        val wire = channel.interrupts().single().second.at("params")!!
        assertEquals(nativeSession, wire.text("threadId"))
        assertEquals("G1", wire.text("turnId"))
        repo.close()
    }

    @Test fun `等待受理期间的取消等真实身份并在发送前再次核对`() = runTest {
        val channel = Channel()
        val reply = CompletableDeferred<JsonValue>()
        channel.prompt = { reply.await() }
        val repo = repository(channel)
        val accepted = repo.submitMessage(local, OutgoingMessage(ClientMessageId("first"), "一")); runCurrent()
        val cancel = async { repo.cancelGeneration(local, accepted.generationId!!, "cancel") }
        runCurrent()
        assertFalse(cancel.isCompleted)
        assertTrue(channel.interrupts().isEmpty())
        frame(channel, "turn/started", "G1")
        assertFalse(cancel.isCompleted)
        reply.complete(obj("turn" to obj("id" to "G1"))); runCurrent()
        cancel.await()
        assertEquals("G1", channel.interrupts().single().second.at("params")!!.text("turnId"))
        repo.close()
    }

    @Test fun `真实身份尚未回执时新轮开始也不能把旧临时身份绑定到新轮`() = runTest {
        val channel = Channel()
        val reply = CompletableDeferred<JsonValue>()
        channel.prompt = { reply.await() }
        val repo = repository(channel)
        val accepted = repo.submitMessage(local, OutgoingMessage(ClientMessageId("first"), "一")); runCurrent()
        frame(channel, "turn/started", "G1")
        frame(channel, "turn/completed", "G1", "completed")
        frame(channel, "turn/started", "G2")
        val cancel = async { stale { repo.cancelGeneration(local, accepted.generationId!!, "cancel-old-alias") } }; runCurrent()
        assertTrue(channel.interrupts().isEmpty())
        assertTrue(cancel.isCompleted)
        reply.complete(obj("turn" to obj("id" to "G1"))); runCurrent()
        cancel.await()
        assertEquals("G2", repo.generationId.value)
        repo.close()
    }

    @Test fun `终态和空闲会话取消均不发送远端中断`() = runTest {
        val channel = Channel()
        val repo = repository(channel)
        stale { repo.cancelGeneration(local, "unknown", "cancel-idle") }
        val accepted = repo.submitMessage(local, OutgoingMessage(ClientMessageId("first"), "一")); runCurrent()
        frame(channel, "turn/completed", "G1", "completed")
        stale { repo.cancelGeneration(local, accepted.generationId!!, "cancel-terminal") }
        stale { repo.cancelGeneration(local, "G1", "cancel-native-terminal") }
        assertTrue(channel.interrupts().isEmpty())
        repo.close()
    }

    @Test fun `受理结果未知会结束身份等待且不发送临时turnId`() = runTest {
        val channel = Channel()
        val reply = CompletableDeferred<JsonValue>()
        channel.prompt = { reply.await() }
        val repo = repository(channel)
        val accepted = repo.submitMessage(local, OutgoingMessage(ClientMessageId("first"), "一")); runCurrent()
        val cancel = async { repo.cancelGeneration(local, accepted.generationId!!, "cancel") }; runCurrent()
        reply.completeExceptionally(RemoteRpcException("RPC_TIMEOUT", RemoteDeliveryOutcome.UNKNOWN)); runCurrent()
        assertEquals(CancelGenerationOutcome.OUTCOME_UNKNOWN, cancel.await().outcome)
        assertTrue(channel.interrupts().isEmpty())
        repo.close()
    }

    @Test fun `真实终态先于受理回执会立即结束停止等待且不发送中断`() = runTest {
        val channel = Channel()
        val reply = CompletableDeferred<JsonValue>()
        channel.prompt = { reply.await() }
        val repo = repository(channel)
        val accepted = repo.submitMessage(local, OutgoingMessage(ClientMessageId("first"), "一")); runCurrent()
        val cancel = async { stale { repo.cancelGeneration(local, accepted.generationId!!, "cancel") } }; runCurrent()
        frame(channel, "turn/started", "G1")
        frame(channel, "turn/completed", "G1", "completed")
        cancel.await()
        assertTrue(channel.interrupts().isEmpty())
        assertEquals(GenerationState.COMPLETED, repo.generationState.value)
        reply.complete(obj("turn" to obj("id" to "G1"))); runCurrent()
        assertEquals(GenerationState.COMPLETED, repo.generationState.value)
        repo.close()
    }

    @Test fun `旧取消不能误停自动出队的新轮且Steering保留当前真实身份`() = runTest {
        val channel = Channel()
        var count = 0
        channel.prompt = { obj("turn" to obj("id" to if (++count == 1) "G1" else "G2")) }
        val repo = repository(channel)
        val first = repo.submitMessage(local, OutgoingMessage(ClientMessageId("first"), "一")); runCurrent()
        repo.submitMessage(local, OutgoingMessage(ClientMessageId("queued"), "二", delivery = MessageDeliveryMode.QUEUE)); runCurrent()
        assertEquals(1, count)
        frame(channel, "turn/completed", "G1", "completed")
        assertEquals(2, count)
        assertEquals("G2", repo.generationId.value)
        stale { repo.cancelGeneration(local, first.generationId!!, "cancel-first") }
        assertTrue(channel.interrupts().isEmpty())
        repo.steerMessage(local, OutgoingMessage(ClientMessageId("steer"), "补充", delivery = MessageDeliveryMode.STEER))
        val steer = channel.calls.single { it.second.text("method") == "turn/steer" }.second.at("params")!!
        assertEquals("G2", steer.text("expectedTurnId"))
        repo.cancelGeneration(local, "G2", "cancel-second")
        assertEquals("G2", channel.interrupts().single().second.at("params")!!.text("turnId"))
        repo.close()
    }

    @Test fun `旧终态不能唤醒新提交的身份等待或覆盖其停止操作`() = runTest {
        val channel = Channel()
        val firstReply = CompletableDeferred<JsonValue>()
        val secondReply = CompletableDeferred<JsonValue>()
        var count = 0
        channel.prompt = { if (++count == 1) firstReply.await() else secondReply.await() }
        val repo = repository(channel)
        repo.submitMessage(local, OutgoingMessage(ClientMessageId("first"), "一")); runCurrent()
        frame(channel, "turn/started", "G1")
        frame(channel, "turn/completed", "G1", "completed")
        val second = repo.submitMessage(local, OutgoingMessage(ClientMessageId("second"), "二")); runCurrent()
        frame(channel, "turn/started", "G2")
        val cancel = async { repo.cancelGeneration(local, second.generationId!!, "cancel-second") }; runCurrent()
        frame(channel, "turn/completed", "G1", "completed")
        assertFalse(cancel.isCompleted)
        assertTrue(channel.interrupts().isEmpty())
        secondReply.complete(obj("turn" to obj("id" to "G2"))); runCurrent()
        cancel.await()
        assertEquals("G2", channel.interrupts().single().second.at("params")!!.text("turnId"))
        firstReply.complete(obj("turn" to obj("id" to "G1"))); runCurrent()
        assertEquals("G2", repo.generationId.value)
        assertEquals(GenerationState.RUNNING, repo.generationState.value)
        repo.close()
    }

    @Test fun `仅支持整会话取消的载体也必须先拒绝旧轮次`() = runTest {
        for (backend in listOf("harness", "hermes")) {
            val channel = Channel()
            val repo = repository(channel, backend)
            val first = repo.submitMessage(local, OutgoingMessage(ClientMessageId("first"), "一")); runCurrent()
            val prefix = if (backend == "harness") "harness.remote" else "agent.acp"
            val terminal = if (backend == "harness") obj("type" to "event", "event" to obj("seq" to 0,
                "type" to "turn/end", "data" to obj("reason" to obj("kind" to "completed"))))
                else obj("method" to "session/update", "params" to obj("sessionId" to nativeSession,
                    "update" to obj("sessionUpdate" to "prompt_completed", "stopReason" to "end_turn")))
            channel.events.emit(RemoteRpcEvent("$prefix.frame", obj("streamId" to channel.streamIds.getValue(prefix),
                (if (backend == "harness") "value" else "frame") to terminal)))
            runCurrent()
            val second = repo.submitMessage(local, OutgoingMessage(ClientMessageId("second"), "二")); runCurrent()
            stale { repo.cancelGeneration(local, first.generationId!!, "cancel-first") }
            assertFalse(channel.calls.any { it.second.text("method") == "session/cancel" || it.second.text("endpoint") == "session/cancel" })
            repo.cancelGeneration(local, second.generationId!!, "cancel-second")
            val cancel = channel.calls.single { it.second.text("method") == "session/cancel" || it.second.text("endpoint") == "session/cancel" }
            assertEquals("$prefix.call", cancel.first)
            val params = if (backend == "harness") cancel.second.at("payload")!!.at("args")!!.at("request")!! else cancel.second.at("params")!!
            assertEquals(nativeSession, params.text("sessionId"))
            repo.close()
        }
    }

    @Test fun `关闭连接会结束真实轮次身份等待`() = runTest {
        val channel = Channel()
        channel.prompt = { awaitCancellation() }
        val repo = repository(channel)
        val accepted = repo.submitMessage(local, OutgoingMessage(ClientMessageId("first"), "一")); runCurrent()
        val cancel = async { runCatching { repo.cancelGeneration(local, accepted.generationId!!, "cancel") } }; runCurrent()
        repo.close(); runCurrent()
        assertTrue(cancel.isCompleted)
        assertTrue(channel.interrupts().isEmpty())
    }
}
