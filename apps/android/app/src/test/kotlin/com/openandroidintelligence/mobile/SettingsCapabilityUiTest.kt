package com.openandroidintelligence.mobile

import android.app.Application
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
import androidx.compose.ui.test.performScrollTo
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.CapabilityProviderSelector
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.HostEnvelope
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.NativePluginLoader
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.kernel.PhoneLimits
import com.openandroidintelligence.kernel.PluginKernel
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
@Config(sdk = [34], application = Application::class)
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

    @Test
    fun bubbleSettingsEntryOpensTheSystemBubbleControlsForThisApp() {
        setContent { ConversationBubbleSettingsItem() }
        compose.onNodeWithText("原生会话气泡").performClick()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val intent = org.robolectric.Shadows.shadowOf(context).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    @Config(sdk = [36])
    fun liveUpdatesSettingsEntryOpensTheAndroid16PromotionControls() {
        setContent { LiveUpdatesSettingsItem() }
        compose.onNodeWithText("Android 16 实时活动").performClick()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val intent = org.robolectric.Shadows.shadowOf(context).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun liveUpdatesSettingsEntryIsAbsentOnOlderAndroid() {
        setContent { LiveUpdatesSettingsItem() }
        compose.onNodeWithText("Android 16 实时活动").assertDoesNotExist()
    }

    // ------------------------------------------------------------------
    // 协商能力分组（条目 3-15）
    // ------------------------------------------------------------------

    @Test
    fun negotiatedCapabilitiesGroupRendersAllDeclaredKeysWithHonestLabels() {
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
        // 客户端只声明了 3 项，其余能力保持「未声明」。
        compose.onAllNodesWithText("未声明").assertCountEquals(ConversationUiFeature.CLOSED_SET.size - 3)
        // 闭集全部渲染，并为会话管理提供人类可读的标签。
        for (label in negotiatedCapabilityLabels.values) {
            compose.onNodeWithText(label).assertExists()
        }
        compose.onNodeWithText("会话与文件夹管理").assertExists()
        compose.onNodeWithText("conversation-management-v1").assertDoesNotExist()
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
            pushChannelWired = false,
        )
        assertFalse(unwired.enabled)
        assertTrue(unwired.supporting.contains("推送"))

        val wired = notificationPushCapabilityPresentation(
            grantsBound = true,
            mirrorAgreed = true,
            pushChannelWired = true,
        )
        assertTrue(wired.enabled)

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
    fun pairingSettingsDescribeLocalCapabilitySwitchesAndSeparateSystemPermissions() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val trustMode = DeveloperTrustMode()
        val audit = AndroidAuditStore(InMemoryAuditSink())
        val environment = PlatformSettingsEnvironment(
            trustMode = trustMode,
            audit = audit,
            auditSink = InMemoryAuditSink(),
            allowDeveloperTrustMode = true,
            kernel = PluginKernel(
                hostEnvelope = HostEnvelope(primitives = emptySet()),
                phoneLimits = PhoneLimits(primitives = emptySet()),
                runtimes = emptyMap(),
                audit = audit,
                trustMode = trustMode,
                nativeLoader = NativePluginLoader(trustMode),
                providerSelector = CapabilityProviderSelector(phoneDefaults = emptyMap()),
                grants = { null },
            ),
            pairingGrants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit),
            appearance = AppearancePreferences(context),
        )
        setContent { SettingsScreen(environment = environment, onBack = {}) }

        compose.onNodeWithText("授权管理").performScrollTo().performClick()
        compose.onNodeWithText("安装并启用插件后，本机设备能力开关默认开启", substring = true).assertExists()
        compose.onNodeWithText("以下两种情况会清空本机授权", substring = true).assertDoesNotExist()
        assertTrue(
            compose.onAllNodesWithText("Android 系统权限", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
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

    @Test
    fun deviceCapabilityFailureReasonsExplainRecoveryAndKeepTheActualCode() {
        assertEquals("服务端状态版本已变化，正在重新读取（GRANT_STALE）", deviceCapabilityReasonLabel("GRANT_STALE"))
        assertEquals("本机设置已变化，正在同步最新设置（LOCAL_AUTHORIZATION_CHANGED）", deviceCapabilityReasonLabel("LOCAL_AUTHORIZATION_CHANGED"))
        assertEquals("网络暂不可用，连接恢复后自动重试（NETWORK_ERROR）", deviceCapabilityReasonLabel("NETWORK_ERROR"))
        assertEquals("尚未提供可识别的阻塞原因", deviceCapabilityReasonLabel("invalid private message"))
    }
}
