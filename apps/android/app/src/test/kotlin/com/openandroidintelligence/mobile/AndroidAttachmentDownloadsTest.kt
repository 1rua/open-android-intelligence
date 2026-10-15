package com.openandroidintelligence.mobile

import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.mobile.conversations.AndroidAttachmentDownloads
import com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache
import com.openandroidintelligence.mobile.plugins.EncryptedDocuments
import com.openandroidintelligence.gateway.schema.Json
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** 通过真实 ContentResolver/文件描述符验证写入边界，媒体提供者夹具代替系统索引。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidAttachmentDownloadsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val key = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 27 }, "AES")
        override fun delete() = Unit
    }
    private val scope = ConversationScope("download", "https://gateway.example", "account", "installation")
    private val original = "actual private original bytes".toByteArray()
    private lateinit var provider: DownloadProvider

    @Before fun registerProvider() {
        provider = DownloadProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    @Test fun explicitDownloadPublishesOrdinaryBytesWithSystemResolvedNamesWithoutReplacingAnExistingFile() = runBlocking {
        val media = originalStore()
        val downloads = AndroidAttachmentDownloads(context, media, scope, key)
        val first = downloads.download("conv", "att")
        val second = downloads.download("conv", "att")
        assertEquals("original.pdf", first.filename)
        assertEquals("original (1).pdf", second.filename)
        assertNotEquals(first.uri, second.uri)
        listOf(first, second).forEach { result ->
            val uri = Uri.parse(result.uri)
            assertArrayEquals(original, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            val values = provider.rows.getValue(ContentUris.parseId(uri)).values
            assertEquals(0, values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
            assertEquals(AndroidAttachmentDownloads.DOWNLOAD_DIRECTORY, values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        }
        assertTrue(media.isRetained("att"))
        media.clearMedia()
    }

    @Test fun interruptedCopyRemainsHiddenAndRestartTruncatesAndCompletesTheSameDownloadRow() = runBlocking {
        val media = originalStore()
        val interrupted = failingSource(media, cancellation = false, processDeath = true)
        val downloads = AndroidAttachmentDownloads(context, interrupted, scope, key)
        assertTrue(runCatching { downloads.download("conv", "att") }.exceptionOrNull() is SimulatedProcessDeath)
        val row = provider.rows.values.single()
        assertEquals(1, row.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(7L, row.file.length())
        val recovered = AndroidAttachmentDownloads(context, media, scope, key)
        assertEquals(1, recovered.recoverPendingDownloads())
        assertEquals(1, provider.rows.size)
        assertEquals(0, row.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertArrayEquals(original, row.file.readBytes())
        media.clearMedia()
    }

    @Test fun ordinaryWriteFailureRemovesOnlyTheUnpublishedPartialAndKeepsThePrivateOriginal() = runBlocking {
        val media = originalStore()
        val downloads = AndroidAttachmentDownloads(context, failingSource(media, cancellation = false), scope, key)
        assertNotNull(runCatching { downloads.download("conv", "att") }.exceptionOrNull())
        assertEquals(1, provider.rows.values.single().values.getAsInteger(MediaStore.MediaColumns.IS_TRASHED))
        assertArrayEquals(original, media.openOriginal("conv", "att").use { it.readBytes() })
        media.clearMedia()
    }

    @Test fun aSystemReclaimedPendingRowIsClearedFromTheJournalWithoutRecreatingAFile() = runBlocking {
        val media = originalStore()
        val downloads = AndroidAttachmentDownloads(context, failingSource(media, cancellation = false, processDeath = true), scope, key)
        assertNotNull(runCatching { downloads.download("conv", "att") }.exceptionOrNull())
        provider.rows.clear()
        val recovered = AndroidAttachmentDownloads(context, media, scope, key)
        assertEquals(1, recovered.recoverPendingDownloads())
        assertTrue(provider.rows.isEmpty())
        assertEquals(0, AndroidAttachmentDownloads(context, media, scope, key).recoverPendingDownloads())
        media.clearMedia()
    }

    @Test fun aFailedPublishNeverLeavesAVisibleOrphan() = runBlocking {
        val media = originalStore()
        provider.failPublish = true
        assertNotNull(runCatching { AndroidAttachmentDownloads(context, media, scope, key).download("conv", "att") }.exceptionOrNull())
        assertEquals(1, provider.rows.values.single().values.getAsInteger(MediaStore.MediaColumns.IS_TRASHED))
        assertTrue(media.isRetained("att")); media.clearMedia()
    }

    @Test fun deleteAndRestoreAfterCopyBeforePublishRejectsTheOldDownloadWithoutTouchingAnAlreadyPublicFile() = runBlocking {
        val media=originalStore()
        media.applyTransition(HistoricalMediaTransition("conv","message",1,listOf("att"),false))
        val published=AndroidAttachmentDownloads(context,media,scope,key).download("conv","att")
        val publicRow=provider.rows.getValue(ContentUris.parseId(Uri.parse(published.uri)))
        provider.beforeLookup={
            runBlocking {
                media.applyTransition(HistoricalMediaTransition("conv","message",2,listOf("att"),true))
                media.applyTransition(HistoricalMediaTransition("conv","message",3,listOf("att"),false))
                archiveOriginal(media)
            }
        }

        assertNotNull("同摘要的新原件不能让旧导出任务跨代发布",
            runCatching { AndroidAttachmentDownloads(context,media,scope,key).download("conv","att") }.exceptionOrNull())
        assertEquals(1,provider.rows.values.count { it.values.getAsInteger(MediaStore.MediaColumns.IS_TRASHED)==1 })
        assertEquals(0,publicRow.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertFalse(publicRow.values.getAsInteger(MediaStore.MediaColumns.IS_TRASHED)==1)
        assertArrayEquals(original,publicRow.file.readBytes())
        assertTrue(privateJournal().keys().isEmpty())
        media.clearMedia()
    }

    @Test fun aVerifiedPendingExportFromAnOlderEpochCannotBePublishedAfterRestartAndSameByteRestore() = runBlocking {
        val media=originalStore()
        media.applyTransition(HistoricalMediaTransition("conv","message",1,listOf("att"),false))
        provider.beforeLookup={ throw SimulatedProcessDeath() }
        assertTrue(runCatching { AndroidAttachmentDownloads(context,media,scope,key).download("conv","att") }.exceptionOrNull() is SimulatedProcessDeath)
        val old=provider.rows.values.single()
        assertArrayEquals("中断点位于完整复制和verified日志之后",original,old.file.readBytes())
        media.applyTransition(HistoricalMediaTransition("conv","message",2,listOf("att"),true))
        media.applyTransition(HistoricalMediaTransition("conv","message",3,listOf("att"),false))
        archiveOriginal(media)

        val downloads=AndroidAttachmentDownloads(context,media,scope,key)
        assertEquals("DOWNLOAD_RECOVERY_FAILED",runCatching { downloads.recoverPendingDownloads() }.exceptionOrNull()?.message)
        assertEquals(1,old.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        val current=downloads.download("conv","att")
        assertEquals("DOWNLOAD_RECOVERY_FAILED",current.recoveryWarningCode)
        assertArrayEquals(original,context.contentResolver.openInputStream(Uri.parse(current.uri))!!.use { it.readBytes() })
        assertEquals(1,old.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(1,privateJournal().keys().size)
        media.clearMedia()
    }

    @Test fun activeCancellationTrashesTheUnpublishedRowAndNeverPublishesItAfterRestart() = runBlocking {
        val media = originalStore()
        val downloads = AndroidAttachmentDownloads(context, failingSource(media, cancellation = true), scope, key)
        assertTrue(runCatching { downloads.download("conv", "att") }.exceptionOrNull() is CancellationException)
        val row = provider.rows.values.single()
        assertEquals(1, row.values.getAsInteger(MediaStore.MediaColumns.IS_TRASHED))
        assertEquals(0, AndroidAttachmentDownloads(context, media, scope, key).recoverPendingDownloads())
        assertEquals(7L, row.file.length())
        assertArrayEquals(original, media.openOriginal("conv", "att").use { it.readBytes() })
        media.clearMedia()
    }

    @Test fun cancellationOfTheActualCoroutineStillCompletesTheNonCancellableRollback() = runBlocking {
        val media = originalStore()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val gated = object : HistoricalMediaPort by media {
            override suspend fun openOriginal(conversationId: String, attachmentId: String): InputStream {
                val input = media.openOriginal(conversationId, attachmentId)
                return object : FilterInputStream(input) {
                    private var first = true
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (first) { first = false; return super.read(buffer, offset, minOf(length, 7)) }
                        entered.countDown()
                        check(resume.await(5, TimeUnit.SECONDS)) { "取消夹具等待超时" }
                        return super.read(buffer, offset, minOf(length, 7))
                    }
                }
            }
        }
        val job = async(Dispatchers.Default) { AndroidAttachmentDownloads(context, gated, scope, key).download("conv", "att") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancel(); resume.countDown(); job.join()
        assertEquals(1, provider.rows.values.single().values.getAsInteger(MediaStore.MediaColumns.IS_TRASHED))
        assertTrue(privateJournal().keys().isEmpty())
        media.clearMedia()
    }

    @Test fun aFailedRecoveryKeepsItsIntentForExplicitRetryWithoutBlockingAnIndependentDownload() = runBlocking {
        val media = originalStore()
        assertTrue(runCatching { AndroidAttachmentDownloads(context, failingSource(media, false, true), scope, key).download("conv", "att") }.exceptionOrNull() is SimulatedProcessDeath)
        archiveHealthy(media)
        val failing = AndroidAttachmentDownloads(context, oldSourceFails(media), scope, key)
        assertEquals("DOWNLOAD_RECOVERY_FAILED", runCatching { failing.recoverPendingDownloads() }.exceptionOrNull()?.message)
        assertEquals(1, provider.rows.size)
        val row = provider.rows.values.single()
        assertEquals(1, row.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        val result = failing.download("conv", "healthy")
        assertEquals("DOWNLOAD_RECOVERY_FAILED", result.recoveryWarningCode)
        assertArrayEquals(original, context.contentResolver.openInputStream(Uri.parse(result.uri))!!.use { it.readBytes() })
        assertEquals(1, AndroidAttachmentDownloads(context, media, scope, key).recoverPendingDownloads())
        assertArrayEquals(original, row.file.readBytes())
        assertEquals(0, row.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        media.clearMedia()
    }

    @Test fun aMalformedOldJournalWarnsOnceButAllowsMultipleHealthyNewDownloads() = runBlocking {
        val media = originalStore()
        privateJournal().write("download_invalid", "{\"token\":\"download_invalid\"}".toByteArray())
        val downloads = AndroidAttachmentDownloads(context, media, scope, key)
        repeat(2) {
            val result = downloads.download("conv", "att")
            assertEquals("DOWNLOAD_RECOVERY_FAILED", result.recoveryWarningCode)
            assertArrayEquals(original, context.contentResolver.openInputStream(Uri.parse(result.uri))!!.use { it.readBytes() })
        }
        assertEquals(2, provider.rows.size)
        assertEquals(setOf("download_invalid"), privateJournal().keys())
        media.clearMedia()
    }

    @Test fun aDeletedOldOriginalDoesNotBlockANewHealthyAttachment() = runBlocking {
        val media = originalStore()
        assertTrue(runCatching { AndroidAttachmentDownloads(context, failingSource(media, false, true), scope, key).download("conv", "att") }.exceptionOrNull() is SimulatedProcessDeath)
        media.remove("conv", "att")
        archiveHealthy(media)
        val result = AndroidAttachmentDownloads(context, media, scope, key).download("conv", "healthy")
        assertEquals("DOWNLOAD_RECOVERY_FAILED", result.recoveryWarningCode)
        assertArrayEquals(original, context.contentResolver.openInputStream(Uri.parse(result.uri))!!.use { it.readBytes() })
        assertEquals(1, provider.rows.values.count { it.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 1 })
        assertEquals(1, privateJournal().keys().size)
        media.clearMedia()
    }

    @Test fun aSystemTrashFailureOfAnOldCancelledIntentDoesNotBlockANewHealthyDownload() = runBlocking {
        val media = originalStore()
        provider.failTrash = true
        assertNotNull(runCatching { AndroidAttachmentDownloads(context, failingSource(media, true), scope, key).download("conv", "att") }.exceptionOrNull())
        val downloads = AndroidAttachmentDownloads(context, media, scope, key)
        val result = downloads.download("conv", "att")
        assertEquals("DOWNLOAD_RECOVERY_FAILED", result.recoveryWarningCode)
        assertArrayEquals(original, context.contentResolver.openInputStream(Uri.parse(result.uri))!!.use { it.readBytes() })
        assertEquals(1, privateJournal().keys().size)
        provider.failTrash = false
        assertEquals(1, downloads.recoverPendingDownloads())
        assertNull(downloads.download("conv", "att").recoveryWarningCode)
        media.clearMedia()
    }

    @Test fun failedCancellationCleanupCanOnlyRetryTrashingAndCannotResumeCopying() = runBlocking {
        val media = originalStore()
        provider.failTrash = true
        assertNotNull(runCatching { AndroidAttachmentDownloads(context, failingSource(media, true), scope, key).download("conv", "att") }.exceptionOrNull())
        val forbidden = object : HistoricalMediaPort by media {
            override suspend fun openOriginal(conversationId: String, attachmentId: String): InputStream = error("取消的下载不能重新复制")
        }
        provider.failTrash = false
        assertEquals(1, AndroidAttachmentDownloads(context, forbidden, scope, key).recoverPendingDownloads())
        assertEquals(1, provider.rows.values.single().values.getAsInteger(MediaStore.MediaColumns.IS_TRASHED))
        media.clearMedia()
    }

    @Test fun accountCleanupTrashesOnlyPendingRowsAndPreservesPublishedDownloadsEvenWithALeftoverJournal() = runBlocking {
        val media = originalStore()
        val published = AndroidAttachmentDownloads(context, media, scope, key).download("conv", "att")
        val publishedRow = provider.rows.getValue(ContentUris.parseId(Uri.parse(published.uri)))
        val token = publishedRow.values.getAsString(MediaStore.DownloadColumns.DOWNLOAD_URI).removePrefix("oai-local:")
        privateJournal().write(token, Json.canonical(Json.of(mapOf("token" to token, "conversationId" to "conv", "attachmentId" to "att",
            "filename" to "original.pdf", "mediaType" to "application/pdf", "sizeBytes" to original.size,
            "sha256" to MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) },
            "uri" to published.uri, "verified" to true))).toByteArray())
        assertTrue(runCatching { AndroidAttachmentDownloads(context, failingSource(media, false, true), scope, key).download("conv", "att") }.exceptionOrNull() is SimulatedProcessDeath)
        val pendingRow = provider.rows.values.single { it.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 1 }
        val cleanup = AndroidAttachmentDownloads(context, media, scope, key)
        cleanup.cancelPendingDownloads()
        cleanup.wipePrivateDownloadJournal()
        assertEquals(1, pendingRow.values.getAsInteger(MediaStore.MediaColumns.IS_TRASHED))
        assertFalse(publishedRow.values.getAsInteger(MediaStore.MediaColumns.IS_TRASHED) == 1)
        assertArrayEquals(original, context.contentResolver.openInputStream(Uri.parse(published.uri))!!.use { it.readBytes() })
        assertTrue(privateJournal().keys().isEmpty())
        assertTrue(runCatching { cleanup.download("conv", "att") }.exceptionOrNull() is CancellationException)
        media.clearMedia()
    }

    @Test fun failedAccountCleanupPreservesTheJournalUntilPendingRowsCanBeTrashed() = runBlocking {
        val media = originalStore()
        assertTrue(runCatching { AndroidAttachmentDownloads(context, failingSource(media, false, true), scope, key).download("conv", "att") }.exceptionOrNull() is SimulatedProcessDeath)
        val cleanup = AndroidAttachmentDownloads(context, media, scope, key)
        provider.failTrash = true
        assertEquals("DOWNLOAD_CLEANUP_FAILED", runCatching { cleanup.wipePrivateDownloadJournal() }.exceptionOrNull()?.message)
        assertEquals(1, privateJournal().keys().size)
        provider.failTrash = false
        cleanup.wipePrivateDownloadJournal()
        assertTrue(privateJournal().keys().isEmpty())
        assertEquals(1, provider.rows.values.single().values.getAsInteger(MediaStore.MediaColumns.IS_TRASHED))
        media.clearMedia()
    }

    private fun privateJournal() = EncryptedDocuments(context, "attachment-download-journal-v1:" +
        Json.canonical(Json.of(listOf(scope.profileId, scope.gatewayId, scope.accountId, scope.installId))), key)

    private suspend fun archiveHealthy(media: EncryptedHistoryMediaCache) {
        media.archiveSubmitted("conv", "healthy", "healthy.pdf", "application/pdf", object : AttachmentContentSource {
            override fun openStream() = ByteArrayInputStream(original)
        }, original.size.toLong(), MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) })
    }
    private fun oldSourceFails(media: HistoricalMediaPort) = object : HistoricalMediaPort by media {
        override suspend fun openOriginal(conversationId: String, attachmentId: String): InputStream {
            if (attachmentId == "att") throw IOException("旧原件暂时不可读")
            return media.openOriginal(conversationId, attachmentId)
        }
    }

    private suspend fun originalStore(): EncryptedHistoryMediaCache = EncryptedHistoryMediaCache(context, scope, keyProvider = key).also { archiveOriginal(it) }
    private suspend fun archiveOriginal(media: EncryptedHistoryMediaCache) {
        media.archiveSubmitted("conv", "att", "original.pdf", "application/pdf", object : AttachmentContentSource {
            override fun openStream() = ByteArrayInputStream(original)
        }, original.size.toLong(), MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { b -> "%02x".format(b) })
    }
    private fun failingSource(media: HistoricalMediaPort, cancellation: Boolean, processDeath: Boolean = false): HistoricalMediaPort = object : HistoricalMediaPort by media {
        override suspend fun openOriginal(conversationId: String, attachmentId: String): InputStream {
            val input = media.openOriginal(conversationId, attachmentId)
            return object : FilterInputStream(input) {
                private var first = true
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (first) { first = false; return super.read(buffer, offset, minOf(length, 7)) }
                    if (processDeath) throw SimulatedProcessDeath()
                    if (cancellation) throw CancellationException("模拟进程中断")
                    throw IOException("模拟读取失败")
                }
            }
        }
    }
    private class SimulatedProcessDeath : Error("模拟无法执行取消回调的进程终止")

    class DownloadProvider : ContentProvider() {
        data class Row(val values: ContentValues, val file: File)
        val rows = linkedMapOf<Long, Row>()
        var failPublish = false
        var failTrash = false
        var beforeLookup: (() -> Unit)? = null
        private var nextId = 1L
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val copied = ContentValues(values ?: error("缺少下载元数据"))
            val name = copied.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
            val extension = name.substringAfterLast('.', "")
            val base = name.substringBeforeLast('.', name)
            var proposed = name
            var duplicate = 1
            while (rows.values.any { it.values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) == proposed }) {
                proposed = "$base (${duplicate++})" + if (extension.isEmpty()) "" else ".$extension"
            }
            copied.put(MediaStore.MediaColumns.DISPLAY_NAME, proposed)
            copied.put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, context!!.packageName)
            val id = nextId++
            rows[id] = Row(copied, File(context!!.cacheDir, "download-provider-$id.bin"))
            return ContentUris.withAppendedId(uri, id)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            beforeLookup?.also { beforeLookup=null }?.invoke()
            val names = projection ?: arrayOf(MediaStore.MediaColumns._ID)
            val cursor = MatrixCursor(names)
            val id = uri.lastPathSegment?.toLongOrNull()
            rows.filter { (key, row) -> (id == null || id == key) && (selectionArgs?.firstOrNull()?.let { it == row.values.getAsString(MediaStore.DownloadColumns.DOWNLOAD_URI) } != false) }
                .forEach { (key, row) -> cursor.addRow(names.map { name -> if (name == MediaStore.MediaColumns._ID) key else row.values.get(name) }.toTypedArray()) }
            return cursor
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            if (failTrash && values?.getAsInteger(MediaStore.MediaColumns.IS_TRASHED) == 1) return 0
            if (failPublish && values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0 && values.getAsInteger(MediaStore.MediaColumns.IS_TRASHED) != 1) return 0
            val row = rows[ContentUris.parseId(uri)] ?: return 0
            row.values.putAll(values); return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = if (rows.remove(ContentUris.parseId(uri)) != null) 1 else 0
        override fun getType(uri: Uri) = rows[ContentUris.parseId(uri)]?.values?.getAsString(MediaStore.MediaColumns.MIME_TYPE)
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = rows[ContentUris.parseId(uri)]?.file ?: throw IOException("下载已被回收")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }
    }
}
