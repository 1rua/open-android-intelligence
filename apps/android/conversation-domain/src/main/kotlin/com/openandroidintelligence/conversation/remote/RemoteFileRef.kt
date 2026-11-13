package com.openandroidintelligence.conversation.remote

/**
 * Host 确认发布的工作区文件引用；path 保留返回值，不能用上传成功推断图片注入成功。
 * 文件随持久会话隔离，重新连接不改变历史文件身份；活跃上传回调另行核对 SessionOwner。
 */
data class RemoteFileRef(
    val session: SessionKey,
    val path: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val contentVersion: String? = null,
) {
    init {
        require(path.isNotEmpty() && '\u0000' !in path && '\\' !in path) { "工作区文件路径无效" }
        require(!path.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(path)) { "工作区文件不能使用绝对路径" }
        val segments = path.removePrefix("./").split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "工作区文件路径不能越界或含歧义段" }
        require(sizeBytes == null || sizeBytes >= 0) { "文件大小不能为负数" }
        require(sha256 == null || sha256.matches(Regex("sha256:[0-9a-f]{64}"))) { "文件摘要无效" }
        require(contentVersion == null || contentVersion.isNotEmpty()) { "文件版本不能为空" }
    }

    fun belongsTo(target: SessionKey): Boolean = session == target
}
