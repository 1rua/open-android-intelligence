package com.openandroidintelligence.conversation.model

sealed interface MessagePart {
    data class Text(val value: String) : MessagePart
    data class Attachment(
        val draftId: AttachmentDraftId,
        val filename: String = "",
        val mediaType: String = "",
        val sizeBytes: Long? = null,
        val sha256: String? = null,
    ) : MessagePart {
        init {
            require(sizeBytes == null || sizeBytes >= 0) { "ATTACHMENT_METADATA_INVALID:length" }
            require(sha256 == null || sha256.matches(Regex("sha256:[0-9a-f]{64}"))) { "ATTACHMENT_METADATA_INVALID:digest" }
        }
    }
    data class Command(val rawText: String, val catalogVersion: CatalogVersion? = null) : MessagePart
}
