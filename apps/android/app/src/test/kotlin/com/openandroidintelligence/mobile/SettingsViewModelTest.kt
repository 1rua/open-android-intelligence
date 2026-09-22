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
    fun emergencyStopCountSurvivesViewModelRecreation() = runBlocking {
        // Application 级持有者：设置面板（AnimatedVisibility 子树）销毁后依然存活
        val recorder = MutableStateFlow(3)

        // 面板关闭再打开：新的 ViewModel 必须从持有者恢复计数，而不是从 0 开始
        val recreated = SettingsViewModel(
            environment = environment.copy(emergencyStoppedCount = recorder),
            runtime = null,
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
            runtime = null,
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

    // ------------------------------------------------------------------
    // 协商能力位透传（条目 3-15）：协商结果必须以三态如实抵达设置界面。
    // 走真实的回环 Gateway 协商 → 登录 → 落盘 → ViewModel 投影全链路。
    // ------------------------------------------------------------------

    @Test
    fun negotiatedConversationUiReachesTheSettingsUiState() {
        val gateway = LoopbackGatewayStub()
        try {
            gateway.respond(NEGOTIATE_PATH, NEGOTIATE_ALL_FIVE_BODY)
            gateway.respond(PASSWORD_PATH, PASSWORD_BODY)
            val runtime = runtimeFor(gateway)
            runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
            awaitConnected(runtime)

            val state = viewModelFor(runtime).uiState.value

            assertTrue(state.isGatewayConnected)
            // 闭集 8 键一个不少
            assertEquals(ConversationUiFeature.CLOSED_SET.size, state.conversationUi.size)
            // 网关同意且客户端声明过：已启用
            assertEquals(true, state.conversationUi["agent-command-catalog-v1"])
            assertEquals(true, state.conversationUi["message-batches-v1"])
            assertEquals(true, state.conversationUi["generation-cancel-v1"])
            // 客户端从未声明：双方未声明，而不是「不支持」
            assertNull("未声明的能力不得写成 false", state.conversationUi["newline-v1"])
            assertNull(state.conversationUi["conversation-mirror-v1"])
            assertNull(state.conversationUi["attachment-status-v1"])

            assertTrue(state.isMessageBatchesAvailable)
            assertTrue(state.isGenerationCancelAvailable)
            assertFalse("未声明即不可用：客户端没有实现它", state.isNewlineAvailable)
            assertFalse(state.isMirrorAvailable)
            assertFalse(state.isAttachmentStatusAvailable)
        } finally {
            gateway.closed()
        }
    }

    @Test
    fun negotiatedDeviceRequestsReachTheSettingsUiState() {
        val gateway = LoopbackGatewayStub()
        try {
            gateway.respond(NEGOTIATE_PATH, NEGOTIATE_ALL_FIVE_BODY)
            gateway.respond(PASSWORD_PATH, PASSWORD_BODY)
            val runtime = runtimeFor(gateway)
            runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
            awaitConnected(runtime)

            val state = viewModelFor(runtime).uiState.value

            assertEquals("risk-queue-v1", state.deviceRequestChannel)
        } finally {
            gateway.closed()
        }
    }

    @Test
    fun aGatewayWithoutDeviceRequestsReadsAsUnavailableInUiState() {
        val gateway = LoopbackGatewayStub()
        try {
            gateway.respond(NEGOTIATE_PATH, NEGOTIATE_WITHOUT_DEVICE_REQUESTS_BODY)
            gateway.respond(PASSWORD_PATH, PASSWORD_BODY)
            val runtime = runtimeFor(gateway)
            runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
            awaitConnected(runtime)

            val state = viewModelFor(runtime).uiState.value

            assertNull("网关未提供设备请求时不得编造通路", state.deviceRequestChannel)
        } finally {
            gateway.closed()
        }
    }

    @Test
    fun aCapabilityTheGatewayRefusedReadsAsUnsupportedInUiState() {
        val gateway = LoopbackGatewayStub()
        try {
            // 网关只同意 catalog 与 generation-cancel，明确没回 message-batches
            gateway.respond(NEGOTIATE_PATH, NEGOTIATE_REFUSED_BODY)
            gateway.respond(PASSWORD_PATH, PASSWORD_BODY)
            val runtime = runtimeFor(gateway)
            runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
            awaitConnected(runtime)

            val state = viewModelFor(runtime).uiState.value

            assertEquals(true, state.conversationUi["generation-cancel-v1"])
            assertEquals(
                "客户端声明了但网关没同意，必须如实标成不支持",
                false,
                state.conversationUi["message-batches-v1"],
            )
            assertFalse(state.isMessageBatchesAvailable)
            assertTrue(state.isGenerationCancelAvailable)
        } finally {
            gateway.closed()
        }
    }

    private fun runtimeFor(gateway: LoopbackGatewayStub): GatewayRuntime = GatewayRuntime(
        context = ApplicationProvider.getApplicationContext(),
        scope = testScope,
        pairingGrants = pairingGrants,
        credentialStore = InMemoryCredentialStore(),
        deviceKeys = InMemoryDeviceKeySource(),
    )

    private fun viewModelFor(runtime: GatewayRuntime): SettingsViewModel = SettingsViewModel(
        environment = environment,
        runtime = runtime,
        externalScope = testScope,
    )

    private fun awaitConnected(runtime: GatewayRuntime) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            if (runtime.phase.value is ConnectionPhase.Connected) return
            Thread.sleep(20L)
        }
        error("登录没有在超时前完成，最后阶段是 ${runtime.phase.value}")
    }

    private companion object {
        const val NEGOTIATE_PATH = "/open-android-intelligence/v2/negotiate"
        const val PASSWORD_PATH = "/open-android-intelligence/v2/sessions/password"

        val PASSWORD_BODY = """
            {"data":{"accountId":"acc_stub","deviceId":"dev_stub","sessionId":"sess_1",
            "accessToken":"token_1","refreshCredential":"refresh_1","pairingSummary":"stub pairing"}}
        """.trimIndent()

        /** 网关同意全部五项客户端声明过的能力。 */
        val NEGOTIATE_ALL_FIVE_BODY = """
            {"data":{"negotiationId":"neg_stub","protocol":{"major":2,"minor":0},
            "features":{"auth":["password","refresh"],"messages":"chat-v1",
            "attachments":"staged-sha256-v1","events":"sse-cursor-v1",
            "deviceRequests":"risk-queue-v1",
            "conversationUi":["agent-command-catalog-v1","agent-command-new-v1",
            "agent-approval-cards-v1","message-batches-v1","generation-cancel-v1"]},
            "limits":{"maxSingleAttachmentBytes":1048576,"maxMessageAttachmentBytes":4194304,
            "allowedMediaTypes":["image/png"],"attachmentTtlSeconds":3600,
            "eventRetentionSeconds":86400},
            "gatewayIdentity":{"deploymentId":"dep_stub","tlsSpkiSha256":"sha256:stub"}}}
        """.trimIndent()

        /** 网关只同意 catalog 与 generation-cancel；message-batches 被略去。 */
        val NEGOTIATE_REFUSED_BODY = """
            {"data":{"negotiationId":"neg_stub","protocol":{"major":2,"minor":0},
            "features":{"auth":["password","refresh"],"messages":"chat-v1",
            "attachments":"staged-sha256-v1","events":"sse-cursor-v1",
            "deviceRequests":"risk-queue-v1",
            "conversationUi":["agent-command-catalog-v1","generation-cancel-v1"]},
            "limits":{"maxSingleAttachmentBytes":1048576,"maxMessageAttachmentBytes":4194304,
            "allowedMediaTypes":["image/png"],"attachmentTtlSeconds":3600,
            "eventRetentionSeconds":86400},
            "gatewayIdentity":{"deploymentId":"dep_stub","tlsSpkiSha256":"sha256:stub"}}}
        """.trimIndent()

        /** 网关没有声明 deviceRequests 能力。 */
        val NEGOTIATE_WITHOUT_DEVICE_REQUESTS_BODY = """
            {"data":{"negotiationId":"neg_stub","protocol":{"major":2,"minor":0},
            "features":{"auth":["password","refresh"],"messages":"chat-v1",
            "attachments":"staged-sha256-v1","events":"sse-cursor-v1",
            "conversationUi":["agent-command-catalog-v1"]},
            "limits":{"maxSingleAttachmentBytes":1048576,"maxMessageAttachmentBytes":4194304,
            "allowedMediaTypes":["image/png"],"attachmentTtlSeconds":3600,
            "eventRetentionSeconds":86400},
            "gatewayIdentity":{"deploymentId":"dep_stub","tlsSpkiSha256":"sha256:stub"}}}
        """.trimIndent()
    }
}
