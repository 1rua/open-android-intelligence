package com.openandroidintelligence.mobile

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.settings.notifications.NotificationSettingsScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun eachCategoryReturnsBeforeClosingTheSettingsRoot() {
        var closes = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        val environment = environment()
        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            MaterialTheme { SettingsScreen(environment, { closes++ }) }
        }
        listOf("外观与交互", "连接与账号", "通知与提醒", "设备能力与授权", "安全与隐私", "高级设置与关于").forEach { category ->
            compose.onNodeWithText(category).performScrollTo().performClick()
            compose.onNodeWithText(category).assertIsDisplayed()
            compose.runOnIdle { dispatcher.onBackPressed() }
            compose.onNodeWithText("设置").assertIsDisplayed()
            assertEquals(0, closes)
        }
        compose.runOnIdle { dispatcher.onBackPressed() }
        assertEquals(1, closes)
    }

    @Test fun notificationPreferenceRemainsCheckedWhenAndroidBlocksIt() {
        compose.setContent {
            MaterialTheme {
                NotificationSettingsScreen(
                    SettingsUiState(replyNotificationsWired = true, replyNotificationsEnabled = true, replyNotificationsAllowed = false),
                    SettingsPresentation.SCREEN, {}, {},
                )
            }
        }
        compose.onNodeWithText("Agent 任务通知").assertIsOn()
        compose.onNodeWithText("系统未允许；App 偏好仍保留").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("通知读取范围").assertDoesNotExist()
        compose.onNodeWithText("通知主动推送").assertDoesNotExist()
    }

    private fun environment(): PlatformSettingsEnvironment {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val trust = DeveloperTrustMode()
        val sink = InMemoryAuditSink()
        val audit = AndroidAuditStore(sink)
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
        grants.bind(PairingGrantBinding("https://core.example", "test-account", "test-install"))
        val kernel = PluginKernel(
            HostEnvelope(emptySet()), PhoneLimits(emptySet()), emptyMap(), audit, trust,
            NativePluginLoader(trust), CapabilityProviderSelector(emptyMap()), { id -> grants.currentKernelGrant(id) },
        )
        return PlatformSettingsEnvironment(trust, audit, sink, true, kernel, grants, AppearancePreferences(context))
    }
}
