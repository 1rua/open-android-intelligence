package com.openandroidintelligence.mobile.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.openandroidintelligence.conversation.components.SettingsNavigationItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.mobile.SettingsPresentation

/**
 * 高级设置与关于页面。
 *
 * 功能模块:
 * 1. 消息处理 - 合并连续消息、等待时间配置
 * 2. 应用维护 - 应用更新、更新渠道
 * 3. 帮助与反馈 - 新手引导、项目文档、GitHub反馈
 * 4. 关于OAI - 版本信息、构建详情、开源许可
 */
@Composable
fun AdvancedSettingsScreen(
    onBack: () -> Unit,
    presentation: SettingsPresentation,
    onNavigateToAppUpdate: () -> Unit,
    onOpenOnboarding: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    SettingsPageScaffold(
        title = "高级设置与关于",
        presentation = presentation,
        onBack = onBack,
    ) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            // ==================== 消息处理 ====================
            SectionLabel("消息处理")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    Text(
                        text = "合并连续消息功能位于主览页面的\"消息发送延迟\"区域",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ==================== 应用维护 ====================
            SectionLabel("应用维护")
            SettingsSectionCard {
                SettingsNavigationItem(
                    headline = "应用更新",
                    supporting = "检查正式版和预览版更新",
                    icon = Icons.Default.SystemUpdate,
                    onClick = onNavigateToAppUpdate,
                )
            }

            // ==================== 帮助与反馈 ====================
            SectionLabel("帮助与反馈")
            SettingsSectionCard {
                SettingsNavigationItem(
                    headline = "新手引导与部署教程",
                    supporting = "重新查看部署步骤与权限说明",
                    icon = Icons.Default.MenuBook,
                    onClick = onOpenOnboarding,
                )
            }

            // ==================== 关于OAI ====================
            SectionLabel("关于")
            SettingsSectionCard {
                SettingsNavigationItem(
                    headline = "关于 OAI",
                    supporting = "版本信息、构建详情和开源许可",
                    icon = Icons.Default.Info,
                    onClick = onOpenAbout,
                )
            }
        }
    }
}
