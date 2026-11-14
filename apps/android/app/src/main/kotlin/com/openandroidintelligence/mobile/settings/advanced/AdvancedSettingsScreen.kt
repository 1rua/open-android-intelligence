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
import com.openandroidintelligence.mobile.settings.SectionLabel
import com.openandroidintelligence.mobile.settings.SettingsPageColumn
import com.openandroidintelligence.mobile.settings.SettingsPageScaffold
import androidx.compose.ui.res.stringResource
import com.openandroidintelligence.ui.resources.R as UiR
import com.openandroidintelligence.mobile.updates.appBuildChannelLabel
import com.openandroidintelligence.mobile.updates.installedAppBuild

private const val PROJECT_URL = "https://github.com/1rua/open-android-intelligence"
private const val DOCUMENTATION_URL = "$PROJECT_URL/tree/dev/docs"
private const val ISSUES_URL = "$PROJECT_URL/issues"

@Composable
internal fun AdvancedSettingsScreen(
    runtime: GatewayRuntime?,
    presentation: SettingsPresentation,
    onBack: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenBuildDetails: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenBackup: () -> Unit,
    onReplayOnboarding: () -> Unit,
    remoteRuntime: com.openandroidintelligence.mobile.RemoteRuntime? = null,
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

    SettingsPageScaffold(title = stringResource(UiR.string.settings_title_advanced), presentation = presentation, onBack = onBack) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            SectionLabel(stringResource(UiR.string.settings_section_message_processing))
            MessageProcessingSettings(runtime = runtime, remoteRuntime = remoteRuntime)

            SectionLabel(stringResource(UiR.string.settings_section_app_maintenance))
            SettingsSectionCard {
                SettingsNavigationItem(
                    headline = stringResource(UiR.string.settings_update_app),
                    supporting = stringResource(UiR.string.settings_update_app_desc),
                    onClick = onOpenUpdates,
                )
                SettingsNavigationItem(
                    headline = stringResource(UiR.string.settings_backup_restore),
                    supporting = stringResource(UiR.string.settings_backup_restore_desc),
                    onClick = onOpenBackup,
                )
            }

            SectionLabel(stringResource(UiR.string.settings_help_feedback))
            SettingsSectionCard {
                SettingsNavigationItem(
                    headline = stringResource(UiR.string.settings_tutorial),
                    supporting = stringResource(UiR.string.settings_tutorial_desc),
                    onClick = onReplayOnboarding,
                )
                SettingsNavigationItem(headline = stringResource(UiR.string.settings_docs), onClick = { openUrl(DOCUMENTATION_URL) })
                SettingsNavigationItem(headline = stringResource(UiR.string.settings_issues), onClick = { openUrl(ISSUES_URL) })
                if (DOCUMENTATION_URL in failedUrls || ISSUES_URL in failedUrls) ExternalLinkFailure()
            }

            SectionLabel(stringResource(UiR.string.settings_about_oai))
            SettingsSectionCard {
                SettingsListItem(headline = stringResource(UiR.string.settings_version), supporting = installed.versionName.ifBlank { stringResource(UiR.string.settings_unknown) })
                SettingsListItem(
                    headline = stringResource(UiR.string.settings_build_channel),
                    supporting = settingsBuildChannelLabel(installed),
                )
                SettingsListItem(headline = stringResource(UiR.string.settings_commit), supporting = installed.commit.ifBlank { stringResource(UiR.string.settings_unknown) })
                SettingsNavigationItem(headline = stringResource(UiR.string.settings_build_details), onClick = onOpenBuildDetails)
                SettingsNavigationItem(headline = stringResource(UiR.string.settings_licenses), onClick = onOpenLicenses)
                SettingsNavigationItem(headline = stringResource(UiR.string.settings_project_url), supporting = PROJECT_URL, onClick = { openUrl(PROJECT_URL) })
                if (PROJECT_URL in failedUrls) ExternalLinkFailure()
            }
        }
    }
}

@Composable
private fun ExternalLinkFailure() {
    Text(
        stringResource(UiR.string.settings_err_open_link),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
