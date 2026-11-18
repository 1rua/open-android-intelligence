package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.schema.JsonValue
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test

class RemoteAuthenticatedMediaSourceTest {
    private class Channel : RemoteRpcChannel {
        override val scope = RemoteRpcConnectionScope("https://remote.example", "account", "host", "client", "connection", 1)
        override val capabilities = setOf("harness.remote.v3", "codex.appserver.v1", "agent.acp.v1", "agent.acp.hermes.v1")
        override val events = emptyFlow<RemoteRpcEvent>()
        val calls = mutableListOf<Pair<String, JsonValue.JObject>>()
        var result: (String, JsonValue.JObject) -> JsonValue = { _, _ -> error("测试没有配置回执") }
        override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue {
            calls += method to params
            return result(method, params)
        }
        override suspend fun close() = Unit
    }
    private fun workspace(backend: String) = RemoteWorkspace(AgentEndpoint(EndpointKey(RemoteServiceOrigin.fromCanonicalOrigin("https://remote.example"),
        "account", "host", BackendId(backend), "workspace"), "工作区"), "/work", setOf("session"))
    private fun source(channel: Channel, backend: String = "harness", current: () -> Boolean = { true }): RemoteAuthenticatedMediaSource {
        val carrier = when (backend) { "harness" -> HarnessSessionCarrier(channel); "codex" -> CodexSessionCarrier(channel); else -> AcpSessionCarrier(channel, backend) }
        return RemoteAuthenticatedMediaSource(carrier, workspace(backend), current)
    }
    private val conversation = RemoteLocalIds.encode("session")
    private fun file(source: RemoteAuthenticatedMediaSource, path: String = "report.bin") = source.accept(conversation,
        obj("type" to "file", "path" to path, "name" to "报告.bin", "mimeType" to "application/octet-stream"))!!.draftId.value
    private fun response(bytes: ByteArray, version: String = "one", offset: Long = 0, eof: Boolean = true) = obj(
        "absolutePath" to "/work/report.bin", "version" to version, "bytes" to bytes.size, "offset" to offset,
        "eof" to eof, "data" to Base64.getEncoder().encodeToString(bytes))
    private fun harness(channel: Channel, bytes: ByteArray) {
        channel.result = { method, request ->
            assertEquals("harness.remote.call", method)
            val args = request.at("payload")!!.at("args")!!
            assertEquals("session", args.text("workspaceFileScopeId"))
            assertEquals("report.bin", args.text("path"))
            obj("ok" to true, "value" to if (request.text("endpoint") == "workspaceFiles/stat")
                obj("absolutePath" to "/work/report.bin", "version" to "one", "bytes" to bytes.size)
            else { assertEquals(0L, args.at("range")!!.number("offset")); response(bytes) })
        }
    }
    @Test fun `Harness只读取确认引用并校验真实文件分块契约`() = runBlocking {
        val channel = Channel(); val source = source(channel); val bytes = "原件内容".toByteArray(); harness(channel, bytes)
        val id = file(source)
        val metadata = source.metadata(conversation, id)
        assertEquals("", metadata.sha256)
        source.consumeOriginal(metadata) { assertArrayEquals(bytes, it.readBytes()) }
        assertEquals(listOf("workspaceFiles/stat", "workspaceFiles/stat", "workspaceFiles/readBytes", "workspaceFiles/stat"), channel.calls.map { it.second.text("endpoint") })
        assertNotNull(runCatching { source.metadata(conversation, RemoteLocalIds.encode("other.bin")) }.exceptionOrNull())
        assertNotNull(runCatching { source.metadata(RemoteLocalIds.encode("other-session"), id) }.exceptionOrNull())
        assertEquals(4, channel.calls.size)
    }
    @Test fun `Codex与ACP文件路由保留公开后端scope`() = runBlocking {
        listOf("codex", "hermes", "antigravity").forEach { backend ->
            val channel = Channel(); val source = source(channel, backend); val bytes = byteArrayOf(1, 2)
            channel.result = { method, request ->
                val endpoint: String; val args: JsonValue
                if (backend == "codex") {
                    assertEquals("harness.remote.call", method)
                    endpoint = request.requiredText("endpoint"); args = request.at("payload")!!.at("args")!!
                    assertEquals("codex:session", args.text("workspaceFileScopeId"))
                } else {
                    assertEquals("agent.acp.call", method); assertEquals("dsh/toolCall", request.text("method"))
                    val params = request.at("params")!!
                    assertEquals("session", params.text("sessionId")); assertEquals(backend, params.text("backend"))
                    endpoint = params.requiredText("endpoint"); args = params.at("args")!!
                    assertEquals("${if (backend == "antigravity") "acp" else backend}:session", args.text("workspaceFileScopeId"))
                }
                val value = if (endpoint == "workspaceFiles/stat") obj("absolutePath" to "/work/report.bin", "version" to "one", "bytes" to bytes.size)
                    else response(bytes)
                if (backend == "codex") obj("ok" to true, "value" to value) else value
            }
            val id = file(source)
            source.consumeOriginal(source.metadata(conversation, id)) { assertArrayEquals(bytes, it.readBytes()) }
        }
    }
    @Test fun `历史内联图片具有真实摘要且外链不会携带认证信息发请求`() = runBlocking {
        val channel = Channel(); val source = source(channel, "codex"); val bytes = byteArrayOf(1, 2, 3)
        val part = source.accept(conversation, obj("type" to "image", "attachment" to obj("attachmentId" to "codex-image:one:0", "mediaType" to "image/png", "bytes" to 3),
            "data" to Base64.getEncoder().encodeToString(bytes)))!!
        val metadata = source.metadata(conversation, part.draftId.value)
        assertTrue(metadata.sha256.matches(Regex("sha256:[a-f0-9]{64}")))
        source.consumeOriginal(metadata) { assertArrayEquals(bytes, it.readBytes()) }
        val external = source.accept(conversation, obj("type" to "image", "url" to "https://other.example/private.png"))!!
        assertNotNull(runCatching { source.metadata(conversation, external.draftId.value) }.exceptionOrNull())
        assertTrue(channel.calls.isEmpty())
    }
    @Test fun `Harness与Codex按真实附件目录回取元数据图片且不把外链当目录`() = runBlocking {
        listOf("harness", "codex").forEach { backend ->
            val channel = Channel(); val source = source(channel, backend)
            val attachment = obj("attachmentId" to if (backend == "codex") "codex-image:original:0" else "image-original",
                "mediaType" to "image/png", "bytes" to 3, "directory" to "真实附件目录")
            val part = source.accept(conversation, obj("type" to "image", "attachment" to attachment))!!
            var envelope: JsonValue? = null
            channel.result = { method, request -> when {
                method.endsWith("transfer.open") || method.endsWith("transfer.chunk") || method.endsWith("transfer.close") -> {
                    if (method.endsWith("transfer.chunk")) envelope = com.openandroidintelligence.gateway.schema.Json.parse(
                        Base64.getDecoder().decode(request.requiredText("data")).decodeToString())
                    obj()
                }
                method.endsWith("transfer.commit") -> {
                    val actual = envelope!!
                    if (backend == "harness") {
                        assertEquals("harness.remote.transfer.commit", method)
                        assertEquals("session/attachment", actual.text("endpoint"))
                        val params = actual.at("payload")!!.at("args")!!.at("request")!!
                        assertEquals("session", params.text("sessionId")); assertEquals(com.openandroidintelligence.gateway.schema.Json.canonical(attachment),
                            com.openandroidintelligence.gateway.schema.Json.canonical(params.at("attachment")!!))
                        obj("kind" to "inline", "response" to obj("ok" to true, "value" to obj("attachment" to attachment, "data" to "AQID")))
                    } else {
                        assertEquals("codex.app.transfer.commit", method)
                        assertEquals("dsh/sessionAttachment", actual.text("method"))
                        assertEquals("session", actual.at("params")!!.text("threadId"))
                        assertEquals("codex-image:original:0", actual.at("params")!!.text("attachmentId"))
                        obj("kind" to "inline", "response" to obj("attachment" to attachment, "data" to "AQID"))
                    }
                }
                else -> error("不允许虚构文件调用")
            } }
            val metadata = source.metadata(conversation, part.draftId.value)
            source.consumeOriginal(metadata) { assertArrayEquals(byteArrayOf(1, 2, 3), it.readBytes()) }
            // 第二次历史重放保留已校验内容，不因数据被补齐而把相同附件当冲突。
            assertEquals(part, source.accept(conversation, obj("type" to "image", "attachment" to attachment)))
        }
    }
    @Test fun `数据URL类型必须与历史MIME一致且空文件保持真实零字节`() = runBlocking {
        val channel = Channel(); val source = source(channel)
        assertNotNull(runCatching { source.accept(conversation, obj("type" to "image", "mimeType" to "image/jpeg", "url" to "data:image/png;base64,AQID")) }.exceptionOrNull())
        val empty = source.accept(conversation, obj("type" to "file", "id" to "empty", "name" to "空文件.pdf", "mimeType" to "application/pdf", "bytes" to 0, "data" to ""))!!
        val metadata = source.metadata(conversation, empty.draftId.value)
        assertEquals(0L, metadata.sizeBytes); assertEquals("application/pdf", metadata.mediaType)
        source.consumeOriginal(metadata) { assertEquals(-1, it.read()) }
        assertTrue(channel.calls.isEmpty())
    }
    @Test fun `真实更高修订替换同附件并拒绝旧历史及旧读取`() = runBlocking {
        val channel = Channel(); val source = source(channel)
        fun block(data: String) = obj("type" to "file", "id" to "same-original", "name" to "原件.pdf", "mimeType" to "application/pdf", "bytes" to 3, "data" to data)
        val original = block("AQID")
        val part = source.accept(conversation, original, RemoteMediaRevision("message", 10, true))!!
        val old = source.metadata(conversation, part.draftId.value)
        source.accept(conversation, block("BAUG"), RemoteMediaRevision("message", 11, false))
        val current = source.metadata(conversation, part.draftId.value)
        assertNotEquals(old.sourceVersion, current.sourceVersion)
        assertNotNull(runCatching { source.consumeOriginal(old) { it.readBytes() } }.exceptionOrNull())
        source.accept(conversation, original, RemoteMediaRevision("message", 10, true))
        assertEquals(current.sourceVersion, source.metadata(conversation, part.draftId.value).sourceVersion)
        source.consumeOriginal(current) { assertArrayEquals(byteArrayOf(4, 5, 6), it.readBytes()) }
        assertNotNull(runCatching { source.accept(conversation, original, RemoteMediaRevision("message", 11, true)) }.exceptionOrNull())
        assertTrue(channel.calls.isEmpty())
    }
    @Test fun `投影把上游真实seq交给媒体源且迟到历史不能回滚引用`() = runBlocking {
        val channel = Channel(); val source = source(channel)
        val projection = RemoteTimelineProjection(conversation, mediaReference = source::accept)
        fun event(sequence: Long, data: String) = obj("event" to obj("type" to "assistant/message", "seq" to sequence,
            "data" to obj("message" to obj("id" to "message", "content" to listOf(obj("type" to "file", "id" to "same", "bytes" to 3,
                "mimeType" to "application/pdf", "data" to data))))))
        projection.accept(event(10, "AQID"), history = true)
        val id = RemoteLocalIds.encode("same")
        val before = source.metadata(conversation, id)
        projection.accept(event(11, "BAUG"))
        val after = source.metadata(conversation, id)
        projection.accept(event(10, "AQID"), history = true)
        assertNotEquals(before.sourceVersion, after.sourceVersion)
        assertEquals(after.sourceVersion, source.metadata(conversation, id).sourceVersion)
        source.consumeOriginal(after) { assertArrayEquals(byteArrayOf(4, 5, 6), it.readBytes()) }
    }
    @Test fun `Codex和ACP重投影序号不作为持久水位且权威重读可更新无序号引用`() = runBlocking {
        listOf("codex", "hermes").forEach { backend ->
            val channel = Channel(); val source = source(channel, backend)
            fun block(data: String) = obj("type" to "file", "id" to "same", "bytes" to 3, "mimeType" to "application/pdf", "data" to data)
            val first = source.beginHistoryRead()
            val part = source.accept(conversation, block("AQID"), RemoteMediaRevision("message", 100, true, first))!!
            val old = source.metadata(conversation, part.draftId.value)
            assertNull(source.referenceState(conversation, part.draftId.value)!!.highestRevision)
            val reread = source.beginHistoryRead()
            source.accept(conversation, block("BAUG"), RemoteMediaRevision("message", 1, true, reread))
            val updated = source.metadata(conversation, part.draftId.value)
            assertNotEquals(old.sourceVersion, updated.sourceVersion)
            assertNull(source.referenceState(conversation, part.draftId.value)!!.revisionDomain)
            val pendingHistory = source.beginHistoryRead()
            source.accept(conversation, block("BwgJ"), RemoteMediaRevision("message", historical = false))
            assertNotNull(runCatching { source.accept(conversation, block("AQID"), RemoteMediaRevision("message", 1000, true, pendingHistory)) }.exceptionOrNull())
            source.consumeOriginal(source.metadata(conversation, part.draftId.value)) { assertArrayEquals(byteArrayOf(7, 8, 9), it.readBytes()) }
        }
    }
    @Test fun `后发历史再次确认相同内容也阻止先发历史不同内容回滚`() = runBlocking {
        listOf("codex", "hermes").forEach { backend ->
            val channel = Channel(); val source = source(channel, backend)
            fun block(data: String) = obj("type" to "file", "id" to "same", "bytes" to 3, "mimeType" to "application/pdf", "data" to data)
            val part = source.accept(conversation, block("AQID"))!!
            val original = source.metadata(conversation, part.draftId.value)
            val oldRead = source.beginHistoryRead()
            val newRead = source.beginHistoryRead()
            source.accept(conversation, block("AQID"), RemoteMediaRevision("message", historical = true, historyRead = newRead))
            assertNotNull(runCatching { source.accept(conversation, block("BAUG"), RemoteMediaRevision("message", historical = true, historyRead = oldRead)) }.exceptionOrNull())
            assertEquals(original.sourceVersion, source.metadata(conversation, part.draftId.value).sourceVersion)
            source.consumeOriginal(original) { assertArrayEquals(byteArrayOf(1, 2, 3), it.readBytes()) }
        }
    }
    @Test fun `实时再次确认相同内容也阻止先前历史不同内容回滚`() = runBlocking {
        listOf("codex", "hermes").forEach { backend ->
            val channel = Channel(); val source = source(channel, backend)
            fun block(data: String) = obj("type" to "file", "id" to "same", "bytes" to 3, "mimeType" to "application/pdf", "data" to data)
            val part = source.accept(conversation, block("AQID"))!!
            val original = source.metadata(conversation, part.draftId.value)
            val oldRead = source.beginHistoryRead()
            source.accept(conversation, block("AQID"), RemoteMediaRevision("message", historical = false))
            assertNotNull(runCatching { source.accept(conversation, block("BAUG"), RemoteMediaRevision("message", historical = true, historyRead = oldRead)) }.exceptionOrNull())
            assertEquals(original.sourceVersion, source.metadata(conversation, part.draftId.value).sourceVersion)
            source.consumeOriginal(original) { assertArrayEquals(byteArrayOf(1, 2, 3), it.readBytes()) }
        }
    }
    @Test fun `相同内容提升Harness已确认水位而不改变原件内容身份`() = runBlocking {
        val channel = Channel(); val source = source(channel)
        val block = obj("type" to "file", "id" to "same", "bytes" to 3, "mimeType" to "application/pdf", "data" to "AQID")
        val part = source.accept(conversation, block, RemoteMediaRevision("message", 10, true))!!
        val before = source.metadata(conversation, part.draftId.value)
        source.accept(conversation, block, RemoteMediaRevision("message", 20, true))
        assertEquals(before.sourceVersion, source.metadata(conversation, part.draftId.value).sourceVersion)
        assertEquals(20L, source.referenceState(conversation, part.draftId.value)!!.highestRevision)
        assertEquals("harness-session-v3", source.referenceState(conversation, part.draftId.value)!!.revisionDomain)
    }
    @Test fun `路径越界绝对外部路径及未确认会话均不得读取`() = runBlocking {
        val channel = Channel(); val source = source(channel)
        listOf("../private", "/outside/report.bin", "a/../private", "a\\private", "file:///etc/private").forEach { path ->
            val id = file(source, path)
            assertNotNull(runCatching { source.metadata(conversation, id) }.exceptionOrNull())
        }
        assertNotNull(runCatching { source.accept(RemoteLocalIds.encode("other"), obj("type" to "file", "path" to "report.bin")) }.exceptionOrNull())
        assertTrue(channel.calls.isEmpty())
    }
    @Test fun `版本偏移大小与eof不一致不能发布读取成功`() = runBlocking {
        listOf("version", "offset", "bytes", "eof", "base64", "after").forEach { failure ->
            val channel = Channel(); val source = source(channel); val bytes = byteArrayOf(1, 2)
            var stats = 0
            channel.result = { _, request ->
                val value = if (request.text("endpoint") == "workspaceFiles/stat") {
                    stats++
                    obj("absolutePath" to "/work/report.bin", "version" to if (failure == "after" && stats == 3) "changed" else "one", "bytes" to 2)
                } else when (failure) {
                    "version" -> response(bytes, version = "changed")
                    "offset" -> response(bytes, offset = 1)
                    "bytes" -> response(byteArrayOf(1))
                    "eof" -> response(byteArrayOf(1), eof = true)
                    "base64" -> obj("absolutePath" to "/work/report.bin", "version" to "one", "bytes" to 2, "offset" to 0, "data" to "AQI", "eof" to true)
                    else -> response(bytes)
                }
                obj("ok" to true, "value" to value)
            }
            val id = file(source)
            assertNotNull(failure, runCatching { source.consumeOriginal(source.metadata(conversation, id)) { it.readBytes() } }.exceptionOrNull())
        }
    }
    @Test fun `连接或账号切换使迟到内容失效`() = runBlocking {
        val channel = Channel(); var current = true; val source = source(channel, current = { current }); val bytes = byteArrayOf(1)
        harness(channel, bytes)
        val id = file(source); val metadata = source.metadata(conversation, id)
        val delegate = channel.result
        channel.result = { method, request -> delegate(method, request).also { if (request.text("endpoint") == "workspaceFiles/readBytes") current = false } }
        assertEquals("REMOTE_MEDIA_SCOPE_CHANGED", runCatching { source.consumeOriginal(metadata) { it.readBytes() } }.exceptionOrNull()?.message)
    }
}
