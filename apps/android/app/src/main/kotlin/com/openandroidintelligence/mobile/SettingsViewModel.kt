package com.openandroidintelligence.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openandroidintelligence.gateway.http.TransportSecurity
import com.openandroidintelligence.kernel.AuditEvent
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.PairingGrantCapabilities
import com.openandroidintelligence.kernel.PairingGrantState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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
) {
    val isGatewayConnected: Boolean
        get() = connectionPhase is ConnectionPhase.Connected

    val connectedUsername: String?
        get() = (connectionPhase as? ConnectionPhase.Connected)?.username

    val connectedGatewayUrl: String?
        get() = (connectionPhase as? ConnectionPhase.Connected)?.gatewayUrl

    val connectedTransportSecurity: TransportSecurity?
        get() = (connectionPhase as? ConnectionPhase.Connected)?.transportSecurity

    val pairingSummary: String?
        get() = (connectionPhase as? ConnectionPhase.Connected)?.pairingSummary

    val grantRevision: Long
        get() = pairingGrants?.revision ?: 0L

    val isSmsGranted: Boolean
        get() = pairingGrants?.granted?.contains(PairingGrantCapabilities.SMS) == true

    val isScreenSelectionGranted: Boolean
        get() = pairingGrants?.screenSelectionEnabled == true

    val isNotificationsGranted: Boolean
        get() = pairingGrants?.granted?.contains(PairingGrantCapabilities.NOTIFICATIONS) == true

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
    val grants: PairingGrantState?,
    val auditEvents: List<AuditEvent>,
)

private data class ControlSettings(
    val trustEnabled: Boolean,
    val emergencyStopped: Boolean,
    val stoppedCount: Int,
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
) : ViewModel() {

    private val scope: CoroutineScope = externalScope ?: viewModelScope

    private val _trustModeEnabled = MutableStateFlow(environment.trustMode.isEnabled())
    private val _emergencyStopped = MutableStateFlow(environment.kernel.isEmergencyStopped())
    // 计数的持久来源：Application 级持有者。面板销毁重建后从这里恢复，
    // 而不是从 0 起步把一次真实熔断说成没有发生。
    private val _stoppedCount = MutableStateFlow(environment.emergencyStoppedCount.value)

    init {
        environment.trustMode.onChange { enabled ->
            _trustModeEnabled.value = enabled
        }
        environment.kernel.onEmergencyStop { stopped ->
            _emergencyStopped.value = stopped
        }
    }

    private val baseSettingsFlow = combine(
        environment.appearance.settings,
        runtime?.phase ?: MutableStateFlow(ConnectionPhase.Disconnected),
        environment.pairingGrants.state,
        environment.auditSink.eventsFlow,
    ) { appearance, phase, grants, auditEvents ->
        BaseSettings(appearance, phase, grants, auditEvents)
    }

    private val controlSettingsFlow = combine(
        _trustModeEnabled,
        _emergencyStopped,
        _stoppedCount,
    ) { trustEnabled, emergencyStopped, stoppedCount ->
        ControlSettings(trustEnabled, emergencyStopped, stoppedCount)
    }

    /** 会话级操作提示与续期进行中状态；runtime 缺席时保持空态。 */
    private val operationSettingsFlow = combine(
        runtime?.operationNotice ?: MutableStateFlow<String?>(null),
        runtime?.isRefreshingSession ?: MutableStateFlow(false),
    ) { notice, refreshing ->
        notice to refreshing
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        baseSettingsFlow,
        controlSettingsFlow,
        operationSettingsFlow,
    ) { base, control, operations ->
        SettingsUiState(
            appearance = base.appearance,
            connectionPhase = base.phase,
            pairingGrants = base.grants,
            isTrustModeEnabled = control.trustEnabled,
            allowDeveloperTrustMode = environment.allowDeveloperTrustMode,
            allowRuntimePlugins = environment.allowRuntimePlugins,
            pluginRuntimesWired = environment.pluginRuntimesWired,
            isEmergencyStopped = control.emergencyStopped,
            emergencyStoppedCount = control.stoppedCount,
            auditEvents = base.auditEvents,
            auditLines = base.auditEvents.map { environment.audit.render(it) },
            operationNotice = operations.first,
            isRefreshingSession = operations.second,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState(
            appearance = environment.appearance.settings.value,
            connectionPhase = runtime?.phase?.value ?: ConnectionPhase.Disconnected,
            pairingGrants = environment.pairingGrants.state.value,
            isTrustModeEnabled = environment.trustMode.isEnabled(),
            allowDeveloperTrustMode = environment.allowDeveloperTrustMode,
            allowRuntimePlugins = environment.allowRuntimePlugins,
            pluginRuntimesWired = environment.pluginRuntimesWired,
            isEmergencyStopped = environment.kernel.isEmergencyStopped(),
            emergencyStoppedCount = 0,
            auditEvents = environment.auditSink.eventsFlow.value,
            auditLines = environment.auditSink.eventsFlow.value.map { environment.audit.render(it) },
            operationNotice = runtime?.operationNotice?.value,
            isRefreshingSession = runtime?.isRefreshingSession?.value ?: false,
        ),
    )

    fun setTheme(theme: ThemePreference) {
        environment.appearance.setTheme(theme)
    }

    fun setDynamicColor(enabled: Boolean) {
        environment.appearance.setDynamicColor(enabled)
    }

    fun setReduceMotion(enabled: Boolean) {
        environment.appearance.setReduceMotion(enabled)
    }

    fun setSmsGrant(enabled: Boolean) {
        environment.pairingGrants.updatePrimitive(PairingGrantCapabilities.SMS, enabled)
    }

    fun setScreenSelectionGrant(enabled: Boolean) {
        environment.pairingGrants.updateScreenSelection(enabled)
    }

    fun setNotificationsGrant(enabled: Boolean) {
        environment.pairingGrants.updatePrimitive(PairingGrantCapabilities.NOTIFICATIONS, enabled)
    }

    fun enableTrustMode(
        acknowledgementText: String = DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT,
    ): Boolean {
        val accepted = environment.trustMode.enable(
            DeveloperTrustMode.Acknowledgement(acknowledgementText),
        )
        _trustModeEnabled.value = accepted
        return accepted
    }

    fun disableTrustMode() {
        environment.trustMode.disable()
        _trustModeEnabled.value = false
    }

    fun emergencyStop(): Int {
        val count = environment.kernel.emergencyStop("emergency-" + System.currentTimeMillis())
        _stoppedCount.value = count
        environment.emergencyStoppedCount.value = count
        _emergencyStopped.value = true
        _trustModeEnabled.value = false
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
}

