package com.openandroidintelligence.mobile.settings.capabilities

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openandroidintelligence.conversation.components.SettingsNavigationItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.SettingsTone
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.mobile.GatewayRuntime
import com.openandroidintelligence.mobile.OpenAndroidIntelligenceApplication
import com.openandroidintelligence.mobile.PlatformSettingsEnvironment
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.deviceCapabilityReasonLabel
import com.openandroidintelligence.mobile.plugins.AndroidPermissionRequirement
import com.openandroidintelligence.mobile.plugins.DeviceCapabilityGrantInspector
import com.openandroidintelligence.mobile.plugins.DeviceCapabilityGrantState
import com.openandroidintelligence.mobile.plugins.ProductionPluginHost
import com.openandroidintelligence.mobile.plugins.androidPermissionRequirements
import com.openandroidintelligence.mobile.screen.accessibilityServiceReady
import com.openandroidintelligence.mobile.settings.SectionLabel
import com.openandroidintelligence.mobile.settings.SettingsPageColumn
import com.openandroidintelligence.mobile.settings.SettingsPageScaffold
import com.openandroidintelligence.notification.control.NotificationControlRegistry

internal fun capabilityHost(context: android.content.Context, environment: PlatformSettingsEnvironment): ProductionPluginHost? =
    (context.applicationContext as? OpenAndroidIntelligenceApplication)?.pluginHost
        ?.takeIf { environment.pluginRuntimesWired }

/**
 * 「设备能力与授权」主页面。
 *
 * 结构由产品需求固定为三组六项加一个插件入口。这里只呈现名称、一个真实状态词和进入详情的箭头：
 * 插件 ID、提供者标识、技术说明都不出现，因为用户在这一层只需要判断“能不能用”。
 */
