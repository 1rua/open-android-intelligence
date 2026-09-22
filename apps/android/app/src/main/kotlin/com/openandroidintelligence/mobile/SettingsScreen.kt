package com.openandroidintelligence.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.openandroidintelligence.conversation.components.CONNECTION_FAILURE_FALLBACK
import com.openandroidintelligence.conversation.components.SettingsCardHeader
import com.openandroidintelligence.conversation.components.SettingsIconBadge
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsNavigationItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.SettingsSwitchItem
import com.openandroidintelligence.conversation.components.SettingsTone
import com.openandroidintelligence.conversation.components.readableFailure
import com.openandroidintelligence.conversation.motion.AppTransitions
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.gateway.http.TransportSecurity
import com.openandroidintelligence.kernel.DeveloperTrustMode

/** 设置页面路由契约。 */
object SettingsRoutes {
    const val OVERVIEW = "settings/overview"
    const val APPEARANCE = "settings/appearance"
    const val GATEWAY = "settings/gateway"
    const val PAIRING = "settings/pairing"
    const val SECURITY = "settings/security"
    const val AUDIT_LOG = "settings/audit"
}

/**
 * 设置主界面（SettingsScreen）。
 *
 * 遵循严格的 Material 3 分组列表规范与深色暗调设计：
 * 1. 采用暗色卡片容器（surfaceContainer）配合 24dp 大圆角（AppRadius.Large）；
 * 2. 组内条目一律采用标准单行 ListItem 设计：前置轻量图标底衬 + 标题副标题两行排版 + 后置控件/导航箭头；
 * 3. 彻底清理任何假数据与无效占位按钮，全面对齐 SharedPreferences、GatewayRuntime、Kernel 与 AuditStore 真实数据；
 * 4. 内置 NavHost 驱动子配置页面流转（外观、网关、设备能力、内核安全、审计日志），关键操作弹出二次确认弹窗；
 * 5. UI State 与 ViewModel 解耦，具备高内聚低耦合特性。
 */
@Composable
fun SettingsScreen(
    environment: PlatformSettingsEnvironment,
    onBack: () -> Unit,
    runtime: GatewayRuntime? = null,
    modifier: Modifier = Modifier,
) {
    val viewModel = remember(environment, runtime) {
        SettingsViewModel(environment = environment, runtime = runtime)
    }
    SettingsScreen(
        viewModel = viewModel,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val navController = rememberNavController()

    var showTrustModeDialog by remember { mutableStateOf(false) }
    var showEmergencyDialog by remember { mutableStateOf(false) }
    var showUnpairDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }

    NavHost(
        navController = navController,
        startDestination = SettingsRoutes.OVERVIEW,
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        enterTransition = { AppTransitions.navEnter(uiState.appearance.reduceMotion) },
        exitTransition = { AppTransitions.navExit(uiState.appearance.reduceMotion) },
        popEnterTransition = { AppTransitions.navPopEnter(uiState.appearance.reduceMotion) },
        popExitTransition = { AppTransitions.navPopExit(uiState.appearance.reduceMotion) },
    ) {
        composable(SettingsRoutes.OVERVIEW) {
            SettingsOverviewScreen(
                uiState = uiState,
                onBack = onBack,
                onNavigate = { route ->
                    navController.navigate(route) {
                        launchSingleTop = true
                    }
                },
                onSetTheme = viewModel::setTheme,
                onSetDynamicColor = viewModel::setDynamicColor,
                onSetReduceMotion = viewModel::setReduceMotion,
                onSetSmsGrant = viewModel::setSmsGrant,
                onSetScreenSelectionGrant = viewModel::setScreenSelectionGrant,
                onSetNotificationsGrant = viewModel::setNotificationsGrant,
                onToggleTrustMode = { enable ->
                    if (enable) showTrustModeDialog = true else viewModel.disableTrustMode()
                },
                onRequestEmergencyStop = { showEmergencyDialog = true },
                onRequestUnpair = { showUnpairDialog = true },
                onOpenThemeDialog = { showThemeDialog = true },
            )
        }

        composable(SettingsRoutes.APPEARANCE) {
            AppearanceSubScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
                onSetTheme = viewModel::setTheme,
                onSetDynamicColor = viewModel::setDynamicColor,
                onSetReduceMotion = viewModel::setReduceMotion,
            )
        }

        composable(SettingsRoutes.GATEWAY) {
            GatewaySubScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
                onRefreshSession = viewModel::refreshSession,
                onLogout = { viewModel.logout(revokeRefresh = false) },
                onRequestUnpair = { showUnpairDialog = true },
            )
        }

        composable(SettingsRoutes.PAIRING) {
            PairingSubScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
                onSetSmsGrant = viewModel::setSmsGrant,
                onSetScreenSelectionGrant = viewModel::setScreenSelectionGrant,
                onSetNotificationsGrant = viewModel::setNotificationsGrant,
            )
        }

        composable(SettingsRoutes.SECURITY) {
            SecuritySubScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
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
            )
        }
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
                        viewModel.logout(revokeRefresh = true)
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

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("选择主题模式") },
            text = {
                Column {
                    ThemePreference.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setTheme(option)
                                    showThemeDialog = false
                                }
                                .padding(vertical = Dimensions.SpaceSmall),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = uiState.appearance.theme == option,
                                onClick = {
                                    viewModel.setTheme(option)
                                    showThemeDialog = false
                                },
                            )
                            Text(
                                text = themeLabel(option),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = Dimensions.SpaceSmall),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("关闭") }
            },
        )
    }
}

