package com.openandroidintelligence.mobile.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.kernel.CapabilityAuthorizationMode
import com.openandroidintelligence.kernel.CapabilityPolicy

/** 只展示并提交当前能力的本机策略，连接状态和插件启停不参与控件门控。 */
@Composable
internal fun CapabilityAuthorizationControls(
    pluginId: String,
    capability: String,
    readOnly: Boolean,
    policy: CapabilityPolicy,
    onPolicyChange: (CapabilityPolicy) -> Unit,
    modifier: Modifier = Modifier,
) {
    val identity = "$pluginId:$capability"
    Column(
        modifier = modifier.fillMaxWidth().testTag("capability-policy:$identity"),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(capabilityAuthorizationTitle(capability), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (policy.enabled) "能力已开启，按下方模式处理请求" else "能力已关闭，授权模式仍会保留",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = policy.enabled,
                onCheckedChange = { onPolicyChange(policy.copy(enabled = it)) },
                modifier = Modifier.testTag("capability-enabled:$identity")
                    .semantics { contentDescription = "${capabilityAuthorizationTitle(capability)}总开关" },
            )
        }
        Text(capability, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.selectableGroup()) {
            CapabilityAuthorizationMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .selectable(
                            selected = policy.mode == mode,
                            role = Role.RadioButton,
                            onClick = { onPolicyChange(policy.copy(mode = mode)) },
                        )
                        .testTag("capability-mode:$identity:${mode.name}")
                        .padding(vertical = Dimensions.SpaceSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = policy.mode == mode, onClick = null)
                    Column(Modifier.weight(1f)) {
                        Text(mode.authorizationLabel(), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            mode.authorizationDescription(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (capability.substringBefore('@') == "org.openandroidintelligence.sms.query") {
            SmsPassiveScopeSettings(
                identity = identity,
                policy = policy,
                onPolicyChange = onPolicyChange,
            )
        }
        if (readOnly) {
            Text(
                "此能力仅提供读取；「允许只读」和「自动允许」不会产生额外权限差异。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("capability-read-only:$identity"),
            )
        }
        Text(
            "设置保存在本机，离线也可修改。Android 系统权限、数据范围和必要的系统确认仍单独生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun CapabilityAuthorizationMode.authorizationLabel(): String = when (this) {
    CapabilityAuthorizationMode.MANUAL -> "每次手动"
    CapabilityAuthorizationMode.READ_ONLY -> "允许只读"
    CapabilityAuthorizationMode.AUTOMATIC -> "自动允许"
}

private fun CapabilityAuthorizationMode.authorizationDescription(): String = when (this) {
    CapabilityAuthorizationMode.MANUAL -> "每次请求等待本机确认，也可在确认时选择临时允许。"
    CapabilityAuthorizationMode.READ_ONLY -> "已授权范围内的读取自动执行；发送、修改和控制仍需确认。"
    CapabilityAuthorizationMode.AUTOMATIC -> "在本机允许范围内自动执行，仍须满足系统权限和必要的安全确认。"
}

internal fun capabilityAuthorizationTitle(capability: String): String = when {
    capability.startsWith("org.openandroidintelligence.screen-reader.") -> "屏幕内容读取"
    capability.startsWith("org.openandroidintelligence.screen-capture.") -> "屏幕截图"
    capability.startsWith("org.openandroidintelligence.screen-control.") -> "屏幕控制"
    capability.startsWith("org.openandroidintelligence.sms.query@") -> "短信读取"
    capability.startsWith("org.openandroidintelligence.sms.schedule@") -> "短信定时任务设置"
    capability.startsWith("org.openandroidintelligence.sms.job-status@") -> "短信任务状态查询"
    capability.startsWith("org.openandroidintelligence.sms.cancel@") -> "短信任务取消"
    capability.startsWith("org.openandroidintelligence.notifications.query") -> "通知读取"
    capability.startsWith("org.openandroidintelligence.call-log.query") -> "通话记录读取"
    else -> "设备能力授权"
}
