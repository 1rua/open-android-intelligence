package com.openandroidintelligence.gateway.remote.facade

import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.gateway.schema.Json
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
