package com.openandroidintelligence.mobile.settings.capabilities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
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
import com.openandroidintelligence.mobile.plugins.needsFallbackConsent
import com.openandroidintelligence.mobile.plugins.openNotificationAccessSettings
import com.openandroidintelligence.mobile.screen.accessibilityServiceClassName
import com.openandroidintelligence.mobile.screen.accessibilityServiceDisconnected
import com.openandroidintelligence.mobile.screen.accessibilityServiceEnabledInSettings
import com.openandroidintelligence.mobile.screen.accessibilityServiceReady
import com.openandroidintelligence.mobile.settings.SectionLabel
import com.openandroidintelligence.mobile.settings.SettingsPageColumn
import com.openandroidintelligence.mobile.settings.SettingsPageScaffold
import com.openandroidintelligence.notification.control.NotificationControlRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop

private const val SMS_QUERY_ID = "org.openandroidintelligence.sms.query"
private const val NOTIFICATION_QUERY_ID = "org.openandroidintelligence.notifications.query"

/** 短信详情里三项子能力的展示顺序与文案。 */
private val smsSiblingCapabilities = listOf(
    "org.openandroidintelligence.sms.schedule" to "短信定时任务设置",
    "org.openandroidintelligence.sms.job-status" to "短信任务状态查询",
    "org.openandroidintelligence.sms.cancel" to "短信任务取消",
)

