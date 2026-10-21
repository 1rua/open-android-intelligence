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
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sms
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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

/** 设置页面路由契约。 */
object SettingsRoutes {
    const val OVERVIEW = "settings/overview"
    const val APPEARANCE = "settings/appearance"
    const val GATEWAY = "settings/gateway"
    const val PAIRING = "settings/pairing"
    const val SECURITY = "settings/security"
    const val AUDIT_LOG = "settings/audit"
    const val PLUGINS = "settings/plugins"
    const val NOTIFICATIONS = "settings/notifications"
    const val APP_UPDATES = "settings/app-updates"
}

/**
 * 设置的呈现宿主。
 *
 * - [SCREEN]：整屏页面（登录页 / 工作台内打开）。系统栏安全区由每个子页自己的
 *   `Scaffold` 负责，页面内容在全屏窗口里正确避让状态栏与手势条。
 * - [BOTTOM_SHEET]：M3 底部弹层的内容。安全区归弹层所有（`ModalBottomSheet` 已在
 *   它自己的 `contentWindowInsets` 里扣过一份），内部 `Scaffold` 因此显式使用
 *   `WindowInsets(0)`：同一份安全区不能父子各扣一次，否则内容会被凭空推下去一截。
 *   首屏标题栏的返回箭头同时改为「关闭」语义——它关掉的是整个弹层，而不是退子页。
 */
enum class SettingsPresentation { SCREEN, BOTTOM_SHEET }

/** 弹层内容使用的零 inset：安全区归属见 [SettingsPresentation.BOTTOM_SHEET]。 */
private val NoWindowInsets = WindowInsets(0, 0, 0, 0)

/** 当前呈现方式下页面（Scaffold / 标题栏）应使用的底色。 */
@Composable
private fun settingsContainerColor(presentation: SettingsPresentation): Color =
    if (presentation == SettingsPresentation.BOTTOM_SHEET) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.surface
    }

