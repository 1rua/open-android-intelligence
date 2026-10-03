package com.openandroidintelligence.mobile.conversations

import android.content.Context
import com.openandroidintelligence.mobile.plugins.EncryptedDocuments
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import java.util.Base64
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit retention, a hard quota, and account-scoped encrypted files. Clearing media preserves the mirror. */
class EncryptedHistoryMediaCache(context:Context,private val scope:ConversationScope,private val http:GatewayHttpClient?=null,keyProvider:com.openandroidintelligence.encrypted.store.AesGcmKeyProvider?=null) : HistoricalMediaPort {
    private val scopeKey=Json.canonical(Json.of(listOf(scope.profileId,scope.gatewayId,scope.accountId,scope.installId)))
    private val documents=if(keyProvider==null) EncryptedDocuments(context,"history-media-cache-v1:$scopeKey") else EncryptedDocuments(context,"history-media-cache-v1:$scopeKey",keyProvider)
    private val preferences=context.getSharedPreferences("history-media-policy-v1",Context.MODE_PRIVATE)
    private val policyKey=MessageDigest.getInstance("SHA-256").digest(scopeKey.toByteArray()).joinToString("") { "%02x".format(it) }
    val quotaBytes:Long get()=preferences.getLong(policyKey,64L*1024*1024)
    val usedBytes:Long get()=documents.ciphertextBytes()
    fun setQuota(megabytes:Int) { require(megabytes in 8..256); check(preferences.edit().putLong(policyKey,megabytes.toLong()*1024*1024).commit()) }
    fun clearMedia()=documents.erase()
    private suspend fun request(method:String,target:String,body:ByteArray=ByteArray(0)):JsonValue.JObject {
        val response=(http ?: error("REMOTE_UNAVAILABLE")).execute(SignedGatewayRequest(method,target,body=body,headers=if (body.isNotEmpty()) listOf(RawHeader("Content-Type","application/json")) else emptyList()))
        check(response.status in 200..299) { "HISTORY_MEDIA_FAILED:${response.status}" }
        return JsonFields.obj(JsonFields.field(JsonFields.obj(Json.parse(response.body.decodeToString())),"data")) ?: error("HISTORY_MEDIA_INVALID")
    }
    private fun target(conversationId:String,id:String,operation:String):String {
        require(listOf(conversationId,id).all { it.matches(Regex("[A-Za-z0-9._~-]{1,128}")) })
        return "/open-android-intelligence/v2/conversations/$conversationId/attachments/$id/$operation"
    }
    override suspend fun metadata(conversationId:String,attachmentId:String)=withContext(Dispatchers.IO) {
        if(http==null) {
            val local=documents.read("metadata:$attachmentId")?.let { JsonFields.obj(Json.parse(it.decodeToString())) } ?: error("REMOTE_UNAVAILABLE")
            check(JsonFields.string(local,"messageScope")==conversationId) { "HISTORY_MEDIA_SCOPE_MISMATCH" }
            return@withContext HistoricalMediaMetadata(conversationId,attachmentId,JsonFields.string(local,"filename").orEmpty(),JsonFields.string(local,"mediaType").orEmpty(),
                JsonFields.long(local,"sizeBytes") ?: 0L,JsonFields.string(local,"sha256").orEmpty(),false,0L)
        }
        val o=JsonFields.obj(JsonFields.field(request("GET",target(conversationId,attachmentId,"metadata")),"metadata")) ?: error("HISTORY_MEDIA_INVALID")
        check(JsonFields.string(o,"attachmentId")==attachmentId && JsonFields.string(o,"conversationId")==conversationId) { "HISTORY_MEDIA_SCOPE_MISMATCH" }
        val size=JsonFields.long(o,"sizeBytes") ?: error("HISTORY_MEDIA_INVALID")
        check(size>=0)
        HistoricalMediaMetadata(conversationId,attachmentId,JsonFields.string(o,"filename").orEmpty(),JsonFields.string(o,"mediaType").orEmpty(),size,
            JsonFields.string(o,"sha256") ?: error("HISTORY_MEDIA_INVALID"),JsonFields.bool(o,"remoteAvailable")==true,size*4/3+2048)
    }
    override suspend fun retain(metadata:HistoricalMediaMetadata)=withContext(Dispatchers.IO) {
        if (isRetained(metadata.attachmentId)) return@withContext
        check(metadata.remoteAvailable) { "REMOTE_UNAVAILABLE" }
        check(metadata.sizeBytes<=8*1024*1024) { "MEDIA_TOO_LARGE_FOR_CACHE" }
        check(usedBytes+metadata.estimatedLocalBytes<=quotaBytes) { "MEDIA_CACHE_QUOTA_EXCEEDED:请先只清媒体" }
        val grant=request("POST",target(metadata.conversationId,metadata.attachmentId,"cache-grant"),"{}".toByteArray())
        val id=JsonFields.string(grant,"grantId") ?: error("MEDIA_GRANT_INVALID")
        require(id.matches(Regex("[A-Za-z0-9_-]{43}")))
        val content=request("GET",target(metadata.conversationId,metadata.attachmentId,"content")+"?grantId=$id")
        check(JsonFields.string(content,"mediaType")==metadata.mediaType) { "MEDIA_TYPE_MISMATCH" }
        val bytes=Base64.getDecoder().decode(JsonFields.string(content,"contentBase64") ?: error("MEDIA_CONTENT_INVALID"))
        try {
            val sha="sha256:"+MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(bytes.size.toLong()==metadata.sizeBytes && sha==metadata.sha256) { "MEDIA_INTEGRITY_FAILED" }
            synchronized(this@EncryptedHistoryMediaCache) {
                if (isRetained(metadata.attachmentId)) return@synchronized
                check(usedBytes+metadata.estimatedLocalBytes<=quotaBytes) { "MEDIA_CACHE_QUOTA_EXCEEDED:请先只清媒体" }
                documents.write("content:${metadata.attachmentId}",bytes)
                val preview=previewBytes(bytes,metadata.mediaType)
                if (preview!=null) documents.write("preview:${metadata.attachmentId}",preview)
                documents.write("metadata:${metadata.attachmentId}",Json.canonical(Json.of(mapOf("messageScope" to metadata.conversationId,"sha256" to sha,"filename" to metadata.filename,"mediaType" to metadata.mediaType,"sizeBytes" to metadata.sizeBytes))).toByteArray())
                if (usedBytes>quotaBytes) { remove(metadata.attachmentId); error("MEDIA_CACHE_QUOTA_EXCEEDED:请先只清媒体") }
            }
        } finally { bytes.fill(0) }
    }
    fun reconcile(ids:Set<String>) { documents.keys().filter { it.startsWith("metadata:") }.map { it.removePrefix("metadata:") }.filter { it !in ids }.forEach(::remove) }
    override fun isRetained(attachmentId:String)=documents.read("metadata:$attachmentId")!=null
    override fun preview(attachmentId:String)=documents.read("preview:$attachmentId")
    override fun remove(attachmentId:String) { documents.delete("content:$attachmentId"); documents.delete("preview:$attachmentId"); documents.delete("metadata:$attachmentId") }
    private fun previewBytes(bytes:ByteArray,type:String):ByteArray? {
        if (!type.startsWith("image/")) return null
        val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
        android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if (bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth.toLong()*bounds.outHeight>64_000_000L) return null
        val factor=(maxOf(bounds.outWidth,bounds.outHeight)/512).coerceAtLeast(1)
        val bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,android.graphics.BitmapFactory.Options().apply { inSampleSize=factor }) ?: return null
        try { return java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,70,it) }.toByteArray().takeIf { it.size<=256*1024 } }
        finally { bitmap.recycle() }
    }
}
