package com.openandroidintelligence.mobile.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions
import kotlinx.coroutines.delay

/** 展示可信请求上下文并提交用户选择；许可签发、范围校验和超时由执行层处理。 */
@Composable
fun ScreenControlConfirmationDialog(
    requestId: String,
    pluginId: String,
    capability: String,
    parameters: String,
    agentName: String,
    purpose: String,
    operation: String,
    expiresAtMillis: Long,
    onApprove: (durationMinutes: Int) -> Unit,
    onDeny: () -> Unit,
) {
    val action = remember(capability, operation, parameters) { deviceRequestAction(capability, operation, parameters) }
    val parameterLines = remember(parameters) { deviceRequestParameterLines(parameters) }
    var showTechnical by remember(requestId) { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis(), requestId, expiresAtMillis) {
        value = System.currentTimeMillis()
        while (value < expiresAtMillis) {
            delay(minOf(1_000L, expiresAtMillis - value))
            value = System.currentTimeMillis()
        }
    }
    val expired = now >= expiresAtMillis

    val maximumHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f
    Dialog(onDismissRequest = onDeny) {
        Surface(
            modifier = Modifier.widthIn(min = 280.dp, max = 560.dp).fillMaxWidth().heightIn(max = maximumHeight),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                Text("确认设备请求", style = MaterialTheme.typography.headlineSmall)
                Column(
                    verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                ) {
                    Text("Agent 声明名称：${agentName.ifBlank { "未提供" }}", modifier = Modifier.testTag("device-confirmation-agent"))
                    Text("操作目的：${purpose.ifBlank { "未提供" }}", modifier = Modifier.testTag("device-confirmation-purpose"))
                    Text(
                        "Agent 名称和操作目的由请求方声明，仅供展示与审计，不证明身份或扩大权限。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(action, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("screen-confirmation-action"))
                    Text("操作类型：${operation.ifBlank { "未提供" }}")
                    Text("本次参数与范围", style = MaterialTheme.typography.titleSmall)
                    parameterLines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Text(
                        deviceRequestSensitiveNotice(capability),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("device-confirmation-sensitive"),
                    )
                    Text(
                        "限时允许仅适用于同一能力、操作和已批准的数据范围，并由合法接入的 Agent 共享。Android 系统授权仍需单独满足。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (expired) "请求已过期，不能继续允许。" else "剩余确认时间：${(expiresAtMillis - now + 999L) / 1_000L} 秒",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("device-confirmation-expiry"),
                    )
                    TextButton(
                        onClick = { showTechnical = !showTechnical },
                        modifier = Modifier.testTag("screen-confirmation-details-toggle"),
                    ) { Text(if (showTechnical) "隐藏技术详情" else "查看技术详情") }
                    if (showTechnical) {
                        Text(
                            "插件：$pluginId\n能力：$capability\n参数：$parameters",
                            style = AppTextStyles.MonoSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().testTag("screen-confirmation-details"),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0, 5, 30, 60).forEach { duration ->
                        TextButton(
                            onClick = { if (System.currentTimeMillis() < expiresAtMillis) onApprove(duration) },
                            enabled = !expired,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("device-confirmation-allow:$duration"),
                        ) { Text(if (duration == 0) "仅允许本次" else if (duration == 60) "允许 1 小时" else "允许 $duration 分钟") }
                    }
                    TextButton(onClick = onDeny,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("screen-confirmation-deny")) {
                        Text("拒绝", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
