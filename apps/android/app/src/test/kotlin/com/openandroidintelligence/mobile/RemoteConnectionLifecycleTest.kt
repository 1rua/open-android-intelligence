package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.data.remote.RemoteWorkspace
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.remote.auth.RemoteLoginResult
import com.openandroidintelligence.gateway.remote.authproduction.RemoteActiveAccount
import com.openandroidintelligence.gateway.remote.identity.RemoteRole
import com.openandroidintelligence.gateway.remote.network.*
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.plugins.thirdPartyOnlyHost
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 连接生命周期双层解耦、Cache-First 冷启动、宽限期与就地重连回归。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemoteConnectionLifecycleTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val root = SupervisorJob()
    private val scope = CoroutineScope(root + Dispatchers.Unconfined)
    private lateinit var account: FakeAccountConnection
    private lateinit var runtime: RemoteRuntime
    private lateinit var grants: PairingGrantStateHolder
    private val origin = "https://remote.example"
    private val accountId = "acct-lifecycle"
    private val key = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 31 }, "AES")
        override fun delete() = Unit
    }

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        account = FakeAccountConnection()
        runtime = buildRuntime()
    }

    @After
    fun teardown() = runBlocking {
        if (::runtime.isInitialized) runtime.logout().join()
        root.cancelAndJoin()
        Dispatchers.resetMain()
    }

    private fun buildRuntime(
        policy: ConnectionRecoveryPolicy = ConnectionRecoveryPolicy(
            offlineGracePeriodMillis = 200L,
            graceRetryAttempts = 2,
            graceRetryDelayMillis = 20L,
            backgroundReconnectInitialDelayMillis = 40L,
            backgroundReconnectMaxDelayMillis = 120L,
        ),
        delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ): RemoteRuntime {
        val audit = AndroidAuditStore(InMemoryAuditSink())
        grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
        val trust = DeveloperTrustMode()
        val host = thirdPartyOnlyHost(context, grants, trust, NativePluginLoader(trust), "lifecycle-tests")
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
        return RemoteRuntime(
            context = context,
            scope = scope,
            pluginHost = host,
            grants = grants,
            confirmLocally = { _, _, _ -> false },
            accountFactory = { account },
            localDocumentKeyProvider = key,
            recoveryPolicy = policy,
            delayFn = delayFn,
        )
    }

    private suspend fun eventually(check: () -> Boolean) = withTimeout(5_000) {
        while (!check()) delay(10)
    }

    @Test
    fun `已连接状态下切换不同Agent跨Host和同Host跨Backend均即时生效且不清空控制器`() = runBlocking {
        runtime.restoreSessionIfAvailable().join()
        val host1Harness = AgentKey(RemoteServiceOrigin.fromCanonicalOrigin(origin), accountId, "host-1", BackendId("harness"))
        val host2Codex = AgentKey(RemoteServiceOrigin.fromCanonicalOrigin(origin), accountId, "host-2", BackendId("codex"))
        val host2Harness = AgentKey(RemoteServiceOrigin.fromCanonicalOrigin(origin), accountId, "host-2", BackendId("harness"))

        runtime.selectAgent(host1Harness).join()
        eventually {
            runtime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                runtime.controller.value?.state?.value?.isOnline == true
        }
        val observedNullAfterOnline = mutableListOf<Boolean>()
        val observer = scope.launch {
            runtime.controller.collect { observedNullAfterOnline += (it == null) }
        }
        try {
            // 跨 Host 切换：host-1 harness -> host-2 codex
            runtime.selectAgent(host2Codex).join()
            eventually {
                runtime.agentConnection.value.activeAgent == host2Codex &&
                    runtime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                    runtime.workspaceContext.value.activeWorkspace?.hostId == "host-2" &&
                    runtime.workspaceContext.value.activeWorkspace?.backendId?.value == "codex"
            }
            assertTrue("跨 Host 切换后旧通道必须关闭", account.channels.first().closed)
            assertNull("切换过程不应被拦截报错", runtime.agentConnection.value.errorCode)

            // 同 Host 跨 Backend 切换：host-2 codex -> host-2 harness
            val channelCountBeforeBackendSwitch = account.channels.size
            runtime.selectAgent(host2Harness).join()
            eventually {
                runtime.agentConnection.value.activeAgent == host2Harness &&
                    runtime.workspaceContext.value.activeWorkspace?.backendId?.value == "harness" &&
                    runtime.controller.value?.state?.value?.isOnline == true
            }
            assertEquals("同 Host 切换 Backend 应复用健康底层通道", channelCountBeforeBackendSwitch, account.channels.size)
            assertFalse("切换到不同 Agent 期间不能先把 controller 清成 null 踢回登录页", observedNullAfterOnline.any { it })
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test
    fun `同一Agent下切换不同工作区时底层连接引用不变且不触发断连重连`() = runBlocking {
        runtime.restoreSessionIfAvailable().join()
        val agent = AgentKey(RemoteServiceOrigin.fromCanonicalOrigin(origin), accountId, "host-1", BackendId("harness"))
        runtime.selectAgent(agent).join()
        eventually {
            runtime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                runtime.workspaceContext.value.workspaces.size == 2
        }
        val initialChannel = account.channels.single()
        val initialConnectCalls = account.connectCalls
        val firstWorkspace = runtime.workspaceContext.value.workspaces.first { it.endpoint.key.workspaceId == "one" }
        val secondWorkspace = runtime.workspaceContext.value.workspaces.first { it.endpoint.key.workspaceId == "two" }

        runtime.selectWorkspace(firstWorkspace).join()
        val firstController = runtime.controller.value!!
        firstController.editDraft("工作区一草稿")

        runtime.selectWorkspace(secondWorkspace).join()
        val secondController = runtime.controller.value!!

        assertEquals("同 Agent 切工作区严禁再次调用 connect()", initialConnectCalls, account.connectCalls)
        assertFalse("同 Agent 切工作区严禁关闭底层 RPC 通道", initialChannel.closed)
        assertSame("底层 RPC 通道对象引用必须完全不变", initialChannel, account.channels.single())
        assertEquals("two", runtime.workspaceContext.value.activeWorkspace?.workspaceId)
        assertEquals(agent, runtime.agentConnection.value.activeAgent)
        assertEquals("", secondController.state.value.draft)
        assertFalse("旧工作区控制器应已关闭", firstController.state.value.isOnline)

        // 切回工作区 one 仍复用同一条通道且草稿独立保留
        runtime.selectWorkspace(firstWorkspace).join()
        assertEquals(initialConnectCalls, account.connectCalls)
        assertFalse(initialChannel.closed)
        assertEquals("工作区一草稿", runtime.controller.value!!.state.value.draft)
    }

    private inner class FakeAccountConnection : RemoteAccountConnection {
        override val account = MutableStateFlow<RemoteActiveAccount?>(null)
        override val state = MutableStateFlow(RemoteAccountConnectionState.READY)
        override val hostState = MutableStateFlow(RemoteHostConnectionState.ONLINE)
        var restorableAccount: RemoteActiveAccount? = RemoteActiveAccount(origin, accountId, 1)
        val channels = CopyOnWriteArrayList<FakeRpcChannel>()
        var connectCalls = 0
        var connectFailuresBeforeSuccess = 0
        var nextConnectError: Exception? = null
        var availableHosts = listOf(
            RemoteHostDevice("host-1", "主力电脑", "linux", "membership", true),
            RemoteHostDevice("host-2", "工作站", "linux", "membership", true),
        )
        private val peers = mutableMapOf<String, AuthenticatedRemotePeer>()

        override fun peekRestorableAccount(): RemoteActiveAccount? = account.value ?: restorableAccount

        override suspend fun login(origin: String, email: String, password: String): RemoteLoginResult =
            error("测试不走密码登录")

        override suspend fun restore(): RemoteActiveAccount? {
            nextConnectError?.let { throw it }
            return restorableAccount?.also { account.value = it }
        }

        override suspend fun listHosts(): List<RemoteHostDevice> = availableHosts

        override suspend fun connect(hostDeviceId: String): RemoteRpcChannel {
            connectCalls++
            if (connectFailuresBeforeSuccess > 0) {
                connectFailuresBeforeSuccess--
                throw RemoteRpcException("NETWORK_UNREACHABLE")
            }
            nextConnectError?.let { throw it }
            return FakeRpcChannel(channels.size + 1, hostDeviceId).also { ch ->
                channels += ch
                peers[ch.scope.connectionId] = AuthenticatedRemotePeer(
                    ch.scope,
                    "sha256:" + "1".repeat(64),
                    "sha256:" + "2".repeat(64),
                    RemoteRole.CLIENT,
                )
            }
        }

        override fun authenticatedPeer(connectionId: String): AuthenticatedRemotePeer =
            peers[connectionId] ?: throw RemoteRpcException("CONNECTION_BINDING_MISMATCH")

        override suspend fun logout() {
            account.value = null
            restorableAccount = null
        }

        fun releasePeer(connectionId: String) {
            peers.remove(connectionId)
        }
    }

    private inner class FakeRpcChannel(generation: Int, hostDeviceId: String) : RemoteRpcChannel {
        override val scope = RemoteRpcConnectionScope(
            origin,
            accountId,
            hostDeviceId,
            "client",
            "connection-$generation",
            generation.toLong(),
        )
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
        override val events = MutableSharedFlow<RemoteRpcEvent>(extraBufferCapacity = 32)
        @Volatile var closed = false
        val invokedMethods = CopyOnWriteArrayList<String>()

        override suspend fun invoke(
            method: String,
            params: JsonValue.JObject,
            requestId: String,
            timeoutMs: Long,
        ): JsonValue {
            if (closed) throw RemoteRpcException("TRANSPORT_CLOSED", RemoteDeliveryOutcome.NOT_SENT)
            invokedMethods += method
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
                                                    mapOf("workspaceId" to "one", "title" to "工作区一", "path" to "/one", "sessionIds" to listOf("s1")),
                                                    mapOf("workspaceId" to "two", "title" to "工作区二", "path" to "/two", "sessionIds" to listOf("s2")),
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
                                mapOf("id" to "cx-one", "name" to "Codex一", "roots" to listOf(mapOf("path" to "/cx-one"))),
                                mapOf("id" to "cx-two", "name" to "Codex二", "roots" to listOf(mapOf("path" to "/cx-two"))),
                            )
                        )
                    )
                    "thread/list" -> Json.of(
                        mapOf(
                            "data" to listOf(
                                mapOf("id" to "cx-thread-1", "name" to "Codex会话", "cwd" to "/cx-one", "createdAt" to 1, "updatedAt" to 10)
                            )
                        )
                    )
                    "dsh/sessionHistory" -> Json.of(mapOf("cursor" to 0, "records" to emptyList<Any>(), "hasMore" to false))
                    "model/list" -> Json.of(mapOf("data" to emptyList<Any>()))
                    "thread/read" -> Json.of(mapOf("thread" to mapOf("id" to "cx-thread-1")))
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
