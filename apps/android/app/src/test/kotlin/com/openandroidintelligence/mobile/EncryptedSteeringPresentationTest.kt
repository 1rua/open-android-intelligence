package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 断线与重开只恢复展示对应，不把正文相同变成远端受理或跨端点身份。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EncryptedSteeringPresentationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val scope = ConversationScope("steering_presentation", "endpoint_one", "account", "install")
    private val key = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 64 }, "AES")
        override fun delete() = Unit
    }
    private val conversation = ConversationId("same_conversation")
    private fun atom(id: String = "client", generation: String? = "same-turn") = LocalSendAtom(conversation, ClientMessageId(id),
        listOf(MessagePart.Text("重复正文")), 10_000L, delivery = MessageDeliveryMode.STEER,
        submissionState = LocalSubmissionState.ACCEPTED, steeringEcho = SteeringEchoPresentation(emptySet(), generationId = generation))
    private fun row(id: String, sequence: Long) = TimelineMessage(id, "user", listOf(MessagePart.Text("重复正文")), 1L,
        conversationId = conversation, historySequence = sequence)

    @Test fun `回声前重开保留原稿历史追赶后重开维持一对一对应`() = runBlocking {
        val original = atom()
        val mirror = EncryptedConversationMirror(context, scope, key)
        mirror.saveTimeline(conversation.value, TimelinePage(listOf(row("seen", 10)), null))
        mirror.saveSendAtoms(conversation.value, listOf(original))
        mirror.closeStore()
        val waiting = EncryptedConversationMirror(context, scope, key)
        assertEquals(2, waiting.mergeTimeline(conversation.value, waiting.timeline(conversation.value)!!).messages.size)
        assertNull(waiting.loadSendAtoms(conversation.value).single().steeringEcho!!.messageId)
        // 发送前未见、但同源 seq 更早的迟到分页也不能消费本地原稿。
        waiting.saveTimeline(conversation.value, TimelinePage(listOf(row("late-old", 9)), null))
        assertNull(waiting.loadSendAtoms(conversation.value).single().steeringEcho!!.messageId)
        waiting.saveTimeline(conversation.value, TimelinePage(listOf(row("echo", 11)), null))
        // 迟到 ACK 的旧原子快照不得撤销先到的正文对应。
        waiting.saveSendAtoms(conversation.value, listOf(original))
        waiting.closeStore()
        val restored = EncryptedConversationMirror(context, scope, key)
        try {
            val saved = restored.loadSendAtoms(conversation.value).single()
            assertNull(saved.messageId)
            assertEquals("echo", saved.steeringEcho!!.messageId)
            assertEquals("same-turn", saved.steeringEcho!!.generationId)
            assertTrue(saved.steeringEcho!!.seenMessageIds.contains("seen"))
            assertEquals(10L, saved.steeringEcho!!.historySequence)
            val visible = restored.mergeTimeline(conversation.value, restored.timeline(conversation.value)!!).messages
            assertEquals(3, visible.size)
            assertEquals("echo", visible.single { it.presentationClientMessageId == original.clientMessageId }.id)
            assertTrue("原始远端与展示结果都不能补造协议身份", visible.all { it.clientMessageId == null })
            assertTrue(restored.timeline(conversation.value)!!.messages.all { it.clientMessageId == null && it.presentationClientMessageId == null })
        } finally { restored.closeStore() }
    }

    @Test fun `同名会话在其他端点与其他会话不能取得原稿对应`() = runBlocking {
        val first = EncryptedConversationMirror(context, scope.copy(profileId = "steering_scope"), key)
        val other = EncryptedConversationMirror(context, scope.copy(profileId = "steering_scope", gatewayId = "endpoint_two"), key)
        try {
            first.saveSendAtoms(conversation.value, listOf(atom()))
            first.saveTimeline("other_conversation", TimelinePage(listOf(row("foreign-session", 1).copy(conversationId = ConversationId("other_conversation"))), null))
            other.saveTimeline(conversation.value, TimelinePage(listOf(row("foreign-endpoint", 1)), null))
            assertNull(first.loadSendAtoms(conversation.value).single().steeringEcho!!.messageId)
            assertTrue(other.loadSendAtoms(conversation.value).isEmpty())
            assertEquals("local_client", first.mergeTimeline(conversation.value, TimelinePage(emptyList(), null)).messages.single().id)
        } finally { first.closeStore(); other.closeStore() }
    }
}
