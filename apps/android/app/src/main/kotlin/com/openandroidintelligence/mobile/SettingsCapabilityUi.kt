package com.openandroidintelligence.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.SettingsSwitchItem
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions

/** 操作提示条关闭按钮的 contentDescription，界面测试据此定位。 */
const val NOTICE_DISMISS_LABEL = "关闭操作提示"

/**
 * 宿主是否接入了真实的屏幕截图来源。
 *
 * 现状：已接入（Wave 1 装配 [com.openandroidintelligence.capability.MediaProjectionScreenCaptureSource]，
 * 经系统授权对话框显式授予后可采集；来源缺失/未授权/采集失败时圈选层
 * [com.openandroidintelligence.conversation.selection.ScreenSelectionOverlay] 仍渲染不可用态）。
 * 若未来撤下来源，把这里改回 false 即恢复如实降级。
 */
const val SCREENSHOT_SOURCE_WIRED = true

/**
 * 宿主是否装配了通知采集通道。
 *
 * 现状：已装配（Wave 1 按 D6 注入 `:notification-control`/`:notification-host`，
 * 监听服务在 Manifest 声明、经系统「通知使用权」显式授予；registry 未装配时
 * 端口 deny-first，设置子页如实呈现「未装配」）。
 */
const val PUSH_CHANNEL_WIRED = true

/**
 * 设置页「屏幕上下文分析与圈选」条目的呈现结论：
 * 能力可用需要「宿主接入了截图来源」+「网关同意回传圈选结果」+「存在活动配对」
 * 三件事同时成立；缺任何一件，supporting 都要指名缺的是什么。
 */
fun screenSelectionCapabilityPresentation(
    grantsBound: Boolean,
    attachmentStatusAgreed: Boolean?,
    screenshotSourceWired: Boolean = SCREENSHOT_SOURCE_WIRED,
): CapabilityPresentation = when {
    !grantsBound -> CapabilityPresentation(
        enabled = false,
        supporting = "未绑定活动配对，授权暂不可用",
    )
    else -> screenSelectionPresentation(
        screenshotSourceWired = screenshotSourceWired,
        attachmentStatusAgreed = attachmentStatusAgreed,
    )
}

/**
 * 设置页「系统通知推送」条目的呈现结论：
 * 需要「宿主装配了推送通道」+「网关同意会话镜像」+「存在活动配对」同时成立。
 */
fun notificationPushCapabilityPresentation(
    grantsBound: Boolean,
    mirrorAgreed: Boolean?,
    pushChannelWired: Boolean = PUSH_CHANNEL_WIRED,
): CapabilityPresentation = when {
    !grantsBound -> CapabilityPresentation(
        enabled = false,
        supporting = "未绑定活动配对，授权暂不可用",
    )
    else -> notificationPushPresentation(
        pushChannelWired = pushChannelWired,
        mirrorAgreed = mirrorAgreed,
    )
}

/**
 * 会话级操作提示条：登出/续期这类落网结果必须可见、可关，而不是静默。
 *
 * 文案由 [GatewayRuntime] 生成——只陈述事实与下一步动作；这里只负责让它
 * 出现在界面上，并提供明确的关闭路径。
 */
@Composable
fun OperationNoticeBanner(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(AppRadius.Medium),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            modifier = Modifier.padding(horizontal = Dimensions.SpaceMedium, vertical = Dimensions.SpaceSmall),
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = NOTICE_DISMISS_LABEL,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** [NegotiatedCapabilitiesGroup] 渲染的闭集键 → 人类可读名。 */
val negotiatedCapabilityLabels: Map<String, String> = mapOf(
    "agent-command-catalog-v1" to "指令目录",
    "agent-command-new-v1" to "新建对话指令",
    "agent-approval-cards-v1" to "审批卡片",
    "message-batches-v1" to "消息批量下发",
    "newline-v1" to "换行输入",
    "generation-cancel-v1" to "生成中止",
    "conversation-mirror-v1" to "会话镜像",
    "attachment-status-v1" to "圈选结果回传",
)

/**
 * 「协商能力」分组：把 [ConnectionPhase.Connected] 带回的能力位按契约 §4
 * 的 8 项闭集如实呈现。三种状态各有各的话——
 * `true`「已启用」、`false`「本网关不支持」、`null`「未声明」——任何把
 * 未声明折叠成不支持的写法都会把一台可以升级的网关说成已拒绝。
 */
@Composable
fun NegotiatedCapabilitiesGroup(
    conversationUi: Map<String, Boolean?>,
    modifier: Modifier = Modifier,
) {
    SettingsSectionCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact)) {
            ConversationUiFeature.CLOSED_SET.forEachIndexed { index, key ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                SettingsListItem(
                    headline = negotiatedCapabilityLabels[key] ?: key,
                    supporting = conversationUiStatusLabel(conversationUi[key]),
                    icon = null,
                )
            }
        }
    }
}

/** 图标由调用方决定；圈选条目与推送条目只是文案与状态不同。 */
private fun capabilitySupporting(presentation: CapabilityPresentation): String = presentation.supporting

/**
 * 「屏幕上下文分析与圈选」授权条目。
 *
 * 能力未接入（无截图来源，或网关没有同意回传）时整条禁用：一个打不开也
 * 不会生效的开关，比一个点了没反应的开关更诚实。开关呈现的是本地授权，
 * supporting 呈现的是能力现状，两者不混为一谈。
 */
@Composable
fun ScreenSelectionCapabilityItem(
    checked: Boolean,
    presentation: CapabilityPresentation,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    SettingsSwitchItem(
        headline = "屏幕上下文分析与圈选",
        supporting = capabilitySupporting(presentation),
        checked = checked,
        enabled = presentation.enabled,
        icon = icon,
        onCheckedChange = onCheckedChange,
        modifier = modifier.testTag("screen-selection-capability-item"),
    )
}

/**
 * 「系统通知推送」授权条目，降级规则同 [ScreenSelectionCapabilityItem]：
 * 推送通道未装配或会话镜像未协商一致时明确不可用。
 */
@Composable
fun NotificationPushCapabilityItem(
    checked: Boolean,
    presentation: CapabilityPresentation,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    SettingsSwitchItem(
        headline = "系统通知推送",
        supporting = capabilitySupporting(presentation),
        checked = checked,
        enabled = presentation.enabled,
        icon = icon,
        onCheckedChange = onCheckedChange,
        modifier = modifier.testTag("notification-push-capability-item"),
    )
}
