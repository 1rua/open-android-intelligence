package com.openandroidintelligence.mobile.settings.capabilities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openandroidintelligence.capability.RemoteScreenCaptureRegistry
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.control.ScreenHostRegistry
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.mobile.GatewayRuntime
import com.openandroidintelligence.mobile.OpenAndroidIntelligenceApplication
import com.openandroidintelligence.mobile.PlatformSettingsEnvironment
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.deviceCapabilityReasonLabel
import com.openandroidintelligence.mobile.notifications.NotificationCollectionScreen
import com.openandroidintelligence.mobile.plugins.AndroidPermissionRequirement
import com.openandroidintelligence.mobile.plugins.CapabilityAuthorizationControls
import com.openandroidintelligence.mobile.plugins.DeviceCapabilityGrantInspector
import com.openandroidintelligence.mobile.plugins.DeviceCapabilityGrantKind
import com.openandroidintelligence.mobile.plugins.DeviceCapabilityGrantState
import com.openandroidintelligence.mobile.plugins.DeviceCapabilityIdentity
import com.openandroidintelligence.mobile.plugins.DeviceCapabilitySyncState
import com.openandroidintelligence.mobile.plugins.androidPermissionRequirements
import com.openandroidintelligence.mobile.plugins.capabilityStatusLabel
import com.openandroidintelligence.mobile.plugins.manualGrantGuidance
import com.openandroidintelligence.mobile.plugins.openNotificationAccessSettings
import com.openandroidintelligence.mobile.screen.accessibilityServiceClassName
import com.openandroidintelligence.mobile.settings.SectionLabel
import com.openandroidintelligence.mobile.settings.SettingsPageColumn
import com.openandroidintelligence.mobile.settings.SettingsPageScaffold
import com.openandroidintelligence.notification.control.NotificationControlRegistry
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay

@Composable
internal fun CapabilitySettingsScreen(
    pluginId: String,
    capability: String,
    environment: PlatformSettingsEnvironment,
    runtime: GatewayRuntime?,
    presentation: SettingsPresentation,
    onBack: () -> Unit,
    onOpenPlugins: () -> Unit,
    onRequestScreenCaptureAuthorization: () -> Unit,
    showAllScreenCapabilities: Boolean = false,
) {
    val title = if (showAllScreenCapabilities) "屏幕读取与控制" else builtInCapabilities.firstOrNull { it.id == capability.substringBefore('@') }?.title
        ?: capability.substringBefore('@')
    SettingsPageScaffold(title = title, presentation = presentation, onBack = onBack) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            if (showAllScreenCapabilities) {
                builtInCapabilities.filter { it.group == "屏幕" }.forEach { screen ->
                    CapabilityDetailContent(screen.pluginId, screen.id, environment, runtime, presentation, onOpenPlugins, onRequestScreenCaptureAuthorization)
                }
            } else CapabilityDetailContent(pluginId, capability, environment, runtime, presentation,
                onOpenPlugins, onRequestScreenCaptureAuthorization)
            if (showAllScreenCapabilities || builtInCapabilities.any { it.group == "屏幕" && it.pluginId == pluginId && it.id == capability.substringBefore('@') }) {
                ScreenStopSection(environment, runtime)
            }
        }
    }
}

@Composable
private fun ScreenStopSection(environment: PlatformSettingsEnvironment, runtime: GatewayRuntime?) {
    var confirmStop by remember { mutableStateOf(false) }
    var stopError by remember { mutableStateOf<String?>(null) }
    SectionLabel("停止屏幕请求")
    SettingsSectionCard {
        Text("停止后将撤销所有临时授权并终止屏幕请求，长期设置保持不变。")
        stopError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { confirmStop = true }, modifier = Modifier.testTag("stop-screen-requests")) {
            Text("立即停止当前屏幕请求")
        }
    }
    if (confirmStop) {
        AlertDialog(onDismissRequest = { confirmStop = false }, title = { Text("停止当前屏幕请求") },
            text = { Text("将撤销所有临时授权并终止当前的屏幕请求，功能设置和系统权限不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    environment.pairingGrants.revokeTemporaryApprovals()
                    stopError = runCatching { ScreenHostRegistry.current?.revoke() }
                        .fold(onSuccess = { null }, onFailure = { "临时授权已撤销，但未能停止屏幕请求，请重试。" })
                    runCatching { runtime?.requestCapabilitySync() }
                    confirmStop = false
                }) { Text("停止并撤销") }
            }, dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("取消") } })
    }
}

