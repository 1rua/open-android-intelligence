package com.openandroidintelligence.mobile.settings.advanced

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.settings.SectionLabel
import com.openandroidintelligence.mobile.settings.SettingsPageColumn
import com.openandroidintelligence.mobile.settings.SettingsPageScaffold
import com.openandroidintelligence.mobile.updates.InstalledAppBuild
import com.openandroidintelligence.mobile.updates.appBuildChannelLabel
import com.openandroidintelligence.mobile.updates.installedAppBuild

@Composable
internal fun BuildDetailsScreen(presentation: SettingsPresentation, onBack: () -> Unit) {
    val context = LocalContext.current
    val installed = remember(context) { installedAppBuild(context.applicationContext) }
    BuildDetailsContent(installed = installed, presentation = presentation, onBack = onBack)
}

@Composable
internal fun BuildDetailsContent(installed: InstalledAppBuild, presentation: SettingsPresentation, onBack: () -> Unit) {
    SettingsPageScaffold(title = "构建详情", presentation = presentation, onBack = onBack) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            SectionLabel("当前安装")
            SettingsSectionCard {
                SettingsListItem(headline = "版本", supporting = installed.versionName.ifBlank { "未知" })
                SettingsListItem(headline = "版本代码", supporting = installed.versionCode.toString())
                SettingsListItem(
                    headline = "构建渠道",
                    supporting = settingsBuildChannelLabel(installed),
                )
                SettingsListItem(headline = "Git Commit", supporting = installed.commit.ifBlank { "未知" })
                SettingsListItem(headline = "应用 ID", supporting = installed.applicationId.ifBlank { "未知" })
                SettingsListItem(headline = "分发版本", supporting = installed.distribution.ifBlank { "未知" })
                SettingsListItem(
                    headline = "签名证书 SHA-256",
                    supporting = installed.signingCertificateSha256?.takeIf { it.isNotBlank() } ?: "未知",
                )
            }
            Text(
                "以上信息来自当前安装包及其构建信息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
