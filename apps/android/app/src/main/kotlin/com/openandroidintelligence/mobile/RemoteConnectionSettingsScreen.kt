package com.openandroidintelligence.mobile

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsNavigationItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.SettingsTone
import com.openandroidintelligence.gateway.remote.runtime.RemoteAccountConnectionState
import com.openandroidintelligence.gateway.remote.runtime.RemoteHostConnectionState
import com.openandroidintelligence.gateway.remote.authproduction.AndroidRemoteIdentityProtector
import com.openandroidintelligence.gateway.remote.authproduction.RemoteCredentialVault
import com.openandroidintelligence.gateway.remote.authproduction.RemoteInstallationIdentityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.openandroidintelligence.mobile.settings.SectionLabel
import com.openandroidintelligence.mobile.settings.SettingsPageColumn
import com.openandroidintelligence.mobile.settings.SettingsPageScaffold
import com.openandroidintelligence.ui.resources.R as UiR

/** 登录页已登录态专用：只负责发现 Host 与选择/连接 Agent，彻底移除工作区展示与选择交互。 */
@Composable
internal fun RemoteLoginAgentSelector(runtime: RemoteRuntime) {
    val agentConn by runtime.agentConnection.collectAsState()
    var autoDiscoveredHost by remember(agentConn.account?.accountId) { mutableStateOf<String?>(null) }

    LaunchedEffect(agentConn.hosts, agentConn.agents.size, agentConn.busy, agentConn.errorCode) {
        val onlineHosts = agentConn.hosts.filter { it.online }
        if (onlineHosts.size == 1 &&
            agentConn.agents.isEmpty() &&
            !agentConn.busy &&
            agentConn.errorCode == null &&
            autoDiscoveredHost != onlineHosts.single().deviceId
        ) {
            val sole = onlineHosts.single()
            autoDiscoveredHost = sole.deviceId
            runtime.discover(sole)
        }
    }

    RemoteHostAndAgentSections(
        runtime = runtime,
        agentConn = agentConn,
        fallbackAgentHint = { stringResource(UiR.string.remote_agent_select_hint) },
    )
}

/** 设置页「连接与账号」：Host 主机 + 可用 Agent（已连接可即时切换）+ 当前 Agent 下工作区（同 Agent 复用通道切换）。 */
@Composable
internal fun RemoteHostAndAgentSettings(runtime: RemoteRuntime) {
    val agentConn by runtime.agentConnection.collectAsState()
    val workspaceCtx by runtime.workspaceContext.collectAsState()

    RemoteHostAndAgentSections(
        runtime = runtime,
        agentConn = agentConn,
        fallbackAgentHint = { it.backendName },
    )

    if (workspaceCtx.workspaces.isNotEmpty()) {
        SectionLabel(stringResource(UiR.string.remote_current_agent_workspaces))
        SettingsSectionCard {
            workspaceCtx.workspaces.forEach { ws ->
                val selected = workspaceCtx.activeWorkspace == ws.endpoint.key
                val title = ws.endpoint.displayName.substringAfterLast(" · ").ifBlank { ws.endpoint.displayName }
                SettingsListItem(
                    headline = title,
                    supporting = ws.path.takeIf { it.isNotBlank() }
                        ?: if (selected) stringResource(UiR.string.remote_workspace_active) else null,
                    selected = selected,
                    icon = if (selected) Icons.Default.Check else Icons.Default.Hub,
                    enabled = agentConn.switchingAgent == null,
                    onClick = { runtime.selectWorkspace(ws) },
                )
            }
        }
    }
}

