package com.openandroidintelligence.gateway.remote.rpc

import com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 已认证的字节通道；每个实例只能拥有一个 Noise/连接代际。 */
interface RemoteMessageTransport {
    suspend fun send(bytes: ByteArray)
    fun onMessage(handler: (ByteArray) -> Unit): AutoCloseable
    fun onClose(handler: (String) -> Unit): AutoCloseable
    suspend fun close()
}

/** RPC 等待先登记再发送；关闭同时终止待响应和所有观察者，绝不迁移到其他目标。 */
class RemoteRpcSession(
    override val scope: RemoteRpcConnectionScope,
    override val capabilities: Set<String>,
    private val transport: RemoteMessageTransport,
    parentScope: CoroutineScope,
    private val host: RemoteRpcHostHandler? = null,
) : RemoteRpcChannel {
    private val lock = Any()
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val worker = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val pending = mutableMapOf<String, CompletableDeferred<JsonValue>>()
    private val subscribers = mutableSetOf<Channel<RemoteRpcEvent>>()
    private val requests = mutableSetOf<String>()
    private var closedCode: String? = null
    private var lastEventSeq: Long? = null
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val messageSubscription = transport.onMessage { bytes ->
        if (!incoming.trySend(bytes.copyOf()).isSuccess) terminate("TRANSPORT_CLOSED")
    }
    private val closeSubscription = transport.onClose(::terminate)
    override val events: Flow<RemoteRpcEvent> = flow {
        val channel = Channel<RemoteRpcEvent>(Channel.UNLIMITED)
        synchronized(lock) { closedCode?.let { throw RemoteRpcException(it) }; subscribers += channel }
        try { for (event in channel) emit(event) } finally { synchronized(lock) { subscribers -= channel }; channel.cancel() }
    }
    init {
        worker.launch {
            try { for (bytes in incoming) handle(bytes) }
            catch (_: CancellationException) { throw CancellationException() }
            catch (_: Exception) { terminate("INVALID_MESSAGE") }
        }
        if (host != null) worker.launch {
            try { host.events(scope).collect { event -> sendEnvelope("event", obj("event" to event.event, "data" to event.data)) } }
            catch (_: Exception) { terminate("HOST_EVENT_FAILED") }
        }
    }
    override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue {
        require(method.isNotBlank() && requestId.isNotBlank() && timeoutMs > 0)
        val result = CompletableDeferred<JsonValue>()
        synchronized(lock) {
            closedCode?.let { throw RemoteRpcException(it) }
            if (pending.putIfAbsent(requestId, result) != null) throw RemoteRpcException("RPC_REQUEST_CONFLICT")
        }
        val sending = worker.launch {
            try { sendEnvelope("rpc.request", obj("method" to method, "params" to params), requestId) }
            catch (_: Exception) { terminate("TRANSPORT_CLOSED") }
        }
        return try {
            withTimeout(timeoutMs) { result.await() }
        } catch (_: TimeoutCancellationException) { throw RemoteRpcException("RPC_TIMEOUT") }
        finally { synchronized(lock) { pending.remove(requestId, result) }; sending.cancel() }
    }
    private suspend fun handle(bytes: ByteArray) {
        val message = RemoteWireJson.decode(bytes).objectValue()
        if (message.longValue("v") != 1L || message.longValue("timestamp") <= 0) throw RemoteRpcException("INVALID_MESSAGE")
        val payload = message.value("payload").objectValue()
        when (message.requiredText("type")) {
            "rpc.response" -> synchronized(lock) { pending.remove(payload.requiredText("requestId")) }?.complete(payload.value("result"))
            "rpc.error" -> {
                val code = payload.requiredText("code").takeIf { Regex("[A-Z0-9_]{1,128}").matches(it) } ?: "RPC_FAILED"
                synchronized(lock) { pending.remove(payload.requiredText("requestId")) }?.completeExceptionally(RemoteRpcException(code))
            }
            "event" -> {
                val seq = (payload.fields.firstOrNull { it.first == "seq" }?.second as? JsonValue.JNumber)?.raw?.toLongOrNull()
                synchronized(lock) {
                    if (closedCode != null) return
                    if (seq != null) {
                        if (seq < 0 || lastEventSeq?.let { seq <= it } == true) throw RemoteRpcException("EVENT_SEQUENCE_INVALID")
                        lastEventSeq = seq
                    }
                    val event = RemoteRpcEvent(payload.requiredText("event"), payload.value("data"))
                    subscribers.forEach { if (!it.trySend(event).isSuccess) throw RemoteRpcException("EVENT_DELIVERY_FAILED") }
                }
            }
            "rpc.request" -> {
                val id = message.requiredText("id")
                synchronized(lock) {
                    if (closedCode != null) return
                    if (!requests.add(id)) throw RemoteRpcException("RPC_REQUEST_REPLAYED")
                }
                worker.launch {
                    try {
                        val handler = host ?: throw RemoteRpcException("METHOD_NOT_ALLOWED")
                        val result = handler.invoke(scope, payload.requiredText("method"), payload.value("params").objectValue())
                        sendEnvelope("rpc.response", obj("requestId" to id, "result" to result))
                    } catch (failure: RemoteRpcException) {
                        sendEnvelope("rpc.error", obj("requestId" to id, "code" to failure.code, "message" to failure.code))
                    } catch (_: Exception) {
                        sendEnvelope("rpc.error", obj("requestId" to id, "code" to "RPC_FAILED", "message" to "RPC_FAILED"))
                    }
                }
            }
            else -> throw RemoteRpcException("INVALID_MESSAGE")
        }
    }
    private suspend fun sendEnvelope(type: String, payload: JsonValue.JObject, id: String = UUID.randomUUID().toString()) {
        synchronized(lock) { closedCode?.let { throw RemoteRpcException(it) } }
        transport.send(Json.canonical(obj("v" to 1, "id" to id, "type" to type,
            "timestamp" to System.currentTimeMillis(), "payload" to payload)).encodeToByteArray())
    }
    private fun terminate(code: String) {
        val pendingCopy: List<CompletableDeferred<JsonValue>>
        val subscriberCopy: List<Channel<RemoteRpcEvent>>
        synchronized(lock) {
            if (closedCode != null) return
            closedCode = code; pendingCopy = pending.values.toList(); pending.clear()
            subscriberCopy = subscribers.toList(); subscribers.clear(); requests.clear()
        }
        val failure = RemoteRpcException(code)
        pendingCopy.forEach { it.completeExceptionally(failure) }; subscriberCopy.forEach { it.close(failure) }
        incoming.close(); lifetime.cancel()
    }
    override suspend fun close() {
        terminate("CLIENT_CLOSED"); messageSubscription.close(); closeSubscription.close(); transport.close()
    }
}
