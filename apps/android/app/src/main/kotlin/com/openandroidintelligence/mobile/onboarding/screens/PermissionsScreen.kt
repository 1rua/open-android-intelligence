package com.openandroidintelligence.mobile.onboarding.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.mobile.onboarding.components.OnboardingStepHeader
import com.openandroidintelligence.conversation.theme.LocalAppStateColors
import com.openandroidintelligence.mobile.onboarding.PERMISSION_NOTICE_BODY
import com.openandroidintelligence.mobile.onboarding.PERMISSION_NOTICE_TITLE
import com.openandroidintelligence.mobile.onboarding.PermissionEntry
import com.openandroidintelligence.mobile.onboarding.PermissionGroup
import com.openandroidintelligence.mobile.onboarding.PermissionStatus

/**
 * 系统权限引导页。
 *
 * 设计约束（来自需求）：
 * 1. **按组呈现**：基础功能 / 可选增强 / 设备能力，每组的承诺强度不同；
 * 2. **动态可见**：调用方传入的 [entries] 已经是「当前设备真正需要」的子集，
 *    本页不做二次过滤——不存在的权限不会出现在列表里；
 * 3. **不诱导、不重复弹窗**：只在用户点按钮时回调 [onRequestPermission]，
 *    进入页面不自动申请；已被永久拒绝的权限改为引导去系统设置；
 * 4. **透明**：底部常驻一块说明，讲清「系统权限 ≠ Agent 授权」。
 *
 * 状态语义：
 * - 运行时权限显示「已授权 / 未授权 / 需去设置」；
 * - 特殊系统访问权限（通知使用权）显示「未开启 / 已在系统设置开启」，
 *   从不说「已授权」——系统并没有给应用一个可查询的授权位。
 */
