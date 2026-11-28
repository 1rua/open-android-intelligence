package com.openandroidintelligence.gateway.remote.facade

import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PhoneControlBusTest {
    private class Ledger : ControlMetadataLedger {
        val rows = linkedMapOf<String, ControlRequestMetadata>()
        @Synchronized override fun <T> transaction(change: (MutableMap<String, ControlRequestMetadata>) -> T): T {
            val next = rows.toMutableMap(); val value = change(next); rows.clear(); rows.putAll(next); return value
        }
    }
    private fun access(device: String = "controller") = ControlAccessBinding(RemoteControlPrincipal(
        ControlOrigin.fromCanonical("https://remote.example"), "account", device, "phone", "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64)), 1, "connection", 1)
    private fun request(id: String, text: String = "正文") = ControlRequestParser.parse(Json.canonical(Json.of(mapOf("request_id" to id,
        "op" to "plugin.invoke", "args" to mapOf("pluginId" to "sms", "capabilityId" to "query@1.0.0", "method" to "query", "params" to mapOf("text" to text))))))
    private fun cancel(id: String, target: String) = ControlRequestParser.parse(Json.canonical(Json.of(mapOf("request_id" to id,
        "op" to "request.cancel", "args" to mapOf("targetRequestId" to target)))))
    private open class Executor : PhoneControlExecutor {
        override fun requireCurrentAccess(access: ControlAccessBinding) = Unit
        val calls = mutableListOf<String>()
        override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
            context.beforeDispatch(); calls += context.scope.requestId
            return PhoneControlResult(ControlValue.Object(mapOf("value" to ControlValue.Text("结果正文"))))
        }
        override fun cancel(scope: ControlRequestScope) {}
        override fun capabilities(access: ControlAccessBinding) = ControlValue.Object(emptyMap())
    }

    private fun records(page: JsonValue) =
        JsonFields.objects(page as? JsonValue.JObject, "records")
    private fun event(record: JsonValue.JObject) = JsonFields.obj(JsonFields.field(record, "event"))
    private fun eventData(record: JsonValue.JObject) = JsonFields.obj(JsonFields.field(event(record), "data"))
    private fun eventType(record: JsonValue.JObject) = JsonFields.string(event(record), "type")
    private fun eventRequestId(record: JsonValue.JObject) = JsonFields.string(eventData(record), "request_id")
    private fun streamTypes(record: JsonValue.JObject) = JsonFields.objects(eventData(record), "stream").mapNotNull {
        JsonFields.string(JsonFields.obj(JsonFields.field(it, "chunk")), "type")
    }

    @Test fun 本机拒绝会结束审批观察且从未进入执行栅栏() = runTest {
        val decision = CompletableDeferred<Unit>()
        val executor = object : Executor() {
            override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
                context.awaitingConfirmation(true)
                decision.await()
                throw ControlRequestException("LOCAL_USER_REJECTED")
            }
        }
        val ledger = Ledger()
        val owner = access()
        val bus = PhoneControlBus(this, ledger, executor, phoneFacadeTestStrings)
        val task = bus.create(owner)
        val pending = request("local-rejected-001")
        bus.submit(owner, task, pending)
        runCurrent()
        assertEquals(ControlRequestState.AWAITING_CONFIRMATION, ledger.rows.values.last().state)

        decision.complete(Unit)
        runCurrent()
        val related = records(bus.history(owner, task)).filter { eventRequestId(it) == pending.requestId }
        assertEquals(listOf("turn/start", "approval/asked", "approval/decided", "assistant/message", "turn/end"), related.map(::eventType))
        assertEquals(JsonFields.string(eventData(related[1]), "id"), JsonFields.string(eventData(related[2]), "id"))
        assertEquals(true, JsonFields.bool(eventData(related[2]), "localOnly"))
        val row = ledger.rows.values.last()
        assertEquals(ControlRequestState.DENIED, row.state)
        assertEquals(ControlExecutionStatus.NOT_STARTED, row.execution)
        assertEquals("LOCAL_USER_REJECTED", row.errorCode)
        assertFalse(row.executionFence)
    }

    @Test fun connectionClosedBeforeTurnPublishesMinimalClosedHistoryForSuccessorAccess() = runTest {
        val ledger = Ledger(); val executor = Executor(); val original = access()
        val bus = PhoneControlBus(this, ledger, executor, phoneFacadeTestStrings); val task = bus.create(original)
        val secret = "不应进入连接关闭历史正文"
        val closed = request("connection-closed-0001", secret)
        bus.submit(original, task, closed)
        // 保持 job 尚未调度，复现 accepted 后短连接先断开、历史里还没有 turn/start 的真实窗口。
        assertEquals(ControlRequestState.ACCEPTED, ledger.rows.values.single { it.requestId == closed.requestId }.state)
        bus.connectionClosed(original); runCurrent()

        val row = ledger.rows.values.single { it.requestId == closed.requestId }
        assertEquals(ControlRequestState.CANCELLED, row.state)
        assertEquals(ControlExecutionStatus.NOT_STARTED, row.execution)
        assertEquals("REMOTE_CONNECTION_CLOSED", row.errorCode)

        // 重连沿用同一主体与授权代次，只更换临时连接标识；官方历史仍应可读。
        val successor = original.copy(connectionId = "successor-connection", connectionGeneration = 2)
        val page = bus.history(successor, task)
        val related = records(page).filter { eventRequestId(it) == closed.requestId }
        val starts = related.filter { eventType(it) == "turn/start" }
        val messages = related.filter { eventType(it) == "assistant/message" }
        val ends = related.filter { eventType(it) == "turn/end" }
        assertEquals(1, starts.size)
        assertEquals(1, messages.size)
        assertEquals(1, ends.size)
        val turn = JsonFields.long(eventData(starts.single()), "turn")
        assertEquals(turn, JsonFields.long(eventData(ends.single()), "turn"))
        assertEquals(turn?.plus(1), JsonFields.long(page as JsonValue.JObject, "nextTurn"))

        val assistantRecord = messages.single()
        assertEquals(listOf("block-start", "text-delta", "block-end", "finish"), streamTypes(assistantRecord))
        val message = JsonFields.obj(JsonFields.field(eventData(assistantRecord), "message"))!!
        val messageId = JsonFields.string(message, "id")
        assertEquals(closed.requestId, messageId)
        val text = JsonFields.string(JsonFields.objects(message, "content").single(), "text")!!
        assertTrue(text.contains("\"state\":\"cancelled\""))
        assertTrue(text.contains("\"error\":{\"code\":\"REMOTE_CONNECTION_CLOSED\""))
        assertFalse(Json.canonical(page).contains(secret))
        val projections = JsonFields.obj(JsonFields.field(page, "projections"))!!
        val values = JsonFields.obj(JsonFields.field(projections, "values"))!!
        val execution = JsonFields.obj(JsonFields.field(values, "execution"))!!
        assertEquals("idle", JsonFields.string(execution, "status"))
        val listed = bus.sessions(successor).map { it as JsonValue.JObject }.single { JsonFields.string(it, "sessionId") == task }
        assertEquals(false, JsonFields.bool(listed, "running"))

        bus.connectionClosed(original); runCurrent()
        val afterRepeat = records(bus.history(successor, task)).filter { eventRequestId(it) == closed.requestId }
        assertEquals(1, afterRepeat.count { eventType(it) == "turn/start" })
        assertEquals(1, afterRepeat.count { eventType(it) == "assistant/message" })
        assertEquals(1, afterRepeat.count { eventType(it) == "turn/end" })
        val repeatedMessage = JsonFields.obj(JsonFields.field(eventData(afterRepeat.single { eventType(it) == "assistant/message" }), "message"))!!
        assertEquals(messageId, JsonFields.string(repeatedMessage, "id"))
        assertEquals(listOf("block-start", "text-delta", "block-end", "finish"), streamTypes(afterRepeat.single { eventType(it) == "assistant/message" }))

        bus.submit(successor, task, closed); runCurrent()
        assertTrue(executor.calls.isEmpty())
        assertThrows(ControlRequestException::class.java) {
            bus.history(successor.copy(authorizationEpoch = successor.authorizationEpoch + 1), task)
        }
    }

    @Test fun connectionClosedAfterExecutionFenceClosesExistingTurnAsUnknownWithoutDuplicatingIt() = runTest {
        val entered = CompletableDeferred<Unit>(); val never = CompletableDeferred<Unit>()
        val executor = object : Executor() {
            override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
                context.beforeDispatch(); calls += context.scope.requestId; entered.complete(Unit); never.await()
                return PhoneControlResult(ControlValue.Object(emptyMap()))
            }
        }
        val ledger = Ledger(); val original = access()
        val bus = PhoneControlBus(this, ledger, executor, phoneFacadeTestStrings); val task = bus.create(original)
        val secret = "已进入执行端口但不进入历史"
        val request = request("connection-fenced-0001", secret)
        bus.submit(original, task, request); runCurrent(); assertTrue(entered.isCompleted)
        assertEquals(ControlRequestState.RUNNING, ledger.rows.values.single { it.requestId == request.requestId }.state)

        bus.connectionClosed(original); runCurrent()
        val successor = original.copy(connectionId = "successor-connection", connectionGeneration = 2)
        val page = bus.history(successor, task)
        val related = records(page).filter { eventRequestId(it) == request.requestId }
        assertEquals(1, related.count { eventType(it) == "turn/start" })
        assertEquals(1, related.count { eventType(it) == "assistant/message" })
        assertEquals(1, related.count { eventType(it) == "turn/end" })
        val message = JsonFields.obj(JsonFields.field(eventData(related.single { eventType(it) == "assistant/message" }), "message"))!!
        val text = JsonFields.string(JsonFields.objects(message, "content").single(), "text")!!
        assertTrue(text.contains("\"state\":\"outcome_unknown\""))
        assertTrue(text.contains("\"status\":\"unknown\""))
        assertTrue(text.contains("\"resultAvailable\":false"))
        assertTrue(text.contains("REMOTE_CONNECTION_CLOSED"))
        assertFalse(Json.canonical(page).contains(secret))
        assertEquals(ControlRequestState.OUTCOME_UNKNOWN, ledger.rows.values.single { it.requestId == request.requestId }.state)
    }

    @Test fun 成功已提交但发布时连接失权仍能在重连后读到终态() = runTest {
        val ledger = Ledger()
        val original = access()
        val request = request("success-before-disconnect")
        val executor = object : Executor() {
            override fun requireCurrentAccess(access: ControlAccessBinding) {
                if (access == original && ledger.rows.values.any {
                    it.requestId == request.requestId && it.state == ControlRequestState.COMPLETED
                }) throw ControlRequestException("REMOTE_AUTHORIZATION_CHANGED")
            }
        }
        val bus = PhoneControlBus(this, ledger, executor, phoneFacadeTestStrings)
        val task = bus.create(original)
        bus.submit(original, task, request)
        runCurrent()
        bus.connectionClosed(original)
        val successor = original.copy(connectionId = "successor", connectionGeneration = 2)
        val events = records(bus.history(successor, task)).filter { eventRequestId(it) == request.requestId }
        assertEquals(listOf("turn/start", "assistant/message", "turn/end"), events.map(::eventType))
        val result = Json.canonical(bus.receipt(successor, task, request.requestId))
        assertTrue(result.contains("\"status\":\"succeeded\""))
        assertFalse(result.contains("结果正文"))
        assertEquals(1, executor.calls.size)
    }

    @Test fun connectionClosedKeepsSuccessfulResultAndItsSingleTerminalTurn() = runTest {
        val executor = Executor(); val original = access()
        val bus = PhoneControlBus(this, Ledger(), executor, phoneFacadeTestStrings); val task = bus.create(original)
        val request = request("successful-request-001")
        bus.submit(original, task, request); runCurrent()
        val successor = original.copy(connectionId = "successor-connection", connectionGeneration = 2)
        val before = bus.history(successor, task) as JsonValue.JObject
        val beforeRelated = records(before).filter { eventRequestId(it) == request.requestId }
        assertEquals(1, beforeRelated.count { eventType(it) == "turn/start" })
        assertEquals(1, beforeRelated.count { eventType(it) == "assistant/message" })
        assertEquals(1, beforeRelated.count { eventType(it) == "turn/end" })
        val beforeNextTurn = JsonFields.long(before, "nextTurn")

        bus.connectionClosed(original); runCurrent()
        val receipt = bus.receipt(successor, task, request.requestId)
        val receiptText = Json.canonical(receipt)
        assertTrue(receiptText.contains("\"state\":\"completed\""))
        assertTrue(receiptText.contains("\"status\":\"succeeded\""))
        assertTrue(receiptText.contains("\"resultAvailable\":true"))
        assertTrue(receiptText.contains("结果正文"))
        val after = bus.history(successor, task) as JsonValue.JObject
        val afterRelated = records(after).filter { eventRequestId(it) == request.requestId }
        assertEquals(1, afterRelated.count { eventType(it) == "assistant/message" })
        assertEquals(1, afterRelated.count { eventType(it) == "turn/end" })
        assertEquals(beforeNextTurn, JsonFields.long(after, "nextTurn"))
        assertEquals(1, executor.calls.size)
    }

    @Test fun repeatAndTaskConflictNeverRepeatProvider() = runTest {
        val ledger = Ledger(); val executor = Executor(); val bus = PhoneControlBus(this, ledger, executor, phoneFacadeTestStrings)
        val task = bus.create(access()); val original = request("original-request-0001")
        bus.submit(access(), task, original); runCurrent()
        bus.submit(access(), task, original); runCurrent()
        assertEquals(1, executor.calls.size)
        val otherTask = bus.create(access())
        assertThrows(ControlRequestException::class.java) { bus.submit(access(), otherTask, original) }
        assertThrows(ControlRequestException::class.java) { bus.submit(access(), task, request(original.requestId, "不同正文")) }
        assertFalse(ledger.rows.values.any { it.toString().contains("结果正文") || it.toString().contains("不同正文") })
    }

    @Test fun cancellationBypassesPendingApprovalAndLateCancelDoesNotStopNextRequest() = runTest {
        val entered = CompletableDeferred<Unit>(); val never = CompletableDeferred<Unit>()
        val executor = object : Executor() {
            override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
                if (context.scope.requestId == "awaiting-request-0001") {
                    context.awaitingConfirmation(true); entered.complete(Unit); never.await()
                }
                return super.execute(context, operation)
            }
        }
        val bus = PhoneControlBus(this, Ledger(), executor, phoneFacadeTestStrings); val task = bus.create(access())
        bus.submit(access(), task, request("awaiting-request-0001")); runCurrent(); assertTrue(entered.isCompleted)
        bus.submit(access(), task, cancel("cancel-request-00001", "awaiting-request-0001")); runCurrent()
        assertTrue(Json.canonical(bus.receipt(access(), task, "awaiting-request-0001")).contains("cancelled"))
        bus.submit(access(), task, request("following-request-01")); runCurrent()
        bus.submit(access(), task, cancel("cancel-request-00002", "awaiting-request-0001")); runCurrent()
        assertEquals(listOf("following-request-01"), executor.calls)
        assertTrue(Json.canonical(bus.receipt(access(), task, "following-request-01")).contains("completed"))
    }

    @Test fun sameAccountDifferentDeviceCannotReadTasksOrArtifacts() = runTest {
        val bus = PhoneControlBus(this, Ledger(), Executor(), phoneFacadeTestStrings); val task = bus.create(access())
        bus.submit(access(), task, request("isolated-request-001")); runCurrent()
        assertThrows(ControlRequestException::class.java) { bus.history(access("other-device"), task) }
        assertThrows(ControlRequestException::class.java) { bus.receipt(access("other-device"), task, "isolated-request-001") }
        assertFalse(Json.canonical(Json.of(bus.sessions(access("other-device")))).contains(task))
    }

    @Test fun providerCannotCompleteWithoutPersistedExecutionFence() = runTest {
        val executor = object : Executor() {
            override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation) = PhoneControlResult(ControlValue.Object(emptyMap()))
        }
        val bus = PhoneControlBus(this, Ledger(), executor, phoneFacadeTestStrings); val task = bus.create(access())
        bus.submit(access(), task, request("missing-fence-000001")); runCurrent()
        val result = Json.canonical(bus.receipt(access(), task, "missing-fence-000001"))
        assertTrue(result.contains("CONTROL_EXECUTION_FENCE_REQUIRED")); assertFalse(result.contains("\"state\":\"completed\""))
    }

    @Test fun smsEffectConsumesThePersistedFenceOnceAndKeepsUnknownAfterSecondAttempt() = runTest {
        val ledger = Ledger()
        var effects = 0
        val executor = object : Executor() {
            override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
                context.consumeSmsSendEffect(); effects++
                context.consumeSmsSendEffect(); effects++
                return PhoneControlResult(ControlValue.Object(emptyMap()))
            }
        }
        val bus = PhoneControlBus(this, ledger, executor, phoneFacadeTestStrings); val task = bus.create(access())
        val request = ControlRequestParser.parse(Json.canonical(Json.of(mapOf("request_id" to "sms-repeated-effect-01",
            "op" to "plugin.invoke", "args" to mapOf("pluginId" to "org.openandroidintelligence.sms",
                "capabilityId" to "org.openandroidintelligence.sms.send@1.0.0", "method" to "send",
                "params" to mapOf("subscriptionId" to 2, "recipient" to "+15550001", "text" to "私密短信"))))))
        bus.submit(access(), task, request); runCurrent()
        assertEquals(1, effects)
        val row = ledger.rows.values.single { it.requestId == request.requestId }
        assertTrue(row.executionFence)
        assertEquals(ControlRequestState.OUTCOME_UNKNOWN, row.state)
        assertEquals(ControlExecutionStatus.UNKNOWN, row.execution)
        assertEquals("SMS_EFFECT_ALREADY_CONSUMED", row.errorCode)
        bus.submit(access(), task, request); runCurrent()
        assertEquals(1, effects)
        assertFalse(ledger.rows.values.any { it.toString().contains("私密短信") })
    }

    @Test fun ordinaryDispatchCheckRemainsRepeatableForExistingReadAndNativeProviders() = runTest {
        val executor = object : Executor() {
            override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
                context.beforeDispatch(); context.beforeDispatch()
                return PhoneControlResult(ControlValue.Object(emptyMap()))
            }
        }
        val bus = PhoneControlBus(this, Ledger(), executor, phoneFacadeTestStrings); val task = bus.create(access())
        bus.submit(access(), task, request("ordinary-repeat-0001")); runCurrent()
        assertTrue(Json.canonical(bus.receipt(access(), task, "ordinary-repeat-0001")).contains("completed"))
    }

    @Test fun typedSmsUnknownRetainsOnlyMetadataAndOrdinaryPluginStatusDoesNotChangeCompletion() = runTest {
        val ledger = Ledger()
        val executor = object : Executor() {
            override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
                val sms = (operation as ControlOperation.PluginInvoke).pluginId == "org.openandroidintelligence.sms"
                if (sms) context.consumeSmsSendEffect() else context.beforeDispatch()
                calls += context.scope.requestId
                return PhoneControlResult(ControlValue.Object(mapOf("status" to ControlValue.Text("outcome_unknown"))),
                    artifacts = if (sms) listOf(ControlArtifact("unknown-file", "text/plain", "1", "私密产物".encodeToByteArray())) else emptyList(),
                    completion = if (sms) PhoneControlCompletion.SMS_SEND_OUTCOME_UNKNOWN else PhoneControlCompletion.SUCCEEDED)
            }
        }
        val bus = PhoneControlBus(this, ledger, executor, phoneFacadeTestStrings); val task = bus.create(access())
        val sms = ControlRequestParser.parse(Json.canonical(Json.of(mapOf("request_id" to "sms-unknown-result-01",
            "op" to "plugin.invoke", "args" to mapOf("pluginId" to "org.openandroidintelligence.sms",
                "capabilityId" to "org.openandroidintelligence.sms.send@1.0.0", "method" to "send",
                "params" to mapOf("subscriptionId" to 2, "recipient" to "+15550001", "text" to "私密正文"))))))
        bus.submit(access(), task, sms); runCurrent()
        val row = ledger.rows.values.single { it.requestId == sms.requestId }
        assertTrue(row.executionFence)
        assertEquals(ControlRequestState.OUTCOME_UNKNOWN, row.state)
        assertEquals(ControlExecutionStatus.UNKNOWN, row.execution)
        val receipt = Json.canonical(bus.receipt(access(), task, sms.requestId))
        assertTrue(receipt.contains("\"resultAvailable\":false"))
        assertFalse(receipt.contains("\"result\":"))
        assertThrows(ControlRequestException::class.java) { bus.artifact(access(), task, "unknown-file") }
        bus.submit(access(), task, sms); runCurrent(); assertEquals(1, executor.calls.size)
        val ordinary = request("ordinary-json-status-01")
        bus.submit(access(), task, ordinary); runCurrent()
        assertEquals(ControlRequestState.COMPLETED, ledger.rows.values.single { it.requestId == ordinary.requestId }.state)
    }

    @Test fun restartedBusCannotRecreateOldTaskAndStatusPublishesAssistantRecord() = runTest {
        val ledger = Ledger(); val bus = PhoneControlBus(this, ledger, Executor(), phoneFacadeTestStrings); val task = bus.create(access())
        bus.submit(access(), task, request("persisted-request-001")); runCurrent()
        val restarted = PhoneControlBus(this, ledger, Executor(), phoneFacadeTestStrings)
        assertThrows(ControlRequestException::class.java) { restarted.create(access(), task) }
        val status = ControlRequestParser.parse(Json.canonical(Json.of(mapOf("request_id" to "status-request-00001", "op" to "request.status",
            "args" to mapOf("targetRequestId" to "persisted-request-001", "targetTaskSessionId" to task)))))
        val receipt = Json.canonical(restarted.submit(access(), "control-bus", status))
        assertTrue(receipt.contains("\"resultAvailable\":false")); assertTrue(receipt.contains("completed"))
        assertTrue(Json.canonical(restarted.history(access(), "control-bus")).contains("status-request-00001"))
    }

    @Test fun v3CursorIsLastInclusiveSequenceAndPageKeepsLastRecord() = runTest {
        val bus = PhoneControlBus(this, Ledger(), Executor(), phoneFacadeTestStrings); val task = bus.create(access())
        val empty = bus.history(access(), task) as com.openandroidintelligence.gateway.schema.JsonValue.JObject
        assertEquals(com.openandroidintelligence.gateway.schema.JsonValue.JNumber("-1"), empty.fields.toMap()["cursor"])
        bus.submit(access(), task, request("cursor-request-00001")); runCurrent()
        val page = bus.history(access(), task) as com.openandroidintelligence.gateway.schema.JsonValue.JObject
        val records = (page.fields.toMap().getValue("records") as com.openandroidintelligence.gateway.schema.JsonValue.JArray).items
        val last = ((records.last() as com.openandroidintelligence.gateway.schema.JsonValue.JObject).fields.toMap().getValue("event") as com.openandroidintelligence.gateway.schema.JsonValue.JObject).fields.toMap().getValue("seq")
        assertEquals(last, page.fields.toMap()["cursor"])
        assertEquals(1, bus.eventsSince(access(), task, 1).size)
    }

    @Test fun localEmergencyStopFreezesPendingAndNewRequestsWithoutLeakingBody() = runTest {
        val never = CompletableDeferred<Unit>()
        val executor = object : Executor() {
            override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
                context.awaitingConfirmation(true); never.await(); return super.execute(context, operation)
            }
        }
        val bus = PhoneControlBus(this, Ledger(), executor, phoneFacadeTestStrings); val task = bus.create(access())
        bus.submit(access(), task, request("kill-request-0000001")); runCurrent(); bus.emergencyStop(); runCurrent()
        assertTrue(Json.canonical(bus.receipt(access(), task, "kill-request-0000001")).contains("cancelled"))
        assertThrows(ControlRequestException::class.java) { bus.submit(access(), task, request("kill-request-0000002")) }
        assertTrue(executor.calls.isEmpty())
    }
}
