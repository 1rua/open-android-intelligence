package com.openandroidintelligence.mobile.settings.notifications

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsNavigationItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.SettingsSwitchItem
import com.openandroidintelligence.mobile.ConversationBubbleSettingsItem
import com.openandroidintelligence.mobile.LiveUpdatesSettingsItem
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.SettingsUiState
import com.openandroidintelligence.mobile.settings.SectionLabel
import com.openandroidintelligence.mobile.settings.SettingsPageColumn
import com.openandroidintelligence.mobile.settings.SettingsPageScaffold

@Composable
internal fun NotificationSettingsScreen(
    uiState: SettingsUiState,
    presentation: SettingsPresentation,
    onBack: () -> Unit,
    onSetNotifications: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    fun openSystemNotifications() {
        error = try {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            null
        } catch (_: ActivityNotFoundException) { "无法打开系统通知设置，请在 Android 设置中找到本应用。" }
    }
    SettingsPageScaffold("通知与提醒", presentation, onBack) { padding ->
        SettingsPageColumn(padding) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SectionLabel("App 通知偏好")
            SettingsSectionCard {
                SettingsSwitchItem(
                    headline = "Agent 任务通知",
                    checked = uiState.replyNotificationsEnabled,
                    enabled = uiState.replyNotificationsWired,
                    supporting = if (uiState.replyNotificationsWired) "显示任务进度与完成提醒，可在通知中回复" else "当前版本未接入任务通知",
                    onCheckedChange = onSetNotifications,
                )
            }
            SectionLabel("Android 系统设置")
            SettingsSectionCard {
                SettingsListItem("系统通知权限", supporting = if (uiState.replyNotificationsAllowed) "允许通知" else "系统未允许任务通知；上面的 App 偏好仍会保留")
                SettingsNavigationItem("系统通知设置", supporting = "在 Android 中管理权限和通知类别", onClick = ::openSystemNotifications)
                ConversationBubbleSettingsItem(uiState.replyNotificationsAllowed)
                if (android.os.Build.VERSION.SDK_INT >= 36) {
                    SettingsListItem("实时任务状态权限", supporting = if (uiState.liveUpdatesAllowed) "系统允许突出显示进行中的任务" else "系统尚未允许，或任务通知类别已关闭")
                    LiveUpdatesSettingsItem()
                } else {
                    SettingsListItem("实时任务状态", supporting = "需要 Android 16 或更高版本")
                }
            }
        }
    }
}
