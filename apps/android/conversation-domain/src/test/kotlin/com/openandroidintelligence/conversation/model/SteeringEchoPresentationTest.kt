package com.openandroidintelligence.conversation.model

import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import com.openandroidintelligence.conversation.ports.MessageDeliveryMode
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.ports.HistoryMessagePosition
import com.openandroidintelligence.conversation.ports.preservingHistoryPosition
import org.junit.Assert.*
import org.junit.Test

/** 正文只形成展示对应：每次发送独立、已见历史不消耗原稿、身份权威不变。 */
class SteeringEchoPresentationTest {
    private val conversation = ConversationId("remote_session")
    private fun atom(id: String = "first", text: String = "继续检查", seen: Set<String> = emptySet(), sequence: Long? = null,
        generation: String? = "native_turn") = LocalSendAtom(conversation, ClientMessageId(id), listOf(MessagePart.Text(text)), 10_000L,
            delivery = MessageDeliveryMode.STEER, steeringEcho = SteeringEchoPresentation(seen, sequence, generationId = generation))
    private fun row(id: String = "echo", text: String = "继续检查", sequence: Long? = null) =
        TimelineMessage(id, "user", listOf(MessagePart.Text(text)), 1L, conversationId = conversation,
            generationId = "native_turn", historySequence = sequence)

    @Test fun `历史位置仅同页可比较且回声合并保留完整来源`() {
        val before = HistoryMessagePosition("page", 0)
        val echoPosition = HistoryMessagePosition("page", 1)
        val after = HistoryMessagePosition("page", 2)
        assertFalse(before.isAfter(echoPosition))
        assertTrue(after.isAfter(echoPosition))
        assertFalse(HistoryMessagePosition("other-page", 100).isAfter(echoPosition))
        val remote = row().copy(historyPosition = echoPosition)
        val visible = mergeLocalSendAtoms(listOf(remote), listOf(atom())).single()
        assertEquals(echoPosition, visible.historyPosition)
        assertNull(visible.clientMessageId)
        assertEquals(atom().clientMessageId, visible.presentationClientMessageId)
    }

    @Test fun `普通明确身份回并保留完整历史位置和稳定序号`() {
        val local = atom(id = "normal", text = "下一轮普通输入").copy(
            delivery = MessageDeliveryMode.NORMAL, steeringEcho = null,
        ).identified("normal-native")
        val position = HistoryMessagePosition("same-history-page", 1)
        // 原生 ID 已确认后，历史是否再次回传客户端身份都不能清掉跨 User 的排序边界。
        listOf<ClientMessageId?>(null, local.clientMessageId).forEach { directClient ->
            val remote = row("normal-native", "下一轮普通输入", sequence = 42).copy(
                clientMessageId = directClient, generationId = "next-turn", historyPosition = position,
            )
            val visible = mergeLocalSendAtoms(listOf(remote), listOf(local)).single()
            assertEquals("normal-native", visible.id)
            assertEquals(local.clientMessageId, visible.clientMessageId)
            assertFalse(visible.localPlaceholder)
            assertEquals("普通发送回并必须完整保留原生历史页位置", position, visible.historyPosition)
            assertEquals("普通发送回并不能擦掉已存在的稳定序号", 42L, visible.historySequence)
        }
    }

    @Test fun `无位置更新只继承同一会话同一行的完整位置`() {
        val old = row().copy(historyPosition = HistoryMessagePosition("old-page", 8))
        val incoming = row().copy(parts = listOf(MessagePart.Text("新正文")))
        assertEquals(old.historyPosition, incoming.preservingHistoryPosition(old).historyPosition)
        val next = incoming.copy(historyPosition = HistoryMessagePosition("new-page", 1))
        assertEquals(HistoryMessagePosition("new-page", 1), next.preservingHistoryPosition(old).historyPosition)
        assertNull(incoming.copy(id = "other").preservingHistoryPosition(old).historyPosition)
        assertNull(incoming.copy(conversationId = ConversationId("other")).preservingHistoryPosition(old).historyPosition)
    }

