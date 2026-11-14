package com.openandroidintelligence.mobile

import android.app.Application
import android.content.ContentUris
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.data.remote.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.mobile.conversations.AndroidAttachmentDownloads
import com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** 真实 Remote 媒体源接加密原件库及系统导出；Host 载体形状另由固定上游 probe 核实。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemoteHistoryMediaCacheTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val keys = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 76 }, "AES")
        override fun delete() = Unit
    }
    private val scope = ConversationScope("remote-media", "endpoint", "account", "install")
    private val conversation = RemoteLocalIds.encode("session")
    private val original = ByteArray(600_003) { (it % 251).toByte() }
    private fun obj(vararg fields: Pair<String, Any?>) = Json.of(linkedMapOf(*fields)) as JsonValue.JObject
    private fun field(value: JsonValue?, key: String) = JsonFields.field(JsonFields.obj(value), key)
    private fun text(value: JsonValue?, key: String) = JsonFields.string(JsonFields.obj(value), key)
    private class Channel : RemoteRpcChannel {
        override val scope = RemoteRpcConnectionScope("https://remote.example", "account", "host", "phone", "connection", 1)
        override val capabilities = setOf("harness.remote.v3")
        override val events = emptyFlow<RemoteRpcEvent>()
        var result: (JsonValue.JObject) -> JsonValue = { error("未配置真实载体回执") }
        var reads = 0
        override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue {
            check(method == "harness.remote.call")
            return result(params)
        }
        override suspend fun close() = Unit
    }
    private data class Fixture(val channel: Channel, val source: RemoteAuthenticatedMediaSource, val id: String, val cache: EncryptedHistoryMediaCache)
    private fun fixture(current: () -> Boolean = { true }, beforeChunk: () -> Unit = {}, afterReferenceCommit: (() -> Unit)? = null): Fixture {
        val channel = Channel()
        val endpoint = EndpointKey(RemoteServiceOrigin.fromCanonicalOrigin(channel.scope.origin), "account", "host", BackendId("harness"), "workspace")
        val source = RemoteAuthenticatedMediaSource(HarnessSessionCarrier(channel), RemoteWorkspace(AgentEndpoint(endpoint, "工作区"), "/work", setOf("session")), current)
        val id = source.accept(conversation, obj("type" to "file", "path" to "report.bin", "name" to "报告原件.bin", "mimeType" to "application/octet-stream"), RemoteMediaRevision("message", 10, true))!!.draftId.value
        channel.result = { request ->
            val args = field(field(request, "payload"), "args")
            check(text(args, "workspaceFileScopeId") == "session" && text(args, "path") == "report.bin")
            val identity = listOf("absolutePath" to "/work/report.bin", "version" to "version-one", "bytes" to original.size)
            val value = if (text(request, "endpoint") == "workspaceFiles/stat") obj(*identity.toTypedArray()) else {
                channel.reads++
                val range = field(args, "range")
                val offset = JsonFields.long(JsonFields.obj(range), "offset")!!.toInt()
                val length = JsonFields.long(JsonFields.obj(range), "length")!!.toInt()
                val end = minOf(original.size, offset + length)
                beforeChunk()
                obj(*(identity + listOf("offset" to offset, "data" to Base64.getEncoder().encodeToString(original.copyOfRange(offset, end)), "eof" to (end == original.size))).toTypedArray())
            }
            obj("ok" to true, "value" to value)
        }
        return Fixture(channel, source, id, EncryptedHistoryMediaCache(context, scope, source, keys, afterReferenceCommit = afterReferenceCommit))
    }
    @Test fun `Remote原件分块落盘后离线读取并导出真实字节`() = runBlocking {
        val fixture = fixture()
        val metadata = fixture.cache.ensureLocal(conversation, fixture.id)
        assertEquals(original.size.toLong(), metadata.sizeBytes)
        assertEquals("sha256:" + MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }, metadata.sha256)
        assertEquals("application/octet-stream", metadata.mediaType)
        assertEquals(3, fixture.channel.reads)
        fixture.source.close()
        val offline = EncryptedHistoryMediaCache(context, scope, keyProvider = keys)
        assertArrayEquals(original, offline.openOriginal(conversation, fixture.id).use { it.readBytes() })
        val provider = AndroidAttachmentDownloadsTest.DownloadProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val exported = AndroidAttachmentDownloads(context, offline, scope, keys).download(conversation, fixture.id)
        assertEquals("报告原件.bin", exported.filename)
        assertArrayEquals(original, context.contentResolver.openInputStream(Uri.parse(exported.uri))!!.use { it.readBytes() })
        assertEquals(0, provider.rows.getValue(ContentUris.parseId(Uri.parse(exported.uri))).values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(3, fixture.channel.reads)
        offline.clearMedia()
    }
    @Test fun `相同内容的高水位独立持久并在加密重开后拒绝较旧不同内容`() = runBlocking {
        val fixture = fixture()
        val cached = fixture.cache.ensureLocal(conversation, fixture.id)
        val epoch = fixture.cache.originalEpoch(conversation, fixture.id)
        val a = obj("type" to "file", "path" to "report.bin", "name" to "报告原件.bin", "mimeType" to "application/octet-stream")
        fixture.source.accept(conversation, a, RemoteMediaRevision("message", 20, true))
        assertEquals(cached.sourceVersion, fixture.source.referenceVersion(conversation, fixture.id))
        assertEquals(epoch, fixture.cache.originalEpoch(conversation, fixture.id))
        assertEquals(3, fixture.channel.reads)
        fixture.source.close(); fixture.cache.retireConnection()
        val channel = Channel()
        val endpoint = EndpointKey(RemoteServiceOrigin.fromCanonicalOrigin(channel.scope.origin), "account", "host", BackendId("harness"), "workspace")
        val reconnect = RemoteAuthenticatedMediaSource(HarnessSessionCarrier(channel), RemoteWorkspace(AgentEndpoint(endpoint, "工作区"), "/work", setOf("session"))) { true }
        val reopened = EncryptedHistoryMediaCache(context, scope, reconnect, keys)
        val b = obj("type" to "file", "path" to "report.bin", "name" to "旧B原件.bin", "mimeType" to "application/octet-stream")
        assertEquals("REMOTE_MEDIA_REVISION_STALE", runCatching { reconnect.accept(conversation, b, RemoteMediaRevision("message", 15, true)) }.exceptionOrNull()?.message)
        assertEquals(cached.sourceVersion, reopened.localMetadata(conversation, fixture.id)!!.sourceVersion)
        reconnect.accept(conversation, a, RemoteMediaRevision("message", 20, true))
        assertEquals(cached.sourceVersion, reopened.ensureLocal(conversation, fixture.id).sourceVersion)
        assertEquals(0, channel.reads)
        assertArrayEquals(original, reopened.openOriginal(conversation, fixture.id).use { it.readBytes() })
        reopened.clearMedia()
    }
    private class SimulatedReferenceProcessDeath : Error("模拟原子来源提交后进程中断")
    @Test fun `来源原子提交后中断重开可恢复同序号新内容并拒绝旧原件`() = runBlocking {
        var terminate = false
        val fixture = fixture(afterReferenceCommit = { if (terminate) throw SimulatedReferenceProcessDeath() })
        fixture.cache.ensureLocal(conversation, fixture.id)
        val previousEpoch = fixture.cache.originalEpoch(conversation, fixture.id)
        val next = obj("type" to "file", "path" to "report.bin", "name" to "新B原件.bin", "mimeType" to "application/octet-stream")
        terminate = true
        assertTrue(runCatching { fixture.source.accept(conversation, next, RemoteMediaRevision("message", 20, true)) }.exceptionOrNull() is SimulatedReferenceProcessDeath)
        assertNotNull(runCatching { fixture.cache.withOriginalEpoch(conversation, fixture.id, previousEpoch) {} }.exceptionOrNull())
        fixture.source.close(); fixture.cache.retireConnection()
        val channel = Channel().also { it.result = fixture.channel.result }
        val endpoint = EndpointKey(RemoteServiceOrigin.fromCanonicalOrigin(channel.scope.origin), "account", "host", BackendId("harness"), "workspace")
        val reconnect = RemoteAuthenticatedMediaSource(HarnessSessionCarrier(channel), RemoteWorkspace(AgentEndpoint(endpoint, "工作区"), "/work", setOf("session"))) { true }
        val reopened = EncryptedHistoryMediaCache(context, scope, reconnect, keys)
        assertNull(reopened.localMetadata(conversation, fixture.id))
        assertEquals("REMOTE_MEDIA_REVISION_STALE", runCatching {
            reconnect.accept(conversation, obj("type" to "file", "path" to "report.bin", "name" to "报告原件.bin", "mimeType" to "application/octet-stream"), RemoteMediaRevision("message", 10, true))
        }.exceptionOrNull()?.message)
        reconnect.accept(conversation, next, RemoteMediaRevision("message", 20, true))
        assertEquals("新B原件.bin", reopened.ensureLocal(conversation, fixture.id).filename)
        assertArrayEquals(original, reopened.openOriginal(conversation, fixture.id).use { it.readBytes() })
        reopened.remove(conversation, fixture.id)
        reconnect.accept(conversation, obj("type" to "file", "path" to "report.bin", "name" to "更高C原件.bin", "mimeType" to "application/octet-stream"), RemoteMediaRevision("message", 30, false))
        assertNotNull(runCatching { reopened.ensureLocal(conversation, fixture.id) }.exceptionOrNull())
        reopened.clearMedia()
    }
    @Test fun `同附件更高修订持久废止旧缓存及导出代数并只保存新版`() = runBlocking {
        val fixture = fixture()
        val before = fixture.cache.ensureLocal(conversation, fixture.id)
        val oldEpoch = fixture.cache.originalEpoch(conversation, fixture.id)
        val oldStream = fixture.cache.openOriginal(conversation, fixture.id)
        assertTrue(oldStream.read() >= 0)
        original[0] = (original[0] + 1).toByte()
        val digest = "sha256:" + MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
        val newer = obj("type" to "file", "path" to "report.bin", "name" to "报告原件.bin", "mimeType" to "application/octet-stream",
            "sizeBytes" to original.size, "sha256" to digest)
        fixture.source.accept(conversation, newer, RemoteMediaRevision("message", 11, false))
        assertNotNull(runCatching { fixture.cache.withOriginalEpoch(conversation, fixture.id, oldEpoch) {} }.exceptionOrNull())
        oldStream.use { assertNotNull(runCatching { it.readBytes() }.exceptionOrNull()) }
        assertNull(fixture.cache.localMetadata(conversation, fixture.id))
        // 即使进程此刻重启且尚未接新 source，已失效旧原件也不能离线复活。
        val offline = EncryptedHistoryMediaCache(context, scope, keyProvider = keys)
        assertNull(offline.localMetadata(conversation, fixture.id))
        fixture.source.accept(conversation, obj("type" to "file", "path" to "report.bin", "name" to "报告原件.bin", "mimeType" to "application/octet-stream"),
            RemoteMediaRevision("message", 10, true))
        val after = fixture.cache.ensureLocal(conversation, fixture.id)
        assertNotEquals(before.sourceVersion, after.sourceVersion)
        assertEquals(digest, after.sha256)
        assertArrayEquals(original, fixture.cache.openOriginal(conversation, fixture.id).use { it.readBytes() })
        fixture.cache.remove(conversation, fixture.id)
        fixture.source.accept(conversation, newer, RemoteMediaRevision("message", 12, false))
        assertNotNull(runCatching { fixture.cache.ensureLocal(conversation, fixture.id) }.exceptionOrNull())
        fixture.cache.clearMedia()
    }
    @Test fun `重建来源后旧历史序号仍不能覆盖持久的新版本`() = runBlocking {
        val fixture = fixture()
        fixture.cache.ensureLocal(conversation, fixture.id)
        val newer = obj("type" to "file", "path" to "report.bin", "name" to "新版原件.bin", "mimeType" to "application/octet-stream")
        fixture.source.accept(conversation, newer, RemoteMediaRevision("message", 11, false))
        val newest = fixture.cache.ensureLocal(conversation, fixture.id)
        fixture.source.close(); fixture.cache.retireConnection()
        val channel = Channel()
        val endpoint = EndpointKey(RemoteServiceOrigin.fromCanonicalOrigin(channel.scope.origin), "account", "host", BackendId("harness"), "workspace")
        val reconnect = RemoteAuthenticatedMediaSource(HarnessSessionCarrier(channel), RemoteWorkspace(AgentEndpoint(endpoint, "工作区"), "/work", setOf("session"))) { true }
        val reopened = EncryptedHistoryMediaCache(context, scope, reconnect, keys)
        assertEquals("REMOTE_MEDIA_REVISION_STALE", runCatching {
            reconnect.accept(conversation, obj("type" to "file", "path" to "report.bin", "name" to "报告原件.bin", "mimeType" to "application/octet-stream"),
                RemoteMediaRevision("message", 10, true))
        }.exceptionOrNull()?.message)
        assertEquals(newest.sourceVersion, reopened.localMetadata(conversation, fixture.id)!!.sourceVersion)
        assertArrayEquals(original, reopened.openOriginal(conversation, fixture.id).use { it.readBytes() })
        reopened.clearMedia()
    }
    @Test fun `原件复制完成后的新修订阻止系统下载最终发布`() = runBlocking {
        val fixture = fixture()
        fixture.cache.ensureLocal(conversation, fixture.id)
        val provider = AndroidAttachmentDownloadsTest.DownloadProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        var updated = false
        val media = object : HistoricalMediaPort by fixture.cache {
            override suspend fun openOriginal(conversationId: String, attachmentId: String): java.io.InputStream {
                val originalStream = fixture.cache.openOriginal(conversationId, attachmentId)
                return object : java.io.FilterInputStream(originalStream) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { count ->
                        if (count < 0 && !updated) {
                            updated = true
                            fixture.source.accept(conversation, obj("type" to "file", "path" to "report.bin", "name" to "新版原件.bin", "mimeType" to "application/octet-stream"),
                                RemoteMediaRevision("message", 11, false))
                        }
                    }
                }
            }
        }
        assertNotNull(runCatching { AndroidAttachmentDownloads(context, media, scope, keys).download(conversation, fixture.id) }.exceptionOrNull())
        assertTrue(updated)
        assertEquals(1, provider.rows.size)
        val rejected = provider.rows.values.single()
        assertArrayEquals("新修订在完整原件已复制后才到达", original, rejected.file.readBytes())
        assertEquals("过期下载必须回收，不能作为普通文件发布", 1, rejected.values.getAsInteger(MediaStore.MediaColumns.IS_TRASHED))
        assertEquals("回滚完成后退出系统待发布状态", 0, rejected.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertFalse("没有未回收的可见下载", provider.rows.values.any {
            it.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0 &&
                it.values.getAsInteger(MediaStore.MediaColumns.IS_TRASHED) != 1
        })
        fixture.cache.clearMedia()
    }
    @Test fun `更高修订到达时读取中的旧原件不会写入缓存`() = runBlocking {
        lateinit var fixture: Fixture
        fixture = fixture(beforeChunk = {
            fixture.source.accept(conversation, obj("type" to "file", "path" to "report.bin", "name" to "新版原件.bin", "mimeType" to "application/octet-stream"),
                RemoteMediaRevision("message", 11, false))
        })
        assertNotNull(runCatching { fixture.cache.ensureLocal(conversation, fixture.id) }.exceptionOrNull())
        assertNull(fixture.cache.localMetadata(conversation, fixture.id))
        assertEquals("新版原件.bin", fixture.cache.ensureLocal(conversation, fixture.id).filename)
        assertArrayEquals(original, fixture.cache.openOriginal(conversation, fixture.id).use { it.readBytes() })
        fixture.cache.clearMedia()
    }
    @Test fun `上传回执按真实摘要重绑已归档原件而不丢唯一离线副本`() = runBlocking {
        val fixture = fixture()
        val digest = "sha256:" + MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
        fixture.cache.archiveSubmitted(conversation, "draft", "已提交原件.pdf", "application/pdf", AttachmentContentSource {
            java.io.ByteArrayInputStream(original)
        }, original.size.toLong(), digest, "draft")
        val before = fixture.cache.originalEpoch(conversation, "draft")
        fixture.source.submitted(conversation, "draft", "/work/report.bin", "已提交原件.pdf", "application/pdf", original.size.toLong(), digest)
        assertNotNull(runCatching { fixture.cache.withOriginalEpoch(conversation, "draft", before) {} }.exceptionOrNull())
        val local = fixture.cache.ensureLocal(conversation, "draft")
        assertEquals(fixture.source.referenceVersion(conversation, "draft"), local.sourceVersion)
        assertArrayEquals(original, fixture.cache.openOriginal(conversation, "draft").use { it.readBytes() })
        assertEquals(0, fixture.channel.reads)
        fixture.cache.clearMedia()
    }
    @Test fun `切换账户后的迟到分块不能进入新缓存`() = runBlocking {
        var active = true
        val fixture = fixture(current = { active }, beforeChunk = { active = false })
        assertNotNull(runCatching { fixture.cache.ensureLocal(conversation, fixture.id) }.exceptionOrNull())
        val reopened = EncryptedHistoryMediaCache(context, scope, keyProvider = keys)
        assertNull(reopened.localMetadata(conversation, fixture.id))
        reopened.clearMedia()
    }
    @Test fun `已确认删除使读取中的旧字节及导出代数失效`() = runBlocking {
        lateinit var fixture: Fixture
        fixture = fixture(beforeChunk = { fixture.cache.remove(conversation, fixture.id) })
        assertNotNull(runCatching { fixture.cache.ensureLocal(conversation, fixture.id) }.exceptionOrNull())
        assertNull(fixture.cache.localMetadata(conversation, fixture.id))
        assertNotNull(runCatching { fixture.cache.originalEpoch(conversation, fixture.id) }.exceptionOrNull())
        val reopened = EncryptedHistoryMediaCache(context, scope, keyProvider = keys)
        assertNull(reopened.localMetadata(conversation, fixture.id))
        reopened.clearMedia()
    }
    @Test fun `退休端点保留离线原件但旧导出流不能继续读取`() = runBlocking {
        val fixture = fixture()
        fixture.cache.ensureLocal(conversation, fixture.id)
        val stream = fixture.cache.openOriginal(conversation, fixture.id)
        assertTrue(stream.read() >= 0)
        fixture.source.close(); fixture.cache.retireConnection()
        stream.use { assertNotNull(runCatching { it.readBytes() }.exceptionOrNull()) }
        val reopened = EncryptedHistoryMediaCache(context, scope, keyProvider = keys)
        assertArrayEquals(original, reopened.openOriginal(conversation, fixture.id).use { it.readBytes() })
        reopened.clearMedia()
    }
}