@Composable
private fun RemoteHostAndAgentSections(
    runtime: RemoteRuntime,
    agentConn: AgentConnectionState,
    fallbackAgentHint: @Composable (DiscoveredAgent) -> String,
) {
    SectionLabel(stringResource(UiR.string.remote_hosts))
    SettingsSectionCard {
        agentConn.hosts.forEach { host ->
            val selected = agentConn.activeAgent?.hostId == host.deviceId
            SettingsListItem(
                headline = host.name,
                modifier = Modifier.testTag("remote-host-item:${host.deviceId}"),
                supporting = stringResource(if (host.online) UiR.string.common_status_online else UiR.string.common_status_offline),
                selected = selected,
                icon = if (selected) Icons.Default.Check else Icons.Default.Hub,
                enabled = !agentConn.busy && host.online,
                onClick = { runtime.discover(host) },
            )
        }
        SettingsListItem(
            headline = stringResource(UiR.string.remote_refresh_hosts),
            modifier = Modifier.testTag("remote-refresh-hosts"),
            enabled = !agentConn.busy && agentConn.account != null,
            onClick = { runtime.refreshHosts() },
        )
    }

    SectionLabel(stringResource(UiR.string.remote_available_agents))
    SettingsSectionCard {
        if (agentConn.agents.isEmpty()) {
            SettingsListItem(
                headline = stringResource(UiR.string.remote_agent_none_discovered),
                supporting = stringResource(UiR.string.remote_agent_select_host_hint),
            )
        } else {
            agentConn.agents.forEach { agent ->
                val selected = agentConn.activeAgent == agent.key
                val switching = agentConn.switchingAgent == agent.key
                val statusHint = when {
                    switching -> stringResource(UiR.string.remote_agent_switching)
                    selected -> stringResource(UiR.string.remote_agent_active)
                    !agent.hostOnline -> stringResource(UiR.string.common_status_offline)
                    else -> fallbackAgentHint(agent)
                }
                SettingsListItem(
                    headline = agent.displayName,
                    modifier = Modifier.testTag("remote-agent-item:${agent.key.backendId.value}"),
                    supporting = statusHint,
                    selected = selected,
                    icon = if (selected) Icons.Default.Check else Icons.Default.Hub,
                    enabled = agent.hostOnline && agentConn.switchingAgent == null,
                    onClick = { runtime.selectAgent(agent) },
                )
            }
        }
        agentConn.backendFailures.forEach { (backend, code) ->
            SettingsListItem(backend, supporting = remoteConnectionFailureText(code), tone = SettingsTone.DANGER)
        }
    }
}

/** 保留连接、助手、账号、安全四组结构，只替换有真实来源的 Remote 连接数据。 */
@Composable
internal fun RemoteConnectionSettingsScreen(
    runtime: RemoteRuntime,
    presentation: SettingsPresentation,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val agentConn by runtime.agentConnection.collectAsState()
    val client by runtime.accountRuntime.state.collectAsState()
    val host by runtime.accountRuntime.hostState.collectAsState()
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val installation = remember(context) { RemoteInstallationIdentityStore(context) }
    var deviceName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(installation) { deviceName = withContext(Dispatchers.IO) { runCatching { installation.deviceName() }.getOrNull() } }
    SettingsPageScaffold(stringResource(UiR.string.conn_screen_title), presentation, onBack) { padding ->
        SettingsPageColumn(padding) {
            SectionLabel(stringResource(UiR.string.conn_section_current_connection))
            SettingsSectionCard {
                SettingsListItem(stringResource(UiR.string.conn_server_address_title),
                    supporting = agentConn.account?.origin ?: stringResource(UiR.string.conn_server_address_none))
                SettingsListItem(stringResource(UiR.string.remote_client_role),
                    modifier = Modifier.testTag("remote-client-status"), supporting = remoteClientLabel(client))
                SettingsListItem(stringResource(UiR.string.remote_host_role), supporting = remoteHostLabel(host))
                RemoteDeviceNameSetting(deviceName) { name ->
                    withContext(Dispatchers.IO) {
                        installation.migrateActiveAccount(RemoteCredentialVault(context), AndroidRemoteIdentityProtector())
                        installation.updateDeviceName(name)
                    }
                    deviceName = name.trim()
                }
                SettingsNavigationItem(stringResource(UiR.string.conn_item_diagnostics), onClick = onOpenDiagnostics)
            }
            RemoteHostAndAgentSettings(runtime)
            SectionLabel(stringResource(UiR.string.conn_section_account_management))
            SettingsSectionCard {
                SettingsListItem(stringResource(UiR.string.conn_item_current_account), icon = Icons.Default.Person,
                    supporting = agentConn.account?.accountId ?: stringResource(UiR.string.conn_account_status_not_logged_in))
                if (agentConn.account != null) SettingsListItem(stringResource(UiR.string.common_action_logout),
                    tone = SettingsTone.DANGER, onClick = { confirmLogout = true })
            }
            SectionLabel(stringResource(UiR.string.conn_section_security))
            SettingsSectionCard {
                val connected = client == RemoteAccountConnectionState.CONNECTED || host == RemoteHostConnectionState.ONLINE
                val address = agentConn.account?.origin?.let(ServiceAddress::parse).takeIf { connected }
                SettingsListItem(stringResource(UiR.string.conn_item_transport_encryption), supporting = stringResource(when {
                    address == null -> UiR.string.conn_encryption_none
                    address.scheme.equals("https", ignoreCase = true) -> UiR.string.conn_encryption_system_ca
                    else -> UiR.string.conn_encryption_http_warning
                }))

            }
        }
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text(stringResource(UiR.string.conn_dialog_logout_title)) },
        text = { Text(stringResource(UiR.string.conn_dialog_logout_message)) },
        confirmButton = { TextButton(onClick = { confirmLogout = false; runtime.logout() }) {
            Text(stringResource(UiR.string.common_action_logout))
        } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(UiR.string.common_action_cancel)) } },
    )
}

