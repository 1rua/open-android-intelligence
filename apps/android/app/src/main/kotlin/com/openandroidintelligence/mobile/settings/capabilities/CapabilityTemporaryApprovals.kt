package com.openandroidintelligence.mobile.settings.capabilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.kernel.DeviceOperation
import com.openandroidintelligence.kernel.LocalCapabilityApproval
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import kotlinx.coroutines.delay

/** 剩余秒数向上取整；已过期返回 0，绝不把过期批准显示成还有时间。 */
internal fun remainingSeconds(millis: Long): Long = millis.coerceAtLeast(0L) / 1000L +
    if (millis > 0 && millis % 1000L != 0L) 1 else 0

/**
 * 一项能力当前生效的临时授权，以及就地撤销。
 *
 * 主页面不再单列「临时授权」区域，但临时授权机制本身必须保留：撤销入口随能力走，
 * 用户在这一项能力里看到的是它真实生效的批准，而不是一张混着所有能力的清单。
 */
@Composable
internal fun CapabilityTemporaryApprovals(
    grants: PairingGrantStateHolder,
    capabilityKeys: Set<String>,
    modifier: Modifier = Modifier,
) {
    val controller = grants.authorization
    val localState by grants.state.collectAsState()
    val changes by controller.changes.collectAsState()
    val revision = localState?.revision
    var approvals by remember(controller) { mutableStateOf<List<LocalCapabilityApproval>>(emptyList()) }
    var clockTick by remember(controller) { mutableStateOf(0L) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(controller, revision, changes, lifecycle, capabilityKeys) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            fun mine(): List<LocalCapabilityApproval> =
                if (revision == null) emptyList() else controller.activeApprovals(revision).filter { it.scope.capability in capabilityKeys }
            approvals = mine()
            while (approvals.isNotEmpty()) {
                delay(1_000L)
                approvals = mine()
                clockTick++
            }
        }
    }
    if (approvals.isEmpty()) return

    var revokeAll by remember { mutableStateOf(false) }
    if (revokeAll) {
        AlertDialog(
            onDismissRequest = { revokeAll = false },
            title = { Text("撤销这些临时授权") },
            text = { Text("撤销后，相应请求需要重新确认；长期能力设置和 Android 系统权限不变。") },
            confirmButton = {
                TextButton(onClick = {
                    approvals.forEach { controller.revoke(it.id) }
                    revokeAll = false
                }, modifier = Modifier.testTag("confirm-revoke-capability-approvals")) { Text("撤销") }
            },
            dismissButton = { TextButton(onClick = { revokeAll = false }) { Text("取消") } },
        )
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
        SettingsSectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                Text("生效中的临时授权", style = MaterialTheme.typography.titleSmall)
                approvals.forEach { approval ->
                    val operation = when (approval.scope.operation) {
                        DeviceOperation.READ -> "读取"
                        DeviceOperation.WRITE -> "修改"
                        DeviceOperation.CONTROL -> "控制"
                    }
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("$operation · ${if (approval.onlyRequestId != null) "单次请求" else "相同参数请求"}",
                                style = MaterialTheme.typography.bodyMedium)
                            val seconds = remember(approval, clockTick) { remainingSeconds(controller.remainingMillis(approval)) }
                            Text("剩余约 $seconds 秒", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { controller.revoke(approval.id) },
                            modifier = Modifier.testTag("revoke-capability-approval:${approval.id}")) { Text("撤销") }
                    }
                }
                TextButton(onClick = { revokeAll = true }, modifier = Modifier.testTag("revoke-capability-approvals")) {
                    Text("撤销全部")
                }
            }
        }
    }
}
