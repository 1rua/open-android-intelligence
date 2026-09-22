package com.openandroidintelligence.mobile

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置页协商能力与操作提示的界面语义：
 * 三态文案必须如实呈现，禁用条目点了必须真的没有反应。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCapabilityUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(content: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme {
                Surface { content() }
            }
        }
    }

    // ------------------------------------------------------------------
    // 协商能力分组（条目 3-15）
    // ------------------------------------------------------------------

    @Test
    fun negotiatedCapabilitiesGroupRendersAllEightKeysWithHonestLabels() {
        val capabilities = negotiatedConversationUi(
            agreed = setOf("agent-command-catalog-v1", "message-batches-v1"),
            requested = setOf(
                "agent-command-catalog-v1",
                "message-batches-v1",
                "agent-command-new-v1",
            ),
        )
        setContent { NegotiatedCapabilitiesGroup(conversationUi = capabilities) }

        // 三种状态都必须真实出现，不能被折叠成同一种
        compose.onAllNodesWithText("已启用").assertCountEquals(2)
        compose.onAllNodesWithText("本网关不支持").assertCountEquals(1)
        // 客户端只声明了 3 项，其余 5 项双方都没声明
        compose.onAllNodesWithText("未声明").assertCountEquals(5)
        // 闭集 8 项全部渲染（用人类可读标签核对）
        for (label in negotiatedCapabilityLabels.values) {
            compose.onNodeWithText(label).assertExists()
        }
    }

    @Test
    fun statusLabelFunctionMatchesTheRenderedWording() {
        assertEquals("已启用", conversationUiStatusLabel(true))
        assertEquals("本网关不支持", conversationUiStatusLabel(false))
        assertEquals("未声明", conversationUiStatusLabel(null))
    }

    // ------------------------------------------------------------------
    // 操作提示条（条目 2-1）：登出/续期结果不再静默
    // ------------------------------------------------------------------

    @Test
    fun operationNoticeBannerShowsTheReasonAndIsDismissable() {
        var dismissed = false
        setContent {
            OperationNoticeBanner(
                text = "Gateway 拒绝续期：刷新凭据已失效，本机自动登录凭据已清除，请重新登录。",
                onDismiss = { dismissed = true },
            )
        }

        compose.onNodeWithText("Gateway 拒绝续期：刷新凭据已失效，本机自动登录凭据已清除，请重新登录。")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription(NOTICE_DISMISS_LABEL).performClick()
        assertTrue(dismissed)
    }

    // ------------------------------------------------------------------
    // 能力未接入条目的禁用态（条目 3-16）
    // ------------------------------------------------------------------

    @Test
    fun screenSelectionPresentationCombinesWiringGrantsAndNegotiation() {
        // 宿主未接入截图来源（显式注入，与全局装配状态解耦）：不可用
        val unwired = screenSelectionCapabilityPresentation(
            grantsBound = true,
            attachmentStatusAgreed = true,
            screenshotSourceWired = false,
        )
        assertFalse(unwired.enabled)
        assertTrue(unwired.supporting.contains("截图"))

        // 来源已接入（Wave 1 起宿主装配了 MediaProjection 来源）且协商同意：可用
        val wired = screenSelectionCapabilityPresentation(
            grantsBound = true,
            attachmentStatusAgreed = true,
            screenshotSourceWired = true,
        )
        assertTrue(wired.enabled)

        // 没有活动配对：授权本身不可用
        val unbound = screenSelectionCapabilityPresentation(
            grantsBound = false,
            attachmentStatusAgreed = true,
        )
        assertFalse(unbound.enabled)
        assertTrue(unbound.supporting.contains("配对"))
    }

    @Test
    fun notificationPushPresentationCombinesWiringGrantsAndNegotiation() {
        val unwired = notificationPushCapabilityPresentation(
            grantsBound = true,
            mirrorAgreed = true,
        )
        assertFalse(unwired.enabled)
        assertTrue(unwired.supporting.contains("推送"))

        val unbound = notificationPushCapabilityPresentation(
            grantsBound = false,
            mirrorAgreed = true,
        )
        assertFalse(unbound.enabled)
        assertTrue(unbound.supporting.contains("配对"))
    }

    @Test
    fun aDisabledScreenSelectionEntryDoesNotToggleWhenPressed() {
        var toggled: Boolean? = null
        setContent {
            ScreenSelectionCapabilityItem(
                checked = false,
                presentation = screenSelectionPresentation(
                    screenshotSourceWired = false,
                    attachmentStatusAgreed = true,
                ),
                onCheckedChange = { toggled = it },
            )
        }

        compose.onNodeWithText("屏幕上下文分析与圈选").performClick()
        compose.onNodeWithText("已启用").assertDoesNotExist()
        assertEquals("禁用条目点了不得触发回调", null, toggled)
    }

    @Test
    fun anEnabledScreenSelectionEntryTogglesWhenPressed() {
        var toggled: Boolean? = null
        setContent {
            ScreenSelectionCapabilityItem(
                checked = false,
                presentation = screenSelectionPresentation(
                    screenshotSourceWired = true,
                    attachmentStatusAgreed = true,
                ),
                onCheckedChange = { toggled = it },
            )
        }

        compose.onNodeWithText("屏幕上下文分析与圈选").performClick()
        assertEquals(true, toggled)
    }

    @Test
    fun aDisabledNotificationPushEntryDoesNotToggleWhenPressed() {
        var toggled: Boolean? = null
        setContent {
            NotificationPushCapabilityItem(
                checked = false,
                presentation = notificationPushPresentation(
                    pushChannelWired = false,
                    mirrorAgreed = true,
                ),
                onCheckedChange = { toggled = it },
            )
        }

        compose.onNodeWithText("系统通知推送").performClick()
        assertEquals("禁用条目点了不得触发回调", null, toggled)
    }

    @Test
    fun anEnabledNotificationPushEntryTogglesWhenPressed() {
        var toggled: Boolean? = null
        setContent {
            NotificationPushCapabilityItem(
                checked = false,
                presentation = notificationPushPresentation(
                    pushChannelWired = true,
                    mirrorAgreed = true,
                ),
                onCheckedChange = { toggled = it },
            )
        }

        compose.onNodeWithText("系统通知推送").performClick()
        assertEquals(true, toggled)
    }
}
