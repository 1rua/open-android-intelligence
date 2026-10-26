package com.openandroidintelligence.mobile.screen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import com.openandroidintelligence.control.ScreenPermission
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsNavigationItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.SettingsTone
import com.openandroidintelligence.conversation.theme.Dimensions

/** 无障碍服务组件名；与 screen-control 模块 Manifest 中声明的类一致。 */
private const val ACCESSIBILITY_SERVICE_CLASS =
    "com.openandroidintelligence.screen.ScreenAccessibilityService"

/** 会话有效期选项：远低于 30 分钟上限，默认取最短。 */
private val TTL_OPTIONS: List<Pair<String, Long>> = listOf(
    "5 分钟" to 5 * 60_000L,
    "10 分钟" to 10 * 60_000L,
    "30 分钟" to 30 * 60_000L,
)

/**
 * 「屏幕读取与控制」授权区块。
 *
 * 放在授权管理子页：它回答的是「这台手机把哪些能力交给了 Agent」，与通知行为
 * 配置是两件事。三层授权分开呈现，任何一层缺失都指名缺的是什么。
 */
@Composable
fun ScreenCapabilitySection(
    state: ScreenCapabilityUiState,
    onStartSession: (Set<ScreenPermission>, Long) -> String?,
    onRevoke: () -> Unit,
    onRequestCaptureAuthorization: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConsent by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    SettingsSectionCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            Text(
                text = screenSectionSummary(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            ScreenPermissionRow(ScreenPermission.READ, state)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ScreenPermissionRow(ScreenPermission.CAPTURE, state)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ScreenPermissionRow(ScreenPermission.CONTROL, state)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsNavigationItem(
                headline = "开启无障碍服务",
                supporting = accessibilitySupporting(state),
                icon = Icons.Default.Accessibility,
                enabled = state.providerAvailable,
                onClick = onOpenAccessibilitySettings,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsNavigationItem(
                headline = "系统投屏授权",
                supporting = captureAuthorizationSupporting(state),
                icon = Icons.Default.PhotoCamera,
                // 只有本地会话已含截图权限时才允许发起系统授权：顺序不能反，
                // 否则开始新会话时的清理会合理地关掉刚建立的投影。
                enabled = state.providerAvailable && state.has(ScreenPermission.CAPTURE),
                onClick = onRequestCaptureAuthorization,
            )
        }
    }

    // 动作按钮单独一张卡：它们是「做一件事」，与上面的状态陈述分开。
    SettingsSectionCard(modifier = Modifier.padding(top = Dimensions.SpaceMedium)) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            if (state.hasSession) {
                SettingsNavigationItem(
                    headline = "结束屏幕会话",
                    supporting = "立即撤销本机屏幕许可，并停止正在进行的截图与操作",
                    icon = Icons.Default.StopCircle,
                    tone = SettingsTone.DANGER,
                    onClick = {
                        onRevoke()
                        notice = "屏幕会话已结束，后续调用会被安全拒绝"
                    },
                )
            } else {
                SettingsNavigationItem(
                    headline = "开始屏幕会话",
                    supporting = "选择允许的屏幕权限；不选择任何一项时无法开始",
                    icon = Icons.Default.Visibility,
                    enabled = state.gate == ScreenGate.READY,
                    onClick = { showConsent = true },
                )
            }
            notice?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showConsent) {
        ScreenConsentDialog(
            state = state,
            onDismiss = { showConsent = false },
            onConfirm = { permissions, ttl ->
                val failure = onStartSession(permissions, ttl)
                showConsent = false
                notice = failure ?: "屏幕会话已开始，你可以在有效期内使用屏幕能力"
            },
        )
    }
}

/**
 * 本地同意弹层：三项权限分别勾选，只记录用户本次明确同意的集合。
 *
 * 不提供「全选」按钮 —— 三项的后果不同，让用户逐项确认比一键同意更安全。
 */
