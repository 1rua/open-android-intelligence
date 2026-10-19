package com.openandroidintelligence.mobile

import android.content.Context
import android.os.Build
import com.openandroidintelligence.conversation.data.GatewayAttachmentDraftCoordinator
import com.openandroidintelligence.conversation.data.GatewayCommandCatalogRepository
import com.openandroidintelligence.conversation.data.GatewayConversationRepository
import com.openandroidintelligence.conversation.ports.completeTimeline
import com.openandroidintelligence.conversation.ports.completeConversations
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.conversation.ports.LocalAttachmentStagingStore
import com.openandroidintelligence.conversation.ports.StagedAttachmentContent
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.gateway.attachments.AttachmentUploader
import com.openandroidintelligence.gateway.attachments.HttpAttachmentTransport
import com.openandroidintelligence.gateway.account.AccountProfile
import com.openandroidintelligence.gateway.auth.AndroidKeystoreGatewayCredentialStore
import com.openandroidintelligence.gateway.auth.GatewayAuthClient
import com.openandroidintelligence.gateway.auth.GatewayCredentialStore
import com.openandroidintelligence.gateway.auth.GatewayAuthException
import com.openandroidintelligence.gateway.auth.GatewaySessionRenewal
import com.openandroidintelligence.gateway.auth.SessionCredentials
import com.openandroidintelligence.gateway.auth.PairingRevocationClient
import com.openandroidintelligence.gateway.commands.CommandCatalogClient
import com.openandroidintelligence.gateway.conversations.ConversationClient
import com.openandroidintelligence.gateway.device.DeviceRequestClient
import com.openandroidintelligence.gateway.device.HttpDeviceRequestTransport
import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.http.GatewayEndpoint
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.GatewayTransport
import com.openandroidintelligence.gateway.http.TransportSecurity
import com.openandroidintelligence.gateway.negotiation.GenerationCancelCapability
import com.openandroidintelligence.encrypted.store.AndroidKeystoreOutboxKeyProvider
import com.openandroidintelligence.encrypted.store.EncryptedAttachmentStagingStore
import com.openandroidintelligence.kernel.PairingGrantBinding
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import com.openandroidintelligence.gateway.events.EventStreamStatus
import com.openandroidintelligence.gateway.events.EventStreamStatusSink
import com.openandroidintelligence.gateway.negotiation.NegotiationResult
import com.openandroidintelligence.gateway.http.EventSessionRejectedException
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * The connection lifecycle of the app: disconnected → negotiating →
 * authenticating → connected, with the workbench wiring built only after the
 * Gateway really issued a session.
 *
 * Nothing here fabricates a session. A failed login keeps the phase at Failed
 * with the Gateway's error code, and the login screen stays the honest state.
 *
 * The address the user typed decides the transport security: an `https://`
 * Gateway must return a TLS identity that the phone pins, while a plaintext
 * `http://` Gateway is accepted as an explicitly reported degraded connection
 * (ADR 0047) and never reported as a verified identity.
 */
/**
 * 本客户端在 `features.conversationUi` 里声明过的能力全集。
 *
 * 这份列表必须与 [com.openandroidintelligence.gateway.negotiation.NegotiationClient]
 * 发出的 offer 保持一致（那边是模块私有常量，无法直接引用）；漂移的代价是
 * 设置页会把一个从未请求过的能力标成「本网关不支持」。列表里有而 NegotiationClient
 * 没有的键永远不会被网关同意，也就永远不会离开「未声明」态。
 */
val CLIENT_CONVERSATION_UI_OFFER: Set<String> = setOf(
    "agent-command-catalog-v1",
    "agent-command-new-v1",
    "agent-approval-cards-v1",
    "message-batches-v1",
    "generation-cancel-v1",
    "conversation-management-v1",
)

sealed interface ConnectionPhase {
    data object Disconnected : ConnectionPhase

    data object Negotiating : ConnectionPhase

    data object Authenticating : ConnectionPhase

    data class OfflineMirror(val gatewayUrl:String,val username:String):ConnectionPhase

    data class Connected(
        val gatewayUrl: String,
        val username: String,
        val pairingSummary: String?,
        /** The pinned TLS identity, or null on a plaintext connection. */
        val tlsSpkiSha256: String?,
        val transportSecurity: TransportSecurity,
        /**
         * 协商交集：网关同意且客户端声明过并实现的对话界面能力。
         * 只放真正兑现得了的键——网关单方面同意一个客户端没有的键，照样无法兑现。
         */
        val conversationUi: Set<String> = emptySet(),
        /** 客户端 offer：本端声明过的能力全集（协议层请求原文）。 */
        val requestedConversationUi: Set<String> = CLIENT_CONVERSATION_UI_OFFER,
        /** 网关声明的设备请求能力档（契约 §10）；null 表示网关未提供。 */
        val deviceRequests: String? = null,
    ) : ConnectionPhase

    data class Failed(val code: String) : ConnectionPhase
}

enum class GatewayLoginStatus { SIGNED_OUT, RESTORING, SIGNED_IN, REAUTHENTICATION_REQUIRED }
enum class GatewayCredentialStatus { UNAVAILABLE, AVAILABLE, REFRESHING, RECOVERY_PENDING }

class GatewayRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pairingGrants: PairingGrantStateHolder,
    /**
     * 审计落点（条目 2-9）：提供时，被 Gateway 接受的审批卡决策（用户确认）
     * 会经 [ApprovalAuditBridge] 写入平台审计链；null 时行为与从前一致。
     */
    private val auditStore: com.openandroidintelligence.kernel.AndroidAuditStore? = null,
    /**
     * 刷新凭据的保管面。生产实现走 Android Keystore；单测注入内存实现，
     * 因为 Keystore 只在设备上存在，而「刷新凭据」这条路径必须可被验证。
     */
    private val credentialStore: GatewayCredentialStore = AndroidKeystoreGatewayCredentialStore(
        File(context.filesDir, "keystore-credentials").also { it.mkdirs() },
    ),
    /** 设备密钥签名面，理由同 [credentialStore]。 */
    private val deviceKeys: DeviceKeySource = KeystoreDeviceKeySource(
        File(context.filesDir, "gateway-credentials"),
    ),
    private val identityTrust: GatewayIdentityTrustStore = GatewayIdentityTrustStore(context),
    private val accountProfiles: AndroidAccountProfileStore = AndroidAccountProfileStore(context),
    private val pluginHost: com.openandroidintelligence.mobile.plugins.ProductionPluginHost? = null,
    private val localDocumentKeyProvider: com.openandroidintelligence.encrypted.store.AesGcmKeyProvider? = null,
) {
    private var executionDriver: com.openandroidintelligence.mobile.plugins.DeviceExecutionDriver? = null
    private var capabilityPublisher: com.openandroidintelligence.mobile.plugins.CapabilityPublisher? = null
    val deviceConfirmation = MutableStateFlow<com.openandroidintelligence.mobile.plugins.DeviceConfirmation?>(null)
    val connectedAccountId: String? get() = lastAccountId.takeIf { activeHttpClient != null }
    val replyNotifications = ReplyNotificationSettings(context)
    private val replyPublisher = AndroidReplyNotificationPublisher(context, replyNotifications)
    private val sessionKeepAlive = AgentSessionKeepAlive(context)
    private val sessionRecovery = AgentSessionRecoveryState(context)
    private val refreshJournal = GatewayRefreshJournal(context)
    private val sessionOperationMutex = Mutex()
    private var sessionRenewal: GatewaySessionRenewal? = null
    private val recoveryWakeup = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var restoringSession = false
    @Volatile private var backgroundRecoveryOnly = false
    @Volatile private var backgroundRestoreJob: Job? = null
    private var restartPlatformStream: (() -> Unit)? = null
    private val pendingRotations = mutableMapOf<String, Pair<SessionCredentials, NegotiationResult>>()
    private val _loginStatus = MutableStateFlow(GatewayLoginStatus.SIGNED_OUT)
    val loginStatus: StateFlow<GatewayLoginStatus> = _loginStatus.asStateFlow()
    private val _credentialStatus = MutableStateFlow(GatewayCredentialStatus.UNAVAILABLE)
    val credentialStatus: StateFlow<GatewayCredentialStatus> = _credentialStatus.asStateFlow()
    private val _connectionStatus = MutableStateFlow(EventStreamStatus.IDLE)
    val connectionStatus: StateFlow<EventStreamStatus> = _connectionStatus.asStateFlow()
    private val backgroundOwnershipLock = Any()
    @Volatile private var appVisible = false
    @Volatile private var backgroundSessionActive = false
    private val bubbleWorkJobs = java.util.concurrent.ConcurrentHashMap<WorkbenchController, Job>()
    private val bubbleWorkOwners = mutableMapOf<WorkbenchController, BubbleConversationWorkspace>()
    private val activeBubbleWork = mutableSetOf<WorkbenchController>()
    private val hasBubbleWork = MutableStateFlow(false)
    private val hasRecoveredGatewayWork = MutableStateFlow(false)
    internal val nativeConversationNotifications = NativeConversationNotifications(context, scope, replyPublisher::update,
        cancel = replyPublisher::cancel,
        forget = replyPublisher::forget,
        titleFor = { target ->
            (_controller.value?.state?.value?.threads as? com.openandroidintelligence.conversation.state.Loadable.Ready)
                ?.value?.firstOrNull { it.id.value == target.conversationId }?.title
        })
    private var bubbleWorkspace: BubbleConversationWorkspace? = null

    internal fun isBubbleTargetCurrent(target: ReplyNotificationTarget): Boolean {
        val connected = _phase.value as? ConnectionPhase.Connected ?: return false
        return connectedAccountId == target.accountId &&
            profileIdFor(connected.gatewayUrl, connected.username) == target.profileId &&
            bubbleWorkspace?.accepts(target) == true
    }

    /** A new bubble conversation never consumes the main window's draft or selection. */
    internal suspend fun createBubbleThread(target: ReplyNotificationTarget): ReplyNotificationTarget {
        check(isBubbleTargetCurrent(target)) { "BUBBLE_SESSION_UNAVAILABLE" }
        val owner = bubbleWorkspace
        val http = checkNotNull(activeHttpClient) { "BUBBLE_SESSION_UNAVAILABLE" }
        val created = withContext(Dispatchers.IO) {
            ConversationClient(http).createConversation("cv_client_${java.util.UUID.randomUUID()}")
        }
        check(bubbleWorkspace === owner && activeHttpClient === http && isBubbleTargetCurrent(target)) {
            "BUBBLE_SESSION_CHANGED"
        }
        _controller.value?.refreshThreads()
        return target.copy(conversationId = created.conversationId)
    }

    private fun bubbleBusyConversationIds(): Set<String> = synchronized(bubbleWorkJobs) {
        bubbleWorkOwners.keys.flatMap { it.ownedBusyThreadIds }.toSet()
    }

    internal fun createBubbleConversation(target: ReplyNotificationTarget): WorkbenchController? {
        val workspace = bubbleWorkspace ?: return null
        val created = if (isBubbleTargetCurrent(target)) workspace.open(target) else null
        if (created != null) {
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                created.hasActiveWork.collect {
                    synchronized(bubbleWorkJobs) {
                        if (bubbleWorkJobs.containsKey(created)) {
                            if (created.hasActiveWork.value) activeBubbleWork += created
                            else activeBubbleWork -= created
                            hasBubbleWork.value = activeBubbleWork.isNotEmpty()
                        }
                    }
                    if (workspace.releaseIfIdle(created)) untrackBubbleWork(created)
                }
            }
            synchronized(bubbleWorkJobs) {
                bubbleWorkJobs[created] = job
                bubbleWorkOwners[created] = workspace
            }
            job.start()
        }
        return created
    }

    internal fun releaseBubbleConversation(controller: WorkbenchController) {
        val owner = synchronized(bubbleWorkJobs) { bubbleWorkOwners[controller] }
        if (owner == null || owner.release(controller)) {
            untrackBubbleWork(controller)
            // A session replacement may have already retired the owning workspace.
            controller.close()
        } else {
            // Read the synchronous submission fact here as well: a send followed
            // immediately by UI disposal may precede the StateFlow collector.
            synchronized(bubbleWorkJobs) {
                if (bubbleWorkJobs.containsKey(controller)) {
                    activeBubbleWork += controller
                    hasBubbleWork.value = true
                }
            }
        }
        refreshBackgroundWork()
    }

    private fun untrackBubbleWork(controller: WorkbenchController) {
        synchronized(bubbleWorkJobs) {
            bubbleWorkJobs.remove(controller)?.cancel()
            bubbleWorkOwners.remove(controller)
            activeBubbleWork -= controller
            hasBubbleWork.value = activeBubbleWork.isNotEmpty()
        }
    }

    private fun clearBubbleWorkTracking() {
        val retired = synchronized(bubbleWorkJobs) {
            val jobs = bubbleWorkJobs.values.toList()
            bubbleWorkJobs.clear()
            bubbleWorkOwners.clear()
            activeBubbleWork.clear()
            hasBubbleWork.value = false
            jobs
        }
        retired.forEach(Job::cancel)
    }

    /** A notification never borrows another account's currently open workbench. */
    internal fun openReplyNotification(target: ReplyNotificationTarget): Boolean {
        val connected = _phase.value as? ConnectionPhase.Connected ?: return false
        if (target.accountId != connectedAccountId || target.profileId != profileIdFor(connected.gatewayUrl, connected.username)) return false
        val workbench = _controller.value ?: return false
        workbench.openThread(target.conversationId)
        return true
    }
    fun decideDeviceRequest(id: String, approved: Boolean) { executionDriver?.decide(id,approved) }

    private val _phase = MutableStateFlow<ConnectionPhase>(ConnectionPhase.Disconnected)
    val phase: StateFlow<ConnectionPhase> = _phase.asStateFlow()
    private val _savedProfiles = MutableStateFlow(accountProfiles.list())
    val savedProfiles: StateFlow<List<AccountProfile>> = _savedProfiles.asStateFlow()
    private val _isManagingProfiles = MutableStateFlow(false)
    val isManagingProfiles: StateFlow<Boolean> = _isManagingProfiles.asStateFlow()

    private val _controller = MutableStateFlow<WorkbenchController?>(null)
    val controller: StateFlow<WorkbenchController?> = _controller.asStateFlow()

    private val _operationNotice = MutableStateFlow<String?>(null)
    val operationNotice: StateFlow<String?> = _operationNotice.asStateFlow()
    fun dismissOperationNotice() { _operationNotice.value = null }

    init {
        val stagingRoot = File(context.noBackupFilesDir, "attachment-staging")
        scope.launch(Dispatchers.IO) {
            runCatching { EncryptedAttachmentStagingStore.cleanupExpiredRoot(stagingRoot) }
                .onFailure { _operationNotice.value = "ATTACHMENT_CLEANUP_FAILED:ATTACHMENT_STORAGE_UNAVAILABLE" }
        }
        scope.launch {
            _controller.collectLatest { workbench ->
                if (workbench == null) refreshBackgroundWork()
                else workbench.hasActiveWork.collect { refreshBackgroundWork() }
            }
        }
        scope.launch { hasRecoveredGatewayWork.collect { refreshBackgroundWork() } }
        scope.launch {
            combine(
                nativeConversationNotifications.sessions.map { sessions -> sessions.any { it.isRunning } }.distinctUntilChanged(),
                hasBubbleWork,
                replyNotifications.enabled,
                replyNotifications.allowed,
                phase,
            ) { _, _, _, _, _ -> Unit }.collect { refreshBackgroundWork() }
        }
    }

    /** 是否正在向 Gateway 轮换凭据；界面据此展示进行中并阻止重复点击。 */
    private val _isRefreshingSession = MutableStateFlow(false)
    val isRefreshingSession: StateFlow<Boolean> = _isRefreshingSession.asStateFlow()

    /**
     * The app is visible again after having been in the background.
     *
     * Only a session that really exists has a channel to re-synchronize, so a
     * missing workbench is a no-op rather than something to invent.
     */
    fun onAppForegrounded() {
        synchronized(backgroundOwnershipLock) {
            appVisible = true
            backgroundRecoveryOnly = false
        }
        replyNotifications.refreshPermission()
        nativeConversationNotifications.refreshActive()
        refreshBackgroundWork(foregrounded = true)
        onNetworkAvailable()
    }

    /** Network changes wake the existing owner; they never start an Android service. */
    fun onNetworkAvailable() {
        recoveryWakeup.trySend(Unit)
        if (backgroundRecoveryOnly && !appVisible && !sessionRecovery.hasRecoveryLease) return
        if (_loginStatus.value == GatewayLoginStatus.REAUTHENTICATION_REQUIRED) return
        if (_phase.value is ConnectionPhase.Disconnected) restoreSessionIfAvailable()
        else if (!restoringSession && _phase.value is ConnectionPhase.OfflineMirror) reconnectOfflineMirror()
        else if (!restoringSession && _phase.value is ConnectionPhase.Connected) {
            restartPlatformStream?.invoke()
            resumeWorkbench()
        }
    }

    private fun resumeWorkbench() {
        val controller = _controller.value ?: return
        // Connectivity callbacks and renewal run off Main. Serialize their UI
        // stream handover with lifecycle callbacks on the controller's owner.
        scope.launch(Dispatchers.Main.immediate) {
            if (_controller.value === controller) controller.onForegrounded()
        }
    }

    /** Called only after Android legally recreates an eligible foreground service. */
    fun onBackgroundSessionRestarted() {
        synchronized(backgroundOwnershipLock) {
            if (!appVisible && !restoringSession && _phase.value !is ConnectionPhase.Connected) {
                backgroundRecoveryOnly = true
            }
        }
        restoreSessionIfAvailable()
        if (!restoringSession && _phase.value !is ConnectionPhase.Connected) sessionKeepAlive.stop()
    }

    /** Only the service-restart owner depends on the service's recovery authorization. */
    @Synchronized
    internal fun onBackgroundSessionStopped() {
        synchronized(backgroundOwnershipLock) {
            if (backgroundRecoveryOnly && !appVisible) backgroundRestoreJob?.cancel()
        }
    }

    fun onAppBackgrounded() {
        // Start while leaving a visible activity, never as an arbitrary background
        // restart. The service then keeps already submitted work receiving replies.
        refreshBackgroundWork()
        synchronized(backgroundOwnershipLock) { appVisible = false }
        replyNotifications.refreshPermission()
        nativeConversationNotifications.refreshActive()
    }

    private fun backgroundWorkSnapshot(): Boolean =
        phase.value is ConnectionPhase.Connected && replyNotifications.enabled.value && replyNotifications.allowed.value &&
        // A bubble can stop before its asynchronous observer is scheduled.
        // Read its owned submission facts without acquiring the tracking lock.
        (nativeConversationNotifications.sessions.value.any { it.isRunning } || bubbleWorkJobs.keys.any { it.hasActiveWork.value } ||
            _controller.value?.hasActiveWork?.value == true || hasRecoveredGatewayWork.value)

    private fun refreshBackgroundWork(foregrounded: Boolean = false) {
        synchronized(backgroundOwnershipLock) {
            // Read only atomic StateFlow values here, never Native's synchronized
            // methods. A stale pre-teardown snapshot cannot restart the service.
            val hasWork = backgroundSessionActive && backgroundWorkSnapshot()
            if (foregrounded) sessionKeepAlive.onForegrounded(hasWork)
            else sessionKeepAlive.update(hasWork, appVisible)
        }
    }

    private var connectionJob: Job? = null
    private var sessionJob: Job? = null
    private var gatewayGrantRevision = -1L
    private var activeAttachmentStaging: LocalAttachmentStagingStore? = null

    private val activeThread = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** The login form's authoritative action; the UI only reflects the phase. */
    fun login(gatewayUrl: String, username: String, password: CharArray, invitation: Boolean = false, deviceKey: Boolean = false, expectedIdentityFingerprint: String? = null) {
        if (connectionJob?.isActive == true || _phase.value is ConnectionPhase.Connected) {
            password.fill('\u0000')
            return
        }
        val endpoint = GatewayEndpoint.parse(gatewayUrl)
        if (endpoint == null) {
            password.fill('\u0000')
            _phase.value = ConnectionPhase.Failed("AUTH_INVALID:url-scheme-required")
            return
        }
        val normalized = endpoint.baseUrl
        val profileId = profileIdFor(normalized, username)
        _phase.value = ConnectionPhase.Negotiating
        connectionJob = scope.launch {
            try {
            val retainedPins = identityTrust.pinsBeforeConnect(endpoint, username)
            val negotiation = runCatching { authClientFor(normalized, retainedPins).negotiate("neg_" + newToken()) }
            val negotiated = negotiation.getOrElse { cause ->
                _phase.value = ConnectionPhase.Failed(errorCode(cause))
                return@launch
            }
            val tlsPin = identityTrust.verifyNegotiation(endpoint, username, negotiated)
            if (expectedIdentityFingerprint != null) {
                val identity=com.openandroidintelligence.gateway.schema.Json.of(mapOf("deploymentId" to negotiated.deploymentId,"tlsSpkiSha256" to negotiated.tlsSpkiSha256))
                check(com.openandroidintelligence.gateway.schema.Json.sha256(identity)==expectedIdentityFingerprint) { "GATEWAY_IDENTITY_MISMATCH" }
            }

            _phase.value = ConnectionPhase.Authenticating
            val credentials = runCatching {
                val publicKey = deviceKeys.publicKeyBase64Url(profileId)
                if (deviceKey) {
                    val binding=accountProfiles.binding(profileId) ?: error("DEVICE_NOT_PAIRED")
                    authClientFor(normalized,setOfNotNull(tlsPin)).loginWithDeviceKey(negotiated.negotiationId,binding.accountId,binding.deviceId) { deviceKeys.sign(profileId,it) }
                } else if (invitation) authClientFor(normalized,setOfNotNull(tlsPin)).loginWithInvite(negotiated.negotiationId,username,String(password).trim().uppercase(),Build.MODEL ?: "Android",publicKey) { deviceKeys.sign(profileId,it) }
                else authClientFor(normalized, setOfNotNull(tlsPin)).loginWithPassword(
                    negotiationId = negotiated.negotiationId,
                    username = username,
                    password = password,
                    displayName = Build.MODEL ?: "Android",
                    devicePublicKeyBase64Url = publicKey,
                )
            }
            credentials.fold(
                onSuccess = { session ->
                    identityTrust.remember(endpoint, username, negotiated)
                    val refreshCred = session.refreshCredential
                    if (refreshCred.isNotEmpty()) {
                        runCatching {
                            credentialStore.saveRefresh(profileId, refreshCred)
                            saveLastProfile(normalized, username, profileId, session)
                        }.onFailure { _operationNotice.value = "自动登录凭据未能保存，下次启动需要重新登录。" }
                    }
                    establish(
                        endpoint = endpoint,
                        username = username,
                        profileId = profileId,
                        session = session,
                        tlsSpkiSha256 = tlsPin,
                        conversationUi = negotiated.conversationUi,
                        deviceRequests = negotiated.deviceRequests,
                        generationCancelCapability =
                            GenerationCancelCapability.fromNegotiation(negotiated),
                    )
                },
                onFailure = { cause -> _phase.value = ConnectionPhase.Failed(errorCode(cause)) },
            )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                _phase.value = ConnectionPhase.Failed(errorCode(cause))
            } finally {
                password.fill('\u0000')
            }
        }
    }

    /** Clears a failed attempt so the login form can be re-entered cleanly. */
    fun resetFailure() {
        if (_phase.value is ConnectionPhase.Failed) {
            _phase.value = ConnectionPhase.Disconnected
        }
    }

    fun logout(revokeRefresh: Boolean) {
        if (_phase.value is ConnectionPhase.OfflineMirror) {
            _operationNotice.value = "当前离线，无法向 Gateway 确认退出，请先重新连接。"
            return
        }
        if (connectionJob?.isActive == true) return
        val current = _phase.value as? ConnectionPhase.Connected ?: return
        val profileId = profileIdFor(current.gatewayUrl, current.username)
        val accountId = lastAccountId ?: return
        val deviceId = lastDeviceId ?: return
        _operationNotice.value = null
        connectionJob = scope.launch {
            if (!flushWorkbenchBeforeLeaving()) return@launch
            var renewed = false
            while (true) {
                try {
                    sessionOperationMutex.withLock {
                        val live = sessionRenewal?.current ?: return@launch
                        authClientFor(current.gatewayUrl, setOfNotNull(current.tlsSpkiSha256)).logout(
                            accessToken = accessTokenHolder ?: live.accessToken, accountId = accountId, deviceId = deviceId,
                            sessionId = lastSessionId ?: live.sessionId, revokeRefresh = revokeRefresh,
                        )
                        // Fence queued rotations before releasing the operation lock.
                        sessionRenewal?.close()
                        sessionRenewal = null
                    }
                    break
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (cause: Exception) {
                    if (cause is GatewayAuthException && cause.httpStatus == 401 && !renewed) {
                        renewed = true
                        val renewal = sessionRenewal ?: return@launch
                        try { renewal.refresh(renewal.current) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            if (sessionRenewal === renewal) _operationNotice.value = "登出未获 Gateway 确认，请检查连接后重试。"
                            return@launch
                        }
                    } else {
                        _operationNotice.value = "登出未获 Gateway 确认，请检查连接后重试。"
                        return@launch
                    }
                }
            }
            val cleanup = listOf<() -> Unit>(
                { credentialStore.clearRefresh(profileId) },
                { refreshJournal.clear(profileId); pendingRotations.remove(profileId) },
                { clearLastProfile() },
            )
            for (action in cleanup) {
                try {
                    action()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _operationNotice.value = "Gateway 已登出，但本机凭据清理失败，请检查设备存储。"
                }
            }
            try { _controller.value?.closeAndAwait() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _operationNotice.value = "Gateway 已登出，本机保存未完成，历史资料已保留。" }
            teardown()
        }
    }

    /** Leave the active workbench without revoking another saved account's credentials. */
    fun reconnectOfflineMirror() {
        if (restoringSession) { recoveryWakeup.trySend(Unit); return }
        if (connectionJob?.isActive == true || _phase.value !is ConnectionPhase.OfflineMirror) return
        leaveWorkbench { restoreSessionIfAvailable() }
    }

    fun chooseAnotherAccount() {
        if (connectionJob?.isActive == true && !restoringSession) return
        cancelRestoration()
        leaveWorkbench { clearLastProfile() }
    }

    fun selectSavedAccount(profileId: String) {
        if (connectionJob?.isActive == true && !restoringSession) return
        cancelRestoration()
        val profile = accountProfiles.find(profileId) ?: return
        val binding = accountProfiles.binding(profileId) ?: return
        leaveWorkbench {
            val edit = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_GATEWAY, profile.gatewayBaseUrl).putString(KEY_LAST_USER, profile.username)
                .putString(KEY_LAST_PROFILE, profileId).putString(KEY_LAST_ACCOUNT, binding.accountId)
                .putString(KEY_LAST_DEVICE, binding.deviceId).putString(KEY_LAST_SESSION, binding.sessionId)
                .putInt(KEY_DEVICE_KEY_ENCODING, binding.keyEncoding)
            if (!edit.commit()) {
                _operationNotice.value = "账号选择未能保存，请检查本机存储后重试。"
                return@leaveWorkbench
            }
            restoreSessionIfAvailable()
        }
    }

    private suspend fun flushWorkbenchBeforeLeaving(): Boolean = try {
        _controller.value?.flushPendingPersistence()
        true
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) {
        _operationNotice.value = "本地会话保存失败，请检查手机存储后重试。"
        false
    }

    private fun cancelRestoration() {
        synchronized(backgroundOwnershipLock) {
            backgroundRecoveryOnly = false
            backgroundRestoreJob = null
        }
        if (!restoringSession) return
        connectionJob?.cancel()
        connectionJob = null
        restoringSession = false
    }

    /** 先持久保存草稿与发送状态，再关闭旧账号；新连接不会被旧任务的收尾覆盖。 */
    private fun leaveWorkbench(action: () -> Unit) {
        lateinit var owned: Job
        owned = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                if (!flushWorkbenchBeforeLeaving()) return@launch
                _controller.value?.closeAndAwait()
                sessionOperationMutex.withLock {
                    teardown()
                    connectionJob = null
                    action()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { _operationNotice.value = "账号切换未完成，请检查手机存储后重试。" }
            finally { if (connectionJob === owned) connectionJob = null }
        }
        connectionJob = owned
        owned.start()
    }

    /** Called only by the login form's explicit local identity confirmation. */
    fun reconfirmGatewayIdentity(gatewayUrl: String, username: String) {
        if (connectionJob?.isActive == true || _phase.value is ConnectionPhase.Connected) return
        val endpoint = GatewayEndpoint.parse(gatewayUrl) ?: return
        if (!endpoint.isTls) return
        runCatching {
            credentialStore.clearRefresh(profileIdFor(endpoint.baseUrl, username))
            identityTrust.forget(endpoint, username)
            _phase.value = ConnectionPhase.Disconnected
            _operationNotice.value = "旧网关身份和自动登录凭据已清除，请核对新身份后使用密码重新登录。"
        }.onFailure { _operationNotice.value = "网关身份清理失败，请检查本机存储。" }
    }

    fun removeLocalAccount(profileId: String) {
        if (connectionJob?.isActive == true || _phase.value is ConnectionPhase.Connected) return
        val profile = accountProfiles.find(profileId) ?: return
        val binding = accountProfiles.binding(profileId)
        val accountId = binding?.accountId ?: accountProfiles.accountId(profileId)
        _isManagingProfiles.value = true
        _operationNotice.value = "正在退出并移除本机账号…"
        connectionJob = scope.launch {
            var refresh: ByteArray? = null
            try {
                val offline = _phase.value as? ConnectionPhase.OfflineMirror
                if (offline?.gatewayUrl == profile.gatewayBaseUrl && offline.username == profile.username) {
                    if (!flushWorkbenchBeforeLeaving()) return@launch
                    _controller.value?.closeAndAwait()
                    teardown()
                }
                refresh = credentialStore.loadRefresh(profileId)
                if (refresh != null && refresh.isNotEmpty() && binding != null) {
                    val endpoint = GatewayEndpoint.parse(profile.gatewayBaseUrl) ?: error("PROFILE_INVALID")
                    val pins = identityTrust.pinsBeforeConnect(endpoint, profile.username, restoring = true)
                    sessionOperationMutex.withLock {
                        val (session, _) = rotateStoredSession(endpoint, profile.username, profileId,
                            binding.accountId, binding.deviceId, pins)
                        authClientFor(endpoint.baseUrl, pins).logout(session.accessToken, session.accountId,
                            session.deviceId, session.sessionId, revokeRefresh = true)
                    }
                }
                withContext(Dispatchers.IO) {
                    credentialStore.clearRefresh(profileId)
                    refreshJournal.clear(profileId)
                    pendingRotations.remove(profileId)
                    credentialStore.clearDeviceKey(profileId)
                    deviceKeys.delete(profileId)
                    if (accountId != null) {
                        val localScope = ConversationScope(profileId,profile.gatewayBaseUrl,accountId,installationId())
                        val localMedia = com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache(context,localScope,keyProvider=localDocumentKeyProvider)
                        com.openandroidintelligence.mobile.conversations.AndroidAttachmentDownloads(context,localMedia,localScope,keyProvider=localDocumentKeyProvider).wipePrivateDownloadJournal()
                        pairingGrants.clearFor(PairingGrantBinding(profile.gatewayBaseUrl, accountId, installationId()))
                        attachmentStagingStore(profile.gatewayBaseUrl, accountId, installationId()).cleanup()
                        com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror(context,ConversationScope(profileId,profile.gatewayBaseUrl,accountId,installationId()),localDocumentKeyProvider).wipe()
                        com.openandroidintelligence.mobile.conversations.EncryptedAttachmentRecovery(context,ConversationScope(profileId,profile.gatewayBaseUrl,accountId,installationId()),localDocumentKeyProvider).wipe()
                        com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache(context,ConversationScope(profileId,profile.gatewayBaseUrl,accountId,installationId()),keyProvider=localDocumentKeyProvider).clearMedia()
                        pluginHost?.eraseAccount(accountId,PairingGrantBinding(profile.gatewayBaseUrl,accountId,installationId()).pairingId)
                        replyPublisher.eraseAccount(profileId, accountId)
                        nativeConversationNotifications.eraseAccount(profileId, accountId)
                    }
                    AndroidEventCursorStore(context, profileId).clearProfile()
                    AndroidEventCursorStore(context, platformCursorProfile(profileId)).clearProfile()
                    identityTrust.forget(GatewayEndpoint.parse(profile.gatewayBaseUrl)!!, profile.username)
                    accountProfiles.delete(profileId)
                }
                if (context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_PROFILE, null) == profileId) clearLastProfile()
                _savedProfiles.value = accountProfiles.list()
                _operationNotice.value = "本机账号已移除；Gateway 账号和其他设备配对保持有效。"
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (cause: Exception) { _operationNotice.value = "本机账号移除未完成，资料已保留，请检查连接或存储后重试。" }
            finally { refresh?.fill(0); _isManagingProfiles.value = false }
        }
    }

    fun unpair() {
        if (connectionJob?.isActive == true) return
        val current = _phase.value as? ConnectionPhase.Connected ?: return
        val http = activeHttpClient ?: return
        val deviceId = lastDeviceId ?: return
        val profileId = profileIdFor(current.gatewayUrl, current.username)
        val accountId = lastAccountId ?: return
        val staging = activeAttachmentStaging
        val binding = PairingGrantBinding(current.gatewayUrl, accountId, installationId())
        connectionJob = scope.launch {
            if (!flushWorkbenchBeforeLeaving()) return@launch
            try {
                PairingRevocationClient(http).revoke(deviceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                _operationNotice.value = "解除配对未获 Gateway 确认，本机凭据已保留，请检查连接后重试。"
                return@launch
            }
            sessionRenewal?.close()
            sessionRenewal = null
            sessionOperationMutex.withLock {
                try { _controller.value?.closeAndAwait() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { _operationNotice.value = "Gateway 已解除配对，本机任务收尾失败，请重试本机账号清理。" }
                teardown()
                val localScope = ConversationScope(profileId,current.gatewayUrl,accountId,installationId())
                try {
                    val localMedia = com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache(context,localScope,keyProvider=localDocumentKeyProvider)
                    com.openandroidintelligence.mobile.conversations.AndroidAttachmentDownloads(context,localMedia,localScope,keyProvider=localDocumentKeyProvider).wipePrivateDownloadJournal()
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { _operationNotice.value = "Gateway 已解除配对，未完成下载的本机恢复记录清理失败，请重试本机账号清理。" }
                val cleanup = listOf<() -> Unit>(
                    { pairingGrants.clearFor(binding) },
                    { credentialStore.clearRefresh(profileId) },
                    { refreshJournal.clear(profileId); pendingRotations.remove(profileId) },
                    { credentialStore.clearDeviceKey(profileId) },
                    { deviceKeys.delete(profileId) },
                    { staging?.cleanup(); Unit },
                    { com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror(context,ConversationScope(profileId,current.gatewayUrl,accountId,installationId()),localDocumentKeyProvider).wipe() },
                    { com.openandroidintelligence.mobile.conversations.EncryptedAttachmentRecovery(context,ConversationScope(profileId,current.gatewayUrl,accountId,installationId()),localDocumentKeyProvider).wipe() },
                    { com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache(context,ConversationScope(profileId,current.gatewayUrl,accountId,installationId()),keyProvider=localDocumentKeyProvider).clearMedia() },
                    { pluginHost?.eraseAccount(accountId,binding.pairingId); Unit },
                    { replyPublisher.eraseAccount(profileId, accountId) },
                    { nativeConversationNotifications.eraseAccount(profileId, accountId) },
                    { AndroidEventCursorStore(context, profileId).clear(accountId) },
                    { AndroidEventCursorStore(context, platformCursorProfile(profileId)).clear(accountId) },
                    { clearLastProfile() },
                ).map { action -> runCatching(action) }
                if (cleanup.any { it.isFailure }) {
                    _operationNotice.value = "Gateway 已解除配对，但本机数据清理失败，请重试本机账户清理。"
                }
            }
        }
    }

    /** Manual and automatic renewal share the same rotation and keep in-flight work alive. */
    fun refreshSession() {
        if (connectionJob?.isActive == true || _isRefreshingSession.value) return
        val renewal = sessionRenewal ?: return
        val current = _phase.value as? ConnectionPhase.Connected ?: return
        val profileId = profileIdFor(current.gatewayUrl, current.username)
        val hasRefresh = runCatching { credentialStore.loadRefresh(profileId)?.let { secret ->
            try { secret.isNotEmpty() } finally { secret.fill(0) }
        } }.getOrDefault(false)
        if (hasRefresh != true) {
            _credentialStatus.value = GatewayCredentialStatus.UNAVAILABLE
            _operationNotice.value = "本机没有为当前账号保存刷新凭据，请重新登录以获取自动登录凭据。"
            return
        }
        val rejected = renewal.current
        scope.launch {
            try {
                renewal.refresh(rejected)
                if (sessionRenewal === renewal) {
                    _operationNotice.value = "已向 Gateway 续期会话凭据，新的访问令牌已生效。"
                    restartPlatformStream?.invoke()
                    resumeWorkbench()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (cause: Exception) {
                if (sessionRenewal === renewal) _operationNotice.value = renewalFailureNotice(cause)
            }
        }
    }

    private fun renewalFailureNotice(cause: Throwable): String = when (cause) {
        is RefreshOutcomeUnknownException -> "Gateway 尚未提供刷新结果重放，续期结果暂不确定。账号资料已保留，请更新 Gateway 后重新认证。"
        else -> "Gateway 会话恢复暂未完成，登录资料已保留，连接可用后将自动重试。"
    }

    /** Persist the operation before sending, then persist the rotated secret before publication. */
    private suspend fun rotateStoredSession(
        endpoint: GatewayEndpoint, username: String, profileId: String,
        accountId: String, deviceId: String, pins: Set<String>,
        checkOwner: () -> Unit = {},
    ): Pair<SessionCredentials, NegotiationResult> {
        checkOwner()
        pendingRotations[profileId]?.let { pending ->
            persistRotation(endpoint, username, profileId, pending.first)
            pendingRotations.remove(profileId)
            return pending
        }
        val negotiated = authClientFor(endpoint.baseUrl, pins).negotiate("neg_" + newToken())
        checkOwner()
        val tlsPin = identityTrust.verifyNegotiation(endpoint, username, negotiated)
        val refresh = credentialStore.loadRefresh(profileId)?.takeIf { it.isNotEmpty() }
            ?: error("NO_REFRESH_CREDENTIAL")
        try {
            val operation = refreshJournal.prepare(profileId, refresh, negotiated.refreshReplaySupported)
            val session = try {
                authClientFor(endpoint.baseUrl, setOfNotNull(tlsPin)).refresh(
                    accountId, deviceId, negotiated.negotiationId, refresh,
                    operation.id.takeIf { operation.replayable },
                )
            } catch (cause: GatewayAuthException) {
                // An explicit retryable refusal is a completed unsuccessful exchange.
                // Transport loss/unknown outcome retains the same journal identity.
                val definitelyRefused = cause.code != "OUTCOME_UNKNOWN" &&
                    (cause.httpStatus == 404 || (cause.retryable == true &&
                        cause.code in setOf("RATE_LIMITED", "GATEWAY_UNAVAILABLE", "SERVICE_UNAVAILABLE")))
                if (!operation.replayable && definitelyRefused) {
                    refreshJournal.clear(profileId)
                }
                throw cause
            }
            check(session.accountId == accountId && session.deviceId == deviceId &&
                session.sessionId.isNotBlank() && session.accessToken.isNotBlank() && session.refreshCredential.isNotEmpty()) {
                "REFRESH_SCOPE_MISMATCH"
            }
            val pending = session to negotiated
            pendingRotations[profileId] = pending
            persistRotation(endpoint, username, profileId, session)
            pendingRotations.remove(profileId)
            return pending
        } finally { refresh.fill(0) }
    }

    private suspend fun persistRotation(endpoint: GatewayEndpoint, username: String, profileId: String, session: SessionCredentials) {
        // Caller cancellation after the response must not abandon an already rotated credential.
        withContext(NonCancellable + Dispatchers.IO) {
            credentialStore.saveRefresh(profileId, session.refreshCredential)
            saveLastProfile(endpoint.baseUrl, username, profileId, session)
            refreshJournal.clear(profileId)
        }
    }

    private suspend fun requireReauthentication(profileId: String, code: String) {
        // Fence first, then let an already committed rotation finish its durable
        // write before clearing it. Revocation can never be undone by that write.
        _loginStatus.value = GatewayLoginStatus.REAUTHENTICATION_REQUIRED
        sessionRenewal?.close()
        sessionRenewal = null
        sessionOperationMutex.withLock { invalidateCredentialsLocked(profileId, code) }
    }

    private fun invalidateCredentialsLocked(profileId: String, code: String) {
        runCatching { credentialStore.clearRefresh(profileId) }
        runCatching { refreshJournal.clear(profileId) }
        pendingRotations.remove(profileId)
        runCatching { clearLastProfile() }
        teardown()
        _loginStatus.value = GatewayLoginStatus.REAUTHENTICATION_REQUIRED
        _credentialStatus.value = GatewayCredentialStatus.UNAVAILABLE
        _phase.value = ConnectionPhase.Failed(code)
        _operationNotice.value = "Gateway 已明确撤销或拒绝会话凭据，请重新登录。账号资料和历史记录已保留。"
    }

    fun updateDebounceSettings(gateway:String?,delayMillis:Int,extend:Boolean) {
        val preferences=com.openandroidintelligence.mobile.conversations.DebouncePreferences(context)
        preferences.write(gateway,delayMillis,extend)
        val current=(_phase.value as? ConnectionPhase.Connected)?.gatewayUrl
        _controller.value?.updateDebouncePolicy(preferences.read(current))
    }
    fun useGlobalDebounceSettings(gateway:String) {
        val preferences=com.openandroidintelligence.mobile.conversations.DebouncePreferences(context)
        preferences.useGlobal(gateway)
        _controller.value?.updateDebouncePolicy(preferences.read(gateway))
    }

    private suspend fun showOfflineMirror(endpoint:GatewayEndpoint,username:String,profileId:String,accountId:String,cause:Throwable) {
        if (!isTransientConnectionFailure(cause) || _phase.value is ConnectionPhase.OfflineMirror) return
        val mirrorScope=ConversationScope(profileId,endpoint.baseUrl,accountId,installationId())
        val mirror=kotlinx.coroutines.withContext(Dispatchers.IO) {
            com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror(context,mirrorScope,localDocumentKeyProvider)
                .also { it.finishBaselineRecovery() }
        }
        val media = kotlinx.coroutines.withContext(Dispatchers.IO) {
            com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache(context,mirrorScope,keyProvider=localDocumentKeyProvider)
        }
        val downloads = com.openandroidintelligence.mobile.conversations.AndroidAttachmentDownloads(context,media,mirrorScope,keyProvider=localDocumentKeyProvider)
        val mediaReady = prepareMediaTransitions(mirror,media)
        val downloadNotice = if (mediaReady) recoverDownloads(downloads) else MEDIA_RECOVERY_NOTICE
        val owned=SupervisorJob(scope.coroutineContext[Job]); sessionJob=owned
        _controller.value=kotlinx.coroutines.withContext(Dispatchers.IO) { WorkbenchController(CoroutineScope(owned+Dispatchers.Main.immediate),
            com.openandroidintelligence.mobile.conversations.OfflineMirrorRepository(mirror),
            object:com.openandroidintelligence.conversation.ports.AgentCommandCatalogRepository {
                override suspend fun get(gatewayId:String,languageCode:String):com.openandroidintelligence.conversation.ports.AgentCommandCatalog=throw java.io.IOException("OFFLINE_MIRROR")
            },{ mirrorScope },persistence=mirror,media=media,downloads=downloads,allowSending=false) }
        _phase.value=ConnectionPhase.OfflineMirror(endpoint.baseUrl,username)
        _operationNotice.value=downloadNotice ?: "离线镜像：可阅读历史并编辑草稿，重新连接后可发送。"
    }

    /** One retrying cold-start owner; network failures never erase the saved login. */
    @Synchronized
    fun restoreSessionIfAvailable() {
        if (backgroundRecoveryOnly && !appVisible && !sessionRecovery.hasRecoveryLease) return
        if (connectionJob?.isActive == true || _phase.value !is ConnectionPhase.Disconnected ||
            _loginStatus.value == GatewayLoginStatus.REAUTHENTICATION_REQUIRED) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastUrl = prefs.getString(KEY_LAST_GATEWAY, null) ?: return
        val lastUser = prefs.getString(KEY_LAST_USER, null) ?: return
        val profileId = prefs.getString(KEY_LAST_PROFILE, null) ?: return
        val accountId = prefs.getString(KEY_LAST_ACCOUNT, null) ?: return
        val deviceId = prefs.getString(KEY_LAST_DEVICE, null) ?: return
        if (prefs.getInt(KEY_DEVICE_KEY_ENCODING, 0) != DEVICE_KEY_ENCODING_VERSION) {
            _phase.value = ConnectionPhase.Failed("DEVICE_KEY_REGISTRATION_UPGRADE_REQUIRED")
            _loginStatus.value = GatewayLoginStatus.REAUTHENTICATION_REQUIRED
            return
        }
        val endpoint = GatewayEndpoint.parse(lastUrl) ?: return
        val hasCredential = runCatching { credentialStore.loadRefresh(profileId)?.let { it.isNotEmpty().also { _ -> it.fill(0) } } }.getOrDefault(false)
        if (hasCredential != true) return
        val backgroundOwner = backgroundRecoveryOnly
        restoringSession = true
        _loginStatus.value = GatewayLoginStatus.RESTORING
        _credentialStatus.value = GatewayCredentialStatus.AVAILABLE
        _phase.value = ConnectionPhase.Negotiating
        lateinit var ownedRestore: Job
        fun checkOwner() {
            ownedRestore.ensureActive()
            synchronized(backgroundOwnershipLock) {
                if (backgroundOwner && backgroundRecoveryOnly && !appVisible && !sessionRecovery.hasRecoveryLease) {
                    throw CancellationException("BACKGROUND_RECOVERY_STOPPED")
                }
            }
        }
        ownedRestore = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var backoff = 1_000L
            try {
                while (isActive) {
                    try {
                        checkOwner()
                        val pins = identityTrust.pinsBeforeConnect(endpoint, lastUser, restoring = true)
                        val (session, negotiated) = sessionOperationMutex.withLock {
                            _credentialStatus.value = GatewayCredentialStatus.REFRESHING
                            rotateStoredSession(endpoint, lastUser, profileId, accountId, deviceId, pins, ::checkOwner)
                        }
                        checkOwner()
                        val tlsPin = identityTrust.verifyNegotiation(endpoint, lastUser, negotiated)
                        if (_phase.value is ConnectionPhase.OfflineMirror) {
                            if (!flushWorkbenchBeforeLeaving()) return@launch
                            _controller.value?.closeAndAwait()
                            sessionJob?.cancel()
                            _controller.value = null
                        }
                        establish(endpoint, lastUser, profileId, session, tlsPin,
                            negotiated.conversationUi, negotiated.deviceRequests, GenerationCancelCapability.fromNegotiation(negotiated), ::checkOwner)
                        return@launch
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (cause: Exception) {
                        if (GatewayAuthException.credentialRefused(cause)) {
                            requireReauthentication(profileId, errorCode(cause))
                            return@launch
                        }
                        checkOwner()
                        _credentialStatus.value = GatewayCredentialStatus.RECOVERY_PENDING
                        _connectionStatus.value = EventStreamStatus.RECONNECTING
                        if (!isTransientConnectionFailure(cause)) {
                            _phase.value = ConnectionPhase.Failed(errorCode(cause))
                            _operationNotice.value = renewalFailureNotice(cause)
                            sessionKeepAlive.stop()
                            return@launch
                        }
                        showOfflineMirror(endpoint, lastUser, profileId, accountId, cause)
                        _operationNotice.value = renewalFailureNotice(cause)
                        withTimeoutOrNull(backoff) { recoveryWakeup.receive() }
                        backoff = minOf(backoff * 2, 60_000L)
                    }
                }
            } finally {
                if (connectionJob === ownedRestore) {
                    restoringSession = false
                    if (backgroundOwner && _loginStatus.value != GatewayLoginStatus.REAUTHENTICATION_REQUIRED &&
                        (!isActive || (_phase.value !is ConnectionPhase.Connected &&
                        backgroundRecoveryOnly && !appVisible && !sessionRecovery.hasRecoveryLease))) {
                        if (_phase.value !is ConnectionPhase.OfflineMirror || activeHttpClient != null) teardown()
                        _credentialStatus.value = GatewayCredentialStatus.RECOVERY_PENDING
                        _connectionStatus.value = EventStreamStatus.IDLE
                    }
                }
            }
        }
        connectionJob = ownedRestore
        synchronized(backgroundOwnershipLock) {
            if (backgroundOwner) backgroundRestoreJob = ownedRestore
        }
        ownedRestore.invokeOnCompletion { cause ->
            synchronized(backgroundOwnershipLock) {
                if (backgroundRestoreJob === ownedRestore) backgroundRestoreJob = null
            }
            // Cancellation may finish only after NonCancellable credential persistence.
            // A visible app waits for that owner before starting another recovery.
            if (cause is CancellationException && scope.isActive && appVisible && connectionJob === ownedRestore) {
                onNetworkAvailable()
            }
        }
        ownedRestore.start()
    }

    private fun isTransientConnectionFailure(cause: Throwable): Boolean = when {
        cause is RefreshOutcomeUnknownException -> false
        cause is javax.net.ssl.SSLException -> false
        cause is java.io.IOException -> true
        cause is GatewayAuthException -> cause.retryable == true || cause.httpStatus in setOf(408, 429, 500, 502, 503, 504)
        cause.message?.matches(Regex("NEGOTIATION_FAILED:(408|429|5[0-9][0-9])")) == true -> true
        else -> false
    }

    private suspend fun establish(
        endpoint: GatewayEndpoint,
        username: String,
        profileId: String,
        session: SessionCredentials,
        tlsSpkiSha256: String?,
        conversationUi: List<String>,
        deviceRequests: String?,
        generationCancelCapability: GenerationCancelCapability = GenerationCancelCapability.NotNegotiated,
        checkOwner: () -> Unit = {},
    ): Unit = initializePostLogin(InitializationStage.SESSION_SETUP) {
        checkOwner()
        val pins = setOfNotNull(tlsSpkiSha256)
        val profile = GatewayProfile(
            accountId = session.accountId,
            deviceId = session.deviceId,
            sessionId = session.sessionId,
            gatewayBaseUrl = endpoint.baseUrl,
            pinnedSpkiSha256 = pins,
            accessToken = session.accessToken,
        )
        sessionRenewal?.close()
        lateinit var renewal: GatewaySessionRenewal
        renewal = GatewaySessionRenewal(profile, scope) { rejected ->
            sessionOperationMutex.withLock {
                check(sessionRenewal === renewal) { "SESSION_RENEWAL_CLOSED" }
                _isRefreshingSession.value = true
                _credentialStatus.value = GatewayCredentialStatus.REFRESHING
                try {
                    val (rotated, _) = rotateStoredSession(endpoint, username, profileId,
                        rejected.accountId, rejected.deviceId, rejected.pinnedSpkiSha256)
                    check(sessionRenewal === renewal) { "SESSION_RENEWAL_CLOSED" }
                    accessTokenHolder = rotated.accessToken
                    lastSessionId = rotated.sessionId
                    _credentialStatus.value = GatewayCredentialStatus.AVAILABLE
                    rejected.copy(sessionId = rotated.sessionId, accessToken = rotated.accessToken)
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (cause: Exception) {
                    if (sessionRenewal === renewal) {
                        if (GatewayAuthException.credentialRefused(cause) || cause.message == "NO_REFRESH_CREDENTIAL") {
                            invalidateCredentialsLocked(profileId, errorCode(cause))
                        }
                        else {
                            _credentialStatus.value = GatewayCredentialStatus.RECOVERY_PENDING
                            _operationNotice.value = renewalFailureNotice(cause)
                        }
                    }
                    throw cause
                } finally { _isRefreshingSession.value = false }
            }
        }
        sessionRenewal = renewal
        val binding = PairingGrantBinding(
                gatewayId = endpoint.baseUrl,
                accountId = session.accountId,
                installationId = installationId(),
            )
        initializePostLogin(InitializationStage.PAIRING_STATE) { pairingGrants.bind(binding) }
        gatewayGrantRevision = session.grantRevision.toLong()
        val conversationScope = ConversationScope(profileId,endpoint.baseUrl,session.accountId,installationId())
        val mirror = initializePostLogin(InitializationStage.MIRROR_RECOVERY) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror(context,conversationScope,localDocumentKeyProvider)
                    .also { it.finishBaselineRecovery() }
            }
        }
        val legacyCursor = AndroidEventCursorStore(context, profileId)
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            if (mirror.loadCursor(session.accountId) == null) legacyCursor.load(session.accountId)?.let { cursor ->
                mirror.saveCursor(session.accountId,cursor)
                legacyCursor.clear(session.accountId)
            }
        }
        val transport = GatewayTransport(profile)
        val eventStreamStatus = com.openandroidintelligence.gateway.events.EventStreamStatusSink()
        val http = GatewayHttpClient(
            profile = profile,
            transport = transport,
            signer = { preimage -> deviceKeys.sign(profileId, preimage) },
            cursorStore = object : com.openandroidintelligence.gateway.events.EventCursorStore {
                override fun load(accountId: String) = mirror.loadCursor(accountId)
                override fun save(accountId: String, cursor: String) = mirror.saveCursor(accountId,cursor)
                override fun clear(accountId: String) = mirror.clearCursor(accountId)
            },
            statusSink = eventStreamStatus,
            // Platform lifecycle is consumed independently of the selected thread.
            handlePlatformEvent = { event -> event.event in PLATFORM_EVENTS },
            sessionRenewal = renewal,
        )
        activeHttpClient = http
        val conversationClient = ConversationClient(http)
        // Contract §7.2: approval cards are only wired when the Gateway said it
        // serves them. Without the endpoint there is nothing to press, so the
        // workbench says so instead of drawing a card that cannot be answered.
        val approvalClient = if ("agent-approval-cards-v1" in conversationUi) {
            val decisionAudit = auditStore?.let { store ->
                ApprovalAuditBridge(
                    audit = store,
                    accountId = { session.accountId },
                    pairingId = { pairingGrants.state.value?.pairingId.orEmpty() },
                )
            }
            com.openandroidintelligence.gateway.approvals.ApprovalClient(http, audit = decisionAudit)
        } else {
            null
        }
        val repository = GatewayConversationRepository(
            client = conversationClient,
            activeConversationId = { activeThread.get() },
            streamStatus = eventStreamStatus,
            approvals = approvalClient,
            supportsConversationManagement = "conversation-management-v1" in conversationUi,
        )
        // generation-cancel-v1 门禁接线：工作台的「停止生成」经由这层仓储
        // 才会带上协商门禁转发给真实客户端；未协商时保持 fail-closed。
        val workbenchRepository = CapabilityGatedConversationRepository(
            upstream = repository,
            client = conversationClient,
            capability = generationCancelCapability,
            activeConversationId = { activeThread.get() },
        )
        val catalogRepository = GatewayCommandCatalogRepository(CommandCatalogClient(http))
        // 契约 §10 设备请求执行通路：网关协商通过了 deviceRequests 才装配。
        // 触发源是网关下发 device.request.* 事件（条目 3-6），本机不自发产生
        // 请求；通路在此接好，收到请求即按 claim → result 两步执行。
        deviceRequestClient = if (deviceRequests != null) {
            DeviceRequestClient(HttpDeviceRequestTransport(http))
        } else {
            null
        }
        accessTokenHolder = session.accessToken
        lastAccountId = session.accountId
        lastDeviceId = session.deviceId
        lastSessionId = session.sessionId

        com.openandroidintelligence.gateway.diagnostics.GatewayLog.protocolEvidence("session.ready", mapOf(
            "accountId" to session.accountId, "deviceId" to session.deviceId, "sessionId" to session.sessionId,
        ))

        sessionJob?.cancel()
        val ownedJob = SupervisorJob(scope.coroutineContext[Job])
        sessionJob = ownedJob
        val sessionScope = CoroutineScope(ownedJob + Dispatchers.Main.immediate)
        sessionScope.launch {
            combine(repository.generationId, repository.generationState) { id, state ->
                id != null && state in setOf(com.openandroidintelligence.conversation.model.GenerationState.QUEUED,
                    com.openandroidintelligence.conversation.model.GenerationState.RUNNING)
            }.collect { running ->
                // The existing timeline recovery fetches the selected thread's
                // authoritative generation. Cached STREAMING rows alone cannot
                // extend a recreated foreground service's recovery lease.
                if (sessionJob === ownedJob && activeHttpClient === http) hasRecoveredGatewayWork.value = running
            }
        }
        if (pluginHost != null && deviceRequests != null) {
            initializePostLogin(InitializationStage.DEVICE_EXECUTION) {
                val publisher = com.openandroidintelligence.mobile.plugins.CapabilityPublisher(context,http,binding.storageKey,session.grantRevision,
                    localRevision = { pairingGrants.state.value?.revision ?: 0L },host = pluginHost,
                    foreignChange = { executionDriver?.cancelAll(); pairingGrants.clearCurrent(); pairingGrants.bind(binding) })
                capabilityPublisher = publisher
                val executor = com.openandroidintelligence.mobile.plugins.DeviceExecutionDriver(context,sessionScope,http,pluginHost,
                    session.accountId,session.deviceId,session.pairingGeneration,binding.storageKey,{ publisher.grantRevision },
                    isForeground = { androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) })
                executionDriver = executor
                sessionScope.launch { executor.confirmation.collect { deviceConfirmation.value = it } }
                sessionScope.launch(Dispatchers.IO) {
                    while (isActive) {
                        try { executor.recoverPending(); com.openandroidintelligence.mobile.plugins.PluginJobScheduler.maintain(context) }
                        catch (cancelled:CancellationException) { throw cancelled } catch (_:Exception) { _operationNotice.value="设备结果交付重试中。" }
                        delay(30_000)
                    }
                }
                sessionScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.flow.combine(pairingGrants.state,pluginHost.revision) { _, _ -> Unit }.collectLatest {
                        while (isActive) {
                            try { publisher.sync(); break }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { _operationNotice.value = "设备能力授权同步失败，正在重试。"; delay(2_000) }
                        }
                    }
                }
            }
        }
        val attachmentCoordinator = initializePostLogin(InitializationStage.ATTACHMENT_RECOVERY) {
            val attachmentTransport = HttpAttachmentTransport(http)
            val uploader = AttachmentUploader(attachmentTransport)
            val gate = com.openandroidintelligence.conversation.attachment.AttachmentSubmissionGate(
                onSubmit = { error("ATTACHMENT_SUBMISSION_REQUIRES_CONVERSATION_CONTROLLER") },
            )
            val staging = attachmentStagingStore(endpoint.baseUrl, session.accountId, installationId())
            activeAttachmentStaging = staging
            GatewayAttachmentDraftCoordinator(
                uploader,
                gate,
                sessionScope,
                staging,
                recovery = com.openandroidintelligence.mobile.conversations.EncryptedAttachmentRecovery(context,ConversationScope(profileId,endpoint.baseUrl,session.accountId,installationId()),localDocumentKeyProvider),
            )
        }

        val mediaCache = initializePostLogin(InitializationStage.MEDIA_CACHE) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache(context,conversationScope,http,localDocumentKeyProvider)
            }
        }
        val downloads = initializePostLogin(InitializationStage.MEDIA_CACHE) {
            com.openandroidintelligence.mobile.conversations.AndroidAttachmentDownloads(context,mediaCache,conversationScope,keyProvider=localDocumentKeyProvider)
        }
        if (prepareMediaTransitions(mirror,mediaCache)) recoverDownloads(downloads)?.let { _operationNotice.value = it }
        else _operationNotice.value = MEDIA_RECOVERY_NOTICE
        val mirroredRepository = com.openandroidintelligence.mobile.conversations.MirroredConversationRepository(workbenchRepository,mirror,mediaCache)
        http.setCursorRecovery {
            val response = http.execute(com.openandroidintelligence.gateway.http.SignedGatewayRequest("GET","/open-android-intelligence/v2/sync/snapshot"))
            check(response.status == 200) { "SNAPSHOT_FAILED" }
            val envelope = JsonFields.obj(Json.parse(response.body.decodeToString())) ?: error("SNAPSHOT_INVALID")
            val data = JsonFields.obj(JsonFields.field(envelope,"data")) ?: error("SNAPSHOT_INVALID")
            val baseline = JsonFields.string(data,"baselineCursor") ?: error("SNAPSHOT_INVALID")
            val presence = nativeConversationNotifications.capturePresenceReconciliation()
            val threads = workbenchRepository.completeConversations(conversationScope).conversations
            if (activeHttpClient === http && sessionJob === ownedJob && "conversation-management-v1" in conversationUi) {
                nativeConversationNotifications.retireMissingTargets(presence, threads.map { it.id.value }.toSet())
            }
            val pages = linkedMapOf<String,com.openandroidintelligence.conversation.ports.TimelinePage>()
            // 只补本机已经保存或当前打开的线程，从未打开的旧历史仍在首次打开时读取。
            val known = kotlinx.coroutines.withContext(Dispatchers.IO) {
                threads.filter { thread -> thread.id.value == activeThread.get() || mirror.timeline(thread.id.value) != null || mirror.loadSendAtoms(thread.id.value).isNotEmpty() }
            }
            known.forEach { thread -> pages[thread.id.value] = workbenchRepository.completeTimeline(thread.id.value) }
            mirror.installBaselineAndCommit(threads,pages,baseline)
            // 同事务保存的媒体修订先补做；缺行本身不生成删除，旧导出不能越过未应用的屏障。
            mirror.applyPendingMediaTransitions(mediaCache)
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                mirror.finishBaselineRecovery()
            }
            _controller.value?.onMirrorRebuilt()
            baseline
        }
        _controller.value = initializePostLogin(InitializationStage.WORKBENCH) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                checkOwner()
                WorkbenchController(
                    scope = sessionScope,
                    repository = mirroredRepository,
                    catalogRepository = catalogRepository,
                    languageCode = java.util.Locale.getDefault().toLanguageTag(),
                    scopeFactory = { conversationScope },
                    attachmentCoordinator = attachmentCoordinator,
                    supportsMessageBatches = setOf("message-batches-v1", "newline-v1").all { it in conversationUi },
                    debouncePolicy=com.openandroidintelligence.mobile.conversations.DebouncePreferences(context).read(endpoint.baseUrl),
                    // This describes manually submitted `/new`; the new-thread button
                    // uses the authoritative POST /conversations endpoint directly.
                    supportsAgentCommandNew = "agent-command-new-v1" in conversationUi,
                    supportsApprovalCards = approvalClient != null,
                    supportsConversationManagement = "conversation-management-v1" in conversationUi,
                    externalBusyThreadIds = {
                        bubbleBusyConversationIds() + nativeConversationNotifications.sessions.value
                            .filter { it.isRunning }.map { it.target.conversationId }
                    },
                    onActiveThreadChanged = { threadId -> activeThread.set(threadId);repository.activeConversationChanged() },
                    streamHealthSource = workbenchRepository,
                    persistence = mirror,
                    media = mediaCache,
                    downloads = downloads,
                )
            }
        }
        clearBubbleWorkTracking()
        bubbleWorkspace?.close()
        bubbleWorkspace = BubbleConversationWorkspace(profileId, session.accountId) { conversationId ->
            if (activeHttpClient !== http || sessionJob !== ownedJob || !ownedJob.isActive) null
            else {
                val bubbleStatus = com.openandroidintelligence.gateway.events.EventStreamStatusSink()
                val bubbleHttp = GatewayHttpClient(
                    profile = profile,
                    transport = transport,
                    signer = { preimage -> deviceKeys.sign(profileId, preimage) },
                    cursorStore = com.openandroidintelligence.gateway.events.InMemoryEventCursorStore(),
                    statusSink = bubbleStatus,
                    handlePlatformEvent = { event -> event.event in PLATFORM_EVENTS },
                    sessionRenewal = renewal,
                )
                createBubbleWorkbench(sessionScope, conversationScope, conversationId, bubbleHttp, bubbleStatus,
                    approvalClient, generationCancelCapability, conversationUi.toSet(),
                    com.openandroidintelligence.mobile.conversations.DebouncePreferences(context).read(endpoint.baseUrl),
                    mediaCache, downloads,
                    attachmentCoordinator = BubbleAttachmentDraftCoordinator(attachmentCoordinator),
                    externalBusyThreadIds = {
                        _controller.value?.ownedBusyThreadIds.orEmpty() + bubbleBusyConversationIds() +
                            nativeConversationNotifications.sessions.value.filter { it.isRunning }.map { it.target.conversationId }
                    })
            }
        }
        synchronized(backgroundOwnershipLock) {
            checkOwner()
            _phase.value = ConnectionPhase.Connected(
                gatewayUrl = endpoint.baseUrl,
                username = username,
                pairingSummary = session.pairingSummary,
                tlsSpkiSha256 = tlsSpkiSha256,
                transportSecurity = endpoint.securityFor(pins),
                // 协商交集：只保留客户端真的声明过并实现的能力。网关回了一个
                // 客户端没有的键，客户端无法兑现，不能让它以「已同意」的面目出现。
                conversationUi = CLIENT_CONVERSATION_UI_OFFER intersect conversationUi.toSet(),
                requestedConversationUi = CLIENT_CONVERSATION_UI_OFFER,
                deviceRequests = deviceRequests,
            )
            _loginStatus.value = GatewayLoginStatus.SIGNED_IN
            _credentialStatus.value = GatewayCredentialStatus.AVAILABLE
            backgroundSessionActive = true
        }
        GatewayLog.d("OaiConnection", "stage=CONNECTED state=ready")
        nativeConversationNotifications.bind(profileId, session.accountId)
        refreshBackgroundWork()
        val platformStatus = EventStreamStatusSink()
        val platformHttp = GatewayHttpClient(
            profile = profile,
            transport = transport,
            signer = { preimage -> deviceKeys.sign(profileId, preimage) },
            cursorStore = AndroidEventCursorStore(context, platformCursorProfile(profileId)),
            webSocketTransport = null,
            statusSink = platformStatus,
            sessionRenewal = renewal,
            handlePlatformEvent = { event ->
                if (activeHttpClient !== http || sessionJob !== ownedJob) true
                else {
                    nativeConversationNotifications.accept(profileId, session.accountId, event)
                    handlePlatformEvent(event, renewal.current.sessionId, session.deviceId, binding)
                }
            },
        )
        val notificationReconciler = if (generationCancelCapability.agreed) {
            NotificationConversationReconciler(nativeConversationNotifications, ConversationClient(platformHttp))
        } else null
        suspend fun reconcileNotifications(force: Boolean = false) {
            if (sessionJob !== ownedJob || activeHttpClient !== http) return
            try {
                if (force && "conversation-management-v1" in conversationUi) {
                    val presence = nativeConversationNotifications.capturePresenceReconciliation()
                    if (presence != null && presence.revisions.isNotEmpty()) {
                        val threads = workbenchRepository.completeConversations(conversationScope).conversations
                        if (sessionJob !== ownedJob || activeHttpClient !== http) return
                        nativeConversationNotifications.retireMissingTargets(presence, threads.map { it.id.value }.toSet())
                    }
                }
                notificationReconciler?.reconcileActive(force)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { /* Keep the last projection and retry on the next recovery tick. */ }
        }
        platformHttp.setCursorRecovery {
            val baseline = recoverPlatformSnapshot(platformHttp,
                syncCapabilities = { capabilityPublisher?.sync() },
                acceptRequest = { executionDriver?.accept(it) },
                recoverLocalRequests = { executionDriver?.recoverPending() })
            reconcileNotifications(force = true)
            baseline
        }
        // Streaming updates stay event-driven. This bounded active-task check
        // repairs missed terminal frames even when no conversation UI is open.
        sessionScope.launch(Dispatchers.IO) {
            while (isActive && sessionJob === ownedJob) {
                if (notificationReconciler?.hasPendingReconciliation() == true) reconcileNotifications()
                delay(5_000)
            }
        }
        var platformJob: Job? = null
        val streamOwnerLock = Any()
        var authenticationBlocked = false
        var blockedSnapshot: GatewayProfile? = null
        // The public connection fact is independent of login and device grants.
        sessionScope.launch {
            platformStatus.status.collect { status ->
                if (sessionJob === ownedJob) _connectionStatus.value = status
                if (status == EventStreamStatus.LIVE && sessionJob === ownedJob) {
                    withContext(Dispatchers.IO) { reconcileNotifications(force = true) }
                    _controller.value?.refreshThreads()
                }
            }
        }
        val startPlatform: () -> Unit = {
            synchronized(streamOwnerLock) {
                if ((!authenticationBlocked || blockedSnapshot != renewal.current) && sessionJob === ownedJob) {
                    val previous = platformJob
                    previous?.cancel()
                    val next = sessionScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                        previous?.join()
                        var backoff = 1_000L
                        while (isActive && sessionJob === ownedJob) {
                            try {
                                _connectionStatus.value = EventStreamStatus.CONNECTING
                                platformHttp.events().collect { _connectionStatus.value = EventStreamStatus.LIVE }
                            } catch (cancelled: CancellationException) { throw cancelled }
                              catch (rejected: EventSessionRejectedException) {
                                // A fresh session was already tried once. A misconfigured
                                // event channel cannot revoke the user's device grant.
                                authenticationBlocked = true
                                blockedSnapshot = renewal.current
                                _connectionStatus.value = EventStreamStatus.FAILED
                                _operationNotice.value = "Gateway 事件通道认证仍被拒绝，已暂停该通道。登录和本机授权已保留，请检查 Gateway 会话与设备认证配置。"
                                return@launch
                            } catch (cause: RefreshOutcomeUnknownException) {
                                authenticationBlocked = true
                                blockedSnapshot = renewal.current
                                _connectionStatus.value = EventStreamStatus.FAILED
                                _operationNotice.value = renewalFailureNotice(cause)
                                return@launch
                            } catch (cause: Exception) {
                                if (sessionJob !== ownedJob) return@launch
                                if (!isTransientConnectionFailure(cause)) {
                                    authenticationBlocked = true
                                    blockedSnapshot = renewal.current
                                    _connectionStatus.value = EventStreamStatus.FAILED
                                    _operationNotice.value = "Gateway 事件通道恢复已暂停，登录资料已保留，请检查连接配置或本机存储后重试。"
                                    return@launch
                                }
                                _connectionStatus.value = EventStreamStatus.RECONNECTING
                                nativeConversationNotifications.disconnect()
                                withTimeoutOrNull(backoff) { recoveryWakeup.receive() }
                                backoff = minOf(backoff * 2, 60_000L)
                            }
                        }
                    }
                    platformJob = next
                    next.start()
                }
            }
        }
        synchronized(backgroundOwnershipLock) {
            checkOwner()
            restartPlatformStream = startPlatform
            startPlatform()
        }
    }

    /** 附件状态失败保留正文；媒体端口保持锁定，不能先恢复旧导出再补做删除。 */
    private suspend fun prepareMediaTransitions(
        mirror: com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror,
        media: com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache,
    ): Boolean = try {
        mirror.applyPendingMediaTransitions(media)
        true
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { false }

    private suspend fun recoverDownloads(downloads: com.openandroidintelligence.mobile.conversations.AndroidAttachmentDownloads): String? = try {
        downloads.recoverPendingDownloads()
        null
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { "未完成的下载恢复失败，已保留恢复记录，可在附件卡片重试。" }

    /** 本机初始化的失败只清理本次运行态，账号资料、密钥、刷新凭据和历史文件全部保留。 */
    private inline fun <T> initializePostLogin(stage: InitializationStage, operation: () -> T): T {
        GatewayLog.d("OaiConnection", "stage=${stage.name} state=starting")
        try {
            return operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (diagnosed: PostLoginInitializationException) {
            throw diagnosed
        } catch (cause: Exception) {
            val code = "POST_LOGIN_INITIALIZATION_FAILED:${stage.name}"
            logConnectionFailure(stage.name, code, cause)
            try {
                teardown()
            } catch (cleanup: Exception) {
                logConnectionFailure(stage.name, "INITIALIZATION_CLEANUP_FAILED", cleanup)
            }
            // 不把原始异常正文、路径或密码材料带入界面状态。
            throw PostLoginInitializationException(code)
        }
    }

    private fun logConnectionFailure(stage: String, code: String, cause: Exception) {
        GatewayLog.w("OaiConnection", "stage=$stage code=$code errorType=${cause.javaClass.simpleName}")
    }

    private enum class InitializationStage {
        SESSION_SETUP,
        PAIRING_STATE,
        DEVICE_EXECUTION,
        ATTACHMENT_RECOVERY,
        MEDIA_CACHE,
        MIRROR_RECOVERY,
        WORKBENCH,
    }

    private class PostLoginInitializationException(code: String) : IllegalStateException(code)

    private fun teardown() {
        restartPlatformStream = null
        sessionRenewal?.close()
        sessionRenewal = null
        synchronized(backgroundOwnershipLock) {
            backgroundSessionActive = false
            sessionKeepAlive.stop()
        }
        clearBubbleWorkTracking()
        nativeConversationNotifications.leave()
        bubbleWorkspace?.close()
        bubbleWorkspace = null
        _controller.value?.close()
        executionDriver?.cancelAll()
        executionDriver = null
        capabilityPublisher = null
        deviceConfirmation.value = null
        sessionJob?.cancel()
        sessionJob = null
        _controller.value = null
        accessTokenHolder = null
        activeHttpClient = null
        hasRecoveredGatewayWork.value = false
        activeAttachmentStaging = null
        deviceRequestClient = null
        activeThread.set(null)
        pairingGrants.unbind()
        _phase.value = ConnectionPhase.Disconnected
        _loginStatus.value = GatewayLoginStatus.SIGNED_OUT
        _credentialStatus.value = GatewayCredentialStatus.UNAVAILABLE
        _connectionStatus.value = EventStreamStatus.IDLE
    }

    private suspend fun handlePlatformEvent(event: GatewayEvent, sessionId: String, deviceId: String, binding: PairingGrantBinding): Boolean {
        if (event.event !in PLATFORM_EVENTS) return false
        val body = JsonFields.obj(Json.parse(event.data)) ?: error("PLATFORM_EVENT_INVALID")
        val payload = JsonFields.obj(JsonFields.field(body, "payload")) ?: error("PLATFORM_EVENT_INVALID")
        when (event.event) {
            "device.requested" -> executionDriver?.accept(JsonFields.string(payload,"requestId") ?: error("PLATFORM_EVENT_INVALID"))
            "device.request.cancel.requested" -> executionDriver?.cancel(JsonFields.string(payload,"requestId") ?: error("PLATFORM_EVENT_INVALID"))
            "session.revoked" -> {
                val revoked = JsonFields.string(payload, "sessionId") ?: error("PLATFORM_EVENT_INVALID")
                if (revoked == sessionId) {
                    GatewayLog.w("OaiConnection", "stage=EVENT_STREAM code=SESSION_REVOKED")
                    val connected = _phase.value as? ConnectionPhase.Connected ?: return true
                    requireReauthentication(profileIdFor(connected.gatewayUrl, connected.username), "SESSION_REVOKED")
                }
            }
            "pairing.grant.changed" -> {
                val revision = JsonFields.long(payload, "grantRevision") ?: error("PLATFORM_EVENT_INVALID")
                check(revision >= 0) { "PLATFORM_EVENT_INVALID" }
                // Persisted pre-upgrade notifications have no target. They are
                // advisory only and cannot clear another device's local grant.
                val target = JsonFields.string(payload, "deviceId") ?: return true
                if (target != deviceId || revision <= gatewayGrantRevision) return true
                if (capabilityPublisher?.observeChange(revision.toInt(),JsonFields.string(payload,"grantDigest")) == true) {
                    gatewayGrantRevision = revision
                    return true
                }
                executionDriver?.cancelAll()
                pairingGrants.clearCurrent()
                pairingGrants.bind(binding)
                gatewayGrantRevision = revision
                _operationNotice.value = "网关授权已变更，请重新确认本机授权。"
            }
        }
        return true
    }

    private fun platformCursorProfile(profileId: String) = "$profileId:platform"

    private fun authClientFor(
        gatewayUrl: String,
        pinnedSpkiSha256: Set<String> = emptySet(),
    ): GatewayAuthClient = GatewayAuthClient(
        transport = GatewayTransport(
            GatewayProfile(
                accountId = "pre-auth",
                deviceId = "pre-auth",
                sessionId = "pre-auth",
                gatewayBaseUrl = gatewayUrl,
                pinnedSpkiSha256 = pinnedSpkiSha256,
            ),
        ),
        installationId = installationId(),
        appVersion = BuildConfig.VERSION_NAME.ifBlank { "unversioned" },
        platformApi = Build.VERSION.SDK_INT,
    )

    private var accessTokenHolder: String? = null
    private var activeHttpClient: GatewayHttpClient? = null

    /**
     * 当前会话的设备请求执行通路（契约 §10 claim/result）。
     *
     * 仅当网关协商通过了 `deviceRequests` 才存在；触发由网关下发
     * `device.request.*` 事件（条目 3-6），本机不会也没有理由自发产生请求。
     */
    var deviceRequestClient: com.openandroidintelligence.gateway.device.DeviceRequestClient? = null
        private set

    /** The session identity of the last successful login, for refresh/logout. */
    private var lastAccountId: String? = null
    private var lastDeviceId: String? = null
    private var lastSessionId: String? = null

    @Synchronized
    private fun installationId(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL, null)?.let { return it }
        val created = "install_" + newToken()
        check(prefs.edit().putString(KEY_INSTALL, created).commit()) { "INSTALLATION_ID_PERSISTENCE_FAILED" }
        return created
    }

    private fun saveLastProfile(gatewayUrl: String, username: String, profileId: String, session: SessionCredentials) {
        val endpoint = checkNotNull(GatewayEndpoint.parse(gatewayUrl))
        val retained = if (endpoint.isTls) checkNotNull(identityTrust.retained(endpoint, username)) else null
        val trustId = if (endpoint.isTls) (retained?.spki ?: "ca:${retained?.deploymentId}") else ""
        accountProfiles.save(AccountProfile(profileId, gatewayUrl, username, trustId),
            AndroidAccountProfileStore.Binding(session.accountId, session.deviceId, session.sessionId, DEVICE_KEY_ENCODING_VERSION))
        _savedProfiles.value = accountProfiles.list()
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_GATEWAY, gatewayUrl)
            .putString(KEY_LAST_USER, username)
            .putString(KEY_LAST_PROFILE, profileId)
            .putString(KEY_LAST_ACCOUNT, session.accountId)
            .putString(KEY_LAST_DEVICE, session.deviceId)
            .putString(KEY_LAST_SESSION, session.sessionId)
            .putInt(KEY_DEVICE_KEY_ENCODING, DEVICE_KEY_ENCODING_VERSION)
            .commit()
        check(saved) { "PROFILE_PERSISTENCE_FAILED" }
        lastAccountId = session.accountId
        lastDeviceId = session.deviceId
        lastSessionId = session.sessionId
    }

    private fun clearLastProfile() {
        val cleared = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_GATEWAY)
            .remove(KEY_LAST_USER)
            .remove(KEY_LAST_PROFILE)
            .remove(KEY_LAST_ACCOUNT)
            .remove(KEY_LAST_DEVICE)
            .remove(KEY_LAST_SESSION)
            .remove(KEY_DEVICE_KEY_ENCODING)
            .commit()
        check(cleared) { "PROFILE_PERSISTENCE_FAILED" }
        lastAccountId = null
        lastDeviceId = null
        lastSessionId = null
    }

    private fun profileIdFor(gatewayUrl: String, username: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$gatewayUrl|$username".toByteArray(Charsets.UTF_8))

    private fun newToken(): String =
        java.util.UUID.randomUUID().toString().replace("-", "")

    private fun errorCode(cause: Throwable): String =
        cause.message?.takeIf { it.isNotBlank() } ?: cause::class.java.simpleName

    /**
     * The TLS identity this connection may pin.
     *
     * Only HTTPS can prove one: a plaintext address pins nothing, and a digest
     * returned over plaintext is unverifiable, so it is dropped rather than
     * trusted. `null` together with an HTTPS endpoint means the Gateway did not
     * return a usable identity, which the caller fails instead of silently
     * downgrading the account to unverified system trust.
     */

    private fun attachmentStagingStore(gatewayId: String, accountId: String, installId: String): LocalAttachmentStagingStore {
        val scopeId = "$gatewayId\n$accountId\n$installId"
        val scopeHash = MessageDigest.getInstance("SHA-256").digest(scopeId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val directory = File(context.noBackupFilesDir, "attachment-staging/$scopeHash")
        val provider = AndroidKeystoreOutboxKeyProvider("oai_attachment_${scopeHash.take(32)}")
        val delegate by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            EncryptedAttachmentStagingStore(directory, provider.getOrCreate(), scopeId)
        }
        return object : LocalAttachmentStagingStore {
            override fun stage(
                selection: LocalAttachmentSelection,
                onBytesStaged: (Long) -> Unit,
                isCancelled: () -> Boolean,
            ): StagedAttachmentContent = delegate.stage(selection, onBytesStaged, isCancelled)

            override fun openStream(stagedId: String): InputStream = delegate.openStream(stagedId)

            override fun delete(stagedId: String) = delegate.delete(stagedId)

            override fun cleanupExpired(nowMillis: Long, maxAgeMillis: Long) =
                delegate.cleanupExpired(nowMillis, maxAgeMillis)

            override fun cleanup() {
                if (directory.exists()) {
                    val files = directory.listFiles() ?: error("ATTACHMENT_STORAGE_UNAVAILABLE")
                    files.filter { it.extension == "part" || it.extension == "stage" }.forEach {
                        check(it.delete()) { "ATTACHMENT_STORAGE_UNAVAILABLE" }
                    }
                }
            }
        }
    }

    private companion object {
        const val MEDIA_RECOVERY_NOTICE = "本地附件状态恢复失败，消息正文已保留，附件暂不可用，请重试同步。"
        val PLATFORM_EVENTS = setOf("session.revoked", "pairing.grant.changed", "device.requested", "device.request.cancel.requested")
        const val PREFS_NAME = "open_android_intelligence_runtime"
        const val KEY_INSTALL = "installation_id"
        const val KEY_LAST_GATEWAY = "last_gateway_url"
        const val KEY_LAST_USER = "last_username"
        const val KEY_LAST_PROFILE = "last_profile_id"
        const val KEY_LAST_ACCOUNT = "last_account_id"
        const val KEY_LAST_DEVICE = "last_device_id"
        const val KEY_LAST_SESSION = "last_session_id"
        const val KEY_DEVICE_KEY_ENCODING = "device_key_encoding_version"
        const val DEVICE_KEY_ENCODING_VERSION = 1
    }
}

