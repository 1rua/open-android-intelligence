package com.openandroidintelligence.mobile

import android.content.Context
import android.os.Build
import com.openandroidintelligence.conversation.data.GatewayAttachmentDraftCoordinator
import com.openandroidintelligence.conversation.assistant.ASSISTANT_HANDOFF_ATTACHMENT_WAIT_MILLIS
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
import com.openandroidintelligence.gateway.auth.AccountLoginChallenge
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
import com.openandroidintelligence.gateway.http.GatewayConnectionSecurity
import com.openandroidintelligence.gateway.http.requireData
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.GatewayTransport
import com.openandroidintelligence.gateway.http.TransportSecurity
import com.openandroidintelligence.gateway.negotiation.GenerationCancelCapability
import com.openandroidintelligence.gateway.negotiation.NegotiationHttpException
import com.openandroidintelligence.encrypted.store.AndroidKeystoreOutboxKeyProvider
import com.openandroidintelligence.encrypted.store.EncryptedAttachmentStagingStore
import com.openandroidintelligence.kernel.PairingGrantBinding
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.mobile.assistant.AssistantAttachmentDraftCoordinator
import com.openandroidintelligence.mobile.assistant.assistantAttachmentWrapperFor
import com.openandroidintelligence.mobile.assistant.AssistantHandoffReasons
import com.openandroidintelligence.mobile.assistant.AssistantHandoffResult
import com.openandroidintelligence.mobile.assistant.AssistantHandoffTarget
import com.openandroidintelligence.mobile.assistant.AssistantConversationWorkspace
import com.openandroidintelligence.mobile.assistant.AssistantSessionFactory
import com.openandroidintelligence.mobile.assistant.createAssistantWorkbench
import com.openandroidintelligence.mobile.assistant.createNativeAssistantWorkbench
import com.openandroidintelligence.mobile.plugins.DeviceCapabilitySyncState
import com.openandroidintelligence.mobile.plugins.capabilitySyncErrorCode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import com.openandroidintelligence.conversation.ports.ConversationRepository
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

    /** 本地登录已恢复，首次连接尚未得到明确结果。 */
    data object Initializing : ConnectionPhase

    /** 保留本地工作台时恢复远端会话。 */
    data object Reconnecting : ConnectionPhase

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
        /** Display metadata from negotiation, falling back to the actual deployment identity. */
        val agentName: String? = null,
    ) : ConnectionPhase

    data class Failed(val code: String) : ConnectionPhase
}

enum class GatewayLoginStatus { SIGNED_OUT, RESTORING, SIGNED_IN, REAUTHENTICATION_REQUIRED }
enum class GatewayCredentialStatus { UNAVAILABLE, AVAILABLE, REFRESHING, RECOVERY_PENDING }