/** 当前呈现方式下 Scaffold 内容区应扣的系统栏 inset。 */
@Composable
private fun settingsContentWindowInsets(presentation: SettingsPresentation): WindowInsets =
    if (presentation == SettingsPresentation.BOTTOM_SHEET) NoWindowInsets else WindowInsets.systemBars

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
@Composable
fun SettingsScreen(
    environment: PlatformSettingsEnvironment,
    onBack: () -> Unit,
    runtime: GatewayRuntime? = null,
    modifier: Modifier = Modifier,
    presentation: SettingsPresentation = SettingsPresentation.SCREEN,
) {
    val viewModel = remember(environment, runtime) {
        SettingsViewModel(environment = environment, runtime = runtime)
    }
    SettingsScreen(
        viewModel = viewModel,
        onBack = onBack,
        modifier = modifier,
        presentation = presentation,
    )
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    presentation: SettingsPresentation = SettingsPresentation.SCREEN,
) {
    val uiState by viewModel.uiState.collectAsState()
    val navController = rememberNavController()
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

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = SettingsRoutes.OVERVIEW,
            // 底色由各子页 Scaffold 负责：弹层里必须是 surfaceContainerLow，
            // 独立页面里是 surface，避免在弹层上再压一层不同的面色。
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
                presentation = presentation,
                onNavigate = { route ->
                    navController.navigate(route) {
                        launchSingleTop = true
                    }
                },
                onSetScreenSelectionGrant = viewModel::setScreenSelectionGrant,
                onSetNotificationsGrant = setReplyNotifications,
                onToggleTrustMode = { enable ->
                    if (enable) showTrustModeDialog = true else viewModel.disableTrustMode()
                },
                onRequestEmergencyStop = { showEmergencyDialog = true },
                onRequestUnpair = { showUnpairDialog = true },
            )
        }

        composable(SettingsRoutes.APPEARANCE) {
            AppearanceSubScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
                presentation = presentation,
                onSetTheme = viewModel::setTheme,
                onSetDynamicColor = viewModel::setDynamicColor,
                onSetPalette = viewModel::setPalette,
                onSetReduceMotion = viewModel::setReduceMotion,
            )
        }

        composable(SettingsRoutes.APP_UPDATES) {
            AppUpdateRoute(onBack = { navController.popBackStack() })
        }

        composable(SettingsRoutes.GATEWAY) {
            GatewaySubScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
                presentation = presentation,
                onRefreshSession = viewModel::refreshSession,
                onLogout = { viewModel.logout(revokeRefresh = true) },
                onSwitchAccount = { viewModel.chooseAnotherAccount(); onBack() },
                onRequestUnpair = { showUnpairDialog = true },
            )
        }

        composable(SettingsRoutes.PAIRING) {
            PairingSubScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
                presentation = presentation,
                onNavigate = { route -> navController.navigate(route) { launchSingleTop = true } },
                onSetScreenSelectionGrant = viewModel::setScreenSelectionGrant,
                onSetNotificationsGrant = setReplyNotifications,
            )
        }

        composable(SettingsRoutes.SECURITY) {
            SecuritySubScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
                presentation = presentation,
                onToggleTrustMode = { enable ->
                    if (enable) showTrustModeDialog = true else viewModel.disableTrustMode()
                },
                onRequestEmergencyStop = { showEmergencyDialog = true },
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
                    "这会撤销当前设备的 Gateway 配对凭据并清理本机授权。已建立的安全会话将被立即切断。",
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

/** 设置子页统一的 Scaffold：标题栏、底色与系统栏 inset 只在 [SettingsPresentation] 处分支一次。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
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
                title = { Text(title) },
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
                windowInsets = if (presentation == SettingsPresentation.BOTTOM_SHEET) {
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
@Composable
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
@Composable
private fun SettingsOverviewScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    presentation: SettingsPresentation,
    onNavigate: (String) -> Unit,
    onSetScreenSelectionGrant: (Boolean) -> Unit,
    onSetNotificationsGrant: (Boolean) -> Unit,
    onToggleTrustMode: (Boolean) -> Unit,
    onRequestEmergencyStop: () -> Unit,
    onRequestUnpair: () -> Unit,
) {
    BackHandler(onBack = onBack)

    SettingsPageScaffold(
        title = "设置与平台管理",
        presentation = presentation,
        onBack = onBack,
        // 弹层里这一下关掉的是整个面板，箭头语义必须说「关闭」；子页才用「返回」。
        closeSemantics = presentation == SettingsPresentation.BOTTOM_SHEET,
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
            }
            (androidx.compose.ui.platform.LocalContext.current.applicationContext as? OpenAndroidIntelligenceApplication)?.let { app ->
                val preferences=remember { com.openandroidintelligence.mobile.conversations.DebouncePreferences(app) }
                val gateway=(app.gatewayRuntime.phase.value as? ConnectionPhase.Connected)?.gatewayUrl
                var override by remember(gateway) { mutableStateOf(gateway!=null && preferences.hasOverride(gateway)) }
                var delayMillis by remember(gateway,override) { mutableStateOf(preferences.read(if (override) gateway else null).delay.inWholeMilliseconds.toInt()) }
                var extend by remember(gateway,override) { mutableStateOf(preferences.read(if (override) gateway else null).mode==com.openandroidintelligence.conversation.batch.DebounceMode.EXTEND_WINDOW) }
                SectionLabel("消息防抖")
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
                        ConnectionPhase.Negotiating -> "正在协商协议…"
                        ConnectionPhase.Authenticating -> "正在验证凭据…"
                        // 这里绝不直接打印 `phase.code`：它是异常的 message，可能带地址与对端正文，
                        // 而且对用户没有可执行意义。与登录页共用同一套「码 → 可操作说明」。
                        is ConnectionPhase.Failed ->
                            "连接失败：" + readableFailure(phase.code, CONNECTION_FAILURE_FALLBACK)
                    }
                    SettingsNavigationItem(
                        headline = "Gateway 连接管理",
                        supporting = statusText,
                        icon = Icons.Default.Hub,
                        tone = if (uiState.isGatewayConnected) SettingsTone.PRIMARY else SettingsTone.NEUTRAL,
                        onClick = { onNavigate(SettingsRoutes.GATEWAY) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val transportSecurity = uiState.connectedTransportSecurity
                    val transportSupporting = when {
                        !uiState.isGatewayConnected -> "未建立连接通道"
                        transportSecurity == TransportSecurity.PLAINTEXT -> "未加密（HTTP），无证书校验"
                        transportSecurity == TransportSecurity.TLS_PINNED -> "TLS 证书指纹固定 (SPKI Pinned)"
                        else -> "系统 CA 信任加密"
                    }
                    SettingsListItem(
                        headline = "传输安全拓扑",
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

            // 模块 3: 设备与配对授权
            SectionLabel("设备能力与配对授权")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact)) {
                    // 裁决 D5：授权版本是本机自增的变更序号，Gateway 不下发
                    // 权威版本——措辞不得让本地序号冒充网关权威值。
                    val grantSummary = if (uiState.isGatewayConnected) {
                        "授权状态本机保管（本机序号 r${uiState.grantRevision}，Gateway 未提供权威版本） · 手机随时可撤销"
                    } else {
                        "未连接 Gateway，授权暂不可用"
                    }
                    SettingsNavigationItem(
                        headline = "配对能力授权管理",
                        supporting = grantSummary,
                        icon = Icons.Default.Key,
                        onClick = { onNavigate(SettingsRoutes.PAIRING) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsNavigationItem(
                        headline = "短信读取",
                        supporting = "在插件管理中安装、启用短信插件，并为当前 Gateway 配对授权",
                        icon = Icons.Default.Sms,
                        onClick = { onNavigate(SettingsRoutes.PLUGINS) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ScreenSelectionCapabilityItem(
                        checked = uiState.isScreenSelectionGranted,
                        presentation = screenSelectionCapabilityPresentation(
                            grantsBound = uiState.pairingGrants != null,
                            attachmentStatusAgreed = uiState.conversationUi["attachment-status-v1"],
                        ),
                        icon = Icons.Default.Fullscreen,
                        onCheckedChange = onSetScreenSelectionGrant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                        headline = "通知采集设置",
                        onClick = { onNavigate(SettingsRoutes.NOTIFICATIONS) },
                        supporting = if (NotificationControlRegistry.policyPort().snapshot().installed) {
                            "选择本机应用、元数据与内容访问、投递模式与系统授权"
                        } else {
                            "通知采集未装配"
                        },
                    )
                }
            }

            // 模块 4: 运行安全模式
            SectionLabel("运行安全模式")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact)) {
                    SettingsSwitchItem(
                        headline = "开发者信任模式",
                        supporting = when {
                            !uiState.allowDeveloperTrustMode -> "当前分发渠道禁用开发者信任模式"
                            uiState.isTrustModeEnabled -> "已开启：原生插件可接管界面（宿主放宽沙箱）"
                            else -> "未开启：处于 WASM 沙箱隔离保护状态"
                        },
                        checked = uiState.isTrustModeEnabled,
                        enabled = uiState.allowDeveloperTrustMode && !uiState.isEmergencyStopped,
                        icon = if (uiState.isTrustModeEnabled) Icons.Default.LockOpen else Icons.Default.Lock,
                        tone = if (uiState.isTrustModeEnabled) SettingsTone.PRIMARY else SettingsTone.NEUTRAL,
                        onCheckedChange = onToggleTrustMode,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsNavigationItem(
                        headline = "内核安全策略与原语硬上限",
                        supporting = "强制 WASM 沙箱隔离与资源限额，不可越权接管原生 UI",
                        icon = Icons.Default.Shield,
                        onClick = { onNavigate(SettingsRoutes.SECURITY) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsNavigationItem(
                        headline = "插件管理",
                        supporting = "已安装插件、声明式设置项与 .alp 安装入口",
                        icon = Icons.Default.Extension,
                        onClick = { onNavigate(SettingsRoutes.PLUGINS) },
                    )
                }
            }

            // 模块 5: 安全审计记录
            SectionLabel("安全审计记录")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact)) {
                    SettingsNavigationItem(
                        headline = "内核安全审计流水",
                        supporting = "已记录 ${uiState.auditEvents.size} 条本地不可篡改操作流水",
                        icon = Icons.AutoMirrored.Filled.ReceiptLong,
                        onClick = { onNavigate(SettingsRoutes.AUDIT_LOG) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsListItem(
                        headline = "不可篡改审计规范",
                        supporting = "由底座内核在插件原语调用、授权裁决与用户确认时如实记录",
                        icon = Icons.Default.Shield,
                        tone = SettingsTone.NEUTRAL,
                    )
                }
            }

            // 模块 6: 危险操作与紧急控制
            // 危险分组不整卡涂 errorContainer：那样同色的 DANGER 徽标会与卡面融为一体、
            // 反而看不出危险。卡面保持中性面，危险只由徽标与文字承担。
            SectionLabel("危险操作与系统熔断")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact)) {
                    SettingsListItem(
                        headline = "一键系统级安全熔断 (Kill Switch)",
                        supporting = if (uiState.isEmergencyStopped) {
                            "已触发紧急停用，共隔离 ${uiState.emergencyStoppedCount} 个插件。请重启应用。"
                        } else {
                            "隔离已启用插件并关闭信任模式，切断内核调用（须重启恢复）。"
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
                            supporting = "撤销当前设备的 Gateway 配对凭据和本机授权",
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
@Composable
private fun AppearanceSubScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    presentation: SettingsPresentation,
    onSetTheme: (ThemePreference) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetPalette: (ThemePalette) -> Unit,
    onSetReduceMotion: (Boolean) -> Unit,
) {
    SettingsPageScaffold(
        title = "外观与动效",
        presentation = presentation,
        onBack = onBack,
    ) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            SectionLabel("主题模式选择")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    ThemePreference.entries.forEachIndexed { index, option ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        SettingsListItem(
                            headline = themeLabel(option),
                            supporting = when (option) {
                                ThemePreference.SYSTEM -> "跟随系统设置自动切换深浅配色"
                                ThemePreference.LIGHT -> "始终使用浅色高对比度主题"
                                ThemePreference.DARK -> "始终使用深色暗调主题，低眩光省电"
                            },
                            icon = Icons.Default.Palette,
                            selected = uiState.appearance.theme == option,
                            // 自绘行显式声明单选语义：读屏会朗读「已选中，单选框」，
                            // 而不是只把它当成一行可点文字。
                            modifier = Modifier.selectable(
                                selected = uiState.appearance.theme == option,
                                role = Role.RadioButton,
                                onClick = { onSetTheme(option) },
                            ),
                            trailing = {
                                RadioButton(
                                    selected = uiState.appearance.theme == option,
                                    onClick = null,
                                )
                            },
                        )
                    }
                }
            }

            SectionLabel("主题色")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    SettingsSwitchItem(
                        headline = "系统动态取色 (Monet)",
                        // 真实语义：默认跟随系统壁纸取色；关闭后回落到本机预设色板。
                        supporting = "默认开启：主题色由系统壁纸推导，装什么壁纸就是什么气质。" +
                            "关闭后可从下面的预设色板中挑一套。",
                        checked = uiState.appearance.dynamicColor,
                        icon = Icons.Default.ColorLens,
                        tone = SettingsTone.PRIMARY,
                        onCheckedChange = onSetDynamicColor,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ThemePalette.entries.forEachIndexed { index, palette ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        PaletteOptionRow(
                            palette = palette,
                            selected = !uiState.appearance.dynamicColor &&
                                uiState.appearance.palette == palette,
                            enabled = !uiState.appearance.dynamicColor,
                            onSelect = { onSetPalette(palette) },
                        )
                    }
                    Text(
                        text = if (uiState.appearance.dynamicColor) {
                            "动态取色已开启；关闭上面的开关即可选择预设色板。"
                        } else {
                            "当前使用预设色板；打开动态取色会改用系统壁纸配色。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = Dimensions.SpaceMedium,
                            vertical = Dimensions.SpaceSmall,
                        ),
                    )
                }
            }

            SectionLabel("动效与无障碍")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    SettingsSwitchItem(
                        headline = "减弱动态效果 (Reduce Motion)",
                        supporting = "停用位移与缩放动效，替换为标准无障碍淡入淡出。系统「动画时长 0」时自动等效。",
                        checked = uiState.appearance.reduceMotion,
                        icon = Icons.Default.Animation,
                        onCheckedChange = onSetReduceMotion,
                    )
                }
            }
        }
    }
}

/**
 * 预设主题色的一行。
 *
 * 用当前方案自己的 primary / primaryContainer / tertiary 画三个色点作为预览：
 * 用户看到的就是切换后界面真正会用的颜色，而不是另画一套"示意色"。
 * 动态取色开启时整行禁用（`enabled = false`），并在分组底部说明原因。
 */
@Composable
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

@Composable
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

/** 子页面 2: Gateway 连接与账号详细配置。 */
@Composable
private fun GatewaySubScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    presentation: SettingsPresentation,
    onRefreshSession: () -> Unit,
    onLogout: () -> Unit,
    onSwitchAccount: () -> Unit,
    onRequestUnpair: () -> Unit,
) {
    SettingsPageScaffold(
        title = "Gateway 连接详情",
        presentation = presentation,
        onBack = onBack,
    ) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            SectionLabel("节点与连接状态")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    SettingsListItem(
                        headline = "账号主体",
                        supporting = uiState.connectedUsername ?: "未登录",
                        icon = Icons.Default.Hub,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsListItem(
                        headline = "Gateway 节点地址",
                        supporting = uiState.connectedGatewayUrl ?: "—",
                        icon = Icons.Default.Security,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsListItem(
                        headline = "传输安全模式",
                        supporting = when (uiState.connectedTransportSecurity) {
                            TransportSecurity.PLAINTEXT -> "明文 HTTP（未加密）"
                            TransportSecurity.TLS_PINNED -> "TLS SPKI 指纹已绑定"
                            TransportSecurity.TLS_SYSTEM_TRUST -> "系统 CA 证书信任"
                            null -> "未连接"
                        },
                        icon = Icons.Default.Shield,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsListItem(
                        headline = "配对摘要",
                        // 裁决 D5：摘要只来自 Gateway 登录响应；两端都未下发时
                        // 如实说明，不得用本地合成值顶替。
                        supporting = uiState.pairingSummary ?: "Gateway 未提供配对摘要",
                        icon = Icons.Default.Key,
                    )
                }
            }

            if (uiState.isGatewayConnected) {
                SectionLabel("协商能力")
                NegotiatedCapabilitiesGroup(conversationUi = uiState.conversationUi)
                SettingsSectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                        SettingsListItem(
                            headline = "设备请求执行通路",
                            supporting = if (uiState.deviceRequestChannel != null) {
                                if (uiState.pluginRuntimesWired) "Gateway 支持 ${uiState.deviceRequestChannel}" else "Gateway 支持设备请求协议；本机插件执行链尚未接通"
                            } else {
                                "Gateway 未提供设备请求"
                            },
                            icon = Icons.Default.Security,
                        )
                    }
                }
            }

            if (uiState.isGatewayConnected) {
                SectionLabel("凭据与会话管理")
                SettingsSectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                        SettingsListItem(
                            headline = "刷新网关凭据",
                            supporting = if (uiState.isRefreshingSession) {
                                "正在向 Gateway 请求新凭据…"
                            } else {
                                "向 Gateway 请求刷新短期访问令牌"
                            },
                            icon = Icons.Default.Refresh,
                            enabled = !uiState.isRefreshingSession,
                            onClick = onRefreshSession,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsListItem(
                            headline = "退出当前登录",
                            supporting = "清除本地活动会话（保留配对密钥）",
                            icon = Icons.Default.Lock,
                            onClick = onLogout,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsListItem(
                            headline = "切换账号",
                            supporting = "保留当前账号资料和配对，从已保存账号重新选择",
                            icon = Icons.Default.Person,
                            onClick = onSwitchAccount,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsListItem(
                            headline = "解除设备配对",
                            supporting = "彻底撤销此设备配对与授权并重置会话",
                            icon = Icons.Default.DeleteForever,
                            tone = SettingsTone.DANGER,
                            onClick = onRequestUnpair,
                        )
                    }
                }
            }
        }
    }
}

