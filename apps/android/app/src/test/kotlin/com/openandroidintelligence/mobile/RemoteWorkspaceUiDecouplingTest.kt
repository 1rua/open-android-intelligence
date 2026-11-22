package com.openandroidintelligence.mobile

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.remote.BackendId
import com.openandroidintelligence.conversation.remote.RemoteServiceOrigin
import com.openandroidintelligence.conversation.theme.OpenAndroidIntelligenceTheme
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.remote.auth.RemoteLoginResult
import com.openandroidintelligence.gateway.remote.auth.RemoteRoleRegistration
import com.openandroidintelligence.gateway.remote.auth.RemoteRoleRegistrationState
import com.openandroidintelligence.gateway.remote.authproduction.RemoteActiveAccount
import com.openandroidintelligence.gateway.remote.identity.RemoteRole
import com.openandroidintelligence.gateway.remote.network.AuthenticatedRemotePeer
import com.openandroidintelligence.gateway.remote.network.RemoteHostDevice
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.remote.runtime.RemoteAccountConnectionState
import com.openandroidintelligence.gateway.remote.runtime.RemoteHostFailure
import com.openandroidintelligence.gateway.remote.runtime.RemoteHostFailureStage
import com.openandroidintelligence.gateway.remote.runtime.RemoteHostConnectionState
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.plugins.thirdPartyOnlyHost
import com.openandroidintelligence.ui.resources.R as UiR
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h900dp-160dpi")
class RemoteWorkspaceUiDecouplingTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val root = SupervisorJob()
    private val scope = CoroutineScope(root + Dispatchers.Default)
    private lateinit var account: FakeUiAccountConnection
    private lateinit var runtime: RemoteRuntime
    private val origin = "https://remote.example"
    private val accountId = "acct-ui-decouple"
    private val key = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 41 }, "AES")
        override fun delete() = Unit
    }

    @Before
    fun setup() {
        account = FakeUiAccountConnection()
        val audit = AndroidAuditStore(InMemoryAuditSink())
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
        val trust = DeveloperTrustMode()
        val host = thirdPartyOnlyHost(context, grants, trust, NativePluginLoader(trust), "ui-decoupling-tests")
        host.attach(
            PluginKernel(
                HostEnvelope(host.hostCapabilities),
                PhoneLimits(host.phoneCapabilities),
                host.runtimes,
                audit,
                trust,
                host.nativeLoader,
                host.selector,
                grants::currentKernelGrant,
                mediate = host::mediate,
                authorizeInvocation = host::authorizeKernelInvocation,
            )
        )
        runtime = RemoteRuntime(
            context = context,
            scope = scope,
            pluginHost = host,
            grants = grants,
            confirmLocally = { _, _, _ -> false },
            accountFactory = { account },
            localDocumentKeyProvider = key,
        )
    }

    @After
    fun teardown() {
        runBlocking {
            withTimeout(5_000) {
                if (::runtime.isInitialized) runtime.logout().join()
                root.cancelAndJoin()
            }
        }
    }

    @Test
    fun `登录页已登录态只展示主机与可用Agent绝不暴露工作区选择`() {
        runBlocking {
            withTimeout(10_000) {
                runtime.restoreSessionIfAvailable().join()
                val host1 = account.availableHosts.first()
                runtime.discover(host1).join()
            }
        }

        compose.setContent {
            OpenAndroidIntelligenceTheme(darkTheme = false, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    RemoteLoginScreen(
                        busy = false,
                        connected = true,
                        signedIn = true,
                        initialOrigin = origin,
                        error = null,
                        onOpenSettings = {},
                        onLogin = { _, _, _ -> },
                        onScanPairing = {},
                        onOAuthLogin = {},
                        endpointContent = { RemoteLoginAgentSelector(runtime) },
                    )
                }
            }
        }
        compose.waitForIdle()

        // 展示 Host 主机与可用 Agent
        compose.onNodeWithText("Host 主机").assertExists()
        compose.onNodeWithText("主力电脑").assertExists()
        compose.onNodeWithText(context.getString(UiR.string.remote_available_agents)).assertExists()
        compose.onNodeWithText("主力电脑 · DSH").assertExists()
        compose.onNodeWithText("主力电脑 · Codex").assertExists()

        // 绝不暴露任何工作区标题或路径
        compose.onNodeWithText("Agent 与工作区").assertDoesNotExist()
        compose.onNodeWithText("工作区一", substring = true).assertDoesNotExist()
        compose.onNodeWithText("工作区二", substring = true).assertDoesNotExist()
        compose.onNodeWithText("/repo/one", substring = true).assertDoesNotExist()
        compose.onNodeWithText("/repo/two", substring = true).assertDoesNotExist()
    }

    @Test
    fun `设置页连接与账号在已连接状态下切换其他Agent与同Agent工作区均即时生效`() {
        runBlocking {
            withTimeout(10_000) {
                runtime.restoreSessionIfAvailable().join()
                runtime.discover(account.availableHosts[0]).join()
                runtime.discover(account.availableHosts[1]).join()

                val initialAgent = AgentKey(RemoteServiceOrigin.fromCanonicalOrigin(origin), accountId, "host-1", BackendId("harness"))
                runtime.selectAgent(initialAgent).join()
                assertEquals(AgentConnectionPhase.CONNECTED, runtime.agentConnection.value.phase)
                assertEquals(initialAgent, runtime.agentConnection.value.activeAgent)
            }
        }

        compose.setContent {
            OpenAndroidIntelligenceTheme(darkTheme = false, dynamicColor = false) {
                RemoteConnectionSettingsScreen(
                    runtime = runtime,
                    presentation = SettingsPresentation.SCREEN,
                    onBack = {},
                    onOpenDiagnostics = {},
                )
            }
        }
        compose.waitForIdle()

        // 1) 已连接状态下，点击同 Agent 的另一个工作区：复用通道且切换生效
        val channelCountBeforeWs = account.channels.size
        compose.onNodeWithText("工作区二", substring = true).performScrollTo().performClick()
        compose.waitUntil(5_000) {
            runtime.workspaceContext.value.activeWorkspace?.workspaceId == "two"
        }
        assertEquals("同 Agent 切工作区不应新建连接", channelCountBeforeWs, account.channels.size)

        // 2) 已连接状态下，点击另一台 Host 的 Codex Agent：即时生效不被已连接拦截
        compose.onNodeWithText("工作站 · Codex").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            runtime.agentConnection.value.activeAgent?.hostId == "host-2" &&
                runtime.agentConnection.value.activeAgent?.backendId?.value == "codex" &&
                runtime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED
        }
        assertNull(runtime.agentConnection.value.errorCode)
    }

    @Test
    fun `离线横幅就地重连期间显示原位加载并防重复点击`() {
        var offline by mutableStateOf(true)
        var reconnecting by mutableStateOf(true)
        var retries = 0

        compose.setContent {
            OpenAndroidIntelligenceTheme(darkTheme = false, dynamicColor = false) {
                RemoteWorkbenchNotices(
                    offline = offline,
                    error = null,
                    onRetry = { retries++ },
                    onDismiss = {},
                    reconnecting = reconnecting,
                )
            }
        }

        compose.onNodeWithText(context.getString(UiR.string.conn_status_reconnecting_in_place)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(UiR.string.conn_action_reconnect)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(UiR.string.conn_action_reconnect)).performClick()
        compose.runOnIdle { assertEquals("重连进行中必须防重复点击", 0, retries) }

        compose.runOnIdle { reconnecting = false }
        compose.onNodeWithText(context.getString(UiR.string.conn_status_offline_mode_hint)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(UiR.string.conn_action_reconnect)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, retries); offline = false }
        compose.onNodeWithText(context.getString(UiR.string.conn_status_offline_mode_hint)).assertDoesNotExist()
    }

    @Test
    fun `凭据撤销时展示重新登录说明且宽限期不弹出离线横幅`() {
        var signedIn by mutableStateOf(false)
        var error by mutableStateOf<String?>("DEVICE_REVOKED")
        var offlineBannerVisible by mutableStateOf(false)

        compose.setContent {
            OpenAndroidIntelligenceTheme(darkTheme = false, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    if (!signedIn) {
                        RemoteLoginScreen(
                            busy = false,
                            connected = false,
                            signedIn = false,
                            initialOrigin = origin,
                            error = error,
                            onOpenSettings = {},
                            onLogin = { _, _, _ -> },
                            onScanPairing = {},
                            onOAuthLogin = {},
                        )
                    } else {
                        RemoteWorkbenchNotices(
                            offline = offlineBannerVisible,
                            error = null,
                            onRetry = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }

        // 凭据被明确撤销：停留在登录页，呈现清晰中文说明与登录表单
        compose.onNodeWithText(context.getString(UiR.string.conn_toast_session_revoked)).assertIsDisplayed()
        compose.onNodeWithTag("remote-login-card").assertIsDisplayed()

        // 宽限期内：不显示离线横幅
        compose.runOnIdle { signedIn = true; error = null; offlineBannerVisible = false }
        compose.onNodeWithText(context.getString(UiR.string.conn_status_offline_mode_hint)).assertDoesNotExist()
    }

    @Test
    fun `诊断页仅在Host故障时展示注册凭据与控制阶段固定错误码`() {
        compose.setContent {
            OpenAndroidIntelligenceTheme(darkTheme = false, dynamicColor = false) {
                RemoteConnectionDiagnosticsScreen(runtime, SettingsPresentation.SCREEN, {})
            }
        }

        compose.onNodeWithTag("remote-host-failure").assertDoesNotExist()

        val failures = listOf(
            RemoteHostFailureStage.REGISTRATION to "REMOTE_ROLE_REGISTRATION_FAILED",
            RemoteHostFailureStage.CREDENTIAL to "REMOTE_CREDENTIAL_MISSING",
            RemoteHostFailureStage.CONTROL to "CONTROL_CONNECTION_FAILED",
        )
        failures.forEach { (stage, code) ->
            compose.runOnIdle { account.hostFailure.value = RemoteHostFailure(stage = stage, code = code) }
            compose.onNodeWithTag("remote-host-failure").assertIsDisplayed()
            compose.onNodeWithText(context.getString(when (stage) {
                RemoteHostFailureStage.REGISTRATION -> UiR.string.remote_host_failure_stage_registration
                RemoteHostFailureStage.CREDENTIAL -> UiR.string.remote_host_failure_stage_credential
                RemoteHostFailureStage.CONTROL -> UiR.string.remote_host_failure_stage_control
            })).assertIsDisplayed()
            compose.onNodeWithText(context.getString(UiR.string.remote_host_failure_code, code)).assertIsDisplayed()
        }

        compose.runOnIdle { account.hostFailure.value = null }
        compose.onNodeWithTag("remote-host-failure").assertDoesNotExist()
    }

    @Test
    fun `自部署双角色注册均失败时登录页显示固定错误`() {
        account.loginResult = failedBothRegistration()

        runBlocking { runtime.login(origin, "user@example.com", "secret").join() }

        assertEquals("REMOTE_ROLE_REGISTRATION_FAILED", runtime.agentConnection.value.errorCode)
        assertNull(runtime.agentConnection.value.account)
        compose.setContent {
            OpenAndroidIntelligenceTheme(darkTheme = false, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    RemoteLoginScreen(
                        busy = false,
                        connected = false,
                        signedIn = false,
                        initialOrigin = origin,
                        error = runtime.agentConnection.value.errorCode,
                        onOpenSettings = {},
                        onLogin = { _, _, _ -> },
                        onScanPairing = {},
                        onOAuthLogin = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("remote-login-card").assertIsDisplayed()
        compose.onNodeWithText("无法连接 Remote", substring = true).assertIsDisplayed()
    }

    @Test
    fun `官方双角色注册均失败时错误码回到登录页`() {
        account.tokenLoginResult = failedBothRegistration()

        val failure = runBlocking {
            runCatching { runtime.completeExternalLogin("external-token", "login-attempt") }.exceptionOrNull()
        }
        assertEquals("REMOTE_ROLE_REGISTRATION_FAILED", (failure as? RemoteRpcException)?.code)
        assertNull(runtime.agentConnection.value.account)
        compose.setContent {
            OpenAndroidIntelligenceTheme(darkTheme = false, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    RemoteLoginScreen(
                        busy = false,
                        connected = false,
                        signedIn = false,
                        initialOrigin = origin,
                        error = (failure as RemoteRpcException).code,
                        onOpenSettings = {},
                        onLogin = { _, _, _ -> },
                        onScanPairing = {},
                        onOAuthLogin = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("remote-login-card").assertIsDisplayed()
        compose.onNodeWithText("无法连接 Remote", substring = true).assertIsDisplayed()
    }

    private inner class FakeUiAccountConnection : RemoteAccountConnection {
        override val account = MutableStateFlow<RemoteActiveAccount?>(null)
        override val state = MutableStateFlow(RemoteAccountConnectionState.CONNECTED)
        override val hostState = MutableStateFlow(RemoteHostConnectionState.ONLINE)
        override val hostFailure = MutableStateFlow<RemoteHostFailure?>(null)
        val channels = CopyOnWriteArrayList<FakeUiChannel>()
        private val peers = mutableMapOf<String, AuthenticatedRemotePeer>()
        var loginResult: RemoteLoginResult? = null
        var tokenLoginResult: RemoteLoginResult? = null
        var restorable: RemoteActiveAccount? = RemoteActiveAccount(origin, accountId, 1)
        val availableHosts = listOf(
            RemoteHostDevice("host-1", "主力电脑", "linux", "membership", true),
            RemoteHostDevice("host-2", "工作站", "linux", "membership", true),
        )

        override fun peekRestorableAccount(): RemoteActiveAccount? = account.value ?: restorable
        override suspend fun login(origin: String, email: String, password: String): RemoteLoginResult =
            checkNotNull(loginResult) { "unused" }
        override suspend fun loginAccountToken(origin: String, token: String, loginAttemptId: String): RemoteLoginResult =
            checkNotNull(tokenLoginResult) { "unused" }
        override suspend fun restore(): RemoteActiveAccount? = restorable?.also { account.value = it }
        override suspend fun listHosts(): List<RemoteHostDevice> = availableHosts
        override suspend fun connect(hostDeviceId: String): RemoteRpcChannel =
            FakeUiChannel(channels.size + 1, hostDeviceId).also { ch ->
                channels += ch
                peers[ch.scope.connectionId] = AuthenticatedRemotePeer(
                    ch.scope,
                    "sha256:" + "1".repeat(64),
                    "sha256:" + "2".repeat(64),
                    RemoteRole.CLIENT,
                )
            }

        override fun authenticatedPeer(connectionId: String): AuthenticatedRemotePeer =
            peers[connectionId] ?: throw RemoteRpcException("CONNECTION_BINDING_MISMATCH")

        override suspend fun logout() {
            account.value = null
            restorable = null
        }

        fun releasePeer(id: String) {
            peers.remove(id)
        }

        private fun failedBothRegistration() = RemoteLoginResult(
            accountId = accountId,
            client = RemoteRoleRegistration(
                role = RemoteRole.CLIENT,
                state = RemoteRoleRegistrationState.FAILED,
                errorCode = "REMOTE_ROLE_REGISTRATION_FAILED",
            ),
            host = RemoteRoleRegistration(
                role = RemoteRole.HOST,
                state = RemoteRoleRegistrationState.FAILED,
                errorCode = "REMOTE_ROLE_REGISTRATION_FAILED",
            ),
        )
    }

    private inner class FakeUiChannel(gen: Int, hostId: String) : RemoteRpcChannel {
        override val scope = RemoteRpcConnectionScope(origin, accountId, hostId, "client", "ui-conn-$gen", gen.toLong())
        override val capabilities = setOf("harness.remote.v3", "codex.appserver.v1")
        override val workspaceTypes = listOf(
            Json.of(
                mapOf(
                    "id" to "codex",
                    "name" to "Codex",
                    "capability" to "codex.appserver.v1",
                    "available" to true,
                )
            ) as JsonValue.JObject
        )
        override val events = MutableSharedFlow<RemoteRpcEvent>(extraBufferCapacity = 16)
        @Volatile var closed = false

        override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue {
            if (closed) throw RemoteRpcException("TRANSPORT_CLOSED", RemoteDeliveryOutcome.NOT_SENT)
            return when (method) {
                "harness.remote.stream.open" -> {
                    val endpoint = JsonFields.string(params, "endpoint")
                    val streamId = JsonFields.string(params, "streamId")
                    if (endpoint == "workspace/follow") {
                        events.emit(
                            RemoteRpcEvent(
                                "harness.remote.frame",
                                Json.of(
                                    mapOf(
                                        "streamId" to streamId,
                                        "value" to mapOf(
                                            "type" to "baseline",
                                            "value" to mapOf(
                                                "items" to listOf(
                                                    mapOf("workspaceId" to "one", "title" to "工作区一", "path" to "/repo/one", "sessionIds" to listOf("s1")),
                                                    mapOf("workspaceId" to "two", "title" to "工作区二", "path" to "/repo/two", "sessionIds" to listOf("s2")),
                                                )
                                            ),
                                        ),
                                    )
                                ),
                            )
                        )
                    }
                    Json.of(emptyMap<String, Any>())
                }
                "harness.remote.stream.close" -> Json.of(emptyMap<String, Any>())
                "harness.remote.call" -> {
                    val value = when (JsonFields.string(params, "endpoint")) {
                        "session/list" -> Json.of(
                            mapOf(
                                "items" to listOf(
                                    mapOf("sessionId" to "s1", "title" to "一号会话", "updatedAt" to 100),
                                    mapOf("sessionId" to "s2", "title" to "二号会话", "updatedAt" to 200),
                                )
                            )
                        )
                        "session/page" -> Json.of(mapOf("cursor" to 0, "records" to emptyList<Any>(), "hasMore" to false))
                        "session/models" -> Json.of(mapOf("groups" to emptyList<Any>()))
                        "commands/list" -> Json.of(mapOf("items" to emptyList<Any>()))
                        else -> Json.of(emptyMap<String, Any>())
                    }
                    Json.of(mapOf("ok" to true, "value" to value))
                }
                "codex.app.call" -> when (JsonFields.string(params, "method")) {
                    "project/list" -> Json.of(
                        mapOf(
                            "data" to listOf(
                                mapOf("id" to "cx-one", "name" to "Codex项目", "roots" to listOf(mapOf("path" to "/cx-one"))),
                            )
                        )
                    )
                    "thread/list" -> Json.of(
                        mapOf(
                            "data" to listOf(
                                mapOf("id" to "cx-1", "name" to "Codex会话", "cwd" to "/cx-one", "createdAt" to 1, "updatedAt" to 10)
                            )
                        )
                    )
                    "dsh/sessionHistory" -> Json.of(mapOf("cursor" to 0, "records" to emptyList<Any>(), "hasMore" to false))
                    "model/list" -> Json.of(mapOf("data" to emptyList<Any>()))
                    "thread/read" -> Json.of(mapOf("thread" to mapOf("id" to "cx-1")))
                    else -> Json.of(emptyMap<String, Any>())
                }
                "codex.app.stream.open", "codex.app.stream.close" -> Json.of(emptyMap<String, Any>())
                else -> Json.of(emptyMap<String, Any>())
            }
        }

        override suspend fun close() {
            closed = true
            account.releasePeer(scope.connectionId)
        }
    }
}
