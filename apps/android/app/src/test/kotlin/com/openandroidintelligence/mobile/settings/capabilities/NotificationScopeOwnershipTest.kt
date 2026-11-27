package com.openandroidintelligence.mobile.settings.capabilities

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 通知采集范围属于**原语**，不属于某个插件 ID。
 *
 * `NotificationsReadPrimitive` 实际执行的是这份全安装共享的采集策略。只按官方插件判定会让
 * 第三方通知提供者的详情页失去编辑入口，用户无法配置真正生效的范围；反过来，官方推送设置
 * 也不应挂到第三方提供者身上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OpenAndroidIntelligenceApplication::class)
class NotificationScopeOwnershipTest {

    @get:Rule val compose = createComposeRule()

    private val thirdParty = "org.example.notify"
    private lateinit var app: OpenAndroidIntelligenceApplication

    @Before fun setUp() {
        assumeTrue(com.openandroidintelligence.mobile.BuildConfig.ALLOW_RUNTIME_PLUGINS)
        app = ApplicationProvider.getApplicationContext()
        app.pluginHost.installStore.installRoot.deleteRecursively()
        app.pluginHost.installStore.install(TestAlpPackages.signedPackage(
            id = thirdParty,
            name = "第三方通知插件",
            capabilities = notificationCapabilities(),
        ))
        app.pluginHost.reload()
        assumeTrue("第三方通知提供者必须已注册", app.pluginHost.entries(thirdParty).isNotEmpty())
    }

    @After fun tearDown() {
        if (::app.isInitialized) {
            app.pluginHost.installStore.installRoot.deleteRecursively()
            runCatching { app.pluginHost.reload() }
        }
    }

    private fun notificationCapabilities() = com.openandroidintelligence.gateway.schema.Json.of(mapOf(
        "provides" to listOf(mapOf(
            "id" to "org.example.notify.query",
            "version" to "1.0.0",
            "schema" to "schemas/org.example.notify.query.json",
        )),
        "depends" to emptyList<Any>(),
        "kernelPrimitives" to listOf(mapOf(
            "id" to "kernel.notifications.read",
            "version" to "1.0.0",
            "purpose" to "测试通知读取",
        )),
    )) as com.openandroidintelligence.gateway.schema.JsonValue.JObject

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

    @Test
    fun aThirdPartyNotificationProviderKeepsItsCollectionScopeControls() {
        val entry = app.pluginHost.entries(thirdParty).single()
        compose.setContent {
            MaterialTheme {
                Surface {
                    CapabilitySettingsScreen(
                        pluginId = thirdParty,
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

        // 采集策略由 notifications 原语共享，任何提供者都必须能在这里编辑。
        compose.onNodeWithText("通知采集范围").performScrollTo().assertExists()
    }

    @Test
    fun officialPushSettingsAreNotAttachedToAThirdPartyProvider() {
        val entry = app.pluginHost.entries(thirdParty).single()
        compose.setContent {
            MaterialTheme {
                Surface {
                    CapabilitySettingsScreen(
                        pluginId = thirdParty,
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

        // 推送配置属于官方能力：第三方提供者不应看到这一节。
        compose.onNodeWithText("主动推送").assertDoesNotExist()
    }
}
