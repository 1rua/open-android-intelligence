package com.openandroidintelligence.mobile.push

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.kernel.SmsScopeMode
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.notifications.InstalledNotificationApp
import com.openandroidintelligence.mobile.notifications.InstalledNotificationAppCache
import com.openandroidintelligence.mobile.notifications.NotificationAppPicker
import com.openandroidintelligence.mobile.notifications.cachedInstalledNotificationApps
import com.openandroidintelligence.mobile.sms.SmsScopeSelector
import com.openandroidintelligence.mobile.sms.parseSmsScopeEntries
import com.openandroidintelligence.mobile.sms.validSmsScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Construct only from authenticated discovery; labels are presentation, never identity. */
data class PushEndpointOption(val endpoint: PushEndpoint, val label: String, val available: Boolean)

/** Production-ready settings entry. Runtime writes run on IO; failed writes are never shown as saved. */
@Composable
fun PushSettingsScreen(
    runtime: DevicePushRuntime,
    endpoints: List<PushEndpointOption>,
    onRefreshEndpoints: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    presentation: SettingsPresentation = SettingsPresentation.SCREEN,
) {
    var snapshot by remember(runtime) { mutableStateOf<PushSnapshot?>(null) }
    var error by remember(runtime) { mutableStateOf<String?>(null) }
    var saving by remember(runtime) { mutableStateOf(false) }
    var saveCompletion by remember(runtime) { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(runtime) {
        try { snapshot = withContext(Dispatchers.IO) { runtime.maintain() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "无法读取本机推送配置，请检查存储状态。" }
    }
    val state = snapshot
    if (state == null) {
        Column(modifier.padding(16.dp)) {
            onBack?.let { TextButton(onClick = it) { Text("返回") } }
            Text(error ?: "正在读取推送设置")
        }
        return
    }
    PushSettingsContent(
        snapshot = state,
        endpoints = endpoints,
        saving = saving,
        error = error,
        saveCompletion = saveCompletion,
        onRefreshEndpoints = onRefreshEndpoints,
        onConfigurationChange = { configuration ->
            if (!saving) scope.launch {
                saving = true
                error = null
                try {
                    snapshot = withContext(Dispatchers.IO) {
                        runtime.configure(configuration, expectedConfiguration = state.configuration)
                    }
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: IllegalStateException) {
                    error = if (failure.message == "PUSH_CONFIGURATION_CHANGED") {
                        snapshot = withContext(Dispatchers.IO) { runtime.snapshot() }
                        "推送配置已在其他位置修改，请重新载入已保存选择后再保存。"
                    } else "推送配置保存失败，尚未应用本次修改。"
                }
                catch (_: IllegalArgumentException) { error = "无法保存：范围或容量无效，容量不能小于当前队列用量。" }
                catch (_: Exception) { error = "推送配置保存失败，尚未应用本次修改。" }
                finally { saving = false; saveCompletion += 1 }
            }
        },
        onRefreshState = { scope.launch {
            try { snapshot = withContext(Dispatchers.IO) { runtime.maintain() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "无法读取当前队列状态。" }
        } },
        modifier = modifier,
        onBack = onBack,
        presentation = presentation,
    )
}

/** 恢复时配置已变化则丢弃旧草稿；保存状态中只保留基线摘要。 */
@Composable
private fun rememberPushTextDraft(baseline: List<String>, initialValue: String): MutableState<String> {
    val fingerprint = remember(baseline) {
        val digest = MessageDigest.getInstance("SHA-256")
        baseline.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
    val saver = remember(fingerprint, initialValue) {
        listSaver<String, String>(
            save = { draft -> if (draft == initialValue) listOf(fingerprint) else listOf(fingerprint, draft) },
            restore = { saved ->
                if (saved.firstOrNull() == fingerprint) saved.getOrNull(1) ?: initialValue else initialValue
            },
        )
    }
    return rememberSaveable(baseline, stateSaver = saver) { mutableStateOf(initialValue) }
}

/** Stateless interface also usable by a settings ViewModel that observes external runtime changes. */
@Composable
fun PushSettingsContent(
    snapshot: PushSnapshot,
    endpoints: List<PushEndpointOption>,
    saving: Boolean,
    error: String?,
    onConfigurationChange: (PushConfiguration) -> Unit,
    onRefreshEndpoints: () -> Unit,
    onRefreshState: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    saveCompletion: Long? = null,
    presentation: SettingsPresentation = SettingsPresentation.SCREEN,
) {
    Column(
        modifier.fillMaxWidth().widthIn(max = Dimensions.ReadingWidth)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimensions.ScreenHorizontal, vertical = Dimensions.SpaceSmall),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        onBack?.let { TextButton(onClick = it) { Text("返回") } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("push-error")) }
        NotificationPushSection(
            config = snapshot.configuration,
            endpoints = endpoints,
            saving = saving,
            saveCompletion = saveCompletion,
            presentation = presentation,
            error = error,
            onConfigurationChange = onConfigurationChange,
        )
        SmsPushSection(
            config = snapshot.configuration,
            endpoints = endpoints,
            saving = saving,
            saveCompletion = saveCompletion,
            error = error,
            presentation = presentation,
            onConfigurationChange = onConfigurationChange,
            onRefreshEndpoints = onRefreshEndpoints,
        )
    }
}

/**
 * 通知主动推送：只负责通知这一条链路。
 *
 * 挂在「通知读取」详情里，因此不再重复“主动推送”这类页面标题，也不引用被动采集策略。
 */
@Composable
internal fun NotificationPushSection(
    config: PushConfiguration,
    endpoints: List<PushEndpointOption>,
    saving: Boolean,
    error: String?,
    onConfigurationChange: (PushConfiguration) -> Unit,
    saveCompletion: Long? = null,
    presentation: SettingsPresentation = SettingsPresentation.SCREEN,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
        Text("主动推送", style = MaterialTheme.typography.titleMedium)
        Text("推送默认关闭。开启后只推送你选中的应用，并只投递到你选定的接收设备。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsSectionCard {
            ToggleRow("通知主动推送", config.notifications.enabled, !saving, "push-notification-enabled") {
                onConfigurationChange(config.copy(notifications = config.notifications.copy(enabled = it)))
            }
            NotificationPushScopeSettings(
                packageIds = config.notifications.scope.packageIds,
                saving = saving,
                error = error,
                saveCompletion = saveCompletion,
                presentation = presentation,
                onPackagesChange = {
                    onConfigurationChange(config.copy(notifications = config.notifications.copy(
                        scope = config.notifications.scope.copy(packageIds = it),
                    )))
                },
            )
            ToggleRow("推送通知正文", config.notifications.scope.includeContent, !saving, "push-notification-content") {
                onConfigurationChange(config.copy(notifications = config.notifications.copy(scope = config.notifications.scope.copy(includeContent = it))))
            }
            EndpointChoices("notification", config.notifications.endpoints, endpoints, !saving) {
                onConfigurationChange(config.copy(notifications = config.notifications.copy(endpoints = it)))
            }
        }
    }
}

/**
 * 短信主动推送：范围、正文与接收设备。
 *
 * 加密离线队列的容量与保留时长不再作为设置项展示；底层队列按既有默认值与已保存值继续运行。
 */
@Composable
internal fun SmsPushSection(
    config: PushConfiguration,
    endpoints: List<PushEndpointOption>,
    saving: Boolean,
    error: String?,
    onConfigurationChange: (PushConfiguration) -> Unit,
    onRefreshEndpoints: () -> Unit,
    saveCompletion: Long? = null,
    presentation: SettingsPresentation = SettingsPresentation.SCREEN,
    modifier: Modifier = Modifier,
) {
    val savedMode = config.sms.scope.mode
    val modeSaver = remember(savedMode) {
        listSaver<SmsScopeMode, String>(
            save = { draft -> listOf(savedMode.name, draft.name) },
            restore = { saved ->
                if (saved.size == 2 && saved[0] == savedMode.name) {
                    SmsScopeMode.entries.firstOrNull { it.name == saved[1] } ?: savedMode
                } else savedMode
            },
        )
    }
    var smsMode by rememberSaveable(savedMode, stateSaver = modeSaver) { mutableStateOf(savedMode) }
    var keywords by rememberPushTextDraft(config.sms.scope.keywords.sorted(), config.sms.scope.keywords.joinToString("\n"))
    var senders by rememberPushTextDraft(config.sms.scope.senderAddresses.sorted(), config.sms.scope.senderAddresses.joinToString("\n"))
    val keywordSet = parseSmsScopeEntries(keywords)
    val senderSet = parseSmsScopeEntries(senders)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
        Text("主动推送", style = MaterialTheme.typography.titleMedium)
        Text("推送默认关闭，与被动读取范围互不影响。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsSectionCard {
            ToggleRow("短信主动推送", config.sms.enabled, !saving, "push-sms-enabled") {
                onConfigurationChange(config.copy(sms = config.sms.copy(enabled = it)))
            }
            SmsScopeSelector(
                mode = smsMode,
                keywords = keywords,
                phoneNumbers = senders,
                onModeChange = { smsMode = it },
                onKeywordsChange = { keywords = it },
                onPhoneNumbersChange = { senders = it },
                tagPrefix = "push-sms",
                enabled = !saving,
            )
            Button(
                onClick = {
                    onConfigurationChange(config.copy(sms = config.sms.copy(scope = config.sms.scope.copy(
                        mode = smsMode,
                        allSenders = smsMode == SmsScopeMode.ALL,
                        keywords = keywordSet,
                        senderAddresses = senderSet,
                    ))))
                },
                enabled = !saving && validSmsScope(smsMode, keywordSet, senderSet),
                modifier = Modifier.testTag("push-save-senders"),
            ) { Text("保存短信范围") }
            ToggleRow("推送短信正文", config.sms.scope.includeContent, !saving, "push-sms-content") {
                onConfigurationChange(config.copy(sms = config.sms.copy(scope = config.sms.scope.copy(includeContent = it))))
            }
            EndpointChoices("sms", config.sms.endpoints, endpoints, !saving) {
                onConfigurationChange(config.copy(sms = config.sms.copy(endpoints = it)))
            }
            Text("正文默认包含；关闭正文开关后仅推送元数据。", style = MaterialTheme.typography.bodySmall)
            Text("关闭推送、缩小范围或移除接收设备，会立即清除不再允许的待发内容。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRefreshEndpoints, enabled = !saving, modifier = Modifier.testTag("push-refresh-endpoints")) { Text("刷新推送目标") }
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag(tag)
            .toggleable(value = value, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = Dimensions.SpaceSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(value, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun EndpointChoices(kind: String, selected: Set<PushEndpoint>, options: List<PushEndpointOption>, enabled: Boolean, onChange: (Set<PushEndpoint>) -> Unit) {
    Text("推送目标（可多选）", style = MaterialTheme.typography.titleSmall)
    val byKey = options.associateBy { it.endpoint }
    val keys = (options.map { it.endpoint } + selected).distinct()
    if (keys.isEmpty()) Text("尚无已验证的接收设备。连接 Core 并刷新后选择。", modifier = Modifier.testTag("push-$kind-no-endpoints"))
    keys.forEach { key ->
        val option = byKey[key]
        val checked = key in selected
        Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled && (checked || (option?.available == true && selected.size < MAX_PUSH_ENDPOINTS)), role = Role.Checkbox,
            onValueChange = { onChange(if (it) selected + key else selected - key) })
            .testTag("push-$kind-endpoint:${key.coreInstanceId}:${key.accountScope}:${key.endpointId}:${key.generation}")
            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, onCheckedChange = null)
            Column(Modifier.weight(1f)) {
                Text(option?.label?.takeIf { it.isNotBlank() } ?: key.endpointId)
                Text("${key.coreInstanceId} / ${key.accountScope}\n${key.endpointId} · 第 ${key.generation} 代", style = MaterialTheme.typography.bodySmall)
                if (option?.available != true) {
                    val replaced = options.any { it.available && it.endpoint.replaces(key) }
                    Text(
                        if (replaced) "接收端身份已更新，原选择和待发记录已保留。请手动重选；旧记录不会转给新接收端。"
                        else "当前接收目标不可用，原选择和待发记录已保留，恢复后重试；可手动取消选择。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** 只保存主动通知的应用范围，不读取或写入被动通知策略端口。 */
@Composable
internal fun NotificationPushScopeSettings(
    packageIds: Set<String>,
    saving: Boolean,
    error: String?,
    onPackagesChange: (Set<String>) -> Unit,
    loadInstalledApps: (() -> List<InstalledNotificationApp>)? = null,
    saveCompletion: Long? = null,
    presentation: SettingsPresentation = SettingsPresentation.SCREEN,
) {
    val packageManager = LocalContext.current.applicationContext.packageManager
    val appLoader = remember(packageManager, loadInstalledApps) {
        loadInstalledApps ?: { cachedInstalledNotificationApps(packageManager) }
    }
    var installedApps by remember(appLoader) { mutableStateOf(InstalledNotificationAppCache.current()) }
    var loadFailed by remember(appLoader) { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var selectedPackages by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var openingPackages by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var rejection by rememberSaveable { mutableStateOf<String?>(null) }
    var submittedPackages by remember { mutableStateOf<List<String>?>(null) }
    var submittedAtCompletion by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(appLoader, refresh) {
        loadFailed = false
        try {
            installedApps = withContext(Dispatchers.IO) { appLoader() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 刷新失败时保留上次已知的应用目录：名称比“读不到”更接近事实。
            if (installedApps == null) loadFailed = true
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 只有权威快照确认保存才退出；失败时保留草稿，允许重新载入。
    LaunchedEffect(packageIds, submittedPackages, saving, error, saveCompletion) {
        val submitted = submittedPackages ?: return@LaunchedEffect
        // 生产入口显式报告一次写入结束，不能因保存前后选择相同就假定成功。
        if (!saving && (saveCompletion == null || saveCompletion != submittedAtCompletion)) {
            if (error != null) {
                rejection = error
                submittedPackages = null
            } else if (packageIds == submitted.toSet()) {
                selectedPackages = null
                submittedPackages = null
            }
        }
    }

    Text("已选择 ${packageIds.size} 个应用", modifier = Modifier.testTag("push-notification-packages"))
    Text("仅收集以上应用；留空时不采集。此范围不修改被动读取设置。", style = MaterialTheme.typography.bodySmall)
    Button(
        onClick = {
            openingPackages = packageIds.toList()
            selectedPackages = openingPackages
            rejection = null
        },
        enabled = !saving,
        modifier = Modifier.testTag("push-save-packages"),
    ) { Text("选择应用") }

    selectedPackages?.let { draft ->
        NotificationAppPicker(
            apps = installedApps,
            loadFailed = loadFailed,
            savedPackages = packageIds.toList(),
            selectedPackages = draft,
            rejection = rejection,
            onSelectionChanged = { selectedPackages = it },
            onRetry = { refresh += 1 },
            onReloadSelection = {
                openingPackages = packageIds.toList()
                selectedPackages = openingPackages
                rejection = null
            },
            onCancel = { selectedPackages = null },
            onSave = {
                when {
                    packageIds != openingPackages.toSet() -> {
                        rejection = "应用范围已在其他位置修改，请重新载入已保存选择后再保存。"
                    }
                    else -> {
                        submittedPackages = draft
                        submittedAtCompletion = saveCompletion
                        onPackagesChange(draft.toSet())
                    }
                }
            },
            saving = saving,
            presentation = presentation,
        )
    }
}
