package com.openandroidintelligence.mobile.push

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PushSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val endpoint = PushEndpoint("core", "account", "endpoint", 1)
    private fun snapshot(config: PushConfiguration) = PushSnapshot(config, 0, false, 0, 0, 0, 0, 0, 0)

    @Test fun userCanEnableNotificationPushOfflineSelectStableEndpointAndNarrowBody() {
        var saved = PushConfiguration()
        compose.setContent {
            var config by remember { mutableStateOf(saved) }
            MaterialTheme { PushSettingsContent(snapshot(config), listOf(PushEndpointOption(endpoint, "Agent", true)), false, null,
                { saved = it; config = it }, {}, {}) }
        }
        compose.onNodeWithTag("push-notification-enabled").assertIsOff().assertIsEnabled().performClick()
        assertTrue(saved.notifications.enabled)
        assertFalse(saved.sms.enabled)
        compose.onNodeWithTag("push-notification-content").performScrollTo().assertIsOn().performClick()
        assertFalse(saved.notifications.scope.includeContent)
        compose.onNodeWithTag("push-notification-endpoint:core:account:endpoint:1").performScrollTo().performClick()
        assertEquals(setOf(endpoint), saved.notifications.endpoints)
        assertTrue(saved.sms.endpoints.isEmpty())
    }

    @Test fun retentionInputSavesOneHour() {
        var saved = PushConfiguration()
        compose.setContent {
            var config by remember { mutableStateOf(saved) }
            MaterialTheme { PushSettingsContent(snapshot(config), emptyList(), false, null, { saved = it; config = it }, {}, {}) }
        }
        compose.onNodeWithTag("push-retention").performScrollTo().performTextReplacement("1")
        compose.onNodeWithTag("push-save-retention").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, saved.retentionHours)
    }
    @Test fun newerGenerationShowsManualReselectionAndPreservesTheOldCheckedRoute() {
        val selected = PushConfiguration(sms = SmsPushSettings(true, SmsPushScope(allSenders = true), setOf(endpoint)))
        var changes = 0
        compose.setContent {
            MaterialTheme { PushSettingsContent(snapshot(selected),
                listOf(PushEndpointOption(endpoint.copy(generation = 2), "same label", true)), false, null,
                { changes++ }, {}, {}) }
        }
        compose.onNodeWithTag("push-sms-endpoint:core:account:endpoint:1").performScrollTo().assertIsOn()
        compose.onNodeWithText("接收端身份已更新，原选择和待发记录已保留。请手动重选；旧记录不会转给新接收端。").assertExists()
        compose.onNodeWithTag("push-sms-endpoint:core:account:endpoint:2").performScrollTo().assertIsOff()
        assertEquals(0, changes)
    }

}
