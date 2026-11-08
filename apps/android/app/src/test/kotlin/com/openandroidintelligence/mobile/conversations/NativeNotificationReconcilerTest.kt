package com.openandroidintelligence.mobile.conversations

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.agents.NativeAgentMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import com.openandroidintelligence.mobile.NativeConversationNotifications
import com.openandroidintelligence.mobile.NativeConversationStatus
import com.openandroidintelligence.mobile.replyCompletionPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * 原生会话丢掉的终态帧只能靠 `/agents` 自己的 generation 与 history 修回来。
 *
 * 这里用真实提醒层 + 注入的两个读取入口，判定本身与 HTTP 无关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeNotificationReconcilerTest {
    private lateinit var context: Application
    private lateinit var scope: CoroutineScope
    private lateinit var notifications: NativeConversationNotifications
    private val alerts = mutableListOf<Int>()
    private var clock = Instant.parse("2026-10-17T02:00:00Z").toEpochMilli()
    private var sequence = 0

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        replyCompletionPreferences(context, "profile", "account").edit().clear().commit()
        context.getSharedPreferences("agent-reply-alert-settings", 0).edit().clear().commit()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        notifications = NativeConversationNotifications(context, scope, { _, alert -> if (alert) alerts += ++sequence }, now = { clock })
        notifications.bind("profile", "account")
    }

    @After fun close() {
        scope.cancel()
    }

    @Test fun idleGenerationEndsTheTaskWithoutClaimingSuccessOrAlerting() = runBlocking {
        notifications.accept("profile", "account", status("delivered"))
        notifications.accept("profile", "account", delta("Working"))
        assertEquals(NativeConversationStatus.RUNNING, notifications.sessions.value.single().status)

        // 宿主说已经有终态，但这一帧在网络里丢了：只剩“现在空闲”这一条事实。
        val reconciler = reconciler(current = { null }, history = { listOf(reply("Final answer")) })
        reconciler.reconcileActive(force = true)

        val session = notifications.sessions.value.single()
        assertEquals("空闲只能证明任务结束，不能推断成功", NativeConversationStatus.ENDED, session.status)
        assertTrue("没有成功终态、没有正文完成帧，就不发完成提醒", alerts.isEmpty())
    }

    @Test fun stillRunningGenerationIsRepairedInsteadOfEnded() = runBlocking {
        notifications.accept("profile", "account", status("delivered"))
        notifications.accept("profile", "account", delta("Working"))
        val generation = notifications.sessions.value.single().generationId

        val reconciler = reconciler(
            current = { NativeCurrentTurn("generation", "running") },
            history = { listOf(user("do it"), reply("Still working")) },
        )
        reconciler.reconcileActive(force = true)

        val session = notifications.sessions.value.single()
        assertEquals(NativeConversationStatus.RUNNING, session.status)
        assertNull(session.lastOutcome)
        assertEquals(generation, session.generationId.takeIf { session.status == NativeConversationStatus.RUNNING })
    }

    @Test fun aGenerationThatChangesDuringTheReadCannotEndTheTask() = runBlocking {
        notifications.accept("profile", "account", status("delivered"))
        notifications.accept("profile", "account", delta("Working"))
        var reads = 0
        val reconciler = reconciler(
            current = {
                reads++
                if (reads == 1) NativeCurrentTurn("generation", "running") else NativeCurrentTurn("next-generation", "queued")
            },
            history = { emptyList() },
        )
        reconciler.reconcileActive(force = true)

        assertEquals("中途换轮说明这份快照不能作为终态证据", NativeConversationStatus.RUNNING,
            notifications.sessions.value.single().status)
        assertTrue(alerts.isEmpty())
    }

    private fun reconciler(
        current: suspend () -> NativeCurrentTurn?,
        history: suspend () -> List<NativeAgentMessage>,
    ) = NativeNotificationReconciler(notifications, { _, _ -> current() }, { _, _ -> history() })

    private fun status(status: String) = frame("conversation.message.status",
        """{"agentId":"hermes","conversationId":"conversation","messageId":"user-1","clientMessageId":"client-1","generationId":"generation","status":"$status","revision":1,"errorCode":null}""")

    private fun delta(text: String) = frame("conversation.message.delta",
        """{"agentId":"hermes","conversationId":"conversation","messageId":"reply-1","sender":"assistant","parts":[{"type":"text","text":"$text"}],"text":"$text","timestamp":$clock,"revision":1,"generationId":"generation"}""")

    private fun frame(type: String, payload: String) = com.openandroidintelligence.gateway.events.GatewayEvent(
        "frame-${++sequence}", type,
        """{"accountId":"account","occurredAt":"${Instant.ofEpochMilli(clock++)}","payload":$payload}""")

    private fun reply(text: String) = NativeAgentMessage("reply-1", "assistant", text,
        Instant.ofEpochMilli(clock).toString(), generationId = "generation", contentRevision = 1, state = "CONFIRMED")

    private fun user(text: String) = NativeAgentMessage("user-1", "user", text,
        Instant.ofEpochMilli(clock).toString(), clientMessageId = "client-1", generationId = "generation")
}
