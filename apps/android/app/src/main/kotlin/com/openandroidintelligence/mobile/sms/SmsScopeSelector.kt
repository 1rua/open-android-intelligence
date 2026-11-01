package com.openandroidintelligence.mobile.sms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.kernel.SmsScopeMode

/** 主动与被动范围只共享控件；草稿及保存入口由各自的配置页面持有。 */
@Composable
internal fun SmsScopeSelector(
    mode: SmsScopeMode,
    keywords: String,
    phoneNumbers: String,
    onModeChange: (SmsScopeMode) -> Unit,
    onKeywordsChange: (String) -> Unit,
    onPhoneNumbersChange: (String) -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
    tagSuffix: String = "",
    enabled: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
            listOf(
                SmsScopeMode.ALL to "全部",
                SmsScopeMode.KEYWORDS to "关键词",
                SmsScopeMode.PHONE_NUMBERS to "电话号码",
            ).forEach { (option, label) ->
                FilterChip(
                    selected = mode == option,
                    onClick = { onModeChange(option) },
                    label = { Text(label) },
                    enabled = enabled,
                    modifier = Modifier.testTag("$tagPrefix-mode-${option.name.lowercase()}$tagSuffix"),
                )
            }
        }
        if (mode != SmsScopeMode.ALL) {
            val byKeyword = mode == SmsScopeMode.KEYWORDS
            val text = if (byKeyword) keywords else phoneNumbers
            val entries = parseSmsScopeEntries(text)
            val invalid = entries.isEmpty() || !validSmsEntries(entries)
            OutlinedTextField(
                value = text,
                onValueChange = if (byKeyword) onKeywordsChange else onPhoneNumbersChange,
                label = { Text(if (byKeyword) "关键词" else "电话号码") },
                supportingText = {
                    Text(when {
                        entries.isEmpty() -> if (byKeyword) "请至少填写一个关键词。" else "请至少填写一个电话号码。"
                        !validSmsEntries(entries) -> "最多填写 256 项，每项不超过 256 个字符。"
                        byKeyword -> "用逗号或换行分隔；短信内容匹配任一关键词即可。"
                        else -> "用逗号或换行分隔；匹配任一发件号码即可。"
                    })
                },
                isError = invalid,
                enabled = enabled,
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
                    .testTag("$tagPrefix-${if (byKeyword) "keywords" else "senders"}$tagSuffix"),
            )
        }
        if (mode == SmsScopeMode.KEYWORDS) {
            Text(
                "关键词在本机匹配，正文开关决定是否提供正文。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            when (mode) {
                SmsScopeMode.ALL -> "短信范围：全部短信"
                SmsScopeMode.KEYWORDS -> "短信范围：匹配 ${parseSmsScopeEntries(keywords).size} 个关键词"
                SmsScopeMode.PHONE_NUMBERS -> "短信范围：清单中的 ${parseSmsScopeEntries(phoneNumbers).size} 个电话号码"
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("$tagPrefix-scope-summary$tagSuffix"),
        )
    }
}

internal fun parseSmsScopeEntries(text: String): Set<String> = text.split(Regex("[,，\\r\\n]+"))
    .map(String::trim)
    .filter(String::isNotEmpty)
    .toSet()

private fun validSmsEntries(entries: Set<String>) = entries.size <= 256 && entries.all { it.length <= 256 }

internal fun validSmsScope(mode: SmsScopeMode, keywords: Set<String>, phoneNumbers: Set<String>): Boolean =
    validSmsEntries(keywords) && validSmsEntries(phoneNumbers) && when (mode) {
        SmsScopeMode.ALL -> true
        SmsScopeMode.KEYWORDS -> keywords.isNotEmpty()
        SmsScopeMode.PHONE_NUMBERS -> phoneNumbers.isNotEmpty()
    }
