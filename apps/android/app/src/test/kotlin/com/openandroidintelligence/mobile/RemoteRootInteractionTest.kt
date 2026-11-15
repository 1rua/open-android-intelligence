package com.openandroidintelligence.mobile

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.state.WorkbenchUiState
import com.openandroidintelligence.conversation.theme.OpenAndroidIntelligenceTheme
import com.openandroidintelligence.gateway.remote.auth.RemoteLoginResult
import com.openandroidintelligence.gateway.remote.authproduction.RemoteActiveAccount
import com.openandroidintelligence.gateway.remote.network.AuthenticatedRemotePeer
import com.openandroidintelligence.gateway.remote.network.RemoteHostDevice
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcChannel
import com.openandroidintelligence.gateway.remote.runtime.RemoteAccountConnectionState
import com.openandroidintelligence.gateway.remote.runtime.RemoteHostConnectionState
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.plugins.thirdPartyOnlyHost
import com.openandroidintelligence.ui.resources.R as UiR
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 执行根页面实际使用的副作用、抽屉与设置页；网络边界使用可控回调，不接真实账号。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h900dp-160dpi")
class RemoteRootInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun staleSelectionOrClientStateCannotMakeTheLoginStatusConnected() {
        var workbench by mutableStateOf<WorkbenchUiState?>(null)
        var client by mutableStateOf(RemoteAccountConnectionState.CONNECTED)
        var busy by mutableStateOf(false)
        compose.setContent {
            OpenAndroidIntelligenceTheme(reduceMotion = true) {
                Surface(Modifier.fillMaxSize()) {
                    RemoteLoginScreen(busy, remoteWorkbenchConnected(workbench, client, busy), false,
                        "https://remote.example", null, {}, { _, _, _ -> }, {}, {})
                }
            }
        }
        // 退休控制器后，即使 Client 暂留 CONNECTED，登录/端点选择页仍未连接。
        compose.onNodeWithText("未连接").assertIsDisplayed()
        compose.runOnIdle { workbench = WorkbenchUiState(isOnline = false) }
        compose.onNodeWithText("未连接").assertIsDisplayed()
        compose.runOnIdle { workbench = WorkbenchUiState(isOnline = true); busy = true }
        compose.onNodeWithText("未连接").assertIsDisplayed()
        compose.runOnIdle { busy = false; client = RemoteAccountConnectionState.CONNECTING }
        compose.onNodeWithText("未连接").assertIsDisplayed()
        compose.runOnIdle { client = RemoteAccountConnectionState.CONNECTED }
        compose.onNodeWithText("已连接").assertIsDisplayed()
        compose.runOnIdle { workbench = null }
        compose.onNodeWithText("未连接").assertIsDisplayed()
    }

    @Test fun microphoneDenialOnlyOpensTheSystemPermissionPageAfterItsSnackbarAction() {
        lateinit var activity: ComponentActivity
        compose.setContent {
            activity = LocalContext.current as ComponentActivity
            val snackbar = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            MaterialTheme {
                Column {
                    Button(onClick = { scope.launch { showRemoteAudioPermissionDenied(activity, snackbar) } }) {
                        Text("触发权限拒绝")
                    }
                    SnackbarHost(snackbar)
                }
            }
        }
        compose.onNodeWithText("触发权限拒绝").performClick()
        compose.onNodeWithText(context.getString(UiR.string.perm_audio_required)).assertIsDisplayed()
        compose.runOnIdle { assertNull(shadowOf(activity).nextStartedActivity) }
        compose.onNodeWithText(context.getString(UiR.string.common_action_go_to_settings)).performClick()
        compose.runOnIdle {
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
            assertEquals(Uri.fromParts("package", activity.packageName, null), intent.data)
        }
    }

    @Test fun replyAndAssistantOnlyCloseTheActualDrawerAfterTheConsumerAccepts() {
        val reply = mutableStateOf<ReplyNotificationTarget?>(null)
        val handoff = mutableStateOf<String?>(null)
        var open by mutableStateOf(true)
        var accepted = false
        var opened = 0
        var replyCalls = 0
        var handoffCalls = 0
        compose.setContent {
            OpenAndroidIntelligenceTheme(reduceMotion = true) {
                RemotePendingConversationNavigation(null, null, reply, handoff,
                    openReply = { replyCalls++; accepted },
                    consumeHandoff = { handoffCalls++; accepted },
                    onOpened = { opened++; open = false })
                PlatformSettingsDrawer(open, { open = false },
                    settingsContent = { _, _ -> Text("设置面板") }) { Text("会话内容") }
            }
        }
        compose.onNodeWithText("设置面板").assertIsDisplayed()
        compose.runOnIdle { reply.value = ReplyNotificationTarget("profile", "account", "failed") }
        compose.runOnIdle { assertNotNull(reply.value); assertTrue(open); assertEquals(0, opened) }
        compose.onNodeWithText("设置面板").assertIsDisplayed()
        compose.runOnIdle { accepted = true; reply.value = ReplyNotificationTarget("profile", "account", "accepted") }
        compose.onNodeWithTag("phone-fullscreen-settings-container").assertDoesNotExist()
        compose.runOnIdle { assertNull(reply.value); assertEquals(1, opened); open = true; accepted = false; handoff.value = "failed" }
        compose.onNodeWithText("设置面板").assertIsDisplayed()
        compose.runOnIdle { assertEquals("failed", handoff.value); assertTrue(open); assertEquals(1, opened) }
        compose.runOnIdle { accepted = true; handoff.value = "accepted" }
        compose.onNodeWithTag("phone-fullscreen-settings-container").assertDoesNotExist()
        compose.runOnIdle {
            assertNull(handoff.value)
            assertEquals(2, opened)
            assertEquals(2, replyCalls)
            assertEquals(2, handoffCalls)
        }
    }

    @Test fun sharedAttachmentWaitsForHydratedHistoryThenClosesTheActualDrawerOnce() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val threads = CompletableDeferred<ConversationPage>()
        val controller = controller(scope, threads)
        val shares = SharedConversationAttachments(SavedStateHandle())
        val uri = Uri.parse("content://shared/photo")
        shares.receive(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri))
        var open by mutableStateOf(true)
        var imports = 0
        try {
            compose.setContent {
                OpenAndroidIntelligenceTheme(reduceMotion = true) {
                    ReceivePendingConversationShares(shares, controller, { uris ->
                        assertEquals(listOf(uri), uris)
                        assertTrue(controller.state.value.historyHydrated)
                        true
                    }) { imports++; open = false }
                    PlatformSettingsDrawer(open, { open = false },
                        settingsContent = { _, _ -> Text("设置面板") }) { Text("会话内容") }
                }
            }
            compose.onNodeWithText("设置面板").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(0, imports)
                assertEquals(listOf(uri.toString()), shares.pending.value)
                threads.complete(ConversationPage(listOf(ConversationSummary(ConversationId("thread-a"), "测试会话", 1)), null))
            }
            compose.onNodeWithTag("phone-fullscreen-settings-container").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(1, imports)
                assertTrue(shares.pending.value.isEmpty())
                controller.openThread("thread-b")
            }
            compose.runOnIdle { assertEquals(1, imports) }
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun connectionSettingsLogoutRequiresConfirmationAndCancellationPreservesTheAccount() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val audit = AndroidAuditStore(InMemoryAuditSink())
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
        val trust = DeveloperTrustMode()
        val host = thirdPartyOnlyHost(context, grants, trust, NativePluginLoader(trust), "logout-ui-test")
        val account = TestAccount()
        val runtime = RemoteRuntime(context, scope, host, grants, accountFactory = { account })
        try {
            compose.setContent {
                LaunchedEffect(runtime) { runtime.restoreSessionIfAvailable().join() }
                OpenAndroidIntelligenceTheme(reduceMotion = true) {
                    RemoteConnectionSettingsScreen(runtime, SettingsPresentation.SCREEN, {}, {})
                }
            }
            compose.onNodeWithText("退出登录").performScrollTo().performClick()
            compose.onNodeWithText("退出当前账号？").assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, account.logouts); assertNotNull(runtime.state.value.account) }
            compose.onNodeWithText("取消").performClick()
            compose.runOnIdle { assertEquals(0, account.logouts); assertNotNull(runtime.state.value.account) }
            compose.onNodeWithText("退出登录").performScrollTo().performClick()
            compose.onNode(hasText("退出登录") and hasAnyAncestor(isDialog())).performClick()
            compose.runOnIdle { assertEquals(1, account.logouts); assertNull(runtime.state.value.account) }
        } finally { scope.cancel() }
    }

    private fun controller(scope: CoroutineScope, threads: CompletableDeferred<ConversationPage>) = WorkbenchController(scope,
        object : ConversationRepository {
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = threads.await()
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
            override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation = error("不创建会话")
            override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance = error("不发送消息")
            override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance = error("不发送消息")
            override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
            override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        }, object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("test"), emptyList())
        }, { ConversationScope("profile", "gateway", "account", "install") },
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false))

    private class TestAccount : RemoteAccountConnection {
        override val account = MutableStateFlow<RemoteActiveAccount?>(null)
        override val state = MutableStateFlow(RemoteAccountConnectionState.READY)
        override val hostState = MutableStateFlow(RemoteHostConnectionState.STOPPED)
        var logouts = 0
        override suspend fun login(origin: String, email: String, password: String): RemoteLoginResult = error("不执行真实登录")
        override suspend fun restore() = RemoteActiveAccount("https://ui-logout.example", "ui-account", 1).also { account.value = it }
        override suspend fun listHosts() = emptyList<RemoteHostDevice>()
        override suspend fun connect(hostDeviceId: String): RemoteRpcChannel = error("不建立网络连接")
        override fun authenticatedPeer(connectionId: String): AuthenticatedRemotePeer = error("不建立网络连接")
        override suspend fun logout() { logouts++; account.value = null; state.value = RemoteAccountConnectionState.SIGNED_OUT }
    }
}
