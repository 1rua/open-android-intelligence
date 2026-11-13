package com.openandroidintelligence.mobile

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.agents.AgentSessionClient
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.negotiation.NATIVE_AGENT_SESSIONS_HEADER
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.CoroutineContext

/** Native streams own the same background lease as workbench and bubble sends. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], application = Application::class)
class NativeAgentBackgroundWorkTest {
    @Test fun otherAgentAssistantPresenceMustNotSuppressTheCompletedReply() {
        verifyCrossAgentCompletion(visible = true)
    }

    @Test fun otherAgentAssistantNotVisibleIsTheControl() {
        verifyCrossAgentCompletion(visible = false)
    }

    private fun verifyCrossAgentCompletion(visible: Boolean) {
        Fixture().use { fixture ->
            val first = fixture.openWorkbench("dsh")
            val release = fixture.startControlledReply(first, "dsh")
            fixture.openWorkbench("codex", previous = first)
            val token = "codex-assistant"
            val assistant = requireNotNull(fixture.runtime.reattachAssistantSession(token))
            assistant.openThread("native-session")
            awaitState(assistant.state) { it.historyHydrated && it.activeThreadId == "native-session" }
            fixture.runtime.setAssistantPresence("assistant-client", token, visible)

            release.countDown()

            awaitState(fixture.runtime.nativeConversationNotifications.sessions) { sessions ->
                sessions.any { it.target.agentId == "dsh" && it.status == NativeConversationStatus.COMPLETED }
            }
            synchronized(fixture.runtime.nativeConversationNotifications) {
                val completed = fixture.runtime.nativeConversationNotifications.sessions.value.single { it.target.agentId == "dsh" }
                assertTrue(replyCompletionPreferences(fixture.context, completed.target.profileId, completed.target.accountId)
                    .all.keys.any { it.startsWith("done.") })
                val notifications = fixture.replyNotifications()
                assertEquals("另一 Agent 的助手可见性不能丢掉完成通知", 1, notifications.size)
                val notification = notifications.single()
                assertEquals("正在查看另一 Agent 的同名会话不应抑制本轮通知", completed.target,
                    ReplyNotificationTarget.from(shadowOf(notification.contentIntent).savedIntent))
            }
        }
    }

    @Test fun otherAgentRunningReplyMustNotBlockCurrentAgentArchive() {
        verifyCrossAgentManagement(delete = false)
    }

    @Test fun otherAgentRunningReplyMustNotBlockCurrentAgentDelete() {
        verifyCrossAgentManagement(delete = true)
    }

    @Test fun archiveWithoutOtherAgentWorkUsesTheSameJvmTransportBoundary() {
        Fixture().use { fixture ->
            val first = fixture.openWorkbench("dsh")
            val controller = fixture.openWorkbench("codex", previous = first)
            controller.archiveThread("native-session")
            awaitState(controller.state) { !it.managingThreads && it.notice != null }
            assertEquals("本机 JVM 的 PATCH 限制与另一 Agent 是否运行无关",
                JVM_PATCH_NOTICE, controller.state.value.notice)
        }
    }

    private fun verifyCrossAgentManagement(delete: Boolean) {
        Fixture().use { fixture ->
            val first = fixture.openWorkbench("dsh")
            fixture.startControlledReply(first, "dsh")
            val second = fixture.openWorkbench("codex", previous = first)
            assertFalse("当前 Agent 没有发送任务", second.hasActiveWork.value)

            if (delete) second.deleteThread("native-session") else second.archiveThread("native-session")

            assertFalse("另一 Agent 的同名运行会话不能阻止当前管理操作",
                second.state.value.notice?.startsWith("CONVERSATION_BUSY") == true)
            awaitState(second.state) { state ->
                !state.managingThreads && (state.notice != null ||
                    delete && state.threads is Loadable.Empty || (state.threads as? Loadable.Ready)?.value?.let { threads ->
                        if (delete) threads.none { it.id.value == "native-session" }
                        else threads.any { it.id.value == "native-session" && it.archived }
                    } == true)
            }
            if (delete) {
                assertNull(second.state.value.notice)
                assertTrue("删除必须实际发给当前 Agent", fixture.gateway.requests.any {
                    it.target == "${AgentSessionClient.ROOT}/codex/sessions/native-session" && it.method == "DELETE"
                })
            } else {
                // 归档已越过忙碌检查；宿主 JVM 在发出 PATCH 前拒绝该方法，空闲对照相同。
                assertEquals(JVM_PATCH_NOTICE, second.state.value.notice)
            }
            assertTrue("另一 Agent 的任务仍在运行", fixture.runtime.nativeConversationNotifications.sessions.value
                .any { it.target.agentId == "dsh" && it.isRunning })
        }
    }

    @Test fun sameAgentRunningReplyStillBlocksArchiveAndDelete() {
        Fixture().use { fixture ->
            val controller = fixture.openWorkbench("dsh")
            fixture.startControlledReply(controller, "dsh")
            controller.archiveThread("native-session")
            assertTrue(controller.state.value.notice?.startsWith("CONVERSATION_BUSY") == true)
            controller.deleteThread("native-session")
            assertTrue(controller.state.value.notice?.startsWith("CONVERSATION_BUSY") == true)
            assertTrue(fixture.gateway.requests.none { it.method in setOf("PATCH", "DELETE") })
        }
    }

    @Test fun returningToTheSameAgentStillProtectsItsRunningConversation() {
        Fixture().use { fixture ->
            val first = fixture.openWorkbench("dsh")
            fixture.startControlledReply(first, "dsh")
            val other = fixture.openWorkbench("codex", previous = first)
            val restored = fixture.openWorkbench("dsh", previous = other)
            assertTrue(restored !== first)
            fixture.gateway.respond("${AgentSessionClient.ROOT}/dsh/sessions/idle-session/history", """{"messages":[]}""")
            restored.openThread("idle-session")
            awaitState(restored.state) { it.activeThreadId == "idle-session" && it.historyHydrated && it.generation == GenerationState.IDLE }
            assertFalse("原会话不属于新控制器自身的在途集合", "native-session" in restored.ownedBusyThreadIds)
            restored.archiveThread("native-session")
            assertTrue(restored.state.value.notice?.startsWith("CONVERSATION_BUSY") == true)
            restored.deleteThread("native-session")
            assertTrue(restored.state.value.notice?.startsWith("CONVERSATION_BUSY") == true)
            assertTrue(fixture.gateway.requests.none { it.method in setOf("PATCH", "DELETE") })
        }
    }

    @Test fun assistantPresenceRetiresOnlyTheMatchingNativeNotification() {
        Fixture().use { fixture ->
            val first = fixture.openWorkbench("dsh")
            fixture.startControlledReply(first, "dsh")
            fixture.openWorkbench("codex", previous = first)
            val codex = requireNotNull(fixture.runtime.reattachAssistantSession("codex-visible"))
            codex.openThread("native-session")
            awaitState(codex.state) { it.historyHydrated && it.activeThreadId == "native-session" }
            fixture.runtime.setAssistantPresence("assistant-client", "codex-visible", true)
            assertEquals("另一 Agent 的同名通知不能被撤回", 1, fixture.replyNotifications().size)

            fixture.openWorkbench("dsh", previous = requireNotNull(fixture.runtime.controller.value))
            val dsh = requireNotNull(fixture.runtime.reattachAssistantSession("dsh-visible"))
            dsh.openThread("native-session")
            awaitState(dsh.state) { it.historyHydrated && it.activeThreadId == "native-session" }
            fixture.runtime.setAssistantPresence("assistant-client", "dsh-visible", true)

            assertTrue("开始查看同一 Agent 的同一会话时，原有通知应立即撤回", fixture.replyNotifications().isEmpty())
        }
    }

    @Test fun assistantVisibilityRequiresMatchingProfileAccountAndAgent() {
        Fixture().use { fixture ->
            fixture.openWorkbench("dsh")
            val assistant = requireNotNull(fixture.runtime.reattachAssistantSession("identity-visible"))
            assistant.openThread("native-session")
            awaitState(assistant.state) { it.historyHydrated && it.activeThreadId == "native-session" }
            fixture.runtime.setAssistantPresence("assistant-client", "identity-visible", true)
            val target = ReplyNotificationTarget(fixture.runtime.savedProfiles.value.single().localProfileId,
                "account-native", "native-session", "dsh")
            assertTrue(fixture.runtime.isAssistantConversationVisible(target))
            assertFalse(fixture.runtime.isAssistantConversationVisible(target.copy(profileId = "other-profile")))
            assertFalse(fixture.runtime.isAssistantConversationVisible(target.copy(accountId = "other-account")))
            assertFalse(fixture.runtime.isAssistantConversationVisible(target.copy(agentId = "codex")))
            assertFalse(fixture.runtime.isAssistantConversationVisible(target.copy(agentId = null)))
            assertFalse(fixture.runtime.isAssistantConversationVisible(target.copy(conversationId = "other-session")))
        }
    }

    @Test fun mainWorkbenchCompletionPublishesTheExistingReplyNotification() {
        Fixture().use { fixture ->
            fixture.selectSession("dsh")
            val controller = awaitState(fixture.runtime.controller) { it?.state?.value?.isOnline == true }!!
            awaitState(controller.state) { state ->
                val threads = state.threads
                threads is Loadable.Ready && threads.value.any { it.id.value == "native-session" }
            }
            controller.openThread("native-session")
            awaitState(controller.state) { it.activeThreadId == "native-session" && it.historyHydrated && it.timeline !is Loadable.Loading }
            fixture.completeWorkbenchSend("dsh", "这是一条已完成的回复")
            fixture.runtime.onAppBackgrounded()

            controller.editDraft("验证主工作台完成通知")
            controller.sendDraft()

            awaitState(controller.state) { state ->
                state.generation == GenerationState.COMPLETED &&
                    (state.timeline as? Loadable.Ready)?.value?.any {
                        it.text == "这是一条已完成的回复" && !it.isStreaming
                    } == true
            }
            val completed = fixture.runtime.nativeConversationNotifications.sessions.value
                .singleOrNull { it.target.agentId == "dsh" && it.target.conversationId == "native-session" }
            assertNotNull("主工作台收到明确完成事件后必须传递给现有通知模块", completed)
            assertEquals(NativeConversationStatus.COMPLETED, completed!!.status)
            assertEquals("这是一条已完成的回复", completed.latestReply)
            val notification = fixture.context.getSystemService(NotificationManager::class.java).activeNotifications
                .single { it.notification.channelId == REPLY_CHANNEL_ID }.notification
            assertEquals(Notification.CATEGORY_MESSAGE, notification.category)
            assertFalse(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertNotNull(notification.bubbleMetadata)
            val target = ReplyNotificationTarget.from(shadowOf(notification.contentIntent).savedIntent)
            assertEquals(completed.target, target)
        }
    }

    @Test fun assistantReplyCompletingAfterItsSurfaceClosesStillNotifies() {
        verifyAssistantCompletion(visibleAtCompletion = false)
    }

    @Test fun assistantReplyAlreadyVisibleKeepsTheExistingNotificationSuppression() {
        verifyAssistantCompletion(visibleAtCompletion = true)
    }

    private fun verifyAssistantCompletion(visibleAtCompletion: Boolean) {
        Fixture().use { fixture ->
            fixture.selectSession("dsh")
            val token = "assistant-notification-test"
            val controller = requireNotNull(fixture.runtime.reattachAssistantSession(token))
            controller.openThread("native-session")
            awaitState(controller.state) { it.activeThreadId == "native-session" && it.historyHydrated && it.timeline !is Loadable.Loading }
            fixture.runtime.setAssistantPresence("assistant-client", token, true)
            val release = CountDownLatch(1)
            fixture.completeWorkbenchSend("dsh", "助手会话已完成", release)
            controller.editDraft("验证助手完成通知")
            controller.sendDraft()
            awaitState(controller.hasActiveWork) { it }
            if (!visibleAtCompletion) {
                fixture.runtime.setAssistantPresence("assistant-client", token, false)
                fixture.runtime.releaseAssistantSession(token)
                fixture.runtime.onAppBackgrounded()
            }
            release.countDown()
            awaitState(fixture.runtime.nativeConversationNotifications.sessions) { sessions ->
                sessions.any { it.target.agentId == "dsh" && it.status == NativeConversationStatus.COMPLETED }
            }
            val notifications = fixture.context.getSystemService(NotificationManager::class.java).activeNotifications
                .filter { it.notification.channelId == REPLY_CHANNEL_ID }
            if (visibleAtCompletion) {
                assertTrue("助手界面正在显示回复时保持原有抑制策略", notifications.isEmpty())
            } else {
                assertEquals("助手界面离开后仍由原通知链路发布完成提醒", 1, notifications.size)
                val target = ReplyNotificationTarget.from(shadowOf(notifications.single().notification.contentIntent).savedIntent)
                assertEquals("dsh", target?.agentId)
                assertEquals("native-session", target?.conversationId)
            }
        }
    }

    @Test fun synchronousNativeSendKeepsItsLeaseWhenBackgroundingPrecedesTheObserver() {
        Fixture().use { fixture ->
            // Both native Core backends must work without legacy conversation events.
            for (agentId in listOf("dsh", "codex")) {
                fixture.runtime.onAppForegrounded()
                val native = fixture.selectSession(agentId)
                val release = fixture.holdSend(agentId)
                val starts = fixture.context.starts.value.size
                val stops = fixture.context.stops.value.size
                fixture.dispatcher.paused = true
                native.editDraft("Run native $agentId turn")
                native.send()
                assertTrue(native.state.value.sending)
                assertEquals(starts, fixture.context.starts.value.size)

                fixture.runtime.onAppBackgrounded()

                assertEquals(starts + 1, fixture.context.starts.value.size)
                assertTrue(fixture.recovery.hasPendingWork)
                fixture.dispatcher.resume()
                assertTrue(native.state.value.sending)
                assertTrue(fixture.runtime.nativeConversationNotifications.sessions.value.isEmpty())
                assertFalse(requireNotNull(fixture.runtime.controller.value).hasActiveWork.value)

                release.countDown()
                awaitState(native.state) { it.streamCompleted && !it.busy }
                awaitState(fixture.context.stops) { it.size == stops + 1 }
                assertFalse(fixture.recovery.hasPendingWork)
                assertFalse(fixture.recovery.hasRecoveryLease)
                assertEquals(AgentSessionForegroundService::class.java.name,
                    fixture.context.starts.value.last().component!!.className)
            }
        }
    }

    @Test fun nativeSendingObserverStartsAndReleasesTheLeaseWithoutALifecycleCallback() {
        Fixture().use { fixture ->
            val native = fixture.selectSession("dsh")
            val release = fixture.holdSend("dsh")
            native.editDraft("Native work while visible")
            native.send()

            awaitState(fixture.context.starts) { it.size == 1 }
            assertTrue(native.state.value.sending)
            assertTrue(fixture.recovery.hasPendingWork)
            fixture.runtime.onAppBackgrounded()
            assertEquals(1, fixture.context.starts.value.size)

            release.countDown()
            awaitState(fixture.context.stops) { it.size == 1 }
            assertFalse(native.state.value.sending)
            assertFalse(fixture.recovery.hasPendingWork)
        }
    }

    @Test fun accountTeardownReleasesTheNativeLeaseAndRetiresItsObserver() {
        Fixture().use { fixture ->
            val native = fixture.selectSession("codex")
            val release = fixture.holdSend("codex")
            native.editDraft("In flight at account teardown")
            native.send()
            awaitState(fixture.context.starts) { it.size == 1 }
            assertTrue(native.state.value.sending)
            fixture.runtime.onAppBackgrounded()

            fixture.runtime.chooseAnotherAccount()

            awaitState(fixture.runtime.phase) { it is ConnectionPhase.Disconnected }
            assertNull(fixture.runtime.nativeAgents.value)
            assertFalse(native.state.value.sending)
            assertFalse(fixture.recovery.hasPendingWork)
            assertFalse(fixture.recovery.hasRecoveryLease)
            assertEquals(1, fixture.context.stops.value.size)
            release.countDown()
            fixture.runtime.onAppForegrounded()
            assertEquals(1, fixture.context.starts.value.size)
            assertFalse(fixture.recovery.hasPendingWork)
        }
    }

    @Test fun accountExitAcceptsAWorkbenchAlreadyRetiredByAgentReplacement() {
        Fixture().use { fixture ->
            fixture.selectSession("codex")
            // The Agent collector closes the old workbench before its IO
            // replacement is installed. Account exit can observe this owner.
            val retired = MutableStateFlow(false)
            val retiring = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
                requireNotNull(fixture.runtime.controller.value).closeAndAwait()
                retired.value = true
            }
            try { awaitState(retired) { it } } finally { retiring.cancel() }
            fixture.runtime.chooseAnotherAccount()
            awaitState(fixture.runtime.phase) { it is ConnectionPhase.Disconnected }
            assertNull(fixture.runtime.nativeAgents.value)
            assertFalse(fixture.recovery.hasPendingWork)
        }
    }

    @Test fun nativeSendingStillRequiresTheUserNotificationSettingAndPermission() {
        Fixture().use { fixture ->
            val native = fixture.selectSession("dsh")
            val release = fixture.holdSend("dsh")
            fixture.runtime.replyNotifications.setEnabled(false)
            native.editDraft("Notification setting disabled")
            native.send()
            assertTrue(native.state.value.sending)
            fixture.runtime.onAppBackgrounded()
            assertTrue(fixture.context.starts.value.isEmpty())
            assertFalse(fixture.recovery.hasPendingWork)

            fixture.runtime.onAppForegrounded()
            shadowOf(fixture.context.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            fixture.runtime.replyNotifications.refreshPermission()
            fixture.runtime.replyNotifications.setEnabled(true)
            assertFalse(fixture.runtime.replyNotifications.allowed.value)
            fixture.runtime.onAppBackgrounded()
            assertTrue(fixture.context.starts.value.isEmpty())
            assertFalse(fixture.recovery.hasPendingWork)
            release.countDown()
            awaitState(native.state) { it.streamCompleted && !it.busy }
        }
    }

    private class Fixture : AutoCloseable {
        val context = RecordingContext()
        val dispatcher = PausableDispatcher()
        private val owner = SupervisorJob()
        private val scope = CoroutineScope(owner + dispatcher)
        val gateway = LoopbackGatewayStub()
        private val releases = mutableListOf<CountDownLatch>()
        val recovery = AgentSessionRecoveryState(context)
        val runtime: GatewayRuntime

        init {
            shadowOf(context.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            context.getSharedPreferences("agent-session-recovery", Context.MODE_PRIVATE).edit().clear().commit()
            gateway.respond("/open-android-intelligence/v2/negotiate", NEGOTIATION,
                headers = mapOf(NATIVE_AGENT_SESSIONS_HEADER to "1"))
            gateway.respond("/open-android-intelligence/v2/sessions/password", SESSION)
            gateway.respond("/open-android-intelligence/v2/conversations", """{"data":{"conversations":[]}}""")
            gateway.respond("/open-android-intelligence/v2/events", "", contentType = "text/event-stream")
            gateway.respond(AgentSessionClient.ROOT, AGENTS)
            for (agentId in listOf("dsh", "codex")) {
                gateway.respond("${AgentSessionClient.ROOT}/$agentId/sessions", """{"sessions":[{"id":"native-session"}]}""")
                gateway.respond("${AgentSessionClient.ROOT}/$agentId/sessions/native-session/history", """{"messages":[]}""")
                gateway.respond("${AgentSessionClient.ROOT}/$agentId/sessions/native-session") { request ->
                    val sessions = if (request.method == "DELETE") """{"sessions":[]}"""
                        else """{"sessions":[{"id":"native-session","archived":true}]}"""
                    gateway.respond("${AgentSessionClient.ROOT}/$agentId/sessions", sessions)
                    200 to "{}"
                }
            }
            runtime = GatewayRuntime(context, scope,
                PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore(InMemoryAuditSink())),
                credentialStore = InMemoryCredentialStore(), deviceKeys = InMemoryDeviceKeySource(),
                localDocumentKeyProvider = TestDocumentKeys)
            runtime.replyNotifications.setEnabled(true)
            runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
            awaitState(runtime.phase) { it is ConnectionPhase.Connected }
            runtime.onAppForegrounded()
            val native = requireNotNull(runtime.nativeAgents.value)
            awaitState(native.state) { it.sessions.isNotEmpty() && !it.loadingSessions }
            assertTrue(context.starts.value.isEmpty())
        }

        fun selectSession(agentId: String): NativeAgentsController {
            val native = requireNotNull(runtime.nativeAgents.value)
            native.selectAgent(agentId)
            awaitState(native.state) { it.selectedAgentId == agentId && it.sessions.isNotEmpty() && !it.loadingSessions && !it.busy }
            native.selectSession("native-session")
            awaitState(native.state) { it.selectedSessionId == "native-session" && !it.loadingHistory }
            return native
        }

        fun openWorkbench(agentId: String, previous: WorkbenchController? = null): WorkbenchController {
            val retiring = previous ?: runtime.controller.value.takeIf {
                runtime.nativeAgents.value?.state?.value?.selectedAgentId != agentId
            }
            selectSession(agentId)
            val controller = awaitState(runtime.controller) { it != null && it !== retiring && it.state.value.isOnline }!!
            awaitState(controller.state) { state ->
                (state.threads as? Loadable.Ready)?.value?.any { it.id.value == "native-session" } == true
            }
            controller.openThread("native-session")
            awaitState(controller.state) { it.historyHydrated && it.activeThreadId == "native-session" }
            return controller
        }

        fun replyNotifications(): List<Notification> = context.getSystemService(NotificationManager::class.java)
            .activeNotifications.filter { it.notification.channelId == REPLY_CHANNEL_ID }.map { it.notification }

        fun startControlledReply(controller: WorkbenchController, agentId: String): CountDownLatch {
            val release = gateway.controlledStream("${AgentSessionClient.ROOT}/$agentId/sessions/native-session/messages", { request ->
                val clientId = JsonFields.string(requireNotNull(JsonFields.obj(Json.parse(request.body))), "clientMessageId")
                listOf(
                    """{"type":"generation","generationId":"gen-cross-agent","clientMessageId":"$clientId","userMessageId":"user-cross-agent","state":"running","revision":1}""",
                    """{"type":"delta","generationId":"gen-cross-agent","messageId":"reply-cross-agent","eventId":"cross-agent-delta","contentRevision":1,"text":"正在处理"}""",
                ).joinToString("") { "event: message\ndata: $it\n\n" }
            }, { request ->
                val clientId = JsonFields.string(requireNotNull(JsonFields.obj(Json.parse(request.body))), "clientMessageId")
                gateway.respond("${AgentSessionClient.ROOT}/$agentId/sessions/native-session/history",
                    """{"messages":[{"id":"user-cross-agent","role":"user","text":"测试问题","clientMessageId":"$clientId","generationId":"gen-cross-agent"},{"id":"reply-cross-agent","role":"assistant","text":"最终回复","clientMessageId":"$clientId","generationId":"gen-cross-agent","contentRevision":2,"state":"CONFIRMED"}]}""")
                "event: message\ndata: " +
                    """{"type":"done","generationId":"gen-cross-agent","clientMessageId":"$clientId","userMessageId":"user-cross-agent","messageId":"reply-cross-agent","eventId":"cross-agent-done","contentRevision":2,"revision":2,"text":"最终回复","outcome":"completed"}""" + "\n\n"
            })
            releases += release
            controller.editDraft("验证跨 Agent 会话身份")
            controller.sendDraft()
            awaitState(controller.state) { state ->
                (state.timeline as? Loadable.Ready)?.value?.any { it.text == "正在处理" && it.isStreaming } == true
            }
            awaitState(runtime.nativeConversationNotifications.sessions) { sessions -> sessions.any { it.target.agentId == agentId && it.isRunning } }
            return release
        }

        fun holdSend(agentId: String): CountDownLatch {
            val release = CountDownLatch(1).also { releases += it }
            gateway.respond("${AgentSessionClient.ROOT}/$agentId/sessions/native-session/messages", contentType = "text/event-stream") {
                check(release.await(10, TimeUnit.SECONDS)) { "Native test stream was never released" }
                200 to "event: message\ndata: {\"type\":\"done\"}\n\n"
            }
            return release
        }

        fun completeWorkbenchSend(agentId: String, text: String, release: CountDownLatch? = null) {
            if (release != null) releases += release
            gateway.respond("${AgentSessionClient.ROOT}/$agentId/sessions/native-session/messages", contentType = "text/event-stream") { request ->
                check(release?.await(10, TimeUnit.SECONDS) != false) { "测试回复未在期限内释放" }
                val clientId = JsonFields.string(requireNotNull(JsonFields.obj(Json.parse(request.body))), "clientMessageId")
                gateway.respond("${AgentSessionClient.ROOT}/$agentId/sessions/native-session/history",
                    """{"messages":[{"id":"user-notification","role":"user","text":"测试问题","clientMessageId":"$clientId","generationId":"gen-notification"},{"id":"reply-notification","role":"assistant","text":"$text","clientMessageId":"$clientId","generationId":"gen-notification","contentRevision":2,"state":"CONFIRMED"}]}""")
                val frames = listOf(
                    """{"type":"generation","generationId":"gen-notification","clientMessageId":"$clientId","userMessageId":"user-notification","state":"running","revision":1}""",
                    """{"type":"delta","generationId":"gen-notification","messageId":"reply-notification","eventId":"reply-delta","contentRevision":1,"text":"$text"}""",
                    """{"type":"done","generationId":"gen-notification","clientMessageId":"$clientId","userMessageId":"user-notification","messageId":"reply-notification","eventId":"reply-done","contentRevision":2,"revision":2,"text":"$text","outcome":"completed"}""",
                )
                200 to frames.joinToString("") { "event: message\ndata: $it\n\n" }
            }
        }

        override fun close() {
            releases.forEach { it.countDown() }
            dispatcher.resume()
            scope.cancel()
            gateway.closed()
            // 等待已取消任务退出，避免其同步回调晚于测试环境重置。
            val completed = MutableStateFlow(owner.isCompleted)
            owner.invokeOnCompletion { completed.value = true }
            awaitState(completed) { it }
        }
    }

    private class RecordingContext : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
        val application get() = baseContext as Application
        val starts = MutableStateFlow<List<Intent>>(emptyList())
        val stops = MutableStateFlow<List<Intent>>(emptyList())
        override fun getApplicationContext(): Context = this
        override fun startForegroundService(service: Intent): ComponentName {
            starts.value += Intent(service)
            return requireNotNull(service.component)
        }
        override fun stopService(service: Intent): Boolean {
            stops.value += Intent(service)
            return true
        }
    }

    private class PausableDispatcher : CoroutineDispatcher() {
        @Volatile var paused = false
        private val pending = ConcurrentLinkedQueue<Runnable>()
        override fun isDispatchNeeded(context: CoroutineContext) = paused
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (paused) pending.add(block) else block.run()
        }
        fun resume() {
            paused = false
            while (true) (pending.poll() ?: return).run()
        }
    }

    private companion object {
        const val JVM_PATCH_NOTICE = "CONVERSATION_MANAGEMENT_FAILED:Invalid HTTP method: PATCH"
        /** Wait for real state emissions while driving Android's owner looper; no sleep-based assertions. */
        fun <T> awaitState(state: StateFlow<T>, predicate: (T) -> Boolean): T {
            val result = CompletableFuture<T>()
            val waiter = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
                result.complete(state.first(predicate))
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            try {
                while (true) {
                    shadowOf(Looper.getMainLooper()).idle()
                    try { return result.get(10, TimeUnit.MILLISECONDS) }
                    catch (timeout: TimeoutException) {
                        if (System.nanoTime() >= deadline) throw AssertionError("State did not reach expected value: ${state.value}", timeout)
                    }
                }
            } finally { waiter.cancel() }
        }

        val NEGOTIATION = """{"protocol":"2.1","data":{"protocol":{"major":2,"minor":1},"features":{"auth":["password","refresh"],"messages":"chat-v1","attachments":"staged-sha256-v1","events":"sse-cursor-v1","deviceRequests":"risk-queue-v1","conversationUi":[]},"limits":{"attachmentTtlSeconds":3600,"eventRetentionSeconds":86400,"maxClockSkewSeconds":120},"gatewayIdentity":{"deploymentId":"dep_stub","tlsSpkiSha256":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}}"""
        val SESSION = """{"data":{"accountId":"account-native","deviceId":"device-native","sessionId":"session-native","accessToken":"token","refreshCredential":"refresh"}}"""
        val AGENTS = """{"agents":[{"id":"dsh","name":"DSH","kind":"dsh","online":true,"sessionScope":"deployment","capabilities":{"list":true,"history":true,"send":true,"stream":true,"archive":true,"delete":true}},{"id":"codex","name":"Codex","kind":"codex","online":true,"sessionScope":"deployment","capabilities":{"list":true,"history":true,"send":true,"stream":true,"archive":true,"delete":true}}]}"""
    }
}
