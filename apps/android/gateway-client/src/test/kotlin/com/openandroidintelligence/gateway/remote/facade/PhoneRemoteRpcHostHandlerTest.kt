package com.openandroidintelligence.gateway.remote.facade

import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcConnectionScope
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcEvent
import com.openandroidintelligence.gateway.schema.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 开流必须先等到实际事件消费订阅，避免无 replay 的事件总线丢掉 baseline/snapshot。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneRemoteRpcHostHandlerTest {
    private class Ledger : ControlMetadataLedger {
        private var rows = mutableMapOf<String, ControlRequestMetadata>()

        @Synchronized
        override fun <T> transaction(change: (MutableMap<String, ControlRequestMetadata>) -> T): T {
            val next = rows.toMutableMap()
            val result = change(next)
            rows = next
            return result
        }
    }

    private class Executor(var current: Boolean = true) : PhoneControlExecutor {
        override fun requireCurrentAccess(access: ControlAccessBinding) {
            if (!current) throw ControlRequestException("REMOTE_AUTHORIZATION_CHANGED")
        }

        override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult =
            error("本测试不执行手机动作")

        override fun cancel(scope: ControlRequestScope) = Unit

        override fun capabilities(access: ControlAccessBinding) = ControlValue.Object(emptyMap())
    }

    private val executor = Executor()
    private val access = ControlAccessBinding(
        RemoteControlPrincipal(
            ControlOrigin.fromCanonical("https://remote.example"),
            "account",
            "controller",
            "phone",
            "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64),
        ),
        authorizationEpoch = 1,
        connectionId = "connection",
        connectionGeneration = 1,
    )
    private val rpcScope = RemoteRpcConnectionScope(
        "https://remote.example",
        "account",
        "phone",
        "controller",
        "connection",
        1,
    )
    private fun TestScope.configure(): Pair<PhoneControlBus, PhoneRemoteRpcHostHandler> {
        val bus = PhoneControlBus(this, Ledger(), executor, phoneFacadeTestStrings)
        val handler = PhoneRemoteRpcHostHandler(this, PhoneVirtualHostRouter(bus)) {
            executor.requireCurrentAccess(access)
            access
        }
        return bus to handler
    }

    private suspend fun PhoneRemoteRpcHostHandler.open(streamId: String, endpoint: String, payload: JsonValue) {
        invoke(rpcScope, "harness.remote.stream.open", Json.of(mapOf(
            "streamId" to streamId,
            "endpoint" to endpoint,
            "payload" to payload,
        )) as JsonValue.JObject)
    }

    private suspend fun PhoneRemoteRpcHostHandler.close(streamId: String) {
        invoke(rpcScope, "harness.remote.stream.close", Json.of(mapOf("streamId" to streamId)) as JsonValue.JObject)
    }

    private fun PhoneControlBus.environmentSubscriptions() = (changes as MutableStateFlow<Long>).subscriptionCount.value

    private fun frameType(event: RemoteRpcEvent): String? {
        val data = JsonFields.obj(event.data) ?: return null
        val value = JsonFields.obj(JsonFields.field(data, "value")) ?: return null
        return JsonFields.string(value, "type")
    }

    private fun snapshotPayload() = Json.of(mapOf(
        "args" to mapOf("request" to mapOf("address" to mapOf("kind" to "session", "sessionId" to "control-bus"))),
    ))

    @Test fun 先开工作区流再订阅仍收到真实baseline() = runTest {
        val (bus, handler) = configure()
        handler.open("workspace", "workspace/follow", Json.of(emptyMap<String, Any>()))
        runCurrent()
        assertEquals("等待事件消费时不得提前启动上游流", 0, bus.environmentSubscriptions())

        val frames = mutableListOf<RemoteRpcEvent>()
        val consumer = launch { handler.events(rpcScope).collect { frames += it } }
        runCurrent()

        assertEquals(1, frames.size)
        assertEquals("harness.remote.frame", frames.single().event)
        assertEquals("baseline", frameType(frames.single()))
        assertTrue(Json.canonical(frames.single().data).contains("phone-capabilities"))
        consumer.cancelAndJoin()
        handler.close()
    }

    @Test fun 先开会话流再订阅仍收到真实snapshot() = runTest {
        val (bus, handler) = configure()
        handler.open("session", "session/follow", snapshotPayload())
        runCurrent()
        assertEquals("等待事件消费时不得提前启动上游流", 0, bus.environmentSubscriptions())

        val frames = mutableListOf<RemoteRpcEvent>()
        val consumer = launch { handler.events(rpcScope).collect { frames += it } }
        runCurrent()

        assertEquals(1, frames.size)
        assertEquals("harness.remote.frame", frames.single().event)
        assertEquals("snapshot", frameType(frames.single()))
        val snapshot = JsonFields.obj(JsonFields.field(JsonFields.obj(frames.single().data), "value"))!!
        assertEquals("control-bus", JsonFields.string(JsonFields.obj(JsonFields.field(snapshot, "header")), "id"))
        consumer.cancelAndJoin()
        handler.close()
    }

    @Test fun 延迟等待订阅时关闭流可取消且不产生首帧() = runTest {
        val (bus, handler) = configure()
        handler.open("closed-while-waiting", "workspace/follow", Json.of(emptyMap<String, Any>()))
        runCurrent()
        assertEquals(0, bus.environmentSubscriptions())

        handler.close("closed-while-waiting")
        runCurrent()
        assertEquals("关闭待订阅流后不得留下上游收集", 0, bus.environmentSubscriptions())

        val frames = mutableListOf<RemoteRpcEvent>()
        val consumer = launch { handler.events(rpcScope).collect { frames += it } }
        runCurrent()
        assertTrue("已关闭的待订阅流不能补发 baseline", frames.isEmpty())
        consumer.cancelAndJoin()
        handler.close()
    }

    @Test fun 延迟等待订阅时撤销授权会终止消费且不交付首帧() = runTest {
        val (bus, handler) = configure()
        handler.open("revoked-while-waiting", "workspace/follow", Json.of(emptyMap<String, Any>()))
        runCurrent()
        assertEquals(0, bus.environmentSubscriptions())

        executor.current = false
        val frames = mutableListOf<RemoteRpcEvent>()
        var terminal: Throwable? = null
        val consumer = launch {
            try {
                handler.events(rpcScope).collect { frames += it }
            } catch (failure: Throwable) {
                terminal = failure
            }
        }
        runCurrent()

        assertTrue("撤销后不能交付已等待的 baseline", frames.isEmpty())
        assertEquals("REMOTE_AUTHORIZATION_CHANGED", (terminal as? ControlRequestException)?.code)
        assertEquals("撤销后不得留下上游收集", 0, bus.environmentSubscriptions())
        consumer.cancelAndJoin()
        handler.close()
    }
}