/** 该能力声明是否需要一张独立的详情页（短信与通知把推送也收进来，所以标题就是能力名）。 */
@Composable
internal fun CapabilitySettingsScreen(
    pluginId: String,
    capability: String,
    environment: PlatformSettingsEnvironment,
    runtime: GatewayRuntime?,
    presentation: SettingsPresentation,
    onBack: () -> Unit,
    onRequestScreenCaptureAuthorization: () -> Unit,
    onOpenPlugins: () -> Unit = {},
    showAllScreenCapabilities: Boolean = false,
) {
    val capabilityId = capability.substringBefore('@')
    val title = if (showAllScreenCapabilities) "屏幕读取与控制"
    else builtInCapabilities.firstOrNull { it.id == capabilityId }?.title ?: capabilityId
    SettingsPageScaffold(title = title, presentation = presentation, onBack = onBack) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            if (showAllScreenCapabilities) {
                builtInCapabilities.filter { it.group == GROUP_SCREEN }.forEach { screen ->
                    CapabilityDetailContent(screen.pluginId, screen.id, environment, runtime, presentation, onOpenPlugins, onRequestScreenCaptureAuthorization)
                }
                ScreenStopSection(environment, runtime)
            } else {
                CapabilityDetailContent(pluginId, capability, environment, runtime, presentation, onOpenPlugins, onRequestScreenCaptureAuthorization)
                if (builtInCapabilities.any { it.group == GROUP_SCREEN && it.pluginId == pluginId && it.id == capabilityId }) {
                    ScreenStopSection(environment, runtime)
                }
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

/**
 * 一项能力的完整详情：开关与授权模式 → 系统权限 → 运行状态 → Core 同步 → 专属设置。
 *
 * 四条事实各自独立呈现，任何一条缺失都指名缺的是什么；不出现能力 ID、提供者标识或参数摘要。
 */
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
    val capabilityId = capability.substringBefore('@')
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
    val builtIn = builtInCapabilities.firstOrNull { it.pluginId == pluginId && it.id == capabilityId }

    notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    if (host == null) {
        // 本机策略的读取依赖插件宿主本身；这一步与 Core 是否在线无关。
        SettingsSectionCard { Text("插件运行环境未连接，暂时无法读取或修改本机设置。") }
        return
    }
    if (entries.isEmpty()) {
        // 宿主已就绪但没有该能力的已验证声明：内建能力只能是加载失败，第三方插件则是未装/未启用。
        val builtInFailure = observedHost?.builtInFailures?.get(pluginId)
        SettingsSectionCard {
            Text(
                if (builtIn != null || builtInFailure != null) {
                    "这项能力未能加载：${builtInFailureLabel(builtInFailure ?: "BUILT_IN_REGISTRATION_FAILED")}。请重新安装应用；仍失败请反馈这一原因。"
                } else {
                    "看不到这项能力的提供者。请确认对应插件仍然安装并启用。"
                },
                color = MaterialTheme.colorScheme.error,
            )
            if (builtIn == null) TextButton(onClick = onOpenPlugins) { Text("打开插件管理") }
        }
        return
    }

    val snapshot by produceState<com.openandroidintelligence.mobile.plugins.CapabilitySnapshot?>(null, hostRevision, localState?.revision, refreshTick) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { host.capabilitySnapshot() }.getOrNull() }
    }
    val accessibilityScreenshotSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    SectionLabel("能力")
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
                if (localStatus == "PROVIDER_NOT_SELECTED" && policy.enabled) {
                    TextButton(onClick = {
                        saveError = runCatching { host.setCapabilityPolicy(pluginId, entry.key, host.capabilityPolicy(pluginId, entry.key)) }
                            .fold(onSuccess = { null }, onFailure = { "未能选择此提供者，请检查插件状态后重试。" })
                        refresh()
                    }) { Text("使用此插件提供这项能力") }
                }
            }
        }
    }

    // 临时授权随能力走：这里只显示这一项能力真正生效的批准，并可就地撤销。
    //
    // 过滤键必须是授权作用域自身的键（`policyKey`，含身份/Schema/风险/原语摘要），
    // 不能是 `entry.key`（`id@version`）：`approveRequest()` 存的是前者，两者永不相等，
    // 用后者会让真实批准全部被过滤掉、撤销入口随之消失。
    val approvalScopeKeys = remember(pluginId, hostRevision) {
        val owned = entries.map { host.authorizationScope(it, Json.of(emptyMap<String, String>())).capability }
        val siblingScopes = if (capabilityId == SMS_QUERY_ID) {
            // 短信详情统一管理四项能力，它们的批准也必须能在这里看到并撤销。
            smsSiblingCapabilities.mapNotNull { (id, _) ->
                host.entries(pluginId).firstOrNull { it.id == id }
            }.map { host.authorizationScope(it, Json.of(emptyMap<String, String>())).capability }
        } else emptyList()
        (owned + siblingScopes).toSet()
    }
    CapabilityTemporaryApprovals(environment.pairingGrants, approvalScopeKeys)

    // 短信详情：三项子能力各自独立开关，只控制各自的能力，不连带扩大读取范围。
    if (capabilityId == SMS_QUERY_ID) {
        val siblings = smsSiblingCapabilities.mapNotNull { (id, label) -> host.entries(pluginId).firstOrNull { it.id == id }?.let { label to it } }
        if (siblings.isNotEmpty()) {
            SectionLabel("短信任务")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    siblings.forEach { (label, entry) ->
                        val policy = host.capabilityPolicy(pluginId, entry.key)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            Switch(
                                checked = policy.enabled,
                                onCheckedChange = { enabled ->
                                    saveError = runCatching {
                                        host.setCapabilityPolicy(pluginId, entry.key, policy.copy(enabled = enabled))
                                    }.fold(onSuccess = { null }, onFailure = { "本机授权设置未能保存，请检查存储后重试。" })
                                    refreshTick++
                                },
                                modifier = Modifier
                                    .testTag("capability-enabled:$pluginId:${entry.key}")
                                    .semantics { contentDescription = "${label}开关" },
                            )
                        }
                    }
                    Text("三项都只控制对应能力；是否读取短信仍由上方的短信读取开关决定。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    // 后台任务许可必须留在原地。
    //
    // 内建能力不再出现在插件管理里，而那里原本是唯一能修改这个许可的地方；
    // 能力开关虽然也会写 `kernel.background.run`，但 `capabilityPolicy` 明确把它排除在外，
    // 且调度器同时要求全局与身份作用域两项许可。不在这里补上，用户打开「短信定时任务设置」
    // 后每次调度仍会以 BACKGROUND_GRANT_REQUIRED 失败，且无法撤销已有的许可。
    if (host.supportsBackground(pluginId)) {
        SectionLabel("后台任务")
        SettingsSectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("允许后台任务", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = host.isBackgroundEnabled(pluginId),
                        onCheckedChange = { enabled ->
                            saveError = runCatching { host.setBackgroundEnabled(pluginId, enabled) }
                                .fold(onSuccess = { null }, onFailure = { "后台任务许可未能保存，请稍后重试。" })
                            refreshTick++
                        },
                        modifier = Modifier
                            .testTag("capability-background-toggle:$pluginId")
                            .semantics { contentDescription = "允许后台任务开关" },
                    )
                }
                Text(
                    "定时与后台操作需要这项单独许可；普通查询不需要。它与上方能力开关、系统权限分别生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    val requirements = remember(entries, accessibilityScreenshotSupported) {
        entries.flatMap { androidPermissionRequirements(it.primitives, accessibilityScreenshotSupported) }.distinctBy { it.key }
    }
    if (requirements.isNotEmpty()) {
        SectionLabel("Android 系统权限")
        SettingsSectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                val states = requirements.associateWith { inspector.state(it) }
                val fallbackNeeded = needsFallbackConsent(requirements) { states.getValue(it) }
                requirements.forEach { requirement ->
                    val state = states.getValue(requirement)
                    // 首选路径已就绪时不再提示备用方案：无障碍截图可用就不该要求屏幕共享授权。
                    if (requirement.isFallback && !fallbackNeeded) return@forEach
                    GrantRequirementRow(requirement, state, onAction = {
                        when (requirement.grantKind) {
                            DeviceCapabilityGrantKind.RUNTIME_PERMISSION -> {
                                val permission = requirement.manifestPermission
                                if (permission != null && state != DeviceCapabilityGrantState.DENIED) {
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
                    }, context = context)
                }
            }
        }
    }

    entries.forEach { entry ->
        val local = snapshot?.inventory?.firstOrNull {
            it["pluginId"] == pluginId && it["capabilityId"] == entry.id && it["capabilityVersion"] == entry.version
        }
        val localStatus = local?.get("status") as? String
        val localReason = (local?.get("reason") as? String)?.takeIf { it.isNotBlank() }
        val diagnostic = effectiveSync.diagnostic(DeviceCapabilityIdentity(pluginId, entry.id, entry.version))
        SectionLabel("运行状态")
        SettingsSectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                Text(localAvailabilityLabel(localStatus, localReason))
                Text("Core 同步：${capabilityStatusLabel(diagnostic.status, diagnostic.reason)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (runtime == null) {
                    Text("Core 未连接；本机开关与授权照常生效，连接恢复后自动同步。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (entryUsesNotificationPolicy(pluginId, capability)) {
        SectionLabel("通知采集范围")
        SettingsSectionCard {
            NotificationCollectionScreen(
                policy = NotificationControlRegistry.policyPort(),
                binding = NotificationControlRegistry.bindingPort(),
                onBack = {},
                presentation = presentation,
                embedded = true,
            )
        }
    }

    // 主动推送按类别归位：通知部分进「通知读取」，短信部分进「短信」。
    //
    // 保存必须串行且基于权威快照：每次成功保存都把返回值写回本地快照，并以它作为下一次的
    // expectedConfiguration。否则用户在刷新完成前连点两下，第二次会用旧配置覆盖第一次的修改
    // （例如先关掉正文推送、再改推送目标，正文开关会被悄悄恢复成开启）。
    val push = (context.applicationContext as? OpenAndroidIntelligenceApplication)?.devicePush
    val pushRuntime = push?.runtime
    val endpoints = push?.endpoints?.collectAsState()?.value.orEmpty()
    if (pushRuntime != null) {
        val pushScope = rememberCoroutineScope()
        var pushSnapshot by remember(pushRuntime) { mutableStateOf<com.openandroidintelligence.mobile.push.PushSnapshot?>(null) }
        var pushSaving by remember(pushRuntime) { mutableStateOf(false) }
        LaunchedEffect(pushRuntime, refreshTick) {
            val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { pushRuntime.snapshot() }.getOrNull()
            }
            if (loaded != null) pushSnapshot = loaded
        }
        fun savePush(transform: (com.openandroidintelligence.mobile.push.PushConfiguration) -> com.openandroidintelligence.mobile.push.PushConfiguration) {
            val base = pushSnapshot ?: return
            if (pushSaving) return
            pushSaving = true
            pushScope.launch {
                try {
                    val next = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        pushRuntime.configure(transform(base.configuration), expectedConfiguration = base.configuration)
                    }
                    pushSnapshot = next
                    saveError = null
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: IllegalStateException) {
                    saveError = if (failure.message == "PUSH_CONFIGURATION_CHANGED") {
                        pushSnapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { pushRuntime.snapshot() }.getOrNull()
                        }
                        "推送配置已在其他位置修改，请重新检查后再保存。"
                    } else "推送设置未能保存，请稍后重试。"
                } catch (_: IllegalArgumentException) {
                    saveError = "无法保存：范围或容量无效。"
                } catch (_: Exception) {
                    saveError = "推送设置未能保存，请稍后重试。"
                } finally {
                    pushSaving = false
                }
            }
        }
        pushSnapshot?.let { snap ->
            SectionLabel("主动推送")
            when {
                capabilityId == NOTIFICATION_QUERY_ID -> com.openandroidintelligence.mobile.push.NotificationPushSection(
                    config = snap.configuration,
                    endpoints = endpoints,
                    saving = pushSaving,
                    error = saveError,
                    onConfigurationChange = { next -> savePush { next } },
                )
                capabilityId == SMS_QUERY_ID -> com.openandroidintelligence.mobile.push.SmsPushSection(
                    config = snap.configuration,
                    endpoints = endpoints,
                    saving = pushSaving,
                    error = saveError,
                    onConfigurationChange = { next -> savePush { next } },
                    onRefreshEndpoints = { push.refreshEndpoints() },
                )
            }
        }
    }
}

private fun entryUsesNotificationPolicy(pluginId: String, capability: String): Boolean =
    pluginId == "org.openandroidintelligence.notifications" &&
        capability.substringBefore('@') == NOTIFICATION_QUERY_ID

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
    context: Context,
) {
    Text(requirement.label, style = MaterialTheme.typography.titleSmall)
    Text(
        when {
            // 无障碍已开启但服务断连时，说明真实处境，而不是笼统的“需开启”。
            requirement.grantKind == DeviceCapabilityGrantKind.ACCESSIBILITY_SERVICE &&
                state != DeviceCapabilityGrantState.GRANTED && accessibilityServiceDisconnected(context) ->
                "服务已开启但当前未连接，请回到无障碍设置确认服务显示「已连接」"
            state == DeviceCapabilityGrantState.GRANTED -> "系统权限或服务已就绪"
            state == DeviceCapabilityGrantState.NOT_REQUESTED -> "权限尚未授予"
            state == DeviceCapabilityGrantState.DENIED -> "权限被拒绝"
            state == DeviceCapabilityGrantState.REQUIRES_SETTINGS -> "需在系统设置中开启"
            else -> "需要屏幕共享授权"
        },
        color = if (state == DeviceCapabilityGrantState.GRANTED) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(requirement.purpose, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state != DeviceCapabilityGrantState.GRANTED ||
        requirement.grantKind == DeviceCapabilityGrantKind.ACCESSIBILITY_SERVICE ||
        requirement.grantKind == DeviceCapabilityGrantKind.NOTIFICATION_ACCESS) {
        TextButton(onClick = onAction, modifier = Modifier.testTag("grant-action:${requirement.key}")) {
            Text(when (requirement.grantKind) {
                DeviceCapabilityGrantKind.RUNTIME_PERMISSION -> if (state == DeviceCapabilityGrantState.DENIED) "前往系统设置" else "申请系统权限"
                DeviceCapabilityGrantKind.ACCESSIBILITY_SERVICE -> "打开无障碍设置"
                DeviceCapabilityGrantKind.NOTIFICATION_ACCESS -> "打开通知使用权设置"
                DeviceCapabilityGrantKind.MEDIA_PROJECTION -> "发起屏幕共享授权"
            })
        }
    }
    if (requirement.grantKind == DeviceCapabilityGrantKind.RUNTIME_PERMISSION) {
        TextButton(onClick = onOpenAppSettings) { Text("打开应用权限设置") }
    }
}

private fun openSystemSettings(context: Context, intent: Intent): Boolean = runCatching {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)
