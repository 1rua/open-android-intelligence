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
                        !uiState.allowDeveloperTrustMode -> "当前发行版不允许开启"
                        uiState.isEmergencyStopped -> "已紧急停止，重启应用后才能恢复"
                        uiState.isTrustModeEnabled -> "允许原生插件在本应用进程中运行，可访问本应用拥有的权限和数据"
                        else -> "原生插件未获准运行；隔离插件仍按本机能力策略执行"
                    },
                    onCheckedChange = onToggleTrustMode,
                )
                SettingsListItem("插件运行环境", supporting = if (uiState.pluginRuntimesWired) "运行时已接入；各项能力仍需满足本机策略与系统权限" else "此版本未接入插件运行时")
                Text(
                    "设备访问关系、能力策略和 Android 系统权限分别检查。关闭开发者信任模式不会取消已经允许的隔离插件能力。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Dimensions.SpaceSmall),
                )
            }
            SectionLabel("操作记录")
            SettingsSectionCard {
                SettingsNavigationItem("授权与调用记录", supporting = "查看授权变更、敏感操作和执行结果", onClick = onOpenAudit)
            }
            SectionLabel("紧急停止")
            SettingsSectionCard {
                SettingsListItem(
                    "紧急停止所有插件",
                    supporting = if (uiState.isEmergencyStopped) "已停止 ${uiState.emergencyStoppedCount} 个插件，需要重启应用才能恢复" else "立即隔离已启用插件、关闭信任模式并撤销临时授权，需要重启应用恢复",
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
                SettingsSectionCard { Text("还没有记录。授权变更和插件调用会显示在这里。") }
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
