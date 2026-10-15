package com.openandroidintelligence.mobile

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.content.ContentUris
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.events.EventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror
import com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache
import com.openandroidintelligence.mobile.conversations.MirroredConversationRepository
import com.openandroidintelligence.mobile.conversations.AndroidAttachmentDownloads
import com.openandroidintelligence.mobile.plugins.EncryptedDocuments
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.File
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RevisionedMediaRecoveryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val scope = ConversationScope("media_revisions", "https://gateway.example", "account", "install")
    private val key = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 54 }, "AES")
        override fun delete() = Unit
    }
    private val original = ByteArray(180_003) { (it % 251).toByte() }
    private val message = TimelineMessage("message", "assistant", listOf(MessagePart.Attachment(
        AttachmentDraftId("attachment"), "原始附件.bin", "application/octet-stream")), 1,
        conversationId = ConversationId("conversation"))

    @Test fun higherTrustedContentRevisionCanSaveTheSameAttachmentAfterAnOrdinaryTombstone() = runBlocking {
        val mirror = mirror()
        val cache = cache()
        deliver(mirror, cache, upsert(1))
        cache.ensureLocal("conversation", "attachment")
        deliver(mirror, cache, tombstone(2))
        assertNull(cache.localMetadata("conversation", "attachment"))

        deliver(mirror, cache, upsert(3))

        assertEquals("正文恢复后附件不能仍被旧删除标记永久拦截", original.size.toLong(),
            cache.ensureLocal("conversation", "attachment").sizeBytes)
        assertArrayEquals(original, cache.openOriginal("conversation", "attachment").use { it.readBytes() })
        mirror.closeStore()
        val reopened = mirror()
        assertEquals(3L, reopened.timeline("conversation")!!.messages.single().contentRevision)
        reopened.closeStore()
    }

    @Test fun aStreamOpenedBeforeDeleteAndRestoreCannotContinueAsTheNewOriginalEvenWhenBytesMatch() = runBlocking {
        val mirror = mirror()
        val cache = cache()
        deliver(mirror, cache, upsert(1))
        cache.ensureLocal("conversation", "attachment")
        val oldStream = cache.openOriginal("conversation", "attachment")
        try {
            assertTrue(oldStream.read() >= 0)
            deliver(mirror, cache, tombstone(2))
            deliver(mirror, cache, upsert(3))

            assertNotNull("已打开的旧原件流必须检查代数，不能跨删除恢复继续导出",
                runCatching { oldStream.readBytes() }.exceptionOrNull())
        } finally { oldStream.close(); mirror.closeStore() }
    }

    @Test fun aCommittedDeleteIsReplayedAfterColdRestartBeforeTheNextEventArrives() = runBlocking {
        val mirror = mirror()
        val cache = cache()
        deliver(mirror, cache, upsert(1))
        cache.ensureLocal("conversation", "attachment")
        // 真实提交消息和游标后模拟进程中断，尚未调用任何物理媒体清理。
        mirror.applyAndCommit(tombstone(2))
        assertEquals("evt_2", mirror.loadCursor(scope.accountId))
        mirror.closeStore()

        val reopened = mirror()
        val reopenedCache = cache()
        MirroredConversationRepository(Host(emptyList()), reopened, reopenedCache).observeEvents(scope).toList()

        assertNull("游标已提交的删除也必须在重启后补做，不能等待服务器重放旧事件",
            reopenedCache.localMetadata("conversation", "attachment"))
        assertNotNull(runCatching { reopenedCache.openOriginal("conversation", "attachment") }.exceptionOrNull())
        reopened.closeStore()
    }

    @Test fun anUnrevisionedSnapshotDeleteStaysPermanentDespiteALaterRevisionedBody() = runBlocking {
        val mirror = mirror()
        val cache = cache()
        deliver(mirror, cache, upsert(1))
        cache.ensureLocal("conversation", "attachment")
        mirror.commitTimeline("conversation", TimelinePage(emptyList(), null, deletedMessageIds = setOf("message")), cache)
        deliver(mirror, cache, upsert(3))
        assertNull(cache.localMetadata("conversation", "attachment"))
        assertTrue(mirror.timeline("conversation")!!.messages.isEmpty())
        assertNotNull(runCatching { cache.ensureLocal("conversation", "attachment") }.exceptionOrNull())
        mirror.closeStore()
    }

    @Test fun aLateOldFetchCannotCommitAfterDeleteAndHigherRevisionRestore() = runBlocking {
        val entered=CompletableDeferred<Unit>()
        val resume=CompletableDeferred<Unit>()
        var calls=0
        val cache=cache {
            if (calls++ == 0) { entered.complete(Unit); resume.await() }
        }
        val mirror=mirror()
        deliver(mirror,cache,upsert(1))
        val old=async(Dispatchers.Default) { runCatching { cache.ensureLocal("conversation","attachment") }.exceptionOrNull() }
        entered.await()
        deliver(mirror,cache,tombstone(2))
        deliver(mirror,cache,upsert(3))
        resume.complete(Unit)
        assertNotNull("保存开始前捕获的旧代数不能借恢复后的许可提交",old.await())
        assertNull(cache.localMetadata("conversation","attachment"))
        assertArrayEquals(original,cache.ensureLocal("conversation","attachment").let {
            cache.openOriginal("conversation","attachment").use { input -> input.readBytes() }
        })
        mirror.closeStore()
    }

    @Test fun aNewerActiveBodyKeepsItsInFlightOriginalWhenTheAttachmentIdentityDoesNotChange() = runBlocking {
        val entered=CompletableDeferred<Unit>()
        val resume=CompletableDeferred<Unit>()
        var metadataCalls=0
        val cache=cache { metadataCalls++; entered.complete(Unit); resume.await() }
        val mirror=mirror()
        deliver(mirror,cache,upsert(1))
        val saving=async(Dispatchers.Default) { runCatching { cache.ensureLocal("conversation","attachment") } }
        entered.await()
        deliver(mirror,cache,upsert(3))
        resume.complete(Unit)

        assertNull("未删除且身份不变的正文更新不能把唯一保存任务作废后悬空",saving.await().exceptionOrNull())
        assertEquals(1,metadataCalls)
        assertEquals(3L,mirror.timeline("conversation")!!.messages.single().contentRevision)
        assertArrayEquals(original,cache.openOriginal("conversation","attachment").use { it.readBytes() })
        mirror.closeStore()
    }

    @Test fun mediaTransitionWriteFailureRollsBackTheBodyAndCursorInTheSameTransaction() = runBlocking {
        val mirror=mirror()
        val cache=cache()
        deliver(mirror,cache,upsert(1))
        val documentScope="conversation-mirror-v1:"+scopeKey()
        val database=File(context.noBackupFilesDir,"conversation-mirror/${sha(documentScope)}/mirror-v1.db")
        SQLiteDatabase.openDatabase(database.path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER fail_media_transition BEFORE INSERT ON records WHEN NEW.resource_type='media-transition' BEGIN SELECT RAISE(ABORT,'MEDIA_TRANSITION_TEST_FAILURE'); END")
        }
        assertNotNull(runCatching { mirror.applyAndCommit(tombstone(2)) }.exceptionOrNull())
        assertEquals("evt_1",mirror.loadCursor(scope.accountId))
        assertEquals(listOf("message"),mirror.timeline("conversation")!!.messages.map { it.id })
        SQLiteDatabase.openDatabase(database.path,null,SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DROP TRIGGER fail_media_transition") }
        mirror.applyAndCommit(tombstone(2))
        mirror.applyPendingMediaTransitions(cache)
        assertEquals("evt_2",mirror.loadCursor(scope.accountId))
        assertTrue(mirror.timeline("conversation")!!.messages.isEmpty())
        mirror.closeStore()
    }

    @Test fun aRestoreAppliedBeforeOutboxAcknowledgementIsIdempotentAfterColdRestart() = runBlocking {
        val mirror=mirror()
        val cache=cache()
        deliver(mirror,cache,upsert(1))
        cache.ensureLocal("conversation","attachment")
        deliver(mirror,cache,tombstone(2))
        mirror.applyAndCommit(upsert(3))
        val database=File(context.noBackupFilesDir,"conversation-mirror/${sha("conversation-mirror-v1:"+scopeKey())}/mirror-v1.db")
        SQLiteDatabase.openDatabase(database.path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER fail_media_ack BEFORE UPDATE ON records WHEN OLD.resource_type='media-transition' AND NEW.tombstone=1 BEGIN SELECT RAISE(ABORT,'MEDIA_ACK_TEST_FAILURE'); END")
        }
        assertNotNull(runCatching { mirror.applyPendingMediaTransitions(cache) }.exceptionOrNull())
        val index=EncryptedDocuments(context,"history-media-index-v2:"+scopeKey(),key)
        val entry=sha(Json.canonical(Json.of(listOf("conversation","attachment"))))
        fun storedEpoch()=JsonFields.long(JsonFields.obj(Json.parse(index.read("version:$entry")!!.decodeToString())),"epoch")
        val appliedEpoch=storedEpoch()
        assertTrue("实际删除和恢复必须推进原件代数",appliedEpoch != null && appliedEpoch > 0L)
        SQLiteDatabase.openDatabase(database.path,null,SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DROP TRIGGER fail_media_ack") }
        mirror.closeStore()

        val reopened=mirror()
        val reopenedCache=cache()
        reopened.applyPendingMediaTransitions(reopenedCache)
        assertEquals("仅待办确认失败不能在重放时额外换代",appliedEpoch,storedEpoch())
        assertArrayEquals(original,reopenedCache.ensureLocal("conversation","attachment").let {
            reopenedCache.openOriginal("conversation","attachment").use { input -> input.readBytes() }
        })
        reopened.closeStore()
    }

    @Test fun aFailedPhysicalTransitionBlocksPrivateMediaButKeepsTheBodyAndCanRecoverAfterRestart() = runBlocking {
        val provider=AndroidAttachmentDownloadsTest.DownloadProvider()
        provider.attachInfo(context,ProviderInfo().apply { authority="media" })
        ShadowContentResolver.registerProviderInternal("media",provider)
        val mirror=mirror()
        val cache=cache()
        deliver(mirror,cache,upsert(1))
        cache.ensureLocal("conversation","attachment")
        val published=AndroidAttachmentDownloads(context,cache,scope,key).download("conversation","attachment")
        val publicRow=provider.rows.getValue(ContentUris.parseId(Uri.parse(published.uri)))
        mirror.applyAndCommit(tombstone(2))
        mirror.applyAndCommit(upsert(3))
        val entry=sha(Json.canonical(Json.of(listOf("conversation","attachment"))))
        val indexDirectory=File(context.noBackupFilesDir,"private-documents/${sha("history-media-index-v2:"+scopeKey())}")
        val blocked=File(indexDirectory,sha("version:$entry")+".tmp")
        assertTrue(blocked.mkdirs())
        assertNotNull(runCatching { mirror.applyPendingMediaTransitions(cache) }.exceptionOrNull())
        assertEquals("正文和附件身份仍应可读",message.parts,mirror.timeline("conversation")!!.messages.single().parts)
        assertEquals("MEDIA_TRANSITIONS_PENDING",runCatching { cache.localMetadata("conversation","attachment") }.exceptionOrNull()?.message)
        assertEquals("MEDIA_TRANSITIONS_PENDING",runCatching { cache.originalEpoch("conversation","attachment") }.exceptionOrNull()?.message)
        assertEquals("MEDIA_TRANSITIONS_PENDING",runCatching { cache.preview("conversation","attachment") }.exceptionOrNull()?.message)
        assertEquals("MEDIA_TRANSITIONS_PENDING",runCatching {
            AndroidAttachmentDownloads(context,cache,scope,key).download("conversation","attachment")
        }.exceptionOrNull()?.message)
        assertEquals("恢复失败不能开始导出尚未应用删除的私有字节",1,provider.rows.size)
        assertEquals(0,publicRow.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertFalse(publicRow.values.getAsInteger(MediaStore.MediaColumns.IS_TRASHED)==1)
        assertArrayEquals(original,publicRow.file.readBytes())
        assertTrue(blocked.renameTo(File(context.cacheDir,"preserved-media-transition-block")))
        mirror.closeStore()

        val reopened=mirror()
        val reopenedCache=cache()
        reopened.applyPendingMediaTransitions(reopenedCache)
        assertArrayEquals(original,reopenedCache.ensureLocal("conversation","attachment").let {
            reopenedCache.openOriginal("conversation","attachment").use { input -> input.readBytes() }
        })
        reopened.applyPendingMediaTransitions(reopenedCache)
        reopened.closeStore()
    }

    private fun mirror() = EncryptedConversationMirror(context, scope, key, Dispatchers.Unconfined)
    private fun upsert(revision: Long) = VerifiedConversationEvent.TimelineUpsert("evt_$revision", revision, revision,
        message.copy(contentRevision = revision))
    private fun tombstone(revision: Long) = VerifiedConversationEvent.TimelineTombstoned("evt_$revision", revision,
        message.id, revision, message.conversationId)
    private suspend fun deliver(mirror: EncryptedConversationMirror, cache: EncryptedHistoryMediaCache,
        event: VerifiedConversationEvent) = MirroredConversationRepository(Host(listOf(event)), mirror, cache).observeEvents(scope).toList()
    private fun scopeKey()=Json.canonical(Json.of(listOf(scope.profileId,scope.gatewayId,scope.accountId,scope.installId)))
    private fun sha(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun cache(beforeMetadata:suspend ()->Unit = {}): EncryptedHistoryMediaCache {
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                if (request.target.endsWith("/metadata?format=stream-v1")) beforeMetadata()
                val data = if (request.target.endsWith("/cache-grant?format=stream-v1")) mapOf("grantId" to "G".repeat(43))
                else mapOf("metadata" to mapOf("conversationId" to "conversation", "attachmentId" to "attachment",
                    "filename" to "原始附件.bin", "mediaType" to "application/octet-stream", "sizeBytes" to original.size,
                    "sha256" to "sha256:" + MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) },
                    "remoteAvailable" to true))
                return WireResponse(200, emptyList(), Json.canonical(Json.of(mapOf("data" to data))).toByteArray())
            }
            override suspend fun <T> consumeResponse(request: WireRequest, consume: suspend (Int, List<RawHeader>, InputStream) -> T): T =
                ByteArrayInputStream(original).use { consume(200, listOf(RawHeader("Content-Type", "application/octet-stream"),
                    RawHeader("X-Open-Android-Intelligence-Media-Format", "stream-v1")), it) }
            override fun eventStream(request: WireRequest): Flow<ByteArray> = emptyFlow()
        }
        val cursors = object : EventCursorStore {
            override fun load(accountId: String): String? = null
            override fun save(accountId: String, cursor: String) = Unit
            override fun clear(accountId: String) = Unit
        }
        val http = GatewayHttpClient(GatewayProfile(scope.accountId, "device", "session", scope.gatewayId, accessToken = "test"),
            transport, { ByteArray(64) }, cursors)
        return EncryptedHistoryMediaCache(context, scope, http, key)
    }

    private class Host(private val events: List<VerifiedConversationEvent>) : ConversationRepository {
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(emptyList(), null)
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation = error("测试不创建会话")
        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance = error("测试不发送消息")
        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance = error("测试不发送批次")
        override fun observeEvents(scope: ConversationScope) = events.asFlow()
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