@Composable
fun PermissionsScreen(
    entries: List<PermissionEntry>,
    statuses: Map<String, PermissionStatus>,
    onRequestPermission: (String) -> Unit,
    onOpenSystemSettings: (String) -> Unit,
    onNext: () -> Unit,
    onSkipOptional: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val grouped = PermissionGroup.entries
        .map { group -> group to entries.filter { it.group == group } }
        .filter { it.second.isNotEmpty() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("onboarding-permissions"),
    ) {
        OnboardingStepHeader(
            categoryTitle = "SYSTEM ACCESS",
            stepIndex = 3,
            totalSteps = 5,
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .widthIn(max = Dimensions.FormWidth)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = Dimensions.ScreenHorizontal),
        ) {
            // ===== 标题 =====
            Text(
                text = "PERMISSIONS",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))
            Text(
                text = "按需授权",
                fontSize = 30.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.9).sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(Dimensions.SpaceCompact))
            Text(
                text = "以下权限只影响对应功能。可选权限跳过不影响登录与对话；" +
                    "系统权限也不等于 Agent 授权——设备能力需在插件启用后按账号单独授权。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ===== 分组 =====
            // 理论上至少有三项基础权限恒定存在；若上游传入空集（异常状态），
            // 这里给一句明确说明而不是留一片空白，符合项目「空状态必须如实呈现」的约定。
            if (grouped.isEmpty()) {
                Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))
                Text(
                    text = "当前没有需要授权的系统权限。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            grouped.forEach { (group, groupEntries) ->
                Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))
                PermissionGroupHeader(group)
                Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))
                groupEntries.forEach { entry ->
                    PermissionCard(
                        entry = entry,
                        status = statuses[entry.id] ?: PermissionStatus.UNKNOWN,
                        onRequest = { onRequestPermission(entry.id) },
                        onOpenSettings = { onOpenSystemSettings(entry.id) },
                    )
                    Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))
                }
            }

            // ===== 常驻说明：权限 ≠ 授权 =====
            Spacer(modifier = Modifier.height(Dimensions.SpaceMedium))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(Dimensions.SpaceCompact),
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .heightIn(min = 44.dp)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
                Spacer(modifier = Modifier.width(Dimensions.SpaceCompact))
                Column {
                    Text(
                        text = PERMISSION_NOTICE_TITLE,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.4.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(modifier = Modifier.height(Dimensions.SpaceTiny))
                    Text(
                        text = PERMISSION_NOTICE_BODY,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))
        }

        // ===== 底部操作 =====
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    horizontal = Dimensions.ScreenHorizontal,
                    vertical = Dimensions.SpaceMedium,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onNext,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .widthIn(max = Dimensions.FormWidth)
                    .fillMaxWidth()
                    .heightIn(min = Dimensions.MinimumTouchTarget + Dimensions.SpaceSmall)
                    .testTag("onboarding-permissions-next"),
            ) {
                Text(text = "下一步", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
                Text(text = "→", style = MaterialTheme.typography.labelLarge)
            }
            TextButton(
                onClick = onSkipOptional,
                modifier = Modifier.testTag("onboarding-permissions-skip"),
            ) {
                Text(
                    text = "跳过可选权限",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 分组标题：左侧中文组名，右侧英文性质提示，底部一条更强的分隔线。 */
@Composable
private fun PermissionGroupHeader(group: PermissionGroup) {
    val (title, hint) = when (group) {
        PermissionGroup.REQUIRED -> "基础功能" to "REQUIRED"
        PermissionGroup.OPTIONAL -> "可选增强" to "OPTIONAL · 可跳过"
        PermissionGroup.DEVICE_CAPABILITY -> "设备能力" to "PLUGIN · 按需"
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimensions.StrokeHairline)
                .background(MaterialTheme.colorScheme.outline),
        )
    }
}

/**
 * 一张权限卡片。
 *
 * 结构：图标容器 + 名称 + 组徽标 + 用途说明 + 状态行 + 授权按钮。
 * 状态变化只改状态点颜色与按钮文案，不改布局，避免授权后卡片高度跳动。
 */
@Composable
private fun PermissionCard(
    entry: PermissionEntry,
    status: PermissionStatus,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val stateColors = LocalAppStateColors.current
    val granted = status == PermissionStatus.GRANTED
    val blocked = status == PermissionStatus.BLOCKED

    val statusText: String
    val actionText: String
    val action: () -> Unit
    // 「每次使用现场确认」的权限只有屏幕采集一项。
    //
    // 不能只看 `isSpecialSystemAccess && manifestPermissions.isEmpty()`：
    // 通知使用权也满足这两个条件，但它**在系统设置里有开关**，用户必须能跳过去开启。
    // 用 id 精确判定，避免把「可去设置开启」误当成「无需操作」而堵死授权路径。
    val dialogOnly = entry.id == "screen_capture"
    when {
        dialogOnly -> {
            statusText = "使用时由系统询问 · 无需现在授权"
            actionText = "无需操作"
            action = {}
        }
        granted -> {
            statusText = "已授权"
            actionText = "已开启"
            action = {}
        }
        blocked -> {
            statusText = "已被拒绝 · 需在系统设置开启"
            actionText = "前往设置"
            action = onOpenSettings
        }
        else -> {
            statusText = "未授权"
            actionText = "开启"
            action = onRequest
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                width = Dimensions.StrokeHairline,
                color = when {
                    granted -> stateColors.success
                    blocked -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
                shape = MaterialTheme.shapes.large,
            )
            .padding(Dimensions.SpaceCompact + Dimensions.SpaceTiny)
            .semantics(mergeDescendants = true) {
                contentDescription = "${entry.displayName}，$statusText"
            }
            .testTag("permission-card-${entry.id}"),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(Dimensions.LeadingIconContainer + 2.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (granted) stateColors.successContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    .border(
                        width = Dimensions.StrokeHairline,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = MaterialTheme.shapes.small,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = entry.icon(),
                    contentDescription = null,
                    tint = if (granted) stateColors.onSuccessContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Dimensions.SmallIcon),
                )
            }
            Spacer(modifier = Modifier.width(Dimensions.SpaceCompact))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
                    GroupBadge(entry)
                }
                Spacer(modifier = Modifier.height(Dimensions.SpaceTiny))
                Text(
                    text = entry.purpose,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(Dimensions.SpaceCompact))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimensions.StrokeHairline)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 颜色不是唯一信号：状态点旁边始终有文字。
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            granted -> stateColors.success
                            blocked -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
            Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            when {
                // 系统对话框型权限：没有可点的授权入口，只给状态文字，避免「点了没反应」。
                dialogOnly -> Text(
                    text = actionText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                !granted -> OutlinedButton(
                    onClick = action,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .heightIn(min = Dimensions.MinimumTouchTarget)
                        .semantics { role = Role.Button },
                ) {
                    Text(text = actionText, style = MaterialTheme.typography.labelMedium)
                }
                else -> Text(
                    text = actionText,
                    style = MaterialTheme.typography.labelMedium,
                    color = stateColors.success,
                )
            }
        }
    }
}

/** 组徽标：色彩克制（只有「必需」用强调色），文字始终存在。 */
@Composable
private fun GroupBadge(entry: PermissionEntry) {
    val (label, color) = when {
        entry.group == PermissionGroup.REQUIRED ->
            "必需" to MaterialTheme.colorScheme.tertiary
        entry.isSpecialSystemAccess ->
            "系统设置" to MaterialTheme.colorScheme.onSurfaceVariant
        entry.group == PermissionGroup.OPTIONAL ->
            "可选" to MaterialTheme.colorScheme.onSurfaceVariant
        else ->
            "按需" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .border(Dimensions.StrokeHairline, color, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.8.sp,
            color = color,
        )
    }
}

/**
 * 权限 id → 图标。
 *
 * 只做映射，不负责语义：图标是辅助识别，读屏靠卡片整体的 contentDescription。
 */
private fun PermissionEntry.icon(): ImageVector = when (id) {
    "notifications", "notification_access" -> Icons.Default.Notifications
    "microphone" -> Icons.Default.Mic
    "screen_capture" -> Icons.Default.ScreenShare
    "sms" -> Icons.Default.Sms
    "call_log" -> Icons.Default.Phone
    else -> Icons.Default.Notifications
}