/** 子页面 3: 设备能力与配对授权。 */
@Composable
private fun PairingSubScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    presentation: SettingsPresentation,
    onNavigate: (String) -> Unit,
    onSetScreenSelectionGrant: (Boolean) -> Unit,
    onSetNotificationsGrant: (Boolean) -> Unit,
) {
    SettingsPageScaffold(
        title = "设备能力授权清单",
        presentation = presentation,
        onBack = onBack,
    ) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            SectionLabel("可授权设备原语")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    Text(
                        text = "手机作为最终安全边界，随时可撤销分配给当前 Gateway 的设备能力。所有变更将即时持久化并记录至安全审计日志。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Dimensions.SpaceSmall),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsNavigationItem(
                        headline = "短信读取",
                        supporting = "在插件管理中安装、启用短信插件，并为当前 Gateway 配对授权",
                        icon = Icons.Default.Sms,
                        onClick = { onNavigate(SettingsRoutes.PLUGINS) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ScreenSelectionCapabilityItem(
                        checked = uiState.isScreenSelectionGranted,
                        presentation = screenSelectionCapabilityPresentation(
                            grantsBound = uiState.pairingGrants != null,
                            attachmentStatusAgreed = uiState.conversationUi["attachment-status-v1"],
                        ),
                        icon = Icons.Default.Fullscreen,
                        onCheckedChange = onSetScreenSelectionGrant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                }
            }

            SettingsSectionCard {
                SettingsNavigationItem(
                    headline = "通知采集设置",
                    supporting = "配置允许读取的应用与内容范围；在插件管理中授权当前 Gateway",
                    onClick = { onNavigate(SettingsRoutes.NOTIFICATIONS) },
                )
            }

            SectionLabel("配对元数据")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    SettingsListItem(
                        headline = "授权变更序号（本机）",
                        // 裁决 D5：r{N} 是本机授权状态的自增序号，不是 Gateway
                        // 的权威版本；Gateway 未提供版本信息，措辞如实区分。
                        supporting = "本机序号 r${uiState.grantRevision} · Gateway 未提供权威版本",
                        icon = Icons.Default.Key,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsListItem(
                        headline = "配对身份标识（本机合成）",
                        supporting = uiState.pairingGrants?.pairingId?.let { "$it · 由本机对配对范围合成，非 Gateway 下发" }
                            ?: "未绑定活动配对",
                        icon = Icons.Default.Security,
                    )
                }
            }
        }
    }
}

