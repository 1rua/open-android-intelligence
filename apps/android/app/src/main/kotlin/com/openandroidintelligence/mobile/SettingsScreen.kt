package com.openandroidintelligence.mobile

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.openandroidintelligence.conversation.components.CONNECTION_FAILURE_FALLBACK
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsNavigationItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.SettingsSwitchItem
import com.openandroidintelligence.conversation.components.SettingsTone
import com.openandroidintelligence.conversation.components.readableFailure
import com.openandroidintelligence.conversation.motion.AppTransitions
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.ThemePalette
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.gateway.http.TransportSecurity
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.mobile.notifications.NotificationCollectionScreen
import com.openandroidintelligence.mobile.plugins.PluginManagementScreen
import com.openandroidintelligence.mobile.updates.AppUpdateRoute
import com.openandroidintelligence.notification.control.NotificationControlRegistry
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.filled.Visibility
import com.openandroidintelligence.mobile.screen.ScreenCapabilityController
import com.openandroidintelligence.mobile.screen.ScreenCapabilitySection
import com.openandroidintelligence.mobile.screen.openAccessibilitySettings
import com.openandroidintelligence.mobile.settings.SettingsRoutes
import com.openandroidintelligence.mobile.settings.AppearanceSettingsScreen
import com.openandroidintelligence.mobile.settings.ConnectionSettingsScreen
import com.openandroidintelligence.mobile.settings.NotificationSettingsScreen
import com.openandroidintelligence.mobile.settings.CapabilityAuthSettingsScreen
import com.openandroidintelligence.mobile.settings.AdvancedSettingsScreen

/**
 * SCREEN 自行处理系统栏；SIDEBAR 的系统栏由抽屉或固定侧栏宿主消费，
 * 设置内容只负责键盘避让，避免重复计算安全区。
 */
enum class SettingsPresentation { SCREEN, SIDEBAR }

private val NoWindowInsets = WindowInsets(0, 0, 0, 0)

/** 当前呈现方式下页面（Scaffold / 标题栏）应使用的底色。 */
private fun settingsContainerColor(presentation: SettingsPresentation): Color =
    if (presentation == SettingsPresentation.SIDEBAR) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.surface
    }

/** 当前呈现方式下 Scaffold 内容区应扣的系统栏 inset。 */
private fun settingsContentWindowInsets(presentation: SettingsPresentation): WindowInsets =
    if (presentation == SettingsPresentation.SIDEBAR) NoWindowInsets else WindowInsets.systemBars

/**
 * 设置主界面（SettingsScreen）。
 *
 * 遵循 Material 3 分组列表规范：
 * 1. 分组卡片走 `surfaceContainer` + 24dp 大圆角（AppRadius.Large），组内条目用标准
 *    M3 ListItem：前置图标底衬 + 标题/辅助两行 + 后置控件或导航箭头；
 * 2. 危险操作不再把整张卡片涂成 `errorContainer`（同色的 DANGER 徽标会消失），
 *    卡片保持中性面，危险只用徽标/文字色表达；
 * 3. 内置 NavHost 驱动子页流转（外观、网关、设备能力、内核安全、审计日志）；
 * 4. 会话级操作提示走 Snackbar，不再用 `zIndex` 悬浮盖住顶栏；
 * 5. 宽屏内容限宽 [Dimensions.ReadingWidth] 居中，平板不再通栏拉伸。
 */
fun SettingsScreen(
    environment: PlatformSettingsEnvironment,
    onBack: () -> Unit,
    runtime: GatewayRuntime? = null,
    modifier: Modifier = Modifier,
    presentation: SettingsPresentation = SettingsPresentation.SCREEN,
    onRequestScreenCaptureAuthorization: () -> Unit = {},
) {
    val viewModel = remember(environment, runtime) {
        SettingsViewModel(environment = environment, runtime = runtime)
    }
    SettingsScreen(
        viewModel = viewModel,
        onBack = onBack,
        modifier = modifier,
        presentation = presentation,
        onRequestScreenCaptureAuthorization = onRequestScreenCaptureAuthorization,
    )
}

fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    presentation: SettingsPresentation = SettingsPresentation.SCREEN,
    // 远程截图专用的系统投屏授权必须由前台 Activity 发起。
    onRequestScreenCaptureAuthorization: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 转场与降级统一读 composition local：它同时覆盖「用户开关」与「系统动画时长 0」
    // 两种减少动态来源，只看 uiState.appearance.reduceMotion 会漏掉后者。
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val snackbarHostState = remember { SnackbarHostState() }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshReplyNotificationPermission()
    }
    DisposableEffect(viewModel, lifecycleOwner) {
        viewModel.refreshReplyNotificationPermission()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshReplyNotificationPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val setReplyNotifications: (Boolean) -> Unit = { enabled ->
        viewModel.setNotificationsGrant(enabled)
        if (enabled) {
            if (viewModel.shouldRequestReplyNotificationPermission()) {
                viewModel.markReplyNotificationPermissionRequested()
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else if (!uiState.replyNotificationsAllowed) {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }
    }

    var showTrustModeDialog by remember { mutableStateOf(false) }
    var showEmergencyDialog by remember { mutableStateOf(false) }
    var showUnpairDialog by remember { mutableStateOf(false) }

    // 会话级操作提示：登出/续期这类落网结果必须可见、可关。改用 Scaffold 级 Snackbar，
    // 不再浮在顶栏之上把标题和返回按钮盖住。
    LaunchedEffect(uiState.operationNotice) {
        val notice = uiState.operationNotice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = notice, withDismissAction = true)
        // Snackbar 关闭即视为已读：清掉运行时提示，避免下次进入设置又弹一遍。
        viewModel.dismissOperationNotice()
    }

    Box(
        modifier = modifier.fillMaxSize()
            .then(if (presentation == SettingsPresentation.SIDEBAR) Modifier.imePadding() else Modifier),
    ) {
        NavHost(
            navController = navController,
            startDestination = SettingsRoutes.OVERVIEW,
            // 底色由各子页 Scaffold 负责，侧栏与独立页面使用各自的 surface 色阶。
            modifier = Modifier.fillMaxSize(),
            enterTransition = { AppTransitions.navEnter(reduceMotion) },
            exitTransition = { AppTransitions.navExit(reduceMotion) },
            popEnterTransition = { AppTransitions.navPopEnter(reduceMotion) },
            popExitTransition = { AppTransitions.navPopExit(reduceMotion) },
        ) {
        composable(SettingsRoutes.OVERVIEW) {
            SettingsOverviewScreen(
                uiState = uiState,
                onBack = onBack,
                backEnabled = currentEntry?.destination?.route == SettingsRoutes.OVERVIEW,
                presentation = presentation,
                onNavigate = { route ->
                    navController.navigate(route) {
                        launchSingleTop = true
                    }
                },
                onSetNotificationsGrant = setReplyNotifications,
                onToggleTrustMode = { enable ->
                    if (enable) showTrustModeDialog = true else viewModel.disableTrustMode()
                },
                onRequestEmergencyStop = { showEmergencyDialog = true },
                onRequestUnpair = { showUnpairDialog = true },
            )
        }

        composable(SettingsRoutes.APPEARANCE) {
            AppearanceSettingsScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
                presentation = presentation,
                onSetTheme = viewModel::setTheme,
                onSetDynamicColor = viewModel::setDynamicColor,
                onSetDynamicIconColor = viewModel::setDynamicIconColor,
                onSetPalette = viewModel::setPalette,
                onSetReduceMotion = viewModel::setReduceMotion,
            )
        }

        composable(SettingsRoutes.APP_UPDATES) {
            AppUpdateRoute(onBack = { navController.popBackStack() })
        }

        composable(SettingsRoutes.GATEWAY) {
            ConnectionSettingsScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
                presentation = presentation,
                onRefreshSession = viewModel::refreshSession,
                onLogout = { viewModel.logout(revokeRefresh = true) },
                onSwitchAccount = { viewModel.chooseAnotherAccount(); onBack() },
                onSelectAgent = viewModel::selectAgent,
                onRefreshAgents = viewModel::refreshAgents,
                onRequestUnpair = { showUnpairDialog = true },
            )
        }

        // PAIRING 路由已废弃,重定向到设备能力页面
        composable(SettingsRoutes.PAIRING) {
            // 兼容旧路由:重定向到新的设备能力与授权页面
            LaunchedEffect(Unit) {
                navController.navigate(com.openandroidintelligence.mobile.settings.SettingsRoutes.CAPABILITIES) {
                    popUpTo(SettingsRoutes.PAIRING) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }

        // 新路由:设备能力与授权(六大分类之一)
        composable(com.openandroidintelligence.mobile.settings.SettingsRoutes.CAPABILITIES) {
            CapabilityAuthSettingsScreen(
                onBack = { navController.popBackStack() },
                presentation = presentation,
                onNavigateToScreenCapability = { navController.navigate(SettingsRoutes.SCREEN_CAPABILITY) { launchSingleTop = true } },
                onNavigateToPushSettings = { navController.navigate(SettingsRoutes.DEVICE_PUSH) { launchSingleTop = true } },
                onNavigateToPlugins = { navController.navigate(SettingsRoutes.PLUGINS) { launchSingleTop = true } },
                onNavigateToNotificationCollection = { navController.navigate(SettingsRoutes.NOTIFICATIONS) { launchSingleTop = true } },
            )
        }

        // 新路由:通知与提醒(六大分类之一)
        composable(com.openandroidintelligence.mobile.settings.SettingsRoutes.NOTIFICATIONS + "_new") {
            NotificationSettingsScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
                presentation = presentation,
                onSetNotificationsGrant = setReplyNotifications,
                onNavigateToNotificationCollection = { navController.navigate(SettingsRoutes.NOTIFICATIONS) { launchSingleTop = true } },
            )
        }

        // 新路由:高级设置与关于(六大分类之一)
        composable(com.openandroidintelligence.mobile.settings.SettingsRoutes.ADVANCED) {
            AdvancedSettingsScreen(
                onBack = { navController.popBackStack() },
                presentation = presentation,
                onNavigateToAppUpdate = { navController.navigate(SettingsRoutes.APP_UPDATES) { launchSingleTop = true } },
                onOpenOnboarding = {
                    (context.applicationContext as? OpenAndroidIntelligenceApplication)?.let { app ->
                        app.onboardingPreferences.startReplay()
                        onBack()
                    }
                },
                onOpenAbout = { /* TODO: 导航到关于页面 */ },
            )
        }

        composable(SettingsRoutes.SCREEN_CAPABILITY) {
            ScreenCapabilitySubScreen(
                onBack = { navController.popBackStack() },
                presentation = presentation,
                onRequestCaptureAuthorization = onRequestScreenCaptureAuthorization,
                onOpenPlugins = { navController.navigate(SettingsRoutes.PLUGINS) { launchSingleTop = true } },
            )
        }

        composable(SettingsRoutes.SECURITY) {
            com.openandroidintelligence.mobile.settings.SecuritySettingsScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
                presentation = presentation,
                onToggleTrustMode = { enable ->
                    if (enable) showTrustModeDialog = true else viewModel.disableTrustMode()
                },
                onRequestEmergencyStop = { showEmergencyDialog = true },
                onNavigateToAuditLog = { navController.navigate(SettingsRoutes.AUDIT_LOG) { launchSingleTop = true } },
            )
        }

        composable(SettingsRoutes.AUDIT_LOG) {
            AuditLogSubScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
                presentation = presentation,
            )
        }

        composable(SettingsRoutes.PLUGINS) {
            PluginManagementScreen(
                allowRuntimePlugins = uiState.allowRuntimePlugins,
                pluginRuntimesWired = uiState.pluginRuntimesWired,
                onBack = { navController.popBackStack() },
                onOpenNotificationSettings = { navController.navigate(SettingsRoutes.NOTIFICATIONS) { launchSingleTop = true } },
            )
        }

        composable(SettingsRoutes.DEVICE_PUSH) {
            val push = (context.applicationContext as? OpenAndroidIntelligenceApplication)?.devicePush
            val endpoints = if (push != null) push.endpoints.collectAsState().value else emptyList()
            DevicePushSubScreen(
                runtime = push?.runtime,
                endpoints = endpoints,
                onRefreshEndpoints = { push?.refreshEndpoints() },
                onBack = { navController.popBackStack() },
                presentation = presentation,
            )
        }

        composable(SettingsRoutes.NOTIFICATIONS) {
            // 通知采集的端口由 :notification-host 经 ContentProvider 自注册；
            // registry 未装配时端口本身 deny-first，子页如实渲染「未装配」。
            NotificationCollectionScreen(
                policy = NotificationControlRegistry.policyPort(),
                binding = NotificationControlRegistry.bindingPort(),
                onBack = { navController.popBackStack() },
            )
        }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(Dimensions.SpaceMedium),
        )
    }

    if (showTrustModeDialog) {
        AlertDialog(
            onDismissRequest = { showTrustModeDialog = false },
            title = { Text("开启开发者信任模式") },
            text = { Text(DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.enableTrustMode()
                        showTrustModeDialog = false
                    },
                ) { Text("我已知晓并开启") }
            },
            dismissButton = {
                TextButton(onClick = { showTrustModeDialog = false }) { Text("取消") }
            },
        )
    }

    if (showEmergencyDialog) {
        AlertDialog(
            onDismissRequest = { showEmergencyDialog = false },
            title = { Text("确认系统安全熔断") },
            text = {
                Text(
                    "这将调用平台内核的全局熔断机制，立即隔离所有已启用插件并关闭开发者信任模式。操作完成后必须重启应用才能恢复。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.emergencyStop()
                        showEmergencyDialog = false
                    },
                ) { Text("确认停用", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showEmergencyDialog = false }) { Text("取消") }
            },
        )
    }

    if (showUnpairDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairDialog = false },
            title = { Text("确认解除设备配对") },
            text = {
                Text(
                    "将撤销设备的配对凭据并切断连接。插件能力与系统权限设置将保留。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.unpair()
                        showUnpairDialog = false
                        onBack()
                    },
                ) { Text("确认解除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairDialog = false }) { Text("取消") }
            },
        )
    }
}

