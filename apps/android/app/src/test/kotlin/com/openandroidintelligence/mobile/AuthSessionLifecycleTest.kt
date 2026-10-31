package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.account.AccountProfile
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AuthSessionLifecycleTest {
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var gateway: LoopbackGatewayStub
    private lateinit var credentialStore: InMemoryCredentialStore
    private lateinit var deviceKeys: InMemoryDeviceKeySource
    private lateinit var pairingGrants: PairingGrantStateHolder

    @Before
    fun setup() {
        // Keep Robolectric font/native extraction outside the measured login.
        // The production login deadline below remains three seconds.
        org.robolectric.nativeruntime.DefaultNativeRuntimeLoader.injectAndLoad()
        gateway = LoopbackGatewayStub()
        credentialStore = InMemoryCredentialStore()
        deviceKeys = InMemoryDeviceKeySource()
        val context = ApplicationProvider.getApplicationContext<Application>()
        pairingGrants = PairingGrantStateHolder(
            store = InMemoryPairingGrantStore(),
            audit = AndroidAuditStore(InMemoryAuditSink()),
        )
        gateway.respond(NEGOTIATE_PATH, NEGOTIATE_BODY)
        gateway.respond(PASSWORD_PATH, PASSWORD_BODY)
        gateway.respond(REFRESH_PATH, REFRESH_BODY)
        gateway.respond(CONVERSATIONS_PATH, CONVERSATIONS_BODY)
        gateway.respond(EVENTS_PATH, "", contentType = "text/event-stream")
    }

    @After
    fun tearDown() {
        runtimeScope.cancel()
        gateway.closed()
    }

    private fun profileId(user: String = "operator") = "${gateway.baseUrl}#$user"

    private fun runtime(scope: CoroutineScope = runtimeScope) = GatewayRuntime(
        context = ApplicationProvider.getApplicationContext(),
        scope = scope,
        pairingGrants = pairingGrants,
        credentialStore = credentialStore,
        deviceKeys = deviceKeys,
        localDocumentKeyProvider = TestDocumentKeys,
    )

    /**
     * T01 & T02: 冷启动时先读取本地状态并进入工作台，网络挂起时不闪现登录页。
     */
    @Test
    fun coldStartWithLocalSessionEntersWorkspaceDirectlyWhileNetworkIsHanging() {
        val rt = runtime()
        rt.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(rt)

        runtimeScope.cancel()
        val path = NEGOTIATE_PATH
        val release = gateway.holdResponse(path, NEGOTIATE_BODY)
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val restarted = runtime(newScope)
            restarted.restoreSessionIfAvailable()

            val localState = restarted.localSessionState.value
            assertTrue("本地会话存在且有效，应进入 Workspace 呈现本地界面", localState is LocalSessionState.Workspace)
            assertEquals("operator", (localState as LocalSessionState.Workspace).account.username)
            assertFalse(localState is LocalSessionState.SignedOut)
        } finally {
            release.countDown()
            newScope.cancel()
        }
    }

    /**
     * T09 & T10: 离线退出能够立即在本地完成，持久化已退出标记，且断网重启或网络恢复后不能复活。
     */
    @Test
    fun offlineLogoutCompletesLocallyAndPreventsFutureAutoRestore() {
        val rt = runtime()
        rt.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(rt)

        runtimeScope.cancel()
        gateway.closed()

        val offlineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val offline = runtime(offlineScope)
            offline.restoreSessionIfAvailable()
            assertTrue(
                "离线恢复应先呈现本地会话并进入 OfflineMirror；phase=${offline.phase.value}, local=${offline.localSessionState.value}",
                awaitCondition(RESTORE_AWAIT_MILLIS) { offline.phase.value is ConnectionPhase.OfflineMirror },
            )

            offline.logout(revokeRefresh = false)

            val localState = offline.localSessionState.value
            assertTrue("离线登出后本地应立即置为 SignedOut", localState is LocalSessionState.SignedOut)
            assertTrue(
                "离线登出的异步清理应关闭工作台；phase=${offline.phase.value}, controller=${offline.controller.value}",
                awaitCondition(RESTORE_AWAIT_MILLIS) { offline.controller.value == null },
            )
            assertNull(offline.controller.value)

            val restarted = runtime(offlineScope)
            restarted.restoreSessionIfAvailable()
            assertTrue(
                "重启恢复后已退出账号必须仍为 SignedOut，不能复活会话；phase=${restarted.phase.value}, local=${restarted.localSessionState.value}",
                awaitCondition(RESTORE_AWAIT_MILLIS) {
                    restarted.localSessionState.value is LocalSessionState.SignedOut &&
                        restarted.phase.value is ConnectionPhase.Disconnected && restarted.controller.value == null
                },
            )
            assertNull(restarted.controller.value)
        } finally {
            offlineScope.cancel()
        }
    }

    private fun awaitConnected(runtime: GatewayRuntime): GatewayRuntime {
        val connected = awaitCondition(AWAIT_MILLIS) { runtime.phase.value is ConnectionPhase.Connected }
        assertTrue("等待登录连接超时：phase=${runtime.phase.value}", connected)
        return runtime
    }

    private fun awaitCondition(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(POLL_MILLIS, TimeUnit.MILLISECONDS)
            if (condition()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return condition()
    }

    companion object {
        private const val AWAIT_MILLIS = 3000L
        private const val RESTORE_AWAIT_MILLIS = 10_000L
        private const val POLL_MILLIS = 20L
        private const val NEGOTIATE_PATH = "/open-android-intelligence/v2/negotiate"
        private const val PASSWORD_PATH = "/open-android-intelligence/v2/sessions/password"
        private const val REFRESH_PATH = "/open-android-intelligence/v2/sessions/refresh"
        private const val CONVERSATIONS_PATH = "/open-android-intelligence/v2/conversations"
        private const val EVENTS_PATH = "/open-android-intelligence/v2/events"

        private val NEGOTIATE_BODY = """
            {"protocol":"2.1","data":{"protocol":{"major":2,"minor":1},
            "features":{"auth":["password","refresh"],"messages":"chat-v1",
            "attachments":"staged-sha256-v1","events":"sse-cursor-v1",
            "deviceRequests":"risk-queue-v1","conversationUi":[]},
            "limits":{"attachmentTtlSeconds":3600,"eventRetentionSeconds":86400,
            "maxClockSkewSeconds":120},
            "gatewayIdentity":{"deploymentId":"dep_stub","tlsSpkiSha256":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}}
        """.trimIndent()

        private val PASSWORD_BODY = """
            {"data":{"accountId":"acc_stub","deviceId":"dev_stub","sessionId":"sess_1",
            "accessToken":"token_1","refreshCredential":"refresh_1","pairingSummary":"stub pairing"}}
        """.trimIndent()

        private val REFRESH_BODY = """
            {"data":{"accountId":"acc_stub","deviceId":"dev_stub","sessionId":"sess_2",
            "accessToken":"token_2","refreshCredential":"refresh_2","pairingSummary":"stub pairing"}}
        """.trimIndent()

        private val CONVERSATIONS_BODY = """
            {"protocol":"2.1","data":{"conversations":[]}}
        """.trimIndent()
    }
}
