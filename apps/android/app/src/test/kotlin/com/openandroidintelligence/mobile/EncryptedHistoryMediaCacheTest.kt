package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.events.EventCursorStore
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.crypto.spec.SecretKeySpec
import java.io.File
import java.security.MessageDigest
import java.util.Base64
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
    private fun http():GatewayHttpClient {
        val transport=object:GatewayByteTransport {
            override suspend fun execute(request:WireRequest):WireResponse {
                val data=when {
                    request.target.endsWith("/cache-grant") -> "{\"grantId\":\"${"G".repeat(43)}\"}"
                    request.target.contains("/content?grantId=") -> "{\"mediaType\":\"text/plain\",\"contentBase64\":\"${Base64.getEncoder().encodeToString(bytes)}\"}"
                    else -> error("UNEXPECTED_REQUEST")
                }
                return WireResponse(200,emptyList(),"{\"data\":$data}".toByteArray())
            }
            override fun eventStream(request:WireRequest):Flow<ByteArray> = emptyFlow()
        }
        val cursors=object:EventCursorStore {override fun load(accountId:String):String?=null;override fun save(accountId:String,cursor:String){};override fun clear(accountId:String){}}
        return GatewayHttpClient(GatewayProfile("acct_same","dev_one","sess_one",scope.gatewayId,accessToken="test"),transport,{ByteArray(64)},cursors)
    }
    @Test fun explicitRetentionSurvivesOfflineRestartAndNeverCrossesGatewayOrAccount()=runBlocking {
        val cache=EncryptedHistoryMediaCache(context,scope,http(),key)
        assertFalse(cache.isRetained(metadata.attachmentId));cache.retain(metadata);assertTrue(cache.isRetained(metadata.attachmentId))
        val offline=EncryptedHistoryMediaCache(context,scope,keyProvider=key)
        assertEquals(metadata.filename,offline.metadata(metadata.conversationId,metadata.attachmentId).filename)
        assertTrue(offline.isRetained(metadata.attachmentId))
        assertFalse(EncryptedHistoryMediaCache(context,scope.copy(gatewayId="https://second.example"),keyProvider=key).isRetained(metadata.attachmentId))
        assertFalse(EncryptedHistoryMediaCache(context,scope.copy(accountId="acct_other"),keyProvider=key).isRetained(metadata.attachmentId))
        File(context.noBackupFilesDir,"private-documents").walkTopDown().filter{it.isFile}.forEach{assertFalse(it.readBytes().decodeToString().contains("PRIVATE HISTORICAL MEDIA"))}
        cache.clearMedia();assertFalse(offline.isRetained(metadata.attachmentId))
    }
    @Test fun hardQuotaRejectsNewMediaWithoutEvictingRetainedItems()=runBlocking {
        val cache=EncryptedHistoryMediaCache(context,scope,http(),key);cache.setQuota(8);cache.retain(metadata)
        val rejected=runCatching {cache.retain(metadata.copy(attachmentId="media_large",sizeBytes=8L*1024*1024,estimatedLocalBytes=12L*1024*1024))}.exceptionOrNull()
        assertTrue(rejected?.message?.startsWith("MEDIA_CACHE_QUOTA_EXCEEDED")==true)
        assertTrue(cache.isRetained(metadata.attachmentId));cache.clearMedia()
    }
}
