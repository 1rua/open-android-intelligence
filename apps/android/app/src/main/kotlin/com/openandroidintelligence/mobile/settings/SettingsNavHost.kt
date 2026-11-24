package com.openandroidintelligence.mobile.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.openandroidintelligence.conversation.components.SettingsNavigationItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.motion.AppTransitions
import com.openandroidintelligence.conversation.motion.LocalPredictiveOverlayBackController
import com.openandroidintelligence.conversation.motion.NavPageTransitionRole
import com.openandroidintelligence.conversation.motion.PredictiveBackOverlayBridge
import com.openandroidintelligence.conversation.motion.ProvideSharedVisibilityScope
import com.openandroidintelligence.conversation.motion.navPageTransitionDecoration
import com.openandroidintelligence.conversation.motion.predictiveBackSlideTransform
import com.openandroidintelligence.conversation.motion.rememberPredictiveBackState
import com.openandroidintelligence.kernel.DeveloperTrustMode
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
import com.openandroidintelligence.mobile.settings.backup.SettingsBackupRoute
import com.openandroidintelligence.mobile.settings.capabilities.DeviceCapabilitiesScreen
import com.openandroidintelligence.mobile.settings.capabilities.CapabilitySettingsScreen
import com.openandroidintelligence.mobile.settings.notifications.NotificationSettingsScreen
import com.openandroidintelligence.mobile.settings.security.SecuritySettingsScreen
import com.openandroidintelligence.mobile.settings.security.AuditSettingsScreen
import com.openandroidintelligence.mobile.updates.AppUpdateRoute
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.isReduced
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val OverviewCardPadding = PaddingValues()

