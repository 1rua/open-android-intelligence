package com.openandroidintelligence.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openandroidintelligence.gateway.http.TransportSecurity
import com.openandroidintelligence.gateway.account.AccountProfile
import com.openandroidintelligence.gateway.events.EventStreamStatus
import com.openandroidintelligence.kernel.AuditEvent
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.PairingGrantState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map

/**
 * 设置界面不可变 UI 状态。
 * 与 ViewModel 彻底解耦，便于独立预览与单元测试。
 */
data class SettingsUiState(
    val appearance: AppearanceSettings = AppearanceSettings(),
    val connectionPhase: ConnectionPhase = ConnectionPhase.Disconnected,
    val pairingGrants: PairingGrantState? = null,
    val isTrustModeEnabled: Boolean = false,
    val allowDeveloperTrustMode: Boolean = true,
    /** 渠道策略（ALLOW_RUNTIME_PLUGINS）：插件安装入口的可用性。 */
    val allowRuntimePlugins: Boolean = true,
    /** 宿主是否装配了插件运行时；false 时插件管理区域如实声明不可执行。 */
    val pluginRuntimesWired: Boolean = false,
    val isEmergencyStopped: Boolean = false,
    val emergencyStoppedCount: Int = 0,
    val auditEvents: List<AuditEvent> = emptyList(),
    val auditLines: List<String> = emptyList(),
    /** 会话级操作提示（续期/登出结果等）；null 表示没有要展示的提示。 */
    val operationNotice: String? = null,
    /** 「刷新网关凭据」是否正在进行，界面据此阻止重复触发。 */
    val isRefreshingSession: Boolean = false,
    val replyNotificationsWired: Boolean = false,
    val replyNotificationsEnabled: Boolean = false,
    val replyNotificationsAllowed: Boolean = false,
    val nativeAgentsState: NativeAgentsState? = null,
    val localSessionState: LocalSessionState = LocalSessionState.SignedOut(),
    val coreHttpHealth: CoreHttpHealth = CoreHttpHealth(),
    val eventStreamStatus: EventStreamStatus = EventStreamStatus.IDLE,
    val loginStatus: GatewayLoginStatus = GatewayLoginStatus.SIGNED_OUT,
    val savedProfiles: List<AccountProfile> = emptyList(),
    val isManagingProfiles: Boolean = false,
    val liveUpdatesAllowed: Boolean = false,
    val localError: String? = null,
    val unpairOperation: LocalOperationState = LocalOperationState.Idle,
    val accountPickerRequested: Boolean = false,
) {
    val isGatewayConnected: Boolean
        get() = connectionPhase is ConnectionPhase.Connected

    val hasLocalSession: Boolean
        get() = localSessionState is LocalSessionState.Workspace

    val connectedUsername: String?
        get() = (connectionPhase as? ConnectionPhase.Connected)?.username
            ?: (connectionPhase as? ConnectionPhase.OfflineMirror)?.username
            ?: (localSessionState as? LocalSessionState.Workspace)?.account?.username

    val connectedGatewayUrl: String?
        get() = (connectionPhase as? ConnectionPhase.Connected)?.gatewayUrl
            ?: (connectionPhase as? ConnectionPhase.OfflineMirror)?.gatewayUrl
            ?: (localSessionState as? LocalSessionState.Workspace)?.account?.gatewayBaseUrl

    val connectedTransportSecurity: TransportSecurity?
        get() = (connectionPhase as? ConnectionPhase.Connected)?.transportSecurity

    val pairingSummary: String?
        get() = (connectionPhase as? ConnectionPhase.Connected)?.pairingSummary

    val grantRevision: Long
        get() = pairingGrants?.revision ?: 0L

    val isNotificationsGranted: Boolean
        get() = replyNotificationsEnabled && replyNotificationsAllowed

    /**
     * 对话界面协商能力的三态投影：`true`=双方同意、`false`=客户端声明但
     * 网关未同意、`null`=双方未声明。键是契约 §4 的 8 项闭集，与网关是否
     * 返回无关——界面按闭集渲染，缺键即「未声明」。
     */
    val conversationUi: Map<String, Boolean?>
        get() {
            val phase = connectionPhase as? ConnectionPhase.Connected
            return negotiatedConversationUi(
                agreed = phase?.conversationUi ?: emptySet(),
                requested = phase?.requestedConversationUi ?: emptySet(),
            )
        }

    val isMessageBatchesAvailable: Boolean
        get() = conversationUi["message-batches-v1"] == true

    val isGenerationCancelAvailable: Boolean
        get() = conversationUi["generation-cancel-v1"] == true

    val isNewlineAvailable: Boolean
        get() = conversationUi["newline-v1"] == true

    val isMirrorAvailable: Boolean
        get() = conversationUi["conversation-mirror-v1"] == true

    val isAttachmentStatusAvailable: Boolean
        get() = conversationUi["attachment-status-v1"] == true

    /**
     * 设备请求通路的协商档（契约 §10）；null = 网关未提供。
     * 通路存在只说明执行能力就绪，触发源始终是网关下发的请求。
     */
    val deviceRequestChannel: String?
        get() = (connectionPhase as? ConnectionPhase.Connected)?.deviceRequests
}

