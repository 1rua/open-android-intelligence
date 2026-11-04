package com.openandroidintelligence.mobile.settings.advanced

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.openandroidintelligence.conversation.batch.DebounceMode
import com.openandroidintelligence.conversation.batch.DebouncePolicy
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.mobile.GatewayRuntime
import com.openandroidintelligence.mobile.conversations.DebouncePreferences
import kotlinx.coroutines.CancellationException

/** 使用原有全局与 Core 级偏好；本界面不引入第二套持久化或独立启用键。 */
@Composable
internal fun MessageProcessingSettings(gatewayUrl: String?, runtime: GatewayRuntime?) {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { DebouncePreferences(context) }
    MessageProcessingEditor(
        preferences = preferences,
        gatewayUrl = gatewayUrl,
        onUpdateSettings = { gateway, delay, extend ->
            if (runtime != null) runtime.updateDebounceSettings(gateway, delay, extend)
            else preferences.write(gateway, delay, extend)
        },
        onUseGlobalSettings = { gateway ->
            if (runtime != null) runtime.useGlobalDebounceSettings(gateway) else preferences.useGlobal(gateway)
        },
    )
}

@Composable
internal fun MessageProcessingEditor(
    preferences: DebouncePreferences,
    gatewayUrl: String?,
    onUpdateSettings: ((String?, Int, Boolean) -> Unit)?,
    onUseGlobalSettings: ((String) -> Unit)?,
) {
    val editable = onUpdateSettings != null && onUseGlobalSettings != null
    // UI 总是从已保存的偏好重读，不预先将用户操作当成保存成功。
    var revision by remember { mutableStateOf(0) }
    var failures by remember(gatewayUrl) { mutableStateOf(emptySet<String>()) }
    val globalPolicy = remember(preferences, revision) { preferences.read(null) }
    val hasOverride = remember(preferences, gatewayUrl, revision) {
        gatewayUrl != null && preferences.hasOverride(gatewayUrl)
    }
    val corePolicy = remember(preferences, gatewayUrl, revision) {
        gatewayUrl?.let { preferences.read(it) }
    }

    fun save(control: String, action: () -> Unit) {
        try {
            action()
            failures = failures - control
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failures = failures + control
        } finally {
            // SharedPreferences.commit() 失败时内存值也可能已变。回读并明确提示失败，
            // 而不是假装内存值已可靠持久化。
            revision++
        }
    }

    SettingsSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            if (!editable) {
                Text("消息处理暂不可编辑：运行时未连接。", style = MaterialTheme.typography.bodyMedium)
            }
            DebouncePolicyEditor(
                title = "全局默认",
                policy = globalPolicy,
                enabled = editable,
                revision = revision,
                onSave = { delay, extend ->
                    onUpdateSettings?.let { update -> save("global") { update(null, delay, extend) } }
                },
            )
            if ("global" in failures) DebounceSaveFailure()
            if (gatewayUrl != null && corePolicy != null) {
                DebounceSwitch(
                    label = "覆盖当前 Core 的设置",
                    checked = hasOverride,
                    enabled = editable,
                    onCheckedChange = { enabled ->
                        save("override") {
                            if (enabled) {
                                checkNotNull(onUpdateSettings)(
                                    gatewayUrl,
                                    corePolicy.delay.inWholeMilliseconds.toInt(),
                                    corePolicy.mode == DebounceMode.EXTEND_WINDOW,
                                )
                            } else {
                                checkNotNull(onUseGlobalSettings)(gatewayUrl)
                            }
                        }
                    },
                )
                if ("override" in failures) DebounceSaveFailure()
                Text(
                    "当前 Core：$gatewayUrl",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasOverride) {
                    DebouncePolicyEditor(
                        title = "当前 Core 覆盖",
                        policy = corePolicy,
                        enabled = editable,
                        revision = revision,
                        onSave = { delay, extend ->
                            onUpdateSettings?.let { update -> save("core") { update(gatewayUrl, delay, extend) } }
                        },
                    )
                } else {
                    Text("当前 Core 使用全局默认。", style = MaterialTheme.typography.bodySmall)
                }
                if ("core" in failures) DebounceSaveFailure()
            } else {
                Text("未选择 Core；可编辑全局默认。", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "0 秒立即发送，不合并消息。设置应用于新批次；最长等待 30 秒，每批最多 20 条。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DebounceSaveFailure() {
    Text(
        "消息处理设置未能保存，重启后可能恢复原设置，请重试。",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun DebouncePolicyEditor(
    title: String,
    policy: DebouncePolicy,
    enabled: Boolean,
    revision: Int,
    onSave: (Int, Boolean) -> Unit,
) {
    val storedDelay = policy.delay.inWholeMilliseconds.toInt()
    val extend = policy.mode == DebounceMode.EXTEND_WINDOW
    var draftDelay by remember(title, storedDelay, revision) { mutableFloatStateOf(storedDelay.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text("合并连续消息", style = MaterialTheme.typography.bodyLarge)
        Text(
            if (draftDelay.toInt() == 0) "立即发送（不合并）" else "等待 ${draftDelay.toInt() / 1000.0} 秒",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 拖动时显示的是尚未提交的选择，只有松开后才请求保存。
        Slider(
            value = draftDelay,
            valueRange = 0f..10000f,
            steps = 19,
            enabled = enabled,
            onValueChange = { draftDelay = it },
            onValueChangeFinished = { onSave(draftDelay.toInt(), extend) },
            modifier = Modifier.semantics { contentDescription = "$title 等待时间" },
        )
        DebounceSwitch(
            label = "$title：新消息延长等待",
            checked = extend,
            enabled = enabled,
            onCheckedChange = { onSave(storedDelay, it) },
        )
    }
}

@Composable
private fun DebounceSwitch(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = Dimensions.MinimumTouchTarget)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = Dimensions.SpaceTiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
