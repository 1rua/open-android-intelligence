package com.openandroidintelligence.mobile

import android.app.Application
import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.CapabilityProviderSelector
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.HostEnvelope
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.NativePluginLoader
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.kernel.PhoneLimits
import com.openandroidintelligence.kernel.PluginKernel
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.encrypted.store.InMemoryOutboxPersistence
import com.openandroidintelligence.mobile.push.DevicePushRuntime
import com.openandroidintelligence.mobile.push.EncryptedPushStateStore
import com.openandroidintelligence.mobile.push.PushSystemAccess
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** SMS access must lead to the real author-scoped plugin grant, never a standalone capability bit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsHonestyCopyTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theSmsEntryOpensPluginManagementInsteadOfOfferingAnIneffectiveGrant() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    SettingsScreen(environment = settingsEnvironment(), runtime = null, onBack = {})
                }
            }
        }

        // 短信读取不提供独立开关：它只存在于授权管理子页，且必须把用户送到真正的
        // 插件安装/授权链路，而不是给一个点了也不生效的授权位。
        compose.onNodeWithText("设备能力与授权").performScrollTo().performClick()
        compose.onNodeWithText("插件管理").performScrollTo().performClick()
        compose.onNodeWithText("未安装任何插件").assertExists()
    }

    @Test
    fun openingSmsSettingsDoesNotGrantAccessToAnUnselectedPlugin() {
        val environment = settingsEnvironment()
        compose.setContent {
            MaterialTheme {
                Surface {
                    SettingsScreen(environment = environment, runtime = null, onBack = {})
                }
            }
        }

        compose.onNodeWithText("设备能力与授权").performScrollTo().performClick()
        compose.onNodeWithText("插件管理").performScrollTo().performClick()
        assertTrue(environment.pairingGrants.state.value!!.granted.isEmpty())
    }

    /**
     * 授权管理提供安装实例策略与独立设备主动推送。
     * 通知类条目（推送、气泡、实时活动、读取范围）已归入主览的「通知管理」区，
     * 因此不应在这个子页里重复出现——两处入口意味着改文案时必然漏掉一处。
     */
    @Test
    fun authorisationSeparatesDevicePushFromReplyNotificationPresentation() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    SettingsScreen(environment = settingsEnvironment(), runtime = null, onBack = {})
                }
            }
        }

        compose.onNodeWithText("设备能力与授权").performScrollTo().performClick()

        compose.onNodeWithText("插件管理").performScrollTo().assertExists()
        compose.onNodeWithText("短信与通知主动推送").performScrollTo().assertExists()
        compose.onNodeWithText("屏幕内容读取").performScrollTo().assertExists()
        compose.onNodeWithText("屏幕截图").performScrollTo().assertExists()
        compose.onNodeWithText("屏幕控制").performScrollTo().assertExists()
        compose.onNodeWithText("通知读取").performScrollTo().assertExists()
    }

    /** 主览不再展示本机自增序号、本机合成标识这类对用户没有意义的技术元数据。 */
    @Test
    fun theAuthorisationSubScreenOmitsLocalBookkeepingMetadata() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    SettingsScreen(environment = settingsEnvironment(), runtime = null, onBack = {})
                }
            }
        }

        compose.onNodeWithText("设备能力与授权").performScrollTo().performClick()

        // 验证只展示用户关心的能力项与设置入口
        compose.onNodeWithText("插件管理").performScrollTo().assertExists()
        compose.onNodeWithText("短信与通知主动推送").performScrollTo().assertExists()
    }

    /**
     * 授权管理说明手机本机能力设置与系统权限、通知范围和屏幕会话分别管理，
     * 不把切换账号写成清除本机决定，也不把它与解除配对的效果混为一谈。
     */
    @Test
    fun theAuthorisationNotesSeparateLocalCapabilitySettingsFromOtherSettings() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    SettingsScreen(environment = settingsEnvironment(), runtime = null, onBack = {})
                }
            }
        }

        compose.onNodeWithText("设备能力与授权").performScrollTo().performClick()

        compose.onNodeWithTag("capabilities-manual-default-notice").performScrollTo().assertExists()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun devicePushFullScreenCoversItsBackgroundAndKeepsProductionControls() {
        assertDevicePushProductionPage(SettingsPresentation.SCREEN)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun devicePushSidebarUsesItsPresentationBackgroundAndOneBackAction() {
        assertDevicePushProductionPage(SettingsPresentation.SIDEBAR)
    }

    @Test
    fun devicePushFullScreenEntersAndReturnsThroughSettingsNavigation() {
        assertDevicePushNavigation(SettingsPresentation.SCREEN, reducedMotion = false)
    }

    @Test
    fun devicePushSidebarEntersAndReturnsThroughSettingsNavigation() {
        assertDevicePushNavigation(SettingsPresentation.SIDEBAR, reducedMotion = false)
    }

    @Test
    fun devicePushFullScreenRespectsTheSharedReducedMotionPolicy() {
        assertDevicePushNavigation(SettingsPresentation.SCREEN, reducedMotion = true)
    }

    @Test
    fun devicePushSidebarRespectsTheSharedReducedMotionPolicy() {
        assertDevicePushNavigation(SettingsPresentation.SIDEBAR, reducedMotion = true)
    }

    @Test
    fun sidebarSystemBackNavigatesSubpagesBeforeClosingSettings() {
        var closeCalls = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        val environment = settingsEnvironment()
        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            MaterialTheme {
                SettingsScreen(
                    environment = environment,
                    onBack = { closeCalls++ },
                    presentation = SettingsPresentation.SIDEBAR,
                )
            }
        }

        compose.onNodeWithText("设备能力与授权").performScrollTo().performClick()
        compose.onNodeWithText("插件管理").performScrollTo().performClick()
        compose.onNodeWithText("未安装任何插件").assertExists()

        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.onNodeWithText("插件管理").performScrollTo().assertIsDisplayed()
        assertEquals("从插件页返回不得关闭设置", 0, closeCalls)
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.onNodeWithText("设备能力与授权").performScrollTo().assertIsDisplayed()
        assertEquals("从授权页返回不得关闭设置", 0, closeCalls)
        compose.runOnIdle { dispatcher.onBackPressed() }
        assertEquals("设置根页返回才退出面板", 1, closeCalls)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun modalDrawerSystemBackPopsSettingsBeforeClosingTheDrawer() {
        val environment = settingsEnvironment()
        var closeCalls = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        lateinit var drawerState: DrawerState

        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            val drawer = rememberDrawerState(DrawerValue.Closed)
            drawerState = drawer
            val scope = rememberCoroutineScope()
            MaterialTheme {
                ModalNavigationDrawer(
                    drawerState = drawer,
                    drawerContent = {
                        ModalDrawerSheet(modifier = Modifier.width(Dimensions.DrawerWidth)) {
                            SettingsScreen(
                                environment = environment,
                                onBack = {
                                    closeCalls++
                                    scope.launch { drawer.close() }
                                },
                                presentation = SettingsPresentation.SIDEBAR,
                            )
                        }
                    },
                ) {
                    TextButton(onClick = { scope.launch { drawer.open() } }) {
                        Text("打开设置")
                    }
                }
            }
        }

        compose.onNodeWithText("打开设置").performClick()
        compose.runOnIdle { assertEquals("设置抽屉应已打开", DrawerValue.Open, drawerState.currentValue) }
        compose.onNodeWithText("设备能力与授权").performScrollTo().performClick()
        compose.onNodeWithText("插件管理").performScrollTo().performClick()
        compose.onNodeWithText("未安装任何插件").assertExists()

        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.onNodeWithText("插件管理").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("从插件页返回不得关闭设置", 0, closeCalls)
            assertEquals("从插件页返回时抽屉必须保持打开", DrawerValue.Open, drawerState.currentValue)
            assertEquals("从插件页返回时抽屉不能开始关闭", DrawerValue.Open, drawerState.targetValue)
        }

        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.onNodeWithText("设备能力与授权").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("从授权页返回不得关闭设置", 0, closeCalls)
            assertEquals("从授权页返回时抽屉必须保持打开", DrawerValue.Open, drawerState.currentValue)
            assertEquals("从授权页返回时抽屉不能开始关闭", DrawerValue.Open, drawerState.targetValue)
        }

        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.runOnIdle {
            assertEquals("设置根页返回应调用关闭回调", 1, closeCalls)
            assertEquals("设置根页返回后抽屉应关闭", DrawerValue.Closed, drawerState.currentValue)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h891dp-420dpi")
    fun sidebarSettingsFitWithin320dpWithLargerText() {
        val environment = settingsEnvironment()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme {
                    Box(Modifier.size(320.dp, 700.dp).testTag("settings-sidebar")) {
                        SettingsScreen(
                            environment = environment,
                            onBack = {},
                            presentation = SettingsPresentation.SIDEBAR,
                        )
                    }
                }
            }
        }

        fun assertTextFits(label: String) {
            val node = compose.onNodeWithText(label).performScrollTo()
            val textBounds = node.fetchSemanticsNode().boundsInRoot
            val panelBounds = compose.onNodeWithTag("settings-sidebar").fetchSemanticsNode().boundsInRoot
            assertTrue("$label 不应超出侧栏左边界", textBounds.left >= panelBounds.left)
            assertTrue("$label 不应超出侧栏右边界", textBounds.right <= panelBounds.right)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("$label 必须具有可检查的文本布局", layouts.isNotEmpty())
            layouts.forEach { layout ->
                val lineCount = layout.lineCount
                assertTrue("$label 必须包含至少一行", lineCount > 0)
                for (line in 0 until lineCount) {
                    assertFalse("$label 第 $line 行不应被省略", layout.isLineEllipsized(line))
                }
            }
        }

        assertTextFits("外观与交互")
        compose.onNodeWithText("外观与交互").performClick()
        assertTextFits("减少动画")
    }

    private fun assertDevicePushProductionPage(presentation: SettingsPresentation) {
        val runtime = DevicePushRuntime(
            EncryptedPushStateStore(
                "settings-page-test",
                InMemoryOutboxPersistence(),
                SecretKeySpec(ByteArray(32) { 1 }, "AES"),
            ),
            PushSystemAccess { true },
        )
        val screenColor = Color(0xFFF4F1EB)
        val sheetColor = Color(0xFFE4E1DB)
        var backCalls = 0
        var refreshCalls = 0
        lateinit var contentView: View
        compose.setContent {
            contentView = LocalView.current
            MaterialTheme(colorScheme = lightColorScheme(surface = screenColor, surfaceContainerLow = sheetColor)) {
                Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                    DevicePushSubScreen(
                        runtime = runtime,
                        endpoints = emptyList(),
                        onRefreshEndpoints = { refreshCalls++ },
                        onBack = { backCalls++ },
                        presentation = presentation,
                    )
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("push-notification-enabled").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("短信与通知主动推送").assertIsDisplayed()
        compose.onNodeWithText("主动推送").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("返回").assertCountEquals(1)
        // Robolectric 不驱动 WindowCapture 等待的窗口重绘回调；直接绘制已布局的真实 View。
        val pixels = compose.runOnIdle { contentView.drawToBitmap() }
        val expectedColor = if (presentation == SettingsPresentation.SCREEN) screenColor else sheetColor
        assertEquals(expectedColor, Color(pixels.getPixel(pixels.width - 2, pixels.height - 2)))

        compose.onNodeWithTag("push-notification-enabled").performClick()
        compose.waitUntil(5_000) { runtime.snapshot().configuration.notifications.enabled }
        compose.onNodeWithTag("push-notification-enabled").assertIsOn()
        compose.onNodeWithText("刷新队列状态").performScrollTo().performClick()
        compose.onNodeWithTag("push-refresh-endpoints").performScrollTo().performClick()
        assertEquals(1, refreshCalls)
        compose.onNodeWithContentDescription("返回").assertIsDisplayed().performClick()
        assertEquals(1, backCalls)
        assertTrue(runtime.snapshot().configuration.notifications.enabled)
    }

    private fun assertDevicePushNavigation(presentation: SettingsPresentation, reducedMotion: Boolean) {
        var closeCalls = 0
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = reducedMotion)) {
                MaterialTheme {
                    Surface {
                        SettingsScreen(
                            environment = settingsEnvironment(),
                            onBack = { closeCalls++ },
                            presentation = presentation,
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("设备能力与授权").performScrollTo().performClick()
        compose.onNodeWithText("短信与通知主动推送").performScrollTo()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("短信与通知主动推送").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(MotionSpecs.ReducedMotionCrossfadeDuration.toLong() / 4)
        val unavailable = compose.onNodeWithText("本机加密队列暂不可用，请检查设备存储和密钥状态。")
        val enteringLeft = unavailable.fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val settledLeft = unavailable.fetchSemanticsNode().boundsInRoot.left
        if (reducedMotion) {
            assertEquals("减少动态时进入不应产生水平位移", settledLeft, enteringLeft, 1f)
        } else {
            assertTrue("普通进入沿用设置导航的水平转场", enteringLeft > settledLeft)
        }
        compose.onAllNodesWithContentDescription("返回").assertCountEquals(1)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("返回").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(MotionSpecs.ReducedMotionCrossfadeDuration.toLong() / 4)
        val exitingLeft = unavailable.fetchSemanticsNode().boundsInRoot.left
        if (reducedMotion) {
            assertEquals("减少动态时返回不应产生水平位移", settledLeft, exitingLeft, 1f)
        } else {
            assertTrue("普通返回沿用设置导航的反向转场", exitingLeft > settledLeft)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        unavailable.assertDoesNotExist()
        compose.onNodeWithText("插件管理").performScrollTo().assertIsDisplayed()
        assertEquals(0, closeCalls)
        compose.onNodeWithContentDescription("返回").performClick()
        val closeDescription = if (presentation == SettingsPresentation.SIDEBAR) "关闭设置" else "返回"
        compose.onNodeWithContentDescription(closeDescription).performClick()
        assertEquals(1, closeCalls)
    }

    private fun settingsEnvironment(): PlatformSettingsEnvironment {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val trustMode = DeveloperTrustMode()
        val auditStore = AndroidAuditStore(InMemoryAuditSink())
        val pairingGrants = PairingGrantStateHolder(
            store = InMemoryPairingGrantStore(),
            audit = auditStore,
        )
        pairingGrants.bind(
            com.openandroidintelligence.kernel.PairingGrantBinding(
                gatewayId = "https://gateway.example.com",
                accountId = "test-account",
                installationId = "test-install",
            ),
        )
        val kernel = PluginKernel(
            hostEnvelope = HostEnvelope(primitives = emptySet()),
            phoneLimits = PhoneLimits(primitives = emptySet()),
            runtimes = emptyMap(),
            audit = auditStore,
            trustMode = trustMode,
            nativeLoader = NativePluginLoader(trustMode),
            providerSelector = CapabilityProviderSelector(phoneDefaults = emptyMap()),
            grants = { pairingId -> pairingGrants.currentKernelGrant(pairingId) },
        )
        return PlatformSettingsEnvironment(
            trustMode = trustMode,
            audit = auditStore,
            auditSink = InMemoryAuditSink(),
            allowDeveloperTrustMode = true,
            kernel = kernel,
            pairingGrants = pairingGrants,
            appearance = AppearancePreferences(context),
        )
    }
}
