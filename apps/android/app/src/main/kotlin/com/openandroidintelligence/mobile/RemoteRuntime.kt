package com.openandroidintelligence.mobile

import android.content.Context
import com.openandroidintelligence.conversation.data.remote.*
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.conversation.sanitizer.ContextInjectionFilter
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.encrypted.store.*
import com.openandroidintelligence.gateway.remote.auth.RemoteLoginResult
import com.openandroidintelligence.gateway.remote.auth.RemoteRoleRegistrationState
import com.openandroidintelligence.gateway.remote.authproduction.RemoteActiveAccount
import com.openandroidintelligence.gateway.remote.facade.PhoneRemoteRpcHostHandler
import com.openandroidintelligence.gateway.remote.identity.RemoteRole
import com.openandroidintelligence.gateway.remote.network.AuthenticatedRemotePeer
import com.openandroidintelligence.gateway.remote.network.RemoteHostDevice
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.mobile.assistant.*
import com.openandroidintelligence.mobile.conversations.*
import com.openandroidintelligence.mobile.plugins.DeviceCapabilitySyncState
import com.openandroidintelligence.mobile.plugins.ProductionPluginHost
import com.openandroidintelligence.mobile.plugins.RemotePhoneControlComposition
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RemoteWorkspaceState(
    val account: RemoteActiveAccount? = null,
    val hosts: List<RemoteHostDevice> = emptyList(),
    val endpoints: List<RemoteWorkspace> = emptyList(),
    val selected: EndpointKey? = null,
    val busy: Boolean = false,
    val errorCode: String? = null,
    val models: RemoteModelCatalog? = null,
    val modelBusy: Boolean = false,
    val accountAvatarBase64: String? = null,
    val backendFailures: Map<String, String> = emptyMap(),
)

