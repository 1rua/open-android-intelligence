package com.openandroidintelligence.gateway.remote.facade

import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneReceiptBudgetTest {
    private class Ledger : ControlMetadataLedger {
        val rows = linkedMapOf<String, ControlRequestMetadata>()

        @Synchronized
        override fun <T> transaction(change: (MutableMap<String, ControlRequestMetadata>) -> T): T {
            val next = rows.toMutableMap()
            val result = change(next)
            rows.clear()
            rows.putAll(next)
            return result
        }
    }

    private class Executor(
        var value: String,
        var artifacts: List<ControlArtifact> = emptyList(),
    ) : PhoneControlExecutor {
        override fun requireCurrentAccess(access: ControlAccessBinding) = Unit

        override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
            context.beforeDispatch()
            return PhoneControlResult(ControlValue.Object(mapOf("value" to ControlValue.Text(value))), artifacts)
        }

        override fun cancel(scope: ControlRequestScope) = Unit

        override fun capabilities(access: ControlAccessBinding) = ControlValue.Object(emptyMap())
    }

    private val access = ControlAccessBinding(
        RemoteControlPrincipal(
            ControlOrigin.fromCanonical("https://remote.example"), "account", "controller", "phone",
            "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
        ),
        authorizationEpoch = 1,
        connectionId = "connection",
        connectionGeneration = 1,
    )

    private fun request(id: String) = ControlRequestParser.parse(Json.canonical(Json.of(mapOf(
        "request_id" to id,
        "op" to "plugin.invoke",
        "args" to mapOf(
            "pluginId" to "org.example.reader",
            "capabilityId" to "org.example.reader.read@1.0.0",
            "method" to "read",
            "params" to emptyMap<String, Any>(),
        ),
    ))))

    private fun records(page: JsonValue): List<JsonValue.JObject> =
        JsonFields.objects(page as? JsonValue.JObject, "records")

    private fun event(record: JsonValue.JObject) = JsonFields.obj(JsonFields.field(record, "event"))!!
    private fun data(record: JsonValue.JObject) = JsonFields.obj(JsonFields.field(event(record), "data"))!!
    private fun type(record: JsonValue.JObject) = JsonFields.string(event(record), "type")
    private fun requestId(record: JsonValue.JObject) = JsonFields.string(data(record), "request_id")

    private fun assistant(records: List<JsonValue.JObject>, id: String): JsonValue.JObject =
        records.single { type(it) == "assistant/message" && requestId(it) == id }

    private fun message(record: JsonValue.JObject) = JsonFields.obj(JsonFields.field(data(record), "message"))!!
    private fun messageText(record: JsonValue.JObject): String =
        JsonFields.string(JsonFields.objects(message(record), "content").single(), "text")!!

    private fun streamText(record: JsonValue.JObject): String {
        val chunks = JsonFields.objects(data(record), "stream")
        val end = chunks.single { JsonFields.string(JsonFields.obj(JsonFields.field(it, "chunk")), "type") == "block-end" }
        return JsonFields.string(JsonFields.obj(JsonFields.field(end, "chunk")).let { JsonFields.obj(JsonFields.field(it, "block")) }, "text")!!
    }

    private fun parseReceipt(text: String): JsonValue.JObject = Json.parse(text) as JsonValue.JObject

    private fun sha256(bytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun TestScope.complete(bus: PhoneControlBus, task: String, id: String): JsonValue.JObject {
        bus.submit(access, task, request(id))
        runCurrent()
        val row = bus.receipt(access, task, id) as JsonValue.JObject
        assertEquals(ControlRequestState.COMPLETED.wire, JsonFields.string(row, "state"))
        return row
    }

    private fun assertArtifactBackedRecord(bus: PhoneControlBus, task: String, id: String, completeReceipt: JsonValue.JObject) {
        val record = assistant(records(bus.history(access, task)), id)
        assertTrue("整条 Session V3 记录必须严格落在 16,000 UTF-16 单元预算内", Json.canonical(record).length <= 16_000)
        val text = messageText(record)
        assertEquals("message 正文与 stream block-end 必须一致", text, streamText(record))
        val compact = parseReceipt(text)
        val reference = JsonFields.obj(JsonFields.field(compact, "resultArtifact"))!!
        assertEquals(id, JsonFields.string(compact, "request_id"))
        assertEquals(ControlRequestState.COMPLETED.wire, JsonFields.string(compact, "state"))
        assertEquals(true, JsonFields.bool(compact, "resultAvailable"))
        assertEquals("result-$id", JsonFields.string(reference, "artifactId"))
        assertEquals("artifacts/result-$id", JsonFields.string(reference, "path"))

        val stored = bus.artifact(access, task, "result-$id")
        val expectedBytes = Json.canonical(completeReceipt).toByteArray(Charsets.UTF_8)
        assertEquals("application/json", stored.mimeType)
        assertEquals(expectedBytes.size.toLong(), JsonFields.long(reference, "bytes"))
        assertArrayEquals(expectedBytes, stored.bytes)
        assertEquals(sha256(expectedBytes), stored.version)
        assertEquals(stored.version, JsonFields.string(reference, "version"))
    }

    @Test fun 边界以下保持内联且不创建结果产物() = runTest {
        val executor = Executor("x".repeat(4_800))
        val bus = PhoneControlBus(this, Ledger(), executor, phoneFacadeTestStrings, currentTimeMillis = { 1_700_000_000_000L })
        val task = bus.create(access)
        val id = "receipt-edge-0001"

        val complete = complete(bus, task, id)
        val record = assistant(records(bus.history(access, task)), id)
        val serializedLength = Json.canonical(record).length
        assertTrue("应贴近 16,000 边界保持内联，实际=$serializedLength", serializedLength in 15_000..16_000)
        assertEquals(Json.canonical(complete), messageText(record))
        assertEquals(Json.canonical(complete), streamText(record))
        assertFalse(messageText(record).contains("resultArtifact"))
        assertThrows(ControlRequestException::class.java) { bus.artifact(access, task, "result-$id") }
    }

    @Test fun 正文小于旧阈值但整条记录超过16000时转入同任务产物() = runTest {
        // 单份回执不足 6,000 字符（远小于旧 12,000 阈值），但同时写入 message/text-delta/block-end 三份后整条记录超过 16,000。
        val executor = Executor("a".repeat(5_500))
        val bus = PhoneControlBus(this, Ledger(), executor, phoneFacadeTestStrings, currentTimeMillis = { 1_700_000_000_000L })
        val task = bus.create(access)
        val id = "receipt-triple-001"

        val complete = complete(bus, task, id)
        assertTrue("原完整回执本身应小于 12,000", Json.canonical(complete).length < 12_000)
        assertArtifactBackedRecord(bus, task, id, complete)
    }

    @Test fun 超过预算写入同任务结果产物并保留完整字节摘要() = runTest {
        val executor = Executor("x".repeat(40_000))
        val bus = PhoneControlBus(this, Ledger(), executor, phoneFacadeTestStrings, currentTimeMillis = { 1_700_000_000_000L })
        val task = bus.create(access)
        val id = "receipt-over-0001"

        val complete = complete(bus, task, id)
        assertArtifactBackedRecord(bus, task, id, complete)
    }

    @Test fun 真实多份转义和已完成场景都独立落入同任务产物() = runTest {
        // 原始文本仅 2,000 字符，但双层 JSON 转义（\" / \\ / \n / \t）再写入三份会使整条记录突破 16,000。
        val executor = Executor("\\\"\n\t".repeat(500))
        val bus = PhoneControlBus(this, Ledger(), executor, phoneFacadeTestStrings, currentTimeMillis = { 1_700_000_000_000L })
        val task = bus.create(access)
        val ids = listOf("receipt-esc-0001", "receipt-esc-0002")

        val completes = ids.associateWith { complete(bus, task, it) }
        ids.forEach { id ->
            val complete = completes.getValue(id)
            assertTrue("原完整回执本身仍远小于 12,000", Json.canonical(complete).length < 12_000)
            assertArtifactBackedRecord(bus, task, id, complete)
        }
    }

    @Test fun 断线闭合仅发布最小终态且不创建结果产物() = runTest {
        val bus = PhoneControlBus(this, Ledger(), Executor("x".repeat(40_000)), phoneFacadeTestStrings, currentTimeMillis = { 1_700_000_000_000L })
        val task = bus.create(access)
        val id = "receipt-closed-001"

        bus.submit(access, task, request(id))
        bus.connectionClosed(access)
        runCurrent()

        val successor = access.copy(connectionId = "successor", connectionGeneration = 2)
        val record = assistant(records(bus.history(successor, task)), id)
        assertTrue(Json.canonical(record).length <= 16_000)
        val compact = parseReceipt(messageText(record))
        assertEquals(ControlRequestState.CANCELLED.wire, JsonFields.string(compact, "state"))
        assertEquals(false, JsonFields.bool(compact, "resultAvailable"))
        assertNull(JsonFields.field(compact, "resultArtifact"))
        assertThrows(ControlRequestException::class.java) { bus.artifact(successor, task, "result-$id") }
    }

    @Test fun 执行器普通产物使用保留result前缀时拒绝且不写入产物() = runTest {
        val id = "receipt-prefix-001"
        val executor = Executor(
            value = "ok",
            artifacts = listOf(ControlArtifact("result-$id", "application/json", "v1", "colliding".toByteArray())),
        )
        val bus = PhoneControlBus(this, Ledger(), executor, phoneFacadeTestStrings, currentTimeMillis = { 1_700_000_000_000L })
        val task = bus.create(access)

        bus.submit(access, task, request(id))
        runCurrent()

        val row = bus.receipt(access, task, id) as JsonValue.JObject
        assertEquals(ControlRequestState.OUTCOME_UNKNOWN.wire, JsonFields.string(row, "state"))
        assertEquals("CONTROL_ARTIFACT_INVALID", JsonFields.string(JsonFields.obj(JsonFields.field(row, "error")), "code"))
        assertThrows(ControlRequestException::class.java) { bus.artifact(access, task, "result-$id") }
    }

    @Test fun 同任务连续请求返回同名产物ID时第二次报冲突且保留首份产物与归属() = runTest {
        val firstId = "receipt-conflict-001"
        val secondId = "receipt-conflict-002"
        var firstContentAllowed = true
        val executor = object : PhoneControlExecutor {
            override fun requireCurrentAccess(access: ControlAccessBinding) = Unit
            override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
                context.beforeDispatch()
                return if (context.scope.requestId == firstId) {
                    PhoneControlResult(
                        ControlValue.Object(mapOf("value" to ControlValue.Text("first-result"))),
                        listOf(ControlArtifact("shared-artifact", "text/plain", "v1", "first-artifact-bytes".toByteArray())),
                        requireContentAccess = {
                            if (!firstContentAllowed) throw ControlRequestException("PUSH_DELIVERY_REVOKED")
                        },
                    )
                } else {
                    PhoneControlResult(
                        ControlValue.Object(mapOf("value" to ControlValue.Text("second-result"))),
                        listOf(
                            ControlArtifact("non-conflicting-sibling", "text/plain", "v2", "must-not-be-written".toByteArray()),
                            ControlArtifact("shared-artifact", "text/plain", "v2", "second-artifact-bytes".toByteArray()),
                        ),
                    )
                }
            }
            override fun cancel(scope: ControlRequestScope) = Unit
            override fun capabilities(access: ControlAccessBinding) = ControlValue.Object(emptyMap())
        }
        val bus = PhoneControlBus(this, Ledger(), executor, phoneFacadeTestStrings, currentTimeMillis = { 1_700_000_000_000L })
        val task = bus.create(access)

        bus.submit(access, task, request(firstId))
        runCurrent()
        val firstReceipt = bus.receipt(access, task, firstId) as JsonValue.JObject
        assertEquals(ControlRequestState.COMPLETED.wire, JsonFields.string(firstReceipt, "state"))
        val beforeConflict = bus.artifact(access, task, "shared-artifact")
        assertEquals("v1", beforeConflict.version)
        assertEquals("first-artifact-bytes", beforeConflict.bytes.decodeToString())

        bus.submit(access, task, request(secondId))
        runCurrent()
        val secondReceipt = bus.receipt(access, task, secondId) as JsonValue.JObject
        assertEquals(ControlRequestState.OUTCOME_UNKNOWN.wire, JsonFields.string(secondReceipt, "state"))
        assertEquals(false, JsonFields.bool(secondReceipt, "resultAvailable"))
        assertEquals("CONTROL_ARTIFACT_CONFLICT", JsonFields.string(JsonFields.obj(JsonFields.field(secondReceipt, "error")), "code"))

        // 写任何产物前必须整体拒绝：同批次中不重名的前置产物也不得落入 task.artifacts。
        val missingSibling = assertThrows(ControlRequestException::class.java) {
            bus.artifact(access, task, "non-conflicting-sibling")
        }
        assertEquals("CONTROL_ARTIFACT_UNAVAILABLE", missingSibling.code)

        // 首条请求的产物字节、版本与请求归属必须完整保留。
        assertEquals(firstReceipt, bus.receipt(access, task, firstId))
        val afterConflict = bus.artifact(access, task, "shared-artifact")
        assertEquals("v1", afterConflict.version)
        assertEquals("first-artifact-bytes", afterConflict.bytes.decodeToString())

        // 撤销首条请求的内容许可后该产物立即不可读，直接证明归属仍严格绑定在 firstId。
        firstContentAllowed = false
        val revokedFirst = assertThrows(ControlRequestException::class.java) {
            bus.artifact(access, task, "shared-artifact")
        }
        assertEquals("CONTROL_ARTIFACT_UNAVAILABLE", revokedFirst.code)
    }
}