/**
 * 把续期失败翻译成用户能照做的说明，且不回显原始异常文本。
 *
 * 原始 message 可能带服务端正文或网络地址，对用户没有可执行意义；界面需要的
 * 是「现在该怎么办」，而不是再一次把内部字符串摊给用户。分类只认关键字，
 * 认不出来时给最保守的一条，而不是编造一个更具体的理由。
 */
internal fun refreshFailureNotice(cause: Throwable): String {
    val text = (cause.message ?: cause::class.java.simpleName).uppercase()
    return when {
        text.contains("REFRESH_REUSED") ->
            "Gateway 拒绝续期：刷新凭据已被使用过，请重新登录以取得新的会话凭据。"
        text.contains("GATEWAY_IDENTITY_CHANGED") ->
            "刷新网关凭据失败：网关部署身份或证书已变更，请核对后重新登录以建立新信任。"
        text.contains("PROTOCOL_INCOMPATIBLE") || text.contains(":406") ->
            "Gateway 拒绝续期：App 与 Gateway 的契约版本不一致，请把两端升级到同一版本后重试。"
        text.contains("401") || text.contains("403") ||
            text.contains("REVOKED") || text.contains("UNAUTHORIZED") ->
            "Gateway 拒绝续期：当前凭据已失效或被撤销，请重新登录。"
        text.contains("TIMEOUT") || text.contains("TIMED OUT") ->
            "刷新网关凭据失败：连接超时，当前连接仍然有效，请检查网络后重试。"
        text.contains("CONNECT") || text.contains("UNKNOWNHOST") || text.contains("IOEXCEPTION") ->
            "刷新网关凭据失败：无法连接 Gateway，当前连接仍然有效，请检查网络后重试。"
        else -> "刷新网关凭据失败，当前连接仍然有效，请稍后重试。"
    }
}
