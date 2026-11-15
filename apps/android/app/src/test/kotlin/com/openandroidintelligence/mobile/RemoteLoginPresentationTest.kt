package com.openandroidintelligence.mobile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.theme.OpenAndroidIntelligenceTheme
import com.openandroidintelligence.kernel.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 使用真实登录布局与设置宿主验证；认证动作仅记录回调，不向真实账号发请求。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h900dp-160dpi")
class RemoteLoginPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun officialEntryKeepsTheOriginalBrandAndOnlyTheRequestedActions() {
        val providers = mutableListOf<String>()
        var scans = 0
        showLogin(onOAuth = { providers += it }, onScan = { scans++ })
        compose.onNodeWithText("未连接").assertIsDisplayed()
        compose.onNodeWithText("Open Android Intelligence").assertIsDisplayed()
        compose.onNodeWithText("连接 DSH Remote，发现已接入的 Agent").assertIsDisplayed()
        compose.onNodeWithText("Remote 服务地址").assertDoesNotExist()
        compose.onNodeWithText("扫电脑上 Remote 插件显示的二维码，不用注册 Remote 账号").assertIsDisplayed()
        compose.onNodeWithText("扫码配对电脑").performClick()
        compose.onNodeWithText("使用 GitHub 登录").performClick()
        compose.onNodeWithText("使用 知乎 登录").performClick()
        compose.onNodeWithText("使用 Google 登录").performClick()
        assertEquals(1, scans)
        assertEquals(listOf("github", "zhihu", "google"), providers)
        compose.onNodeWithText("凭据只用于本次认证，刷新凭据由 Android Keystore 保护").assertExists()
        compose.onNodeWithText("前往设置").assertExists()
    }

    @Test fun cardSwitchAndPasswordVisibilityNeverTriggerAuthentication() {
        var logins = 0
        showLogin(onLogin = { _, _, _ -> logins++ })
        compose.onNodeWithText("使用自部署服务").performClick()
        compose.onNodeWithTag("remote-self-hosted-login").assertIsDisplayed()
        compose.onNodeWithText("邮箱或账号").performTextInput("alice")
        compose.onNodeWithText("访问凭据或密码").performTextInput("secret")
        compose.onNodeWithText("访问凭据或密码").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithContentDescription("显示密码").performClick()
        compose.onNodeWithText("访问凭据或密码").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
        compose.onNodeWithContentDescription("隐藏密码").performClick()
        compose.onNodeWithText("使用官方服务").performClick()
        compose.onNodeWithTag("remote-official-login").assertIsDisplayed()
        compose.onNodeWithText("使用自部署服务").performClick()
        compose.onNodeWithText("邮箱或账号").assertTextContains("alice")
        compose.onNodeWithText("访问凭据或密码").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        assertEquals("切换入口与密码显示都不能发起认证", 0, logins)
    }

    @Test fun selfHostedLoginSubmitsTheActualFormOnceAndClearsTheSecret() {
        val submitted = mutableListOf<List<String>>()
        showLogin(onLogin = { origin, account, password -> submitted += listOf(origin, account, password) })
        compose.onNodeWithText("使用自部署服务").performClick()
        compose.onNodeWithText("Remote 服务地址").performTextReplacement(" remote.example ")
        compose.onNodeWithText("邮箱或账号").performTextInput(" alice ")
        compose.onNodeWithText("访问凭据或密码").performTextInput("secret")
        compose.onNodeWithText("登录 Remote 服务器").performScrollTo().performClick()
        assertEquals(listOf(listOf("https://remote.example", "alice", "secret")), submitted)
        compose.onNodeWithText("登录 Remote 服务器").assertIsNotEnabled()
        compose.onNodeWithText("访问凭据或密码").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }

    @Test fun busyEntryDisablesAuthenticationAndModeChanges() {
        showLogin(busy = true)
        listOf("使用自部署服务", "扫码配对电脑", "使用 GitHub 登录", "使用 知乎 登录", "使用 Google 登录")
            .forEach { compose.onNodeWithText(it).assertIsNotEnabled() }
    }

    @Test fun keyboardDoneUsesTheSameLoginActionAndInvalidAddressCannotSubmit() {
        var logins = 0
        showLogin(onLogin = { _, _, _ -> logins++ })
        compose.onNodeWithText("使用自部署服务").performClick()
        compose.onNodeWithText("Remote 服务地址").performTextReplacement("https://")
        compose.onNodeWithText("邮箱或账号").performTextInput("alice")
        compose.onNodeWithText("访问凭据或密码").performTextInput("secret")
        compose.onNodeWithText("登录 Remote 服务器").assertIsNotEnabled()
        compose.onNodeWithText("访问凭据或密码").performImeAction()
        assertEquals(0, logins)
        compose.onNodeWithText("Remote 服务地址").performTextReplacement("remote.example")
        compose.onNodeWithText("访问凭据或密码").performImeAction()
        assertEquals(1, logins)
    }

    @Test fun failedConnectionUsesTheExistingReadableMessageAndRetryAction() {
        var retries = 0
        compose.setContent {
            OpenAndroidIntelligenceTheme {
                RemoteLoginPhase(false, "UNKNOWN_FAILURE:https://secret.example/token", { retries++ })
            }
        }
        compose.onNodeWithText("连接失败，请检查服务器地址、网络及账号").assertIsDisplayed()
        compose.onNodeWithText("UNKNOWN_FAILURE:https://secret.example/token").assertDoesNotExist()
        compose.onNodeWithText("重试").performClick()
        assertEquals(1, retries)
    }

    @Test fun offlineMirrorKeepsItsOriginalExplanationAndReconnectAction() {
        var reconnects = 0
        compose.setContent {
            OpenAndroidIntelligenceTheme {
                RemoteWorkbenchNotices(true, null, { reconnects++ }, {})
            }
        }
        compose.onNodeWithText(context.getString(com.openandroidintelligence.ui.resources.R.string.conn_status_offline_mode_hint)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(com.openandroidintelligence.ui.resources.R.string.conn_action_reconnect)).performClick()
        assertEquals(1, reconnects)
    }

    @Test fun loginFooterOpensTheOriginalSettingsAndBackReturnsThroughItsSubpage() {
        val environment = environment()
        var settingsOpen by mutableStateOf(false)
        var logins = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            OpenAndroidIntelligenceTheme(darkTheme = true, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    RemoteLoginSettingsHost(false, false, false, "https://remote.example", null,
                        settingsOpen, { settingsOpen = it }, { _, _, _ -> logins++ }, {}, {}, {},
                        settingsContent = { presentation, close -> SettingsScreen(environment, close, presentation = presentation) },
                        endpointContent = {})
                }
            }
        }
        compose.onNodeWithText("使用自部署服务").performClick()
        compose.onNodeWithText("邮箱或账号").performTextInput("保留的输入")
        compose.onNodeWithText("前往设置").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("phone-fullscreen-settings-container").assertIsDisplayed()
        compose.onNodeWithText("外观与交互").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(settingsOpen); dispatcher.onBackPressed() }
        compose.onNodeWithText("设置").assertIsDisplayed()
        compose.runOnIdle { assertTrue("子页返回不能直接关闭设置", settingsOpen); dispatcher.onBackPressed() }
        compose.runOnIdle { assertFalse(settingsOpen) }
        compose.onNodeWithText("邮箱或账号").assertTextContains("保留的输入")
        compose.onNodeWithText("前往设置").performScrollTo().assertIsDisplayed()
        assertEquals("设置往返不应触发认证", 0, logins)
    }

    @Test fun loginSurfaceAndCardRenderWithTheExistingLightAndDarkTheme() {
        var dark by mutableStateOf(false)
        lateinit var contentView: View
        var surface = Color.Unspecified
        var card = Color.Unspecified
        compose.setContent {
            OpenAndroidIntelligenceTheme(darkTheme = dark, dynamicColor = false) {
                contentView = LocalView.current
                surface = MaterialTheme.colorScheme.surface
                card = MaterialTheme.colorScheme.surfaceContainer
                Surface(Modifier.fillMaxSize(), color = surface) {
                    RemoteLoginScreen(false, false, false, "https://remote.example", null, {}, { _, _, _ -> }, {}, {})
                }
            }
        }
        listOf(false, true).forEach { isDark ->
            compose.runOnIdle { dark = isDark }
            val bounds = compose.onNodeWithTag("remote-login-card").fetchSemanticsNode().boundsInRoot
            val bitmap = compose.runOnIdle {
                Bitmap.createBitmap(contentView.width, contentView.height, Bitmap.Config.ARGB_8888).also {
                    contentView.draw(Canvas(it))
                }
            }
            assertColor(surface, Color(bitmap.getPixel(1, bitmap.height / 2)))
            assertColor(card, Color(bitmap.getPixel(bounds.center.x.toInt(), bounds.top.toInt() + 2)))
            val output = File("build/reports/remote-ui-recovery/login-${if (isDark) "dark" else "light"}.png")
            output.parentFile?.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun showLogin(busy: Boolean = false, onLogin: (String, String, String) -> Unit = { _, _, _ -> },
        onScan: () -> Unit = {}, onOAuth: (String) -> Unit = {}) {
        compose.setContent {
            OpenAndroidIntelligenceTheme(darkTheme = true, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    RemoteLoginScreen(busy, false, false, "https://remote.example", null, {}, onLogin, onScan, onOAuth)
                }
            }
        }
    }

    private fun assertColor(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, 0.02f)
        assertEquals(expected.green, actual.green, 0.02f)
        assertEquals(expected.blue, actual.blue, 0.02f)
    }

    private fun environment(): PlatformSettingsEnvironment {
        val trust = DeveloperTrustMode()
        val sink = InMemoryAuditSink()
        val audit = AndroidAuditStore(sink)
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
        val kernel = PluginKernel(HostEnvelope(emptySet()), PhoneLimits(emptySet()), emptyMap(), audit, trust,
            NativePluginLoader(trust), CapabilityProviderSelector(emptyMap()), { id -> grants.currentKernelGrant(id) })
        return PlatformSettingsEnvironment(trust, audit, sink, true, kernel, grants, AppearancePreferences(context))
    }
}
