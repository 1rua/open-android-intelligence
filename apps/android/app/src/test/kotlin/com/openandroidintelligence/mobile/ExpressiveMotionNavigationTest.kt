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
import com.openandroidintelligence.conversation.motion.AppSharedTransitionHost
import com.openandroidintelligence.conversation.motion.AppTransitions
import com.openandroidintelligence.conversation.motion.LocalSharedTransitionScope
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.motion.ProvideSharedVisibilityScope
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.OpenAndroidIntelligenceTheme
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 根导航、登录形变按钮、设置共享转场与预测性返回兼容集成回归。
 *
 * 同时验证标准态与 `reduceMotion = true` / `durationScale = 0f` 降级路径，
 * 确保共享容器过渡与手势预览不破坏既有路由栈、语义角色与无障碍访问。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h900dp-160dpi")
class ExpressiveMotionNavigationTest {

    @get:Rule
    val compose = createComposeRule()

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
    fun settingsNavHostUsesSharedBoundsForCategoriesAndHorizontalSlideForSecondaryPagesStandard() {
        assertSettingsNavHostTransitions(reduceMotion = false)
    }

    @Test
    fun settingsNavHostUsesSharedBoundsForCategoriesAndHorizontalSlideForSecondaryPagesReduced() {
        assertSettingsNavHostTransitions(reduceMotion = true)
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

        // 一级分类：总览 -> 外观与交互（共享分类卡片边界）
        compose.onNodeWithText("外观与交互").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(UiR.string.settings_title_appearance)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(UiR.string.settings_section_theme_mode)).assertIsDisplayed()

        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("设置").assertIsDisplayed()
        assertEquals(0, closes)

        // 一级分类 -> 二级子页（设备能力与授权 -> 插件管理）：不得挂载总览共享 key，保留水平滑入转场
        compose.onNodeWithText("设备能力与授权").performScrollTo().performClick()
        compose.waitForIdle()
        val pluginsEntry = compose.onNodeWithTag("capability-entry:plugins")
        pluginsEntry.performScrollTo()

        compose.mainClock.autoAdvance = false
        pluginsEntry.performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(MotionSpecs.ReducedMotionCrossfadeDuration.toLong() / 4)

        val secondaryTitle = compose.onNodeWithText("未安装任何插件")
        val enteringLeft = secondaryTitle.fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val settledLeft = secondaryTitle.fetchSemanticsNode().boundsInRoot.left

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

        // 开始手势并推进至 0.5f（positionInRoot 不受根节点裁剪，可精确观测负向平移）
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(60f, 100f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()

        val progressedPosX = compose.onNodeWithText("外观与交互").fetchSemanticsNode().positionInRoot.x
        if (reduceMotion) {
            assertEquals("减少动态时预测性返回不应产生横向位移", initialPosX, progressedPosX, 1f)
        } else {
            assertTrue("标准态预测性返回应向左平移: initial=$initialPosX, progressed=$progressedPosX", progressedPosX < initialPosX - 50f)
        }

        // 取消手势：不触发 onBack 且弹簧复原至初始位置
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals(0, closes)
        val restoredPosX = compose.onNodeWithText("外观与交互").fetchSemanticsNode().positionInRoot.x
        assertEquals("取消预测性返回后设置面板应复原至初始水平位置", initialPosX, restoredPosX, 1f)

        // 再次完整提交返回：触发关闭回调
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(90f, 100f, 0.8f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        compose.waitForIdle()
        assertEquals(1, closes)
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