@Composable
private fun CapabilityDetailContent(
    pluginId: String,
    capability: String,
    environment: PlatformSettingsEnvironment,
    runtime: GatewayRuntime?,
    presentation: SettingsPresentation,
    onOpenPlugins: () -> Unit,
    onRequestScreenCaptureAuthorization: () -> Unit,
) {
    val context = LocalContext.current
    val host = capabilityHost(context, environment)
    val observedHost = (context.applicationContext as? OpenAndroidIntelligenceApplication)?.pluginHost
    val hostRevision = observedHost?.revision?.collectAsState()?.value
    val localState by environment.pairingGrants.state.collectAsState()
    val syncState = runtime?.deviceCapabilityState?.collectAsState()?.value ?: DeviceCapabilitySyncState()
    val effectiveSync = syncState.forLocalState(localState?.revision ?: -1L, hostRevision ?: -1L)
    val entries = remember(host, hostRevision, pluginId, capability) {
        host?.entries(pluginId).orEmpty().filter {
            if ('@' in capability) it.key == capability else it.id == capability
        }
    }
    var refreshTick by remember { mutableIntStateOf(0) }
    var requestedPermission by remember { mutableStateOf<String?>(null) }
    var deniedPermissions by remember { mutableStateOf(emptySet<String>()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current
    fun refresh() {
        refreshTick++
        runCatching { runtime?.requestCapabilitySync() }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requestedPermission?.let { permission -> deniedPermissions = if (granted) deniedPermissions - permission else deniedPermissions + permission }
        requestedPermission = null
        notice = if (granted) "系统权限已授予；本机能力设置保持原样。" else "系统权限未授予；本机能力设置保持原样，可在应用权限设置中检查。"
        refresh()
    }
    DisposableEffect(lifecycle, runtime) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(pluginId, capability) {
        // System capture may finish after the Activity resumes; reread the real
        // provider while this screen is visible instead of assuming the dialog succeeded.
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(1_000L)
                refreshTick++
            }
        }
    }
    if (entryUsesNotificationPolicy(pluginId, capability)) {
        val notificationPolicy = NotificationControlRegistry.policyPort()
        LaunchedEffect(notificationPolicy, runtime) {
            notificationPolicy.observe().drop(1).collect { refresh() }
        }
    }
    val inspector = remember(context, refreshTick, hostRevision, deniedPermissions) {
        DeviceCapabilityGrantInspector(
            context = context,
            notificationAccessGranted = { runCatching { NotificationControlRegistry.bindingPort().isListenerBound() }.getOrDefault(false) },
            accessibilityEnabled = { accessibilityServiceReady(context) },
            captureAuthorized = { runCatching { RemoteScreenCaptureRegistry.source?.isAvailable == true }.getOrDefault(false) },
            deniedRuntimePermissions = { it in deniedPermissions },
        )
    }
    val builtIn = builtInCapabilities.firstOrNull { it.pluginId == pluginId && it.id == capability.substringBefore('@') }
    SectionLabel(builtIn?.title ?: capability.substringBefore('@'))
    notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (entries.isEmpty()) {
        val installed by produceState<Result<List<com.openandroidintelligence.mobile.plugins.InstalledPluginView>>?>(null, observedHost, hostRevision) {
            value = withContext(Dispatchers.IO) { observedHost?.let { runCatching { it.installStore.listInstalled() } } }
        }
        SettingsSectionCard {
            Text(when {
                installed == null || installed?.isFailure == true || observedHost?.loadFailures?.containsKey("*") == true -> "无法读取插件安装状态。"
                installed?.getOrNull().orEmpty().any { it.pluginId == pluginId || it.directory.name == pluginId } && host == null ->
                    "插件已安装，但插件运行环境未连接。"
                installed?.getOrNull().orEmpty().any { it.pluginId == pluginId || it.directory.name == pluginId } ->
                    "插件已安装，但该能力未能验证或未声明。"
                else -> "尚未安装对应的能力插件。"
            })
            if (host == null) Text("插件运行环境未连接，无法读取本机策略。")
            TextButton(onClick = onOpenPlugins) { Text("打开插件管理") }
        }
    } else if (host != null) {
        val snapshot by produceState<com.openandroidintelligence.mobile.plugins.CapabilitySnapshot?>(null, hostRevision, localState?.revision, refreshTick) {
            value = withContext(Dispatchers.IO) { runCatching { host.capabilitySnapshot() }.getOrNull() }
        }
        entries.forEach { entry ->
            val policy = host.capabilityPolicy(entry.identity.pluginId, entry.key)
            val local = snapshot?.inventory?.firstOrNull {
                it["pluginId"] == pluginId && it["capabilityId"] == entry.id && it["capabilityVersion"] == entry.version
            }
            val localStatus = local?.get("status") as? String
            val localReason = (local?.get("reason") as? String)?.takeIf { it.isNotBlank() }
            val diagnostic = effectiveSync.diagnostic(DeviceCapabilityIdentity(pluginId, entry.id, entry.version))
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    Text("提供者：$pluginId · ${entry.key}", style = MaterialTheme.typography.bodySmall)
                    Text(if (host.isEnabled(pluginId)) "插件已启用" else "插件已安装，但未启用")
                    if (localState?.localError != null) Text("本机授权状态读取失败，当前按关闭处理。", color = MaterialTheme.colorScheme.error)
                    key(entry.identity.pluginId, entry.identity.authorKeyFingerprint, entry.key,
                        Json.sha256(entry.schema), entry.risk, entry.primitives.sorted()) {
                        CapabilityAuthorizationControls(pluginId, entry.key,
                            entry.risk == "read" && "kernel.screen.control" !in entry.primitives, policy,
                            onPolicyChange = { next ->
                                saveError = runCatching { host.setCapabilityPolicy(pluginId, entry.key, next) }
                                    .fold(onSuccess = { null }, onFailure = { "本机授权设置未能保存，请检查存储后重试。" })
                                refreshTick++
                            },
                            draftIdentity = host.authorizationScope(entry, Json.of(emptyMap<String, String>())).capability,
                        )
                    }
                    Text("本机可用性：${localAvailabilityLabel(localStatus, localReason)}")
                    if (localStatus == "PROVIDER_NOT_SELECTED" && policy.enabled) {
                        TextButton(onClick = {
                            saveError = runCatching { host.setCapabilityPolicy(pluginId, entry.key, host.capabilityPolicy(pluginId, entry.key)) }
                                .fold(onSuccess = { null }, onFailure = { "未能选择此提供者，请检查插件状态后重试。" })
                            refresh()
                        }) { Text("使用此插件提供这项能力") }
                    }
                    Text("Core 同步：${capabilityStatusLabel(diagnostic.status, diagnostic.reason)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            val requirements = remember(entry) { androidPermissionRequirements(entry.primitives) }
            if (requirements.isNotEmpty()) {
                SectionLabel("Android 系统许可")
                SettingsSectionCard {
                    requirements.forEach { requirement ->
                        GrantRequirementRow(requirement, inspector.state(requirement), onAction = {
                            when (requirement.grantKind) {
                                DeviceCapabilityGrantKind.RUNTIME_PERMISSION -> {
                                    val permission = requirement.manifestPermission
                                    if (permission != null && inspector.state(requirement) != DeviceCapabilityGrantState.DENIED) {
                                        requestedPermission = permission
                                        permissionLauncher.launch(permission)
                                    } else if (permission != null) {
                                        if (!openSystemSettings(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))) notice = manualGrantGuidance(requirement)
                                    } else notice = manualGrantGuidance(requirement)
                                }
                                DeviceCapabilityGrantKind.ACCESSIBILITY_SERVICE -> if (!openSystemSettings(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) notice = manualGrantGuidance(requirement)
                                DeviceCapabilityGrantKind.NOTIFICATION_ACCESS -> if (!runCatching { openNotificationAccessSettings(context) }.getOrDefault(false)) notice = manualGrantGuidance(requirement)
                                DeviceCapabilityGrantKind.MEDIA_PROJECTION -> onRequestScreenCaptureAuthorization()
                            }
                        }, onOpenAppSettings = {
                            if (!openSystemSettings(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))) {
                                notice = manualGrantGuidance(requirement)
                            }
                        })
                    }
                }
            }
        }
    }
    if (entryUsesNotificationPolicy(pluginId, capability) || entries.any { "kernel.notifications.read" in it.primitives }) {
        SectionLabel("通知采集范围与投递")
        NotificationCollectionScreen(
            policy = NotificationControlRegistry.policyPort(),
            binding = NotificationControlRegistry.bindingPort(),
            onBack = {},
            presentation = presentation,
            embedded = true,
        )
    }
}

