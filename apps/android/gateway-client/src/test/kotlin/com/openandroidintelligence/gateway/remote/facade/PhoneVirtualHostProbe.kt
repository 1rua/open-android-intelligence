package com.openandroidintelligence.gateway.remote.facade

import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcConnectionScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** 仅测试进程的 stdin/stdout 桥：运行真实门面和总线，平台采集器由隔离提供者替代。 */
object PhoneVirtualHostProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val rows = linkedMapOf<String, ControlRequestMetadata>()
        val ledger = object : ControlMetadataLedger {
            @Synchronized override fun <T> transaction(change: (MutableMap<String, ControlRequestMetadata>) -> T): T {
                val next = rows.toMutableMap(); val result = change(next); rows.clear(); rows.putAll(next); return result
            }
        }
        val executor = object : PhoneControlExecutor {
            override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
                context.beforeDispatch()
                return PhoneControlResult(ControlValue.Object(mapOf("snapshotId" to ControlValue.Text("probe-snapshot"),
                    "nodes" to ControlValue.Array(emptyList()))), listOf(ControlArtifact("probe-artifact", "application/json", "probe-version", "手机隔离探针".toByteArray())))
            }
            override fun cancel(scope: ControlRequestScope) {}
            override fun capabilities(access: ControlAccessBinding) = ControlValue.Object(emptyMap())
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val router = PhoneVirtualHostRouter(PhoneControlBus(scope, ledger, executor))
        val access = ControlAccessBinding(RemoteControlPrincipal(ControlOrigin.fromCanonical("https://probe.invalid"), "isolated-account", "probe-controller",
            "probe-phone", "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64)), 1, "probe-connection", 1)
        val rpcScope = RemoteRpcConnectionScope("https://probe.invalid", "isolated-account", "probe-phone", "probe-controller", "probe-connection", 1)
        val handler = PhoneRemoteRpcHostHandler(scope, router) { access }
        val outputLock = Any()
        fun write(value: JsonValue) = synchronized(outputLock) { println(Json.canonical(value)) }
        val events = scope.launch(start = CoroutineStart.UNDISPATCHED) { handler.events(rpcScope).collect {
            write(Json.of(mapOf("event" to it.event, "data" to it.data)))
        } }
        try {
            generateSequence(::readLine).forEach { line ->
                val request = JsonFields.obj(Json.parse(line)) ?: error("PROBE_INVALID")
                val method = JsonFields.string(request, "method") ?: error("PROBE_INVALID")
                val params = JsonFields.field(request, "params") ?: Json.of(emptyMap<String, Any>())
                val result = try {
                    handler.invoke(rpcScope, method, params as JsonValue.JObject)
                } catch (failure: Exception) { Json.of(mapOf("ok" to false, "error" to mapOf("code" to ((failure as? ControlRequestException)?.code ?: "PROBE_FAILURE")))) }
                write(Json.of(mapOf("response" to result)))
            }
        } finally { events.cancel(); handler.close(); scope.cancel() }
    }
}
