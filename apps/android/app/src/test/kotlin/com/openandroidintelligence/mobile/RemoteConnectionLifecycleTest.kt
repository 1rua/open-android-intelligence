package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.data.remote.RemoteSessionException
import com.openandroidintelligence.conversation.data.remote.RemoteWorkspace
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.remote.auth.RemoteRoleRegistration
import com.openandroidintelligence.gateway.remote.auth.RemoteRoleRegistrationState
import com.openandroidintelligence.gateway.remote.auth.RemoteLoginResult
import com.openandroidintelligence.gateway.remote.authproduction.RemoteActiveAccount
import com.openandroidintelligence.gateway.remote.identity.RemoteRole
import com.openandroidintelligence.gateway.remote.network.*
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.conversations.HotSwappableAttachmentCoordinator
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
        customKeyProvider: AesGcmKeyProvider = key,
        coldStartOnInit: Boolean = true,
        reconnectRandom: () -> Double = { 0.5 },
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
            localDocumentKeyProvider = customKeyProvider,
            recoveryPolicy = policy,
            delayFn = delayFn,
            reconnectRandom = reconnectRandom,
            coldStartOnInit = coldStartOnInit,
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
    fun `无已选工作区冷启动不依赖UI即恢复账号控制角色且不连接电脑`() = runBlocking {
        val cachedAccount = RemoteActiveAccount(origin, accountId, 7)
        val restoredAccount = cachedAccount.copy(epoch = 8)
        val firstRestore = CompletableDeferred<Unit>()
        val coldAccount = FakeAccountConnection().apply {
            restorableAccount = cachedAccount
            restoreResult = restoredAccount
            restoreGate = firstRestore
            state.value = RemoteAccountConnectionState.SIGNED_OUT
            hostState.value = RemoteHostConnectionState.STOPPED
        }
        val coldRoot = SupervisorJob()
        val coldScope = CoroutineScope(coldRoot + Dispatchers.Default)
        val coldRuntime = buildRuntime(customScope = coldScope, customAccount = coldAccount)
        try {
            eventually { coldAccount.restoreCalls == 1 && coldAccount.account.value == restoredAccount }

            // 首帧仍来自同步缓存；恢复虽已开始，但尚未选择任何 Agent、工作区或电脑。
            assertEquals(cachedAccount, coldRuntime.agentConnection.value.account)
            assertNull(coldRuntime.agentConnection.value.activeAgent)
            assertNull(coldRuntime.workspaceContext.value.activeWorkspace)
            assertNull(coldRuntime.controller.value)
            assertEquals(0, coldAccount.connectCalls)

            // 账号控制角色已恢复；释放恢复屏障后只接受更高授权代际并刷新 Host 目录。
            assertEquals(RemoteAccountConnectionState.CONNECTED, coldAccount.state.value)
            assertEquals(RemoteHostConnectionState.ONLINE, coldAccount.hostState.value)
            firstRestore.complete(Unit)
            eventually { coldRuntime.agentConnection.value.account == restoredAccount && coldRuntime.state.value.hosts.isNotEmpty() }
            assertNull(coldRuntime.agentConnection.value.activeAgent)
            assertNull(coldRuntime.workspaceContext.value.activeWorkspace)
            assertNull(coldRuntime.controller.value)
            assertEquals(0, coldAccount.connectCalls)
            assertTrue(coldRuntime.state.value.hosts.isNotEmpty())
            assertEquals(1, coldAccount.restoreCalls)
        } finally {
            firstRestore.complete(Unit)
            coldRuntime.logout().join()
            coldRoot.cancelAndJoin()
        }
    }

    @Test
    fun `后台待命初始化不触发通用恢复而UI准备后仍同步启动首帧恢复`() = runBlocking {
        val cachedAccount = RemoteActiveAccount(origin, accountId, 7)
        val restoredAccount = cachedAccount
        val releaseRestore = CompletableDeferred<Unit>()
        val coldAccount = FakeAccountConnection().apply {
            account.value = cachedAccount
            restorableAccount = cachedAccount
            restoreResult = restoredAccount
            restoreGate = releaseRestore
            state.value = RemoteAccountConnectionState.DISCONNECTED
            hostState.value = RemoteHostConnectionState.ONLINE
        }
        val coldRoot = SupervisorJob()
        val coldScope = CoroutineScope(coldRoot + Dispatchers.Unconfined)
        val coldRuntime = buildRuntime(customScope = coldScope, customAccount = coldAccount, coldStartOnInit = false)
        try {
            assertEquals("后台只创建运行时时不能启动普通恢复", 0, coldAccount.restoreCalls)

            coldRuntime.prepareForUi()
            assertEquals("UI 读取缓存前必须同步进入普通恢复", 1, coldAccount.restoreCalls)
            coldRuntime.prepareForUi()
            coldRuntime.onAppForegrounded()
            assertEquals("UI 与生命周期重复进入必须幂等", 1, coldAccount.restoreCalls)

            releaseRestore.complete(Unit)
            eventually { coldRuntime.agentConnection.value.account == restoredAccount }
            assertNull(coldRuntime.agentConnection.value.activeAgent)
            assertEquals(0, coldAccount.connectCalls)
            assertEquals(1, coldAccount.restoreCalls)
        } finally {
            releaseRestore.complete(Unit)
            coldRuntime.logout().join()
            coldRoot.cancelAndJoin()
        }
    }

    @Test
    fun `两种登录入口均按实际注册角色决定目录访问和错误码`() = runBlocking {
        runtime.restoreSessionIfAvailable().join()
        for (external in listOf(false, true)) {
            val before = account.listCalls
            account.registration = RemoteLoginResult(accountId,
                RemoteRoleRegistration(RemoteRole.CLIENT, RemoteRoleRegistrationState.FAILED, errorCode = "RATE_LIMITED"),
                RemoteRoleRegistration(RemoteRole.HOST, RemoteRoleRegistrationState.REGISTERED))
            if (external) runtime.completeExternalLogin("account-token", "attempt") else runtime.login(origin, "test", "password").join()
            assertNotNull(runtime.agentConnection.value.account)
            assertNull(runtime.agentConnection.value.errorCode)
            assertEquals(before, account.listCalls)
            account.registration = account.registration!!.copy(host = RemoteRoleRegistration(RemoteRole.HOST,
                RemoteRoleRegistrationState.FAILED, errorCode = "REMOTE_ROLE_REGISTRATION_FAILED"))
            if (external) {
                val failure = runCatching { runtime.completeExternalLogin("account-token", "attempt") }.exceptionOrNull()
                assertEquals("RATE_LIMITED", (failure as? RemoteRpcException)?.code)
            } else {
                runtime.login(origin, "test", "password").join()
                assertEquals("RATE_LIMITED", runtime.agentConnection.value.errorCode)
            }
            assertNull(runtime.agentConnection.value.account)
        }
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
        eventually { firstController.state.value.activeThreadId != null }
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
    fun `数据隧道意外关闭后自动新建连接并恢复当前会话订阅`() = runBlocking {
        seedSavedWorkspaceWithCachedTimeline(cachedText = "重连前的本地回复")
        val coldAccount = FakeAccountConnection().apply { initialHistoryText = "重连前的本地回复" }
        val coldRoot = SupervisorJob()
        val coldScope = CoroutineScope(coldRoot + Dispatchers.Default)
        val coldRuntime = buildRuntime(
            policy = ConnectionRecoveryPolicy(
                offlineGracePeriodMillis = 80L,
                graceRetryAttempts = 0,
                graceRetryDelayMillis = 10L,
                backgroundReconnectInitialDelayMillis = 20L,
                backgroundReconnectMaxDelayMillis = 60L,
            ),
            customScope = coldScope,
            customAccount = coldAccount,
        )
        try {
            eventually {
                coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                    coldRuntime.controller.value?.state?.value?.isOnline == true
            }
            val controller = requireNotNull(coldRuntime.controller.value)
            val oldChannel = coldAccount.channels.last()

            coldAccount.closeDataConnection(oldChannel.scope)

            eventually {
                coldAccount.channels.size >= 2 &&
                    coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                    controller.state.value.isOnline
            }
            val newChannel = coldAccount.channels.last()
            assertTrue("旧数据通道必须先被关闭", oldChannel.closed)
            assertNotEquals("恢复必须使用新的 connectionId", oldChannel.scope.connectionId, newChannel.scope.connectionId)
            assertSame("数据面恢复不得替换当前控制器", controller, coldRuntime.controller.value)
            assertEquals(RemoteLocalIds.encode("s1"), controller.state.value.activeThreadId)
            eventually { newChannel.sessionFollowStreams.containsKey("s1") }
            newChannel.emitAssistantMessage("s1", "新通道实时回复")
            eventually {
                (controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty().any { it.text == "新通道实时回复" }
            }
            coldAccount.closeDataConnection(oldChannel.scope)
            delay(100)
            assertEquals("迟到旧连接关闭不得再次拆毁新连接", 2, coldAccount.channels.size)
            assertFalse(newChannel.closed)
        } finally {
            coldRuntime.logout().join()
            coldRoot.cancelAndJoin()
        }
    }

    @Test
    fun `Host Presence 从离线到在线立即打断长退避并重建当前数据面`() = runBlocking {
        seedSavedWorkspaceWithCachedTimeline()
        val coldAccount = FakeAccountConnection()
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
            customScope = coldScope,
            customAccount = coldAccount,
        )
        try {
            eventually {
                coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                    coldRuntime.controller.value?.state?.value?.isOnline == true
            }
            val controller = requireNotNull(coldRuntime.controller.value)
            val oldChannel = coldAccount.channels.last()
            coldAccount.publishHosts(coldAccount.availableHosts.map { if (it.deviceId == "host-1") it.copy(online = false) else it })
            coldAccount.hostOneOffline = true
            coldAccount.closeDataConnection(oldChannel.scope)
            eventually { coldRuntime.agentConnection.value.phase == AgentConnectionPhase.OFFLINE }
            assertEquals("Host 离线且退避等待时不得提前重连", 1, coldAccount.channels.size)

            coldAccount.hostOneOffline = false
            coldAccount.publishHosts(coldAccount.availableHosts.map { if (it.deviceId == "host-1") it.copy(online = true) else it })

            eventually {
                coldAccount.channels.size == 2 &&
                    coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                    controller.state.value.isOnline
            }
            assertSame(controller, coldRuntime.controller.value)
        } finally {
            coldRuntime.logout().join()
            coldRoot.cancelAndJoin()
        }
    }

    @Test
    fun `Client控制代际恢复会唤醒失效的数据面`() = runBlocking {
        seedSavedWorkspaceWithCachedTimeline()
        val coldAccount = FakeAccountConnection()
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
            customScope = coldScope,
            customAccount = coldAccount,
        )
        try {
            eventually { coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED }
            val oldChannel = coldAccount.channels.last()
            coldAccount.closeDataConnection(oldChannel.scope)
            eventually { coldRuntime.agentConnection.value.phase == AgentConnectionPhase.OFFLINE }

            coldAccount.hostOneOffline = false
            coldAccount.publishClientControlConnected()

            eventually {
                coldAccount.channels.size == 2 &&
                    coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED
            }
        } finally {
            coldRuntime.logout().join()
            coldRoot.cancelAndJoin()
        }
    }

    @Test
    fun `控制通道拒绝旧票时等续票结果并保留当前工作台`() = withConnectedRuntime { coldRuntime, coldAccount ->
        val controller = requireNotNull(coldRuntime.controller.value)
        val oldChannel = coldAccount.channels.single()

        coldAccount.closeDataConnection(oldChannel.scope, "AUTH_INVALID")
        eventually { coldRuntime.agentConnection.value.phase == AgentConnectionPhase.OFFLINE }
        assertSame(controller, coldRuntime.controller.value)

        coldAccount.nextConnectError = RemoteRpcException("AUTH_INVALID")
        coldRuntime.reconnectInPlace().join()
        assertEquals(AgentConnectionPhase.OFFLINE, coldRuntime.agentConnection.value.phase)
        assertSame(controller, coldRuntime.controller.value)

        coldAccount.nextConnectError = null
        coldAccount.publishClientControlConnected()
        eventually { coldAccount.channels.size == 2 && controller.state.value.isOnline }
        assertSame(controller, coldRuntime.controller.value)

        coldAccount.failClient("AUTH_INVALID")
        eventually { coldRuntime.agentConnection.value.phase == AgentConnectionPhase.AUTH_REVOKED }
        assertNull(coldRuntime.controller.value)
    }

    @Test
    fun `Client控制通道确认设备被替代后停止数据面重试`() = withConnectedRuntime { coldRuntime, coldAccount ->
        coldAccount.closeDataConnection(coldAccount.channels.single().scope, "CONNECTION_REPLACED")
        eventually { coldRuntime.agentConnection.value.phase == AgentConnectionPhase.OFFLINE }
        coldAccount.nextConnectError = RemoteRpcException("CONNECTION_REPLACED")
        coldAccount.failClient("CONNECTION_REPLACED")

        eventually { coldRuntime.agentConnection.value.phase == AgentConnectionPhase.AUTH_REVOKED }
        assertEquals("CONNECTION_REPLACED", coldRuntime.agentConnection.value.errorCode)
        assertNull(coldRuntime.controller.value)
        coldRuntime.onAppForegrounded()
        coldRuntime.onNetworkAvailable()
        assertEquals("控制面终态后唤醒不得再发起数据建链", 1, coldAccount.connectCalls)
    }

    @Test
    fun `凭据持久化失败关闭账号后仍能终止原工作台`() = withConnectedRuntime { coldRuntime, coldAccount ->
        coldAccount.failClient("REMOTE_CREDENTIAL_PERSISTENCE_FAILED", clearAccount = true)

        eventually { coldRuntime.agentConnection.value.phase == AgentConnectionPhase.AUTH_REVOKED }
        assertEquals("REMOTE_CREDENTIAL_PERSISTENCE_FAILED", coldRuntime.agentConnection.value.errorCode)
        assertNull(coldRuntime.controller.value)
        assertEquals(1, coldAccount.connectCalls)
    }

    @Test
    fun `旧Host恢复信号不能抢占用户切换新Host`() = withConnectedRuntime { coldRuntime, coldAccount ->
        coldAccount.closeDataConnection(coldAccount.channels.single().scope)
        eventually { coldRuntime.agentConnection.value.phase == AgentConnectionPhase.OFFLINE }
        val toAgent = AgentKey(RemoteServiceOrigin.fromCanonicalOrigin(origin), accountId, "host-2", BackendId("harness"))
        val connectGate = CompletableDeferred<Unit>()
        coldAccount.connectGate = connectGate
        val switch = coldRuntime.selectAgent(toAgent)
        try {
            eventually { coldAccount.connectCalls == 2 && coldRuntime.agentConnection.value.switchingAgent == toAgent }
            coldAccount.publishClientControlConnected()
            coldRuntime.onAppForegrounded()
            coldRuntime.onNetworkAvailable()
        } finally {
            connectGate.complete(Unit)
        }
        withTimeout(5_000) { switch.join() }
        assertEquals(toAgent, coldRuntime.agentConnection.value.activeAgent)
        assertEquals(listOf("host-1", "host-2"), coldAccount.channels.map { it.scope.hostDeviceId })
    }

    @Test
    fun `旧Host恢复信号不能抢占设置页发现新Host`() = withConnectedRuntime { coldRuntime, coldAccount ->
        coldAccount.closeDataConnection(coldAccount.channels.single().scope)
        eventually { coldRuntime.agentConnection.value.phase == AgentConnectionPhase.OFFLINE }
        coldAccount.hostOneOffline = true
        val connectGate = CompletableDeferred<Unit>()
        coldAccount.connectGate = connectGate
        val discover = coldRuntime.discover(coldAccount.availableHosts.first { it.deviceId == "host-2" })
        try {
            eventually { coldAccount.connectCalls == 2 }
            coldAccount.publishClientControlConnected()
            coldRuntime.onNetworkAvailable()
        } finally {
            connectGate.complete(Unit)
        }
        withTimeout(5_000) { discover.join() }
        assertTrue(coldRuntime.agentConnection.value.agents.any { it.key.hostId == "host-2" })
    }

    private fun withConnectedRuntime(run: suspend (RemoteRuntime, FakeAccountConnection) -> Unit) = runBlocking {
        seedSavedWorkspaceWithCachedTimeline()
        val coldAccount = FakeAccountConnection()
        val coldRoot = SupervisorJob()
        val coldRuntime = buildRuntime(
            policy = ConnectionRecoveryPolicy(
                offlineGracePeriodMillis = 80L,
                graceRetryAttempts = 0,
                graceRetryDelayMillis = 10L,
                backgroundReconnectInitialDelayMillis = 60_000L,
                backgroundReconnectMaxDelayMillis = 60_000L,
            ),
            customScope = CoroutineScope(coldRoot + Dispatchers.Unconfined),
            customAccount = coldAccount,
        )
        try {
            eventually { coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED }
            run(coldRuntime, coldAccount)
        } finally {
            coldRuntime.logout().join()
            coldRoot.cancelAndJoin()
        }
    }

    @Test
    fun `前台恢复立即打断长退避并合并重复唤醒`() = runBlocking {
        seedSavedWorkspaceWithCachedTimeline()
        val coldAccount = FakeAccountConnection()
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
            customScope = coldScope,
            customAccount = coldAccount,
        )
        try {
            eventually { coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED }
            val oldChannel = coldAccount.channels.last()
            coldAccount.closeDataConnection(oldChannel.scope)
            eventually { coldRuntime.agentConnection.value.phase == AgentConnectionPhase.OFFLINE }

            repeat(5) { coldRuntime.onAppForegrounded() }

            eventually {
                coldAccount.channels.size == 2 &&
                    coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED
            }
            delay(100)
            assertEquals("重复前台唤醒不得并发重复建连", 2, coldAccount.channels.size)
        } finally {
            coldRuntime.logout().join()
            coldRoot.cancelAndJoin()
        }
    }

    @Test
    fun `手动刷新强制重建Client控制面和当前数据面`() = runBlocking {
        seedSavedWorkspaceWithCachedTimeline()
        val coldAccount = FakeAccountConnection()
        val coldRoot = SupervisorJob()
        val coldScope = CoroutineScope(coldRoot + Dispatchers.Default)
        val coldRuntime = buildRuntime(customScope = coldScope, customAccount = coldAccount)
        try {
            eventually { coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED }
            val controller = requireNotNull(coldRuntime.controller.value)
            val oldChannel = coldAccount.channels.last()

            val connectGate = CompletableDeferred<Unit>()
            coldAccount.connectGate = connectGate
            val firstRefresh = coldRuntime.forceRefreshActiveConnection()
            try {
                eventually { coldAccount.connectCalls == 2 }
                assertEquals(1, coldAccount.forceClientControlReconnectCalls.get())
                assertSame("整链路强刷进行中应合并重复刷新", firstRefresh, coldRuntime.forceRefreshActiveConnection())
            } finally {
                connectGate.complete(Unit)
            }
            withTimeout(5_000) { firstRefresh.join() }

            eventually {
                coldAccount.channels.size == 2 &&
                    coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED
            }
            assertEquals(1, coldAccount.forceClientControlReconnectCalls.get())
            assertTrue(oldChannel.closed)
            assertNotEquals(oldChannel.scope.connectionId, coldAccount.channels.last().scope.connectionId)
            assertSame(controller, coldRuntime.controller.value)
        } finally {
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
            try {
                eventually {
                    coldRuntime.agentConnection.value.phase == AgentConnectionPhase.AUTH_REVOKED &&
                        coldRuntime.controller.value == null
                }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError(
                    "离线凭据撤销未退出到登录态：phase=${coldRuntime.agentConnection.value.phase}, " +
                        "error=${coldRuntime.agentConnection.value.errorCode}, " +
                        "reconnecting=${coldRuntime.agentConnection.value.reconnectingInPlace}, " +
                        "controller=${coldRuntime.controller.value}, connectCalls=${coldAccount.connectCalls}, " +
                        "channelCount=${coldAccount.channels.size}",
                    timeout,
                )
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

    @Test
    fun `同Agent切工作区初始化目标工作区失败时保留旧控制器存活不破坏在线态`() = runBlocking {
        val failKeyInit = java.util.concurrent.atomic.AtomicBoolean(false)
        val flakyKeyProvider = object : AesGcmKeyProvider {
            override fun getOrCreate() = if (failKeyInit.get()) error("KEYSTORE_UNAVAILABLE") else key.getOrCreate()
            override fun delete() = key.delete()
        }
        val testRoot = SupervisorJob()
        val testScope = CoroutineScope(testRoot + Dispatchers.Default)
        val testRuntime = buildRuntime(customScope = testScope, customKeyProvider = flakyKeyProvider)
        try {
            testRuntime.restoreSessionIfAvailable().join()
            val agent = AgentKey(RemoteServiceOrigin.fromCanonicalOrigin(origin), accountId, "host-1", BackendId("harness"))
            testRuntime.selectAgent(agent).join()
            eventually {
                testRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                    testRuntime.workspaceContext.value.workspaces.size == 2
            }
            val wsOne = testRuntime.workspaceContext.value.workspaces.first { it.endpoint.key.workspaceId == "one" }
            val wsTwo = testRuntime.workspaceContext.value.workspaces.first { it.endpoint.key.workspaceId == "two" }
            testRuntime.selectWorkspace(wsOne).join()
            val firstController = testRuntime.controller.value!!
            eventually { firstController.state.value.activeThreadId != null }
            firstController.editDraft("切工作区失败应保活旧草稿")

            failKeyInit.set(true)
            testRuntime.selectWorkspace(wsTwo).join()

            assertSame("目标工作区构造失败时必须保留原控制器引用", firstController, testRuntime.controller.value)
            assertTrue("原控制器在线态不能被提前关闭", firstController.state.value.isOnline)
            assertEquals("切工作区失败应保活旧草稿", firstController.state.value.draft)
            assertEquals(wsOne.endpoint.key, testRuntime.workspaceContext.value.activeWorkspace)
            assertEquals(AgentConnectionPhase.CONNECTED, testRuntime.agentConnection.value.phase)
            assertEquals("KEYSTORE_UNAVAILABLE", testRuntime.agentConnection.value.errorCode)
        } finally {
            failKeyInit.set(false)
            testRuntime.logout().join()
            testRoot.cancelAndJoin()
        }
    }

    @Test
    fun `跨宿主发现恢复原工作区失败自动触发后台重连并自愈`() = runBlocking {
        val reconnectDelayGate = CompletableDeferred<Unit>()
        val testRoot = SupervisorJob()
        val testScope = CoroutineScope(testRoot + Dispatchers.Default)
        val testAccount = FakeAccountConnection()
        val testRuntime = buildRuntime(
            policy = ConnectionRecoveryPolicy(
                offlineGracePeriodMillis = 100L,
                graceRetryAttempts = 0,
                graceRetryDelayMillis = 10L,
                backgroundReconnectInitialDelayMillis = 40L,
                backgroundReconnectMaxDelayMillis = 80L,
            ),
            delayFn = { ms ->
                if (ms >= 40L) reconnectDelayGate.await() else kotlinx.coroutines.delay(ms)
            },
            customScope = testScope,
            customAccount = testAccount,
        )
        try {
            testRuntime.restoreSessionIfAvailable().join()
            val host1Harness = AgentKey(RemoteServiceOrigin.fromCanonicalOrigin(origin), accountId, "host-1", BackendId("harness"))
            testRuntime.selectAgent(host1Harness).join()
            eventually {
                testRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                    testRuntime.controller.value?.state?.value?.isOnline == true
            }
            val initialController = testRuntime.controller.value!!

            // 跨宿主发现期间 host-1 掉线：发现成功后恢复原工作区失败，应自动调度后台重连
            testAccount.hostOneOffline = true
            val host2 = testAccount.availableHosts.first { it.deviceId == "host-2" }
            testRuntime.discover(host2).join()
            eventually {
                testRuntime.agentConnection.value.phase == AgentConnectionPhase.OFFLINE &&
                    testRuntime.agentConnection.value.offlineConfirmed
            }

            // host-1 恢复可达并放行后台重连：无需人工点击即自动恢复 CONNECTED
            testAccount.hostOneOffline = false
            reconnectDelayGate.complete(Unit)
            eventually {
                testRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                    !testRuntime.agentConnection.value.offlineConfirmed &&
                    initialController.state.value.isOnline
            }
            assertSame(initialController, testRuntime.controller.value)
        } finally {
            testAccount.hostOneOffline = false
            reconnectDelayGate.complete(Unit)
            testRuntime.logout().join()
            testRoot.cancelAndJoin()
        }
    }

    @Test
    fun `水合工作区沿nextCursor翻页拉全多页会话`() = runBlocking {
        account.codexThreadPages[null] = listOf(
            mapOf("id" to "cx-thread-1", "name" to "Codex第一页会话", "cwd" to "/cx-one", "createdAt" to 1, "updatedAt" to 20),
        ) to "cursor-page-2"
        account.codexThreadPages["cursor-page-2"] = listOf(
            mapOf("id" to "cx-thread-2", "name" to "Codex第二页会话", "cwd" to "/cx-one", "createdAt" to 2, "updatedAt" to 30),
        ) to null

        runtime.restoreSessionIfAvailable().join()
        val codexAgent = AgentKey(RemoteServiceOrigin.fromCanonicalOrigin(origin), accountId, "host-1", BackendId("codex"))
        runtime.selectAgent(codexAgent).join()
        eventually {
            val ctx = runtime.workspaceContext.value
            val wsOne = ctx.workspaces.firstOrNull { it.endpoint.key.workspaceId == "cx-one" } ?: return@eventually false
            val titles = (ctx.sessionsByWorkspace[wsOne.endpoint.key] as? Loadable.Ready)?.value?.map { it.title }.orEmpty()
            "Codex第一页会话" in titles && "Codex第二页会话" in titles
        }
        val wsOne = runtime.workspaceContext.value.workspaces.first { it.endpoint.key.workspaceId == "cx-one" }
        val titles = (runtime.workspaceContext.value.sessionsByWorkspace[wsOne.endpoint.key] as Loadable.Ready).value.map { it.title }
        assertTrue(titles.containsAll(listOf("Codex第一页会话", "Codex第二页会话")))
    }

    @Test
    fun `服务端成功返回空工作区列表时清空内存与持久化缓存`() = runBlocking {
        val testRoot = SupervisorJob()
        val testScope = CoroutineScope(testRoot + Dispatchers.Unconfined)
        val testAccount = FakeAccountConnection()
        val firstRuntime = buildRuntime(customScope = testScope, customAccount = testAccount)
        try {
            firstRuntime.restoreSessionIfAvailable().join()
            val agent = AgentKey(RemoteServiceOrigin.fromCanonicalOrigin(origin), accountId, "host-1", BackendId("harness"))
            firstRuntime.selectAgent(agent).join()
            eventually { firstRuntime.workspaceContext.value.workspaces.size == 2 }

            testAccount.harnessWorkspacesOverride = emptyList()
            firstRuntime.hydrateWorkspaces(force = true).join()
            eventually {
                firstRuntime.workspaceContext.value.catalogPhase == WorkspaceCatalogPhase.READY &&
                    firstRuntime.workspaceContext.value.workspaces.isEmpty()
            }
            assertTrue("状态投影中的遗留端点也必须清空", firstRuntime.state.value.endpoints.isEmpty())
            firstRuntime.closeRuntimeWithoutClearingCacheForTest()
        } finally {
            testRoot.cancelAndJoin()
        }

        val coldRoot = SupervisorJob()
        val coldScope = CoroutineScope(coldRoot + Dispatchers.Default)
        val offlineAccount = FakeAccountConnection().apply {
            nextConnectError = RemoteRpcException("NETWORK_UNREACHABLE")
        }
        val coldRuntime = buildRuntime(
            policy = ConnectionRecoveryPolicy(
                offlineGracePeriodMillis = 80L,
                graceRetryAttempts = 0,
                graceRetryDelayMillis = 10L,
                backgroundReconnectInitialDelayMillis = 60_000L,
                backgroundReconnectMaxDelayMillis = 60_000L,
            ),
            customScope = coldScope,
            customAccount = offlineAccount,
        )
        try {
            coldRuntime.restoreSessionIfAvailable().join()
            coldRuntime.hydrateWorkspaces().join()
            assertTrue("重建后持久化缓存不应复活已删除工作区", coldRuntime.workspaceContext.value.workspaces.isEmpty())
            assertTrue("重建后兼容投影 endpoints 应为空", coldRuntime.state.value.endpoints.isEmpty())
        } finally {
            coldRuntime.logout().join()
            coldRoot.cancelAndJoin()
        }
    }

    @Test
    fun `旧水合挂起期间被权威空工作区发现取代时不得复活已删除工作区或卡在LOADING`() = runBlocking {
        val testRoot = SupervisorJob()
        val testScope = CoroutineScope(testRoot + Dispatchers.Default)
        val testAccount = FakeAccountConnection()
        val firstRuntime = buildRuntime(customScope = testScope, customAccount = testAccount)
        try {
            firstRuntime.restoreSessionIfAvailable().join()
            val agent = AgentKey(RemoteServiceOrigin.fromCanonicalOrigin(origin), accountId, "host-1", BackendId("harness"))
            firstRuntime.selectAgent(agent).join()
            eventually {
                firstRuntime.workspaceContext.value.catalogPhase == WorkspaceCatalogPhase.READY &&
                    firstRuntime.workspaceContext.value.workspaces.size == 2
            }

            // 拦截旧水合的 workspace/follow，使其停在远端读取前，此时权威 discover 返回空列表。
            val enteredStaleHydration = CompletableDeferred<Unit>()
            val releaseStaleHydration = CompletableDeferred<Unit>()
            val intercepted = java.util.concurrent.atomic.AtomicBoolean(false)
            testAccount.workspaceFollowHook = {
                if (intercepted.compareAndSet(false, true)) {
                    enteredStaleHydration.complete(Unit)
                    releaseStaleHydration.await()
                }
            }
            val staleHydration = firstRuntime.hydrateWorkspaces(force = true)
            withTimeout(5_000) { enteredStaleHydration.await() }

            // 权威 discover 返回空工作区列表并取消旧水合。
            testAccount.harnessWorkspacesOverride = emptyList()
            firstRuntime.discover(testAccount.availableHosts.first()).join()
            releaseStaleHydration.complete(Unit)
            staleHydration.join()

            assertEquals(WorkspaceCatalogPhase.READY, firstRuntime.workspaceContext.value.catalogPhase)
            assertTrue(
                "旧水合不得复活已被权威清空的工作区 (workspaces=${firstRuntime.workspaceContext.value.workspaces}, failures=${firstRuntime.agentConnection.value.backendFailures}, err=${firstRuntime.agentConnection.value.errorCode})",
                firstRuntime.workspaceContext.value.workspaces.isEmpty(),
            )
            assertTrue(
                "兼容投影 endpoints 不得残留已删除 Harness 工作区",
                firstRuntime.state.value.endpoints.none { it.endpoint.key.backendId.value == "harness" },
            )
            firstRuntime.closeRuntimeWithoutClearingCacheForTest()
        } finally {
            testRoot.cancelAndJoin()
        }

        // 重建冷启动确认持久化缓存未被旧水合回写复活。
        val coldRoot = SupervisorJob()
        val coldScope = CoroutineScope(coldRoot + Dispatchers.Default)
        val offlineAccount = FakeAccountConnection().apply {
            nextConnectError = RemoteRpcException("NETWORK_UNREACHABLE")
        }
        val coldRuntime = buildRuntime(
            policy = ConnectionRecoveryPolicy(
                offlineGracePeriodMillis = 80L,
                graceRetryAttempts = 0,
                graceRetryDelayMillis = 10L,
                backgroundReconnectInitialDelayMillis = 60_000L,
                backgroundReconnectMaxDelayMillis = 60_000L,
            ),
            customScope = coldScope,
            customAccount = offlineAccount,
        )
        try {
            coldRuntime.restoreSessionIfAvailable().join()
            coldRuntime.hydrateWorkspaces().join()
            assertTrue(coldRuntime.workspaceContext.value.workspaces.isEmpty())
            assertTrue(coldRuntime.state.value.endpoints.none { it.endpoint.key.backendId.value == "harness" })
        } finally {
            coldRuntime.logout().join()
            coldRoot.cancelAndJoin()
        }
    }

    @Test
    fun `离线订阅HotSwappableAttachmentCoordinator后热升级自动接收VERIFIED`() = runBlocking {
        val hotCoordinator = HotSwappableAttachmentCoordinator()
        val observedStates = CopyOnWriteArrayList<AttachmentState>()
        val observedErrors = CopyOnWriteArrayList<String?>()
        val collectorJob = scope.launch {
            hotCoordinator.observe("draft-1").collect { state ->
                observedStates += state.state
                observedErrors += state.errorMessage
            }
        }
        eventually {
            AttachmentState.RETRYABLE_FAILURE in observedStates &&
                "REMOTE_ATTACHMENT_OFFLINE" in observedErrors
        }

        val onlineFlow = MutableStateFlow(AttachmentDraftState(AttachmentDraftId("draft-1"), AttachmentState.UPLOADING, 0.5f))
        val stubOnline = object : AttachmentDraftCoordinator {
            override fun restoredDrafts(): List<AttachmentDraft> = emptyList()
            override suspend fun prepare(selection: LocalAttachmentSelection) = error("unused")
            override suspend fun armSubmission(draftId: String, revision: Long) = error("unused")
            override suspend fun cancelSubmission(intentId: String) = CancelSubmissionResult(false)
            override fun observe(draftId: String): Flow<AttachmentDraftState> = onlineFlow
            override fun retry(draftId: String) = Unit
            override suspend fun discard(draftId: String) = Unit
            override suspend fun releaseAfterSubmit(draftId: String) = Unit
            override suspend fun archiveSubmitted(conversationId: String, draftId: String, media: HistoricalMediaPort) = Unit
            override suspend fun ensureSubmittedSource(conversationId: String, draftId: String, media: HistoricalMediaPort) = Unit
            override suspend fun retrySubmittedSource(conversationId: String, draftId: String, media: HistoricalMediaPort) = error("unused")
            override fun remoteAttachmentId(draftId: String): String? = null
        }

        try {
            hotCoordinator.attach(stubOnline)
            eventually { AttachmentState.UPLOADING in observedStates }
            onlineFlow.value = AttachmentDraftState(AttachmentDraftId("draft-1"), AttachmentState.VERIFIED, 1.0f)
            eventually { AttachmentState.VERIFIED in observedStates }
        } finally {
            collectorJob.cancelAndJoin()
        }
    }

    @Test
    fun `冷启动CONNECTING宽限期内收到onNetworkAvailable不得打断宽限期重试或提前暴露errorCode`() = runBlocking {
        seedSavedWorkspaceWithCachedTimeline(cachedText = "冷启动本地缓存回复")
        val firstConnectGate = CompletableDeferred<Unit>()
        val graceDelayGate = CompletableDeferred<Unit>()
        val coldAccount = FakeAccountConnection().apply {
            connectGate = firstConnectGate
            connectFailuresBeforeSuccess = 1
        }
        val coldRoot = SupervisorJob()
        val coldScope = CoroutineScope(coldRoot + Dispatchers.Default)
        val observedErrorCodes = CopyOnWriteArrayList<String?>()
        val observedPhases = CopyOnWriteArrayList<AgentConnectionPhase>()
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
            coldRuntime.agentConnection.collect {
                observedErrorCodes += it.errorCode
                observedPhases += it.phase
            }
        }
        try {
            assertEquals(AgentConnectionPhase.CONNECTING, coldRuntime.agentConnection.value.phase)
            assertNull(coldRuntime.agentConnection.value.errorCode)

            // 模拟 Android ConnectivityManager 在冷启动注册默认网络回调时立刻触发的 onAvailable()
            coldRuntime.onNetworkAvailable()
            firstConnectGate.complete(Unit)
            eventually { coldAccount.connectCalls >= 1 }
            coldAccount.connectGate = null
            delay(30)

            assertEquals(
                "冷启动宽限期内收到系统 onAvailable 不得劫持为 RECONNECTING/OFFLINE",
                AgentConnectionPhase.CONNECTING,
                coldRuntime.agentConnection.value.phase,
            )
            assertFalse(coldRuntime.agentConnection.value.offlineConfirmed)
            assertNull(
                "初始握手与宽限期完成前严禁向 UI 暴露瞬态 errorCode",
                coldRuntime.agentConnection.value.errorCode,
            )
            assertTrue("宽限期内从未出现过非空 errorCode: $observedErrorCodes", observedErrorCodes.all { it == null })
            assertFalse("宽限期内不应提前进入 OFFLINE: $observedPhases", AgentConnectionPhase.OFFLINE in observedPhases)

            graceDelayGate.complete(Unit)
            eventually {
                coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                    coldRuntime.controller.value?.state?.value?.isOnline == true
            }
            assertNull(coldRuntime.agentConnection.value.errorCode)
        } finally {
            firstConnectGate.complete(Unit)
            graceDelayGate.complete(Unit)
            observer.cancelAndJoin()
            coldRuntime.logout().join()
            coldRoot.cancelAndJoin()
        }
    }

    @Test
    fun `冷启动与打开新会话期间模型目录读取失败不得污染agentConnection的errorCode`() = runBlocking {
        seedSavedWorkspaceWithCachedTimeline(cachedText = "冷启动本地缓存回复")
        val coldAccount = FakeAccountConnection().apply {
            modelsError = RemoteSessionException("METHOD_NOT_FOUND")
        }
        val coldRoot = SupervisorJob()
        val coldScope = CoroutineScope(coldRoot + Dispatchers.Default)
        val observedErrors = CopyOnWriteArrayList<String?>()
        val coldRuntime = buildRuntime(
            customScope = coldScope,
            customAccount = coldAccount,
        )
        val observer = scope.launch {
            coldRuntime.agentConnection.collect { observedErrors += it.errorCode }
        }
        try {
            eventually {
                coldRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                    coldRuntime.controller.value?.state?.value?.isOnline == true
            }
            // 给冷启动成功后的异步 refreshModels 留出执行时间
            delay(50)
            assertNull(
                "冷启动成功建链后后台刷新模型失败不得误报连接错误: ${coldRuntime.agentConnection.value.errorCode}",
                coldRuntime.agentConnection.value.errorCode,
            )

            // 打开/新建会话时同样触发 onActiveThreadChanged -> refreshModels，不得污染 errorCode
            val controller = requireNotNull(coldRuntime.controller.value)
            val beforeThread = controller.state.value.activeThreadId
            controller.createThread()
            eventually {
                val st = controller.state.value
                !st.creatingThread && st.activeThreadId != null && st.activeThreadId != beforeThread
            }
            delay(50)
            assertEquals(AgentConnectionPhase.CONNECTED, coldRuntime.agentConnection.value.phase)
            assertNull(
                "打开新会话后不得误报无法连接 Remote: ${coldRuntime.agentConnection.value.errorCode}",
                coldRuntime.agentConnection.value.errorCode,
            )
            assertTrue("全程不应暴露模型刷新导致的连接错误: $observedErrors", observedErrors.all { it == null })
        } finally {
            observer.cancelAndJoin()
            coldRuntime.logout().join()
            coldRoot.cancelAndJoin()
        }
    }

    @Test
    fun `工作区就绪重选会话切换与断网重连进入时及时清空滞留的errorCode`() = runBlocking {
        val failKeyInit = java.util.concurrent.atomic.AtomicBoolean(false)
        val flakyKeyProvider = object : AesGcmKeyProvider {
            override fun getOrCreate() = if (failKeyInit.get()) error("KEYSTORE_UNAVAILABLE") else key.getOrCreate()
            override fun delete() = key.delete()
        }
        val testRoot = SupervisorJob()
        val testScope = CoroutineScope(testRoot + Dispatchers.Default)
        val testRuntime = buildRuntime(customScope = testScope, customKeyProvider = flakyKeyProvider)
        try {
            testRuntime.restoreSessionIfAvailable().join()
            val agent = AgentKey(RemoteServiceOrigin.fromCanonicalOrigin(origin), accountId, "host-1", BackendId("harness"))
            testRuntime.selectAgent(agent).join()
            eventually {
                testRuntime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                    testRuntime.workspaceContext.value.workspaces.size == 2
            }
            val wsOne = testRuntime.workspaceContext.value.workspaces.first { it.endpoint.key.workspaceId == "one" }
            val wsTwo = testRuntime.workspaceContext.value.workspaces.first { it.endpoint.key.workspaceId == "two" }
            testRuntime.selectWorkspace(wsOne).join()
            val controller = requireNotNull(testRuntime.controller.value)
            eventually { controller.state.value.activeThreadId != null }

            // 1) 切工作区失败产生非空 errorCode，随后在当前工作区打开新会话时必须立即清空滞留 errorCode
            failKeyInit.set(true)
            testRuntime.selectWorkspace(wsTwo).join()
            assertEquals("KEYSTORE_UNAVAILABLE", testRuntime.agentConnection.value.errorCode)
            failKeyInit.set(false)

            controller.createThread()
            eventually {
                !controller.state.value.creatingThread &&
                    controller.state.value.activeThreadId == RemoteLocalIds.encode("s-created-1")
            }
            assertNull("打开新会话后滞留 errorCode 必须被清空", testRuntime.agentConnection.value.errorCode)

            // 2) 再次留下 errorCode 后重新选择已在线当前工作区，也必须及时清空
            failKeyInit.set(true)
            testRuntime.selectWorkspace(wsTwo).join()
            assertEquals("KEYSTORE_UNAVAILABLE", testRuntime.agentConnection.value.errorCode)
            failKeyInit.set(false)

            testRuntime.selectWorkspace(wsOne).join()
            assertNull("工作区已在线就绪时重选必须清空 errorCode", testRuntime.agentConnection.value.errorCode)

            // 3) 再次留下 errorCode 后触发断网进入宽限期 RECONNECTING，不得携带旧 errorCode 进入重连期
            failKeyInit.set(true)
            testRuntime.selectWorkspace(wsTwo).join()
            assertEquals("KEYSTORE_UNAVAILABLE", testRuntime.agentConnection.value.errorCode)
            failKeyInit.set(false)

            testRuntime.onNetworkLost()
            eventually { testRuntime.agentConnection.value.phase == AgentConnectionPhase.RECONNECTING }
            assertNull("断网进入宽限期 RECONNECTING 时必须清空旧 errorCode", testRuntime.agentConnection.value.errorCode)
        } finally {
            failKeyInit.set(false)
            testRuntime.logout().join()
            testRoot.cancelAndJoin()
        }
    }

    private inner class FakeAccountConnection : RemoteAccountConnection {
        override val account = MutableStateFlow<RemoteActiveAccount?>(null)
        override val state = MutableStateFlow(RemoteAccountConnectionState.READY)
        override val hostState = MutableStateFlow(RemoteHostConnectionState.ONLINE)
        override val hosts = MutableStateFlow<List<RemoteHostDevice>>(emptyList())
        override val clientControlGeneration = MutableStateFlow(0L)
        private val closureFlow = MutableSharedFlow<RemoteDataConnectionClosure>(extraBufferCapacity = 8, replay = 1)
        override val dataConnectionClosures: Flow<RemoteDataConnectionClosure> = closureFlow
        override val clientFailureSnapshot = MutableStateFlow<RemoteClientFailure?>(null)
        val forceClientControlReconnectCalls = java.util.concurrent.atomic.AtomicInteger(0)
        var listCalls = 0
        var registration: RemoteLoginResult? = null
        var restorableAccount: RemoteActiveAccount? = RemoteActiveAccount(origin, accountId, 1)
        val channels = CopyOnWriteArrayList<FakeRpcChannel>()
        var connectCalls = 0
        var connectFailuresBeforeSuccess = 0
        var nextConnectError: Exception? = null
        var connectTimeoutMillis: Long? = null
        var hostOneOffline = false
        var connectGate: CompletableDeferred<Unit>? = null
        var restoreCalls = 0
        var restoreGate: CompletableDeferred<Unit>? = null
        var restoreResult: RemoteActiveAccount? = null
        var initialHistoryText: String = "冷启动本地缓存回复"
        var incrementalReplyText: String? = null
        var availableHosts = listOf(
            RemoteHostDevice("host-1", "主力电脑", "linux", "membership", true),
            RemoteHostDevice("host-2", "工作站", "linux", "membership", true),
        )
        val extraWorkspaceTwoSessions = CopyOnWriteArrayList<Map<String, Any>>()
        @Volatile var harnessWorkspacesOverride: List<Map<String, Any>>? = null
        @Volatile var workspaceFollowHook: (suspend () -> Unit)? = null
        @Volatile var modelsError: Exception? = null
        val createdSessionsCount = java.util.concurrent.atomic.AtomicInteger(0)
        val createdSessions = CopyOnWriteArrayList<Map<String, Any>>()
        val codexThreadPages = linkedMapOf<String?, Pair<List<Map<String, Any>>, String?>>()
        private val peers = mutableMapOf<String, AuthenticatedRemotePeer>()

        override fun peekRestorableAccount(): RemoteActiveAccount? = account.value ?: restorableAccount

        private fun finishLogin(): RemoteLoginResult = requireNotNull(registration).also { result ->
            account.value = if (listOf(result.client, result.host).any { it.state == RemoteRoleRegistrationState.REGISTERED })
                RemoteActiveAccount(origin, accountId, 2) else null
        }
        override suspend fun login(origin: String, email: String, password: String) = finishLogin()
        override suspend fun loginAccountToken(origin: String, token: String, loginAttemptId: String) = finishLogin()

        override suspend fun restore(): RemoteActiveAccount? {
            restoreCalls++
            val restored = restoreResult ?: restorableAccount
            if (restored != null) {
                account.value = restored
                state.value = RemoteAccountConnectionState.CONNECTED
                hostState.value = RemoteHostConnectionState.ONLINE
            }
            restoreGate?.await()
            return restored
        }

        override suspend fun listHosts(): List<RemoteHostDevice> { listCalls++; return availableHosts }

        fun publishHosts(next: List<RemoteHostDevice>) {
            availableHosts = next
            hosts.value = next
        }

        fun publishClientControlConnected() {
            clientControlGeneration.value += 1
        }

        fun failClient(code: String, clearAccount: Boolean = false) {
            val failedAccount = requireNotNull(account.value)
            if (clearAccount) {
                account.value = null
                restorableAccount = null
            }
            clientFailureSnapshot.value = RemoteClientFailure(failedAccount, code)
        }

        fun closeDataConnection(scope: RemoteRpcConnectionScope, code: String = "TRANSPORT_CLOSED") {
            closureFlow.tryEmit(RemoteDataConnectionClosure(scope, code))
        }

        override fun refreshControlConnection() = Unit

        override fun forceClientControlReconnect() {
            forceClientControlReconnectCalls.incrementAndGet()
        }

        override suspend fun connect(hostDeviceId: String): RemoteRpcChannel {
            connectCalls++
            connectGate?.await()
            connectTimeoutMillis?.let { timeoutMs ->
                withTimeout(timeoutMs) { delay(timeoutMs + 200L) }
            }
            if (hostDeviceId == "host-1" && hostOneOffline) {
                throw RemoteRpcException("HOST_UNREACHABLE")
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
        val sessionFollowStreams = java.util.concurrent.ConcurrentHashMap<String, String>()

        suspend fun emitAssistantMessage(sessionId: String, text: String) {
            val streamId = sessionFollowStreams.getValue(sessionId)
            events.emit(RemoteRpcEvent("harness.remote.frame", Json.of(mapOf(
                "streamId" to streamId,
                "value" to mapOf(
                    "type" to "event",
                    "event" to mapOf(
                        "seq" to 2,
                        "type" to "assistant/message",
                        "data" to mapOf("message" to mapOf("id" to "live-msg-2", "content" to listOf(mapOf("type" to "text", "text" to text)))),
                    ),
                ),
            ))))
        }

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
                    if (endpoint == "session/follow") {
                        val address = listOf("payload", "args", "request", "address").fold<String, JsonValue?>(params) { value, name ->
                            JsonFields.field(JsonFields.obj(value), name)
                        }
                        sessionFollowStreams[requireNotNull(JsonFields.string(JsonFields.obj(address), "sessionId"))] = requireNotNull(streamId)
                    }
                    if (endpoint == "workspace/follow") {
                        owner.workspaceFollowHook?.invoke()
                        val extraIds = owner.extraWorkspaceTwoSessions.map { it["sessionId"] as String }
                        val items = owner.harnessWorkspacesOverride ?: listOf(
                            mapOf("workspaceId" to "one", "title" to "工作区一", "path" to "/one", "sessionIds" to listOf("s1")),
                            mapOf("workspaceId" to "two", "title" to "工作区二", "path" to "/two", "sessionIds" to (listOf("s2") + extraIds)),
                        )
                        events.emit(
                            RemoteRpcEvent(
                                "harness.remote.frame",
                                Json.of(
                                    mapOf(
                                        "streamId" to streamId,
                                        "value" to mapOf(
                                            "type" to "baseline",
                                            "value" to mapOf("items" to items),
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
                                ) + owner.extraWorkspaceTwoSessions + owner.createdSessions)
                            )
                        )
                        "session/create" -> {
                            val newId = "s-created-${owner.createdSessionsCount.incrementAndGet()}"
                            owner.createdSessions += mapOf("sessionId" to newId, "title" to "新建会话", "updatedAt" to 400)
                            Json.of(mapOf("sessionId" to newId))
                        }
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
                        "session/models" -> {
                            owner.modelsError?.let { throw it }
                            Json.of(mapOf("groups" to emptyList<Any>()))
                        }
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
                    "thread/list" -> {
                        if (owner.codexThreadPages.isNotEmpty()) {
                            val nested = JsonFields.obj(JsonFields.field(params, "params"))
                            val cursor = nested?.let { JsonFields.string(it, "cursor") }
                            val (items, nextCursor) = owner.codexThreadPages[cursor] ?: (emptyList<Map<String, Any>>() to null)
                            Json.of(
                                buildMap {
                                    put("data", items)
                                    if (nextCursor != null) put("nextCursor", nextCursor)
                                }
                            )
                        } else {
                            Json.of(
                                mapOf(
                                    "data" to listOf(
                                        mapOf("id" to "cx-thread-1", "name" to "Codex会话", "cwd" to "/cx-one", "createdAt" to 1, "updatedAt" to 10)
                                    )
                                )
                            )
                        }
                    }
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