@Composable
internal fun SettingsNavHost(
    viewModel: SettingsViewModel,
    environment: PlatformSettingsEnvironment,
    onBack: () -> Unit,
    modifier: Modifier,
    presentation: SettingsPresentation,
    onRequestScreenCaptureAuthorization: () -> Unit,
    remoteRuntime: com.openandroidintelligence.mobile.RemoteRuntime? = null,
    hostOpen: Boolean = true,
) {
    val localError by remember(viewModel) {
        viewModel.uiState.map { it.localError }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = viewModel.uiState.value.localError)
    val localRuntime = viewModel.localSettingsRuntime
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val context = LocalContext.current
    val app = context.applicationContext as? OpenAndroidIntelligenceApplication
    val reduce = LocalMotionPolicy.current.isReduced
    val snackbar = remember { SnackbarHostState() }
    var trustDialog by rememberSaveable { mutableStateOf(false) }
    var emergencyDialog by rememberSaveable { mutableStateOf(false) }
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
    LaunchedEffect(localError, navigationError) {
        val message = localError ?: navigationError ?: return@LaunchedEffect
        snackbar.showSnackbar(message, withDismissAction = true)
        viewModel.dismissLocalError()
        navigationError = null
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
    fun setNotifications(value: Boolean) {
        if (!viewModel.setNotificationsGrant(value)) return
        if (value && viewModel.shouldRequestReplyNotificationPermission()) {
            viewModel.markReplyNotificationPermissionRequested()
            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val containerColor = if (presentation == SettingsPresentation.SIDEBAR) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.surface
    }
    val transitionRoles = remember { mutableMapOf<String, NavPageTransitionRole>() }
    LaunchedEffect(entry) {
        val visibleIds = nav.visibleEntries.value.mapTo(HashSet()) { it.id }
        transitionRoles.keys.retainAll(visibleIds)
    }

    val atOverview = entry?.destination?.route == SettingsRoutes.OVERVIEW
    val rootPredictiveBackEnabled = hostOpen && atOverview && presentation == SettingsPresentation.SCREEN
    val overlayBackController = LocalPredictiveOverlayBackController.current
    val standaloneBackState = if (overlayBackController == null) {
        rememberPredictiveBackState(
            enabled = rootPredictiveBackEnabled,
            onBack = onBack,
        )
    } else {
        PredictiveBackOverlayBridge(
            controller = overlayBackController,
            enabled = rootPredictiveBackEnabled,
            onDismiss = onBack,
        )
        null
    }
    BackHandler(enabled = hostOpen && atOverview && presentation == SettingsPresentation.SIDEBAR, onBack = onBack)
    // 设置内部无跨路由共享边界需求，避免挂载冗余 SharedTransitionLayout（LookaheadScope）引发双倍测量。
    val hostModifier = if (standaloneBackState != null) {
        modifier
            .fillMaxSize()
            .predictiveBackSlideTransform(
                stateProvider = { standaloneBackState.value },
                reduced = reduce,
            )
    } else {
        modifier.fillMaxSize()
    }
    Box(
        hostModifier
            .background(containerColor)
            .clipToBounds(),
    ) {
        NavHost(
            navController = nav,
            startDestination = SettingsRoutes.OVERVIEW,
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                transitionRoles[targetState.id] = NavPageTransitionRole.FOREGROUND
                AppTransitions.navEnter(reduce)
            },
            exitTransition = {
                transitionRoles[initialState.id] = NavPageTransitionRole.BACKGROUND
                AppTransitions.navExit(reduce)
            },
            popEnterTransition = {
                transitionRoles[targetState.id] = NavPageTransitionRole.BACKGROUND
                AppTransitions.navPopEnter(reduce)
            },
            popExitTransition = {
                transitionRoles[initialState.id] = NavPageTransitionRole.FOREGROUND
                AppTransitions.navPopExit(reduce)
            },
        ) {
            val roleProvider: (String) -> NavPageTransitionRole? = { entryId -> transitionRoles[entryId] }
            settingsComposable(SettingsRoutes.OVERVIEW, containerColor, roleProvider, reduce) {
                SettingsPageScaffold("设置", presentation, onBack, closeSemantics = presentation == SettingsPresentation.SIDEBAR) { padding ->
                    SettingsPageColumn(padding) {
                        SettingsCategory.entries.forEach { category ->
                            SettingsSectionCard(contentPadding = OverviewCardPadding) {
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
            settingsComposable(SettingsRoutes.APPEARANCE, containerColor, roleProvider, reduce) {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                AppearanceSettingsScreen(uiState, presentation, ::back, viewModel::setTheme, viewModel::setDynamicColor,
                    viewModel::setDynamicIconColor, viewModel::setPalette, viewModel::setReduceMotion,
                    onStreamingHapticFeedbackChange = viewModel::setStreamingHapticFeedback)
            }
            settingsComposable(SettingsRoutes.GATEWAY, containerColor, roleProvider, reduce) {
                if (remoteRuntime != null) com.openandroidintelligence.mobile.RemoteConnectionSettingsScreen(remoteRuntime, presentation, ::back,
                    onOpenDiagnostics = { open(SettingsRoutes.CONNECTION_DETAILS) })
                else SettingsPageScaffold("Remote", presentation, ::back) { padding ->
                    SettingsPageColumn(padding) { Text(androidx.compose.ui.res.stringResource(com.openandroidintelligence.ui.resources.R.string.remote_conversation_not_ready)) }
                }
            }
            settingsComposable(SettingsRoutes.CONNECTION_DETAILS, containerColor, roleProvider, reduce) {
                if (remoteRuntime != null) com.openandroidintelligence.mobile.RemoteConnectionDiagnosticsScreen(remoteRuntime, presentation, ::back)
            }
            settingsComposable(SettingsRoutes.REMINDERS, containerColor, roleProvider, reduce) {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                NotificationSettingsScreen(uiState, presentation, ::back, ::setNotifications)
            }
            settingsComposable(SettingsRoutes.PAIRING, containerColor, roleProvider, reduce) {
                DeviceCapabilitiesScreen(environment, localRuntime, presentation, ::back,
                    onOpenCapability = { plugin, capability -> open(capabilitySettingsRoute(plugin, capability)) },
                    onOpenPlugins = { open(SettingsRoutes.PLUGINS) })
            }
            settingsComposable(
                route = SettingsRoutes.CAPABILITY,
                containerColor = containerColor,
                roleProvider = roleProvider,
                reduced = reduce,
                arguments = listOf(navArgument("pluginId") { type = NavType.StringType }, navArgument("capability") { type = NavType.StringType }),
            ) { destination ->
                CapabilitySettingsScreen(destination.arguments?.getString("pluginId").orEmpty(), destination.arguments?.getString("capability").orEmpty(),
                    environment, localRuntime, presentation, ::back, onRequestScreenCaptureAuthorization,
                    onOpenPlugins = { open(SettingsRoutes.PLUGINS) })
            }
            settingsComposable(SettingsRoutes.SCREEN_CAPABILITY, containerColor, roleProvider, reduce) {
                CapabilitySettingsScreen("org.openandroidintelligence.screen-reader", "org.openandroidintelligence.screen-reader.read",
                    environment, localRuntime, presentation, ::back, onRequestScreenCaptureAuthorization,
                    onOpenPlugins = { open(SettingsRoutes.PLUGINS) }, showAllScreenCapabilities = true)
            }
            settingsComposable(SettingsRoutes.PLUGINS, containerColor, roleProvider, reduce) {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                PluginManagementScreen(uiState.allowRuntimePlugins, uiState.pluginRuntimesWired, ::back,
                    onOpenNotificationSettings = { open(SettingsRoutes.NOTIFICATIONS) }, presentation = presentation,
                    onOpenCapability = { plugin, capability -> open(capabilitySettingsRoute(plugin, capability)) },
                    onOpenScreen = { open(SettingsRoutes.SCREEN_CAPABILITY) }, onOpenAuthorizations = { open(SettingsRoutes.PAIRING) })
            }
            composable(SettingsRoutes.NOTIFICATIONS) {
                Box(Modifier.fillMaxSize().background(containerColor))
                LaunchedEffect(Unit) {
                    nav.navigate(notificationReadingRoute) {
                        popUpTo(SettingsRoutes.NOTIFICATIONS) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            // 主动推送不再是独立页面：配置就在各能力详情里。旧路由仍可达，但只做重定向，
            // 避免外部深链或旧返回栈落到一个不存在的页面。
            composable(SettingsRoutes.DEVICE_PUSH) {
                Box(Modifier.fillMaxSize().background(containerColor))
                LaunchedEffect(Unit) {
                    nav.navigate(notificationReadingRoute) {
                        popUpTo(SettingsRoutes.DEVICE_PUSH) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            settingsComposable(SettingsRoutes.SECURITY, containerColor, roleProvider, reduce) {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                SecuritySettingsScreen(uiState, presentation, ::back,
                    onToggleTrustMode = { if (it) trustDialog = true else viewModel.disableTrustMode() },
                    onOpenAudit = { open(SettingsRoutes.AUDIT_LOG) }, onRequestEmergencyStop = { emergencyDialog = true })
            }
            settingsComposable(SettingsRoutes.AUDIT_LOG, containerColor, roleProvider, reduce) {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                AuditSettingsScreen(uiState, presentation, ::back)
            }
            settingsComposable(SettingsRoutes.ADVANCED, containerColor, roleProvider, reduce) {
                AdvancedSettingsScreen(localRuntime, presentation, ::back,
                    onOpenUpdates = { open(SettingsRoutes.APP_UPDATES) }, onOpenBuildDetails = { open(SettingsRoutes.BUILD_DETAILS) },
                    onOpenLicenses = { open(SettingsRoutes.LICENSES) }, onOpenBackup = { open(SettingsRoutes.BACKUP) },
                    onReplayOnboarding = { app?.onboardingPreferences?.startReplay(); onBack() }, remoteRuntime = remoteRuntime)
            }
            settingsComposable(SettingsRoutes.APP_UPDATES, containerColor, roleProvider, reduce) {
                AppUpdateRoute(onBack = ::back, presentation = presentation)
            }
            settingsComposable(SettingsRoutes.BUILD_DETAILS, containerColor, roleProvider, reduce) {
                BuildDetailsScreen(presentation, ::back)
            }
            settingsComposable(SettingsRoutes.LICENSES, containerColor, roleProvider, reduce) {
                OpenSourceNoticesScreen(presentation, ::back)
            }
            settingsComposable(SettingsRoutes.BACKUP, containerColor, roleProvider, reduce) {
                SettingsBackupRoute(presentation, ::back, remoteRuntime)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    // Register after NavHost only for same-depth shortcuts; normal predictive back stays with NavHost.
    BackHandler(enabled = hostOpen && shortcutSources.isNotEmpty()) { back() }
    if (trustDialog) AlertDialog(onDismissRequest = { trustDialog = false }, title = { Text("开启开发者信任模式？") },
        text = { Text("原生插件将使用本应用的权限并访问全部数据。请仅在信任插件来源时开启。\n\n${DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT}") },
        confirmButton = { TextButton(onClick = { viewModel.enableTrustMode(); trustDialog = false }) { Text("我已知晓并开启") } },
        dismissButton = { TextButton(onClick = { trustDialog = false }) { Text("取消") } })
    if (emergencyDialog) AlertDialog(onDismissRequest = { emergencyDialog = false }, title = { Text("紧急停止所有插件？") },
        text = { Text("将立即停止所有插件并撤销临时授权，需重启应用恢复。保存的设置与系统权限不变。") },
        confirmButton = { TextButton(onClick = { viewModel.emergencyStop(); emergencyDialog = false }) { Text("确认停止", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { emergencyDialog = false }) { Text("取消") } })
}

/** 统一注入设置子路由的 [ProvideSharedVisibilityScope] 与分层导航视差阴影修饰。 */
private fun NavGraphBuilder.settingsComposable(
    route: String,
    containerColor: Color,
    roleProvider: (String) -> NavPageTransitionRole?,
    reduced: Boolean,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route = route, arguments = arguments) { backStackEntry ->
        ProvideSharedVisibilityScope {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(containerColor)
                    .navPageTransitionDecoration(
                        roleProvider = { roleProvider(backStackEntry.id) },
                        reduced = reduced,
                    ),
            ) {
                content(backStackEntry)
            }
        }
    }
}
