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

private val clientReauthorizationCodes = setOf("TOKEN_EXPIRED", "AUTH_INVALID")
private val clientRetryableCodes = setOf("TRANSPORT_CLOSED", "CONNECTION_FAILED", "HEARTBEAT_TIMEOUT", "HELLO_TIMEOUT",
    "SERVER_SHUTDOWN", "RATE_LIMITED", "INTERNAL_ERROR", "REMOTE_REFRESH_FAILED", "REMOTE_REFRESH_CONFLICT",
    "REMOTE_REFRESH_PERSISTENCE_FAILED", "REMOTE_CREDENTIAL_PERSISTENCE_FAILED", "REMOTE_IDENTITY_LOCKED", "CLIENT_CLOSED")
private fun safeClientErrorCode(code: String, fallback: String) =
    if (code == "CLIENT_CLOSED") code else safeRoleErrorCode(code, fallback)

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
    private val mutableClientFailure = MutableStateFlow<String?>(null)
    val clientFailure = mutableClientFailure.asStateFlow()
    val hosts: StateFlow<List<RemoteHostDevice>> = mutableHosts.asStateFlow()
    private val store = RemoteIdentityStore(vault, protector)
    private val lifecycle = Mutex()
    private val mutableHostStandby = MutableStateFlow(false)
    val hostStandby = mutableHostStandby.asStateFlow()
    private val mutableHostState = MutableStateFlow(RemoteHostConnectionState.STOPPED)
    val hostState: StateFlow<RemoteHostConnectionState> = mutableHostState
    private val mutableHostFailure = MutableStateFlow<RemoteHostFailure?>(null)
    val hostFailure = mutableHostFailure.asStateFlow()
    @Volatile private var loginJob: Job? = null
    private var api: RemoteServerApi? = null
    private var host: RemoteHostControl? = null
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
                    val active = vault.activate(server.origin, login.accountId, epoch, loginAttemptId,
                        result.client.errorCode, result.host.errorCode)
                    api = server; mutableAccount.value = active
                    startClientRole(active, result.client.errorCode)
                    if (result.host.state == RemoteRoleRegistrationState.REGISTERED) {
                        android.util.Log.i(RemoteHostControl.TAG, "REGISTERED")
                        startHost(active, server)
                    }
                }
                if (result.host.state == RemoteRoleRegistrationState.FAILED) {
                    publishHost(RemoteHostConnectionState.FAILED, RemoteHostFailure(RemoteHostFailureStage.REGISTRATION,
                        result.host.errorCode ?: "REMOTE_ROLE_REGISTRATION_FAILED"))
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
        installation.existingIdentities()?.let { return it }
        val active = vault.activeAccount()
        return installation.loadOrCreate(
            active?.let { store.load(RemoteIdentityScope(it.origin, it.accountId, RemoteRole.CLIENT))?.identity },
            active?.let { store.load(RemoteIdentityScope(it.origin, it.accountId, RemoteRole.HOST))?.identity },
        )
    }
    /** 同步检查本机凭据信封中是否存在未被撤销的活动账号（纯本地校验，不访问网络）。 */
    fun peekRestorableAccount(): RemoteActiveAccount? =
        mutableAccount.value ?: runCatching { vault.activeAccount() }.getOrNull()

    suspend fun restore(): RemoteActiveAccount? = lifecycle.withLock {
        if (mutableAccount.value != null) return@withLock mutableAccount.value
        val active = vault.activeAccount() ?: return@withLock null
        val server = server(active.origin)
        val failures = vault.roleFailures(active)
        val client = readStoredRole(active, RemoteRole.CLIENT)
        val hostReceipt = readStoredRole(active, RemoteRole.HOST)
        val hostCredential = hostReceipt.first
        if (client.first == null && hostCredential == null && client.second == null && hostReceipt.second == null) { vault.revokeActive(); return@withLock null }
        if (installation.existingIdentities() == null) {
            // 升级时必须保住两个旧身份，无法解密的旧角色不可被新密钥替换。
            (client.second ?: hostReceipt.second)?.let { throw RemoteRpcException(it) }
            installation.loadOrCreate(client.first?.identity, hostCredential?.identity)
        }
        currentCoroutineContext().ensureActive()
        vault.requireCurrent(active)
        api = server; mutableAccount.value = active
        startClientRole(active, failures.clientStop
            ?: if (client.first != null) null else client.second ?: failures.clientRegistration ?: "REMOTE_CREDENTIAL_MISSING")
        when {
            failures.hostStop != null -> publishHost(RemoteHostConnectionState.FAILED, failures.hostStop)
            hostCredential != null || hostReceipt.second == "REMOTE_IDENTITY_LOCKED" -> startHost(active, server)
            hostReceipt.second != null -> publishHost(RemoteHostConnectionState.FAILED,
                RemoteHostFailure(RemoteHostFailureStage.CREDENTIAL, requireNotNull(hostReceipt.second)))
            failures.hostRegistration != null -> publishHost(RemoteHostConnectionState.FAILED,
                RemoteHostFailure(RemoteHostFailureStage.REGISTRATION, failures.hostRegistration))
        }
        active
    }
    private fun readStoredRole(active: RemoteActiveAccount, role: RemoteRole): Pair<RemoteRoleCredential?, String?> = try {
        store.load(RemoteIdentityScope(active.origin, active.accountId, role)) to null
    } catch (_: RemoteIdentityLockedException) { null to "REMOTE_IDENTITY_LOCKED" }
      catch (_: RemoteIdentityCorruptedException) { null to "REMOTE_IDENTITY_CORRUPTED" }

    private fun startClientRole(active: RemoteActiveAccount, error: String?) {
        mutableClientFailure.value = error
        if (error != null) android.util.Log.w("RemoteAccountRuntime", "CLIENT_UNAVAILABLE $error")
        mutableState.value = if (error == null) RemoteAccountConnectionState.READY else RemoteAccountConnectionState.DISCONNECTED
        if (error == null) startClient(active)
    }
    suspend fun listHosts(): List<RemoteHostDevice> = presenceRefresh.withLock {
        val active = current()
        if (mutableClientFailure.value != null) return@withLock emptyList()
        val localHost = readStoredRole(active, RemoteRole.HOST).first?.identity?.deviceId?.value
        val installationHost = installation.loadOrCreate().host.deviceId.value
        withReadCredential(active, RemoteRole.CLIENT) { server, token -> server.listHosts(token) }.filter { it.deviceId != localHost && it.deviceId != installationHost }.also { devices ->
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
        val closingHost = synchronized(selectionLock) {
            selectionGeneration.incrementAndGet(); pendingConnect?.cancel(); pendingConnect = null
            mutableHostStandby.value = false
            mutableAccount.value = null
            mutableClientFailure.value = null
            mutableState.value = RemoteAccountConnectionState.DISCONNECTED
            publishHost(RemoteHostConnectionState.STOPPED, null)
            host.also { host = null }
        }
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
            old?.takeIf { installation.existingIdentities() == null }?.let { active ->
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
                { closingHost?.stop(); Unit },
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
        if (clientJob?.isActive == true || mutableClientFailure.value != null) return@synchronized
        requireActive(active)
        clientJob = scope.launch(start = CoroutineStart.LAZY) {
            var retry = 1_000L
            var rejectedToken: String? = null
            var refreshedAfterRejection = false
            val runningJob = currentCoroutineContext()[Job]
            try {
                while (isActive && mutableAccount.value == active) {
                    var service: RemoteSignalService? = null
                    var usedToken: String? = null
                    val failure = try {
                        val server = api ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
                        val credential = credential(active, RemoteRole.CLIENT, rejectedToken)
                        usedToken = credential.tokens.accessToken
                        if (rejectedToken != null) refreshedAfterRejection = true
                        rejectedToken = null
                        val ended = CompletableDeferred<String>()
                        val current = RemoteSignalService(context, scope, server,
                            RemoteIdentityScope(active.origin, active.accountId, RemoteRole.CLIENT), credential,
                            { requireActive(active) }, { id, role -> authorize(active, RemoteRole.CLIENT, id, role) },
                            onAuthenticatedPeer = { authenticated -> synchronized(selectionLock) {
                                requireActive(active); peers[authenticated.scope.connectionId] = authenticated
                            } }, onPeerClosed = ::peerClosed,
                            onClosed = { ended.complete(it) },
                            onHeartbeat = { scope.launch { refreshPresence(active) } },
                            iceServers = { id -> withReadCredential(active, RemoteRole.CLIENT) { api, token -> api.iceServers(token, id) } })
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
                        refreshedAfterRejection = false
                        scope.launch { refreshPresence(active) }
                        RemoteRpcException(safeClientErrorCode(ended.await(), "TRANSPORT_CLOSED"))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        // 只重建认证与传输，绝不重放旧隧道上的业务写入。
                        if (mutableAccount.value != active) return@launch
                        when (error) {
                            is RemoteIdentityLockedException -> RemoteRpcException("REMOTE_IDENTITY_LOCKED")
                            is RemoteIdentityCorruptedException -> RemoteRpcException("REMOTE_IDENTITY_CORRUPTED")
                            is RemoteRpcException -> RemoteRpcException(safeClientErrorCode(error.code, "CONTROL_CONNECTION_FAILED"))
                            else -> RemoteRpcException("CONTROL_CONNECTION_FAILED")
                        }
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
                    if (mutableAccount.value != active) return@launch
                    val refreshRejected = failure.code in clientReauthorizationCodes && !refreshedAfterRejection && usedToken != null
                    if (refreshRejected) rejectedToken = usedToken
                    else if (failure.code in clientReauthorizationCodes || failure.code !in clientRetryableCodes) {
                        persistClientTerminal(active, failure.code)
                        return@launch
                    }
                    delay(retry)
                    retry = (retry * 2).coerceAtMost(30_000L)
                }
            } finally {
                synchronized(selectionLock) {
                    if (clientJob === runningJob) clientJob = null
                }
            }
        }.also { it.start() }
    }
    private fun persistClientTerminal(active: RemoteActiveAccount, code: String) {
        if (mutableAccount.value != active) return
        try {
            vault.stopClientUntilLogin(active, code)
            synchronized(selectionLock) {
                if (mutableAccount.value == active) {
                    mutableClientFailure.value = code
                    mutableState.value = RemoteAccountConnectionState.DISCONNECTED
                    if (clientAvailable.isCompleted) clientAvailable = CompletableDeferred()
                    clientAvailable.completeExceptionally(RemoteRpcException(code))
                }
            }
        } catch (_: Exception) {
            failClosedActive(active)
        }
    }
    /** 角色围栏与账号级撤销都不可写时，只能立即关闭两个角色，避免内存状态放开未持久化终态。 */
    private fun failClosedActive(active: RemoteActiveAccount) {
        runCatching { vault.failClosed(active) }
        var closingHost: RemoteHostControl? = null
        var closingClient: RemoteSignalService? = null
        var closingJob: Job? = null
        var closingPeers: List<AuthenticatedRemotePeer> = emptyList()
        synchronized(selectionLock) {
            if (mutableAccount.value == active) {
                selectionGeneration.incrementAndGet()
                pendingConnect?.cancel(); pendingConnect = null
                mutableAccount.value = null
                mutableClientFailure.value = "REMOTE_CREDENTIAL_PERSISTENCE_FAILED"
                mutableState.value = RemoteAccountConnectionState.SIGNED_OUT
                mutableHosts.value = emptyList()
                mutableHostStandby.value = false
                publishHost(RemoteHostConnectionState.FAILED,
                    RemoteHostFailure(RemoteHostFailureStage.CONTROL, "REMOTE_CREDENTIAL_PERSISTENCE_FAILED"))
                closingHost = host.also { host = null }
                closingClient = client.also { client = null }
                closingJob = clientJob.also { clientJob = null }
                if (clientAvailable.isCompleted) clientAvailable = CompletableDeferred()
                clientAvailable.completeExceptionally(RemoteRpcException("REMOTE_CREDENTIAL_PERSISTENCE_FAILED"))
                closingPeers = peers.values.toList()
                api = null
            }
        }
        runCatching {
            closeRemoteConnectionResources(
                { closingJob?.cancel(); Unit },
                { closingHost?.stop(); Unit },
                { closingClient?.close(); Unit },
                *closingPeers.map { peer -> { peerClosed(peer.scope) } }.toTypedArray(),
            )
        }
    }
    private suspend fun refreshPresence(active: RemoteActiveAccount) {
        if (mutableAccount.value != active || presenceRefresh.isLocked) return
        try { listHosts() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            android.util.Log.w("RemoteAccountRuntime", "PRESENCE_REFRESH_FAILED:${safeRoleErrorCode((failure as? RemoteRpcException)?.code.orEmpty(), "CONNECTION_FAILED")}")
            synchronized(selectionLock) {
                if (mutableAccount.value == active) mutableHosts.value = mutableHosts.value.map { it.copy(online = false) }
            }
        }
    }
    fun refreshControlConnection() {
        val active = mutableAccount.value ?: return
        var refreshClient = false
        synchronized(selectionLock) {
            if (mutableAccount.value != active) return
            if (mutableHostState.value == RemoteHostConnectionState.FAILED) host?.wake()
            // 只有已经由授权登录或恢复启动过的 Client 才能重启，避免仅 Host 获授权时创建空转任务。
            if (mutableClientFailure.value == null) {
                val job = clientJob
                if (job == null || !job.isActive) startClient(active)
                refreshClient = true
            }
        }
        if (refreshClient) scope.launch { refreshPresence(active) }
    }
    private fun startHost(active: RemoteActiveAccount, server: RemoteServerApi) {
        val handler = hostHandler ?: return
        val owner = RemoteHostControl(scope, { rejected -> credential(active, RemoteRole.HOST, rejected) }, { ticket, closed ->
            RemoteSignalService(context, scope, server, RemoteIdentityScope(active.origin, active.accountId, RemoteRole.HOST),
                ticket, { requireActive(active) }, { id, role -> authorize(active, RemoteRole.HOST, id, role) }, handler,
                onAuthenticatedPeer = { peer -> synchronized(selectionLock) {
                    requireActive(active)
                    peers[peer.scope.connectionId] = peer
                } }, onPeerClosed = ::peerClosed, onClosed = closed,
                iceServers = { id -> withReadCredential(active, RemoteRole.HOST) { api, token -> api.iceServers(token, id) } })
        }, { failure, requiresLogin ->
            synchronized(selectionLock) {
                if (mutableAccount.value == active) mutableHostStandby.value = false
            }
            if (!requiresLogin) RemoteTerminalPersistence.PERSISTED else try {
                vault.stopHostUntilLogin(active, failure)
                RemoteTerminalPersistence.PERSISTED
            } catch (_: Exception) {
                failClosedActive(active)
                RemoteTerminalPersistence.FAILED_CLOSED
            }
        }) { state, failure -> synchronized(selectionLock) {
            if (mutableAccount.value == active) publishHost(state, failure)
        } }
        synchronized(selectionLock) {
            requireActive(active)
            host = owner
            mutableHostStandby.value = true
            owner.start()
        }
    }
    private fun publishHost(state: RemoteHostConnectionState, failure: RemoteHostFailure?) {
        mutableHostFailure.value = failure
        mutableHostState.value = state
        if (failure != null) android.util.Log.w(RemoteHostControl.TAG, "${failure.stage} ${failure.code}")
        else android.util.Log.i(RemoteHostControl.TAG, state.name)
    }
    private fun requireActive(active: RemoteActiveAccount) {
        check(mutableAccount.value == active) { "REMOTE_AUTHORIZATION_SUPERSEDED" }
        vault.requireCurrent(active)
    }
    private fun current(): RemoteActiveAccount = mutableAccount.value?.also(::requireActive) ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
    private suspend fun credential(active: RemoteActiveAccount, role: RemoteRole, rejectedToken: String? = null): RemoteRoleCredential {
        requireActive(active)
        val identityScope = RemoteIdentityScope(active.origin, active.accountId, role)
        return vault.credentialTransaction {
            requireActive(active)
            val failures = vault.roleFailures(active)
            val stopped = if (role == RemoteRole.CLIENT) failures.clientStop else failures.hostStop?.code
            if (stopped != null) throw RemoteRpcException(stopped)
            val existing = store.load(identityScope) ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
            if (existing.tokens.accessToken != rejectedToken && existing.tokens.accessTokenExpiresAt > System.currentTimeMillis() + 30_000) existing
            else {
                val server = api ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
                val result = RemoteTokenRotator(store, server).rotate(identityScope)
                requireActive(active)
                when (result) {
                    is RemoteTokenRotationResult.Rotated -> result.credential
                    is RemoteTokenRotationResult.Failed -> {
                        if (result.code in setOf("TOKEN_REUSED", "DEVICE_REVOKED", "TOKEN_EXPIRED", "AUTH_INVALID", "REMOTE_REFRESH_PERSISTENCE_FAILED")) {
                            // 目录与控制连接共用此角色的刷新票。在放开事务前发布终态，等待者不能重交旧票。
                            if (role == RemoteRole.CLIENT) {
                                persistClientTerminal(active, result.code)
                                synchronized(selectionLock) { client.takeIf { mutableAccount.value == active } }?.credentialRejected(result.code)
                            } else {
                                try { vault.stopHostUntilLogin(active, RemoteHostFailure(RemoteHostFailureStage.CREDENTIAL, result.code)) }
                                catch (_: Exception) { failClosedActive(active); throw RemoteRpcException("REMOTE_CREDENTIAL_PERSISTENCE_FAILED") }
                                synchronized(selectionLock) { host.takeIf { mutableAccount.value == active } }?.credentialRejected(result.code)
                            }
                        }
                        throw RemoteRpcException(result.code)
                    }
                }
            }
        }
    }
    /** 只重试幂等目录与中继配置读取，访问票被拒时按同一账号及角色轮换一次。 */
    private suspend fun <T> withReadCredential(active: RemoteActiveAccount, role: RemoteRole,
        read: suspend (RemoteServerApi, String) -> T): T {
        val server = api ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
        val token = credential(active, role).tokens.accessToken
        val result = try { read(server, token) } catch (failure: RemoteRpcException) {
            if (failure.code != "TOKEN_EXPIRED" && failure.code != "AUTH_INVALID") throw failure
            read(server, credential(active, role, token).tokens.accessToken)
        }
        requireActive(active)
        return result
    }
    private suspend fun authorize(active: RemoteActiveAccount, localRole: RemoteRole, id: String, expectedRole: RemoteRole): RemoteAuthorizedPeer {
        val descriptor = withReadCredential(active, localRole) { server, token -> server.peer(token, id) }
        requireActive(active)
        if (descriptor.role != expectedRole || descriptor.device.deviceId != id) throw RemoteRpcException("MEMBERSHIP_REQUIRED")
        val identityScope = RemoteIdentityScope(active.origin, active.accountId, localRole)
        vault.credentialTransaction {
            requireActive(active)
            val current = store.load(identityScope) ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
            val pin = RemotePeerPin(expectedRole, RemoteDeviceId(id), descriptor.publicKey)
            val updated = try { current.pin(pin) } catch (_: RemotePeerPinMismatchException) {
                throw RemoteRpcException("PEER_IDENTITY_MISMATCH")
            }
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
