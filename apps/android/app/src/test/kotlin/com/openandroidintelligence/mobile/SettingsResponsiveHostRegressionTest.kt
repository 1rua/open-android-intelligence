package com.openandroidintelligence.mobile

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.encrypted.store.InMemoryOutboxPersistence
import com.openandroidintelligence.kernel.SmsScopeMode
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.push.DevicePushRuntime
import com.openandroidintelligence.mobile.push.EncryptedPushStateStore
import com.openandroidintelligence.mobile.push.PushSystemAccess
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 实际宿主的窗口断点回归；不把源码契约检查当作 Compose 行为证明。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1200dp-h900dp-160dpi")
class SettingsResponsiveHostRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @After fun close() { scope.cancel() }

    @Test fun workbenchUsesHostWidthAt840dpEvenThoughTheSettingsSlotIs320dp() {
        val runtime = disconnectedRuntime()
        val controller = WorkbenchController(
            scope, EmptyGateway(),
            object : AgentCommandCatalogRepository {
                override suspend fun get(gatewayId: String, languageCode: String) =
                    AgentCommandCatalog(CatalogVersion("test"), emptyList())
            },
            { ConversationScope("profile", "gateway", "account", "installation") },
        )
        var hostWidth by mutableStateOf(839.dp)
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(true)) {
                MaterialTheme {
                    Box(Modifier.size(hostWidth, 700.dp)) {
                        GatewayWorkbenchScreen(
                            runtime = runtime,
                            controller = controller,
                            gatewayLabel = "https://gateway.example.com",
                            onOpenSettings = {}, onPickCamera = {}, onPickGallery = {},
                            onPickDocument = {}, onVoiceInput = {},
                            settingsOpen = true,
                            reduceMotion = true,
                            settingsPanel = { presentation, _ ->
                                Box(Modifier.fillMaxSize().testTag("workbench-settings-slot")) {
                                    Text(presentation.name)
                                }
                            },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(SettingsPresentation.SCREEN.name).assertIsDisplayed()
        compose.onNodeWithTag("workbench-settings-slot").assertWidthIsEqualTo(839.dp)
        for (width in listOf(840.dp, 1000.dp)) {
            compose.runOnIdle { hostWidth = width }
            compose.onNodeWithText(SettingsPresentation.SIDEBAR.name).assertIsDisplayed()
            compose.onNodeWithTag("workbench-settings-slot").assertWidthIsEqualTo(320.dp)
        }
        compose.runOnIdle { hostWidth = 839.dp }
        compose.onNodeWithText(SettingsPresentation.SCREEN.name).assertIsDisplayed()
        compose.onNodeWithTag("workbench-settings-slot").assertWidthIsEqualTo(839.dp)
    }

    @Test fun loginSettingsKeepsTheRealSubpageAcrossCompactExpandedCompact() {
        assertRealSettingsNavigation(839.dp, listOf(840.dp, 839.dp), reduceMotion = false)
    }

    @Test fun loginSettingsKeepsTheRealSubpageAcrossExpandedCompactExpandedWithReducedMotion() {
        assertRealSettingsNavigation(840.dp, listOf(839.dp, 840.dp), reduceMotion = true)
    }

    @Test fun loginSettingsMovesTheSameUnsavedPushDraftAndNavigationInBothDirections() {
        assertPushDraftSurvivesResize(839.dp, listOf(840.dp, 839.dp, 840.dp), reduceMotion = false)
    }

    @Test fun loginSettingsMovesTheSameUnsavedPushDraftFromWideToCompactAndBack() {
        assertPushDraftSurvivesResize(840.dp, listOf(839.dp, 840.dp, 839.dp), reduceMotion = true)
    }

    private fun assertRealSettingsNavigation(initialWidth: Dp, widths: List<Dp>, reduceMotion: Boolean) {
        val environment = settingsEnvironment()
        val runtime = disconnectedRuntime()
        var width by mutableStateOf(initialWidth)
        var open by mutableStateOf(false)
        var dismissals = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion)) {
                MaterialTheme {
                    Box(Modifier.size(width, 700.dp)) {
                        PlatformSettingsDrawer(
                            open = open,
                            onDismiss = { dismissals++; open = false },
                            environment = environment,
                            runtime = runtime,
                            onRequestScreenCaptureAuthorization = {},
                        ) {
                            TextButton(onClick = { open = true }) { Text("打开设置") }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("打开设置").performClick()
        compose.onNodeWithText("外观与交互").performScrollTo().performClick()
        compose.onNodeWithText("外观与交互").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 120f) }
        val offset = scrollOffset()
        assertTrue("必须先产生真实滚动状态", offset > 0f)
        for (nextWidth in widths) {
            compose.runOnIdle { width = nextWidth }
            compose.onNodeWithText("外观与交互").assertIsDisplayed()
            assertEquals("真实设置子页的滚动位置应保留", offset, scrollOffset(), 1f)
            compose.runOnIdle { assertTrue(open); assertEquals(0, dismissals) }
        }
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.runOnIdle { assertTrue("子页返回只能回总览，不能被新宿主的返回处理提前关闭", open) }
        compose.onNodeWithText("外观与交互").assertExists()
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.onNodeWithText("打开设置").assertIsDisplayed()
        compose.runOnIdle { assertFalse(open); assertEquals(1, dismissals) }
        for (nextWidth in widths) {
            compose.runOnIdle { width = nextWidth }
            compose.onNodeWithText("打开设置").assertIsDisplayed()
            compose.onNodeWithText("外观与交互").assertDoesNotExist()
            compose.runOnIdle { assertFalse("关闭后跨断点不能重新打开", open) }
        }
    }

    private fun assertPushDraftSurvivesResize(initialWidth: Dp, widths: List<Dp>, reduceMotion: Boolean) {
        // 使用生产主动推送表单及 NavHost：未保存的保留时长、滚动和导航都在可移动内容内部。
        // 独立内存队列只隔离 Android KeyStore 和账号，不向真实 Core 或设备发送请求。
        val push = DevicePushRuntime(
            EncryptedPushStateStore(
                "resize-settings-test", InMemoryOutboxPersistence(), SecretKeySpec(ByteArray(32) { 1 }, "AES"),
            ),
            PushSystemAccess { true },
        )
        var width by mutableStateOf(initialWidth)
        var open by mutableStateOf(false)
        var disposals = 0
        lateinit var navigation: NavHostController
        lateinit var focusManager: FocusManager
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion)) {
                MaterialTheme {
                    Box(Modifier.size(width, 700.dp)) {
                        PlatformSettingsDrawer(
                            open = open,
                            onDismiss = { open = false },
                            settingsContent = { presentation, onBack ->
                                val navController = rememberNavController()
                                val focus = LocalFocusManager.current
                                SideEffect { navigation = navController; focusManager = focus }
                                DisposableEffect(Unit) { onDispose { disposals++ } }
                                NavHost(navController, startDestination = "overview") {
                                    composable("overview") {
                                        Column {
                                            TextButton(onClick = { navController.navigate("push") }) { Text("进入主动推送") }
                                            TextButton(onClick = onBack) { Text("关闭设置") }
                                        }
                                    }
                                    composable("push") {
                                        DevicePushSubScreen(
                                            runtime = push, endpoints = emptyList(), onRefreshEndpoints = {},
                                            onBack = { navController.popBackStack() }, presentation = presentation,
                                        )
                                    }
                                }
                            },
                        ) {
                            TextButton(onClick = { open = true }) { Text("打开设置") }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("打开设置").performClick()
        compose.onNodeWithText("进入主动推送").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("push-sms-mode-keywords").fetchSemanticsNodes().isNotEmpty() }
        // 用短信关键词草稿验证：它同样只在保存时提交，且不会因输入焦点自动滚动。
        compose.onNodeWithTag("push-sms-mode-keywords").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("push-sms-keywords").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("push-sms-keywords").performScrollTo().performTextReplacement("跨断点保留的草稿")
        val currentOffset = scrollOffset()
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 120f - currentOffset) }
        val originalNavigation = compose.runOnIdle { navigation }
        val offset = scrollOffset()
        assertTrue("编辑前必须滚到下方生产表单", offset > 0f)
        assertEquals("未提交前不能保存范围草稿", SmsScopeMode.ALL, push.snapshot().configuration.sms.scope.mode)
        for (nextWidth in widths) {
            compose.runOnIdle { width = nextWidth }
            compose.onNodeWithTag("push-sms-enabled").assertExists()
            // 不调用 performScrollTo，防止测试自己恢复丢失的滚动位置。
            compose.onNodeWithTag("push-sms-keywords").assertTextContains("跨断点保留的草稿")
            assertEquals("跨断点必须保持同一滚动位置", offset, scrollOffset(), 1f)
            compose.runOnIdle {
                assertSame("必须保留同一个导航控制器", originalNavigation, navigation)
                assertEquals("push", navigation.currentDestination?.route)
                assertEquals("设置内容不能被销毁重建", 0, disposals)
                assertTrue("打开状态必须保留", open)
            }
            assertEquals("缩放不能偷偷提交草稿", SmsScopeMode.ALL, push.snapshot().configuration.sms.scope.mode)
        }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("进入主动推送").assertIsDisplayed()
        compose.runOnIdle { assertTrue("子页返回不能关闭面板", open) }
        compose.onNodeWithText("关闭设置").performClick()
        compose.onNodeWithText("打开设置").assertIsDisplayed()
        for (nextWidth in widths) {
            compose.runOnIdle { width = nextWidth }
            compose.onNodeWithText("打开设置").assertIsDisplayed()
            compose.onNodeWithText("短信与通知主动推送").assertDoesNotExist()
            compose.runOnIdle { assertFalse("已关闭设置不能因跨断点重新打开", open) }
        }
    }

    private fun scrollOffset(): Float = compose.onNode(hasScrollAction()).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun disconnectedRuntime() = GatewayRuntime(
        context = context, scope = scope,
        pairingGrants = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore(InMemoryAuditSink())),
        credentialStore = InMemoryCredentialStore(), deviceKeys = InMemoryDeviceKeySource(),
        localDocumentKeyProvider = TestDocumentKeys,
    )

    private fun settingsEnvironment(): PlatformSettingsEnvironment {
        val trustMode = DeveloperTrustMode()
        val audit = AndroidAuditStore(InMemoryAuditSink())
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
        return PlatformSettingsEnvironment(
            trustMode = trustMode, audit = audit, auditSink = InMemoryAuditSink(), allowDeveloperTrustMode = true,
            kernel = PluginKernel(
                hostEnvelope = HostEnvelope(primitives = emptySet()), phoneLimits = PhoneLimits(primitives = emptySet()),
                runtimes = emptyMap(), audit = audit, trustMode = trustMode, nativeLoader = NativePluginLoader(trustMode),
                providerSelector = CapabilityProviderSelector(phoneDefaults = emptyMap()),
                grants = { grants.currentKernelGrant(it) },
            ),
            pairingGrants = grants, appearance = AppearancePreferences(context),
        )
    }

    private class EmptyGateway : ConversationRepository {
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(emptyList(), null)
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) = Conversation(ConversationId("test"), "测试", 0)
        override suspend fun submitMessage(message: OutgoingMessage) = MessageAcceptance("test", message.clientMessageId.value)
        override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId)
        override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
