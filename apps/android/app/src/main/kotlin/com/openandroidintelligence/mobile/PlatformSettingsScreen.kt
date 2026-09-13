package com.openandroidintelligence.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
 * The platform management surface.
 *
 * Every value here is a real runtime fact: the trust mode switch drives the
 * kernel's [DeveloperTrustMode] (including its required acknowledgement), the
 * audit list renders actual recorded events, and the plugin card states what
 * this build has actually registered — an honest empty state, never a fixture.
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
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppearanceCard(
                settings = appearance,
                onThemeChange = environment.appearance::setTheme,
                onDynamicColorChange = environment.appearance::setDynamicColor,
                onReduceMotionChange = environment.appearance::setReduceMotion,
            )

            runtimePhase?.let { phase ->
                SettingsCard(title = "Gateway 连接") {
                    when (phase) {
                        is ConnectionPhase.Connected -> {
                            Text(
                                text = "已连接 ${phase.username}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = phase.gatewayUrl,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = "TLS 身份：${phase.tlsSpkiSha256}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ConnectionPhase.Disconnected -> Text("未连接 Gateway", style = MaterialTheme.typography.bodyMedium)
                        ConnectionPhase.Negotiating -> Text("正在协商 Gateway 协议…", style = MaterialTheme.typography.bodyMedium)
                        ConnectionPhase.Authenticating -> Text("正在验证 Gateway 凭据…", style = MaterialTheme.typography.bodyMedium)
                        is ConnectionPhase.Failed -> {
                            Text("Gateway 连接失败", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
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

            SettingsCard(title = "运行安全模式") {
                SettingsRow(
                    title = "开发者信任模式",
                    subtitle = if (trustEnabled) {
                        "已开启：原生插件可接管界面（宿主不再承诺隔离）"
                    } else {
                        "未开启：处于 WASM 沙箱隔离保护状态"
                    },
                    enabled = environment.allowDeveloperTrustMode,
                    checked = trustEnabled,
                    onCheckedChange = { requested ->
                        if (requested) {
                            showAcknowledgement = true
                        } else {
                            environment.trustMode.disable()
                            trustEnabled = false
                        }
                    },
                )
                if (!environment.allowDeveloperTrustMode) {
                    Text(
                        text = "当前分发渠道不允许开启开发者信任模式",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsCard(title = "设备插件") {
                Text(
                    text = "设备插件目录未提供",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "当前组合根没有可观察的插件列表端口，因此不猜测已安装、启用或授权状态。插件仍由平台内核负责验证、隔离与授权。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsCard(
                title = "安全审计记录",
                trailing = { Text("${auditLines.size} 条", style = MaterialTheme.typography.labelSmall) },
            ) {
                if (auditLines.isEmpty()) {
                    Text(
                        text = "本会话尚无审计记录。记录由平台内核在插件执行、权限裁决与用户确认时写入，仅包含主体、时间、动作与结果。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    auditLines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (line.contains("outcome=DENIED") || line.contains("outcome=FAILED")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }

            SettingsCard(title = "协议与身份") {
                Text(
                    text = "网络路径：直连 HTTPS + SSE（默认）",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "请求认证：设备密钥签名 + 短期访问令牌",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "事件恢复：SSE 光标断点续传",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SettingsCard(title = "紧急停用") {
                Text(
                    text = "调用平台内核的全局熔断，隔离已启用插件并关闭开发者信任模式。该操作只能通过重启进程恢复。",
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
                    Text("一键紧急停用")
                }
                if (emergencyStopped) {
                    Text(
                        text = "已触发紧急停用，隔离 $stoppedCount 个已启用插件。请重启应用。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
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

@Composable
private fun AppearanceCard(
    settings: AppearanceSettings,
    onThemeChange: (ThemePreference) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onReduceMotionChange: (Boolean) -> Unit,
) {
    SettingsCard(title = "外观与动效") {
        Text(
            text = "品牌默认使用松烟·硅石配色；系统动态取色和减少动态都是可持久化的本机偏好。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("主题", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemePreference.values().forEach { option ->
                FilterChip(
                    selected = settings.theme == option,
                    onClick = { onThemeChange(option) },
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
        PreferenceSwitchRow(
            title = "系统动态取色",
            subtitle = "使用 Android 官方 Monet 调色；关闭时使用固定品牌令牌",
            checked = settings.dynamicColor,
            onCheckedChange = onDynamicColorChange,
        )
        PreferenceSwitchRow(
            title = "减少动态",
            subtitle = "停用位移和弹簧，保留短淡化与完整交互语义",
            checked = settings.reduceMotion,
            onCheckedChange = onReduceMotionChange,
        )
    }
}

@Composable
private fun PreferenceSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsCard(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                trailing?.invoke()
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            content()
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
