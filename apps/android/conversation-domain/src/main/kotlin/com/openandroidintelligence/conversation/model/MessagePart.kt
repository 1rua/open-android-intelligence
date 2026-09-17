package com.openandroidintelligence.conversation.model

sealed interface MessagePart {
    data class Text(val value: String) : MessagePart
    data class Attachment(
        val draftId: AttachmentDraftId,
        val filename: String = "",
        val mediaType: String = "",
    ) : MessagePart
    data class Command(val rawText: String, val catalogVersion: CatalogVersion? = null) : MessagePart
}
