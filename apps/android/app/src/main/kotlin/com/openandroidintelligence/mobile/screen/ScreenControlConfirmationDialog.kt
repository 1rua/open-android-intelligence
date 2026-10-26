package com.openandroidintelligence.mobile.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions

/**
 * 面向其他应用的本地确认弹层。
 *
 * 改造前这里直接把 `pluginId`、能力 ID 和原始 JSON 参数打在弹窗里 —— 用户看到的
 * 是一串没有意义的技术标识，既无法据此判断该不该允许，又暴露了内部实现细节。
 *
 * 现在：默认只显示「要做什么 + 对谁做」，原始参数折叠在「技术详情」里按需展开，
 * 供排障使用。确认结论仍由既有 `DeviceExecutionDriver.decide()` 处理，
 * 本弹层不参与任何授权判定。
 */
@Composable
fun ScreenControlConfirmationDialog(
    pluginId: String,
    capability: String,
    parameters: String,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val summary = remember(capability, parameters) {
        summarizeDeviceControl(capability, parameters)
    }
    var showTechnical by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDeny,
        title = {
            Text(if (summary.isScreen) "允许这次屏幕操作" else "确认设备操作")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = summary.action,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("screen-confirmation-action"),
                )
                summary.target?.let { target ->
                    Text(
                        text = "作用于：$target",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (summary.isScreen) {
                        "允许后这次操作才会执行；页面内容变化时可能需要重新读取并再次确认。"
                    } else {
                        "允许后这次操作才会执行。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(
                    onClick = { showTechnical = !showTechnical },
                    modifier = Modifier.testTag("screen-confirmation-details-toggle"),
                ) {
                    Text(if (showTechnical) "隐藏技术详情" else "查看技术详情")
                }
                if (showTechnical) {
                    Text(
                        text = "插件：$pluginId\n能力：$capability\n参数：$parameters",
                        style = AppTextStyles.MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("screen-confirmation-details"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onApprove,
                modifier = Modifier.testTag("screen-confirmation-approve"),
            ) { Text("允许这次操作") }
        },
        dismissButton = {
            TextButton(
                onClick = onDeny,
                modifier = Modifier.testTag("screen-confirmation-deny"),
            ) { Text("不允许") }
        },
    )
}
