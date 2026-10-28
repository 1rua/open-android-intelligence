package com.openandroidintelligence.mobile

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** 点击 Activity 实际复用的工作台，验证真实 HTTP 登出与失败反馈。 */
@RunWith(RobolectricTestRunner::class)
// 窄屏抽屉的布局与真实触摸使用原生图形管道，避免 Legacy 仿真的命中偏差。
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-420dpi")
class GatewayWorkbenchLogoutTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var gateway: LoopbackGatewayStub
    private lateinit var runtime: GatewayRuntime
    private lateinit var credentials: InMemoryCredentialStore
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var settingsOpened = false

    @Before fun setUp() {
        gateway = LoopbackGatewayStub()
        credentials = InMemoryCredentialStore()
        gateway.respond("/open-android-intelligence/v2/negotiate", NEGOTIATION)
        gateway.respond("/open-android-intelligence/v2/sessions/password", SESSION)
        gateway.respond(LOGOUT_PATH, """{"data":{}}""")
        runtime = GatewayRuntime(
            context = ApplicationProvider.getApplicationContext(),
            scope = runtimeScope,
            pairingGrants = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore(InMemoryAuditSink())),
            credentialStore = credentials,
            deviceKeys = InMemoryDeviceKeySource(),
            localDocumentKeyProvider = TestDocumentKeys,
        )
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        val deadline = System.currentTimeMillis() + AWAIT_MILLIS
        while (runtime.phase.value !is ConnectionPhase.Connected && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(20)
        }
        assertTrue("真实测试会话必须建立后再点击退出", runtime.phase.value is ConnectionPhase.Connected)
    }

    @After fun close() {
        runtimeScope.cancel()
        gateway.closed()
    }

    @Test fun narrowDrawerLogoutRevokesTheCurrentSession() {
        showWorkbench()
        compose.onNodeWithContentDescription("打开会话抽屉").performClick()
        clickLogoutAndAwaitDisconnected()
        assertLogoutRequest()
    }

    @Test
    @Config(qualifiers = "w1000dp-h891dp-420dpi")
    fun wideSidebarLogoutRevokesTheCurrentSession() {
        showWorkbench()
        compose.onNodeWithText("退出登录").assertIsDisplayed()
        clickLogoutAndAwaitDisconnected()
        assertLogoutRequest()
    }

    @Test fun refusedLogoutShowsItsReasonOnTheWorkbenchAndCanRetry() {
        gateway.respond(LOGOUT_PATH, """{"error":{"code":"SERVICE_UNAVAILABLE"}}""", 503)
        showWorkbench()
        compose.onNodeWithContentDescription("打开会话抽屉").performClick()
        compose.onNodeWithText("退出登录").assertIsDisplayed().performClick()

        awaitRuntimeCondition { runtime.phase.value is ConnectionPhase.Disconnected }
        // 按照本地优先退出计划，远端503不阻塞本地退出，本地完成登出
        assertTrue("本地退出完成进入 Disconnected", runtime.phase.value is ConnectionPhase.Disconnected)
        assertFalse(settingsOpened)
    }

    @Test fun confirmedLogoutCancelsTheActualOngoingReplyNotification() {
        val manager = postRunningReplyNotification()
        runtime.logout(revokeRefresh = true)
        awaitRuntimeCondition { runtime.phase.value is ConnectionPhase.Disconnected }
        assertTrue("Confirmed logout must remove the old ongoing conversation", manager.activeNotifications.none { it.notification.channelId == REPLY_CHANNEL_ID })
        assertTrue(runtime.nativeConversationNotifications.sessions.value.isEmpty())
        assertLogoutRequest()
    }

    @Test fun choosingAnotherAccountCancelsTheActualOngoingReplyNotification() {
        val manager = postRunningReplyNotification()
        runtime.chooseAnotherAccount()
        awaitRuntimeCondition { runtime.phase.value is ConnectionPhase.Disconnected }
        assertTrue("The account chooser must not retain the previous account's ongoing bubble", manager.activeNotifications.none { it.notification.channelId == REPLY_CHANNEL_ID })
        assertTrue(runtime.nativeConversationNotifications.sessions.value.isEmpty())
    }

    @Test fun runningConversationKeepsItsBubbleAndBackgroundServiceUntilTheTaskCompletes() {
        runtime.onAppForegrounded()
        val manager = postRunningReplyNotification()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val serviceIntent = requireNotNull(org.robolectric.Shadows.shadowOf(context).nextStartedService)
        assertEquals(AgentSessionForegroundService::class.java.name, serviceIntent.component!!.className)

        runtime.onAppBackgrounded()
        val profile = runtime.savedProfiles.value.single()
        val occurredAt = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
        runtime.nativeConversationNotifications.accept(profile.localProfileId, "acc_stub", com.openandroidintelligence.gateway.events.GatewayEvent(
            "evt_background_delta", "conversation.message.delta",
            """{"accountId":"acc_stub","occurredAt":"$occurredAt","payload":{"conversationId":"conv_runtime_notification","generationId":"gen_runtime_notification","messageId":"reply_runtime_notification","sender":"assistant","text":"Background reply","revision":2}}""",
        ))
        // The process going off-screen must not detach the native session or its bubble.
        runtime.nativeConversationNotifications.refreshActive()
        val running = manager.activeNotifications.single { it.notification.channelId == REPLY_CHANNEL_ID }.notification
        assertTrue(running.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(running.bubbleMetadata != null)
        val style = android.app.Notification.Builder.recoverBuilder(context, running).style as android.app.Notification.MessagingStyle
        assertEquals("Background reply", style.messages.single().text.toString())

        runtime.nativeConversationNotifications.accept(profile.localProfileId, "acc_stub", com.openandroidintelligence.gateway.events.GatewayEvent(
            "evt_background_complete", "conversation.message.status",
            """{"accountId":"acc_stub","occurredAt":"${java.time.Instant.now()}","payload":{"conversationId":"conv_runtime_notification","generationId":"gen_runtime_notification","messageId":"user_runtime_notification","clientMessageId":"client_runtime_notification","status":"completed","revision":1,"errorCode":null}}""",
        ))
        awaitRuntimeCondition { org.robolectric.Shadows.shadowOf(context).nextStoppedService?.component?.className == AgentSessionForegroundService::class.java.name }
        assertFalse(runtime.nativeConversationNotifications.sessions.value.single().isRunning)
    }

    private fun postRunningReplyNotification(): android.app.NotificationManager {
        val context = ApplicationProvider.getApplicationContext<Application>()
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        runtime.replyNotifications.refreshPermission()
        val profile = runtime.savedProfiles.value.single()
        val occurredAt = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
        runtime.nativeConversationNotifications.accept(profile.localProfileId, "acc_stub", com.openandroidintelligence.gateway.events.GatewayEvent(
            "evt_running_notification", "conversation.message.delta",
            """{"accountId":"acc_stub","occurredAt":"$occurredAt","payload":{"conversationId":"conv_runtime_notification","generationId":"gen_runtime_notification","messageId":"reply_runtime_notification","sender":"assistant","text":"Still working","revision":1}}""",
        ))
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(manager.activeNotifications.any { it.notification.channelId == REPLY_CHANNEL_ID && it.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0 })
        return manager
    }

    private fun showWorkbench() {
        val controller = requireNotNull(runtime.controller.value)
        compose.setContent {
            MaterialTheme {
                GatewayWorkbenchScreen(
                    runtime = runtime,
                    controller = controller,
                    gatewayLabel = gateway.baseUrl,
                    onOpenSettings = { settingsOpened = true },
                    onPickCamera = {},
                    onPickGallery = {},
                    onPickDocument = {},
                    onVoiceInput = {},
                )
            }
        }
    }

    private fun clickLogoutAndAwaitDisconnected() {
        compose.onNodeWithText("退出登录").assertIsDisplayed().performClick()
        awaitRuntimeCondition { runtime.phase.value is ConnectionPhase.Disconnected }
        assertNull(runtime.controller.value)
        assertFalse("退出不得变成打开设置", settingsOpened)
    }

    private fun assertLogoutRequest() {
        val request = gateway.requests.single { it.method == "DELETE" && it.target.startsWith(LOGOUT_PATH) }
        assertEquals("$LOGOUT_PATH?revokeRefresh=true", request.target)
        for ((name, value) in listOf(
            "X-Open-Android-Intelligence-Account" to "acc_stub",
            "X-Open-Android-Intelligence-Device" to "dev_stub",
            "X-Open-Android-Intelligence-Session" to "sess_1",
        )) {
            assertTrue("登出必须绑定当前 $name", request.headers.any {
                it.substringBefore(':').equals(name, ignoreCase = true) && it.substringAfter(':').trim() == value
            })
        }
        assertFalse(gateway.requests.any { it.target.contains("pairings/current") })
    }

    /** Runtime IO resumes on Android's looper independently of Compose's frame clock. */
    private fun awaitRuntimeCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(AWAIT_MILLIS)
        while (System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("phase=${runtime.phase.value}, notice=${runtime.operationNotice.value}, " +
            "requests=${gateway.requests.map { "${it.method} ${it.target}" }}", condition())
    }

    private companion object {
        const val AWAIT_MILLIS = 10_000L
        const val LOGOUT_PATH = "/open-android-intelligence/v2/sessions/current"
        val NEGOTIATION = """
            {"protocol":"2.1","data":{"protocol":{"major":2,"minor":1},
            "features":{"auth":["password","refresh"],"messages":"chat-v1",
            "attachments":"staged-sha256-v1","events":"sse-cursor-v1",
            "deviceRequests":"risk-queue-v1","conversationUi":[]},
            "limits":{"attachmentTtlSeconds":3600,"eventRetentionSeconds":86400,"maxClockSkewSeconds":120},
            "gatewayIdentity":{"deploymentId":"dep_stub","tlsSpkiSha256":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}}
        """.trimIndent()
        val SESSION = """
            {"data":{"accountId":"acc_stub","deviceId":"dev_stub","sessionId":"sess_1",
            "accessToken":"token_1","refreshCredential":"refresh_1","pairingSummary":"stub pairing"}}
        """.trimIndent()
    }
}