private data class BaseSettings(
    val appearance: AppearanceSettings,
    val phase: ConnectionPhase,
    val localSession: LocalSessionState,
    val grants: PairingGrantState?,
    val auditEvents: List<AuditEvent>,
)

private data class ControlSettings(
    val trustEnabled: Boolean,
    val emergencyStopped: Boolean,
    val stoppedCount: Int,
    val replyEnabled: Boolean,
    val replyAllowed: Boolean,
)

/**
 * 设置界面专用 ViewModel。
 *
 * 职责：
 * 1. 统一汇聚来自 SharedPreferences（AppearancePreferences、PairingGrantPersistence）、
 *    GatewayRuntime、Kernel DeveloperTrustMode、PluginKernel 以及 AndroidAuditStore 的响应式流；
 * 2. 向上层 UI 提供纯净、强类型的 [SettingsUiState]；
 * 3. 处理外观偏好调节、设备能力授权增删、开发者信任模式开关确认、系统级紧急熔断与会话生命周期操作。
 */
class SettingsViewModel(
    private val environment: PlatformSettingsEnvironment,
    private val runtime: GatewayRuntime? = null,
    externalScope: CoroutineScope? = null,
    private val remoteRuntime: RemoteRuntime? = null,
) : ViewModel() {

    internal val settingsEnvironment get() = environment
    internal val gatewayRuntime get() = runtime
    private val scope: CoroutineScope = externalScope ?: viewModelScope

    private val _accountPickerRequested = MutableStateFlow(false)
    private val _localError = MutableStateFlow<String?>(null)
    private val _trustModeEnabled = MutableStateFlow(environment.trustMode.isEnabled())
    private val _emergencyStopped = MutableStateFlow(environment.kernel.isEmergencyStopped())
    // 计数的持久来源：Application 级持有者。面板销毁重建后从这里恢复，
    // 而不是从 0 起步把一次真实熔断说成没有发生。
    private val _stoppedCount = MutableStateFlow(environment.emergencyStoppedCount.value)

    private val trustRegistration = environment.trustMode.observeChanges { enabled ->
        _trustModeEnabled.value = enabled
    }
    private val stopRegistration = environment.kernel.observeEmergencyStop { stopped ->
        _emergencyStopped.value = stopped
    }

    override fun onCleared() {
        trustRegistration.close()
        stopRegistration.close()
        super.onCleared()
    }

    private val baseSettingsFlow = combine(
        environment.appearance.settings,
        runtime?.phase ?: MutableStateFlow(ConnectionPhase.Disconnected),
        runtime?.localSessionState ?: MutableStateFlow<LocalSessionState>(LocalSessionState.SignedOut()),
        environment.pairingGrants.state,
        environment.auditSink.eventsFlow,
    ) { appearance, phase, localSession, grants, auditEvents ->
        BaseSettings(appearance, phase, localSession, grants, auditEvents)
    }

    private val controlSettingsFlow = combine(
        _trustModeEnabled,
        _emergencyStopped,
        _stoppedCount,
        remoteRuntime?.replyNotifications?.enabled ?: runtime?.replyNotifications?.enabled ?: MutableStateFlow(false),
        remoteRuntime?.replyNotifications?.allowed ?: runtime?.replyNotifications?.allowed ?: MutableStateFlow(false),
    ) { trustEnabled, emergencyStopped, stoppedCount, replyEnabled, replyAllowed ->
        ControlSettings(trustEnabled, emergencyStopped, stoppedCount, replyEnabled, replyAllowed)
    }

    /** 会话级操作提示与续期进行中状态；runtime 缺席时保持空态。 */
    private val operationSettingsFlow = combine(
        runtime?.operationNotice ?: MutableStateFlow<String?>(null),
        runtime?.isRefreshingSession ?: MutableStateFlow(false),
    ) { notice, refreshing ->
        notice to refreshing
    }

    private val nativeAgentsFlow: StateFlow<NativeAgentsState?> =
        (runtime?.nativeAgents?.flatMapLatest { controller ->
            controller?.state ?: MutableStateFlow<NativeAgentsState?>(null)
        } ?: MutableStateFlow(null)).stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = runtime?.nativeAgents?.value?.state?.value,
        )

    private data class ConnectionFacts(
        val http: CoreHttpHealth,
        val events: EventStreamStatus,
        val login: GatewayLoginStatus,
        val profiles: List<AccountProfile>,
        val managing: Boolean,
    )

    private val connectionFacts = combine(
        runtime?.coreHttpHealth ?: MutableStateFlow(CoreHttpHealth()),
        runtime?.connectionStatus ?: MutableStateFlow(EventStreamStatus.IDLE),
        runtime?.loginStatus ?: MutableStateFlow(GatewayLoginStatus.SIGNED_OUT),
        runtime?.savedProfiles ?: MutableStateFlow(emptyList<AccountProfile>()),
        runtime?.isManagingProfiles ?: MutableStateFlow(false),
    ) { http, events, login, profiles, managing -> ConnectionFacts(http, events, login, profiles, managing) }

    private val baseUiState = combine(
        baseSettingsFlow,
        controlSettingsFlow,
        operationSettingsFlow,
        nativeAgentsFlow,
    ) { base, control, operations, nativeAgentsState ->
        SettingsUiState(
            appearance = base.appearance,
            connectionPhase = base.phase,
            localSessionState = base.localSession,
            pairingGrants = base.grants,
            isTrustModeEnabled = control.trustEnabled,
            allowDeveloperTrustMode = environment.allowDeveloperTrustMode,
            allowRuntimePlugins = environment.allowRuntimePlugins,
            pluginRuntimesWired = environment.pluginRuntimesWired,
            isEmergencyStopped = control.emergencyStopped,
            emergencyStoppedCount = control.stoppedCount,
            auditEvents = base.auditEvents,
            operationNotice = operations.first,
            isRefreshingSession = operations.second,
            replyNotificationsWired = remoteRuntime != null || runtime != null,
            replyNotificationsEnabled = control.replyEnabled,
            replyNotificationsAllowed = control.replyAllowed,
            nativeAgentsState = nativeAgentsState,
        )
    }

    private val auditPresentation = environment.auditSink.eventsFlow
        .map { events -> events.map { environment.audit.render(it) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val presentedBase = combine(baseUiState, _accountPickerRequested, auditPresentation) { base, requested, audit ->
        base.copy(accountPickerRequested = requested, auditLines = audit)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        presentedBase,
        connectionFacts,
        _localError,
        runtime?.unpairOperation ?: MutableStateFlow<LocalOperationState>(LocalOperationState.Idle),
        runtime?.replyNotifications?.liveUpdatesAllowed ?: MutableStateFlow(false),
    ) { base, facts, error, unpair, liveAllowed ->
        base.copy(
            coreHttpHealth = facts.http,
            eventStreamStatus = facts.events,
            loginStatus = facts.login,
            savedProfiles = facts.profiles,
            isManagingProfiles = facts.managing,
            localError = error,
            unpairOperation = unpair,
            liveUpdatesAllowed = liveAllowed,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState(
            appearance = environment.appearance.settings.value,
            connectionPhase = runtime?.phase?.value ?: ConnectionPhase.Disconnected,
            localSessionState = runtime?.localSessionState?.value ?: LocalSessionState.SignedOut(),
            pairingGrants = environment.pairingGrants.state.value,
            isTrustModeEnabled = environment.trustMode.isEnabled(),
            allowDeveloperTrustMode = environment.allowDeveloperTrustMode,
            allowRuntimePlugins = environment.allowRuntimePlugins,
            pluginRuntimesWired = environment.pluginRuntimesWired,
            isEmergencyStopped = environment.kernel.isEmergencyStopped(),
            emergencyStoppedCount = environment.emergencyStoppedCount.value,
            auditEvents = environment.auditSink.eventsFlow.value,
            auditLines = environment.auditSink.eventsFlow.value.map { environment.audit.render(it) },
            operationNotice = runtime?.operationNotice?.value,
            isRefreshingSession = runtime?.isRefreshingSession?.value ?: false,
            replyNotificationsWired = runtime != null,
            replyNotificationsEnabled = runtime?.replyNotifications?.enabled?.value ?: false,
            replyNotificationsAllowed = runtime?.replyNotifications?.allowed?.value ?: false,
            nativeAgentsState = runtime?.nativeAgents?.value?.state?.value,
            coreHttpHealth = runtime?.coreHttpHealth?.value ?: CoreHttpHealth(),
            eventStreamStatus = runtime?.connectionStatus?.value ?: EventStreamStatus.IDLE,
            loginStatus = runtime?.loginStatus?.value ?: GatewayLoginStatus.SIGNED_OUT,
            savedProfiles = runtime?.savedProfiles?.value.orEmpty(),
            isManagingProfiles = runtime?.isManagingProfiles?.value ?: false,
            liveUpdatesAllowed = runtime?.replyNotifications?.liveUpdatesAllowed?.value ?: false,
            unpairOperation = runtime?.unpairOperation?.value ?: LocalOperationState.Idle,
        ),
    )

    fun setTheme(theme: ThemePreference) {
        environment.appearance.setTheme(theme)
    }

    fun setDynamicColor(enabled: Boolean) {
        environment.appearance.setDynamicColor(enabled)
    }

    fun setDynamicIconColor(enabled: Boolean) {
        environment.appearance.setDynamicIconColor(enabled)
    }

    fun setPalette(palette: com.openandroidintelligence.conversation.theme.ThemePalette) {
        environment.appearance.selectPalette(palette)
    }

    fun setReduceMotion(enabled: Boolean) {
        environment.appearance.setReduceMotion(enabled)
    }

    fun setStreamingHapticFeedback(enabled: Boolean) {
        environment.appearance.setStreamingHapticFeedback(enabled)
    }

    fun setNotificationsGrant(enabled: Boolean): Boolean {
        val saved = (remoteRuntime?.replyNotifications ?: runtime?.replyNotifications)?.setEnabled(enabled) == true
        if (!saved) {
            _localError.value = "通知偏好未能保存，请检查手机存储后重试。"
            return false
        }
        if (enabled) (remoteRuntime?.nativeConversationNotifications ?: runtime?.nativeConversationNotifications)?.refreshActive()
        return true
    }

    fun dismissLocalError() { _localError.value = null }
    fun dismissUnpairOperation() { runtime?.dismissUnpairOperation() }
    fun selectSavedAccount(profileId: String) { runtime?.selectSavedAccount(profileId) }
    fun removeLocalAccount(profileId: String) { runtime?.removeLocalAccount(profileId) }
    fun retryConnection() { runtime?.retryConnection() }

    fun refreshReplyNotificationPermission() {
        (remoteRuntime?.replyNotifications ?: runtime?.replyNotifications)?.refreshPermission()
        runtime?.nativeConversationNotifications?.refreshActive()
    }
    fun shouldRequestReplyNotificationPermission(): Boolean = (remoteRuntime?.replyNotifications ?: runtime?.replyNotifications)?.shouldRequestPermission() == true
    fun markReplyNotificationPermissionRequested() { (remoteRuntime?.replyNotifications ?: runtime?.replyNotifications)?.markPermissionRequested() }

    fun enableTrustMode(
        acknowledgementText: String = DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT,
    ): Boolean {
        val accepted = environment.trustMode.enable(
            DeveloperTrustMode.Acknowledgement(acknowledgementText),
        )
        _trustModeEnabled.value = environment.trustMode.isEnabled()
        if (!environment.trustMode.lastPersistenceSucceeded) {
            _localError.value = "信任模式当前已改变，但未能保存；下次启动可能恢复原设置，请检查存储。"
        }
        return accepted
    }

    fun disableTrustMode() {
        environment.trustMode.disable()
        _trustModeEnabled.value = false
        if (!environment.trustMode.lastPersistenceSucceeded) {
            _localError.value = "信任模式当前已关闭，但关闭状态未能保存，请检查存储后重试。"
        }
    }

    fun emergencyStop(): Int {
        val count = environment.kernel.emergencyStop("emergency-" + System.currentTimeMillis())
        _stoppedCount.value = count
        environment.emergencyStoppedCount.value = count
        _emergencyStopped.value = true
        _trustModeEnabled.value = false
        if (!environment.trustMode.lastPersistenceSucceeded) {
            _localError.value = "本次紧急停止已生效，但信任模式关闭状态未能保存；重启前请检查存储并重新确认关闭状态。"
        }
        return count
    }

    fun refreshSession() {
        runtime?.refreshSession()
    }

    /** 关闭当前展示的操作提示；无 runtime 时提示本就不存在。 */
    fun dismissOperationNotice() {
        runtime?.dismissOperationNotice()
    }

    fun logout(revokeRefresh: Boolean) {
        runtime?.logout(revokeRefresh = revokeRefresh)
    }

    fun unpair() { runtime?.unpair() }
    fun chooseAnotherAccount() {
        if (runtime == null) return
        _accountPickerRequested.value = true
        runtime.chooseAnotherAccount()
        if (runtime.operationNotice.value != null && runtime.localSessionState.value !is LocalSessionState.AccountPicker) {
            _accountPickerRequested.value = false
        }
    }
    fun consumeAccountPickerRequest() { _accountPickerRequested.value = false }

    fun selectAgent(agentId: String) {
        val agents = runtime?.nativeAgents?.value
        if (agents == null || agents.state.value.busy) {
            _localError.value = "Agent 当前尚未就绪，请稍后再选择。"
            return
        }
        agents.selectAgent(agentId)
    }

    fun refreshAgents() {
        runtime?.nativeAgents?.value?.refresh()
    }
}