/** 主动推送沿用设置子页容器；正文继续负责自己的滚动和配置操作。 */
internal fun DevicePushSubScreen(
    runtime: com.openandroidintelligence.mobile.push.DevicePushRuntime?,
    endpoints: List<com.openandroidintelligence.mobile.push.PushEndpointOption>,
    onRefreshEndpoints: () -> Unit,
    onBack: () -> Unit,
    presentation: SettingsPresentation,
) {
    SettingsPageScaffold(
        title = "短信与通知主动推送",
        presentation = presentation,
        onBack = onBack,
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (runtime != null) {
                com.openandroidintelligence.mobile.push.PushSettingsScreen(
                    runtime = runtime,
                    endpoints = endpoints,
                    onRefreshEndpoints = onRefreshEndpoints,
                    modifier = Modifier.fillMaxHeight(),
                )
            } else {
                Text("本机加密队列暂不可用，请检查设备存储和密钥状态。")
            }
        }
    }
}

/** 设置子页统一的 Scaffold：标题栏、底色与系统栏 inset 只在 [SettingsPresentation] 处分支一次。 */
@OptIn(ExperimentalMaterial3Api::class)
private fun SettingsPageScaffold(
    title: String,
    presentation: SettingsPresentation,
    onBack: () -> Unit,
    closeSemantics: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    val containerColor = settingsContainerColor(presentation)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        if (closeSemantics) {
                            Icon(Icons.Default.Close, contentDescription = "关闭设置")
                        } else {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = containerColor,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                windowInsets = if (presentation == SettingsPresentation.SIDEBAR) {
                    NoWindowInsets
                } else {
                    TopAppBarDefaults.windowInsets
                },
            )
        },
        containerColor = containerColor,
        contentWindowInsets = settingsContentWindowInsets(presentation),
        content = content,
    )
}

