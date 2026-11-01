package com.openandroidintelligence.mobile.screen

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScreenControlConfirmationDialogTest {
    @get:Rule val compose = createComposeRule()
    private val capability = "org.openandroidintelligence.sms.query@1.0.0"

    @Test fun everyDurationIsPassedUnchangedAndDenialIsASeparateChoice() {
        val approvals = mutableListOf<Int>()
        var denials = 0
        compose.setContent {
            MaterialTheme {
                ScreenControlConfirmationDialog(
                    requestId = "sample-request", pluginId = "sample-plugin", capability = capability,
                    parameters = "{}", agentName = "测试 Agent", purpose = "检查专用测试样本", operation = "query",
                    expiresAtMillis = System.currentTimeMillis() + 300_000,
                    onApprove = { approvals += it }, onDeny = { denials++ },
                )
            }
        }
        for (duration in listOf(0, 5, 30, 60)) {
            compose.onNodeWithTag("device-confirmation-allow:$duration").assertIsEnabled().performClick()
        }
        assertEquals(listOf(0, 5, 30, 60), approvals)
        assertEquals(0, denials)
        compose.onNodeWithTag("screen-confirmation-deny").performClick()
        assertEquals(1, denials)
    }

    @Test fun confirmationShowsDeclarationsScopeAndSensitiveDataWithoutCallingAQueryASend() {
        compose.setContent {
            MaterialTheme {
                ScreenControlConfirmationDialog(
                    requestId = "sample-request", pluginId = "sample-plugin", capability = capability,
                    parameters = """{"senders":["TEST-SENDER"],"limit":1}""",
                    agentName = "测试 Agent", purpose = "检查专用测试样本", operation = "query",
                    expiresAtMillis = System.currentTimeMillis() + 300_000, onApprove = {}, onDeny = {},
                )
            }
        }
        compose.onNodeWithTag("device-confirmation-agent").assertExists()
        compose.onNodeWithText("操作目的：检查专用测试样本").assertExists()
        compose.onNodeWithText("Agent 名称和操作目的由请求方声明，仅供展示与审计，不证明身份或扩大权限。").assertExists()
        compose.onNodeWithText("读取短信").assertExists()
        compose.onNodeWithText("短信发送方：[\"TEST-SENDER\"]").assertExists()
        compose.onNodeWithText("发送短信").assertDoesNotExist()
        compose.onNodeWithTag("device-confirmation-sensitive").assertExists()
    }

    @Test fun anExpiredRequestDisablesEveryApprovalWithoutManufacturingAUserDenial() {
        var responded = false
        compose.setContent {
            MaterialTheme {
                ScreenControlConfirmationDialog(
                    requestId = "expired-request", pluginId = "sample-plugin", capability = capability,
                    parameters = "{}", agentName = "测试 Agent", purpose = "测试过期", operation = "query",
                    expiresAtMillis = 1L, onApprove = { responded = true }, onDeny = { responded = true },
                )
            }
        }
        for (duration in listOf(0, 5, 30, 60)) compose.onNodeWithTag("device-confirmation-allow:$duration").assertIsNotEnabled()
        compose.onNodeWithText("请求已过期，不能继续允许。").assertExists()
        assertFalse(responded)
    }

    @Test fun aDifferentRequestDoesNotInheritExpandedTechnicalDetails() {
        var id by mutableStateOf("first-request")
        compose.setContent {
            MaterialTheme {
                ScreenControlConfirmationDialog(
                    requestId = id, pluginId = "sample-plugin", capability = capability,
                    parameters = "{}", agentName = "测试 Agent", purpose = "测试状态切换", operation = "query",
                    expiresAtMillis = System.currentTimeMillis() + 300_000, onApprove = {}, onDeny = {},
                )
            }
        }
        compose.onNodeWithTag("screen-confirmation-details-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("screen-confirmation-details").assertExists()
        compose.runOnIdle { id = "second-request" }
        compose.onNodeWithTag("screen-confirmation-details").assertDoesNotExist()
    }
}
