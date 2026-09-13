package com.openandroidintelligence.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.kernel.PairingGrantCapabilities

enum class SettingsTab {
    GATEWAY,
    SECURITY,
    PLUGINS,
    TRANSPORT,
    APPEARANCE,
}

/**
 * 设置底板（Settings Bottom Sheet），严格还原设计原型（截图 3）：
 * 1. 顶部拖拽把手 + 标题「设置」与关闭按钮；
 * 2. 四大分类横向标签：网关账号、内核安全、设备插件、传输链路；
 * 3. 网关账号：真实活动资料、刷新凭据、配对能力清单（短信/屏幕/剪贴板权限开关）、退出登录/解除配对；
 * 4. 内核安全：开发者信任模式开关（带安全确认弹窗）、内核安全原语、真实安全审计记录、一键紧急停用；
 * 5. 设备插件：真实查询 PluginKernel 已激活插件，无插件时真实呈现空状态说明；
 * 6. 传输链路：直接 HTTPS + SSE 默认链路与 Tailscale Companion 状态；
 * 7. 完全符合 Material Design 3 规范与系统动态取色。
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

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            // ===== 1. 标题行 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // ===== 2. 分类标签胶囊行 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsTabChip(
                    title = "网关账号",
                    icon = Icons.Default.Person,
                    selected = currentTab == SettingsTab.GATEWAY,
                    onClick = { currentTab = SettingsTab.GATEWAY },
                )
                SettingsTabChip(
                    title = "内核安全",
                    icon = Icons.Default.Security,
                    selected = currentTab == SettingsTab.SECURITY,
                    onClick = { currentTab = SettingsTab.SECURITY },
                )
                SettingsTabChip(
                    title = "设备插件",
                    icon = Icons.Default.Extension,
                    selected = currentTab == SettingsTab.PLUGINS,
                    onClick = { currentTab = SettingsTab.PLUGINS },
                )
                SettingsTabChip(
                    title = "传输链路",
                    icon = Icons.Default.Language,
                    selected = currentTab == SettingsTab.TRANSPORT,
                    onClick = { currentTab = SettingsTab.TRANSPORT },
                )
                SettingsTabChip(
                    title = "外观动效",
                    icon = Icons.Default.Palette,
                    selected = currentTab == SettingsTab.APPEARANCE,
                    onClick = { currentTab = SettingsTab.APPEARANCE },
                )
            }

            // ===== 3. 内容滚动区 =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (currentTab) {
                    SettingsTab.GATEWAY -> GatewayTabContent(
                        phase = phase,
                        runtime = runtime,
                        environment = environment,
                        onDismiss = onDismissRequest,
                    )
                    SettingsTab.SECURITY -> SecurityTabContent(environment = environment)
                    SettingsTab.PLUGINS -> PluginsTabContent(environment)
                    SettingsTab.TRANSPORT -> TransportTabContent(phase = phase)
                    SettingsTab.APPEARANCE -> AppearanceTabContent(environment = environment)
                }
            }
        }
    }
}

@Composable
private fun SettingsTabChip(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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

    // 卡片 1: 活动 Gateway 账号资料
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "活动 Gateway 账号资料",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (connected != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = if (connected != null) "已配对 · 在线" else "未连接",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (connected != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            Text(
                text = "当前手机绑定的逻辑 Agent Gateway 与独立配对信任关系。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("账号主体", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = connected?.username ?: "未登录",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                FilledTonalButton(
                    onClick = { runtime.restoreSessionIfAvailable() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("刷新凭据", style = MaterialTheme.typography.labelSmall)
                }
            }

            Column {
                Text("Gateway 节点地址", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = connected?.gatewayUrl ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Text(
                            text = when {
                                connected == null -> "未连接"
                                connected.tlsSpkiSha256.isNotBlank() -> "TLS 已固定"
                                else -> "系统 CA 信任"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (connected == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Column {
                Text("配对会话标识 (Pairing Key)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = connected?.pairingSummary ?: "未返回配对摘要",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // 卡片 2: 配对能力授权清单
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "配对能力授权清单 (Pairing Grants)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "手机端作为最终授权者，随时撤销分配给当前 Gateway 的设备能力。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = grantState?.let { "本机授权版本 r${it.revision}" } ?: "未连接 Gateway，授权暂不可用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GrantSwitchRow(
                title = "读取与发送短信 (SMS Broker)",
                subtitle = "限制：每次交互须经手机确认",
                checked = grantState?.granted?.contains(PairingGrantCapabilities.SMS) == true,
                enabled = grantState != null,
                onCheckedChange = {
                    environment.pairingGrants.updatePrimitive(PairingGrantCapabilities.SMS, it)
                },
            )
            GrantSwitchRow(
                title = "屏幕上下文分析与圈选",
                subtitle = "支持数字助理 Assist 选区截图",
                checked = grantState?.screenSelectionEnabled == true,
                enabled = grantState != null,
                onCheckedChange = { environment.pairingGrants.updateScreenSelection(it) },
            )
            GrantSwitchRow(
                title = "系统通知推送",
                subtitle = "后台低功耗推送服务",
                checked = grantState?.granted?.contains(PairingGrantCapabilities.NOTIFICATIONS) == true,
                enabled = grantState != null,
                onCheckedChange = {
                    environment.pairingGrants.updatePrimitive(PairingGrantCapabilities.NOTIFICATIONS, it)
                },
            )
        }
    }

    // 操作按钮：退出登录 与 解除配对
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
private fun GrantSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * Tab 2: 内核安全与模式
 */
