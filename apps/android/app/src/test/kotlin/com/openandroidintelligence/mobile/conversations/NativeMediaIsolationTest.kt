package com.openandroidintelligence.mobile.conversations

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.ports.AttachmentContentSource
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.HistoricalMediaTransition
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeMediaIsolationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val key = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 42 }, "AES")
        override fun delete() = Unit
    }
    private val scope = ConversationScope("native-media-${UUID.randomUUID()}", "https://core.example", "account", "install")
    private val conversationId = "same-conversation"
    private val attachmentId = "same-attachment"
    private fun cache(agentId: String?, scope: ConversationScope = this.scope) =
        EncryptedHistoryMediaCache(context, scope, keyProvider = key, nativeAgentId = agentId)
    private suspend fun archive(cache: EncryptedHistoryMediaCache, bytes: ByteArray, mediaType: String = "text/plain") {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        cache.archiveSubmitted(conversationId, attachmentId, "original", mediaType,
            AttachmentContentSource { ByteArrayInputStream(bytes) }, bytes.size.toLong(), "sha256:$digest", "same-draft")
    }
    private suspend fun original(cache: EncryptedHistoryMediaCache) =
        cache.openOriginal(conversationId, attachmentId).use { it.readBytes() }

    @Test fun sameRemoteIdsInDifferentAgentsNeverShareOriginalsOrDeletion() = runBlocking {
        val a = "PRIVATE ORIGINAL FOR AGENT A".toByteArray()
        val b = "PRIVATE ORIGINAL FOR AGENT B".toByteArray()
        val first = cache("agent-a")
        archive(first, a)
        val second = cache("agent-b")
        assertNull("Another Agent must not see the original metadata", second.localMetadata(conversationId, attachmentId))
        assertFalse(second.isRetained(attachmentId))
        assertNull(second.submittedMetadata(conversationId, "same-draft"))
        assertNotNull(runCatching { original(second) }.exceptionOrNull())
        archive(second, b)
        assertArrayEquals(a, original(cache("agent-a")))
        assertArrayEquals(b, original(cache("agent-b")))
        assertEquals(conversationId, second.localMetadata(conversationId, attachmentId)?.conversationId)
        assertEquals(attachmentId, second.localMetadata(conversationId, attachmentId)?.attachmentId)
        second.applyTransition(HistoricalMediaTransition(conversationId, "message", 2L, listOf(attachmentId), true))
        assertNull(cache("agent-b").localMetadata(conversationId, attachmentId))
        assertArrayEquals(a, original(cache("agent-a")))
        cache("agent-b").remove(conversationId, attachmentId)
        assertArrayEquals(a, original(first))
        assertNotNull(runCatching { archive(cache("agent-b"), b) }.exceptionOrNull())
        assertArrayEquals(a, original(cache("agent-a")))
    }

    @Test fun usedAgentsRemainEnumerableAfterRebuildingWithinTheirAccountAndCore() = runBlocking {
        val firstRegistry = NativeLocalPartitions(context, scope, key)
        val anotherRegistry = NativeLocalPartitions(context, scope, key)
        assertTrue(firstRegistry.agents().isEmpty())
        val a = "original A".toByteArray()
        archive(cache("agent-a"), a)
        archive(cache("agent-b"), "original B".toByteArray())
        assertEquals(setOf("agent-a", "agent-b"), NativeLocalPartitions(context, scope, key).agents())
        firstRegistry.register("mirror-only")
        anotherRegistry.register("download-only")
        anotherRegistry.register("agent-a")
        val expected = setOf("agent-a", "agent-b", "mirror-only", "download-only")
        assertEquals(expected, NativeLocalPartitions(context, scope, key).agents())
        val partition = firstRegistry.partitionScope("agent-a")
        assertEquals(scope.copy(accountId = "account#native#agent-a"), partition)
        assertArrayEquals(a, original(EncryptedHistoryMediaCache(context, partition, keyProvider = key)))
        listOf(scope.copy(profileId = "other-profile"), scope.copy(gatewayId = "https://other-core.example"),
            scope.copy(accountId = "other-account"), scope.copy(installId = "other-install")).forEach { otherScope ->
            val other = NativeLocalPartitions(context, otherScope, key)
            assertTrue(other.agents().isEmpty())
            other.register("other-agent")
            assertEquals(setOf("other-agent"), NativeLocalPartitions(context, otherScope, key).agents())
            assertNull(cache("agent-a", otherScope).localMetadata(conversationId, attachmentId))
            assertNotNull(runCatching { original(cache("agent-a", otherScope)) }.exceptionOrNull())
        }
        assertEquals(expected, NativeLocalPartitions(context, scope, key).agents())
    }


    @Test fun trustedLegacyOriginalCopiesOnceWithoutChangingTheRollbackSourceOrSharingTheClaim() = runBlocking {
        val bytes = "LEGACY ORIGINAL KEPT FOR ROLLBACK".toByteArray()
        val legacy = cache(null)
        archive(legacy, bytes)
        val metadata = checkNotNull(legacy.localMetadata(conversationId, attachmentId))
        val destination = cache("agent-a")
        assertNull(destination.localMetadata(conversationId, attachmentId))
        assertTrue(destination.migrateLegacyOriginal(conversationId, attachmentId, metadata.sizeBytes, metadata.sha256))
        assertArrayEquals(bytes, original(cache("agent-a")))
        assertArrayEquals(bytes, original(cache(null)))
        assertTrue(cache("agent-a").migrateLegacyOriginal(conversationId, attachmentId, metadata.sizeBytes, metadata.sha256))
        val anotherAgent = cache("agent-b")
        assertFalse(anotherAgent.migrateLegacyOriginal(conversationId, attachmentId, metadata.sizeBytes, metadata.sha256))
        assertNull(anotherAgent.localMetadata(conversationId, attachmentId))
        assertArrayEquals(bytes, original(cache(null)))
    }


    @Test fun incompleteOrMismatchedLegacyEvidenceNeverClaimsOrCopiesAnOriginal() = runBlocking {
        val bytes = "UNCLAIMED LEGACY ORIGINAL".toByteArray()
        archive(cache(null), bytes)
        val metadata = checkNotNull(cache(null).localMetadata(conversationId, attachmentId))
        val unproven = cache("unproven-agent")
        assertFalse(unproven.migrateLegacyOriginal(conversationId, attachmentId, metadata.sizeBytes, ""))
        assertFalse(unproven.migrateLegacyOriginal(conversationId, attachmentId, -1, metadata.sha256))
        assertFalse(unproven.migrateLegacyOriginal(conversationId, attachmentId, metadata.sizeBytes + 1, metadata.sha256))
        assertFalse(unproven.migrateLegacyOriginal(conversationId, attachmentId, metadata.sizeBytes, "sha256:${"0".repeat(64)}"))
        assertFalse(unproven.migrateLegacyOriginal("another-conversation", attachmentId, metadata.sizeBytes, metadata.sha256))
        assertFalse(unproven.migrateLegacyOriginal(conversationId, "another-attachment", metadata.sizeBytes, metadata.sha256))
        assertNull(unproven.localMetadata(conversationId, attachmentId))
        assertTrue(cache("proven-agent").migrateLegacyOriginal(conversationId, attachmentId, metadata.sizeBytes, metadata.sha256))
        assertArrayEquals(bytes, original(cache("proven-agent")))
        assertArrayEquals(bytes, original(cache(null)))
    }

    @Test fun legacyCopyCannotUndoAnAgentsDeletionOrReplaceItsDifferentOriginal() = runBlocking {
        val legacyBytes = "LEGACY ORIGINAL".toByteArray()
        archive(cache(null), legacyBytes)
        val metadata = checkNotNull(cache(null).localMetadata(conversationId, attachmentId))
        val deleted = cache("deleted-agent")
        deleted.remove(conversationId, attachmentId)
        assertFalse(cache("deleted-agent").migrateLegacyOriginal(conversationId, attachmentId, metadata.sizeBytes, metadata.sha256))
        assertNull(cache("deleted-agent").localMetadata(conversationId, attachmentId))
        val ownBytes = "THIS AGENT ALREADY HAS ITS OWN ORIGINAL".toByteArray()
        archive(cache("existing-agent"), ownBytes)
        assertFalse(cache("existing-agent").migrateLegacyOriginal(conversationId, attachmentId, metadata.sizeBytes, metadata.sha256))
        assertArrayEquals(ownBytes, original(cache("existing-agent")))
        assertTrue(cache("proven-agent").migrateLegacyOriginal(conversationId, attachmentId, metadata.sizeBytes, metadata.sha256))
        assertArrayEquals(legacyBytes, original(cache("proven-agent")))
        assertArrayEquals(legacyBytes, original(cache(null)))
    }

    @Test fun deletingTheLegacyOriginalPreventsItFromBeingCopiedIntoAnAgent() = runBlocking {
        val legacy = cache(null)
        archive(legacy, "DELETED LEGACY ORIGINAL".toByteArray())
        val metadata = checkNotNull(legacy.localMetadata(conversationId, attachmentId))
        legacy.applyTransition(HistoricalMediaTransition(conversationId, "message", 2L, listOf(attachmentId), true))
        assertFalse(cache("agent-a").migrateLegacyOriginal(conversationId, attachmentId, metadata.sizeBytes, metadata.sha256))
        assertNull(cache("agent-a").localMetadata(conversationId, attachmentId))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun agentPreviewsAndRetirementNeverTouchAnotherAgentsOrTheUnattributedOriginal() = runBlocking {
        fun image(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            return try {
                bitmap.eraseColor(color)
                ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
            } finally { bitmap.recycle() }
        }
        val oldBytes = image(Color.GREEN)
        archive(cache(null), oldBytes, "image/png")
        val a = cache("agent-a")
        val b = cache("agent-b")
        assertNull(a.localMetadata(conversationId, attachmentId))
        assertNull(a.preview(conversationId, attachmentId))
        assertNull(b.preview(attachmentId))
        val aBytes = image(Color.RED)
        archive(a, aBytes, "image/png")
        val aPreview = checkNotNull(a.preview(conversationId, attachmentId))
        assertNull(b.preview(conversationId, attachmentId))
        val bBytes = image(Color.BLUE)
        archive(b, bBytes, "image/png")
        val bPreview = checkNotNull(b.preview(conversationId, attachmentId))
        assertFalse(aPreview.contentEquals(bPreview))
        assertArrayEquals(aPreview, cache("agent-a").preview(attachmentId))
        assertArrayEquals(bPreview, cache("agent-b").preview(conversationId, attachmentId))
        b.clearMedia()
        assertNull(cache("agent-b").preview(conversationId, attachmentId))
        assertArrayEquals(aPreview, cache("agent-a").preview(conversationId, attachmentId))
        assertArrayEquals(aBytes, original(cache("agent-a")))
        assertArrayEquals(oldBytes, original(cache(null)))
        assertEquals(setOf("agent-a", "agent-b"), NativeLocalPartitions(context, scope, key).agents())
    }


    @Test fun verifiedLegacyCopyKeepsLargeOriginalsAndUsesTheSameStreamingIntegrityChecks() = runBlocking {
        val size = 33L * 1024 * 1024 + 7
        val expected = "sha256:79d526b3f4fbf5f47d829040f3ac05c8abc852a23bdd414b670fbeb8a6ab3301"
        cache(null).archiveSubmitted(conversationId, attachmentId, "large.bin", "application/octet-stream",
            AttachmentContentSource { RepeatedInput(size) }, size, expected)
        assertTrue(cache("agent-a").migrateLegacyOriginal(conversationId, attachmentId, size, expected))
        listOf(cache(null), cache("agent-a")).forEach { saved ->
            val digest = MessageDigest.getInstance("SHA-256")
            var readBytes = 0L
            saved.openOriginal(conversationId, attachmentId).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    readBytes += count
                }
            }
            assertEquals(size, readBytes)
            assertEquals(expected, "sha256:" + digest.digest().joinToString("") { "%02x".format(it) })
        }
    }

    private class RepeatedInput(private var remaining: Long) : InputStream() {
        override fun read(): Int = if (remaining == 0L) -1 else { remaining--; 7 }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return -1
            val count = minOf(remaining, length.toLong()).toInt()
            buffer.fill(7.toByte(), offset, offset + count)
            remaining -= count
            return count
        }
    }

}
