package com.openandroidintelligence.conversation.model

/**
 * Metadata and optional image preview payload for an attachment associated with a timeline entry.
 */
data class TimelineAttachment(
    val draftId: String,
    val filename: String = "",
    val mediaType: String = "",
    val imageBytes: ByteArray? = null,
) {
    val isImage: Boolean
        get() = mediaType.startsWith("image/") ||
            filename.endsWith(".jpg", ignoreCase = true) ||
            filename.endsWith(".jpeg", ignoreCase = true) ||
            filename.endsWith(".png", ignoreCase = true) ||
            filename.endsWith(".webp", ignoreCase = true) ||
            filename.endsWith(".gif", ignoreCase = true) ||
            filename.endsWith(".heic", ignoreCase = true) ||
            filename.endsWith(".heif", ignoreCase = true)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TimelineAttachment
        if (draftId != other.draftId) return false
        if (filename != other.filename) return false
        if (mediaType != other.mediaType) return false
        if (imageBytes != null) {
            if (other.imageBytes == null) return false
            if (!imageBytes.contentEquals(other.imageBytes)) return false
        } else if (other.imageBytes != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = draftId.hashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + (imageBytes?.contentHashCode() ?: 0)
        return result
    }
}
