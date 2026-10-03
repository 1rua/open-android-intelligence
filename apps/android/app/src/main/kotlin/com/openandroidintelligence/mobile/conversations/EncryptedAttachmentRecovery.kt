package com.openandroidintelligence.mobile.conversations

import android.content.Context
import com.openandroidintelligence.mobile.plugins.EncryptedDocuments
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.gateway.schema.*

class EncryptedAttachmentRecovery(context: Context, scope: ConversationScope,keyProvider:com.openandroidintelligence.encrypted.store.AesGcmKeyProvider?=null) : AttachmentDraftRecoveryStore {
    private val documentScope="attachment-map-v1:"+Json.canonical(Json.of(listOf(scope.profileId,scope.gatewayId,scope.accountId,scope.installId)))
    private val documents=if (keyProvider==null) EncryptedDocuments(context,documentScope) else EncryptedDocuments(context,documentScope,keyProvider)
    override fun load(): List<RecoveredAttachmentDraft> {
        val bytes=documents.read("drafts") ?: return emptyList()
        val o=JsonFields.obj(Json.parse(bytes.decodeToString())) ?: error("ATTACHMENT_MAP_INVALID")
        return JsonFields.objects(o,"drafts").map { row ->
            fun s(key:String)=JsonFields.string(row,key) ?: error("ATTACHMENT_MAP_INVALID")
            val size=JsonFields.long(row,"size") ?: error("ATTACHMENT_MAP_INVALID")
            RecoveredAttachmentDraft(AttachmentDraft(AttachmentDraftId(s("id")),s("filename"),s("mediaType"),size,s("sha256")),
                StagedAttachmentContent(s("stagedId"),size,s("sha256")),JsonFields.string(row,"remoteId"))
        }
    }
    override fun save(records: List<RecoveredAttachmentDraft>) { documents.write("drafts",Json.canonical(Json.of(mapOf("drafts" to records.map { r ->
        mapOf("id" to r.draft.id.value,"filename" to r.draft.filename,"mediaType" to r.draft.mediaType,"size" to r.content.sizeBytes,
            "sha256" to r.content.sha256Hex,"stagedId" to r.content.id,"remoteId" to r.remoteId)
    }))).toByteArray()) }
    fun wipe()=documents.erase()
}
