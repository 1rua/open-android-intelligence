package com.openandroidintelligence.mobile

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror
import com.openandroidintelligence.mobile.conversations.SqliteConversationRecords
import com.openandroidintelligence.mobile.plugins.EncryptedDocuments
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import javax.crypto.spec.SecretKeySpec
import javax.crypto.SecretKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SqliteConversationMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val key = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 31 }, "AES")
        override fun delete() = Unit
    }
    private val scope = ConversationScope("profile_sqlite_migration", "https://migration.example", "acct_migration", "install_migration")
    private fun legacy() = EncryptedDocuments(context, "conversation-mirror-v1:" + Json.canonical(Json.of(
        listOf(scope.profileId, scope.gatewayId, scope.accountId, scope.installId))), key)

    @Test fun legacyDocumentsMigrateWithoutLosingEitherSideDraftOrFrozenIdentity() {
        legacy().write("timeline:conv_old", Json.canonical(Json.of(mapOf("revision" to 4, "deletedMessageIds" to emptyList<String>(),
            "messages" to listOf(
                mapOf("id" to "msg_user", "sender" to "user", "parts" to listOf(mapOf("type" to "text", "text" to "迁移的秘密问题")), "timestamp" to 1, "state" to "CONFIRMED"),
                mapOf("id" to "msg_assistant", "sender" to "assistant", "parts" to listOf(mapOf("type" to "text", "text" to "迁移的秘密回答")), "timestamp" to 2, "state" to "CONFIRMED"),
            )))).toByteArray())
        val mirror = EncryptedConversationMirror(context, scope, key)
        assertEquals(listOf("user", "assistant"), mirror.timeline("conv_old")!!.messages.map { it.sender })
        mirror.save(WorkbenchCheckpoint(emptyList(), "conv_old", "迁移会话", emptyList(), emptyMap(), "迁移的秘密草稿", 1,
            emptyList(), SavedSubmission("待确认发送", emptyList(), 1, "conv_old", "cm_frozen"), emptySet(), emptySet()))
        val reopened = EncryptedConversationMirror(context, scope, key)
        assertEquals("迁移的秘密草稿", reopened.load()!!.draft)
        assertEquals("cm_frozen", reopened.load()!!.submission!!.clientMessageId)
        assertEquals(2, reopened.timeline("conv_old")!!.messages.size)
        assertTrue("迁移必须创建 SQLite 镜像", File(context.noBackupFilesDir, "conversation-mirror").walkTopDown().any { it.name == "mirror-v1.db" })
        File(context.noBackupFilesDir, "conversation-mirror").walkTopDown().filter { it.isFile }.forEach {
            val bytes = it.readBytes().decodeToString()
            assertFalse(bytes.contains("迁移的秘密问题")); assertFalse(bytes.contains("迁移的秘密回答")); assertFalse(bytes.contains("迁移的秘密草稿"))
        }
    }

    @Test fun longConversationExceedingTheLegacyDocumentLimitStillReopens() {
        val mirror = EncryptedConversationMirror(context, scope, key)
        val body = "这是需要跨重启保留的长消息。".repeat(4096)
        val messages = (0 until 100).map { TimelineMessage("msg_$it", if (it % 2 == 0) "user" else "assistant",
            listOf(MessagePart.Text(body)), it.toLong(), conversationId = ConversationId("conv_long")) }
        mirror.saveTimeline("conv_long", TimelinePage(messages, null, 8))
        assertEquals(100, EncryptedConversationMirror(context, scope, key).timeline("conv_long")!!.messages.size)
    }

    @Test fun aSingleMessageLargerThanThePlatformCursorWindowMigratesAndReopensWithoutTruncation() = runBlocking {
        val body="a".repeat(3 * 1024 * 1024)
        legacy().write("timeline:conv_large_legacy",Json.canonical(Json.of(mapOf("revision" to 3,
            "messages" to listOf(mapOf("id" to "msg_large_legacy","sender" to "assistant",
                "parts" to listOf(mapOf("type" to "text","text" to body)),"timestamp" to 1,"state" to "CONFIRMED"))))).toByteArray())
        val mirror=EncryptedConversationMirror(context,scope,key)
        val recent=TimelineMessage("msg_large_recent","user",listOf(MessagePart.Text(body)),2,
            conversationId=ConversationId("conv_large_recent"))
        mirror.saveTimeline("conv_large_recent",TimelinePage(listOf(recent),null,4))
        mirror.closeStore()
        val reopened=EncryptedConversationMirror(context,scope,key)
        try {
            assertEquals(body,(reopened.timeline("conv_large_legacy")!!.messages.single().parts.single() as MessagePart.Text).value)
            assertEquals(recent,reopened.timeline("conv_large_recent")!!.messages.single())
        } finally { reopened.closeStore() }
    }

    @Test fun unopenedHistoryStaysEncryptedAndTheBoundedCacheKeepsOnlyRecentlyReadPlaintext() {
        val countedKey=CountingKey()
        val keys=object:AesGcmKeyProvider { override fun getOrCreate()=countedKey; override fun delete()=Unit }
        val first=Json.canonical(Json.of(mapOf("text" to "甲".repeat(700)))).toByteArray()
        val second=Json.canonical(Json.of(mapOf("text" to "乙".repeat(700)))).toByteArray()
        val store=records(keys,4096)
        store.write("test-entry:first",first)
        store.write("test-entry:second",second)
        store.close()
        countedKey.encodedReads=0
        val reopened=records(keys,4096)
        try {
            assertEquals("重建存储不能提前解密任何未读取的历史",0,countedKey.encodedReads)
            assertArrayEquals(first,reopened.read("test-entry:first"))
            val afterColdRead=countedKey.encodedReads
            assertTrue(afterColdRead>0)
            assertArrayEquals(first,reopened.read("test-entry:first"))
            assertEquals("连续读取同一记录应命中明文缓存",afterColdRead,countedKey.encodedReads)
            assertArrayEquals(second,reopened.read("test-entry:second"))
            val afterOtherRead=countedKey.encodedReads
            assertArrayEquals(first,reopened.read("test-entry:first"))
            assertTrue("预算不足时旧明文应被淘汰并按需重新认证",countedKey.encodedReads>afterOtherRead)
            reopened.close()
            val coldAgain=records(keys,4096)
            try {
                assertArrayEquals(first,coldAgain.read("test-entry:first"))
                assertArrayEquals(second,coldAgain.read("test-entry:second"))
            } finally { coldAgain.close() }
        } finally { reopened.close() }
    }

    @Test fun cachedRecordsObserveAnotherConnectionsCommitAndNeverKeepAnUncommittedValue() {
        val first=records(key,4096)
        val second=records(key,4096)
        val before="{\"value\":\"提交前\"}".toByteArray()
        val after="{\"value\":\"外部新提交\"}".toByteArray()
        try {
            first.write("cursor",before)
            assertArrayEquals(before,second.read("cursor"))
            first.write("cursor",after)
            assertArrayEquals("热缓存必须看到另一个连接的新提交",after,second.read("cursor"))
            assertTrue(runCatching { second.transaction {
                second.write("cursor",before)
                assertArrayEquals(before,second.read("cursor"))
                throw java.io.IOException("TEST_TRANSACTION_ROLLBACK")
            } }.isFailure)
            assertArrayEquals("事务失败后必须重新以SQLite已提交值为准",after,second.read("cursor"))
            first.delete("cursor")
            assertNull("其他连接的删除也必须失效热缓存",second.read("cursor"))
        } finally { first.close(); second.close() }
    }

    @Test fun eventMessageRevisionAndCursorRollBackTogetherAndCanBeReplayed() = runBlocking {
        val mirror=EncryptedConversationMirror(context,scope,key)
        mirror.saveCursor(scope.accountId,"evt_before")
        database().use { it.execSQL("CREATE TRIGGER fail_cursor BEFORE INSERT ON records WHEN NEW.resource_type='cursor' BEGIN SELECT RAISE(ABORT,'TEST_CURSOR_COMMIT_FAILURE'); END") }
        val event=VerifiedConversationEvent.TimelineUpsert("evt_reply",2,4,TimelineMessage("msg_reply","assistant",
            listOf(MessagePart.Text("必须与游标一起提交的回复")),2,conversationId=ConversationId("conv_atomic")))
        assertTrue(runCatching { mirror.applyAndCommit(event) }.exceptionOrNull() is java.io.IOException)
        val failed=EncryptedConversationMirror(context,scope,key)
        assertEquals("evt_before",failed.loadCursor(scope.accountId))
        assertNull("游标提交失败必须回滚已写消息",failed.timeline("conv_atomic"))
        database().use { it.execSQL("DROP TRIGGER fail_cursor") }
        failed.applyAndCommit(event)
        val reopened=EncryptedConversationMirror(context,scope,key)
        assertEquals("evt_reply",reopened.loadCursor(scope.accountId))
        assertEquals("msg_reply",reopened.timeline("conv_atomic")!!.messages.single().id)
        assertEquals(4L,reopened.timeline("conv_atomic")!!.messages.single().contentRevision)
        assertTrue(runCatching { reopened.saveCursor("other_account","evt_bad") }.isFailure)
    }

    @Test fun authenticatedPayloadCannotBeCopiedToAnotherMessageRecord() {
        val mirror=EncryptedConversationMirror(context,scope,key)
        mirror.saveTimeline("conv_bound",TimelinePage(listOf(
            TimelineMessage("msg_one","user",listOf(MessagePart.Text("第一条")),1),
            TimelineMessage("msg_two","assistant",listOf(MessagePart.Text("第二条")),2)),null))
        database().use { db ->
            val rows=db.rawQuery("SELECT row_id,payload FROM records WHERE resource_type='message-record' ORDER BY row_id",null).use { result ->
                buildList { while(result.moveToNext()) add(result.getString(0) to result.getBlob(1)) }
            }
            assertEquals(2,rows.size)
            val values=android.content.ContentValues().apply { put("payload",rows.first().second) }
            db.update("records",values,"row_id=?",arrayOf(rows.last().first))
        }
        val index=records(key,4096)
        try {
            assertEquals("枚举线程元索引不能读取未打开的消息正文",setOf("timeline:conv_bound"),index.keys())
        } finally { index.close() }
        val reopened=EncryptedConversationMirror(context,scope,key)
        try {
            assertTrue("目标消息读取时必须被身份AAD拒绝",runCatching { reopened.timeline("conv_bound") }.exceptionOrNull()?.message == "MIRROR_CORRUPTED")
        } finally { runBlocking { reopened.closeStore() } }
    }

    @Test fun everyAccountScopeIdentityAxisPartitionsTheLocalHistory() {
        val mirror=EncryptedConversationMirror(context,scope,key)
        mirror.saveTimeline("conv_same",TimelinePage(listOf(TimelineMessage("msg_one","assistant",listOf(MessagePart.Text("隔离正文")),1)),null))
        listOf(scope.copy(accountId="another_account"),scope.copy(gatewayId="https://another.example"),
            scope.copy(installId="another_installation"),scope.copy(profileId="another_profile")).forEach { other ->
            assertNull(EncryptedConversationMirror(context,other,key).timeline("conv_same"))
        }
    }

    @Test fun aClosedMirrorCannotRecreateRecordsAfterAnotherInstanceRemovesItsScope() = runBlocking {
        val old=EncryptedConversationMirror(context,scope,key)
        old.saveTimeline("conv_removed",TimelinePage(listOf(TimelineMessage("msg_old","assistant",listOf(MessagePart.Text("清理前")),1)),null))
        old.closeStore()
        EncryptedConversationMirror(context,scope,key).wipe()
        assertTrue(runCatching { old.saveCursor(scope.accountId,"evt_late") }.exceptionOrNull()?.message == "MIRROR_CLOSED")
        assertTrue(runCatching { old.saveTimeline("conv_removed",TimelinePage(listOf(TimelineMessage("msg_late","assistant",listOf(MessagePart.Text("迟到写入")),2)),null)) }.isFailure)
        assertNull(EncryptedConversationMirror(context,scope,key).timeline("conv_removed"))
    }

    private fun database():SQLiteDatabase {
        val documentScope="conversation-mirror-v1:"+Json.canonical(Json.of(listOf(scope.profileId,scope.gatewayId,scope.accountId,scope.installId)))
        val hash=MessageDigest.getInstance("SHA-256").digest(documentScope.toByteArray()).joinToString("") { "%02x".format(it) }
        return SQLiteDatabase.openDatabase(File(context.noBackupFilesDir,"conversation-mirror/$hash/mirror-v1.db").path,null,SQLiteDatabase.OPEN_READWRITE)
    }

    private fun records(keys:AesGcmKeyProvider,cacheBytes:Int):SqliteConversationRecords {
        val documentScope="conversation-mirror-v1:"+Json.canonical(Json.of(listOf(scope.profileId,scope.gatewayId,scope.accountId,scope.installId)))
        return SqliteConversationRecords(context,SqliteConversationRecords.digest(documentScope),keys,
            EncryptedDocuments(context,documentScope,keys),plaintextCacheBytes=cacheBytes)
    }

    private class CountingKey:SecretKey {
        var encodedReads=0
        override fun getAlgorithm()="AES"
        override fun getFormat()="RAW"
        override fun getEncoded():ByteArray { encodedReads++; return ByteArray(32) { 31 } }
    }
}