@Composable
internal fun DeviceCapabilitiesScreen(
    environment: PlatformSettingsEnvironment,
    runtime: GatewayRuntime?,
    presentation: SettingsPresentation,
    onBack: () -> Unit,
    onOpenCapability: (String, String) -> Unit,
    onOpenPlugins: () -> Unit,
) {
    val context = LocalContext.current
    val observedHost = (context.applicationContext as? OpenAndroidIntelligenceApplication)?.pluginHost
    val hostRevision = observedHost?.revision?.collectAsState()?.value
    val localState by environment.pairingGrants.state.collectAsState()
    var refreshTick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    // 从系统权限设置返回后必须重新读真实状态，不能沿用离开前的缓存。
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshTick++ }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val entries = remember(observedHost, hostRevision, refreshTick) { observedHost?.verifiedCapabilities().orEmpty() }
    val catalog = remember(entries) { capabilityCatalog(entries) }
    val appContext = context.applicationContext
    val inspector = remember(appContext, observedHost, hostRevision, refreshTick) {
        DeviceCapabilityGrantInspector(
            context = appContext,
            notificationAccessGranted = { runCatching { NotificationControlRegistry.bindingPort().isListenerBound() }.getOrDefault(false) },
            accessibilityEnabled = { accessibilityServiceReady(appContext) },
            captureAuthorized = { runCatching { com.openandroidintelligence.capability.RemoteScreenCaptureRegistry.source?.isAvailable == true }.getOrDefault(false) },
        )
    }
    // 运行状态来自宿主的内存快照：同步读取，避免首帧先显示「需授权」再跳成「已开启」的闪烁。
    val providerReady = remember(observedHost, hostRevision, refreshTick) {
        runCatching {
            observedHost?.capabilitySnapshot()?.inventory.orEmpty()
                .mapNotNull { entry ->
                    val pluginId = entry["pluginId"] as? String ?: return@mapNotNull null
                    val capabilityId = entry["capabilityId"] as? String ?: return@mapNotNull null
                    "$pluginId:$capabilityId" to (entry["status"] == "READY")
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }
    val loadFailures = observedHost?.builtInFailures.orEmpty()

    SettingsPageScaffold(title = "设备能力与授权", presentation = presentation, onBack = onBack) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            catalog.groupBy { it.group }.forEach { (group, items) ->
                SectionLabel(group)
                SettingsSectionCard {
                    items.forEachIndexed { index, item ->
                        if (index > 0) HorizontalRule()
                        val state = capabilityState(
                            CapabilityFacts(
                                loadFailed = item.pluginId in loadFailures,
                                declared = item.verified != null,
                                policyEnabled = observedHost != null && item.verified != null &&
                                    runCatching { observedHost.capabilityPolicy(item.pluginId, item.capability).enabled }.getOrDefault(false),
                                policyReadable = localState?.localError == null,
                                androidGranted = systemGranted(inspector, item),
                                providerReady = item.verified == null ||
                                    providerReady[item.pluginId + ":" + item.capability.substringBefore('@')] == true,
                            ),
                        )
                        SettingsNavigationItem(
                            headline = item.title,
                            icon = capabilityIcon(item.title),
                            supporting = state.label,
                            tone = state.tone(),
                            onClick = { onOpenCapability(item.pluginId, item.capability) },
                            modifier = Modifier.testTag("capability-entry:${item.pluginId}:${item.capability}"),
                        )
                    }
                }
            }
            SectionLabel("更多")
            SettingsSectionCard {
                SettingsNavigationItem(
                    headline = "插件管理",
                    supporting = "管理插件",
                    icon = Icons.Default.Extension,
                    onClick = onOpenPlugins,
                    modifier = Modifier.testTag("capability-entry:plugins"),
                )
            }
            // 失败原因只在这里说一次，且是可执行的说明；正常时不显示任何技术备注。
            loadFailures.takeIf { it.isNotEmpty() }?.let {
                Text(
                    text = "部分内建能力未能加载：" + it.entries.joinToString("；") { (pluginId, code) ->
                        "${builtInDisplayName(pluginId)}（${builtInFailureLabel(code)}）"
                    } + "。请重新安装应用，仍失败请反馈这两项信息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("capabilities-load-failure"),
                )
            }
            localState?.localError?.let {
                Text("无法读取本机授权设置，当前按关闭处理。${deviceCapabilityReasonLabel(it)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * 系统权限是否满足。
 *
 * 屏幕截图按真实可用路径判断：无障碍截图已就绪时，屏幕共享（备用方案）未授权不算阻塞，
 * 否则用户会被主页面推去做一次根本不需要的授权。其余能力逐条检查各自的首选入口。
 */
private fun systemGranted(inspector: DeviceCapabilityGrantInspector, item: CapabilityCatalogItem): Boolean {
    val primitives = item.verified?.primitives ?: return false
    val requirements = androidPermissionRequirements(primitives, accessibilityScreenshotSupported = accessibilityScreenshotSupported())
    if (requirements.isEmpty()) return true
    fun stateOf(requirement: AndroidPermissionRequirement) = runCatching { inspector.state(requirement) }
        .getOrDefault(DeviceCapabilityGrantState.REQUIRES_SETTINGS)
    val primary = requirements.filterNot { it.isFallback }
    if (primary.all { stateOf(it) == DeviceCapabilityGrantState.GRANTED }) return true
    // 首选路径未就绪时，已获系统授权的备用方案同样构成可用。
    return requirements.any { it.isFallback && stateOf(it) == DeviceCapabilityGrantState.GRANTED }
}

private fun accessibilityScreenshotSupported(): Boolean =
    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R

internal fun builtInDisplayName(pluginId: String): String =
    builtInCapabilities.firstOrNull { it.pluginId == pluginId }?.title ?: pluginId

/** 加载失败原因码 → 用户能懂的说明。内部码只出现在日志里。 */
internal fun builtInFailureLabel(code: String): String = when (code) {
    "BUILT_IN_PACKAGE_MISSING", "BUILT_IN_PACKAGE_UNREADABLE" -> "应用自带的能力文件缺失"
    "BUILT_IN_PACKAGE_INVALID" -> "能力文件校验未通过"
    "BUILT_IN_PLUGIN_ID_MISMATCH" -> "能力文件身份不匹配"
    "BUILT_IN_RUNTIME_UNSUPPORTED", "BUILT_IN_ABI_UNSUPPORTED" -> "当前版本不支持该能力的运行环境"
    "BUILT_IN_REGISTRATION_FAILED" -> "能力未能注册"
    else -> "加载失败"
}

private fun capabilityIcon(title: String): ImageVector = when (title) {
    "屏幕内容读取" -> Icons.Default.Visibility
    "屏幕截图" -> Icons.Default.PhotoCamera
    "屏幕控制" -> Icons.Default.TouchApp
    "短信" -> Icons.Default.Sms
    "通知读取" -> Icons.Default.Assignment
    "通话记录" -> Icons.Default.Call
    else -> Icons.Default.Extension
}

/** 状态词的颜色只是辅助；文字本身已经说明了状态。 */
@Composable
private fun CapabilityState.tone(): SettingsTone = when (this) {
    CapabilityState.ON -> SettingsTone.PRIMARY
    CapabilityState.NEEDS_AUTH -> SettingsTone.NEUTRAL
    CapabilityState.UNAVAILABLE -> SettingsTone.DANGER
    CapabilityState.OFF -> SettingsTone.NEUTRAL
}

@Composable
private fun HorizontalRule() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = Dimensions.SpaceSmall),
    )
}
