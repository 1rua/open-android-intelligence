package com.openandroidintelligence.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openandroidintelligence.kernel.AuditEvent
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.PairingGrantState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

/** 仅呈现真实本机设置；Remote 账号与连接由独立连接页面持有。 */
data class SettingsUiState(
    val appearance: AppearanceSettings = AppearanceSettings(),
    val pairingGrants: PairingGrantState? = null,
    val isTrustModeEnabled: Boolean = false,
    val allowDeveloperTrustMode: Boolean = true,
    val allowRuntimePlugins: Boolean = true,
    val pluginRuntimesWired: Boolean = false,
    val isEmergencyStopped: Boolean = false,
    val emergencyStoppedCount: Int = 0,
    val auditEvents: List<AuditEvent> = emptyList(),
    val auditLines: List<String> = emptyList(),
    val replyNotificationsWired: Boolean = false,
    val replyNotificationsEnabled: Boolean = false,
    val replyNotificationsAllowed: Boolean = false,
    val liveUpdatesAllowed: Boolean = false,
    val localError: String? = null,
) {
    val grantRevision: Long get() = pairingGrants?.revision ?: 0L
    val isNotificationsGranted: Boolean get() = replyNotificationsEnabled && replyNotificationsAllowed
}

/** 本机偏好、内核状态与 Remote 共用通知偏好的唯一设置投影。 */
class SettingsViewModel(
    private val environment: PlatformSettingsEnvironment,
    internal val localSettingsRuntime: LocalSettingsRuntime? = null,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    internal val settingsEnvironment get() = environment
    private val scope = externalScope ?: viewModelScope
    private val _localError = MutableStateFlow<String?>(null)
    /** 独立暴露本地保存错误流，供设置宿主轻量订阅 Snackbar 而不触发全量 uiState 重组。 */
    val localError: StateFlow<String?> = _localError.asStateFlow()
    private val _trustModeEnabled = MutableStateFlow(environment.trustMode.isEnabled())
    private val _emergencyStopped = MutableStateFlow(environment.kernel.isEmergencyStopped())
    private val _stoppedCount = MutableStateFlow(environment.emergencyStoppedCount.value)
    private val trustRegistration = environment.trustMode.observeChanges { _trustModeEnabled.value = it }
    private val stopRegistration = environment.kernel.observeEmergencyStop { _emergencyStopped.value = it }
    override fun onCleared() { trustRegistration.close(); stopRegistration.close(); super.onCleared() }

    private fun snapshot() = SettingsUiState(
        appearance = environment.appearance.settings.value,
        pairingGrants = environment.pairingGrants.state.value,
        isTrustModeEnabled = _trustModeEnabled.value,
        allowDeveloperTrustMode = environment.allowDeveloperTrustMode,
        allowRuntimePlugins = environment.allowRuntimePlugins,
        pluginRuntimesWired = environment.pluginRuntimesWired,
        isEmergencyStopped = _emergencyStopped.value,
        emergencyStoppedCount = _stoppedCount.value,
        auditEvents = environment.auditSink.eventsFlow.value,
        auditLines = environment.auditSink.eventsFlow.value.map(environment.audit::render),
        replyNotificationsWired = localSettingsRuntime != null,
        replyNotificationsEnabled = localSettingsRuntime?.replyNotifications?.enabled?.value ?: false,
        replyNotificationsAllowed = localSettingsRuntime?.replyNotifications?.allowed?.value ?: false,
        liveUpdatesAllowed = localSettingsRuntime?.replyNotifications?.liveUpdatesAllowed?.value ?: false,
        localError = _localError.value,
    )
    private val localChanges = combine(environment.appearance.settings, environment.pairingGrants.state,
        environment.auditSink.eventsFlow, _trustModeEnabled, _emergencyStopped) { _, _, _, _, _ -> Unit }
    private val notificationChanges = combine(
        localSettingsRuntime?.replyNotifications?.enabled ?: MutableStateFlow(false),
        localSettingsRuntime?.replyNotifications?.allowed ?: MutableStateFlow(false),
        localSettingsRuntime?.replyNotifications?.liveUpdatesAllowed ?: MutableStateFlow(false),
    ) { _, _, _ -> Unit }
    val uiState: StateFlow<SettingsUiState> = combine(localChanges, notificationChanges, _localError, _stoppedCount) { _, _, _, _ -> snapshot() }
        .stateIn(scope, SharingStarted.Eagerly, snapshot())

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
        val saved = localSettingsRuntime?.replyNotifications?.setEnabled(enabled) == true
        if (!saved) {
            _localError.value = "通知偏好未能保存，请检查手机存储后重试。"
            return false
        }
        if (enabled) localSettingsRuntime?.refreshNotifications?.invoke()
        return true
    }

    fun dismissLocalError() { _localError.value = null }

    fun refreshReplyNotificationPermission() {
        localSettingsRuntime?.replyNotifications?.refreshPermission()
        localSettingsRuntime?.refreshNotifications?.invoke()
    }
    fun shouldRequestReplyNotificationPermission(): Boolean = localSettingsRuntime?.replyNotifications?.shouldRequestPermission() == true
    fun markReplyNotificationPermissionRequested() { localSettingsRuntime?.replyNotifications?.markPermissionRequested() }

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

}
