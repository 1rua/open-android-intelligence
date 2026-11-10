package com.openandroidintelligence.mobile.settings.advanced

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.mobile.GatewayRuntime
import com.openandroidintelligence.mobile.conversations.DebouncePreferences
import kotlinx.coroutines.CancellationException

/**
 * 「消息处理」里唯一的合并设置：客户端本地的合并时长。
 *
 * 合并只发生在手机上，与当前 Core 无关，因此这里不提供任何按 Core 覆盖的入口，
 * 也不会向 Core 写配置。
 */
@Composable
internal fun MessageProcessingSettings(runtime: GatewayRuntime?) {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { DebouncePreferences(context) }
    MessageProcessingEditor(
        preferences = preferences,
        onSaveDelay = { delayMillis ->
            if (runtime != null) runtime.updateDebounceSettings(delayMillis)
            else preferences.write(delayMillis)
        },
    )
}

@Composable
internal fun MessageProcessingEditor(
    preferences: DebouncePreferences,
    onSaveDelay: ((Int) -> Unit)?,
) {
    val editable = onSaveDelay != null
    // UI 总是从已保存的偏好重读，不预先将用户操作当成保存成功。
    var revision by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    val storedDelay = remember(preferences, revision) { preferences.read().delay.inWholeMilliseconds.toInt() }

    fun save(delayMillis: Int) {
        try {
            checkNotNull(onSaveDelay)(delayMillis)
            failed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
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
            DebounceDelayEditor(
                storedDelay = storedDelay,
                enabled = editable,
                revision = revision,
                onSave = ::save,
            )
            if (failed) DebounceSaveFailure()
            Text(
                "设置为 0 秒时立即发送；大于 0 秒时合并短时间内连续发送的消息。",
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
private fun DebounceDelayEditor(
    storedDelay: Int,
    enabled: Boolean,
    revision: Int,
    onSave: (Int) -> Unit,
) {
    var draftDelay by remember(storedDelay, revision) { mutableFloatStateOf(storedDelay.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny)) {
        Text("合并连续消息", style = MaterialTheme.typography.bodyLarge)
        Text(
            if (draftDelay.toInt() == 0) "当前设置：立即发送（不合并）" else "当前设置：等待 ${draftDelay.toInt() / 1000.0} 秒合并",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 拖动时显示的是尚未提交的选择，只有松开后才请求保存。
        Slider(
            value = draftDelay,
            valueRange = DebouncePreferences.MIN_DELAY_MILLIS.toFloat()..DebouncePreferences.MAX_DELAY_MILLIS.toFloat(),
            steps = DEBOUNCE_SLIDER_STEPS,
            enabled = enabled,
            onValueChange = { draftDelay = it },
            onValueChangeFinished = { onSave(draftDelay.toInt()) },
            modifier = Modifier.semantics { contentDescription = "合并等待时间" },
        )
    }
}

/** 0～30 秒每秒一格：30 个区间之间共 29 个可停靠的中间值。 */
private const val DEBOUNCE_SLIDER_STEPS = 29
