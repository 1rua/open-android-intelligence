package com.openandroidintelligence.mobile.plugins

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import com.openandroidintelligence.kernel.CapabilityAuthorizationMode
import com.openandroidintelligence.kernel.CapabilityPolicy
import com.openandroidintelligence.kernel.SmsScopeMode
import com.openandroidintelligence.mobile.push.PushConfiguration
import com.openandroidintelligence.mobile.push.PushSettingsContent
import com.openandroidintelligence.mobile.push.PushSnapshot
import com.openandroidintelligence.mobile.push.SmsPushScope
import com.openandroidintelligence.mobile.push.SmsPushSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SmsPassiveScopeSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val identity = "sms:query@1.0.0"

    @Test fun senderSeparatorsAreNormalizedWithoutChangingSenderIdentity() {
        assertEquals(setOf("TEST-A", "TEST B", "+15550000000"),
            parseSmsAllowedSources(" TEST-A,TEST B\r\n+15550000000，TEST-A\n\n"))
        assertTrue(parseSmsAllowedSources(" , ，\n\r ").isEmpty())
    }

    @Test fun editsRequireExplicitSaveAndPreserveTheCapabilitySwitchAndMode() {
        val original = CapabilityPolicy(enabled = false, mode = CapabilityAuthorizationMode.READ_ONLY)
        var saved = original
        var commits = 0
        compose.setContent {
            var policy by remember { mutableStateOf(original) }
            MaterialTheme {
                SmsPassiveScopeSettings(identity, policy, { saved = it; policy = it; commits++ })
            }
        }
        compose.onNodeWithTag("sms-passive-save:$identity").assertIsNotEnabled()
        compose.onNodeWithTag("sms-passive-mode-phone_numbers:$identity").performClick()
        compose.onNodeWithTag("sms-passive-senders:$identity").performTextReplacement(" +15550000001,+15550000002\n+15550000001 ")
        compose.onNodeWithTag("sms-passive-content:$identity").performClick()
        assertEquals(original, saved)
        assertEquals(0, commits)
        compose.onNodeWithTag("sms-passive-save:$identity").assertIsEnabled().performClick()
        assertEquals(original.copy(
            allowedSources = setOf("+15550000001", "+15550000002"),
            includeContent = false,
            smsScopeMode = SmsScopeMode.PHONE_NUMBERS,
        ), saved)
        assertEquals(1, commits)
        compose.onNodeWithTag("sms-passive-save:$identity").assertIsNotEnabled()
    }

    @Test fun clearingThePhoneListRequiresAnExplicitAllSelectionWithoutReEnablingBodyContent() {
        val original = CapabilityPolicy(allowedSources = setOf("+15550000001"), includeContent = false)
        var saved = original
        compose.setContent {
            var policy by remember { mutableStateOf(saved) }
            MaterialTheme { SmsPassiveScopeSettings(identity, policy, { saved = it; policy = it }) }
        }
        compose.onNodeWithTag("sms-passive-mode-phone_numbers:$identity").assertIsSelected()
        compose.onNodeWithTag("sms-passive-senders:$identity").performTextReplacement(" ,，\r\n ")
        compose.onNodeWithText("请至少填写一个电话号码。").assertExists()
        compose.onNodeWithTag("sms-passive-save:$identity").assertIsNotEnabled()
        assertEquals(original, saved)
        compose.onNodeWithTag("sms-passive-mode-all:$identity").performClick()
        compose.onNodeWithTag("sms-passive-save:$identity").assertIsEnabled().performClick()
        assertEquals(CapabilityPolicy(includeContent = false), saved)
    }

    @Test fun keywordModeRequiresConditionsAndSavesTheActualPolicyWithoutEnablingTheCapability() {
        val original = CapabilityPolicy(enabled = false, mode = CapabilityAuthorizationMode.READ_ONLY, includeContent = false)
        var saved = original
        var commits = 0
        compose.setContent {
            var policy by remember { mutableStateOf(original) }
            MaterialTheme { SmsPassiveScopeSettings(identity, policy, { saved = it; policy = it; commits++ }) }
        }
        compose.onNodeWithTag("sms-passive-mode-all:$identity").assertIsSelected()
        compose.onNodeWithText("关键词在本机匹配，正文开关决定是否提供正文。").assertDoesNotExist()
        compose.onNodeWithTag("sms-passive-mode-keywords:$identity").performClick()
        compose.onNodeWithText("关键词在本机匹配，正文开关决定是否提供正文。").assertExists()
        compose.onNodeWithTag("sms-passive-save:$identity").assertIsNotEnabled()
        compose.onNodeWithTag("sms-passive-keywords:$identity").performTextReplacement(" ,，\r\n ")
        compose.onNodeWithText("请至少填写一个关键词。").assertExists()
        compose.onNodeWithTag("sms-passive-save:$identity").assertIsNotEnabled()
        compose.onNodeWithTag("sms-passive-keywords:$identity").performTextReplacement(" 验证码，快递\n验证码 ")
        assertEquals(0, commits)
        compose.onNodeWithTag("sms-passive-save:$identity").assertIsEnabled().performClick()
        assertEquals(original.copy(smsScopeMode = SmsScopeMode.KEYWORDS, smsKeywords = setOf("验证码", "快递")), saved)
        assertEquals(1, commits)
        compose.onNodeWithTag("sms-passive-save:$identity").assertIsNotEnabled()
    }

    @Test fun switchingModesRetainsEachConditionDraftAndTheBodySelection() {
        val original = CapabilityPolicy(allowedSources = setOf("+15550000001"), smsKeywords = setOf("快递"))
        var saved = original
        compose.setContent {
            var policy by remember { mutableStateOf(original) }
            MaterialTheme { SmsPassiveScopeSettings(identity, policy, { saved = it; policy = it }) }
        }
        compose.onNodeWithTag("sms-passive-senders:$identity").performTextReplacement("+15550000002")
        compose.onNodeWithTag("sms-passive-mode-keywords:$identity").performClick()
        compose.onNodeWithTag("sms-passive-keywords:$identity").performTextReplacement("验证码")
        compose.onNodeWithTag("sms-passive-mode-all:$identity").performClick()
        compose.onNodeWithTag("sms-passive-mode-phone_numbers:$identity").performClick()
        compose.onNodeWithTag("sms-passive-senders:$identity").assertTextContains("+15550000002")
        compose.onNodeWithTag("sms-passive-content:$identity").performClick()
        compose.onNodeWithTag("sms-passive-save:$identity").performClick()
        assertEquals(original.copy(
            allowedSources = setOf("+15550000002"), smsKeywords = setOf("验证码"), includeContent = false,
        ), saved)
    }

    @Test fun oversizedConditionsCannotReachTheSaveCallback() {
        var commits = 0
        compose.setContent {
            MaterialTheme { SmsPassiveScopeSettings(identity, CapabilityPolicy(), { commits++ }) }
        }
        compose.onNodeWithTag("sms-passive-mode-keywords:$identity").performClick()
        compose.onNodeWithTag("sms-passive-keywords:$identity").performTextReplacement("词".repeat(257))
        compose.onNodeWithTag("sms-passive-save:$identity").assertIsNotEnabled()
        compose.onNodeWithTag("sms-passive-keywords:$identity").performTextReplacement((1..257).joinToString(",") { "词$it" })
        compose.onNodeWithTag("sms-passive-save:$identity").assertIsNotEnabled()
        assertEquals(0, commits)
    }

    @Test fun legacySenderIdentitiesRemainVisibleAndAreNeverChangedToAllWhenSavingBody() {
        val original = CapabilityPolicy(allowedSources = setOf("TEST-A", "TEST B"))
        var saved = original
        compose.setContent {
            var policy by remember { mutableStateOf(original) }
            MaterialTheme { SmsPassiveScopeSettings(identity, policy, { saved = it; policy = it }) }
        }
        compose.onNodeWithTag("sms-passive-mode-phone_numbers:$identity").assertIsSelected()
        compose.onNodeWithTag("sms-passive-senders:$identity").assertTextContains("TEST B\nTEST-A")
        compose.onNodeWithTag("sms-passive-content:$identity").performClick()
        compose.onNodeWithTag("sms-passive-save:$identity").performClick()
        assertEquals(original.copy(includeContent = false), saved)
    }

    @Test fun passiveAndPushShareTheSelectorButNeverOverwriteEachOthersRangeOrBody() {
        val originalPolicy = CapabilityPolicy(
            enabled = false, mode = CapabilityAuthorizationMode.READ_ONLY,
            allowedSources = setOf("LEGACY-SENDER"), includeContent = false,
        )
        val originalPush = PushConfiguration(sms = SmsPushSettings(
            scope = SmsPushScope(mode = SmsScopeMode.KEYWORDS, allSenders = false, keywords = setOf("快递")),
        ))
        var savedPolicy = originalPolicy
        var savedPush = originalPush
        var showPush by mutableStateOf(false)
        compose.setContent {
            var policy by remember { mutableStateOf(originalPolicy) }
            var config by remember { mutableStateOf(originalPush) }
            MaterialTheme {
                Column {
                    TextButton(onClick = { showPush = !showPush }) { Text("切换短信设置") }
                    if (showPush) {
                        PushSettingsContent(PushSnapshot(config, 0, false, 0, 0, 0, 0, 0, 0), emptyList(), false, null,
                            { savedPush = it; config = it }, {}, {})
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            SmsPassiveScopeSettings(identity, policy, { savedPolicy = it; policy = it })
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("sms-passive-mode-keywords:$identity").performClick()
        compose.onNodeWithTag("sms-passive-keywords:$identity").performTextReplacement("验证码")
        compose.onNodeWithTag("sms-passive-save:$identity").performScrollTo().assertIsEnabled().performClick()
        val passiveResult = originalPolicy.copy(smsScopeMode = SmsScopeMode.KEYWORDS, smsKeywords = setOf("验证码"))
        assertEquals(passiveResult, savedPolicy)
        assertEquals(originalPush, savedPush)
        compose.onNodeWithText("切换短信设置").performClick()
        compose.onNodeWithTag("push-sms-mode-keywords").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("push-sms-keywords").performScrollTo().assertTextContains("快递")
        compose.onNodeWithTag("push-sms-mode-phone_numbers").performScrollTo().performClick()
        compose.onNodeWithTag("push-sms-senders").performScrollTo().performTextReplacement("+15550000002")
        compose.onNodeWithTag("push-sms-content").performScrollTo().performClick()
        compose.onNodeWithTag("push-save-senders").performScrollTo().performClick()
        assertEquals(originalPush.copy(sms = originalPush.sms.copy(scope = originalPush.sms.scope.copy(
            mode = SmsScopeMode.PHONE_NUMBERS, allSenders = false,
            senderAddresses = setOf("+15550000002"), includeContent = false,
        ))), savedPush)
        assertEquals(passiveResult, savedPolicy)
        compose.onNodeWithText("切换短信设置").performClick()
        compose.onNodeWithTag("sms-passive-mode-keywords:$identity").assertIsSelected()
        compose.onNodeWithTag("sms-passive-keywords:$identity").assertTextContains("验证码")
    }

    @Test fun notificationsDoNotAdvertiseSmsFilters() {
        val plugin = "org.openandroidintelligence.notifications"
        val capability = "$plugin.query@1.0.0"
        compose.setContent {
            MaterialTheme { CapabilityAuthorizationControls(plugin, capability, true, CapabilityPolicy(), {}) }
        }
        compose.onNodeWithTag("sms-passive-scope:$plugin:$capability").assertDoesNotExist()
        compose.onNodeWithText("短信被动查询范围").assertDoesNotExist()
    }

    @Test fun smsTaskCapabilitiesDoNotAdvertiseUnenforcedPassiveFilters() {
        val plugin = "org.openandroidintelligence.sms"
        var capability by mutableStateOf("$plugin.schedule@1.0.0")
        compose.setContent {
            MaterialTheme { CapabilityAuthorizationControls(plugin, capability, false, CapabilityPolicy(), {}) }
        }
        for (name in listOf("schedule", "cancel", "job-status", "query-extra")) {
            compose.runOnIdle { capability = "$plugin.$name@1.0.0" }
            compose.onNodeWithTag("sms-passive-scope:$plugin:$capability").assertDoesNotExist()
            compose.onNodeWithText("短信被动查询范围").assertDoesNotExist()
        }
    }

    @Test fun smsQueryUsesThePolicyBackedScopeEditor() {
        val plugin = "org.openandroidintelligence.sms"
        val capability = "$plugin.query@1.0.0"
        compose.setContent {
            MaterialTheme { CapabilityAuthorizationControls(plugin, capability, true, CapabilityPolicy(), {}) }
        }
        compose.onNodeWithTag("sms-passive-scope:$plugin:$capability").assertExists()
        compose.onNodeWithText("仅限制本能力的短信读取。主动推送的范围和接收目标另行配置。").assertExists()
    }
}
