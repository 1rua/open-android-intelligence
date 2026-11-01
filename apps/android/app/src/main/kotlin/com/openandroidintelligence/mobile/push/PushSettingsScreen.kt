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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
) {
    var snapshot by remember(runtime) { mutableStateOf<PushSnapshot?>(null) }
    var error by remember(runtime) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
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
        onRefreshEndpoints = onRefreshEndpoints,
        onConfigurationChange = { configuration ->
            if (!saving) scope.launch {
                saving = true
                error = null
                try { snapshot = withContext(Dispatchers.IO) { runtime.configure(configuration) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IllegalArgumentException) { error = "无法保存：范围或容量无效，容量不能小于当前队列用量。" }
                catch (_: Exception) { error = "推送配置保存失败，尚未应用本次修改。" }
                finally { saving = false }
            }
        },
        onRefreshState = { scope.launch {
            try { snapshot = withContext(Dispatchers.IO) { runtime.maintain() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "无法读取当前队列状态。" }
        } },
        modifier = modifier,
        onBack = onBack,
    )
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
) {
    val config = snapshot.configuration
    var packages by remember(config.notifications.scope.packageIds) { mutableStateOf(config.notifications.scope.packageIds.joinToString("\n")) }
    var senders by remember(config.sms.scope.senderAddresses) { mutableStateOf(config.sms.scope.senderAddresses.joinToString("\n")) }
    var retention by remember(config.retentionHours) { mutableStateOf(config.retentionHours.toString()) }
    var maxEvents by remember(config.maxEvents) { mutableStateOf(config.maxEvents.toString()) }
    var maxMiB by remember(config.maxQueueBytes) { mutableStateOf((config.maxQueueBytes / 1_048_576.0).toString()) }
    val packageSet = splitEntries(packages)
    val senderSet = splitEntries(senders)
    val hours = retention.toIntOrNull()
    val capacity = maxEvents.toIntOrNull()
    val bytes = maxMiB.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 8 }?.let { (it * 1_048_576).toInt() }
    Column(
        modifier.widthIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        onBack?.let { TextButton(onClick = it) { Text("返回") } }
        Text("主动推送", style = MaterialTheme.typography.headlineSmall)
        Text("设置保存在本机。短信和通知分别授权，不依赖被动查询的手动批准。", style = MaterialTheme.typography.bodyMedium)
        Text(if (snapshot.hasLoggedInAccount) "采集和投递仍须具备系统权限及有效接收端点。" else "当前已退出全部账号：暂停采集和推送，保留配置与未过期队列。")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("push-error")) }

        Text("通知", style = MaterialTheme.typography.titleMedium)
        ToggleRow("通知主动推送", config.notifications.enabled, !saving, "push-notification-enabled") {
            onConfigurationChange(config.copy(notifications = config.notifications.copy(enabled = it)))
        }
        OutlinedTextField(packages, { packages = it }, label = { Text("允许推送的应用包名，每行一项") },
            modifier = Modifier.fillMaxWidth().testTag("push-notification-packages"), enabled = !saving)
        Text("仅收集以上应用；留空时不采集。此范围不修改被动读取设置。", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { onConfigurationChange(config.copy(notifications = config.notifications.copy(scope = config.notifications.scope.copy(packageIds = packageSet)))) },
            enabled = !saving && validEntries(packageSet), modifier = Modifier.testTag("push-save-packages")) { Text("保存应用范围") }
        ToggleRow("推送通知正文", config.notifications.scope.includeContent, !saving, "push-notification-content") {
            onConfigurationChange(config.copy(notifications = config.notifications.copy(scope = config.notifications.scope.copy(includeContent = it))))
        }
        EndpointChoices("notification", config.notifications.endpoints, endpoints, !saving) {
            onConfigurationChange(config.copy(notifications = config.notifications.copy(endpoints = it)))
        }

        HorizontalDivider()
        Text("短信", style = MaterialTheme.typography.titleMedium)
        ToggleRow("短信主动推送", config.sms.enabled, !saving, "push-sms-enabled") {
            onConfigurationChange(config.copy(sms = config.sms.copy(enabled = it)))
        }
        ToggleRow("允许所有短信发件人", config.sms.scope.allSenders, !saving, "push-sms-all-senders") {
            onConfigurationChange(config.copy(sms = config.sms.copy(scope = config.sms.scope.copy(allSenders = it))))
        }
        OutlinedTextField(senders, { senders = it }, label = { Text("允许的发件人地址，每行一项") },
            modifier = Modifier.fillMaxWidth().testTag("push-sms-senders"), enabled = !saving && !config.sms.scope.allSenders)
        Button(onClick = { onConfigurationChange(config.copy(sms = config.sms.copy(scope = config.sms.scope.copy(senderAddresses = senderSet)))) },
            enabled = !saving && !config.sms.scope.allSenders && validEntries(senderSet), modifier = Modifier.testTag("push-save-senders")) { Text("保存短信范围") }
        ToggleRow("推送短信正文", config.sms.scope.includeContent, !saving, "push-sms-content") {
            onConfigurationChange(config.copy(sms = config.sms.copy(scope = config.sms.scope.copy(includeContent = it))))
        }
        EndpointChoices("sms", config.sms.endpoints, endpoints, !saving) {
            onConfigurationChange(config.copy(sms = config.sms.copy(endpoints = it)))
        }
        Text("正文默认包含；关闭正文开关后仅推送元数据。", style = MaterialTheme.typography.bodySmall)
        Text("关闭推送、缩小范围或移除端点，会立即清除不再允许的待发内容。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onRefreshEndpoints, enabled = !saving, modifier = Modifier.testTag("push-refresh-endpoints")) { Text("刷新接收端点") }

        HorizontalDivider()
        Text("加密离线队列", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(retention, { retention = it }, label = { Text("保留小时数：1–168，默认 72") },
            modifier = Modifier.fillMaxWidth().testTag("push-retention"), enabled = !saving, singleLine = true,
            isError = hours == null || hours !in 1..168)
        OutlinedTextField(maxEvents, { maxEvents = it }, label = { Text("事件上限：1–4096") }, modifier = Modifier.fillMaxWidth().testTag("push-max-events"), enabled = !saving, singleLine = true)
        OutlinedTextField(maxMiB, { maxMiB = it }, label = { Text("容量上限 MiB：最多 8") }, modifier = Modifier.fillMaxWidth().testTag("push-max-mib"), enabled = !saving, singleLine = true)
        Button(onClick = { if (hours != null && capacity != null && bytes != null) onConfigurationChange(config.copy(retentionHours = hours, maxEvents = capacity, maxQueueBytes = bytes)) },
            enabled = !saving && hours != null && hours in 1..168 && capacity != null && capacity in 1..4096 && bytes != null && bytes >= 4096,
            modifier = Modifier.testTag("push-save-retention")) { Text("保存队列设置") }
        Text("待发 ${snapshot.queuedEvents} 条，共 ${snapshot.pendingDeliveries} 次接收端投递。已过期 ${snapshot.expiredEvents} 条，容量拒绝 ${snapshot.capacityRejections} 次。", modifier = Modifier.testTag("push-queue-state"))
        Text("队列满时拒绝新事件并保留已有未确认事件；接收端消费确认后清除正文。", style = MaterialTheme.typography.bodySmall)
        Text("待远端清理 ${snapshot.pendingRevocations} 次；离线时保留清理记录，恢复连接后重试。", modifier = Modifier.testTag("push-revocation-state"))
        TextButton(onClick = onRefreshState, enabled = !saving) { Text("刷新队列状态") }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, onChange, enabled = enabled, modifier = Modifier.testTag(tag).semantics { contentDescription = label })
    }
}

@Composable
private fun EndpointChoices(kind: String, selected: Set<PushEndpoint>, options: List<PushEndpointOption>, enabled: Boolean, onChange: (Set<PushEndpoint>) -> Unit) {
    Text("接收端点，可多选", style = MaterialTheme.typography.titleSmall)
    val byKey = options.associateBy { it.endpoint }
    val keys = (options.map { it.endpoint } + selected).distinct()
    if (keys.isEmpty()) Text("尚无已验证接收端点。连接 Core 并刷新后选择。", modifier = Modifier.testTag("push-$kind-no-endpoints"))
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
                        else "当前端点不可用，原选择和待发记录已保留，恢复后重试；可手动取消选择。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun splitEntries(value: String) = value.split('\n', ',', '，').map(String::trim).filter(String::isNotEmpty).toSet()
private fun validEntries(value: Set<String>) = value.size <= 256 && value.all { it.length <= 256 }
