package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.events.GatewayEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * 同一个会话 ID 在两个原生 Agent 下必须是两条提醒，不能互相命中对方的去重记录。
 *
 * 原生 `/agents` 会话 ID 只在一个 Agent 内唯一；旧链路没有这个维度，所以平台帧
 * 的摘要与路由必须保持原样，只有带 Agent 的帧才加长身份。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeNotificationRoutingTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val clock = Instant.parse("2026-10-17T02:00:00Z").toEpochMilli()
    private var sequence = 0

    @Test fun sameConversationIdUnderTwoAgentsProducesTwoDistinctNotifications() {
        replyCompletionPreferences(context, "profile", "account").edit().clear().commit()
        val posted = mutableListOf<Pair<ReplyNotificationTarget, String>>()
        val observer = ReplyCompletionObserver(
            replyCompletionPreferences(context, "profile", "account"), "profile", "account",
            { target, generation -> posted += target to generation }, { clock },
        )
        observer.accept(frame("conversation.message.completed", agent = "hermes", generation = "gen-a", text = "Hermes 回答"))
        observer.accept(frame("conversation.message.status", agent = "hermes", generation = "gen-a", status = "completed"))
        assertEquals(1, posted.size)
        assertEquals("hermes", posted.single().first.agentId)

        observer.accept(frame("conversation.message.completed", agent = "codex", generation = "gen-a", text = "Codex 回答"))
        observer.accept(frame("conversation.message.status", agent = "codex", generation = "gen-a", status = "completed"))
        assertEquals("另一个 Agent 的同名会话不能被前一家的完成围栏吃掉", 2, posted.size)
        assertEquals(listOf("hermes", "codex"), posted.map { it.first.agentId })
    }

    @Test fun replayOfOneAgentDoesNotSilenceTheOthersNotification() {
        replyCompletionPreferences(context, "profile", "account").edit().clear().commit()
        val posted = mutableListOf<ReplyNotificationTarget>()
        val observer = ReplyCompletionObserver(
            replyCompletionPreferences(context, "profile", "account"), "profile", "account",
            { target, _ -> posted += target }, { clock },
        )
        repeat(2) {
            observer.accept(frame("conversation.message.completed", agent = "hermes", generation = "gen-a", text = "Hermes 回答"))
            observer.accept(frame("conversation.message.status", agent = "hermes", generation = "gen-a", status = "completed"))
            observer.accept(frame("conversation.message.completed", agent = "codex", generation = "gen-a", text = "Codex 回答"))
            observer.accept(frame("conversation.message.status", agent = "codex", generation = "gen-a", status = "completed"))
        }
        assertEquals("同一事件重放不能复活提醒，也不能顶替另一家", 2, posted.size)
    }

    @Test fun platformFramesKeepTheirOriginalThreePartIdentityAndRoute() {
        val platform = ReplyNotificationTarget("profile", "account", "conv-a")
        val native = ReplyNotificationTarget("profile", "account", "conv-a", "hermes")
        assertNotEquals(platform.identityKey(), native.identityKey())
        assertEquals("conv-a", platform.identityKey())
        // 旧链路的路由形状（三段）与解析必须完全不变。
        assertEquals(platform, ReplyNotificationTarget.from(platform.intent(context)))
        val restored = requireNotNull(ReplyNotificationTarget.from(native.intent(context)))
        assertEquals(native, restored)
        assertEquals("hermes", BubbleConversationActivity.targetFromIntent(native.bubbleIntent(context))?.agentId)
        assertNull(BubbleConversationActivity.targetFromIntent(platform.bubbleIntent(context))?.agentId)
    }

    private fun frame(
        type: String,
        agent: String,
        generation: String,
        status: String? = null,
        text: String = "",
    ): GatewayEvent {
        val payload = if (type == "conversation.message.status") {
            """{"agentId":"$agent","conversationId":"conv-a","messageId":"user-1","clientMessageId":"client-1","generationId":"$generation","status":"$status","revision":2,"errorCode":${if (status == "failed") "\"AGENT_UNAVAILABLE\"" else "null"}}"""
        } else {
            """{"agentId":"$agent","conversationId":"conv-a","messageId":"reply-1","sender":"assistant","parts":[{"type":"text","text":"$text"}],"text":"$text","timestamp":1,"revision":1,"generationId":"$generation"}"""
        }
        return GatewayEvent("frame-${++sequence}", type,
            """{"accountId":"account","occurredAt":"${Instant.ofEpochMilli(clock)}","payload":$payload}""")
    }
}
