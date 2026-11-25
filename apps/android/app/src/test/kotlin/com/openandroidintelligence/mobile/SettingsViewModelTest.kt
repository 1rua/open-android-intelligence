package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.mobile.LocalProfile
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.CapabilityProviderSelector
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.HostEnvelope
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.NativePluginLoader
import com.openandroidintelligence.kernel.PairingGrantBinding
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.kernel.PhoneLimits
import com.openandroidintelligence.kernel.PluginKernel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsViewModelTest {

    private lateinit var environment: PlatformSettingsEnvironment
    private lateinit var pairingGrants: PairingGrantStateHolder
    private lateinit var trustMode: DeveloperTrustMode
    private lateinit var kernel: PluginKernel
    private lateinit var appearance: AppearancePreferences
    private lateinit var auditSink: InMemoryAuditSink
    private lateinit var auditStore: AndroidAuditStore

    private val testScope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        trustMode = DeveloperTrustMode()
        auditSink = InMemoryAuditSink()
        auditStore = AndroidAuditStore(auditSink)
        pairingGrants = PairingGrantStateHolder(
            store = InMemoryPairingGrantStore(),
            audit = auditStore,
        )
        pairingGrants.bind(
            PairingGrantBinding(
                gatewayId = "https://gateway.example.com",
                accountId = "test-account",
                installationId = "test-install",
            ),
        )
        kernel = PluginKernel(
            hostEnvelope = HostEnvelope(
                primitives = setOf(
                    "org.openandroidintelligence.notifications.query@1.0.0",
                    "org.openandroidintelligence.sms.query@1.0.0",
                ),
            ),
            phoneLimits = PhoneLimits(primitives = emptySet()),
            runtimes = emptyMap(),
            audit = auditStore,
            trustMode = trustMode,
            nativeLoader = NativePluginLoader(trustMode),
            providerSelector = CapabilityProviderSelector(phoneDefaults = emptyMap()),
            grants = { pairingId -> pairingGrants.currentKernelGrant(pairingId) },
        )
        appearance = AppearancePreferences(context)
        environment = PlatformSettingsEnvironment(
            trustMode = trustMode,
            audit = auditStore,
            auditSink = auditSink,
            allowDeveloperTrustMode = true,
            kernel = kernel,
            pairingGrants = pairingGrants,
            appearance = appearance,
        )
    }

    @Test
    fun initialUiStateReflectsEnvironment() = runBlocking {
        val viewModel = SettingsViewModel(
            environment = environment,
            externalScope = testScope,
        )
        val state = viewModel.uiState.value

        assertNotNull(state)
        assertEquals(ThemePreference.SYSTEM, state.appearance.theme)
        assertFalse(state.isTrustModeEnabled)
        assertTrue(state.allowDeveloperTrustMode)
        assertFalse(state.isEmergencyStopped)
        assertEquals(0, state.emergencyStoppedCount)
        assertFalse(state.replyNotificationsWired)
        assertNotNull(state.pairingGrants)
    }

    @Test
    fun themePreferencesUpdateCleanly() = runBlocking {
        val viewModel = SettingsViewModel(
            environment = environment,
            externalScope = testScope,
        )

        viewModel.setTheme(ThemePreference.DARK)
        assertEquals(ThemePreference.DARK, viewModel.uiState.value.appearance.theme)

        viewModel.setTheme(ThemePreference.LIGHT)
        assertEquals(ThemePreference.LIGHT, viewModel.uiState.value.appearance.theme)

        viewModel.setDynamicColor(false)
        assertFalse(viewModel.uiState.value.appearance.dynamicColor)

        viewModel.setDynamicIconColor(false)
        assertFalse(viewModel.uiState.value.appearance.dynamicIconColor)

        viewModel.setDynamicIconColor(true)
        assertTrue(viewModel.uiState.value.appearance.dynamicIconColor)

        viewModel.setReduceMotion(true)
        assertTrue(viewModel.uiState.value.appearance.reduceMotion)

        viewModel.setStreamingHapticFeedback(true)
        assertTrue(viewModel.uiState.value.appearance.streamingHapticFeedback)
        viewModel.setStreamingHapticFeedback(false)
        assertFalse(viewModel.uiState.value.appearance.streamingHapticFeedback)
    }

    @Test
    fun streamingHapticFeedbackDefaultsOffAndPersistsWhenPreferencesAreRecreated() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val storage = context.getSharedPreferences("open_android_intelligence_appearance", 0)
        val key = "streaming_haptic_feedback"
        val hadPreviousValue = storage.contains(key)
        val previousValue = storage.getBoolean(key, false)
        assertTrue(storage.edit().remove(key).commit())

        try {
            val appearance = AppearancePreferences(context)
            assertFalse(appearance.settings.value.streamingHapticFeedback)

            appearance.setStreamingHapticFeedback(true)
            assertTrue(appearance.settings.value.streamingHapticFeedback)
            assertTrue(AppearancePreferences(context).settings.value.streamingHapticFeedback)
        } finally {
            val editor = storage.edit()
            if (hadPreviousValue) editor.putBoolean(key, previousValue) else editor.remove(key)
            assertTrue(editor.commit())
        }
    }

    @Test
    fun dynamicIconColorPreferencePersistsAcrossInstances() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val pm = context.packageManager
        val themedComponent = android.content.ComponentName(context, LauncherIconManager.ALIAS_THEMED)
        val classicComponent = android.content.ComponentName(context, LauncherIconManager.ALIAS_CLASSIC)

        val viewModel = SettingsViewModel(
            environment = environment,
            externalScope = testScope,
        )

        viewModel.setDynamicIconColor(false)
        assertFalse(viewModel.uiState.value.appearance.dynamicIconColor)
        assertEquals(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(themedComponent))
        assertEquals(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.getComponentEnabledSetting(classicComponent))

        val newAppearance = AppearancePreferences(context)
        assertFalse(newAppearance.settings.value.dynamicIconColor)

        viewModel.setDynamicIconColor(true)
        assertTrue(viewModel.uiState.value.appearance.dynamicIconColor)
        assertEquals(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.getComponentEnabledSetting(themedComponent))
        assertEquals(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(classicComponent))

        val updatedAppearance = AppearancePreferences(context)
        assertTrue(updatedAppearance.settings.value.dynamicIconColor)
    }

    @Test
    fun replyNotificationSwitchControlsAppPreferenceWithoutGrantingNotificationCollection() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notifications = ReplyNotificationSettings(context)
        val local = LocalSettingsRuntime(notifications, {}, {}, {})
        val viewModel = SettingsViewModel(environment, local, externalScope = testScope)
        val originalGrants = pairingGrants.state.value
        viewModel.setNotificationsGrant(false)
        assertFalse(notifications.enabled.value)
        assertFalse(viewModel.uiState.value.isNotificationsGranted)
        viewModel.setNotificationsGrant(true)
        assertTrue(notifications.enabled.value)
        assertTrue(viewModel.uiState.value.isNotificationsGranted)
        assertEquals("任务通知不授予读取其他应用通知的能力", originalGrants, pairingGrants.state.value)
        assertTrue(ReplyNotificationSettings(context).enabled.value)

        org.robolectric.Shadows.shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        viewModel.refreshReplyNotificationPermission()
        assertFalse(viewModel.uiState.value.isNotificationsGranted)
        assertTrue("系统拒绝权限不会丢失用户的开关偏好", notifications.enabled.value)
    }

    @Test
    fun developerTrustModeRequiresAcknowledgement() = runBlocking {
        val viewModel = SettingsViewModel(
            environment = environment,
            externalScope = testScope,
        )

        // Invalid acknowledgement text fails
        val rejected = viewModel.enableTrustMode("invalid-text")
        assertFalse(rejected)
        assertFalse(viewModel.uiState.value.isTrustModeEnabled)

        // Valid acknowledgement succeeds
        val accepted = viewModel.enableTrustMode(DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT)
        assertTrue(accepted)
        assertTrue(viewModel.uiState.value.isTrustModeEnabled)

        viewModel.disableTrustMode()
        assertFalse(viewModel.uiState.value.isTrustModeEnabled)
    }

    @Test
    fun emergencyStopReportsAnUnsavedTrustDisableWithoutReenablingExecution() = runBlocking {
        val failingTrust = DeveloperTrustMode(object : com.openandroidintelligence.kernel.TrustModePersistence {
            override fun load() = true
            override fun save(enabled: Boolean) { error("disk unavailable") }
        })
        val failingKernel = PluginKernel(
            hostEnvelope = HostEnvelope(emptySet()), phoneLimits = PhoneLimits(emptySet()), runtimes = emptyMap(),
            audit = auditStore, trustMode = failingTrust, nativeLoader = NativePluginLoader(failingTrust),
            providerSelector = CapabilityProviderSelector(emptyMap()), grants = { null },
        )
        val model = SettingsViewModel(environment.copy(trustMode = failingTrust, kernel = failingKernel), externalScope = testScope)
        model.emergencyStop()
        assertTrue(failingKernel.isEmergencyStopped())
        assertFalse(failingTrust.isEnabled())
        assertTrue(model.uiState.value.localError?.contains("未能保存") == true)
        assertEquals(model.uiState.value.localError, model.localError.value)
    }

    @Test
    fun localErrorFlowTracksAndClearsErrorsWithoutFullUiStateSubscription() = runBlocking {
        val viewModel = SettingsViewModel(
            environment = environment,
            localSettingsRuntime = null,
            externalScope = testScope,
        )
        assertNull(viewModel.localError.value)
        assertNull(viewModel.uiState.value.localError)

        assertFalse(viewModel.setNotificationsGrant(true))
        val emitted = viewModel.localError.value
        assertNotNull(emitted)
        assertEquals(emitted, viewModel.uiState.value.localError)

        viewModel.dismissLocalError()
        assertNull(viewModel.localError.value)
        assertNull(viewModel.uiState.value.localError)
    }

    @Test
    fun emergencyStopShutsDownKernelAndRevokesTrust() = runBlocking {
        val viewModel = SettingsViewModel(
            environment = environment,
            externalScope = testScope,
        )

        viewModel.enableTrustMode(DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT)
        assertTrue(viewModel.uiState.value.isTrustModeEnabled)

        val stoppedCount = viewModel.emergencyStop()
        assertEquals(0, stoppedCount)
        assertTrue(viewModel.uiState.value.isEmergencyStopped)
        assertFalse(viewModel.uiState.value.isTrustModeEnabled)
        assertTrue(kernel.isEmergencyStopped())
    }

    @Test
    fun emergencyStopCountSurvivesViewModelRecreation() = runBlocking {
        // Application 级持有者：设置面板（AnimatedVisibility 子树）销毁后依然存活
        val recorder = MutableStateFlow(3)

        // 面板关闭再打开：新的 ViewModel 必须从持有者恢复计数，而不是从 0 开始
        val recreated = SettingsViewModel(
            environment = environment.copy(emergencyStoppedCount = recorder),
            externalScope = testScope,
        )
        assertEquals(
            "熔断计数必须有比设置面板更长的持有者，面板重建不得归零",
            3,
            recreated.uiState.value.emergencyStoppedCount,
        )

        // 本界面再次触发熔断时，把真实的隔离数写回持有者
        val active = SettingsViewModel(
            environment = environment.copy(emergencyStoppedCount = recorder),
            externalScope = testScope,
        )
        active.enableTrustMode(DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT)
        active.emergencyStop()
        assertEquals("触发熔断后持有者必须收到新的隔离数", 0, recorder.value)
    }

    @Test
    fun externalEmergencyStopUpdatesUiStateReactively() = runBlocking {
        val viewModel = SettingsViewModel(
            environment = environment,
            externalScope = testScope,
        )

        viewModel.enableTrustMode(DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT)
        assertTrue(viewModel.uiState.value.isTrustModeEnabled)
        assertFalse(viewModel.uiState.value.isEmergencyStopped)

        // Trigger emergency stop externally from kernel
        kernel.emergencyStop("external-test-stop")

        assertTrue(kernel.isEmergencyStopped())
        assertTrue(viewModel.uiState.value.isEmergencyStopped)
        assertFalse(viewModel.uiState.value.isTrustModeEnabled)
    }

    @Test
    fun externalTrustModeDisableUpdatesUiStateReactively() = runBlocking {
        val viewModel = SettingsViewModel(
            environment = environment,
            externalScope = testScope,
        )

        viewModel.enableTrustMode(DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT)
        assertTrue(viewModel.uiState.value.isTrustModeEnabled)

        // Disable trust mode directly on the domain model
        trustMode.disable()

        assertFalse(trustMode.isEnabled())
        assertFalse(viewModel.uiState.value.isTrustModeEnabled)
    }

    @Test
    fun settingsRoutesAreDefinedAndDistinct() {
        val routes = listOf(
            SettingsRoutes.OVERVIEW,
            SettingsRoutes.APPEARANCE,
            SettingsRoutes.GATEWAY,
            SettingsRoutes.PAIRING,
            SettingsRoutes.SECURITY,
            SettingsRoutes.AUDIT_LOG,
            SettingsRoutes.APP_UPDATES,
        )
        assertEquals(routes.size, routes.distinct().size)
    }

}
