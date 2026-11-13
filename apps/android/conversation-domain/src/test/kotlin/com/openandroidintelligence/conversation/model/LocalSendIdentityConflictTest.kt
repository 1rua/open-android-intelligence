package com.openandroidintelligence.conversation.model

import com.openandroidintelligence.conversation.ports.TimelineMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalSendIdentityConflictTest {
    @Test
    fun boundServerIdWinsOverAnEarlierRowWithTheSameClientId() {
        val clientId = ClientMessageId("client_1")
        val atom = LocalSendAtom(
            conversationId = ConversationId("conv_a"),
            clientMessageId = clientId,
            parts = listOf(MessagePart.Text("本地修订B")),
            timestamp = 2_000L,
            messageId = "server-b",
            contentRevision = 1L,
        )
        val remote = listOf(
            TimelineMessage(
                id = "server-a",
                sender = "user",
                parts = listOf(MessagePart.Text("远端正文A")),
                timestamp = 1_000L,
                conversationId = atom.conversationId,
                clientMessageId = clientId,
            ),
            TimelineMessage(
                id = "server-b",
                sender = "user",
                parts = listOf(MessagePart.Text("远端正文B")),
                timestamp = 2_000L,
                conversationId = atom.conversationId,
                clientMessageId = clientId,
            ),
        )

        val merged = mergeLocalSendAtoms(remote, listOf(atom)).associateBy { it.id }

        assertEquals(setOf("server-a", "server-b"), merged.keys)
        assertEquals(listOf(MessagePart.Text("远端正文A")), merged.getValue("server-a").parts)
        assertEquals(listOf(MessagePart.Text("本地修订B")), merged.getValue("server-b").parts)
    }

    @Test
    fun tombstoneForAnotherServerIdDoesNotDeleteTheBoundAtom() {
        val clientId = ClientMessageId("client_1")
        val atom = LocalSendAtom(
            conversationId = ConversationId("conv_a"),
            clientMessageId = clientId,
            parts = listOf(MessagePart.Text("保留的本地内容")),
            timestamp = 2_000L,
            messageId = "server-b",
        )
        val remoteTombstone = TimelineMessage(
            id = "server-a",
            sender = "user",
            parts = emptyList(),
            timestamp = 1_000L,
            state = "DELETED",
            conversationId = atom.conversationId,
            clientMessageId = clientId,
        )

        val reconciled = reconcileSnapshotDeletions(listOf(remoteTombstone), listOf(atom)).single()

        assertEquals("server-b", reconciled.messageId)
        assertFalse(reconciled.isDeleted)
        assertEquals(listOf(MessagePart.Text("保留的本地内容")), reconciled.parts)
    }

    @Test
    fun provisionalAtomDoesNotChooseBetweenMultipleRealIdsWithTheSameClientId() {
        val clientId = ClientMessageId("client_1")
        val atom = LocalSendAtom(
            conversationId = ConversationId("conv_a"),
            clientMessageId = clientId,
            parts = listOf(MessagePart.Text("本地草稿")),
            timestamp = 2_000L,
        )
        val remote = listOf(
            TimelineMessage(
                id = "server-a",
                sender = "user",
                parts = listOf(MessagePart.Text("远端A")),
                timestamp = 1_000L,
                conversationId = atom.conversationId,
                clientMessageId = clientId,
            ),
            TimelineMessage(
                id = "server-b",
                sender = "user",
                parts = listOf(MessagePart.Text("远端B")),
                timestamp = 2_000L,
                conversationId = atom.conversationId,
                clientMessageId = clientId,
            ),
        )

        val merged = mergeLocalSendAtoms(remote, listOf(atom)).associateBy { it.id }

        assertEquals(setOf("server-a", "server-b", "local_client_1"), merged.keys)
        assertEquals(listOf(MessagePart.Text("远端A")), merged.getValue("server-a").parts)
        assertEquals(listOf(MessagePart.Text("远端B")), merged.getValue("server-b").parts)
        assertEquals(listOf(MessagePart.Text("本地草稿")), merged.getValue("local_client_1").parts)
        assertEquals(true, merged.getValue("local_client_1").localPlaceholder)
    }

    @Test
    fun provisionalClientMatchMustRespectSenderAndConversationRegardlessOfInputOrder() {
        val clientId = ClientMessageId("client_1")
        val conversationId = ConversationId("conv_a")
        val atom = LocalSendAtom(
            conversationId = conversationId,
            clientMessageId = clientId,
            parts = listOf(MessagePart.Text("本地草稿")),
            timestamp = 2_000L,
        )
        val wrongSender = TimelineMessage(
            id = "assistant-row",
            sender = "assistant",
            parts = listOf(MessagePart.Text("相同client的助手消息")),
            timestamp = 1_000L,
            conversationId = conversationId,
            clientMessageId = clientId,
        )
        val wrongConversation = TimelineMessage(
            id = "other-thread-row",
            sender = "user",
            parts = listOf(MessagePart.Text("另一个会话的消息")),
            timestamp = 1_500L,
            conversationId = ConversationId("conv_b"),
            clientMessageId = clientId,
        )

        listOf(
            listOf(wrongSender, wrongConversation),
            listOf(wrongConversation, wrongSender),
        ).forEach { remote ->
            val merged = mergeLocalSendAtoms(remote, listOf(atom)).associateBy { it.id }

            assertEquals(setOf("assistant-row", "other-thread-row", "local_client_1"), merged.keys)
            assertEquals(listOf(MessagePart.Text("相同client的助手消息")), merged.getValue("assistant-row").parts)
            assertEquals(listOf(MessagePart.Text("另一个会话的消息")), merged.getValue("other-thread-row").parts)
            assertEquals(listOf(MessagePart.Text("本地草稿")), merged.getValue("local_client_1").parts)
        }
    }
}
