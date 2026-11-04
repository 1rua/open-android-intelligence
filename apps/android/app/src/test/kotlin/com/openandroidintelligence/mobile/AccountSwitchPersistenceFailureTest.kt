package com.openandroidintelligence.mobile

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountSwitchPersistenceFailureTest {
    @Test fun failedLastProfileCommitKeepsTheOriginalControllerAndWorkspaceUsable() {
        org.robolectric.nativeruntime.DefaultNativeRuntimeLoader.injectAndLoad()
        val context = LastProfileClearFailureContext(ApplicationProvider.getApplicationContext<Application>())
        val gateway = LoopbackGatewayStub()
        val credentials = InMemoryCredentialStore()
        val keys = InMemoryDeviceKeySource()
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore(InMemoryAuditSink()))
        val documentKeys = object : AesGcmKeyProvider {
            override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 43 }, "AES")
            override fun delete() = Unit
        }
        val scopes = mutableListOf<CoroutineScope>()
        val controllers = mutableSetOf<WorkbenchController>()
        fun newRuntime(): GatewayRuntime {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
            return GatewayRuntime(context, scope, grants, credentialStore = credentials, deviceKeys = keys,
                localDocumentKeyProvider = documentKeys)
        }
        try {
            gateway.respond("$API/negotiate", NEGOTIATE_BODY)
            gateway.respond("$API/sessions/password", PASSWORD_BODY)
            gateway.respond("$API/conversations", """{"protocol":"2.1","data":{"conversations":[],"nextCursor":null}}""")
            gateway.respond("$API/events", "", contentType = "text/event-stream")
            val initial = newRuntime()
            initial.login(gateway.baseUrl, "operator", "secret".toCharArray())
            assertTrue("必须先成功登录并保存账号，不能把初始化失败当作切换失败", awaitCondition {
                initial.phase.value is ConnectionPhase.Connected && initial.controller.value != null
            })
            val prefs = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            val profileId = checkNotNull(prefs.getString("last_profile_id", null))
            val credential = checkNotNull(credentials.loadRefresh(profileId)).decodeToString()
            assertEquals(0, context.rejectedCommits.get())
            val initialController = checkNotNull(initial.controller.value).also(controllers::add)
            awaitAction { initialController.closeAndAwait() }
            scopes.first().cancel()
            assertTrue("首个 Runtime 的后台任务必须结束", awaitCondition {
                scopes.first().coroutineContext[Job]?.isCompleted == true
            })

            gateway.respond("$API/negotiate", """{"error":{"code":"PROTOCOL_INCOMPATIBLE","retryable":false}}""", status = 406)
            val runtime = newRuntime()
            runtime.restoreSessionIfAvailable()
            assertTrue("406 恢复必须保留可读工作台", awaitCondition {
                runtime.phase.value is ConnectionPhase.Failed && runtime.operationNotice.value != null &&
                    runtime.controller.value != null && runtime.localSessionState.value is LocalSessionState.Workspace
            })
            assertEquals("PROTOCOL_INCOMPATIBLE:406", (runtime.phase.value as ConnectionPhase.Failed).code)
            val original = checkNotNull(runtime.controller.value).also(controllers::add)
            val workspace = runtime.localSessionState.value
            val failureNotice = runtime.operationNotice.value
            val lastProfile = CLEAR_KEYS.associateWith { prefs.all[it] }
            val draftBeforeSwitch = "F06 切换前尚未提交的草稿"
            original.editDraft(draftBeforeSwitch)
            context.failLastProfileClear.set(true)
            val requestBoundary = gateway.requests.size

            runtime.chooseAnotherAccount()

            assertTrue("清除账号选择失败必须返回可重试提示", awaitCondition {
                context.rejectedCommits.get() == 1 && runtime.operationNotice.value != failureNotice
            })
            assertSame("写盘失败不能替换原 controller", original, runtime.controller.value)
            assertEquals("写盘失败不能进入账号选择器", workspace, runtime.localSessionState.value)
            assertTrue(runtime.localSessionState.value is LocalSessionState.Workspace)
            assertEquals("PROTOCOL_INCOMPATIBLE:406", (runtime.phase.value as ConnectionPhase.Failed).code)
            assertEquals("账号切换未完成，请检查手机存储后重试。", runtime.operationNotice.value)
            assertEquals(lastProfile, CLEAR_KEYS.associateWith { prefs.all[it] })
            assertEquals(credential, credentials.loadRefresh(profileId)!!.decodeToString())
            assertTrue(keys.hasKey(profileId))
            assertEquals(draftBeforeSwitch, original.state.value.draft)
            val before = EncryptedConversationMirror(context, original.localConversationScope, documentKeys)
            try {
                assertEquals("clearLastProfile 失败前必须已完成草稿 flush", draftBeforeSwitch, before.load()!!.draft)
            } finally { runBlocking { before.closeStore() } }

            val edited = "F06 失败后仍可继续编辑并保存"
            original.editDraft(edited)
            assertEquals("不能保留一个已经关闭的空壳 controller", edited, original.state.value.draft)
            awaitAction { original.flushPendingPersistence() }
            val reopened = EncryptedConversationMirror(context, original.localConversationScope, documentKeys)
            try { assertEquals("原工作台必须仍可持久化新草稿", edited, reopened.load()!!.draft) }
            finally { runBlocking { reopened.closeStore() } }
            assertSame(original, runtime.controller.value)
            assertTrue(runtime.localSessionState.value is LocalSessionState.Workspace)
            assertTrue("切换失败不能撤销远端登录", gateway.requests.drop(requestBoundary).none {
                it.method == "POST" && it.target.substringBefore('?') == "$API/sessions/logout"
            })
        } finally {
            try { controllers.forEach { controller -> awaitAction { controller.closeAndAwait() } } }
            finally {
                scopes.forEach { it.cancel() }
                try {
                    assertTrue("后台任务必须在 Robolectric Context 释放前结束", awaitCondition {
                        scopes.all { it.coroutineContext[Job]?.isCompleted == true }
                    })
                } finally { gateway.closed() }
            }
        }
    }

    private class LastProfileClearFailureContext(base: Context) : ContextWrapper(base) {
        val failLastProfileClear = AtomicBoolean(false)
        val rejectedCommits = AtomicInteger()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val delegate = super.getSharedPreferences(name, mode)
            if (name != RUNTIME_PREFS) return delegate
            return object : SharedPreferences by delegate {
                override fun edit(): SharedPreferences.Editor {
                    val editor = delegate.edit()
                    val removals = mutableSetOf<String>()
                    return object : SharedPreferences.Editor by editor {
                        override fun remove(key: String?): SharedPreferences.Editor {
                            if (key != null) removals += key
                            editor.remove(key)
                            return this
                        }
                        override fun commit(): Boolean {
                            if (failLastProfileClear.get() && removals == CLEAR_KEYS) {
                                rejectedCommits.incrementAndGet()
                                return false
                            }
                            return editor.commit()
                        }
                    }
                }
            }
        }
    }

    private fun awaitAction(action: suspend () -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val completion = scope.async { action() }
        try {
            assertTrue("持久化操作必须完成", awaitCondition { completion.isCompleted })
            runBlocking { completion.await() }
        } finally { scope.cancel() }
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private companion object {
        const val API = "/open-android-intelligence/v2"
        const val RUNTIME_PREFS = "open_android_intelligence_runtime"
        val CLEAR_KEYS = setOf("last_gateway_url", "last_username", "last_profile_id", "last_account_id",
            "last_device_id", "last_session_id", "device_key_encoding_version")
        const val NEGOTIATE_BODY = """{"protocol":"2.1","data":{"protocol":{"major":2,"minor":1},"features":{"auth":["password","refresh"],"messages":"chat-v1","attachments":"staged-sha256-v1","events":"sse-cursor-v1","deviceRequests":"risk-queue-v1","conversationUi":[]},"limits":{"attachmentTtlSeconds":3600,"eventRetentionSeconds":86400,"maxClockSkewSeconds":120},"gatewayIdentity":{"deploymentId":"dep_stub","tlsSpkiSha256":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}}"""
        const val PASSWORD_BODY = """{"data":{"accountId":"acc_stub","deviceId":"dev_stub","sessionId":"sess_1","accessToken":"token_1","refreshCredential":"refresh_1","pairingSummary":"stub pairing"}}"""
    }
}