/** 改名只更新安装资料，注册时携带；不为改名保留账号级令牌。 */
@Composable
internal fun RemoteDeviceNameSetting(name: String?, onSave: suspend (String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SettingsListItem(stringResource(UiR.string.remote_device_name), supporting = name,
        enabled = name != null && !saving, onClick = { draft = name.orEmpty(); failed = false; editing = true })
    if (editing) AlertDialog(
        onDismissRequest = { if (!saving) editing = false },
        title = { Text(stringResource(UiR.string.remote_device_name)) },
        text = {
            OutlinedTextField(value = draft, onValueChange = { draft = it; failed = false }, singleLine = true,
                    enabled = !saving, modifier = Modifier.testTag("remote-device-name-input"),
                    label = { Text(stringResource(UiR.string.remote_device_name)) },
                    isError = failed,
                    supportingText = { Text(stringResource(if (failed) UiR.string.remote_device_name_save_failed else UiR.string.remote_device_name_effect)) })
        },
        confirmButton = { TextButton(enabled = !saving && runCatching { RemoteInstallationIdentityStore.normalizeName(draft) }.isSuccess,
            onClick = {
                saving = true
                scope.launch {
                    try { onSave(draft); editing = false }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                    finally { saving = false }
                }
            }) { Text(stringResource(UiR.string.common_action_save)) } },
        dismissButton = { TextButton(enabled = !saving, onClick = { editing = false }) { Text(stringResource(UiR.string.common_action_cancel)) } },
    )
}

@Composable
internal fun RemoteConnectionDiagnosticsScreen(runtime: RemoteRuntime, presentation: SettingsPresentation, onBack: () -> Unit) {
    val agentConn by runtime.agentConnection.collectAsState()
    val client by runtime.accountRuntime.state.collectAsState()
    val host by runtime.accountRuntime.hostState.collectAsState()
    SettingsPageScaffold(stringResource(UiR.string.conn_item_diagnostics), presentation, onBack) { padding ->
        SettingsPageColumn(padding) {
            SectionLabel(stringResource(UiR.string.conn_section_status_diagnostics))
            SettingsSectionCard {
                SettingsListItem(stringResource(UiR.string.remote_client_role), supporting = remoteClientLabel(client))
                SettingsListItem(stringResource(UiR.string.remote_host_role), supporting = remoteHostLabel(host))
                SettingsListItem(stringResource(UiR.string.conn_item_current_agent), supporting = runtime.connectedAgentName
                    ?: stringResource(UiR.string.conn_agent_not_fetched))
                agentConn.errorCode?.let { SettingsListItem(stringResource(UiR.string.conn_status_failed), supporting = remoteConnectionFailureText(it), tone = SettingsTone.DANGER) }
                agentConn.backendFailures.forEach { (backend, code) -> SettingsListItem(backend, supporting = remoteConnectionFailureText(code), tone = SettingsTone.DANGER) }
            }
        }
    }
}

@Composable
private fun remoteClientLabel(state: RemoteAccountConnectionState): String = stringResource(when (state) {
    RemoteAccountConnectionState.SIGNED_OUT -> UiR.string.remote_role_signed_out
    RemoteAccountConnectionState.READY -> UiR.string.remote_role_ready
    RemoteAccountConnectionState.CONNECTING -> UiR.string.remote_role_connecting
    RemoteAccountConnectionState.CONNECTED -> UiR.string.remote_role_connected
    RemoteAccountConnectionState.DISCONNECTED -> UiR.string.remote_role_disconnected
})

@Composable
private fun remoteHostLabel(state: RemoteHostConnectionState): String = stringResource(when (state) {
    RemoteHostConnectionState.STOPPED -> UiR.string.remote_role_stopped
    RemoteHostConnectionState.CONNECTING -> UiR.string.remote_role_connecting
    RemoteHostConnectionState.ONLINE -> UiR.string.remote_role_online
    RemoteHostConnectionState.FAILED -> UiR.string.remote_role_failed
})
