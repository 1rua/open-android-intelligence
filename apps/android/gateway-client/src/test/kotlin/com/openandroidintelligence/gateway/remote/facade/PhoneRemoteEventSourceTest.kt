package com.openandroidintelligence.gateway.remote.facade

import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.gateway.schema.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class PhoneRemoteEventSourceTest {
    private fun access(client: String = "client", generation: Long = 1) = ControlAccessBinding(RemoteControlPrincipal(
        ControlOrigin.fromCanonical("https://remote.example"), "account", client, "phone", "sha256:" + "a".repeat(64),
        "sha256:" + "b".repeat(64)), 1, "connection-$client", generation)
    private fun bus(scope: CoroutineScope): PhoneControlBus {
        val rows = linkedMapOf<String, ControlRequestMetadata>()
        val ledger = object : ControlMetadataLedger {
            override fun <T> transaction(change: (MutableMap<String, ControlRequestMetadata>) -> T): T = change(rows)
        }
        val executor = object : PhoneControlExecutor {
            override fun requireCurrentAccess(access: ControlAccessBinding) = Unit
            override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult = error("此测试不调用平台")
            override fun cancel(scope: ControlRequestScope) { }
            override fun capabilities(access: ControlAccessBinding) = ControlValue.Object(emptyMap())
        }
        return PhoneControlBus(scope, ledger, executor, phoneFacadeTestStrings)
    }
    private fun answer(id: String) = mapOf("clientId" to JsonValue.JString(id), "eventId" to JsonValue.JString("unknown"),
        "outcome" to Json.of(mapOf("kind" to "rejected", "error" to mapOf("name" to "Error", "message" to "", "code" to ""))))

    @Test fun 首帧就绪后真实新增事件可见且另一个设备目录不泄露() = runTest {
        val bus = bus(this); val source = PhoneRemoteEventSource(bus); val frames = mutableListOf<JsonValue>()
        val subscription = backgroundScope.launch { source.open(access(), emptyMap()).collect { frames += it } }
        runCurrent()
        val ready = frames.single()
        assertEquals("ready", JsonFields.string(JsonFields.obj(ready), "type"))
        val clientId = JsonFields.string(JsonFields.obj(ready), "clientId")!!
        assertEquals(JsonValue.JNull, source.answer(access(), answer(clientId)))
        val denied = assertThrows(ControlRequestException::class.java) { source.answer(access("other"), answer(clientId)) }
        assertEquals("CONTROL_EVENT_CLIENT_UNAVAILABLE", denied.code)
        val task = bus.create(access()); runCurrent()
        assertTrue(frames.any { Json.canonical(it).contains(task) && JsonFields.string(JsonFields.obj(it), "event") == "api-session/added" })
        val before = frames.size
        bus.create(access("other")); runCurrent(); assertEquals(before, frames.size)
        subscription.cancelAndJoin()
        assertThrows(ControlRequestException::class.java) { source.answer(access(), answer(clientId)) }
    }

    @Test fun 取消旧代际重新订阅使用独立clientId且不能接受旧回答() = runTest {
        val source = PhoneRemoteEventSource(bus(this)); val oldFrames = mutableListOf<JsonValue>(); val newFrames = mutableListOf<JsonValue>()
        val old = backgroundScope.launch { source.open(access(), emptyMap()).collect { oldFrames += it } }
        runCurrent(); val oldId = JsonFields.string(JsonFields.obj(oldFrames.first()), "clientId")!!
        old.cancelAndJoin()
        val next = backgroundScope.launch { source.open(access(generation = 2), emptyMap()).collect { newFrames += it } }
        runCurrent(); val newId = JsonFields.string(JsonFields.obj(newFrames.first()), "clientId")!!
        assertNotEquals(oldId, newId)
        assertThrows(ControlRequestException::class.java) { source.answer(access(generation = 2), answer(oldId)) }
        assertEquals(JsonValue.JNull, source.answer(access(generation = 2), answer(newId)))
        next.cancelAndJoin()
    }
}