@Composable
private fun SecurityTabContent(
    environment: PlatformSettingsEnvironment,
) {
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

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Android 宿主运行安全模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "受保护模式（默认）：强制 WASM 沙箱隔离与资源限额，不可接管原生 UI。\n开发者信任模式：允许同进程 Native DEX/Kotlin 插件完全接管界面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("开发者信任模式 (Trust Mode)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        if (trustEnabled) "已开启：原生代码可接管界面" else "未开启（处于沙箱隔离保护状态）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = trustEnabled,
                    enabled = environment.allowDeveloperTrustMode,
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
    }

    // 内核安全原语监控
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("内核安全原语监控 (Kernel Primitives)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("由平台内核固定定义并执行硬上限，插件无法擅自篡改。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text(
                "本页不展示未由内核端口提供的配额、代理或存储运行状态。需要查看具体插件时，请使用内核实际提供的状态接口。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 安全审计日志
    val auditEvents by environment.auditSink.eventsFlow.collectAsState()
    val auditLines = auditEvents.map { environment.audit.render(it) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("安全审计日志 (Audit Records)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${auditLines.size} 条", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }

            if (auditLines.isEmpty()) {
                Text(
                    "本会话暂无安全审计记录。平台内核在敏感授权和原语调用时会在此记入不可篡改记录。",
                    style = MaterialTheme.typography.bodySmall,
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

    // 一键紧急停用：系统级安全熔断，隔离全部插件并切断内核调用。
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("系统级安全熔断 (Kill Switch)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "在插件失控、密钥疑似泄露或出现异常授权时使用：立即隔离全部已启用插件、关闭开发者信任模式，并拒绝内核后续一切调用。此操作不可在应用内撤销。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = { showEmergencyDialog = true },
                enabled = !emergencyStopped,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("一键紧急停用", fontWeight = FontWeight.Bold)
            }

            if (emergencyStopped) {
                Text(
                    text = "已触发紧急停用：平台内核已切断，共隔离 $stoppedCount 个已启用插件。请重启应用以恢复。",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Tab 3: 设备插件
 */
@Composable
private fun PluginsTabContent(environment: PlatformSettingsEnvironment) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("设备插件 (Device Plugins)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "当前组合根没有可观察的插件目录端口，因此不猜测已安装或启用状态。插件需由作者签名并经平台内核验证、隔离和授权。",
                style = MaterialTheme.typography.bodySmall,
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
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("网络传输通道与安全拓扑", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "• 默认传输: 直连 HTTPS + 证书指纹核验 (SPKI Pinned)\n" +
                "• 事件流通道: Server-Sent Events (SSE) 断点自动重连\n" +
                "• Tailscale Companion：当前未提供运行时状态端口",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Tab 5: 外观动效
 */
@Composable
private fun AppearanceTabContent(environment: PlatformSettingsEnvironment) {
    val settings by environment.appearance.settings.collectAsState()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "外观与动效偏好",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "品牌默认使用松烟·硅石配色；系统动态取色和减少动态均为可持久化的本机偏好。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

            Text("主题模式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemePreference.values().forEach { option ->
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

            GrantSwitchRow(
                title = "系统动态取色",
                subtitle = "从壁纸提取色调（Android 12+）。关闭时使用松烟·硅石品牌色。",
                checked = settings.dynamicColor,
                enabled = true,
                onCheckedChange = environment.appearance::setDynamicColor,
            )

            GrantSwitchRow(
                title = "减少动态",
                subtitle = "使用平滑淡化替换位移与缩放动效。",
                checked = settings.reduceMotion,
                enabled = true,
                onCheckedChange = environment.appearance::setReduceMotion,
            )
        }
    }
}
