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
import androidx.compose.ui.res.stringResource
import com.openandroidintelligence.ui.resources.R as UiR

@Composable
internal fun httpStatusLabel(state: SettingsUiState): String = when (state.coreHttpHealth.reachability) {
    CoreHttpReachability.UNKNOWN -> if (state.coreHttpHealth.failureCode == "NETWORK_LOST") stringResource(UiR.string.conn_reachability_disconnected) else stringResource(UiR.string.conn_reachability_untested)
    CoreHttpReachability.REACHABLE -> state.coreHttpHealth.httpStatus?.let { stringResource(UiR.string.conn_reachability_reachable_with_code, it) } ?: stringResource(UiR.string.conn_reachability_reachable)
    CoreHttpReachability.UNREACHABLE -> stringResource(UiR.string.conn_reachability_unreachable)
    CoreHttpReachability.SECURITY_BLOCKED -> stringResource(UiR.string.conn_reachability_security_blocked)
}

@Composable
internal fun eventStatusLabel(status: EventStreamStatus): String = when (status) {
    EventStreamStatus.IDLE -> stringResource(UiR.string.conn_status_disconnected)
    EventStreamStatus.CONNECTING -> stringResource(UiR.string.conn_status_connecting)
    EventStreamStatus.LIVE -> stringResource(UiR.string.conn_status_connected)
    EventStreamStatus.RECONNECTING -> stringResource(UiR.string.conn_status_reconnecting)
    EventStreamStatus.FAILED -> stringResource(UiR.string.conn_status_failed)
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
    SettingsPageScaffold(stringResource(UiR.string.conn_screen_title), presentation, onBack) { padding ->
        SettingsPageColumn(padding) {
            SectionLabel(stringResource(UiR.string.conn_section_current_connection))
            SettingsSectionCard {
                SettingsListItem(stringResource(UiR.string.conn_server_address_title), supporting = uiState.connectedGatewayUrl ?: stringResource(UiR.string.conn_server_address_none))
                SettingsListItem(stringResource(UiR.string.conn_item_http_connection), supporting = httpStatusLabel(uiState))
                SettingsListItem(stringResource(UiR.string.conn_item_realtime_channel), supporting = eventStatusLabel(uiState.eventStreamStatus))
                SettingsNavigationItem(stringResource(UiR.string.conn_item_diagnostics), onClick = onOpenDiagnostics)
            }
            SectionLabel(stringResource(UiR.string.conn_section_agent_management))
            SettingsSectionCard {
                val agents = uiState.nativeAgentsState
                when {
                    agents == null -> SettingsListItem(stringResource(UiR.string.conn_item_current_agent), supporting = stringResource(UiR.string.conn_agent_not_fetched))
                    agents.supported == null -> SettingsListItem(stringResource(UiR.string.conn_agent_checking_support), supporting = stringResource(UiR.string.common_status_loading))
                    agents.supported == false -> SettingsListItem(stringResource(UiR.string.conn_agent_unsupported_by_core), supporting = stringResource(UiR.string.conn_agent_check_core_version))
                    agents.agents.isEmpty() -> SettingsListItem(stringResource(UiR.string.conn_agent_none_discovered), supporting = stringResource(UiR.string.conn_agent_check_core_config))
                    else -> agents.agents.forEach { agent ->
                        val selected = agent.id == agents.selectedAgentId
                        SettingsListItem(
                            agent.name,
                            supporting = (if (agent.online) stringResource(UiR.string.common_status_online) else stringResource(UiR.string.common_status_offline)) +
                                (if (agent.sessionScope == "account") stringResource(UiR.string.conn_agent_badge_isolated) else stringResource(UiR.string.conn_agent_badge_shared)) +
                                (if (selected) stringResource(UiR.string.conn_agent_badge_current) else ""),
                            selected = selected,
                            icon = if (selected) Icons.Default.Check else Icons.Default.Hub,
                            enabled = !agents.busy,
                            onClick = { onSelectAgent(agent.id) },
                        )
                    }
                }
                SettingsListItem(stringResource(UiR.string.conn_action_refresh_agent_list), enabled = uiState.isGatewayConnected && agents != null, onClick = onRefreshAgents)
            }
            SectionLabel(stringResource(UiR.string.conn_section_account_management))
            SettingsSectionCard {
                val login = when (uiState.loginStatus) {
                    GatewayLoginStatus.SIGNED_OUT -> stringResource(UiR.string.conn_account_status_not_logged_in)
                    GatewayLoginStatus.RESTORING -> stringResource(UiR.string.conn_account_status_restoring)
                    GatewayLoginStatus.SIGNED_IN -> stringResource(UiR.string.conn_account_status_logged_in)
                    GatewayLoginStatus.REAUTHENTICATION_REQUIRED -> stringResource(UiR.string.conn_account_status_relogin_required)
                }
                SettingsListItem(stringResource(UiR.string.conn_item_current_account), supporting = listOfNotNull(uiState.connectedUsername, login).joinToString(" · "), icon = Icons.Default.Person)
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
                    ) { Text(stringResource(UiR.string.conn_action_remove_local_account), color = MaterialTheme.colorScheme.error) }
                }
                SettingsNavigationItem(stringResource(UiR.string.conn_action_switch_account), onClick = onManageAccounts, enabled = uiState.hasLocalSession)
                SettingsNavigationItem(stringResource(UiR.string.conn_action_add_account), onClick = onAddAccount)
                SettingsListItem(
                    stringResource(UiR.string.conn_action_refresh_credentials),
                    supporting = if (uiState.isRefreshingSession) stringResource(UiR.string.conn_status_renewing_credentials) else stringResource(UiR.string.conn_desc_refresh_credentials),
                    enabled = uiState.isGatewayConnected && !uiState.isRefreshingSession,
                    onClick = onRefreshSession,
                )
                if (uiState.hasLocalSession) {
                    HorizontalDivider()
                    SettingsListItem(stringResource(UiR.string.common_action_logout), tone = SettingsTone.DANGER, onClick = { confirmLogout = true })
                }
            }
            SectionLabel(stringResource(UiR.string.conn_section_security))
            SettingsSectionCard {
                SettingsListItem(stringResource(UiR.string.conn_item_transport_encryption), supporting = when (uiState.connectedTransportSecurity) {
                    TransportSecurity.PLAINTEXT -> stringResource(UiR.string.conn_encryption_http_warning)
                    TransportSecurity.TLS_PINNED -> stringResource(UiR.string.conn_encryption_tls_pinned)
                    TransportSecurity.TLS_SYSTEM_TRUST -> stringResource(UiR.string.conn_encryption_system_ca)
                    null -> stringResource(UiR.string.conn_encryption_none)
                })
                SettingsListItem(stringResource(UiR.string.conn_item_core_identity), supporting = when (uiState.connectedTransportSecurity) {
                    TransportSecurity.TLS_PINNED -> stringResource(UiR.string.conn_identity_verified_saved)
                    TransportSecurity.TLS_SYSTEM_TRUST -> stringResource(UiR.string.conn_identity_verified_system_ca)
                    TransportSecurity.PLAINTEXT -> stringResource(UiR.string.conn_identity_http_cannot_verify)
                    null -> stringResource(UiR.string.conn_identity_unverified)
                })
                SettingsListItem(stringResource(UiR.string.conn_item_device_pairing), supporting = uiState.pairingSummary ?: if (uiState.hasLocalSession) stringResource(UiR.string.conn_pairing_pending_verification) else stringResource(UiR.string.conn_pairing_none))
                SettingsListItem(
                    stringResource(UiR.string.conn_action_unpair_device),
                    supporting = stringResource(UiR.string.conn_desc_unpair_device),
                    tone = SettingsTone.DANGER,
                    enabled = uiState.isGatewayConnected,
                    onClick = onRequestUnpair,
                )
            }
        }
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text(stringResource(UiR.string.conn_dialog_logout_title)) },
        text = { Text(stringResource(UiR.string.conn_dialog_logout_message)) },
        confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }) { Text(stringResource(UiR.string.common_action_logout)) } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(UiR.string.common_action_cancel)) } },
    )
    val profile = uiState.savedProfiles.firstOrNull { it.localProfileId == removing }
    if (profile != null) AlertDialog(
        onDismissRequest = { removing = null },
        title = { Text(stringResource(UiR.string.conn_dialog_remove_account_title)) },
        text = { Text(stringResource(UiR.string.conn_dialog_remove_account_message, profile.username)) },
        confirmButton = { TextButton(onClick = {
            removing = null
            if (uiState.isGatewayConnected || uiState.hasLocalSession) onManageAccounts() else onRemoveAccount(profile.localProfileId)
        }) { Text(if (uiState.isGatewayConnected || uiState.hasLocalSession) stringResource(UiR.string.conn_action_go_to_account_management) else stringResource(UiR.string.common_action_remove), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(UiR.string.common_action_cancel)) } },
    )
}

