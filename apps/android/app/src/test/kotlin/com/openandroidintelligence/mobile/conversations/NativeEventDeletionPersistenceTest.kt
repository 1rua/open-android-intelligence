package com.openandroidintelligence.mobile.conversations

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.WorkbenchCheckpoint
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.agents.*
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeEventDeletionPersistenceTest {
    @Test fun deletedHostHistoryClearsRealRecordsBeforeAckAndCannotReturnAfterReopen() = runBlocking {
        for (sender in listOf("assistant", "user")) {
            val scope = newScope()
            val original = oldMessage("reply", sender)
            val store = mirror(scope)
            val checkpoint = seed(store, listOf(original))
            val h = Harness(scope, store, history(listOf(deleted(original))))
            try {
                h.enqueue(original, "deleted")
                h.start()
                val ack = h.acknowledgments.next()
                assertEquals("deleted", h.confirmations.next())
                assertEquals("base", ack.cursor)
                assertEquals("deleted", ack.pending)
                assertDeleted(ack, original)
                val converted = h.attemptedPages.next().filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().single()
                assertTrue(converted.eventId.startsWith("native-history:hermes:chat:reply:"))
                assertEquals(0L, converted.revision)
                assertNull(converted.message.contentRevision)
                assertTrue(converted.message.isDeleted)

                val following = oldMessage("following", "assistant").copy(
                    parts = listOf(MessagePart.Text("following reply")), contentRevision = 1, generationId = "following-gen")
                h.wireHistory = history(listOf(deleted(original), following))
                h.enqueue(following, "following")
                val followingAck = h.acknowledgments.next()
                assertEquals("deleted", followingAck.cursor)
                assertEquals("following", followingAck.pending)
                assertDeleted(followingAck, original)
                assertEquals("following", h.confirmations.next())
                assertTrue(h.requests.any { it.target.contains("cursor=deleted") })
                assertEquals("following reply", (store.loadTimeline("chat")!!.messages.single().parts.single() as MessagePart.Text).value)
                assertTrue(h.requests.none { it.target.endsWith("/messages") })
            } finally {
                h.stop()
                store.closeStore()
            }

            val reopened = mirror(scope)
            try {
                assertEquals("following", reopened.eventCursor("chat"))
                assertNull(reopened.pendingAck("chat"))
                assertDeleted(snapshot(reopened), original)
                val stale = original.copy(contentRevision = 100)
                reopened.commitEvents(listOf(upsert(stale)))
                reopened.offlineMirror().saveTimeline("chat", TimelinePage(listOf(stale), null, 100))
                reopened.saveCheckpoint(checkpoint)
                if (sender == "user") reopened.saveSendAtomsDurably("chat", listOf(LocalSendAtom(
                    ConversationId("chat"), original.clientMessageId!!, original.parts, original.timestamp,
                    messageId = original.id, contentRevision = 100)))
                val projected = reopened.offlineMirror().projectEvent(upsert(stale)) as VerifiedConversationEvent.TimelineUpsert
                assertTrue(projected.message.isDeleted)
                assertTrue(projected.message.parts.isEmpty())
                assertDeleted(snapshot(reopened), original)
            } finally { reopened.closeStore() }
            val restarted = mirror(scope)
            try {
                assertDeleted(snapshot(restarted), original)
                assertEquals("following", restarted.loadTimeline("chat")!!.messages.single().id)
            } finally { restarted.closeStore() }
        }
    }

    @Test fun aRealDeletionTransactionFailureRollsBackAllFactsAndNeverAcksBeforeRecovery() = runBlocking {
        val scope = newScope()
        val reply = oldMessage("reply", "assistant")
        val question = oldMessage("question", "user")
        val store = mirror(scope)
        seed(store, listOf(reply, question))
        // 第一条撤回已进入真实 SQLite 事务，第二条身份冲突使整页保存失败。
        val conflicting = deleted(question).copy(clientMessageId = ClientMessageId("foreign-client"))
        val h = Harness(scope, store, history(listOf(deleted(reply), conflicting)))
        try {
            h.enqueue(reply, "deleted")
            h.start()
            val failure = h.failedSaves.next()
            assertEquals("MESSAGE_ID_CONFLICT", failure.message)
            h.stop()
            assertTrue(h.requests.none { it.target.endsWith("/events/ack") })
            assertEquals("base", store.eventCursor("chat"))
            assertNull(store.pendingAck("chat"))
            assertEquals(setOf("reply", "question"), store.loadTimeline("chat")!!.messages.map { it.id }.toSet())
            assertTrue(containsPrivateBody(store))
            assertFalse(store.loadSendAtoms("chat").single().isDeleted)
        } finally {
            h.stop()
            store.closeStore()
        }

        val recovered = mirror(scope)
        val retry = Harness(scope, recovered, history(listOf(deleted(reply), deleted(question))))
        try {
            assertEquals("base", recovered.eventCursor("chat"))
            assertFalse((recovered.offlineMirror().projectEvent(upsert(reply)) as VerifiedConversationEvent.TimelineUpsert).message.isDeleted)
            retry.enqueue(reply, "deleted")
            retry.start()
            val ack = retry.acknowledgments.next()
            assertEquals("deleted", retry.confirmations.next())
            assertDeleted(ack, reply)
            assertDeleted(ack, question)
            assertFalse(containsPrivateBody(recovered))
        } finally {
            retry.stop()
            recovered.closeStore()
        }
        val restarted = mirror(scope)
        try {
            assertEquals("deleted", restarted.eventCursor("chat"))
            assertDeleted(snapshot(restarted), reply)
            assertDeleted(snapshot(restarted), question)
        } finally { restarted.closeStore() }
    }

    @Test fun aLostAckAfterRealDeletionCommitRetriesTheDurableCursorWithoutOldHistory() = runBlocking {
        val scope = newScope()
        val original = oldMessage("reply", "assistant")
        val store = mirror(scope)
        seed(store, listOf(original))
        val h = Harness(scope, store, history(listOf(deleted(original)))).apply { loseNextAck = true }
        try {
            h.enqueue(original, "deleted")
            h.start()
            assertTrue(h.ackFailures.next() is IOException)
            h.stop()
            assertDeleted(h.acknowledgments.next(), original)
            assertEquals("base", store.eventCursor("chat"))
            assertEquals("deleted", store.pendingAck("chat"))
        } finally {
            h.stop()
            store.closeStore()
        }
        val reopened = mirror(scope)
        // 故意保留旧正文响应：恢复待确认游标不能重新消费它。
        val retry = Harness(scope, reopened, history(listOf(original)))
        try {
            retry.start()
            assertDeleted(retry.acknowledgments.next(), original)
            assertEquals("deleted", retry.confirmations.next())
            retry.stop()
            assertEquals("deleted", reopened.eventCursor("chat"))
            assertNull(reopened.pendingAck("chat"))
            assertTrue(retry.requests.none { it.target.endsWith("/history") || it.target.endsWith("/messages") })
            assertTrue(retry.attemptedPages.tryReceive().isFailure)
            assertDeleted(snapshot(reopened), original)
        } finally {
            retry.stop()
            reopened.closeStore()
        }
    }

    @Test fun aRevisionedTombstoneStillAllowsNewerContentInTheRealMirror() = runBlocking {
        val store = mirror(newScope())
        val original = oldMessage("reply", "assistant")
        try {
            seed(store, listOf(original))
            store.commitEvents(listOf(VerifiedConversationEvent.TimelineTombstoned("older-delete", 2,
                original.id, 6, ConversationId("chat"))))
            assertEquals(original.parts, store.loadTimeline("chat")!!.messages.single().parts)
            store.commitEvents(listOf(VerifiedConversationEvent.TimelineTombstoned("revisioned-delete", 3,
                original.id, 8, ConversationId("chat"))))
            for (revision in listOf(7L, 8L)) {
                store.commitEvents(listOf(upsert(original.copy(contentRevision = revision))))
                assertTrue(store.loadTimeline("chat")!!.messages.isEmpty())
            }
            val fresh = original.copy(contentRevision = 9, parts = listOf(MessagePart.Text("explicit newer content")))
            store.commitEvents(listOf(upsert(fresh)))
            store.commitEvents(listOf(VerifiedConversationEvent.TimelineTombstoned("late-delete", 4,
                original.id, 8, ConversationId("chat")), upsert(original.copy(contentRevision = 8))))
            assertEquals(fresh.parts, store.loadTimeline("chat")!!.messages.single().parts)
        } finally { store.closeStore() }
    }

    @Test fun revisionlessDeletionKeepsSnapshotReadEpochsAndMessageRevisionsSeparate() = runBlocking {
        val store = mirror(newScope())
        val original = oldMessage("reply", "assistant")
        try {
            seed(store, listOf(original))
            val thread = ConversationSummary(ConversationId("chat"), "original", 1)
            store.saveConversations(ConversationPage(listOf(thread), null, 900))
            val olderRead = store.beginListRead()
            val newerRead = store.beginListRead()
            store.commitPage("chat", listOf(VerifiedConversationEvent.TimelineUpsert("deleted-history", 2, 0,
                deleted(original))), emptyList(), "deleted")
            assertEquals(newerRead, store.offlineMirror().historyEpoch())
            assertFalse(store.saveConversations(ConversationPage(listOf(thread.copy(title = "late")), null, 800), olderRead))
            val fresh = thread.copy(title = "fresh")
            assertTrue(store.saveConversations(ConversationPage(listOf(fresh), null, 20), newerRead))
            assertEquals(listOf(fresh), store.offlineMirror().threads())
            assertEquals(20L, store.offlineMirror().threadsSnapshotRevision())
            store.offlineMirror().saveTimeline("chat", TimelinePage(listOf(original.copy(contentRevision = 100)), null, 1000))
            assertDeleted(snapshot(store), original)
            assertEquals(newerRead, store.offlineMirror().historyEpoch())
            assertEquals("deleted", store.pendingAck("chat"))
            assertEquals("base", store.eventCursor("chat"))
        } finally { store.closeStore() }
    }

    @Test fun unrevisionedDeletionCannotEraseTheSameMessageIdInAnotherSessionOrAgent() = runBlocking {
        val scope = newScope()
        val original = oldMessage("reply", "assistant")
        val otherSession = original.copy(conversationId = ConversationId("other-session"), parts = listOf(MessagePart.Text("other session")))
        val store = mirror(scope)
        val otherAgent = NativeConversationMirror(context, scope, { "other-agent" }, key, observerId = "phone:1")
        try {
            seed(store, listOf(original))
            store.commitEvents(listOf(upsert(otherSession)))
            seed(otherAgent, listOf(original))
            store.commitPage("chat", listOf(VerifiedConversationEvent.TimelineUpsert("deleted-history", 2, 0,
                deleted(original))), emptyList(), "deleted")
            assertDeleted(snapshot(store), original)
            assertEquals("other session", (store.loadTimeline("other-session")!!.messages.single().parts.single() as MessagePart.Text).value)
            assertEquals(original.parts, otherAgent.loadTimeline("chat")!!.messages.single().parts)
            assertEquals("base", otherAgent.eventCursor("chat"))
        } finally { store.closeStore(); otherAgent.closeStore() }
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val key = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 37 }, "AES")
        override fun delete() = Unit
    }
    private fun newScope() = ConversationScope("f03-${UUID.randomUUID()}", "https://synthetic.example", "account", "install")
    private fun mirror(scope: ConversationScope) = NativeConversationMirror(context, scope, { "hermes" }, key, observerId = "phone:1")
    private fun oldMessage(id: String, sender: String) = TimelineMessage(id, sender,
        listOf(MessagePart.Text(PRIVATE_BODY), MessagePart.Attachment(AttachmentDraftId("private-media"), "private.txt", "text/plain")),
        1, conversationId = ConversationId("chat"), generationId = "gen", contentRevision = 7,
        clientMessageId = if (sender == "user") ClientMessageId("client-$id") else null)
    private fun deleted(message: TimelineMessage) = message.copy(parts = emptyList(), state = "DELETED", contentRevision = null)
    private fun upsert(message: TimelineMessage) = VerifiedConversationEvent.TimelineUpsert(
        "body-${message.conversationId?.value}-${message.id}-${message.contentRevision}", 1, message.contentRevision!!, message)

    private suspend fun seed(store: NativeConversationMirror, rows: List<TimelineMessage>): WorkbenchCheckpoint {
        val checkpoint = WorkbenchCheckpoint(listOf(ConversationSummary(ConversationId("chat"), "chat", 1)), "chat", "chat",
            rows, rows.associate { it.id to it.contentRevision!! }, "", 0, emptyList(), null, emptySet(), emptySet())
        store.saveCheckpoint(checkpoint)
        store.commitEvents(rows.map(::upsert))
        store.commitPage("chat", emptyList(), emptyList(), "base")
        store.confirmAck("chat", "base")
        assertEquals(rows.map { it.id }.toSet(), store.loadTimeline("chat")!!.messages.map { it.id }.toSet())
        return checkpoint
    }

    private fun history(messages: List<TimelineMessage>): String = Json.canonical(Json.of(mapOf("messages" to messages.map { message ->
        buildMap<String, Any?> {
            put("id", message.id); put("role", message.sender); put("state", message.state)
            put("text", message.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value })
            put("generationId", message.generationId)
            message.clientMessageId?.let { put("clientMessageId", it.value) }
            message.contentRevision?.let { put("contentRevision", it) }
        }
    })))

    private data class DurableDeletion(val revision: Long, val attachmentIds: Set<String>)
    private data class DurableSnapshot(val page: TimelinePage?, val checkpoint: WorkbenchCheckpoint?, val atoms: List<LocalSendAtom>,
        val pending: String?, val cursor: String?, val retainsPrivateBody: Boolean, val deletions: Map<String, DurableDeletion>)
    private suspend fun snapshot(store: NativeConversationMirror): DurableSnapshot {
        val records = records(store)
        // 先读原始删除记录和正文，再读取投影；投影自身不能补写屏障来掩盖保存缺口。
        val prefix = "event-revision:chat:"
        val deletions = records.keys("event-revision").filter { it.startsWith(prefix) }.mapNotNull { record ->
            val bytes = records.read(record) ?: return@mapNotNull null
            try {
                val value = JsonFields.obj(Json.parse(bytes.decodeToString()))
                if (JsonFields.bool(value, "snapshotDeleted") == true)
                    record.removePrefix(prefix) to DurableDeletion(checkNotNull(JsonFields.long(value, "revision")),
                        JsonFields.strings(value, "deletedAttachmentIds").toSet())
                else null
            } finally { bytes.fill(0) }
        }.toMap()
        val retainsBody = containsPrivateBody(store)
        return DurableSnapshot(store.offlineMirror().timeline("chat"), store.load(), store.loadSendAtoms("chat"),
            store.pendingAck("chat"), store.eventCursor("chat"), retainsBody, deletions)
    }
    private fun assertDeleted(snapshot: DurableSnapshot, message: TimelineMessage) {
        val page = checkNotNull(snapshot.page)
        assertTrue(page.messages.none { it.id == message.id })
        assertTrue(page.deletedMessageIds.contains(message.id))
        val deletion = checkNotNull(snapshot.deletions[message.id])
        assertEquals(message.contentRevision, deletion.revision)
        assertEquals(message.parts.filterIsInstance<MessagePart.Attachment>().map { it.draftId.value }.toSet(), deletion.attachmentIds)
        assertTrue(snapshot.checkpoint!!.messages.none { it.id == message.id })
        assertFalse(snapshot.retainsPrivateBody)
        if (message.sender == "user") {
            val atom = snapshot.atoms.single { it.messageId == message.id }
            assertTrue(atom.snapshotDeleted)
            assertTrue(atom.parts.isEmpty())
        }
    }
    private fun records(store: NativeConversationMirror) =
        EncryptedConversationMirror::class.java.getDeclaredField("documents").let { field ->
            field.isAccessible = true
            field.get(store.offlineMirror()) as SqliteConversationRecords
        }
    private fun containsPrivateBody(store: NativeConversationMirror): Boolean {
        val records = records(store)
        // 连同分记录正文一起解密检查，避免只验证 UI 隐藏而遗留可恢复副本。
        val keys = records.keys() + listOf("message-record", "send-atom-record", "thread-record", "checkpoint-message")
            .flatMap { records.keys(it) }
        return keys.any { record ->
            records.read(record)?.let { bytes ->
                try { bytes.decodeToString().contains(PRIVATE_BODY) } finally { bytes.fill(0) }
            } ?: false
        }
    }
    private suspend fun <T> Channel<T>.next(): T = withTimeout(10_000) { receive() }

    private inner class Harness(private val scope: ConversationScope, private val store: NativeConversationMirror,
        initialHistory: String) {
        @Volatile var wireHistory = initialHistory
        val requests = CopyOnWriteArrayList<WireRequest>()
        val acknowledgments = Channel<DurableSnapshot>(Channel.UNLIMITED)
        val confirmations = Channel<String>(Channel.UNLIMITED)
        val attemptedPages = Channel<List<VerifiedConversationEvent>>(Channel.UNLIMITED)
        val failedSaves = Channel<Exception>(Channel.UNLIMITED)
        val ackFailures = Channel<Exception>(Channel.UNLIMITED)
        private val pages = Channel<String>(Channel.UNLIMITED)
        private val job = SupervisorJob()
        @Volatile var loseNextAck = false
        private val observedStore = object : NativeConversationStore by store {
            override suspend fun commitPage(conversationId: String, events: List<VerifiedConversationEvent>,
                approvals: List<NativeEventApproval>, cursor: String) {
                attemptedPages.send(events)
                try { store.commitPage(conversationId, events, approvals, cursor) }
                catch (failure: Exception) { failedSaves.send(failure); throw failure }
            }
            override suspend fun confirmAck(conversationId: String, cursor: String) {
                store.confirmAck(conversationId, cursor)
                confirmations.send(cursor)
            }
        }
        private val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                val body = when {
                    request.target.endsWith("/history") -> wireHistory
                    request.target.contains("/events?") -> pages.receive()
                    request.target.endsWith("/events/ack") -> {
                        val cursor = JsonFields.string(JsonFields.obj(Json.parse(request.body.decodeToString())), "cursor")!!
                        val reader = mirror(scope)
                        try { acknowledgments.send(snapshot(reader)) } finally { reader.closeStore() }
                        if (loseNextAck) {
                            loseNextAck = false
                            val failure = IOException("lost ACK response")
                            ackFailures.send(failure)
                            throw failure
                        }
                        Json.canonical(Json.of(mapOf("cursor" to cursor)))
                    }
                    else -> error("Unexpected native recovery request: ${request.target}")
                }
                return WireResponse(200, listOf(RawHeader("Content-Type", "application/json")), body.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        private val agent = CoreAgent("hermes", "synthetic", "hermes", false, "account",
            AgentCapabilities(history = true, eventSync = true))
        private val client = AgentSessionClient(GatewayHttpClient(
            GatewayProfile("account", "phone", "login", "https://core.example", accessToken = "synthetic"),
            transport, { ByteArray(64) }, InMemoryEventCursorStore()))
        private val repository = CoreAgentConversationRepository(client, { agent }, { "chat" },
            CoroutineScope(job + Dispatchers.Default), Dispatchers.Default, storeForAgent = { observedStore },
            nativeRetryJitter = { 60_000 })

        suspend fun enqueue(message: TimelineMessage, cursor: String) {
            val payload = mapOf("conversationId" to "chat", "messageId" to message.id, "generationId" to message.generationId,
                "revision" to message.contentRevision, "sender" to message.sender, "parts" to listOf(mapOf("type" to "text", "text" to PRIVATE_BODY)),
                "text" to PRIVATE_BODY, "timestamp" to 1)
            pages.send(Json.canonical(Json.of(mapOf("events" to listOf(mapOf("eventId" to "event-$cursor",
                "eventType" to "conversation.message.completed", "payload" to payload)), "cursor" to cursor, "hasMore" to true))))
        }
        fun start() = repository.activeConversationChanged()
        suspend fun stop() = job.cancelAndJoin()
    }

    private companion object {
        const val PRIVATE_BODY = "F03_SYNTHETIC_PRIVATE_BODY_MUST_BE_REMOVED"
    }
}
