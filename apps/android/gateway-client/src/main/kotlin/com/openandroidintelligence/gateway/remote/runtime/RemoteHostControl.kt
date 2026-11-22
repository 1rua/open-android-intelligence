package com.openandroidintelligence.gateway.remote.runtime

import android.util.Log
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityCorruptedException
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityLockedException
import com.openandroidintelligence.gateway.remote.auth.safeRoleErrorCode
import com.openandroidintelligence.gateway.remote.identity.RemoteRoleCredential
import com.openandroidintelligence.gateway.remote.network.RemoteSignalService
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** 诊断仅记录故障阶段和固定错误码。 */
enum class RemoteHostFailureStage { REGISTRATION, CREDENTIAL, CONTROL }
data class RemoteHostFailure(val stage: RemoteHostFailureStage, val code: String)

/** 一个已获授权的 Host 角色拥有一个保持任务。每轮从凭据仓库重新取票，旧隧道随旧连接关闭。 */
internal class RemoteHostControl(
    scope: CoroutineScope,
    private val credential: suspend (rejectedToken: String?) -> RemoteRoleCredential,
    private val connect: (RemoteRoleCredential, (String) -> Unit) -> RemoteSignalService,
    private val onTerminal: (RemoteHostFailure, Boolean) -> Unit,
    private val publish: (RemoteHostConnectionState, RemoteHostFailure?) -> Unit,
) {
    private val retryNow = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var socket: RemoteSignalService? = null
    private var stopped = false
    private val job = scope.launch(start = CoroutineStart.LAZY) { maintain() }

    fun start() { job.start() }
    fun wake() { retryNow.trySend(Unit) }
    fun stop() {
        val closing = synchronized(lock) {
            stopped = true
            socket.also { socket = null }
        }
        job.cancel()
        closing?.close()
    }

    private suspend fun maintain() {
        var retryMs = 1_000L
        var rejectedToken: String? = null
        var refreshedAfterRejection = false
        while (currentCoroutineContext().isActive) {
            var service: RemoteSignalService? = null
            var usedToken: String? = null
            var stage = RemoteHostFailureStage.CREDENTIAL
            val failure = try {
                publish(RemoteHostConnectionState.CONNECTING, null)
                val ticket = credential(rejectedToken)
                usedToken = ticket.tokens.accessToken
                if (rejectedToken != null) refreshedAfterRejection = true
                rejectedToken = null
                currentCoroutineContext().ensureActive()
                stage = RemoteHostFailureStage.CONTROL
                val ended = CompletableDeferred<String>()
                val current = synchronized(lock) {
                    if (stopped) return
                    connect(ticket) { ended.complete(it) }.also { socket = it }
                }
                service = current
                current.startHost()
                if (current.connected) {
                    publish(RemoteHostConnectionState.ONLINE, null)
                    retryMs = 1_000L
                    refreshedAfterRejection = false
                }
                RemoteHostFailure(stage, safeRoleErrorCode(ended.await(), "CONTROL_CONNECTION_FAILED"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val code = when (error) {
                    is RemoteIdentityLockedException -> "REMOTE_IDENTITY_LOCKED"
                    is RemoteIdentityCorruptedException -> "REMOTE_IDENTITY_CORRUPTED"
                    is RemoteRpcException -> safeRoleErrorCode(error.code, "CONTROL_CONNECTION_FAILED")
                    else -> "CONTROL_CONNECTION_FAILED"
                }
                RemoteHostFailure(stage, code)
            } finally {
                synchronized(lock) { if (socket === service) socket = null }
                try { service?.close() } catch (_: RemoteRpcException) { Log.w(TAG, "CONNECTION_CLEANUP_FAILED") }
            }
            // 服务端可先于本机时钟判票失效。只轮换本轮使用过的票一次，连续拒绝交回本机登录处理。
            if (stage == RemoteHostFailureStage.CONTROL && failure.code in setOf("TOKEN_EXPIRED", "AUTH_INVALID") && !refreshedAfterRejection) {
                rejectedToken = usedToken
            } else if (failure.code !in retryableCodes) {
                try { onTerminal(failure, failure.code in authorizationStops) } catch (_: Exception) {
                    Log.w(TAG, "HOST_STOP_PERSISTENCE_FAILED")
                }
                publish(RemoteHostConnectionState.FAILED, failure)
                return
            }
            publish(RemoteHostConnectionState.FAILED, failure)
            withTimeoutOrNull(retryMs) { retryNow.receive() }
            retryMs = (retryMs * 2).coerceAtMost(30_000L)
        }
    }

    companion object {
        const val TAG = "OAI-Virtual-Host"
        private val authorizationStops = setOf("CONNECTION_REPLACED", "DEVICE_REVOKED", "TOKEN_REUSED", "TOKEN_EXPIRED", "AUTH_INVALID")
        private val retryableCodes = setOf("TRANSPORT_CLOSED", "CONNECTION_FAILED", "HEARTBEAT_TIMEOUT", "HELLO_TIMEOUT",
            "SERVER_SHUTDOWN", "RATE_LIMITED", "INTERNAL_ERROR", "REMOTE_REFRESH_FAILED", "REMOTE_REFRESH_CONFLICT", "REMOTE_IDENTITY_LOCKED")
    }
}
