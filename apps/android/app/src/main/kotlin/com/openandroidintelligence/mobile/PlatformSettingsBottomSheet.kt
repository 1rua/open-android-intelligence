package com.openandroidintelligence.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.openandroidintelligence.conversation.components.SettingsCardHeader
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.SettingsSwitchItem
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.kernel.PairingGrantCapabilities
import com.openandroidintelligence.ui.design.LocalMotionPolicy

enum class SettingsTab {
    GATEWAY,
    SECURITY,
    PLUGINS,
    TRANSPORT,
    APPEARANCE,
}

/**
 * 设置底板（Modal Bottom Sheet）：
 * 1. 顶部拖拽把手 + 标题「设置」与关闭按钮；
 * 2. 分类横向标签（网关账号、内核安全、设备插件、传输链路、外观动效），切换走标准淡入滑移转场；
 * 3. 每个分类内部改为「分组大卡片 + 官方 ListItem 条目」的 M3 设置页结构；
 * 4. 值全部来自真实运行时：活动 Gateway 资料、真实授权清单、真实审计与真实信任模式状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformSettingsBottomSheet(
    environment: PlatformSettingsEnvironment,
    runtime: GatewayRuntime,
    onDismissRequest: () -> Unit,
) {
    val phase by runtime.phase.collectAsState()
    var currentTab by remember { mutableStateOf(SettingsTab.GATEWAY) }
    val reduceMotion = LocalMotionPolicy.current.reduceMotion

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = Dimensions.SpaceCompact),
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = 4.dp),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Dimensions.ScreenHorizontal)
                .padding(bottom = Dimensions.SpaceLarge),
        ) {
            SettingsSheetTitle(onDismissRequest = onDismissRequest)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Dimensions.SpaceMedium),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                SettingsTabChip(
                    title = "网关账号",
                    icon = Icons.Default.Person,
                    selected = currentTab == SettingsTab.GATEWAY,
                    onClick = { currentTab = SettingsTab.GATEWAY },
                    modifier = Modifier.weight(1f),
                )
                SettingsTabChip(
                    title = "内核安全",
                    icon = Icons.Default.Security,
                    selected = currentTab == SettingsTab.SECURITY,
                    onClick = { currentTab = SettingsTab.SECURITY },
                    modifier = Modifier.weight(1f),
                )
                SettingsTabChip(
                    title = "设备插件",
                    icon = Icons.Default.Extension,
                    selected = currentTab == SettingsTab.PLUGINS,
                    onClick = { currentTab = SettingsTab.PLUGINS },
                    modifier = Modifier.weight(1f),
                )
                SettingsTabChip(
                    title = "传输链路",
                    icon = Icons.Default.Hub,
                    selected = currentTab == SettingsTab.TRANSPORT,
                    onClick = { currentTab = SettingsTab.TRANSPORT },
                    modifier = Modifier.weight(1f),
                )
                SettingsTabChip(
                    title = "外观动效",
                    icon = Icons.Default.Palette,
                    selected = currentTab == SettingsTab.APPEARANCE,
                    onClick = { currentTab = SettingsTab.APPEARANCE },
                    modifier = Modifier.weight(1f),
                )
            }

            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    (androidx.compose.animation.slideInHorizontally(
                        animationSpec = MotionSpecs.emphasized(reduceMotion),
                        initialOffsetX = { full -> if (targetState.ordinal >= initialState.ordinal) full / 8 else -full / 8 },
                    ) + androidx.compose.animation.fadeIn(MotionSpecs.fade(reduceMotion))) togetherWith
                        (androidx.compose.animation.slideOutHorizontally(
                            animationSpec = MotionSpecs.emphasized(reduceMotion),
                            targetOffsetX = { full -> if (targetState.ordinal >= initialState.ordinal) -full / 8 else full / 8 },
                        ) + androidx.compose.animation.fadeOut(MotionSpecs.fade(reduceMotion))) using
                        SizeTransform(clip = false)
                },
                label = "settings-tab-transition",
            ) { tab ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
                ) {
                    when (tab) {
                        SettingsTab.GATEWAY -> GatewayTabContent(
                            phase = phase,
                            runtime = runtime,
                            environment = environment,
                            onDismiss = onDismissRequest,
                        )
                        SettingsTab.SECURITY -> SecurityTabContent(environment = environment)
                        SettingsTab.PLUGINS -> PluginsTabContent()
                        SettingsTab.TRANSPORT -> TransportTabContent(phase = phase)
                        SettingsTab.APPEARANCE -> AppearanceTabContent(environment = environment)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSheetTitle(onDismissRequest: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Dimensions.SpaceCompact),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimensions.Icon),
            )
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = Dimensions.SpaceSmall),
            )
        }
        IconButton(
            onClick = onDismissRequest,
            modifier = Modifier.size(Dimensions.MinimumTouchTarget),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 分类胶囊：命中态用 secondaryContainer，避免和卡片的 surfaceContainer 抢层级。 */
