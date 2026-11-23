package com.openandroidintelligence.mobile

import android.app.Application
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.motion.AppSharedTransitionHost
import com.openandroidintelligence.conversation.motion.AppTransitions
import com.openandroidintelligence.conversation.motion.LocalSharedTransitionScope
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.motion.ProvideSharedVisibilityScope
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.ports.AgentCommandCatalogRepository
import com.openandroidintelligence.conversation.ports.BatchAcceptance
import com.openandroidintelligence.conversation.ports.CancelGenerationOutcome
import com.openandroidintelligence.conversation.ports.CancelGenerationResult
import com.openandroidintelligence.conversation.ports.Conversation
import com.openandroidintelligence.conversation.ports.ConversationPage
import com.openandroidintelligence.conversation.ports.ConversationRepository
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.MessageAcceptance
import com.openandroidintelligence.conversation.ports.MessageBatch
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.ports.TimelinePage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.OpenAndroidIntelligenceTheme
import com.openandroidintelligence.conversation.workbench.WorkbenchScreen
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
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import com.openandroidintelligence.ui.resources.R as UiR
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 根导航、登录形变按钮、设置分层视差转场与预测性返回兼容集成回归。
 *
 * 同时验证标准态与 `reduceMotion = true` / `durationScale = 0f` 降级路径，
 * 确保分层视差遮罩与手势预览不破坏既有路由栈、语义角色与无障碍访问。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h900dp-160dpi")
class ExpressiveMotionNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        testScope.cancel()
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Test
    fun rootSharedTransitionHostProvidesScopeAcrossLoginAndWorkbenchTransitions() {
        var onWorkbench by mutableStateOf(false)
        var capturedScope: Any? = null

        compose.setContent {
            OpenAndroidIntelligenceTheme(darkTheme = true, reduceMotion = false) {
                AppSharedTransitionHost(Modifier.fillMaxSize()) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        AnimatedContent(
                            targetState = onWorkbench,
                            transitionSpec = {
                                AppTransitions.enter(false, forward = targetState) togetherWith
                                    AppTransitions.exit(false, forward = targetState)
                            },
                            label = "root-surface-test",
                        ) { workbench ->
                            ProvideSharedVisibilityScope {
                                capturedScope = LocalSharedTransitionScope.current
                                if (!workbench) {
                                    RemoteLoginScreen(
                                        busy = false,
                                        connected = false,
                                        signedIn = false,
                                        initialOrigin = "https://remote.example",
                                        error = null,
                                        onOpenSettings = {},
                                        onLogin = { _, _, _ -> },
                                        onScanPairing = {},
                                        onOAuthLogin = {},
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize().testTag("workbench-root-placeholder")) {
                                        Text("WorkbenchReady")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        compose.waitForIdle()
        assertNotNull(capturedScope)
        compose.onNodeWithTag("remote-login-card").assertIsDisplayed()

        compose.runOnIdle { onWorkbench = true }
        compose.waitForIdle()
        compose.onNodeWithTag("workbench-root-placeholder").assertIsDisplayed()

        compose.runOnIdle { onWorkbench = false }
        compose.waitForIdle()
        compose.onNodeWithTag("remote-login-card").assertIsDisplayed()
    }

    @Test
    fun remoteLoginSharedBoundsAndMorphingButtonsWorkInStandardMode() {
        assertRemoteLoginMotion(MotionPolicy(reduceMotion = false, durationScale = 1f))
    }

    @Test
    fun remoteLoginSharedBoundsAndMorphingButtonsDegradeWithReduceMotion() {
        assertRemoteLoginMotion(MotionPolicy(reduceMotion = true, durationScale = 1f))
    }

    @Test
    fun remoteLoginSharedBoundsAndMorphingButtonsDegradeWithZeroDurationScale() {
        assertRemoteLoginMotion(MotionPolicy(reduceMotion = false, durationScale = 0f))
    }

    @Test
    fun settingsNavHostUsesOpaqueHorizontalSlideForFirstLevelAndSecondaryPagesStandard() {
        assertSettingsNavHostTransitions(reduceMotion = false)
    }

    @Test
    fun settingsNavHostUsesOpaqueHorizontalSlideForFirstLevelAndSecondaryPagesReduced() {
        assertSettingsNavHostTransitions(reduceMotion = true)
    }

    @Test
    fun settingsNavHostSubpagePredictiveBackPreviewsParallaxAndCommitsStandard() {
        assertSettingsSubpagePredictiveBack()
    }

    @Config(qualifiers = "w1200dp-h900dp-160dpi")
    @Test
    fun workbenchAndSettingsHostSupportsSubpageBackParallaxDismissAndWideSidebar() {
        assertWorkbenchAndSettingsHostChain()
    }

    @Test
    fun settingsNavHostPredictiveBackPreviewsReboundsAndCommitsStandard() {
        assertSettingsPredictiveBack(reduceMotion = false)
    }

    @Test
    fun settingsNavHostPredictiveBackSkipsSlideWhenReducedMotionEnabled() {
        assertSettingsPredictiveBack(reduceMotion = true)
    }

    @Test
    fun settingsNavHostWithInternalSharedHostPreservesSubpageAndScrollAcross840dpBreakpoint() {
        val environment = settingsEnvironment()
        var width by mutableStateOf(839.dp)
        var open by mutableStateOf(false)
        var dismissals = 0

        compose.setContent {
            OpenAndroidIntelligenceTheme(reduceMotion = false) {
                Box(Modifier.size(width, 700.dp)) {
                    PlatformSettingsDrawer(
                        open = open,
                        onDismiss = {
                            dismissals++
                            open = false
                        },
                        environment = environment,
                        onRequestScreenCaptureAuthorization = {},
                    ) {
                        TextButton(onClick = { open = true }) { Text("OpenSettingsTrigger") }
                    }
                }
            }
        }

        compose.onNodeWithText("OpenSettingsTrigger").performClick()
        compose.onNodeWithText("外观与交互").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasScrollAction())
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 120f) }
        val initialOffset = compose.onNode(androidx.compose.ui.test.hasScrollAction())
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(initialOffset > 0f)

        listOf(840.dp, 839.dp, 960.dp).forEach { nextWidth ->
            compose.runOnIdle { width = nextWidth }
            compose.waitForIdle()
            compose.onNodeWithText("外观与交互").assertIsDisplayed()
            val currentOffset = compose.onNode(androidx.compose.ui.test.hasScrollAction())
                .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertEquals("跨 840dp 断点移动 SettingsNavHost 后滚动位置不得丢失", initialOffset, currentOffset, 1f)
            assertEquals(0, dismissals)
        }
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    private fun assertRemoteLoginMotion(policy: MotionPolicy) {
        var busy by mutableStateOf(false)
        var oauthClicks = 0
        var loginClicks = 0

        compose.setContent {
            OpenAndroidIntelligenceTheme(darkTheme = true, reduceMotion = policy.reduceMotion) {
                CompositionLocalProvider(LocalMotionPolicy provides policy) {
                    AppSharedTransitionHost(Modifier.fillMaxSize()) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                            RemoteLoginScreen(
                                busy = busy,
                                connected = false,
                                signedIn = false,
                                initialOrigin = "https://remote.example",
                                error = null,
                                onOpenSettings = {},
                                onLogin = { _, _, _ -> loginClicks++ },
                                onScanPairing = {},
                                onOAuthLogin = { oauthClicks++ },
                            )
                        }
                    }
                }
            }
        }

        // 官方模式：GitHub 主按钮复用 M3MorphingButton，保留 Button 语义与 >= 48dp 热区
        val githubLabel = context.getString(UiR.string.remote_login_github)
        compose.onNodeWithTag("remote-official-login").assertIsDisplayed()
        compose.onNodeWithText(githubLabel)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(hasClickAction())
            .assertHeightIsAtLeast(Dimensions.MinimumTouchTarget)
            .assertIsEnabled()
            .performClick()
        assertEquals(1, oauthClicks)

        // 切换到自部署模式：AnimatedContent + ProvideSharedVisibilityScope + m3SharedBounds
        compose.onNodeWithText(context.getString(UiR.string.remote_login_use_self_hosted)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("remote-self-hosted-login").assertIsDisplayed()
        compose.onNodeWithTag("remote-official-login").assertDoesNotExist()

        val serverLoginLabel = context.getString(UiR.string.remote_login_server)
        compose.onNodeWithText(serverLoginLabel)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(Dimensions.MinimumTouchTarget)
            .assertIsNotEnabled()

        compose.onNodeWithText(context.getString(UiR.string.remote_service_address))
            .performTextReplacement("https://remote.example")
        compose.onNodeWithText(context.getString(UiR.string.remote_login_account))
            .performTextInput("alice")
        compose.onNodeWithText(context.getString(UiR.string.remote_login_credential))
            .performTextInput("secret")

        compose.onNodeWithText(serverLoginLabel).assertIsEnabled().performClick()
        assertEquals(1, loginClicks)

        // busy=true 时按钮形变为 connecting 态且阻断点击
        compose.runOnIdle { busy = true }
        compose.waitForIdle()
        compose.onNode(
            hasText(context.getString(UiR.string.conn_status_connecting)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
        ).assertIsNotEnabled()

        // 恢复 busy=false 并切换回官方模式
        compose.runOnIdle { busy = false }
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(UiR.string.remote_login_use_official)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("remote-official-login").assertIsDisplayed()
        compose.onNodeWithTag("remote-self-hosted-login").assertDoesNotExist()
    }

    private fun assertSettingsNavHostTransitions(reduceMotion: Boolean) {
        var closes = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        val environment = settingsEnvironment()

        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            OpenAndroidIntelligenceTheme(reduceMotion = reduceMotion) {
                Surface(Modifier.fillMaxSize()) {
                    SettingsScreen(
                        environment = environment,
                        onBack = { closes++ },
                        presentation = SettingsPresentation.SCREEN,
                    )
                }
            }
        }

        // 一级分类：总览 -> 外观与交互（统一不透明水平滑入，减少动态下零水平位移）
        val appearanceEntry = compose.onNodeWithText("外观与交互").performScrollTo()
        compose.mainClock.autoAdvance = false
        appearanceEntry.performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(MotionSpecs.ReducedMotionCrossfadeDuration.toLong() / 4)

        val appearanceSection = compose.onNodeWithText(context.getString(UiR.string.settings_section_theme_mode))
        val firstLevelEnteringX = appearanceSection.fetchSemanticsNode().positionInRoot.x
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(UiR.string.settings_title_appearance)).assertIsDisplayed()
        val firstLevelSettledX = appearanceSection.fetchSemanticsNode().positionInRoot.x
        if (reduceMotion) {
            assertEquals("减少动态下一级分类页进入不应产生水平位移", firstLevelSettledX, firstLevelEnteringX, 1f)
        } else {
            assertTrue(
                "标准模式下一级分类页应从右侧水平滑入: entering=$firstLevelEnteringX, settled=$firstLevelSettledX",
                firstLevelEnteringX > firstLevelSettledX,
            )
        }

        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("设置").assertIsDisplayed()
        assertEquals(0, closes)

        // 二级子页（设备能力与授权 -> 插件管理）：同样采用不透明水平滑入，减少动态下零水平位移
        compose.onNodeWithText("设备能力与授权").performScrollTo().performClick()
        compose.waitForIdle()
        val pluginsEntry = compose.onNodeWithTag("capability-entry:plugins")
        pluginsEntry.performScrollTo()

        compose.mainClock.autoAdvance = false
        pluginsEntry.performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(MotionSpecs.ReducedMotionCrossfadeDuration.toLong() / 4)

        val secondaryTitle = compose.onNodeWithText("未安装任何插件")
        val enteringLeft = secondaryTitle.fetchSemanticsNode().positionInRoot.x
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val settledLeft = secondaryTitle.fetchSemanticsNode().positionInRoot.x

        if (reduceMotion) {
            assertEquals("减少动态下二级页进入不应产生水平位移", settledLeft, enteringLeft, 1f)
        } else {
            assertTrue("标准模式下二级页应从右侧水平滑入: entering=$enteringLeft, settled=$settledLeft", enteringLeft > settledLeft)
        }

        // 逐级返回至设置根页再关闭
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("设备能力与授权").assertIsDisplayed()
        assertEquals(0, closes)

        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("设置").assertIsDisplayed()
        assertEquals(0, closes)

        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, closes)
    }

    private fun assertSettingsSubpagePredictiveBack() {
        var closes = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        val environment = settingsEnvironment()

        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            OpenAndroidIntelligenceTheme(reduceMotion = false) {
                Surface(Modifier.fillMaxSize()) {
                    SettingsScreen(
                        environment = environment,
                        onBack = { closes++ },
                        presentation = SettingsPresentation.SCREEN,
                    )
                }
            }
        }

        val settledOverviewRowX = compose.onNode(hasText("连接与账号") and hasClickAction())
            .fetchSemanticsNode().positionInRoot.x

        compose.onNodeWithText("外观与交互").performScrollTo().performClick()
        compose.waitForIdle()

        val themeModeLabel = context.getString(UiR.string.settings_section_theme_mode)
        val settledForegroundX = compose.onNodeWithText(themeModeLabel).fetchSemanticsNode().positionInRoot.x
        compose.onNodeWithText("连接与账号").assertDoesNotExist()

        // 派发预测性返回 0f -> 0.4f -> 0.7f：前景右移的同时底层上一级「设置」立即可见并从负 X 向 0 平滑移动
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 200f, 0.4f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()

        val overviewRowAt04 = compose.onNode(hasText("连接与账号") and hasClickAction())
        overviewRowAt04.assertExists()
        val fgAt04 = compose.onNodeWithText(themeModeLabel).fetchSemanticsNode().positionInRoot.x
        val bgAt04 = overviewRowAt04.fetchSemanticsNode().positionInRoot.x
        assertTrue("拖拽至 0.4 时前景应显著右移: settled=$settledForegroundX, fg04=$fgAt04", fgAt04 > settledForegroundX + 40f)
        assertTrue("拖拽至 0.4 时底层总览页应位于负视差位: bg04=$bgAt04, settled=$settledOverviewRowX", bgAt04 < 0f && bgAt04 < settledOverviewRowX)

        compose.runOnIdle {
            dispatcher.dispatchOnBackProgressed(BackEventCompat(140f, 200f, 0.7f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()

        val fgAt07 = compose.onNodeWithText(themeModeLabel).fetchSemanticsNode().positionInRoot.x
        val bgAt07 = compose.onNode(hasText("连接与账号") and hasClickAction()).fetchSemanticsNode().positionInRoot.x
        assertTrue("拖拽推进至 0.7 时前景应继续右移: fg04=$fgAt04, fg07=$fgAt07", fgAt07 > fgAt04 + 20f)
        assertTrue("拖拽推进至 0.7 时底层总览页应继续向 0 复原: bg04=$bgAt04, bg07=$bgAt07, settled=$settledOverviewRowX", bgAt07 > bgAt04 + 5f && bgAt07 < settledOverviewRowX)

        // 取消手势：复原「外观与交互」且不触发关闭
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals(0, closes)
        compose.onNodeWithText(themeModeLabel).assertIsDisplayed()
        assertEquals(settledForegroundX, compose.onNodeWithText(themeModeLabel).fetchSemanticsNode().positionInRoot.x, 1f)
        compose.onNodeWithText("连接与账号").assertDoesNotExist()

        // 再次拖出并提交：从当前进度无回弹落位「设置」
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 200f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        val fgBeforeCommit = compose.onNodeWithText(themeModeLabel).fetchSemanticsNode().positionInRoot.x
        val bgBeforeCommit = compose.onNode(hasText("连接与账号") and hasClickAction()).fetchSemanticsNode().positionInRoot.x

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.mainClock.advanceTimeByFrame()
        val fgAfterCommit = compose.onAllNodes(hasText(themeModeLabel)).fetchSemanticsNodes().firstOrNull()?.positionInRoot?.x
        if (fgAfterCommit != null) {
            assertTrue("提交返回后前景不得回弹: before=$fgBeforeCommit, after=$fgAfterCommit", fgAfterCommit >= fgBeforeCommit - 1f)
        }
        val bgAfterCommit = compose.onNode(hasText("连接与账号") and hasClickAction()).fetchSemanticsNode().positionInRoot.x
        assertTrue("提交返回后底层总览页不得反向回弹: before=$bgBeforeCommit, after=$bgAfterCommit", bgAfterCommit >= bgBeforeCommit - 1f)

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0, closes)
        compose.onNodeWithText("连接与账号").assertIsDisplayed()
        compose.onNodeWithText(themeModeLabel).assertDoesNotExist()
        assertEquals(
            settledOverviewRowX,
            compose.onNode(hasText("连接与账号") and hasClickAction()).fetchSemanticsNode().positionInRoot.x,
            1f,
        )
    }

    private fun assertWorkbenchAndSettingsHostChain() {
        val environment = settingsEnvironment()
        val controller = WorkbenchController(
            testScope,
            EmptyGateway(),
            object : AgentCommandCatalogRepository {
                override suspend fun get(gatewayId: String, languageCode: String) =
                    AgentCommandCatalog(CatalogVersion("test"), emptyList())
            },
            { ConversationScope("profile", "gateway", "account", "installation") },
        )
        var hostWidth by mutableStateOf(411.dp)
        var settingsOpen by mutableStateOf(true)
        lateinit var dispatcher: OnBackPressedDispatcher

        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            OpenAndroidIntelligenceTheme(reduceMotion = false) {
                Box(Modifier.size(hostWidth, 900.dp)) {
                    WorkbenchSettingsHost { presentation ->
                        WorkbenchScreen(
                            controller = controller,
                            gatewayLabel = "https://gateway.example.com",
                            onOpenSettings = { settingsOpen = true },
                            onLogout = {},
                            onPickCamera = {},
                            onPickGallery = {},
                            onPickDocument = {},
                            onVoiceInput = {},
                            header = {
                                Box(Modifier.testTag("workbench-parallax-marker")) {
                                    Text("WorkbenchHeader")
                                }
                            },
                            settingsOpen = settingsOpen,
                            onSettingsOpenChange = { settingsOpen = it },
                            settingsPanel = { close ->
                                SettingsScreen(
                                    environment = environment,
                                    onBack = close,
                                    presentation = presentation,
                                    onRequestScreenCaptureAuthorization = {},
                                    hostOpen = settingsOpen,
                                )
                            },
                        )
                    }
                }
            }
        }

        // 1) 窄屏（411dp）子页返回仅弹出内部 NavHost，绝不关闭设置覆盖层
        val themeModeLabel = context.getString(UiR.string.settings_section_theme_mode)
        compose.onNodeWithText("外观与交互").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(themeModeLabel).assertIsDisplayed()

        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 200f, 0.5f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        compose.waitForIdle()
        assertTrue("子页预测性返回不得关闭外层设置", settingsOpen)
        compose.onNodeWithText("连接与账号").assertIsDisplayed()
        compose.onNodeWithText(themeModeLabel).assertDoesNotExist()

        // 2) 回到总览页后，预测性返回同时驱动覆盖层滑出与背景工作台视差，且松手提交不回弹至 x=0
        val overlayContainer = compose.onNodeWithTag("phone-fullscreen-settings-container")
        overlayContainer.assertIsDisplayed()
        val overlayContentNode = compose.onNode(hasText("连接与账号") and hasClickAction())
        val settledOverlayX = overlayContentNode.fetchSemanticsNode().positionInRoot.x
        val openBgX = compose.onNodeWithTag("workbench-parallax-marker", useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.x

        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 200f, 0.4f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        val overlayX04 = overlayContentNode.fetchSemanticsNode().positionInRoot.x
        val bgX04 = compose.onNodeWithTag("workbench-parallax-marker", useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.x
        assertTrue("总览侧滑 0.4 时设置覆盖层内容应向右移出: settled=$settledOverlayX, x04=$overlayX04", overlayX04 > settledOverlayX + 80f)
        assertTrue("总览侧滑 0.4 时背景工作台应同步产生视差位移: open=$openBgX, bg04=$bgX04", abs(bgX04 - openBgX) > 20f)

        compose.runOnIdle {
            dispatcher.dispatchOnBackProgressed(BackEventCompat(140f, 200f, 0.7f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        val overlayX07 = overlayContentNode.fetchSemanticsNode().positionInRoot.x
        val bgX07 = compose.onNodeWithTag("workbench-parallax-marker", useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.x
        assertTrue("总览侧滑推进至 0.7 时设置覆盖层内容应继续向右: x04=$overlayX04, x07=$overlayX07", overlayX07 > overlayX04 + 40f)
        assertTrue("总览侧滑推进至 0.7 时背景工作台应平滑回正: bg04=$bgX04, bg07=$bgX07", bgX07 > bgX04 + 5f)

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(16)
        compose.waitForIdle()
        val overlayNodesAfterCommit = compose.onAllNodes(hasText("连接与账号") and hasClickAction())
            .fetchSemanticsNodes()
        if (overlayNodesAfterCommit.isNotEmpty()) {
            val afterCommitX = overlayNodesAfterCommit.first().positionInRoot.x
            assertTrue("松手提交关闭设置后覆盖层不得弹回 x=0: x07=$overlayX07, after=$afterCommitX", afterCommitX >= overlayX07 - 1f)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertFalse("在总览页提交预测性返回后应关闭设置覆盖层", settingsOpen)
        compose.onNodeWithTag("phone-fullscreen-settings-container").assertDoesNotExist()
        compose.onNodeWithTag("workbench-parallax-marker", useUnmergedTree = true).assertIsDisplayed()

        // 3) 宽屏（>= 840dp）侧栏切换不崩溃且正确渲染
        compose.runOnIdle {
            hostWidth = 960.dp
            settingsOpen = true
        }
        compose.waitForIdle()
        compose.onNodeWithTag("phone-fullscreen-settings-container").assertDoesNotExist()
        compose.onNodeWithTag("workbench-parallax-marker", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("连接与账号").assertIsDisplayed()

        compose.onNodeWithText("外观与交互").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(themeModeLabel).assertIsDisplayed()

        compose.runOnIdle { settingsOpen = false }
        compose.waitForIdle()
        compose.onNodeWithText(themeModeLabel).assertDoesNotExist()
        compose.onNodeWithTag("workbench-parallax-marker", useUnmergedTree = true).assertIsDisplayed()
    }

    private class EmptyGateway : ConversationRepository {
        override suspend fun listConversations(
            scope: ConversationScope,
            page: PageRequest,
        ) = ConversationPage(emptyList(), null)

        override suspend fun timeline(
            conversationId: String,
            page: PageRequest,
        ) = TimelinePage(emptyList(), null)

        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
            Conversation(ConversationId("test"), "测试", 0)

        override suspend fun submitMessage(message: OutgoingMessage) =
            MessageAcceptance("test", message.clientMessageId.value)

        override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId)
        override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
        override suspend fun cancelGeneration(generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }

    private fun assertSettingsPredictiveBack(reduceMotion: Boolean) {
        var closes = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        val environment = settingsEnvironment()

        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            OpenAndroidIntelligenceTheme(reduceMotion = reduceMotion) {
                Surface(Modifier.fillMaxSize()) {
                    SettingsScreen(
                        environment = environment,
                        onBack = { closes++ },
                        presentation = SettingsPresentation.SCREEN,
                    )
                }
            }
        }

        val initialPosX = compose.onNodeWithText("外观与交互").fetchSemanticsNode().positionInRoot.x

        // 左边缘起滑推进至 0.5f（positionInRoot 不受根节点裁剪，左边缘向右跟随平移）
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(60f, 100f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()

        val leftProgressedPosX = compose.onNodeWithText("外观与交互").fetchSemanticsNode().positionInRoot.x
        if (reduceMotion) {
            assertEquals("减少动态时预测性返回不应产生横向位移", initialPosX, leftProgressedPosX, 1f)
        } else {
            assertTrue("标准态左边缘预测性返回应向右平移: initial=$initialPosX, progressed=$leftProgressedPosX", leftProgressedPosX > initialPosX + 50f)
        }

        // 取消手势：不触发 onBack 且弹簧复原至初始位置
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals(0, closes)
        val restoredPosX = compose.onNodeWithText("外观与交互").fetchSemanticsNode().positionInRoot.x
        assertEquals("取消预测性返回后设置面板应复原至初始水平位置", initialPosX, restoredPosX, 1f)

        // 右边缘起滑推进至 0.5f：应向左负向平移，再完整提交触发关闭回调
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(300f, 100f, 0f, BackEventCompat.EDGE_RIGHT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(240f, 100f, 0.5f, BackEventCompat.EDGE_RIGHT))
        }
        compose.waitForIdle()
        val rightProgressedPosX = compose.onNodeWithText("外观与交互").fetchSemanticsNode().positionInRoot.x
        if (reduceMotion) {
            assertEquals(initialPosX, rightProgressedPosX, 1f)
        } else {
            assertTrue("标准态右边缘预测性返回应向左平移: initial=$initialPosX, progressed=$rightProgressedPosX", rightProgressedPosX < initialPosX - 50f)
        }
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, closes)
    }

    @Test
    fun settingsNavHostIgnoresBackWhenHostIsClosing() {
        var closes = 0
        var hostOpen by mutableStateOf(false)
        lateinit var dispatcher: OnBackPressedDispatcher
        val environment = settingsEnvironment()

        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            OpenAndroidIntelligenceTheme(reduceMotion = false) {
                Surface(Modifier.fillMaxSize()) {
                    SettingsScreen(
                        environment = environment,
                        onBack = { closes++ },
                        presentation = SettingsPresentation.SCREEN,
                        hostOpen = hostOpen,
                    )
                }
            }
        }

        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals("外层宿主进入退场态（hostOpen=false）时不得重入触发 onBack", 0, closes)
    }

    private fun settingsEnvironment(): PlatformSettingsEnvironment {
        val trust = DeveloperTrustMode()
        val sink = InMemoryAuditSink()
        val audit = AndroidAuditStore(sink)
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
        grants.bind(PairingGrantBinding("https://core.example", "test-account", "test-install"))
        val kernel = PluginKernel(
            hostEnvelope = HostEnvelope(emptySet()),
            phoneLimits = PhoneLimits(emptySet()),
            runtimes = emptyMap(),
            audit = audit,
            trustMode = trust,
            nativeLoader = NativePluginLoader(trust),
            providerSelector = CapabilityProviderSelector(emptyMap()),
            grants = { id -> grants.currentKernelGrant(id) },
        )
        return PlatformSettingsEnvironment(
            trustMode = trust,
            audit = audit,
            auditSink = sink,
            allowDeveloperTrustMode = true,
            kernel = kernel,
            pairingGrants = grants,
            appearance = AppearancePreferences(context),
        )
    }
}