/** Challenge material is transient and deliberately has no generated toString(). */
sealed interface AccountLoginStep {
    data object None : AccountLoginStep
    class Verification(val error: String? = null) : AccountLoginStep
    class Enrollment(val provisioningUri: String, val secret: String?, val error: String? = null) : AccountLoginStep
    class RecoveryCodes(val codes: List<String>) : AccountLoginStep
}

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
    private val initialConnectionGraceMillis: Long = 8_000L,
) {
    private var executionDriver: com.openandroidintelligence.mobile.plugins.DeviceExecutionDriver? = null
    private var selectedScreenBinding: com.openandroidintelligence.control.ScreenAuthorizationBinding? = null
    private var capabilityPublisher: com.openandroidintelligence.mobile.plugins.CapabilityPublisher? = null
    private var deviceAuthorizationAgreement: java.util.concurrent.atomic.AtomicBoolean? = null
    val deviceConfirmation = MutableStateFlow<com.openandroidintelligence.mobile.plugins.DeviceConfirmation?>(null)
    private val selectedDeviceConfirmation = MutableStateFlow<com.openandroidintelligence.mobile.plugins.DeviceConfirmation?>(null)
    val additionalDeviceConnections: AdditionalDeviceConnections? by lazy {
        pluginHost?.let { AdditionalDeviceConnections(context, scope, it, pairingGrants, installationId(),
            isForeground = { appVisible }, keyProvider = localDocumentKeyProvider) }
    }
    private val additionalConnectionProfiles = java.util.concurrent.ConcurrentHashMap<String, DeviceConnectionProfile>()
    private fun publishDeviceConfirmation() {
        deviceConfirmation.value = selectedDeviceConfirmation.value ?: additionalDeviceConnections?.confirmation?.value
    }

    val deviceCapabilityState = MutableStateFlow(DeviceCapabilitySyncState())
    val connectedAccountId: String? get() = lastAccountId.takeIf {
        activeHttpClient != null && _localSessionState.value !is LocalSessionState.SignedOut
    }
    val replyNotifications = ReplyNotificationSettings(context)
    private val replyPublisher = AndroidReplyNotificationPublisher(context, replyNotifications,
        suppress = { target -> isAssistantConversationVisible(target.conversationId) })
    private val sessionKeepAlive = AgentSessionKeepAlive(context)
    private val sessionRecovery = AgentSessionRecoveryState(context)
    private val refreshJournal = GatewayRefreshJournal(context)
    private val sessionOperationMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun sessionOperationMutex(profileId: String) = sessionOperationMutexes.computeIfAbsent(profileId) { Mutex() }
    private var sessionRenewal: GatewaySessionRenewal? = null
    private val recoveryWakeup = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var restoringSession = false
    @Volatile private var backgroundRecoveryOnly = false
    @Volatile private var backgroundRestoreJob: Job? = null
    private var restartPlatformStream: ((PlatformStreamWakeReason) -> Unit)? = null
    private var restartNativeReads: (() -> Unit)? = null
    private val pendingRotations = java.util.concurrent.ConcurrentHashMap<String, Pair<SessionCredentials, NegotiationResult>>()
    private val _loginStatus = MutableStateFlow(GatewayLoginStatus.SIGNED_OUT)
    val loginStatus: StateFlow<GatewayLoginStatus> = _loginStatus.asStateFlow()
    private val _localSessionState = MutableStateFlow<LocalSessionState>(LocalSessionState.LoadingLocal)
    val localSessionState: StateFlow<LocalSessionState> = _localSessionState.asStateFlow()
    private val _credentialStatus = MutableStateFlow(GatewayCredentialStatus.UNAVAILABLE)
    val credentialStatus: StateFlow<GatewayCredentialStatus> = _credentialStatus.asStateFlow()
    private val _connectionStatus = MutableStateFlow(EventStreamStatus.IDLE)
    val connectionStatus: StateFlow<EventStreamStatus> = _connectionStatus.asStateFlow()
    private val _coreHttpHealth = MutableStateFlow(CoreHttpHealth())
    /** Last observed HTTP fact; independent of Platform and Native stream health. */
    val coreHttpHealth: StateFlow<CoreHttpHealth> = _coreHttpHealth.asStateFlow()
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
    /**
     * 原生模式下气泡的"新建会话"入口。
     *
     * 旧 `/conversations` 路由在 Core 上不存在，创建只能交给当前 Agent；未装配时
     * 为 null，气泡沿用旧链路的实现。
     */
    @Volatile private var bubbleThreadCreator: (suspend (ReplyNotificationTarget) -> ReplyNotificationTarget?)? = null
    /** 切换 Agent 后要补做的一次会话导航；只保留用户最后一次点击。 */
    @Volatile private var pendingNotificationNavigation: ReplyNotificationTarget? = null
    /**
     * 当前已发布到 [controller] 的工作台属于哪个 Agent。
     *
     * 切 Agent 时 `selectedAgentId` 会先同步更新，而新工作台要等旧分区 IO 收尾后
     * 才发布。用前者判断"已经切好了"，会在旧工作台上打开会话并误报成功，点击
     * 的那条通知随后被清掉。只有这个字段才代表交接真的完成。
     */
    @Volatile private var activeWorkbenchAgentId: String? = null
    /**
     * 系统数字助理的临时会话工作区；与主工作台、气泡互不共享选择与草稿。
     *
     * 它只属于一个 (profile, account, agent) 身份：账号或 Agent 切换时由
     * [closeAssistantWorkspaceAndAwait] 连同旧会话一起退役。
     */
    @Volatile private var assistantWorkspace: AssistantConversationWorkspace? = null
    private var assistantWorkJob: Job? = null
    /** 只保护工作区引用的安装/退役，不覆盖工作区内部的控制器锁。 */
    private val assistantWorkspaceLock = Any()
    /** 当前会话可构建的助手控制器工厂；teardown 后为 null。 */
    @Volatile private var assistantSessionFactory: AssistantSessionFactory? = null
    /** 助理界面全部离开；工作区在途工作结束时再退役。 */
    @Volatile private var assistantWorkspaceRetirePending = false
    /** 已声明「正在展示」的客户端 → 会话；只用于抑制同会话的原生通知。 */
    private val assistantPresence = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 已接受、等待主 App 界面消费的交接目标（sessionToken → 目标）。 */
    private val pendingHandoffTargets =
        java.util.concurrent.ConcurrentHashMap<String, AssistantHandoffTarget>()

    /** 每个助手会话的附件包装器：交接成功后据此把草稿移出助手所有权（见 `disown`）。 */
    private val assistantAttachmentWrappers =
        java.util.concurrent.ConcurrentHashMap<String, AssistantAttachmentDraftCoordinator>()

    /**
     * 主工作台与助手会话**共用**的附件协调器（按 Agent 分区）。
     *
     * 交接要求两侧拿到同一个包装器/协调器：不同实例按 draftId 交接会得到一个永不推进
     * 的 `LOCAL_PREPARING` 流，发送时落到 `ATTACHMENT_NOT_VERIFIED`。原生会话模式下主
     * 工作台与助手工厂都从这里取，保证附件所有权真正可转移。
     */
    private val nativeOwningAttachmentCoordinators =
        java.util.concurrent.ConcurrentHashMap<String, com.openandroidintelligence.conversation.ports.AttachmentDraftCoordinator>()
    private val _assistantWorkspaceVersion = MutableStateFlow(0)
    /** 助手工作区的安装/退役代次；助理进程据此接回已登记的会话。 */
    internal val assistantWorkspaceVersion: StateFlow<Int> = _assistantWorkspaceVersion.asStateFlow()

    /** 当前连接所属本地档案；未连接时为 null。不暴露任何凭据。 */
    internal val connectedProfileId: String?
        get() {
            val connected = _phase.value as? ConnectionPhase.Connected ?: return null
            return profileIdFor(connected.gatewayUrl, connected.username)
        }

    /** 当前 Agent 展示名；未连接时为 null。 */
    internal val connectedAgentName: String?
        get() = (_phase.value as? ConnectionPhase.Connected)?.agentName

    internal fun isBubbleTargetCurrent(target: ReplyNotificationTarget): Boolean {
        val connected = _phase.value as? ConnectionPhase.Connected ?: return false
        if (connectedAccountId != target.accountId) return false
        if (profileIdFor(connected.gatewayUrl, connected.username) != target.profileId) return false
        if (bubbleWorkspace?.accepts(target) != true) return false
        // 原生会话 ID 只在同一个 Agent 内唯一：目标带 Agent 时，该 Agent 必须在
        // 本账号当前发现到的列表里，气泡才不会打开一个已经不存在的 Agent 的会话。
        return target.agentId?.let { id ->
            _nativeAgents.value?.state?.value?.agents?.any { it.id == id } == true
        } ?: true
    }

    /** A new bubble conversation never consumes the main window's draft or selection. */
    internal suspend fun createBubbleThread(target: ReplyNotificationTarget): ReplyNotificationTarget {
        check(isBubbleTargetCurrent(target)) { "BUBBLE_SESSION_UNAVAILABLE" }
        // 原生 Agent 会话不能走旧 `/conversations` 路由，创建必须由该 Agent 完成。
        bubbleThreadCreator?.invoke(target)?.let { created ->
            check(isBubbleTargetCurrent(created)) { "BUBBLE_SESSION_CHANGED" }
            _controller.value?.refreshThreads()
            return created
        }
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

    /** 助理会话正在发送的线程；主工作台与气泡都把它视为忙碌，避免管理操作踩到在途提交。 */
    private fun assistantBusyThreadIds(): Set<String> = assistantWorkspace?.busyThreadIds().orEmpty()

    /** 系统助理界面是否正展示这条会话；同会话的原生通知气泡因此不再打扰用户。 */
    internal fun isAssistantConversationVisible(conversationId: String): Boolean {
        if (assistantPresence.isEmpty()) return false
        val workspace = assistantWorkspace ?: return false
        return assistantPresence.values.any { sessionToken ->
            workspace.conversationFor(sessionToken) == conversationId
        }
    }

    /**
     * 系统数字助理通过主进程打开一条会话控制器。
     *
     * 同一个 sessionToken 重复调用是幂等的：助理进程重连不会替换仍在发送的控制器。
     * 工作区尚未建立（未连接、正在切换账号）时返回 null，调用方据此呈现未连接状态。
     */
    internal fun createAssistantSessionController(sessionToken: String): WorkbenchController? {
        val workspace = assistantWorkspace ?: return null
        return runCatching { workspace.open(sessionToken) }.getOrNull()
    }

    /**
     * 连接就绪后接回助理进程已登记的会话。
     *
     * 与 [createAssistantSessionController] 的区别：工作区还没建立（连接刚完成、Agent
     * 刚发现）时先按当前身份创建，再打开同一 token；仍不可用时返回 null。
     */
    internal fun reattachAssistantSession(sessionToken: String): WorkbenchController? {
        if (!ensureAssistantWorkspace()) return null
        return createAssistantSessionController(sessionToken)
    }

    /**
     * 确保助手工作区存在；不存在且当前连接允许时按当前身份创建。
     *
     * 工作区只属于一个 (profile, account, agent)：身份不匹配时返回 false，由调用方
     * 呈现未连接，而不是把旧会话借给新账号。
     */
    internal fun ensureAssistantWorkspace(): Boolean {
        val factory = assistantSessionFactory ?: return false
        val agentId = assistantAgentId()
        var replaced: AssistantConversationWorkspace? = null
        synchronized(assistantWorkspaceLock) {
            val current = assistantWorkspace
            if (current != null) {
                if (current.matches(factory.profileId, factory.accountId, agentId)) return true
                // 身份已变化：旧工作区必须连同在途会话一起退役，绝不借给新身份。
                assistantWorkspace = null
                assistantWorkJob?.cancel()
                assistantWorkJob = null
                assistantPresence.clear()
                assistantWorkspaceRetirePending = false
                _assistantWorkspaceVersion.value++
                replaced = current
            }
            val workspace = AssistantConversationWorkspace(
                profileId = factory.profileId,
                accountId = factory.accountId,
                agentId = agentId,
                scope = factory.scope,
                create = { token -> factory.create(agentId, token) },
                onSessionReleased = factory::release,
            )
            assistantWorkspace = workspace
            assistantWorkspaceRetirePending = false
            // 助手任务同样需要后台保活：只读原子 StateFlow，不取工作区锁。
            // 最后一个在途会话结束后，若界面已全部离开，再真正退役工作区；
            // 退役会取消本订阅，因此放到独立任务里执行，不在 collect 内自取消。
            assistantWorkJob = scope.launch {
                workspace.hasActiveWork.collect {
                    refreshBackgroundWork()
                    if (!it) scope.launch { maybeRetireAssistantWorkspace() }
                }
            }
            _assistantWorkspaceVersion.value++
        }
        replaced?.close()
        refreshBackgroundWork()
        return true
    }

    /** 助理客户端当前展示的会话；租约变化时回调，供通知层立即撤回重复提醒。 */
    private fun publishAssistantPresence(clientToken: String, sessionToken: String?) {
        if (sessionToken == null) assistantPresence.remove(clientToken)
        else assistantPresence[clientToken] = sessionToken
        val conversationId = sessionToken?.let { token -> assistantWorkspace?.conversationFor(token) }
        if (conversationId != null) replyPublisher.cancel(ReplyNotificationTarget(
            profileId = connectedProfileId.orEmpty(),
            accountId = connectedAccountId.orEmpty(),
            conversationId = conversationId,
        ))
    }

    /** 助理进程声明/撤销「正在展示本会话」，用于抑制原生气泡。 */
    internal fun setAssistantPresence(clientToken: String, sessionToken: String?, visible: Boolean) {
        publishAssistantPresence(clientToken, sessionToken.takeIf { visible })
    }

    /**
     * 助理客户端离开。
     *
     * 该客户端名下的会话已由 `releaseAssistantSession` 按在途状态交还；这里只撤销
     * 可见性，并在最后一个客户端离开后请求退役工作区。退役同样等最后一条在途
     * 工作结束，不取消已提交的发送，也不影响其他仍在使用的客户端。
     */
    internal fun releaseAssistantClient(clientToken: String) {
        assistantPresence.remove(clientToken)
        synchronized(assistantWorkspaceLock) {
            if (assistantWorkspace == null) return
            if (assistantPresence.isNotEmpty()) return
            assistantWorkspaceRetirePending = true
        }
        maybeRetireAssistantWorkspace()
        refreshBackgroundWork()
    }

    /** 助理界面关闭：有在途发送时延迟关闭，等真实终态再释放。 */
    internal fun releaseAssistantSession(sessionToken: String) {
        assistantWorkspace?.release(sessionToken)
        maybeRetireAssistantWorkspace()
        refreshBackgroundWork()
    }

    /** 没有界面在用时退役工作区；仍有在途发送则等它结束。 */
    private fun maybeRetireAssistantWorkspace() {
        val retired = synchronized(assistantWorkspaceLock) {
            val workspace = assistantWorkspace ?: return
            if (!assistantWorkspaceRetirePending || assistantPresence.isNotEmpty()) return
            if (workspace.hasActiveWork.value) return
            assistantWorkspace = null
            assistantWorkJob?.cancel()
            assistantWorkJob = null
            assistantWorkspaceRetirePending = false
            _assistantWorkspaceVersion.value++
            workspace
        }
        retired.close()
        refreshBackgroundWork()
    }

    /** 账号/Agent 切换：先摘下旧工作区，再等它把本地提交收尾。 */
    private suspend fun closeAssistantWorkspaceAndAwait() {
        val retired = synchronized(assistantWorkspaceLock) {
            val current = assistantWorkspace
            assistantWorkspace = null
            assistantWorkJob?.cancel()
            assistantWorkJob = null
            assistantPresence.clear()
            assistantWorkspaceRetirePending = false
            if (current != null) _assistantWorkspaceVersion.value++
            current
        }
        retired?.closeAndAwait()
        refreshBackgroundWork()
    }

    /** 同步退役（登出、配对解除等不可等待的收尾路径）。 */
    private fun retireAssistantWorkspace() {
        val retired = synchronized(assistantWorkspaceLock) {
            val current = assistantWorkspace
            assistantWorkspace = null
            assistantWorkJob?.cancel()
            assistantWorkJob = null
            assistantPresence.clear()
            assistantWorkspaceRetirePending = false
            if (current != null) _assistantWorkspaceVersion.value++
            current
        }
        retired?.close()
        refreshBackgroundWork()
    }

    /**
     * 账号切换：旧账号的助手会话不能被带进新连接。
     *
     * 只比较 profile/account；Agent 选择在连接建立后才可发现，由
     * [ensureAssistantWorkspace] 的身份比对兜底。
     */
    private suspend fun closeAssistantWorkspaceForAccountChange(profileId: String, accountId: String) {
        val current = assistantWorkspace ?: return
        if (current.profileId == profileId && current.accountId == accountId) return
        closeAssistantWorkspaceAndAwait()
    }
    /**
     * 当前 Agent 身份；主工作台与原生发现都从这里取值。
     *
     * 助手工作区与主工作台必须锁定同一个 Agent，否则同一账号下切换 Agent 后
     * 旧助手会话仍会把消息发往已不再选中的 Agent。
     */
    private fun assistantAgentId(): String? =
        _nativeAgents.value?.state?.value?.agent?.id ?: _workbenchAgentId

    private var _workbenchAgentId: String? = null

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

    /**
     * 为一个助手会话创建附件包装器，并**同时**登记到 [assistantAttachmentWrappers]。
     *
     * 两件事必须在同一处：原生与旧版 Gateway 两条分支都走这里，任何一条忘了登记，
     * `prepareAssistantHandoff` 的 `disown` 就永远不会执行——浮层关闭时工作区退役，
     * 包装器会把刚迁给主 App 的草稿连同暂存文件一起 `discard`。把「创建」和「登记」
     * 绑成一个不可拆分的动作，比让每个分支各自记得登记可靠。
     */
    private fun assistantAttachmentWrapper(
        owning: com.openandroidintelligence.conversation.ports.AttachmentDraftCoordinator?,
        sessionToken: String,
    ): AssistantAttachmentDraftCoordinator? =
        assistantAttachmentWrapperFor(assistantAttachmentWrappers, owning, sessionToken)

    /**
     * 把助手浮层的会话、草稿与附件交给主 App（「上滑进入主 App」）。
     *
     * 严格失败：身份不匹配、控制器未就绪、目标线程打不开都返回拒绝原因，由浮层保留
     * 草稿与浮层并如实说明，绝不静默丢弃。成功时返回交接目标（含深链需要的身份）。
     *
     * 附件必须先全部就绪：规格 §3.4 要求「仍有附件在上传/校验中时，交接等待其完成
     * （有上限）；超时或校验失败按严格交接处理」。这里先等
     * [ASSISTANT_HANDOFF_ATTACHMENT_WAIT_MILLIS]，超时直接拒绝，**不**做「先迁移已
     * 就绪的、再跳过其余的」——后者会让未迁移的附件留在助手包装器里，浮层关闭时被
     * 连同暂存文件一起丢弃，而用户几乎看不到那句提示。
     *
     * 调用顺序：先 `openThread`（它会在别处用主工作台自己的草稿快照覆盖输入栏），
     * 再 `adoptAssistantDraft`。接管只覆盖非空内容：助手草稿/附件为空时保留主 App
     * 输入栏里已有的东西（见 [WorkbenchController.adoptAssistantDraft]）。
     */
    internal suspend fun prepareAssistantHandoff(sessionToken: String): AssistantHandoffResult {
        val profileId = connectedProfileId
        val accountId = connectedAccountId
        if (profileId == null || accountId == null) {
            return AssistantHandoffResult.Rejected(AssistantHandoffReasons.NOT_CONNECTED)
        }
        val workspace = assistantWorkspace
            ?: return AssistantHandoffResult.Rejected(AssistantHandoffReasons.NOT_CONNECTED)
        val agentId = assistantAgentId()
        if (!workspace.matches(profileId, accountId, agentId)) {
            return AssistantHandoffResult.Rejected(AssistantHandoffReasons.ACCOUNT_MISMATCH)
        }
        // 主工作台必须已经切到同一个 Agent：否则会把助手草稿并入旧 Agent 的输入栏，
        // 或把另一 Agent 的会话 ID 交给 openThread。原生会话下两者都由发布值证明。
        if (_nativeAgents.value != null && agentId != null && _workbenchAgentId != agentId) {
            return AssistantHandoffResult.Rejected(AssistantHandoffReasons.AGENT_NOT_READY)
        }
        val receiver = _controller.value
            ?: return AssistantHandoffResult.Rejected(AssistantHandoffReasons.WORKBENCH_NOT_READY)
        val assistantController = runCatching { workspace.open(sessionToken) }.getOrNull()
            ?: return AssistantHandoffResult.Rejected(AssistantHandoffReasons.NOT_CONNECTED)
        // 等待上传/校验中的附件变成 VERIFIED；超时或校验失败整次拒绝，浮层与草稿原样保留。
        if (!assistantController.awaitHandoffAttachments(ASSISTANT_HANDOFF_ATTACHMENT_WAIT_MILLIS)) {
            return AssistantHandoffResult.Rejected(AssistantHandoffReasons.ATTACHMENTS_NOT_READY)
        }
        val draft = assistantController.assistantDraftForHandoff()
        val conversationId = assistantController.state.value.activeThreadId
        // 尚未产生会话（首次唤起、还没发过消息）：不打开、也不新建线程，只并入输入栏。
        if (conversationId != null) receiver.openThread(conversationId)
        val skipped = receiver.adoptAssistantDraft(draft.text, draft.attachments)
        // 附件所有权已随草稿转移到主工作台：先把它们移出助手包装器，
        // 否则浮层随后关闭时会把主 App 刚接管的附件连同暂存文件一起丢弃。
        assistantAttachmentWrappers.remove(sessionToken)?.let { wrapper ->
            draft.attachments.forEach { migrated -> runCatching { wrapper.disown(migrated.id.value) } }
        }
        val target = AssistantHandoffTarget(profileId, accountId, agentId, conversationId)
        pendingHandoffTargets[sessionToken] = target
        return AssistantHandoffResult.Accepted(target, skippedAttachments = skipped)
    }

    /**
     * 浮层命中交接目标：切到目标 Agent（若不同）、打开会话并关掉 App 内的助手浮层。
     *
     * 与 [prepareAssistantHandoff] 的区别是「数据已在主进程」：这里只负责把界面导航到
     * 那条会话。`agentId` 变化时用 [nativeNavigationWaitsForWorkbench] 判断工作台是否
     * 已经真的交接完，未就绪返回 false 交给调用方重试/提示，不把会话开在旧工作台上。
     */
    internal fun openAssistantHandoff(target: AssistantHandoffTarget): Boolean {
        val connected = _phase.value as? ConnectionPhase.Connected ?: return false
        if (target.accountId != connectedAccountId ||
            target.profileId != profileIdFor(connected.gatewayUrl, connected.username)) return false
        target.agentId?.let { agentId ->
            val agents = _nativeAgents.value ?: return@let
            if (agents.state.value.selectedAgentId != agentId) agents.selectAgent(agentId)
            if (nativeNavigationWaitsForWorkbench(
                    agentId, agents.state.value.selectedAgentId, _workbenchAgentId)) return false
        }
        val workbench = _controller.value ?: return false
        target.conversationId?.let(workbench::openThread)
        return true
    }

    /**
     * 主 App 界面消费一次交接：用 [sessionToken] 找回被接受的目标并导航到那条会话。
     *
     * 只认主进程刚刚接受过的那一次（表里没有就是失败）：通知栈重放或伪造的 token
     * 都不能借这条路径打开一个会话。返回 false 时调用方如实提示，不清掉浮层状态。
     */
    internal fun consumeAssistantHandoff(sessionToken: String): Boolean {
        val target = pendingHandoffTargets.remove(sessionToken) ?: return false
        if (!openAssistantHandoff(target)) {
            // 导航失败（Agent 尚未就绪等）保留目标，交给后续 frame/重试再消费一次。
            pendingHandoffTargets[sessionToken] = target
            return false
        }
        return true
    }

    /** A notification never borrows another account's currently open workbench. */
    internal fun openReplyNotification(target: ReplyNotificationTarget): Boolean {
        val connected = _phase.value as? ConnectionPhase.Connected ?: return false
        if (target.accountId != connectedAccountId || target.profileId != profileIdFor(connected.gatewayUrl, connected.username)) return false
        val workbench = _controller.value ?: return false
        workbench.openThread(target.conversationId)
        return true
    }
    fun decideDeviceRequest(id: String, approved: Boolean, durationMinutes: Int = 0, decisionToken: String? = null) {
        val shown = deviceConfirmation.value ?: return
        if (shown.requestId != id || decisionToken != null && shown.decisionToken != decisionToken) return
        val selected = executionDriver
        if (selected?.confirmation?.value == shown) selected.decide(id, approved, durationMinutes)
        else if (additionalDeviceConnections?.confirmation?.value == shown)
            additionalDeviceConnections?.respond(id, approved, durationMinutes, shown.decisionToken)
    }

    private val _phase = MutableStateFlow<ConnectionPhase>(ConnectionPhase.Disconnected)
    val phase: StateFlow<ConnectionPhase> = _phase.asStateFlow()
    private val _accountLoginStep = MutableStateFlow<AccountLoginStep>(AccountLoginStep.None)
    val accountLoginStep: StateFlow<AccountLoginStep> = _accountLoginStep.asStateFlow()
    private val accountLoginInput = Channel<AccountLoginInput>(capacity = 1, onUndeliveredElement = { it.proof?.fill('\u0000') })

    private class AccountLoginInput(val proof: CharArray? = null, val recovery: Boolean = false)

    /** Ownership of each proof transfers to the login coroutine or is cleared immediately. */
    fun submitAccountLoginProof(proof: CharArray, recovery: Boolean = false) {
        val step = _accountLoginStep.value
        if ((step !is AccountLoginStep.Verification && step !is AccountLoginStep.Enrollment) ||
            !_accountLoginStep.compareAndSet(step, AccountLoginStep.None) ||
            !accountLoginInput.trySend(AccountLoginInput(proof, recovery)).isSuccess) {
            proof.fill('\u0000')
        }
    }

    fun confirmAccountRecoveryCodesSaved() {
        val step = _accountLoginStep.value
        if (step is AccountLoginStep.RecoveryCodes && _accountLoginStep.compareAndSet(step, AccountLoginStep.None)) {
            accountLoginInput.trySend(AccountLoginInput())
        }
    }

    fun cancelAccountLogin() {
        if (_accountLoginStep.value === AccountLoginStep.None) return
        _accountLoginStep.value = AccountLoginStep.None
        connectionJob?.cancel()
        _phase.value = ConnectionPhase.Disconnected
    }
    private val _savedProfiles = MutableStateFlow(accountProfiles.list())
    val savedProfiles: StateFlow<List<AccountProfile>> = _savedProfiles.asStateFlow()
    private val _unpairOperation = MutableStateFlow<LocalOperationState>(LocalOperationState.Idle)
    val unpairOperation: StateFlow<LocalOperationState> = _unpairOperation.asStateFlow()
    fun dismissUnpairOperation() {
        if (_unpairOperation.value !is LocalOperationState.Running) _unpairOperation.value = LocalOperationState.Idle
    }
    private val pushHttpClients = java.util.concurrent.ConcurrentHashMap<String, GatewayHttpClient>()
    private val pushConnectionLock = Mutex()

    private val deviceProfileEpochs = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private fun fenceDeviceProfile(profileId: String) {
        deviceProfileEpochs.computeIfAbsent(profileId) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        pushHttpClients.remove(profileId)
        additionalConnectionProfiles.remove(profileId)
    }

    fun isPushProfileLoggedIn(profileId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean("signed_out_$profileId", false) || accountProfiles.find(profileId) == null) return false
        return runCatching {
            credentialStore.loadRefresh(profileId)?.let { bytes ->
                try { bytes.isNotEmpty() } finally { bytes.fill(0) }
            } ?: false
        }.getOrDefault(false)
    }

    fun hasLoggedInPushAccount(): Boolean = accountProfiles.list().any { isPushProfileLoggedIn(it.localProfileId) }

    /** Each authenticated account discovers independently without changing the visible workbench. */
    fun pushConnections(): kotlinx.coroutines.flow.Flow<com.openandroidintelligence.mobile.push.PushEndpointDiscovery> =
        kotlinx.coroutines.flow.flow {
            pushConnectionLock.withLock {
                val saved = accountProfiles.list()
                val loggedIn = saved.filter { isPushProfileLoggedIn(it.localProfileId) }
                val currentIds = loggedIn.map { it.localProfileId }.toSet()
                pushHttpClients.keys.retainAll(currentIds)
                additionalConnectionProfiles.keys.removeAll { id ->
                    id !in currentIds || additionalConnectionProfiles[id]?.isCurrent?.invoke() != true
                }
                independentConnectionDiscovery(loggedIn, discover = ::discoverPushConnection,
                    unavailable = { GatewayLog.d("OaiDevicePush", "stage=discover code=PUSH_CONNECTION_UNAVAILABLE") })
                    .collect { emit(it) }
            }
        }

    private suspend fun discoverPushConnection(saved: AccountProfile): com.openandroidintelligence.mobile.push.PushEndpointDiscovery? {
        val id = saved.localProfileId
        val endpoint = GatewayEndpoint.parse(saved.gatewayBaseUrl)?.takeIf { it.isTls } ?: return null
        val account = accountProfiles.binding(id) ?: return null
        val epoch = deviceProfileEpochs.computeIfAbsent(id) { java.util.concurrent.atomic.AtomicLong() }.get()
        val current = { deviceProfileEpochs[id]?.get() == epoch && isPushProfileLoggedIn(id) &&
            accountProfiles.binding(id)?.accountId == account.accountId }
        val http = if (id == context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_PROFILE, null) && activeHttpClient != null) {
            check(deviceAuthorizationAgreement?.get() == true) { "DEVICE_AUTHORIZATION_MODEL_UNSUPPORTED" }
            pushHttpClients.remove(id)
            activeHttpClient!!
        } else pushHttpClients[id] ?: sessionOperationMutex(id).withLock {
            check(current()) { "PUSH_ACCOUNT_LOGGED_OUT" }
            pushHttpClients[id] ?: run {
                val trust = identityTrust.retained(endpoint, saved.username) ?: error("GATEWAY_IDENTITY_UNVERIFIED")
                val (credentials, negotiated) = rotateStoredSession(endpoint, saved.username, id,
                    account.accountId, account.deviceId, setOfNotNull(trust.spki), preservePresentation = true)
                check(negotiated.androidDeviceAuthorization && current()) { "DEVICE_AUTHORIZATION_MODEL_UNSUPPORTED" }
                val profile = GatewayProfile(accountId = credentials.accountId, deviceId = credentials.deviceId,
                    sessionId = credentials.sessionId, gatewayBaseUrl = endpoint.baseUrl,
                    pinnedSpkiSha256 = setOfNotNull(trust.spki), accessToken = credentials.accessToken)
                val renew = GatewaySessionRenewal(profile, scope) { rejected ->
                    sessionOperationMutex(id).withLock {
                        check(current()) { "PUSH_ACCOUNT_LOGGED_OUT" }
                        val (rotated, agreement) = rotateStoredSession(endpoint, saved.username, id,
                            rejected.accountId, rejected.deviceId, rejected.pinnedSpkiSha256,
                            preservePresentation = true)
                        check(agreement.androidDeviceAuthorization && current()) { "DEVICE_AUTHORIZATION_MODEL_UNSUPPORTED" }
                        rejected.copy(sessionId = rotated.sessionId, accessToken = rotated.accessToken)
                    }
                }
                GatewayHttpClient(profile = profile, transport = GatewayTransport(profile),
                    signer = { preimage -> deviceKeys.sign(id, preimage) },
                    cursorStore = AndroidEventCursorStore(context, "push:$id"), sessionRenewal = renew)
                    .also { pushHttpClients[id] = it }
            }
        }
        val discovery = com.openandroidintelligence.mobile.push.GatewayPushTransport(
            com.openandroidintelligence.mobile.push.PushGatewayConnections { _, _ -> null }).discover(http, current)
        val selectedProfile = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_PROFILE, null)
        if (id != selectedProfile || executionDriver == null) {
            val descriptor = try {
                val pairing = optionalDevicePairing(http, current) ?: run {
                    // Device recovery can retry while the authenticated push route advances.
                    GatewayLog.d("OaiDevicePush", "stage=discover code=DEVICE_PAIRING_RECOVERY_PENDING")
                    return discovery
                }
                check(com.openandroidintelligence.gateway.schema.JsonFields.string(pairing, "deviceId") == account.deviceId) {
                    "DEVICE_PROFILE_BINDING_MISMATCH"
                }
                DeviceConnectionProfile(id, endpoint.baseUrl, account.accountId, account.deviceId,
                    com.openandroidintelligence.gateway.schema.JsonFields.int(pairing, "pairingGeneration") ?: error("DEVICE_PAIRING_INVALID"),
                    http, current, com.openandroidintelligence.gateway.schema.JsonFields.int(pairing, "grantRevision") ?: error("DEVICE_PAIRING_INVALID"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                // An invalid binding or identity must also retire an older cached profile.
                fenceDeviceProfile(id)
                throw failure
            }
            check(current()) { "PUSH_ACCOUNT_LOGGED_OUT" }
            additionalConnectionProfiles[id] = descriptor
        } else additionalConnectionProfiles.remove(id)
        check(current()) { "PUSH_ACCOUNT_LOGGED_OUT" }
        return discovery
    }

    suspend fun syncAdditionalDeviceRequests() {
        val profiles = additionalConnectionProfiles.values.toList()
        additionalDeviceConnections?.sync(profiles)
    }

    private val _isManagingProfiles = MutableStateFlow(false)
    val isManagingProfiles: StateFlow<Boolean> = _isManagingProfiles.asStateFlow()

    private val _controller = MutableStateFlow<WorkbenchController?>(null)
    private var workspacePresented = false
    val controller: StateFlow<WorkbenchController?> = _controller.asStateFlow()
    private val _nativeAgents = MutableStateFlow<NativeAgentsController?>(null)
    val nativeAgents: StateFlow<NativeAgentsController?> = _nativeAgents.asStateFlow()

    private val _operationNotice = MutableStateFlow<String?>(null)
    val operationNotice: StateFlow<String?> = _operationNotice.asStateFlow()
    fun dismissOperationNotice() { _operationNotice.value = null }
    internal val canRetryWorkbenchInitialization: Boolean
        get() = _operationNotice.value == WORKBENCH_RECOVERY_NOTICE

    suspend fun setAccountAvatar(profileId: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty() && bytes.size <= ACCOUNT_AVATAR_MAX_BYTES)
        synchronized(accountProfiles) {
            accountProfiles.saveAvatar(profileId, Base64.getEncoder().encodeToString(bytes))
            _savedProfiles.value = accountProfiles.list()
        }
    }

    init {
        if (pluginHost != null) scope.launch {
            additionalDeviceConnections?.confirmation?.collect { publishDeviceConfirmation() }
        }
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
        scope.launch {
            _nativeAgents.collectLatest { native ->
                if (native == null) refreshBackgroundWork()
                else native.state.map { it.sending }.distinctUntilChanged().collect { refreshBackgroundWork() }
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
        wakeConnection(PlatformStreamWakeReason.FOREGROUND)
    }

    /**
     * Republish device capabilities after a local Android authorization change.
     *
     * Called when the user returns from a system settings page (accessibility,
     * notification access) or finishes a MediaProjection consent dialog: the
     * published inventory still describes the previous Android state, so Core
     * would keep reporting a stale `ANDROID_PERMISSION_REQUIRED` even though the
     * user just granted it.
     *
     * Absent session/owner means there is nothing to synchronize with — a no-op,
     * never a fabricated success. Failures stay visible through
     * [deviceCapabilityState] like any other publication error.
     */
    fun requestCapabilitySync() {
        val publisher = capabilityPublisher ?: return
        scope.launch(Dispatchers.IO) {
            // The publisher serializes its own work and re-checks ownership;
            // a throwing sync already recorded its error state for the UI.
            runCatching { publisher.sync() }
        }
    }

    /** Duplicate availability signals preserve healthy process-owned channels. */
    fun onNetworkAvailable(routeChanged: Boolean = false) {
        wakeConnection(if (routeChanged) PlatformStreamWakeReason.NETWORK_CHANGED else PlatformStreamWakeReason.RECOVERY_SIGNAL)
    }

    fun onNetworkLost() {
        _coreHttpHealth.value = CoreHttpHealth(observedAtMillis = android.os.SystemClock.elapsedRealtime(), failureCode = "NETWORK_LOST")
        GatewayLog.d("OaiConnection", "stage=NETWORK_LOST source=default_route")
        // The transport owns socket health and its bounded failure detector.
        // A network callback cannot publish a fabricated stream failure.
    }

    private fun wakeConnection(reason: PlatformStreamWakeReason) {
        recoveryWakeup.trySend(Unit)
        if (backgroundRecoveryOnly && !appVisible && !sessionRecovery.hasRecoveryLease) return
        if (_loginStatus.value == GatewayLoginStatus.REAUTHENTICATION_REQUIRED) return
        if (_phase.value is ConnectionPhase.Disconnected) restoreSessionIfAvailable()
        else if (!restoringSession && _phase.value is ConnectionPhase.OfflineMirror) reconnectOfflineMirror()
        else if (!restoringSession && _phase.value is ConnectionPhase.Connected) {
            restartNativeReads?.invoke()
            restartPlatformStream?.invoke(reason)
            if (_nativeAgents.value == null || reason == PlatformStreamWakeReason.NETWORK_CHANGED ||
                reason == PlatformStreamWakeReason.USER_RETRY || _connectionStatus.value != EventStreamStatus.LIVE) {
                resumeWorkbench()
            }
        }
    }

    /** 明确故障后的用户重试复用原工作台和恢复所有者。 */
    fun retryConnection() {
        if (_phase.value is ConnectionPhase.Failed &&
            _localSessionState.value is LocalSessionState.Workspace &&
            _loginStatus.value != GatewayLoginStatus.REAUTHENTICATION_REQUIRED) {
            connectionJob?.cancel()
            connectionJob = null
            restoringSession = false
            _phase.value = ConnectionPhase.Disconnected
            _operationNotice.value = null
            restoreSessionIfAvailable()
        } else wakeConnection(PlatformStreamWakeReason.USER_RETRY)
    }

    private fun redirectRetiringWorkbench(
        previous: WorkbenchController,
        ownerScope: CoroutineScope,
        isCurrent: () -> Boolean,
    ) {
        var queuedAction: Job? = null
        var editorThread = previous.state.value.activeThreadId
        var editorVersion = previous.state.value.draftVersion
        var editorForwardingValid = true
        fun forward(action: (WorkbenchController) -> Unit) {
            val preceding = queuedAction
            queuedAction = ownerScope.launch {
                preceding?.join()
                if (!isCurrent()) return@launch
                val replacement = controller.filterNotNull().first { it !== previous }
                if (isActive && _controller.value === replacement && isCurrent()) action(replacement)
            }
        }
        previous.redirectRetiredDraftEditing { text, _ ->
            val expectedThread = editorThread
            forward { replacement ->
                if (replacement.state.value.activeThreadId == expectedThread && editorForwardingValid) {
                    if (replacement.state.value.draftVersion != editorVersion) editorForwardingValid=false
                    else {
                        replacement.editDraftAtVersion(text,editorVersion)
                        editorVersion = replacement.state.value.draftVersion
                    }
                }
            }
        }
        previous.redirectRetiredNavigation(
            openThread = { id -> editorThread = id; forward { replacement ->
                replacement.openThread(id)
                editorVersion = replacement.state.value.draftVersion
                editorForwardingValid = true
            } },
            editDraft = { text ->
                val expectedThread = editorThread
                forward { if (it.state.value.activeThreadId == expectedThread) it.editDraft(text) }
            },
        )
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
            assistantWorkspace?.hasActiveWork?.value == true ||
            _controller.value?.hasActiveWork?.value == true || _nativeAgents.value?.state?.value?.sending == true ||
            hasRecoveredGatewayWork.value)

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
    private var establishingJob: Job? = null
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
                if (cause is CancellationException) throw cause
                val rootCause = generateSequence(cause) { it.cause }.take(8).last()
                GatewayLog.w("OaiConnection", "stage=NEGOTIATION state=failed errorType=${cause.javaClass.simpleName} rootType=${rootCause.javaClass.simpleName}")
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
                else if (negotiated.accountAuthSupported) {
                    val auth = authClientFor(normalized, setOfNotNull(tlsPin))
                    val binding = accountProfiles.binding(profileId)?.takeIf { it.keyEncoding == DEVICE_KEY_ENCODING_VERSION }
                    val retryPassword = password.copyOf()
                    val challenge = try {
                        auth.beginAccountLogin(
                            negotiationId = negotiated.negotiationId,
                            username = username,
                            password = password,
                            displayName = Build.MODEL ?: "Android",
                            devicePublicKeyBase64Url = publicKey,
                            accountId = binding?.accountId,
                            deviceId = binding?.deviceId,
                        )
                    } catch (cause: GatewayAuthException) {
                        if (binding == null || cause.code != "PAIRING_REVOKED") throw cause
                        // Recreate a revoked pairing only after a fresh password
                        // check and the server's current second-factor challenge.
                        auth.beginAccountLogin(negotiated.negotiationId, username, retryPassword,
                            Build.MODEL ?: "Android", publicKey)
                    } finally { retryPassword.fill('\u0000') }
                    completeAccountChallenge(auth, challenge, profileId)
                } else authClientFor(normalized, setOfNotNull(tlsPin)).loginWithPassword(
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
                            saveLastProfile(normalized, username, profileId, session, explicitLogin = true)
                        }.onFailure { _operationNotice.value = "自动登录凭据未能保存，下次启动需要重新登录。" }
                    }
                    establish(
                        endpoint = endpoint,
                        username = username,
                        profileId = profileId,
                        session = session,
                        tlsSpkiSha256 = tlsPin,
                        conversationUi = negotiated.conversationUi,
                        nativeAgentSessions = negotiated.nativeAgentSessions,
                        androidDeviceAuthorization = negotiated.androidDeviceAuthorization,
                        deviceRequests = negotiated.deviceRequests,
                        agentName = negotiated.agentName ?: negotiated.deploymentId,
                        generationCancelCapability =
                            GenerationCancelCapability.fromNegotiation(negotiated),
                    )
                },
                onFailure = { cause ->
                    if (cause is CancellationException) throw cause
                    _phase.value = ConnectionPhase.Failed(errorCode(cause))
                },
            )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                _phase.value = ConnectionPhase.Failed(errorCode(cause))
            } finally {
                _accountLoginStep.value = AccountLoginStep.None
                accountLoginInput.tryReceive().getOrNull()?.proof?.fill('\u0000')
                password.fill('\u0000')
            }
        }
    }

    private suspend fun completeAccountChallenge(
        auth: GatewayAuthClient,
        challenge: AccountLoginChallenge,
        profileId: String,
    ): SessionCredentials {
        suspend fun inputBeforeExpiry(): AccountLoginInput {
            val now = java.time.Instant.now()
            check(now.isBefore(challenge.expiresAt)) { "ACCOUNT_CHALLENGE_EXPIRED" }
            val remaining = java.time.Duration.between(now, challenge.expiresAt).toMillis().coerceAtLeast(1L)
            return withTimeoutOrNull(remaining) { accountLoginInput.receive() }
                ?: error("ACCOUNT_CHALLENGE_EXPIRED")
        }
        fun retryableProofFailure(cause: Throwable): Boolean =
            cause is GatewayAuthException && cause.code in setOf("MFA_INVALID", "MFA_INVALID_CODE", "AUTHENTICATION_FAILED")
        val sign: (ByteArray) -> ByteArray = { deviceKeys.sign(profileId, it) }
        if (challenge.enrollmentRequired) {
            check(java.time.Instant.now().isBefore(challenge.expiresAt)) { "ACCOUNT_CHALLENGE_EXPIRED" }
            val enrollment = auth.beginMfaEnrollment(challenge)
            var error: String? = null
            while (true) {
                _accountLoginStep.value = AccountLoginStep.Enrollment(enrollment.provisioningUri, enrollment.secret, error)
                val input = inputBeforeExpiry()
                val proof = input.proof ?: continue
                _accountLoginStep.value = AccountLoginStep.None
                val codes = try {
                    auth.confirmMfaEnrollment(challenge, enrollment.enrollmentId, proof)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (cause: Exception) {
                    if (!retryableProofFailure(cause)) throw cause
                    error = "验证码未通过，请重试。"
                    continue
                } finally {
                    proof.fill('\u0000')
                }
                _accountLoginStep.value = AccountLoginStep.RecoveryCodes(codes)
                inputBeforeExpiry().proof?.fill('\u0000')
                _accountLoginStep.value = AccountLoginStep.None
                return auth.completeAccountLogin(challenge, sign)
            }
        }
        if (challenge.methods.isEmpty()) return auth.completeAccountLogin(challenge, sign)
        var error: String? = null
        while (true) {
            _accountLoginStep.value = AccountLoginStep.Verification(error)
            val input = inputBeforeExpiry()
            val proof = input.proof ?: continue
            try {
                _accountLoginStep.value = AccountLoginStep.None
                return auth.completeAccountLogin(challenge, sign,
                    otp = if (input.recovery) null else proof,
                    recoveryCode = if (input.recovery) proof else null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                if (!retryableProofFailure(cause)) throw cause
                error = "验证码或恢复码未通过，请重试。"
            } finally {
                proof.fill('\u0000')
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
        if (deferUntilSessionEstablished { logout(revokeRefresh) }) return
        if (connectionJob?.isActive == true && !restoringSession) return
        val currentConnected = _phase.value as? ConnectionPhase.Connected
        val currentOffline = _phase.value as? ConnectionPhase.OfflineMirror
        val localAccount = (_localSessionState.value as? LocalSessionState.Workspace)?.account
        val gatewayUrl = currentConnected?.gatewayUrl ?: currentOffline?.gatewayUrl ?: localAccount?.gatewayBaseUrl ?: return
        val username = currentConnected?.username ?: currentOffline?.username ?: localAccount?.username ?: return
        val profileId = profileIdFor(gatewayUrl, username)

        // 1. 本地优先：立即写入持久退出屏障，防止自动恢复
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.edit().putBoolean("signed_out_$profileId", true).commit()) {
            _operationNotice.value = "本机退出状态未能保存，请检查设备存储后重试。"
            return
        }

        fenceDeviceProfile(profileId)
        // 2. 本地展示状态立即切为 SignedOut
        deviceAuthorizationAgreement?.set(false)
        val signedOut = LocalSessionState.SignedOut(lastEndpoint = gatewayUrl, lastUsername = username)
        _localSessionState.value = signedOut
        _loginStatus.value = GatewayLoginStatus.SIGNED_OUT
        executionDriver?.cancelAll()
        com.openandroidintelligence.control.ScreenHostRegistry.current?.disconnect()
        val restoring = connectionJob?.takeIf { restoringSession }
        cancelRestoration()

        // 3. 异步执行凭据清理、远端单次通知与 teardown，不阻塞本地退出完成
        _operationNotice.value = null
        lateinit var owned: Job
        owned = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                // 旧恢复可能正在持久化已轮换凭据，等待它退出后再清理当前账号。
                restoring?.join()
                _localSessionState.value = signedOut
                try {
                    flushWorkbenchBeforeLeaving()
                    _controller.value?.closeAndAwait()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _operationNotice.value = "本机已退出，但本地会话保存失败，请检查设备存储。"
                }

                var confirmed = false
                if (currentConnected != null) {
                    try {
                        notifyGatewayLogout(currentConnected, revokeRefresh)
                        confirmed = true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        _operationNotice.value = "本机已退出，登出未获 Gateway 确认，请检查连接后重试。"
                    }
                } else {
                    _operationNotice.value = "本机已退出，离线登出未获 Gateway 确认；本机凭据已锁定保留，重新登录后可继续处理。"
                }

                sessionRenewal?.close()
                sessionRenewal = null

                sessionOperationMutex(profileId).withLock {
                    val cleanup = buildList<() -> Unit> {
                        // 离线没有发出远端请求，保留凭据和未确认轮换记录；退出屏障阻止使用它们。
                        if (currentConnected != null) {
                            add { credentialStore.clearRefresh(profileId) }
                            add { refreshJournal.clear(profileId); pendingRotations.remove(profileId) }
                        }
                        add { clearLastProfile() }
                    }
                    for (action in cleanup) {
                        try {
                            action()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            _operationNotice.value = if (confirmed) {
                                "Gateway 已登出，但本机凭据清理失败，请检查设备存储。"
                            } else {
                                "本机已退出，登出未获 Gateway 确认，且本机凭据清理失败，请检查设备存储。"
                            }
                        }
                    }
                    teardown()
                }
            } finally {
                if (connectionJob === owned) connectionJob = null
            }
        }
        connectionJob = owned
        owned.start()
    }

    private suspend fun notifyGatewayLogout(current: ConnectionPhase.Connected, revokeRefresh: Boolean) {
        val renewal = sessionRenewal
        val rejected = renewal?.current
        val accountId = rejected?.accountId ?: checkNotNull(lastAccountId) { "LOGOUT_SESSION_UNAVAILABLE" }
        val deviceId = rejected?.deviceId ?: checkNotNull(lastDeviceId) { "LOGOUT_SESSION_UNAVAILABLE" }
        val auth = authClientFor(current.gatewayUrl, setOfNotNull(current.tlsSpkiSha256))
        suspend fun notify(accessToken: String, sessionId: String) {
            auth.logout(accessToken, accountId, deviceId, sessionId, revokeRefresh)
        }
        try {
            notify(
                rejected?.accessToken ?: checkNotNull(accessTokenHolder) { "LOGOUT_SESSION_UNAVAILABLE" },
                rejected?.sessionId ?: checkNotNull(lastSessionId) { "LOGOUT_SESSION_UNAVAILABLE" },
            )
        } catch (cause: GatewayAuthException) {
            if (cause.httpStatus != 401 || renewal == null || rejected == null) throw cause
            val rotated = renewal.refresh(rejected)
            notify(rotated.accessToken, rotated.sessionId)
        }
    }

    /** Leave the active workbench without revoking another saved account's credentials. */
    fun reconnectOfflineMirror() {
        if (restoringSession) { recoveryWakeup.trySend(Unit); return }
        if (connectionJob?.isActive == true || _phase.value !is ConnectionPhase.OfflineMirror) return
        // 保留可读工作台，新的连接所有者准备完成后再按原持久化屏障交接。
        _phase.value = ConnectionPhase.Disconnected
        restoreSessionIfAvailable()
    }

    fun chooseAnotherAccount() {
        cancelAccountLogin()
        if (deferUntilSessionEstablished(::chooseAnotherAccount)) return
        if (connectionJob?.isActive == true && !restoringSession) {
            _operationNotice.value = "当前账号操作尚未结束，请稍后再切换。"
            return
        }
        cancelRestoration()
        val previousProfile = (_localSessionState.value as? LocalSessionState.Workspace)?.account?.localProfileId
        leaveWorkbench(prepare = { clearLastProfile() }) {
            _localSessionState.value = LocalSessionState.AccountPicker(accountProfiles.list(), previousProfile)
        }
    }

    fun selectSavedAccount(profileId: String) {
        cancelAccountLogin()
        if (deferUntilSessionEstablished { selectSavedAccount(profileId) }) return
        if (connectionJob?.isActive == true && !restoringSession) {
            _operationNotice.value = "当前账号操作尚未结束，请稍后再选择账号。"
            return
        }
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
    private fun leaveWorkbench(prepare: () -> Unit = {}, action: () -> Unit) {
        val profileId = (_localSessionState.value as? LocalSessionState.Workspace)?.account?.localProfileId
            ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_PROFILE, null)
        lateinit var owned: Job
        owned = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                if (!flushWorkbenchBeforeLeaving()) return@launch
                suspend fun leave() {
                    // Fail before retirement, and keep a late rotation from restoring
                    // the active profile pointer after the user's account switch.
                    prepare()
                    _controller.value?.closeAndAwait()
                    teardown()
                    connectionJob = null
                    action()
                }
                if (profileId != null) sessionOperationMutex(profileId).withLock { leave() } else leave()
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

    private suspend fun clearNativeLocalPartitions(localScope: ConversationScope) {
        val partitions = com.openandroidintelligence.mobile.conversations.NativeLocalPartitions(context,localScope,localDocumentKeyProvider)
        accountProfiles.find(localScope.profileId)?.selectedAgentId?.let(partitions::register)
        partitions.agents().forEach { agentId ->
            val child = partitions.partitionScope(agentId)
            val localMedia = com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache(
                context,localScope,keyProvider=localDocumentKeyProvider,nativeAgentId=agentId)
            com.openandroidintelligence.mobile.conversations.AndroidAttachmentDownloads(
                context,localMedia,child,keyProvider=localDocumentKeyProvider).wipePrivateDownloadJournal()
            attachmentStagingStore(child.gatewayId,child.accountId,child.installId).cleanup()
            com.openandroidintelligence.mobile.conversations.EncryptedAttachmentRecovery(context,child,localDocumentKeyProvider).wipe()
            val localMirror = com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror(context,child,localDocumentKeyProvider)
            try { localMirror.wipe() } finally { localMirror.closeNativeStore() }
            localMedia.clearMedia()
        }
        partitions.wipe()
    }

    fun removeLocalAccount(profileId: String) {
        if (connectionJob?.isActive == true || _phase.value is ConnectionPhase.Connected) {
            _operationNotice.value = "请先进入账号管理，并等待当前账号操作完成后再移除。"
            return
        }
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
                    sessionOperationMutex(profileId).withLock {
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
                        clearNativeLocalPartitions(localScope)
                        val localMedia = com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache(context,localScope,keyProvider=localDocumentKeyProvider)
                        com.openandroidintelligence.mobile.conversations.AndroidAttachmentDownloads(context,localMedia,localScope,keyProvider=localDocumentKeyProvider).wipePrivateDownloadJournal()
                        pairingGrants.clearFor(PairingGrantBinding(profile.gatewayBaseUrl, accountId, installationId()))
                        attachmentStagingStore(profile.gatewayBaseUrl, accountId, installationId()).cleanup()
                        com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror(context,ConversationScope(profileId,profile.gatewayBaseUrl,accountId,installationId()),localDocumentKeyProvider).wipe()
                        com.openandroidintelligence.mobile.conversations.EncryptedAttachmentRecovery(context,ConversationScope(profileId,profile.gatewayBaseUrl,accountId,installationId()),localDocumentKeyProvider).wipe()
                        com.openandroidintelligence.mobile.conversations.EncryptedNativeConversationOrganizationStore(context,localScope,localDocumentKeyProvider).wipe()
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

    /** Connected may publish just before the initializer releases its operation gate. */
    private fun deferUntilSessionEstablished(action: () -> Unit): Boolean {
        val initializing = connectionJob ?: return false
        if (!initializing.isActive || initializing !== establishingJob ||
            _phase.value !is ConnectionPhase.Connected) return false
        val expectedHttp = activeHttpClient ?: return false
        val expectedSession = sessionJob ?: return false
        scope.launch {
            initializing.join()
            if (!initializing.isCancelled && (connectionJob === initializing || connectionJob == null) &&
                activeHttpClient === expectedHttp && sessionJob === expectedSession && expectedSession.isActive &&
                _phase.value is ConnectionPhase.Connected) action()
        }
        return true
    }

    fun unpair() {
        if (deferUntilSessionEstablished(::unpair)) return
        if (connectionJob?.isActive == true) {
            if (_unpairOperation.value !is LocalOperationState.Running) {
                _unpairOperation.value = LocalOperationState.Failed("当前账号操作尚未结束，请稍后重试。")
            }
            return
        }
        val current = _phase.value as? ConnectionPhase.Connected
        val http = activeHttpClient
        val deviceId = lastDeviceId
        val accountId = lastAccountId
        if (current == null || http == null || deviceId == null || accountId == null) {
            _unpairOperation.value = LocalOperationState.Failed("需要连接当前 Core 后才能解除设备配对。")
            return
        }
        val profileId = profileIdFor(current.gatewayUrl, current.username)
        val staging = activeAttachmentStaging
        val binding = PairingGrantBinding(current.gatewayUrl, accountId, installationId())
        _unpairOperation.value = LocalOperationState.Running
        connectionJob = scope.launch {
            var remoteCompleted = false
            var partialFailure = false
            try {
                if (!flushWorkbenchBeforeLeaving()) {
                    _unpairOperation.value = LocalOperationState.Failed("本地会话未能保存，设备配对和凭据已保留。")
                    return@launch
                }
                try {
                    PairingRevocationClient(http).revoke(deviceId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (cause: Exception) {
                    val message = "解除配对未获 Core 确认，本机凭据已保留，请检查连接后重试。"
                    _operationNotice.value = message
                    _unpairOperation.value = LocalOperationState.Failed(message)
                    return@launch
                }
                remoteCompleted = true
                sessionRenewal?.close()
                sessionRenewal = null
                sessionOperationMutex(profileId).withLock {
                    try { _controller.value?.closeAndAwait() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        partialFailure = true
                        _operationNotice.value = "Core 已解除配对，本机任务收尾失败，请重试本机账号清理。"
                    }
                    teardown()
                    val localScope = ConversationScope(profileId,current.gatewayUrl,accountId,installationId())
                    try {
                        val localMedia = com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache(context,localScope,keyProvider=localDocumentKeyProvider)
                        com.openandroidintelligence.mobile.conversations.AndroidAttachmentDownloads(context,localMedia,localScope,keyProvider=localDocumentKeyProvider).wipePrivateDownloadJournal()
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) {
                        partialFailure = true
                        _operationNotice.value = "Core 已解除配对，未完成下载的恢复记录清理失败，请重试本机账号清理。"
                    }
                    val cleanup = listOf<suspend () -> Unit>(
                        { clearNativeLocalPartitions(localScope) },
                        { pairingGrants.clearFor(binding) },
                        { credentialStore.clearRefresh(profileId) },
                        { refreshJournal.clear(profileId); pendingRotations.remove(profileId) },
                        { credentialStore.clearDeviceKey(profileId) },
                        { deviceKeys.delete(profileId) },
                        { staging?.cleanup(); Unit },
                        { com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror(context,ConversationScope(profileId,current.gatewayUrl,accountId,installationId()),localDocumentKeyProvider).wipe() },
                        { com.openandroidintelligence.mobile.conversations.EncryptedAttachmentRecovery(context,ConversationScope(profileId,current.gatewayUrl,accountId,installationId()),localDocumentKeyProvider).wipe() },
                        { com.openandroidintelligence.mobile.conversations.EncryptedNativeConversationOrganizationStore(context,localScope,localDocumentKeyProvider).wipe() },
                        { com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache(context,ConversationScope(profileId,current.gatewayUrl,accountId,installationId()),keyProvider=localDocumentKeyProvider).clearMedia() },
                        { pluginHost?.eraseAccount(accountId,binding.pairingId); Unit },
                        { replyPublisher.eraseAccount(profileId, accountId) },
                        { nativeConversationNotifications.eraseAccount(profileId, accountId) },
                        { AndroidEventCursorStore(context, profileId).clear(accountId) },
                        { AndroidEventCursorStore(context, platformCursorProfile(profileId)).clear(accountId) },
                        { clearLastProfile() },
                    ).map { action -> runCatching { action() } }
                    if (cleanup.any { it.isFailure }) {
                        partialFailure = true
                        _operationNotice.value = "Core 已解除配对，但本机数据清理失败，请重试本机账号清理。"
                    }
                }
                _localSessionState.value = LocalSessionState.SignedOut(lastEndpoint = current.gatewayUrl, lastUsername = current.username)
                _unpairOperation.value = if (partialFailure) {
                    LocalOperationState.Failed(_operationNotice.value ?: "配对已解除，但部分本机资料尚未清理。", remoteCompleted = true)
                } else LocalOperationState.Completed
            } catch (cancelled: CancellationException) {
                _unpairOperation.value = LocalOperationState.Failed(
                    if (remoteCompleted) "配对已解除，本机清理被中断，请检查本机账号资料。" else "解除配对被中断，请重新连接后确认配对状态。",
                    remoteCompleted,
                )
                throw cancelled
            } catch (_: Exception) {
                _unpairOperation.value = LocalOperationState.Failed(
                    if (remoteCompleted) "配对已解除，本机清理未完成，请检查存储后重试本机账号清理。" else "解除配对未完成，本机凭据已保留，请检查连接后重试。",
                    remoteCompleted,
                )
            }
        }
    }

    /** Manual and automatic renewal share the same rotation and keep in-flight work alive. */
    fun refreshSession() {
        if (deferUntilSessionEstablished(::refreshSession)) return
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
                    restartPlatformStream?.invoke(PlatformStreamWakeReason.CREDENTIAL_CHANGED)
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
        preservePresentation: Boolean = false,
    ): Pair<SessionCredentials, NegotiationResult> {
        checkOwner()
        pendingRotations[profileId]?.let { pending ->
            persistRotation(endpoint, username, profileId, pending.first, preservePresentation)
            pendingRotations.remove(profileId)
            return pending
        }
        val negotiated = authClientFor(endpoint.baseUrl, pins, beforeConnect = checkOwner).negotiate("neg_" + newToken())
        checkOwner()
        val tlsPin = identityTrust.verifyNegotiation(endpoint, username, negotiated)
        val refresh = credentialStore.loadRefresh(profileId)?.takeIf { it.isNotEmpty() }
            ?: error("NO_REFRESH_CREDENTIAL")
        try {
            val operation = refreshJournal.prepare(profileId, refresh, negotiated.refreshReplaySupported)
            val session = try {
                authClientFor(endpoint.baseUrl, setOfNotNull(tlsPin), beforeConnect = {
                    try { checkOwner() } catch (cancelled: CancellationException) {
                        // A new legacy refresh rejected before open has never reached the server.
                        // Replayable journals may belong to an earlier unknown outcome; retain them.
                        if (!operation.replayable) refreshJournal.clear(profileId)
                        throw cancelled
                    }
                }).refresh(
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
            persistRotation(endpoint, username, profileId, session, preservePresentation)
            pendingRotations.remove(profileId)
            return pending
        } finally { refresh.fill(0) }
    }

    private suspend fun persistRotation(endpoint: GatewayEndpoint, username: String, profileId: String, session: SessionCredentials,
        preservePresentation: Boolean = false) {
        // Caller cancellation after the response must not abandon an already rotated credential.
        withContext(NonCancellable + Dispatchers.IO) {
            credentialStore.saveRefresh(profileId, session.refreshCredential)
            if (preservePresentation) {
                synchronized(accountProfiles) {
                    val existing = accountProfiles.find(profileId) ?: error("PUSH_ACCOUNT_REMOVED")
                    accountProfiles.save(existing, AndroidAccountProfileStore.Binding(session.accountId, session.deviceId,
                        session.sessionId, DEVICE_KEY_ENCODING_VERSION))
                    _savedProfiles.value = accountProfiles.list()
                }
            } else saveLastProfile(endpoint.baseUrl, username, profileId, session)
            refreshJournal.clear(profileId)
        }
    }

    private suspend fun requireReauthentication(profileId: String, code: String) {
        // Fence first, then let an already committed rotation finish its durable
        // write before clearing it. Revocation can never be undone by that write.
        _loginStatus.value = GatewayLoginStatus.REAUTHENTICATION_REQUIRED
        sessionRenewal?.close()
        sessionRenewal = null
        sessionOperationMutex(profileId).withLock { invalidateCredentialsLocked(profileId, code) }
    }

    private fun invalidateCredentialsLocked(profileId: String, code: String) {
        fenceDeviceProfile(profileId)
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
        _controller.value?.updateDebouncePolicy(preferences.read(currentDebounceGateway()))
    }
    fun useGlobalDebounceSettings(gateway:String) {
        val preferences=com.openandroidintelligence.mobile.conversations.DebouncePreferences(context)
        preferences.useGlobal(gateway)
        _controller.value?.updateDebouncePolicy(preferences.read(currentDebounceGateway()))
    }

    /**
     * 设置导入/回滚之后重新装载合并策略。
     *
     * 只刷新运行时持有的批处理策略，不写任何偏好：写入早已由导入事务完成，
     * 这里再做一次覆盖就会把「回滚后应恢复的值」重新改掉。
     */
    /**
     * 本机已知的最近一个 Core 地址（设置备份的回落来源）。
     *
     * 依次看：当前连接/离线镜像 → 本地会话里的账号 → 运行时持久化的最近地址。
     * 只读、不触发连接；全部缺失时返回 null，让调用方如实省略该节。
     */
    fun lastGatewayUrl(): String? {
        val phase = _phase.value
        (phase as? ConnectionPhase.Connected)?.gatewayUrl?.takeIf { it.isNotBlank() }?.let { return it }
        (phase as? ConnectionPhase.OfflineMirror)?.gatewayUrl?.takeIf { it.isNotBlank() }?.let { return it }
        (_localSessionState.value as? LocalSessionState.Workspace)?.account?.gatewayBaseUrl
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_GATEWAY, null)?.takeIf { it.isNotBlank() }
    }

    fun reloadDebounceSettings() {
        val preferences=com.openandroidintelligence.mobile.conversations.DebouncePreferences(context)
        _controller.value?.updateDebouncePolicy(preferences.read(currentDebounceGateway()))
    }

    private fun currentDebounceGateway(): String? = when (val phase = _phase.value) {
        is ConnectionPhase.Connected -> phase.gatewayUrl
        is ConnectionPhase.OfflineMirror -> phase.gatewayUrl
        else -> (_localSessionState.value as? LocalSessionState.Workspace)?.account?.gatewayBaseUrl
    }

    private suspend fun showLocalWorkspace(endpoint:GatewayEndpoint,username:String,profileId:String,accountId:String,
        cause:Throwable? = null, checkOwner: () -> Unit = {},
        initializing: Boolean = false, restoreLastThread: Boolean = true, sessionOwner: Job? = null) {
        if (cause != null && !isTransientConnectionFailure(cause) || _phase.value is ConnectionPhase.OfflineMirror) return
        checkOwner()
        if (_controller.value == null) {
            val selectedAgentId = accountProfiles.find(profileId)?.selectedAgentId
            val nativeMirror = selectedAgentId?.let { agentId ->
                com.openandroidintelligence.mobile.conversations.NativeConversationMirror(context,
                    ConversationScope(profileId,endpoint.baseUrl,accountId,installationId()), { agentId }, localDocumentKeyProvider)
            }
            val mirrorScope=ConversationScope(profileId,endpoint.baseUrl,accountId,installationId())
            val mirror=kotlinx.coroutines.withContext(Dispatchers.IO) {
                nativeMirror?.offlineMirror() ?: com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror(context,mirrorScope,localDocumentKeyProvider)
                    .also { it.finishBaselineRecovery() }
            }
            val media = kotlinx.coroutines.withContext(Dispatchers.IO) {
                com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache(context,mirrorScope,keyProvider=localDocumentKeyProvider,
                    nativeAgentId=selectedAgentId)
            }
            val localOwnerScope = selectedAgentId?.let {
                com.openandroidintelligence.mobile.conversations.NativeLocalPartitions.partitionScope(mirrorScope, it)
            } ?: mirrorScope
            val downloads = com.openandroidintelligence.mobile.conversations.AndroidAttachmentDownloads(context,media,localOwnerScope,keyProvider=localDocumentKeyProvider)
            val mediaReady = if (nativeMirror != null) prepareNativeMedia(nativeMirror,media)
                else prepareMediaTransitions(mirror,media)
            val downloadNotice = if (mediaReady) recoverDownloads(downloads) else MEDIA_RECOVERY_NOTICE
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            checkOwner()
            val owned = sessionOwner ?: SupervisorJob(scope.coroutineContext[Job]).also { sessionJob = it }
            _controller.value=kotlinx.coroutines.withContext(Dispatchers.IO) { WorkbenchController(CoroutineScope(owned+Dispatchers.Main.immediate),
                com.openandroidintelligence.mobile.conversations.OfflineMirrorRepository(mirror),
                object:com.openandroidintelligence.conversation.ports.AgentCommandCatalogRepository {
                    override suspend fun get(gatewayId:String,languageCode:String):com.openandroidintelligence.conversation.ports.AgentCommandCatalog=throw java.io.IOException("OFFLINE_MIRROR")
                },{ localOwnerScope },persistence=nativeMirror ?: mirror,media=media,downloads=downloads,allowSending=false,
                authoritativeMessageIds=nativeMirror != null, organizationStoredLocally=nativeMirror != null,
                restoreLastThread=restoreLastThread) }
            workspacePresented = true
            if (downloadNotice != null) _operationNotice.value = downloadNotice
        }
        checkOwner()
        _phase.value=if (initializing) {
            if (restoreLastThread) ConnectionPhase.Reconnecting else ConnectionPhase.Initializing
        } else ConnectionPhase.OfflineMirror(endpoint.baseUrl,username)
        if (!initializing && _operationNotice.value == null) _operationNotice.value=OFFLINE_MIRROR_NOTICE
        val mirrorAccount = accountProfiles.find(profileId) ?: AccountProfile(profileId, endpoint.baseUrl, username, "")
        _localSessionState.value = LocalSessionState.Workspace(mirrorAccount, _phase.value)
    }

    /** One retrying cold-start owner; network failures never erase the saved login. */
    @Synchronized
    fun restoreSessionIfAvailable() {
        if (backgroundRecoveryOnly && !appVisible && !sessionRecovery.hasRecoveryLease) return
        if (connectionJob?.isActive == true || _phase.value !is ConnectionPhase.Disconnected ||
            _loginStatus.value == GatewayLoginStatus.REAUTHENTICATION_REQUIRED) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastUrl = prefs.getString(KEY_LAST_GATEWAY, null)
        val lastUser = prefs.getString(KEY_LAST_USER, null)
        val profileId = prefs.getString(KEY_LAST_PROFILE, null)
        val accountId = prefs.getString(KEY_LAST_ACCOUNT, null)
        val deviceId = prefs.getString(KEY_LAST_DEVICE, null)
        if (lastUrl == null || lastUser == null || profileId == null || accountId == null || deviceId == null) {
            _localSessionState.value = LocalSessionState.SignedOut(lastEndpoint = lastUrl, lastUsername = lastUser)
            return
        }
        // 检查持久退出屏障：不读取或使用已退出账户的刷新凭据。
        if (prefs.getBoolean("signed_out_$profileId", false)) {
            _localSessionState.value = LocalSessionState.SignedOut(lastEndpoint = lastUrl, lastUsername = lastUser, reason = "EXPLICITLY_SIGNED_OUT")
            return
        }
        if (prefs.getInt(KEY_DEVICE_KEY_ENCODING, 0) != DEVICE_KEY_ENCODING_VERSION) {
            _phase.value = ConnectionPhase.Failed("DEVICE_KEY_REGISTRATION_UPGRADE_REQUIRED")
            _loginStatus.value = GatewayLoginStatus.REAUTHENTICATION_REQUIRED
            _localSessionState.value = LocalSessionState.Locked(accountProfiles.find(profileId), "DEVICE_KEY_REGISTRATION_UPGRADE_REQUIRED")
            return
        }
        val endpoint = GatewayEndpoint.parse(lastUrl) ?: run {
            _localSessionState.value = LocalSessionState.SignedOut(lastEndpoint = lastUrl, lastUsername = lastUser)
            return
        }
        val hasCredential = runCatching { credentialStore.loadRefresh(profileId)?.let { it.isNotEmpty().also { _ -> it.fill(0) } } }.getOrDefault(false)
        if (hasCredential != true) {
            _localSessionState.value = LocalSessionState.SignedOut(lastEndpoint = lastUrl, lastUsername = lastUser)
            return
        }

        // 本地账号决定首屏，网络结果只决定连接提示。进程首次呈现不恢复会话导航。
        val startOnBlank = !workspacePresented
        val savedAccount = accountProfiles.find(profileId) ?: AccountProfile(profileId, endpoint.baseUrl, lastUser, "")
        _localSessionState.value = LocalSessionState.Workspace(savedAccount,
            if (startOnBlank) ConnectionPhase.Initializing else ConnectionPhase.Reconnecting)
        val backgroundOwner = backgroundRecoveryOnly
        restoringSession = true
        _loginStatus.value = GatewayLoginStatus.RESTORING
        _credentialStatus.value = GatewayCredentialStatus.AVAILABLE
        _phase.value = if (startOnBlank) ConnectionPhase.Initializing else ConnectionPhase.Reconnecting
        _connectionStatus.value = if (startOnBlank) EventStreamStatus.CONNECTING else EventStreamStatus.RECONNECTING
        lateinit var ownedRestore: Job
        fun checkOwner() {
            ownedRestore.ensureActive()
            if (connectionJob !== ownedRestore) throw CancellationException("RESTORE_OWNER_CHANGED")
            synchronized(backgroundOwnershipLock) {
                if (backgroundOwner && backgroundRecoveryOnly && !appVisible && !sessionRecovery.hasRecoveryLease) {
                    throw CancellationException("BACKGROUND_RECOVERY_STOPPED")
                }
            }
        }
        ownedRestore = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var backoff = 1_000L
            val startedAt = android.os.SystemClock.elapsedRealtime()
            var lastTransientFailure: Exception? = null
            fun graceRemaining() = (initialConnectionGraceMillis -
                (android.os.SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L)
            suspend fun showConfirmedFailure(cause: Exception) {
                checkOwner()
                showLocalWorkspace(endpoint, lastUser, profileId, accountId, cause, ::checkOwner)
            }
            try {
                // 本地数据可读并不等于离线；首次握手期间只呈现新对话。
                if (_controller.value == null) {
                    try { showLocalWorkspace(endpoint, lastUser, profileId, accountId, checkOwner = ::checkOwner,
                        initializing = true, restoreLastThread = !startOnBlank) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { _operationNotice.value = "本地镜像读取失败，正在尝试连接。" }
                }
                while (isActive) {
                    if (graceRemaining() == 0L) lastTransientFailure?.let { showConfirmedFailure(it) }
                    try {
                        checkOwner()
                        val pins = identityTrust.pinsBeforeConnect(endpoint, lastUser, restoring = true)
                        val (session, negotiated) = sessionOperationMutex(profileId).withLock {
                            _credentialStatus.value = GatewayCredentialStatus.REFRESHING
                            rotateStoredSession(endpoint, lastUser, profileId, accountId, deviceId, pins, ::checkOwner)
                        }
                        checkOwner()
                        val tlsPin = identityTrust.verifyNegotiation(endpoint, lastUser, negotiated)
                        // 在同一页面交接本地与在线控制器，不发布 controller=null 的中间帧。
                        val restoreSelection = _controller.value?.state?.value?.activeThreadId != null
                        if (_controller.value != null && activeHttpClient == null) {
                            if (!flushWorkbenchBeforeLeaving()) return@launch
                            if (!negotiated.nativeAgentSessions) {
                                _controller.value?.let { previous ->
                                    redirectRetiringWorkbench(previous, scope) { connectionJob === ownedRestore }
                                }
                                _controller.value?.closeAndAwait()
                                sessionJob?.cancel()
                            }
                        }
                        establish(endpoint, lastUser, profileId, session, tlsPin,
                            negotiated.conversationUi, negotiated.deviceRequests, GenerationCancelCapability.fromNegotiation(negotiated), ::checkOwner,
                            agentName = negotiated.agentName ?: negotiated.deploymentId,
                            nativeAgentSessions = negotiated.nativeAgentSessions,
                            androidDeviceAuthorization = negotiated.androidDeviceAuthorization,
                            restoreLastThread = restoreSelection)
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
                            _operationNotice.value = com.openandroidintelligence.conversation.components.readableFailure(errorCode(cause))
                            sessionKeepAlive.stop()
                            return@launch
                        }
                        lastTransientFailure = cause
                        val remaining = graceRemaining()
                        if (remaining == 0L) showConfirmedFailure(cause)
                        // 在宽限边界再次判定已发生的失败，无需额外计时状态或订阅。
                        withTimeoutOrNull(if (remaining > 0) minOf(backoff, remaining) else backoff) { recoveryWakeup.receive() }
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
        GatewayConnectionSecurity.isSecurityFailure(cause) -> false
        cause is java.io.IOException -> true
        cause is GatewayAuthException -> cause.retryable == true || cause.httpStatus in setOf(408, 429, 500, 502, 503, 504)
        cause is NegotiationHttpException -> cause.retryable
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
        agentName: String? = null,
        nativeAgentSessions: Boolean = false,
        androidDeviceAuthorization: Boolean = false,
        restoreLastThread: Boolean = true,
    ): Unit = initializePostLogin(InitializationStage.SESSION_SETUP) {
        establishingJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        checkOwner()
        val retainedLocalController = _controller.value?.takeIf { nativeAgentSessions && activeHttpClient == null }
        val retainedLocalOwner = sessionJob.takeIf { retainedLocalController != null }
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
        deviceAuthorizationAgreement?.set(false)
        executionDriver?.cancelAll()
        selectedScreenBinding?.let { com.openandroidintelligence.control.ScreenHostRegistry.current?.disconnectConnection(it) }
        selectedScreenBinding = null
        val authorizationAgreement = java.util.concurrent.atomic.AtomicBoolean(androidDeviceAuthorization)
        deviceAuthorizationAgreement = authorizationAgreement
        lateinit var renewal: GatewaySessionRenewal
        renewal = GatewaySessionRenewal(profile, scope) { rejected ->
            sessionOperationMutex(profileId).withLock {
                check(sessionRenewal === renewal) { "SESSION_RENEWAL_CLOSED" }
                _isRefreshingSession.value = true
                _credentialStatus.value = GatewayCredentialStatus.REFRESHING
                try {
                    val (rotated, negotiated) = rotateStoredSession(endpoint, username, profileId,
                        rejected.accountId, rejected.deviceId, rejected.pinnedSpkiSha256)
                    check(sessionRenewal === renewal) { "SESSION_RENEWAL_CLOSED" }
                    if (!negotiated.androidDeviceAuthorization) {
                        authorizationAgreement.set(false)
                        executionDriver?.cancelAll()
                        com.openandroidintelligence.control.ScreenHostRegistry.current?.disconnect()
                        deviceCapabilityState.value = DeviceCapabilitySyncState("AUTHORIZATION_MODEL_UNSUPPORTED")
                    }
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
        // 账号/Agent 变化时立即退役旧助手工作区，不让旧会话借到新身份的 HTTP 客户端。
        closeAssistantWorkspaceForAccountChange(profileId, session.accountId)
        val binding = PairingGrantBinding(
                gatewayId = endpoint.baseUrl,
                accountId = session.accountId,
                installationId = installationId(),
            )
        initializePostLogin(InitializationStage.PAIRING_STATE) {
            pairingGrants.bind(binding) { authorizationAgreement.get() && sessionRenewal === renewal }
        }
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
        _coreHttpHealth.value = CoreHttpHealth()
        val transport = GatewayTransport(
            profile,
            onHttpResponse = { status ->
                if (sessionRenewal === renewal) _coreHttpHealth.value = CoreHttpHealth(
                    reachability = CoreHttpReachability.REACHABLE,
                    httpStatus = status,
                    observedAtMillis = android.os.SystemClock.elapsedRealtime(),
                )
            },
            onHttpFailure = { failure ->
                if (sessionRenewal === renewal && failure !is CancellationException) {
                    val security = GatewayConnectionSecurity.isSecurityFailure(failure)
                    _coreHttpHealth.value = CoreHttpHealth(
                        reachability = if (security) CoreHttpReachability.SECURITY_BLOCKED else CoreHttpReachability.UNREACHABLE,
                        observedAtMillis = android.os.SystemClock.elapsedRealtime(),
                        failureCode = if (security) "TRANSPORT_SECURITY_REJECTED" else failure.javaClass.simpleName,
                    )
                }
            },
        )
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
        val decisionAudit = auditStore?.let { store ->
            ApprovalAuditBridge(
                audit = store,
                accountId = { session.accountId },
                pairingId = { pairingGrants.state.value?.pairingId.orEmpty() },
            )
        }
        val approvalClient = if ("agent-approval-cards-v1" in conversationUi) {
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
            managementOperations = mirror,
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

        if (sessionJob !== retainedLocalOwner) sessionJob?.cancel()
        val ownedJob = SupervisorJob(scope.coroutineContext[Job])
        ownedJob.invokeOnCompletion {
            retainedLocalController?.close()
            retainedLocalOwner?.cancel()
        }
        sessionJob = ownedJob
        val sessionScope = CoroutineScope(ownedJob + Dispatchers.Main.immediate)
        _nativeAgents.value?.close()
        // 控制器在工作台装配阶段创建（见下方 nativeAgentSessions 分支），
        // 以便同时恢复用户上次选择的原生 Agent。旧控制器在此统一释放。
        if (!nativeAgentSessions) _nativeAgents.value = null
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
        if (pluginHost != null && deviceRequests != null && androidDeviceAuthorization) {
            initializePostLogin(InitializationStage.DEVICE_EXECUTION) {
                lateinit var publisher: com.openandroidintelligence.mobile.plugins.CapabilityPublisher
                publisher = com.openandroidintelligence.mobile.plugins.CapabilityPublisher(context,http,binding.storageKey,session.grantRevision,
                    localRevision = { pairingGrants.currentKernelGrant(binding.pairingId)?.revision ?: -1L },host = pluginHost,
                    foreignChange = { executionDriver?.cancelAll() },
                    authorizationCurrent = { authorizationAgreement.get() },
                    bindingCurrent = { pairingGrants.currentBinding() == binding },
                    ownerCurrent = { sessionRenewal === renewal && sessionJob === ownedJob && capabilityPublisher === publisher })
                capabilityPublisher = publisher
                sessionScope.launch {
                    combine(publisher.state, pairingGrants.state, pluginHost.revision) { state, local, hostRevision ->
                        if (local?.pairingId != binding.pairingId) DeviceCapabilitySyncState()
                        else state.forLocalState(local.revision, hostRevision)
                    }.collect { state ->
                        state.publishTo(deviceCapabilityState,
                            ownerCurrent = { authorizationAgreement.get() && sessionRenewal === renewal && sessionJob === ownedJob &&
                                capabilityPublisher === publisher && pairingGrants.currentBinding() == binding },
                            currentLocalRevision = { pairingGrants.currentKernelGrant(binding.pairingId)?.revision ?: -1L },
                            currentHostRevision = { pluginHost.revision.value })
                    }
                }
                selectedScreenBinding = com.openandroidintelligence.control.ScreenHostRegistry.current?.registerConnection(
                    session.accountId, binding.pairingId, session.pairingGeneration.toLong()) {
                    pairingGrants.state.value?.revision ?: -1L
                }?.also { com.openandroidintelligence.control.ScreenHostRegistry.current?.selectConnection(it) }
                val executor = com.openandroidintelligence.mobile.plugins.DeviceExecutionDriver(context,sessionScope,http,pluginHost,
                    session.accountId,session.deviceId,session.pairingGeneration,binding.storageKey,{ publisher.grantRevision },
                    isForeground = { androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) },
                    authorizationCurrent = { authorizationAgreement.get() && sessionRenewal === renewal },
                    localAuthorizationRevision = { pairingGrants.state.value?.revision ?: -1L }, authenticatedBinding = binding,
                    screenBinding = selectedScreenBinding)
                executionDriver = executor
                sessionScope.launch { executor.confirmation.collect { selectedDeviceConfirmation.value = it; publishDeviceConfirmation() } }
                sessionScope.launch(Dispatchers.IO) {
                    while (isActive) {
                        try { executor.recoverPending(); com.openandroidintelligence.mobile.plugins.PluginJobScheduler.maintain(context) }
                        catch (cancelled:CancellationException) { throw cancelled } catch (_:Exception) { _operationNotice.value="设备结果交付重试中。" }
                        delay(30_000)
                    }
                }
                sessionScope.launch(Dispatchers.IO) {
                    var publicationNotice: String? = null
                    kotlinx.coroutines.flow.combine(pairingGrants.state,pluginHost.revision) { _, _ -> Unit }.collectLatest {
                        com.openandroidintelligence.control.ScreenHostRegistry.current?.refreshAuthorization()
                        while (isActive) {
                            if (sessionRenewal !== renewal || sessionJob !== ownedJob || capabilityPublisher !== publisher) break
                            if (!authorizationAgreement.get()) {
                                deviceCapabilityState.value = DeviceCapabilitySyncState("AUTHORIZATION_MODEL_UNSUPPORTED")
                                break
                            }
                            // OS permission changes and listener reconnects need
                            // reconciliation even when plugin/local-grant flows are unchanged.
                            try {
                                publisher.sync()
                                if (sessionRenewal === renewal && sessionJob === ownedJob && capabilityPublisher === publisher) {
                                    publicationNotice?.let { _operationNotice.compareAndSet(it, null) }
                                    publicationNotice = null
                                }
                                delay(5_000)
                            }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (cause: Exception) {
                                if (sessionRenewal !== renewal || sessionJob !== ownedJob || capabilityPublisher !== publisher) break
                                if (!authorizationAgreement.get()) {
                                    deviceCapabilityState.value = DeviceCapabilitySyncState("AUTHORIZATION_MODEL_UNSUPPORTED")
                                    break
                                }
                                publicationNotice = "设备能力状态同步未完成（${capabilitySyncErrorCode(cause)}），正在重试。"
                                _operationNotice.value = publicationNotice
                                delay(2_000)
                            }
                        }
                    }
                }
            }
        } else if (pluginHost != null) {
            executionDriver = null
            capabilityPublisher = null
            com.openandroidintelligence.control.ScreenHostRegistry.current?.disconnect()
            deviceCapabilityState.value = DeviceCapabilitySyncState("AUTHORIZATION_MODEL_UNSUPPORTED")
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
        val mirroredRepository = com.openandroidintelligence.mobile.conversations.MirroredConversationRepository(workbenchRepository,mirror,mediaCache,
            managementCommittedByDelegate = true)
        http.setCursorRecovery {
            fun requireRecoveryOwner() {
                ownedJob.ensureActive()
                if (activeHttpClient !== http || sessionJob !== ownedJob) throw CancellationException("SNAPSHOT_OWNER_CHANGED")
            }
            if (nativeAgentSessions || _nativeAgents.value?.state?.value?.supported == true) {
                requireRecoveryOwner()
                val baseline = recoverPlatformSnapshot(http,
                    syncCapabilities = { requireRecoveryOwner(); capabilityPublisher?.sync() },
                    acceptRequest = { requireRecoveryOwner(); executionDriver?.enqueue(it) },
                    recoverLocalRequests = { requireRecoveryOwner(); executionDriver?.recoverPending() })
                requireRecoveryOwner()
                return@setCursorRecovery baseline
            }
            repeat(3) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val historyEpoch=kotlinx.coroutines.withContext(Dispatchers.IO) {
                    requireRecoveryOwner()
                    mirror.beginHistoryRead()
                }
                val response = http.execute(com.openandroidintelligence.gateway.http.SignedGatewayRequest("GET","/open-android-intelligence/v2/sync/snapshot"))
                check(response.status == 200) { "SNAPSHOT_FAILED" }
                val envelope = JsonFields.obj(Json.parse(response.body.decodeToString())) ?: error("SNAPSHOT_INVALID")
                val data = JsonFields.obj(JsonFields.field(envelope,"data")) ?: error("SNAPSHOT_INVALID")
                val baseline = JsonFields.string(data,"baselineCursor") ?: error("SNAPSHOT_INVALID")
                val presence = nativeConversationNotifications.capturePresenceReconciliation()
                val conversationPage = workbenchRepository.completeConversations(conversationScope)
                check(conversationPage.authoritative && conversationPage.nextCursor == null) { "SNAPSHOT_NOT_AUTHORITATIVE" }
                val threads = conversationPage.conversations
                val pages = linkedMapOf<String,com.openandroidintelligence.conversation.ports.TimelinePage>()
                // 只补本机已经保存或当前打开的线程，从未打开的旧历史仍在首次打开时读取。
                val known = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    threads.filter { thread -> thread.id.value == activeThread.get() || mirror.timeline(thread.id.value) != null || mirror.loadSendAtoms(thread.id.value).isNotEmpty() }
                }
                known.forEach { thread -> pages[thread.id.value] = workbenchRepository.completeTimeline(thread.id.value) }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                requireRecoveryOwner()
                if (!mirror.installBaselineAndCommit(threads,pages,baseline,historyEpoch,conversationPage.snapshotRevision)) return@repeat
                requireRecoveryOwner()
                if ("conversation-management-v1" in conversationUi) nativeConversationNotifications.retireMissingTargets(presence, conversationPage)
                // 同事务保存的媒体修订先补做；缺行本身不生成删除，旧导出不能越过未应用的屏障。
                mirror.applyPendingMediaTransitions(mediaCache)
                _controller.value?.onMirrorRebuilt()
                return@setCursorRecovery baseline
            }
            error("SNAPSHOT_STALE")
        }
        // 每个会话连接保留固定的 Agent 存储；界面切换不能改变迟到 IO 的写入范围。
        val nativeStores = java.util.concurrent.ConcurrentHashMap<String, com.openandroidintelligence.mobile.conversations.NativeConversationMirror>()
        fun nativeStore(agent: com.openandroidintelligence.gateway.agents.CoreAgent) = nativeStores.computeIfAbsent(agent.id) {
            com.openandroidintelligence.mobile.conversations.NativeConversationMirror(context, conversationScope,
                currentAgentId = { agent.id }, keyProvider = localDocumentKeyProvider,
                observerId = "${session.deviceId}:${session.pairingGeneration}")
        }
        ownedJob.invokeOnCompletion {
            nativeStores.values.forEach { store -> runCatching { store.close() } }
        }
        // 工作台持有固定 Agent 身份的仓库；切换选择不会改变已开始提交的接收者。
        val coreAgentRepositories = java.util.concurrent.ConcurrentHashMap<String, com.openandroidintelligence.mobile.conversations.CoreAgentConversationRepository>()
        fun nativeRepository(agent: com.openandroidintelligence.gateway.agents.CoreAgent) = coreAgentRepositories.computeIfAbsent(agent.id) {
            val discoveryOwner = checkNotNull(_nativeAgents.value)
            com.openandroidintelligence.mobile.conversations.CoreAgentConversationRepository(
                client = com.openandroidintelligence.gateway.agents.AgentSessionClient(http, traceEnabled = BuildConfig.DEBUG),
                currentAgent = { discoveryOwner.state.value.agents.firstOrNull { it.id == agent.id } ?: agent },
                activeConversationId = {
                    activeThread.get().takeIf { _nativeAgents.value === discoveryOwner &&
                        discoveryOwner.state.value.selectedAgentId == agent.id }
                },
                parentScope = sessionScope,
                supportsApprovalCards = "agent-approval-cards-v1" in conversationUi,
                supportsGenerationCancel = generationCancelCapability.agreed,
                approvalAudit = decisionAudit,
                organizationStore = com.openandroidintelligence.mobile.conversations.EncryptedNativeConversationOrganizationStore(
                    context, conversationScope, localDocumentKeyProvider),
                storeForAgent = ::nativeStore,
            )
        }
        fun restoreNativeReads() {
            if (sessionJob === ownedJob) coreAgentRepositories.values.forEach { it.connectionRestored() }
        }
        restartNativeReads = ::restoreNativeReads

        /**
         * 原生会话事件的提醒出口。
         *
         * 主界面、气泡和后台读取共用同一个 Agent 仓储，所以同一条回复只会经过这里
         * 一次；提醒层再做一层持久围栏，进程重启后的重放也不会重复提醒。
         */
        fun publishNativeNotification(agentId: String, event: com.openandroidintelligence.conversation.ports.VerifiedConversationEvent) {
            if (activeHttpClient !== http || sessionJob !== ownedJob) return
            com.openandroidintelligence.mobile.conversations.nativeNotificationFrame(
                accountId = session.accountId, agentId = agentId, event = event,
            )?.let { frame -> nativeConversationNotifications.accept(profileId, session.accountId, frame) }
        }

        /** 气泡自己的仓储：同一 Agent、同一份镜像，但拥有独立的读取者身份与事件订阅。 */
        fun bubbleRepository(
            agent: com.openandroidintelligence.gateway.agents.CoreAgent,
            active: java.util.concurrent.atomic.AtomicReference<String?>,
        ): com.openandroidintelligence.mobile.conversations.CoreAgentConversationRepository {
            // 每个气泡控制器各有一个读取者：共用游标所有权会让两边的 ACK 互相推翻。
            val observer = "${session.deviceId}:${session.pairingGeneration}:bubble:" +
                java.util.UUID.randomUUID().toString().replace("-", "")
            return com.openandroidintelligence.mobile.conversations.CoreAgentConversationRepository(
                client = com.openandroidintelligence.gateway.agents.AgentSessionClient(http, traceEnabled = BuildConfig.DEBUG),
                currentAgent = { _nativeAgents.value?.state?.value?.agents?.firstOrNull { it.id == agent.id } ?: agent },
                activeConversationId = active::get,
                parentScope = sessionScope,
                supportsApprovalCards = "agent-approval-cards-v1" in conversationUi,
                supportsGenerationCancel = generationCancelCapability.agreed,
                approvalAudit = decisionAudit,
                organizationStore = com.openandroidintelligence.mobile.conversations.EncryptedNativeConversationOrganizationStore(
                    context, conversationScope, localDocumentKeyProvider),
                storeForAgent = { current ->
                    com.openandroidintelligence.mobile.conversations.NativeConversationStoreView(nativeStore(current), observer)
                },
                onEventPublished = { agentId, event -> if (agentId == agent.id) publishNativeNotification(agentId, event) },
            )
        }

        /**
         * 原生模式下气泡的完整工作台：同一份镜像、同一套会话组件，但拥有自己的
         * 仓储、草稿与导航状态，绝不改动主界面正在看的会话或草稿。
         */
        fun buildNativeBubbleWorkbench(target: ReplyNotificationTarget): WorkbenchController? {
            val agentId = target.agentId ?: return null
            val agents = _nativeAgents.value?.state?.value?.agents.orEmpty()
            val agent = agents.firstOrNull { it.id == agentId } ?: return null
            val ownerScope = com.openandroidintelligence.mobile.conversations.NativeLocalPartitions
                .partitionScope(conversationScope, agentId)
            val media = com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache(
                context, conversationScope, http, localDocumentKeyProvider, nativeAgentId = agentId)
            val bubbleDownloads = com.openandroidintelligence.mobile.conversations.AndroidAttachmentDownloads(
                context, media, ownerScope, keyProvider = localDocumentKeyProvider)
            val staging = attachmentStagingStore(endpoint.baseUrl, ownerScope.accountId, installationId())
            val recovery = com.openandroidintelligence.mobile.conversations.EncryptedAttachmentRecovery(
                context, ownerScope, localDocumentKeyProvider)
            val bubbleAttachments = BubbleAttachmentDraftCoordinator(
                GatewayAttachmentDraftCoordinator(AttachmentUploader(HttpAttachmentTransport(http)),
                    com.openandroidintelligence.conversation.attachment.AttachmentSubmissionGate(
                        onSubmit = { error("ATTACHMENT_SUBMISSION_REQUIRES_CONVERSATION_CONTROLLER") }),
                    sessionScope, staging, recovery = recovery))
            // 仓储只认"当前被这个气泡打开的会话"，切换会话时由控制器通知它。
            val active = java.util.concurrent.atomic.AtomicReference(target.conversationId)
            val repository = bubbleRepository(agent, active)
            return WorkbenchController(
                scope = sessionScope,
                repository = repository,
                catalogRepository = com.openandroidintelligence.mobile.conversations.CoreAgentCommandCatalogRepository(
                    com.openandroidintelligence.gateway.agents.AgentSessionClient(http, traceEnabled = BuildConfig.DEBUG),
                    currentAgent = { _nativeAgents.value?.state?.value?.agents?.firstOrNull { it.id == agentId } },
                    negotiated = "agent-command-catalog-v1" in conversationUi,
                ),
                restoreLastThread = false,
                // 气泡与主界面共享同一份会话数据与渲染组件，但草稿和导航各自独立。
                canReadConversations = { _nativeAgents.value?.state?.value?.agents?.any { it.id == agentId } == true },
                scopeFactory = { ownerScope },
                attachmentCoordinator = bubbleAttachments,
                onActiveThreadChanged = { threadId ->
                    active.set(threadId)
                    // 让事件读取跟着气泡当前打开的会话走，而不是一直停在启动时那个。
                    repository.activeConversationChanged()
                },
                supportsApprovalCards = approvalClient != null && agent.capabilities.approvals,
                supportsConversationManagement = true,
                supportsConversationDelete = agent.capabilities.delete,
                supportsConversationArchive = agent.capabilities.archive,
                supportsConversationUnarchive = false,
                supportsConversationRename = agent.capabilities.rename,
                organizationStoredLocally = true,
                authoritativeMessageIds = true,
                languageCode = java.util.Locale.getDefault().toLanguageTag(),
                debouncePolicy = com.openandroidintelligence.mobile.conversations.DebouncePreferences(context).read(endpoint.baseUrl),
                externalBusyThreadIds = {
                    _controller.value?.ownedBusyThreadIds.orEmpty()
                },
                streamHealthSource = repository,
                onClosed = {
                    bubbleAttachments.close(sessionScope)
                    // 气泡真正关闭后不再为该会话长轮询：读取者身份只属于这个气泡。
                    repository.close()
                },
                media = media,
                downloads = bubbleDownloads,
            ).also { it.openThread(target.conversationId) }
        }

        // 原生模式下气泡的"新建会话"必须交给当前 Agent：Core 没有旧会话路由。
        bubbleThreadCreator = { target ->
            val agentId = target.agentId
            if (!nativeAgentSessions || agentId == null) null
            else {
                val agent = _nativeAgents.value?.state?.value?.agents?.firstOrNull { it.id == agentId }
                if (agent == null) null
                else {
                    val created = withContext(Dispatchers.IO) {
                        com.openandroidintelligence.gateway.agents.AgentSessionClient(http, traceEnabled = BuildConfig.DEBUG)
                            .create(agent, requestId = "bubble_" + java.util.UUID.randomUUID().toString().replace("-", ""))
                    }
                    _controller.value?.refreshThreads()
                    target.copy(conversationId = created.id)
                }
            }
        }

        // 助手会话的专属资源：原生面为每个会话准备独立观察者与本地存储，
        // 旧版对话面则使用独立事件客户端（HTTP 事件流只有一个消费者）。
        val assistantResources = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
        val assistantFactory = AssistantSessionFactory(
            profileId = profileId,
            accountId = session.accountId,
            scope = sessionScope,
            open = { agentId, sessionToken ->
                if (activeHttpClient !== http || sessionJob !== ownedJob || !ownedJob.isActive) null
                else if (nativeAgentSessions) {
                    val discoveryOwner = _nativeAgents.value ?: return@AssistantSessionFactory null
                    val assistantAgent = discoveryOwner.state.value.agents.firstOrNull { it.id == agentId }
                        ?: discoveryOwner.state.value.agent ?: return@AssistantSessionFactory null
                    // 每个助手会话独立观察者：与主工作台共用游标会让任何一方 ACK 掉另一方未处理的事件。
                    val store = com.openandroidintelligence.mobile.conversations.NativeConversationMirror(
                        context, conversationScope, currentAgentId = { assistantAgent.id },
                        keyProvider = localDocumentKeyProvider,
                        observerId = "${session.deviceId}:${session.pairingGeneration}:assistant:$sessionToken",
                    )
                    val active = java.util.concurrent.atomic.AtomicReference<String?>(null)
                    val repository = com.openandroidintelligence.mobile.conversations.CoreAgentConversationRepository(
                        client = com.openandroidintelligence.gateway.agents.AgentSessionClient(http, traceEnabled = BuildConfig.DEBUG),
                        currentAgent = { discoveryOwner.state.value.agents.firstOrNull { it.id == assistantAgent.id } ?: assistantAgent },
                        activeConversationId = active::get,
                        parentScope = sessionScope,
                        supportsApprovalCards = "agent-approval-cards-v1" in conversationUi,
                        supportsGenerationCancel = generationCancelCapability.agreed,
                        approvalAudit = decisionAudit,
                        organizationStore = com.openandroidintelligence.mobile.conversations.EncryptedNativeConversationOrganizationStore(
                            context, conversationScope, localDocumentKeyProvider),
                        storeForAgent = { store },
                    )
                    // 与主工作台共用同一个按 Agent 分区的附件协调器：交接按 draftId 跟踪
                    // 的必须是同一份暂存/上传状态，否则交过去的是永不推进的引用。
                    val owning = nativeOwningAttachmentCoordinators[assistantAgent.id] ?: attachmentCoordinator
                    val attachments = assistantAttachmentWrapper(owning, sessionToken)
                    val controller = createNativeAssistantWorkbench(
                        scope = sessionScope,
                        conversationScope = conversationScope,
                        repository = repository,
                        catalogRepository = com.openandroidintelligence.mobile.conversations.CoreAgentCommandCatalogRepository(
                            com.openandroidintelligence.gateway.agents.AgentSessionClient(http, traceEnabled = BuildConfig.DEBUG),
                            currentAgent = { discoveryOwner.state.value.agents.firstOrNull { it.id == assistantAgent.id } },
                            negotiated = "agent-command-catalog-v1" in conversationUi,
                        ),
                        capabilities = conversationUi.toSet(),
                        debouncePolicy = com.openandroidintelligence.mobile.conversations.DebouncePreferences(context).read(endpoint.baseUrl),
                        supportsApprovalCards = approvalClient != null && assistantAgent.capabilities.approvals,
                        onActiveThreadChanged = { active.set(it) },
                        media = mediaCache,
                        downloads = downloads,
                        attachmentCoordinator = attachments,
                        externalBusyThreadIds = { assistantWorkspace?.busyThreadIds().orEmpty() },
                    )
                    assistantResources[sessionToken] = { runCatching { store.close() } }
                    controller
                } else {
                    val assistantStatus = EventStreamStatusSink()
                    val assistantHttp = GatewayHttpClient(
                        profile = profile,
                        transport = transport,
                        signer = { preimage -> deviceKeys.sign(profileId, preimage) },
                        cursorStore = com.openandroidintelligence.gateway.events.InMemoryEventCursorStore(),
                        statusSink = assistantStatus,
                        handlePlatformEvent = { event -> event.event in PLATFORM_EVENTS },
                        sessionRenewal = renewal,
                    )
                    val attachments = assistantAttachmentWrapper(attachmentCoordinator, sessionToken)
                    createAssistantWorkbench(
                        scope = sessionScope,
                        conversationScope = conversationScope,
                        http = assistantHttp,
                        status = assistantStatus,
                        approvals = approvalClient,
                        generationCancel = generationCancelCapability,
                        capabilities = conversationUi.toSet(),
                        debouncePolicy = com.openandroidintelligence.mobile.conversations.DebouncePreferences(context).read(endpoint.baseUrl),
                        media = mediaCache,
                        downloads = downloads,
                        attachmentCoordinator = attachments,
                        externalBusyThreadIds = { assistantWorkspace?.busyThreadIds().orEmpty() },
                    )
                }
            },
            onSessionReleased = { sessionToken ->
                assistantResources.remove(sessionToken)?.invoke()
            },
            onClose = {
                assistantResources.values.toList().forEach { resource -> runCatching(resource) }
                assistantResources.clear()
            },
        )
        assistantSessionFactory = assistantFactory
        ownedJob.invokeOnCompletion {
            // 旧连接的收尾只能清掉它自己安装的工厂；新连接可能已经接管。
            val current = assistantSessionFactory
            if (current === assistantFactory) assistantSessionFactory = null
            assistantFactory.close()
        }

        var workbenchAgentId: String? = null
        suspend fun buildWorkbenchController(currentAgentName: String? = null, restoreSelection: Boolean = restoreLastThread): WorkbenchController {
            val discoveryOwner = _nativeAgents.value.takeIf { nativeAgentSessions }
            val ownerAgent = discoveryOwner?.state?.value?.agent
            val ownerAgentId = ownerAgent?.id
                ?: accountProfiles.find(profileId)?.selectedAgentId.takeIf { nativeAgentSessions }
            val ownerRepository = if (nativeAgentSessions) nativeRepository(requireNotNull(ownerAgent)) else null
            val activeRepo: ConversationRepository = ownerRepository ?: mirroredRepository
            val activeCatalogRepo = if (nativeAgentSessions) {
                com.openandroidintelligence.mobile.conversations.CoreAgentCommandCatalogRepository(
                    com.openandroidintelligence.gateway.agents.AgentSessionClient(http, traceEnabled = BuildConfig.DEBUG),
                    currentAgent = { discoveryOwner?.state?.value?.agents?.firstOrNull { it.id == ownerAgentId } },
                    negotiated = "agent-command-catalog-v1" in conversationUi,
                )
            } else catalogRepository
            val ownerScope = ownerAgentId?.takeIf { nativeAgentSessions }?.let {
                com.openandroidintelligence.mobile.conversations.NativeLocalPartitions.partitionScope(conversationScope, it)
            } ?: conversationScope
            val nativePersistence = ownerAgentId?.takeIf { nativeAgentSessions }?.let { agentId ->
                com.openandroidintelligence.mobile.conversations.NativeConversationMirror(
                    context, conversationScope, currentAgentId = { agentId }, keyProvider = localDocumentKeyProvider)
            }
            val ownerMedia = if (nativeAgentSessions && ownerAgentId != null)
                com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache(context, conversationScope, http,
                    localDocumentKeyProvider, nativeAgentId=ownerAgentId) else mediaCache
            val ownerDownloads = if (nativePersistence != null)
                com.openandroidintelligence.mobile.conversations.AndroidAttachmentDownloads(context,ownerMedia,ownerScope,
                    keyProvider=localDocumentKeyProvider) else downloads
            val ownerAttachments = if (nativePersistence != null) {
                val ownerStaging = attachmentStagingStore(endpoint.baseUrl,ownerScope.accountId,installationId())
                val ownerRecovery = com.openandroidintelligence.mobile.conversations.EncryptedAttachmentRecovery(context,ownerScope,localDocumentKeyProvider)
                val savedNative = nativePersistence.load()
                val trustedDraftIds = savedNative?.let { saved -> buildSet {
                    addAll(saved.attachments.map { it.id.value })
                    addAll(saved.composerDrafts.flatMap { it.attachments }.map { it.id.value })
                    addAll(saved.pendingSubmissions.flatMap { it.attachmentIds })
                    addAll(saved.submission?.attachmentIds.orEmpty())
                } }.orEmpty()
                if (trustedDraftIds.isNotEmpty()) try {
                    val legacyScopeKey = "attachment-map-v1:" + Json.canonical(Json.of(listOf(
                        conversationScope.profileId,conversationScope.gatewayId,conversationScope.accountId,conversationScope.installId)))
                    val legacyHash = MessageDigest.getInstance("SHA-256").digest(legacyScopeKey.toByteArray()).joinToString("") { "%02x".format(it) }
                    val legacyRecovery = com.openandroidintelligence.mobile.conversations.EncryptedAttachmentRecovery(context,conversationScope,
                        localDocumentKeyProvider ?: existingLocalKey("oai_documents_$legacyHash"))
                    com.openandroidintelligence.mobile.conversations.NativeAttachmentDraftMigration.migrate(trustedDraftIds,
                        attachmentStagingStore(endpoint.baseUrl,conversationScope.accountId,installationId(),existingOnly=true),
                        ownerStaging,legacyRecovery,ownerRecovery,isCancelled={ !ownedJob.isActive })
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { _operationNotice.value = MEDIA_RECOVERY_NOTICE }
                GatewayAttachmentDraftCoordinator(AttachmentUploader(HttpAttachmentTransport(http)),
                    com.openandroidintelligence.conversation.attachment.AttachmentSubmissionGate(
                        onSubmit = { error("ATTACHMENT_SUBMISSION_REQUIRES_CONVERSATION_CONTROLLER") }),
                    sessionScope, ownerStaging,
                    recovery=ownerRecovery)
            } else attachmentCoordinator
            if (nativePersistence != null) {
                if (prepareNativeMedia(nativePersistence,ownerMedia))
                    recoverDownloads(ownerDownloads)?.let { _operationNotice.value = it }
                else _operationNotice.value = MEDIA_RECOVERY_NOTICE
            }
            // 原生会话模式下主工作台与助手工厂共用同一个附件协调器（按 Agent 分区）：
            // 交接要求两侧按 draftId 跟踪的是同一份暂存/上传状态，不同实例会让交过去的
            // 附件成为永不推进的 LOCAL_PREPARING。
            if (nativeAgentSessions && ownerAgentId != null) {
                nativeOwningAttachmentCoordinators[ownerAgentId] = ownerAttachments
            }
            return WorkbenchController(
                scope = sessionScope,
                repository = activeRepo,
                catalogRepository = activeCatalogRepo,
                restoreLastThread = restoreSelection,
                canReadConversations = { !nativeAgentSessions ||
                    _nativeAgents.value?.state?.value?.agent?.let { it.id == ownerAgentId && it.online } == true },
                languageCode = java.util.Locale.getDefault().toLanguageTag(),
                scopeFactory = { ownerScope },
                attachmentCoordinator = ownerAttachments,
                supportsMessageBatches = !nativeAgentSessions && setOf("message-batches-v1", "newline-v1").all { it in conversationUi },
                debouncePolicy = com.openandroidintelligence.mobile.conversations.DebouncePreferences(context).read(endpoint.baseUrl),
                supportsAgentCommandNew = "agent-command-new-v1" in conversationUi,
                supportsApprovalCards = approvalClient != null && (!nativeAgentSessions ||
                    _nativeAgents.value?.state?.value?.agent?.capabilities?.approvals == true),
                supportsConversationManagement = "conversation-management-v1" in conversationUi || nativeAgentSessions,
                supportsConversationDelete = if (nativeAgentSessions) _nativeAgents.value?.state?.value?.agent?.capabilities?.delete == true
                    else "conversation-management-v1" in conversationUi,
                supportsConversationArchive = if (nativeAgentSessions) _nativeAgents.value?.state?.value?.agent?.capabilities?.archive == true
                    else "conversation-management-v1" in conversationUi,
                supportsConversationUnarchive = !nativeAgentSessions && "conversation-management-v1" in conversationUi,
                supportsConversationRename = if (nativeAgentSessions) _nativeAgents.value?.state?.value?.agent?.capabilities?.rename == true
                    else "conversation-management-v1" in conversationUi,
                organizationStoredLocally = nativeAgentSessions,
                authoritativeMessageIds = nativeAgentSessions,
                externalBusyThreadIds = {
                    bubbleBusyConversationIds() + assistantBusyThreadIds() +
                        nativeConversationNotifications.sessions.value
                            .filter { it.isRunning }.map { it.target.conversationId }
                },
                onActiveThreadChanged = { threadId ->
                    if (!nativeAgentSessions || _nativeAgents.value?.state?.value?.selectedAgentId == ownerAgentId) {
                        activeThread.set(threadId)
                        repository.activeConversationChanged()
                    }
                    ownerRepository?.activeConversationChanged()
                },
                streamHealthSource = (activeRepo as? com.openandroidintelligence.conversation.model.StreamHealthSource) ?: workbenchRepository,
                persistence = if (nativeAgentSessions) nativePersistence else mirror,
                media = ownerMedia,
                downloads = ownerDownloads,
            ).also {
                workbenchAgentId = ownerAgentId
                // 助手工作区与主工作台锁定同一个 Agent；这里把本地构建结果同步给运行时。
                _workbenchAgentId = ownerAgentId
            }
        }

        // 原生 Agent 发现完成后再创建其在线工作台；等待期间保留本地页面。
        if (nativeAgentSessions && _controller.value == null) {
            initializePostLogin(InitializationStage.WORKBENCH) {
                showLocalWorkspace(endpoint, username, profileId, session.accountId, checkOwner = checkOwner,
                    initializing = true, restoreLastThread = restoreLastThread, sessionOwner = ownedJob)
            }
        }
        if (!nativeAgentSessions) {
            _controller.value = initializePostLogin(InitializationStage.WORKBENCH) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    checkOwner()
                    buildWorkbenchController()
                }
            }
        }

        workspacePresented = true
        if (nativeAgentSessions) {
            val rememberedAgentId = accountProfiles.find(profileId)?.selectedAgentId
            _nativeAgents.value?.close()
            val nativeController = NativeAgentsController(com.openandroidintelligence.gateway.agents.AgentSessionClient(http, traceEnabled = BuildConfig.DEBUG), sessionScope,
                lastSelectedAgentId = rememberedAgentId,
                onSelectionLost = { lost -> _operationNotice.value = "上次使用的 Agent「$lost」已不在当前列表中，请重新选择。" })
            _nativeAgents.value = nativeController
            var previousAgent: Pair<String, com.openandroidintelligence.gateway.agents.AgentCapabilities>? = null
            val workbenchHandoff = Mutex()
            suspend fun updateNativeWorkbench(nState: NativeAgentsState) = workbenchHandoff.withLock {
                if (sessionJob !== ownedJob || _nativeAgents.value !== nativeController ||
                    nState.selectedAgentId != nativeController.state.value.selectedAgentId) return@withLock
                val selected = nState.agent?.id
                val descriptor = nState.agent?.let { it.id to it.capabilities }
                if (selected != null && descriptor != previousAgent) {
                    val isFirstObservation = previousAgent == null
                    val agentChanged = previousAgent?.first != selected
                    previousAgent = descriptor
                    // 用户实际切换（或恢复的选择被服务端确认）才持久化与重建工作台，
                    // 首帧观察不重建，避免每次重连都无谓地清空界面状态。
                    if (!isFirstObservation || nState.selectionConfirmed) {
                        synchronized(accountProfiles) {
                            accountProfiles.find(profileId)?.let { profile ->
                                if (profile.selectedAgentId != selected) {
                                    accountProfiles.saveSelectedAgent(profileId, selected)
                                    _savedProfiles.value = accountProfiles.list()
                                }
                            }
                        }
                    }
                    val newAgent = nState.agent
                    val newAgentName: String = newAgent?.name ?: selected
                    val currentPhase = _phase.value
                    if (currentPhase is ConnectionPhase.Connected && currentPhase.agentName != newAgentName) {
                        _phase.value = currentPhase.copy(agentName = newAgentName)
                    }
                    if (workbenchAgentId != selected) {
                        var restoreSelection = when {
                            isFirstObservation -> if (retainedLocalController != null)
                                _controller.value?.state?.value?.activeThreadId != null else restoreLastThread
                            agentChanged -> true
                            else -> _controller.value?.state?.value?.activeThreadId != null
                        }
                        try {
                            // 交接期间到达旧界面的选择与输入，等新 owner 发布后执行。
                            _controller.value?.let { previous ->
                                val sourceAgent = workbenchAgentId ?: rememberedAgentId
                                if (sourceAgent == selected || isFirstObservation && sourceAgent == null &&
                                    previous.state.value.activeThreadId == null) {
                                    redirectRetiringWorkbench(previous, sessionScope) {
                                        sessionJob === ownedJob && _nativeAgents.value === nativeController &&
                                            nativeController.state.value.agent?.id == selected
                                    }
                                }
                            }
                            // 旧分区 IO 收尾后才交接，新 owner 再次核对后才发布。
                            _controller.value?.closeAndAwait()
                            if (isFirstObservation && retainedLocalController != null) {
                                restoreSelection = _controller.value?.state?.value?.activeThreadId != null
                            }
                            retainedLocalOwner?.cancel()
                            val replacement = withContext(Dispatchers.IO) {
                                buildWorkbenchController(newAgentName, restoreSelection)
                            }
                            if (sessionJob !== ownedJob || _nativeAgents.value !== nativeController ||
                                nativeController.state.value.selectedAgentId != selected) {
                                replacement.close()
                                return@withLock
                            }
                            _controller.value = replacement
                            _operationNotice.compareAndSet(WORKBENCH_RECOVERY_NOTICE, null)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (cause: Exception) {
                            logConnectionFailure(InitializationStage.WORKBENCH.name, "WORKBENCH_RECOVERY_FAILED", cause)
                            previousAgent = null
                            _operationNotice.value = WORKBENCH_RECOVERY_NOTICE
                        }
                    } else {
                        // Initial requests may have run before Agent discovery completed.
                        // Agent discovery now supplies the subscription identity.
                        // Rebind the event consumer without discarding its draft.
                        _controller.value?.onForegrounded()
                        _controller.value?.loadCatalog()
                    }
                }
                val available = nState.agent
                _controller.value?.updateRemoteCapabilities(
                    online = available?.online == true,
                    send = available?.capabilities?.send == true,
                    delete = available?.capabilities?.delete == true,
                    archive = available?.capabilities?.archive == true,
                    rename = available?.capabilities?.rename == true,
                    approvals = approvalClient != null && available?.capabilities?.approvals == true,
                )
            }
            restartNativeReads = {
                if (sessionJob === ownedJob && _nativeAgents.value === nativeController) {
                    restoreNativeReads()
                    if (canRetryWorkbenchInitialization) {
                        nativeController.refresh()
                        sessionScope.launch { updateNativeWorkbench(nativeController.state.value) }
                    }
                }
            }
            sessionScope.launch {
                nativeController.state.collect { updateNativeWorkbench(it) }
            }
        }
        clearBubbleWorkTracking()
        bubbleWorkspace?.close()
        bubbleWorkspace = BubbleConversationWorkspace(profileId, session.accountId) { target ->
            if (activeHttpClient !== http || sessionJob !== ownedJob || !ownedJob.isActive) null
            // 原生 Agent 会话没有旧 `/conversations` 路由：气泡直接复用该 Agent 的
            // 原生仓储与镜像，界面组件仍是同一套工作台。
            else if (nativeAgentSessions && target.agentId != null) buildNativeBubbleWorkbench(target)
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
                createBubbleWorkbench(sessionScope, conversationScope, target.conversationId, bubbleHttp, bubbleStatus,
                    approvalClient, generationCancelCapability, conversationUi.toSet(),
                    com.openandroidintelligence.mobile.conversations.DebouncePreferences(context).read(endpoint.baseUrl),
                    mediaCache, downloads,
                    managementOperations = mirror,
                    attachmentCoordinator = BubbleAttachmentDraftCoordinator(attachmentCoordinator),
                    externalBusyThreadIds = {
                        _controller.value?.ownedBusyThreadIds.orEmpty() + bubbleBusyConversationIds() +
                            nativeConversationNotifications.sessions.value.filter { it.isRunning }.map { it.target.conversationId }
                    })
            }
        }
        synchronized(backgroundOwnershipLock) {
            checkOwner()
            val effectiveAgentName = if (nativeAgentSessions) {
                // 原生多 Agent 模式下优先保持用户已选中的 Agent 名称或配置中的名称，
                // 不使用网关单机部署名覆盖。
                _nativeAgents.value?.state?.value?.agent?.name
                    ?: accountProfiles.find(profileId)?.agentName
                    ?: agentName
            } else {
                agentName
            }
            runCatching {
                synchronized(accountProfiles) {
                    accountProfiles.find(profileId)?.let { accountProfiles.save(it.copy(agentName = effectiveAgentName)) }
                    _savedProfiles.value = accountProfiles.list()
                }
            }
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
                agentName = effectiveAgentName,
            )
            // 仅撤回本次恢复阶段的离线提示，不覆盖附件恢复或其他并发操作的新告警。
            _operationNotice.compareAndSet(OFFLINE_MIRROR_NOTICE, null)
            _loginStatus.value = GatewayLoginStatus.SIGNED_IN
            val activeAccount = accountProfiles.find(profileId) ?: AccountProfile(profileId, endpoint.baseUrl, username, "")
            _localSessionState.value = LocalSessionState.Workspace(activeAccount, _phase.value)
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
            statusSink = platformStatus,
            sessionRenewal = renewal,
            handlePlatformEvent = { event ->
                if (activeHttpClient !== http || sessionJob !== ownedJob) true
                else {
                    nativeConversationNotifications.accept(profileId, session.accountId, event)
                    if (event.event == "conversation.message.status") {
                        // 原生会话下工作台不订阅平台事件流；把投递状态转发过去，
                        // "等待 Agent 接收"才能在网关确认接收时立刻变成"已接收"。
                        runCatching {
                            com.openandroidintelligence.conversation.data.GatewayEventDecoder.decode(event)
                        }.getOrNull()
                            ?.let { decoded ->
                                (decoded as? com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.MessageStatus)
                                    ?.let { status ->
                                        GatewayLog.d(
                                            "OaiLatency",
                                            "gateway.status status=${status.status.wireValue} messageId=${status.messageId} occurredAt=${status.occurredAt} receivedAt=${System.currentTimeMillis()}",
                                        )
                                        _controller.value?.onGatewayDeliveryStatus(status)
                                    }
                            }
                    }
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
                        val conversationPage = workbenchRepository.completeConversations(conversationScope)
                        if (sessionJob !== ownedJob || activeHttpClient !== http) return
                        nativeConversationNotifications.retireMissingTargets(presence, conversationPage)
                    }
                }
                notificationReconciler?.reconcileActive(force)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { /* Keep the last projection and retry on the next recovery tick. */ }
        }
        platformHttp.setCursorRecovery {
            val baseline = recoverPlatformSnapshot(platformHttp,
                syncCapabilities = { capabilityPublisher?.sync() },
                acceptRequest = { executionDriver?.enqueue(it) },
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
        var notificationRecoveryJob: Job? = null
        val streamOwnerLock = Any()
        var streamBlocked = false
        var blockedSnapshot: GatewayProfile? = null
        var platformHasBeenLive = false
        var ownerSequence = 0L
        fun updateScreenConnection(live: Boolean) {
            if (sessionJob === ownedJob) com.openandroidintelligence.control.ScreenHostRegistry.current?.connectionChanged(live)
        }
        sessionScope.launch {
            platformStatus.status.collect { status ->
                if (sessionJob !== ownedJob) return@collect
                val projected = if (platformHasBeenLive && status == EventStreamStatus.CONNECTING)
                    EventStreamStatus.RECONNECTING else status
                _connectionStatus.value = projected
                updateScreenConnection(status == EventStreamStatus.LIVE)
                if (status == EventStreamStatus.LIVE) {
                    platformHasBeenLive = true
                    restoreNativeReads()
                    // Keep network IO out of the status collector. Recovery
                    // activity must not cancel Native reads or refresh history.
                    if (notificationRecoveryJob?.isActive != true) {
                        notificationRecoveryJob = sessionScope.launch(Dispatchers.IO) {
                            reconcileNotifications(force = true)
                        }
                    }
                    if (!nativeAgentSessions) _controller.value?.refreshThreads()
                }
            }
        }
        val startPlatform: (PlatformStreamWakeReason) -> Unit = start@ { reason ->
            synchronized(streamOwnerLock) {
                if (sessionJob !== ownedJob) return@start
                if (streamBlocked && blockedSnapshot == renewal.current && reason != PlatformStreamWakeReason.USER_RETRY) return@start
                val previous = platformJob
                val replace = reason in setOf(PlatformStreamWakeReason.NETWORK_CHANGED,
                    PlatformStreamWakeReason.CREDENTIAL_CHANGED, PlatformStreamWakeReason.USER_RETRY)
                if (previous?.isActive == true && !replace) return@start
                previous?.cancel()
                val owner = ++ownerSequence
                val next = sessionScope.launchAfterPlatformStream(previous) {
                    if (sessionJob !== ownedJob) return@launchAfterPlatformStream
                    streamBlocked = false
                    GatewayLog.d("OaiConnection", "stage=PLATFORM_START owner=$owner reason=${reason.name}")
                    try {
                        // GatewayHttpClient owns the only network retry loop.
                        platformHttp.events().collect { }
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (rejected: EventSessionRejectedException) {
                        ensureActive()
                        if (sessionJob !== ownedJob) return@launchAfterPlatformStream
                        streamBlocked = true
                        blockedSnapshot = renewal.current
                        updateScreenConnection(false)
                        _connectionStatus.value = EventStreamStatus.FAILED
                        _operationNotice.value = "Gateway 事件通道认证仍被拒绝，已暂停该通道。登录和本机授权已保留，请检查 Gateway 会话与设备认证配置。"
                    } catch (cause: RefreshOutcomeUnknownException) {
                        ensureActive()
                        if (sessionJob !== ownedJob) return@launchAfterPlatformStream
                        streamBlocked = true
                        blockedSnapshot = renewal.current
                        updateScreenConnection(false)
                        _connectionStatus.value = EventStreamStatus.FAILED
                        _operationNotice.value = renewalFailureNotice(cause)
                    } catch (cause: Exception) {
                        ensureActive()
                        if (sessionJob !== ownedJob) return@launchAfterPlatformStream
                        streamBlocked = true
                        blockedSnapshot = renewal.current
                        updateScreenConnection(false)
                        _connectionStatus.value = EventStreamStatus.FAILED
                        nativeConversationNotifications.disconnect()
                        GatewayLog.w("OaiConnection", "stage=PLATFORM_PAUSED owner=$owner code=${cause.javaClass.simpleName}")
                        _operationNotice.value = "Gateway 事件通道恢复已暂停，登录资料已保留，请检查连接配置或本机存储后重试。"
                    }
                }
                platformJob = next
            }
        }
        synchronized(backgroundOwnershipLock) {
            checkOwner()
            restartPlatformStream = startPlatform
            startPlatform(PlatformStreamWakeReason.INITIAL)
        }
    }

    private suspend fun prepareNativeMedia(
        mirror: com.openandroidintelligence.mobile.conversations.NativeConversationMirror,
        media: com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache,
    ): Boolean {
        return prepareMediaTransitions(mirror.offlineMirror(),media)
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
        establishingJob = null
        _nativeAgents.value?.close()
        _nativeAgents.value = null
        restartPlatformStream = null
        restartNativeReads = null
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
        // 登出不能把旧账号的助手会话留在工作区里。
        retireAssistantWorkspace()
        _controller.value?.close()
        executionDriver?.cancelAll()
        com.openandroidintelligence.control.ScreenHostRegistry.current?.disconnect()
        executionDriver = null
        capabilityPublisher = null
        deviceAuthorizationAgreement?.set(false)
        deviceAuthorizationAgreement = null
        selectedScreenBinding?.let { com.openandroidintelligence.control.ScreenHostRegistry.current?.disconnectConnection(it) }
        selectedScreenBinding = null
        deviceCapabilityState.value = DeviceCapabilitySyncState()
        selectedDeviceConfirmation.value = null
        publishDeviceConfirmation()
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
        _coreHttpHealth.value = CoreHttpHealth()
    }

    private suspend fun handlePlatformEvent(event: GatewayEvent, sessionId: String, deviceId: String, binding: PairingGrantBinding): Boolean {
        if (event.event !in PLATFORM_EVENTS) return false
        val body = JsonFields.obj(Json.parse(event.data)) ?: error("PLATFORM_EVENT_INVALID")
        val payload = JsonFields.obj(JsonFields.field(body, "payload")) ?: error("PLATFORM_EVENT_INVALID")
        when (event.event) {
            "device.requested" -> executionDriver?.enqueue(JsonFields.string(payload,"requestId") ?: error("PLATFORM_EVENT_INVALID"))
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
                gatewayGrantRevision = revision
                deviceCapabilityState.value = DeviceCapabilitySyncState()
                _operationNotice.value = "设备能力状态已变化，正在重新同步；本机开关保持原设置。"
            }
        }
        return true
    }

    private fun platformCursorProfile(profileId: String) = "$profileId:platform"

    private fun authClientFor(
        gatewayUrl: String,
        pinnedSpkiSha256: Set<String> = emptySet(),
        beforeConnect: () -> Unit = {},
    ): GatewayAuthClient = GatewayAuthClient(
        transport = GatewayTransport(
            GatewayProfile(
                accountId = "pre-auth",
                deviceId = "pre-auth",
                sessionId = "pre-auth",
                gatewayBaseUrl = gatewayUrl,
                pinnedSpkiSha256 = pinnedSpkiSha256,
            ),
            beforeConnect = beforeConnect,
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

    private fun saveLastProfile(gatewayUrl: String, username: String, profileId: String, session: SessionCredentials, explicitLogin: Boolean = false) {
        val endpoint = checkNotNull(GatewayEndpoint.parse(gatewayUrl))
        val retained = if (endpoint.isTls) checkNotNull(identityTrust.retained(endpoint, username)) else null
        val trustId = if (endpoint.isTls) (retained?.spki ?: "ca:${retained?.deploymentId}") else ""
        synchronized(accountProfiles) {
            val previous = accountProfiles.find(profileId)
            accountProfiles.save(AccountProfile(profileId, gatewayUrl, username, trustId,
                avatarBase64 = previous?.avatarBase64, agentName = previous?.agentName,
                selectedAgentId = previous?.selectedAgentId),
                AndroidAccountProfileStore.Binding(session.accountId, session.deviceId, session.sessionId, DEVICE_KEY_ENCODING_VERSION))
            _savedProfiles.value = accountProfiles.list()
        }
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_GATEWAY, gatewayUrl)
            .putString(KEY_LAST_USER, username)
            .putString(KEY_LAST_PROFILE, profileId)
            .putString(KEY_LAST_ACCOUNT, session.accountId)
            .putString(KEY_LAST_DEVICE, session.deviceId)
            .putString(KEY_LAST_SESSION, session.sessionId)
            .putInt(KEY_DEVICE_KEY_ENCODING, DEVICE_KEY_ENCODING_VERSION)
            // 只有用户显式认证成功才允许恢复；迟到的轮换落盘不能撤销退出屏障。
            .apply { if (explicitLogin) remove("signed_out_$profileId") }
            .commit()
        check(saved) { "PROFILE_PERSISTENCE_FAILED" }
        // 登录页预填地址：用户真正登录成功才记录，下次输地址不用重打一遍。
        LoginGatewayPrefill.remember(context, gatewayUrl)
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

    private fun existingLocalKey(alias: String) = object: com.openandroidintelligence.encrypted.store.AesGcmKeyProvider {
        override fun getOrCreate(): javax.crypto.SecretKey = java.security.KeyStore.getInstance("AndroidKeyStore")
            .apply { load(null) }.getKey(alias,null) as? javax.crypto.SecretKey ?: error("LOCAL_MEDIA_KEY_UNAVAILABLE")
        override fun delete(): Unit = error("LEGACY_MEDIA_READ_ONLY")
    }

    private fun attachmentStagingStore(gatewayId: String, accountId: String, installId: String,
        existingOnly: Boolean = false): LocalAttachmentStagingStore {
        val scopeId = "$gatewayId\n$accountId\n$installId"
        val scopeHash = MessageDigest.getInstance("SHA-256").digest(scopeId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val directory = File(context.noBackupFilesDir, "attachment-staging/$scopeHash")
        val keyAlias = "oai_attachment_${scopeHash.take(32)}"
        val provider = if (existingOnly) existingLocalKey(keyAlias) else AndroidKeystoreOutboxKeyProvider(keyAlias)
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
        const val WORKBENCH_RECOVERY_NOTICE = "读取本地会话失败，历史记录已保留，请重新连接后重试。"
        const val OFFLINE_MIRROR_NOTICE = "离线镜像：可阅读历史并编辑草稿，重新连接后可发送。"
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
            "Gateway 拒绝续期：App 与 Gateway 版本不一致，请把两端升级到同一版本后重试。"
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
