package com.openandroidintelligence.mobile.settings.advanced

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsNavigationItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.mobile.GatewayRuntime
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.SettingsUiState
import com.openandroidintelligence.mobile.settings.SectionLabel
import com.openandroidintelligence.mobile.settings.SettingsPageColumn
import com.openandroidintelligence.mobile.settings.SettingsPageScaffold
import com.openandroidintelligence.mobile.updates.appBuildChannelLabel
import com.openandroidintelligence.mobile.updates.installedAppBuild

private const val PROJECT_URL = "https://github.com/1rua/open-android-intelligence"
private const val DOCUMENTATION_URL = "$PROJECT_URL/tree/dev/docs"
private const val ISSUES_URL = "$PROJECT_URL/issues"

@Composable
internal fun AdvancedSettingsScreen(
    uiState: SettingsUiState,
    runtime: GatewayRuntime?,
    presentation: SettingsPresentation,
    onBack: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenBuildDetails: () -> Unit,
    onOpenLicenses: () -> Unit,
    onReplayOnboarding: () -> Unit,
) {
    val context = LocalContext.current
    val installed = remember(context) { installedAppBuild(context.applicationContext) }
    var failedUrls by remember { mutableStateOf(emptySet<String>()) }
    fun openUrl(url: String) {
        failedUrls = try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            failedUrls - url
        } catch (_: ActivityNotFoundException) {
            failedUrls + url
        } catch (_: SecurityException) {
            failedUrls + url
        }
    }

    SettingsPageScaffold(title = "高级设置与关于", presentation = presentation, onBack = onBack) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            SectionLabel("消息处理")
            MessageProcessingSettings(gatewayUrl = uiState.connectedGatewayUrl, runtime = runtime)

            SectionLabel("应用维护")
            SettingsSectionCard {
                SettingsNavigationItem(
                    headline = "应用更新",
                    supporting = "检查正式版和预览版更新",
                    onClick = onOpenUpdates,
                )
            }

            SectionLabel("帮助与反馈")
            SettingsSectionCard {
                SettingsNavigationItem(
                    headline = "新手引导与部署教程",
                    supporting = "查看 Gateway 部署教程与权限说明",
                    onClick = onReplayOnboarding,
                )
                SettingsNavigationItem(headline = "项目文档", onClick = { openUrl(DOCUMENTATION_URL) })
                SettingsNavigationItem(headline = "问题与反馈", onClick = { openUrl(ISSUES_URL) })
                if (DOCUMENTATION_URL in failedUrls || ISSUES_URL in failedUrls) ExternalLinkFailure()
            }

            SectionLabel("关于 OAI")
            SettingsSectionCard {
                SettingsListItem(headline = "版本", supporting = installed.versionName.ifBlank { "未知" })
                SettingsListItem(
                    headline = "构建渠道",
                    supporting = settingsBuildChannelLabel(installed),
                )
                SettingsListItem(headline = "Git Commit", supporting = installed.commit.ifBlank { "未知" })
                SettingsNavigationItem(headline = "构建详情", onClick = onOpenBuildDetails)
                SettingsNavigationItem(headline = "开源许可证", onClick = onOpenLicenses)
                SettingsNavigationItem(headline = "项目地址", supporting = PROJECT_URL, onClick = { openUrl(PROJECT_URL) })
                if (PROJECT_URL in failedUrls) ExternalLinkFailure()
            }
        }
    }
}

@Composable
private fun ExternalLinkFailure() {
    Text(
        "无法打开链接，请检查系统浏览器后重试。",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