@Composable
private fun ScreenConsentDialog(
    state: ScreenCapabilityUiState,
    onDismiss: () -> Unit,
    onConfirm: (Set<ScreenPermission>, Long) -> Unit,
) {
    var read by remember { mutableStateOf(true) }
    var capture by remember { mutableStateOf(false) }
    var control by remember { mutableStateOf(false) }
    var ttlIndex by remember { mutableStateOf(0) }

    val controlAvailable = state.accessibilityReady
    val selected = buildSet {
        if (read) add(ScreenPermission.READ)
        if (capture) add(ScreenPermission.CAPTURE)
        if (control && controlAvailable) add(ScreenPermission.CONTROL)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("允许屏幕能力") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact)) {
                Text(
                    text = "这些许可只保存在这台手机上，随时可以结束。进程重启后需要重新确认。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ConsentRow(
                    headline = screenPermissionHeadline(ScreenPermission.READ),
                    supporting = screenPermissionDescription(ScreenPermission.READ),
                    checked = read,
                    enabled = true,
                    testTag = "screen-consent-read",
                    onCheckedChange = { read = it },
                )
                ConsentRow(
                    headline = screenPermissionHeadline(ScreenPermission.CAPTURE),
                    supporting = screenPermissionDescription(ScreenPermission.CAPTURE),
                    checked = capture,
                    enabled = true,
                    testTag = "screen-consent-capture",
                    onCheckedChange = { capture = it },
                )
                ConsentRow(
                    headline = screenPermissionHeadline(ScreenPermission.CONTROL),
                    supporting = if (controlAvailable) {
                        screenPermissionDescription(ScreenPermission.CONTROL)
                    } else {
                        "需要先在系统设置中开启无障碍服务，开启后才能选择这一项"
                    },
                    checked = control && controlAvailable,
                    enabled = controlAvailable,
                    testTag = "screen-consent-control",
                    onCheckedChange = { control = it },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "有效期",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TTL_OPTIONS.forEachIndexed { index, (label, _) ->
                        TextButton(
                            onClick = { ttlIndex = index },
                            modifier = Modifier.testTag("screen-consent-ttl-$index"),
                        ) {
                            Text(
                                text = label,
                                color = if (index == ttlIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                Text(
                    text = captureWarning(selected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected, TTL_OPTIONS[ttlIndex].second) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.testTag("screen-consent-confirm"),
            ) { Text("开始屏幕会话") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ConsentRow(
    headline: String,
    supporting: String,
    checked: Boolean,
    enabled: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    val interactive = enabled
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (interactive) {
                    Modifier.toggleable(
                        value = checked,
                        role = Role.Checkbox,
                        onValueChange = onCheckedChange,
                    )
                } else {
                    Modifier
                },
            )
            .testTag(testTag),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = headline,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 只读权限行：回答「这一项现在能不能用、为什么」。 */
@Composable
private fun ScreenPermissionRow(permission: ScreenPermission, state: ScreenCapabilityUiState) {
    SettingsListItem(
        headline = screenPermissionHeadline(permission),
        supporting = permissionSupporting(permission, state),
        icon = when (permission) {
            ScreenPermission.READ -> Icons.Default.Visibility
            ScreenPermission.CAPTURE -> Icons.Default.PhotoCamera
            ScreenPermission.CONTROL -> Icons.Default.Accessibility
        },
        // 已允许用 PRIMARY 强调；未允许保持中性。颜色不是唯一信号 ——
        // 同一行的 supporting 文字始终写明「已允许 / 未允许」及具体原因。
        tone = if (state.has(permission)) SettingsTone.PRIMARY else SettingsTone.NEUTRAL,
        modifier = Modifier.testTag("screen-permission-${permission.name.lowercase()}"),
    )
}

/** 总述：先回答「现在能不能用」。 */
internal fun screenSectionSummary(state: ScreenCapabilityUiState): String = when {
    !state.providerAvailable ->
        "当前版本未接入屏幕能力，这里不会有可以生效的开关。"
    !state.connectionReady ->
        "还没有连接到已配对的设备。屏幕能力需要在登录并配对之后才能授予。"
    state.hasSession -> {
        val remaining = state.session?.let { formatRemaining(it.remainingMillis) } ?: ""
        "屏幕会话进行中（$remaining）。结束会话会立即撤销所有已授予的屏幕权限。"
    }
    else -> "屏幕能力默认关闭。开始会话后，Agent 才能按你选择的权限读取或操作屏幕。"
}

/** 单项权限的现状说明。 */
internal fun permissionSupporting(permission: ScreenPermission, state: ScreenCapabilityUiState): String {
    if (!state.providerAvailable) return "当前版本未接入屏幕能力"
    if (!state.connectionReady) return "未连接设备，暂不可授予"
    if (state.has(permission)) {
        val error = state.capabilityErrors[permission]
        return if (error == null) {
            "已允许。${screenPermissionDescription(permission)}"
        } else {
            "已允许，但当前不可用：${readableScreenError(error)}"
        }
    }
    // 未授予时指出缺的是什么，而不是笼统说「未授权」。
    return when (permission) {
        ScreenPermission.READ -> if (state.accessibilityReady) {
            "未允许。读取需要无障碍服务，服务已就绪，可在开始会话时选择"
        } else {
            "未允许。读取需要先开启无障碍服务"
        }
        ScreenPermission.CAPTURE -> if (state.captureSystemGranted) {
            "未允许。开始包含截图权限的会话后，再完成一次系统投屏授权"
        } else {
            "未允许。开始包含截图权限的会话后，再完成一次系统投屏授权"
        }
        ScreenPermission.CONTROL -> if (state.accessibilityReady) {
            "未允许。需要在开始会话时单独勾选，并且每次操作仍会单独确认"
        } else {
            "未允许。需要先开启无障碍服务"
        }
    }
}

/** 无障碍入口的现状说明。 */
internal fun accessibilitySupporting(state: ScreenCapabilityUiState): String = when {
    !state.providerAvailable -> "当前版本未接入屏幕能力"
    state.accessibilityReady -> "无障碍服务已连接，可以读取界面结构和执行操作"
    else -> "尚未连接：${readableScreenError(state.accessibilityError)}"
}

/** 系统投屏授权现状说明。 */
internal fun captureAuthorizationSupporting(state: ScreenCapabilityUiState): String = when {
    !state.providerAvailable -> "当前版本未接入屏幕能力"
    !state.has(ScreenPermission.CAPTURE) -> "需要先开始一个包含截图权限的屏幕会话"
    state.captureSystemGranted -> "已获得系统投屏授权，可以截图"
    else -> "尚未授权：${readableScreenError(state.captureError)}"
}

/** 勾选截图时的额外提醒：像素不等于控制坐标。 */
private fun captureWarning(selected: Set<ScreenPermission>): String =
    if (ScreenPermission.CAPTURE in selected) {
        "截图会让你在开始后再确认一次系统投屏弹窗；截图像素不直接等于控制坐标。"
    } else {
        ""
    }

/**
 * 打开系统无障碍设置。
 *
 * 交接文档允许在系统支持时直达本应用服务的详情页，但
 * `ACTION_ACCESSIBILITY_DETAILS_SETTINGS` 与
 * `EXTRA_ACCESSIBILITY_SERVICE_COMPONENT_NAME` 是系统内部常量，
 * **不在公开 SDK 中**（android.jar 里只有 `ACTION_ACCESSIBILITY_SETTINGS`）。
 * 用裸字符串硬拼这些未公开的常量等于依赖非公开 API，会在不同厂商 ROM 上行为不一致，
 * 因此这里只使用平台公开入口，由用户在系统页里找到本应用的服务 —— 交接文档明确
 * 「不支持则退回标准设置页」，这就是那条退让路径。
 *
 * 绝不尝试用任何方式自动开启服务：那必须由用户在系统界面亲手完成。
 */
fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        android.widget.Toast.makeText(
            context,
            "系统未提供无障碍设置入口",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    } catch (_: SecurityException) {
        android.widget.Toast.makeText(
            context,
            "系统未提供无障碍设置入口",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }
}

/** 无障碍服务类名：与 screen-control 模块 Manifest 声明的组件完全一致，供文档与校验引用。 */
fun accessibilityServiceClassName(): String = ACCESSIBILITY_SERVICE_CLASS