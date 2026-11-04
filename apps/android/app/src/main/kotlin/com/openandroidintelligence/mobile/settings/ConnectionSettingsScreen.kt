package com.openandroidintelligence.mobile.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsNavigationItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.SettingsTone
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.gateway.http.TransportSecurity
import com.openandroidintelligence.mobile.ConnectionPhase
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.SettingsUiState

/**
 * 连接与账号设置页面。
 *
 * 功能模块:
 * 1. 当前连接 - OAI Core状态和连接详情
 * 2. Agent管理 - 当前Agent和Agent列表
 * 3. 账号管理 - 当前账号、切换账号、退出登录
 * 4. 连接安全 - 传输加密、解除配对
 */
@Composable
fun ConnectionSettingsScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    presentation: SettingsPresentation,
    onRefreshSession: () -> Unit,
    onLogout: () -> Unit,
    onSwitchAccount: () -> Unit,
    onSelectAgent: (String) -> Unit,
    onRefreshAgents: () -> Unit,
    onRequestUnpair: () -> Unit,
) {
    SettingsPageScaffold(
        title = "连接与账号",
        presentation = presentation,
        onBack = onBack,
    ) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            // ==================== 当前连接 ====================
            SectionLabel("当前连接")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    when (val phase = uiState.connectionPhase) {
                        is ConnectionPhase.Connected -> {
                            SettingsListItem(
                                headline = "OAI Core",
                                supporting = phase.gatewayUrl,
                                icon = Icons.Default.Hub,
                                tone = SettingsTone.PRIMARY,
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SettingsListItem(
                                headline = "连接加密",
                                supporting = when (phase.security) {
                                    is TransportSecurity.SpkiPinned -> "已验证 (TLS证书固定)"
                                    else -> "已加密"
                                },
                                icon = Icons.Default.Security,
                            )
                        }
                        else -> {
                            SettingsListItem(
                                headline = "未连接",
                                supporting = "请先连接到 OAI Core",
                                icon = Icons.Default.Hub,
                            )
                        }
                    }
                }
            }

            // ==================== Agent管理 ====================
            SectionLabel("Agent")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    if (uiState.currentAgent != null) {
                        SettingsListItem(
                            headline = "当前Agent",
                            supporting = uiState.currentAgent,
                            icon = Icons.Default.Person,
                            tone = SettingsTone.PRIMARY,
                        )
                    } else {
                        Text(
                            text = "未选择Agent",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsListItem(
                        headline = "刷新Agent列表",
                        supporting = "从OAI Core获取最新的Agent列表",
                        icon = Icons.Default.Refresh,
                        onClick = onRefreshAgents,
                    )
                }
            }

            // ==================== 账号管理 ====================
            SectionLabel("账号")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    SettingsListItem(
                        headline = "刷新会话",
                        supporting = "重新验证登录状态",
                        icon = Icons.Default.Refresh,
                        onClick = onRefreshSession,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsListItem(
                        headline = "切换账号",
                        supporting = "使用其他账号登录",
                        icon = Icons.Default.Person,
                        onClick = onSwitchAccount,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsListItem(
                        headline = "退出登录",
                        supporting = "撤销凭据并返回登录页",
                        icon = Icons.Default.Person,
                        tone = SettingsTone.DANGER,
                        onClick = onLogout,
                    )
                }
            }

            // ==================== 连接安全 ====================
            SectionLabel("连接安全")
            SettingsSectionCard {
                SettingsListItem(
                    headline = "解除设备配对",
                    supporting = "撤销设备配对凭据并切断连接,能力设置将保留",
                    icon = Icons.Default.Security,
                    tone = SettingsTone.DANGER,
                    onClick = onRequestUnpair,
                )
            }
        }
    }
}
