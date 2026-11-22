package com.openandroidintelligence.mobile

import android.content.Context
import com.openandroidintelligence.gateway.remote.auth.RemoteLoginResult
import com.openandroidintelligence.gateway.remote.authproduction.RemoteActiveAccount
import com.openandroidintelligence.gateway.remote.network.AuthenticatedRemotePeer
import com.openandroidintelligence.gateway.remote.network.RemoteHostDevice
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcChannel
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcHostHandler
import com.openandroidintelligence.gateway.remote.runtime.RemoteAccountRuntime
import com.openandroidintelligence.gateway.remote.runtime.RemoteAccountConnectionState
import com.openandroidintelligence.gateway.remote.runtime.RemoteHostConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow

private val NO_HOST_STANDBY = MutableStateFlow(false)

/** App 只需要账号生命周期和真实连接，不了解凭据、Noise 或信令的实现细节。 */
interface RemoteAccountConnection {
    val account: StateFlow<RemoteActiveAccount?>
    val state: StateFlow<RemoteAccountConnectionState>
    val hostStandby: StateFlow<Boolean> get() = NO_HOST_STANDBY
    val hostState: StateFlow<RemoteHostConnectionState>
    val hosts: Flow<List<RemoteHostDevice>> get() = kotlinx.coroutines.flow.emptyFlow()
    suspend fun login(origin: String, email: String, password: String): RemoteLoginResult
    suspend fun loginAccountToken(origin: String, token: String, loginAttemptId: String): RemoteLoginResult = error("REMOTE_LOGIN_UNAVAILABLE")
    suspend fun importPairing(value: com.openandroidintelligence.gateway.remote.auth.RemotePairingImport): Unit = error("REMOTE_LOGIN_UNAVAILABLE")
    /** 同步读取本机已存且未撤销的活动账号摘要，用于冷启动首帧路由，不触发网络或写操作。 */
    fun peekRestorableAccount(): RemoteActiveAccount? = account.value
    suspend fun restore(): RemoteActiveAccount?
    suspend fun listHosts(): List<RemoteHostDevice>
    suspend fun connect(hostDeviceId: String): RemoteRpcChannel
    fun authenticatedPeer(connectionId: String): AuthenticatedRemotePeer
    suspend fun logout()
    fun refreshControlConnection() {}
}

internal class ProductionRemoteAccountConnection(context: Context, scope: CoroutineScope, host: RemoteRpcHostHandler) : RemoteAccountConnection {
    private val runtime = RemoteAccountRuntime(context, scope, host)
    override val account = runtime.account
    override val state = runtime.state
    override val hostStandby = runtime.hostStandby
    override val hostState = runtime.hostState
    override val hosts = runtime.hosts
    override suspend fun login(origin: String, email: String, password: String) = runtime.login(origin, email, password)
    override suspend fun loginAccountToken(origin: String, token: String, loginAttemptId: String) = runtime.loginAccountToken(origin, token, loginAttemptId)
    override suspend fun importPairing(value: com.openandroidintelligence.gateway.remote.auth.RemotePairingImport) = runtime.importPairing(value)
    override fun peekRestorableAccount(): RemoteActiveAccount? = runtime.peekRestorableAccount()
    override suspend fun restore() = runtime.restore()
    override suspend fun listHosts() = runtime.listHosts()
    override suspend fun connect(hostDeviceId: String) = runtime.connect(hostDeviceId)
    override fun authenticatedPeer(connectionId: String) = runtime.authenticatedPeer(connectionId)
    override suspend fun logout() = runtime.logout()
    override fun refreshControlConnection() = runtime.refreshControlConnection()
}
