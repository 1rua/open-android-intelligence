package com.openandroidintelligence.mobile.settings.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsNavigationItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.SettingsTone
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.gateway.events.EventStreamStatus
import com.openandroidintelligence.gateway.http.TransportSecurity
import com.openandroidintelligence.mobile.ConnectionPhase
import com.openandroidintelligence.mobile.CoreHttpReachability
import com.openandroidintelligence.mobile.GatewayLoginStatus
import com.openandroidintelligence.mobile.LocalSessionState
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.SettingsUiState
import com.openandroidintelligence.mobile.settings.SectionLabel
import com.openandroidintelligence.mobile.settings.SettingsPageColumn
import com.openandroidintelligence.mobile.settings.SettingsPageScaffold

internal fun httpStatusLabel(state: SettingsUiState): String = when (state.coreHttpHealth.reachability) {
    CoreHttpReachability.UNKNOWN -> if (state.coreHttpHealth.failureCode == "NETWORK_LOST") "网络已断开" else "未检测"
    CoreHttpReachability.REACHABLE -> "可访问" + (state.coreHttpHealth.httpStatus?.let { "（HTTP $it）" } ?: "")
    CoreHttpReachability.UNREACHABLE -> "无法访问 Core"
    CoreHttpReachability.SECURITY_BLOCKED -> "安全校验阻止"
}

internal fun eventStatusLabel(status: EventStreamStatus): String = when (status) {
    EventStreamStatus.IDLE -> "未连接"
    EventStreamStatus.CONNECTING -> "正在连接"
    EventStreamStatus.LIVE -> "已连接"
    EventStreamStatus.RECONNECTING -> "正在重连"
    EventStreamStatus.FAILED -> "连接失败"
}

