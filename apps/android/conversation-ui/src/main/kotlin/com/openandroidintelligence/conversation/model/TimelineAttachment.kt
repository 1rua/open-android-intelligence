package com.openandroidintelligence.conversation.model

enum class AttachmentLocalState { PENDING, SAVED, UNAVAILABLE, FAILED }
enum class AttachmentDownloadState { IDLE, DOWNLOADING, SAVED, FAILED }

/**
 * 原图的加载状态。
 *
 * 缩略图可以随消息一起同步，原图则必须按需向媒体端口取：一张原图可能几十 MB，
 * 而用户只会在打开大图预览时才需要它。界面因此必须能区分「还没请求」「正在读」
 * 「已经可用」与「取不到」，否则加载失败会被误画成一张永久空白。
 */
enum class OriginalImageState { IDLE, LOADING, READY, FAILED }

/** 消息附件元数据、有限缩略图以及独立的本地保存、用户下载与原图加载状态。 */
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
    /**
     * 已解码到展示尺寸的原图（控制器按最长边 2048px、JPEG 85 压缩后的字节）。
     *
     * 它只在大图预览打开过之后才存在；为 null 表示当前只有 [previewBytes] 可看。
     * 原图字节由控制器按最近使用缓存持有，淘汰时会被清零，界面不得假设它长期有效。
     */
    val originalBytes: ByteArray? = null,
    val originalState: OriginalImageState = OriginalImageState.IDLE,
    val originalError: String? = null,
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
        if (originalState != other.originalState || originalError != other.originalError) return false
        if (draftId != other.draftId) return false
        if (filename != other.filename) return false
        if (mediaType != other.mediaType) return false
        // 字节数组按内容比较：repaint 会重复构造附件对象，引用比较会让每一帧都被当成新数据。
        // 同一个缓存数组通常以同一引用反复出现，先做身份短路，避免流式期间反复扫描大数组。
        if (previewBytes !== other.previewBytes) {
            if (previewBytes != null) {
                if (other.previewBytes == null) return false
                if (!previewBytes.contentEquals(other.previewBytes)) return false
            } else if (other.previewBytes != null) return false
        }
        if (originalBytes !== other.originalBytes) {
            if (originalBytes != null) {
                if (other.originalBytes == null) return false
                if (!originalBytes.contentEquals(other.originalBytes)) return false
            } else if (other.originalBytes != null) return false
        }

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
        result = 31 * result + (originalBytes?.contentHashCode() ?: 0)
        result = 31 * result + originalState.hashCode()
        result = 31 * result + (originalError?.hashCode() ?: 0)
        return result
    }
}
