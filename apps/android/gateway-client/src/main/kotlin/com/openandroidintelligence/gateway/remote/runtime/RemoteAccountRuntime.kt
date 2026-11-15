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
    private val selectionLock = Any()
    private var pendingConnect: Deferred<RemoteRpcChannel>? = null
    private val peers = ConcurrentHashMap<String, AuthenticatedRemotePeer>()

    suspend fun login(origin: String, email: String, password: String): RemoteLoginResult =
        loginAuthorized(origin) { it.login(email, password) }
    suspend fun loginAccountToken(origin: String, token: String): RemoteLoginResult =
        loginAuthorized(origin) { it.accountFromToken(token) }
    private suspend fun loginAuthorized(origin: String, authorize: suspend (RemoteServerApi) -> RemoteAccountLogin): RemoteLoginResult {
        loginJob?.cancel()
        val operation = currentCoroutineContext()[Job]
        loginJob = operation
        return try {
            lifecycle.withLock {
                revokeLocal()
                val server = apiFactory(origin)
                val epoch = vault.revokeActive()
                val login = authorize(server)
                ensureActiveAuthorization(epoch)
                val clientIdentity = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
                val hostIdentity = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
                val result = vault.credentialTransaction {
                    ensureActiveAuthorization(epoch)
                    RemoteLoginCoordinator(store, server).registerRoles(server.origin, login.accountId, login.token, clientIdentity, hostIdentity)
                }
                currentCoroutineContext().ensureActive()
                ensureActiveAuthorization(epoch)
                if (result.client.state == RemoteRoleRegistrationState.REGISTERED || result.host.state == RemoteRoleRegistrationState.REGISTERED) {
                    val active = vault.activate(server.origin, login.accountId, epoch)
                    api = server; mutableAccount.value = active; mutableState.value = RemoteAccountConnectionState.READY
                    startHost(active)
                }
                result
            }
        } finally { if (loginJob === operation) loginJob = null }
    }
    /** 配对只导入手机 Client；不请求上游禁止的账号级注册，也不派生虚拟 Host。 */
    suspend fun importPairing(value: RemotePairingImport) {
        loginJob?.cancel()
        val operation = currentCoroutineContext()[Job]
        loginJob = operation
        try {
            lifecycle.withLock {
                val server = apiFactory(RemoteServerApi.DEFAULT_ORIGIN)
                val clientScope = RemoteIdentityScope(server.origin, value.accountId, RemoteRole.CLIENT)
                val active = vault.activeAccount()
                // 保存成功后的进程退出可能留下临时配对票据。恢复时保留已续期凭据，不重复覆盖。
                if (active?.origin == server.origin && active.accountId == value.accountId) {
                    val existing = store.load(clientScope)
                    check(existing?.identity?.sameIdentity(value.credential.identity) == true) { "PAIRING_DEVICE_CONFLICT" }
                    value.credential.peers.forEach { expected ->
                        check(existing.peers.any { it.peerDeviceId == expected.peerDeviceId && it.matches(expected.publicKey) }) { "REMOTE_PEER_PIN_MISMATCH" }
                    }
                    api = server; mutableAccount.value = active; mutableState.value = RemoteAccountConnectionState.READY
                    return@withLock
                }
                vault.requireEpoch(value.epoch)
                revokeLocal()
                val epoch = vault.authorizationEpoch()
                vault.credentialTransaction {
                    ensureActiveAuthorization(epoch)
                    // 配对授权不能复用旧的账号级 Host 记录；恢复后仍只具有本次 Client 权限。
                    store.clear(RemoteIdentityScope(server.origin, value.accountId, RemoteRole.HOST))
                    store.save(clientScope, value.credential)
                    currentCoroutineContext().ensureActive()
                    ensureActiveAuthorization(epoch)
                    val saved = vault.activate(server.origin, value.accountId, epoch)
                    api = server; mutableAccount.value = saved; mutableState.value = RemoteAccountConnectionState.READY
                    mutableHostState.value = RemoteHostConnectionState.STOPPED
                }
            }
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
        currentCoroutineContext().ensureActive()
        vault.requireCurrent(active)
        api = server; mutableAccount.value = active; mutableState.value = RemoteAccountConnectionState.READY
        startHost(active); active
    }
    suspend fun listHosts(): List<RemoteHostDevice> {
        val active = current(); val server = api ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
        val credential = credential(active, RemoteRole.CLIENT)
        return server.listHosts(credential.tokens.accessToken).also { requireActive(active) }
    }
    suspend fun connect(hostDeviceId: String): RemoteRpcChannel {
        val active = current()
        val server = api ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
        val selection: Long
        val operation: Deferred<RemoteRpcChannel>
        synchronized(selectionLock) {
            selection = selectionGeneration.incrementAndGet()
            pendingConnect?.cancel()
            // 同一 Client 设备的 Control WS 只能拥有一个活动选择；新选择先结束旧观察。
            synchronized(clients) { clients.forEach { it.close() }; clients.clear() }
            peers.entries.removeIf { it.value.role == RemoteRole.CLIENT }
            mutableState.value = RemoteAccountConnectionState.CONNECTING
            operation = scope.async(start = CoroutineStart.LAZY) { connectSelected(active, server, hostDeviceId, selection) }
            pendingConnect = operation
            operation.start()
        }
        return try { operation.await() } finally {
            synchronized(selectionLock) { if (pendingConnect === operation) pendingConnect = null }
            if (!currentCoroutineContext().isActive) operation.cancel()
        }
    }
    private fun requireSelection(active: RemoteActiveAccount, selection: Long) {
        requireActive(active)
        if (selectionGeneration.get() != selection) throw RemoteRpcException("CONNECTION_REPLACED", RemoteDeliveryOutcome.NOT_SENT)
    }
    /** 代次判断和状态发布必须共用选择锁，旧回调不能在新选择发布后覆盖它。 */
    private fun publishSelectionState(active: RemoteActiveAccount, selection: Long, state: RemoteAccountConnectionState) = synchronized(selectionLock) {
        if (mutableAccount.value == active && selectionGeneration.get() == selection) mutableState.value = state
    }
    private suspend fun connectSelected(active: RemoteActiveAccount, server: RemoteServerApi, hostDeviceId: String, selection: Long): RemoteRpcChannel {
        currentCoroutineContext().ensureActive(); requireSelection(active, selection)
        val credential = credential(active, RemoteRole.CLIENT)
        currentCoroutineContext().ensureActive(); requireSelection(active, selection)
        val peer = authorize(active, RemoteRole.CLIENT, hostDeviceId, RemoteRole.HOST)
        currentCoroutineContext().ensureActive(); requireSelection(active, selection)
        val service: RemoteSignalService
        val opening: Deferred<RemoteRpcChannel>
        synchronized(selectionLock) {
            requireSelection(active, selection)
            service = RemoteSignalService(context, scope, server, RemoteIdentityScope(active.origin, active.accountId, RemoteRole.CLIENT), credential,
                { requireSelection(active, selection) }, { id, role -> authorize(active, RemoteRole.CLIENT, id, role) },
                onAuthenticatedPeer = { authenticated -> synchronized(selectionLock) {
                    requireSelection(active, selection); peers[authenticated.scope.connectionId] = authenticated
                } },
                onPeerClosed = ::peerClosed,
                onClosed = { publishSelectionState(active, selection, RemoteAccountConnectionState.DISCONNECTED) })
            synchronized(clients) { clients += service }
            // UNDISPATCHED 让创建 Control WS 与最后的代际核对处于同一选择锁内。
            opening = scope.async(start = CoroutineStart.UNDISPATCHED) { service.connect(peer) }
        }
        return try {
            val channel = opening.await()
            currentCoroutineContext().ensureActive(); requireSelection(active, selection)
            val profile = probeCapabilities(channel)
            currentCoroutineContext().ensureActive(); requireSelection(active, selection)
            synchronized(selectionLock) { requireSelection(active, selection); mutableState.value = RemoteAccountConnectionState.CONNECTED }
            object : RemoteRpcChannel by channel {
                override val capabilities = profile.capabilities
                override val workspaceTypes = profile.workspaceTypes
                override suspend fun close() {
                    channel.close(); service.close(); peerClosed(channel.scope)
                    synchronized(clients) { clients -= service }
                    publishSelectionState(active, selection, RemoteAccountConnectionState.READY)
                }
            }
        } catch (failure: Exception) {
            opening.cancel(); service.close(); synchronized(clients) { clients -= service }
            publishSelectionState(active, selection, RemoteAccountConnectionState.DISCONNECTED)
            throw failure
        }
    }
    private fun peerClosed(connection: RemoteRpcConnectionScope) {
        peers.computeIfPresent(connection.connectionId) { _, peer -> peer.takeUnless { it.scope == connection } }
        hostHandler?.connectionClosed(connection)
    }
    fun authenticatedPeer(connectionId: String): AuthenticatedRemotePeer = peers[connectionId] ?: throw RemoteRpcException("CONNECTION_BINDING_MISMATCH")
    suspend fun logout() {
        loginJob?.cancel(); loginJob = null
        lifecycle.withLock { revokeLocal() }
    }
    private fun revokeLocal() {
        val old = mutableAccount.value
        synchronized(selectionLock) {
            selectionGeneration.incrementAndGet(); pendingConnect?.cancel(); pendingConnect = null
            mutableAccount.value = null
            mutableState.value = RemoteAccountConnectionState.DISCONNECTED
        }
        val closingHost = host.also { host = null }
        mutableHostState.value = RemoteHostConnectionState.STOPPED
        val closingClients = synchronized(clients) { clients.toList().also { clients.clear() } }
        val closingPeers = peers.values.toList()
        api = null
        var revocationFailed = false
        var cleanupFailed = false
        try { vault.revokeActive() }
        catch (_: Exception) { revocationFailed = true }
        try {
            closeRemoteConnectionResources(
                { closingHost?.close(); Unit },
                *closingClients.map { client -> { client.close() } }.toTypedArray(),
                *closingPeers.map { peer -> { peerClosed(peer.scope) } }.toTypedArray(),
                { if (!revocationFailed && old != null) store.clearAccount(old.origin, old.accountId) },
            )
        } catch (_: Exception) { cleanupFailed = true }
        if (revocationFailed || cleanupFailed) throw RemoteRpcException("REMOTE_LOGOUT_INCOMPLETE")
        mutableState.value = RemoteAccountConnectionState.SIGNED_OUT
    }
    private suspend fun startHost(active: RemoteActiveAccount) {
        if (hostHandler == null) return
        val server = api ?: return
        val credential = try { credential(active, RemoteRole.HOST) } catch (_: RemoteRpcException) { return }
        val service = RemoteSignalService(context, scope, server, RemoteIdentityScope(active.origin, active.accountId, RemoteRole.HOST), credential,
            { requireActive(active) }, { id, role -> authorize(active, RemoteRole.HOST, id, role) }, hostHandler,
            onAuthenticatedPeer = { authenticated -> synchronized(selectionLock) {
                requireActive(active)
                peers[authenticated.scope.connectionId] = authenticated
            } },
            onPeerClosed = ::peerClosed,
            onClosed = { if (mutableAccount.value == active) mutableHostState.value = RemoteHostConnectionState.FAILED })
        host = service; mutableHostState.value = RemoteHostConnectionState.CONNECTING
        // Host 角色独立上线，不因某个已授权外部 Client 连接关闭而停掉主动聊天角色。
        scope.launch {
            try { service.startHost(); if (host === service) mutableHostState.value = RemoteHostConnectionState.ONLINE }
            catch (_: Exception) { service.close(); if (host === service) mutableHostState.value = RemoteHostConnectionState.FAILED }
        }
    }
    private fun requireActive(active: RemoteActiveAccount) {
        check(mutableAccount.value == active) { "REMOTE_AUTHORIZATION_SUPERSEDED" }
        vault.requireCurrent(active)
    }
    private fun current(): RemoteActiveAccount = mutableAccount.value?.also(::requireActive) ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
    private suspend fun credential(active: RemoteActiveAccount, role: RemoteRole): RemoteRoleCredential {
        requireActive(active)
        val identityScope = RemoteIdentityScope(active.origin, active.accountId, role)
        return vault.credentialTransaction {
            requireActive(active)
            val existing = store.load(identityScope) ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
            if (existing.tokens.accessTokenExpiresAt > System.currentTimeMillis() + 30_000) existing
            else {
                val server = api ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
                val result = RemoteTokenRotator(store, server).rotate(identityScope)
                requireActive(active)
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
        requireActive(active)
        if (descriptor.role != expectedRole || descriptor.device.deviceId != id) throw RemoteRpcException("MEMBERSHIP_REQUIRED")
        val identityScope = RemoteIdentityScope(active.origin, active.accountId, localRole)
        vault.credentialTransaction {
            requireActive(active)
            val current = store.load(identityScope) ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
            val pin = RemotePeerPin(expectedRole, RemoteDeviceId(id), descriptor.publicKey)
            val updated = current.pin(pin)
            if (updated !== current) store.save(identityScope, updated)
        }
        requireActive(active); return descriptor
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
