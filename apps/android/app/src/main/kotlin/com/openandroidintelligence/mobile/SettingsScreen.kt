package com.openandroidintelligence.mobile

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openandroidintelligence.mobile.settings.SettingsNavHost
import androidx.compose.ui.res.stringResource
import com.openandroidintelligence.ui.resources.R as UiR

/** Stable route strings retained for existing entry points and saved navigation state. */
object SettingsRoutes {
    const val OVERVIEW = "settings/overview"
    const val APPEARANCE = "settings/appearance"
    const val GATEWAY = "settings/gateway"
    const val PAIRING = "settings/pairing"
    const val SECURITY = "settings/security"
    const val AUDIT_LOG = "settings/audit"
    const val PLUGINS = "settings/plugins"
    const val DEVICE_PUSH = "settings/device-push"
    const val NOTIFICATIONS = "settings/notifications"
    const val APP_UPDATES = "settings/app-updates"
    const val SCREEN_CAPABILITY = "settings/screen-capability"
    const val REMINDERS = "settings/reminders"
    const val ADVANCED = "settings/advanced"
    const val CONNECTION_DETAILS = "settings/connection-details"
    const val BUILD_DETAILS = "settings/build-details"
    const val LICENSES = "settings/licenses"
    const val BACKUP = "settings/backup"
    internal const val CAPABILITY = "settings/capability/{pluginId}/{capability}"
}

/** SIDEBAR system insets belong to its existing host, not to each settings page. */
enum class SettingsPresentation { SCREEN, SIDEBAR }

@Composable
fun SettingsScreen(
    environment: PlatformSettingsEnvironment,
    onBack: () -> Unit,
    runtime: GatewayRuntime? = null,
    modifier: Modifier = Modifier,
    presentation: SettingsPresentation = SettingsPresentation.SCREEN,
    onRequestScreenCaptureAuthorization: () -> Unit = {},
    remoteRuntime: RemoteRuntime? = null,
) {
    val factory = remember(environment, runtime, remoteRuntime) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(environment, runtime, remoteRuntime = remoteRuntime) as T
        }
    }
    val model: SettingsViewModel = viewModel(key = "platform-settings", factory = factory)
    SettingsNavHost(model, environment, runtime, onBack, modifier, presentation, onRequestScreenCaptureAuthorization, remoteRuntime)
}

/** Kept for callers and tests that inject the existing authoritative state owner. */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    presentation: SettingsPresentation = SettingsPresentation.SCREEN,
    onRequestScreenCaptureAuthorization: () -> Unit = {},
) = SettingsNavHost(viewModel, viewModel.settingsEnvironment, viewModel.gatewayRuntime, onBack, modifier, presentation, onRequestScreenCaptureAuthorization, viewModel.remoteSettingsRuntime)

/**
 * 兼容入口：主动推送已不再是用户可达的独立页面，配置挂在「通知读取」与「短信」详情里。
 * 保留这个名字只是为了既有响应式/返回栈回归仍能在同一承载位置验证这两段内容。
 */
@Composable
internal fun DevicePushSubScreen(
    runtime: com.openandroidintelligence.mobile.push.DevicePushRuntime?,
    endpoints: List<com.openandroidintelligence.mobile.push.PushEndpointOption>,
    onRefreshEndpoints: () -> Unit,
    onBack: () -> Unit,
    presentation: SettingsPresentation,
) {
    if (runtime == null) {
        androidx.compose.foundation.layout.Box(Modifier) {
            androidx.compose.material3.Text(stringResource(UiR.string.settings_push_unavailable))
        }
        return
    }
    com.openandroidintelligence.mobile.settings.SettingsPageScaffold(stringResource(UiR.string.settings_push_title), presentation, onBack) { padding ->
        com.openandroidintelligence.mobile.push.PushSettingsScreen(
            runtime = runtime,
            endpoints = endpoints,
            onRefreshEndpoints = onRefreshEndpoints,
            modifier = Modifier.padding(padding),
            presentation = presentation,
        )
    }
}
