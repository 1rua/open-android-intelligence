package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcChannel
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 固定上游的三个 transfer carrier 使用同一分块格式，但 RPC 名称保持各自领域。 */
internal class RemoteCarrierWire(private val rpc: RemoteRpcChannel, private val prefix: String) {
    suspend fun call(envelope: JsonValue.JObject, transfer: Boolean = false, timeoutMs: Long = 30_000): JsonValue {
        val bytes = Json.canonical(envelope).toByteArray(Charsets.UTF_8)
        if (!transfer && bytes.size <= DIRECT_BYTES) return rpc.invoke("$prefix.call", envelope, timeoutMs = timeoutMs)
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw RemoteSessionException("REMOTE_TRANSFER_TOO_LARGE")
        val id = UUID.randomUUID().toString()
        var dispatched = false
        try {
            dispatched = true
            rpc.invoke("$prefix.transfer.open", obj("transferId" to id, "totalBytes" to bytes.size,
                "totalChunks" to chunks(bytes.size)))
            repeat(chunks(bytes.size)) { index ->
                val from = index * CHUNK_BYTES
                rpc.invoke("$prefix.transfer.chunk", obj("transferId" to id, "index" to index,
                    "data" to Base64.getEncoder().encodeToString(bytes.copyOfRange(from, minOf(from + CHUNK_BYTES, bytes.size)))))
            }
            val result = rpc.invoke("$prefix.transfer.commit", obj("transferId" to id), timeoutMs = timeoutMs)
            if (result.text("kind") == "inline") return result.at("response") ?: JsonValue.JNull
            if (result.text("transferId") != id) throw RemoteSessionException("REMOTE_INVALID_TRANSFER")
            return readTransfer(result)
        } finally {
            bytes.fill(0)
            if (dispatched) withContext(NonCancellable) {
                runCatching { rpc.invoke("$prefix.transfer.close", obj("transferId" to id), timeoutMs = 5_000) }
            }
        }
    }

    suspend fun readTransfer(descriptor: JsonValue): JsonValue {
        val id = descriptor.requiredText("transferId")
        val total = descriptor.number("totalBytes")?.takeIf { it in 1..MAX_BYTES.toLong() }?.toInt()
            ?: throw RemoteSessionException("REMOTE_INVALID_TRANSFER")
        if (descriptor.text("kind") != "chunked" || descriptor.number("totalChunks") != chunks(total).toLong()) {
            throw RemoteSessionException("REMOTE_INVALID_TRANSFER")
        }
        val response = ByteArray(total)
        try {
            repeat(chunks(total)) { index ->
                val part = rpc.invoke("$prefix.transfer.read", obj("transferId" to id, "index" to index))
                if (part.text("transferId") != id || part.number("index") != index.toLong()) {
                    throw RemoteSessionException("REMOTE_INVALID_TRANSFER")
                }
                val encoded = part.requiredText("data")
                if (encoded.length > ((CHUNK_BYTES + 2) / 3) * 4) throw RemoteSessionException("REMOTE_INVALID_TRANSFER")
                val data = try { Base64.getDecoder().decode(encoded) }
                catch (_: IllegalArgumentException) { throw RemoteSessionException("REMOTE_INVALID_TRANSFER") }
                try {
                    if (data.size != minOf(CHUNK_BYTES, total - index * CHUNK_BYTES) || Base64.getEncoder().encodeToString(data) != encoded) {
                        throw RemoteSessionException("REMOTE_INVALID_TRANSFER")
                    }
                    data.copyInto(response, index * CHUNK_BYTES)
                } finally { data.fill(0) }
            }
            return RemoteWireJson.decode(response)
        } finally { response.fill(0) }
    }

    /** 先注册事件再打开流；取消和换代都以同一个旧通道关闭原订阅。 */
    fun stream(open: (String) -> JsonValue.JObject, valueField: String = "frame",
        acceptsSessionId: String? = null): Flow<JsonValue> = channelFlow {
        val streamId = UUID.randomUUID().toString()
        val listener = launch(start = CoroutineStart.UNDISPATCHED) {
            rpc.events.collect { event ->
                val data = event.data
                val matches = data.text("streamId") == streamId ||
                    (acceptsSessionId != null && data.text("streamId") == "session:$acceptsSessionId")
                if (!matches) return@collect
                when (event.event) {
                    "$prefix.frame" -> {
                        val value = data.at(valueField) ?: data.at("transfer")?.let { descriptor ->
                            try { readTransfer(descriptor) } finally {
                                withContext(NonCancellable) { runCatching {
                                    rpc.invoke("$prefix.transfer.close", obj("transferId" to descriptor.requiredText("transferId")), timeoutMs = 5_000)
                                } }
                            }
                        } ?: throw RemoteSessionException("REMOTE_INVALID_STREAM_FRAME")
                        send(value)
                    }
                    "$prefix.stream.closed" -> {
                        val reason = data.requiredText("reason")
                        if (reason == "completed" || reason == "cancelled") {
                            send(obj("type" to "remote-stream-closed", "reason" to reason))
                            close()
                        } else close(RemoteSessionException(data.at("failure")?.text("code") ?: "REMOTE_STREAM_CLOSED"))
                    }
                }
            }
            close(RemoteSessionException("REMOTE_CHANNEL_CLOSED"))
        }
        try {
            rpc.invoke("$prefix.stream.open", open(streamId))
            send(obj("type" to "remote-stream-ready"))
            awaitClose { listener.cancel() }
        } finally {
            listener.cancel()
            withContext(NonCancellable) { runCatching {
                rpc.invoke("$prefix.stream.close", obj("streamId" to streamId), timeoutMs = 5_000)
            } }
        }
    }

    companion object {
        private const val DIRECT_BYTES = 2 * 1024 * 1024
        private const val CHUNK_BYTES = 512 * 1024
        private const val MAX_BYTES = 288 * 1024 * 1024
        private fun chunks(bytes: Int) = (bytes + CHUNK_BYTES - 1) / CHUNK_BYTES
    }
}
