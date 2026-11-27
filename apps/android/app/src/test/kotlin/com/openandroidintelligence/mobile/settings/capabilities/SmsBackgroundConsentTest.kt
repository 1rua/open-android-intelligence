package com.openandroidintelligence.mobile.settings.capabilities

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.HostEnvelope
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.PairingGrantBinding
import com.openandroidintelligence.kernel.PhoneLimits
import com.openandroidintelligence.kernel.PluginKernel
import com.openandroidintelligence.mobile.AppearancePreferences
import com.openandroidintelligence.mobile.OpenAndroidIntelligenceApplication
import com.openandroidintelligence.mobile.PlatformSettingsEnvironment
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.plugins.TestAlpPackages
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「后台任务许可」必须在能力详情里可改。
 *
 * 内建能力从插件管理里消失后，那里原本是唯一能修改这个许可的地方。能力开关会显式排除
 * `kernel.background.run`，调度器又同时要求全局与身份作用域两项许可，所以缺了这张卡片，
 * 用户打开「短信定时任务设置」后每次调度都会以 `BACKGROUND_GRANT_REQUIRED` 失败，
 * 已有许可也无法撤销。
 *
 * 用一个声明了后台任务的第三方短信包验证同一条装配：许可卡片是按“插件是否声明后台”
 * 出现的，与它是不是内建无关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OpenAndroidIntelligenceApplication::class)
class SmsBackgroundConsentTest {

    @get:Rule val compose = createComposeRule()

    private val pluginId = "org.example.sms-background"
    private lateinit var app: OpenAndroidIntelligenceApplication

    @Before fun setUp() {
        assumeTrue(com.openandroidintelligence.mobile.BuildConfig.ALLOW_RUNTIME_PLUGINS)
        app = ApplicationProvider.getApplicationContext()
        app.pluginHost.installStore.installRoot.deleteRecursively()
        app.pluginHost.installStore.install(TestAlpPackages.compiledSmsPackage(pluginId = pluginId))
        app.pluginHost.reload()
        assumeTrue("测试包必须声明后台任务", app.pluginHost.supportsBackground(pluginId))
    }

    @After fun tearDown() {
        if (::app.isInitialized) {
            app.pluginHost.installStore.installRoot.deleteRecursively()
            runCatching { app.pluginHost.reload() }
        }
    }

    private fun environment(): PlatformSettingsEnvironment {
        val trust = DeveloperTrustMode()
        val audit = AndroidAuditStore(InMemoryAuditSink())
        val grants = app.pairingGrants
        grants.bind(PairingGrantBinding("https://core.example", "account", "install"))
        return PlatformSettingsEnvironment(
            trustMode = trust,
            audit = audit,
            auditSink = InMemoryAuditSink(),
            allowDeveloperTrustMode = true,
            kernel = PluginKernel(
                hostEnvelope = HostEnvelope(app.pluginHost.hostCapabilities),
                phoneLimits = PhoneLimits(app.pluginHost.phoneCapabilities),
                runtimes = app.pluginHost.runtimes,
                audit = audit,
                trustMode = trust,
                nativeLoader = app.pluginHost.nativeLoader,
                providerSelector = app.pluginHost.selector,
                grants = { grants.currentKernelGrant(it) },
            ),
            pairingGrants = grants,
            appearance = AppearancePreferences(app),
            pluginRuntimesWired = true,
        )
    }

    private fun showSmsDetail() {
        val entry = app.pluginHost.entries(pluginId).first { it.id.endsWith(".query") }
        compose.setContent {
            MaterialTheme {
                Surface {
                    CapabilitySettingsScreen(
                        pluginId = pluginId,
                        capability = entry.key,
                        environment = environment(),
                        runtime = null,
                        presentation = SettingsPresentation.SCREEN,
                        onBack = {},
                        onRequestScreenCaptureAuthorization = {},
                    )
                }
            }
        }
    }

    @Test
    fun theDetailExposesTheIndependentBackgroundConsent() {
        assertFalse("新装时后台许可默认关闭", app.pluginHost.isBackgroundEnabled(pluginId))
        showSmsDetail()

        compose.onNodeWithTag("capability-background-toggle:$pluginId").performScrollTo().assertIsOff()
        compose.onNodeWithTag("capability-background-toggle:$pluginId").performClick()
        assertTrue("开关必须真的写入本机许可", app.pluginHost.isBackgroundEnabled(pluginId))
        compose.onNodeWithTag("capability-background-toggle:$pluginId").assertIsOn()

        compose.onNodeWithTag("capability-background-toggle:$pluginId").performClick()
        assertFalse("用户必须能撤销已有的后台许可", app.pluginHost.isBackgroundEnabled(pluginId))
    }

    @Test
    fun theBackgroundCopyStatesThatItIsSeparateFromTheCapabilitySwitch() {
        showSmsDetail()
        // 不能让人以为打开能力开关就等于允许后台执行。
        compose.onNodeWithText("定时与后台操作需要这项单独许可", substring = true).performScrollTo().assertExists()
    }

    @Test
    fun enablingABackgroundCapabilityDoesNotSilentlyGrantBackgroundExecution() {
        // 许可与能力开关解耦：打开「短信定时任务设置」不会连带打开后台许可。
        val schedule = app.pluginHost.entries(pluginId).first { it.id.endsWith(".schedule") }
        app.pluginHost.setCapabilityPolicy(pluginId, schedule.key, app.pluginHost.capabilityPolicy(pluginId, schedule.key).copy(enabled = true))
        assertFalse("能力开启不能代替后台许可", app.pluginHost.isBackgroundEnabled(pluginId))
    }
}