/** 子页面 4: 运行安全与信任模式。 */
@Composable
private fun SecuritySubScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    presentation: SettingsPresentation,
    onToggleTrustMode: (Boolean) -> Unit,
    onRequestEmergencyStop: () -> Unit,
) {
    SettingsPageScaffold(
        title = "运行安全模式",
        presentation = presentation,
        onBack = onBack,
    ) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            SectionLabel("宿主隔离策略")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    SettingsSwitchItem(
                        headline = "开发者信任模式 (Trust Mode)",
                        supporting = if (uiState.isTrustModeEnabled) {
                            "已开启：同进程原生插件可接管原生界面（沙箱隔离已放宽）"
                        } else {
                            "未开启：受保护模式，强制 WASM 沙箱强隔离保护"
                        },
                        checked = uiState.isTrustModeEnabled,
                        enabled = uiState.allowDeveloperTrustMode && !uiState.isEmergencyStopped,
                        icon = if (uiState.isTrustModeEnabled) Icons.Default.LockOpen else Icons.Default.Lock,
                        tone = if (uiState.isTrustModeEnabled) SettingsTone.PRIMARY else SettingsTone.NEUTRAL,
                        onCheckedChange = onToggleTrustMode,
                    )
                    if (!uiState.allowDeveloperTrustMode) {
                        Text(
                            text = "当前分发渠道（如 Google Play 构建）策略严禁启用开发者信任模式。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = Dimensions.SpaceSmall),
                        )
                    }
                }
            }

            // 裁决（条目 2-10）：这两项是设计说明，不是运行时数据——不用
            // ListItem 的「条目」造型伪装成可监控的指标，只以说明文本呈现。
            SectionLabel("内核隔离说明")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    Text(
                        text = "隔离机制说明",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "受保护模式下，平台内核对插件强制执行资源配额与内存墙隔离：插件运行在独立运行时中，无法越权接管界面或越过宿主调用系统能力。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "权限边界说明",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "插件可用的原语集合与上限由底座 PluginKernel 固化定义并在调用时裁决，插件无法单方面篡改自身权限；每一次授权与调用都会写入审计日志。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionLabel("系统安全熔断")
            // 卡面保持中性：DANGER 只在徽标与文案上表达，否则同色的徽标会"消失"在卡面里。
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    SettingsListItem(
                        headline = "一键紧急停用 (Kill Switch)",
                        supporting = if (uiState.isEmergencyStopped) {
                            "已触发紧急停用，共隔离 ${uiState.emergencyStoppedCount} 个插件。必须重启应用才能恢复。"
                        } else {
                            "立即隔离所有已启用插件并关闭信任模式，切断平台内核的后续调用。"
                        },
                        icon = Icons.Default.Warning,
                        tone = SettingsTone.DANGER,
                        enabled = !uiState.isEmergencyStopped,
                        onClick = onRequestEmergencyStop,
                    )
                }
            }
        }
    }
}

/** 子页面 5: 内核安全审计记录。 */
@Composable
private fun AuditLogSubScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    presentation: SettingsPresentation,
) {
    SettingsPageScaffold(
        title = "安全审计日志",
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
                    SectionLabel("不可篡改审计流水 (${uiState.auditEvents.size} 条)")
                }
                if (uiState.auditLines.isEmpty()) {
                    item {
                        SettingsSectionCard {
                            Text(
                                text = "本会话暂无安全审计记录。\n平台内核在敏感授权和原语调用时会在此记入不可篡改记录，包含主体、时间、动作与结果事实。",
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
@Composable
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
@Composable
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
