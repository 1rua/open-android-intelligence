package com.openandroidintelligence.mobile.settings.capabilities

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
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
import com.openandroidintelligence.capability.RemoteScreenCaptureRegistry
import com.openandroidintelligence.capability.ScreenCapture
import com.openandroidintelligence.capability.ScreenCaptureSource
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
import com.openandroidintelligence.mobile.screen.probeAccessibilityBinding
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

        // 系统设置刚关闭但 onUnbind 尚未回调：必须立即不再报就绪，且回前台探测应将滞后连接态校准回 false。
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        assertFalse(accessibilityServiceReady(context))
        assertFalse(probeAccessibilityBinding(context))
        assertFalse(ScreenHostRegistry.accessibilityConnected.value)

        // 重新开启设置并连上后再解绑：立即回落为断连。
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            component.flattenToShortString(),
        )
        ScreenHostRegistry.setAccessibilityConnected(true)
        assertTrue(accessibilityServiceReady(context))
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

        // 2. 开启系统通知使用权但监听服务尚未连接：主动发起 rebind，同时对齐本地授权位解除 LOCAL_GRANT_REQUIRED，
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

        // 5. 监听服务未连时重新打开顶部开关：既触发 rebind，也恢复本地授权位（展示 NOTIFICATION_LISTENER_UNAVAILABLE）。
        bindingPort.setConnected(false)
        host.setCapabilityPolicy(NOTIFICATION_PLUGIN_ID, entry.key, CapabilityPolicy(enabled = true))
        assertEquals(2, bindingPort.rebindCalls)
        assertTrue(policyPort.snapshot().granted)
        assertEquals("PROVIDER_UNAVAILABLE", notificationInventory()["status"])
        assertEquals("NOTIFICATION_LISTENER_UNAVAILABLE", notificationInventory()["reason"])

        // 6. 服务恢复连接后立即回到 READY。
        bindingPort.setConnected(true)
        assertEquals("READY", notificationInventory()["status"])
    }

    @Test
    fun notificationPolicyDisableSyncHandlesMultiProviderAndPluginDisable() {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val policyPort = InMemoryPolicyPort(granted = false)
        val bindingPort = FakeNotificationBindingPort(systemGranted = true, connected = true)
        NotificationControlRegistry.install(policyPort, bindingPort)

        val audit = AndroidAuditStore(InMemoryAuditSink())
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
        val trust = DeveloperTrustMode()
        val host = ProductionPluginHost(
            context = context,
            grants = grants,
            trustMode = trust,
            nativeLoader = NativePluginLoader(trust),
            installationId = "install-notify-multi",
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

        val thirdParty = "org.example.notify.multi"
        host.installStore.installRoot.deleteRecursively()
        try {
            host.installStore.install(TestAlpPackages.compiledNotificationsPackage(pluginId = thirdParty))
            host.reload()
            host.enable(thirdParty, true)
            val builtInEntry = host.entries(NOTIFICATION_PLUGIN_ID).single()
            val thirdPartyEntry = host.entries(thirdParty).single()

            assertTrue(policyPort.snapshot().granted)

            // 底部显式关闭共享通知采集授权：同步关闭全部通知提供者的顶部总开关，避免另一插件在下次恢复时反向重开。
            host.syncNotificationCapabilitiesWithPolicyGrant(granted = false, targetPluginId = thirdParty)
            assertFalse(host.capabilityPolicy(thirdParty, thirdPartyEntry.key).enabled)
            assertFalse(host.capabilityPolicy(NOTIFICATION_PLUGIN_ID, builtInEntry.key).enabled)
            assertFalse(policyPort.snapshot().granted)
            assertFalse(host.reconcileNotificationPolicyGrant())
            assertFalse(policyPort.snapshot().granted)

            // 未授系统通知使用权时若底部开关先行将 policyPort 置为 true，宿主同步门禁会立即将其回退为 false。
            bindingPort.systemGranted = false
            policyPort.update(NotificationPolicyUpdate(expectedRevision = policyPort.snapshot().revision, granted = true))
            assertTrue(policyPort.snapshot().granted)
            host.syncNotificationCapabilitiesWithPolicyGrant(granted = true, targetPluginId = thirdParty)
            assertTrue(host.capabilityPolicy(thirdParty, thirdPartyEntry.key).enabled)
            assertFalse(policyPort.snapshot().granted)

            // 系统授权恢复后，停用并重新启用该插件本身：对称撤销并恢复 policy-engine 授权。
            bindingPort.systemGranted = true
            assertTrue(host.reconcileNotificationPolicyGrant())
            assertTrue(policyPort.snapshot().granted)
            host.enable(thirdParty, false)
            assertFalse(policyPort.snapshot().granted)
            assertFalse(host.reconcileNotificationPolicyGrant())
            host.enable(thirdParty, true)
            assertTrue(policyPort.snapshot().granted)
        } finally {
            host.installStore.installRoot.deleteRecursively()
            runCatching { host.reload() }
        }
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
            compose.runOnIdle { owner.resumeFromBackground() }

            compose.waitUntil(timeoutMillis = 5_000) { policyPort.snapshot().granted }
            compose.onNodeWithText("已就绪").performScrollTo().assertIsDisplayed()

            // 在底部「通知采集范围」显式关闭「允许读取通知」→ 顶部总开关同步关闭，后续 ON_RESUME 不自动重开。
            compose.onNodeWithText("允许读取通知").performScrollTo().performClick()
            compose.waitForIdle()
            assertFalse(policyPort.snapshot().granted)
            assertFalse(app.pluginHost.capabilityPolicy(thirdParty, entry.key).enabled)

            compose.runOnIdle { owner.resumeFromBackground() }
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
            Settings.Secure.putString(
                app.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ComponentName(app, accessibilityServiceClassName()).flattenToString(),
            )
            ScreenHostRegistry.setAccessibilityConnected(true)
            compose.runOnIdle { owner.resumeFromBackground() }

            compose.waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    compose.onNodeWithText("已就绪").performScrollTo().assertIsDisplayed()
                }.isSuccess
            }
            compose.onNodeWithText("系统权限或服务已就绪").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("SCREEN_CONNECTION_REQUIRED", substring = true).assertDoesNotExist()

            // 模拟用户在系统无障碍设置关闭服务后直接切回 App（此时 onUnbind 尚未送达）：ON_RESUME 应自动校准连接态为 false。
            Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
            compose.runOnIdle { owner.resumeFromBackground() }
            compose.waitUntil(timeoutMillis = 5_000) { !ScreenHostRegistry.accessibilityConnected.value }
            compose.onNodeWithText("系统权限或服务未就绪", substring = true).performScrollTo().assertIsDisplayed()
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
        fun resumeFromBackground() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
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

    /** 刻意沿用 [NotificationBindingPort.observeListenerConnected] 默认冷 flowOf 实现的端口替身。 */
    private class ColdNotificationBindingPort(
        @Volatile var systemGranted: Boolean,
        @Volatile var connected: Boolean,
    ) : NotificationBindingPort {
        override fun isListenerBound(): Boolean = systemGranted
        override fun isListenerConnected(): Boolean = connected
        override fun openSystemListenerSettings(context: Context): Boolean = true
    }

    private fun withTestBundledPlugins(
        app: OpenAndroidIntelligenceApplication,
        block: () -> Unit,
    ) {
        val field = ProductionPluginHost::class.java.getDeclaredField("builtInPackages").apply {
            isAccessible = true
        }
        val previousReader = field.get(app.pluginHost)
        app.pluginHost.installStore.installRoot.deleteRecursively()
        field.set(app.pluginHost, BuiltInPackageReader { TestAlpPackages.compiledBundledPackage(it) })
        app.pluginHost.reload()
        try {
            block()
        } finally {
            field.set(app.pluginHost, previousReader)
            app.pluginHost.installStore.installRoot.deleteRecursively()
            runCatching { app.pluginHost.reload() }
        }
    }

    @Test
    @Config(sdk = [34], application = OpenAndroidIntelligenceApplication::class)
    fun deviceCapabilitiesRefreshesColdNotificationListenerPortOnResume() {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val app = ApplicationProvider.getApplicationContext<OpenAndroidIntelligenceApplication>()
        val previousNotification = KernelPrimitiveRegistry.provider("kernel.notifications.read")
        val bindingPort = ColdNotificationBindingPort(systemGranted = false, connected = false)
        val policyPort = InMemoryPolicyPort(granted = false)
        NotificationControlRegistry.install(policyPort, bindingPort)
        KernelPrimitiveRegistry.register(object : KernelPrimitiveProvider {
            override val primitiveId = "kernel.notifications.read"
            override fun availability(): String? = when {
                !bindingPort.isListenerBound() -> "NOTIFICATION_ACCESS_REQUIRED"
                !policyPort.snapshot().granted -> "LOCAL_GRANT_REQUIRED"
                !bindingPort.isListenerConnected() -> "NOTIFICATION_LISTENER_UNAVAILABLE"
                else -> null
            }
            override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray = "{}".encodeToByteArray()
        })

        val owner = TestLifecycleOwner()
        val env = app.platformSettingsEnvironment()
        try {
            withTestBundledPlugins(app) {
                val entry = app.pluginHost.entries(NOTIFICATION_PLUGIN_ID).first()
                app.pluginHost.setCapabilityPolicy(NOTIFICATION_PLUGIN_ID, entry.key, CapabilityPolicy(enabled = true))
                compose.setContent {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        MaterialTheme {
                            Surface {
                                DeviceCapabilitiesScreen(
                                    environment = env,
                                    runtime = null,
                                    presentation = SettingsPresentation.SCREEN,
                                    onBack = {},
                                    onOpenCapability = { _, _ -> },
                                    onOpenPlugins = {},
                                )
                            }
                        }
                    }
                }
                compose.waitForIdle()
                val row = compose.onNodeWithTag("capability-entry:$NOTIFICATION_PLUGIN_ID:${entry.key}")
                row.performScrollTo().assertTextContains("需授权", substring = true)

                // 系统授权并在回前台 ON_RESUME 时恢复服务连接，冷流端口不得冻结在首次初始值
                bindingPort.systemGranted = true
                bindingPort.connected = true
                policyPort.update(NotificationPolicyUpdate(expectedRevision = policyPort.snapshot().revision, granted = true))
                compose.runOnIdle { owner.resumeFromBackground() }
                compose.waitUntil(timeoutMillis = 5_000) {
                    runCatching {
                        row.performScrollTo().assertTextContains("已开启", substring = true)
                    }.isSuccess
                }
            }
        } finally {
            previousNotification?.let(KernelPrimitiveRegistry::register)
        }
    }

    @Test
    @Config(sdk = [34], application = OpenAndroidIntelligenceApplication::class)
    fun deviceCapabilitiesReflectsScreenCaptureRevocationWhileStayingOnScreen() {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val app = ApplicationProvider.getApplicationContext<OpenAndroidIntelligenceApplication>()
        val sourceField = RemoteScreenCaptureRegistry::class.java.getDeclaredField("source").apply {
            isAccessible = true
        }
        val previousSource = sourceField.get(RemoteScreenCaptureRegistry)
        var captureAvailable = true
        val fakeCaptureSource = object : ScreenCaptureSource {
            override val isAvailable: Boolean get() = captureAvailable
            override fun createAuthorizationIntent(): Intent? = null
            override fun onAuthorizationResult(resultCode: Int, resultData: Intent?): Boolean = false
            override suspend fun capture(): ScreenCapture? = null
            override fun release() {
                captureAvailable = false
            }
        }
        sourceField.set(RemoteScreenCaptureRegistry, fakeCaptureSource)
        RemoteScreenCaptureRegistry.notifyAvailabilityChanged()

        val previousScreenCapture = KernelPrimitiveRegistry.provider("kernel.screen.capture")
        KernelPrimitiveRegistry.register(object : KernelPrimitiveProvider {
            override val primitiveId = "kernel.screen.capture"
            override fun availability(): String? = if (captureAvailable) null else "NOT_AUTHORIZED"
            override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray = "{}".encodeToByteArray()
        })

        val capturePlugin = "org.openandroidintelligence.screen-capture"
        val env = app.platformSettingsEnvironment()
        try {
            withTestBundledPlugins(app) {
                val entry = app.pluginHost.entries(capturePlugin).first()
                app.pluginHost.setCapabilityPolicy(capturePlugin, entry.key, CapabilityPolicy(enabled = true))
                compose.setContent {
                    MaterialTheme {
                        Surface {
                            DeviceCapabilitiesScreen(
                                environment = env,
                                runtime = null,
                                presentation = SettingsPresentation.SCREEN,
                                onBack = {},
                                onOpenCapability = { _, _ -> },
                                onOpenPlugins = {},
                            )
                        }
                    }
                }
                compose.waitForIdle()
                val row = compose.onNodeWithTag("capability-entry:$capturePlugin:${entry.key}")
                row.performScrollTo().assertTextContains("已开启", substring = true)

                // 停留在设备能力页面期间从通知栏停止投屏：通知可用态变更后应即时变为未授权
                compose.runOnIdle {
                    captureAvailable = false
                    RemoteScreenCaptureRegistry.notifyAvailabilityChanged()
                }
                compose.waitForIdle()
                row.performScrollTo().assertTextContains("需授权", substring = true)
            }
        } finally {
            sourceField.set(RemoteScreenCaptureRegistry, previousSource)
            RemoteScreenCaptureRegistry.notifyAvailabilityChanged()
            previousScreenCapture?.let(KernelPrimitiveRegistry::register)
        }
    }

    @Test
    @Config(sdk = [34], application = OpenAndroidIntelligenceApplication::class)
    fun capabilitySettingsUpdatesBackgroundToggleWhenExternalLocalStateChanges() {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val app = ApplicationProvider.getApplicationContext<OpenAndroidIntelligenceApplication>()
        val smsPlugin = "org.openandroidintelligence.sms"
        val env = app.platformSettingsEnvironment()
        withTestBundledPlugins(app) {
            val registration = app.pluginHost.entries(smsPlugin).first { it.id == "org.openandroidintelligence.sms.query" }
            try {
                compose.setContent {
                    MaterialTheme {
                        Surface {
                            CapabilitySettingsScreen(
                                pluginId = smsPlugin,
                                capability = registration.key,
                                environment = env,
                                runtime = null,
                                presentation = SettingsPresentation.SCREEN,
                                onBack = {},
                                onRequestScreenCaptureAuthorization = {},
                            )
                        }
                    }
                }
                compose.waitForIdle()
                val toggle = compose.onNodeWithTag("capability-background-toggle:$smsPlugin")
                toggle.performScrollTo().assertIsOff()

                // 外部直接写入 PairingGrants（未走 host.setBackgroundEnabled / hostRevision 未自增）时开关应立即更新
                compose.runOnIdle {
                    app.pairingGrants.updatePluginBackground(registration.identity, true)
                }
                compose.waitForIdle()
                toggle.performScrollTo().assertIsOn()
            } finally {
                app.pairingGrants.updatePluginBackground(registration.identity, false)
            }
        }
    }
}
