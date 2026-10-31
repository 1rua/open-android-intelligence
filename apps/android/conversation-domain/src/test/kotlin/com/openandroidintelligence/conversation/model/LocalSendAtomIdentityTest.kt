package com.openandroidintelligence.conversation.model

import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * 身份容错：事件链路可能先用客户端占位 id，再用服务端 id 描述同一条消息。
 *
 * 现场崩溃是 `IllegalStateException: MESSAGE_ID_CONFLICT`（`LocalSendAtom.accepted`），
 * 发生在收到最终回复的 `MessageStatus` 时。这里锁定两个不变量：
 * 1. 占位身份可以被服务端真实 id 升级；
 * 2. 已绑定的身份不会被另一个 id 改写，且状态仍然推进（绝不抛异常）。
 */
class LocalSendAtomIdentityTest {

    private fun atom(messageId: String? = "client_1") = LocalSendAtom(
        conversationId = ConversationId("conv_a"),
        clientMessageId = ClientMessageId("client_1"),
        parts = listOf(MessagePart.Text("查看设备状态")),
        timestamp = 1_000L,
        messageId = messageId,
    )

    @Test
    fun provisionalClientIdIsPromotedToTheServerId() {
        val promoted = atom("client_1").identified("server_1")

        assertEquals("server_1", promoted.messageId)
        assertEquals(LocalSubmissionState.ACCEPTED, promoted.submissionState)
        assertEquals(AgentMessageStatus.QUEUED, promoted.status)
    }

    @Test
    fun divergentServerIdKeepsTheBoundIdentityAndStillAppliesStatus() {
        val bound = atom("server_1").copy(status = AgentMessageStatus.QUEUED, statusRevision = 0)

        val updated = bound.identified("server_2").copy(
            status = AgentMessageStatus.COMPLETED, statusRevision = 1,
        )

        assertEquals("server_1", updated.messageId)
        assertEquals(AgentMessageStatus.COMPLETED, updated.status)
        assertEquals(1L, updated.statusRevision)
    }

    @Test
    fun blankIdentityChangesNothing() {
        val bound = atom("server_1")

        assertEquals(bound, bound.identified(" "))
    }

    @Test
    fun completedAtomWithoutServerIdentityStillProducesAnExplicitLocalPlaceholder() {
        val completed = atom(null).copy(status = AgentMessageStatus.COMPLETED)
        val row = mergeLocalSendAtoms(emptyList(), listOf(completed)).single()
        org.junit.Assert.assertTrue(row.localPlaceholder)
        assertEquals("local_client_1", row.id)
    }

    @Test
    fun aRealMessageWithALocalLookingIdKeepsItsAuthorityAndGeneration() {
        val remote = com.openandroidintelligence.conversation.ports.TimelineMessage(
            "local_native_id", "user", listOf(MessagePart.Text("原稿")), 1_000L,
            clientMessageId = ClientMessageId("client_1"), generationId = "generation_1")
        val row = mergeLocalSendAtoms(listOf(remote), listOf(atom("local_native_id"))).single()
        org.junit.Assert.assertFalse(row.localPlaceholder)
        assertEquals("local_native_id", row.id)
        assertEquals("generation_1", row.generationId)
    }

    @Test
    fun strictAcceptedStillRejectsSilentIdentityRewrites() {
        try {
            atom("server_1").accepted("server_2")
            fail("accepted() 必须继续拒绝身份改写，避免创建路径悄悄换 id")
        } catch (_: IllegalStateException) {
            // 期望：创建/受理路径仍保持强不变量。
        }
    }
}
