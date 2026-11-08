package com.openandroidintelligence.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协商能力位在界面上的呈现规则。
 *
 * 关键要求是「未声明」与「声明了但没被同意」不能混为一谈：前者是双方都没提，
 * 后者是这台 Gateway 明确不支持，用户看到的结论完全不同。
 */
class ConversationUiCapabilitiesTest {

    @Test
    fun theClosedSetIsExactlyTheContractEnum() {
        assertEquals(
            listOf(
                "agent-command-catalog-v1",
                "agent-command-new-v1",
                "agent-approval-cards-v1",
                "message-batches-v1",
                "newline-v1",
                "generation-cancel-v1",
                "conversation-mirror-v1",
                "attachment-status-v1",
                "conversation-management-v1",
            ),
            ConversationUiFeature.CLOSED_SET,
        )
    }

    @Test
    fun agreementDeclarationAndSilenceStayDistinguishable() {
        val capabilities = negotiatedConversationUi(
            agreed = setOf("agent-command-catalog-v1", "generation-cancel-v1"),
            requested = setOf("agent-command-catalog-v1", "message-batches-v1", "newline-v1"),
        )

        assertEquals(ConversationUiFeature.CLOSED_SET.size, capabilities.size)
        assertEquals(true, capabilities["agent-command-catalog-v1"])
        assertEquals(true, capabilities["generation-cancel-v1"])
        assertEquals("声明了但网关没同意，必须如实标成不支持", false, capabilities["message-batches-v1"])
        assertEquals(false, capabilities["newline-v1"])
        assertNull("双方都没声明过，不能写成不支持", capabilities["attachment-status-v1"])
        assertNull(capabilities["conversation-mirror-v1"])
    }

    @Test
    fun statusLabelsSayWhatActuallyHappened() {
        assertEquals("已启用", conversationUiStatusLabel(true))
        assertEquals("本网关不支持", conversationUiStatusLabel(false))
        assertEquals("未声明", conversationUiStatusLabel(null))
    }

    @Test
    fun notificationPushIsUnavailableWhileNoPushChannelIsWired() {
        val presentation = notificationPushPresentation(
            pushChannelWired = false,
            mirrorAgreed = true,
        )

        assertFalse(presentation.enabled)
        assertTrue("必须明说不可用：${presentation.supporting}", presentation.supporting.contains("不可用"))
        assertTrue("必须点明缺的是推送通道：${presentation.supporting}", presentation.supporting.contains("推送"))
    }

    @Test
    fun notificationPushIsUnavailableWhenTheGatewayDidNotAgreeToMirrorConversations() {
        val undeclared = notificationPushPresentation(pushChannelWired = true, mirrorAgreed = null)
        assertFalse(undeclared.enabled)
        assertTrue(undeclared.supporting.contains("未声明"))

        val refused = notificationPushPresentation(pushChannelWired = true, mirrorAgreed = false)
        assertFalse(refused.enabled)
        assertTrue(refused.supporting.contains("网关不支持"))
    }
}