/** App 的 Remote 装配面：Host/Agent 连接层与 Workspace 上下文层双层解耦，各自独立演化。 */
class RemoteRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pluginHost: ProductionPluginHost,
    private val grants: PairingGrantStateHolder,
    private val confirmLocally: (suspend (String, String, String) -> Boolean)? = null,
    accountFactory: (RemoteRpcHostHandler) -> RemoteAccountConnection = { ProductionRemoteAccountConnection(context, scope, it) },
    private val localDocumentKeyProvider: AesGcmKeyProvider? = null,
    private val recoveryPolicy: ConnectionRecoveryPolicy = ConnectionRecoveryPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    private val stateLock = Any()
    private val mutableAgentConnection = MutableStateFlow(AgentConnectionState())
    val agentConnection: StateFlow<AgentConnectionState> = mutableAgentConnection.asStateFlow()

    private val mutableWorkspaceContext = MutableStateFlow(WorkspaceContextState())
    val workspaceContext: StateFlow<WorkspaceContextState> = mutableWorkspaceContext.asStateFlow()

    private val mutableState = MutableStateFlow(RemoteWorkspaceState())
    val state: StateFlow<RemoteWorkspaceState> = mutableState.asStateFlow()

    private val mutableController = MutableStateFlow<WorkbenchController?>(null)
    val controller: StateFlow<WorkbenchController?> = mutableController.asStateFlow()

    val deviceCapabilityState = MutableStateFlow(DeviceCapabilitySyncState())

    private val selection = Mutex()
    private val switchGeneration = AtomicLong(0L)
    private val hydrationGeneration = AtomicLong(0L)
    private val hydrationControlLock = Any()
    private val workspaceCachePersistenceLock = Any()
    private var activeOperationJob: Job? = null
    private var startupRecoveryJob: Job? = null
    private var reconnectLoopJob: Job? = null
    private val hydrationJob = AtomicReference<Job?>(null)
    private var coldStartBootstrapped = false

    private val preferences = context.getSharedPreferences("remote-workspace-selection-v1", Context.MODE_PRIVATE)
    private val cachedWorkspacesByAgent = LinkedHashMap<AgentKey, List<RemoteWorkspace>>()

    private val phone by lazy {
        confirmLocally?.let { RemotePhoneControlComposition(context, scope, pluginHost, grants, it, localDocumentKeyProvider) }
            ?: RemotePhoneControlComposition(context, scope, pluginHost, grants, metadataKeyProvider = localDocumentKeyProvider)
    }

    private data class Registered(val access: ControlAccessBinding, val registration: AutoCloseable)
    private val registrationLock = Any()
    private val registered = mutableMapOf<RemoteRpcConnectionScope, Registered>()
    private val handler = object : RemoteRpcHostHandler {
        private val delegate = lazy { PhoneRemoteRpcHostHandler(scope, phone.router, ::access) }
        override suspend fun invoke(scope: RemoteRpcConnectionScope, method: String, params: JsonValue.JObject) =
            delegate.value.invoke(scope, method, params)
        override fun events(scope: RemoteRpcConnectionScope) = flow { emitAll(delegate.value.events(scope)) }
        override fun connectionClosed(scope: RemoteRpcConnectionScope) {
            val retired = synchronized(registrationLock) { registered.remove(scope) }
            // 取消与授权清理可能回调执行总线，不能占用登记锁。
            try { if (delegate.isInitialized()) delegate.value.disconnect(scope) }
            finally { retired?.registration?.close() }
        }
    }

    val accountRuntime = accountFactory(handler)
    val loginEntryBridge by lazy { RemoteLoginEntryBridge(context, this, scope) }

    // 第一层：Host / Agent 连接层句柄
    @Volatile private var channel: RemoteRpcChannel? = null
    @Volatile private var activeCarriers: List<RemoteSessionCarrier> = emptyList()
    @Volatile private var hydratedAgentForChannel: Pair<RemoteRpcConnectionScope, AgentKey>? = null
    @Volatile private var lastHydratedAtMs: Long = 0L

    // 第二层：Workspace 上下文层句柄
    @Volatile private var repository: RemoteConversationRepository? = null
    private var hotRepository: HotSwappableConversationRepository? = null
    private var hotAttachments: HotSwappableAttachmentCoordinator? = null
    private var hotMediaSource: HotSwappableHistoricalMediaSource? = null
    private var hotCatalog: HotSwappableCommandCatalogRepository? = null
    private var activeThreadRef: AtomicReference<String?>? = null
    private var workspaceOwnerJob: Job? = null
    private var ownerAttachments: RemoteAttachmentCoordinator? = null
    private var ownerMirror: EncryptedConversationMirror? = null
    private var ownerMedia: EncryptedHistoryMediaCache? = null
    private var ownerMediaSource: RemoteAuthenticatedMediaSource? = null
    private var ownerConversationScope: ConversationScope? = null

    private val assistantControllers = ConcurrentHashMap<String, WorkbenchController>()
    private val assistantPresence = ConcurrentHashMap<String, Pair<String?, Boolean>>()
    private val handoffs = ConcurrentHashMap<String, AssistantHandoffTarget>()
    private val assistantWrappers = ConcurrentHashMap<String, AssistantAttachmentDraftCoordinator>()
    private val assistantRepositories = ConcurrentHashMap<String, RemoteConversationRepository>()
    private var assistantWorkspace: AssistantConversationWorkspace? = null
    private var surfaceRepository: ((AtomicReference<String?>) -> RemoteConversationRepository)? = null

    private val modelSequence = AtomicLong()
    private val mutableAssistantVersion = MutableStateFlow(0L)
    val assistantWorkspaceVersion = mutableAssistantVersion.asStateFlow()

    val replyNotifications = ReplyNotificationSettings(context)
    private val keepAlive = AgentSessionKeepAlive(context)
    private val backgroundOwnership = Any()
    private var ownerActive = false
    @Volatile private var networkConnected = true
    private val networkLossEpoch = AtomicLong(0L)
    private val handledNetworkLossEpoch = AtomicLong(0L)
    private var workObserver: Job? = null
    private var reconcileObserver: Job? = null
    @Volatile private var foreground = false

    private val publisher = AndroidReplyNotificationPublisher(context, replyNotifications) { target ->
        assistantControllers.any { (token, controller) ->
            assistantPresence.values.any { it.first == token && it.second } &&
                controller.state.value.activeThreadId == target.conversationId &&
                target.agentId == workspaceContext.value.activeWorkspace?.let(::endpointId)
        }
    }

    internal val nativeConversationNotifications = NativeConversationNotifications(
        context,
        scope,
        publisher::update,
        titleFor = ::notificationTitle,
        cancel = publisher::cancel,
        forget = publisher::forget,
    )

    val localSettings by lazy {
        LocalSettingsRuntime(
            replyNotifications,
            nativeConversationNotifications::refreshActive,
            ::reloadDebounceSettings,
            { controller.value?.refreshThreads() },
        )
    }

    init {
        scope.launch {
            accountRuntime.hosts.collect { devices ->
                if (agentConnection.value.account == accountRuntime.account.value) {
                    updateLayerStates(agentTransform = { it.copy(hosts = devices) })
                }
            }
        }
        scope.launch {
            combine(
                nativeConversationNotifications.sessions,
                replyNotifications.enabled,
                replyNotifications.allowed,
            ) { _, _, _ -> Unit }.collect { refreshBackgroundWork() }
        }
        bootstrapColdStartCacheFirst()
    }

    private fun putCachedWorkspaces(agentKey: AgentKey, workspaces: List<RemoteWorkspace>) {
        synchronized(stateLock) {
            cachedWorkspacesByAgent[agentKey] = workspaces
        }
    }

    private fun clearCachedWorkspaces() {
        synchronized(stateLock) {
            cachedWorkspacesByAgent.clear()
        }
    }

    /** 原子推进水合代际并摘除旧水合引用，阻止旧水合继续覆写磁盘或内存工作区缓存。 */
    private fun invalidateHydration(): Job? = synchronized(hydrationControlLock) {
        hydrationGeneration.incrementAndGet()
        hydrationJob.getAndSet(null)
    }

    /** 在独立持久化锁下串行落盘工作区列表，并在 stateLock 内复检代际后更新内存缓存，避免磁盘 commit 阻塞状态锁。 */
    private fun persistAgentWorkspacesIfCurrent(
        agentKey: AgentKey,
        workspaces: List<RemoteWorkspace>,
        expectedGeneration: Long? = null,
    ): Boolean = synchronized(workspaceCachePersistenceLock) {
        if (expectedGeneration != null &&
            (hydrationGeneration.get() != expectedGeneration || agentConnection.value.activeAgent != agentKey)
        ) {
            return@synchronized false
        }
        saveAgentWorkspaces(agentKey, workspaces)
        synchronized(stateLock) {
            if (expectedGeneration != null &&
                (hydrationGeneration.get() != expectedGeneration || agentConnection.value.activeAgent != agentKey)
            ) {
                return@synchronized false
            }
            cachedWorkspacesByAgent[agentKey] = workspaces
        }
        true
    }

    /** 是否存在尚未在 selection 锁内完成拆线的断网代际。 */
    private fun hasPendingNetworkLoss(): Boolean = networkLossEpoch.get() > handledNetworkLossEpoch.get()

    /** 在 selection 锁内原子消费截至 targetEpoch 的断网清理，避免被取消时半拆线却提前吞掉断网代际。 */
    private suspend fun consumePendingNetworkLossLocked(targetEpoch: Long = networkLossEpoch.get()): Boolean {
        if (targetEpoch <= handledNetworkLossEpoch.get()) return false
        withContext(NonCancellable) {
            try {
                if (hotRepository?.isOnline == true) {
                    detachOnlineWorkspaceInPlace(StreamHealth.RECONNECTING)
                }
                closeChannelOnly()
            } finally {
                handledNetworkLossEpoch.updateAndGet { current -> maxOf(current, targetEpoch) }
            }
        }
        return true
    }

    private fun Throwable.isTrueCancellation(): Boolean =
        this is CancellationException && this !is TimeoutCancellationException

    private fun updateLayerStates(
        agentTransform: (AgentConnectionState) -> AgentConnectionState = { it },
        workspaceTransform: (WorkspaceContextState) -> WorkspaceContextState = { it },
    ) {
        synchronized(stateLock) {
            val prevWorkspace = mutableWorkspaceContext.value
            val nextAgent = agentTransform(mutableAgentConnection.value)
            val nextWorkspace = workspaceTransform(prevWorkspace)
            val agentKey = nextWorkspace.agentKey
            val workspaceCatalogChanged = nextWorkspace.agentKey != prevWorkspace.agentKey ||
                nextWorkspace.workspaces != prevWorkspace.workspaces ||
                nextWorkspace.catalogPhase != prevWorkspace.catalogPhase
            if (workspaceCatalogChanged &&
                agentKey != null &&
                agentKey == nextAgent.activeAgent &&
                (nextWorkspace.workspaces.isNotEmpty() || nextWorkspace.catalogPhase == WorkspaceCatalogPhase.READY)
            ) {
                cachedWorkspacesByAgent[agentKey] = nextWorkspace.workspaces
            }
            mutableAgentConnection.value = nextAgent
            mutableWorkspaceContext.value = nextWorkspace
            mutableState.value = synthesizeLegacyState(nextAgent, nextWorkspace)
        }
    }

    private fun synthesizeLegacyState(
        agent: AgentConnectionState,
        workspace: WorkspaceContextState,
    ): RemoteWorkspaceState {
        val mergedEndpoints = linkedMapOf<EndpointKey, RemoteWorkspace>()
        cachedWorkspacesByAgent.values.flatten().forEach { ws ->
            mergedEndpoints[ws.endpoint.key] = ws
        }
        workspace.workspaces.forEach { ws ->
            mergedEndpoints[ws.endpoint.key] = ws
        }
        return RemoteWorkspaceState(
            account = agent.account,
            hosts = agent.hosts,
            endpoints = mergedEndpoints.values.toList(),
            selected = workspace.activeWorkspace,
            busy = agent.busy,
            errorCode = agent.errorCode,
            models = workspace.models,
            modelBusy = workspace.modelBusy,
            accountAvatarBase64 = agent.accountAvatarBase64,
            backendFailures = agent.backendFailures,
        )
    }

    /** 冷启动缓存优先同步引导：命中本地账号与上次工作区缓存时首帧即进入会话页，后台静默建链与宽限期重试。 */
    private fun bootstrapColdStartCacheFirst() {
        val cachedAccount = accountRuntime.peekRestorableAccount() ?: return
        val avatarBase64 = avatar(cachedAccount)
        val cachedAgents = loadDiscoveredAgents(cachedAccount)
        cachedAgents.forEach { agent ->
            val list = loadCachedAgentWorkspaces(agent.key)
            if (list.isNotEmpty()) putCachedWorkspaces(agent.key, list)
        }
        val savedWorkspace = loadSavedSelection(cachedAccount)
        if (savedWorkspace == null) {
            updateLayerStates(
                agentTransform = {
                    it.copy(
                        account = cachedAccount,
                        agents = cachedAgents,
                        accountAvatarBase64 = avatarBase64,
                    )
                },
            )
            // 无上次工作区时，复用账号恢复入口；生产账号会启动已授权的控制角色并读取目录。
            startupRecoveryJob = restoreSessionIfAvailable()
            coldStartBootstrapped = true
            return
        }
        val agentKey = AgentKey.fromEndpointKey(savedWorkspace.endpoint.key)
        val cachedAgentWorkspaces = loadCachedAgentWorkspaces(agentKey).let { existing ->
            if (existing.any { it.endpoint.key == savedWorkspace.endpoint.key }) existing
            else existing + savedWorkspace
        }
        putCachedWorkspaces(agentKey, cachedAgentWorkspaces)
        val matchedAgent = cachedAgents.firstOrNull { it.key == agentKey }
        // 为消除冷启动首帧闪烁登录页，必须在此同步打开单个上次激活工作区镜像并挂载控制器；其余兄弟工作区由后续异步水合补齐。
        val installedOk = runCatching {
            installOfflineWorkspaceSynchronously(savedWorkspace, StreamHealth.CONNECTING)
        }.isSuccess
        if (!installedOk) return

        val cachedSessions = loadCachedWorkspaceSessionsSnapshot(listOf(savedWorkspace))
        coldStartBootstrapped = true
        updateLayerStates(
            agentTransform = {
                it.copy(
                    account = cachedAccount,
                    agents = cachedAgents,
                    activeAgent = agentKey,
                    activeHostName = matchedAgent?.hostName,
                    activeAgentName = matchedAgent?.displayName ?: savedWorkspace.endpoint.key.backendId.value,
                    phase = AgentConnectionPhase.CONNECTING,
                    busy = false,
                    offlineConfirmed = false,
                    errorCode = null,
                    accountAvatarBase64 = avatarBase64,
                )
            },
            workspaceTransform = {
                WorkspaceContextState(
                    agentKey = agentKey,
                    workspaces = cachedAgentWorkspaces,
                    catalogPhase = WorkspaceCatalogPhase.READY,
                    activeWorkspace = savedWorkspace.endpoint.key,
                    sessionsByWorkspace = cachedSessions,
                )
            },
        )
        startupRecoveryJob = scope.launch {
            runColdStartGracefulRecovery(cachedAccount, savedWorkspace)
        }
    }

    private class OfflineWorkspaceHandle(
        val owner: CompletableJob,
        val mirror: EncryptedConversationMirror,
        val media: EncryptedHistoryMediaCache,
        val local: ConversationScope,
        val hotRepo: HotSwappableConversationRepository,
        val hotAttach: HotSwappableAttachmentCoordinator,
        val mediaSource: HotSwappableHistoricalMediaSource,
        val hotCat: HotSwappableCommandCatalogRepository,
        val activeRef: AtomicReference<String?>,
        val controller: WorkbenchController,
    )

    private fun buildOfflineWorkspaceHandle(
        workspace: RemoteWorkspace,
        initialHealth: StreamHealth,
    ): OfflineWorkspaceHandle {
        val local = conversationScope(workspace.endpoint.key)
        val owner = SupervisorJob(scope.coroutineContext[Job])
        var mirror: EncryptedConversationMirror? = null
        var media: EncryptedHistoryMediaCache? = null
        var controller: WorkbenchController? = null
        try {
            mirror = EncryptedConversationMirror(context, local, localDocumentKeyProvider)
            val mediaSource = HotSwappableHistoricalMediaSource()
            media = EncryptedHistoryMediaCache(context, local, source = mediaSource, keyProvider = localDocumentKeyProvider)
            val ownedScope = CoroutineScope(scope.coroutineContext + owner)
            val hotRepo = HotSwappableConversationRepository(OfflineMirrorRepository(mirror), ownedScope, initialHealth)
            val hotAttach = HotSwappableAttachmentCoordinator()
            val activeRef = AtomicReference<String?>(null)
            val hotCat = HotSwappableCommandCatalogRepository {
                RemoteSessionException("COMMANDS_UNAVAILABLE_OFFLINE")
            }
            controller = createController(
                ownedScope = ownedScope,
                repo = hotRepo,
                catalogRepo = hotCat,
                mirror = mirror,
                media = media,
                attachments = hotAttach,
                local = local,
                active = activeRef,
                restore = true,
                initialOnline = false,
                canReadConversations = { hotRepo.isOnline },
            )
            return OfflineWorkspaceHandle(
                owner = owner,
                mirror = mirror,
                media = media,
                local = local,
                hotRepo = hotRepo,
                hotAttach = hotAttach,
                mediaSource = mediaSource,
                hotCat = hotCat,
                activeRef = activeRef,
                controller = controller,
            )
        } catch (failure: Throwable) {
            runCatching { controller?.close() }
            runCatching { media?.retireConnection() }
            runCatching { mirror?.closeNativeStore() }
            owner.cancel()
            throw failure
        }
    }

    private fun publishOfflineWorkspaceHandle(handle: OfflineWorkspaceHandle) {
        workspaceOwnerJob = handle.owner
        ownerMirror = handle.mirror
        ownerMedia = handle.media
        ownerConversationScope = handle.local
        hotRepository = handle.hotRepo
        hotAttachments = handle.hotAttach
        hotMediaSource = handle.mediaSource
        hotCatalog = handle.hotCat
        activeThreadRef = handle.activeRef
        nativeConversationNotifications.bind(handle.local.profileId, handle.local.accountId)
        mutableController.value = handle.controller
        observeBackgroundWork()
    }

    private fun installOfflineWorkspaceSynchronously(
        workspace: RemoteWorkspace,
        initialHealth: StreamHealth,
    ) {
        publishOfflineWorkspaceHandle(buildOfflineWorkspaceHandle(workspace, initialHealth))
    }

    private suspend fun runColdStartGracefulRecovery(
        expectedAccount: RemoteActiveAccount,
        targetWorkspace: RemoteWorkspace,
    ) {
        val ticket = switchGeneration.get()
        val deadline = clock() + recoveryPolicy.offlineGracePeriodMillis
        val maxAttempts = (recoveryPolicy.graceRetryAttempts + 1).coerceAtLeast(1)
        var lastErrorCode: String? = null
        for (attempt in 0 until maxAttempts) {
            currentCoroutineContext().ensureActive()
            if (switchGeneration.get() != ticket) return
            val lossEpochAtAttempt = networkLossEpoch.get()
            val result = selection.withLock {
                if (switchGeneration.get() != ticket) return@withLock AttemptResult.CANCELLED
                if (workspaceContext.value.activeWorkspace != targetWorkspace.endpoint.key) return@withLock AttemptResult.CANCELLED
                consumePendingNetworkLossLocked(lossEpochAtAttempt)
                try {
                    val restored = accountRuntime.restore() ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
                    if (restored != expectedAccount && accountRuntime.account.value != restored) {
                        throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
                    }
                    runCatching { refreshHostsInternal() }.onFailure { if (it.isTrueCancellation()) throw it }
                    connectAndUpgradeCurrentWorkspaceLocked(restored, targetWorkspace)
                    AttemptResult.SUCCESS
                } catch (failure: Exception) {
                    if (failure.isTrueCancellation()) throw failure
                    val code = errorCodeOf(failure)
                    lastErrorCode = code
                    if (isAuthRevokedError(code)) {
                        handleAuthRevoked(code)
                        AttemptResult.AUTH_REVOKED
                    } else {
                        AttemptResult.FAILED
                    }
                }
            }
            when (result) {
                AttemptResult.SUCCESS -> {
                    hydrateWorkspaces(force = true)
                    refreshModels()
                    return
                }
                AttemptResult.CANCELLED -> return
                AttemptResult.AUTH_REVOKED -> return
                AttemptResult.FAILED -> {
                    val hasMore = attempt + 1 < maxAttempts && clock() < deadline
                    if (hasMore) {
                        delayFn(recoveryPolicy.graceRetryDelayMillis)
                    }
                }
            }
        }
        // 宽限期耗尽仍不可达：优雅降级至已确认离线态并启动后台静默重连
        if (switchGeneration.get() == ticket &&
            workspaceContext.value.activeWorkspace == targetWorkspace.endpoint.key &&
            agentConnection.value.phase != AgentConnectionPhase.CONNECTED
        ) {
            hotRepository?.setConnectionStreamHealth(StreamHealth.RECONNECTING)
            updateLayerStates(
                agentTransform = {
                    it.copy(
                        phase = AgentConnectionPhase.OFFLINE,
                        offlineConfirmed = true,
                        reconnectingInPlace = false,
                        errorCode = lastErrorCode ?: "REMOTE_CONNECTION_FAILED",
                    )
                },
            )
            startBackgroundReconnectLoop(targetWorkspace, ticket)
        }
    }

    private enum class AttemptResult { SUCCESS, FAILED, AUTH_REVOKED, CANCELLED }

    /** 标题来自同账号、同端点的会话目录，路由用的编码身份不作为展示标题。 */
    private fun notificationTitle(target: ReplyNotificationTarget): String? {
        val account = agentConnection.value.account ?: return null
        if (target.profileId != profileId(account.origin) || target.accountId != account.accountId) return null

        fun matchesId(candidateId: String): Boolean {
            if (candidateId == target.conversationId) return true
            val decodedTarget = runCatching { RemoteLocalIds.decode(target.conversationId) }.getOrNull()
            if (decodedTarget != null && candidateId == decodedTarget) return true
            val decodedCandidate = runCatching { RemoteLocalIds.decode(candidateId) }.getOrNull()
            if (decodedCandidate != null) {
                if (decodedCandidate == target.conversationId) return true
                if (decodedTarget != null && decodedCandidate == decodedTarget) return true
            }
            return false
        }

        fun findTitle(threads: List<ConversationSummary>?): String? =
            threads?.firstOrNull { matchesId(it.id.value) }?.title
                ?.let { ContextInjectionFilter.sanitizeSummaryText(it) }
                ?.takeIf { it.isNotBlank() }

        val mainController = controller.value
        val mainState = mainController?.state?.value
        if (mainState != null && mainState.activeThreadId?.let(::matchesId) == true) {
            ContextInjectionFilter.sanitizeSummaryText(mainState.activeThreadTitle)
                .takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        val mainThreads = (mainState?.threads as? Loadable.Ready)?.value
        findTitle(mainThreads)?.let { return it }

        for (assistant in assistantControllers.values) {
            val aState = assistant.state.value
            if (aState.activeThreadId?.let(::matchesId) == true) {
                ContextInjectionFilter.sanitizeSummaryText(aState.activeThreadTitle)
                    .takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
            val aThreads = (aState.threads as? Loadable.Ready)?.value
            findTitle(aThreads)?.let { return it }
        }

        val owner = ownerConversationScope
        if (owner == null || (target.profileId == owner.profileId && target.accountId == owner.accountId &&
                (target.agentId == null || target.agentId == owner.gatewayId))
        ) {
            runCatching { findTitle(ownerMirror?.threads()) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun refreshBackgroundWork(foregrounded: Boolean = false) = synchronized(backgroundOwnership) {
        val work = ownerActive && networkConnected && repository != null && agentConnection.value.account != null &&
            replyNotifications.enabled.value && replyNotifications.allowed.value &&
            (controller.value?.hasActiveWork?.value == true || assistantWorkspace?.hasActiveWork?.value == true ||
                nativeConversationNotifications.sessions.value.any {
                    (it.status == NativeConversationStatus.QUEUED || it.status == NativeConversationStatus.RUNNING) &&
                        it.target.agentId == workspaceContext.value.activeWorkspace?.let(::endpointId)
                })
        if (foregrounded) keepAlive.onForegrounded(work) else keepAlive.update(work, foreground)
    }

    private fun observeBackgroundWork() {
        workObserver?.cancel()
        reconcileObserver?.cancel()
        synchronized(backgroundOwnership) { ownerActive = true }
        workObserver = scope.launch {
            val main = controller.value ?: return@launch
            val assistant = assistantWorkspace?.hasActiveWork ?: MutableStateFlow(false)
            combine(main.hasActiveWork, assistant) { _, _ -> Unit }.collect { refreshBackgroundWork() }
        }
        val activeRepo = repository
        val selectedKey = activeRepo?.workspace?.endpoint?.key ?: workspaceContext.value.activeWorkspace
        if (activeRepo != null && selectedKey != null && activeRepo.carrier.backend in setOf("harness", "codex")) {
            reconcileObserver = scope.launch {
                reconcileActiveNotifications(activeRepo, selectedKey)
                while (currentCoroutineContext().isActive) {
                    delay(15_000L)
                    reconcileActiveNotifications(activeRepo, selectedKey)
                }
            }
        }
    }
    private suspend fun reconcileActiveNotifications(repo: RemoteConversationRepository?, key: EndpointKey?) {
        if (!networkConnected || repo == null || key == null || repository !== repo || repo.workspace.endpoint.key != key) return
        if (repo.carrier.backend !in setOf("harness", "codex")) return
        val targetAgent = endpointId(key)
        val due = nativeConversationNotifications.reconciliationRequests(targetAgent)
        for (request in due) {
            if (!currentCoroutineContext().isActive || !networkConnected || repository !== repo || repo.workspace.endpoint.key != key) return
            try {
                val snapshot = repo.fetchReconciliationSnapshot(request.session.target.conversationId)
                nativeConversationNotifications.reconcile(
                    request,
                    snapshot.activeGenerationId?.let { it to "running" },
                    snapshot.messages,
                    activityKnown = snapshot.activityKnown,
                )
            } catch (failure: Exception) {
                if (failure.isTrueCancellation()) throw failure
                // 对账失败时保留待核对状态，等待后续恢复或周期重试。
            }
        }
    }

    val connectedAccountId: String? get() = agentConnection.value.account?.accountId
    val connectedProfileId: String? get() = agentConnection.value.account?.let { profileId(it.origin) }
    val connectedAgentName: String?
        get() {
            val selected = workspaceContext.value.activeWorkspace
            return workspaceContext.value.workspaces.firstOrNull { it.endpoint.key == selected }?.endpoint?.displayName
                ?: agentConnection.value.activeAgentName
        }

    /** 只借当前已认证端点和明确会话，不创建连接、不选工作区，也不创建对话。 */
    fun borrowPushSession(): com.openandroidintelligence.mobile.push.RemotePushSession? {
        val borrowed = channel ?: return null
        val ownedRepository = repository ?: return null
        val selected = workspaceContext.value.activeWorkspace ?: return null
        val thread = controller.value?.state?.value?.activeThreadId ?: return null
        val sessionId = runCatching { RemoteLocalIds.decode(thread) }.getOrNull() ?: return null
        val identity = com.openandroidintelligence.mobile.push.RemotePushIdentity(context)
        val peer = runCatching { accountRuntime.authenticatedPeer(borrowed.scope.connectionId) }.getOrNull() ?: return null
        val target = runCatching {
            identity.target(peer, selected.backendId.value, selected.workspaceId, sessionId)
        }.getOrNull() ?: return null
        val label = connectedAgentName ?: return null
        fun current(): Boolean = channel === borrowed && repository === ownedRepository &&
            workspaceContext.value.activeWorkspace == selected &&
            controller.value?.state?.value?.activeThreadId == thread &&
            accountRuntime.account.value?.let {
                it.origin == target.origin && it.accountId == target.accountId && it.epoch == target.accountEpoch
            } == true &&
            runCatching { accountRuntime.authenticatedPeer(borrowed.scope.connectionId) == peer }.getOrDefault(false) &&
            identity.isCurrent(target)
        if (!current()) return null
        return object : com.openandroidintelligence.mobile.push.RemotePushSession {
            override val target = target
            override val label = "$label · $sessionId"
            override fun isCurrent() = current()
            override suspend fun notifyReference(
                requestId: String,
                reference: com.openandroidintelligence.mobile.push.RemotePushReference,
                beforeSend: () -> Unit,
            ) {
                fun checkCurrent() {
                    beforeSend()
                    check(current()) { "PUSH_SESSION_CHANGED" }
                }
                val guarded = object : RemoteRpcChannel by borrowed {
                    override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue {
                        checkCurrent()
                        val result = borrowed.invoke(method, params, requestId, timeoutMs)
                        checkCurrent()
                        return result
                    }
                }
                val carrier = carriers(guarded).firstOrNull { it.backend == selected.backendId.value }
                    ?: error("PUSH_BACKEND_UNAVAILABLE")
                checkCurrent()
                carrier.prompt(sessionId, requestId, listOf(Json.of(mapOf("type" to "text", "text" to Json.canonical(reference.encode())))))
                checkCurrent()
            }
        }
    }

    private fun access(connection: RemoteRpcConnectionScope): ControlAccessBinding {
        var discarded: AutoCloseable? = null
        try {
            return synchronized(registrationLock) {
                val peer = accountRuntime.authenticatedPeer(connection.connectionId)
                check(peer.scope == connection && peer.role == RemoteRole.HOST) { "REMOTE_HOST_BINDING_MISMATCH" }
                val active = accountRuntime.account.value ?: error("ACCOUNT_AUTH_REQUIRED")
                check(active.origin == connection.origin && active.accountId == connection.accountId) { "REMOTE_ACCOUNT_BINDING_MISMATCH" }
                fun current() = accountRuntime.account.value == active &&
                    runCatching { accountRuntime.authenticatedPeer(connection.connectionId) == peer }.getOrDefault(false)
                registered[connection]?.let {
                    check(it.access.authorizationEpoch == active.epoch) { "REMOTE_AUTHORIZATION_CHANGED" }
                    return@synchronized it.access
                }
                val access = ControlAccessBinding(
                    RemoteControlPrincipal(
                        ControlOrigin.fromCanonical(connection.origin),
                        connection.accountId,
                        connection.clientDeviceId,
                        connection.hostDeviceId,
                        peer.peerFingerprint,
                        peer.localFingerprint,
                    ),
                    active.epoch,
                    connection.connectionId,
                    connection.generation,
                )
                val registration = phone.register(access, ::current)
                if (!current()) {
                    discarded = registration
                    error("REMOTE_AUTHORIZATION_CHANGED")
                }
                registered[connection] = Registered(access, registration)
                access
            }
        } finally {
            discarded?.close()
        }
    }

    private suspend fun beginAccountReset(
        authenticate: suspend () -> Boolean,
    ) {
        updateLayerStates(agentTransform = { it.copy(busy = true, errorCode = null) })
        try {
            retireAllAndClearController()
            val canRefreshHosts = try {
                authenticate()
            } catch (failure: Exception) {
                // 登录失败且原授权已撤销时，登录页不能继续展示先前缓存的账号。
                if (!failure.isTrueCancellation() && accountRuntime.account.value == null) {
                    updateLayerStates(agentTransform = { AgentConnectionState(busy = true) },
                        workspaceTransform = { WorkspaceContextState() })
                }
                throw failure
            }
            val acc = accountRuntime.account.value
            updateLayerStates(
                agentTransform = {
                    AgentConnectionState(
                        account = acc,
                        busy = true,
                        accountAvatarBase64 = acc?.let(::avatar),
                    )
                },
                workspaceTransform = { WorkspaceContextState() },
            )
            if (canRefreshHosts) {
                refreshHostsInternal()
            }
        } finally {
            updateLayerStates(agentTransform = { it.copy(busy = false) })
        }
    }

    fun login(origin: String, email: String, password: String) = scope.launch {
        loginEntryBridge.cancelLocal()
        try {
            beginAccountReset {
                canBrowseRegisteredHosts(accountRuntime.login(origin, email, password))
            }
        } catch (failure: Exception) {
            if (failure.isTrueCancellation()) throw failure
            fail(failure)
        }
    }

    suspend fun completeExternalLogin(token: String, loginAttemptId: String) {
        beginAccountReset {
            canBrowseRegisteredHosts(accountRuntime.loginAccountToken("https://dsh.r2049.cn", token, loginAttemptId))
        }
    }

    /** Client 与 Host 均未取得凭据时账号不会激活，必须把固定登录错误返回给登录页。 */
    private fun canBrowseRegisteredHosts(result: RemoteLoginResult): Boolean {
        if (result.client.state == RemoteRoleRegistrationState.FAILED &&
            result.host.state == RemoteRoleRegistrationState.FAILED
        ) {
            val reason = listOfNotNull(result.client.errorCode, result.host.errorCode)
                .firstOrNull { it != "REMOTE_ROLE_REGISTRATION_FAILED" }
            throw RemoteRpcException(reason ?: "REMOTE_ROLE_REGISTRATION_FAILED")
        }
        return result.client.state == RemoteRoleRegistrationState.REGISTERED
    }

    suspend fun completePairing(value: com.openandroidintelligence.gateway.remote.auth.RemotePairingImport, hostId: String) {
        beginAccountReset {
            accountRuntime.importPairing(value)
            false
        }
    }

    fun refreshPairedHost(hostId: String) = scope.launch {
        try {
            refreshHostsInternal()
            currentCoroutineContext().ensureActive()
            val host = agentConnection.value.hosts.firstOrNull { it.deviceId == hostId } ?: error("PAIRING_DEVICE_CONFLICT")
            discover(host).join()
        } catch (failure: Exception) {
            if (failure.isTrueCancellation()) throw failure
            fail(failure)
        }
    }

    fun restoreSessionIfAvailable(): Job {
        val activeStartup = startupRecoveryJob
        if (coldStartBootstrapped && activeStartup != null && activeStartup.isActive) return activeStartup
        if (coldStartBootstrapped && agentConnection.value.phase != AgentConnectionPhase.DISCONNECTED) {
            return CompletableDeferred(Unit)
        }
        if (accountRuntime.account.value != null && agentConnection.value.hosts.isNotEmpty()) {
            return CompletableDeferred(Unit)
        }
        return scope.launch {
            if (agentConnection.value.busy) return@launch
            updateLayerStates(agentTransform = { it.copy(busy = true) })
            try {
                val account = accountRuntime.account.value ?: accountRuntime.restore()
                if (account == null) {
                    updateLayerStates(agentTransform = { it.copy(account = null) })
                    return@launch
                }
                val cachedAgents = loadDiscoveredAgents(account)
                updateLayerStates(
                    agentTransform = {
                        it.copy(
                            account = account,
                            agents = if (it.agents.isEmpty()) cachedAgents else it.agents,
                            accountAvatarBase64 = avatar(account),
                        )
                    },
                )
                val saved = loadSavedSelection(account)
                if (saved != null && mutableController.value == null) {
                    val agentKey = AgentKey.fromEndpointKey(saved.endpoint.key)
                    val cachedAgentWorkspaces = loadCachedAgentWorkspaces(agentKey).let { list ->
                        if (list.any { it.endpoint.key == saved.endpoint.key }) list else list + saved
                    }
                    putCachedWorkspaces(agentKey, cachedAgentWorkspaces)
                    installOfflineWorkspaceSynchronously(saved, StreamHealth.CONNECTING)
                    val cachedSessions = withContext(Dispatchers.IO) {
                        loadCachedWorkspaceSessionsSnapshot(cachedAgentWorkspaces)
                    }
                    updateLayerStates(
                        agentTransform = {
                            it.copy(
                                activeAgent = agentKey,
                                activeAgentName = saved.endpoint.key.backendId.value,
                                phase = AgentConnectionPhase.CONNECTING,
                                offlineConfirmed = false,
                                busy = false,
                            )
                        },
                        workspaceTransform = {
                            WorkspaceContextState(
                                agentKey = agentKey,
                                workspaces = cachedAgentWorkspaces,
                                catalogPhase = WorkspaceCatalogPhase.READY,
                                activeWorkspace = saved.endpoint.key,
                                sessionsByWorkspace = cachedSessions,
                            )
                        },
                    )
                    runColdStartGracefulRecovery(account, saved)
                } else {
                    refreshHostsInternal()
                }
            } catch (failure: Exception) {
                if (failure.isTrueCancellation()) throw failure
                fail(failure)
            } finally {
                updateLayerStates(agentTransform = { it.copy(busy = false) })
            }
        }
    }

    fun dismissConnectionError() {
        updateLayerStates(agentTransform = { it.copy(errorCode = null) })
    }

    fun refreshHosts() = scope.launch {
        try {
            refreshHostsInternal()
        } catch (failure: Exception) {
            if (failure.isTrueCancellation()) throw failure
            fail(failure)
        }
    }

    private suspend fun refreshHostsInternal() {
        val active = accountRuntime.account.value ?: return
        val devices = accountRuntime.listHosts()
        if (accountRuntime.account.value == active && agentConnection.value.account == active) {
            val hostOnlineById = devices.associate { it.deviceId to it.online }
            updateLayerStates(
                agentTransform = { current ->
                    val updatedAgents = current.agents.map { agent ->
                        agent.copy(hostOnline = hostOnlineById[agent.key.hostId] ?: agent.hostOnline)
                    }
                    current.copy(hosts = devices, agents = updatedAgents, errorCode = null)
                },
            )
        }
    }

    /** 发现目标 Host 上的所有可用 Agent 与工作区；跨 Host 时不影响既有控制器停留会话页。 */
    fun discover(host: RemoteHostDevice): Job {
        val ticket = switchGeneration.incrementAndGet()
        val lossEpochAtStart = networkLossEpoch.get()
        reconnectLoopJob?.cancel()
        val previousJob = activeOperationJob
        return scope.launch {
            try {
                previousJob?.cancel()
                selection.withLock {
                    if (switchGeneration.get() != ticket) return@withLock
                    updateLayerStates(
                        agentTransform = {
                            it.copy(
                                busy = true,
                                switchingAgent = null,
                                reconnectingInPlace = false,
                                errorCode = null,
                            )
                        },
                    )
                    val activeAccount = agentConnection.value.account ?: accountRuntime.account.value
                    val previousWorkspace = workspaceContext.value.activeWorkspace?.let { activeKey ->
                        workspaceContext.value.workspaces.firstOrNull { it.endpoint.key == activeKey }
                            ?: activeAccount?.let { loadSavedSelectionIfMatches(it, activeKey) }
                    }
                    consumePendingNetworkLossLocked(lossEpochAtStart)
                    var borrowed = channel?.takeIf { it.scope.hostDeviceId == host.deviceId }
                    var discovery: RemoteRpcChannel? = null
                    var ownDiscoveryError: String? = null
                    try {
                        try {
                            borrowed?.invoke("harness.transport.describe", JsonValue.JObject(emptyList()))
                        } catch (failure: RemoteRpcException) {
                            if (failure.deliveryOutcome != RemoteDeliveryOutcome.NOT_SENT || failure.code == "RATE_LIMITED") {
                                throw failure
                            }
                            borrowed = null
                        } catch (_: TimeoutCancellationException) {
                            borrowed = null
                        }
                        if (borrowed == null) {
                            modelSequence.incrementAndGet()
                        }
                        if (borrowed == null && previousWorkspace != null && mutableController.value != null) {
                            // 跨宿主发现将替换底层隧道：先让原工作台原地退回本地镜像与重连中指示，绝不清空控制器踢回登录页。
                            detachOnlineWorkspaceInPlace(StreamHealth.RECONNECTING)
                        }
                        if (borrowed == null) {
                            closeChannelOnly()
                        }
                        discovery = borrowed ?: accountRuntime.connect(host.deviceId)
                        val discoveredCarriers = carriers(discovery)
                        val failures = linkedMapOf<String, String>()
                        val origin = RemoteServiceOrigin.fromCanonicalOrigin(discovery.scope.origin)
                        val accountId = discovery.scope.accountId
                        val discoveredAgents = mutableListOf<DiscoveredAgent>()
                        val discoveredWorkspacesByAgent = linkedMapOf<AgentKey, List<RemoteWorkspace>>()

                        discoveredCarriers.forEach { carrier ->
                            val agentKey = AgentKey(origin, accountId, host.deviceId, BackendId(carrier.backend))
                            val workspaces = try {
                                carrier.workspaces().map { ws ->
                                    ws.copy(
                                        endpoint = ws.endpoint.copy(
                                            displayName = "${host.name} · ${carrier.backend} · ${ws.endpoint.displayName}",
                                        ),
                                    )
                                }
                            } catch (failure: Exception) {
                                if (failure.isTrueCancellation()) throw failure
                                failures[carrier.backend] = errorCodeOf(failure)
                                emptyList()
                            }
                            if (workspaces.isNotEmpty() || failures[carrier.backend] == null) {
                                val backendLabel = backendDisplayName(carrier.backend)
                                discoveredAgents += DiscoveredAgent(
                                    key = agentKey,
                                    hostName = host.name,
                                    backendName = backendLabel,
                                    displayName = "${host.name} · $backendLabel",
                                    hostOnline = host.online,
                                    availability = EndpointAvailability.AVAILABLE,
                                )
                            }
                            if (failures[carrier.backend] == null) {
                                discoveredWorkspacesByAgent[agentKey] = workspaces
                                invalidateHydration()?.cancel()
                                persistAgentWorkspacesIfCurrent(agentKey, workspaces)
                            }
                        }

                        updateLayerStates(
                            agentTransform = { current ->
                                val mergedAgents = (current.agents.filterNot { it.key.hostId == host.deviceId } + discoveredAgents)
                                    .distinctBy { it.key }
                                activeAccount?.let { saveDiscoveredAgents(it, mergedAgents) }
                                current.copy(
                                    agents = mergedAgents,
                                    backendFailures = failures,
                                )
                            },
                            workspaceTransform = { current ->
                                discoveredWorkspacesByAgent.forEach { (agentKey, list) ->
                                    cachedWorkspacesByAgent[agentKey] = list
                                }
                                val activeAgent = current.agentKey
                                if (activeAgent != null &&
                                    activeAgent.hostId == host.deviceId &&
                                    failures[activeAgent.backendId.value] == null
                                ) {
                                    val updated = discoveredWorkspacesByAgent[activeAgent]
                                        ?: cachedWorkspacesByAgent[activeAgent].orEmpty()
                                    current.copy(
                                        workspaces = updated,
                                        catalogPhase = WorkspaceCatalogPhase.READY,
                                    )
                                } else {
                                    current
                                }
                            },
                        )
                    } catch (failure: Exception) {
                        if (failure.isTrueCancellation()) throw failure
                        ownDiscoveryError = errorCodeOf(failure)
                        fail(failure)
                    } finally {
                        withContext(NonCancellable) {
                            try {
                                if (discovery !== borrowed) discovery?.close()
                            } finally {
                                try {
                                    val currentAccount = accountRuntime.account.value
                                    val stillSameTarget = previousWorkspace != null &&
                                        activeAccount != null &&
                                        currentAccount == activeAccount &&
                                        workspaceContext.value.activeWorkspace == previousWorkspace.endpoint.key
                                    if (borrowed == null && stillSameTarget && scope.isActive && switchGeneration.get() == ticket) {
                                        val restoredOnline = runCatching {
                                            connectAndUpgradeCurrentWorkspaceLocked(currentAccount!!, previousWorkspace!!)
                                        }.onFailure {
                                            if (it.isTrueCancellation()) throw it
                                        }.isSuccess
                                        if (!restoredOnline) {
                                            updateLayerStates(
                                                agentTransform = {
                                                    it.copy(
                                                        phase = AgentConnectionPhase.OFFLINE,
                                                        offlineConfirmed = true,
                                                        reconnectingInPlace = false,
                                                    )
                                                },
                                            )
                                            startBackgroundReconnectLoop(previousWorkspace!!, ticket)
                                        }
                                        if (ownDiscoveryError != null && agentConnection.value.errorCode == null) {
                                            updateLayerStates(agentTransform = { it.copy(errorCode = ownDiscoveryError) })
                                        }
                                    }
                                } finally {
                                    if (switchGeneration.get() == ticket) {
                                        updateLayerStates(
                                            agentTransform = {
                                                it.copy(busy = false, switchingAgent = null, reconnectingInPlace = false)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    if (switchGeneration.get() == ticket) {
                        updateLayerStates(
                            agentTransform = {
                                it.copy(busy = false, switchingAgent = null, reconnectingInPlace = false)
                            },
                        )
                    }
                }
            }
        }.also { activeOperationJob = it }
    }

    /** 切换选定 Agent：跨 Host 与同 Host 跨 Backend 均可随时即时生效，绝不先清空 controller 踢回登录页。 */
    fun selectAgent(
        agent: DiscoveredAgent,
        preferredWorkspace: RemoteWorkspace? = null,
        initialThreadId: String? = null,
    ): Job = selectAgent(agent.key, preferredWorkspace, initialThreadId)

    fun selectAgent(
        agent: AgentKey,
        preferredWorkspace: RemoteWorkspace? = null,
        initialThreadId: String? = null,
    ): Job {
        val ticket = switchGeneration.incrementAndGet()
        val lossEpochAtStart = networkLossEpoch.get()
        reconnectLoopJob?.cancel()
        val previousOp = activeOperationJob
        return scope.launch {
            try {
                previousOp?.cancel()
                selection.withLock {
                    if (switchGeneration.get() != ticket) return@withLock
                    consumePendingNetworkLossLocked(lossEpochAtStart)
                    selectAgentLocked(agent, preferredWorkspace, initialThreadId, ticket)
                }
            } finally {
                withContext(NonCancellable) {
                    if (switchGeneration.get() == ticket) {
                        updateLayerStates(
                            agentTransform = {
                                it.copy(busy = false, switchingAgent = null, reconnectingInPlace = false)
                            },
                        )
                    }
                }
            }
        }.also { activeOperationJob = it }
    }

    private suspend fun selectAgentLocked(
        agent: AgentKey,
        preferredWorkspace: RemoteWorkspace?,
        initialThreadId: String?,
        ticket: Long,
    ) {
        val activeAccount = agentConnection.value.account ?: accountRuntime.account.value ?: run {
            fail(RemoteRpcException("ACCOUNT_AUTH_REQUIRED"))
            return
        }
        val prevActiveAgent = agentConnection.value.activeAgent
        val prevActiveWorkspace = workspaceContext.value.activeWorkspace?.let { key ->
            workspaceContext.value.workspaces.firstOrNull { it.endpoint.key == key }
                ?: loadSavedSelectionIfMatches(activeAccount, key)
        }
        val hostName = agentConnection.value.hosts.firstOrNull { it.deviceId == agent.hostId }?.name
            ?: agentConnection.value.agents.firstOrNull { it.key == agent }?.hostName
        val agentDisplayName = agentConnection.value.agents.firstOrNull { it.key == agent }?.displayName
            ?: hostName?.let { "$it · ${backendDisplayName(agent.backendId.value)}" }
            ?: backendDisplayName(agent.backendId.value)

        updateLayerStates(
            agentTransform = {
                it.copy(
                    busy = true,
                    switchingAgent = agent,
                    reconnectingInPlace = false,
                    errorCode = null,
                )
            },
            workspaceTransform = { it.copy(models = null, modelBusy = false) },
        )

        try {
            if (accountRuntime.account.value == null) {
                accountRuntime.restore()
            }
            if (channel != null && channel?.scope?.hostDeviceId != agent.hostId && hotRepository?.isOnline == true) {
                detachOnlineWorkspaceInPlace(StreamHealth.RECONNECTING)
            }
            val targetChannel = obtainOrConnectChannelLocked(agent.hostId)
            currentCoroutineContext().ensureActive()
            if (switchGeneration.get() != ticket) return

            val carrier = activeCarriers.firstOrNull { it.backend == agent.backendId.value }
                ?: throw RemoteSessionException("REMOTE_BACKEND_UNAVAILABLE")

            val rawWorkspaces = carrier.workspaces()
            val formattedWorkspaces = rawWorkspaces.map { ws ->
                ws.copy(
                    endpoint = ws.endpoint.copy(
                        displayName = if (hostName != null) {
                            "$hostName · ${carrier.backend} · ${ws.endpoint.displayName}"
                        } else {
                            ws.endpoint.displayName
                        },
                    ),
                )
            }
            invalidateHydration()?.cancel()
            persistAgentWorkspacesIfCurrent(agent, formattedWorkspaces)

            val targetWorkspace = chooseTargetWorkspace(agent, formattedWorkspaces, preferredWorkspace)
                ?: throw RemoteSessionException("REMOTE_WORKSPACE_NOT_FOUND")

            val discoveredEntry = DiscoveredAgent(
                key = agent,
                hostName = hostName ?: agent.hostId,
                backendName = backendDisplayName(agent.backendId.value),
                displayName = agentDisplayName,
                hostOnline = true,
                availability = EndpointAvailability.AVAILABLE,
            )

            val reuseExistingController = mutableController.value != null &&
                hotRepository?.isOnline == true &&
                agent == prevActiveAgent &&
                workspaceContext.value.activeWorkspace == targetWorkspace.endpoint.key
            if (reuseExistingController) {
                val activeCtrl = mutableController.value
                if (initialThreadId != null) {
                    activeCtrl?.openThread(initialThreadId)
                }
                refreshModels(activeCtrl?.state?.value?.activeThreadId)
            } else {
                switchWorkspaceControllerLocked(
                    account = activeAccount,
                    channel = targetChannel,
                    carrier = carrier,
                    workspace = targetWorkspace,
                    initialThreadId = initialThreadId,
                )
            }

            val cachedSessions = withContext(Dispatchers.IO) {
                loadCachedWorkspaceSessionsSnapshot(formattedWorkspaces)
            }
            updateLayerStates(
                agentTransform = { current ->
                    val mergedAgents = (current.agents.filterNot { it.key == agent } + discoveredEntry)
                        .distinctBy { it.key }
                    saveDiscoveredAgents(activeAccount, mergedAgents)
                    current.copy(
                        account = activeAccount,
                        agents = mergedAgents,
                        activeAgent = agent,
                        activeHostName = hostName ?: current.hosts.firstOrNull { it.deviceId == agent.hostId }?.name,
                        activeAgentName = agentDisplayName,
                        phase = AgentConnectionPhase.CONNECTED,
                        busy = false,
                        switchingAgent = null,
                        reconnectingInPlace = false,
                        offlineConfirmed = false,
                        errorCode = null,
                    )
                },
                workspaceTransform = {
                    WorkspaceContextState(
                        agentKey = agent,
                        workspaces = formattedWorkspaces,
                        catalogPhase = WorkspaceCatalogPhase.READY,
                        activeWorkspace = targetWorkspace.endpoint.key,
                        sessionsByWorkspace = cachedSessions,
                    )
                },
            )
            hydrateWorkspaces(force = true)
        } catch (failure: Exception) {
            if (failure.isTrueCancellation()) throw failure
            val code = errorCodeOf(failure)
            if (isAuthRevokedError(code)) {
                handleAuthRevoked(code)
            } else {
                val sameHostChannelStillOnline = channel != null &&
                    channel?.scope?.hostDeviceId == prevActiveAgent?.hostId &&
                    hotRepository?.isOnline == true
                if (!sameHostChannelStillOnline && mutableController.value != null) {
                    detachOnlineWorkspaceInPlace(StreamHealth.RECONNECTING)
                }
                updateLayerStates(
                    agentTransform = {
                        it.copy(
                            busy = false,
                            switchingAgent = null,
                            reconnectingInPlace = false,
                            errorCode = code,
                            phase = if (!sameHostChannelStillOnline && mutableController.value != null && it.activeAgent != null) {
                                AgentConnectionPhase.OFFLINE
                            } else {
                                it.phase
                            },
                            offlineConfirmed = !sameHostChannelStillOnline && mutableController.value != null && it.activeAgent != null,
                        )
                    },
                )
                if (!sameHostChannelStillOnline &&
                    mutableController.value != null &&
                    prevActiveWorkspace != null &&
                    switchGeneration.get() == ticket
                ) {
                    startBackgroundReconnectLoop(prevActiveWorkspace, ticket)
                }
            }
        } finally {
            withContext(NonCancellable) {
                if (switchGeneration.get() == ticket) {
                    updateLayerStates(
                        agentTransform = {
                            it.copy(busy = false, switchingAgent = null, reconnectingInPlace = false)
                        },
                    )
                }
            }
        }
    }

    /** 兼容旧调用入口，委托至双层工作区切换。 */
    fun select(endpoint: RemoteWorkspace): Job = selectWorkspace(endpoint)

    /**
     * 切换工作区：
     * - 同一 Agent 下切换：严禁断开/重建底层连接或认证会话，复用现有通道与 Carrier，仅切换工作区上下文；
     * - 跨 Agent 切换：在锁内委托给 [selectAgentLocked]。
     */
    fun selectWorkspace(workspace: RemoteWorkspace, initialThreadId: String? = null): Job {
        val targetAgent = AgentKey.fromEndpointKey(workspace.endpoint.key)
        val ticket = switchGeneration.incrementAndGet()
        val lossEpochAtStart = networkLossEpoch.get()
        reconnectLoopJob?.cancel()
        val previousOp = activeOperationJob
        return scope.launch {
            try {
                previousOp?.cancel()
                selection.withLock {
                    if (switchGeneration.get() != ticket) return@withLock
                    consumePendingNetworkLossLocked(lossEpochAtStart)
                    val currentAgent = agentConnection.value.activeAgent
                    if (currentAgent != targetAgent) {
                        selectAgentLocked(targetAgent, preferredWorkspace = workspace, initialThreadId = initialThreadId, ticket = ticket)
                        return@withLock
                    }

                    val activeAccount = agentConnection.value.account ?: accountRuntime.account.value ?: run {
                        fail(RemoteRpcException("ACCOUNT_AUTH_REQUIRED"))
                        return@withLock
                    }
                    val currentChannel = channel
                    val currentOnlineCarrier = activeCarriers.firstOrNull { it.backend == targetAgent.backendId.value }

                    // 若就是当前工作区：在线时直接打开目标会话，离线时就地重连
                    if (workspaceContext.value.activeWorkspace == workspace.endpoint.key) {
                        val activeCtrl = controller.value
                        if (activeCtrl != null && hotRepository?.isOnline == true) {
                            initialThreadId?.let(activeCtrl::openThread)
                            return@withLock
                        }
                        if (activeCtrl != null) {
                            launchInPlaceReconnectUnderLock(activeAccount, workspace, ticket, lossEpochAtStart)
                            return@withLock
                        }
                    }

                    updateLayerStates(
                        agentTransform = { it.copy(busy = true, reconnectingInPlace = false, errorCode = null) },
                        workspaceTransform = { it.copy(models = null, modelBusy = false) },
                    )

                    try {
                        val reusableChannel = currentChannel?.takeIf {
                            it.scope.hostDeviceId == targetAgent.hostId &&
                                it.scope.accountId == activeAccount.accountId &&
                                (hotRepository?.isOnline == true || agentConnection.value.phase == AgentConnectionPhase.CONNECTED)
                        }
                        val reusableCarrier = if (reusableChannel != null) {
                            currentOnlineCarrier ?: carriers(reusableChannel).firstOrNull { it.backend == targetAgent.backendId.value }
                        } else null

                        if (reusableChannel != null && reusableCarrier != null) {
                            // 在线复用底层通道与 Carrier！只更换工作区上下文与控制器
                            val resolvedWorkspace = workspaceContext.value.workspaces
                                .firstOrNull { it.endpoint.key == workspace.endpoint.key } ?: workspace
                            switchWorkspaceControllerLocked(
                                account = activeAccount,
                                channel = reusableChannel,
                                carrier = reusableCarrier,
                                workspace = resolvedWorkspace,
                                initialThreadId = initialThreadId,
                            )
                            updateLayerStates(
                                agentTransform = {
                                    it.copy(
                                        phase = AgentConnectionPhase.CONNECTED,
                                        offlineConfirmed = false,
                                        reconnectingInPlace = false,
                                        busy = false,
                                        errorCode = null,
                                    )
                                },
                                workspaceTransform = { current ->
                                    val list = if (current.workspaces.any { it.endpoint.key == resolvedWorkspace.endpoint.key }) {
                                        current.workspaces
                                    } else {
                                        current.workspaces + resolvedWorkspace
                                    }
                                    current.copy(
                                        agentKey = targetAgent,
                                        workspaces = list,
                                        activeWorkspace = resolvedWorkspace.endpoint.key,
                                    )
                                },
                            )
                            controller.value?.let { ctrl ->
                                refreshModels(ctrl.state.value.activeThreadId)
                            }
                            hydrateWorkspaces(force = false)
                        } else {
                            // 同 Agent 处于离线状态时切换工作区：先就地切换本地镜像视图，再后台静默建链
                            switchOfflineWorkspaceOnlyLocked(workspace, initialThreadId)
                            launchInPlaceReconnectUnderLock(activeAccount, workspace, ticket, lossEpochAtStart)
                        }
                    } catch (failure: Exception) {
                        if (failure.isTrueCancellation()) throw failure
                        val code = errorCodeOf(failure)
                        if (isAuthRevokedError(code)) handleAuthRevoked(code) else fail(failure)
                    } finally {
                        withContext(NonCancellable) {
                            if (switchGeneration.get() == ticket) {
                                updateLayerStates(agentTransform = { it.copy(busy = false) })
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    if (switchGeneration.get() == ticket) {
                        updateLayerStates(
                            agentTransform = {
                                it.copy(busy = false, switchingAgent = null, reconnectingInPlace = false)
                            },
                        )
                    }
                }
            }
        }.also { activeOperationJob = it }
    }

    /** 自动拉取当前 Agent 下的全部可用工作区与各工作区下的会话列表（打开会话菜单或建链后调用）。 */
    fun hydrateWorkspaces(force: Boolean = false): Job {
        val currentAgent = agentConnection.value.activeAgent ?: return CompletableDeferred(Unit)
        val currentChannel = channel
        val currentCarrier = activeCarriers.firstOrNull { it.backend == currentAgent.backendId.value }
        val alreadyHydrated = hydratedAgentForChannel == (currentChannel?.scope to currentAgent)
        val freshWindow = lastHydratedAtMs > 0L && (clock() - lastHydratedAtMs) in 0L until WORKSPACE_HYDRATION_STALE_WINDOW_MS
        if (!force && alreadyHydrated && freshWindow && workspaceContext.value.catalogPhase == WorkspaceCatalogPhase.READY) {
            return CompletableDeferred(Unit)
        }
        val (previousHydrationToCancel, nextJob) = synchronized(hydrationControlLock) {
            val activeHydration = hydrationJob.get()
            if (activeHydration?.isActive == true && !force) {
                return activeHydration
            }
            val generation = hydrationGeneration.incrementAndGet()
            val previousHydration = activeHydration
            val created = scope.launch(start = CoroutineStart.LAZY) {
                previousHydration?.cancelAndJoin()
                currentCoroutineContext().ensureActive()
                if (hydrationGeneration.get() != generation || agentConnection.value.activeAgent != currentAgent) return@launch
                val cached = loadCachedAgentWorkspaces(currentAgent)
                val effective = if (workspaceContext.value.workspaces.isNotEmpty()) workspaceContext.value.workspaces else cached
                val missing = effective.filterNot { it.endpoint.key in workspaceContext.value.sessionsByWorkspace }
                if (missing.isNotEmpty() || (cached.isNotEmpty() && workspaceContext.value.workspaces.isEmpty())) {
                    val cachedSessions = if (missing.isNotEmpty()) {
                        withContext(Dispatchers.IO) { loadCachedWorkspaceSessionsSnapshot(missing) }
                    } else emptyMap()
                    currentCoroutineContext().ensureActive()
                    if (hydrationGeneration.get() != generation || agentConnection.value.activeAgent != currentAgent) return@launch
                    updateLayerStates(
                        workspaceTransform = { current ->
                            if (hydrationGeneration.get() == generation && current.agentKey == currentAgent) {
                                val mergedWorkspaces = if (current.workspaces.isEmpty()) cached else current.workspaces
                                current.copy(
                                    workspaces = mergedWorkspaces,
                                    catalogPhase = if (mergedWorkspaces.isNotEmpty() && current.catalogPhase == WorkspaceCatalogPhase.IDLE) {
                                        WorkspaceCatalogPhase.READY
                                    } else current.catalogPhase,
                                    sessionsByWorkspace = cachedSessions + current.sessionsByWorkspace,
                                )
                            } else current
                        },
                    )
                }
                if (currentChannel == null || currentCarrier == null || currentChannel.scope.hostDeviceId != currentAgent.hostId) {
                    if (effective.isNotEmpty() && workspaceContext.value.catalogPhase != WorkspaceCatalogPhase.READY) {
                        updateLayerStates(
                            workspaceTransform = { current ->
                                if (hydrationGeneration.get() == generation && current.agentKey == currentAgent &&
                                    current.catalogPhase != WorkspaceCatalogPhase.READY
                                ) {
                                    current.copy(catalogPhase = WorkspaceCatalogPhase.READY)
                                } else current
                            },
                        )
                    }
                    return@launch
                }
                if (workspaceContext.value.workspaces.isEmpty() || workspaceContext.value.catalogPhase != WorkspaceCatalogPhase.READY) {
                    updateLayerStates(
                        workspaceTransform = { current ->
                            if (hydrationGeneration.get() == generation && current.agentKey == currentAgent) {
                                current.copy(catalogPhase = WorkspaceCatalogPhase.LOADING, catalogErrorCode = null)
                            } else current
                        },
                    )
                }
                try {
                    val hostName = agentConnection.value.activeHostName
                        ?: agentConnection.value.hosts.firstOrNull { it.deviceId == currentAgent.hostId }?.name
                    val workspaces = currentCarrier.workspaces().map { ws ->
                        ws.copy(
                            endpoint = ws.endpoint.copy(
                                displayName = if (hostName != null) {
                                    "$hostName · ${currentCarrier.backend} · ${ws.endpoint.displayName}"
                                } else {
                                    ws.endpoint.displayName
                                },
                            ),
                        )
                    }
                    currentCoroutineContext().ensureActive()
                    if (!persistAgentWorkspacesIfCurrent(currentAgent, workspaces, expectedGeneration = generation)) return@launch
                    val sessionsMap = linkedMapOf<EndpointKey, Loadable<List<ConversationSummary>>>()
                    workspaces.forEach { ws ->
                        sessionsMap[ws.endpoint.key] = try {
                            Loadable.Ready(fetchAllWorkspaceSessions(currentCarrier, ws))
                        } catch (failure: Exception) {
                            if (failure.isTrueCancellation()) throw failure
                            val fallback = workspaceContext.value.sessionsByWorkspace[ws.endpoint.key]
                            (fallback as? Loadable.Ready) ?: Loadable.Failed(errorCodeOf(failure))
                        }
                    }
                    if (hydrationGeneration.get() == generation && agentConnection.value.activeAgent == currentAgent) {
                        updateLayerStates(
                            workspaceTransform = { current ->
                                if (hydrationGeneration.get() == generation && current.agentKey == currentAgent) {
                                    hydratedAgentForChannel = currentChannel.scope to currentAgent
                                    lastHydratedAtMs = clock()
                                    current.copy(
                                        workspaces = workspaces,
                                        catalogPhase = WorkspaceCatalogPhase.READY,
                                        catalogErrorCode = null,
                                        sessionsByWorkspace = sessionsMap,
                                    )
                                } else current
                            },
                        )
                    }
                } catch (failure: Exception) {
                    if (failure.isTrueCancellation()) throw failure
                    if (hydrationGeneration.get() == generation && agentConnection.value.activeAgent == currentAgent) {
                        updateLayerStates(
                            workspaceTransform = { current ->
                                if (hydrationGeneration.get() == generation && current.agentKey == currentAgent) {
                                    current.copy(
                                        catalogPhase = WorkspaceCatalogPhase.FAILED,
                                        catalogErrorCode = errorCodeOf(failure),
                                    )
                                } else current
                            },
                        )
                    }
                }
            }
            hydrationJob.set(created)
            previousHydration to created
        }
        previousHydrationToCancel?.cancel()
        nextJob.start()
        return nextJob
    }

    /** 在指定工作区下创建新会话（跨工作区时先轻量切换再新建）。 */
    fun createSessionInWorkspace(workspace: RemoteWorkspace): Job {
        val activeKey = workspaceContext.value.activeWorkspace
        if (activeKey == workspace.endpoint.key) {
            controller.value?.createThread()
            return CompletableDeferred(Unit)
        }
        return scope.launch {
            selectWorkspace(workspace).join()
            if (workspaceContext.value.activeWorkspace == workspace.endpoint.key) {
                controller.value?.createThread()
            }
        }
    }

    /** 离线态点击「离线模式 / 重试」：全程停留在会话页就地重连，严禁销毁控制器或跳回登录页。 */
    fun reconnectInPlace(): Job {
        val currentAgentState = agentConnection.value
        if (currentAgentState.reconnectingInPlace) return activeOperationJob ?: CompletableDeferred(Unit)
        val activeKey = workspaceContext.value.activeWorkspace ?: return refreshHosts()
        val targetWorkspace = workspaceContext.value.workspaces.firstOrNull { it.endpoint.key == activeKey }
            ?: currentAgentState.account?.let { loadSavedSelectionIfMatches(it, activeKey) }
            ?: return refreshHosts()
        val expectedAccount = currentAgentState.account ?: accountRuntime.account.value ?: accountRuntime.peekRestorableAccount()
        if (expectedAccount == null) {
            return scope.launch {
                selection.withLock { handleAuthRevoked("ACCOUNT_AUTH_REQUIRED") }
            }
        }

        val ticket = switchGeneration.incrementAndGet()
        val lossEpochAtStart = networkLossEpoch.get()
        reconnectLoopJob?.cancel()
        val previousOp = activeOperationJob
        hotRepository?.setConnectionStreamHealth(StreamHealth.RECONNECTING)
        updateLayerStates(
            agentTransform = {
                it.copy(
                    phase = AgentConnectionPhase.RECONNECTING,
                    reconnectingInPlace = true,
                    errorCode = null,
                )
            },
        )

        return scope.launch {
            try {
                previousOp?.cancel()
                selection.withLock {
                    if (switchGeneration.get() != ticket) return@withLock
                    launchInPlaceReconnectUnderLock(expectedAccount, targetWorkspace, ticket, lossEpochAtStart)
                }
            } finally {
                withContext(NonCancellable) {
                    if (switchGeneration.get() == ticket) {
                        updateLayerStates(
                            agentTransform = {
                                it.copy(
                                    reconnectingInPlace = false,
                                    phase = if (it.reconnectingInPlace && it.phase == AgentConnectionPhase.RECONNECTING && it.activeAgent != null) {
                                        AgentConnectionPhase.OFFLINE
                                    } else {
                                        it.phase
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }.also { activeOperationJob = it }
    }

    private suspend fun launchInPlaceReconnectUnderLock(
        expectedAccount: RemoteActiveAccount,
        targetWorkspace: RemoteWorkspace,
        ticket: Long,
        lossEpochAtStart: Long,
    ) {
        consumePendingNetworkLossLocked(lossEpochAtStart)
        hotRepository?.setConnectionStreamHealth(StreamHealth.RECONNECTING)
        updateLayerStates(
            agentTransform = {
                it.copy(
                    phase = AgentConnectionPhase.RECONNECTING,
                    reconnectingInPlace = true,
                    errorCode = null,
                )
            },
        )
        try {
            val restored = accountRuntime.restore() ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
            if (restored != expectedAccount && accountRuntime.account.value != restored) {
                throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
            }
            runCatching { refreshHostsInternal() }.onFailure { if (it.isTrueCancellation()) throw it }
            connectAndUpgradeCurrentWorkspaceLocked(restored, targetWorkspace)
            if (switchGeneration.get() == ticket) {
                hydrateWorkspaces(force = true)
                refreshModels()
            }
        } catch (failure: Exception) {
            if (failure.isTrueCancellation()) throw failure
            val code = errorCodeOf(failure)
            if (isAuthRevokedError(code)) {
                handleAuthRevoked(code)
            } else if (switchGeneration.get() == ticket) {
                hotRepository?.setConnectionStreamHealth(StreamHealth.RECONNECTING)
                updateLayerStates(
                    agentTransform = {
                        it.copy(
                            phase = AgentConnectionPhase.OFFLINE,
                            offlineConfirmed = true,
                            reconnectingInPlace = false,
                            errorCode = code,
                        )
                    },
                )
                startBackgroundReconnectLoop(targetWorkspace, ticket)
            }
        }
    }

    private fun startBackgroundReconnectLoop(targetWorkspace: RemoteWorkspace, ticket: Long) {
        reconnectLoopJob?.cancel()
        reconnectLoopJob = scope.launch {
            var delayMs = recoveryPolicy.backgroundReconnectInitialDelayMillis
            while (isActive &&
                switchGeneration.get() == ticket &&
                !agentConnection.value.reconnectingInPlace &&
                agentConnection.value.phase == AgentConnectionPhase.OFFLINE &&
                workspaceContext.value.activeWorkspace == targetWorkspace.endpoint.key
            ) {
                delayFn(delayMs)
                if (!isActive ||
                    switchGeneration.get() != ticket ||
                    agentConnection.value.reconnectingInPlace ||
                    agentConnection.value.phase != AgentConnectionPhase.OFFLINE ||
                    workspaceContext.value.activeWorkspace != targetWorkspace.endpoint.key
                ) break

                val lossEpochAtAttempt = networkLossEpoch.get()
                val outcome = selection.withLock {
                    if (switchGeneration.get() != ticket ||
                        workspaceContext.value.activeWorkspace != targetWorkspace.endpoint.key
                    ) return@withLock AttemptResult.CANCELLED
                    consumePendingNetworkLossLocked(lossEpochAtAttempt)
                    try {
                        val acc = accountRuntime.restore() ?: throw RemoteRpcException("ACCOUNT_AUTH_REQUIRED")
                        runCatching { refreshHostsInternal() }.onFailure { if (it.isTrueCancellation()) throw it }
                        connectAndUpgradeCurrentWorkspaceLocked(acc, targetWorkspace)
                        AttemptResult.SUCCESS
                    } catch (failure: Exception) {
                        if (failure.isTrueCancellation()) throw failure
                        val code = errorCodeOf(failure)
                        if (isAuthRevokedError(code)) {
                            handleAuthRevoked(code)
                            AttemptResult.AUTH_REVOKED
                        } else {
                            AttemptResult.FAILED
                        }
                    }
                }
                when (outcome) {
                    AttemptResult.SUCCESS -> {
                        hydrateWorkspaces(force = true)
                        refreshModels()
                        break
                    }
                    AttemptResult.CANCELLED -> break
                    AttemptResult.AUTH_REVOKED -> break
                    AttemptResult.FAILED -> {
                        delayMs = (delayMs * 2).coerceAtMost(recoveryPolicy.backgroundReconnectMaxDelayMillis)
                    }
                }
            }
        }
    }

    /** 复用或重建 Host 通道（仅跨 Host 或当前通道失效时连接，同 Host 复用当前通道）。 */
    private suspend fun obtainOrConnectChannelLocked(hostId: String): RemoteRpcChannel {
        val existing = channel
        if (existing != null && existing.scope.hostDeviceId == hostId) {
            val alive = try {
                existing.invoke("harness.transport.describe", JsonValue.JObject(emptyList()))
                true
            } catch (failure: RemoteRpcException) {
                failure.deliveryOutcome != RemoteDeliveryOutcome.NOT_SENT || failure.code == "RATE_LIMITED"
            } catch (failure: Exception) {
                if (failure.isTrueCancellation()) throw failure
                false
            }
            if (alive) {
                if (activeCarriers.isEmpty()) activeCarriers = carriers(existing)
                return existing
            }
        }
        closeChannelOnly()
        val opened = accountRuntime.connect(hostId)
        channel = opened
        activeCarriers = carriers(opened)
        hydratedAgentForChannel = null
        lastHydratedAtMs = 0L
        return opened
    }
    private class OnlineWorkspaceBinding(
        val mediaSource: RemoteAuthenticatedMediaSource,
        val attachments: RemoteAttachmentCoordinator,
        val repo: RemoteConversationRepository,
        val surfaceRepo: (AtomicReference<String?>) -> RemoteConversationRepository,
    )

    private fun buildOnlineWorkspaceBinding(
        account: RemoteActiveAccount,
        targetChannel: RemoteRpcChannel,
        peer: AuthenticatedRemotePeer,
        carrier: RemoteSessionCarrier,
        confirmed: RemoteWorkspace,
        ownerJob: Job,
        ownedScope: CoroutineScope,
        local: ConversationScope,
        mirror: EncryptedConversationMirror,
        activeRef: AtomicReference<String?>,
    ): OnlineWorkspaceBinding {
        val isAuthorized: () -> Boolean = {
            ownerJob.isActive &&
                channel === targetChannel &&
                accountRuntime.account.value == account &&
                runCatching { accountRuntime.authenticatedPeer(targetChannel.scope.connectionId) == peer }.getOrDefault(false)
        }
        val mediaSource = RemoteAuthenticatedMediaSource(carrier, confirmed, isAuthorized)
        val stagingKey = endpointId(confirmed.endpoint.key)
        val staging = EncryptedAttachmentStagingStore(
            File(context.noBackupFilesDir, "remote-attachment-staging/$stagingKey"),
            (localDocumentKeyProvider ?: AndroidKeystoreOutboxKeyProvider("remote_attachment_$stagingKey")).getOrCreate(),
            stagingKey,
            persistent = true,
        )
        val realAttachments = RemoteAttachmentCoordinator(
            carrier,
            ownedScope,
            staging,
            EncryptedAttachmentRecovery(context, local, localDocumentKeyProvider),
            mediaSource,
            confirmed.endpoint.key.workspaceId,
            Json.canonical(Json.of(listOf(account.epoch, peer.localFingerprint, peer.peerFingerprint, peer.role.name))),
            isAuthorized,
        )
        val createRepo: (AtomicReference<String?>) -> RemoteConversationRepository = { ref ->
            RemoteConversationRepository(
                carrier,
                confirmed,
                ownedScope,
                ref::get,
                realAttachments::promptContent,
                mediaSource = mediaSource,
                onEvent = { key, event ->
                    mirror.commitNativeEvents(listOf(event))
                    nativeNotificationInput(local.profileId, local.accountId, endpointId(key), event)?.let {
                        nativeConversationNotifications.accept(it)
                    }
                },
                prepareTimelineSnapshot = { key, conversationId ->
                    prepareTimelineSnapshotReconciliation(local, key, conversationId)
                },
            )
        }
        return OnlineWorkspaceBinding(
            mediaSource = mediaSource,
            attachments = realAttachments,
            repo = createRepo(activeRef),
            surfaceRepo = createRepo,
        )
    }

    private fun buildAssistantWorkspace(
        local: ConversationScope,
        confirmed: RemoteWorkspace,
        ownedScope: CoroutineScope,
    ) = AssistantConversationWorkspace(
        local.profileId,
        local.accountId,
        endpointId(confirmed.endpoint.key),
        ownedScope,
        create = ::createAssistantOwned,
        onSessionReleased = { token ->
            assistantControllers.remove(token)
            assistantWrappers.remove(token)?.close(scope)
            assistantRepositories.remove(token)?.let { old -> scope.launch { old.close() } }
        },
    )
    /** 原地热升级当前控制器为在线状态，不销毁不重建 WorkbenchController，保持滚动位置和草稿。 */
    private suspend fun connectAndUpgradeCurrentWorkspaceLocked(
        account: RemoteActiveAccount,
        targetWorkspace: RemoteWorkspace,
    ) {
        val nextChannel = obtainOrConnectChannelLocked(targetWorkspace.endpoint.key.hostId)
        val carrier = activeCarriers.firstOrNull { it.backend == targetWorkspace.endpoint.key.backendId.value }
            ?: throw RemoteSessionException("REMOTE_BACKEND_UNAVAILABLE")
        val confirmed = carrier.workspaces().firstOrNull { it.endpoint.key == targetWorkspace.endpoint.key }
            ?: throw RemoteSessionException("REMOTE_WORKSPACE_NOT_FOUND")
        val formattedConfirmed = confirmed.copy(endpoint = targetWorkspace.endpoint)

        val currentController = mutableController.value
        val currentHotRepo = hotRepository
        val currentHotMedia = hotMediaSource
        val currentHotAttach = hotAttachments
        val currentHotCat = hotCatalog
        val currentOwnerJob = workspaceOwnerJob
        val currentMirror = ownerMirror
        val currentMedia = ownerMedia
        val currentScope = ownerConversationScope
        val currentActiveRef = activeThreadRef

        fun publishConnectedLayerState() {
            val agentKey = AgentKey.fromEndpointKey(formattedConfirmed.endpoint.key)
            updateLayerStates(
                agentTransform = { current ->
                    val resolvedHostName = current.activeHostName
                        ?: current.hosts.firstOrNull { h -> h.deviceId == agentKey.hostId }?.name
                        ?: current.agents.firstOrNull { a -> a.key == agentKey }?.hostName
                    val backendName = backendDisplayName(agentKey.backendId.value)
                    val resolvedAgentName = current.agents.firstOrNull { a -> a.key == agentKey }?.displayName
                        ?: resolvedHostName?.let { h -> "$h · $backendName" }
                        ?: current.activeAgentName
                        ?: backendName
                    val discoveredEntry = DiscoveredAgent(
                        key = agentKey,
                        hostName = resolvedHostName ?: agentKey.hostId,
                        backendName = backendName,
                        displayName = resolvedAgentName,
                        hostOnline = true,
                        availability = EndpointAvailability.AVAILABLE,
                    )
                    val mergedAgents = (current.agents.filterNot { a -> a.key == agentKey } + discoveredEntry)
                        .distinctBy { a -> a.key }
                    saveDiscoveredAgents(account, mergedAgents)
                    current.copy(
                        account = account,
                        agents = mergedAgents,
                        activeAgent = agentKey,
                        activeHostName = resolvedHostName,
                        activeAgentName = resolvedAgentName,
                        phase = AgentConnectionPhase.CONNECTED,
                        offlineConfirmed = false,
                        reconnectingInPlace = false,
                        busy = false,
                        errorCode = null,
                    )
                },
                workspaceTransform = { current ->
                    val list = if (current.workspaces.any { it.endpoint.key == formattedConfirmed.endpoint.key }) {
                        current.workspaces
                    } else {
                        current.workspaces + formattedConfirmed
                    }
                    current.copy(
                        agentKey = agentKey,
                        workspaces = list,
                        activeWorkspace = formattedConfirmed.endpoint.key,
                    )
                },
            )
        }

        if (currentController == null ||
            currentHotRepo == null ||
            currentHotMedia == null ||
            currentHotAttach == null ||
            currentHotCat == null ||
            currentOwnerJob == null ||
            !currentOwnerJob.isActive ||
            currentMirror == null ||
            currentMedia == null ||
            currentScope == null ||
            currentActiveRef == null
        ) {
            switchWorkspaceControllerLocked(account, nextChannel, carrier, formattedConfirmed, null)
            publishConnectedLayerState()
            refreshBackgroundWork()
        } else {
            // 先释放旧在线子句柄，保留控制器与本地镜像及媒体缓存
            retireOnlineHandlesOnly()
            val ownedScope = CoroutineScope(scope.coroutineContext + currentOwnerJob)
            val peer = accountRuntime.authenticatedPeer(nextChannel.scope.connectionId)
            val binding = buildOnlineWorkspaceBinding(
                account = account,
                targetChannel = nextChannel,
                peer = peer,
                carrier = carrier,
                confirmed = formattedConfirmed,
                ownerJob = currentOwnerJob,
                ownedScope = ownedScope,
                local = currentScope,
                mirror = currentMirror,
                activeRef = currentActiveRef,
            )
            currentHotMedia.attach(binding.mediaSource)
            ownerMediaSource = binding.mediaSource
            ownerAttachments = binding.attachments
            currentHotAttach.attach(binding.attachments)
            repository = binding.repo
            surfaceRepository = binding.surfaceRepo
            currentHotCat.attach(binding.repo.commandCatalog)

            if (!hasPendingNetworkLoss()) networkConnected = true
            val mirroredOnline = MirroredConversationRepository(binding.repo, currentMirror, currentMedia)
            currentHotRepo.attachOnline(mirroredOnline)

            assistantWorkspace?.closeAndAwait()
            assistantWorkspace = buildAssistantWorkspace(currentScope, formattedConfirmed, ownedScope)
            mutableAssistantVersion.value++

            saveSelection(formattedConfirmed)
            publishConnectedLayerState()
            observeBackgroundWork()

            val supported = carrier.capabilities
            currentController.updateRemoteCapabilities(
                online = true,
                send = SessionCapability.SEND in supported,
                delete = false,
                archive = SessionCapability.ARCHIVE in supported,
                rename = SessionCapability.RENAME in supported,
                approvals = SessionCapability.APPROVAL in supported,
                unarchive = SessionCapability.UNARCHIVE in supported,
            )
        }
    }

    /** 切换至新工作区上下文并原子发布新控制器（新上下文就绪前保留旧控制器存活，切换失败不波及旧工作台）。 */
    private suspend fun switchWorkspaceControllerLocked(
        account: RemoteActiveAccount,
        channel: RemoteRpcChannel,
        carrier: RemoteSessionCarrier,
        workspace: RemoteWorkspace,
        initialThreadId: String?,
    ) {
        val peer = accountRuntime.authenticatedPeer(channel.scope.connectionId)
        val confirmedRaw = carrier.workspaces().firstOrNull { it.endpoint.key == workspace.endpoint.key }
            ?: throw RemoteSessionException("REMOTE_WORKSPACE_NOT_FOUND")
        val confirmed = confirmedRaw.copy(endpoint = workspace.endpoint)

        val local = conversationScope(confirmed.endpoint.key)
        val sameLocalScope = ownerConversationScope == local
        if (sameLocalScope) {
            // 复用同一本地存储路径时先释放旧数据库句柄，避免并发持锁冲突
            retireWorkspaceContextKeepingChannel()
        }

        val owner = SupervisorJob(scope.coroutineContext[Job])
        val ownedScope = CoroutineScope(scope.coroutineContext + owner)
        var mirror: EncryptedConversationMirror? = null
        var media: EncryptedHistoryMediaCache? = null
        var binding: OnlineWorkspaceBinding? = null
        var newController: WorkbenchController? = null
        try {
            mirror = withContext(Dispatchers.IO) { EncryptedConversationMirror(context, local, localDocumentKeyProvider) }
            val hotMedia = HotSwappableHistoricalMediaSource()
            media = EncryptedHistoryMediaCache(context, local, source = hotMedia, keyProvider = localDocumentKeyProvider)
            val active = AtomicReference<String?>(null)
            binding = buildOnlineWorkspaceBinding(
                account = account,
                targetChannel = channel,
                peer = peer,
                carrier = carrier,
                confirmed = confirmed,
                ownerJob = owner,
                ownedScope = ownedScope,
                local = local,
                mirror = mirror,
                activeRef = active,
            )
            hotMedia.attach(binding.mediaSource)
            val hotAttach = HotSwappableAttachmentCoordinator().apply { attach(binding.attachments) }
            val mirroredOnline = MirroredConversationRepository(binding.repo, mirror, media)
            val hotRepo = HotSwappableConversationRepository(OfflineMirrorRepository(mirror), ownedScope, StreamHealth.LIVE)
                .apply { attachOnline(mirroredOnline) }
            val hotCat = HotSwappableCommandCatalogRepository {
                RemoteSessionException("COMMANDS_UNAVAILABLE_OFFLINE")
            }.apply {
                attach(binding.repo.commandCatalog)
            }

            newController = createController(
                ownedScope = ownedScope,
                repo = hotRepo,
                catalogRepo = hotCat,
                mirror = mirror,
                media = media,
                attachments = hotAttach,
                local = local,
                active = active,
                restore = true,
                initialOnline = true,
                canReadConversations = { hotRepo.isOnline },
                capabilitiesOverride = carrier.capabilities,
            )
            if (!sameLocalScope) {
                // 新工作区全部句柄与控制器构建就绪后再退休旧工作区，确保构造失败时旧控制器与UI仍完整存活
                retireWorkspaceContextKeepingChannel()
            }

            if (!hasPendingNetworkLoss()) networkConnected = true
            workspaceOwnerJob = owner
            ownerMediaSource = binding.mediaSource
            ownerAttachments = binding.attachments
            ownerMirror = mirror
            ownerMedia = media
            ownerConversationScope = local
            repository = binding.repo
            surfaceRepository = binding.surfaceRepo
            hotRepository = hotRepo
            hotAttachments = hotAttach
            hotMediaSource = hotMedia
            hotCatalog = hotCat
            activeThreadRef = active
            nativeConversationNotifications.bind(local.profileId, local.accountId)
            mutableController.value = newController
            assistantWorkspace = buildAssistantWorkspace(local, confirmed, ownedScope)
            initialThreadId?.let(newController::openThread)
            newController.state.value.activeThreadId?.let { activeId ->
                binding.repo.activeConversationChanged()
                refreshModels(activeId)
            }
            runCatching { saveSelection(confirmed) }
            observeBackgroundWork()
        } catch (failure: Throwable) {
            runCatching { newController?.close() }
            runCatching { binding?.repo?.close() }
            runCatching { binding?.mediaSource?.close() }
            runCatching { media?.retireConnection() }
            runCatching { mirror?.closeNativeStore() }
            owner.cancel()
            throw failure
        }
    }

    private suspend fun switchOfflineWorkspaceOnlyLocked(
        workspace: RemoteWorkspace,
        initialThreadId: String?,
    ) {
        val targetScope = conversationScope(workspace.endpoint.key)
        val sameLocalScope = ownerConversationScope == targetScope
        val handle = if (sameLocalScope) {
            retireWorkspaceContextKeepingChannel()
            buildOfflineWorkspaceHandle(workspace, StreamHealth.RECONNECTING)
        } else {
            val prepared = buildOfflineWorkspaceHandle(workspace, StreamHealth.RECONNECTING)
            retireWorkspaceContextKeepingChannel()
            prepared
        }
        publishOfflineWorkspaceHandle(handle)
        initialThreadId?.let { handle.controller.openThread(it) }
        saveSelection(workspace)
        val agentKey = AgentKey.fromEndpointKey(workspace.endpoint.key)
        updateLayerStates(
            workspaceTransform = { current ->
                current.copy(
                    agentKey = agentKey,
                    activeWorkspace = workspace.endpoint.key,
                )
            },
        )
    }

    private suspend fun fetchAllWorkspaceSessions(
        carrier: RemoteSessionCarrier,
        workspace: RemoteWorkspace,
    ): List<ConversationSummary> {
        val merged = linkedMapOf<String, ConversationSummary>()
        val seenCursors = hashSetOf<String>()
        var cursor: String? = null
        while (true) {
            val page = carrier.sessions(workspace, PageRequest(cursor = cursor))
            page.items.forEach { item ->
                val encodedId = RemoteLocalIds.encode(item.id)
                merged[encodedId] = ConversationSummary(
                    id = ConversationId(encodedId),
                    title = ContextInjectionFilter.sanitizeSummaryText(item.title),
                    updatedAt = item.updatedAt,
                    archived = item.archived,
                )
            }
            val next = page.nextCursor?.takeIf { it.isNotBlank() } ?: break
            if (!seenCursors.add(next)) break
            cursor = next
        }
        return merged.values.toList()
    }

    private fun createController(
        ownedScope: CoroutineScope,
        repo: ConversationRepository,
        catalogRepo: AgentCommandCatalogRepository,
        mirror: EncryptedConversationMirror,
        media: EncryptedHistoryMediaCache,
        attachments: AttachmentDraftCoordinator?,
        local: ConversationScope,
        active: AtomicReference<String?>,
        restore: Boolean = true,
        initialOnline: Boolean = true,
        canReadConversations: () -> Boolean = { true },
        capabilitiesOverride: Set<SessionCapability>? = null,
    ): WorkbenchController {
        val supported = capabilitiesOverride ?: repository?.carrier?.capabilities.orEmpty()
        val organization = EncryptedNativeConversationOrganizationStore(context, local, localDocumentKeyProvider)
        val tracker = repo as? GenerationTracker ?: object : GenerationTracker {
            override val generationId = MutableStateFlow<String?>(null)
            override val generationState = MutableStateFlow(GenerationState.IDLE)
        }
        val history = object : ConversationRepository by repo, GenerationTracker by tracker, EventCommitBarrier, MessageOutcomeQuery {
            override suspend fun awaitPendingEventCommits() {
                (repo as? EventCommitBarrier)?.awaitPendingEventCommits()
            }
            override suspend fun queryMessage(conversationId: String, clientMessageId: ClientMessageId): TimelineMessage? =
                (repo as? MessageOutcomeQuery)?.queryMessage(conversationId, clientMessageId)
            override suspend fun readOrganization() = withContext(Dispatchers.IO) { organization.read(local.gatewayId) }
            override suspend fun updateOrganization(expectedRevision: Long, value: ConversationOrganization) =
                withContext(Dispatchers.IO) { organization.update(local.gatewayId, expectedRevision, value) }
        }
        return WorkbenchController(
            scope = ownedScope,
            repository = history,
            catalogRepository = catalogRepo,
            scopeFactory = { local },
            attachmentCoordinator = attachments,
            restoreLastThread = restore,
            supportsApprovalCards = SessionCapability.APPROVAL in supported,
            onActiveThreadChanged = { id ->
                active.set(id)
                if (activeThreadRef === active) {
                    repository?.activeConversationChanged()
                    if (restore) refreshModels(id)
                }
            },
            streamHealthSource = repo as? StreamHealthSource,
            persistence = mirror.takeIf { restore },
            media = media,
            downloads = AndroidAttachmentDownloads(context, media, local, localDocumentKeyProvider),
            allowSending = true,
            initialOnline = initialOnline,
            canReadConversations = canReadConversations,
            supportsConversationManagement = true,
            organizationStoredLocally = true,
            supportsConversationDelete = false,
            supportsConversationRename = SessionCapability.RENAME in supported,
            supportsConversationArchive = SessionCapability.ARCHIVE in supported,
            supportsConversationUnarchive = SessionCapability.UNARCHIVE in supported,
            authoritativeMessageIds = true,
        )
    }

    fun refreshModels(id: String? = controller.value?.state?.value?.activeThreadId): Job {
        val sequence = modelSequence.incrementAndGet()
        return scope.launch {
            val repo = repository ?: return@launch
            if (id == null) {
                updateLayerStates(workspaceTransform = { it.copy(models = null) })
                return@launch
            }
            updateLayerStates(workspaceTransform = { it.copy(modelBusy = true) })
            try {
                val models = repo.modelCatalog(id)
                if (repository === repo && modelSequence.get() == sequence && controller.value?.state?.value?.activeThreadId == id) {
                    updateLayerStates(workspaceTransform = { it.copy(models = models) })
                }
            } catch (failure: Exception) {
                if (failure.isTrueCancellation()) throw failure
                if (repository === repo && modelSequence.get() == sequence && !agentConnection.value.busy) {
                    fail(failure)
                }
            } finally {
                if (repository === repo && modelSequence.get() == sequence) {
                    updateLayerStates(workspaceTransform = { it.copy(modelBusy = false) })
                }
            }
        }
    }

    fun selectModel(selection: RemoteModelSelection): Job {
        val sequence = modelSequence.incrementAndGet()
        return scope.launch {
            val repo = repository ?: return@launch
            val id = controller.value?.state?.value?.activeThreadId ?: return@launch
            updateLayerStates(workspaceTransform = { it.copy(modelBusy = true) })
            try {
                val confirmed = repo.selectModel(id, selection)
                if (repository === repo && modelSequence.get() == sequence && controller.value?.state?.value?.activeThreadId == id) {
                    updateLayerStates(workspaceTransform = { it.copy(models = it.models?.copy(selected = confirmed)) })
                }
            } catch (failure: Exception) {
                if (failure.isTrueCancellation()) throw failure
                fail(failure)
            } finally {
                if (repository === repo && modelSequence.get() == sequence) {
                    updateLayerStates(workspaceTransform = { it.copy(modelBusy = false) })
                }
            }
        }
    }

    fun logout() = scope.launch {
        switchGeneration.incrementAndGet()
        handledNetworkLossEpoch.updateAndGet { maxOf(it, networkLossEpoch.get()) }
        startupRecoveryJob?.cancel()
        reconnectLoopJob?.cancel()
        activeOperationJob?.cancel()
        invalidateHydration()?.cancel()
        loginEntryBridge.cancelLocal()
        var failure: Exception? = null
        try {
            accountRuntime.logout()
        } catch (error: Exception) {
            if (error.isTrueCancellation()) throw error
            failure = error
        }
        selection.withLock {
            try {
                retireAllAndClearController()
            } catch (error: Exception) {
                if (error.isTrueCancellation()) throw error
                if (failure == null) failure = error
            }
            val retired = synchronized(registrationLock) { registered.values.toList().also { registered.clear() } }
            try {
                closeRemoteConnectionResources(
                    *retired.map { old -> { old.registration.close() } }.toTypedArray(),
                    { nativeConversationNotifications.leave() },
                )
            } catch (error: Exception) {
                if (failure == null) failure = error
            }
            clearCachedWorkspaces()
            coldStartBootstrapped = false
            if (failure == null && accountRuntime.account.value == null) {
                updateLayerStates(
                    agentTransform = { AgentConnectionState() },
                    workspaceTransform = { WorkspaceContextState() },
                )
            } else {
                updateLayerStates(agentTransform = { it.copy(busy = false, errorCode = "REMOTE_LOGOUT_INCOMPLETE") })
            }
        }
    }

    /** 仅供单元测试模拟进程终止重启（停止全部协程并关闭加密存储句柄，但保留本地缓存与选定配置）。 */
    @androidx.annotation.VisibleForTesting
    internal suspend fun closeRuntimeWithoutClearingCacheForTest() {
        switchGeneration.incrementAndGet()
        handledNetworkLossEpoch.updateAndGet { maxOf(it, networkLossEpoch.get()) }
        startupRecoveryJob?.cancelAndJoin()
        reconnectLoopJob?.cancelAndJoin()
        invalidateHydration()?.cancelAndJoin()
        activeOperationJob?.cancelAndJoin()
        selection.withLock {
            retireAllAndClearController()
            val retired = synchronized(registrationLock) { registered.values.toList().also { registered.clear() } }
            closeRemoteConnectionResources(
                *retired.map { old -> { old.registration.close() } }.toTypedArray(),
                { nativeConversationNotifications.leave() },
            )
            coldStartBootstrapped = false
        }
    }

    private suspend fun handleAuthRevoked(code: String) {
        switchGeneration.incrementAndGet()
        handledNetworkLossEpoch.updateAndGet { maxOf(it, networkLossEpoch.get()) }
        reconnectLoopJob?.cancel()
        invalidateHydration()?.cancel()
        lastHydratedAtMs = 0L
        runCatching { retireAllAndClearController() }
        updateLayerStates(
            agentTransform = {
                it.copy(
                    activeAgent = null,
                    activeHostName = null,
                    activeAgentName = null,
                    phase = AgentConnectionPhase.AUTH_REVOKED,
                    busy = false,
                    reconnectingInPlace = false,
                    offlineConfirmed = false,
                    switchingAgent = null,
                    errorCode = code,
                )
            },
            workspaceTransform = { WorkspaceContextState() },
        )
    }

    private suspend fun detachOnlineWorkspaceInPlace(nextHealth: StreamHealth) {
        hotRepository?.detachOnline(nextHealth)
        hotAttachments?.detach()
        hotMediaSource?.detach()
        hotCatalog?.detach()
        mutableController.value?.setRemoteConversationOnline(false)
        ownerConversationScope?.let { nativeConversationNotifications.disconnectEndpoint(it.profileId, it.accountId, it.gatewayId) }
        retireOnlineHandlesOnly()
        refreshBackgroundWork()
    }

    private suspend fun retireOnlineHandlesOnly() {
        modelSequence.incrementAndGet()
        reconcileObserver?.cancelAndJoin(); reconcileObserver = null
        hotCatalog?.detach()
        ownerMediaSource?.close(); ownerMediaSource = null
        assistantWorkspace?.closeAndAwait(); assistantWorkspace = null
        assistantControllers.values.forEach { it.closeAndAwait() }; assistantControllers.clear()
        assistantRepositories.values.forEach { it.close() }; assistantRepositories.clear()
        assistantWrappers.values.forEach { it.close(scope).join() }; assistantWrappers.clear()
        repository?.close(); repository = null
        ownerAttachments = null
        surfaceRepository = null
        mutableAssistantVersion.value++
    }

    /** 关闭当前工作区控制器与镜像上下文，但保留底层 Host 通道以供同 Agent/同 Host 复用。 */
    private suspend fun retireWorkspaceContextKeepingChannel() {
        modelSequence.incrementAndGet()
        hotRepository?.detachOnline(StreamHealth.IDLE)
        hotAttachments?.detach()
        hotMediaSource?.detach()
        hotCatalog?.detach()
        lastHydratedAtMs = 0L
        ownerMediaSource?.close(); ownerMediaSource = null
        ownerMedia?.retireConnection()
        ownerConversationScope?.let { nativeConversationNotifications.disconnectEndpoint(it.profileId, it.accountId, it.gatewayId) }
        synchronized(backgroundOwnership) { ownerActive = false; keepAlive.stop() }
        workObserver?.cancelAndJoin(); workObserver = null
        reconcileObserver?.cancelAndJoin(); reconcileObserver = null
        controller.value?.closeAndAwait()
        assistantWorkspace?.closeAndAwait(); assistantWorkspace = null
        assistantControllers.values.forEach { it.closeAndAwait() }; assistantControllers.clear()
        assistantRepositories.values.forEach { it.close() }; assistantRepositories.clear()
        assistantWrappers.values.forEach { it.close(scope).join() }; assistantWrappers.clear()
        repository?.close(); repository = null
        workspaceOwnerJob?.cancelAndJoin(); workspaceOwnerJob = null
        hotRepository = null
        hotAttachments = null
        hotMediaSource = null
        hotCatalog = null
        activeThreadRef = null
        ownerAttachments = null
        ownerMirror = null
        ownerMedia = null
        ownerConversationScope = null
        surfaceRepository = null
        mutableAssistantVersion.value++
    }

    private suspend fun closeChannelOnly() {
        modelSequence.incrementAndGet()
        activeCarriers = emptyList()
        hydratedAgentForChannel = null
        lastHydratedAtMs = 0L
        val oldChannel = channel
        channel = null
        oldChannel?.close()
    }

    /** 仅在退出登录、认证失败或重登替换时清空工作台控制器与全部通道。 */
    private suspend fun retireAllAndClearController() {
        retireWorkspaceContextKeepingChannel()
        mutableController.value = null
        closeChannelOnly()
        handledNetworkLossEpoch.updateAndGet { maxOf(it, networkLossEpoch.get()) }
    }

    private fun chooseTargetWorkspace(
        agent: AgentKey,
        workspaces: List<RemoteWorkspace>,
        preferredWorkspace: RemoteWorkspace?,
    ): RemoteWorkspace? {
        if (preferredWorkspace != null) {
            workspaces.firstOrNull { it.endpoint.key == preferredWorkspace.endpoint.key }?.let { return it }
        }
        val lastId = loadAgentLastWorkspaceId(agent)
        if (lastId != null) {
            workspaces.firstOrNull { it.endpoint.key.workspaceId == lastId }?.let { return it }
        }
        return workspaces.firstOrNull()
    }

    private fun loadSavedSelection(account: RemoteActiveAccount): RemoteWorkspace? {
        val saved = preferences.getString("selection:${profileId(account.origin)}:${account.accountId}", null) ?: return null
        return decodeWorkspaceArray(account, saved)
    }

    private fun loadSavedSelectionIfMatches(account: RemoteActiveAccount, key: EndpointKey): RemoteWorkspace? =
        loadSavedSelection(account)?.takeIf { it.endpoint.key == key }
            ?: endpointForKey(account, key)

    private fun decodeWorkspaceArray(account: RemoteActiveAccount, encoded: String): RemoteWorkspace? {
        val fields = runCatching { Json.parse(encoded) }.getOrNull() as? JsonValue.JArray ?: return null
        val values = fields.items.map { (it as? JsonValue.JString)?.value ?: return null }
        if (values.size != 5) return null
        val key = EndpointKey(
            RemoteServiceOrigin.fromCanonicalOrigin(account.origin),
            account.accountId,
            values[0],
            BackendId(values[1]),
            values[2],
        )
        return RemoteWorkspace(AgentEndpoint(key, values[3], EndpointAvailability.UNKNOWN), values[4])
    }

    private fun saveSelection(workspace: RemoteWorkspace) {
        val key = workspace.endpoint.key
        saveEndpoint(workspace)
        val profile = profileId(key.origin.value)
        preferences.edit()
            .putString(
                "selection:$profile:${key.accountId}",
                Json.canonical(Json.of(listOf(key.hostId, key.backendId.value, key.workspaceId, workspace.endpoint.displayName, workspace.path))),
            )
            .putString(
                "agent-last-workspace:$profile:${key.accountId}:${key.hostId}:${key.backendId.value}",
                key.workspaceId,
            )
            .commit()
    }

    private fun loadAgentLastWorkspaceId(agent: AgentKey): String? {
        val profile = profileId(agent.origin.value)
        return preferences.getString("agent-last-workspace:$profile:${agent.accountId}:${agent.hostId}:${agent.backendId.value}", null)
    }

    private fun saveEndpoint(workspace: RemoteWorkspace) {
        val key = workspace.endpoint.key
        val encoded = Json.canonical(Json.of(listOf(key.hostId, key.backendId.value, key.workspaceId, workspace.endpoint.displayName, workspace.path)))
        check(preferences.edit().putString("endpoint:${profileId(key.origin.value)}:${key.accountId}:${endpointId(key)}", encoded).commit()) {
            "LOCAL_SAVE_FAILED"
        }
    }

    private fun saveAgentWorkspaces(agent: AgentKey, workspaces: List<RemoteWorkspace>) {
        val profile = profileId(agent.origin.value)
        val retainedKeys = workspaces.map { ws ->
            saveEndpoint(ws)
            "endpoint:$profile:${ws.endpoint.key.accountId}:${endpointId(ws.endpoint.key)}"
        }.toSet()
        val editor = preferences.edit()
        val endpointPrefix = "endpoint:$profile:${agent.accountId}:"
        preferences.all.keys.filter { it.startsWith(endpointPrefix) && it !in retainedKeys }.forEach { prefKey ->
            val raw = preferences.getString(prefKey, null) ?: return@forEach
            val existing = decodeWorkspaceArray(RemoteActiveAccount(agent.origin.value, agent.accountId, 0L), raw)
            if (existing != null && agent.matches(existing.endpoint.key)) {
                editor.remove(prefKey)
            }
        }
        val retainedIds = workspaces.map { it.endpoint.key.workspaceId }.toSet()
        val lastWorkspaceId = loadAgentLastWorkspaceId(agent)
        if (lastWorkspaceId != null && lastWorkspaceId !in retainedIds) {
            editor.remove("agent-last-workspace:$profile:${agent.accountId}:${agent.hostId}:${agent.backendId.value}")
        }
        val account = RemoteActiveAccount(agent.origin.value, agent.accountId, 0L)
        val savedSelection = loadSavedSelection(account)
        if (savedSelection != null && agent.matches(savedSelection.endpoint.key) &&
            workspaces.none { it.endpoint.key == savedSelection.endpoint.key }
        ) {
            editor.remove("selection:$profile:${agent.accountId}")
        }
        val encoded = Json.canonical(
            Json.of(
                workspaces.map { ws ->
                    val k = ws.endpoint.key
                    listOf(k.hostId, k.backendId.value, k.workspaceId, ws.endpoint.displayName, ws.path)
                },
            ),
        )
        editor.putString("agent-workspaces:$profile:${agent.accountId}:${agent.hostId}:${agent.backendId.value}", encoded)
            .commit()
    }

    private fun loadCachedAgentWorkspaces(agent: AgentKey): List<RemoteWorkspace> {
        val profile = profileId(agent.origin.value)
        val encoded = preferences.getString("agent-workspaces:$profile:${agent.accountId}:${agent.hostId}:${agent.backendId.value}", null)
        if (encoded != null) {
            val array = runCatching { Json.parse(encoded) }.getOrNull() as? JsonValue.JArray
            if (array != null) {
                val list = mutableListOf<RemoteWorkspace>()
                array.items.forEach { item ->
                    val values = (item as? JsonValue.JArray)?.items?.mapNotNull { (it as? JsonValue.JString)?.value }
                    if (values != null && values.size == 5) {
                        val key = EndpointKey(agent.origin, agent.accountId, values[0], BackendId(values[1]), values[2])
                        if (agent.matches(key)) {
                            list += RemoteWorkspace(AgentEndpoint(key, values[3], EndpointAvailability.UNKNOWN), values[4])
                        }
                    }
                }
                return list.distinctBy { it.endpoint.key }
            }
        }
        val list = mutableListOf<RemoteWorkspace>()
        val prefix = "endpoint:$profile:${agent.accountId}:"
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach { prefKey ->
            val raw = preferences.getString(prefKey, null) ?: return@forEach
            val ws = decodeWorkspaceArray(RemoteActiveAccount(agent.origin.value, agent.accountId, 0L), raw) ?: return@forEach
            if (agent.matches(ws.endpoint.key)) list += ws
        }
        return list.distinctBy { it.endpoint.key }
    }

    private fun saveDiscoveredAgents(account: RemoteActiveAccount, agents: List<DiscoveredAgent>) {
        val profile = profileId(account.origin)
        val encoded = Json.canonical(
            Json.of(
                agents.map {
                    listOf(it.key.hostId, it.key.backendId.value, it.hostName, it.backendName, it.displayName)
                },
            ),
        )
        preferences.edit().putString("agents:$profile:${account.accountId}", encoded).commit()
    }

    private fun loadDiscoveredAgents(account: RemoteActiveAccount): List<DiscoveredAgent> {
        val profile = profileId(account.origin)
        val encoded = preferences.getString("agents:$profile:${account.accountId}", null) ?: return emptyList()
        val array = runCatching { Json.parse(encoded) }.getOrNull() as? JsonValue.JArray ?: return emptyList()
        val origin = RemoteServiceOrigin.fromCanonicalOrigin(account.origin)
        return array.items.mapNotNull { item ->
            val values = (item as? JsonValue.JArray)?.items?.mapNotNull { (it as? JsonValue.JString)?.value } ?: return@mapNotNull null
            if (values.size != 5) return@mapNotNull null
            DiscoveredAgent(
                key = AgentKey(origin, account.accountId, values[0], BackendId(values[1])),
                hostName = values[2],
                backendName = values[3],
                displayName = values[4],
            )
        }
    }

    private fun loadCachedWorkspaceSessionsSnapshot(workspaces: List<RemoteWorkspace>): Map<EndpointKey, Loadable<List<ConversationSummary>>> {
        val result = linkedMapOf<EndpointKey, Loadable<List<ConversationSummary>>>()
        val currentScope = ownerConversationScope
        val currentMirror = ownerMirror
        workspaces.forEach { ws ->
            val cached = runCatching {
                val local = conversationScope(ws.endpoint.key)
                if (currentMirror != null && currentScope == local) {
                    currentMirror.threads()
                } else {
                    val mirror = EncryptedConversationMirror(context, local, localDocumentKeyProvider)
                    try {
                        mirror.threads()
                    } finally {
                        runCatching { mirror.closeNativeStore() }
                    }
                }
            }.getOrNull()
            result[ws.endpoint.key] = if (cached != null) {
                Loadable.Ready(cached.map { it.copy(title = ContextInjectionFilter.sanitizeSummaryText(it.title)) })
            } else {
                Loadable.Empty
            }
        }
        return result
    }

    private fun endpointForKey(account: RemoteActiveAccount, key: EndpointKey): RemoteWorkspace? {
        val encoded = preferences.getString("endpoint:${profileId(account.origin)}:${account.accountId}:${endpointId(key)}", null)
            ?: return null
        return decodeWorkspaceArray(account, encoded)
    }

    private fun endpointForNotification(target: ReplyNotificationTarget): RemoteWorkspace? {
        val account = agentConnection.value.account ?: return null
        if (target.profileId != profileId(account.origin) || target.accountId != account.accountId || target.agentId == null) return null
        state.value.endpoints.firstOrNull { endpointId(it.endpoint.key) == target.agentId }?.let { return it }
        val encoded = preferences.getString("endpoint:${target.profileId}:${target.accountId}:${target.agentId}", null) ?: return null
        return decodeWorkspaceArray(account, encoded)?.takeIf { endpointId(it.endpoint.key) == target.agentId }
    }
    private fun errorCodeOf(failure: Throwable): String = when (failure) {
        is TimeoutCancellationException -> "CONNECT_TIMEOUT"
        is RemoteRpcException -> failure.code
        is RemoteSessionException -> failure.code
        else -> failure.message?.takeIf { Regex("[A-Z0-9_]{3,128}").matches(it) } ?: "REMOTE_CONNECTION_FAILED"
    }

    private fun fail(failure: Exception) {
        val code = errorCodeOf(failure)
        updateLayerStates(agentTransform = { it.copy(errorCode = code) })
    }

    private fun backendDisplayName(backend: String): String = when (backend) {
        "harness" -> "DSH"
        "codex" -> "Codex"
        else -> backend.replaceFirstChar { it.uppercaseChar() }
    }
    private fun carriers(channel: RemoteRpcChannel): List<RemoteSessionCarrier> = buildList {
        fun ready(backend: String, capability: String): Boolean {
            if (capability !in channel.capabilities) return false
            if (channel.workspaceTypes.isEmpty()) return true
            return channel.workspaceTypes.firstOrNull { JsonFields.string(it, "id") == backend }?.let {
                JsonFields.bool(it, "available") == true && JsonFields.string(it, "capability") in channel.capabilities
            } == true
        }
        // 官方类型清单只细化 Codex/ACP，Harness 由独立的 Session V3 能力声明。
        if ("harness.remote.v3" in channel.capabilities) add(HarnessSessionCarrier(channel))
        if (ready("codex", "codex.appserver.v1")) add(CodexSessionCarrier(channel))
        if ("agent.acp.v1" in channel.capabilities) {
            setOf("cursor", "antigravity", "pi", "opencode", "claude", "hermes")
                .filter { ready(it, "agent.acp.$it.v1") }
                .forEach { add(AcpSessionCarrier(channel, it)) }
        }
    }

    private fun conversationScope(key: EndpointKey) =
        ConversationScope(profileId(key.origin.value), endpointId(key), key.accountId, installationId())

    private fun installationId(): String = preferences.getString("installation-id", null)
        ?: java.util.UUID.randomUUID().toString().also {
            check(preferences.edit().putString("installation-id", it).commit()) { "LOCAL_SAVE_FAILED" }
        }

    private fun profileId(origin: String) = "remote_" + digest(origin)

    private fun avatar(account: RemoteActiveAccount): String? =
        preferences.getString("avatar:${profileId(account.origin)}:${account.accountId}", null)

    fun setAccountAvatar(owner: String, bytes: ByteArray) {
        val account = agentConnection.value.account ?: return
        val expected = "${profileId(account.origin)}:${account.accountId}"
        check(owner == expected && bytes.isNotEmpty() && bytes.size <= ACCOUNT_AVATAR_MAX_BYTES) { "REMOTE_AVATAR_OWNER_CHANGED" }
        val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
        check(preferences.edit().putString("avatar:$expected", encoded).commit()) { "LOCAL_SAVE_FAILED" }
        if (agentConnection.value.account == account) {
            updateLayerStates(agentTransform = { it.copy(accountAvatarBase64 = encoded) })
        }
    }

    internal fun endpointId(key: EndpointKey) =
        digest(Json.canonical(Json.of(listOf(key.origin.value, key.accountId, key.hostId, key.backendId.value, key.workspaceId))))

    private fun digest(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    internal fun ensureAssistantWorkspace(): Boolean = repository != null && controller.value != null

    internal fun createAssistantSessionController(token: String): WorkbenchController? =
        assistantWorkspace?.let { runCatching { it.open(token) }.getOrNull() }

    private fun createAssistantOwned(token: String): WorkbenchController? {
        assistantControllers[token]?.let { return it }
        val owner = workspaceOwnerJob ?: return null
        val mirror = ownerMirror ?: return null
        val media = ownerMedia ?: return null
        val local = ownerConversationScope ?: return null
        val active = AtomicReference<String?>(null)
        val repo = surfaceRepository?.invoke(active) ?: return null
        assistantRepositories[token] = repo
        val wrapper = assistantAttachmentWrapperFor(assistantWrappers, ownerAttachments, token)
        val hotCat = HotSwappableCommandCatalogRepository {
            RemoteSessionException("COMMANDS_UNAVAILABLE_OFFLINE")
        }.apply {
            attach(repo.commandCatalog)
        }
        return createController(
            CoroutineScope(scope.coroutineContext + owner),
            repo,
            hotCat,
            mirror,
            media,
            wrapper,
            local,
            active,
            restore = false,
            initialOnline = true,
            capabilitiesOverride = repo.carrier.capabilities,
        ).also { controller ->
            assistantControllers.putIfAbsent(token, controller)?.let { controller.close() }
        }.let { assistantControllers[token] ?: it }
    }

    internal fun reattachAssistantSession(token: String) = assistantControllers[token]

    internal fun releaseAssistantSession(token: String) {
        assistantWorkspace?.release(token)
    }

    internal fun setAssistantPresence(clientToken: String, sessionToken: String?, visible: Boolean) {
        assistantPresence[clientToken] = sessionToken to visible
    }

    internal fun releaseAssistantClient(token: String) {
        assistantPresence.remove(token)
    }

    internal suspend fun prepareAssistantHandoff(token: String): AssistantHandoffResult {
        val sender = assistantControllers[token]
            ?: return AssistantHandoffResult.Rejected(AssistantHandoffReasons.notConnected(context))
        val receiver = controller.value
            ?: return AssistantHandoffResult.Rejected(AssistantHandoffReasons.workbenchNotReady(context))
        val profile = connectedProfileId
            ?: return AssistantHandoffResult.Rejected(AssistantHandoffReasons.notConnected(context))
        val account = connectedAccountId
            ?: return AssistantHandoffResult.Rejected(AssistantHandoffReasons.accountMismatch(context))
        if (!sender.awaitHandoffAttachments(30_000)) {
            return AssistantHandoffResult.Rejected(AssistantHandoffReasons.attachmentsNotReady(context))
        }
        val draft = sender.assistantDraftForHandoff()
        val conversation = sender.state.value.activeThreadId
        conversation?.let(receiver::openThread)
        val skipped = receiver.adoptAssistantDraft(draft.text, draft.attachments)
        assistantWrappers[token]?.let { wrapper -> draft.attachments.forEach { wrapper.disown(it.id.value) } }
        val target = AssistantHandoffTarget(
            profile,
            account,
            workspaceContext.value.activeWorkspace?.let(::endpointId),
            conversation,
        )
        handoffs[token] = target
        return AssistantHandoffResult.Accepted(target, skipped)
    }

    internal fun consumeAssistantHandoff(token: String): Boolean = handoffs.remove(token)?.let { target ->
        if (!isBubbleTargetCurrent(ReplyNotificationTarget(target.profileId, target.accountId, target.conversationId ?: "", target.agentId))) {
            return false
        }
        target.conversationId?.let { controller.value?.openThread(it) }
        true
    } ?: false

    internal fun isBubbleTargetCurrent(target: ReplyNotificationTarget): Boolean =
        target.profileId == connectedProfileId &&
            target.accountId == connectedAccountId &&
            target.agentId == workspaceContext.value.activeWorkspace?.let(::endpointId)

    internal fun createBubbleConversation(target: ReplyNotificationTarget): WorkbenchController? {
        if (!isBubbleTargetCurrent(target)) return null
        return createAssistantSessionController("bubble:${target.identityKey()}")?.also { it.openThread(target.conversationId) }
    }

    internal fun releaseBubbleConversation(controller: WorkbenchController) {
        assistantControllers.entries.firstOrNull { it.value === controller }?.let { releaseAssistantSession(it.key) }
    }

    internal suspend fun createBubbleThread(target: ReplyNotificationTarget): ReplyNotificationTarget {
        check(isBubbleTargetCurrent(target)) { "REMOTE_ENDPOINT_CHANGED" }
        val repo = repository ?: error("REMOTE_NOT_CONNECTED")
        val local = ownerConversationScope ?: error("REMOTE_NOT_CONNECTED")
        val created = repo.createConversation(local, "conversation_${java.util.UUID.randomUUID()}")
        return target.copy(conversationId = created.id.value)
    }

    internal suspend fun activateNotificationEndpoint(target: ReplyNotificationTarget): Boolean {
        if (isBubbleTargetCurrent(target) && repository != null) return true
        val endpoint = endpointForNotification(target) ?: return false
        selectWorkspace(endpoint).join()
        return isBubbleTargetCurrent(target) && repository != null
    }

    internal suspend fun openReplyNotification(target: ReplyNotificationTarget): Boolean {
        if (!activateNotificationEndpoint(target)) return false
        controller.value?.openThread(target.conversationId) ?: return false
        return true
    }

    fun onAppForegrounded() {
        accountRuntime.refreshControlConnection()
        synchronized(backgroundOwnership) { foreground = true }
        replyNotifications.refreshPermission()
        nativeConversationNotifications.refreshActive()
        refreshBackgroundWork(foregrounded = true)
        val currentRepo = repository
        val currentSelected = workspaceContext.value.activeWorkspace ?: currentRepo?.workspace?.endpoint?.key
        if (networkConnected && currentRepo != null && currentSelected != null && nativeConversationNotifications.hasPendingReconciliation()) {
            scope.launch { reconcileActiveNotifications(currentRepo, currentSelected) }
        }
    }

    fun onAppBackgrounded() {
        refreshBackgroundWork()
        synchronized(backgroundOwnership) { foreground = false }
        replyNotifications.refreshPermission()
        nativeConversationNotifications.refreshActive()
    }

    /** 仅枚举当前 Remote 账号已登记的完整端点，不读取旧 Core 档案。 */
    fun localOrganizationScopes(): List<ConversationScope> {
        val account = agentConnection.value.account ?: return emptyList()
        val prefix = "endpoint:${profileId(account.origin)}:${account.accountId}:"
        return preferences.all.keys.filter { it.startsWith(prefix) }.mapNotNull { key ->
            val id = key.removePrefix(prefix)
            endpointForNotification(ReplyNotificationTarget(profileId(account.origin), account.accountId, "", id))
                ?.endpoint?.key?.let(::conversationScope)
        }.distinct()
    }

    fun readLocalOrganization(local: ConversationScope): ConversationOrganization =
        EncryptedNativeConversationOrganizationStore(context, local, localDocumentKeyProvider).read(local.gatewayId)

    fun updateLocalOrganization(local: ConversationScope, value: ConversationOrganization): ConversationOrganization {
        check(local in localOrganizationScopes()) { "REMOTE_ORGANIZATION_SCOPE_UNAVAILABLE" }
        val store = EncryptedNativeConversationOrganizationStore(context, local, localDocumentKeyProvider)
        return store.update(local.gatewayId, store.read(local.gatewayId).revision, value)
    }

    fun loginOrigin(): String = preferences.getString("preferred-origin", null) ?: "https://dsh.r2049.cn"

    fun saveLoginOrigin(origin: String) {
        val canonical = RemoteServiceOrigin.fromCanonicalOrigin(origin).value
        check(preferences.edit().putString("preferred-origin", canonical).commit()) { "LOCAL_SAVE_FAILED" }
    }

    fun reloadDebounceSettings() {
        val policy = DebouncePreferences(context).read()
        controller.value?.updateDebouncePolicy(policy)
        assistantControllers.values.forEach { it.updateDebouncePolicy(policy) }
    }
    /** 发起时间线远端快照读取前捕获通知围栏，防止旧在途快照晚于新事件或主动对账请求返回时误消费配额。 */
    private fun prepareTimelineSnapshotReconciliation(
        local: ConversationScope,
        key: EndpointKey,
        conversationId: String,
    ): ((RemoteReconciliationSnapshot) -> Unit)? {
        val target = ReplyNotificationTarget(local.profileId, local.accountId, conversationId, endpointId(key))
        val fence = nativeConversationNotifications.captureTimelineReconciliationFence(target) ?: return null
        return { snapshot ->
            nativeConversationNotifications.reconcileWithFence(
                fence,
                snapshot.activeGenerationId?.let { it to "running" },
                snapshot.messages,
                activityKnown = snapshot.activityKnown,
            )
        }
    }

    fun onNetworkAvailable(@Suppress("UNUSED_PARAMETER") routeChanged: Boolean = false) {
        networkConnected = true
        accountRuntime.refreshControlConnection()
        val activeKey = workspaceContext.value.activeWorkspace ?: return
        val phase = agentConnection.value.phase
        val needReconnect = hasPendingNetworkLoss() ||
            phase == AgentConnectionPhase.OFFLINE ||
            (phase == AgentConnectionPhase.RECONNECTING && !agentConnection.value.reconnectingInPlace) ||
            controller.value?.state?.value?.isOnline == false
        if (needReconnect) {
            reconnectInPlace()
        } else {
            refreshBackgroundWork()
            if (nativeConversationNotifications.hasPendingReconciliation()) {
                scope.launch { reconcileActiveNotifications(repository, activeKey) }
            }
        }
    }

    fun onNetworkLost() {
        networkConnected = false
        reconcileObserver?.cancel()
        ownerConversationScope?.let { nativeConversationNotifications.disconnectEndpoint(it.profileId, it.accountId, it.gatewayId) }
        refreshBackgroundWork()
        // 仅在 AUTH_REVOKED 态跳过；DISCONNECTED 态可能正处于首次 selectAgent/selectWorkspace 建链中途，必须纳入断网代际追踪。
        if (agentConnection.value.phase == AgentConnectionPhase.AUTH_REVOKED) return
        // 先推进断网代际再读取 switchGeneration，保证更晚发起的切工作区/重连操作必在锁内看到并消费本代际断网。
        val myLossEpoch = networkLossEpoch.incrementAndGet()
        val lostTicket = switchGeneration.get()
        scope.launch {
            var handled = false
            var graceTarget: Pair<Long, RemoteWorkspace>? = null
            selection.withLock {
                // 若本代际断网已被更新操作消费，或已有更高代际断网事件入队，则本协程退出交由最新事件接管。
                if (myLossEpoch <= handledNetworkLossEpoch.get() || myLossEpoch < networkLossEpoch.get()) return@withLock
                handled = true
                val wasOnline = hotRepository?.isOnline == true
                consumePendingNetworkLossLocked(myLossEpoch)
                if (wasOnline) {
                    if (switchGeneration.get() == lostTicket) {
                        updateLayerStates(
                            agentTransform = {
                                it.copy(
                                    phase = AgentConnectionPhase.RECONNECTING,
                                    offlineConfirmed = false,
                                    reconnectingInPlace = false,
                                )
                            },
                        )
                        val activeKey = workspaceContext.value.activeWorkspace
                        val activeAccount = agentConnection.value.account ?: accountRuntime.account.value
                        val ws = activeKey?.let { key ->
                            workspaceContext.value.workspaces.firstOrNull { it.endpoint.key == key }
                                ?: activeAccount?.let { loadSavedSelectionIfMatches(it, key) }
                        }
                        if (ws != null) graceTarget = lostTicket to ws
                    }
                } else {
                    controller.value?.setRemoteConversationOnline(false)
                }
            }
            if (!handled) return@launch
            // 断开处理排队或关闭句柄期间若网络已恢复，立即补发就地重连，避免空等宽限期或停在离线退避。
            if (networkConnected) {
                if (switchGeneration.get() == lostTicket &&
                    (graceTarget != null || agentConnection.value.phase == AgentConnectionPhase.OFFLINE)
                ) {
                    reconnectInPlace()
                }
                return@launch
            }
            // 宽限期倒计时必须在释放 selection 锁后进行，避免阻塞用户就地重试或切换工作区。
            val (ticket, ws) = graceTarget ?: return@launch
            delayFn(recoveryPolicy.offlineGracePeriodMillis)
            if (switchGeneration.get() == ticket &&
                agentConnection.value.phase == AgentConnectionPhase.RECONNECTING &&
                hotRepository?.isOnline != true
            ) {
                updateLayerStates(
                    agentTransform = {
                        it.copy(
                            phase = AgentConnectionPhase.OFFLINE,
                            offlineConfirmed = true,
                            reconnectingInPlace = false,
                        )
                    },
                )
                startBackgroundReconnectLoop(ws, ticket)
            }
        }
    }

    fun onBackgroundSessionRestarted() {
        restoreSessionIfAvailable()
    }

    fun onBackgroundSessionStopped() {
        onNetworkLost()
    }

    private companion object {
        private const val WORKSPACE_HYDRATION_STALE_WINDOW_MS = 2_000L
    }
}
