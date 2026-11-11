package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.state.WorkbenchUiState
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.http.GatewayConnectionFactory
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.CapabilityProviderSelector
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.HostEnvelope
import com.openandroidintelligence.kernel.NativePluginLoader
import com.openandroidintelligence.kernel.PhoneLimits
import com.openandroidintelligence.kernel.PluginKernel
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ColdStartConnectionTest {
    private lateinit var gateway: LoopbackGatewayStub
    private lateinit var credentialStore: InMemoryCredentialStore
    private lateinit var deviceKeys: InMemoryDeviceKeySource
    private lateinit var pairingGrants: PairingGrantStateHolder
    private val scopes = mutableListOf<CoroutineScope>()
    private val runtimes = mutableListOf<GatewayRuntime>()
    private val closedControllers = mutableSetOf<WorkbenchController>()
    private var handshakeRelease: CountDownLatch? = null
    private val connectionImplementationReported = AtomicBoolean(false)

    @Before
    fun setUp() {
        org.robolectric.nativeruntime.DefaultNativeRuntimeLoader.injectAndLoad()
        gateway = LoopbackGatewayStub()
        credentialStore = InMemoryCredentialStore()
        deviceKeys = InMemoryDeviceKeySource()
        pairingGrants = PairingGrantStateHolder(
            store = InMemoryPairingGrantStore(),
            audit = AndroidAuditStore(InMemoryAuditSink()),
        )
        gateway.respond(NEGOTIATE_PATH, NEGOTIATE_BODY)
        gateway.respond(PASSWORD_PATH, PASSWORD_BODY)
        gateway.respond(REFRESH_PATH, REFRESH_BODY)
        gateway.respond(CONVERSATIONS_PATH, CONVERSATIONS_BODY)
        gateway.respond("$CONVERSATIONS_PATH/$OLD_THREAD/messages", MESSAGES_BODY)
        gateway.respond("$CONVERSATIONS_PATH/$OLD_THREAD/generations/current", """{"data":{"generation":null}}""")
        gateway.respond("$API/events", "", contentType = "text/event-stream")
    }

    @After
    fun tearDown() {
        handshakeRelease?.countDown()
        try {
            runtimes.asReversed().forEach { current ->
                current.controller.value?.let(::closeController)
            }
        } finally {
            try {
                scopes.forEach { it.cancel() }
                assertTrue("恢复任务必须在 Robolectric Context 释放前结束", awaitCondition {
                    scopes.all { it.coroutineContext[Job]?.isCompleted == true }
                })
            } finally {
                gateway.closed()
            }
        }
    }

    @Test
    fun coldStartWithBlockedNegotiationDoesNotEnterOfflineMirror() {
        savedLoginWithHistory()
        val negotiationCount = requestCount("POST", NEGOTIATE_PATH)
        val release = gateway.holdResponse(NEGOTIATE_PATH, NEGOTIATE_BODY)
        handshakeRelease = release
        val scope = newScope()
        val restarted = runtime(scope)
        val observed = observe(restarted, scope)
        restarted.restoreSessionIfAvailable()

        assertTrue("新 Runtime 必须真的发出握手请求", awaitCondition {
            requestCount("POST", NEGOTIATE_PATH) > negotiationCount
        })
        assertTrue("断言时握手响应仍须被闩锁阻塞", release.count == 1L)
        assertTrue("保存的登录应直接呈现工作台", restarted.localSessionState.value is LocalSessionState.Workspace)
        assertNotNull("网络等待期间必须有可显示的工作台", restarted.controller.value)
        assertFalse(
            "握手尚无任何网络失败，不应宣称离线：phase=${restarted.phase.value}, notice=${restarted.operationNotice.value}",
            restarted.phase.value is ConnectionPhase.OfflineMirror,
        )
        assertNoStartupFailure(observed)
        assertBlankWorkbench(restarted)
    }

    @Test
    fun repeatedAvailabilityAndForegroundKeepTheHealthyPlatformEventOwner() {
        val current = connectedRuntimeWithHistory(native = true).runtime
        val controller = requireNotNull(current.controller.value)
        val historyPath = "$NATIVE_SESSIONS_PATH/$OLD_THREAD/history"
        val synchronizedMessageId = "$OLD_MESSAGE-route-synchronized"
        val synchronizedText = "初始路由同步完成后的历史消息"
        val initialHistoryEntered = CountDownLatch(1)
        val initialHistoryRelease = CountDownLatch(1)
        gateway.respond(historyPath) {
            initialHistoryEntered.countDown()
            check(initialHistoryRelease.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS)) { "初始路由历史响应必须被释放" }
            200 to """{"messages":[
                {"id":"$OLD_MESSAGE","role":"assistant","text":"$OLD_TEXT","timestamp":1},
                {"id":"$synchronizedMessageId","role":"assistant","text":"$synchronizedText","timestamp":2}
            ]}""".trimIndent()
        }
        val streamRequestsBeforeRouteChange = requestCount("GET", "$API/events")
        val release = gateway.openEventStream("$API/events")
        try {
            current.onNetworkAvailable(routeChanged = true)
            assertTrue("必须从新路由的真实 SSE 心跳建立健康连接，并实际开始历史同步", awaitCondition {
                requestCount("GET", "$API/events") > streamRequestsBeforeRouteChange &&
                    current.connectionStatus.value == com.openandroidintelligence.gateway.events.EventStreamStatus.LIVE &&
                    initialHistoryEntered.count == 0L
            })
            assertEquals("平台流健康时，受控的初始历史响应仍未释放", 1L, initialHistoryRelease.count)
            // 平台 LIVE 不代表工作台历史已同步；用响应中的唯一消息确认这次初始读取完成。
            initialHistoryRelease.countDown()
            assertTrue("记录基线前，初始路由的历史必须真实进入当前工作台", awaitCondition {
                (controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty().any {
                    it.key == synchronizedMessageId && it.text == synchronizedText
                }
            })
            val streamCount = requestCount("GET", "$API/events")
            val historyCount = requestCount("GET", historyPath)
            repeat(5) { current.onNetworkAvailable(); current.onAppForegrounded() }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("重复通知不能重建健康平台订阅", streamCount, requestCount("GET", "$API/events"))
            assertEquals("健康 Native 不能因 Platform 活动重复补历史", historyCount,
                requestCount("GET", historyPath))
            assertEquals(com.openandroidintelligence.gateway.events.EventStreamStatus.LIVE, current.connectionStatus.value)
        } finally {
            initialHistoryRelease.countDown()
            release.countDown()
        }
    }

    @Test
    fun returningToForegroundReplacesASilentPlatformConnectionWithoutChangingTheConversation() {
        val current = connectedRuntimeWithHistory(native = true).runtime
        val controller = requireNotNull(current.controller.value)
        val stream = gateway.controllableEventStream("$API/events")
        current.onNetworkAvailable(routeChanged = true)
        assertTrue("必须先用真实 SSE 连续心跳建立健康平台连接", awaitCondition {
            stream.heartbeatCount.get() >= 2 &&
                current.connectionStatus.value == com.openandroidintelligence.gateway.events.EventStreamStatus.LIVE
        })
        val connectedStreams = stream.connectionCount
        current.onAppBackgrounded()
        stream.pauseCurrentConnections()
        val resumedAt = System.nanoTime()
        current.onAppForegrounded()

        assertTrue("返回前台后，静默但仍未关闭的真实 SSE 必须在十秒内被替换，且无须切换会话", awaitCondition(10_000) {
            stream.connectionCount > connectedStreams &&
                current.connectionStatus.value == com.openandroidintelligence.gateway.events.EventStreamStatus.LIVE
        })
        assertTrue("恢复可用连接必须满足十秒预算", elapsedMillis(resumedAt) <= 10_000)
        println("连接恢复实测：recoveryMillis=${elapsedMillis(resumedAt)}")
        assertSame("自动恢复必须保留当前工作台", controller, current.controller.value)
        assertEquals(OLD_THREAD, controller.state.value.activeThreadId)
        assertEquals(OLD_DRAFT, controller.state.value.draft)
    }

    @Test
    fun aQuickColdConnectionKeepsSavedHistoryUnselectedAndReadable() {
        savedLoginWithHistory()
        val requestBoundary = gateway.requests.size
        val scope = newScope()
        val restarted = runtime(scope)
        val observed = observe(restarted, scope)

        restarted.restoreSessionIfAvailable()

        awaitConnected(restarted)
        awaitHistoryListed(restarted)
        assertNoStartupFailure(observed)
        assertNeverSelectedOldHistory(observed)
        assertBlankWorkbench(restarted)
        assertNoConversationCreation(requestBoundary)
        // 空白首页不能靠删除旧历史实现；用户仍须能主动打开同一个真实会话。
        openAndAwaitHistory(restarted)
    }

    @Test
    fun a503BeforeGraceDoesNotShowAnErrorAndRecoveryDoesNotSelectSavedHistory() {
        savedLoginWithHistory()
        val available = AtomicBoolean(false)
        val attempts = AtomicInteger()
        gateway.respond(NEGOTIATE_PATH) {
            attempts.incrementAndGet()
            if (available.get()) 200 to NEGOTIATE_BODY else 503 to UNAVAILABLE_BODY
        }
        val scope = newScope()
        val restarted = runtime(scope, initialConnectionGraceMillis = LONG_GRACE_MILLIS)
        val observed = observe(restarted, scope)
        val startedAt = System.nanoTime()

        restarted.restoreSessionIfAvailable()

        assertTrue("必须先收到真实 503 并处理恢复失败", awaitCondition(BEFORE_GRACE_AWAIT_MILLIS) {
            attempts.get() >= 1 && restarted.credentialStatus.value == GatewayCredentialStatus.RECOVERY_PENDING
        })
        assertTrue("此断言必须发生在初始宽限结束前", elapsedMillis(startedAt) < LONG_GRACE_MILLIS)
        assertNoStartupFailure(observed)
        assertBlankWorkbench(restarted)
        assertTrue("临时 503 不能清除已保存登录", credentialStore.hasRefresh(savedProfileId()))

        available.set(true)
        restarted.onNetworkAvailable()

        awaitConnected(restarted)
        awaitHistoryListed(restarted)
        assertTrue("恢复必须真正重试握手", attempts.get() >= 2)
        assertNoStartupFailure(observed)
        assertNeverSelectedOldHistory(observed)
        assertBlankWorkbench(restarted)
        openAndAwaitHistory(restarted)
    }

    @Test
    fun repeated503AfterGraceShowsOfflineHistoryAndCanBeRetried() {
        val profileId = savedLoginWithHistory().profileId
        val available = AtomicBoolean(false)
        val attempts = AtomicInteger()
        gateway.respond(NEGOTIATE_PATH) {
            attempts.incrementAndGet()
            if (available.get()) 200 to NEGOTIATE_BODY else 503 to UNAVAILABLE_BODY
        }
        val scope = newScope()
        val restarted = runtime(scope, initialConnectionGraceMillis = SHORT_GRACE_MILLIS)
        val observed = observe(restarted, scope)
        val startedAt = System.nanoTime()

        restarted.restoreSessionIfAvailable()

        assertTrue("超过宽限且连续两次 503 后应公开离线状态", awaitCondition {
            attempts.get() >= 2 && restarted.phase.value is ConnectionPhase.OfflineMirror &&
                restarted.operationNotice.value != null
        })
        assertTrue("夹具必须确实跨过宽限", elapsedMillis(startedAt) >= SHORT_GRACE_MILLIS)
        assertTrue("离线不能回到登录页", restarted.localSessionState.value is LocalSessionState.Workspace)
        assertTrue("网络错误不能清除刷新凭据", credentialStore.hasRefresh(profileId))
        assertTrue("网络错误不能移除账号", restarted.savedProfiles.value.any { it.localProfileId == profileId })
        assertTrue("实际失败后才允许离线阶段", observed.phases.any { it is ConnectionPhase.OfflineMirror })
        // 停止远端历史读取后仍能打开同一条已落盘消息，证明缓存没有丢失。
        val remoteHistoryReads = requestCount("GET", "$CONVERSATIONS_PATH/$OLD_THREAD/messages")
        openAndAwaitHistory(restarted)
        assertEquals("离线读取必须来自本地镜像", remoteHistoryReads,
            requestCount("GET", "$CONVERSATIONS_PATH/$OLD_THREAD/messages"))

        val previousAttempts = attempts.get()
        available.set(true)
        restarted.reconnectOfflineMirror()

        awaitConnected(restarted)
        assertTrue("手动重试必须重新发起握手", attempts.get() > previousAttempts)
        assertTrue("重试成功后账号和凭据仍可用", credentialStore.hasRefresh(profileId))
        awaitHistoryListed(restarted)
        openAndAwaitHistory(restarted)
    }

    @Test
    fun aProtocolIncompatibilityFailsBeforeGraceAndCanBeRetriedWithoutCreatingASession() {
        val profileId = savedLoginWithHistory().profileId
        val savedCredential = credentialStore.loadRefresh(profileId)?.decodeToString()
        assertNotNull("不兼容测试必须先有真实保存的刷新凭据", savedCredential)
        val requestBoundary = gateway.requests.size
        val negotiationCount = requestCount("POST", NEGOTIATE_PATH)
        gateway.respond(NEGOTIATE_PATH,
            """{"error":{"code":"PROTOCOL_INCOMPATIBLE","retryable":false}}""", status = 406)
        val scope = newScope()
        val restarted = runtime(scope, initialConnectionGraceMillis = LONG_GRACE_MILLIS)
        val observed = observe(restarted, scope)
        val startedAt = System.nanoTime()

        restarted.restoreSessionIfAvailable()

        assertTrue("真实版本不兼容应立即报告失败，不能等到宽限结束", awaitCondition(BEFORE_GRACE_AWAIT_MILLIS) {
            restarted.phase.value is ConnectionPhase.Failed && restarted.operationNotice.value != null
        })
        assertTrue("必须实际向 Gateway 发出返回 406 的协商请求", requestCount("POST", NEGOTIATE_PATH) > negotiationCount)
        assertTrue("明确失败的分类必须发生在宽限结束前", elapsedMillis(startedAt) < LONG_GRACE_MILLIS)
        assertEquals("应保留真实的版本不兼容错误码", "PROTOCOL_INCOMPATIBLE:406",
            (restarted.phase.value as ConnectionPhase.Failed).code)
        assertNotNull("版本不兼容不能关闭本地工作台", restarted.controller.value)
        assertTrue("版本不兼容不能丢失本地账号上下文", restarted.localSessionState.value is LocalSessionState.Workspace)
        assertEquals("协商失败不能清除或轮换已保存凭据", savedCredential,
            credentialStore.loadRefresh(profileId)?.decodeToString())
        assertFalse("明确版本不兼容不能归类为离线镜像", observed.phases.any {
            it is ConnectionPhase.OfflineMirror
        })
        assertBlankWorkbench(restarted)
        assertNoConversationCreation(requestBoundary)

        val failedNegotiationCount = requestCount("POST", NEGOTIATE_PATH)
        gateway.respond(NEGOTIATE_PATH, NEGOTIATE_BODY)
        restarted.retryConnection()

        awaitConnected(restarted)
        awaitHistoryListed(restarted)
        assertTrue("Failed 重试入口必须重新发起真实协商", requestCount("POST", NEGOTIATE_PATH) > failedNegotiationCount)
        assertTrue("重试成功必须清除明确失败提示", awaitCondition { restarted.operationNotice.value == null })
        assertTrue("重试成功后已保存登录仍可用", credentialStore.hasRefresh(profileId))
        assertBlankWorkbench(restarted)
        assertNeverSelectedOldHistory(observed)
        openAndAwaitHistory(restarted)
        assertNoConversationCreation(requestBoundary)
    }

    @Test
    fun failedRestorationKeepsSettingsAccountSwitchAndAllowsAnotherCoreLogin() {
        val profileId = savedLoginWithHistory().profileId
        val originalCredential = credentialStore.loadRefresh(profileId)?.decodeToString()
        gateway.respond(NEGOTIATE_PATH,
            """{"error":{"code":"PROTOCOL_INCOMPATIBLE","retryable":false}}""", status = 406)
        val scope = newScope()
        val restarted = runtime(scope)
        val settings = settingsFor(restarted, scope)
        assertFalse(settings.uiState.value.hasLocalSession)

        restarted.restoreSessionIfAvailable()

        assertTrue("真实恢复失败仍须将本地工作台投影到设置页", awaitCondition {
            settings.uiState.value.connectionPhase is ConnectionPhase.Failed && settings.uiState.value.hasLocalSession
        })
        assertFalse(settings.uiState.value.isGatewayConnected)
        assertEquals("operator", settings.uiState.value.connectedUsername)
        assertEquals(gateway.baseUrl, settings.uiState.value.connectedGatewayUrl)
        assertEquals(originalCredential, credentialStore.loadRefresh(profileId)?.decodeToString())

        settings.chooseAnotherAccount()
        assertTrue("设置页切换操作必须实际退出旧工作台", awaitCondition {
            restarted.controller.value == null && restarted.phase.value is ConnectionPhase.Disconnected &&
                restarted.localSessionState.value is LocalSessionState.AccountPicker && !settings.uiState.value.hasLocalSession
        })
        assertEquals("切换账号不能清除原账号凭据", originalCredential,
            credentialStore.loadRefresh(profileId)?.decodeToString())
        assertTrue(restarted.savedProfiles.value.any { it.localProfileId == profileId })

        val otherCore = LoopbackGatewayStub()
        try {
            otherCore.respond(NEGOTIATE_PATH, NEGOTIATE_BODY)
            otherCore.respond(PASSWORD_PATH, PASSWORD_BODY)
            otherCore.respond(CONVERSATIONS_PATH, """{"data":{"conversations":[],"nextCursor":null}}""")
            otherCore.respond("$API/events", "", contentType = "text/event-stream")
            restarted.login(otherCore.baseUrl, "other-operator", "secret".toCharArray())
            awaitConnected(restarted)
            assertTrue("设置页必须跟随新 Core 的本地账号", awaitCondition {
                settings.uiState.value.hasLocalSession &&
                    (settings.uiState.value.localSessionState as? LocalSessionState.Workspace)?.account?.gatewayBaseUrl == otherCore.baseUrl
            })
            assertEquals("other-operator", settings.uiState.value.connectedUsername)
            assertEquals(otherCore.baseUrl, settings.uiState.value.connectedGatewayUrl)
            assertEquals("切换 Core 不能覆盖原账号凭据", originalCredential,
                credentialStore.loadRefresh(profileId)?.decodeToString())
        } finally {
            otherCore.closed()
        }
    }

    @Test
    fun pausedRestorationKeepsSettingsLogoutAvailableAndRetainsTheSavedAccount() {
        val profileId = savedLoginWithHistory().profileId
        val retainedCredential = credentialStore.loadRefresh(profileId)?.decodeToString()
        assertNotNull("退出测试必须先有真实保存的刷新凭据", retainedCredential)
        gateway.respond(NEGOTIATE_PATH,
            """{"error":{"code":"PROTOCOL_INCOMPATIBLE","retryable":false}}""", status = 406)
        val scope = newScope()
        val restarted = runtime(scope)
        val settings = settingsFor(restarted, scope)
        restarted.restoreSessionIfAvailable()
        assertTrue("先实际完成失败恢复", awaitCondition {
            settings.uiState.value.connectionPhase is ConnectionPhase.Failed && settings.uiState.value.hasLocalSession
        })

        // 清除失败后不触发重试，本地工作台停留在暂停的连接状态。
        restarted.resetFailure()
        assertTrue(awaitCondition { settings.uiState.value.connectionPhase is ConnectionPhase.Disconnected })
        assertTrue(settings.uiState.value.hasLocalSession)
        assertFalse(settings.uiState.value.isGatewayConnected)
        assertEquals("operator", settings.uiState.value.connectedUsername)
        val recreatedSettings = settingsFor(restarted, scope)
        assertTrue("暂停后新打开的设置也须从真实本地上下文初始化", recreatedSettings.uiState.value.hasLocalSession)

        assertTrue("退出前该账号仍具有推送登录身份", restarted.isPushProfileLoggedIn(profileId))
        recreatedSettings.logout(revokeRefresh = false)

        assertTrue("退出必须跟随真实本地状态隐藏账号操作", awaitCondition {
            restarted.localSessionState.value is LocalSessionState.SignedOut &&
                !settings.uiState.value.hasLocalSession && !recreatedSettings.uiState.value.hasLocalSession &&
                restarted.controller.value == null
        })
        assertTrue("离线退出保留锁定的刷新凭据", credentialStore.hasRefresh(profileId))
        assertEquals("离线退出不能改写已保存凭据", retainedCredential,
            credentialStore.loadRefresh(profileId)?.decodeToString())
        assertFalse("保留凭据不能保留推送登录身份", restarted.isPushProfileLoggedIn(profileId))
        val preferences = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("open_android_intelligence_runtime", android.content.Context.MODE_PRIVATE)
        assertTrue("退出屏障必须持久保存，阻止后续自动使用保留凭据",
            preferences.getBoolean("signed_out_$profileId", false))
        assertTrue("普通退出保留账号资料", restarted.savedProfiles.value.any { it.localProfileId == profileId })
        assertTrue("普通退出保留设备身份", deviceKeys.hasKey(profileId))
    }

    @Test
    fun returningFromBackgroundRetainsTheSameControllerThreadAndDraft() {
        val connected = connectedRuntimeWithHistory()
        val current = connected.runtime
        val controller = requireNotNull(current.controller.value)
        val observed = observe(current, connected.scope)
        val readsBefore = requestCount("GET", "$CONVERSATIONS_PATH/$OLD_THREAD/messages")

        current.onAppBackgrounded()
        assertSame("进入后台不能替换工作台", controller, current.controller.value)
        assertEquals(OLD_THREAD, controller.state.value.activeThreadId)
        current.onAppForegrounded()
        current.onNetworkAvailable()

        assertTrue("回到前台必须执行真实的历史同步", awaitCondition {
            requestCount("GET", "$CONVERSATIONS_PATH/$OLD_THREAD/messages") > readsBefore
        })
        assertSame("前台恢复应沿用同一个 controller", controller, current.controller.value)
        assertTrue("恢复过程中不应短暂关闭或替换工作台", observed.controllers.all { it === controller })
        assertTrue("恢复过程中不应切走用户已选会话", observed.states.all { it.activeThreadId == OLD_THREAD })
        assertEquals(OLD_DRAFT, controller.state.value.draft)
        assertEquals(OLD_THREAD, controller.state.value.activeThreadId)
        assertTrue("缓存消息不能因前后台切换消失", awaitCondition {
            controller.hasSavedMessage()
        })
    }

    @Test
    fun aNativeColdRestartReadsExistingSessionsWithoutPostingANewSession() {
        savedLoginWithHistory(native = true)
        val requestBoundary = gateway.requests.size
        val scope = newScope()
        val restarted = runtime(scope)

        restarted.restoreSessionIfAvailable()

        awaitConnected(restarted)
        awaitNativeSessions(restarted)
        awaitHistoryListed(restarted)
        val restoreRequests = gateway.requests.drop(requestBoundary)
        assertTrue("冷重启必须使用已保存刷新凭据", restoreRequests.any {
            it.method == "POST" && it.target.substringBefore('?') == REFRESH_PATH
        })
        assertTrue("必须真的读取原生会话列表", restoreRequests.any {
            it.method == "GET" && it.target.substringBefore('?') == NATIVE_SESSIONS_PATH
        })
        assertNoConversationCreation(requestBoundary)
        assertNull("恢复 Agent 列表不能顺带选中原生会话", restarted.nativeAgents.value?.state?.value?.selectedSessionId)
        openAndAwaitHistory(restarted)
        assertNoConversationCreation(requestBoundary)
    }

    @Test
    fun aNativeColdRestartKeepsCachedHistoryEditableWhileDiscoveryIsBlocked() {
        savedLoginWithHistory(native = true)
        val requestBoundary = gateway.requests.size
        val discoveryCount = requestCount("GET", "$API/agents")
        val release = gateway.holdResponse("$API/agents", NATIVE_AGENTS_BODY)
        handshakeRelease = release
        try {
            val restarted = runtime(newScope())
            restarted.restoreSessionIfAvailable()

            awaitConnected(restarted)
            assertTrue("在线握手完成后必须真的发出 Agent 发现请求", awaitCondition {
                requestCount("GET", "$API/agents") > discoveryCount
            })
            assertTrue("读取缓存前 Agent 列表必须仍被阻塞", release.count == 1L)
            assertTrue("本例必须已完成真实凭据刷新", gateway.requests.drop(requestBoundary).any {
                it.method == "POST" && it.target.substringBefore('?') == REFRESH_PATH
            })
            val localController = requireNotNull(restarted.controller.value)
            val remoteHistoryReads = requestCount("GET", "$NATIVE_SESSIONS_PATH/$OLD_THREAD/history")

            openAndAwaitHistory(restarted)
            val editedDraft = "$OLD_DRAFT（等待 Agent 列表期间编辑）"
            localController.editDraft(editedDraft)
            assertEquals("Agent 尚未发现时本地工作台仍应允许编辑草稿", editedDraft, localController.state.value.draft)
            awaitAction("Agent 发现挂起时仍须能保存本地草稿") { localController.flushPendingPersistence() }

            assertSame("Agent 发现挂起时应保留当前可用本地工作台", localController, restarted.controller.value)
            assertTrue("本地阅读与编辑必须发生在发现响应放行前", release.count == 1L)
            assertEquals("Agent 列表尚未返回时应读取原生缓存", remoteHistoryReads,
                requestCount("GET", "$NATIVE_SESSIONS_PATH/$OLD_THREAD/history"))
            assertTrue("本地操作不应断开已建立的连接", restarted.phase.value is ConnectionPhase.Connected)
            assertNoConversationCreation(requestBoundary)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun aNativeColdRestartKeepsCachedHistoryEditableWhenDiscoveryReturnsNoAgents() {
        savedLoginWithHistory(native = true)
        val requestBoundary = gateway.requests.size
        gateway.respond("$API/agents", """{"agents":[]}""")
        val restarted = runtime(newScope())
        restarted.restoreSessionIfAvailable()

        awaitConnected(restarted)
        assertTrue("必须实际处理空 Agent 列表，不能停留在发现初态", awaitCondition {
            val state = restarted.nativeAgents.value?.state?.value
            state?.supported == true && state.agents.isEmpty() && !state.loadingSessions
        })
        assertTrue("空列表必须来自重启后的实际请求", gateway.requests.drop(requestBoundary).any {
            it.method == "GET" && it.target.substringBefore('?') == "$API/agents"
        })
        val localController = requireNotNull(restarted.controller.value)
        val remoteHistoryReads = requestCount("GET", "$NATIVE_SESSIONS_PATH/$OLD_THREAD/history")

        openAndAwaitHistory(restarted)
        val editedDraft = "$OLD_DRAFT（Agent 列表为空时编辑）"
        localController.editDraft(editedDraft)
        assertEquals("无在线 Agent 时仍应允许编辑本地草稿", editedDraft, localController.state.value.draft)
        awaitAction("空 Agent 列表不能阻断本地草稿保存") { localController.flushPendingPersistence() }

        assertSame("空 Agent 列表不能关闭或丢弃本地工作台", localController, restarted.controller.value)
        assertEquals("无在线 Agent 时旧消息必须来自原缓存", remoteHistoryReads,
            requestCount("GET", "$NATIVE_SESSIONS_PATH/$OLD_THREAD/history"))
        assertTrue("空列表不能把有效登录退回断开状态", restarted.phase.value is ConnectionPhase.Connected)
        assertTrue("空列表不能退回登录页", restarted.localSessionState.value is LocalSessionState.Workspace)
        assertNoConversationCreation(requestBoundary)
    }

    @Test
    fun aNativeWorkbenchReadFailureKeepsTheConnectedLocalControllerAndShowsANotice() {
        savedLoginWithHistory(native = true)
        val failReads = AtomicBoolean(false)
        val injectedFailures = AtomicInteger()
        val keys = object : AesGcmKeyProvider {
            override fun getOrCreate() = if (failReads.get()) {
                injectedFailures.incrementAndGet()
                throw IllegalStateException("SYNTHETIC_LOCAL_READ_FAILURE")
            } else TestDocumentKeys.getOrCreate()

            override fun delete() = TestDocumentKeys.delete()
        }
        val discoveryCount = requestCount("GET", "$API/agents")
        val release = gateway.holdResponse("$API/agents", NATIVE_AGENTS_BODY)
        handshakeRelease = release
        try {
            val scope = newScope()
            val restarted = runtime(scope, keyProvider = keys)
            restarted.restoreSessionIfAvailable()

            awaitConnected(restarted)
            assertTrue("注入前 Agent 发现请求必须已挂起", awaitCondition {
                requestCount("GET", "$API/agents") > discoveryCount
            })
            assertTrue("注入前必须仍有本地工作台", restarted.localSessionState.value is LocalSessionState.Workspace)
            val localController = requireNotNull(restarted.controller.value)
            assertNull("只在本地页和在线初始化成功后注入故障", restarted.operationNotice.value)
            assertEquals("初始本地页与根镜像必须使用可用密钥完成加载", 0, injectedFailures.get())
            val observed = observe(restarted, scope)

            failReads.set(true)
            release.countDown()

            assertTrue("Agent 工作台读取失败必须真实触发并对用户显示提示", awaitCondition {
                injectedFailures.get() > 0 && restarted.operationNotice.value != null
            })
            assertNotNull("异步 Agent 初始化失败不能清空本地工作台", restarted.controller.value)
            assertSame("新工作台读取失败时应保留原本地工作台", localController, restarted.controller.value)
            assertTrue("局部读取失败不能拆掉已建立的会话", restarted.phase.value is ConnectionPhase.Connected)
            assertFalse("失败处理不能短暂 teardown 到 Disconnected", observed.phases.any {
                it is ConnectionPhase.Disconnected
            })
            assertTrue("失败处理不能短暂发布空工作台", observed.controllers.all { it != null })

            failReads.set(false)
            restarted.onNetworkAvailable()

            assertTrue("手动重试成功后必须真正交接到新的在线工作台", awaitCondition {
                val replacement = restarted.controller.value
                replacement != null && replacement !== localController
            })
            awaitHistoryListed(restarted)
            assertTrue("局部初始化重试成功后必须清除错误提示", awaitCondition {
                restarted.operationNotice.value == null
            })
            assertTrue("局部初始化重试不能断开已建立的会话", restarted.phase.value is ConnectionPhase.Connected)
            assertBlankWorkbench(restarted)
            assertNeverSelectedOldHistory(observed)
            openAndAwaitHistory(restarted)
        } finally {
            // 故障只覆盖异步 Agent 加载；清理期间恢复正常密钥，避免制造收尾错误。
            failReads.set(false)
            release.countDown()
        }
    }

    @Test
    fun aNativeControllerHandoffPreservesThreadSelectionAndDraftEnteredOnTheRetiredController() {
        savedLoginWithHistory(native = true)
        val requestBoundary = gateway.requests.size
        val blockNextConstruction = AtomicBoolean(false)
        val constructionEntered = CountDownLatch(1)
        val constructionRelease = CountDownLatch(1)
        val keys = object : AesGcmKeyProvider {
            override fun getOrCreate(): javax.crypto.SecretKey {
                if (blockNextConstruction.get()) {
                    val stack = Thread.currentThread().stackTrace
                    // 新工作台先准备其本地媒体与镜像，再构造 Controller；闩锁位于同一个外部密钥边界。
                    val isWorkbenchConstruction = stack.any {
                        it.className.startsWith(GatewayRuntime::class.java.name) &&
                            it.methodName.contains("buildWorkbenchController")
                    }
                    if (isWorkbenchConstruction && blockNextConstruction.compareAndSet(true, false)) {
                        constructionEntered.countDown()
                        check(constructionRelease.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                            "SYNTHETIC_NATIVE_CONSTRUCTION_RELEASE_TIMEOUT"
                        }
                    }
                }
                return TestDocumentKeys.getOrCreate()
            }

            override fun delete() = TestDocumentKeys.delete()
        }
        val discoveryCount = requestCount("GET", "$API/agents")
        val discoveryRelease = gateway.holdResponse("$API/agents", NATIVE_AGENTS_BODY)
        handshakeRelease = discoveryRelease
        try {
            val restarted = runtime(newScope(), keyProvider = keys)
            restarted.restoreSessionIfAvailable()

            awaitConnected(restarted)
            assertTrue("连接完成后 Agent 发现请求必须确实挂起", awaitCondition {
                requestCount("GET", "$API/agents") > discoveryCount
            })
            assertTrue("设置构造闩锁前 Agent 发现仍须被阻塞", discoveryRelease.count == 1L)
            awaitHistoryListed(restarted)
            assertBlankWorkbench(restarted)
            val previousController = requireNotNull(restarted.controller.value)

            blockNextConstruction.set(true)
            discoveryRelease.countDown()

            assertTrue("必须在旧工作台 closeAndAwait 之后进入新工作台构造", awaitCondition {
                constructionEntered.count == 0L
            })
            assertTrue("发出用户操作时新工作台必须仍在构造闩锁内", constructionRelease.count == 1L)
            assertSame("交接尚未发布时界面仍持有已退役的旧工作台", previousController, restarted.controller.value)
            previousController.openThread(OLD_THREAD)
            previousController.editDraft("交接输入", previousController.state.value.draftEpoch)
            constructionRelease.countDown()

            assertTrue("交接必须把旧界面依序到达的选择与输入交给最新工作台", awaitCondition {
                val latest = restarted.controller.value
                latest != null && latest !== previousController &&
                    latest.state.value.activeThreadId == OLD_THREAD && latest.state.value.draft == "交接输入" &&
                    latest.hasSavedMessage()
            })
            val latest = requireNotNull(restarted.controller.value)
            assertEquals(OLD_THREAD, latest.state.value.activeThreadId)
            assertEquals("交接输入", latest.state.value.draft)
            assertTrue("交接后必须读到原消息身份与正文", latest.hasSavedMessage())
            assertTrue("交接不能断开已建立的会话", restarted.phase.value is ConnectionPhase.Connected)
            assertNoConversationCreation(requestBoundary)
        } finally {
            blockNextConstruction.set(false)
            constructionRelease.countDown()
            discoveryRelease.countDown()
        }
    }

    @Test fun agentCapabilityLossAndRecoveryKeepTheSameReadableWorkbench() {
        val running = connectedRuntimeWithHistory(native = true)
        val current = running.runtime
        val original = requireNotNull(current.controller.value)
        gateway.respond("$API/agents", NATIVE_AGENTS_BODY.replace("\"online\":true", "\"online\":false")
            .replace("\"list\":true", "\"list\":false").replace("\"history\":true", "\"history\":false")
            .replace("\"send\":true", "\"send\":false"))
        requireNotNull(current.nativeAgents.value).refresh()
        assertTrue("测试必须实际收到 Agent 离线描述", awaitCondition {
            current.nativeAgents.value?.state?.value?.agent?.online == false
        })
        assertTrue("工作台需要同步离线状态", awaitCondition { current.controller.value?.state?.value?.isOnline == false })
        assertSame("能力变化不能重建工作台", original, current.controller.value)
        assertEquals(OLD_THREAD, original.state.value.activeThreadId)
        assertEquals(OLD_DRAFT, original.state.value.draft)
        assertTrue(original.hasSavedMessage())
        gateway.respond("$API/agents", NATIVE_AGENTS_BODY)
        requireNotNull(current.nativeAgents.value).refresh()
        assertTrue("Agent 恢复后原工作台应重新可发送", awaitCondition {
            current.nativeAgents.value?.state?.value?.agent?.online == true && original.state.value.isOnline
        })
        assertSame(original, current.controller.value)
        assertTrue(original.hasSavedMessage())
        assertEquals(OLD_DRAFT, original.state.value.draft)
    }

    @Test fun offlineWorkbenchCommandCatalogUsesItsOwnerLatestDescriptor() {
        val agentsWithCommands = NATIVE_AGENTS_BODY.replace("\"stream\":true", "\"stream\":true,\"commands\":true")
        gateway.respond(NEGOTIATE_PATH, NEGOTIATE_BODY.replace("\"conversationUi\":[]",
            "\"conversationUi\":[\"agent-command-catalog-v1\"]"), headers = mapOf("X-OAI-Agent-Sessions" to "1"))
        gateway.respond("$API/agents", agentsWithCommands.replace("\"online\":true", "\"online\":false"))
        gateway.respond(NATIVE_SESSIONS_PATH, NATIVE_SESSIONS_BODY)
        val commandsPath = "$API/agents/$NATIVE_AGENT/commands"
        gateway.respond(commandsPath, """{"format":"agent-command-catalog-1.0","catalogVersion":"host-v1","commands":[{"invocation":"/status","description":"宿主状态"}]}""")
        val current = runtime(newScope())
        current.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(current)
        assertTrue("离线 Agent 仍须创建可读工作台", awaitCondition {
            current.nativeAgents.value?.state?.value?.agent?.online == false &&
                current.controller.value?.state?.value?.organizationStoredLocally == true
        })
        val original = requireNotNull(current.controller.value)
        original.loadCatalog()
        assertEquals("离线工作台不能读取远端命令目录", 0, requestCount("GET", commandsPath))
        gateway.respond("$API/agents", agentsWithCommands)
        requireNotNull(current.nativeAgents.value).refresh()
        assertTrue(awaitCondition { current.nativeAgents.value?.state?.value?.agent?.online == true && original.state.value.isOnline })
        original.loadCatalog()
        assertTrue("同一 Agent 上线后重试目录必须发出真实 HTTP 请求", awaitCondition { requestCount("GET", commandsPath) > 0 })
        assertTrue(awaitCondition { original.state.value.catalog is com.openandroidintelligence.conversation.state.Loadable.Ready })
        assertSame("在线状态更新不能重建工作台", original, current.controller.value)
        gateway.respond("$API/agents", agentsWithCommands.replace("\"commands\":true", "\"commands\":false"))
        requireNotNull(current.nativeAgents.value).refresh()
        assertTrue(awaitCondition { current.nativeAgents.value?.state?.value?.agent?.capabilities?.commands == false })
        val before = requestCount("GET", commandsPath)
        original.loadCatalog()
        assertTrue(awaitCondition { original.state.value.catalog is com.openandroidintelligence.conversation.state.Loadable.Failed })
        assertEquals("能力被收回后不能使用旧描述请求目录", before, requestCount("GET", commandsPath))
    }

    @Test fun aSendWaitingForPersistenceCannotReachANewAgentWithTheSameSessionId() {
        assertAgentHandoffDoesNotRedirectSend(OLD_THREAD)
    }

    @Test fun aSendWaitingForPersistenceCannotReachANewAgentWithADifferentSessionId() {
        assertAgentHandoffDoesNotRedirectSend("other-agent-session")
    }

    private fun assertAgentHandoffDoesNotRedirectSend(otherSessionId: String) {
        val current=connectedRuntimeWithHistory(native=true).runtime
        val previous=requireNotNull(current.controller.value)
        val native=requireNotNull(current.nativeAgents.value)
        val otherAgent="other-native-agent"
        val agents=NATIVE_AGENTS_BODY.replace("]}",",{\"id\":\"$otherAgent\",\"name\":\"Other agent\",\"kind\":\"codex\",\"online\":true,"+
            "\"sessionScope\":\"deployment\",\"capabilities\":{\"list\":true,\"history\":true,\"send\":true,\"stream\":true}}]}")
        gateway.respond("$API/agents",agents)
        gateway.respond("$API/agents/$otherAgent/sessions","{\"sessions\":[{\"id\":\"$otherSessionId\"}]}")
        gateway.respond("$API/agents/$otherAgent/sessions/$otherSessionId/history","{\"messages\":[]}")
        gateway.respond("$NATIVE_SESSIONS_PATH/$OLD_THREAD/messages","event: message\ndata: {\"type\":\"done\"}\n\n",
            contentType="text/event-stream")
        gateway.respond("$API/agents/$otherAgent/sessions/$OLD_THREAD/messages","event: message\ndata: {\"type\":\"done\"}\n\n",
            contentType="text/event-stream")
        native.refresh()
        assertTrue("两个 Agent 必须真实出现在发现结果中",awaitCondition { native.state.value.agents.any { it.id==otherAgent } })
        // 在真实工作台的提交锁处暂停，复现发送先排队、随后切换 Agent 的顺序。
        val field=WorkbenchController::class.java.getDeclaredField("persistenceLifecycleMutex").apply { isAccessible=true }
        val persistenceGate=field.get(previous) as kotlinx.coroutines.sync.Mutex
        assertTrue(persistenceGate.tryLock())
        val requestsBefore=gateway.requests.size
        try {
            previous.editDraft("原 Agent 的已确认提交")
            previous.sendDraft()
            assertTrue("发送必须已经开始等待本地持久化",awaitCondition { previous.hasActiveWork.value })
            native.selectAgent(otherAgent)
            assertTrue("新选择必须先于持久化恢复",awaitCondition { native.state.value.selectedAgentId==otherAgent })
            assertTrue("交接应在获得存储锁之前冻结旧发送入口",awaitCondition { !previous.state.value.isOnline })
        } finally { persistenceGate.unlock() }
        assertTrue("新的 Agent 工作台必须完成交接",awaitCondition {
            current.controller.value?.let { it!==previous && it.localConversationScope!=previous.localConversationScope }==true
        })
        assertTrue("原 Agent 的消息不能进入新 Agent",gateway.requests.drop(requestsBefore).none {
            it.method=="POST" && it.target.startsWith("$API/agents/$otherAgent/") && it.target.endsWith("/messages")
        })
    }

    private data class RunningSession(val runtime: GatewayRuntime, val scope: CoroutineScope, val profileId: String)

    private fun connectedRuntimeWithHistory(native: Boolean = false): RunningSession {
        if (native) {
            gateway.respond(NEGOTIATE_PATH, NEGOTIATE_BODY, headers = mapOf("X-OAI-Agent-Sessions" to "1"))
            gateway.respond("$API/agents", NATIVE_AGENTS_BODY)
            gateway.respond(NATIVE_SESSIONS_PATH, NATIVE_SESSIONS_BODY)
            gateway.respond("$NATIVE_SESSIONS_PATH/$OLD_THREAD/history", NATIVE_HISTORY_BODY)
        }
        val scope = newScope()
        val current = runtime(scope)
        current.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(current)
        if (native) {
            awaitNativeSessions(current)
            requireNotNull(current.nativeAgents.value).selectSession(OLD_THREAD)
            assertTrue("准备旧原生会话必须实际读取历史", awaitCondition {
                val state = current.nativeAgents.value?.state?.value
                state?.selectedSessionId == OLD_THREAD && !state.loadingHistory && state.messages.any { it.id == OLD_MESSAGE }
            })
        }
        awaitHistoryListed(current)
        openAndAwaitHistory(current)
        val controller = requireNotNull(current.controller.value)
        controller.editDraft(OLD_DRAFT)
        awaitAction("旧会话与草稿必须真实落盘") { controller.flushPendingPersistence() }
        assertEquals(OLD_THREAD, controller.state.value.activeThreadId)
        assertEquals(OLD_DRAFT, controller.state.value.draft)
        val profileId = savedProfileId()
        if (native) {
            assertTrue("原生 Agent 选择必须已写入原账号", awaitCondition {
                AndroidAccountProfileStore(ApplicationProvider.getApplicationContext())
                    .find(profileId)?.selectedAgentId == NATIVE_AGENT
            })
        }
        assertTrue("准备的登录必须具有刷新凭据", credentialStore.hasRefresh(profileId))
        return RunningSession(current, scope, profileId)
    }

    private fun savedLoginWithHistory(native: Boolean = false): RunningSession {
        val connected = connectedRuntimeWithHistory(native)
        closeController(requireNotNull(connected.runtime.controller.value))
        connected.scope.cancel()
        assertTrue("旧进程的任务应结束", awaitCondition {
            connected.scope.coroutineContext[Job]?.isCompleted == true
        })
        return connected
    }

    private fun closeController(controller: WorkbenchController) {
        if (controller in closedControllers) return
        awaitAction("提交并关闭工作台后才能释放测试环境") { controller.closeAndAwait() }
        closedControllers += controller
    }

    private fun awaitAction(message: String, action: suspend () -> Unit) {
        val actionScope = newScope()
        val completion = actionScope.async(Dispatchers.IO) { action() }
        try {
            assertTrue(message, awaitCondition { completion.isCompleted })
            runBlocking { completion.await() }
        } finally {
            actionScope.cancel()
        }
    }

    private class StartupObservations {
        val phases = CopyOnWriteArrayList<ConnectionPhase>()
        val notices = CopyOnWriteArrayList<String>()
        val controllers = CopyOnWriteArrayList<WorkbenchController?>()
        val states = CopyOnWriteArrayList<WorkbenchUiState>()
    }

    private fun observe(current: GatewayRuntime, scope: CoroutineScope) = StartupObservations().also { observed ->
        scope.launch(start = CoroutineStart.UNDISPATCHED) { current.phase.collect { observed.phases += it } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            current.operationNotice.collect { notice -> if (notice != null) observed.notices += notice }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            current.controller.collectLatest { controller ->
                observed.controllers += controller
                controller?.state?.collect { observed.states += it }
            }
        }
    }

    private fun assertNoStartupFailure(observed: StartupObservations) {
        assertFalse("首次连接宽限内不能出现离线或失败阶段：${observed.phases}", observed.phases.any {
            it is ConnectionPhase.OfflineMirror || it is ConnectionPhase.Failed
        })
        assertTrue("首次连接宽限内不能闪现错误提示：${observed.notices}", observed.notices.isEmpty())
    }

    private fun assertNeverSelectedOldHistory(observed: StartupObservations) {
        assertTrue("必须实际观察到工作台状态", observed.states.isNotEmpty())
        assertTrue("冷启动不能先选旧会话再清空", observed.states.none { it.activeThreadId == OLD_THREAD })
        assertTrue("旧草稿不能在冷启动时闪现", observed.states.none { it.draft == OLD_DRAFT })
        assertTrue("旧消息不能在冷启动时闪现", observed.states.none {
            (it.timeline as? Loadable.Ready)?.value.orEmpty().any { row -> row.key == OLD_MESSAGE }
        })
    }

    private fun assertBlankWorkbench(current: GatewayRuntime) {
        val state = requireNotNull(current.controller.value).state.value
        assertNull("冷启动首页不应自动选回旧会话", state.activeThreadId)
        assertEquals("冷启动首页不能恢复旧标题", "", state.activeThreadTitle)
        assertEquals("冷启动首页不能恢复旧草稿", "", state.draft)
        assertTrue("冷启动首页不能显示旧时间线", (state.timeline as? Loadable.Ready)?.value.orEmpty().isEmpty())
        assertTrue("冷启动首页不能带入旧附件", state.attachments.isEmpty())
    }

    private fun awaitConnected(current: GatewayRuntime) {
        val connected = awaitCondition { current.phase.value is ConnectionPhase.Connected }
        assertTrue("等待真实连接超时：phase=${current.phase.value}, notice=${current.operationNotice.value}", connected)
    }

    private fun awaitHistoryListed(current: GatewayRuntime) {
        assertTrue("旧会话必须仍可从工作台列表找到", awaitCondition {
            (current.controller.value?.state?.value?.threads as? Loadable.Ready)?.value.orEmpty().any { it.id.value == OLD_THREAD }
        })
    }

    private fun awaitNativeSessions(current: GatewayRuntime) {
        assertTrue("原生 Agent 与旧会话必须来自实际列表请求", awaitCondition {
            val state = current.nativeAgents.value?.state?.value
            state?.selectedAgentId == NATIVE_AGENT && !state.loadingSessions && state.sessions.any { it.id == OLD_THREAD }
        })
    }

    private fun openAndAwaitHistory(current: GatewayRuntime) {
        val controller = requireNotNull(current.controller.value)
        controller.openThread(OLD_THREAD)
        assertTrue("手动打开旧会话必须看到原始消息身份", awaitCondition {
            current.controller.value?.hasSavedMessage() == true
        })
        assertEquals(OLD_THREAD, current.controller.value?.state?.value?.activeThreadId)
    }

    private fun WorkbenchController.hasSavedMessage() =
        (state.value.timeline as? Loadable.Ready)?.value.orEmpty().any { it.key == OLD_MESSAGE && it.text == OLD_TEXT }

    private fun assertNoConversationCreation(requestBoundary: Int) {
        val creations = gateway.requests.drop(requestBoundary).filter { request ->
            val path = request.target.substringBefore('?')
            request.method == "POST" && (path == CONVERSATIONS_PATH || NATIVE_CREATION_PATH.matches(path))
        }
        assertTrue("冷重启不能隐式 POST 创建会话：$creations", creations.isEmpty())
    }

    private fun savedProfileId(): String = AndroidAccountProfileStore(ApplicationProvider.getApplicationContext())
        .list().single { it.gatewayBaseUrl == gateway.baseUrl && it.username == "operator" }.localProfileId

    private fun requestCount(method: String, path: String) = gateway.requests.count {
        it.method == method && it.target.substringBefore('?') == path
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)

    private fun runtime(
        scope: CoroutineScope,
        initialConnectionGraceMillis: Long = DEFAULT_GRACE_MILLIS,
        keyProvider: AesGcmKeyProvider = TestDocumentKeys,
    ) = GatewayRuntime(
        context = ApplicationProvider.getApplicationContext(),
        scope = scope,
        pairingGrants = pairingGrants,
        credentialStore = credentialStore,
        deviceKeys = deviceKeys,
        localDocumentKeyProvider = keyProvider,
        initialConnectionGraceMillis = initialConnectionGraceMillis,
        connectionFactory = GatewayConnectionFactory(::androidConnection),
    ).also(runtimes::add)

    /** 使用真正 Android HTTP 实现，避免把 JVM 的阻塞取消行为当作手机连接行为。 */
    private fun androidConnection(url: URL): HttpURLConnection {
        val clientClass = Class.forName("com.android.okhttp.OkHttpClient")
        val client = clientClass.getConstructor().newInstance()
        val ready = clientClass.getDeclaredMethod("copyWithDefaults").apply { isAccessible = true }.invoke(client)
        val connection = Class.forName("com.android.okhttp.internal.huc.HttpURLConnectionImpl")
            .getConstructor(URL::class.java, clientClass).newInstance(url, ready) as HttpURLConnection
        if (connectionImplementationReported.compareAndSet(false, true)) {
            println("连接测试 HTTP 实现：${connection.javaClass.name}")
        }
        return connection
    }

    private fun settingsFor(runtime: GatewayRuntime, scope: CoroutineScope): SettingsViewModel {
        val trust = DeveloperTrustMode()
        val sink = InMemoryAuditSink()
        val audit = AndroidAuditStore(sink)
        val kernel = PluginKernel(
            hostEnvelope = HostEnvelope(primitives = emptySet()),
            phoneLimits = PhoneLimits(primitives = emptySet()),
            runtimes = emptyMap(),
            audit = audit,
            trustMode = trust,
            nativeLoader = NativePluginLoader(trust),
            providerSelector = CapabilityProviderSelector(phoneDefaults = emptyMap()),
            grants = { pairingId -> pairingGrants.currentKernelGrant(pairingId) },
        )
        return SettingsViewModel(
            environment = PlatformSettingsEnvironment(
                trustMode = trust, audit = audit, auditSink = sink,
                allowDeveloperTrustMode = true, kernel = kernel, pairingGrants = pairingGrants,
                appearance = AppearancePreferences(ApplicationProvider.getApplicationContext()),
            ),
            runtime = runtime,
            externalScope = scope,
        )
    }

    private fun elapsedMillis(startedAt: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private fun awaitCondition(timeoutMillis: Long = AWAIT_MILLIS, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper(POLL_MILLIS, TimeUnit.MILLISECONDS)
            if (condition()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return condition()
    }

    private companion object {
        const val AWAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
        const val DEFAULT_GRACE_MILLIS = 8_000L
        const val LONG_GRACE_MILLIS = 8_000L
        const val BEFORE_GRACE_AWAIT_MILLIS = 2_000L
        const val SHORT_GRACE_MILLIS = 100L
        const val OLD_THREAD = "cold-start-old-conversation"
        const val OLD_MESSAGE = "cold-start-old-message"
        const val OLD_TEXT = "冷启动前已经缓存的旧回答"
        const val OLD_DRAFT = "冷启动前尚未发送的旧草稿"
        const val NATIVE_AGENT = "cold-start-native-agent"
        const val API = "/open-android-intelligence/v2"
        const val NEGOTIATE_PATH = "$API/negotiate"
        const val PASSWORD_PATH = "$API/sessions/password"
        const val REFRESH_PATH = "$API/sessions/refresh"
        const val CONVERSATIONS_PATH = "$API/conversations"
        const val NATIVE_SESSIONS_PATH = "$API/agents/$NATIVE_AGENT/sessions"
        val NATIVE_CREATION_PATH = Regex("$API/agents/[^/]+/sessions")
        val NEGOTIATE_BODY = """
            {"protocol":"2.1","data":{"protocol":{"major":2,"minor":1},
            "features":{"auth":["password","refresh"],"messages":"chat-v1",
            "attachments":"staged-sha256-v1","events":"sse-cursor-v1",
            "deviceRequests":"risk-queue-v1","conversationUi":[]},
            "limits":{"attachmentTtlSeconds":3600,"eventRetentionSeconds":86400,
            "maxClockSkewSeconds":120},
            "gatewayIdentity":{"deploymentId":"dep_stub","tlsSpkiSha256":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}}
        """.trimIndent()
        val PASSWORD_BODY = """
            {"data":{"accountId":"acc_stub","deviceId":"dev_stub","sessionId":"sess_1",
            "accessToken":"token_1","refreshCredential":"refresh_1","pairingSummary":"stub pairing"}}
        """.trimIndent()
        val REFRESH_BODY = """
            {"data":{"accountId":"acc_stub","deviceId":"dev_stub","sessionId":"sess_2",
            "accessToken":"token_2","refreshCredential":"refresh_2","pairingSummary":"stub pairing"}}
        """.trimIndent()
        val UNAVAILABLE_BODY = """{"error":{"code":"GATEWAY_UNAVAILABLE","retryable":true}}"""
        val CONVERSATIONS_BODY = """
            {"protocol":"2.1","data":{"conversations":[{"conversationId":"$OLD_THREAD","title":"旧会话"}],"nextCursor":null}}
        """.trimIndent()
        val MESSAGES_BODY = """
            {"protocol":"2.1","data":{"messages":[{"messageId":"$OLD_MESSAGE","sender":"assistant",
            "parts":[{"type":"text","text":"$OLD_TEXT"}],"timestamp":1,"state":"CONFIRMED"}],"nextCursor":null}}
        """.trimIndent()
        val NATIVE_AGENTS_BODY = """
            {"agents":[{"id":"$NATIVE_AGENT","name":"Cold start agent","kind":"hermes","online":true,
            "sessionScope":"deployment","capabilities":{"list":true,"history":true,"send":true,"stream":true}}]}
        """.trimIndent()
        val NATIVE_SESSIONS_BODY = """{"sessions":[{"id":"$OLD_THREAD","title":"旧原生会话"}]}"""
        val NATIVE_HISTORY_BODY = """
            {"messages":[{"id":"$OLD_MESSAGE","role":"assistant","text":"$OLD_TEXT","timestamp":1}]}
        """.trimIndent()
    }
}
