package com.openandroidintelligence.mobile.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.openandroidintelligence.conversation.components.SettingsNavigationItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.mobile.SettingsPresentation

/**
 * 设备能力与授权设置页面。
 *
 * 功能模块:
 * 1. 屏幕能力 - 读取界面/截图/控制其他应用
 * 2. 短信能力 - 读取/发送短信
 * 3. 通知能力 - 读取其他应用通知
 * 4. 主动推送 - 短信与通知的主动推送配置
 * 5. 插件管理 - 已安装插件和能力提供者
 *
 * 设计原则:
 * - 按能力组织,而非按插件
 * - 每个能力显示: 开关/授权模式/系统权限/提供者插件
 * - 主动推送独立子页(范围/端点/队列)
 */
@Composable
fun CapabilityAuthSettingsScreen(
    onBack: () -> Unit,
    presentation: SettingsPresentation,
    onNavigateToScreenCapability: () -> Unit,
    onNavigateToPushSettings: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToNotificationCollection: () -> Unit,
) {
    SettingsPageScaffold(
        title = "设备能力与授权",
        presentation = presentation,
        onBack = onBack,
    ) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            // ==================== 核心设备能力 ====================
            SectionLabel("设备能力")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    SettingsNavigationItem(
                        headline = "屏幕读取与控制",
                        supporting = "读取界面内容、截图、操作其他应用",
                        icon = Icons.Default.Visibility,
                        onClick = onNavigateToScreenCapability,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsNavigationItem(
                        headline = "短信",
                        supporting = "读取和发送短信",
                        icon = Icons.Default.Sms,
                        onClick = { /* TODO: 导航到短信能力配置 */ },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsNavigationItem(
                        headline = "通知读取",
                        supporting = "读取其他应用的通知内容",
                        icon = Icons.Default.Notifications,
                        onClick = onNavigateToNotificationCollection,
                    )
                }
            }

            // ==================== 主动推送 ====================
            SectionLabel("主动推送")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    SettingsNavigationItem(
                        headline = "短信与通知推送",
                        supporting = "自动将新短信和通知发送到Agent(默认关闭)",
                        icon = Icons.Default.Sms,
                        onClick = onNavigateToPushSettings,
                    )
                    Text(
                        text = "主动推送默认关闭,开启后可配置推送范围、目标端点和离线队列",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ==================== 插件管理 ====================
            SectionLabel("插件与提供者")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    SettingsNavigationItem(
                        headline = "插件管理",
                        supporting = "查看已安装插件和能力提供者",
                        icon = Icons.Default.Extension,
                        onClick = onNavigateToPlugins,
                    )
                    Text(
                        text = "插件提供具体的设备能力实现,每个能力可以由一个或多个插件提供",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ==================== 授权说明 ====================
            SectionLabel("授权如何生效")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    Text(
                        text = "三层授权机制",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "1. 系统权限 - Android系统级别的权限授予\n" +
                            "2. 能力开关 - 本机全局的能力总开关\n" +
                            "3. 授权模式 - 每次手动/允许只读/自动允许",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "持久化与切换",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "能力设置保存在本机,切换OAI Core、账号或Agent不会重置授权配置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