/**
 * 设置页统一内容容器：宽屏限宽居中 + 页面边距 + 垂直滚动。
 *
 * 短列表（分组卡片）保留 `Column + verticalScroll`：分组是固定的 7 块、每块内部
 * 条目数量有限，懒加载不会带来收益；而既有界面测试依赖「所有分组行都在语义树里」
 * 才能 `performScrollTo`，换成 LazyColumn 会让屏外行不再被组合，测试锚点随之失效。
 * 真正无上限增长的列表（审计流水）另用 LazyColumn，见 [AuditLogSubScreen]。
 */
private fun SettingsPageColumn(
    contentPadding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = Dimensions.ReadingWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = Dimensions.ScreenHorizontal,
                    vertical = Dimensions.SpaceSmall,
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
            content = content,
        )
    }
}

/** 设置主览界面。 */
private fun SettingsOverviewScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    backEnabled: Boolean,
    presentation: SettingsPresentation,
    onNavigate: (String) -> Unit,
    onSetNotificationsGrant: (Boolean) -> Unit,
    onToggleTrustMode: (Boolean) -> Unit,
    onRequestEmergencyStop: () -> Unit,
    onRequestUnpair: () -> Unit,
) {
    BackHandler(enabled = backEnabled, onBack = onBack)

    SettingsPageScaffold(
        title = "设置",
        presentation = presentation,
        onBack = onBack,
        // 根页退出设置面板；子页继续使用「返回」语义。
        closeSemantics = presentation == SettingsPresentation.SIDEBAR,
    ) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            SectionLabel("应用信息")
            SettingsSectionCard {
                SettingsNavigationItem(
                    headline = "应用更新",
                    supporting = "检查正式版和预览版更新",
                    icon = Icons.Default.SystemUpdate,
                    onClick = { onNavigate(SettingsRoutes.APP_UPDATES) },
                )
                // 引导入口：已完成引导的用户仍需要一个能重新查看部署步骤与权限说明的地方。
                // 重看只进入引导流程，不改写「已完成」状态 —— 关掉后仍回主界面。
                (androidx.compose.ui.platform.LocalContext.current.applicationContext
                    as? OpenAndroidIntelligenceApplication)?.let { app ->
                    SettingsNavigationItem(
                        headline = "新手引导与部署教程",
                        supporting = "重新查看 Gateway 部署步骤与权限说明",
                        icon = Icons.Default.MenuBook,
                        onClick = {
                            app.onboardingPreferences.startReplay()
                            onBack()
                        },
                    )
                }
            }
            (androidx.compose.ui.platform.LocalContext.current.applicationContext as? OpenAndroidIntelligenceApplication)?.let { app ->
                val preferences=remember { com.openandroidintelligence.mobile.conversations.DebouncePreferences(app) }
                val gateway=(app.gatewayRuntime.phase.value as? ConnectionPhase.Connected)?.gatewayUrl
                var override by remember(gateway) { mutableStateOf(gateway!=null && preferences.hasOverride(gateway)) }
                var delayMillis by remember(gateway,override) { mutableStateOf(preferences.read(if (override) gateway else null).delay.inWholeMilliseconds.toInt()) }
                var extend by remember(gateway,override) { mutableStateOf(preferences.read(if (override) gateway else null).mode==com.openandroidintelligence.conversation.batch.DebounceMode.EXTEND_WINDOW) }
                SectionLabel("消息发送延迟")
                SettingsSectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                        if (gateway!=null) Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = override,
                                    role = Role.Switch,
                                ) { value ->
                                    override = value
                                    if (value) app.gatewayRuntime.updateDebounceSettings(gateway,delayMillis,extend)
                                    else app.gatewayRuntime.useGlobalDebounceSettings(gateway)
                                }
                                .padding(vertical = Dimensions.SpaceTiny),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "覆盖当前 Gateway 的设置",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            // Switch 只做展示：整行 toggleable 已经承载开关语义与点击，
                            // 避免读屏出现「行」和「开关」两个可点目标。
                            Switch(checked = override, onCheckedChange = null)
                        }
                        Text(
                            text = if (delayMillis==0) "关闭防抖" else "等待 ${delayMillis/1000.0} 秒",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(value=delayMillis.toFloat(),valueRange=0f..10000f,steps=19,onValueChange={ delayMillis=it.toInt() },
                            onValueChangeFinished={ app.gatewayRuntime.updateDebounceSettings(if (override) gateway else null,delayMillis,extend) })
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = extend,
                                    role = Role.Switch,
                                ) { value ->
                                    extend = value
                                    app.gatewayRuntime.updateDebounceSettings(if (override) gateway else null,delayMillis,value)
                                }
                                .padding(vertical = Dimensions.SpaceTiny),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "新消息延长等待",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = extend, onCheckedChange = null)
                        }
                        Text(
                            text = "设置应用于新批次；最长等待 30 秒，每批最多 20 条。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // 模块 1: 外观与动效
            // 主题入口只保留「外观」子页一处：主览不再重复一份主题行与主题弹窗，
            // 否则同一设置存在三处入口（主览行 / AlertDialog / 子页），改一处漏两处。
            SectionLabel("外观与动效")
            SettingsSectionCard {
                SettingsNavigationItem(
                    headline = "外观、主题与动效",
                    supporting = "深浅配色（当前：${themeLabel(uiState.appearance.theme)}）· 动态取色 · 减少动态效果",
                    icon = Icons.Default.Palette,
                    onClick = { onNavigate(SettingsRoutes.APPEARANCE) },
                )
            }

            // 模块 2: 网关与账号
            SectionLabel("网关与账号")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact)) {
                    val phase = uiState.connectionPhase
                    val statusText = when (phase) {
                        is ConnectionPhase.Connected -> "已连接 · " + phase.username
                        is ConnectionPhase.OfflineMirror -> "离线镜像 · " + phase.username
                        ConnectionPhase.Disconnected -> "未连接 Gateway"
                        ConnectionPhase.Initializing -> "正在连接 OAI Core…"
                        ConnectionPhase.Reconnecting -> "正在重新连接 OAI Core…"
                        ConnectionPhase.Negotiating -> "正在连接…"
                        ConnectionPhase.Authenticating -> "正在验证凭据…"
                        // 这里绝不直接打印 `phase.code`：它是异常的 message，可能带地址与对端正文，
                        // 而且对用户没有可执行意义。与登录页共用同一套「码 → 可操作说明」。
                        is ConnectionPhase.Failed ->
                            "连接失败：" + readableFailure(phase.code, CONNECTION_FAILURE_FALLBACK)
                    }
                    SettingsNavigationItem(
                        headline = "OAI Core 管理",
                        supporting = statusText,
                        icon = Icons.Default.Hub,
                        tone = if (uiState.isGatewayConnected) SettingsTone.PRIMARY else SettingsTone.NEUTRAL,
                        onClick = { onNavigate(SettingsRoutes.GATEWAY) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val transportSecurity = uiState.connectedTransportSecurity
                    val transportSupporting = when {
                        !uiState.isGatewayConnected -> "还没有建立连接"
                        transportSecurity == TransportSecurity.PLAINTEXT -> "未加密（HTTP），内容可能被同网络的人看到"
                        transportSecurity == TransportSecurity.TLS_PINNED -> "已加密，且证书与首次连接时记录的指纹一致"
                        else -> "已加密（系统信任的证书）"
                    }
                    SettingsListItem(
                        headline = "连接加密状态",
                        supporting = transportSupporting,
                        icon = Icons.Default.Security,
                        tone = if (transportSecurity == TransportSecurity.PLAINTEXT && uiState.isGatewayConnected) {
                            SettingsTone.DANGER
                        } else {
                            SettingsTone.NEUTRAL
                        },
                        trailing = {
                            Surface(
                                shape = RoundedCornerShape(AppRadius.ExtraSmall),
                                color = if (uiState.isGatewayConnected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                            ) {
                                Text(
                                    text = if (uiState.isGatewayConnected) "在线" else "离线",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (uiState.isGatewayConnected) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(horizontal = Dimensions.SpaceSmall, vertical = Dimensions.SpaceTiny),
                                )
                            }
                        },
                    )
                }
            }

            // 模块 3: 设备权限授权
            // 所有手机能力与权限管理条目统一收进子页，主览只留一个入口：
            // 权限散落在主览里时，用户既看不出共有哪些授权，也容易把插件跳转误认成开关。
            // 这里不再展示本机自增序号 r{N}——它对用户没有可执行意义，还容易被读成
            // Gateway 下发的权威版本（裁决 D5），因此改为陈述可执行的事实。
            SectionLabel("设备权限授权")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact)) {
                    SettingsNavigationItem(
                        headline = "授权管理",
                        supporting = "管理本机插件能力开关；系统权限、通知读取范围和屏幕会话分别设置",
                        icon = Icons.Default.Key,
                        onClick = { onNavigate(SettingsRoutes.PAIRING) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsNavigationItem(
                        headline = "插件管理",
                        supporting = "安装并启用插件，管理本机设备能力开关和所需系统权限",
                        icon = Icons.Default.Extension,
                        onClick = { onNavigate(SettingsRoutes.PLUGINS) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // 屏幕能力单独一条：它按「本地会话 + 三类权限」逐项授予，
                    // 与「安装插件」是两件不同的事，混在一起会让用户以为装完就能用。
                    SettingsNavigationItem(
                        headline = "短信与通知主动推送",
                        supporting = "自主设置内容范围、推送目标与离线保留时间；默认关闭",
                        icon = Icons.Default.Notifications,
                        onClick = { onNavigate(SettingsRoutes.DEVICE_PUSH) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsNavigationItem(
                        headline = "屏幕读取与控制",
                        supporting = "按需授予读取界面、截取画面、操作其他应用；随时可以结束",
                        icon = Icons.Default.Visibility,
                        onClick = { onNavigate(SettingsRoutes.SCREEN_CAPABILITY) },
                    )
                }
            }

            // 模块 4: 通知管理
            // 通知类设置单独成区：这些是「通知怎么提醒、怎么展示」的行为配置，
            // 与本机插件能力开关是两件事，不应混在同一张设置卡里。
            // 「通知读取范围」也归在这里：它配置的是读哪些应用的通知内容，
            // 属于通知的采集范围，而不是对 Gateway 的能力开关。
            SectionLabel("通知管理")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact)) {
                    NotificationPushCapabilityItem(
                        checked = uiState.isNotificationsGranted,
                        presentation = replyNotificationPresentation(
                            wired = uiState.replyNotificationsWired,
                            systemAllowed = uiState.replyNotificationsAllowed,
                        ),
                        icon = Icons.Default.Notifications,
                        onCheckedChange = onSetNotificationsGrant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ConversationBubbleSettingsItem()
                    if (android.os.Build.VERSION.SDK_INT >= 36) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        LiveUpdatesSettingsItem()
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsNavigationItem(
                        headline = "通知读取范围",
                        onClick = { onNavigate(SettingsRoutes.NOTIFICATIONS) },
                        icon = Icons.Default.MarkEmailRead,
                        supporting = if (NotificationControlRegistry.policyPort().snapshot().installed) {
                            "选择要读取哪些应用的通知、读取哪些内容，以及是否自动发送"
                        } else {
                            "本机未装配通知读取能力，暂不可配置"
                        },
                    )
                }
            }

            // 模块 5: 运行安全
            // 「插件管理」已上移到设备权限授权区，这里不再重复同一入口：
            // 同一设置出现两处入口，改文案时必然漏掉一处。
            SectionLabel("运行安全")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact)) {
                    SettingsSwitchItem(
                        headline = "开发者信任模式",
                        supporting = when {
                            !uiState.allowDeveloperTrustMode -> "当前版本不支持开启（应用商店版本已禁用）"
                            uiState.isTrustModeEnabled -> "已开启：插件可以直接控制系统界面，隔离保护已放宽"
                            else -> "未开启：插件在隔离环境中运行，无法控制系统界面"
                        },
                        checked = uiState.isTrustModeEnabled,
                        enabled = uiState.allowDeveloperTrustMode && !uiState.isEmergencyStopped,
                        icon = if (uiState.isTrustModeEnabled) Icons.Default.LockOpen else Icons.Default.Lock,
                        tone = if (uiState.isTrustModeEnabled) SettingsTone.PRIMARY else SettingsTone.NEUTRAL,
                        onCheckedChange = onToggleTrustMode,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsNavigationItem(
                        headline = "安全策略说明",
                        supporting = "了解插件能做什么、不能做什么，以及每次授权如何被记录",
                        icon = Icons.Default.Shield,
                        onClick = { onNavigate(SettingsRoutes.SECURITY) },
                    )
                }
            }

            // 模块 6: 操作记录
            SectionLabel("操作记录")
            SettingsSectionCard {
                SettingsNavigationItem(
                    headline = "授权与调用记录",
                    supporting = if (uiState.auditEvents.isEmpty()) {
                        "还没有记录。授权变更和插件调用会记录在这里"
                    } else {
                        "已记录 ${uiState.auditEvents.size} 条，可逐条核对发生过的授权与调用"
                    },
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    onClick = { onNavigate(SettingsRoutes.AUDIT_LOG) },
                )
            }

            // 模块 7: 危险操作与紧急控制
            // 危险分组不整卡涂 errorContainer：那样同色的 DANGER 徽标会与卡面融为一体、
            // 反而看不出危险。卡面保持中性面，危险只由徽标与文字承担。
            SectionLabel("紧急与危险操作")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact)) {
                    SettingsListItem(
                        headline = "紧急停止所有插件",
                        supporting = if (uiState.isEmergencyStopped) {
                            "已紧急停止，共停用 ${uiState.emergencyStoppedCount} 个插件。需要重启应用才能恢复。"
                        } else {
                            "立即停用所有插件并关闭信任模式，需要重启应用才能恢复。"
                        },
                        icon = Icons.Default.Warning,
                        tone = SettingsTone.DANGER,
                        enabled = !uiState.isEmergencyStopped,
                        onClick = onRequestEmergencyStop,
                        trailing = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(Dimensions.SmallIcon),
                            )
                        },
                    )

                    if (uiState.isGatewayConnected) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        SettingsListItem(
                            headline = "解除设备配对",
                            supporting = "撤销当前设备的 Gateway 配对凭据；本机能力开关与系统权限分别管理",
                            icon = Icons.Default.DeleteForever,
                            tone = SettingsTone.DANGER,
                            onClick = onRequestUnpair,
                            trailing = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(Dimensions.SmallIcon),
                                )
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))
        }
    }
}

