package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import kotlinx.coroutines.runBlocking
import javax.crypto.spec.SecretKeySpec
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class EncryptedHistoryMediaCacheTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private val key=object:AesGcmKeyProvider {override fun getOrCreate()=SecretKeySpec(ByteArray(32){3},"AES");override fun delete(){}}
    private val scope=ConversationScope("profile_media","https://first.example","acct_same","install_one")
    private val bytes="PRIVATE HISTORICAL MEDIA".toByteArray()
    private val metadata=HistoricalMediaMetadata("conv_media","media_one","history.txt","text/plain",bytes.size.toLong(),
        "sha256:"+MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)},true,2048)
    private fun source() = object : HistoricalMediaSource {
        override fun checkCurrent() = Unit
        override suspend fun metadata(conversationId: String, attachmentId: String) = metadata.also {
            check(conversationId == it.conversationId && attachmentId == it.attachmentId)
        }
        override suspend fun consumeOriginal(metadata: HistoricalMediaMetadata, consume: (java.io.InputStream) -> Unit) {
            java.io.ByteArrayInputStream(bytes).use(consume)
        }
    }
    @Test fun explicitRetentionSurvivesOfflineRestartAndNeverCrossesGatewayOrAccount()=runBlocking {
        val cache=EncryptedHistoryMediaCache(context,scope,source(),key)
        assertFalse(cache.isRetained(metadata.attachmentId));cache.retain(metadata);assertTrue(cache.isRetained(metadata.attachmentId))
        val offline=EncryptedHistoryMediaCache(context,scope,keyProvider=key)
        assertEquals(metadata.filename,offline.metadata(metadata.conversationId,metadata.attachmentId).filename)
        assertTrue(offline.isRetained(metadata.attachmentId))
        assertFalse(EncryptedHistoryMediaCache(context,scope.copy(gatewayId="https://second.example"),keyProvider=key).isRetained(metadata.attachmentId))
        assertFalse(EncryptedHistoryMediaCache(context,scope.copy(accountId="acct_other"),keyProvider=key).isRetained(metadata.attachmentId))
        File(context.noBackupFilesDir,"private-documents").walkTopDown().filter{it.isFile}.forEach{assertFalse(it.readBytes().decodeToString().contains("PRIVATE HISTORICAL MEDIA"))}
        cache.clearMedia();assertFalse(offline.isRetained(metadata.attachmentId))
    }
    @Test fun anotherConversationCannotOpenOrDeleteARetainedOriginal()=runBlocking {
        val cache=EncryptedHistoryMediaCache(context,scope,source(),key);cache.retain(metadata)
        assertNull(cache.localMetadata("conv_other",metadata.attachmentId))
        cache.remove("conv_other",metadata.attachmentId)
        assertArrayEquals(bytes,cache.openOriginal(metadata.conversationId,metadata.attachmentId).use { it.readBytes() })
        cache.clearMedia()
    }
}
