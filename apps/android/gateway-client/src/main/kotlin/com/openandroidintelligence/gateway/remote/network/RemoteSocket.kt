package com.openandroidintelligence.gateway.remote.network

import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException
import com.openandroidintelligence.gateway.remote.protocol.RemoteControlFrame
import com.openandroidintelligence.gateway.remote.protocol.RemoteControlCodec
import com.openandroidintelligence.gateway.remote.protocol.RemoteFrameLimits
import java.util.concurrent.atomic.AtomicReference
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
    val frames = Channel<RemoteControlFrame>(128)
    private val opened = CompletableDeferred<Unit>()
    private val terminal = AtomicReference<String?>(null)
    @Volatile var limits = RemoteFrameLimits()
    private val socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { if (terminal.get() != null) webSocket.cancel() else opened.complete(Unit) }
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val bytes = text.encodeToByteArray()
                val frame = if (relayData) RemoteControlCodec.decodeRelayData(bytes, limits) else RemoteControlCodec.decode(bytes, limits)
                if (terminal.get() == null && !frames.trySend(frame).isSuccess) fail("TRANSPORT_CLOSED", webSocket::cancel)
            } catch (_: Exception) { fail("INVALID_MESSAGE", webSocket::cancel) }
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { fail("INVALID_MESSAGE", webSocket::cancel) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { fail("TRANSPORT_CLOSED", webSocket::cancel) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // 先公布服务端的原因。确认关闭后并发心跳可能立即发送失败，必须沿用同一终态。
            fail(remoteSocketCloseCode(code, reason)) { webSocket.close(code, null) }
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail(remoteSocketCloseCode(code, reason), webSocket::cancel) }
    })
    suspend fun awaitOpen() = opened.await()
    fun send(frame: RemoteControlFrame) {
        terminal.get()?.let { throw RemoteRpcException(it) }
        val bytes = if (relayData) RemoteControlCodec.encodeRelayData(frame, limits) else RemoteControlCodec.encode(frame, limits)
        if (!socket.send(bytes.toString(Charsets.UTF_8))) {
            fail("TRANSPORT_CLOSED", socket::cancel)
            throw RemoteRpcException(requireNotNull(terminal.get()))
        }
    }
    fun close() {
        if (!terminal.compareAndSet(null, "CLIENT_CLOSED")) return
        opened.completeExceptionally(RemoteRpcException("CLIENT_CLOSED"))
        frames.close()
        socket.cancel()
    }
    private fun fail(code: String, closeTransport: () -> Unit) {
        if (!terminal.compareAndSet(null, code)) return
        val failure = RemoteRpcException(code)
        opened.completeExceptionally(failure)
        frames.close(failure)
        try { closeTransport() } finally { onFailure(code) }
    }
}

/** 固定上游 close-semantics.ts 同时检查关闭码和原因，未知原因文本不进入日志。 */
internal fun remoteSocketCloseCode(code: Int, reason: String): String = when {
    code == 4003 -> "CONNECTION_REPLACED"
    code == 4004 -> "DEVICE_REVOKED"
    code == 4002 -> "AUTH_INVALID"
    code in setOf(4009, 4001, 1013) && reason.trim() == "server shutdown" -> "SERVER_SHUTDOWN"
    code == 4009 && reason.trim() == "heartbeat timeout" || code == 4001 && reason.trim() == "CONNECTION_FAILED" -> "HEARTBEAT_TIMEOUT"
    code == 4001 -> com.openandroidintelligence.gateway.remote.auth.safeRoleErrorCode(reason.trim(), "TRANSPORT_CLOSED")
    else -> "TRANSPORT_CLOSED"
}
