package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.data.remote.RemoteWorkspace
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
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
        customScope: CoroutineScope = scope,
        customAccount: FakeAccountConnection = account,
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
            scope = customScope,
            pluginHost = host,
            grants = grants,
            confirmLocally = { _, _, _ -> false },
            accountFactory = { customAccount },
            localDocumentKeyProvider = key,
            recoveryPolicy = policy,
            delayFn = delayFn,
        )
    }

    private suspend fun seedSavedWorkspaceWithCachedTimeline(
        workspaceId: String = "one",
        sessionId: String = "s1",
        cachedText: String = "冷启动本地缓存回复",
    ) {
        val seedAccount = FakeAccountConnection().apply {
            initialHistoryText = cachedText
        }
        val seedRoot = SupervisorJob()
        val seedScope = CoroutineScope(seedRoot + Dispatchers.Unconfined)
        val seedRuntime = buildRuntime(customScope = seedScope, customAccount = seedAccount)
        try {
            seedRuntime.restoreSessionIfAvailable().join()
            val targetAgent = AgentKey(RemoteServiceOrigin.fromCanonicalOrigin(origin), accountId, "host-1", BackendId("harness"))
            seedRuntime.selectAgent(targetAgent).join()
            val targetWs = seedRuntime.workspaceContext.value.workspaces.first { it.endpoint.key.workspaceId == workspaceId }
            seedRuntime.selectWorkspace(targetWs, initialThreadId = RemoteLocalIds.encode(sessionId)).join()
            eventually {
                val st = seedRuntime.controller.value?.state?.value ?: return@eventually false
                val timeline = (st.timeline as? Loadable.Ready)?.value.orEmpty()
                st.activeThreadId == RemoteLocalIds.encode(sessionId) &&
                    (st.threads as? Loadable.Ready)?.value?.isNotEmpty() == true &&
                    timeline.any { msg -> msg.text == cachedText }
            }
        } finally {
            seedRuntime.closeRuntimeWithoutClearingCacheForTest()
            seedRoot.cancelAndJoin()
        }
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

    @Test
    fun `打开会话菜单或切入Agent时自动拉取当前Agent全部工作区与各工作区会话无需二次点击Host`() = runBlocking {
        runtime.restoreSessionIfAvailable().join()
        val agent = AgentKey(RemoteServiceOrigin.fromCanonicalOrigin(origin), accountId, "host-1", BackendId("harness"))

        // 不调用 discover(host)，直接切入 Agent
        runtime.selectAgent(agent).join()
        eventually {
            val ctx = runtime.workspaceContext.value
            ctx.catalogPhase == WorkspaceCatalogPhase.READY &&
                ctx.workspaces.size == 2 &&
                ctx.workspaces.all { (ctx.sessionsByWorkspace[it.endpoint.key] as? Loadable.Ready)?.value?.isNotEmpty() == true }
        }

        val ctx = runtime.workspaceContext.value
        val wsOne = ctx.workspaces.first { it.endpoint.key.workspaceId == "one" }
        val wsTwo = ctx.workspaces.first { it.endpoint.key.workspaceId == "two" }
        val sessionsOne = (ctx.sessionsByWorkspace[wsOne.endpoint.key] as Loadable.Ready).value
        val sessionsTwo = (ctx.sessionsByWorkspace[wsTwo.endpoint.key] as Loadable.Ready).value
        assertEquals(listOf("一号会话"), sessionsOne.map { it.title })
        assertEquals(listOf("二号会话"), sessionsTwo.map { it.title })

        // 模拟服务端工作区二新增会话，打开菜单触发 hydrateWorkspaces(force = true) 自动刷新
        account.extraWorkspaceTwoSessions += mapOf("sessionId" to "s3", "title" to "二号新增会话", "updatedAt" to 300)
        runtime.hydrateWorkspaces(force = true).join()
        eventually {
            val updated = (runtime.workspaceContext.value.sessionsByWorkspace[wsTwo.endpoint.key] as? Loadable.Ready)?.value.orEmpty()
            updated.any { it.title == "二号新增会话" }
        }
        val refreshedTwo = (runtime.workspaceContext.value.sessionsByWorkspace[wsTwo.endpoint.key] as Loadable.Ready).value
        assertTrue(refreshedTwo.any { it.title == "二号会话" })
        assertTrue(refreshedTwo.any { it.title == "二号新增会话" })
    }

    @Test
    fun `冷启动命中本地缓存时首帧直达会话页展示缓存且宽限期内不弹离线横幅`() = runBlocking {
        seedSavedWorkspaceWithCachedTimeline(cachedText = "冷启动本地缓存回复")
        val firstConnectGate = CompletableDeferred<Unit>()
        val graceDelayGate = CompletableDeferred<Unit>()
        val coldAccount = FakeAccountConnection().apply {
            connectGate = firstConnectGate
            connectFailuresBeforeSuccess = 1
        }
        val coldRoot = SupervisorJob()
        val coldScope = CoroutineScope(coldRoot + Dispatchers.Default)
        val offlineConfirmedObserved = CopyOnWriteArrayList<Boolean>()
        val coldRuntime = buildRuntime(
            policy = ConnectionRecoveryPolicy(
                offlineGracePeriodMillis = 2_000L,
                graceRetryAttempts = 2,
                graceRetryDelayMillis = 30L,
            ),
            delayFn = { ms ->
                if (ms == 30L) graceDelayGate.await() else kotlinx.coroutines.delay(ms)
            },
            customScope = coldScope,
            customAccount = coldAccount,
        )
        val observer = scope.launch {
            coldRuntime.agentConnection.collect { offlineConfirmedObserved += it.offlineConfirmed }
        }
        try {
            // 第 0 帧立即直达会话页，不经过登录页，且尚未确认离线
            val firstController = coldRuntime.controller.value
            assertNotNull("命中缓存冷启动首帧必须立即挂载会话控制器", firstController)
            assertEquals(AgentConnectionPhase.CONNECTING, coldRuntime.agentConnection.value.phase)
            assertFalse("宽限期内严禁过早确认离线", coldRuntime.agentConnection.value.offlineConfirmed)

            val firstThreads = (firstController!!.state.value.threads as? Loadable.Ready)?.value.orEmpty()
            assertTrue("首帧应立即展示本地缓存会话列表", firstThreads.any { it.title == "一号会话" })
            val firstMessages = (firstController.state.value.timeline as? Loadable.Ready)?.value.orEmpty()
            assertTrue(
                "首帧应立即展示本地缓存消息历史",
                firstMessages.any { msg -> msg.text == "冷启动本地缓存回复" },
            )

            // 放行首次建链（失败一次）并卡在宽限重试等待中：仍不弹离线横幅
            firstConnectGate.complete(Unit)
            eventually { coldAccount.connectCalls >= 1 }
            coldAccount.connectGate = null
            assertFalse("宽限期内首连失败不应弹出离线横幅", coldRuntime.agentConnection.value.offlineConfirmed)
            assertEquals(AgentConnectionPhase.CONNECTING, coldRuntime.agentConnection.value.phase)
            assertFalse("整个宽限期尚未耗尽前从未出现过 offlineConfirmed=true", offlineConfirmedObserved.any { it })
            graceDelayGate.complete(Unit)
            eventually { coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED }
        } finally {
            firstConnectGate.complete(Unit)
            graceDelayGate.complete(Unit)
            observer.cancelAndJoin()
            coldRuntime.logout().join()
            coldRoot.cancelAndJoin()
        }
    }

    @Test
    fun `冷启动瞬时网络抖动在宽限期内重试成功时原地升级在线且不重建控制器`() = runBlocking {
        seedSavedWorkspaceWithCachedTimeline(cachedText = "首帧已有缓存回复")
        val coldAccount = FakeAccountConnection().apply {
            connectFailuresBeforeSuccess = 1
            initialHistoryText = "首帧已有缓存回复"
            incrementalReplyText = "在线恢复同步的新增消息"
        }
        val coldRoot = SupervisorJob()
        val coldScope = CoroutineScope(coldRoot + Dispatchers.Default)
        val offlineConfirmedObserved = CopyOnWriteArrayList<Boolean>()
        val coldRuntime = buildRuntime(
            policy = ConnectionRecoveryPolicy(
                offlineGracePeriodMillis = 2_000L,
                graceRetryAttempts = 2,
                graceRetryDelayMillis = 15L,
            ),
            customScope = coldScope,
            customAccount = coldAccount,
        )
        val initialController = coldRuntime.controller.value!!
        val controllerInstances = CopyOnWriteArrayList<WorkbenchController?>()
        val observer = scope.launch {
            launch { coldRuntime.agentConnection.collect { offlineConfirmedObserved += it.offlineConfirmed } }
            launch { coldRuntime.controller.collect { controllerInstances += it } }
        }
        try {
            eventually {
                coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                    initialController.state.value.isOnline
            }
            assertSame("在线升级必须原地复用同一个 WorkbenchController", initialController, coldRuntime.controller.value)
            assertTrue("全程不应销毁或清空 controller", controllerInstances.all { it === initialController })
            assertFalse("宽限期内恢复成功全程不应弹出离线横幅", offlineConfirmedObserved.any { it })
            assertFalse("瞬时抖动后不应置位 offlineConfirmed", coldRuntime.agentConnection.value.offlineConfirmed)
            eventually {
                val timeline = (initialController.state.value.timeline as? Loadable.Ready)?.value.orEmpty()
                timeline.any { msg -> msg.text == "在线恢复同步的新增消息" }
            }
        } finally {
            observer.cancelAndJoin()
            coldRuntime.logout().join()
            coldRoot.cancelAndJoin()
        }
    }

    @Test
    fun `冷启动宽限期耗尽降级离线后由后台静默重连恢复并原地热升级`() = runBlocking {
        seedSavedWorkspaceWithCachedTimeline()
        val backgroundReconnectGate = CompletableDeferred<Unit>()
        val coldAccount = FakeAccountConnection().apply {
            nextConnectError = RemoteRpcException("NETWORK_UNREACHABLE")
        }
        val coldRoot = SupervisorJob()
        val coldScope = CoroutineScope(coldRoot + Dispatchers.Default)
        val coldRuntime = buildRuntime(
            policy = ConnectionRecoveryPolicy(
                offlineGracePeriodMillis = 200L,
                graceRetryAttempts = 1,
                graceRetryDelayMillis = 10L,
                backgroundReconnectInitialDelayMillis = 40L,
                backgroundReconnectMaxDelayMillis = 80L,
            ),
            delayFn = { ms ->
                if (ms >= 40L) backgroundReconnectGate.await() else kotlinx.coroutines.delay(ms)
            },
            customScope = coldScope,
            customAccount = coldAccount,
        )
        val initialController = coldRuntime.controller.value!!
        try {
            eventually {
                coldRuntime.agentConnection.value.phase == AgentConnectionPhase.OFFLINE &&
                    coldRuntime.agentConnection.value.offlineConfirmed
            }
            assertSame("降级离线时不得销毁控制器", initialController, coldRuntime.controller.value)
            assertFalse(initialController.state.value.isOnline)

            // 远端恢复可达，放行后台静默重连轮次
            coldAccount.nextConnectError = null
            backgroundReconnectGate.complete(Unit)
            eventually {
                coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                    !coldRuntime.agentConnection.value.offlineConfirmed &&
                    initialController.state.value.isOnline
            }
            assertSame("后台重连成功必须原地升级同一个控制器", initialController, coldRuntime.controller.value)
        } finally {
            backgroundReconnectGate.complete(Unit)
            coldRuntime.logout().join()
            coldRoot.cancelAndJoin()
        }
    }

    @Test
    fun `离线态点击重试全程停留在会话页不踢回登录页且防重复触发`() = runBlocking {
        seedSavedWorkspaceWithCachedTimeline()
        val backgroundReconnectGate = CompletableDeferred<Unit>()
        val coldAccount = FakeAccountConnection().apply {
            nextConnectError = RemoteRpcException("NETWORK_UNREACHABLE")
        }
        val coldRoot = SupervisorJob()
        val coldScope = CoroutineScope(coldRoot + Dispatchers.Default)
        val coldRuntime = buildRuntime(
            policy = ConnectionRecoveryPolicy(
                offlineGracePeriodMillis = 100L,
                graceRetryAttempts = 0,
                graceRetryDelayMillis = 10L,
                backgroundReconnectInitialDelayMillis = 60_000L,
                backgroundReconnectMaxDelayMillis = 60_000L,
            ),
            delayFn = { ms ->
                if (ms >= 60_000L) backgroundReconnectGate.await() else kotlinx.coroutines.delay(ms)
            },
            customScope = coldScope,
            customAccount = coldAccount,
        )
        val beforeController = coldRuntime.controller.value!!
        val reconnectGate = CompletableDeferred<Unit>()
        val controllerHistory = CopyOnWriteArrayList<WorkbenchController?>()
        val collector = scope.launch {
            coldRuntime.controller.collect { controllerHistory += it }
        }
        try {
            eventually {
                coldRuntime.agentConnection.value.phase == AgentConnectionPhase.OFFLINE &&
                    coldRuntime.agentConnection.value.offlineConfirmed
            }

            // 1) 重连进行中：展示 reconnectingInPlace 且防重复点击
            coldAccount.connectGate = reconnectGate
            val callsBeforeRetry = coldAccount.connectCalls
            val firstRetryJob = coldRuntime.reconnectInPlace()
            assertTrue("重连进行中应置位 reconnectingInPlace", coldRuntime.agentConnection.value.reconnectingInPlace)
            assertEquals(AgentConnectionPhase.RECONNECTING, coldRuntime.agentConnection.value.phase)
            eventually { coldAccount.connectCalls == callsBeforeRetry + 1 }

            val secondRetryJob = coldRuntime.reconnectInPlace()
            assertSame("防重入应复用同一重连任务而不重复发起", firstRetryJob, secondRetryJob)

            // 放行但依然失败：停留在会话页，不回登录页
            reconnectGate.complete(Unit)
            firstRetryJob.join()
            assertEquals("单次重连失败仅应发起 1 次建链请求", callsBeforeRetry + 1, coldAccount.connectCalls)
            assertEquals(AgentConnectionPhase.OFFLINE, coldRuntime.agentConnection.value.phase)
            assertTrue(coldRuntime.agentConnection.value.offlineConfirmed)
            assertFalse(coldRuntime.agentConnection.value.reconnectingInPlace)
            assertSame("重连失败后必须保留原工作台控制器", beforeController, coldRuntime.controller.value)

            // 2) 恢复网络并再次就地重试：成功升级在线并收起离线标志
            coldAccount.connectGate = null
            coldAccount.nextConnectError = null
            coldRuntime.reconnectInPlace().join()
            eventually {
                coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                    !coldRuntime.agentConnection.value.offlineConfirmed &&
                    beforeController.state.value.isOnline
            }
            assertSame("就地重连成功不能替换控制器实例", beforeController, coldRuntime.controller.value)
            assertTrue("整个离线重连恢复过程控制器从未变为 null（严禁踢回登录页）", controllerHistory.all { it === beforeController })
        } finally {
            reconnectGate.complete(Unit)
            backgroundReconnectGate.complete(Unit)
            collector.cancelAndJoin()
            coldRuntime.logout().join()
            coldRoot.cancelAndJoin()
        }
    }

    @Test
    fun `离线重连仅当服务端明确返回凭据撤销时才退出到登录态并保留错误码`() = runBlocking {
        seedSavedWorkspaceWithCachedTimeline()
        val backgroundReconnectGate = CompletableDeferred<Unit>()
        val coldAccount = FakeAccountConnection().apply {
            nextConnectError = RemoteRpcException("NETWORK_UNREACHABLE")
        }
        val coldRoot = SupervisorJob()
        val coldScope = CoroutineScope(coldRoot + Dispatchers.Default)
        val coldRuntime = buildRuntime(
            policy = ConnectionRecoveryPolicy(
                offlineGracePeriodMillis = 100L,
                graceRetryAttempts = 0,
                graceRetryDelayMillis = 10L,
                backgroundReconnectInitialDelayMillis = 60_000L,
                backgroundReconnectMaxDelayMillis = 60_000L,
            ),
            delayFn = { ms ->
                if (ms >= 60_000L) backgroundReconnectGate.await() else kotlinx.coroutines.delay(ms)
            },
            customScope = coldScope,
            customAccount = coldAccount,
        )
        try {
            eventually {
                coldRuntime.agentConnection.value.phase == AgentConnectionPhase.OFFLINE &&
                    coldRuntime.agentConnection.value.offlineConfirmed
            }

            coldAccount.nextConnectError = RemoteRpcException("DEVICE_REVOKED")
            coldRuntime.reconnectInPlace().join()
            eventually {
                coldRuntime.agentConnection.value.phase == AgentConnectionPhase.AUTH_REVOKED &&
                    coldRuntime.controller.value == null
            }
            assertEquals("DEVICE_REVOKED", coldRuntime.agentConnection.value.errorCode)
        } finally {
            backgroundReconnectGate.complete(Unit)
            coldRuntime.logout().join()
            coldRoot.cancelAndJoin()
        }
    }

    @Test
    fun `断网就地重连与跨站发现热升级后历史媒体缓存仍可读且不误退役`() = runBlocking {
        seedSavedWorkspaceWithCachedTimeline(workspaceId = "one")
        val backgroundReconnectGate = CompletableDeferred<Unit>()
        val coldAccount = FakeAccountConnection()
        val coldRoot = SupervisorJob()
        val coldScope = CoroutineScope(coldRoot + Dispatchers.Default)
        val coldRuntime = buildRuntime(
            policy = ConnectionRecoveryPolicy(
                offlineGracePeriodMillis = 100L,
                graceRetryAttempts = 0,
                graceRetryDelayMillis = 10L,
                backgroundReconnectInitialDelayMillis = 60_000L,
                backgroundReconnectMaxDelayMillis = 60_000L,
            ),
            delayFn = { ms ->
                if (ms >= 60_000L) backgroundReconnectGate.await() else kotlinx.coroutines.delay(ms)
            },
            customScope = coldScope,
            customAccount = coldAccount,
        )
        try {
            eventually {
                coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                    coldRuntime.controller.value?.state?.value?.isOnline == true
            }
            val initialController = coldRuntime.controller.value!!
            val mediaPort = WorkbenchController::class.java
                .getDeclaredField("media")
                .apply { isAccessible = true }
                .get(initialController) as HistoricalMediaPort
            val epochBefore = mediaPort.originalEpoch("conv", "att-1")
            assertTrue(epochBefore.isNotBlank())

            // 模拟断网降级 -> 就地重连原地热升级
            coldRuntime.onNetworkLost()
            eventually { coldRuntime.agentConnection.value.phase == AgentConnectionPhase.OFFLINE }
            coldRuntime.reconnectInPlace().join()
            eventually {
                coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                    initialController.state.value.isOnline
            }
            val epochAfterReconnect = mediaPort.originalEpoch("conv", "att-1")
            assertTrue(epochAfterReconnect.isNotBlank())

            // 模拟跨 Host 发现触发原地退网再恢复原通道：媒体缓存句柄依然可用
            coldRuntime.discover(RemoteHostDevice("host-2", "工作站", "linux", "membership", true)).join()
            eventually {
                coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                    initialController.state.value.isOnline
            }
            assertSame(initialController, coldRuntime.controller.value)
            val epochAfterDiscover = mediaPort.originalEpoch("conv", "att-1")
            assertTrue(epochAfterDiscover.isNotBlank())
        } finally {
            backgroundReconnectGate.complete(Unit)
            coldRuntime.logout().join()
            coldRoot.cancelAndJoin()
        }
    }

    @Test
    fun `建链抛出TimeoutCancellationException时正常降级OFFLINE并复位reconnectingInPlace`() = runBlocking {
        seedSavedWorkspaceWithCachedTimeline(workspaceId = "one")
        val backgroundReconnectGate = CompletableDeferred<Unit>()
        val coldAccount = FakeAccountConnection().apply {
            connectTimeoutMillis = 1L
        }
        val coldRoot = SupervisorJob()
        val coldScope = CoroutineScope(coldRoot + Dispatchers.Default)
        val coldRuntime = buildRuntime(
            policy = ConnectionRecoveryPolicy(
                offlineGracePeriodMillis = 80L,
                graceRetryAttempts = 1,
                graceRetryDelayMillis = 10L,
                backgroundReconnectInitialDelayMillis = 60_000L,
                backgroundReconnectMaxDelayMillis = 60_000L,
            ),
            delayFn = { ms ->
                if (ms >= 60_000L) backgroundReconnectGate.await() else kotlinx.coroutines.delay(ms)
            },
            customScope = coldScope,
            customAccount = coldAccount,
        )
        val initialController = coldRuntime.controller.value!!
        try {
            eventually {
                coldRuntime.agentConnection.value.phase == AgentConnectionPhase.OFFLINE &&
                    coldRuntime.agentConnection.value.offlineConfirmed
            }
            assertEquals("CONNECT_TIMEOUT", coldRuntime.agentConnection.value.errorCode)
            assertFalse(coldRuntime.agentConnection.value.reconnectingInPlace)
            assertSame(initialController, coldRuntime.controller.value)

            // 点击就地重试再次超时：必须停留在会话页、给出超时错误码并复位 reconnectingInPlace
            coldRuntime.reconnectInPlace().join()
            assertEquals(AgentConnectionPhase.OFFLINE, coldRuntime.agentConnection.value.phase)
            assertTrue(coldRuntime.agentConnection.value.offlineConfirmed)
            assertFalse(coldRuntime.agentConnection.value.reconnectingInPlace)
            assertEquals("CONNECT_TIMEOUT", coldRuntime.agentConnection.value.errorCode)
            assertSame(initialController, coldRuntime.controller.value)

            // 恢复网络后可再次点击就地重试并成功恢复在线态
            coldAccount.connectTimeoutMillis = null
            coldRuntime.reconnectInPlace().join()
            eventually {
                coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                    !coldRuntime.agentConnection.value.offlineConfirmed &&
                    initialController.state.value.isOnline
            }
            assertFalse(coldRuntime.agentConnection.value.reconnectingInPlace)
        } finally {
            backgroundReconnectGate.complete(Unit)
            coldRuntime.logout().join()
            coldRoot.cancelAndJoin()
        }
    }

    @Test
    fun `离线态未缓存兄弟工作区标记为Empty而非停留Loading或Idle`() = runBlocking {
        seedSavedWorkspaceWithCachedTimeline(workspaceId = "one")
        val backgroundReconnectGate = CompletableDeferred<Unit>()
        val coldAccount = FakeAccountConnection().apply {
            nextConnectError = RemoteRpcException("NETWORK_UNREACHABLE")
        }
        val coldRoot = SupervisorJob()
        val coldScope = CoroutineScope(coldRoot + Dispatchers.Default)
        val coldRuntime = buildRuntime(
            policy = ConnectionRecoveryPolicy(
                offlineGracePeriodMillis = 80L,
                graceRetryAttempts = 0,
                graceRetryDelayMillis = 10L,
                backgroundReconnectInitialDelayMillis = 60_000L,
                backgroundReconnectMaxDelayMillis = 60_000L,
            ),
            delayFn = { ms ->
                if (ms >= 60_000L) backgroundReconnectGate.await() else kotlinx.coroutines.delay(ms)
            },
            customScope = coldScope,
            customAccount = coldAccount,
        )
        try {
            eventually {
                coldRuntime.agentConnection.value.phase == AgentConnectionPhase.OFFLINE &&
                    coldRuntime.agentConnection.value.offlineConfirmed
            }
            coldRuntime.hydrateWorkspaces().join()
            val ctx = coldRuntime.workspaceContext.value
            val wsOne = ctx.workspaces.first { it.endpoint.key.workspaceId == "one" }
            val wsTwo = ctx.workspaces.first { it.endpoint.key.workspaceId == "two" }
            assertEquals(WorkspaceCatalogPhase.READY, ctx.catalogPhase)
            assertTrue(ctx.sessionsByWorkspace[wsOne.endpoint.key] is Loadable.Ready)
            assertEquals(Loadable.Empty, ctx.sessionsByWorkspace[wsTwo.endpoint.key])
        } finally {
            backgroundReconnectGate.complete(Unit)
            coldRuntime.logout().join()
            coldRoot.cancelAndJoin()
        }
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
        var connectTimeoutMillis: Long? = null
        var connectGate: CompletableDeferred<Unit>? = null
        var initialHistoryText: String = "冷启动本地缓存回复"
        var incrementalReplyText: String? = null
        var availableHosts = listOf(
            RemoteHostDevice("host-1", "主力电脑", "linux", "membership", true),
            RemoteHostDevice("host-2", "工作站", "linux", "membership", true),
        )
        val extraWorkspaceTwoSessions = CopyOnWriteArrayList<Map<String, Any>>()
        private val peers = mutableMapOf<String, AuthenticatedRemotePeer>()

        override fun peekRestorableAccount(): RemoteActiveAccount? = account.value ?: restorableAccount

        override suspend fun login(origin: String, email: String, password: String): RemoteLoginResult =
            error("测试不走密码登录")

        override suspend fun restore(): RemoteActiveAccount? =
            restorableAccount?.also { account.value = it }

        override suspend fun listHosts(): List<RemoteHostDevice> = availableHosts

        override suspend fun connect(hostDeviceId: String): RemoteRpcChannel {
            connectCalls++
            connectGate?.await()
            connectTimeoutMillis?.let { timeoutMs ->
                withTimeout(timeoutMs) { delay(timeoutMs + 200L) }
            }
            if (connectFailuresBeforeSuccess > 0) {
                connectFailuresBeforeSuccess--
                throw RemoteRpcException("NETWORK_UNREACHABLE")
            }
            nextConnectError?.let { throw it }
            return FakeRpcChannel(channels.size + 1, hostDeviceId, this).also { ch ->
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

    private inner class FakeRpcChannel(
        generation: Int,
        hostDeviceId: String,
        private val owner: FakeAccountConnection,
    ) : RemoteRpcChannel {
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
                        val extraIds = owner.extraWorkspaceTwoSessions.map { it["sessionId"] as String }
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
                                                    mapOf("workspaceId" to "two", "title" to "工作区二", "path" to "/two", "sessionIds" to (listOf("s2") + extraIds)),
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
                                "items" to (listOf(
                                    mapOf("sessionId" to "s1", "title" to "一号会话", "updatedAt" to 100),
                                    mapOf("sessionId" to "s2", "title" to "二号会话", "updatedAt" to 200),
                                ) + owner.extraWorkspaceTwoSessions)
                            )
                        )
                        "session/page" -> {
                            val records = buildList {
                                add(
                                    mapOf(
                                        "type" to "event",
                                        "event" to mapOf(
                                            "seq" to 1,
                                            "type" to "assistant/message",
                                            "data" to mapOf(
                                                "message" to mapOf(
                                                    "id" to "seed-msg-1",
                                                    "content" to listOf(mapOf("type" to "text", "text" to owner.initialHistoryText)),
                                                )
                                            ),
                                        ),
                                    )
                                )
                                owner.incrementalReplyText?.let { inc ->
                                    add(
                                        mapOf(
                                            "type" to "event",
                                            "event" to mapOf(
                                                "seq" to 2,
                                                "type" to "assistant/message",
                                                "data" to mapOf(
                                                    "message" to mapOf(
                                                        "id" to "inc-msg-2",
                                                        "content" to listOf(mapOf("type" to "text", "text" to inc)),
                                                    )
                                                ),
                                            ),
                                        )
                                    )
                                }
                            }
                            Json.of(mapOf("cursor" to records.size, "records" to records, "hasMore" to false))
                        }
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
            owner.releasePeer(scope.connectionId)
        }
    }
}
