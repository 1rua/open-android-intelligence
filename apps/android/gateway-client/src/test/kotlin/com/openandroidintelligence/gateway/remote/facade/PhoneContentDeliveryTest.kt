package com.openandroidintelligence.gateway.remote.facade

import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcConnectionScope
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** 回归真实 Session 历史、文件面与 Host 已缓冲事件的交付边界。 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PhoneContentDeliveryTest {
    private class Ledger : ControlMetadataLedger {
        private var rows = mutableMapOf<String, ControlRequestMetadata>()
        @Synchronized override fun <T> transaction(change: (MutableMap<String, ControlRequestMetadata>) -> T): T {
            val next = rows.toMutableMap(); val result = change(next); rows = next; return result
        }
    }
    private class Executor : PhoneControlExecutor {
        var current = true
        override fun requireCurrentAccess(access: ControlAccessBinding) {
            if (!current) throw ControlRequestException("REMOTE_AUTHORIZATION_CHANGED")
        }
        override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
            context.beforeDispatch()
            return PhoneControlResult(ControlValue.Object(mapOf("secret" to ControlValue.Text(SECRET))),
                listOf(ControlArtifact("private-image", "image/webp", "version", SECRET.toByteArray())))
        }
        override fun cancel(scope: ControlRequestScope) {}
        override fun capabilities(access: ControlAccessBinding): ControlValue.Object {
            if (!current) throw ControlRequestException("REMOTE_AUTHORIZATION_CHANGED")
            return ControlValue.Object(emptyMap())
        }
    }
    private val access = ControlAccessBinding(RemoteControlPrincipal(ControlOrigin.fromCanonical("https://remote.example"),
        "account", "controller", "phone", "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64)), 1, "connection", 1)
    private val rpcScope = RemoteRpcConnectionScope("https://remote.example", "account", "phone", "controller", "connection", 1)
    private fun request(id: String = "content-request-00001") = ControlRequestParser.parse(Json.canonical(Json.of(mapOf(
        "request_id" to id, "op" to "ui.dump_tree", "args" to emptyMap<String, Any>()))))
    private fun page(taskId: String) = Json.of(mapOf("args" to mapOf("request" to mapOf("address" to mapOf("kind" to "session", "sessionId" to taskId)))))
    private fun file(taskId: String) = Json.of(mapOf("args" to mapOf("workspaceFileScopeId" to taskId,
        "path" to "artifacts/private-image", "range" to mapOf("offset" to 0, "length" to 4096))))
    private suspend fun denied(code: String, action: suspend () -> Unit) {
        try { action(); fail("应在交付前拒绝 $code") } catch (failure: ControlRequestException) { assertEquals(code, failure.code) }
    }

    @Test fun emergencyStopDeniesCompletedBodyFilesAndIncrementsButKeepsReceiptFacts() = runTest {
        val executor = Executor(); val bus = PhoneControlBus(this, Ledger(), executor); val router = PhoneVirtualHostRouter(bus)
        val taskId = bus.create(access); bus.submit(access, taskId, request()); runCurrent()
        assertTrue(Json.canonical(router.route(access, "session/page", page(taskId))).contains(SECRET))
        assertEquals(SECRET, bus.artifact(access, taskId, "private-image").bytes.decodeToString())
        bus.emergencyStop(); runCurrent()
        denied("LOCAL_EMERGENCY_STOP") { router.route(access, "session/page", page(taskId)) }
        denied("LOCAL_EMERGENCY_STOP") { router.route(access, "workspaceFiles/stat", file(taskId)) }
        denied("LOCAL_EMERGENCY_STOP") { router.route(access, "workspaceFiles/readBytes", file(taskId)) }
        denied("LOCAL_EMERGENCY_STOP") { bus.eventsSince(access, taskId, -1) }
        val receipt = Json.canonical(bus.receipt(access, taskId, "content-request-00001"))
        assertTrue(receipt.contains("\"state\":\"completed\"")); assertTrue(receipt.contains("\"status\":\"succeeded\""))
        assertTrue(receipt.contains("\"resultAvailable\":false")); assertFalse(receipt.contains(SECRET))
        val status = ControlRequestParser.parse(Json.canonical(Json.of(mapOf("request_id" to "content-status-000001", "op" to "request.status",
            "args" to mapOf("targetRequestId" to "content-request-00001", "targetTaskSessionId" to taskId)))))
        val statusReply = Json.canonical(bus.submit(access, "control-bus", status))
        assertTrue(statusReply.contains("\"state\":\"completed\"")); assertFalse(statusReply.contains(SECRET))
        val metadataPage = Json.canonical(router.route(access, "session/page", page("control-bus")))
        assertTrue(metadataPage.contains("content-status-000001")); assertFalse(metadataPage.contains(SECRET))
    }

    @Test fun originalConnectionCannotDeliverBufferedSecretAfterEmergencyStop() = runTest {
        val executor = Executor(); val bus = PhoneControlBus(this, Ledger(), executor)
        val handler = PhoneRemoteRpcHostHandler(this, PhoneVirtualHostRouter(bus)) { access }
        val taskId = bus.create(access); val release = CompletableDeferred<Unit>(); val frames = mutableListOf<String>()
        var terminal: Throwable? = null
        val events = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            handler.events(rpcScope).catch { terminal = it }.collect { event ->
                frames += Json.canonical(event.data)
                if (frames.size == 1) release.await()
            }
        }
        handler.invoke(rpcScope, "harness.remote.stream.open", Json.of(mapOf("streamId" to "content-stream", "endpoint" to "session/follow",
            "payload" to page(taskId))) as JsonValue.JObject)
        runCurrent(); assertEquals(1, frames.size)
        bus.submit(access, taskId, request()); runCurrent()
        bus.emergencyStop(); runCurrent(); release.complete(Unit); runCurrent()
        assertFalse(frames.any { it.contains(SECRET) })
        assertTrue(terminal is ControlRequestException || frames.any { it.contains("CONTROL_STREAM_UNAVAILABLE") })
        events.cancelAndJoin(); handler.close()
    }

    @Test fun bufferedEventsRecheckCurrentAuthorizationAtDelivery() = runTest {
        val executor = Executor(); val bus = PhoneControlBus(this, Ledger(), executor)
        val handler = PhoneRemoteRpcHostHandler(this, PhoneVirtualHostRouter(bus)) {
            if (!executor.current) throw ControlRequestException("REMOTE_AUTHORIZATION_CHANGED")
            access
        }
        val taskId = bus.create(access); val release = CompletableDeferred<Unit>(); val frames = mutableListOf<String>()
        var terminal: Throwable? = null
        val events = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            handler.events(rpcScope).catch { terminal = it }.collect { event ->
                frames += Json.canonical(event.data)
                if (frames.size == 1) release.await()
            }
        }
        handler.invoke(rpcScope, "harness.remote.stream.open", Json.of(mapOf("streamId" to "content-stream", "endpoint" to "session/follow",
            "payload" to page(taskId))) as JsonValue.JObject)
        runCurrent(); bus.submit(access, taskId, request()); runCurrent()
        executor.current = false; release.complete(Unit); runCurrent()
        assertFalse(frames.any { it.contains(SECRET) }); assertTrue(terminal is ControlRequestException)
        events.cancelAndJoin(); handler.close()
    }

    companion object { private const val SECRET = "急停后不得交付的正文与像素" }
}
