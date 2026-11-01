package com.openandroidintelligence.mobile.plugins

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.openandroidintelligence.kernel.CapabilityAuthorizationMode
import com.openandroidintelligence.kernel.CapabilityPolicy
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
        compose.onNodeWithTag("sms-passive-senders:$identity").performTextReplacement(" TEST-A,TEST-B\nTEST-A ")
        compose.onNodeWithTag("sms-passive-content:$identity").performClick()
        assertEquals(original, saved)
        assertEquals(0, commits)
        compose.onNodeWithTag("sms-passive-save:$identity").assertIsEnabled().performClick()
        assertEquals(CapabilityPolicy(false, CapabilityAuthorizationMode.READ_ONLY, setOf("TEST-A", "TEST-B"), false), saved)
        assertEquals(1, commits)
        compose.onNodeWithTag("sms-passive-save:$identity").assertIsNotEnabled()
    }

    @Test fun clearingTheSenderListSavesAllSendersWithoutReEnablingBodyContent() {
        var saved = CapabilityPolicy(allowedSources = setOf("TEST-A"), includeContent = false)
        compose.setContent {
            var policy by remember { mutableStateOf(saved) }
            MaterialTheme { SmsPassiveScopeSettings(identity, policy, { saved = it; policy = it }) }
        }
        compose.onNodeWithTag("sms-passive-senders:$identity").performTextReplacement("")
        compose.onNodeWithText("发送方范围：全部发送方").assertExists()
        compose.onNodeWithTag("sms-passive-save:$identity").performClick()
        assertEquals(CapabilityPolicy(includeContent = false), saved)
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
