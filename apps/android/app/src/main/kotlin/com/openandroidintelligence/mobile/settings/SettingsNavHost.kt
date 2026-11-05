package com.openandroidintelligence.mobile.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.openandroidintelligence.conversation.components.SettingsNavigationItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.motion.AppTransitions
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.mobile.GatewayRuntime
import com.openandroidintelligence.mobile.LocalOperationState
import com.openandroidintelligence.mobile.LocalSessionState
import com.openandroidintelligence.mobile.OpenAndroidIntelligenceApplication
import com.openandroidintelligence.mobile.PlatformSettingsEnvironment
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.SettingsRoutes
import com.openandroidintelligence.mobile.SettingsViewModel
import com.openandroidintelligence.mobile.plugins.PluginManagementScreen
import com.openandroidintelligence.mobile.settings.appearance.AppearanceSettingsScreen
import com.openandroidintelligence.mobile.settings.advanced.AdvancedSettingsScreen
import com.openandroidintelligence.mobile.settings.advanced.BuildDetailsScreen
import com.openandroidintelligence.mobile.settings.advanced.OpenSourceNoticesScreen
import com.openandroidintelligence.mobile.settings.capabilities.DeviceCapabilitiesScreen
import com.openandroidintelligence.mobile.settings.capabilities.CapabilitySettingsScreen
import com.openandroidintelligence.mobile.settings.connection.ConnectionSettingsScreen
import com.openandroidintelligence.mobile.settings.connection.ConnectionDiagnosticsScreen
import com.openandroidintelligence.mobile.settings.notifications.NotificationSettingsScreen
import com.openandroidintelligence.mobile.settings.security.SecuritySettingsScreen
import com.openandroidintelligence.mobile.settings.security.AuditSettingsScreen
import com.openandroidintelligence.mobile.updates.AppUpdateRoute
import com.openandroidintelligence.ui.design.LocalMotionPolicy

