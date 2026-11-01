package com.openandroidintelligence.mobile.plugins

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openandroidintelligence.kernel.CapabilityAuthorizationMode
import com.openandroidintelligence.kernel.CapabilityPolicy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CapabilityAuthorizationControlsTest {
    @get:Rule val compose = createComposeRule()
    private val plugin = "org.example.device"
    private val read = "org.example.device.read@1.0.0"
    private val control = "org.example.device.control@1.0.0"

    @Test fun modesRemainConfigurableWithoutAConnectionAndDoNotTurnADisabledCapabilityOn() {
        val original = CapabilityPolicy(enabled = false, allowedSources = setOf("TEST-SENDER"), includeContent = false)
        var saved = original
        compose.setContent {
            var policy by remember { mutableStateOf(saved) }
            MaterialTheme {
                CapabilityAuthorizationControls(plugin, read, true, policy, { saved = it; policy = it })
            }
        }
        compose.onNodeWithTag("capability-mode:$plugin:$read:READ_ONLY").assertIsEnabled().performClick()
        compose.onNodeWithTag("capability-mode:$plugin:$read:READ_ONLY").assertIsSelected()
        assertEquals(original.copy(mode = CapabilityAuthorizationMode.READ_ONLY), saved)
        compose.onNodeWithTag("capability-mode:$plugin:$read:AUTOMATIC").performClick()
        compose.onNodeWithTag("capability-enabled:$plugin:$read").assertIsOff().performClick()
        assertEquals(original.copy(enabled = true, mode = CapabilityAuthorizationMode.AUTOMATIC), saved)
        compose.onNodeWithText("设置保存在本机，离线也可修改。", substring = true).assertExists()
    }

    @Test fun changingOneEntryDoesNotChangeAnotherCapabilityInTheSamePlugin() {
        var readPolicy = CapabilityPolicy()
        var controlPolicy = CapabilityPolicy()
        compose.setContent {
            var first by remember { mutableStateOf(readPolicy) }
            var second by remember { mutableStateOf(controlPolicy) }
            MaterialTheme {
                Column {
                    CapabilityAuthorizationControls(plugin, read, true, first, { readPolicy = it; first = it })
                    CapabilityAuthorizationControls(plugin, control, false, second, { controlPolicy = it; second = it })
                }
            }
        }
        compose.onNodeWithTag("capability-enabled:$plugin:$read").performClick()
        assertEquals(CapabilityPolicy(false), readPolicy)
        assertEquals(CapabilityPolicy(), controlPolicy)
        compose.onNodeWithTag("capability-enabled:$plugin:$control").assertIsOn()
        compose.onNodeWithTag("capability-mode:$plugin:$control:MANUAL").assertIsSelected()
    }

    @Test fun onlyReadCapabilitiesExplainThatAutomaticAddsNoPermissions() {
        compose.setContent {
            MaterialTheme {
                Column {
                    CapabilityAuthorizationControls(plugin, read, true, CapabilityPolicy(), {})
                    CapabilityAuthorizationControls(plugin, control, false, CapabilityPolicy(), {})
                }
            }
        }
        compose.onNodeWithTag("capability-read-only:$plugin:$read").assertExists()
        compose.onNodeWithTag("capability-read-only:$plugin:$control").assertDoesNotExist()
        compose.onNodeWithText("此能力仅提供读取；「允许只读」和「自动允许」不会产生额外权限差异。").assertExists()
    }
}
