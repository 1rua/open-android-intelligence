package com.openandroidintelligence.mobile.settings.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsNavigationItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.SettingsSwitchItem
import com.openandroidintelligence.conversation.components.SettingsTone
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.kernel.AuditOutcome
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.SettingsUiState
import com.openandroidintelligence.mobile.settings.SectionLabel
import com.openandroidintelligence.mobile.settings.SettingsPageColumn
import com.openandroidintelligence.mobile.settings.SettingsPageScaffold

@Composable
internal fun SecuritySettingsScreen(
    uiState: SettingsUiState,
    presentation: SettingsPresentation,
    onBack: () -> Unit,
    onToggleTrustMode: (Boolean) -> Unit,
    onOpenAudit: () -> Unit,
    onRequestEmergencyStop: () -> Unit,
) {
    SettingsPageScaffold("安全与隐私", presentation, onBack) { padding ->
        SettingsPageColumn(padding) {
            SectionLabel("插件运行安全")
            SettingsSectionCard {
                SettingsSwitchItem(
                    "开发者信任模式",
                    checked = uiState.isTrustModeEnabled,
                    enabled = uiState.allowDeveloperTrustMode && !uiState.isEmergencyStopped,
                    supporting = when {
                        !uiState.allowDeveloperTrustMode -> "当前版本不支持"
                        uiState.isEmergencyStopped -> "已紧急停止，重启恢复"
                        uiState.isTrustModeEnabled -> "原生插件可使用应用的权限和数据"
                        else -> "原生插件未开启；其他插件正常受控运行"
                    },
                    onCheckedChange = onToggleTrustMode,
                )
                SettingsListItem("插件运行环境", supporting = if (uiState.pluginRuntimesWired) "插件服务正常" else "未启用插件服务")
                Text(
                    "安全规则与系统权限独立检查。关闭信任模式不影响已允许功能。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Dimensions.SpaceSmall),
                )
            }
            SectionLabel("使用记录")
            SettingsSectionCard {
                SettingsNavigationItem("授权与调用记录", supporting = "查看重要操作与执行结果", onClick = onOpenAudit)
            }
            SectionLabel("紧急停止")
            SettingsSectionCard {
                SettingsListItem(
                    "紧急停止所有插件",
                    supporting = if (uiState.isEmergencyStopped) "已停止 ${uiState.emergencyStoppedCount} 个插件，重启恢复" else "立即停用所有插件并撤销临时授权，重启恢复",
                    enabled = !uiState.isEmergencyStopped,
                    tone = SettingsTone.DANGER,
                    onClick = onRequestEmergencyStop,
                )
            }
        }
    }
}

@Composable
internal fun AuditSettingsScreen(uiState: SettingsUiState, presentation: SettingsPresentation, onBack: () -> Unit) {
    SettingsPageScaffold("授权与调用记录", presentation, onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(Dimensions.SpaceMedium),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            item { SectionLabel("全部记录（${uiState.auditEvents.size} 条）") }
            if (uiState.auditEvents.isEmpty()) item {
                SettingsSectionCard { Text("暂无记录。") }
            }
            itemsIndexed(uiState.auditEvents) { _, event ->
                SettingsSectionCard {
                    val result = when (event.outcome) {
                        AuditOutcome.ALLOWED -> "允许"
                        AuditOutcome.DENIED -> "拒绝"
                        AuditOutcome.FAILED -> "失败"
                    }
                    Text(event.action, style = MaterialTheme.typography.titleSmall)
                    Text("${event.timestampUtc} · $result", color = if (event.outcome == AuditOutcome.ALLOWED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    Text("插件：${event.pluginId}\n账号：${event.accountId}\n配对：${event.pairingId}\n请求：${event.correlationId}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
