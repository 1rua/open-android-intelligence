package com.openandroidintelligence.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.openandroidintelligence.conversation.components.SettingsCardHeader
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.SettingsSwitchItem
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.gateway.http.TransportSecurity
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.ObservableAuditSink
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.kernel.PluginKernel

/** Distribution policy from the build flavor; the Play build cannot unlock trust mode. */
data class DistributionPolicy(
    val allowRuntimePlugins: Boolean,
    val allowDeveloperTrustMode: Boolean,
)

/** Everything the settings screen reads, wired from the composition root. */
data class PlatformSettingsEnvironment(
    val trustMode: DeveloperTrustMode,
    val audit: AndroidAuditStore,
    val auditSink: ObservableAuditSink,
    val allowDeveloperTrustMode: Boolean,
    /** The kernel the emergency cut-off acts on. */
    val kernel: PluginKernel,
    val pairingGrants: PairingGrantStateHolder,
    val appearance: AppearancePreferences,
)

/**
 * 平台管理主界面。
 *
 * 布局遵循 Android 官方设置页：外层是 `surface`，每个业务分组是一张 24dp 大圆角、
 * `surfaceContainer` 背景的容器卡片，组内条目统一使用官方 `ListItem`（前置图标底衬 +
 * 标题 + 辅助说明 + 后置控件），外边距固定 20dp，组内按 8dp 栅格推进。
 *
 * 值仍然是真实运行时事实：信任模式开关驱动内核的 [DeveloperTrustMode]，
 * 审计列表渲染真实记录，插件卡片如实说明当前構建注册了什么。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformSettingsScreen(
    environment: PlatformSettingsEnvironment,
    onBack: () -> Unit,
    runtime: GatewayRuntime? = null,
    modifier: Modifier = Modifier,
) {
    var trustEnabled by remember { mutableStateOf(environment.trustMode.isEnabled()) }
    var showAcknowledgement by remember { mutableStateOf(false) }
    var emergencyStopped by remember { mutableStateOf(environment.kernel.isEmergencyStopped()) }
    var showEmergencyDialog by remember { mutableStateOf(false) }
    var stoppedCount by remember { mutableStateOf(0) }
    val auditEvents by environment.auditSink.eventsFlow.collectAsState()
    val auditLines = auditEvents.map { environment.audit.render(it) }
    val appearance by environment.appearance.settings.collectAsState()
    val runtimePhase = runtime?.phase?.collectAsState()?.value

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
        modifier = modifier,
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
            SectionLabel("外观与动效")
            AppearanceSection(
                settings = appearance,
                onThemeChange = environment.appearance::setTheme,
                onDynamicColorChange = environment.appearance::setDynamicColor,
                onReduceMotionChange = environment.appearance::setReduceMotion,
            )

            runtimePhase?.let { phase ->
                SectionLabel("Gateway 连接")
                GatewaySection(phase = phase)
            }

            SectionLabel("运行安全模式")
            TrustModeSection(
                trustEnabled = trustEnabled,
                allowTrustMode = environment.allowDeveloperTrustMode,
                onToggle = { requested ->
                    if (requested) {
                        showAcknowledgement = true
                    } else {
                        environment.trustMode.disable()
                        trustEnabled = false
                    }
                },
            )

            SectionLabel("设备插件")
            SettingsSectionCard {
                Text(
                    text = "当前组合根没有可观察的插件目录端口，因此不猜测已安装或启用状态。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Dimensions.SpaceSmall),
                )
                Text(
                    text = "插件需由作者签名并经平台内核验证、隔离和授权。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionLabel("协议与身份")
            SettingsSectionCard {
                ProtocolRow("网络路径", "直连 HTTPS + SSE（默认）")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ProtocolRow("请求认证", "设备密钥签名 + 短期访问令牌")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ProtocolRow("事件恢复", "SSE 光标断点续传")
            }

            SectionLabel("安全审计记录")
            AuditSection(lines = auditLines)

            SectionLabel("紧急停用")
            EmergencySection(
                stopped = emergencyStopped,
                stoppedCount = stoppedCount,
                onStop = { showEmergencyDialog = true },
            )
        }
    }

    if (showAcknowledgement) {
        AlertDialog(
            onDismissRequest = { showAcknowledgement = false },
            title = { Text("开启开发者信任模式") },
            text = { Text(DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val accepted = environment.trustMode.enable(
                            DeveloperTrustMode.Acknowledgement(
                                DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT,
                            ),
                        )
                        trustEnabled = accepted
                        showAcknowledgement = false
                    },
                ) { Text("我已知晓并开启") }
            },
            dismissButton = {
                TextButton(onClick = { showAcknowledgement = false }) { Text("取消") }
            },
        )
    }

    if (showEmergencyDialog) {
        AlertDialog(
            onDismissRequest = { showEmergencyDialog = false },
            title = { Text("确认紧急停用") },
            text = {
                Text(
                    "这会让平台内核拒绝后续插件调用，并关闭开发者信任模式。操作完成后必须重启应用才能恢复。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        stoppedCount = environment.kernel.emergencyStop(
                            "emergency-" + System.currentTimeMillis(),
                        )
                        emergencyStopped = true
                        trustEnabled = false
                        showEmergencyDialog = false
                    },
                ) { Text("确认停用", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showEmergencyDialog = false }) { Text("取消") }
            },
        )
    }
}

/** 分组标题：放在大卡片上方，字体走 labelLarge + onSurfaceVariant。 */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelLarge) {
        Text(
            text = text,
            modifier = modifier.padding(start = Dimensions.SpaceMedium, top = Dimensions.SpaceSmall),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AppearanceSection(
    settings: AppearanceSettings,
    onThemeChange: (ThemePreference) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onReduceMotionChange: (Boolean) -> Unit,
) {
    SettingsSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact)) {
            SettingsCardHeader(title = "主题模式")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemePreference.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = settings.theme == option,
                        onClick = { onThemeChange(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemePreference.entries.size),
                        label = { Text(themeLabel(option)) },
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsSwitchItem(
                headline = "系统动态取色",
                supporting = "跟随系统壁纸提取色调（Android 12+）；关闭时使用品牌默认配色。",
                checked = settings.dynamicColor,
                icon = Icons.Default.Palette,
                onCheckedChange = onDynamicColorChange,
            )
            SettingsSwitchItem(
                headline = "减少动态",
                supporting = "停用位移与缩放，保留短淡化并完整保留交互语义。",
                checked = settings.reduceMotion,
                icon = Icons.Default.Animation,
                onCheckedChange = onReduceMotionChange,
            )
        }
    }
}

