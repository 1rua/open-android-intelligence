package com.openandroidintelligence.gateway.remote.facade

import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcConnectionScope
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneVirtualHostRouterTest {
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

    private class Executor(var current: Boolean = true) : PhoneControlExecutor {
        val digests = mutableListOf<String>()

        override fun requireCurrentAccess(access: ControlAccessBinding) {
            if (!current) throw ControlRequestException("REMOTE_AUTHORIZATION_CHANGED")
        }

        override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
            context.beforeDispatch()
            digests += context.parameterDigest
            return PhoneControlResult(ControlValue.Object(emptyMap()))
        }

        override fun cancel(scope: ControlRequestScope) = Unit

        override fun capabilities(access: ControlAccessBinding) = ControlValue.Object(emptyMap())
    }

    private fun access() = ControlAccessBinding(
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

    private fun businessText(requestId: String) = Json.canonical(Json.of(mapOf(
        "request_id" to requestId,
        "op" to "plugin.invoke",
        "args" to mapOf(
            "pluginId" to "org.example.reader",
            "capabilityId" to "org.example.reader.read@1.0.0",
            "method" to "read",
            "params" to emptyMap<String, Any>(),
        ),
    )))

    private fun prompt(
        requestId: String,
        clientTimeZone: Any? = "Asia/Shanghai",
        extra: Map<String, Any?> = emptyMap(),
    ): JsonValue.JObject {
        val request = linkedMapOf<String, Any?>(
            "sessionId" to "control-bus",
            "requestId" to "transport-request-0001",
            "mode" to "queue",
            "content" to listOf(mapOf("type" to "text", "text" to businessText(requestId))),
            "clientTimeZone" to clientTimeZone,
        )
        request.putAll(extra)
        return Json.of(mapOf("args" to mapOf("request" to request))) as JsonValue.JObject
    }

    private suspend fun failureCode(block: suspend () -> Unit): String? = try {
        block()
        null
    } catch (failure: ControlRequestException) {
        failure.code
    }

    private fun workspaceCreateCarrier(path: Any? = "phone-capabilities"): JsonValue.JObject =
        Json.of(mapOf("args" to mapOf("request" to mapOf("path" to path)))) as JsonValue.JObject

    private fun workspace(result: JsonValue): JsonValue.JObject {
        val root = result as JsonValue.JObject
        assertEquals(setOf("created", "workspace"), root.fields.map { it.first }.toSet())
        assertEquals(JsonValue.JBool(false), JsonFields.field(root, "created"))
        return JsonFields.obj(JsonFields.field(root, "workspace"))!!
    }

    private fun assertWorkspace(workspace: JsonValue.JObject, expectedSessionIds: List<String>) {
        assertEquals("phone-capabilities", JsonFields.string(workspace, "workspaceId"))
        assertEquals("phone-capabilities", JsonFields.string(workspace, "path"))
        assertEquals("test-phone-workspace", JsonFields.string(workspace, "title"))
        assertEquals(expectedSessionIds, JsonFields.strings(workspace, "sessionIds"))
        java.time.Instant.parse(JsonFields.string(workspace, "createdAt")!!)
        java.time.Instant.parse(JsonFields.string(workspace, "updatedAt")!!)
    }

    @Test fun 官方carrier打开已有工作区返回当前记录且无副作用() = runTest {
        val ledger = Ledger()
        val executor = Executor()
        val bus = PhoneControlBus(this, ledger, executor, phoneFacadeTestStrings)
        bus.create(access())
        runCurrent()
        val sessionsBefore = bus.sessions(access())
        val sessionIds = sessionsBefore.map { JsonFields.string(it as JsonValue.JObject, "sessionId")!! }
        val ledgerBefore = ledger.rows.toMap()
        val changesBefore = bus.changes.value
        val router = PhoneVirtualHostRouter(bus)
        val handler = PhoneRemoteRpcHostHandler(this, router) { access() }
        val scope = RemoteRpcConnectionScope("https://remote.example", "account", "phone", "controller", "connection", 1)
        val payload = Json.of(mapOf("args" to mapOf("request" to mapOf("path" to "phone-capabilities"))))
        val response = handler.invoke(scope, "harness.remote.call", Json.of(mapOf(
            "endpoint" to "workspace/create",
            "payload" to payload,
        )) as JsonValue.JObject) as JsonValue.JObject

        assertEquals(JsonValue.JBool(true), JsonFields.field(response, "ok"))
        val workspace = workspace(JsonFields.field(response, "value")!!)
        assertWorkspace(workspace, sessionIds)
        assertEquals(sessionsBefore, bus.sessions(access()))
        assertEquals(ledgerBefore, ledger.rows)
        assertEquals(changesBefore, bus.changes.value)
        assertTrue(executor.digests.isEmpty())
        handler.close()
    }

    @Test fun 工作区打开路径错误返回稳定未找到且不创建内容() = runTest {
        val ledger = Ledger()
        val bus = PhoneControlBus(this, ledger, Executor(), phoneFacadeTestStrings)
        val router = PhoneVirtualHostRouter(bus)
        val before = bus.sessions(access())

        listOf("", "/phone-capabilities", "phone-capabilities/").forEach { path ->
            assertEquals("CONTROL_WORKSPACE_NOT_FOUND", failureCode {
                router.route(access(), "workspace/create", workspaceCreateCarrier(path))
            })
        }
        assertEquals(before, bus.sessions(access()))
        assertTrue(ledger.rows.isEmpty())
    }

    @Test fun 工作区打开字段或授权无效时拒绝且没有副作用() = runTest {
        val ledger = Ledger()
        val executor = Executor()
        val bus = PhoneControlBus(this, ledger, executor, phoneFacadeTestStrings)
        val router = PhoneVirtualHostRouter(bus)
        val baseline = workspaceCreateCarrier()

        listOf(
            Json.of(mapOf("args" to mapOf("request" to emptyMap<String, Any>()))),
            Json.of(mapOf("args" to mapOf("request" to mapOf("path" to "phone-capabilities", "workspaceId" to "phone-capabilities")))),
            Json.of(mapOf("args" to mapOf("request" to mapOf("path" to "phone-capabilities")), "extra" to true)),
            Json.of(mapOf("request" to mapOf("path" to "phone-capabilities"))),
        ).forEach { input ->
            assertEquals("CONTROL_PARAMETERS_INVALID", failureCode { router.route(access(), "workspace/create", input) })
        }
        assertTrue(ledger.rows.isEmpty())

        executor.current = false
        assertEquals("REMOTE_AUTHORIZATION_CHANGED", failureCode {
            router.route(access(), "workspace/create", baseline)
        })
        assertTrue(executor.digests.isEmpty())
    }

    @Test fun 官方时区元数据不参与业务幂等或参数摘要() = runTest {
        val ledger = Ledger()
        val executor = Executor()
        val router = PhoneVirtualHostRouter(PhoneControlBus(this, ledger, executor, phoneFacadeTestStrings))
        val requestId = "timezone-request-0001"
        val expectedDigest = ControlRequestParser.parse(businessText(requestId)).parameterDigest

        val first = router.route(access(), "session/prompt", prompt(requestId, "Asia/Shanghai"))
        assertEquals(JsonValue.JBool(true), (first as JsonValue.JObject).fields.toMap()["accepted"])
        runCurrent()
        assertEquals(listOf(expectedDigest), executor.digests)

        val row = ledger.rows.values.single { it.requestId == requestId }
        assertEquals(expectedDigest, row.parameterDigest)
        assertFalse(row.toString().contains("Asia/Shanghai"))

        router.route(access(), "session/prompt", prompt(requestId, "UTC"))
        runCurrent()
        assertEquals(1, executor.digests.size)
        assertEquals(row, ledger.rows.values.single { it.requestId == requestId })
    }

    @Test fun 时区不能绕过当前授权且非法元数据仍在执行前拒绝() = runTest {
        val executor = Executor(current = false)
        val router = PhoneVirtualHostRouter(PhoneControlBus(this, Ledger(), executor, phoneFacadeTestStrings))

        assertEquals("REMOTE_AUTHORIZATION_CHANGED", failureCode {
            router.route(access(), "session/prompt", prompt("timezone-denied-0001"))
        })
        assertTrue(executor.digests.isEmpty())

        executor.current = true
        assertEquals("CONTROL_PARAMETERS_INVALID", failureCode {
            router.route(access(), "session/prompt", prompt("timezone-invalid-001", "GMT"))
        })
        assertEquals("CONTROL_PARAMETERS_INVALID", failureCode {
            router.route(access(), "session/prompt", prompt("timezone-invalid-002", 8))
        })
        assertEquals("CONTROL_PARAMETERS_INVALID", failureCode {
            router.route(access(), "session/prompt", prompt("timezone-unknown-001", extra = mapOf("metadata" to true)))
        })
        assertTrue(executor.digests.isEmpty())
    }
}