/** 子页面 1: 外观与动效详细配置（主题的唯一入口）。 */

/**
 * 预设主题色的一行。
 *
 * 用当前方案自己的 primary / primaryContainer / tertiary 画三个色点作为预览：
 * 用户看到的就是切换后界面真正会用的颜色，而不是另画一套"示意色"。
 * 动态取色开启时整行禁用（`enabled = false`），并在分组底部说明原因。
 */
private fun PaletteOptionRow(
    palette: ThemePalette,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val light = remember(palette) { palette.lightScheme() }
    val dark = remember(palette) { palette.darkScheme() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimensions.MinimumTouchTarget)
            .clip(RoundedCornerShape(AppRadius.Medium))
            .then(
                if (enabled) {
                    Modifier.selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = onSelect,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = Dimensions.SpaceMedium, vertical = Dimensions.SpaceCompact)
            .alpha(if (enabled) 1f else 0.6f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact),
    ) {
        Box(
            modifier = Modifier
                .size(Dimensions.LeadingIconContainer + 4.dp)
                .clip(CircleShape)
                .then(
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                PaletteDot(color = light.primary, dark = dark.primary)
                PaletteDot(color = light.primaryContainer, dark = dark.primaryContainer)
                PaletteDot(color = light.tertiary, dark = dark.tertiary)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = palette.label,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = palette.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
        }
    }
}

private fun PaletteDot(
    color: androidx.compose.ui.graphics.Color,
    dark: androidx.compose.ui.graphics.Color,
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (isDark) dark else color),
    )
}

