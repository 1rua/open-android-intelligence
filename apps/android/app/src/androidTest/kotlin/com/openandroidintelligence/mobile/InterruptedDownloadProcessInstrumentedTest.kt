package com.openandroidintelligence.mobile

import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openandroidintelligence.conversation.ports.AttachmentContentSource
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.HistoricalMediaPort
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.mobile.conversations.AndroidAttachmentDownloads
import com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 由验证脚本分两次启动，中间真实 force-stop；普通仪器套件不启动等待杀进程的探针。 */
@RunWith(AndroidJUnit4::class)
class InterruptedDownloadProcessInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun runId(): String {
        val value = InstrumentationRegistry.getArguments().getString("processRunId")
        assumeTrue("此探针需要脚本提供进程中断运行身份", value != null)
        return requireNotNull(value).also { require(it.matches(Regex("[A-Za-z0-9_]+"))) }
    }
    private fun scope(id: String) = ConversationScope("process_download_$id", "https://fixture.example", "acct_process", "install_process")
    private fun original() = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun signal(id: String) = File(context.filesDir, "download-process-$id.json")

    @Test fun exportWaitsForExternalProcessTermination(): Unit = runBlocking {
        val id = runId()
        val source = original()
        val media = EncryptedHistoryMediaCache(context, scope(id))
        val filename = "进程恢复-$id.bin"
        media.archiveSubmitted("conv_process", "att_process", filename, "application/octet-stream",
            AttachmentContentSource { source.inputStream() }, source.size.toLong(), sha(source))
        val paused = object : HistoricalMediaPort by media {
            override suspend fun openOriginal(conversationId: String, attachmentId: String): InputStream {
                val originalStream = media.openOriginal(conversationId, attachmentId)
                return object : FilterInputStream(originalStream) {
                    private var reads = 0
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (reads++ == 1) {
                            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                            // 与生产查询一致：显式包含 pending/trashed，并取最新一行，避免普通查询默认过滤掉未发布条目。
                            val options = android.os.Bundle().apply {
                                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME}=?")
                                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(filename))
                                putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns._ID} DESC")
                            }
                            context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING),
                                options, null)!!.use { row ->
                                assertTrue(row.moveToFirst())
                                assertEquals(1, row.getInt(1))
                                val uri = android.content.ContentUris.withAppendedId(collection, row.getLong(0))
                                signal(id).writeText(Json.canonical(Json.of(mapOf("stage" to "waiting-for-kill",
                                    "uri" to uri.toString(), "pid" to android.os.Process.myPid(), "sha256" to sha(source)))))
                            }
                            check(CountDownLatch(1).await(30, TimeUnit.SECONDS)) { "PROCESS_TERMINATION_NOT_PERFORMED" }
                        }
                        return super.read(buffer, offset, length)
                    }
                }
            }
        }
        AndroidAttachmentDownloads(context, paused, scope(id)).download("conv_process", "att_process")
        fail("外部必须在文件未发布时终止此进程，不能返回下载成功")
    }

    @Test fun nextProcessRecoversTheRealPendingDownload(): Unit = runBlocking {
        val id = runId()
        val saved = JsonFields.obj(Json.parse(signal(id).readText())) ?: error("PROCESS_PROBE_MISSING")
        assertEquals("waiting-for-kill", JsonFields.string(saved, "stage"))
        val killedPid = JsonFields.int(saved, "pid") ?: error("PROCESS_PROBE_MISSING")
        assertNotEquals("恢复必须发生在新的真实进程", killedPid, android.os.Process.myPid())
        val uri = Uri.parse(JsonFields.string(saved, "uri") ?: error("PROCESS_PROBE_MISSING"))
        val media = EncryptedHistoryMediaCache(context, scope(id))
        assertEquals(1, AndroidAttachmentDownloads(context, media, scope(id)).recoverPendingDownloads())
        val restored = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertArrayEquals(original(), restored)
        assertEquals(JsonFields.string(saved, "sha256"), sha(restored))
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use { row ->
            assertTrue(row.moveToFirst()); assertEquals(0, row.getInt(0))
            assertEquals(AndroidAttachmentDownloads.DOWNLOAD_DIRECTORY, row.getString(1))
        }
        media.clearMedia()
        assertArrayEquals("恢复后清理私有原件仍不能修改公开文件", original(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        signal(id).writeText(Json.canonical(Json.of(mapOf("stage" to "recovered-and-verified", "uri" to uri.toString(),
            "killedPid" to killedPid, "recoveryPid" to android.os.Process.myPid(), "sha256" to sha(restored)))))
    }
}