private fun entryUsesNotificationPolicy(pluginId: String, capability: String): Boolean =
    pluginId == "org.openandroidintelligence.notifications" &&
        capability.substringBefore('@') == "org.openandroidintelligence.notifications.query"

internal fun localAvailabilityLabel(status: String?, reason: String?): String {
    val label = when (status) {
        "READY" -> "已就绪"
        "PLUGIN_DISABLED" -> "插件未启用"
        "LOCAL_GRANT_REQUIRED" -> "未获手机授权"
        "ANDROID_PERMISSION_REQUIRED" -> "系统权限或服务未就绪"
        "PROVIDER_NOT_SELECTED" -> "尚未选择提供者"
        "PROVIDER_UNAVAILABLE" -> "手机服务未就绪"
        else -> "暂不可用"
    }
    return if (reason.isNullOrBlank()) label else "$label；${deviceCapabilityReasonLabel(reason)}"
}

@Composable
private fun GrantRequirementRow(
    requirement: AndroidPermissionRequirement,
    state: DeviceCapabilityGrantState,
    onAction: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Text(requirement.label, style = MaterialTheme.typography.titleSmall)
    Text(when (state) {
        DeviceCapabilityGrantState.GRANTED -> "系统权限或服务已就绪"
        DeviceCapabilityGrantState.NOT_REQUESTED -> "权限尚未授予"
        DeviceCapabilityGrantState.DENIED -> "权限被拒绝"
        DeviceCapabilityGrantState.REQUIRES_SETTINGS -> "需在系统设置中开启"
        DeviceCapabilityGrantState.ASKED_ON_USE -> "需要屏幕截取授权"
    })
    Text(requirement.purpose, style = MaterialTheme.typography.bodySmall)
    if (state != DeviceCapabilityGrantState.GRANTED ||
        requirement.grantKind == DeviceCapabilityGrantKind.ACCESSIBILITY_SERVICE ||
        requirement.grantKind == DeviceCapabilityGrantKind.NOTIFICATION_ACCESS) {
        TextButton(onClick = onAction) { Text(when (requirement.grantKind) {
            DeviceCapabilityGrantKind.RUNTIME_PERMISSION -> if (state == DeviceCapabilityGrantState.DENIED) "前往系统设置" else "申请系统权限"
            DeviceCapabilityGrantKind.ACCESSIBILITY_SERVICE -> "打开无障碍设置"
            DeviceCapabilityGrantKind.NOTIFICATION_ACCESS -> "打开通知使用权设置"
            DeviceCapabilityGrantKind.MEDIA_PROJECTION -> "请求屏幕捕获系统许可"
        }) }
    }
    if (requirement.grantKind == DeviceCapabilityGrantKind.RUNTIME_PERMISSION) {
        TextButton(onClick = onOpenAppSettings) { Text("打开应用权限设置") }
    }
    if (requirement.grantKind == DeviceCapabilityGrantKind.MEDIA_PROJECTION) {
        Text("也可以使用无障碍截图；截屏授权与功能授权独立。", style = MaterialTheme.typography.bodySmall)
    }
}

private fun openSystemSettings(context: Context, intent: Intent): Boolean = runCatching {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)

private fun accessibilityServiceReady(context: Context): Boolean = runCatching {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    val component = ComponentName(context, accessibilityServiceClassName())
    val enabledInSettings = enabled.split(':').any {
        it.equals(component.flattenToString(), true) || it.equals(component.flattenToShortString(), true)
    }
    val status = ScreenHostRegistry.current?.status()
    enabledInSettings && status != null && status.accessibilityError == null
}.getOrDefault(false)
