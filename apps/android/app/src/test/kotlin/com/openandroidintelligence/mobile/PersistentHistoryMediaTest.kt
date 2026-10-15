package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache
import com.openandroidintelligence.mobile.plugins.EncryptedDocuments
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.events.EventCursorStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PersistentHistoryMediaTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val key = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 42 }, "AES")
        override fun delete() = Unit
    }
    private val scope = ConversationScope("persistent", "https://gateway.example", "account", "installation")
    private val scopeKey = Json.canonical(Json.of(listOf(scope.profileId, scope.gatewayId, scope.accountId, scope.installId)))

    @Test fun localOriginalsExceedTheOldFileAndTotalLimitsAndSurviveRestartAfterTheStagingTtl() = runBlocking {
        val cache = EncryptedHistoryMediaCache(context, scope, keyProvider = key)
        val size = 33L * 1024 * 1024
        val expected = repeatedDigest(size)
        val source = object : AttachmentContentSource { override fun openStream() = RepeatedStream(size) }
        cache.archiveSubmitted("conv", "att_a", "a.bin", "application/octet-stream", source, size, expected)
        cache.archiveSubmitted("conv", "att_b", "b.bin", "application/octet-stream", source, size, expected)
        File(context.noBackupFilesDir, "history-media").walkTopDown().filter { it.extension == "stage" }.forEach {
            assertTrue(it.setLastModified(System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000))
        }
        val reopened = EncryptedHistoryMediaCache(context, scope, keyProvider = key)
        listOf("att_a", "att_b").forEach { id ->
            assertEquals(size, reopened.ensureLocal("conv", id).sizeBytes)
            reopened.openOriginal("conv", id).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(32 * 1024)
                var count = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read; digest.update(buffer, 0, read)
                }
                assertEquals(size, count); assertEquals(expected, hex(digest.digest()))
            }
        }
        assertNull(EncryptedHistoryMediaCache(context, scope.copy(accountId = "other"), keyProvider = key).localMetadata("conv", "att_a"))
        reopened.clearMedia()
    }

    @Test fun sameAttachmentIdentityInDifferentConversationsNeverSharesOriginalsOrDeletion() = runBlocking {
        val cache = EncryptedHistoryMediaCache(context, scope, keyProvider = key)
        val a = "original A".toByteArray(); val b = "original B".toByteArray()
        archive(cache, "conv_a", "att_same", a); archive(cache, "conv_b", "att_same", b)
        cache.remove("conv_a", "att_same")
        val reopened = EncryptedHistoryMediaCache(context, scope, keyProvider = key)
        assertNull(reopened.localMetadata("conv_a", "att_same"))
        assertNotNull(runCatching { archive(reopened, "conv_a", "att_same", a) }.exceptionOrNull())
        assertArrayEquals(b, reopened.openOriginal("conv_b", "att_same").use { it.readBytes() })
        reopened.clearMedia()
    }

    @Test fun migrationCommitsTheOriginalBeforeRetiringTheLegacyCopy() = runBlocking {
        val content = "legacy original".toByteArray()
        val legacy = legacy("old", content)
        val cache = EncryptedHistoryMediaCache(context, scope, keyProvider = key)
        cache.migrateLegacy()
        assertArrayEquals(content, cache.openOriginal("conv", "old").use { it.readBytes() })
        assertNull(legacy.read("metadata:old")); assertNull(legacy.read("content:old"))
        cache.clearMedia()
    }

    @Test fun interruptedMigrationLeavesTheOldOriginalReadableAndCanBeRetried() = runBlocking {
        val content = "legacy original preserved".toByteArray()
        val legacy = legacy("old_failed", content)
        val entry = hex(MessageDigest.getInstance("SHA-256").digest(Json.canonical(Json.of(listOf("conv", "old_failed"))).toByteArray()))
        val indexScope = "history-media-index-v2:$scopeKey"
        val directory = File(context.noBackupFilesDir, "private-documents/${sha(indexScope.toByteArray())}")
        val blocked = File(directory, sha("metadata:$entry".toByteArray()) + ".tmp")
        assertTrue(blocked.mkdirs())
        val cache = EncryptedHistoryMediaCache(context, scope, keyProvider = key)
        assertNotNull(runCatching { cache.migrateLegacy() }.exceptionOrNull())
        assertArrayEquals(content, legacy.read("content:old_failed")); assertNotNull(legacy.read("metadata:old_failed"))
        assertTrue(blocked.renameTo(File(context.cacheDir, "preserved-migration-block")))
        cache.migrateLegacy()
        assertArrayEquals(content, cache.openOriginal("conv", "old_failed").use { it.readBytes() })
        cache.clearMedia()
    }

    @Test fun integrityFailureDoesNotPublishALocalOriginal() = runBlocking {
        val cache = EncryptedHistoryMediaCache(context, scope, keyProvider = key)
        val bytes = "broken transfer".toByteArray()
        val failure = runCatching { cache.archiveSubmitted("conv", "bad", "bad.txt", "text/plain",
            object : AttachmentContentSource { override fun openStream() = ByteArrayInputStream(bytes) }, bytes.size.toLong(), "0".repeat(64)) }.exceptionOrNull()
        assertEquals("MEDIA_INTEGRITY_FAILED", failure?.message)
        assertNull(cache.localMetadata("conv", "bad")); cache.clearMedia()
    }

    @Test fun clearingMediaRetiresThePersistentScopeAndReopeningCannotReadOldCiphertext() = runBlocking {
        var deletedKeys = 0
        val tracked = object : AesGcmKeyProvider {
            override fun getOrCreate() = key.getOrCreate()
            override fun delete() { deletedKeys++ }
        }
        val cache = EncryptedHistoryMediaCache(context, scope, keyProvider = tracked)
        archive(cache, "conv", "retired", "private original".toByteArray())
        val hash = sha(scopeKey.toByteArray())
        val active = File(context.noBackupFilesDir, "history-media/$hash")
        assertTrue(active.isDirectory)
        cache.clearMedia()
        assertFalse(active.exists())
        assertTrue(File(context.noBackupFilesDir, "history-media-retired/$hash").walkTopDown().any { it.extension == "stage" })
        assertTrue(deletedKeys > 0)
        val reopened = EncryptedHistoryMediaCache(context, scope, keyProvider = tracked)
        assertNull(reopened.localMetadata("conv", "retired"))
        assertNotNull(runCatching { reopened.openOriginal("conv", "retired") }.exceptionOrNull())
    }

    @Test fun remoteOriginalLargerThanTwentyFiveMibStreamsOnceAndThenReadsOffline() = runBlocking {
        val size = 26L * 1024 * 1024 + 7
        var contentReads = 0
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                val data = if (request.target.endsWith("/cache-grant?format=stream-v1")) mapOf("grantId" to "G".repeat(43)) else {
                    check(request.target.endsWith("/metadata?format=stream-v1")) { "原件不能通过整文件字节入口读取" }
                    mapOf("metadata" to mapOf("conversationId" to "conv", "attachmentId" to "remote_large", "filename" to "large.bin",
                        "mediaType" to "application/octet-stream", "sizeBytes" to size, "sha256" to "sha256:${repeatedDigest(size)}", "remoteAvailable" to true))
                }
                return WireResponse(200, emptyList(), Json.canonical(Json.of(mapOf("data" to data))).toByteArray())
            }
            override suspend fun <T> consumeResponse(request: WireRequest, consume: suspend (Int, List<RawHeader>, InputStream) -> T): T {
                contentReads++
                assertTrue(request.target.contains("/content?format=stream-v1&grantId="))
                return RepeatedStream(size).use { consume(200, listOf(RawHeader("Content-Type", "application/octet-stream"),
                    RawHeader("Content-Length", size.toString()), RawHeader("X-Open-Android-Intelligence-Media-Format", "stream-v1")), it) }
            }
            override fun eventStream(request: WireRequest): Flow<ByteArray> = emptyFlow()
        }
        val cache = EncryptedHistoryMediaCache(context, scope, client(transport), key)
        cache.ensureLocal("conv", "remote_large"); cache.ensureLocal("conv", "remote_large")
        assertEquals(1, contentReads)
        val offline = EncryptedHistoryMediaCache(context, scope, keyProvider = key)
        assertEquals(size, offline.ensureLocal("conv", "remote_large").sizeBytes)
        offline.openOriginal("conv", "remote_large").use { input ->
            val buffer = ByteArray(32 * 1024); var count = 0L
            while (true) { val read = input.read(buffer); if (read < 0) break; count += read }
            assertEquals(size, count)
        }
        offline.clearMedia()
    }

    @Test fun anUnboundedLegacyJsonBodyFailsBeforePublishingAnOriginal() = runBlocking {
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest) = WireResponse(200, emptyList(),
                Json.canonical(Json.of(mapOf("data" to mapOf("grantId" to "G".repeat(43))))).toByteArray())
            override suspend fun <T> consumeResponse(request: WireRequest, consume: suspend (Int, List<RawHeader>, InputStream) -> T): T =
                RepeatedStream(13L * 1024 * 1024).use { consume(200, listOf(RawHeader("Content-Type", "application/json")), it) }
            override fun eventStream(request: WireRequest): Flow<ByteArray> = emptyFlow()
        }
        val cache = EncryptedHistoryMediaCache(context, scope, client(transport), key)
        val metadata = HistoricalMediaMetadata("conv", "legacy_large", "large.bin", "application/octet-stream", 10L * 1024 * 1024,
            "sha256:" + "0".repeat(64), true, 0)
        assertEquals("MEDIA_STREAM_UNSUPPORTED", runCatching { cache.retain(metadata) }.exceptionOrNull()?.message)
        assertNull(cache.localMetadata("conv", "legacy_large")); cache.clearMedia()
    }

    private fun client(transport: GatewayByteTransport): GatewayHttpClient {
        val cursors = object : EventCursorStore {
            override fun load(accountId: String): String? = null
            override fun save(accountId: String, cursor: String) = Unit
            override fun clear(accountId: String) = Unit
        }
        return GatewayHttpClient(GatewayProfile(scope.accountId, "device", "session", scope.gatewayId, accessToken = "test"), transport, { ByteArray(64) }, cursors)
    }

    private suspend fun archive(cache: EncryptedHistoryMediaCache, conversation: String, id: String, bytes: ByteArray) =
        cache.archiveSubmitted(conversation, id, "$id.txt", "text/plain", object : AttachmentContentSource {
            override fun openStream() = ByteArrayInputStream(bytes)
        }, bytes.size.toLong(), sha(bytes))
    private fun legacy(id: String, bytes: ByteArray): EncryptedDocuments {
        val documents = EncryptedDocuments(context, "history-media-cache-v1:$scopeKey", key)
        documents.write("content:$id", bytes)
        documents.write("metadata:$id", Json.canonical(Json.of(mapOf("messageScope" to "conv", "filename" to "old.txt",
            "mediaType" to "text/plain", "sizeBytes" to bytes.size, "sha256" to "sha256:${sha(bytes)}"))).toByteArray())
        return documents
    }
    private fun repeatedDigest(size: Long): String {
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(32 * 1024) { 7 }
        var left = size
        while (left > 0) { val count = minOf(left, buffer.size.toLong()).toInt(); digest.update(buffer, 0, count); left -= count }
        return hex(digest.digest())
    }
    private fun sha(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private class RepeatedStream(private val size: Long) : InputStream() {
        private var offset = 0L
        override fun read() = if (offset >= size) -1 else { offset++; 7 }
        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            if (length == 0) return 0
            if (offset >= size) return -1
            val count = minOf(length.toLong(), size - offset).toInt()
            buffer.fill(7, start, start + count); offset += count
            return count
        }
    }
}