private fun themeLabel(option: ThemePreference): String = when (option) {
    ThemePreference.SYSTEM -> "跟随系统"
    ThemePreference.LIGHT -> "浅色"
    ThemePreference.DARK -> "深色"
}

@Composable
private fun GatewaySection(phase: ConnectionPhase) {
    SettingsSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            when (phase) {
                is ConnectionPhase.Connected -> {
                    GatewayRow("账号", phase.username)
                    GatewayRow("节点地址", phase.gatewayUrl, monospace = true)
                    GatewayRow(
                        "传输安全",
                        when (phase.transportSecurity) {
                            TransportSecurity.PLAINTEXT -> "未加密（HTTP），Gateway 身份未校验"
                            TransportSecurity.TLS_PINNED -> "TLS 已固定"
                            TransportSecurity.TLS_SYSTEM_TRUST -> "系统 CA 信任"
                        },
                        monospace = true,
                        error = !phase.transportSecurity.isEncrypted,
                    )
                }

                ConnectionPhase.Disconnected -> Text("未连接 Gateway", style = MaterialTheme.typography.bodyMedium)
                ConnectionPhase.Negotiating -> Text("正在协商 Gateway 协议…", style = MaterialTheme.typography.bodyMedium)
                ConnectionPhase.Authenticating -> Text("正在验证 Gateway 凭据…", style = MaterialTheme.typography.bodyMedium)
                is ConnectionPhase.Failed -> {
                    Text(
                        "Gateway 连接失败",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = phase.code,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GatewayRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    error: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TrustModeSection(
    trustEnabled: Boolean,
    allowTrustMode: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    SettingsSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            SettingsCardHeader(title = "开发者信任模式 (Trust Mode)")
            Text(
                text = "受保护模式（默认）：强制 WASM 沙箱隔离与资源限额，不可接管原生 UI。\n" +
                    "开发者信任模式：允许同进程原生插件完全接管界面。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsSwitchItem(
                headline = "开发者信任模式",
                supporting = if (trustEnabled) {
                    "已开启：原生插件可接管界面（宿主不再承诺隔离）"
                } else {
                    "未开启：处于 WASM 沙箱隔离保护状态"
                },
                checked = trustEnabled,
                enabled = allowTrustMode,
                icon = Icons.Default.Lock,
                onCheckedChange = onToggle,
            )
            if (!allowTrustMode) {
                Text(
                    text = "当前分发渠道不允许开启开发者信任模式。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AuditSection(lines: List<String>) {
    SettingsSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            SettingsCardHeader(
                title = "内核安全审计 (Audit Records)",
                trailing = {
                    Text(
                        "${lines.size} 条",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            if (lines.isEmpty()) {
                Text(
                    text = "本会话尚无审计记录。记录由平台内核在插件执行、权限裁决与用户确认时写入，仅包含主体、时间、动作与结果。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (line.contains("outcome=DENIED") || line.contains("outcome=FAILED")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmergencySection(
    stopped: Boolean,
    stoppedCount: Int,
    onStop: () -> Unit,
) {
    SettingsSectionCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            SettingsCardHeader(title = "系统级安全熔断 (Kill Switch)")
            Text(
                text = "调用平台内核的全局熔断，隔离已启用插件并关闭开发者信任模式。该操作只能通过重启进程恢复。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onStop,
                enabled = !stopped,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.Small),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Warning, null, modifier = Modifier.padding(end = Dimensions.SpaceSmall))
                Text("一键紧急停用")
            }
            if (stopped) {
                Text(
                    text = "已触发紧急停用，隔离 $stoppedCount 个已启用插件。请重启应用。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ProtocolRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimensions.SpaceCompact),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.ExtraSmall),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                text = "已启用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = Dimensions.SpaceSmall, vertical = 2.dp),
            )
        }
    }
}