@Composable
internal fun ConnectionSettingsScreen(
    uiState: SettingsUiState,
    presentation: SettingsPresentation,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onSelectAgent: (String) -> Unit,
    onRefreshAgents: () -> Unit,
    onSelectAccount: (String) -> Unit,
    onManageAccounts: () -> Unit,
    onAddAccount: () -> Unit,
    onRefreshSession: () -> Unit,
    onLogout: () -> Unit,
    onRemoveAccount: (String) -> Unit,
    onRequestUnpair: () -> Unit,
) {
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    SettingsPageScaffold("连接与账号", presentation, onBack) { padding ->
        SettingsPageColumn(padding) {
            SectionLabel("当前连接")
            SettingsSectionCard {
                SettingsListItem("OAI Core 地址", supporting = uiState.connectedGatewayUrl ?: "尚未选择 Core")
                SettingsListItem("HTTP 连接", supporting = httpStatusLabel(uiState))
                SettingsListItem("实时事件通道", supporting = eventStatusLabel(uiState.eventStreamStatus))
                SettingsNavigationItem("连接详情与诊断", onClick = onOpenDiagnostics)
            }
            SectionLabel("Agent 管理")
            SettingsSectionCard {
                val agents = uiState.nativeAgentsState
                when {
                    agents == null -> SettingsListItem("当前 Agent", supporting = "未获取，连接后刷新")
                    agents.supported == null -> SettingsListItem("正在检查 Agent 支持情况", supporting = "请稍候…")
                    agents.supported == false -> SettingsListItem("当前 Core 不支持 Agent 列表", supporting = "请检查 Core 版本")
                    agents.agents.isEmpty() -> SettingsListItem("尚未发现 Agent", supporting = "请检查 Core 上的 Agent 配置")
                    else -> agents.agents.forEach { agent ->
                        val selected = agent.id == agents.selectedAgentId
                        SettingsListItem(
                            agent.name,
                            supporting = (if (agent.online) "在线" else "离线") +
                                (if (agent.sessionScope == "account") " · 账号隔离" else " · 共享实例") +
                                (if (selected) " · 当前使用" else ""),
                            selected = selected,
                            icon = if (selected) Icons.Default.Check else Icons.Default.Hub,
                            enabled = !agents.busy,
                            onClick = { onSelectAgent(agent.id) },
                        )
                    }
                }
                SettingsListItem("刷新 Agent 列表", enabled = uiState.isGatewayConnected && agents != null, onClick = onRefreshAgents)
            }
            SectionLabel("账号管理")
            SettingsSectionCard {
                val login = when (uiState.loginStatus) {
                    GatewayLoginStatus.SIGNED_OUT -> "未登录"
                    GatewayLoginStatus.RESTORING -> "正在恢复登录"
                    GatewayLoginStatus.SIGNED_IN -> "已登录"
                    GatewayLoginStatus.REAUTHENTICATION_REQUIRED -> "需要重新登录"
                }
                SettingsListItem("当前账号", supporting = listOfNotNull(uiState.connectedUsername, login).joinToString(" · "), icon = Icons.Default.Person)
                uiState.savedProfiles.forEach { profile ->
                    SettingsListItem(
                        profile.username,
                        supporting = profile.gatewayBaseUrl,
                        enabled = !uiState.isManagingProfiles,
                        onClick = { onSelectAccount(profile.localProfileId) },
                    )
                    TextButton(
                        onClick = { removing = profile.localProfileId },
                        enabled = !uiState.isManagingProfiles,
                    ) { Text("移除本机账号", color = MaterialTheme.colorScheme.error) }
                }
                SettingsNavigationItem("切换账号", onClick = onManageAccounts, enabled = uiState.hasLocalSession)
                SettingsNavigationItem("添加账号", onClick = onAddAccount)
                SettingsListItem(
                    "刷新登录凭据",
                    supporting = if (uiState.isRefreshingSession) "续期中…" else "提前更新当前登录凭据",
                    enabled = uiState.isGatewayConnected && !uiState.isRefreshingSession,
                    onClick = onRefreshSession,
                )
                if (uiState.hasLocalSession) {
                    HorizontalDivider()
                    SettingsListItem("退出登录", tone = SettingsTone.DANGER, onClick = { confirmLogout = true })
                }
            }
            SectionLabel("连接安全")
            SettingsSectionCard {
                SettingsListItem("传输加密", supporting = when (uiState.connectedTransportSecurity) {
                    TransportSecurity.PLAINTEXT -> "明文（HTTP），易被窃听"
                    TransportSecurity.TLS_PINNED -> "已加密，证书公钥一致"
                    TransportSecurity.TLS_SYSTEM_TRUST -> "已加密，系统证书受信任"
                    null -> "未建立加密连接"
                })
                SettingsListItem("Core 身份验证", supporting = when (uiState.connectedTransportSecurity) {
                    TransportSecurity.TLS_PINNED -> "已核对保存的服务器身份"
                    TransportSecurity.TLS_SYSTEM_TRUST -> "通过系统证书验证"
                    TransportSecurity.PLAINTEXT -> "HTTP 无法验证身份"
                    null -> "未验证"
                })
                SettingsListItem("设备配对", supporting = uiState.pairingSummary ?: if (uiState.hasLocalSession) "保留账号资料，待连接核实" else "无配对信息")
                SettingsListItem(
                    "解除设备配对",
                    supporting = "取消手机与 Core 配对，清理当前账号本地数据",
                    tone = SettingsTone.DANGER,
                    enabled = uiState.isGatewayConnected,
                    onClick = onRequestUnpair,
                )
            }
        }
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text("退出当前账号？") },
        text = { Text("将保留已存账号、配对和本地聊天记录。该账号的远程任务与通知推送将暂停。") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("退出登录") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
    )
    val profile = uiState.savedProfiles.firstOrNull { it.localProfileId == removing }
    if (profile != null) AlertDialog(
        onDismissRequest = { removing = null },
        title = { Text("移除本机账号？") },
        text = { Text("将移除「${profile.username}」在手机上的登录与聊天数据；服务器端数据不受影响。" +
            if (uiState.isGatewayConnected || uiState.hasLocalSession) "\n先返回账号管理，再确认要移除的账号。" else "") },
        confirmButton = { TextButton(onClick = {
            removing = null
            if (uiState.isGatewayConnected || uiState.hasLocalSession) onManageAccounts() else onRemoveAccount(profile.localProfileId)
        }) { Text(if (uiState.isGatewayConnected || uiState.hasLocalSession) "进入账号管理" else "移除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
    )
}

@Composable
internal fun ConnectionDiagnosticsScreen(uiState: SettingsUiState, presentation: SettingsPresentation, onBack: () -> Unit) {
    SettingsPageScaffold("连接详情与诊断", presentation, onBack) { padding ->
        SettingsPageColumn(padding) {
            SectionLabel("连接状态与诊断")
            SettingsSectionCard {
                SettingsListItem("HTTP 连接", supporting = httpStatusLabel(uiState))
                SettingsListItem("实时事件通道", supporting = eventStatusLabel(uiState.eventStreamStatus))
                SettingsListItem("连接阶段", supporting = when (uiState.connectionPhase) {
                    is ConnectionPhase.Connected -> "连接正常"
                    is ConnectionPhase.OfflineMirror -> "离线查看本地记录，账号未退出"
                    ConnectionPhase.Initializing, ConnectionPhase.Negotiating -> "正在连接"
                    ConnectionPhase.Authenticating -> "正在验证账号"
                    ConnectionPhase.Reconnecting -> "正在重连"
                    is ConnectionPhase.Failed -> "连接失败，请检查 Core 地址、网络及账号"
                    ConnectionPhase.Disconnected -> "尚未连接"
                })
            }
            SectionLabel("此 Core 支持的功能")
            com.openandroidintelligence.mobile.NegotiatedCapabilitiesGroup(uiState.conversationUi)
            SettingsSectionCard {
                SettingsListItem("设备端任务执行", supporting = when {
                    !uiState.isGatewayConnected -> "连接后可查看"
                    uiState.deviceRequestChannel == null -> "当前 Core 未开启"
                    !uiState.pluginRuntimesWired -> "插件服务未就绪"
                    else -> "已支持，执行需授权"
                })
            }
        }
    }
}
