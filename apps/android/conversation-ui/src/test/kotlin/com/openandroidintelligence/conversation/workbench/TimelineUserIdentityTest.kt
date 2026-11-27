package com.openandroidintelligence.conversation.workbench

import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import com.openandroidintelligence.conversation.state.TimelineEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** 仅验证展示项身份；硬件退出图层仍由设备上的像素验收覆盖。 */
class TimelineUserIdentityTest {
    @Test
    fun optimisticUserKeepsItsUiKeyWhenCompletedBeforeTheRealIdArrives() {
        val model = TimelineDisplayModel()
        val local = user("local_cm_once", "cm_once").copy(
            pendingAcceptance = true,
            localPlaceholder = true,
        )
        val first = model.build(listOf(local), GenerationState.QUEUED).single()
        assertEquals("local_cm_once", first.key)

        // 设备证据中的顺序：旧 local 行先显示完成，随后真实 messageId 才到达。
        val localCompleted = local.copy(pendingAcceptance = false, messageStatus = AgentMessageStatus.COMPLETED)
        assertEquals(first.key, model.build(listOf(localCompleted), GenerationState.COMPLETED).single().key)

        val real = localCompleted.copy(key = "msg_real", localPlaceholder = false)
        val replaced = model.build(listOf(real), GenerationState.COMPLETED).single() as TimelineDisplayItem.Single
        assertEquals(first.key, replaced.key, "一次提交升级真实 ID 时不能变成 LazyColumn 的退出加新增")
        assertSame(real, replaced.entry)
        assertEquals("msg_real", replaced.entry.key)
        assertEquals(ClientMessageId("cm_once"), replaced.entry.clientMessageId)

        // 同一真实消息的 generationId 补齐、清空，以及终态刷新都不能改变 UI 身份。
        for (generationId in listOf("gen_once", null)) {
            val terminal = real.copy(generationId = generationId)
            val item = model.build(listOf(terminal), GenerationState.COMPLETED).single() as TimelineDisplayItem.Single
            assertEquals(first.key, item.key)
            assertSame(terminal, item.entry)
            assertEquals(generationId, item.entry.generationId)
        }
    }

    @Test
    fun differentInputsWithEqualTextKeepDistinctUiAndProtocolIdentities() {
        val model = TimelineDisplayModel()
        model.build(listOf(user("local_a", "client_a"), user("local_b", "client_b")))
        val real = listOf(user("real_a", "client_a"), user("real_b", "client_b"))
        val items = model.build(real).map { it as TimelineDisplayItem.Single }
        assertEquals(listOf("local_a", "local_b"), items.map { it.key })
        assertEquals(listOf("real_a", "real_b"), items.map { it.entry.key })
    }

    @Test
    fun conflictingRealMessagesForOneClientAreNeverMergedOrGivenDuplicateUiKeys() {
        val model = TimelineDisplayModel()
        model.build(listOf(user("local_a", "client_a")))
        val real = listOf(user("real_a", "client_a"), user("real_b", "client_a"))
        val items = model.build(real).map { it as TimelineDisplayItem.Single }
        assertEquals(listOf("real_a", "real_b"), items.map { it.key })
        assertEquals(real, items.map { it.entry })
    }

    @Test
    fun legacyUserAndMultipleAssistantMessagesRetainTheirOwnKeys() {
        val model = TimelineDisplayModel()
        val entries = listOf(user("legacy", null),
            user("answer_a", "client_a").copy(sender = "assistant", isUser = false),
            user("answer_b", "client_a").copy(sender = "assistant", isUser = false))
        assertEquals(entries.map { it.key }, model.build(entries).map { it.key })
    }

    @Test
    fun aFormerAliasCannotCollideWithAnotherRealMessageOrSurviveRemoval() {
        val model = TimelineDisplayModel()
        model.build(listOf(user("local_a", "client_a")))
        val real = listOf(user("real_a", "client_a"), user("local_a", "client_b"))
        assertEquals(listOf("real_a", "local_a"), model.build(real).map { it.key })
        model.build(emptyList())
        assertEquals("real_a", model.build(listOf(real.first())).single().key)
    }

    private fun user(key: String, clientId: String?) = TimelineEntry(
        key = key,
        sender = "user",
        text = "同样的正文",
        isUser = true,
        timestamp = 1_000L,
        pendingAcceptance = false,
        batchGroupId = null,
        clientMessageId = clientId?.let(::ClientMessageId),
    )
}