@Composable
private fun SettingsTabChip(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(title, maxLines = 1) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.Small),
        modifier = modifier,
    )
}

/**
 * Tab 1: Gateway 账号资料与配对授权
 */
@Composable
private fun GatewayTabContent(
    phase: ConnectionPhase,
    runtime: GatewayRuntime,
    environment: PlatformSettingsEnvironment,
    onDismiss: () -> Unit,
) {
    val connected = phase as? ConnectionPhase.Connected
    val grantState by environment.pairingGrants.state.collectAsState()
    var showUnpairDialog by remember { mutableStateOf(false) }

    SettingsSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            SettingsCardHeader(
                title = "活动 Gateway 账号资料",
                trailing = { ConnectionBadge(connected = connected != null) },
            )
            Text(
                text = "当前手机绑定的逻辑 Agent Gateway 与独立配对信任关系。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DetailRow(
                label = "账号主体",
                value = connected?.username ?: "未登录",
                action = {
                    FilledTonalButton(
                        onClick = { runtime.restoreSessionIfAvailable() },
                        contentPadding = PaddingValues(horizontal = Dimensions.SpaceCompact),
                    ) {
                        Text("刷新凭据", style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
            DetailRow(
                label = "Gateway 节点地址",
                value = connected?.gatewayUrl ?: "—",
                monospace = true,
                trailing = { TransportBadge(phase) },
            )
            DetailRow(
                label = "配对会话标识",
                value = connected?.pairingSummary ?: "未返回配对摘要",
                monospace = true,
            )
        }
    }

    SettingsSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            SettingsCardHeader(title = "配对能力授权清单")
            Text(
                text = "手机端作为最终授权者，随时可撤销分配给当前 Gateway 的设备能力。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = grantState?.let { "本机授权版本 r${it.revision}" } ?: "未连接 Gateway，授权暂不可用",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsSwitchItem(
                headline = "读取与发送短信",
                supporting = "限制：每次交互须经手机确认",
                checked = grantState?.granted?.contains(PairingGrantCapabilities.SMS) == true,
                enabled = grantState != null,
                icon = Icons.Default.Sms,
                onCheckedChange = {
                    environment.pairingGrants.updatePrimitive(PairingGrantCapabilities.SMS, it)
                },
            )
            SettingsSwitchItem(
                headline = "屏幕上下文分析与圈选",
                supporting = "支持数字助理 Assist 选区截图",
                checked = grantState?.screenSelectionEnabled == true,
                enabled = grantState != null,
                icon = Icons.Default.Fullscreen,
                onCheckedChange = { environment.pairingGrants.updateScreenSelection(it) },
            )
            SettingsSwitchItem(
                headline = "系统通知推送",
                supporting = "后台低功耗推送服务",
                checked = grantState?.granted?.contains(PairingGrantCapabilities.NOTIFICATIONS) == true,
                enabled = grantState != null,
                icon = Icons.Default.Notifications,
                onCheckedChange = {
                    environment.pairingGrants.updatePrimitive(PairingGrantCapabilities.NOTIFICATIONS, it)
                },
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact),
    ) {
        FilledTonalButton(
            onClick = {
                runtime.logout(revokeRefresh = false)
                onDismiss()
            },
            modifier = Modifier.weight(1f),
        ) {
            Text("退出当前登录")
        }

        Button(
            onClick = { showUnpairDialog = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.weight(1f),
        ) {
            Text("解除配对")
        }
    }

    if (showUnpairDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairDialog = false },
            title = { Text("确认解除配对") },
            text = {
                Text(
                    "这会撤销当前设备的 Gateway 配对凭据和本机授权。当前版本未接入对话镜像擦除端口，已保存的镜像不会被此按钮假装清理。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runtime.logout(revokeRefresh = true)
                        showUnpairDialog = false
                        onDismiss()
                    },
                ) { Text("确认解除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ConnectionBadge(connected: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text = if (connected) "已配对 · 在线" else "未连接",
            style = MaterialTheme.typography.labelMedium,
            color = if (connected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = Dimensions.SpaceSmall, vertical = 4.dp),
        )
    }
}

@Composable
private fun TransportBadge(phase: ConnectionPhase) {
    val connected = phase as? ConnectionPhase.Connected
    val (label, error) = when {
        connected == null -> "未连接" to false
        !connected.transportSecurity.isEncrypted -> "未加密（HTTP）" to true
        connected.tlsSpkiSha256 != null -> "TLS 已固定" to false
        else -> "系统 CA 信任" to false
    }
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.ExtraSmall),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (error) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            modifier = Modifier.padding(horizontal = Dimensions.SpaceSmall, vertical = 2.dp),
        )
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (monospace) FontFamily.Monospace else null,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                trailing?.invoke()
            }
        }
        action?.invoke()
    }
}

