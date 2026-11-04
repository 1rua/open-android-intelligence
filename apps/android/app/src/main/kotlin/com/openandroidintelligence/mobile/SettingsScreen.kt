package com.openandroidintelligence.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openandroidintelligence.mobile.settings.SettingsNavHost

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
) {
    val factory = remember(environment, runtime) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(environment, runtime) as T
        }
    }
    val model: SettingsViewModel = viewModel(key = "platform-settings", factory = factory)
    SettingsNavHost(model, environment, runtime, onBack, modifier, presentation, onRequestScreenCaptureAuthorization)
}

/** Kept for callers and tests that inject the existing authoritative state owner. */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    presentation: SettingsPresentation = SettingsPresentation.SCREEN,
    onRequestScreenCaptureAuthorization: () -> Unit = {},
) = SettingsNavHost(viewModel, viewModel.settingsEnvironment, viewModel.gatewayRuntime, onBack, modifier, presentation, onRequestScreenCaptureAuthorization)

/** Compatibility entry used by responsive-host regressions. */
@Composable
internal fun DevicePushSubScreen(
    runtime: com.openandroidintelligence.mobile.push.DevicePushRuntime?,
    endpoints: List<com.openandroidintelligence.mobile.push.PushEndpointOption>,
    onRefreshEndpoints: () -> Unit,
    onBack: () -> Unit,
    presentation: SettingsPresentation,
) = com.openandroidintelligence.mobile.settings.DevicePushSettingsPage(runtime, endpoints, onRefreshEndpoints, onBack, presentation)