@Composable
internal fun ConnectionDiagnosticsScreen(uiState: SettingsUiState, presentation: SettingsPresentation, onBack: () -> Unit) {
    SettingsPageScaffold(stringResource(UiR.string.conn_item_diagnostics), presentation, onBack) { padding ->
        SettingsPageColumn(padding) {
            SectionLabel(stringResource(UiR.string.conn_section_status_diagnostics))
            SettingsSectionCard {
                SettingsListItem(stringResource(UiR.string.conn_item_http_connection), supporting = httpStatusLabel(uiState))
                SettingsListItem(stringResource(UiR.string.conn_item_realtime_channel), supporting = eventStatusLabel(uiState.eventStreamStatus))
                SettingsListItem(stringResource(UiR.string.conn_item_connection_phase), supporting = when (uiState.connectionPhase) {
                    is ConnectionPhase.Connected -> stringResource(UiR.string.conn_phase_normal)
                    is ConnectionPhase.OfflineMirror -> stringResource(UiR.string.conn_phase_offline_view)
                    ConnectionPhase.Initializing, ConnectionPhase.Negotiating -> stringResource(UiR.string.conn_status_connecting)
                    ConnectionPhase.Authenticating -> stringResource(UiR.string.conn_phase_verifying_account)
                    ConnectionPhase.Reconnecting -> stringResource(UiR.string.conn_status_reconnecting)
                    is ConnectionPhase.Failed -> stringResource(UiR.string.conn_phase_failed)
                    ConnectionPhase.Disconnected -> stringResource(UiR.string.conn_phase_not_connected)
                })
            }
            SectionLabel(stringResource(UiR.string.conn_section_core_capabilities))
            com.openandroidintelligence.mobile.NegotiatedCapabilitiesGroup(uiState.conversationUi)
            SettingsSectionCard {
                SettingsListItem(stringResource(UiR.string.conn_cap_device_execution), supporting = when {
                    !uiState.isGatewayConnected -> stringResource(UiR.string.conn_cap_view_after_connected)
                    uiState.deviceRequestChannel == null -> stringResource(UiR.string.conn_cap_core_not_enabled)
                    !uiState.pluginRuntimesWired -> stringResource(UiR.string.conn_cap_plugin_not_ready)
                    else -> stringResource(UiR.string.conn_cap_supported_requires_auth)
                })
            }
        }
    }
}
