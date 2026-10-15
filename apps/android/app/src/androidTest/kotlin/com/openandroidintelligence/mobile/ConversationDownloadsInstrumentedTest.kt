package com.openandroidintelligence.mobile

import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openandroidintelligence.conversation.ports.AttachmentContentSource
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.mobile.conversations.AndroidAttachmentDownloads
import com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** 使用真实 AndroidKeyStore、分块原件和系统 MediaStore，不替换文件提供者。 */
@RunWith(AndroidJUnit4::class)
class ConversationDownloadsInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun scope() = ConversationScope("instrumented_" + UUID.randomUUID(), "https://fixture.example", "acct_instrumented", "install_instrumented")
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun privateOriginalReopensOfflineAndPublishesOrdinaryBytes(): Unit = runBlocking {
        val scope = scope()
        val bytes = "Android 真实系统下载与离线恢复验收".toByteArray()
        val media = EncryptedHistoryMediaCache(context, scope)
        media.archiveSubmitted("conv_device", "att_device", "离线附件.txt", "text/plain",
            AttachmentContentSource { bytes.inputStream() }, bytes.size.toLong(), sha(bytes))
        val reopened = EncryptedHistoryMediaCache(context, scope)
        assertArrayEquals(bytes, reopened.openOriginal("conv_device", "att_device").use { it.readBytes() })
        val result = AndroidAttachmentDownloads(context, reopened).download("conv_device", "att_device")
        val uri = Uri.parse(result.uri)
        assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertPublished(uri)
        AndroidAttachmentDownloads(context, reopened).wipePrivateDownloadJournal()
        reopened.clearMedia()
        assertArrayEquals("清理私有账号内容不能修改已下载文件", bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    }

    @Test fun identicalNamesCreateSeparateFilesWithoutOverwriting(): Unit = runBlocking {
        val media = EncryptedHistoryMediaCache(context, scope())
        val first = "第一份普通文件".toByteArray()
        val second = "第二份普通文件".toByteArray()
        media.archiveSubmitted("conv_names", "att_first", "同名报告.txt", "text/plain", AttachmentContentSource { first.inputStream() }, first.size.toLong(), sha(first))
        media.archiveSubmitted("conv_names", "att_second", "同名报告.txt", "text/plain", AttachmentContentSource { second.inputStream() }, second.size.toLong(), sha(second))
        val download = AndroidAttachmentDownloads(context, media)
        val firstDownload = async(Dispatchers.IO) { download.download("conv_names", "att_first") }
        val secondDownload = async(Dispatchers.IO) { download.download("conv_names", "att_second") }
        val a = firstDownload.await()
        val b = secondDownload.await()
        assertNotEquals(a.uri, b.uri)
        assertArrayEquals(first, context.contentResolver.openInputStream(Uri.parse(a.uri))!!.use { it.readBytes() })
        assertArrayEquals(second, context.contentResolver.openInputStream(Uri.parse(b.uri))!!.use { it.readBytes() })
        // MediaStore 保留用户提供的显示名，但对物理文件名避让；实际文件与内容都必须独立。
        assertNotEquals(storedPath(Uri.parse(a.uri)), storedPath(Uri.parse(b.uri)))
        assertPublished(Uri.parse(a.uri)); assertPublished(Uri.parse(b.uri))
    }

    @Test fun originalBeyondTheLegacyResponseLimitIsStoredAndExportedAsAStream(): Unit = runBlocking {
        val size = 33L * 1024 * 1024
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(64 * 1024) { 7 }
        var remaining = size
        while (remaining > 0) {
            val count = minOf(remaining, block.size.toLong()).toInt()
            digest.update(block, 0, count); remaining -= count
        }
        val expected = digest.digest().joinToString("") { "%02x".format(it) }
        val media = EncryptedHistoryMediaCache(context, scope())
        media.archiveSubmitted("conv_large", "att_large", "大附件.bin", "application/octet-stream", AttachmentContentSource {
            object : InputStream() {
                private var left = size
                override fun read(): Int = if (left-- > 0) 7 else -1
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (left <= 0) return -1
                    val count = minOf(left, length.toLong()).toInt()
                    buffer.fill(7, offset, offset + count); left -= count; return count
                }
            }
        }, size, expected)
        val result = AndroidAttachmentDownloads(context, media).download("conv_large", "att_large")
        val actual = MessageDigest.getInstance("SHA-256")
        var total = 0L
        context.contentResolver.openInputStream(Uri.parse(result.uri))!!.use { input ->
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                actual.update(block, 0, count); total += count
            }
        }
        assertEquals(size, total)
        assertEquals(expected, actual.digest().joinToString("") { "%02x".format(it) })
        assertPublished(Uri.parse(result.uri))
    }

    private fun assertPublished(uri: Uri) {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use { row ->
            assertTrue(row.moveToFirst())
            assertEquals(0, row.getInt(0))
            assertEquals(AndroidAttachmentDownloads.DOWNLOAD_DIRECTORY, row.getString(1))
        }
    }

    @Suppress("DEPRECATION")
    private fun storedPath(uri: Uri): String = context.contentResolver.query(uri,
        arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use { row ->
        assertTrue(row.moveToFirst())
        row.getString(0).also { assertNotNull(it) }
    }
}
