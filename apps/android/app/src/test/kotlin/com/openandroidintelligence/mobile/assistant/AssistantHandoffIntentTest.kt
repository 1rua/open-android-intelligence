package com.openandroidintelligence.mobile.assistant

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.mobile.ReplyNotificationTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 交接意图与回复通知意图的**互斥**判定。
 *
 * 两者的形状完全一样：同一条 `OPEN_AGENT_REPLY` action、同一条
 * `oai-reply://conversation/{profile}/{account}/{conversation}[/{agent}]` 深链。
 * 如果两边都解析成功，回复通知那条路径会先用 `openThread` 把用户带到会话，
 * 并**忽略 agentId** —— 在切换过 Agent 的设备上会打开同名的旧会话，或直接失败。
 * 因此 MainActivity 必须：带交接 token 时只走交接，否则才当回复通知处理。
 *
 * 这里在纯解析层锁定这条规则；MainActivity 的分支本身在 Robolectric 下无法脱离
 * 完整 Compose 宿主验证，所以断言的是它依赖的判定条件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantHandoffIntentTest {

    private fun handoffIntent(token: String = "session-token"): Intent {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        return Intent()
            .setClassName(context.packageName, "com.openandroidintelligence.mobile.MainActivity")
            .setAction("com.openandroidintelligence.mobile.OPEN_AGENT_REPLY")
            .putExtra(HANDOFF_EXTRA_SESSION_TOKEN, token)
            .setData(android.net.Uri.parse("oai-reply://conversation/p/a/thread-1/agent-1"))
    }

    @Test fun aHandoffIntentCarriesABlankCheckedSessionToken() {
        assertEquals("session-token", handoffIntent().handoffSessionToken())
    }

    /** 空白 token 不算交接：不能让一条伪造的空 extra 抢占回复通知路径。 */
    @Test fun aBlankTokenIsNotATransfer() {
        assertNull(handoffIntent(token = "  ").handoffSessionToken())
    }

    /** 没有交接 token 时，回复通知照旧可解析（普通通知点击不能被这条规则挡住）。 */
    @Test fun aPlainReplyIntentStaysAReplyTarget() {
        val intent = handoffIntent().apply { removeExtra(HANDOFF_EXTRA_SESSION_TOKEN) }

        assertNull(intent.handoffSessionToken())
        val target = ReplyNotificationTarget.from(intent)
        assertNotNull("普通回复通知必须仍能被解析", target)
        assertEquals("thread-1", target?.conversationId)
    }

    /**
     * 交接意图同时**会被**回复通知解析出来——这正是必须互斥的原因。
     *
     * 这条断言把风险写进测试：若将来有人把 MainActivity 改回「两个都设置」，
     * 这里会提醒他 `ReplyNotificationTarget.from` 会返回非 null、两条路径会打架。
     */
    @Test fun aHandoffIntentIsAlsoShapedLikeAReplyTarget() {
        assertNotNull(ReplyNotificationTarget.from(handoffIntent()))
    }
}
