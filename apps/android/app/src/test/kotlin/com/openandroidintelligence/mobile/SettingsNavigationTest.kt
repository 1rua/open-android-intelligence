package com.openandroidintelligence.mobile

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.plugins.thirdPartyOnlyHost
import com.openandroidintelligence.mobile.settings.notifications.NotificationSettingsScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.openandroidintelligence.ui.resources.R as UiR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun eachCategoryReturnsBeforeClosingTheSettingsRoot() {
        var closes = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        val context = ApplicationProvider.getApplicationContext<Application>()
        val environment = environment()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val host = thirdPartyOnlyHost(context, environment.pairingGrants, environment.trustMode,
            NativePluginLoader(environment.trustMode), "settings-navigation-test")
        val runtime = RemoteRuntime(context, scope, host, environment.pairingGrants)
        try {
            compose.setContent {
                dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                MaterialTheme {
                    SettingsScreen(environment, { closes++ }, runtime = runtime.localSettings, remoteRuntime = runtime)
                }
            }
            listOf("外观与交互", "连接与账号", "通知与提醒", "设备能力与授权", "安全与隐私", "高级设置与关于").forEach { category ->
                compose.onNodeWithText(category).performScrollTo().performClick()
                val pageTitle = category
                try {
                    compose.onNodeWithText(pageTitle).assertIsDisplayed()
                } catch (failure: AssertionError) {
                    throw AssertionError("打开分类「$category」后页面标题「$pageTitle」未显示", failure)
                }
                if (category == "连接与账号") {
                    compose.onNodeWithText(context.getString(UiR.string.remote_client_role)).assertIsDisplayed()
                    // 主动连接与当前账号都可合法显示“未登录”，断言须绑定具体角色行。
                    compose.onNode(hasText(context.getString(UiR.string.remote_role_signed_out)) and
                        hasAnyAncestor(hasTestTag("remote-client-status")), useUnmergedTree = true).assertIsDisplayed()
                    compose.onNodeWithText(context.getString(UiR.string.remote_host_role)).assertIsDisplayed()
                    compose.onNodeWithText(context.getString(UiR.string.remote_role_stopped)).assertIsDisplayed()
                }
                compose.runOnIdle { dispatcher.onBackPressed() }
                compose.onNodeWithText("设置").assertIsDisplayed()
                assertEquals("分类「$category」返回时应保留设置根页面", 0, closes)
            }
            compose.runOnIdle { dispatcher.onBackPressed() }
            assertEquals(1, closes)
        } finally { scope.cancel() }
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