/**
 * Tab 2: 内核安全与模式
 */
@Composable
private fun SecurityTabContent(environment: PlatformSettingsEnvironment) {
    var trustEnabled by remember { mutableStateOf(environment.trustMode.isEnabled()) }
    var showAckDialog by remember { mutableStateOf(false) }
    var emergencyStopped by remember { mutableStateOf(environment.kernel.isEmergencyStopped()) }
    var showEmergencyDialog by remember { mutableStateOf(false) }
    var stoppedCount by remember { mutableStateOf(0) }

    if (showAckDialog) {
        AlertDialog(
            onDismissRequest = { showAckDialog = false },
            title = { Text("开启开发者信任模式确认") },
            text = {
                Text("开启后，同进程原生插件将作为宿主可信代码运行，接管原生界面并直接访问已有数据。平台内核将不再承诺沙箱隔离。是否确认开启？")
            },
            confirmButton = {
                TextButton(onClick = {
                    val accepted = environment.trustMode.enable(
                        com.openandroidintelligence.kernel.DeveloperTrustMode.Acknowledgement(
                            com.openandroidintelligence.kernel.DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT,
                        ),
                    )
                    trustEnabled = accepted
                    showAckDialog = false
                }) {
                    Text("我理解风险并确认开启")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAckDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (showEmergencyDialog) {
        AlertDialog(
            onDismissRequest = { showEmergencyDialog = false },
            title = { Text("一键紧急停用确认") },
            text = {
                Text(
                    "将立即隔离全部已启用设备插件、关闭开发者信任模式，并切断平台内核的后续调用。" +
                        "该操作会记入安全审计，且只能通过重启进程恢复。是否确认执行？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    stoppedCount = environment.kernel.emergencyStop(
                        "emergency-" + System.currentTimeMillis(),
                    )
                    emergencyStopped = true
                    trustEnabled = false
                    showEmergencyDialog = false
                }) {
                    Text("确认紧急停用", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmergencyDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    SettingsSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            SettingsCardHeader(title = "Android 宿主运行安全模式")
            Text(
                "受保护模式（默认）：强制 WASM 沙箱隔离与资源限额，不可接管原生 UI。\n开发者信任模式：允许同进程 Native DEX/Kotlin 插件完全接管界面。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsSwitchItem(
                headline = "开发者信任模式 (Trust Mode)",
                supporting = if (trustEnabled) {
                    "已开启：原生代码可接管界面"
                } else {
                    "未开启（处于沙箱隔离保护状态）"
                },
                checked = trustEnabled,
                enabled = environment.allowDeveloperTrustMode,
                icon = Icons.Default.Security,
                onCheckedChange = { requested ->
                    if (requested) {
                        showAckDialog = true
                    } else {
                        environment.trustMode.disable()
                        trustEnabled = false
                    }
                },
            )
        }
    }

    SettingsSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            SettingsCardHeader(title = "内核安全原语监控")
            Text(
                "由平台内核固定定义并执行硬上限，插件无法擅自篡改。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "本页不展示未由内核端口提供的配额、代理或存储运行状态。需要查看具体插件时，请使用内核实际提供的状态接口。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val auditEvents by environment.auditSink.eventsFlow.collectAsState()
    val auditLines = auditEvents.map { environment.audit.render(it) }
    SettingsSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            SettingsCardHeader(
                title = "安全审计日志",
                trailing = {
                    Text(
                        "${auditLines.size} 条",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            if (auditLines.isEmpty()) {
                Text(
                    "本会话暂无安全审计记录。平台内核在敏感授权和原语调用时会在此记入不可篡改记录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                auditLines.take(5).forEach { line ->
                    Text(
                        text = "• $line",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    SettingsSectionCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            SettingsCardHeader(title = "系统级安全熔断 (Kill Switch)")
            Text(
                "在插件失控、密钥疑似泄露或出现异常授权时使用：立即隔离全部已启用插件、关闭开发者信任模式，并拒绝内核后续一切调用。此操作不可在应用内撤销。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = { showEmergencyDialog = true },
                enabled = !emergencyStopped,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.Small),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.padding(end = Dimensions.SpaceSmall),
                )
                Text("一键紧急停用")
            }
            if (emergencyStopped) {
                Text(
                    text = "已触发紧急停用：平台内核已切断，共隔离 $stoppedCount 个已启用插件。请重启应用以恢复。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Tab 3: 设备插件
 */
@Composable
private fun PluginsTabContent() {
    SettingsSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            SettingsCardHeader(title = "设备插件")
            Text(
                "当前组合根没有可观察的插件目录端口，因此不猜测已安装或启用状态。插件需由作者签名并经平台内核验证、隔离和授权。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Tab 4: 传输链路
 */
@Composable
private fun TransportTabContent(phase: ConnectionPhase) {
    SettingsSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            SettingsCardHeader(title = "网络传输通道与安全拓扑")
            Text(
                "• 默认传输: 直连 HTTPS + 证书指纹核验 (SPKI Pinned)\n" +
                    "• 事件流通道: Server-Sent Events (SSE) 断点自动重连\n" +
                    "• Tailscale Companion：当前未提供运行时状态端口",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (phase is ConnectionPhase.Connected && !phase.transportSecurity.isEncrypted) {
                Text(
                    text = "⚠ 当前连接未加密：地址为明文 HTTP，没有可核验的 Gateway 身份。" +
                        "账号口令、消息与附件内容对网络中的旁观者可读；已固定 TLS 身份的连接不会被允许这样降级。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Tab 5: 外观动效
 */
@Composable
private fun AppearanceTabContent(environment: PlatformSettingsEnvironment) {
    val settings by environment.appearance.settings.collectAsState()
    SettingsSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            SettingsCardHeader(title = "外观与动效偏好")
            Text(
                text = "系统动态取色在 Android 12+ 默认开启；关闭时回落到品牌默认配色。两项都是可持久化的本机偏好。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsCardHeader(title = "主题模式")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                ThemePreference.entries.forEach { option ->
                    FilterChip(
                        selected = settings.theme == option,
                        onClick = { environment.appearance.setTheme(option) },
                        label = {
                            Text(
                                when (option) {
                                    ThemePreference.SYSTEM -> "跟随系统"
                                    ThemePreference.LIGHT -> "浅色"
                                    ThemePreference.DARK -> "深色"
                                },
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsSwitchItem(
                headline = "系统动态取色",
                supporting = "从壁纸提取色调（Android 12+）。关闭时使用默认品牌配色。",
                checked = settings.dynamicColor,
                icon = Icons.Default.Palette,
                onCheckedChange = environment.appearance::setDynamicColor,
            )
            SettingsSwitchItem(
                headline = "减少动态",
                supporting = "使用平滑淡化替换位移与缩放动效。",
                checked = settings.reduceMotion,
                icon = Icons.Default.Animation,
                onCheckedChange = environment.appearance::setReduceMotion,
            )
        }
    }
}
