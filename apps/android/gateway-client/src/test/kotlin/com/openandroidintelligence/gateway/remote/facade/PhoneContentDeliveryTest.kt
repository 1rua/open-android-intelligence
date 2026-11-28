package com.openandroidintelligence.gateway.remote.facade

import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcConnectionScope
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
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
    private class Executor(private val artifactIdForRequest: (String) -> String = { artifactId(it) }) : PhoneControlExecutor {
        var current = true
        var contentCurrent = true
        var disconnectInCheck = false
        val contentCurrentByRequest = mutableMapOf<String, Boolean>()
        var lastContentAccess: ControlAccessBinding? = null
        override fun requireCurrentAccess(access: ControlAccessBinding) {
            if (!current) throw ControlRequestException("REMOTE_AUTHORIZATION_CHANGED")
        }
        override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
            context.beforeDispatch()
            val screenAction = operation is ControlOperation.Screen
            return PhoneControlResult(ControlValue.Object(if (screenAction) mapOf("performed" to ControlValue.Boolean(true))
                    else mapOf("secret" to ControlValue.Text(SECRET))),
                listOf(ControlArtifact(artifactIdForRequest(context.scope.key.requestId), "image/webp", "version", SECRET.toByteArray())), requireContentAccess = { access ->
                    lastContentAccess = access
                    if (disconnectInCheck) {
                        current = false
                        throw ControlRequestException("REMOTE_AUTHORIZATION_CHANGED")
                    }
                    if (!contentCurrent || contentCurrentByRequest[context.scope.key.requestId] == false) throw ControlRequestException("PUSH_DELIVERY_REVOKED")
                }, ui = if (screenAction) ControlValue.Object(mapOf("secret" to ControlValue.Text(SECRET))) else null)
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
    private fun file(taskId: String, requestId: String = "content-request-00001") = Json.of(mapOf("args" to mapOf("workspaceFileScopeId" to taskId,
        "path" to "artifacts/${artifactId(requestId)}", "range" to mapOf("offset" to 0, "length" to 4096))))
    private suspend fun denied(code: String, action: suspend () -> Unit) {
        try { action(); fail("应在交付前拒绝 $code") } catch (failure: ControlRequestException) { assertEquals(code, failure.code) }
    }

    @Test fun emergencyStopDeniesCompletedBodyFilesAndIncrementsButKeepsReceiptFacts() = runTest {
        val executor = Executor(); val bus = PhoneControlBus(this, Ledger(), executor, phoneFacadeTestStrings); val router = PhoneVirtualHostRouter(bus)
        val taskId = bus.create(access); bus.submit(access, taskId, request()); runCurrent()
        assertTrue(Json.canonical(router.route(access, "session/page", page(taskId))).contains(SECRET))
        assertEquals(SECRET, bus.artifact(access, taskId, artifactId()).bytes.decodeToString())
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

    @Test fun 消费或本机推送撤销后历史流与产物不能重新交付旧正文()=runTest {
        val executor=Executor();val bus=PhoneControlBus(this,Ledger(),executor,phoneFacadeTestStrings)
        val task=bus.create(access);bus.submit(access,task,request());runCurrent()
        assertTrue(Json.canonical(bus.history(access,task)).contains(SECRET))
        executor.contentCurrent=false
        val history=Json.canonical(bus.history(access,task))
        assertFalse(history.contains(SECRET));assertTrue(history.contains("resultAvailable"))
        val events=Json.canonical(Json.of(bus.eventsSince(access,task,-1)))
        assertFalse(events.contains(SECRET));assertTrue(events.contains("resultAvailable"))
        denied("CONTROL_ARTIFACT_UNAVAILABLE") { bus.artifact(access, task, artifactId()) }
        assertFalse(Json.canonical(bus.receipt(access,task,"content-request-00001")).contains(SECRET))
    }

    @Test fun 同一拥有者重连后用当前连接复核撤权状态和产物() = runTest {
        val executor = Executor()
        val bus = PhoneControlBus(this, Ledger(), executor, phoneFacadeTestStrings)
        val task = bus.create(access)
        val original = request()
        bus.submit(access, task, original)
        runCurrent()
        bus.connectionClosed(access)

        val reconnected = access.copy(connectionId = "new-connection", connectionGeneration = 2)
        val newEpoch = reconnected.copy(authorizationEpoch = access.authorizationEpoch + 1)
        val beforeRecheck = executor.lastContentAccess
        assertFalse(Json.canonical(bus.receipt(newEpoch, task, original.requestId)).contains(SECRET))
        assertEquals(beforeRecheck, executor.lastContentAccess)
        executor.disconnectInCheck = true
        denied("REMOTE_AUTHORIZATION_CHANGED") { bus.artifact(reconnected, task, artifactId()) }
        executor.disconnectInCheck = false
        executor.current = true
        assertEquals(SECRET, bus.artifact(reconnected, task, artifactId()).bytes.decodeToString())
        assertEquals(reconnected, executor.lastContentAccess)
        executor.contentCurrent = false
        val status = ControlRequestParser.parse(Json.canonical(Json.of(mapOf("request_id" to "content-status-000002", "op" to "request.status",
            "args" to mapOf("targetRequestId" to original.requestId, "targetTaskSessionId" to task)))))
        val statusReply = Json.canonical(bus.submit(reconnected, "control-bus", status))
        assertTrue(statusReply.contains("\"resultAvailable\":false"))
        assertTrue(statusReply.contains("\"status\":\"succeeded\""))
        executor.contentCurrent = true
        assertFalse(Json.canonical(bus.history(reconnected, task)).contains(SECRET))
        denied("CONTROL_ARTIFACT_UNAVAILABLE") { bus.artifact(reconnected, task, artifactId()) }
    }

    @Test fun 动作成功后只撤销附带观察时仍回传确认的动作结果() = runTest {
        val executor = Executor().apply { contentCurrent = false }
        val bus = PhoneControlBus(this, Ledger(), executor, phoneFacadeTestStrings)
        val task = bus.create(access)
        val request = ControlRequestParser.parse("""{
            "request_id":"act-content-000001", "op":"ui.global_action",
            "args":{"snapshotId":"test-snapshot", "globalAction":"home"}}""")
        bus.submit(access, task, request)
        runCurrent()
        val receipt = Json.canonical(bus.receipt(access, task, request.requestId))
        assertTrue(receipt.contains("\"status\":\"succeeded\""))
        assertTrue(receipt.contains("\"performed\":true"))
        assertFalse(receipt.contains(SECRET))
        assertFalse(receipt.contains("\"ui\":"))
        denied("CONTROL_ARTIFACT_UNAVAILABLE") { bus.artifact(access, task, artifactId(request.requestId)) }
    }

    @Test fun 只失效一个请求时controlBus回执能力与同任务其他请求仍可读()=runTest {
        val executor=Executor();val bus=PhoneControlBus(this,Ledger(),executor,phoneFacadeTestStrings)
        val task=bus.create(access)
        val first=request("content-old-000001");val second=request("content-new-000001")
        bus.submit(access,task,first);runCurrent();bus.submit(access,task,second);runCurrent()
        executor.contentCurrentByRequest["content-old-000001"] = false
        val oldReceipt=Json.canonical(bus.receipt(access,task,"content-old-000001"))
        assertTrue(oldReceipt.contains("resultAvailable\":false"));assertFalse(oldReceipt.contains(SECRET))
        denied("CONTROL_ARTIFACT_UNAVAILABLE") { bus.artifact(access, task, artifactId("content-old-000001")) }
        val newReceipt=Json.canonical(bus.receipt(access,task,"content-new-000001"))
        assertTrue(newReceipt.contains(SECRET));assertTrue(newReceipt.contains("resultAvailable\":true"))
        assertEquals(SECRET, bus.artifact(access, task, artifactId("content-new-000001")).bytes.decodeToString())
        val control=Json.canonical(bus.history(access,task))
        assertTrue(control.contains("content-old-000001"));assertTrue(control.contains("content-new-000001"))
        assertTrue(control.contains(SECRET)) // 未失效的新请求正文仍可读；旧请求在同一快照中已替换为metadata
    }

    @Test fun 撤权擦除后同任务旧回执的产物路径仍不能分配给新请求() = runTest {
        val first = request()
        val second = request("content-request-00002")
        val executor = Executor { artifactId(first.requestId) }
        val bus = PhoneControlBus(this, Ledger(), executor, phoneFacadeTestStrings)
        val router = PhoneVirtualHostRouter(bus)
        val task = bus.create(access)
        val originalFile = file(task, first.requestId)

        bus.submit(access, task, first)
        runCurrent()
        router.route(access, "workspaceFiles/stat", originalFile)
        executor.contentCurrentByRequest[first.requestId] = false
        denied("CONTROL_ARTIFACT_UNAVAILABLE") { router.route(access, "workspaceFiles/stat", originalFile) }

        bus.submit(access, task, second)
        runCurrent()
        val receipt = bus.receipt(access, task, second.requestId) as JsonValue.JObject
        assertEquals("CONTROL_ARTIFACT_CONFLICT", JsonFields.string(JsonFields.obj(JsonFields.field(receipt, "error")), "code"))
        denied("CONTROL_ARTIFACT_UNAVAILABLE") { router.route(access, "workspaceFiles/readBytes", originalFile) }
    }

    @Test fun originalConnectionCannotDeliverBufferedSecretAfterEmergencyStop() = runTest {
        val executor = Executor(); val bus = PhoneControlBus(this, Ledger(), executor, phoneFacadeTestStrings)
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
        val executor = Executor(); val bus = PhoneControlBus(this, Ledger(), executor, phoneFacadeTestStrings)
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

    companion object {
        private const val SECRET = "急停后不得交付的正文与像素"
        private fun artifactId(requestId: String = "content-request-00001") = "private-image-$requestId"
    }
}