    @Test fun `成功回执尚无正文时本地原稿始终可见`() {
        val accepted = atom().copy(submissionState = LocalSubmissionState.ACCEPTED, status = AgentMessageStatus.DELIVERED)
        val visible = mergeLocalSendAtoms(emptyList(), listOf(accepted)).single()
        assertEquals(accepted.parts, visible.parts)
        assertTrue(visible.localPlaceholder)
        assertEquals(accepted.clientMessageId, visible.clientMessageId)
    }

    @Test fun `回声先到按正文合并但不生成协议身份和受理状态`() {
        val local = atom()
        val remote = row()
        val reconciled = reconcileSteeringEchoPresentation(listOf(remote), listOf(local)).single()
        assertEquals("echo", reconciled.steeringEcho!!.messageId)
        assertNull(reconciled.messageId)
        assertEquals(LocalSubmissionState.PREPARED, reconciled.submissionState)
        val visible = mergeLocalSendAtoms(listOf(remote), listOf(reconciled)).single()
        assertEquals(remote.id, visible.id)
        assertNull(visible.clientMessageId)
        assertEquals(local.clientMessageId, visible.presentationClientMessageId)
        assertEquals("native_turn", reconciled.steeringEcho!!.generationId)
        assertFalse(visible.localPlaceholder)
        assertEquals("CONFIRMED", visible.state)
        assertNull(remote.presentationClientMessageId)
    }

    @Test fun `两次同文发送只有一个回声时仍保留另一原稿`() {
        val first = atom()
        val second = atom("second").copy(timestamp = first.timestamp + 1)
        val remote = row()
        val updated = reconcileSteeringEchoPresentation(listOf(remote), listOf(first, second))
        assertEquals(listOf("echo", null), updated.map { it.steeringEcho!!.messageId })
        val visible = mergeLocalSendAtoms(listOf(remote), updated)
        assertEquals(2, visible.size)
        assertEquals(1, visible.count { it.localPlaceholder })
        assertEquals(second.clientMessageId, visible.single { it.localPlaceholder }.clientMessageId)
    }

    @Test fun `两个相同回声逐条消费且重复历史不消耗下一个原稿`() {
        val locals = listOf(atom(), atom("second").copy(timestamp = 10_001L))
        val first = reconcileSteeringEchoPresentation(listOf(row()), locals)
        val repeated = reconcileSteeringEchoPresentation(listOf(row()), first)
        assertEquals(first, repeated)
        val both = reconcileSteeringEchoPresentation(listOf(row(), row("second-echo")), repeated)
        assertEquals(listOf("echo", "second-echo"), both.map { it.steeringEcho!!.messageId })
        assertEquals(2, mergeLocalSendAtoms(listOf(row(), row("second-echo")), both).size)
    }

    @Test fun `同毫秒发送在历史行倒序到达后仍保留本地发送顺序`() {
        val locals = listOf(atom(), atom("second"))
        val bound = reconcileSteeringEchoPresentation(listOf(row(), row("second-echo")), locals)
        val visible = mergeLocalSendAtoms(listOf(row("second-echo"), row()), bound)
        assertEquals(listOf("echo", "second-echo"), visible.map { it.id })
        val partial = mergeLocalSendAtoms(listOf(row()), locals)
        assertEquals(listOf("echo", "local_second"), partial.map { it.id })
    }

    @Test fun `发送前已见同文及迟到旧序号历史均不匹配`() {
        val local = atom(seen = setOf("seen"), sequence = 8)
        val candidates = listOf(row("seen", sequence = 9), row("old-page", sequence = 7), row("same-sequence", sequence = 8))
        assertEquals(listOf(local), reconcileSteeringEchoPresentation(candidates, listOf(local)))
        val updated = reconcileSteeringEchoPresentation(candidates + row("new", sequence = 9), listOf(local)).single()
        assertEquals("new", updated.steeringEcho!!.messageId)
    }

    @Test fun `Host时间落后或缺少时间不影响正文回并`() {
        listOf(0L, 1L, 10_000L, Long.MAX_VALUE).forEach { time ->
            val visible = mergeLocalSendAtoms(listOf(row().copy(timestamp = time)), listOf(atom())).single()
            assertEquals("echo", visible.id)
            assertEquals(10_000L, visible.timestamp)
        }
    }

