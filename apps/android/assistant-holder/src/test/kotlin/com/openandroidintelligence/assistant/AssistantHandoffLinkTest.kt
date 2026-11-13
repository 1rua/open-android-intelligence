package com.openandroidintelligence.assistant

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 交接深链的构造与校验。
 *
 * 深链沿用主 App 既有的 `oai-reply://conversation/{profile}/{account}/{conversation}[/{agent}]`
 * 形状：本模块不新增 action、也不新增协议字段。外部应用可以伪造任意 Intent，因此这里
 * 只负责「形状合法」；主进程再按「刚刚接受过的那一次 token」核对。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantHandoffLinkTest {

    private fun link(
        conversationId: String? = "thread-9",
        agentId: String? = "agent-1",
    ): AssistantHandoffLink? = AssistantHandoffLink.from(
        profileId = "profile-1",
        accountId = "account-1",
        conversationId = conversationId,
        agentId = agentId,
        sessionToken = "session-1",
    )

    @Test
    fun theIntentUsesTheExistingReplyDeepLinkShape() {
        val intent = requireNotNull(link()).toIntent(RuntimeEnvironment.getApplication())

        assertEquals(ASSISTANT_REPLY_ACTION, intent.action)
        assertEquals("oai-reply", intent.data?.scheme)
        assertEquals("conversation", intent.data?.host)
        assertEquals(listOf("profile-1", "account-1", "thread-9", "agent-1"), intent.data?.pathSegments)
        assertEquals(
            "session-1",
            intent.getStringExtra(AssistantHandoffLink.EXTRA_SESSION_TOKEN),
        )
        // 助理会话没有 Activity 栈：必须以 NEW_TASK 启动。
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(
            "com.openandroidintelligence.mobile.MainActivity",
            intent.component?.className,
        )
    }

    @Test
    fun aLinkWithoutAConversationKeepsOnlyTheSessionIdentity() {
        val intent = requireNotNull(link(conversationId = null, agentId = null))
            .toIntent(RuntimeEnvironment.getApplication())

        // 没有会话时不构造深链：主 App 会把它当成一条真实会话并打开别人的线程。
        assertNull(intent.data)
        assertEquals(ASSISTANT_REPLY_ACTION, intent.action)
        assertEquals("session-1", intent.getStringExtra(AssistantHandoffLink.EXTRA_SESSION_TOKEN))
    }

    @Test
    fun whitespaceOrPathSeparatorsInIdentityAreRejected() {
        assertNull(link(conversationId = " "))
        assertNull(link(conversationId = "thread/9"))
        assertNull(link(agentId = "a b"))
        assertNull(
            AssistantHandoffLink.from(
                profileId = "",
                accountId = "account-1",
                conversationId = "thread-9",
                agentId = null,
                sessionToken = "session-1",
            ),
        )
    }

    @Test
    fun aValidLinkCarriesTheAgentOnlyWhenPresent() {
        val withoutAgent = requireNotNull(link(agentId = null))
        val intent = withoutAgent.toIntent(RuntimeEnvironment.getApplication())

        assertEquals(listOf("profile-1", "account-1", "thread-9"), intent.data?.pathSegments)
        assertNotNull(intent.getStringExtra(AssistantHandoffLink.EXTRA_SESSION_TOKEN))
    }
}