@Composable
internal fun SettingsNavHost(
    viewModel: SettingsViewModel,
    environment: PlatformSettingsEnvironment,
    runtime: GatewayRuntime?,
    onBack: () -> Unit,
    modifier: Modifier,
    presentation: SettingsPresentation,
    onRequestScreenCaptureAuthorization: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val context = LocalContext.current
    val app = context.applicationContext as? OpenAndroidIntelligenceApplication
    val reduce = LocalMotionPolicy.current.reduceMotion
    val snackbar = remember { SnackbarHostState() }
    var trustDialog by rememberSaveable { mutableStateOf(false) }
    var emergencyDialog by rememberSaveable { mutableStateOf(false) }
    var unpairDialog by rememberSaveable { mutableStateOf(false) }
    var navigationError by remember { mutableStateOf<String?>(null) }
    var shortcutSources by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var shortcutTargets by rememberSaveable { mutableStateOf(emptyList<String>()) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshReplyNotificationPermission()
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(viewModel, lifecycle) {
        viewModel.refreshReplyNotificationPermission()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshReplyNotificationPermission()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(uiState.operationNotice) {
        uiState.operationNotice?.let { snackbar.showSnackbar(it, withDismissAction = true); viewModel.dismissOperationNotice() }
    }
    LaunchedEffect(uiState.localError, navigationError) {
        val message = uiState.localError ?: navigationError ?: return@LaunchedEffect
        snackbar.showSnackbar(message, withDismissAction = true)
        viewModel.dismissLocalError()
        navigationError = null
    }
    LaunchedEffect(uiState.accountPickerRequested, uiState.localSessionState) {
        if (uiState.accountPickerRequested && uiState.localSessionState is LocalSessionState.AccountPicker) {
            viewModel.consumeAccountPickerRequest()
            onBack()
        }
    }

    fun currentRoute(): String? {
        val destination = nav.currentBackStackEntry ?: return null
        return if (destination.destination.route == SettingsRoutes.CAPABILITY) capabilitySettingsRoute(
            destination.arguments?.getString("pluginId").orEmpty(), destination.arguments?.getString("capability").orEmpty(),
        ) else destination.destination.route
    }
    fun open(requestedRoute: String) {
        val route = canonicalSettingsRoute(requestedRoute)
        if (currentRoute() == route) return
        val current = currentRoute()
        val owner = settingsOwner(route)
        val returningToSource = shortcutSources.lastOrNull() == route
        val fromDetail = current != null && settingsOwner(current) !in setOf(null, SettingsRoutes.OVERVIEW)
        if (fromDetail) {
            // Save the peer entry before replacing it, so returning restores its draft.
            val updated = SettingsShortcutHistory(shortcutSources, shortcutTargets).open(current, route)
            shortcutSources = updated.sources
            shortcutTargets = updated.targets
            nav.popBackStack(nav.currentDestination!!.id, inclusive = true, saveState = true)
        }
        if (owner != null && owner != SettingsRoutes.OVERVIEW && nav.currentDestination?.route != owner) {
            nav.navigate(owner) { launchSingleTop = true; popUpTo(SettingsRoutes.OVERVIEW) }
        }
        nav.navigate(route) { launchSingleTop = true; restoreState = returningToSource }
    }
    fun back() {
        val history = SettingsShortcutHistory(shortcutSources, shortcutTargets)
        val source = currentRoute()?.let(history::sourceFor)
        if (source != null) {
            val updated = history.pop()
            shortcutSources = updated.sources
            shortcutTargets = updated.targets
            nav.popBackStack()
            val owner = settingsOwner(source)
            if (owner != null && owner != SettingsRoutes.OVERVIEW && nav.currentDestination?.route != owner) {
                nav.navigate(owner) { launchSingleTop = true; popUpTo(SettingsRoutes.OVERVIEW) }
            }
            nav.navigate(source) { launchSingleTop = true; restoreState = true }
        } else if (!nav.popBackStack()) onBack()
    }
    fun manageAccounts() {
        if (runtime == null) {
            navigationError = "当前未接入账号管理"
            return
        }
        if (uiState.localSessionState is LocalSessionState.AccountPicker || !uiState.hasLocalSession) {
            onBack()
        } else {
            viewModel.chooseAnotherAccount()
        }
    }
    fun setNotifications(value: Boolean) {
        if (!viewModel.setNotificationsGrant(value)) return
        if (value && viewModel.shouldRequestReplyNotificationPermission()) {
            viewModel.markReplyNotificationPermissionRequested()
            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val rootBackProgress = remember { Animatable(0f) }
    PredictiveBackHandler(enabled = entry?.destination?.route == SettingsRoutes.OVERVIEW && presentation == SettingsPresentation.SCREEN) { events ->
        try {
            events.collect { event -> rootBackProgress.snapTo(if (reduce) 0f else event.progress) }
            onBack()
        } catch (cancelled: CancellationException) {
            // A cancelled system gesture must restore the still-open panel.
            throw cancelled
        } finally { withContext(NonCancellable) { rootBackProgress.snapTo(0f) } }
    }
    BackHandler(enabled = entry?.destination?.route == SettingsRoutes.OVERVIEW && presentation == SettingsPresentation.SIDEBAR, onBack = onBack)
    Box(modifier.fillMaxSize().graphicsLayer { translationX = -size.width * rootBackProgress.value }) {
        NavHost(
            navController = nav,
            startDestination = SettingsRoutes.OVERVIEW,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { AppTransitions.navEnter(reduce) },
            exitTransition = { AppTransitions.navExit(reduce) },
            popEnterTransition = { AppTransitions.navPopEnter(reduce) },
            popExitTransition = { AppTransitions.navPopExit(reduce) },
        ) {
            composable(SettingsRoutes.OVERVIEW) {
                SettingsPageScaffold("设置", presentation, onBack, closeSemantics = presentation == SettingsPresentation.SIDEBAR) { padding ->
                    SettingsPageColumn(padding) {
                        SettingsCategory.entries.forEach { category ->
                            SettingsSectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues()) {
                                SettingsNavigationItem(
                                    category.label,
                                    supporting = category.summary,
                                    icon = when (category) {
                                        SettingsCategory.APPEARANCE -> Icons.Default.Palette
                                        SettingsCategory.CONNECTION -> Icons.Default.Hub
                                        SettingsCategory.NOTIFICATIONS -> Icons.Default.Notifications
                                        SettingsCategory.CAPABILITIES -> Icons.Default.Extension
                                        SettingsCategory.SECURITY -> Icons.Default.Security
                                        SettingsCategory.ADVANCED -> Icons.Default.Info
                                    },
                                    onClick = { open(category.route) },
                                )
                            }
                        }
                    }
                }
            }
            composable(SettingsRoutes.APPEARANCE) {
                AppearanceSettingsScreen(uiState, presentation, ::back, viewModel::setTheme, viewModel::setDynamicColor,
                    viewModel::setDynamicIconColor, viewModel::setPalette, viewModel::setReduceMotion)
            }
            composable(SettingsRoutes.GATEWAY) {
                ConnectionSettingsScreen(uiState, presentation, ::back,
                    onOpenDiagnostics = { open(SettingsRoutes.CONNECTION_DETAILS) },
                    onSelectAgent = viewModel::selectAgent, onRefreshAgents = viewModel::refreshAgents,
                    onSelectAccount = viewModel::selectSavedAccount, onManageAccounts = { manageAccounts() },
                    onAddAccount = { manageAccounts() }, onRefreshSession = viewModel::refreshSession,
                    onLogout = { viewModel.logout(true) }, onRemoveAccount = viewModel::removeLocalAccount,
                    onRequestUnpair = { viewModel.dismissUnpairOperation(); unpairDialog = true })
            }
            composable(SettingsRoutes.CONNECTION_DETAILS) { ConnectionDiagnosticsScreen(uiState, presentation, ::back) }
            composable(SettingsRoutes.REMINDERS) { NotificationSettingsScreen(uiState, presentation, ::back, ::setNotifications) }
            composable(SettingsRoutes.PAIRING) {
                DeviceCapabilitiesScreen(environment, runtime, presentation, ::back,
                    onOpenCapability = { plugin, capability -> open(capabilitySettingsRoute(plugin, capability)) },
                    onOpenPush = { open(SettingsRoutes.DEVICE_PUSH) }, onOpenPlugins = { open(SettingsRoutes.PLUGINS) })
            }
            composable(SettingsRoutes.CAPABILITY, arguments = listOf(navArgument("pluginId") { type = NavType.StringType }, navArgument("capability") { type = NavType.StringType })) { destination ->
                CapabilitySettingsScreen(destination.arguments?.getString("pluginId").orEmpty(), destination.arguments?.getString("capability").orEmpty(),
                    environment, runtime, presentation, ::back, { open(SettingsRoutes.PLUGINS) }, onRequestScreenCaptureAuthorization)
            }
            composable(SettingsRoutes.SCREEN_CAPABILITY) {
                CapabilitySettingsScreen("org.openandroidintelligence.screen-reader", "org.openandroidintelligence.screen-reader.read",
                    environment, runtime, presentation, ::back, { open(SettingsRoutes.PLUGINS) }, onRequestScreenCaptureAuthorization,
                    showAllScreenCapabilities = true)
            }
            composable(SettingsRoutes.PLUGINS) {
                PluginManagementScreen(uiState.allowRuntimePlugins, uiState.pluginRuntimesWired, ::back,
                    onOpenNotificationSettings = { open(SettingsRoutes.NOTIFICATIONS) }, presentation = presentation,
                    onOpenCapability = { plugin, capability -> open(capabilitySettingsRoute(plugin, capability)) },
                    onOpenScreen = { open(SettingsRoutes.SCREEN_CAPABILITY) }, onOpenAuthorizations = { open(SettingsRoutes.PAIRING) })
            }
            composable(SettingsRoutes.NOTIFICATIONS) {
                LaunchedEffect(Unit) {
                    nav.navigate(notificationReadingRoute) {
                        popUpTo(SettingsRoutes.NOTIFICATIONS) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            composable(SettingsRoutes.DEVICE_PUSH) {
                val push = app?.devicePush
                val endpoints = push?.endpoints?.collectAsStateWithLifecycle()?.value.orEmpty()
                DevicePushSettingsPage(push?.runtime, endpoints, { push?.refreshEndpoints() }, ::back, presentation)
            }
            composable(SettingsRoutes.SECURITY) {
                SecuritySettingsScreen(uiState, presentation, ::back,
                    onToggleTrustMode = { if (it) trustDialog = true else viewModel.disableTrustMode() },
                    onOpenAudit = { open(SettingsRoutes.AUDIT_LOG) }, onRequestEmergencyStop = { emergencyDialog = true })
            }
            composable(SettingsRoutes.AUDIT_LOG) { AuditSettingsScreen(uiState, presentation, ::back) }
            composable(SettingsRoutes.ADVANCED) {
                AdvancedSettingsScreen(uiState, runtime, presentation, ::back,
                    onOpenUpdates = { open(SettingsRoutes.APP_UPDATES) }, onOpenBuildDetails = { open(SettingsRoutes.BUILD_DETAILS) },
                    onOpenLicenses = { open(SettingsRoutes.LICENSES) }, onReplayOnboarding = { app?.onboardingPreferences?.startReplay(); onBack() })
            }
            composable(SettingsRoutes.APP_UPDATES) { AppUpdateRoute(onBack = ::back, presentation = presentation) }
            composable(SettingsRoutes.BUILD_DETAILS) { BuildDetailsScreen(presentation, ::back) }
            composable(SettingsRoutes.LICENSES) { OpenSourceNoticesScreen(presentation, ::back) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    // Register after NavHost only for same-depth shortcuts; normal predictive back stays with NavHost.
    BackHandler(enabled = shortcutSources.isNotEmpty()) { back() }
    if (trustDialog) AlertDialog(onDismissRequest = { trustDialog = false }, title = { Text("开启开发者信任模式？") },
        text = { Text("原生插件将使用本应用的权限并访问全部数据。请仅在信任插件来源时开启。\n\n${DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT}") },
        confirmButton = { TextButton(onClick = { viewModel.enableTrustMode(); trustDialog = false }) { Text("我已知晓并开启") } },
        dismissButton = { TextButton(onClick = { trustDialog = false }) { Text("取消") } })
    if (emergencyDialog) AlertDialog(onDismissRequest = { emergencyDialog = false }, title = { Text("紧急停止所有插件？") },
        text = { Text("将立即停止所有插件并撤销临时授权，需重启应用恢复。保存的设置与系统权限不变。") },
        confirmButton = { TextButton(onClick = { viewModel.emergencyStop(); emergencyDialog = false }) { Text("确认停止", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { emergencyDialog = false }) { Text("取消") } })
    if (unpairDialog || uiState.unpairOperation !is LocalOperationState.Idle) {
        val operation = uiState.unpairOperation
        val running = operation is LocalOperationState.Running
        AlertDialog(onDismissRequest = { if (!running) { unpairDialog = false; viewModel.dismissUnpairOperation() } },
            title = { Text(if (operation is LocalOperationState.Completed) "设备配对已解除" else "解除设备配对？") },
            text = { Text(when (operation) {
                LocalOperationState.Idle -> "将取消设备配对并断开连接，清理手机上的本地聊天与缓存数据。系统权限与全局功能设置保留。"
                LocalOperationState.Running -> "正在请求取消配对，请稍候…"
                LocalOperationState.Completed -> "配对已解除，手机端数据已清理。"
                is LocalOperationState.Failed -> operation.message
            }) },
            confirmButton = { TextButton(enabled = !running, onClick = {
                if (operation is LocalOperationState.Completed || operation is LocalOperationState.Failed && operation.remoteCompleted) {
                    unpairDialog = false; viewModel.dismissUnpairOperation()
                } else viewModel.unpair()
            }) { Text(if (operation is LocalOperationState.Completed || operation is LocalOperationState.Failed && operation.remoteCompleted) "知道了" else "确认解除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { if (!running && operation !is LocalOperationState.Completed) TextButton(onClick = { unpairDialog = false; viewModel.dismissUnpairOperation() }) { Text("取消") } })
    }
}

@Composable
internal fun DevicePushSettingsPage(
    runtime: com.openandroidintelligence.mobile.push.DevicePushRuntime?,
    endpoints: List<com.openandroidintelligence.mobile.push.PushEndpointOption>,
    onRefreshEndpoints: () -> Unit,
    onBack: () -> Unit,
    presentation: SettingsPresentation,
) {
    SettingsPageScaffold("短信与通知主动推送", presentation, onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (runtime != null) com.openandroidintelligence.mobile.push.PushSettingsScreen(runtime, endpoints, onRefreshEndpoints, presentation = presentation)
            else Text("本机加密队列暂不可用，请检查设备存储和密钥状态。")
        }
    }
}