/** 子页面 2: OAI Core 管理与连接详细配置。 */

/**
 * 子页面 3: 授权管理。
 *
 * 只呈现手机本机能力开关与相关系统权限入口：
 * 1. 通知类设置（推送、气泡、实时活动、读取范围）属于通知行为配置，已归入
 *    主览的「通知管理」区，不在这里重复出现；
 * 2. 不再展示本机自增序号、本机合成的配对标识——它们是实现细节，对用户没有
 *    可执行意义，还容易被误读成 Gateway 下发的权威版本（裁决 D5）；
 * 3. 短信读取由插件管理里的本机能力开关控制；系统权限仍由 Android 单独确认，
 *    因此这一行跳转到插件管理，不在此处重复提供一个开关。
 */

/**
 * 子页面：屏幕读取与控制。
 *
 * 三层授权分开呈现：系统权限（无障碍 / 投屏）、本机能力开关（安装+启用插件）、
 * 本进程内的本地屏幕会话。任何一层缺失都指名缺的是哪一件，绝不用一个布尔值代替。
 *
 * 状态来自 `ScreenHostRegistry.current?.status()` —— 只读诊断，不会创建许可。
 */
private fun ScreenCapabilitySubScreen(
    onBack: () -> Unit,
    presentation: SettingsPresentation,
    onRequestCaptureAuthorization: () -> Unit,
    onOpenPlugins: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember { ScreenCapabilityController(scope) }
    val state by controller.state.collectAsState()
    var policyError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 状态不是永久承诺：回到前台、从系统设置返回、授权结果返回都要重新查询。
    DisposableEffect(controller, lifecycleOwner) {
        controller.refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    SettingsPageScaffold(
        title = "屏幕读取与控制",
        presentation = presentation,
        onBack = onBack,
    ) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            SectionLabel("屏幕能力")
            policyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            val app = context.applicationContext as? OpenAndroidIntelligenceApplication
            val host = app?.pluginHost
            val revision = host?.revision?.collectAsState()?.value
            val entries = remember(host, revision) {
                listOf("org.openandroidintelligence.screen-reader", "org.openandroidintelligence.screen-capture",
                    "org.openandroidintelligence.screen-control").flatMap { host?.entries(it).orEmpty() }
            }
            if (entries.isEmpty()) {
                SettingsSectionCard {
                    Text("尚未安装屏幕能力插件。安装并启用后，可分别设置读取、截图与控制的全局授权。")
                    TextButton(onClick = onOpenPlugins) { Text("打开插件管理") }
                }
            } else entries.forEach { entry ->
                SettingsSectionCard {
                    com.openandroidintelligence.mobile.plugins.CapabilityAuthorizationControls(entry.identity.pluginId,
                        entry.key, entry.risk == "read", host!!.capabilityPolicy(entry.identity.pluginId, entry.key),
                        onPolicyChange = { policy ->
                            policyError = runCatching { host.setCapabilityPolicy(entry.identity.pluginId, entry.key, policy) }
                                .fold(onSuccess = { null }, onFailure = { "本机授权设置未能保存，请检查存储后重试。" })
                            controller.refresh()
                        })
                }
            }
            SectionLabel("Android 系统许可")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    Text(if (state.accessibilityReady) "无障碍服务已连接" else "无障碍服务尚未连接，屏幕内容读取与控制暂不可用")
                    TextButton(onClick = { openAccessibilitySettings(context) }) { Text("打开无障碍设置") }
                    Text(if (state.captureSystemGranted) "屏幕捕获系统许可当前可用" else "屏幕捕获需要 Android 系统同意")
                    TextButton(onClick = onRequestCaptureAuthorization) { Text("请求屏幕捕获系统许可") }
                    TextButton(onClick = {
                        app?.pairingGrants?.revokeTemporaryApprovals()
                        com.openandroidintelligence.control.ScreenHostRegistry.current?.revoke()
                        controller.refresh()
                    }) { Text("立即停止当前屏幕请求") }
                }
            }

            SectionLabel("这三项分别意味着什么")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    Text(
                        text = "读取、截图和控制分别配置全局模式。允许截图不包含控制；允许只读不会批准点击、滑动或输入。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "全局模式在本机持久保存，切换 Core 或账号保持不变。临时批准可撤销，进程重启后失效；Android 系统捕获同意与无障碍服务继续独立检查。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "密码框内容不会被读取，也不会被输入。系统级的限制不会被绕过。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 子页面 4: 安全策略说明与紧急停止。 */

/** 子页面 5: 授权与调用记录。 */
private fun AuditLogSubScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    presentation: SettingsPresentation,
) {
    SettingsPageScaffold(
        title = "操作记录",
        presentation = presentation,
        onBack = onBack,
    ) { padding ->
        // 审计流水是无上限增长的列表（每次敏感调用都会追加），这里是本页真正的
        // 性能点：逐行 item 化，新增记录只组合新行，而不是把整段 forEach 重新组合。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = Dimensions.ReadingWidth)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(
                    start = Dimensions.ScreenHorizontal,
                    end = Dimensions.ScreenHorizontal,
                    top = Dimensions.SpaceSmall,
                    bottom = Dimensions.SpaceMedium,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                item {
                    SectionLabel("全部记录（${uiState.auditEvents.size} 条）")
                }
                if (uiState.auditLines.isEmpty()) {
                    item {
                        SettingsSectionCard {
                            Text(
                                text = "还没有任何记录。\n每次授权变更和插件调用都会在这里留下一条记录，包含谁、什么时候、做了什么、结果如何。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(Dimensions.SpaceSmall),
                            )
                        }
                    }
                } else {
                    itemsIndexed(uiState.auditLines) { index, line ->
                        AuditLogRow(
                            line = line,
                            first = index == 0,
                            last = index == uiState.auditLines.lastIndex,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 审计流水单行：沿用分组卡片造型（首尾行外圆角、中间行平角），
 * 但每一行都是独立列表项——滚动时只组合可见行。
 */
private fun AuditLogRow(line: String, first: Boolean, last: Boolean) {
    val shape = RoundedCornerShape(
        topStart = if (first) AppRadius.Large else 0.dp,
        topEnd = if (first) AppRadius.Large else 0.dp,
        bottomStart = if (last) AppRadius.Large else 0.dp,
        bottomEnd = if (last) AppRadius.Large else 0.dp,
    )
    val failed = line.contains("outcome=DENIED") || line.contains("outcome=FAILED")
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            if (!first) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            Text(
                text = line,
                style = AppTextStyles.MonoSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = Dimensions.SpaceMedium,
                    vertical = Dimensions.SpaceSmall,
                ),
            )
        }
    }
}

/** 分组标题：M3 设置页规范（labelMedium + onSurfaceVariant，不抢正文注意力）。 */
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(
            start = Dimensions.SpaceMedium,
            top = Dimensions.SpaceSmall,
            bottom = Dimensions.SpaceTiny,
        ),
    )
}

private fun themeLabel(option: ThemePreference): String = when (option) {
    ThemePreference.SYSTEM -> "跟随系统"
    ThemePreference.LIGHT -> "浅色"
    ThemePreference.DARK -> "深色"
}
