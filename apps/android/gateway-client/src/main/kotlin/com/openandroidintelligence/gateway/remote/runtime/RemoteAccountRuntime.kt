package com.openandroidintelligence.gateway.remote.runtime

import android.content.Context
import com.openandroidintelligence.gateway.remote.auth.*
import com.openandroidintelligence.gateway.remote.authproduction.*
import com.openandroidintelligence.gateway.remote.identity.*
import com.openandroidintelligence.gateway.remote.network.*
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.schema.JsonValue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RemoteAccountConnectionState { SIGNED_OUT, READY, CONNECTING, CONNECTED, DISCONNECTED }
enum class RemoteHostConnectionState { STOPPED, CONNECTING, ONLINE, FAILED }

/** 单活动账号的生产入口；界面选择与聊天状态留给各自领域，不从旧 Core 凭据恢复。 */
class RemoteAccountRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
    private val hostHandler: RemoteRpcHostHandler? = null,
    private val vault: RemoteCredentialVault = RemoteCredentialVault(context),
    private val protector: RemoteIdentityProtector = AndroidRemoteIdentityProtector(),
    private val apiFactory: (String) -> RemoteServerApi = { RemoteServerApi(it) },
) {
    private val mutableAccount = MutableStateFlow<RemoteActiveAccount?>(null)
    val account: StateFlow<RemoteActiveAccount?> = mutableAccount
    private val mutableState = MutableStateFlow(RemoteAccountConnectionState.SIGNED_OUT)
    val state: StateFlow<RemoteAccountConnectionState> = mutableState
    private val store = RemoteIdentityStore(vault, protector)
    private val lifecycle = Mutex()
    private val mutableHostState = MutableStateFlow(RemoteHostConnectionState.STOPPED)
    val hostState: StateFlow<RemoteHostConnectionState> = mutableHostState
    @Volatile private var loginJob: Job? = null
    private var api: RemoteServerApi? = null
    private var host: RemoteSignalService? = null
    private val clients = mutableSetOf<RemoteSignalService>()
    private val selectionGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private val peers = ConcurrentHashMap<String, AuthenticatedRemotePeer>()

    suspend fun login(origin: String, email: String, password: String): RemoteLoginResult {
        loginJob?.cancel()
        revokeLocal()
        val server = apiFactory(origin)
        val epoch = vault.revokeActive()
        val operation = currentCoroutineContext()[Job]
        loginJob = operation
        return try {
            val login = server.login(email, password)
            ensureActiveAuthorization(epoch)
            val clientIdentity = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
            val hostIdentity = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
            val result = vault.credentialTransaction {
                ensureActiveAuthorization(epoch)
                RemoteLoginCoordinator(store, server).registerRoles(server.origin, login.accountId, login.token, clientIdentity, hostIdentity)
            }
            ensureActiveAuthorization(epoch)
            if (result.client.state == RemoteRoleRegistrationState.REGISTERED || result.host.state == RemoteRoleRegistrationState.REGISTERED) {
                val active = vault.activate(server.origin, login.accountId, epoch)
                api = server; mutableAccount.value = active; mutableState.value = RemoteAccountConnectionState.READY
                startHost(active)
            }
            result
        } finally { if (loginJob === operation) loginJob = null }
    }
    private fun ensureActiveAuthorization(epoch: Long) { vault.requireEpoch(epoch) }
    suspend fun restore(): RemoteActiveAccount? = lifecycle.withLock {
        if (mutableAccount.value != null) return@withLock mutableAccount.value
        val active = vault.activeAccount() ?: return@withLock null
        val server = apiFactory(active.origin)
        val client = store.load(RemoteIdentityScope(active.origin, active.accountId, RemoteRole.CLIENT))
        val hostCredential = store.load(RemoteIdentityScope(active.origin, active.accountId, RemoteRole.HOST))
        if (client == null && hostCredential == null) { vault.revokeActive(); return@withLock null }
        api = server; mutableAccount.value = active; mutableState.value = RemoteAccountConnectionState.READY
        startHost(active); active
    }
    suspend fun listHosts(): List<RemoteHostDevice> {
        val active = current(); val server = api ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
        val credential = credential(active, RemoteRole.CLIENT)
        return server.listHosts(credential.tokens.accessToken).also { vault.requireCurrent(active) }
    }
    suspend fun connect(hostDeviceId: String): RemoteRpcChannel {
        val active = current(); val server = api ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
        val selection = selectionGeneration.incrementAndGet()
        // 同一 Client 设备的 Control WS 只能拥有一个活动选择；新选择先结束旧观察。
        synchronized(clients) { clients.forEach { it.close() }; clients.clear() }
        peers.entries.removeIf { it.value.role == RemoteRole.CLIENT }
        mutableState.value = RemoteAccountConnectionState.CONNECTING
        val credential = credential(active, RemoteRole.CLIENT)
        val peer = authorize(active, RemoteRole.CLIENT, hostDeviceId, RemoteRole.HOST)
        val service = RemoteSignalService(context, scope, server, RemoteIdentityScope(active.origin, active.accountId, RemoteRole.CLIENT), credential,
            { vault.requireCurrent(active) }, { id, role -> authorize(active, RemoteRole.CLIENT, id, role) },
            onAuthenticatedPeer = { peers[it.scope.connectionId] = it },
            onPeerClosed = { peers.remove(it.connectionId) },
            onClosed = { if (mutableAccount.value == active && selectionGeneration.get() == selection) mutableState.value = RemoteAccountConnectionState.DISCONNECTED })
        synchronized(clients) { clients += service }
        return try {
            val channel = service.connect(peer)
            val profile = probeCapabilities(channel)
            vault.requireCurrent(active)
            if (selectionGeneration.get() != selection) { channel.close(); throw RemoteRpcException("CONNECTION_REPLACED") }
            mutableState.value = RemoteAccountConnectionState.CONNECTED
            object : RemoteRpcChannel by channel {
                override val capabilities = profile.capabilities
                override val workspaceTypes = profile.workspaceTypes
                override suspend fun close() {
                    channel.close(); service.close(); peers.remove(channel.scope.connectionId)
                    synchronized(clients) { clients -= service }
                    if (mutableAccount.value == active && selectionGeneration.get() == selection) mutableState.value = RemoteAccountConnectionState.READY
                }
            }
        } catch (failure: Exception) {
            service.close(); synchronized(clients) { clients -= service }
            if (mutableAccount.value == active && selectionGeneration.get() == selection) mutableState.value = RemoteAccountConnectionState.DISCONNECTED
            throw failure
        }
    }
    fun authenticatedPeer(connectionId: String): AuthenticatedRemotePeer = peers[connectionId] ?: throw RemoteRpcException("CONNECTION_BINDING_MISMATCH")
    suspend fun logout() { loginJob?.cancel(); loginJob = null; revokeLocal() }
    private fun revokeLocal() {
        // 持久撤销先于任何网络清理；写盘失败不报告退出成功。
        val old = vault.activeAccount()
        selectionGeneration.incrementAndGet()
        vault.revokeActive()
        mutableAccount.value = null; mutableState.value = RemoteAccountConnectionState.SIGNED_OUT
        host?.close(); host = null; mutableHostState.value = RemoteHostConnectionState.STOPPED
        synchronized(clients) { clients.forEach { it.close() }; clients.clear() }; peers.clear()
        if (old != null) store.clearAccount(old.origin, old.accountId)
        api = null
    }
    private suspend fun startHost(active: RemoteActiveAccount) {
        if (hostHandler == null) return
        val server = api ?: return
        val credential = try { credential(active, RemoteRole.HOST) } catch (_: RemoteRpcException) { return }
        val service = RemoteSignalService(context, scope, server, RemoteIdentityScope(active.origin, active.accountId, RemoteRole.HOST), credential,
            { vault.requireCurrent(active) }, { id, role -> authorize(active, RemoteRole.HOST, id, role) }, hostHandler,
            onAuthenticatedPeer = { peers[it.scope.connectionId] = it },
            onPeerClosed = { peers.remove(it.connectionId) },
            onClosed = { if (mutableAccount.value == active) mutableHostState.value = RemoteHostConnectionState.FAILED })
        host = service; mutableHostState.value = RemoteHostConnectionState.CONNECTING
        // Host 角色独立上线，不因某个已授权外部 Client 连接关闭而停掉主动聊天角色。
        scope.launch {
            try { service.startHost(); if (host === service) mutableHostState.value = RemoteHostConnectionState.ONLINE }
            catch (_: Exception) { service.close(); if (host === service) mutableHostState.value = RemoteHostConnectionState.FAILED }
        }
    }
    private fun current(): RemoteActiveAccount = mutableAccount.value?.also(vault::requireCurrent) ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
    private suspend fun credential(active: RemoteActiveAccount, role: RemoteRole): RemoteRoleCredential {
        vault.requireCurrent(active)
        val identityScope = RemoteIdentityScope(active.origin, active.accountId, role)
        return vault.credentialTransaction {
            vault.requireCurrent(active)
            val existing = store.load(identityScope) ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
            if (existing.tokens.accessTokenExpiresAt > System.currentTimeMillis() + 30_000) existing
            else {
                val server = api ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
                val result = RemoteTokenRotator(store, server).rotate(identityScope)
                vault.requireCurrent(active)
                when (result) {
                    is RemoteTokenRotationResult.Rotated -> result.credential
                    is RemoteTokenRotationResult.Failed -> throw RemoteRpcException(result.code)
                }
            }
        }
    }
    private suspend fun authorize(active: RemoteActiveAccount, localRole: RemoteRole, id: String, expectedRole: RemoteRole): RemoteAuthorizedPeer {
        val credential = credential(active, localRole)
        val descriptor = (api ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")).peer(credential.tokens.accessToken, id)
        vault.requireCurrent(active)
        if (descriptor.role != expectedRole || descriptor.device.deviceId != id) throw RemoteRpcException("MEMBERSHIP_REQUIRED")
        val identityScope = RemoteIdentityScope(active.origin, active.accountId, localRole)
        vault.credentialTransaction {
            vault.requireCurrent(active)
            val current = store.load(identityScope) ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
            val pin = RemotePeerPin(expectedRole, RemoteDeviceId(id), descriptor.publicKey)
            val updated = current.pin(pin)
            if (updated !== current) store.save(identityScope, updated)
        }
        vault.requireCurrent(active); return descriptor
    }
    private data class CapabilityProfile(val capabilities: Set<String>, val workspaceTypes: List<JsonValue.JObject>)
    private suspend fun probeCapabilities(channel: RemoteRpcChannel): CapabilityProfile {
        val describe = channel.invoke("harness.transport.describe", obj()).objectValue()
        val values = describe.arrayValue("capabilities")
        if (values.size > 128) throw RemoteRpcException("INVALID_MESSAGE")
        val capabilities = values.map {
            (it as? JsonValue.JString)?.value?.takeIf(String::isNotBlank) ?: throw RemoteRpcException("INVALID_MESSAGE")
        }.toSet()
        if (capabilities.size != values.size || "harness.remote.v1" in capabilities && "harness.remote.v3" in capabilities) throw RemoteRpcException("INVALID_MESSAGE")
        val workspaceTypes = describe.fields.firstOrNull { it.first == "workspaceTypes" }?.second?.let { value ->
            val rows = (value as? JsonValue.JArray)?.items ?: throw RemoteRpcException("INVALID_MESSAGE")
            if (rows.size > 32) throw RemoteRpcException("INVALID_MESSAGE")
            rows.map { it.objectValue().also { row ->
                row.requiredText("id"); row.requiredText("name"); row.requiredText("capability"); row.booleanValue("available")
            } }
        } ?: emptyList()
        return CapabilityProfile(capabilities, workspaceTypes)
    }
}
