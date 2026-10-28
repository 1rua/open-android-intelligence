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
        gateway = LoopbackGatewayStub()
        credentialStore = InMemoryCredentialStore()
        deviceKeys = InMemoryDeviceKeySource()
        val context = ApplicationProvider.getApplicationContext<Application>()
        pairingGrants = PairingGrantStateHolder(
            store = InMemoryPairingGrantStore(),
            audit = AndroidAuditStore(InMemoryAuditSink()),
        )
        gateway.respond(NEGOTIATE_PATH, """{"data":{"status":"active","sessionRenewalToken":"renewal_stub","accessToken":"acc_stub","account":{"accountId":"acc_stub"},"device":{"deviceId":"dev_stub"}}}""")
        gateway.respond(REFRESH_PATH, """{"data":{"accessToken":"acc_new","sessionRenewalToken":"renewal_new","refreshToken":"refresh_new"}}""")
        gateway.respond(PASSWORD_PATH, """{"data":{"sessionRenewalToken":"renewal_stub","accessToken":"acc_stub","account":{"accountId":"acc_stub"},"device":{"deviceId":"dev_stub"},"refreshToken":"refresh_stub"}}""")
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
        val release = gateway.holdResponse(path, """{"data":{}}""")
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

            offline.logout(revokeRefresh = false)

            val localState = offline.localSessionState.value
            assertTrue("离线登出后本地应立即置为 SignedOut", localState is LocalSessionState.SignedOut)
            assertNull(offline.controller.value)

            val restarted = runtime(offlineScope)
            restarted.restoreSessionIfAvailable()
            assertTrue("已退出的账号不能自动恢复，依然为 SignedOut", restarted.localSessionState.value is LocalSessionState.SignedOut)
            assertNull(restarted.controller.value)
        } finally {
            offlineScope.cancel()
        }
    }

    private fun awaitConnected(runtime: GatewayRuntime): GatewayRuntime {
        assertTrue(awaitCondition(AWAIT_MILLIS) { runtime.phase.value is ConnectionPhase.Connected })
        return runtime
    }

    companion object {
        private const val AWAIT_MILLIS = 3000L
        private const val NEGOTIATE_PATH = "/open-android-intelligence/v2/sessions/negotiate"
        private const val REFRESH_PATH = "/open-android-intelligence/v2/sessions/refresh"
        private const val PASSWORD_PATH = "/open-android-intelligence/v2/credentials/password"
    }
}
