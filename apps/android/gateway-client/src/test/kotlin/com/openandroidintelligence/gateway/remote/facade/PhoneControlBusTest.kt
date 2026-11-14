package com.openandroidintelligence.gateway.remote.facade

import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.gateway.schema.Json
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
        val calls = mutableListOf<String>()
        override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
            context.beforeDispatch(); calls += context.scope.requestId
            return PhoneControlResult(ControlValue.Object(mapOf("value" to ControlValue.Text("结果正文"))))
        }
        override fun cancel(scope: ControlRequestScope) {}
        override fun capabilities(access: ControlAccessBinding) = ControlValue.Object(emptyMap())
    }

    @Test fun repeatAndTaskConflictNeverRepeatProvider() = runTest {
        val ledger = Ledger(); val executor = Executor(); val bus = PhoneControlBus(this, ledger, executor)
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
        val bus = PhoneControlBus(this, Ledger(), executor); val task = bus.create(access())
        bus.submit(access(), task, request("awaiting-request-0001")); runCurrent(); assertTrue(entered.isCompleted)
        bus.submit(access(), task, cancel("cancel-request-00001", "awaiting-request-0001")); runCurrent()
        assertTrue(Json.canonical(bus.receipt(access(), task, "awaiting-request-0001")).contains("cancelled"))
        bus.submit(access(), task, request("following-request-01")); runCurrent()
        bus.submit(access(), task, cancel("cancel-request-00002", "awaiting-request-0001")); runCurrent()
        assertEquals(listOf("following-request-01"), executor.calls)
        assertTrue(Json.canonical(bus.receipt(access(), task, "following-request-01")).contains("completed"))
    }

    @Test fun sameAccountDifferentDeviceCannotReadTasksOrArtifacts() = runTest {
        val bus = PhoneControlBus(this, Ledger(), Executor()); val task = bus.create(access())
        bus.submit(access(), task, request("isolated-request-001")); runCurrent()
        assertThrows(ControlRequestException::class.java) { bus.history(access("other-device"), task) }
        assertThrows(ControlRequestException::class.java) { bus.receipt(access("other-device"), task, "isolated-request-001") }
        assertFalse(Json.canonical(Json.of(bus.sessions(access("other-device")))).contains(task))
    }

    @Test fun providerCannotCompleteWithoutPersistedExecutionFence() = runTest {
        val executor = object : Executor() {
            override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation) = PhoneControlResult(ControlValue.Object(emptyMap()))
        }
        val bus = PhoneControlBus(this, Ledger(), executor); val task = bus.create(access())
        bus.submit(access(), task, request("missing-fence-000001")); runCurrent()
        val result = Json.canonical(bus.receipt(access(), task, "missing-fence-000001"))
        assertTrue(result.contains("CONTROL_EXECUTION_FENCE_REQUIRED")); assertFalse(result.contains("\"state\":\"completed\""))
    }

    @Test fun restartedBusCannotRecreateOldTaskAndStatusPublishesAssistantRecord() = runTest {
        val ledger = Ledger(); val bus = PhoneControlBus(this, ledger, Executor()); val task = bus.create(access())
        bus.submit(access(), task, request("persisted-request-001")); runCurrent()
        val restarted = PhoneControlBus(this, ledger, Executor())
        assertThrows(ControlRequestException::class.java) { restarted.create(access(), task) }
        val status = ControlRequestParser.parse(Json.canonical(Json.of(mapOf("request_id" to "status-request-00001", "op" to "request.status",
            "args" to mapOf("targetRequestId" to "persisted-request-001", "targetTaskSessionId" to task)))))
        val receipt = Json.canonical(restarted.submit(access(), "control-bus", status))
        assertTrue(receipt.contains("\"resultAvailable\":false")); assertTrue(receipt.contains("completed"))
        assertTrue(Json.canonical(restarted.history(access(), "control-bus")).contains("status-request-00001"))
    }
}
