package com.openandroidintelligence.mobile.settings.capabilities

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.openandroidintelligence.conversation.components.SettingsNavigationItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.mobile.GatewayRuntime
import com.openandroidintelligence.mobile.OpenAndroidIntelligenceApplication
import com.openandroidintelligence.mobile.PlatformSettingsEnvironment
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.plugins.ProductionPluginHost
import com.openandroidintelligence.mobile.settings.SectionLabel
import com.openandroidintelligence.mobile.settings.SettingsPageColumn
import com.openandroidintelligence.mobile.settings.SettingsPageScaffold

internal fun capabilityHost(context: android.content.Context, environment: PlatformSettingsEnvironment): ProductionPluginHost? =
    (context.applicationContext as? OpenAndroidIntelligenceApplication)?.pluginHost
        ?.takeIf { environment.pluginRuntimesWired }

@Composable
internal fun DeviceCapabilitiesScreen(
    environment: PlatformSettingsEnvironment,
    runtime: GatewayRuntime?,
    presentation: SettingsPresentation,
    onBack: () -> Unit,
    onOpenCapability: (String, String) -> Unit,
    onOpenPush: () -> Unit,
    onOpenPlugins: () -> Unit,
) {
    val context = LocalContext.current
    val host = capabilityHost(context, environment)
    val observedHost = (context.applicationContext as? OpenAndroidIntelligenceApplication)?.pluginHost
    val syncState = runtime?.deviceCapabilityState?.collectAsState()?.value
    val hostRevision = observedHost?.revision?.collectAsState()?.value
    val localState by environment.pairingGrants.state.collectAsState()
    val entries = remember(host, hostRevision) { host?.verifiedCapabilities().orEmpty() }
    val catalog = remember(entries) { capabilityCatalog(entries) }
    // A damaged installation or failed scan must not be presented as an absent plugin.
    val installed by produceState<Result<List<com.openandroidintelligence.mobile.plugins.InstalledPluginView>>?>(null, hostRevision, observedHost) {
        value = withContext(Dispatchers.IO) { observedHost?.let { runCatching { it.installStore.listInstalled() } } }
    }
    val installedIds = installed?.getOrNull()?.map { it.pluginId ?: it.directory.name }?.toSet().orEmpty()

    SettingsPageScaffold(title = "设备能力与授权", presentation = presentation, onBack = onBack) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            catalog.groupBy { it.group }.forEach { (group, items) ->
                SectionLabel(group)
                SettingsSectionCard {
                    items.forEach { item ->
                        val supporting = when {
                            installed == null || installed?.isFailure == true || observedHost?.loadFailures?.containsKey("*") == true -> "无法读取插件安装状态"
                            item.verified == null && item.pluginId in installedIds && host == null -> "插件已安装，运行环境未连接"
                            item.verified == null && item.pluginId in installedIds -> "插件已安装，但此能力未能验证或未声明"
                            item.verified == null -> "未安装对应插件"
                            host == null -> "插件运行环境未连接"
                            !host.isEnabled(item.pluginId) -> "插件未启用 · ${item.pluginId}"
                            localState?.localError != null -> "本机授权状态不可用 · ${item.pluginId}"
                            !host.capabilityPolicy(item.pluginId, item.capability).enabled -> "本机能力已关闭 · ${item.pluginId}"
                            else -> "本机能力已开启 · ${item.pluginId}"
                        }
                        SettingsNavigationItem(
                            headline = item.title,
                            supporting = supporting,
                            onClick = { onOpenCapability(item.pluginId, item.capability) },
                            modifier = Modifier.testTag("capability-entry:${item.pluginId}:${item.capability}"),
                        )
                    }
                }
            }
            Text("新能力默认每次手动；Android 系统权限继续独立检查。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionLabel("临时授权")
            TemporaryApprovalsSection(environment.pairingGrants, host)
            SectionLabel("其他设备能力设置")
            SettingsSectionCard {
                SettingsNavigationItem("短信与通知主动推送", onClick = onOpenPush,
                    supporting = "单独管理短信与通知推送，不改变被动读取范围")
                SettingsNavigationItem("插件管理", onClick = onOpenPlugins,
                    supporting = "安装、启用与管理插件")
            }
            Text(if (syncState == null) "Core 未连接；本机授权仍可单独管理。" else "系统权限、本机授权和 Core 同步状态分别生效。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
