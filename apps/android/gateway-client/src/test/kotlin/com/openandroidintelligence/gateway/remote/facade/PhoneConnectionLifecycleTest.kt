package com.openandroidintelligence.gateway.remote.facade

import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.encrypted.store.*
import javax.crypto.spec.SecretKeySpec
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.schema.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** 真实 RPC 终止贯穿手机观察和执行总线，不以单个清理方法的调用次数充当闭环。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneConnectionLifecycleTest {
    private class Ledger : ControlMetadataLedger {
        var rows = mutableMapOf<String, ControlRequestMetadata>()
        override fun <T> transaction(change: (MutableMap<String, ControlRequestMetadata>) -> T): T {
            val next = rows.toMutableMap(); val result = change(next); rows = next; return result
        }
    }
    private class Wire : RemoteMessageTransport {
        var receive: ((ByteArray) -> Unit)? = null
        var terminal: ((String) -> Unit)? = null
        val writes = mutableListOf<String>()
        override suspend fun send(bytes: ByteArray) { writes += bytes.decodeToString() }
        override fun onMessage(handler: (ByteArray) -> Unit) = AutoCloseable { receive = null }.also { receive = handler }
        override fun onClose(handler: (String) -> Unit) = AutoCloseable { terminal = null }.also { terminal = handler }
        override suspend fun close() { terminal?.invoke("TRANSPORT_CLOSED") }
        fun invoke(method: String, params: JsonValue.JObject, id: String) {
            receive!!(Json.canonical(Json.of(mapOf("v" to 1, "id" to id, "timestamp" to 1, "type" to "rpc.request",
                "payload" to mapOf("method" to method, "params" to params)))).encodeToByteArray())
        }
    }
    private val principal = RemoteControlPrincipal(ControlOrigin.fromCanonical("https://remote.example"), "account", "client", "host",
        "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64))
    private fun binding(generation: Long) = ControlAccessBinding(principal, 1, "same-connection", generation)
    private fun rpc(generation: Long) = RemoteRpcConnectionScope("https://remote.example", "account", "host", "client", "same-connection", generation)
    private fun request(id: String) = ControlRequestParser.parse(Json.canonical(Json.of(mapOf("request_id" to id,
        "op" to "ui.dump_tree", "args" to emptyMap<String, Any>()))))
    private fun stream(id: String) = Json.of(mapOf("streamId" to id, "endpoint" to "session/control", "payload" to emptyMap<String, Any>())) as JsonValue.JObject

    @Test fun 真实断线清理等待审批和排队请求并保留已执行未知事实() = runTest {
        val ledger = Ledger(); val active = mutableSetOf<ControlAccessBinding>()
        val executed = mutableListOf<String>(); val cancelled = mutableListOf<String>()
        val executor = object : PhoneControlExecutor {
            override fun requireCurrentAccess(access: ControlAccessBinding) { check(access in active) }
            override fun capabilities(access: ControlAccessBinding) = ControlValue.Object(emptyMap())
            override fun cancel(scope: ControlRequestScope) { cancelled += scope.key.requestId }
            override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
                executed += context.scope.key.requestId
                if (context.scope.key.requestId == "already-executed-0001") context.beforeDispatch()
                else context.awaitingConfirmation(true)
                awaitCancellation()
            }
        }
        val bus = PhoneControlBus(backgroundScope, ledger, executor, phoneFacadeTestStrings)
        val facade = PhoneRemoteRpcHostHandler(backgroundScope, PhoneVirtualHostRouter(bus)) { connection ->
            binding(connection.generation).also { executor.requireCurrentAccess(it) }
        }
        val host = object : RemoteRpcHostHandler {
            override fun events(scope: RemoteRpcConnectionScope) = facade.events(scope)
            override suspend fun invoke(scope: RemoteRpcConnectionScope, method: String, params: JsonValue.JObject) = facade.invoke(scope, method, params)
            override fun connectionClosed(scope: RemoteRpcConnectionScope) {
                facade.disconnect(scope); bus.connectionClosed(binding(scope.generation))
            }
        }
        val access = binding(1); active += access
        val wire = Wire(); val session = RemoteRpcSession(rpc(1), emptySet(), wire, backgroundScope, host) { active -= access }
        wire.invoke("harness.remote.stream.open", stream("observer"), "open"); runCurrent()
        assertEquals(1, (bus.changes as StateFlow<Long> as MutableStateFlow<Long>).subscriptionCount.value)
        val waiting = bus.create(access); val running = bus.create(access)
        bus.submit(access, waiting, request("waiting-approval-0001")); bus.submit(access, waiting, request("queued-request-00001"))
        bus.submit(access, running, request("already-executed-0001")); runCurrent()
        wire.terminal!!("TRANSPORT_CLOSED"); runCurrent()
        assertEquals(0, (bus.changes as MutableStateFlow<Long>).subscriptionCount.value)
        assertFalse(executed.contains("queued-request-00001"))
        assertEquals(setOf("waiting-approval-0001", "queued-request-00001", "already-executed-0001"), cancelled.toSet())
        val next = binding(2); active += next
        val waitingReceipt = Json.canonical(bus.receipt(next, waiting, "waiting-approval-0001"))
        val queuedReceipt = Json.canonical(bus.receipt(next, waiting, "queued-request-00001"))
        val unknownReceipt = Json.canonical(bus.receipt(next, running, "already-executed-0001"))
        assertTrue(waitingReceipt.contains("\"state\":\"cancelled\"")); assertTrue(queuedReceipt.contains("\"status\":\"not_started\""))
        assertTrue(unknownReceipt.contains("\"state\":\"outcome_unknown\"")); assertTrue(unknownReceipt.contains("\"status\":\"unknown\""))
        assertTrue(unknownReceipt.contains("\"resultAvailable\":false"))
        bus.submit(next, running, request("already-executed-0001")); runCurrent()
        assertEquals(1, executed.count { it == "already-executed-0001" })
        session.close(); facade.close()
    }

    @Test fun 加密账本写失败仍清旧连接观察和请求且新连接继续工作() = runTest {
        val storage = InMemoryOutboxPersistence()
        var failuresRemaining = 0
        val persistence = object : EncryptedOutboxPersistence {
            override fun read() = storage.read()
            override fun clear() = storage.clear()
            override fun write(ciphertext: ByteArray) {
                if (failuresRemaining > 0) { failuresRemaining--; throw java.io.IOException("模拟持久化不可写") }
                storage.write(ciphertext)
            }
        }
        val key = SecretKeySpec(ByteArray(32) { 23 }, "AES")
        val ledger = ControlMetadataStore(AesGcmEncryptedBlobStore(persistence, key))
        val active = mutableSetOf(binding(1), binding(2)); val cancelled = mutableListOf<String>()
        val executor = object : PhoneControlExecutor {
            override fun requireCurrentAccess(access: ControlAccessBinding) { check(access in active) }
            override fun capabilities(access: ControlAccessBinding) = ControlValue.Object(emptyMap())
            override fun cancel(scope: ControlRequestScope) { cancelled += scope.key.requestId }
            override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
                if (context.scope.key.requestId == "disk-running-000001") context.beforeDispatch()
                else context.awaitingConfirmation(true)
                awaitCancellation()
            }
        }
        val bus = PhoneControlBus(backgroundScope, ledger, executor, phoneFacadeTestStrings)
        val facade = PhoneRemoteRpcHostHandler(backgroundScope, PhoneVirtualHostRouter(bus)) { connection ->
            binding(connection.generation).also { executor.requireCurrentAccess(it) }
        }
        val host = object : RemoteRpcHostHandler {
            override fun events(scope: RemoteRpcConnectionScope) = facade.events(scope)
            override suspend fun invoke(scope: RemoteRpcConnectionScope, method: String, params: JsonValue.JObject) = facade.invoke(scope, method, params)
            override fun connectionClosed(scope: RemoteRpcConnectionScope) {
                closeRemoteConnectionResources({ facade.disconnect(scope) }, { bus.connectionClosed(binding(scope.generation)) })
            }
        }
        val oldWire = Wire(); val newWire = Wire()
        val old = RemoteRpcSession(rpc(1), emptySet(), oldWire, backgroundScope, host) { active -= binding(1) }
        val current = RemoteRpcSession(rpc(2), emptySet(), newWire, backgroundScope, host) { active -= binding(2) }
        oldWire.invoke("harness.remote.stream.open", stream("old-observer"), "old-open")
        newWire.invoke("harness.remote.stream.open", stream("new-observer"), "new-open"); runCurrent()
        assertEquals(2, (bus.changes as MutableStateFlow<Long>).subscriptionCount.value)
        val waiting = bus.create(binding(1)); val running = bus.create(binding(1))
        bus.submit(binding(1), waiting, request("disk-waiting-000001"))
        bus.submit(binding(1), waiting, request("disk-queued-0000001"))
        bus.submit(binding(1), running, request("disk-running-000001")); runCurrent()
        failuresRemaining = 1
        val failure = runCatching { oldWire.terminal!!("TRANSPORT_CLOSED") }.exceptionOrNull()
        assertEquals("CONNECTION_CLEANUP_FAILED", (failure as RemoteRpcException).code)
        runCurrent()
        assertEquals(1, (bus.changes as MutableStateFlow<Long>).subscriptionCount.value)
        assertNull(oldWire.receive); assertNull(oldWire.terminal)
        assertEquals(setOf("disk-waiting-000001", "disk-queued-0000001", "disk-running-000001"), cancelled.toSet())
        host.connectionClosed(rpc(1)); runCurrent()
        val before = newWire.writes.size; bus.create(binding(2)); runCurrent()
        assertTrue("另一代际观察仍收到真实总线事件", newWire.writes.size > before)
        val unknown = Json.canonical(bus.receipt(binding(2), running, "disk-running-000001"))
        assertTrue(unknown.contains("outcome_unknown")); assertTrue(unknown.contains("resultAvailable\":false"))
        val restored = ControlMetadataStore(AesGcmEncryptedBlobStore(persistence, key))
        assertTrue(restored.transaction { rows -> rows.values.filter { it.requestId.startsWith("disk-") }.all { it.state.terminal } })
        old.close(); current.close(); runCurrent(); facade.close()
        assertEquals(0, (bus.changes as MutableStateFlow<Long>).subscriptionCount.value)
    }

    @Test fun 重复连接断开清除真实观察且迟到旧代际关闭不影响新连接() = runTest {
        val active = mutableSetOf<ControlAccessBinding>()
        val executor = object : PhoneControlExecutor {
            override fun requireCurrentAccess(access: ControlAccessBinding) { check(access in active) }
            override fun capabilities(access: ControlAccessBinding) = ControlValue.Object(emptyMap())
            override fun cancel(scope: ControlRequestScope) {}
            override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult = error("本用例不执行手机动作")
        }
        val bus = PhoneControlBus(backgroundScope, Ledger(), executor, phoneFacadeTestStrings)
        val facade = PhoneRemoteRpcHostHandler(backgroundScope, PhoneVirtualHostRouter(bus)) { connection ->
            binding(connection.generation).also { executor.requireCurrentAccess(it) }
        }
        var previous: RemoteRpcConnectionScope? = null
        repeat(20) { index ->
            val access = binding(index + 1L); active += access
            val wire = Wire(); val current = rpc(index + 1L)
            val session = RemoteRpcSession(current, emptySet(), wire, backgroundScope, facade) { active -= access }
            wire.invoke("harness.remote.stream.open", stream("observer"), "open"); runCurrent()
            assertEquals(1, (bus.changes as MutableStateFlow<Long>).subscriptionCount.value)
            previous?.let(facade::disconnect); runCurrent()
            assertEquals(1, (bus.changes as MutableStateFlow<Long>).subscriptionCount.value)
            val before = wire.writes.size; bus.create(access); runCurrent()
            assertTrue(wire.writes.size > before)
            session.close(); runCurrent()
            assertEquals(0, (bus.changes as MutableStateFlow<Long>).subscriptionCount.value)
            assertNull(wire.receive); assertNull(wire.terminal)
            previous = current
        }
        facade.close()
    }
}
