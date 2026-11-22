package com.openandroidintelligence.mobile.settings.capabilities

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.control.ScreenHostRegistry
import com.openandroidintelligence.core.model.NotificationDeliveryMode
import com.openandroidintelligence.core.model.NotificationFieldAccess
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.CapabilityPolicy
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.HostEnvelope
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.KernelPrimitiveProvider
import com.openandroidintelligence.kernel.KernelPrimitiveRegistry
import com.openandroidintelligence.kernel.LocalGrantContext
import com.openandroidintelligence.kernel.NativePluginLoader
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.kernel.PhoneLimits
import com.openandroidintelligence.kernel.PluginKernel
import com.openandroidintelligence.mobile.AppearancePreferences
import com.openandroidintelligence.mobile.BuildConfig
import com.openandroidintelligence.mobile.OpenAndroidIntelligenceApplication
import com.openandroidintelligence.mobile.PlatformSettingsEnvironment
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.plugins.BuiltInPackageReader
import com.openandroidintelligence.mobile.plugins.NOTIFICATION_PLUGIN_ID
import com.openandroidintelligence.mobile.plugins.ProductionPluginHost
import com.openandroidintelligence.mobile.plugins.TestAlpPackages
import com.openandroidintelligence.mobile.screen.accessibilityServiceClassName
import com.openandroidintelligence.mobile.screen.accessibilityServiceDisconnected
import com.openandroidintelligence.mobile.screen.accessibilityServiceReady
import com.openandroidintelligence.notification.control.NotificationBindingPort
import com.openandroidintelligence.notification.control.NotificationControlRegistry
import com.openandroidintelligence.notification.control.NotificationPolicyPort
import com.openandroidintelligence.notification.control.NotificationPolicySnapshot
import com.openandroidintelligence.notification.control.NotificationPolicyUpdate
import com.openandroidintelligence.notification.control.NotificationPolicyUpdateOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CapabilityGrantStatusChainTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private var previousNotificationProvider: KernelPrimitiveProvider? = null
    private val previousAccessibilityConnected = ScreenHostRegistry.accessibilityConnected.value
    private var previousPolicyPort: NotificationPolicyPort? = null
    private var previousBindingPort: NotificationBindingPort? = null

    @Before
    fun setUp() {
        previousNotificationProvider = KernelPrimitiveRegistry.provider("kernel.notifications.read")
        previousPolicyPort = NotificationControlRegistry.policyPort()
        previousBindingPort = NotificationControlRegistry.bindingPort()
        ScreenHostRegistry.setAccessibilityConnected(false)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
    }

    @After
    fun tearDown() {
        ScreenHostRegistry.setAccessibilityConnected(previousAccessibilityConnected)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        previousNotificationProvider?.let(KernelPrimitiveRegistry::register)
            ?: KernelPrimitiveRegistry.provider("kernel.notifications.read")?.let(KernelPrimitiveRegistry::unregister)
        val policy = previousPolicyPort
        val binding = previousBindingPort
        if (policy != null && binding != null) {
            NotificationControlRegistry.install(policy, binding)
        } else {
            NotificationControlRegistry.reset()
        }
    }

    @Test
    fun accessibilityReadyDistinguishesEnabledInSettingsFromLiveServiceBinding() {
        val component = ComponentName(context, accessibilityServiceClassName())

        assertFalse(accessibilityServiceReady(context))
        assertFalse(accessibilityServiceDisconnected(context))

        // 仅设置里开启但服务尚未连接：必须是「已开启但断连」，不得误判为已就绪。
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            component.flattenToShortString(),
        )
        assertFalse(accessibilityServiceReady(context))
        assertTrue(accessibilityServiceDisconnected(context))

        // 服务真实连接（onServiceConnected）后，即时切换为已就绪。
        ScreenHostRegistry.setAccessibilityConnected(true)
        assertTrue(accessibilityServiceReady(context))
        assertFalse(accessibilityServiceDisconnected(context))

        // 服务解绑后立即回落为断连。
        ScreenHostRegistry.setAccessibilityConnected(false)
        assertFalse(accessibilityServiceReady(context))
        assertTrue(accessibilityServiceDisconnected(context))
    }

    @Test
    fun notificationPolicyReconcilesWhenSystemAccessGrantedAndRespectsExplicitDisable() {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val policyPort = InMemoryPolicyPort(granted = false)
        val bindingPort = FakeNotificationBindingPort(systemGranted = false, connected = false)
        NotificationControlRegistry.install(policyPort, bindingPort)
        KernelPrimitiveRegistry.register(object : KernelPrimitiveProvider {
            override val primitiveId = "kernel.notifications.read"
            override fun availability(): String? {
                if (!bindingPort.isListenerBound()) return "NOTIFICATION_ACCESS_REQUIRED"
                if (!policyPort.snapshot().granted) return "LOCAL_GRANT_REQUIRED"
                if (!bindingPort.isListenerConnected()) return "NOTIFICATION_LISTENER_UNAVAILABLE"
                return null
            }
            override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray = "{}".encodeToByteArray()
        })

        val audit = AndroidAuditStore(InMemoryAuditSink())
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
        val trust = DeveloperTrustMode()
        val host = ProductionPluginHost(
            context = context,
            grants = grants,
            trustMode = trust,
            nativeLoader = NativePluginLoader(trust),
            installationId = "install-notify-chain",
            builtInPackages = BuiltInPackageReader { TestAlpPackages.compiledBundledPackage(it) },
        )
        val kernel = PluginKernel(
            hostEnvelope = HostEnvelope(host.hostCapabilities),
            phoneLimits = PhoneLimits(host.phoneCapabilities),
            runtimes = host.runtimes,
            audit = audit,
            trustMode = trust,
            nativeLoader = host.nativeLoader,
            providerSelector = host.selector,
            grants = { grants.currentKernelGrant(it) },
            mediate = host::mediate,
            authorizeInvocation = host::authorizeKernelInvocation,
        )
        host.attach(kernel)
        val entry = host.entries(NOTIFICATION_PLUGIN_ID).single()

        fun notificationInventory(): Map<String, Any?> =
            host.capabilitySnapshot().inventory.single { it["pluginId"] == NOTIFICATION_PLUGIN_ID }

        // 1. 未授系统通知使用权时：绝不自动打开 policy-engine 授权，状态为 NOTIFICATION_ACCESS_REQUIRED。
        assertFalse(host.reconcileNotificationPolicyGrant())
        assertFalse(policyPort.snapshot().granted)
        assertEquals("ANDROID_PERMISSION_REQUIRED", notificationInventory()["status"])
        assertEquals("NOTIFICATION_ACCESS_REQUIRED", notificationInventory()["reason"])

        // 2. 开启系统通知使用权但监听服务尚未连接：主动发起 rebind，并解除 LOCAL_GRANT_REQUIRED，
        //    如实呈现服务未连接（NOTIFICATION_LISTENER_UNAVAILABLE）。
        bindingPort.systemGranted = true
        assertTrue(host.reconcileNotificationPolicyGrant())
        assertEquals(1, bindingPort.rebindCalls)
        assertTrue(policyPort.snapshot().granted)
        assertEquals("PROVIDER_UNAVAILABLE", notificationInventory()["status"])
        assertEquals("NOTIFICATION_LISTENER_UNAVAILABLE", notificationInventory()["reason"])

        // 3. 监听服务连接就绪后：状态立即转为 READY。
        bindingPort.setConnected(true)
        assertEquals("READY", notificationInventory()["status"])
        assertEquals("", notificationInventory()["reason"])

        // 4. 用户显式关闭顶部通知读取开关：同步撤销 policy-engine 本地授权，后续再 reconcile 不会自动重开。
        host.setCapabilityPolicy(NOTIFICATION_PLUGIN_ID, entry.key, CapabilityPolicy(enabled = false))
        assertFalse(policyPort.snapshot().granted)
        assertFalse(host.reconcileNotificationPolicyGrant())
        assertFalse(policyPort.snapshot().granted)
        assertEquals("LOCAL_GRANT_REQUIRED", notificationInventory()["status"])
        assertEquals("LOCAL_CAPABILITY_DISABLED", notificationInventory()["reason"])

        // 5. 用户重新打开顶部开关：系统权限已就绪时同步恢复 policy-engine 授权并直接回到 READY。
        host.setCapabilityPolicy(NOTIFICATION_PLUGIN_ID, entry.key, CapabilityPolicy(enabled = true))
        assertTrue(policyPort.snapshot().granted)
        assertEquals("READY", notificationInventory()["status"])
    }

    @Test
    @Config(sdk = [34], application = OpenAndroidIntelligenceApplication::class)
    fun capabilitySettingsRefreshesOnResumeAndSyncsBottomConsentSwitch() {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val app = ApplicationProvider.getApplicationContext<OpenAndroidIntelligenceApplication>()
        val policyPort = InMemoryPolicyPort(granted = false)
        val bindingPort = FakeNotificationBindingPort(systemGranted = false, connected = false)
        NotificationControlRegistry.install(policyPort, bindingPort)
        KernelPrimitiveRegistry.register(object : KernelPrimitiveProvider {
            override val primitiveId = "kernel.notifications.read"
            override fun availability(): String? {
                if (!bindingPort.isListenerBound()) return "NOTIFICATION_ACCESS_REQUIRED"
                if (!policyPort.snapshot().granted) return "LOCAL_GRANT_REQUIRED"
                if (!bindingPort.isListenerConnected()) return "NOTIFICATION_LISTENER_UNAVAILABLE"
                return null
            }
            override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray = "{}".encodeToByteArray()
        })

        // 安装可验证的通知测试包，避免依赖发布构建暂存资产。
        app.pluginHost.installStore.installRoot.deleteRecursively()
        val thirdParty = "org.example.notify.resume"
        app.pluginHost.installStore.install(
            TestAlpPackages.compiledNotificationsPackage(pluginId = thirdParty),
        )
        app.pluginHost.reload()
        app.pluginHost.enable(thirdParty, true)
        val entry = app.pluginHost.entries(thirdParty).single()
        app.pluginHost.setCapabilityPolicy(thirdParty, entry.key, CapabilityPolicy(enabled = true))

        val owner = TestLifecycleOwner()
        val env = app.platformSettingsEnvironment()
        try {
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    MaterialTheme {
                        Surface {
                            CapabilitySettingsScreen(
                                pluginId = thirdParty,
                                capability = entry.key,
                                environment = env,
                                runtime = null,
                                presentation = SettingsPresentation.SCREEN,
                                onBack = {},
                                onRequestScreenCaptureAuthorization = {},
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            assertFalse(policyPort.snapshot().granted)

            // 模拟用户在系统设置里开启通知使用权并连上服务，随后回到 App（ON_RESUME）。
            bindingPort.systemGranted = true
            bindingPort.setConnected(true)
            compose.runOnIdle { owner.handleEvent(Lifecycle.Event.ON_RESUME) }

            compose.waitUntil(timeoutMillis = 5_000) { policyPort.snapshot().granted }
            compose.onNodeWithText("已就绪").performScrollTo().assertIsDisplayed()

            // 在底部「通知采集范围」显式关闭「允许读取通知」→ 顶部总开关同步关闭，后续 ON_RESUME 不自动重开。
            compose.onNodeWithText("允许读取通知").performScrollTo().performClick()
            compose.waitForIdle()
            assertFalse(policyPort.snapshot().granted)
            assertFalse(app.pluginHost.capabilityPolicy(thirdParty, entry.key).enabled)

            compose.runOnIdle { owner.handleEvent(Lifecycle.Event.ON_RESUME) }
            compose.waitForIdle()
            assertFalse(policyPort.snapshot().granted)
            assertFalse(app.pluginHost.capabilityPolicy(thirdParty, entry.key).enabled)
        } finally {
            app.pluginHost.installStore.installRoot.deleteRecursively()
            runCatching { app.pluginHost.reload() }
        }
    }

    @Test
    @Config(sdk = [34], application = OpenAndroidIntelligenceApplication::class)
    fun screenCapabilitySettingsRecoversFromDisconnectedAccessibilityOnConnectAndResume() {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val app = ApplicationProvider.getApplicationContext<OpenAndroidIntelligenceApplication>()
        val previousScreenControl = KernelPrimitiveRegistry.provider("kernel.screen.control")
        KernelPrimitiveRegistry.register(object : KernelPrimitiveProvider {
            override val primitiveId = "kernel.screen.control"
            override fun availability(): String? =
                if (ScreenHostRegistry.accessibilityConnected.value) null else "ACCESSIBILITY_SERVICE_UNAVAILABLE"
            override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray = "{}".encodeToByteArray()
        })

        app.pluginHost.installStore.installRoot.deleteRecursively()
        val thirdPartyScreen = "org.example.screen.control.resume"
        app.pluginHost.installStore.install(
            TestAlpPackages.signedPackage(
                id = thirdPartyScreen,
                name = "测试屏幕控制",
                capabilities = com.openandroidintelligence.gateway.schema.Json.of(
                    mapOf(
                        "provides" to listOf(
                            mapOf(
                                "id" to "org.example.screen.control",
                                "version" to "1.0.0",
                                "schema" to "schemas/org.example.screen.control.json",
                            ),
                        ),
                        "depends" to emptyList<Any>(),
                        "kernelPrimitives" to listOf(
                            mapOf(
                                "id" to "kernel.screen.control",
                                "version" to "1.0.0",
                                "purpose" to "测试屏幕控制就绪刷新",
                            ),
                        ),
                    ),
                ) as com.openandroidintelligence.gateway.schema.JsonValue.JObject,
            ),
        )
        app.pluginHost.reload()
        app.pluginHost.enable(thirdPartyScreen, true)
        val entry = app.pluginHost.entries(thirdPartyScreen).single()
        app.pluginHost.setCapabilityPolicy(thirdPartyScreen, entry.key, CapabilityPolicy(enabled = true))

        val owner = TestLifecycleOwner()
        val env = app.platformSettingsEnvironment()
        try {
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    MaterialTheme {
                        Surface {
                            CapabilitySettingsScreen(
                                pluginId = thirdPartyScreen,
                                capability = entry.key,
                                environment = env,
                                runtime = null,
                                presentation = SettingsPresentation.SCREEN,
                                onBack = {},
                                onRequestScreenCaptureAuthorization = {},
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText("SCREEN_CONNECTION_REQUIRED", substring = true).assertDoesNotExist()
            compose.onNodeWithText("系统权限或服务未就绪", substring = true).performScrollTo().assertIsDisplayed()

            // 模拟无障碍服务在系统设置开启后完成绑定并返回 App（ON_RESUME）。
            ScreenHostRegistry.setAccessibilityConnected(true)
            compose.runOnIdle { owner.handleEvent(Lifecycle.Event.ON_RESUME) }

            compose.waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    compose.onNodeWithText("已就绪").performScrollTo().assertIsDisplayed()
                }.isSuccess
            }
            compose.onNodeWithText("系统权限或服务已就绪").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("SCREEN_CONNECTION_REQUIRED", substring = true).assertDoesNotExist()
        } finally {
            app.pluginHost.installStore.installRoot.deleteRecursively()
            runCatching { app.pluginHost.reload() }
            previousScreenControl?.let(KernelPrimitiveRegistry::register)
        }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = registry
        fun handleEvent(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }

    private class InMemoryPolicyPort(granted: Boolean) : NotificationPolicyPort {
        private val state = MutableStateFlow(
            NotificationPolicySnapshot(
                installed = true,
                granted = granted,
                packageIds = emptyList(),
                fieldAccess = NotificationFieldAccess.METADATA,
                mode = NotificationDeliveryMode.ON_DEMAND,
                revision = 1u,
            ),
        )
        override fun snapshot(): NotificationPolicySnapshot = state.value
        override fun observe(): Flow<NotificationPolicySnapshot> = state
        override fun update(request: NotificationPolicyUpdate): NotificationPolicyUpdateOutcome {
            if (request.expectedRevision != state.value.revision) {
                return NotificationPolicyUpdateOutcome.Rejected("REVISION_STALE")
            }
            val next = state.value.copy(
                granted = request.granted ?: state.value.granted,
                packageIds = request.packageIds ?: state.value.packageIds,
                fieldAccess = request.fieldAccess ?: state.value.fieldAccess,
                mode = request.mode ?: state.value.mode,
                revision = state.value.revision + 1u,
            )
            state.value = next
            return NotificationPolicyUpdateOutcome.Accepted(next)
        }
    }

    private class FakeNotificationBindingPort(
        @Volatile var systemGranted: Boolean,
        connected: Boolean,
    ) : NotificationBindingPort {
        private val connectedFlow = MutableStateFlow(connected)
        var rebindCalls = 0

        fun setConnected(value: Boolean) {
            connectedFlow.value = value
        }

        override fun isListenerBound(): Boolean = systemGranted
        override fun isListenerConnected(): Boolean = connectedFlow.value
        override fun observeListenerConnected(): Flow<Boolean> = connectedFlow
        override fun requestListenerRebind(): Boolean {
            if (!systemGranted || connectedFlow.value) return false
            rebindCalls++
            return true
        }
        override fun openSystemListenerSettings(context: Context): Boolean = true
    }
}
