package com.openandroidintelligence.mobile.settings.capabilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.testTag
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.kernel.DeviceOperation
import com.openandroidintelligence.kernel.LocalCapabilityApproval
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.mobile.plugins.ProductionPluginHost
import kotlinx.coroutines.delay

internal fun remainingSeconds(millis: Long): Long = millis.coerceAtLeast(0L) / 1000L +
    if (millis > 0 && millis % 1000L != 0L) 1 else 0

@Composable
internal fun TemporaryApprovalsSection(grants: PairingGrantStateHolder, host: ProductionPluginHost?) {
    val controller = grants.authorization
    val localState by grants.state.collectAsState()
    val changes by controller.changes.collectAsState()
    val revision = localState?.revision
    var approvals by remember(controller) { mutableStateOf<List<LocalCapabilityApproval>>(emptyList()) }
    var clockTick by remember(controller) { mutableStateOf(0L) }
    var revokeId by remember { mutableStateOf<String?>(null) }
    var revokeAll by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(controller, revision, changes, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            approvals = if (revision == null) emptyList() else controller.activeApprovals(revision)
            if (revision != null && approvals.isNotEmpty()) {
                while (true) {
                    delay(1_000L)
                    approvals = controller.activeApprovals(revision)
                    clockTick++
                    if (approvals.isEmpty()) break
                }
            }
        }
    }
    if (revokeId != null || revokeAll) {
        AlertDialog(
            onDismissRequest = { revokeId = null; revokeAll = false },
            title = { Text(if (revokeAll) "撤销全部临时授权" else "撤销这条临时授权") },
            text = { Text("撤销后，相应请求需要重新确认；长期能力设置和 Android 系统权限不变。") },
            confirmButton = {
                TextButton(onClick = {
                    if (revokeAll) grants.revokeTemporaryApprovals()
                    else revokeId?.let(controller::revoke)
                    revokeId = null
                    revokeAll = false
                }, modifier = Modifier.testTag("confirm-revoke-approval")) { Text("撤销") }
            },
            dismissButton = { TextButton(onClick = { revokeId = null; revokeAll = false }) { Text("取消") } },
        )
    }
    SettingsSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            Text("临时批准仅在本进程有效，并严格匹配能力、操作与完整参数摘要；不同参数不会沿用批准。")
            if (revision == null) Text("无法读取当前本机授权版本。")
            else if (approvals.isEmpty()) Text("当前没有生效的临时授权。")
            approvals.forEach { approval ->
                val hostRevision = host?.revision?.collectAsState()?.value
                val entry = remember(host, hostRevision, approval.scope) { host?.capabilityForAuthorizationScope(approval.scope) }
                Text(entry?.let(::capabilityTitle) ?: approval.scope.capability, style = MaterialTheme.typography.titleSmall)
                entry?.let { Text("${it.identity.pluginId} · ${it.key}", style = MaterialTheme.typography.bodySmall) }
                val operation = when (approval.scope.operation) {
                    DeviceOperation.READ -> "读取"
                    DeviceOperation.WRITE -> "修改"
                    DeviceOperation.CONTROL -> "控制"
                }
                Text("操作：$operation · ${if (approval.onlyRequestId != null) "仅本次请求" else "有效期内参数完全相同的请求"}")
                approval.displaySummary.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (approval.displaySummary.isEmpty()) Text("没有保存可读参数说明；只有完整参数一致的请求能使用此授权。", style = MaterialTheme.typography.bodySmall)
                Text("参数校验摘要：${approval.scope.parametersSha256}", style = MaterialTheme.typography.bodySmall)
                val seconds = remember(approval, clockTick) { remainingSeconds(controller.remainingMillis(approval)) }
                Text("剩余约 $seconds 秒", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { revokeId = approval.id },
                    modifier = Modifier.testTag("revoke-approval:${approval.id}")) { Text("撤销此项") }
            }
            if (approvals.isNotEmpty()) {
                TextButton(onClick = { revokeAll = true }, modifier = Modifier.testTag("revoke-all-approvals")) {
                    Text("撤销全部临时授权")
                }
            }
        }
    }
}
