package com.openandroidintelligence.mobile

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsNavigationItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.SettingsTone
import com.openandroidintelligence.gateway.remote.runtime.RemoteAccountConnectionState
import com.openandroidintelligence.gateway.remote.runtime.RemoteHostConnectionState
import com.openandroidintelligence.mobile.settings.SectionLabel
import com.openandroidintelligence.mobile.settings.SettingsPageColumn
import com.openandroidintelligence.mobile.settings.SettingsPageScaffold
import com.openandroidintelligence.ui.resources.R as UiR

/** Remote 的发现结果沿用原设置行与分组卡片，选中状态来自真实工作区。 */
@Composable
internal fun RemoteEndpointList(runtime: RemoteRuntime) {
    val state by runtime.state.collectAsState()
    SectionLabel(stringResource(UiR.string.remote_hosts))
    SettingsSectionCard {
        state.hosts.forEach { host ->
            val selected = state.selected?.hostId == host.deviceId
            SettingsListItem(host.name,
                supporting = stringResource(if (host.online) UiR.string.common_status_online else UiR.string.common_status_offline),
                selected = selected, icon = if (selected) Icons.Default.Check else Icons.Default.Hub,
                enabled = !state.busy && host.online, onClick = { runtime.discover(host) })
        }
        SettingsListItem(stringResource(UiR.string.remote_refresh_hosts), enabled = !state.busy && state.account != null,
            onClick = { runtime.refreshHosts() })
    }
    SectionLabel(stringResource(UiR.string.remote_workspaces))
    SettingsSectionCard {
        if (state.endpoints.isEmpty()) SettingsListItem(stringResource(UiR.string.conn_agent_none_discovered),
            supporting = stringResource(UiR.string.conn_agent_not_fetched))
        state.endpoints.forEach { endpoint ->
            val selected = state.selected == endpoint.endpoint.key
            SettingsListItem(endpoint.endpoint.displayName,
                selected = selected, icon = if (selected) Icons.Default.Check else Icons.Default.Hub,
                enabled = !state.busy, onClick = { runtime.select(endpoint) })
        }
        state.backendFailures.forEach { (backend, code) ->
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
    val state by runtime.state.collectAsState()
    val client by runtime.accountRuntime.state.collectAsState()
    val host by runtime.accountRuntime.hostState.collectAsState()
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    SettingsPageScaffold(stringResource(UiR.string.conn_screen_title), presentation, onBack) { padding ->
        SettingsPageColumn(padding) {
            SectionLabel(stringResource(UiR.string.conn_section_current_connection))
            SettingsSectionCard {
                SettingsListItem(stringResource(UiR.string.conn_server_address_title),
                    supporting = state.account?.origin ?: stringResource(UiR.string.conn_server_address_none))
                SettingsListItem(stringResource(UiR.string.remote_client_role),
                    modifier = Modifier.testTag("remote-client-status"), supporting = remoteClientLabel(client))
                SettingsListItem(stringResource(UiR.string.remote_host_role), supporting = remoteHostLabel(host))
                SettingsNavigationItem(stringResource(UiR.string.conn_item_diagnostics), onClick = onOpenDiagnostics)
            }
            RemoteEndpointList(runtime)
            SectionLabel(stringResource(UiR.string.conn_section_account_management))
            SettingsSectionCard {
                SettingsListItem(stringResource(UiR.string.conn_item_current_account), icon = Icons.Default.Person,
                    supporting = state.account?.accountId ?: stringResource(UiR.string.conn_account_status_not_logged_in))
                if (state.account != null) SettingsListItem(stringResource(UiR.string.common_action_logout),
                    tone = SettingsTone.DANGER, onClick = { confirmLogout = true })
            }
            SectionLabel(stringResource(UiR.string.conn_section_security))
            SettingsSectionCard {
                val connected = client == RemoteAccountConnectionState.CONNECTED || host == RemoteHostConnectionState.ONLINE
                val address = state.account?.origin?.let(ServiceAddress::parse).takeIf { connected }
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

@Composable
internal fun RemoteConnectionDiagnosticsScreen(runtime: RemoteRuntime, presentation: SettingsPresentation, onBack: () -> Unit) {
    val state by runtime.state.collectAsState()
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
                state.errorCode?.let { SettingsListItem(stringResource(UiR.string.conn_status_failed), supporting = remoteConnectionFailureText(it), tone = SettingsTone.DANGER) }
                state.backendFailures.forEach { (backend, code) -> SettingsListItem(backend, supporting = remoteConnectionFailureText(code), tone = SettingsTone.DANGER) }
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
