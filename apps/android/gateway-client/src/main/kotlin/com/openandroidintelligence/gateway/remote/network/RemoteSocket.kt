package com.openandroidintelligence.gateway.remote.network

import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException
import com.openandroidintelligence.gateway.remote.protocol.RemoteControlFrame
import com.openandroidintelligence.gateway.remote.protocol.RemoteControlCodec
import com.openandroidintelligence.gateway.remote.protocol.RemoteFrameLimits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okio.ByteString

/** WebSocket 回调只投递复制后的帧；解析和状态变更由单个协程顺序消费。 */
internal class RemoteSocket(
    http: OkHttpClient,
    url: String,
    private val relayData: Boolean = false,
    private val onFailure: (String) -> Unit,
) {
    val frames = Channel<RemoteControlFrame>(Channel.UNLIMITED)
    private val opened = CompletableDeferred<Unit>()
    @Volatile private var closed = false
    @Volatile var limits = RemoteFrameLimits()
    private val socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { if (closed) webSocket.cancel() else opened.complete(Unit) }
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val bytes = text.encodeToByteArray()
                val frame = if (relayData) RemoteControlCodec.decodeRelayData(bytes, limits) else RemoteControlCodec.decode(bytes, limits)
                if (!closed && !frames.trySend(frame).isSuccess) fail("TRANSPORT_CLOSED")
            } catch (_: Exception) { fail("INVALID_MESSAGE") }
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { fail("INVALID_MESSAGE") }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { fail("TRANSPORT_CLOSED") }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail(when (code) {
            4002 -> "AUTH_INVALID"; 4003 -> "CONNECTION_REPLACED"; 4004 -> "DEVICE_REVOKED"; else -> "TRANSPORT_CLOSED"
        }) }
    })
    suspend fun awaitOpen() = opened.await()
    fun send(frame: RemoteControlFrame) {
        if (closed) throw RemoteRpcException("TRANSPORT_CLOSED")
        val bytes = if (relayData) RemoteControlCodec.encodeRelayData(frame, limits) else RemoteControlCodec.encode(frame, limits)
        if (!socket.send(bytes.toString(Charsets.UTF_8))) { fail("TRANSPORT_CLOSED"); throw RemoteRpcException("TRANSPORT_CLOSED") }
    }
    fun close() { if (closed) return; closed = true; opened.completeExceptionally(RemoteRpcException("CLIENT_CLOSED")); frames.close(); socket.cancel() }
    private fun fail(code: String) {
        if (closed) return
        closed = true; opened.completeExceptionally(RemoteRpcException(code)); frames.close(RemoteRpcException(code)); socket.cancel(); onFailure(code)
    }
}
