package com.openandroidintelligence.mobile.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.openandroidintelligence.kernel.SmsScopeMode
import java.security.MessageDigest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.kernel.CapabilityPolicy
import com.openandroidintelligence.mobile.sms.SmsScopeSelector
import com.openandroidintelligence.mobile.sms.parseSmsScopeEntries
import com.openandroidintelligence.mobile.sms.validSmsScope

private data class SmsPassiveDraft(
    val mode: SmsScopeMode,
    val keywords: String,
    val senders: String,
    val includeContent: Boolean,
)

/** A saved draft belongs to both the provider and its last saved scope. */
private fun scopeBaseline(identity: String, policy: CapabilityPolicy): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    add(identity)
    add(policy.smsScopeMode.name)
    add(policy.smsKeywords.size.toString())
    policy.smsKeywords.sorted().forEach(::add)
    add(policy.allowedSources.size.toString())
    policy.allowedSources.sorted().forEach(::add)
    add(policy.includeContent.toString())
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** 短信被动读取范围的编辑草稿；唯一持久化仍通过当前能力的 policy 回调。 */
@Composable
internal fun SmsPassiveScopeSettings(
    identity: String,
    policy: CapabilityPolicy,
    onPolicyChange: (CapabilityPolicy) -> Unit,
    draftIdentity: String = identity,
) {
    // Do not put saved phone numbers or keywords in the baseline: only the draft
    // itself is saved in Compose's state, as in the existing editable fields.
    val baseline = remember(draftIdentity, policy.smsScopeMode, policy.smsKeywords, policy.allowedSources, policy.includeContent) {
        scopeBaseline(draftIdentity, policy)
    }
    val saver = remember(baseline) {
        Saver<SmsPassiveDraft, List<String>>(
            save = { listOf(baseline, it.mode.name, it.keywords, it.senders, it.includeContent.toString()) },
            restore = {
                if (it.size != 5 || it[0] != baseline) null
                else runCatching { SmsPassiveDraft(SmsScopeMode.valueOf(it[1]), it[2], it[3], it[4].toBooleanStrict()) }.getOrNull()
            },
        )
    }
    var draft by rememberSaveable(baseline, stateSaver = saver) {
        mutableStateOf(SmsPassiveDraft(policy.smsScopeMode, policy.smsKeywords.sorted().joinToString("\n"),
            policy.allowedSources.sorted().joinToString("\n"), policy.includeContent))
    }
    val scopeMode = draft.mode
    val keywords = draft.keywords
    val senders = draft.senders
    val includeContent = draft.includeContent
    val allowedSources = parseSmsAllowedSources(senders)
    val keywordSet = parseSmsScopeEntries(keywords)
    val changed = scopeMode != policy.smsScopeMode || keywordSet != policy.smsKeywords ||
        allowedSources != policy.allowedSources || includeContent != policy.includeContent

    Column(
        modifier = Modifier.fillMaxWidth().testTag("sms-passive-scope:$identity"),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        Text("短信被动查询范围", style = MaterialTheme.typography.titleSmall)
        Text(
            "仅限制本能力的短信读取。主动推送的范围和接收目标另行配置。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SmsScopeSelector(
            mode = scopeMode,
            keywords = keywords,
            phoneNumbers = senders,
            onModeChange = { draft = draft.copy(mode = it) },
            onKeywordsChange = { draft = draft.copy(keywords = it) },
            onPhoneNumbersChange = { draft = draft.copy(senders = it) },
            tagPrefix = "sms-passive",
            tagSuffix = ":$identity",
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("包含短信正文", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (includeContent) "允许读取短信正文" else "仅允许读取发送方、时间等信息，不包含正文",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = includeContent,
                onCheckedChange = { draft = draft.copy(includeContent = it) },
                modifier = Modifier.testTag("sms-passive-content:$identity")
                    .semantics { contentDescription = "包含短信正文" },
            )
        }
        TextButton(
            enabled = changed && validSmsScope(scopeMode, keywordSet, allowedSources),
            onClick = {
                onPolicyChange(policy.copy(
                    allowedSources = allowedSources,
                    includeContent = includeContent,
                    smsScopeMode = scopeMode,
                    smsKeywords = keywordSet,
                ))
            },
            modifier = Modifier.testTag("sms-passive-save:$identity"),
        ) { Text("保存被动查询范围") }
    }
}

internal fun parseSmsAllowedSources(text: String): Set<String> = parseSmsScopeEntries(text)