/** 设置主览界面。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsOverviewScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onSetTheme: (ThemePreference) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetReduceMotion: (Boolean) -> Unit,
    onSetSmsGrant: (Boolean) -> Unit,
    onSetScreenSelectionGrant: (Boolean) -> Unit,
    onSetNotificationsGrant: (Boolean) -> Unit,
    onToggleTrustMode: (Boolean) -> Unit,
    onRequestEmergencyStop: () -> Unit,
    onRequestUnpair: () -> Unit,
    onOpenThemeDialog: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置与平台管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = Dimensions.ScreenHorizontal,
                    vertical = Dimensions.SpaceSmall,
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
        ) {
            // 模块 1: 外观与动效
            SectionLabel("外观与动效")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact)) {
                    SettingsListItem(
                        headline = "主题模式",
                        supporting = when (uiState.appearance.theme) {
                            ThemePreference.SYSTEM -> "跟随系统设置自动切换深浅配色"
                            ThemePreference.LIGHT -> "始终使用浅色高对比度主题"
                            ThemePreference.DARK -> "始终使用深色暗调主题，低眩光省电"
                        },
                        icon = Icons.Default.Palette,
                        onClick = onOpenThemeDialog,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = themeLabel(uiState.appearance.theme),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = Dimensions.SpaceTiny),
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(Dimensions.SmallIcon),
                                )
                            }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsSwitchItem(
                        headline = "系统动态取色",
                        supporting = "跟随系统壁纸提取主题色调（Android 12+）",
                        checked = uiState.appearance.dynamicColor,
                        icon = Icons.Default.ColorLens,
                        tone = SettingsTone.PRIMARY,
                        onCheckedChange = onSetDynamicColor,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsSwitchItem(
                        headline = "减弱动态效果",
                        supporting = "停用位移与缩放，保留平滑淡入淡出",
                        checked = uiState.appearance.reduceMotion,
                        icon = Icons.Default.Animation,
                        onCheckedChange = onSetReduceMotion,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsNavigationItem(
                        headline = "显示与样式详细配置",
                        supporting = "查看详细的主题说明与动效策略",
                        icon = Icons.Default.Palette,
                        onClick = { onNavigate(SettingsRoutes.APPEARANCE) },
                    )
                }
            }

            // 模块 2: 网关与账号
            SectionLabel("网关与账号")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact)) {
                    val phase = uiState.connectionPhase
                    val statusText = when (phase) {
                        is ConnectionPhase.Connected -> "已连接 · " + phase.username
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
                                    modifier = Modifier.padding(horizontal = Dimensions.SpaceSmall, vertical = 2.dp),
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
                    val grantSummary = if (uiState.isGatewayConnected) {
                        "本机授权版本 r${uiState.grantRevision} · 手机随时可撤销"
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
                    SettingsSwitchItem(
                        headline = "读取与发送短信",
                        supporting = "限制：每次交互须经手机确认",
                        checked = uiState.isSmsGranted,
                        enabled = uiState.pairingGrants != null,
                        icon = Icons.Default.Sms,
                        onCheckedChange = onSetSmsGrant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsSwitchItem(
                        headline = "屏幕上下文分析与圈选",
                        supporting = "支持数字助理 Assist 选区截图",
                        checked = uiState.isScreenSelectionGranted,
                        enabled = uiState.pairingGrants != null,
                        icon = Icons.Default.Fullscreen,
                        onCheckedChange = onSetScreenSelectionGrant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsSwitchItem(
                        headline = "系统通知推送",
                        supporting = "后台低功耗推送服务",
                        checked = uiState.isNotificationsGranted,
                        enabled = uiState.pairingGrants != null,
                        icon = Icons.Default.Notifications,
                        onCheckedChange = onSetNotificationsGrant,
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
            SectionLabel("危险操作与系统熔断")
            SettingsSectionCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
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
                                tint = MaterialTheme.colorScheme.onErrorContainer,
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
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
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

/** 子页面 1: 外观与动效详细配置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSubScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onSetTheme: (ThemePreference) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetReduceMotion: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("外观与动效") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = Dimensions.ScreenHorizontal,
                    vertical = Dimensions.SpaceSmall,
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
        ) {
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
                            onClick = { onSetTheme(option) },
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

            SectionLabel("动态色彩与无障碍")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    SettingsSwitchItem(
                        headline = "系统动态取色 (Monet)",
                        supporting = "从壁纸提取色调（Android 12+）。关闭时回落至品牌默认配色。",
                        checked = uiState.appearance.dynamicColor,
                        icon = Icons.Default.ColorLens,
                        tone = SettingsTone.PRIMARY,
                        onCheckedChange = onSetDynamicColor,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsSwitchItem(
                        headline = "减弱动态效果 (Reduce Motion)",
                        supporting = "停用位移与缩放动效，替换为标准无障碍淡入淡出。",
                        checked = uiState.appearance.reduceMotion,
                        icon = Icons.Default.Animation,
                        onCheckedChange = onSetReduceMotion,
                    )
                }
            }
        }
    }
}

/** 子页面 2: Gateway 连接与账号详细配置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GatewaySubScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onRefreshSession: () -> Unit,
    onLogout: () -> Unit,
    onRequestUnpair: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gateway 连接详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = Dimensions.ScreenHorizontal,
                    vertical = Dimensions.SpaceSmall,
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
        ) {
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
                        supporting = uiState.pairingSummary ?: "未返回配对摘要",
                        icon = Icons.Default.Key,
                    )
                }
            }

            if (uiState.isGatewayConnected) {
                SectionLabel("凭据与会话管理")
                SettingsSectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                        SettingsListItem(
                            headline = "刷新网关凭据",
                            supporting = "向 Gateway 请求刷新短期访问令牌",
                            icon = Icons.Default.Refresh,
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingSubScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onSetSmsGrant: (Boolean) -> Unit,
    onSetScreenSelectionGrant: (Boolean) -> Unit,
    onSetNotificationsGrant: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设备能力授权清单") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = Dimensions.ScreenHorizontal,
                    vertical = Dimensions.SpaceSmall,
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
        ) {
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
                    SettingsSwitchItem(
                        headline = "读取与发送短信",
                        supporting = "org.openandroidintelligence.sms.query@1.0.0 (交互须经本地确认)",
                        checked = uiState.isSmsGranted,
                        enabled = uiState.pairingGrants != null,
                        icon = Icons.Default.Sms,
                        onCheckedChange = onSetSmsGrant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsSwitchItem(
                        headline = "屏幕上下文分析与圈选",
                        supporting = "允许数字助理获取当前屏幕快照并进行多模态分析",
                        checked = uiState.isScreenSelectionGranted,
                        enabled = uiState.pairingGrants != null,
                        icon = Icons.Default.Fullscreen,
                        onCheckedChange = onSetScreenSelectionGrant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsSwitchItem(
                        headline = "系统通知推送",
                        supporting = "org.openandroidintelligence.notifications.query@1.0.0 (后台通道)",
                        checked = uiState.isNotificationsGranted,
                        enabled = uiState.pairingGrants != null,
                        icon = Icons.Default.Notifications,
                        onCheckedChange = onSetNotificationsGrant,
                    )
                }
            }

            SectionLabel("配对元数据")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    SettingsListItem(
                        headline = "本机授权版本",
                        supporting = "r${uiState.grantRevision}",
                        icon = Icons.Default.Key,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsListItem(
                        headline = "配对身份标识",
                        supporting = uiState.pairingGrants?.pairingId ?: "未绑定活动配对",
                        icon = Icons.Default.Security,
                    )
                }
            }
        }
    }
}

/** 子页面 4: 运行安全与信任模式。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecuritySubScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onToggleTrustMode: (Boolean) -> Unit,
    onRequestEmergencyStop: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行安全模式") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = Dimensions.ScreenHorizontal,
                    vertical = Dimensions.SpaceSmall,
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
        ) {
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

            SectionLabel("内核安全原语监控")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    SettingsListItem(
                        headline = "平台内核隔离原语",
                        supporting = "受保护模式强制执行资源配额与内存墙隔离，插件无法越权接管界面。",
                        icon = Icons.Default.Shield,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsListItem(
                        headline = "安全原语硬上限",
                        supporting = "由底座 PluginKernel 固化定义并裁决，插件无法单方面篡改权限。",
                        icon = Icons.Default.Security,
                    )
                }
            }

            SectionLabel("系统安全熔断")
            SettingsSectionCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuditLogSubScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("安全审计日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = Dimensions.ScreenHorizontal,
                    vertical = Dimensions.SpaceSmall,
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
        ) {
            SectionLabel("不可篡改审计流水 (${uiState.auditEvents.size} 条)")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    if (uiState.auditLines.isEmpty()) {
                        Text(
                            text = "本会话暂无安全审计记录。\n平台内核在敏感授权和原语调用时会在此记入不可篡改记录，包含主体、时间、动作与结果事实。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Dimensions.SpaceSmall),
                        )
                    } else {
                        uiState.auditLines.forEachIndexed { index, line ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (line.contains("outcome=DENIED") || line.contains("outcome=FAILED")) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(
                                    horizontal = Dimensions.SpaceSmall,
                                    vertical = Dimensions.SpaceSmall,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 分组标题：M3 规范小标头，使用 primary 色彩与 labelLarge 字体。 */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
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