    @Test fun `缺少稳定序号的真实回声不会被零值或旧边界错误过滤`() {
        val remote = row().copy(historySequence = null, timestamp = 0L)
        val updated = reconcileSteeringEchoPresentation(listOf(remote), listOf(atom(sequence = 100L))).single()
        assertEquals("echo", updated.steeringEcho!!.messageId)
        assertNull(reconcileSteeringEchoPresentation(listOf(remote), listOf(atom(seen = setOf("echo"), sequence = 100L)))
            .single().steeringEcho!!.messageId)
    }

    @Test fun `其他会话角色占位删除和其他客户端身份均不能消费原稿`() {
        val candidates = listOf(row("other-session").copy(conversationId = ConversationId("other")),
            row("missing-session").copy(conversationId = null), row("assistant").copy(sender = "assistant"),
            row("system").copy(sender = "system"), row("placeholder").copy(localPlaceholder = true),
            row("deleted").copy(state = "DELETED"), row("foreign-client").copy(clientMessageId = ClientMessageId("foreign")))
        val local = atom()
        assertEquals(listOf(local), reconcileSteeringEchoPresentation(candidates, listOf(local)))
    }

    @Test fun `正文精确比较不吞空格不同的消息也不匹配空正文`() {
        val local = atom()
        assertNull(reconcileSteeringEchoPresentation(listOf(row(text = "继续检查 ")), listOf(local)).single().steeringEcho!!.messageId)
        assertNull(reconcileSteeringEchoPresentation(listOf(row(text = "")), listOf(atom(text = ""))).single().steeringEcho!!.messageId)
    }

    @Test fun `普通发送和旧版未开启展示回并记录维持身份匹配规则`() {
        val ordinary = atom().copy(delivery = MessageDeliveryMode.NORMAL)
        val legacy = atom("legacy").copy(steeringEcho = null)
        val visible = mergeLocalSendAtoms(listOf(row()), listOf(ordinary, legacy))
        assertEquals(3, visible.size)
        assertEquals(2, visible.count { it.localPlaceholder })
    }

    @Test fun `已绑定普通发送的远端行不能被引导消费`() {
        val normal = atom("normal").copy(messageId = "echo", delivery = MessageDeliveryMode.NORMAL, steeringEcho = null)
        val steer = atom()
        assertNull(reconcileSteeringEchoPresentation(listOf(row()), listOf(normal, steer)).last().steeringEcho!!.messageId)
    }

    @Test fun `正文按统一清洗结果比较但回声到达前保持原稿`() {
        val local = atom(text = "继续检查<system-reminder>内部提示</system-reminder>")
        assertEquals(local.parts, mergeLocalSendAtoms(emptyList(), listOf(local)).single().parts)
        assertEquals("echo", reconcileSteeringEchoPresentation(listOf(row()), listOf(local)).single().steeringEcho!!.messageId)
    }

    @Test fun `已匹配行暂时缺失时不能隐藏本地正文或重绑另一个回声`() {
        val bound = reconcileSteeringEchoPresentation(listOf(row()), listOf(atom())).single()
        val next = reconcileSteeringEchoPresentation(listOf(row("other-echo")), listOf(bound)).single()
        assertEquals("echo", next.steeringEcho!!.messageId)
        val visible = mergeLocalSendAtoms(emptyList(), listOf(next)).single()
        assertTrue(visible.localPlaceholder)
        assertEquals(bound.parts, visible.parts)
    }

    @Test fun `已展示回并的远端删除不能复活原稿或补造身份`() {
        val bound = reconcileSteeringEchoPresentation(listOf(row()), listOf(atom())).single()
        val deleted = row().copy(parts = emptyList(), state = "DELETED")
        val recovered = reconcileSnapshotDeletions(listOf(deleted), listOf(bound)).single()
        assertTrue(recovered.snapshotDeleted)
        assertNull(recovered.messageId)
        assertTrue(mergeLocalSendAtoms(emptyList(), listOf(recovered)).isEmpty())
    }
}
