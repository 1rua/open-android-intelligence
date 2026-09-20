package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.CapabilityProviderSelector
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.HostEnvelope
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.NativePluginLoader
import com.openandroidintelligence.kernel.PairingGrantBinding
import com.openandroidintelligence.kernel.PairingGrantCapabilities
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.kernel.PhoneLimits
import com.openandroidintelligence.kernel.PluginKernel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
            runtime = null,
            externalScope = testScope,
        )
        val state = viewModel.uiState.value

        assertNotNull(state)
        assertEquals(ThemePreference.SYSTEM, state.appearance.theme)
        assertFalse(state.isTrustModeEnabled)
        assertTrue(state.allowDeveloperTrustMode)
        assertFalse(state.isEmergencyStopped)
        assertEquals(0, state.emergencyStoppedCount)
        assertEquals(ConnectionPhase.Disconnected, state.connectionPhase)
        assertFalse(state.isGatewayConnected)
        assertNotNull(state.pairingGrants)
    }

    @Test
    fun themePreferencesUpdateCleanly() = runBlocking {
        val viewModel = SettingsViewModel(
            environment = environment,
            runtime = null,
            externalScope = testScope,
        )

        viewModel.setTheme(ThemePreference.DARK)
        assertEquals(ThemePreference.DARK, viewModel.uiState.value.appearance.theme)

        viewModel.setTheme(ThemePreference.LIGHT)
        assertEquals(ThemePreference.LIGHT, viewModel.uiState.value.appearance.theme)

        viewModel.setDynamicColor(false)
        assertFalse(viewModel.uiState.value.appearance.dynamicColor)

        viewModel.setReduceMotion(true)
        assertTrue(viewModel.uiState.value.appearance.reduceMotion)
    }

    @Test
    fun pairingGrantsUpdateAndPersist() = runBlocking {
        val viewModel = SettingsViewModel(
            environment = environment,
            runtime = null,
            externalScope = testScope,
        )

        viewModel.setSmsGrant(true)
        assertTrue(viewModel.uiState.value.isSmsGranted)

        viewModel.setScreenSelectionGrant(true)
        assertTrue(viewModel.uiState.value.isScreenSelectionGranted)

        viewModel.setNotificationsGrant(true)
        assertTrue(viewModel.uiState.value.isNotificationsGranted)

        viewModel.setSmsGrant(false)
        assertFalse(viewModel.uiState.value.isSmsGranted)
    }

    @Test
    fun developerTrustModeRequiresAcknowledgement() = runBlocking {
        val viewModel = SettingsViewModel(
            environment = environment,
            runtime = null,
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
    fun emergencyStopShutsDownKernelAndRevokesTrust() = runBlocking {
        val viewModel = SettingsViewModel(
            environment = environment,
            runtime = null,
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
    fun externalEmergencyStopUpdatesUiStateReactively() = runBlocking {
        val viewModel = SettingsViewModel(
            environment = environment,
            runtime = null,
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
            runtime = null,
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
    fun gatewayRuntimePhaseUpdatesUiStateReactively() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val runtime = GatewayRuntime(
            context = context,
            scope = testScope,
            pairingGrants = pairingGrants,
        )
        val viewModel = SettingsViewModel(
            environment = environment,
            runtime = runtime,
            externalScope = testScope,
        )

        assertEquals(ConnectionPhase.Disconnected, viewModel.uiState.value.connectionPhase)

        // Trigger a login with invalid URL to synchronously update connectionPhase to Failed
        runtime.login("not-a-valid-scheme", "user", "pwd".toCharArray())

        val phase = viewModel.uiState.value.connectionPhase
        assertTrue(phase is ConnectionPhase.Failed)
        assertEquals("AUTH_INVALID:url-scheme-required", (phase as ConnectionPhase.Failed).code)

        runtime.resetFailure()
        assertEquals(ConnectionPhase.Disconnected, viewModel.uiState.value.connectionPhase)
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
        )
        assertEquals(routes.size, routes.distinct().size)
    }
}
