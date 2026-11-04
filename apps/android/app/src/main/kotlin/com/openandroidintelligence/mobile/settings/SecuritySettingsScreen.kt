package com.openandroidintelligence.mobile.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.SettingsSwitchItem
import com.openandroidintelligence.conversation.components.SettingsTone
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.SettingsUiState

/**
 * 安全与隐私设置页面。
 *
 * 包含:
 * 1. 开发者信任模式开关
 * 2. 插件运行方式说明
 * 3. 插件权限控制机制说明
 * 4. 紧急停止功能
 */
@Composable
fun SecuritySettingsScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    presentation: SettingsPresentation,
    onToggleTrustMode: (Boolean) -> Unit,
    onRequestEmergencyStop: () -> Unit,
) {
    SettingsPageScaffold(
        title = "安全与隐私",
        presentation = presentation,
        onBack = onBack,
    ) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            SectionLabel("插件运行方式")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    SettingsSwitchItem(
                        headline = "开发者信任模式",
                        supporting = if (uiState.isTrustModeEnabled) {
                            "已开启：插件可以直接控制系统界面，隔离保护已放宽，请只在信任插件来源时使用"
                        } else {
                            "已关闭：插件在隔离环境中运行，无法控制系统界面（推荐）"
                        },
                        checked = uiState.isTrustModeEnabled,
                        enabled = uiState.allowDeveloperTrustMode && !uiState.isEmergencyStopped,
                        icon = if (uiState.isTrustModeEnabled) Icons.Default.LockOpen else Icons.Default.Lock,
                        tone = if (uiState.isTrustModeEnabled) SettingsTone.PRIMARY else SettingsTone.NEUTRAL,
                        onCheckedChange = onToggleTrustMode,
                    )
                    if (!uiState.allowDeveloperTrustMode) {
                        Text(
                            text = "当前这个版本（应用商店版本）不允许开启开发者信任模式。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = Dimensions.SpaceSmall),
                        )
                    }
                }
            }

            SectionLabel("插件能做什么")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    Text(
                        text = "隔离运行",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "插件运行在独立的受限环境中，有明确的资源上限，不能偷偷弹出界面，也不能绕过应用直接调用系统能力。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "权限由手机掌握",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "插件能做什么由应用本身固定定义，插件无法自行扩权。你授予的每一项权限、每一次插件调用都会写进操作记录，事后可以逐条核对。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionLabel("紧急停止")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    SettingsListItem(
                        headline = "紧急停止所有插件",
                        supporting = if (uiState.isEmergencyStopped) {
                            "已停止，共停用 ${uiState.emergencyStoppedCount} 个插件。必须重启应用才能恢复。"
                        } else {
                            "立即停用全部插件并关闭信任模式，需要重启应用才能恢复。"
                        },
                        icon = Icons.Default.Warning,
                        tone = SettingsTone.DANGER,
                        enabled = !uiState.isEmergencyStopped,
                        onClick = onRequestEmergencyStop,
                    )
                }
            }
        }
    }
}
