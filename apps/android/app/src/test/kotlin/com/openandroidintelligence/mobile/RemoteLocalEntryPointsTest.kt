package com.openandroidintelligence.mobile

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.capability.RemoteScreenCaptureRegistry
import com.openandroidintelligence.capability.ScreenCaptureSource
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.plugins.thirdPartyOnlyHost
import com.openandroidintelligence.mobile.screen.ScreenCaptureConsentTicket
import com.openandroidintelligence.mobile.screen.rememberScreenCaptureAuthorization
import com.openandroidintelligence.plugin.ui.NativeUiContext
import com.openandroidintelligence.plugin.ui.NativeUiProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.WorkbenchController
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 本机入口经过生产组合函数验证，不用静态源码断言代替按钮行为。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemoteLocalEntryPointsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `本机屏幕授权按钮实际拉起系统选择器且未冒充授权成功`() {
        lateinit var activity: ComponentActivity
        var requests = 0
        var results = 0
        val source = object : ScreenCaptureSource {
            override val isAvailable = false
            override fun createAuthorizationIntent() = Intent("test.screen.CONSENT").also { requests++ }
            override fun onAuthorizationResult(resultCode: Int, resultData: Intent?): Boolean { results++; return false }
            override suspend fun capture() = null
            override fun release() = Unit
        }
        val field = RemoteScreenCaptureRegistry::class.java.getDeclaredField("source").apply { isAccessible = true }
        val previous = field.get(null)
        field.set(null, source)
        try {
            compose.setContent {
                MaterialTheme {
                    activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
                    val request = rememberScreenCaptureAuthorization(activity, { true },
                        { ScreenCaptureConsentTicket(1, 1, 1) }, { true }, {})
                    Button(onClick = request) { Text("授权屏幕采集") }
                }
            }
            compose.runOnIdle { assertEquals(0, requests) }
            compose.onNodeWithText("授权屏幕采集").performClick()
            compose.runOnIdle {
                assertEquals(1, requests)
                assertEquals(0, results)
                assertEquals("test.screen.CONSENT", shadowOf(activity).nextStartedActivityForResult.intent.action)
            }
        } finally { field.set(null, previous) }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-420dpi")
    fun `窄屏工作台退出按钮只触发退出不会打开设置`() = assertWorkbenchLogout(narrow = true)

    @Test @Config(qualifiers = "w1000dp-h891dp-420dpi")
    fun `宽屏工作台退出按钮只触发退出不会打开设置`() = assertWorkbenchLogout(narrow = false)

    private fun assertWorkbenchLogout(narrow: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repo = object : ConversationRepository {
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(emptyList(), null)
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
            override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation = error("不创建会话")
            override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance = error("不发送")
            override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance = error("不发送")
            override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
            override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        }
        val controller = WorkbenchController(scope, repo, object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("test"), emptyList())
        }, { ConversationScope("remote", "endpoint", "account", "install") })
        var logout = 0
        var settings = 0
        try {
            compose.setContent { MaterialTheme {
                WorkbenchSettingsHost {
                    com.openandroidintelligence.conversation.workbench.WorkbenchScreen(controller, "https://remote.example",
                        onOpenSettings = { settings++ }, onLogout = { logout++ },
                        onPickCamera = {}, onPickGallery = {}, onPickDocument = {}, onVoiceInput = {})
                }
            } }
            if (narrow) compose.onNodeWithContentDescription("打开会话抽屉").performClick()
            compose.onNodeWithText("退出登录").performClick()
            compose.runOnIdle { assertEquals(1, logout); assertEquals(0, settings) }
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun `Remote原生全屏插件展示且仍能打开设置或恢复宿主`() {
        val trust = DeveloperTrustMode().apply { enable(DeveloperTrustMode.Acknowledgement(DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT)) }
        val audit = AndroidAuditStore(InMemoryAuditSink())
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
        val host = thirdPartyOnlyHost(context, grants, trust, NativePluginLoader(trust), "native-ui-test")
        var settings = 0
        val provider = object : NativeUiProvider {
            @Composable override fun Render(context: NativeUiContext) {
                Text("本机插件界面")
                Button(onClick = context.openSettings) { Text("插件打开设置") }
            }
        }
        compose.setContent {
            MaterialTheme { RemoteNativePluginSurface(host, "test.native.ui" to provider, null, "https://remote.example") { settings++ } }
        }
        compose.onNodeWithText("本机插件界面").assertExists()
        compose.onNodeWithText("插件打开设置").performClick()
        compose.runOnIdle { assertEquals(1, settings) }
        compose.onNodeWithText("恢复宿主界面").performClick()
        compose.runOnIdle { assertNull(host.nativeUiProvider()) }
    }
}
