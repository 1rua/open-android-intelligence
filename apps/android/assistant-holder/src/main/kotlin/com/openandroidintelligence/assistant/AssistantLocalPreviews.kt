package com.openandroidintelligence.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember

/**
 * 会话级本地缩略图存储：`localKey → 缩略图字节`。
 *
 * 为什么需要它：桥接协议 v2 不传附件字节。Binder 事务有约 1MB 的硬上限，圈选截图
 * 又属于屏幕内容，跨进程复制既不可靠也不必要。助理进程自己产生的缩略图（本进程圈选 /
 * 裁剪出的图片）就留在自己的内存里，按协议里的 `localKey` 取回；主进程发布回来的
 * 附件只有元数据，取不到预览时界面显示「预览不可用」，而不是伪造一张图。
 *
 * 生命周期：**进程内内存，随会话销毁**。宿主在会话开始时用
 * [rememberAssistantLocalPreviews] 创建，会话切换/结束时 [close]；不持久化、不跨进程、
 * 不会在下一个助理会话里复用上一个会话的屏幕内容。
 *
 * 边界：
 * - 最多保留 [MAX_ENTRIES] 张缩略图、合计 [MAX_TOTAL_BYTES] 字节，超出按最近最少使用
 *   淘汰；淘汰与清空时把字节清零，屏幕内容不在进程里多留一份；
 * - 单张超过总预算的图片不保存（返回 false），界面显示「预览不可用」而不是把内存吃满；
 * - 写入时复制调用方的数组，读取返回的是内部只读引用（渲染层会自行复制后解码）。
 *
 * 注意：协议里的 `localKey` 当前就是附件草稿 ID（`draftId`），因此渲染层用
 * `attachment.draftId` 查询即可；两者不一致时由宿主在写入侧统一，查询语义不变。
 */
internal class AssistantLocalPreviews : AutoCloseable {

    private val lock = Any()

    /** accessOrder = true：读取也算最近使用，淘汰从最久未用的开始。 */
    private val thumbnails = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private val voiceDurations = HashMap<String, Long>()
    private var totalBytes = 0
    private var closed = false

    /**
     * 已发送、但还没等到主进程回传权威附件 ID 的圈选键（先进先出）。
     *
     * 圈选产物在助理进程里只能先落在一个**本进程临时键**上：真正的附件草稿 ID 由主进程
     * 的附件协调器生成，协议回传的 `localKey` 就是那个 ID。两者不一致，缩略图就永远查
     * 不到。这里记录「这一段字节在等哪一条附件」，等状态帧带来新附件时再把字节改挂到
     * 权威 ID 上。
     */
    private val pendingImageKeys = ArrayDeque<String>()

    /**
     * 修订号：缩略图或时长变化时递增。
     *
     * 它是 Compose 状态，界面在组合期读取后，写入方（录音、圈选回调）更新存储会让
     * 时间线重新解析预览，而不必等下一次桥接状态帧。存储本身仍可在非组合线程写入。
     */
    private val revisionState = mutableIntStateOf(0)

    /** 当前修订号；在组合中读取即可跟踪后续写入。 */
    val revision: Int get() = revisionState.intValue

    /**
     * 记录「这一段缩略图字节在等主进程回传权威附件 ID」。
     *
     * 圈选产物由助理进程裁剪，落库后的草稿 ID 只有主进程知道。发送时把本地键登记进来，
     * 等状态帧带来新附件时用 [adoptPendingImage] 把字节改挂到权威 ID 上，界面才查得到
     * 缩略图。键已存在时重复登记不会产生第二条记录。
     */
    fun markPendingImage(localKey: String) {
        if (localKey.isBlank()) return
        synchronized(lock) {
            if (closed) return
            if (localKey in pendingImageKeys) return
            pendingImageKeys.addLast(localKey)
        }
    }

    /**
     * 把最早的待认领缩略图挂到主进程回传的权威附件 ID 上。
     *
     * 只在本次状态帧里**新出现**的图片附件上调用：调用方保证同一个权威 ID 只认领一次，
     * 否则后来的附件会把前一张图的字节抢走。没有待认领项或本地没有对应字节时返回 false，
     * 界面继续显示「预览不可用」而不是画一张假图。
     */
    fun adoptPendingImage(authoritativeKey: String): Boolean {
        if (authoritativeKey.isBlank()) return false
        synchronized(lock) {
            if (closed) return false
            while (pendingImageKeys.isNotEmpty()) {
                val localKey = pendingImageKeys.removeFirst()
                if (localKey == authoritativeKey) return false
                val bytes = thumbnails.remove(localKey) ?: continue
                totalBytes -= bytes.size
                thumbnails[authoritativeKey] = bytes
                totalBytes += bytes.size
                evictLocked()
                revisionState.intValue++
                return true
            }
            return false
        }
    }

