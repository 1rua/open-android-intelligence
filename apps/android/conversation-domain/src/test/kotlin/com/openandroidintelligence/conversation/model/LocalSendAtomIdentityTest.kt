package com.openandroidintelligence.conversation.model

import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import com.openandroidintelligence.conversation.ports.MessageDeliveryMode
import com.openandroidintelligence.conversation.ports.TimelineMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun atom(
        messageId: String? = "client_1",
        delivery: MessageDeliveryMode = MessageDeliveryMode.NORMAL,
    ) = LocalSendAtom(
        conversationId = ConversationId("conv_a"),
        clientMessageId = ClientMessageId("client_1"),
        parts = listOf(MessagePart.Text("查看设备状态")),
        timestamp = 1_000L,
        messageId = messageId,
        delivery = delivery,
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
        val remote = TimelineMessage(
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

    /**
     * 边界 A 第一切片（回归锁）：
     * 1. 引导消息本地记录（无论是仅有 clientMessageId、旧合成 steer_${clientId}、还是受理乱序到达）
     *    与远端迟到的权威 user 消息（sender="user", 真实 messageId, 相同 clientMessageId）
     *    必须唯一合并为单条时间线行，且身份归为用户 ("user")。
     * 2. 不允许将身份改为 "system"，不允许拼接 "↳ 已引导：" 改写原文，且真实权威 ID 必须吸收旧占位 ID。
     */
    @Test
    fun steeringAtomMergesWithRemoteAuthoritativeUserMessageAndLegacySyntheticSteerId() {
        val remoteAuthoritativeUser = TimelineMessage(
            id = "msg_native_input_authoritative_100",
            sender = "user",
            parts = listOf(MessagePart.Text("请改用新模式执行")),
            timestamp = 1_500L,
            state = "CONFIRMED",
            conversationId = ConversationId("conv_a"),
            clientMessageId = ClientMessageId("client_steer_1"),
            generationId = "gen_live",
            localPlaceholder = false,
        )

        // 场景 1: 本地 STEER 发送记录（即使历史记录存过旧版合成 ID "steer_client_steer_1" 或仅有 clientId 占位）
        val steerAtomWithLegacySyntheticId = LocalSendAtom(
            conversationId = ConversationId("conv_a"),
            clientMessageId = ClientMessageId("client_steer_1"),
            parts = listOf(MessagePart.Text("请改用新模式执行")),
            timestamp = 1_400L,
            messageId = "steer_client_steer_1",
            delivery = MessageDeliveryMode.STEER,
            submissionState = LocalSubmissionState.ACCEPTED,
            status = AgentMessageStatus.QUEUED,
        )

        val merged = mergeLocalSendAtoms(
            remote = listOf(remoteAuthoritativeUser),
            atoms = listOf(steerAtomWithLegacySyntheticId),
        )

        // 断言：必须唯一合并为 1 条消息
        assertEquals("远端权威用户消息与旧合成 STEER ID 记录必须唯一合并，不得拆分为两行", 1, merged.size)
        val row = merged.single()

        // 断言：身份必须是 user，原汁原味正文，不能被改写为 system 或拼接 "↳ 已引导："
        assertEquals("引导消息身份必须始终为 user", "user", row.sender)
        assertEquals(
            "引导消息正文必须保留用户原文，禁止拼接系统前缀",
            listOf(MessagePart.Text("请改用新模式执行")),
            row.parts,
        )
        // 断言：ID 应取远端权威服务端 ID，且不再是 localPlaceholder
        assertEquals("msg_native_input_authoritative_100", row.id)
        assertFalse("已获得权威远端消息时不应是占位符", row.localPlaceholder)
        assertEquals("gen_live", row.generationId)
    }
}
