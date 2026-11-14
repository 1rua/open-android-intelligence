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

/** App 只需要账号生命周期和真实连接，不了解凭据、Noise 或信令的实现细节。 */
interface RemoteAccountConnection {
    val account: StateFlow<RemoteActiveAccount?>
    val state: StateFlow<RemoteAccountConnectionState>
    val hostState: StateFlow<RemoteHostConnectionState>
    suspend fun login(origin: String, email: String, password: String): RemoteLoginResult
    suspend fun restore(): RemoteActiveAccount?
    suspend fun listHosts(): List<RemoteHostDevice>
    suspend fun connect(hostDeviceId: String): RemoteRpcChannel
    fun authenticatedPeer(connectionId: String): AuthenticatedRemotePeer
    suspend fun logout()
}

internal class ProductionRemoteAccountConnection(context: Context, scope: CoroutineScope, host: RemoteRpcHostHandler) : RemoteAccountConnection {
    private val runtime = RemoteAccountRuntime(context, scope, host)
    override val account = runtime.account
    override val state = runtime.state
    override val hostState = runtime.hostState
    override suspend fun login(origin: String, email: String, password: String) = runtime.login(origin, email, password)
    override suspend fun restore() = runtime.restore()
    override suspend fun listHosts() = runtime.listHosts()
    override suspend fun connect(hostDeviceId: String) = runtime.connect(hostDeviceId)
    override fun authenticatedPeer(connectionId: String) = runtime.authenticatedPeer(connectionId)
    override suspend fun logout() = runtime.logout()
}