    /** 当前保存的缩略图数量（测试/诊断用）。 */
    val size: Int get() = synchronized(lock) { thumbnails.size }

    /** 当前占用的总字节数（测试/诊断用）。 */
    val bytes: Int get() = synchronized(lock) { totalBytes }

    /**
     * 保存一张缩略图。
     *
     * @param localKey 协议里的本地定位键（当前等于附件草稿 ID）。
     * @param bytes 缩略图字节；内部会复制一份，调用方随后可以安全清零自己的副本。
     * @return 是否保存成功。空键、空字节、已关闭或单张超过总预算时返回 false。
     */
    fun put(localKey: String, bytes: ByteArray?): Boolean {
        if (localKey.isBlank() || bytes == null || bytes.isEmpty()) return false
        if (bytes.size > MAX_TOTAL_BYTES) return false
        val copy = bytes.copyOf()
        synchronized(lock) {
            if (closed) return false
            thumbnails.remove(localKey)?.let { totalBytes -= it.size }
            thumbnails[localKey] = copy
            totalBytes += copy.size
            evictLocked()
            revisionState.intValue++
        }
        return true
    }

    /**
     * 读取缩略图字节。
     *
     * 返回的是存储内部的只读引用：渲染层（`rememberImagePreviewState`）会先复制再解码，
     * 调用方不得原地修改；需要长期保存时请自行复制。
     */
    fun thumbnail(localKey: String): ByteArray? =
        if (localKey.isBlank()) null else synchronized(lock) { thumbnails[localKey] }

    /**
     * 记录一条本进程录音的真实时长，供时间线里的语音芯片显示。
     *
     * 只接受正数：0 或负数表示「不知道」而不是「0 秒」，界面因此不显示一个编造的值。
     * 时长只属于当前会话，随 [close] 一起清空。
     */
    fun putVoiceDuration(localKey: String, durationMillis: Long) {
        if (localKey.isBlank() || durationMillis <= 0L) return
        synchronized(lock) {
            if (closed) return
            if (voiceDurations[localKey] == durationMillis) return
            voiceDurations[localKey] = durationMillis
            revisionState.intValue++
        }
    }

    /** 本进程记录的语音时长（毫秒）；没有记录时返回 null。 */
    fun voiceDuration(localKey: String): Long? =
        if (localKey.isBlank()) null else synchronized(lock) { voiceDurations[localKey] }

    /** 丢弃一张缩略图（附件被移除时调用），并清零它占用的内存。 */
    fun remove(localKey: String) {
        synchronized(lock) {
            val removed = thumbnails.remove(localKey) ?: return
            totalBytes -= removed.size
            removed.fill(0)
            revisionState.intValue++
        }
    }

    /** 清空全部缩略图与时长；存储仍可继续使用。 */
    fun clear() {
        synchronized(lock) {
            thumbnails.values.forEach { it.fill(0) }
            thumbnails.clear()
            totalBytes = 0
            voiceDurations.clear()
            pendingImageKeys.clear()
            revisionState.intValue++
        }
    }

    /**
     * 会话结束：清空内容并拒绝后续写入。
     *
     * 之后迟到的截图/录音回调不会把内容塞回一个已经结束的会话（与桥接层丢弃迟到
     * 附件的语义一致）。
     */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            thumbnails.values.forEach { it.fill(0) }
            thumbnails.clear()
            totalBytes = 0
            voiceDurations.clear()
            pendingImageKeys.clear()
            revisionState.intValue++
        }
    }

    private fun evictLocked() {
        while (thumbnails.size > MAX_ENTRIES) evictEldestLocked()
        while (totalBytes > MAX_TOTAL_BYTES) evictEldestLocked()
    }

    private fun evictEldestLocked() {
        val iterator = thumbnails.entries.iterator()
        if (!iterator.hasNext()) return
        val eldest = iterator.next()
        iterator.remove()
        totalBytes -= eldest.value.size
        eldest.value.fill(0)
    }

    internal companion object {
        /** 最多保留的缩略图数量：一屏多的历史足够，不会随会话无限增长。 */
        const val MAX_ENTRIES = 16

        /** 缩略图总预算：4MB 足够十几张压缩小图，又不会在系统助理进程里留下大块内存。 */
        const val MAX_TOTAL_BYTES = 4 * 1024 * 1024
    }
}

/**
 * 会话级 [AssistantLocalPreviews]：会话身份变化时重建，旧存储关闭并清零。
 *
 * 传给它的 key 应当是助理会话身份（例如桥接协议里的 `sessionId`）。key 为 null
 * 时同样提供一个可用的空存储，避免界面层到处判空。
 */
@Composable
internal fun rememberAssistantLocalPreviews(sessionKey: String?): AssistantLocalPreviews {
    val previews = remember(sessionKey) { AssistantLocalPreviews() }
    DisposableEffect(previews) {
        onDispose { previews.close() }
    }
    return previews
}
