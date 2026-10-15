package com.openandroidintelligence.conversation.model

enum class AttachmentLocalState { PENDING, SAVED, UNAVAILABLE, FAILED }
enum class AttachmentDownloadState { IDLE, DOWNLOADING, SAVED, FAILED }

/** 消息附件元数据、有限缩略图以及独立的本地保存与用户下载状态。 */
data class TimelineAttachment(
    val draftId: String,
    val filename: String = "",
    val mediaType: String = "",
    val previewBytes: ByteArray? = null,
    val savedOffline: Boolean = false,
    val localState: AttachmentLocalState = if (savedOffline) AttachmentLocalState.SAVED else AttachmentLocalState.PENDING,
    val downloadState: AttachmentDownloadState = AttachmentDownloadState.IDLE,
    val localError: String? = null,
    val downloadError: String? = null,
) {
    val isImage: Boolean
        get() = mediaType.startsWith("image/", ignoreCase = true) ||
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
        if (savedOffline != other.savedOffline) return false
        if (localState != other.localState || downloadState != other.downloadState ||
            localError != other.localError || downloadError != other.downloadError) return false
        if (draftId != other.draftId) return false
        if (filename != other.filename) return false
        if (mediaType != other.mediaType) return false
        if (previewBytes != null) {
            if (other.previewBytes == null) return false
            if (!previewBytes.contentEquals(other.previewBytes)) return false
        } else if (other.previewBytes != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = 31*draftId.hashCode()+savedOffline.hashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + (previewBytes?.contentHashCode() ?: 0)
        result = 31 * result + localState.hashCode()
        result = 31 * result + downloadState.hashCode()
        result = 31 * result + (localError?.hashCode() ?: 0)
        result = 31 * result + (downloadError?.hashCode() ?: 0)
        return result
    }
}
