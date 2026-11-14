package com.openandroidintelligence.gateway.remote.facade

import com.openandroidintelligence.control.remote.ControlAccessBinding
import com.openandroidintelligence.control.remote.ControlRequestException
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.schema.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** 固定上游 Harness carrier；每条连接独占观察流，关闭只结束观察而不取消手机任务。 */
class PhoneRemoteRpcHostHandler(
    private val coroutineScope: CoroutineScope,
    private val router: PhoneVirtualHostRouter,
    private val authenticatedAccess: (RemoteRpcConnectionScope) -> ControlAccessBinding,
) : RemoteRpcHostHandler, AutoCloseable {
    private data class PendingEvent(val event: RemoteRpcEvent, val epoch: Long, val metadataOnly: Boolean, val content: Boolean = true)
    private class Connection(val scope: RemoteRpcConnectionScope) {
        val events = MutableSharedFlow<PendingEvent>(extraBufferCapacity = 128)
        val streams = ConcurrentHashMap<String, Job>()
        val closed = MutableStateFlow(false)
    }
    private val connections = ConcurrentHashMap<String, Connection>()
    private fun connection(scope: RemoteRpcConnectionScope): Connection {
        authenticatedAccess(scope)
        val connection = connections.getOrPut(scope.connectionId) { Connection(scope) }
        check(connection.scope == scope) { "REMOTE_CONNECTION_CHANGED" }
        return connection
    }
    override fun events(scope: RemoteRpcConnectionScope): Flow<RemoteRpcEvent> {
        val connection = connection(scope)
        val access = authenticatedAccess(scope)
        return channelFlow {
            val collector = launch { connection.events.collect { send(it) } }
            connection.closed.first { it }
            collector.cancel()
        }.buffer(Channel.RENDEZVOUS).map { pending ->
            // 复核放在无额外缓冲的最终出队处，慢消费者等待期间不获得旧正文的发送权。
            if (authenticatedAccess(scope) != access) throw ControlRequestException("REMOTE_AUTHORIZATION_CHANGED")
            if (pending.content) router.requireOutput(access, pending.epoch, pending.metadataOnly)
            else router.outputEpoch(access)
            pending.event
        }
    }

    override suspend fun invoke(scope: RemoteRpcConnectionScope, method: String, params: JsonValue.JObject): JsonValue {
        val connection = connection(scope)
        val access = authenticatedAccess(scope)
        val fields = params.fields.toMap()
        return when (method) {
            "harness.transport.describe" -> { exact(fields, emptySet()); router.route(access, method, params) }
            "harness.remote.call" -> {
                exact(fields, setOf("endpoint", "payload"))
                business { router.route(access, text(fields, "endpoint"), fields.getValue("payload")) }
            }
            "harness.remote.stream.open" -> {
                if (!fields.keys.all { it in setOf("streamId", "endpoint", "payload", "transferFrames") } ||
                    !fields.keys.containsAll(setOf("streamId", "endpoint", "payload")) || fields["transferFrames"] == JsonValue.JBool(true))
                    throw ControlRequestException("CONTROL_PARAMETERS_INVALID")
                val streamId = text(fields, "streamId")
                val epoch = router.outputEpoch(access)
                val metadataOnly = router.metadataStream(text(fields, "endpoint"), fields.getValue("payload"))
                lateinit var job: Job
                job = coroutineScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        router.stream(access, text(fields, "endpoint"), fields.getValue("payload")).collect { value ->
                            authenticatedAccess(scope)
                            connection.events.emit(PendingEvent(RemoteRpcEvent("harness.remote.frame", Json.of(mapOf("streamId" to streamId,
                                "hasValue" to true, "value" to value))), epoch, metadataOnly))
                        }
                    } catch (_: CancellationException) { throw CancellationException() }
                    catch (_: Exception) {
                        connection.events.emit(PendingEvent(RemoteRpcEvent("harness.remote.stream.closed", Json.of(mapOf("streamId" to streamId,
                            "reason" to "failed", "failure" to mapOf("code" to "CONTROL_STREAM_UNAVAILABLE", "message" to "手机任务观察已失效。", "details" to emptyMap<String, Any>())))), epoch, true, content = false))
                    } finally { connection.streams.remove(streamId, job) }
                }
                if (connection.streams.putIfAbsent(streamId, job) != null) { job.cancel(); throw ControlRequestException("CONTROL_STREAM_CONFLICT") }
                job.start()
                Json.of(emptyMap<String, Any>())
            }
            "harness.remote.stream.close" -> {
                exact(fields, setOf("streamId")); connection.streams.remove(text(fields, "streamId"))?.cancel()
                Json.of(emptyMap<String, Any>())
            }
            else -> throw ControlRequestException("METHOD_NOT_FOUND")
        }
    }

    fun disconnect(scope: RemoteRpcConnectionScope) {
        connections[scope.connectionId]?.takeIf { it.scope == scope }?.let { connection ->
            if (connections.remove(scope.connectionId, connection)) { connection.closed.value = true; connection.streams.values.forEach(Job::cancel); connection.streams.clear() }
        }
    }
    override fun close() { connections.values.forEach { connection -> connection.closed.value = true; connection.streams.values.forEach(Job::cancel) }; connections.clear() }
    private suspend fun business(call: suspend () -> JsonValue): JsonValue = try { Json.of(mapOf("ok" to true, "value" to call())) }
    catch (failure: ControlRequestException) { Json.of(mapOf("ok" to false, "error" to mapOf("code" to failure.code, "message" to "手机控制请求被拒绝。", "details" to emptyMap<String, Any>()))) }
    private fun exact(fields: Map<String, JsonValue>, keys: Set<String>) { if (fields.keys != keys) throw ControlRequestException("CONTROL_PARAMETERS_INVALID") }
    private fun text(fields: Map<String, JsonValue>, name: String) = (fields[name] as? JsonValue.JString)?.value?.takeIf { it.isNotEmpty() }
        ?: throw ControlRequestException("CONTROL_PARAMETERS_INVALID")
}
