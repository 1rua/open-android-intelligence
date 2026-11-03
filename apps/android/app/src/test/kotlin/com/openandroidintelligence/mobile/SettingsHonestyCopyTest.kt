package com.openandroidintelligence.mobile

import android.app.Application
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
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
import com.openandroidintelligence.encrypted.store.InMemoryOutboxPersistence
import com.openandroidintelligence.mobile.push.DevicePushRuntime
import com.openandroidintelligence.mobile.push.EncryptedPushStateStore
import com.openandroidintelligence.mobile.push.PushSystemAccess
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
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
        compose.onNodeWithText("读取与发送短信").assertDoesNotExist()
        compose.onNodeWithText("授权管理").performScrollTo().performClick()
        compose.onNodeWithText("全局设备能力").performScrollTo().performClick()
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

        compose.onNodeWithText("授权管理").performScrollTo().performClick()
        compose.onNodeWithText("全局设备能力").performScrollTo().performClick()
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

        compose.onNodeWithText("授权管理").performScrollTo().performClick()

        compose.onNodeWithText("全局设备能力").performScrollTo().assertExists()
        compose.onNodeWithText("屏幕上下文分析与圈选").assertDoesNotExist()
        compose.onNodeWithText("短信与通知主动推送").performScrollTo().assertExists()
        compose.onNodeWithText("屏幕读取与控制").performScrollTo().assertExists()
        compose.onNodeWithText("通知读取范围").assertDoesNotExist()
        compose.onNodeWithText("系统通知推送").assertDoesNotExist()
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

        compose.onNodeWithText("授权管理").performScrollTo().performClick()

        compose.onNodeWithText("配对元数据").assertDoesNotExist()
        compose.onNodeWithText("授权变更序号（本机）").assertDoesNotExist()
        compose.onNodeWithText("配对身份标识（本机合成）").assertDoesNotExist()
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

        compose.onNodeWithText("授权管理").performScrollTo().performClick()

        compose.onNodeWithText(
            "这台手机统一保存各项能力的开关与授权模式。切换 Core、账号或 Agent 不会重置；退出全部账号后暂停采集、执行和推送。",
        ).performScrollTo().assertExists()
        compose.onNodeWithText("切换账号或解除配对后，需要重新授权").assertDoesNotExist()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun devicePushFullScreenCoversItsBackgroundAndKeepsProductionControls() {
        assertDevicePushProductionPage(SettingsPresentation.SCREEN)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun devicePushBottomSheetUsesItsPresentationBackgroundAndOneBackAction() {
        assertDevicePushProductionPage(SettingsPresentation.BOTTOM_SHEET)
    }

    @Test
    fun devicePushFullScreenEntersAndReturnsThroughSettingsNavigation() {
        assertDevicePushNavigation(SettingsPresentation.SCREEN, reducedMotion = false)
    }

    @Test
    fun devicePushBottomSheetEntersAndReturnsThroughSettingsNavigation() {
        assertDevicePushNavigation(SettingsPresentation.BOTTOM_SHEET, reducedMotion = false)
    }

    @Test
    fun devicePushFullScreenRespectsTheSharedReducedMotionPolicy() {
        assertDevicePushNavigation(SettingsPresentation.SCREEN, reducedMotion = true)
    }

    @Test
    fun devicePushBottomSheetRespectsTheSharedReducedMotionPolicy() {
        assertDevicePushNavigation(SettingsPresentation.BOTTOM_SHEET, reducedMotion = true)
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
        compose.onNodeWithText("返回").assertDoesNotExist()
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
        compose.onNodeWithText("授权管理").performScrollTo().performClick()
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
        compose.onNodeWithText("返回").assertDoesNotExist()
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
        compose.onNodeWithText("全局设备能力").performScrollTo().assertIsDisplayed()
        assertEquals(0, closeCalls)
        compose.onNodeWithContentDescription("返回").performClick()
        val closeDescription = if (presentation == SettingsPresentation.BOTTOM_SHEET) "关闭设置" else "返回"
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
