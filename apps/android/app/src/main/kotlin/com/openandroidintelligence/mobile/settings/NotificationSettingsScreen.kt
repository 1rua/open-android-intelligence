package com.openandroidintelligence.mobile.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.openandroidintelligence.conversation.components.SettingsNavigationItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.SettingsSwitchItem
import com.openandroidintelligence.conversation.components.SettingsTone
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.SettingsUiState

/**
 * 通知与提醒设置页面。
 *
 * 整合三套通知系统:
 * 1. Agent任务通知 - OAI发给用户的通知(ReplyNotifications)
 * 2. Android会话气泡 - 浮动对话窗口
 * 3. 实时任务状态 - 锁屏通知栏展示(Android 16+)
 *
 * 注意: 通知读取(被动查询)和主动推送在"设备能力与授权"页面配置
 */
@Composable
fun NotificationSettingsScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    presentation: SettingsPresentation,
    onSetNotificationsGrant: (Boolean) -> Unit,
    onNavigateToNotificationCollection: () -> Unit,
) {
    SettingsPageScaffold(
        title = "通知与提醒",
        presentation = presentation,
        onBack = onBack,
    ) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            // ==================== Agent任务通知 ====================
            SectionLabel("Agent任务通知")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    SettingsSwitchItem(
                        headline = "Agent任务通知",
                        supporting = "在通知栏显示Agent的工作进度和结果",
                        checked = uiState.replyNotificationsGranted,
                        enabled = !uiState.isEmergencyStopped,
                        icon = Icons.Default.Notifications,
                        tone = SettingsTone.PRIMARY,
                        onCheckedChange = onSetNotificationsGrant,
                    )
                    if (!uiState.replyNotificationsAllowed) {
                        Text(
                            text = "需要授予通知权限,点击开关将跳转到系统设置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ==================== 会话气泡和实时状态 ====================
            SectionLabel("Android界面集成")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    Text(
                        text = "会话气泡",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "在屏幕上显示浮动的对话窗口",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "实时任务状态 (Android 16+)",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "在锁屏和通知栏显示正在进行的任务",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ==================== 通知读取(被动查询) ====================
            SectionLabel("通知读取")
            SettingsSectionCard {
                SettingsNavigationItem(
                    headline = "通知读取范围",
                    supporting = "Agent查询时读取哪些应用的通知",
                    icon = Icons.Default.Notifications,
                    onClick = onNavigateToNotificationCollection,
                )
            }

            // ==================== 说明 ====================
            SectionLabel("说明")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    Text(
                        text = "通知主动推送",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "短信与通知的主动推送功能在\"设备能力与授权\"页面配置,包括推送范围、端点白名单和离线队列",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
