package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.schema.JsonValue
import java.io.ByteArrayInputStream
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 RemoteAttachmentCoordinator 图片多模态内容块传输、后端白名单移除以及非图片附件回退上传。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteAttachmentCoordinatorTest {
    private class Recovery(var records: List<RecoveredAttachmentDraft> = emptyList()) : AttachmentDraftRecoveryStore {
        override fun load() = records
        override fun save(records: List<RecoveredAttachmentDraft>) { this.records = records }
    }

    private class Staging(val files: MutableMap<String, ByteArray> = mutableMapOf()) : LocalAttachmentStagingStore {
        val deleted = mutableListOf<String>()
        fun put(stagedId: String, bytes: ByteArray) { files[stagedId] = bytes }
        override fun stage(selection: LocalAttachmentSelection, onBytesStaged: (Long) -> Unit, isCancelled: () -> Boolean): StagedAttachmentContent {
            val bytes = selection.contentSource.openStream().use { it.readBytes() }
            val id = "staged_${files.size + 1}"
            files[id] = bytes
            return StagedAttachmentContent(id, bytes.size.toLong(), "hash_${files.size}")
        }
        override fun openStream(stagedId: String): ByteArrayInputStream =
            ByteArrayInputStream(files[stagedId] ?: error("FILE_NOT_FOUND: $stagedId"))
        override fun delete(stagedId: String) { deleted += stagedId }
        override fun cleanupExpired(nowMillis: Long, maxAgeMillis: Long) = Unit
        override fun cleanup() = Unit
    }

    private class DummyChannel(override val capabilities: Set<String> = emptySet()) : RemoteRpcChannel {
        override val scope = RemoteRpcConnectionScope("https://remote.example", "account", "host", "client", "connection", 1)
        override val events = emptyFlow<RemoteRpcEvent>()
        val calls = mutableListOf<String>()
        override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue {
            calls += method
            val upload = params.requiredText("uploadId")
            return when (method) {
                "workspace.upload.open" -> {
                    val total = params.number("totalBytes") ?: 20L
                    obj("uploadId" to upload, "chunkBytes" to total.toInt().coerceAtLeast(1))
                }
                "workspace.upload.chunk" -> {
                    val count = params.text("data")?.let { Base64.getDecoder().decode(it).size.toLong() } ?: 20L
                    val offset = (params.number("offset") ?: 0L) + count
                    obj("uploadId" to upload, "receivedBytes" to offset)
                }
                "workspace.upload.commit" -> {
                    val name = "archive.zip"
                    obj("bytes" to 20L, "name" to name, "path" to "./$name")
                }
                "workspace.upload.abort" -> obj()
                else -> error("UNEXPECTED_CALL: $method")
            }
        }
        override suspend fun close() = Unit
    }

    private class TestCarrier(
        override val backend: String,
        override val channel: RemoteRpcChannel = DummyChannel(),
    ) : RemoteSessionCarrier {
        override val capabilities: Set<com.openandroidintelligence.conversation.remote.SessionCapability> =
            setOf(com.openandroidintelligence.conversation.remote.SessionCapability.SEND, com.openandroidintelligence.conversation.remote.SessionCapability.STREAM)
        override suspend fun workspaces(): List<RemoteWorkspace> = emptyList()
        override suspend fun sessions(workspace: RemoteWorkspace, page: PageRequest): RemoteSessionPage = RemoteSessionPage(emptyList())
        override suspend fun create(workspace: RemoteWorkspace): RemoteSessionRow = RemoteSessionRow("1", "", 0, 0)
        override suspend fun history(sessionId: String, page: PageRequest): RemoteHistoryPage = RemoteHistoryPage(emptyList())
        override fun follow(sessionId: String) = emptyFlow<JsonValue>()
        override suspend fun prompt(sessionId: String, requestId: String, content: List<JsonValue>, model: RemoteModelSelection?): JsonValue = obj()
        override suspend fun interrupt(sessionId: String, generationId: String): JsonValue = obj()
        override suspend fun respond(sessionId: String, handle: String, allow: Boolean): JsonValue = obj()
        override suspend fun models(sessionId: String): RemoteModelCatalog = RemoteModelCatalog(emptyList(), emptyMap())
        override suspend fun selectModel(sessionId: String, selection: RemoteModelSelection): RemoteModelSelection = selection
    }

    @Test
    fun `任意后端包括dsh的图片附件均走多模态图片块而不触发workspace上传`() = runTest {
        // dsh 原先不在白名单中，且 channel 不声明 workspace.upload.v1 能力
        val channel = DummyChannel(capabilities = emptySet())
        val carrier = TestCarrier(backend = "dsh", channel = channel)
        val staging = Staging()
        val recovery = Recovery()
        val coordinator = RemoteAttachmentCoordinator(carrier, backgroundScope, staging, recovery)

        val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) // PNG header
        val selection = LocalAttachmentSelection(
            filename = "sample.png",
            mediaType = "image/png",
            contentSource = AttachmentContentSource { ByteArrayInputStream(imageBytes) },
        )
        val draft = coordinator.prepare(selection)
        coordinator.observe(draft.id.value).first { it.state == AttachmentState.VERIFIED }

        val localSessionId = RemoteLocalIds.encode("session_1")
        val content = coordinator.promptContent(localSessionId, draft.id.value)

        // 验证没有调用 workspace.upload.*
        assertTrue("图片不得调用 workspace.upload.* 接口", channel.calls.isEmpty())

        // 验证返回标准多模态图片内容块
        assertEquals(1, content.size)
        val block = content.single()
        assertEquals("image", block.text("type"))
        assertEquals("image/png", block.text("mimeType"))
        assertEquals("image/png", block.text("mediaType"))

        val expectedBase64 = Base64.getEncoder().encodeToString(imageBytes)
        assertEquals(expectedBase64, block.text("data"))

        // 验证兼容嵌套 source 结构
        val source = block.at("source")
        assertNotNull(source)
        assertEquals("base64", source?.text("type"))
        assertEquals("image/png", source?.text("media_type"))
        assertEquals(expectedBase64, source?.text("data"))
    }

    @Test
    fun `不同图片MIME类型如webp和jpeg均能正确生成多模态块`() = runTest {
        val backends = listOf("dsh", "acp", "hermes", "codex", "custom_agent")
        val mimeTypes = listOf("image/webp", "image/jpeg", "image/gif")

        for ((index, backend) in backends.withIndex()) {
            val mime = mimeTypes[index % mimeTypes.size]
            val channel = DummyChannel()
            val carrier = TestCarrier(backend = backend, channel = channel)
            val staging = Staging()
            val recovery = Recovery()
            val coordinator = RemoteAttachmentCoordinator(carrier, backgroundScope, staging, recovery)

            val bytes = "test_image_bytes_$index".toByteArray()
            val selection = LocalAttachmentSelection(
                filename = "img_$index.${mime.substringAfter('/')}",
                mediaType = mime,
                contentSource = AttachmentContentSource { ByteArrayInputStream(bytes) },
            )
            val draft = coordinator.prepare(selection)
            coordinator.observe(draft.id.value).first { it.state == AttachmentState.VERIFIED }

            val content = coordinator.promptContent(RemoteLocalIds.encode("session_multi"), draft.id.value)

            assertTrue(channel.calls.isEmpty())
            val block = content.single()
            assertEquals("image", block.text("type"))
            assertEquals(mime, block.text("mimeType"))
            assertEquals(mime, block.text("mediaType"))
            assertEquals(Base64.getEncoder().encodeToString(bytes), block.text("data"))
            assertEquals(mime, block.at("source")?.text("media_type"))
        }
    }

    @Test
    fun `非图片附件若不支持workspace上传依然报错`() = runTest {
        val channel = DummyChannel(capabilities = emptySet())
        val carrier = TestCarrier(backend = "dsh", channel = channel)
        val staging = Staging()
        val recovery = Recovery()
        val coordinator = RemoteAttachmentCoordinator(carrier, backgroundScope, staging, recovery)

        val fileBytes = "text content".toByteArray()
        val selection = LocalAttachmentSelection(
            filename = "document.txt",
            mediaType = "text/plain",
            contentSource = AttachmentContentSource { ByteArrayInputStream(fileBytes) },
        )
        val draft = coordinator.prepare(selection)
        coordinator.observe(draft.id.value).first { it.state == AttachmentState.VERIFIED }

        try {
            coordinator.promptContent(RemoteLocalIds.encode("session_txt"), draft.id.value)
            fail("非图片文件在不支持 workspace.upload 时应抛出 RemoteSessionException")
        } catch (e: RemoteSessionException) {
            assertEquals("WORKSPACE_UPLOAD_UNSUPPORTED", e.code)
        }
    }

    @Test
    fun `非图片附件在支持workspace上传时正常走分块流程`() = runTest {
        val channel = DummyChannel(capabilities = setOf("workspace.upload.v1"))
        val carrier = TestCarrier(backend = "dsh", channel = channel)
        val staging = Staging()
        val recovery = Recovery()
        val coordinator = RemoteAttachmentCoordinator(carrier, backgroundScope, staging, recovery)

        val fileBytes = "regular file payload".toByteArray() // 20 bytes
        val selection = LocalAttachmentSelection(
            filename = "archive.zip",
            mediaType = "application/zip",
            contentSource = AttachmentContentSource { ByteArrayInputStream(fileBytes) },
        )
        val draft = coordinator.prepare(selection)
        coordinator.observe(draft.id.value).first { it.state == AttachmentState.VERIFIED }

        val content = coordinator.promptContent(RemoteLocalIds.encode("session_zip"), draft.id.value)
        assertTrue(channel.calls.contains("workspace.upload.open"))
        assertTrue(channel.calls.contains("workspace.upload.chunk"))
        assertTrue(channel.calls.contains("workspace.upload.commit"))
        assertEquals("./archive.zip", content.single().text("text"))
    }
}
