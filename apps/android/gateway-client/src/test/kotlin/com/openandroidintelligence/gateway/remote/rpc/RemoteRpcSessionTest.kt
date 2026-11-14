package com.openandroidintelligence.gateway.remote.rpc

import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteRpcSessionTest {
    private val identity = RemoteRpcConnectionScope("https://remote.example", "account", "host", "client", "connection", 1)
    private class Wire : RemoteMessageTransport {
        var receive: ((ByteArray) -> Unit)? = null
        var closed: ((String) -> Unit)? = null
        val writes = mutableListOf<ByteArray>()
        var afterWrite: ((ByteArray) -> Unit)? = null
        var closes = 0
        override suspend fun send(bytes: ByteArray) { writes += bytes; afterWrite?.invoke(bytes) }
        override fun onMessage(handler: (ByteArray) -> Unit): AutoCloseable { receive = handler; return AutoCloseable { receive = null } }
        override fun onClose(handler: (String) -> Unit): AutoCloseable { closed = handler; return AutoCloseable { closed = null } }
        override suspend fun close() { closes++ }
        fun respond(type: String, payload: JsonValue.JObject) { receive?.invoke(Json.canonical(obj("v" to 1, "id" to "response", "timestamp" to 1,
            "type" to type, "payload" to payload)).encodeToByteArray()) }
    }
    @Test fun 响应同步到达时等待已在发送前登记() = runTest {
        val wire = Wire(); val channel = RemoteRpcSession(identity, emptySet(), wire, backgroundScope)
        wire.afterWrite = { wire.respond("rpc.response", obj("requestId" to "request", "result" to "ok")) }
        val result = async { channel.invoke("session/prompt", obj(), "request", 1000) }
        runCurrent(); assertEquals(JsonValue.JString("ok"), result.await()); assertEquals(1, wire.writes.size)
        channel.close()
    }
    @Test fun 已发送断线保持未知且关闭后不能自动重发() = runTest {
        val wire = Wire(); val channel = RemoteRpcSession(identity, emptySet(), wire, backgroundScope)
        val result = async { runCatching { channel.invoke("session/prompt", obj(), "request", 1000) }.exceptionOrNull() }
        runCurrent(); assertEquals(1, wire.writes.size)
        wire.closed!!("TRANSPORT_CLOSED"); runCurrent()
        val failure = result.await() as RemoteRpcException
        assertEquals(RemoteDeliveryOutcome.UNKNOWN, failure.deliveryOutcome)
        val next = runCatching { channel.invoke("session/prompt", obj(), "next", 1000) }.exceptionOrNull() as RemoteRpcException
        assertEquals(RemoteDeliveryOutcome.NOT_SENT, next.deliveryOutcome); assertEquals(1, wire.writes.size)
        assertNull(wire.receive); assertTrue(wire.closes > 0)
    }
    @Test fun 明确拒绝与发送后超时分别返回拒绝和未知() = runTest {
        val wire = Wire(); val channel = RemoteRpcSession(identity, emptySet(), wire, backgroundScope)
        val rejected = async { runCatching { channel.invoke("unsupported", obj(), "reject", 1000) }.exceptionOrNull() }
        runCurrent(); wire.respond("rpc.error", obj("requestId" to "reject", "code" to "METHOD_NOT_FOUND", "message" to "secret")); runCurrent()
        val failure = rejected.await() as RemoteRpcException
        assertEquals("METHOD_NOT_FOUND", failure.code); assertEquals(RemoteDeliveryOutcome.REJECTED, failure.deliveryOutcome); assertFalse(failure.toString().contains("secret"))
        val timeout = async { runCatching { channel.invoke("session/prompt", obj(), "timeout", 1000) }.exceptionOrNull() }
        runCurrent(); advanceTimeBy(1001); runCurrent()
        assertEquals(RemoteDeliveryOutcome.UNKNOWN, (timeout.await() as RemoteRpcException).deliveryOutcome)
        assertEquals(2, wire.writes.size); channel.close()
    }
    @Test fun 关闭结束旧事件且迟到帧不能投给新连接() = runTest {
        val wire = Wire(); val old = RemoteRpcSession(identity, emptySet(), wire, backgroundScope)
        val observed = mutableListOf<RemoteRpcEvent>()
        val observer = backgroundScope.launch { runCatching { old.events.collect { observed += it } } }
        runCurrent(); wire.respond("event", obj("event" to "session.changed", "data" to "before", "seq" to 1)); runCurrent()
        assertEquals(1, observed.size); old.close(); runCurrent(); assertTrue(observer.isCompleted)
        wire.respond("event", obj("event" to "session.changed", "data" to "late", "seq" to 2)); runCurrent(); assertEquals(1, observed.size)
    }
}
