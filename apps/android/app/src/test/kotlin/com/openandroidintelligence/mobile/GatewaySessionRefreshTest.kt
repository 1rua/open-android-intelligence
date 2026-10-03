package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import com.openandroidintelligence.gateway.account.AccountProfile
import com.openandroidintelligence.kernel.PairingGrantStore
import com.openandroidintelligence.kernel.PairingGrantState
import com.openandroidintelligence.kernel.PairingGrantBinding
import com.openandroidintelligence.kernel.PairingGrantCapabilities
import org.robolectric.shadows.ShadowLooper
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 已连接会话的凭据续期：必须真的向 Gateway 发 `POST /sessions/refresh`，
 * 而不是「点了没反应」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GatewaySessionRefreshTest {

    private lateinit var gateway: LoopbackGatewayStub
    private lateinit var credentialStore: InMemoryCredentialStore
    private lateinit var pairingGrants: PairingGrantStateHolder
    private lateinit var deviceKeys: InMemoryDeviceKeySource

    private val runtimeScope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
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
    }

    @After
    fun tearDown() {
        runtimeScope.cancel()
        gateway.closed()
    }

    @Test
    fun aProfileWithoutBindingCanRemoveItsAlreadySavedSecrets() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AndroidAccountProfileStore(context).save(AccountProfile(profileId(), gateway.baseUrl, "operator", ""))
        credentialStore.saveRefresh(profileId(), "orphan_refresh".toByteArray())
        deviceKeys.publicKeyBase64Url(profileId())
        val runtime = runtime()
        runtime.removeLocalAccount(profileId())
        assertTrue(awaitCondition(AWAIT_MILLIS) { runtime.savedProfiles.value.isEmpty() && !runtime.isManagingProfiles.value })
        assertFalse(credentialStore.hasRefresh(profileId()))
        assertFalse(deviceKeys.hasKey(profileId()))
        assertFalse(gateway.requests.any { it.target == REFRESH_PATH })
    }

    @Test
    fun aGrantCleanupFailureAfterConfirmedUnpairStillDeletesRefreshAndSigningKeys() {
        val path = "/open-android-intelligence/v2/pairings/current"
        gateway.respond(path, """{"protocol":"2.1","data":{"deviceId":"dev_stub","deviceKeysRevoked":true,"refreshRevoked":true,"grantsRevoked":true,"deviceRequestsRevoked":true,"unconfirmedAttachmentsRevoked":true,"sessionsRevoked":true}}""")
        val underlying = InMemoryPairingGrantStore()
        pairingGrants = PairingGrantStateHolder(object : PairingGrantStore {
            override fun load(binding: PairingGrantBinding) = underlying.load(binding)
            override fun save(binding: PairingGrantBinding, state: PairingGrantState) = underlying.save(binding, state)
            override fun clear(binding: PairingGrantBinding) = error("PAIRING_GRANT_CLEAR_FAILED")
        }, AndroidAuditStore(InMemoryAuditSink()))
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        runtime.unpair()
        assertTrue(awaitCondition(AWAIT_MILLIS) { !deviceKeys.hasKey(profileId()) && runtime.operationNotice.value?.contains("清理失败") == true })
        assertFalse(credentialStore.hasRefresh(profileId()))
        assertNull(pairingGrants.state.value)
        assertTrue(runtime.phase.value is ConnectionPhase.Disconnected)
    }

    @Test
    fun aStreamAuthenticationRejectionFreezesTheAccountWithoutOpeningAConversation() {
        val path = "/open-android-intelligence/v2/events"
        gateway.respond(path, """{"error":{"code":"SESSION_REVOKED"}}""", 401)
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        assertTrue(awaitCondition(AWAIT_MILLIS) { runtime.phase.value is ConnectionPhase.Failed })
        assertTrue(gateway.requests.any { it.target.substringBefore('?') == path })
        assertNull(pairingGrants.state.value)
    }

    @Test
    fun platformGrantEventsOnlyInvalidateTheTargetDeviceAndNewRevisions() {
        val path = "/open-android-intelligence/v2/events"
        gateway.respond(path, "", contentType = "text/event-stream")
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        pairingGrants.updatePrimitive(PairingGrantCapabilities.SMS, true)
        fun frame(id: String, device: String, revision: Int) = "id: $id\nevent: pairing.grant.changed\ndata: {\"payload\":{\"deviceId\":\"$device\",\"grantRevision\":$revision}}\n\n"
        gateway.respond(path, frame("evt_other", "dev_other", 3), contentType = "text/event-stream")
        val platformCursor = AndroidEventCursorStore(ApplicationProvider.getApplicationContext(), profileId() + ":platform")
        assertTrue(awaitCondition(AWAIT_MILLIS) { platformCursor.load("acc_stub") == "evt_other" })
        assertTrue(PairingGrantCapabilities.SMS in pairingGrants.state.value!!.granted)
        gateway.respond(path, frame("evt_current", "dev_stub", 4), contentType = "text/event-stream")
        assertTrue(awaitCondition(AWAIT_MILLIS) { platformCursor.load("acc_stub") == "evt_current" })
        assertTrue(pairingGrants.state.value!!.granted.isEmpty())
        pairingGrants.updatePrimitive(PairingGrantCapabilities.SMS, true)
        gateway.respond(path, frame("evt_duplicate", "dev_stub", 4), contentType = "text/event-stream")
        assertTrue(awaitCondition(AWAIT_MILLIS) { platformCursor.load("acc_stub") == "evt_duplicate" })
        assertTrue(PairingGrantCapabilities.SMS in pairingGrants.state.value!!.granted)
        assertNull(AndroidEventCursorStore(ApplicationProvider.getApplicationContext(), profileId()).load("acc_stub"))
    }

    @Test
    fun refreshingAConnectedSessionAsksTheGatewayForANewCredential() {
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        val connected = awaitConnected(runtime)
        assertNotNull(connected)

        runtime.refreshSession()

        assertTrue(
            "点击「刷新网关凭据」后必须出现续期请求；实际收到 ${gateway.requests.map { "${it.method} ${it.target}" }}",
            awaitCondition(AWAIT_MILLIS) { gateway.targetsOf("POST").contains(REFRESH_PATH) },
        )
    }

    @Test
    fun theRotatedCredentialIsWhatTheNextRefreshPresents() {
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)

        runtime.refreshSession()
        assertTrue(
            "第一次续期必须落盘轮换后的凭据",
            awaitCondition(AWAIT_MILLIS) { credentialStore.loadRefresh(profileId())?.decodeToString() == "refresh_2" },
        )
        // 生产语义：续期进行中「刷新网关凭据」是禁用的（防重入）。测试也必须
        // 等这个窗口结束再点第二次，否则第二次调用会被静默吞掉——CI 的慢机
        // 上凭据落盘与防重入复位之间有毫秒级时差，不能拿落盘当完成信号。
        assertTrue(
            "第一次续期结束后防重入必须复位",
            awaitCondition(AWAIT_MILLIS) { !runtime.isRefreshingSession.value },
        )

        runtime.refreshSession()
        assertTrue(
            "第二次续期必须出现",
            awaitCondition(AWAIT_MILLIS) { gateway.requests.count { it.target == REFRESH_PATH } >= 2 },
        )

        val refreshes = gateway.requests.filter { it.target == REFRESH_PATH }
        assertTrue("第一次续期应带上登录返回的凭据", refreshes.first().body.contains("refresh_1"))
        assertTrue(
            "第二次续期必须带上轮换后的凭据，否则新令牌没有真正被使用",
            refreshes.last().body.contains("refresh_2"),
        )
    }

    @Test
    fun withoutARefreshCredentialThePathSaysSoInsteadOfPretending() {
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        credentialStore.clearRefresh(profileId())

        runtime.refreshSession()

        val notice = runtime.operationNotice.value
        assertNotNull("没有刷新凭据时必须给出可读说明，而不是静默", notice)
        assertTrue("说明必须点出缺少刷新凭据：$notice", notice!!.contains("刷新凭据"))
        assertFalse(
            "没有凭据时不得向 Gateway 发续期请求",
            gateway.targetsOf("POST").contains(REFRESH_PATH),
        )
    }

    @Test
    fun aRejectedRefreshIsReportedAndKeepsTheLiveConnection() {
        gateway.respond(REFRESH_PATH, """{"error":{"code":"REFRESH_REUSED"}}""", status = 401)
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)

        runtime.refreshSession()

        assertTrue(
            "失败必须有 notice",
            awaitCondition(AWAIT_MILLIS) { runtime.operationNotice.value != null },
        )
        val notice = runtime.operationNotice.value!!
        assertTrue("拒绝续期要如实说明并指向重新登录：$notice", notice.contains("重新登录"))
        assertTrue(
            "续期被拒不应打断当前仍有效的连接",
            runtime.phase.value is ConnectionPhase.Connected,
        )
        assertFalse("Gateway 明确拒绝后本机不得再保留刷新凭据", credentialStore.hasRefresh(profileId()))

        runtime.dismissOperationNotice()
        assertNull("notice 必须可被界面关闭", runtime.operationNotice.value)
    }

    private fun runtime(): GatewayRuntime = GatewayRuntime(
        context = ApplicationProvider.getApplicationContext(),
        scope = runtimeScope,
        pairingGrants = pairingGrants,
        credentialStore = credentialStore,
        deviceKeys = deviceKeys,
    )

    @Test
    fun unpairUsesTheRevocationEndpointAndClearsTheActualSigningKeyAfterConfirmation() {
        val path = "/open-android-intelligence/v2/pairings/current"
        gateway.respond(path, """{"protocol":"2.1","data":{"deviceId":"dev_stub","deviceKeysRevoked":true,"refreshRevoked":true,"grantsRevoked":true,"deviceRequestsRevoked":true,"unconfirmedAttachmentsRevoked":true,"sessionsRevoked":true}}""")
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        assertTrue(deviceKeys.hasKey(profileId()))
        runtime.unpair()
        assertTrue(awaitCondition(AWAIT_MILLIS) { !deviceKeys.hasKey(profileId()) })
        assertTrue(gateway.targetsOf("DELETE").contains(path))
        assertFalse(credentialStore.hasRefresh(profileId()))
        assertTrue(runtime.phase.value is ConnectionPhase.Disconnected)
    }

    @Test
    fun refusedUnpairPreservesSigningAndRefreshKeysForRetry() {
        gateway.respond("/open-android-intelligence/v2/pairings/current", """{"error":{"code":"SESSION_REVOKED"}}""", 401)
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        runtime.unpair()
        assertTrue(awaitCondition(AWAIT_MILLIS) { runtime.operationNotice.value != null })
        assertTrue(deviceKeys.hasKey(profileId()))
        assertTrue(credentialStore.hasRefresh(profileId()))
        assertTrue(runtime.phase.value is ConnectionPhase.Connected)
    }

    @Test
    fun logoutRetainsPublicProfileAndPairingKeyButClearsRefreshCredential() {
        gateway.respond("/open-android-intelligence/v2/sessions/current", "{\"data\":{}}")
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        runtime.logout(revokeRefresh = true)
        assertTrue(awaitCondition(AWAIT_MILLIS) { runtime.phase.value is ConnectionPhase.Disconnected })
        assertEquals(listOf(profileId()), runtime.savedProfiles.value.map { it.localProfileId })
        assertFalse(credentialStore.hasRefresh(profileId()))
        assertTrue(deviceKeys.hasKey(profileId()))
        assertFalse(gateway.requests.any { it.target.contains("pairings/current") })
    }

    @Test
    fun switchingBackUsesOnlyTheSelectedProfilesCredential() {
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        runtime.chooseAnotherAccount()
        runtime.login(gateway.baseUrl, "bob", "secret".toCharArray())
        awaitConnected(runtime)
        credentialStore.saveRefresh(profileId("bob"), "bob_refresh".toByteArray())
        runtime.chooseAnotherAccount()
        runtime.selectSavedAccount(profileId())
        assertEquals("operator", awaitConnected(runtime).username)
        assertTrue(gateway.requests.last { it.target == REFRESH_PATH }.body.contains("refresh_1"))
        assertEquals("bob_refresh", credentialStore.loadRefresh(profileId("bob"))!!.decodeToString())
        assertEquals(2, runtime.savedProfiles.value.size)
    }

    @Test
    fun removingALoggedOutProfileDeletesItsKeyWithoutUnpairingTheGateway() {
        gateway.respond("/open-android-intelligence/v2/sessions/current", "{\"data\":{}}")
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        runtime.logout(revokeRefresh = true)
        assertTrue(awaitCondition(AWAIT_MILLIS) { runtime.phase.value is ConnectionPhase.Disconnected })
        runtime.removeLocalAccount(profileId())
        assertTrue(awaitCondition(AWAIT_MILLIS) { runtime.savedProfiles.value.isEmpty() && !runtime.isManagingProfiles.value })
        assertFalse(deviceKeys.hasKey(profileId()))
        assertFalse(gateway.requests.any { it.target.contains("pairings/current") })
    }

    /** 与 [GatewayRuntime] 内部的 profileId 规则一致：baseUrl|username 的 base64url。 */
    private fun profileId(username: String = "operator"): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("${gateway.baseUrl}|$username".toByteArray(Charsets.UTF_8))

    private fun awaitConnected(runtime: GatewayRuntime): ConnectionPhase.Connected {
        val deadline = System.currentTimeMillis() + AWAIT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(POLL_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            (runtime.phase.value as? ConnectionPhase.Connected)?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        fail("登录没有在 ${AWAIT_MILLIS}ms 内完成，最后阶段是 ${runtime.phase.value}")
        error("unreachable")
    }

    private fun awaitCondition(timeoutMillis: Long, block: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(POLL_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (block()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return false
    }

    private companion object {
        const val AWAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
        const val NEGOTIATE_PATH = "/open-android-intelligence/v2/negotiate"
        const val PASSWORD_PATH = "/open-android-intelligence/v2/sessions/password"
        const val REFRESH_PATH = "/open-android-intelligence/v2/sessions/refresh"

        val NEGOTIATE_BODY = """
            {"protocol":"2.1","data":{"protocol":{"major":2,"minor":1},
            "features":{"auth":["password","refresh"],"messages":"chat-v1",
            "attachments":"staged-sha256-v1","events":"sse-cursor-v1",
            "deviceRequests":"risk-queue-v1",
            "conversationUi":["agent-command-catalog-v1","agent-command-new-v1",
            "agent-approval-cards-v1","message-batches-v1","generation-cancel-v1"]},
            "limits":{            "attachmentTtlSeconds":3600,
            "eventRetentionSeconds":86400,"maxClockSkewSeconds":120},
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
    }
}
