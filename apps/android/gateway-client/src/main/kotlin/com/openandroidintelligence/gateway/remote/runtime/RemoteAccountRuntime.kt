package com.openandroidintelligence.gateway.remote.runtime

import android.content.Context
import com.openandroidintelligence.gateway.remote.auth.*
import com.openandroidintelligence.gateway.remote.authproduction.*
import com.openandroidintelligence.gateway.remote.identity.*
import com.openandroidintelligence.gateway.remote.network.*
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.schema.JsonValue
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val apiFactory: ((String) -> RemoteServerApi)? = null,
) {
    private val installation = vault.installationIdentityStore { RemoteInstallationIdentityStore.defaultDeviceName(context) }
    private fun server(origin: String) = apiFactory?.invoke(origin) ?: RemoteServerApi(origin, deviceNameProvider = installation::deviceName)
    private val mutableAccount = MutableStateFlow<RemoteActiveAccount?>(null)
    val account: StateFlow<RemoteActiveAccount?> = mutableAccount
    private val mutableState = MutableStateFlow(RemoteAccountConnectionState.SIGNED_OUT)
    val state: StateFlow<RemoteAccountConnectionState> = mutableState
    private val mutableHosts = MutableStateFlow<List<RemoteHostDevice>>(emptyList())
    val hosts: StateFlow<List<RemoteHostDevice>> = mutableHosts.asStateFlow()
    private val store = RemoteIdentityStore(vault, protector)
    private val lifecycle = Mutex()
    private val mutableHostState = MutableStateFlow(RemoteHostConnectionState.STOPPED)
    val hostState: StateFlow<RemoteHostConnectionState> = mutableHostState
    @Volatile private var loginJob: Job? = null
    private var api: RemoteServerApi? = null
    private var host: RemoteSignalService? = null
    @Volatile private var client: RemoteSignalService? = null
    @Volatile private var clientJob: Job? = null
    private var clientAvailable = CompletableDeferred<RemoteSignalService>()
    private val presenceRefresh = Mutex()
    private val selectionGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private val selectionLock = Any()
    private var pendingConnect: Deferred<RemoteRpcChannel>? = null
    private val peers = ConcurrentHashMap<String, AuthenticatedRemotePeer>()

    suspend fun login(origin: String, email: String, password: String): RemoteLoginResult =
        loginAuthorized(origin, vault.beginLoginAttempt()) { it.login(email, password) }
    suspend fun loginAccountToken(origin: String, token: String, loginAttemptId: String): RemoteLoginResult =
        loginAuthorized(origin, loginAttemptId) { it.accountFromToken(token) }
    private suspend fun loginAuthorized(origin: String, loginAttemptId: String, authorize: suspend (RemoteServerApi) -> RemoteAccountLogin): RemoteLoginResult {
        loginJob?.cancel()
        val operation = currentCoroutineContext()[Job]
        loginJob = operation
        return try {
            lifecycle.withLock {
                vault.requireLoginAttempt(loginAttemptId)
                revokeLocal()
                val installation = installationIdentities()
                val server = server(origin)
                val epoch = vault.revokeActive()
                val login = authorize(server)
                ensureActiveAuthorization(epoch)
                val clientIdentity = installation.client
                val hostIdentity = installation.host
                val result = vault.credentialTransaction {
                    ensureActiveAuthorization(epoch)
                    RemoteLoginCoordinator(store, server).registerRoles(server.origin, login.accountId, login.token, clientIdentity, hostIdentity)
                }
                currentCoroutineContext().ensureActive()
                ensureActiveAuthorization(epoch)
                if (result.client.state == RemoteRoleRegistrationState.REGISTERED || result.host.state == RemoteRoleRegistrationState.REGISTERED) {
                    val active = vault.activate(server.origin, login.accountId, epoch, loginAttemptId)
                    api = server; mutableAccount.value = active; mutableState.value = RemoteAccountConnectionState.READY
                    startClient(active); startHost(active)
                }
                result
            }
        } finally {
            vault.cancelLoginAttempt(loginAttemptId)
            if (loginJob === operation) loginJob = null
        }
    }
    /** 配对只导入手机 Client；不请求上游禁止的账号级注册，也不派生虚拟 Host。 */
    suspend fun importPairing(value: RemotePairingImport) {
        loginJob?.cancel()
        val operation = currentCoroutineContext()[Job]
        loginJob = operation
        try {
            lifecycle.withLock {
                val server = server(RemoteServerApi.DEFAULT_ORIGIN)
                val clientScope = RemoteIdentityScope(server.origin, value.accountId, RemoteRole.CLIENT)
                val active = vault.activeAccount()
                // 保存成功后的进程退出可能留下临时配对票据。恢复时保留已续期凭据，不重复覆盖。
                if (active?.origin == server.origin && active.accountId == value.accountId) {
                    check(active.loginAttemptId == value.loginAttemptId) { "REMOTE_AUTHORIZATION_SUPERSEDED" }
                    val existing = store.load(clientScope)
                    check(existing?.identity?.sameIdentity(value.credential.identity) == true) { "PAIRING_DEVICE_CONFLICT" }
                    value.credential.peers.forEach { expected ->
                        check(existing.peers.any { it.peerDeviceId == expected.peerDeviceId && it.matches(expected.publicKey) }) { "REMOTE_PEER_PIN_MISMATCH" }
                    }
                    api = server; mutableAccount.value = active; mutableState.value = RemoteAccountConnectionState.READY
                    startClient(active)
                    return@withLock
                }
                vault.requireLoginAttempt(value.loginAttemptId)
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
                    val saved = vault.activate(server.origin, value.accountId, epoch, value.loginAttemptId)
                    api = server; mutableAccount.value = saved; mutableState.value = RemoteAccountConnectionState.READY
                    mutableHostState.value = RemoteHostConnectionState.STOPPED
                }
                startClient(mutableAccount.value ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED"))
            }
        } finally { if (loginJob === operation) loginJob = null }
    }
    private fun ensureActiveAuthorization(epoch: Long) { vault.requireEpoch(epoch) }
    private fun installationIdentities(): RemoteInstallationIdentities {
        val active = vault.activeAccount()
        return installation.loadOrCreate(
            active?.let { store.load(RemoteIdentityScope(it.origin, it.accountId, RemoteRole.CLIENT))?.identity },
            active?.let { store.load(RemoteIdentityScope(it.origin, it.accountId, RemoteRole.HOST))?.identity },
        )
    }
    suspend fun restore(): RemoteActiveAccount? = lifecycle.withLock {
        if (mutableAccount.value != null) return@withLock mutableAccount.value
        val active = vault.activeAccount() ?: return@withLock null
        val server = server(active.origin)
        val client = store.load(RemoteIdentityScope(active.origin, active.accountId, RemoteRole.CLIENT))
        val hostCredential = store.load(RemoteIdentityScope(active.origin, active.accountId, RemoteRole.HOST))
        if (client == null && hostCredential == null) { vault.revokeActive(); return@withLock null }
        installation.loadOrCreate(client?.identity, hostCredential?.identity)
        currentCoroutineContext().ensureActive()
        vault.requireCurrent(active)
        api = server; mutableAccount.value = active; mutableState.value = RemoteAccountConnectionState.READY
        startClient(active); startHost(active); active
    }
    suspend fun listHosts(): List<RemoteHostDevice> = presenceRefresh.withLock {
        val active = current(); val server = api ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
        val credential = credential(active, RemoteRole.CLIENT)
        val localHost = store.load(RemoteIdentityScope(active.origin, active.accountId, RemoteRole.HOST))?.identity?.deviceId?.value
        val installationHost = installation.loadOrCreate().host.deviceId.value
        server.listHosts(credential.tokens.accessToken).filter { it.deviceId != localHost && it.deviceId != installationHost }.also { devices ->
            synchronized(selectionLock) { requireActive(active); mutableHosts.value = devices }
        }
    }
    suspend fun connect(hostDeviceId: String): RemoteRpcChannel {
        val active = current()
        val selection: Long
        val operation: Deferred<RemoteRpcChannel>
        synchronized(selectionLock) {
            selection = selectionGeneration.incrementAndGet()
            pendingConnect?.cancel()
            client?.cancelPendingSelection()
            if (client?.connected != true && clientAvailable.isCompleted) clientAvailable = CompletableDeferred()
            operation = scope.async(start = CoroutineStart.LAZY) { connectSelected(active, hostDeviceId, selection) }
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
    private suspend fun connectSelected(active: RemoteActiveAccount, hostDeviceId: String, selection: Long): RemoteRpcChannel {
        currentCoroutineContext().ensureActive(); requireSelection(active, selection)
        val peer = authorize(active, RemoteRole.CLIENT, hostDeviceId, RemoteRole.HOST)
        currentCoroutineContext().ensureActive(); requireSelection(active, selection)
        val service = withTimeout(30_000) { synchronized(selectionLock) { requireActive(active); clientAvailable }.await() }
        currentCoroutineContext().ensureActive(); requireSelection(active, selection)
        var channel: RemoteRpcChannel? = null
        return try {
            val opened = service.connect(peer).also { channel = it }
            currentCoroutineContext().ensureActive(); requireSelection(active, selection)
            val profile = probeCapabilities(opened)
            currentCoroutineContext().ensureActive(); requireSelection(active, selection)
            object : RemoteRpcChannel by opened {
                override val capabilities = profile.capabilities
                override val workspaceTypes = profile.workspaceTypes
                override suspend fun close() {
                    opened.close(); peerClosed(opened.scope)
                }
            }
        } catch (failure: Exception) {
            channel?.close()
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
        var old = mutableAccount.value
        synchronized(selectionLock) {
            selectionGeneration.incrementAndGet(); pendingConnect?.cancel(); pendingConnect = null
            mutableAccount.value = null
            mutableState.value = RemoteAccountConnectionState.DISCONNECTED
        }
        val closingHost = host.also { host = null }
        mutableHostState.value = RemoteHostConnectionState.STOPPED
        val closingClient = synchronized(selectionLock) {
            clientJob?.cancel(); clientJob = null
            clientAvailable.completeExceptionally(RemoteRpcException("CLIENT_CLOSED"))
            clientAvailable = CompletableDeferred()
            client.also { client = null }
        }
        mutableHosts.value = emptyList()
        val closingPeers = peers.values.toList()
        api = null
        var revocationFailed = false
        var cleanupFailed = false
        var identityPreserved = false
        try {
            // 冷启动尚未 restore 时仍须清理磁盘活动账号，并先保住升级前唯一的安装密钥。
            if (old == null) old = vault.activeAccount()
            old?.let { active ->
                installation.loadOrCreate(
                    store.load(RemoteIdentityScope(active.origin, active.accountId, RemoteRole.CLIENT))?.identity,
                    store.load(RemoteIdentityScope(active.origin, active.accountId, RemoteRole.HOST))?.identity,
                )
            }
            identityPreserved = true
        } catch (_: Exception) { cleanupFailed = true }
        // 身份迁移失败也必须撤销账号、关闭连接；保留不可用旧材料，不能误删唯一密钥。
        try { vault.revokeActive() }
        catch (_: Exception) { revocationFailed = true }
        try {
            closeRemoteConnectionResources(
                { closingHost?.close(); Unit },
                { closingClient?.close(); Unit },
                *closingPeers.map { peer -> { peerClosed(peer.scope) } }.toTypedArray(),
                { if (!revocationFailed && identityPreserved) old?.let { store.clearAccount(it.origin, it.accountId) } },
            )
        } catch (_: Exception) { cleanupFailed = true }
        if (revocationFailed || cleanupFailed) throw RemoteRpcException("REMOTE_LOGOUT_INCOMPLETE")
        mutableState.value = RemoteAccountConnectionState.SIGNED_OUT
    }
    /** 账号控制面与 Host 业务隧道分别拥有生命周期；登录后不等待选工作区才上线。 */
    private fun startClient(active: RemoteActiveAccount) = synchronized(selectionLock) {
        if (clientJob?.isActive == true) return@synchronized
        requireActive(active)
        clientJob = scope.launch(start = CoroutineStart.LAZY) {
            var retry = 1_000L
            while (isActive && mutableAccount.value == active) {
                var service: RemoteSignalService? = null
                try {
                    val server = api ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
                    val credential = credential(active, RemoteRole.CLIENT)
                    val ended = CompletableDeferred<Unit>()
                    val current = RemoteSignalService(context, scope, server,
                        RemoteIdentityScope(active.origin, active.accountId, RemoteRole.CLIENT), credential,
                        { requireActive(active) }, { id, role -> authorize(active, RemoteRole.CLIENT, id, role) },
                        onAuthenticatedPeer = { authenticated -> synchronized(selectionLock) {
                            requireActive(active); peers[authenticated.scope.connectionId] = authenticated
                        } }, onPeerClosed = ::peerClosed,
                        onClosed = { ended.complete(Unit) },
                        onHeartbeat = { scope.launch { refreshPresence(active) } })
                    service = current
                    synchronized(selectionLock) {
                        requireActive(active); client = current
                        mutableState.value = RemoteAccountConnectionState.CONNECTING
                    }
                    current.startClient()
                    synchronized(selectionLock) {
                        requireActive(active)
                        if (!current.connected) throw RemoteRpcException("TRANSPORT_CLOSED")
                        mutableState.value = RemoteAccountConnectionState.CONNECTED
                        clientAvailable.complete(current)
                    }
                    retry = 1_000L
                    scope.launch { refreshPresence(active) }
                    ended.await()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    // 只重建认证与传输，绝不重放旧隧道上的业务写入。
                } finally {
                    service?.close()
                    synchronized(selectionLock) {
                        if (mutableAccount.value == active) {
                            if (client === service) client = null
                            if (clientAvailable.isCompleted) clientAvailable = CompletableDeferred()
                            mutableState.value = RemoteAccountConnectionState.DISCONNECTED
                        }
                    }
                }
                delay(retry)
                retry = (retry * 2).coerceAtMost(30_000L)
            }
        }.also { it.start() }
    }
    private suspend fun refreshPresence(active: RemoteActiveAccount) {
        if (mutableAccount.value != active || presenceRefresh.isLocked) return
        try { listHosts() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            android.util.Log.w("RemoteAccountRuntime", "PRESENCE_REFRESH_FAILED:${(failure as? RemoteRpcException)?.code ?: "CONNECTION_FAILED"}")
            synchronized(selectionLock) {
                if (mutableAccount.value == active) mutableHosts.value = mutableHosts.value.map { it.copy(online = false) }
            }
        }
    }
    fun refreshControlConnection() {
        val active = mutableAccount.value ?: return
        if (clientJob?.isActive != true) startClient(active)
        scope.launch { refreshPresence(active) }
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
