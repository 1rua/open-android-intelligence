package com.openandroidintelligence.mobile.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.kernel.CapabilityPolicy

/** 短信被动读取范围的编辑草稿；唯一持久化仍通过当前能力的 policy 回调。 */
@Composable
internal fun SmsPassiveScopeSettings(
    identity: String,
    policy: CapabilityPolicy,
    onPolicyChange: (CapabilityPolicy) -> Unit,
) {
    var senders by remember(identity, policy.allowedSources) {
        mutableStateOf(policy.allowedSources.sorted().joinToString("\n"))
    }
    var includeContent by remember(identity, policy.includeContent) { mutableStateOf(policy.includeContent) }
    val allowedSources = parseSmsAllowedSources(senders)
    val changed = allowedSources != policy.allowedSources || includeContent != policy.includeContent

    Column(
        modifier = Modifier.fillMaxWidth().testTag("sms-passive-scope:$identity"),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        Text("短信被动查询范围", style = MaterialTheme.typography.titleSmall)
        Text(
            "仅限制本能力的短信读取。主动推送的范围和接收目标另行配置。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = senders,
            onValueChange = { senders = it },
            label = { Text("允许的发送方") },
            supportingText = { Text("用逗号或换行分隔；清单为空表示允许全部发送方。") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().testTag("sms-passive-senders:$identity"),
        )
        Text(
            if (allowedSources.isEmpty()) "发送方范围：全部发送方" else "发送方范围：清单中的 ${allowedSources.size} 个发送方",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("sms-passive-scope-summary:$identity"),
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("包含短信正文", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (includeContent) "允许读取短信正文" else "仅允许读取发送方、时间等信息，不包含正文",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = includeContent,
                onCheckedChange = { includeContent = it },
                modifier = Modifier.testTag("sms-passive-content:$identity")
                    .semantics { contentDescription = "包含短信正文" },
            )
        }
        TextButton(
            enabled = changed,
            onClick = { onPolicyChange(policy.copy(allowedSources = allowedSources, includeContent = includeContent)) },
            modifier = Modifier.testTag("sms-passive-save:$identity"),
        ) { Text("保存被动查询范围") }
    }
}

internal fun parseSmsAllowedSources(text: String): Set<String> = text.split(Regex("[,，\\r\\n]+"))
    .map(String::trim)
    .filter(String::isNotEmpty)
    .toSet()
