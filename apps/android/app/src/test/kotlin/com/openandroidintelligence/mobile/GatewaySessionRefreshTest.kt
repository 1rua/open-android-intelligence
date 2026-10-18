package com.openandroidintelligence.mobile

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import com.openandroidintelligence.gateway.account.AccountProfile
import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.kernel.PairingGrantStore
import com.openandroidintelligence.kernel.PairingGrantState
import com.openandroidintelligence.kernel.PairingGrantBinding
import com.openandroidintelligence.kernel.PairingGrantCapabilities
import com.openandroidintelligence.conversation.ports.TimelineMessage
import org.robolectric.shadows.ShadowLooper
import java.util.Base64
import java.security.Security
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun aRepeatedStreamAuthenticationRejectionStopsTheChannelWithoutFreezingTheAccount() {
        val path = "/open-android-intelligence/v2/events"
        gateway.respond(path, """{"error":{"code":"SESSION_REVOKED"}}""", 401)
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        assertTrue(awaitCondition(AWAIT_MILLIS) { gateway.requests.any { it.target == REFRESH_PATH } })
        assertTrue(awaitCondition(AWAIT_MILLIS) { runtime.operationNotice.value?.contains("事件通道") == true })
        assertTrue(runtime.phase.value is ConnectionPhase.Connected)
        assertTrue(gateway.requests.any { it.target.substringBefore('?') == path })
        assertNotNull(pairingGrants.state.value)
        assertTrue(credentialStore.hasRefresh(profileId()))
        assertTrue(deviceKeys.hasKey(profileId()))
        assertEquals(1, gateway.requests.count { it.target == REFRESH_PATH })
    }

    @Test
    fun anExpiredBackgroundSessionRenewsAndReplaysTheCursorWithoutReplacingTheWorkbench() {
        val expired = AtomicBoolean(false)
        val path = "/open-android-intelligence/v2/events"
        gateway.respond(path, contentType = "text/event-stream") { request ->
            when {
                expired.get() && request.headers.any { it.equals("Authorization: Bearer token_1", true) } ->
                    401 to """{"error":{"code":"SESSION_EXPIRED"}}"""
                expired.get() -> 200 to "id: evt_after\nevent: heartbeat\ndata: {}\n\n"
                else -> 200 to "id: evt_before\nevent: heartbeat\ndata: {}\n\n"
            }
        }
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        val workbench = requireNotNull(runtime.controller.value)
        workbench.editDraft("后台未发送的草稿")
        assertTrue(awaitCondition(AWAIT_MILLIS) { gateway.requests.any { it.target.contains("cursor=evt_before") } })
        runtime.onAppBackgrounded()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofHours(12))
        expired.set(true)

        assertTrue("后台 access 过期应自动续期", awaitCondition(AWAIT_MILLIS) {
            gateway.requests.any { it.target.contains("cursor=evt_after") && it.headers.any { header -> header.equals("Authorization: Bearer token_2", true) } }
        })
        runtime.onAppForegrounded()
        assertTrue(runtime.phase.value is ConnectionPhase.Connected)
        assertSame("续期不能取消在途工作台", workbench, runtime.controller.value)
        assertEquals("后台未发送的草稿", workbench.state.value.draft)
        assertEquals("refresh_2", credentialStore.loadRefresh(profileId())?.decodeToString())
        assertEquals(1, gateway.requests.count { it.target == REFRESH_PATH })
        assertNotNull(pairingGrants.state.value)
    }

    @Test
    fun aTemporaryRefreshFailureRecoversWithoutDeletingCredentialsOrTheWorkbench() {
        val available = AtomicBoolean(false)
        val path = "/open-android-intelligence/v2/events"
        gateway.respond(path, contentType = "text/event-stream") { request ->
            if (request.headers.any { it.equals("Authorization: Bearer token_2", true) })
                200 to ": heartbeat\n\n" else 401 to """{"error":{"code":"SESSION_EXPIRED"}}"""
        }
        gateway.respond(REFRESH_PATH) { if (available.get()) 200 to REFRESH_BODY else 503 to """{"error":{"code":"GATEWAY_UNAVAILABLE","retryable":true}}""" }
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        val workbench = runtime.controller.value
        assertTrue(awaitCondition(AWAIT_MILLIS) { gateway.requests.any { it.target == REFRESH_PATH } })
        assertTrue(runtime.phase.value is ConnectionPhase.Connected)
        assertTrue(credentialStore.hasRefresh(profileId()))
        assertTrue(deviceKeys.hasKey(profileId()))
        available.set(true)
        assertTrue(awaitCondition(AWAIT_MILLIS) { credentialStore.loadRefresh(profileId())?.decodeToString() == "refresh_2" })
        assertSame(workbench, runtime.controller.value)
        assertTrue(runtime.phase.value is ConnectionPhase.Connected)
    }

    @Test
    fun aColdProcessAutomaticallyRecoversWhenTheGatewayReturns() {
        val originalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val original = GatewayRuntime(
            ApplicationProvider.getApplicationContext(), originalScope, pairingGrants,
            credentialStore = credentialStore, deviceKeys = deviceKeys, localDocumentKeyProvider = TestDocumentKeys,
        )
        original.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(original)
        awaitWorkbenchCommit(requireNotNull(original.controller.value))
        awaitAction("进程回收前释放数据库句柄") { requireNotNull(original.controller.value).closeAndAwait() }
        originalScope.cancel()
        val available = AtomicBoolean(false)
        gateway.respond(NEGOTIATE_PATH) { if (available.get()) 200 to NEGOTIATE_BODY else 503 to """{"error":{"code":"GATEWAY_UNAVAILABLE","retryable":true}}""" }
        val restored = runtime()
        val previousAttempts = gateway.requests.count { it.target == NEGOTIATE_PATH }
        restored.restoreSessionIfAvailable()
        assertTrue(awaitCondition(AWAIT_MILLIS) { gateway.requests.count { it.target == NEGOTIATE_PATH } > previousAttempts })
        assertTrue(credentialStore.hasRefresh(profileId()))
        available.set(true)
        restored.onAppForegrounded()
        awaitConnected(restored)
        assertEquals("refresh_2", credentialStore.loadRefresh(profileId())?.decodeToString())
        assertFalse(gateway.requests.dropWhile { it.target != REFRESH_PATH }.any { it.target == PASSWORD_PATH })
    }

    @Test
    fun aLostRefreshResponseIsReplayedWithTheSameDurableOperationAfterProcessDeath() {
        gateway.respond(NEGOTIATE_PATH, NEGOTIATE_BODY, headers = mapOf("X-Open-Android-Intelligence-Refresh-Replay" to "v1"))
        gateway.respond("/open-android-intelligence/v2/events", ": heartbeat\n\n", contentType = "text/event-stream")
        val loseResponse = AtomicBoolean(true)
        gateway.respond(REFRESH_PATH) { if (loseResponse.getAndSet(false)) 0 to "" else 200 to REFRESH_BODY }
        val originalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val original = GatewayRuntime(ApplicationProvider.getApplicationContext(), originalScope, pairingGrants,
            credentialStore = credentialStore, deviceKeys = deviceKeys, localDocumentKeyProvider = TestDocumentKeys)
        original.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(original)
        awaitWorkbenchCommit(requireNotNull(original.controller.value))
        original.refreshSession()
        assertTrue(awaitCondition(AWAIT_MILLIS) { original.credentialStatus.value == GatewayCredentialStatus.RECOVERY_PENDING && !original.isRefreshingSession.value })
        assertEquals(1, gateway.requests.count { it.target == REFRESH_PATH })
        assertEquals("refresh_1", credentialStore.loadRefresh(profileId())?.decodeToString())
        awaitAction("模拟进程死亡释放句柄") { requireNotNull(original.controller.value).closeAndAwait() }
        originalScope.cancel()

        val restarted = runtime()
        restarted.restoreSessionIfAvailable()
        awaitConnected(restarted)
        val attempts = gateway.requests.filter { it.target == REFRESH_PATH }
        assertEquals(2, attempts.size)
        val firstId = attempts[0].headers.single { it.startsWith("Idempotency-Key:", true) }
        assertEquals(firstId, attempts[1].headers.single { it.startsWith("Idempotency-Key:", true) })
        assertTrue(attempts.all { it.body.contains("refresh_1") })
        assertEquals("refresh_2", credentialStore.loadRefresh(profileId())?.decodeToString())
        assertEquals(1, gateway.requests.count { it.target == PASSWORD_PATH })
    }

    @Test
    fun aStructuredUnknownRotationOutcomeRetainsTheSameOperationIdentity() {
        gateway.respond(NEGOTIATE_PATH, NEGOTIATE_BODY, headers = mapOf("X-Open-Android-Intelligence-Refresh-Replay" to "v1"))
        val unknown = AtomicBoolean(true)
        gateway.respond(REFRESH_PATH) {
            if (unknown.getAndSet(false)) 503 to """{"error":{"code":"OUTCOME_UNKNOWN","retryable":true}}"""
            else 200 to REFRESH_BODY
        }
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        runtime.refreshSession()
        assertTrue(awaitCondition(AWAIT_MILLIS) { runtime.credentialStatus.value == GatewayCredentialStatus.RECOVERY_PENDING && !runtime.isRefreshingSession.value })
        runtime.refreshSession()
        assertTrue(awaitCondition(AWAIT_MILLIS) { credentialStore.loadRefresh(profileId())?.decodeToString() == "refresh_2" })
        val attempts = gateway.requests.filter { it.target == REFRESH_PATH }
        assertEquals(2, attempts.size)
        assertEquals(attempts[0].headers.single { it.startsWith("Idempotency-Key:", true) },
            attempts[1].headers.single { it.startsWith("Idempotency-Key:", true) })
    }

    @Test
    fun revocationDuringRotationPersistenceCannotResurrectAutomaticLogin() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blockingStore = object : com.openandroidintelligence.gateway.auth.GatewayCredentialStore by credentialStore {
            override fun saveRefresh(profileId: String, credential: ByteArray) {
                if (credential.decodeToString() == "refresh_2") {
                    entered.countDown()
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
                credentialStore.saveRefresh(profileId, credential)
            }
        }
        val path = "/open-android-intelligence/v2/events"
        gateway.respond(path, ": heartbeat\n\n", contentType = "text/event-stream")
        val runtime = GatewayRuntime(ApplicationProvider.getApplicationContext(), runtimeScope, pairingGrants,
            credentialStore = blockingStore, deviceKeys = deviceKeys, localDocumentKeyProvider = TestDocumentKeys)
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        runtime.refreshSession()
        try {
            assertTrue(awaitCondition(AWAIT_MILLIS) { entered.count == 0L })
            gateway.respond(path, "id: evt_revoked\nevent: session.revoked\ndata: {\"payload\":{\"sessionId\":\"sess_1\"}}\n\n", contentType = "text/event-stream")
            assertTrue(awaitCondition(AWAIT_MILLIS) { runtime.loginStatus.value == GatewayLoginStatus.REAUTHENTICATION_REQUIRED })
        } finally { release.countDown() }
        assertTrue(awaitCondition(AWAIT_MILLIS) { runtime.phase.value is ConnectionPhase.Failed && !credentialStore.hasRefresh(profileId()) })
        assertFalse(ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("open_android_intelligence_runtime", Context.MODE_PRIVATE).contains("last_profile_id"))
        assertTrue(deviceKeys.hasKey(profileId()))
    }

    @Test
    fun logoutWithAnExpiredAccessSessionRenewsOnceAndCannotRestoreTheExitedAccount() {
        gateway.respond("/open-android-intelligence/v2/sessions/current") { request ->
            if (request.headers.any { it.equals("Authorization: Bearer token_2", true) })
                200 to """{"data":{"sessionId":"sess_2","refreshRevoked":true}}"""
            else 401 to """{"error":{"code":"SESSION_EXPIRED"}}"""
        }
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        runtime.logout(revokeRefresh = true)
        assertTrue(awaitCondition(AWAIT_MILLIS) { runtime.phase.value is ConnectionPhase.Disconnected && !credentialStore.hasRefresh(profileId()) })
        assertEquals(1, gateway.requests.count { it.target == REFRESH_PATH })
        val before = gateway.requests.size
        runtime().restoreSessionIfAvailable()
        assertEquals(before, gateway.requests.size)
        assertTrue(deviceKeys.hasKey(profileId()))
    }

    @Test
    fun aFailedRotatedCredentialWriteRetriesPersistenceWithoutReusingTheOldCredential() {
        val failOnce = AtomicBoolean(true)
        val store = object : com.openandroidintelligence.gateway.auth.GatewayCredentialStore by credentialStore {
            override fun saveRefresh(profileId: String, credential: ByteArray) {
                if (credential.decodeToString() == "refresh_2" && failOnce.getAndSet(false)) throw java.io.IOException("storage temporarily unavailable")
                credentialStore.saveRefresh(profileId, credential)
            }
        }
        val runtime = GatewayRuntime(ApplicationProvider.getApplicationContext(), runtimeScope, pairingGrants,
            credentialStore = store, deviceKeys = deviceKeys, localDocumentKeyProvider = TestDocumentKeys)
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        val workbench = runtime.controller.value
        runtime.refreshSession()
        assertTrue(awaitCondition(AWAIT_MILLIS) { runtime.credentialStatus.value == GatewayCredentialStatus.RECOVERY_PENDING && !runtime.isRefreshingSession.value })
        assertEquals("refresh_1", credentialStore.loadRefresh(profileId())?.decodeToString())
        runtime.refreshSession()
        assertTrue(awaitCondition(AWAIT_MILLIS) { credentialStore.loadRefresh(profileId())?.decodeToString() == "refresh_2" && !runtime.isRefreshingSession.value })
        assertEquals(1, gateway.requests.count { it.target == REFRESH_PATH })
        assertSame(workbench, runtime.controller.value)
    }

    @Test
    fun anAuthoritativeRecoveredGenerationConfirmsBackgroundWorkAndItsCompletionClearsEligibility() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val recovery = AgentSessionRecoveryState(context)
        assertTrue(recovery.markPendingWork())
        assertNotNull(recovery.beginRecovery())
        val conversation = "/open-android-intelligence/v2/conversations/conv_recovered"
        gateway.respond("/open-android-intelligence/v2/conversations", """{"protocol":"2.1","data":{"conversations":[{"conversationId":"conv_recovered","title":"Recovered task"}]}}""")
        gateway.respond("$conversation/messages", """{"protocol":"2.1","data":{"messages":[],"nextCursor":null}}""")
        gateway.respond("$conversation/generations/current", """{"protocol":"2.1","data":{"generation":{"conversationId":"conv_recovered","generationId":"generation_recovered","state":"running"}}}""")
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        requireNotNull(runtime.controller.value).openThread("conv_recovered")
        val recovered = awaitCondition(AWAIT_MILLIS) { recovery.hasPendingWork && !recovery.isRecovering }
        assertTrue("恢复资格=${recovery.hasPendingWork}/${recovery.isRecovering}，通知权限=${runtime.replyNotifications.allowed.value}，生成=${runtime.controller.value?.state?.value?.generation}，请求=${gateway.requests.map { it.target }}", recovered)

        gateway.respond("$conversation/generations/current", """{"protocol":"2.1","data":{"generation":null}}""")
        requireNotNull(runtime.controller.value).onForegrounded()
        assertTrue(awaitCondition(AWAIT_MILLIS) { !recovery.hasPendingWork })
    }

    @Test
    fun firstLoginPersistsTheWorkbenchWithANonExportableRandomizedEncryptionKey() {
        Security.insertProviderAt(RandomizedEncryptionRequiredProvider(), 1)
        try {
            gateway.respond("/open-android-intelligence/v2/events", "", contentType = "text/event-stream")
            val key = NonExportableAesKey()
            val keys = object : AesGcmKeyProvider {
                override fun getOrCreate() = key
                override fun delete() = Unit
            }
            val runtime = runtime(keys)
            runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
            awaitConnected(runtime)
            val controller=requireNotNull(runtime.controller.value)
            assertNull("会话密钥必须保持不可导出",key.encoded)
            awaitWorkbenchCommit(controller)
            val context=ApplicationProvider.getApplicationContext<Application>()
            val installId=requireNotNull(context.getSharedPreferences("open_android_intelligence_runtime",Context.MODE_PRIVATE)
                .getString("installation_id",null))
            fun reopen()=com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror(context,
                com.openandroidintelligence.conversation.ports.ConversationScope(profileId(),gateway.baseUrl,"acc_stub",installId),keys)
            val initial=reopen()
            try {
                assertNotNull("工作台首次提交必须能由新存储实例认证并恢复",initial.load())
            } finally { awaitAction("初始镜像实例必须完成关闭") { initial.closeStore() } }
            controller.editDraft("不可导出密钥保护的持久草稿")
            awaitWorkbenchCommit(controller)
            val restored=reopen()
            try {
                assertEquals("不可导出密钥保护的持久草稿",requireNotNull(restored.load()).draft)
            } finally { awaitAction("草稿镜像实例必须完成关闭") { restored.closeStore() } }
            assertTrue("加密镜像必须使用新的 SQLite 分记录存储",context.noBackupFilesDir.resolve("conversation-mirror")
                .walkTopDown().any { it.isFile && it.name=="mirror-v1.db" && it.length()>0 })
            assertTrue(credentialStore.hasRefresh(profileId()))
            assertTrue(deviceKeys.hasKey(profileId()))
            runtime.chooseAnotherAccount()
            awaitAccountChooser(runtime)
        } finally {
            Security.removeProvider(RandomizedEncryptionRequiredProvider.NAME)
        }
    }

    @Test
    fun postLoginStorageFailureReportsItsStageAndPreservesAccountCredentials() {
        val records = CopyOnWriteArrayList<String>()
        val previousSink = GatewayLog.sink
        GatewayLog.sink = { tag, message -> records += "$tag $message" }
        val sensitiveText = "口令令牌响应正文不得出现在诊断中"
        try {
            val keys = object : AesGcmKeyProvider {
                override fun getOrCreate(): javax.crypto.SecretKey = throw IllegalStateException(sensitiveText)
                override fun delete() = error("失败不能删除密钥")
            }
            val runtime = runtime(keys)
            runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
            assertTrue(awaitCondition(AWAIT_MILLIS) { runtime.phase.value is ConnectionPhase.Failed })
            assertEquals("POST_LOGIN_INITIALIZATION_FAILED:ATTACHMENT_RECOVERY", (runtime.phase.value as ConnectionPhase.Failed).code)
            assertNull(runtime.controller.value)
            assertNull(runtime.connectedAccountId)
            assertNull(pairingGrants.state.value)
            assertTrue("登录已签发的刷新凭据必须保留", credentialStore.hasRefresh(profileId()))
            assertTrue("配对设备密钥必须保留", deviceKeys.hasKey(profileId()))
            assertEquals(listOf(profileId()), runtime.savedProfiles.value.map { it.localProfileId })
            assertFalse("本地初始化失败发生在事件流请求之前", gateway.requests.any { it.target.contains("/events") })
            assertTrue("诊断必须点明阶段和异常类型", records.any { it.contains("ATTACHMENT_RECOVERY") && it.contains("IllegalStateException") })
            assertFalse("不得记录原始异常信息", records.any { it.contains(sensitiveText) || it.contains("refresh_1") || it.contains("token_1") })
        } finally {
            GatewayLog.sink = previousSink
        }
    }

    @Test
    fun credentialRefreshDoesNotReopenStorageOrReplaceTheLiveWorkbench() {
        val rejectStorage = AtomicBoolean(false)
        val keys = object : AesGcmKeyProvider {
            override fun getOrCreate(): javax.crypto.SecretKey {
                if (rejectStorage.get()) throw IllegalStateException("测试存储不可用")
                return TestDocumentKeys.getOrCreate()
            }
            override fun delete() = error("续期失败不能删除密钥")
        }
        val runtime = runtime(keys)
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        assertTrue("先等待旧工作台的初始读取完成，避免把故障注入到无关的旧请求", awaitCondition(AWAIT_MILLIS) {
            val state = runtime.controller.value?.state?.value
            state?.threads is com.openandroidintelligence.conversation.state.Loadable.Failed &&
                state.catalog is com.openandroidintelligence.conversation.state.Loadable.Failed
        })
        rejectStorage.set(true)
        val workbench = runtime.controller.value
        runtime.refreshSession()

        assertTrue(awaitCondition(AWAIT_MILLIS) { !runtime.isRefreshingSession.value && credentialStore.loadRefresh(profileId())?.decodeToString() == "refresh_2" })
        assertTrue(runtime.phase.value is ConnectionPhase.Connected)
        assertSame(workbench, runtime.controller.value)
        assertNotNull(runtime.connectedAccountId)
        assertTrue(deviceKeys.hasKey(profileId()))
        assertEquals(listOf(profileId()), runtime.savedProfiles.value.map { it.localProfileId })
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
    fun anAuthoritativelyRejectedRefreshRequiresReauthenticationWithoutDeletingDeviceKeys() {
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
        assertTrue(runtime.phase.value is ConnectionPhase.Failed)
        assertFalse("Gateway 明确拒绝后本机不得再保留刷新凭据", credentialStore.hasRefresh(profileId()))
        assertTrue(deviceKeys.hasKey(profileId()))

        runtime.dismissOperationNotice()
        assertNull("notice 必须可被界面关闭", runtime.operationNotice.value)
    }

    private fun runtime(keys: AesGcmKeyProvider = TestDocumentKeys): GatewayRuntime = GatewayRuntime(
        localDocumentKeyProvider = keys,
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
    fun refusedLogoutKeepsTheSessionAndItsCredentialsForRetry() {
        gateway.respond("/open-android-intelligence/v2/sessions/current", """{"error":{"code":"SERVICE_UNAVAILABLE"}}""", 503)
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        runtime.logout(revokeRefresh = true)
        assertTrue(awaitCondition(AWAIT_MILLIS) { runtime.operationNotice.value?.contains("未获 Gateway 确认") == true })
        assertTrue(runtime.phase.value is ConnectionPhase.Connected)
        assertNotNull(runtime.controller.value)
        assertTrue(credentialStore.hasRefresh(profileId()))
        assertTrue(deviceKeys.hasKey(profileId()))
    }

    @Test
    fun confirmedLogoutStillDisablesAutomaticRestoreWhenLocalCredentialCleanupFails() {
        gateway.respond("/open-android-intelligence/v2/sessions/current", """{"data":{}}""")
        val failingCredentials = object : com.openandroidintelligence.gateway.auth.GatewayCredentialStore by credentialStore {
            override fun clearRefresh(profileId: String) = error("DEVICE_STORAGE_UNAVAILABLE")
        }
        val runtime = GatewayRuntime(
            context = ApplicationProvider.getApplicationContext(),
            scope = runtimeScope,
            pairingGrants = pairingGrants,
            credentialStore = failingCredentials,
            deviceKeys = deviceKeys,
            localDocumentKeyProvider = TestDocumentKeys,
        )
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        runtime.logout(revokeRefresh = true)
        assertTrue(awaitCondition(AWAIT_MILLIS) { runtime.phase.value is ConnectionPhase.Disconnected })
        assertEquals("Gateway 已登出，但本机凭据清理失败，请检查设备存储。", runtime.operationNotice.value)
        assertTrue(credentialStore.hasRefresh(profileId()))
        assertTrue(deviceKeys.hasKey(profileId()))
        assertEquals(listOf(profileId()), runtime.savedProfiles.value.map { it.localProfileId })

        val priorRequests = gateway.requests.size
        val restarted = runtime()
        restarted.restoreSessionIfAvailable()
        assertTrue("远端已确认退出后，本机清理失败也必须禁用自动恢复", restarted.phase.value is ConnectionPhase.Disconnected)
        assertNull(restarted.controller.value)
        assertEquals(priorRequests, gateway.requests.size)
    }

    @Test
    fun cancellingAnUnconfirmedLogoutKeepsCredentialsWithoutReportingANetworkFailure() {
        val path = "/open-android-intelligence/v2/sessions/current"
        val release = gateway.holdResponse(path, """{"data":{}}""")
        try {
            val runtime = runtime()
            runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
            awaitConnected(runtime)
            runtime.dismissOperationNotice()
            runtime.logout(revokeRefresh = true)
            assertTrue(awaitCondition(AWAIT_MILLIS) { gateway.requests.any { it.method == "DELETE" && it.target.startsWith(path) } })
            runtimeScope.cancel()
            release.countDown()
            assertTrue(awaitCondition(AWAIT_MILLIS) { runtimeScope.coroutineContext[Job]?.isCompleted == true })
            assertNull("协程取消不能伪装成网络失败", runtime.operationNotice.value)
            assertTrue(credentialStore.hasRefresh(profileId()))
            assertTrue(deviceKeys.hasKey(profileId()))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun logoutPreservesOtherAccountsAndHistoryAndCannotAutoRestoreTheExitedAccount() {
        gateway.respond("/open-android-intelligence/v2/sessions/current", """{"data":{}}""")
        val context = ApplicationProvider.getApplicationContext<Application>()
        AndroidAccountProfileStore(context).save(AccountProfile(profileId("bob"), gateway.baseUrl, "bob", ""))
        credentialStore.saveRefresh(profileId("bob"), "bob_refresh".toByteArray())
        deviceKeys.publicKeyBase64Url(profileId("bob"))
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        val installId = requireNotNull(context.getSharedPreferences("open_android_intelligence_runtime", Context.MODE_PRIVATE).getString("installation_id", null))
        val mirror = com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror(
            context,
            com.openandroidintelligence.conversation.ports.ConversationScope(profileId(), gateway.baseUrl, "acc_stub", installId),
            TestDocumentKeys,
        )
        val history = com.openandroidintelligence.conversation.ports.TimelinePage(
            listOf(TimelineMessage("msg_kept", "user",
                listOf(com.openandroidintelligence.conversation.model.MessagePart.Text("退出后保留的历史")), 1L, "CONFIRMED",
                conversationId=com.openandroidintelligence.conversation.model.ConversationId("conv_kept"))),
            null,
            1L,
        )
        mirror.saveTimeline("conv_kept", history)

        runtime.logout(revokeRefresh = true)
        assertTrue(awaitCondition(AWAIT_MILLIS) { runtime.phase.value is ConnectionPhase.Disconnected })
        assertEquals(setOf(profileId(), profileId("bob")), runtime.savedProfiles.value.map { it.localProfileId }.toSet())
        assertEquals("bob_refresh", credentialStore.loadRefresh(profileId("bob"))!!.decodeToString())
        assertTrue(deviceKeys.hasKey(profileId("bob")))
        assertTrue(deviceKeys.hasKey(profileId()))
        assertEquals(history, mirror.timeline("conv_kept"))
        assertFalse(gateway.requests.any { it.target.contains("pairings/current") })
        val priorAuthRequests = gateway.requests.count { it.target in setOf(NEGOTIATE_PATH, REFRESH_PATH) }
        val restarted = runtime()
        restarted.restoreSessionIfAvailable()
        assertTrue(restarted.phase.value is ConnectionPhase.Disconnected)
        assertNull(restarted.controller.value)
        assertEquals("已退出的账号不得自动发起恢复请求", priorAuthRequests,
            gateway.requests.count { it.target in setOf(NEGOTIATE_PATH, REFRESH_PATH) })
    }

    @Test
    fun offlineLogoutExplainsTheMissingConfirmationAndPreservesTheMirrorAndCredentials() {
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        val onlineController=requireNotNull(runtime.controller.value)
        onlineController.editDraft("离线草稿仍需保留")
        // 以真实关键提交完成模拟退出，不能把取消作用域当成同步落盘。
        awaitWorkbenchCommit(onlineController)
        runtimeScope.cancel()
        gateway.closed()
        val offlineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val offline = GatewayRuntime(
                context = ApplicationProvider.getApplicationContext(),
                scope = offlineScope,
                pairingGrants = pairingGrants,
                credentialStore = credentialStore,
                deviceKeys = deviceKeys,
                localDocumentKeyProvider = TestDocumentKeys,
            )
            offline.restoreSessionIfAvailable()
            assertTrue("断网重启必须进入已保存的离线镜像", awaitCondition(AWAIT_MILLIS) { offline.phase.value is ConnectionPhase.OfflineMirror })
            val controller = requireNotNull(offline.controller.value)
            offline.dismissOperationNotice()
            offline.logout(revokeRefresh = true)
            assertEquals("当前离线，无法向 Gateway 确认退出，请先重新连接。", offline.operationNotice.value)
            assertTrue(offline.phase.value is ConnectionPhase.OfflineMirror)
            assertEquals(controller, offline.controller.value)
            assertEquals("离线草稿仍需保留", controller.state.value.draft)
            assertTrue(credentialStore.hasRefresh(profileId()))
            assertTrue(deviceKeys.hasKey(profileId()))
        } finally {
            offlineScope.cancel()
        }
    }

    @Test
    fun switchingBackUsesOnlyTheSelectedProfilesCredential() {
        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitConnected(runtime)
        runtime.chooseAnotherAccount()
        awaitAccountChooser(runtime)
        runtime.login(gateway.baseUrl, "bob", "secret".toCharArray())
        assertEquals("bob",awaitConnected(runtime).username)
        credentialStore.saveRefresh(profileId("bob"), "bob_refresh".toByteArray())
        runtime.chooseAnotherAccount()
        awaitAccountChooser(runtime)
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

    private fun awaitWorkbenchCommit(controller:com.openandroidintelligence.conversation.state.WorkbenchController) {
        awaitAction("草稿和工作台必须等待真实关键提交完成") { controller.flushPendingPersistence() }
    }

    private fun awaitAction(message:String,action:suspend () -> Unit) {
        val completion=runtimeScope.async { action() }
        assertTrue(message,awaitCondition(AWAIT_MILLIS) { completion.isCompleted })
        runBlocking { completion.await() }
    }

    private fun awaitAccountChooser(runtime:GatewayRuntime) {
        val prefs=ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("open_android_intelligence_runtime",Context.MODE_PRIVATE)
        assertTrue("旧账号完成提交、关闭和取消默认恢复后才能继续选择账号",awaitCondition(AWAIT_MILLIS) {
            runtime.phase.value is ConnectionPhase.Disconnected && runtime.controller.value==null &&
                runtime.connectedAccountId==null && !prefs.contains("last_profile_id")
        })
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
