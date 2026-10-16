package com.openandroidintelligence.conversation.data

import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.gateway.attachments.*
import com.openandroidintelligence.gateway.auth.GatewayAuthClient
import com.openandroidintelligence.gateway.auth.ed25519WirePublicKey
import com.openandroidintelligence.gateway.commands.CommandCatalogClient
import com.openandroidintelligence.gateway.conversations.ConversationClient
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.JsonFields
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.openandroidintelligence.gateway.events.EventStreamStatus
import com.openandroidintelligence.gateway.events.EventStreamStatusSink
import com.openandroidintelligence.gateway.events.GatewayEvent

/**
 * Real HTTP, real password session, real signatures, real Python Gateway Core.
 *
 * 会话重命名走 `PATCH`，而 JDK 的 `HttpURLConnection` 明确拒绝 PATCH（平台上的
 * OkHttp 实现才允许），因此这条链路的“手机端确实发出 PATCH”证据由设备侧插桩测试
 * `ConversationRenameTransportInstrumentedTest` 承担，“网关接受并落库”证据由
 * 插件仓 `hermes-gateway-plugin` 的 `tests/test_conversation_rename.py` 承担。
 *
 * 该插件已拆为独立仓库，本测试通过 `HERMES_PLUGIN_ROOT` 定位它，并用
 * `OPEN_ANDROID_GATEWAY_CONTRACT_ROOT` 指定本仓持有的契约；两者缺一时直接失败，
 * 不会跳过——否则这条真实链路会退化成无人验证的绿。
 */
class HermesHttpInteropTest {
    @Test fun reopenedNativeHistoryPreservesOriginalMessageToolsAndRetrievableImage() = runBlocking {
        coroutineScope {
            val root = File("../../..").canonicalFile
            val pluginRoot = (System.getenv("HERMES_PLUGIN_ROOT")?.let(::File) ?: File(root, ".hermes-gateway-plugin")).canonicalFile
            val fixture = File(pluginRoot, "tests/android_gateway_fixture.py")
            check(fixture.isFile) { "HERMES_FIXTURE_MISSING" }
            val sdkValue = System.getenv("HERMES_SDK_ROOT")?.takeIf { it.isNotBlank() }
                ?: error("HERMES_SDK_ROOT_REQUIRED")
            val sdkRoot = File(sdkValue).let { if (it.isAbsolute) it else File(root, sdkValue) }.canonicalFile
            check(File(sdkRoot, "hermes_state.py").isFile) { "HERMES_SDK_SOURCE_MISSING" }
            val log = File(root, "tmp/conversation-bugfix/hermes-history-http.txt").also { it.parentFile!!.mkdirs() }
            val builder = ProcessBuilder("python3", fixture.path, "--history-agent", "--contract-root",
                System.getenv("OPEN_ANDROID_GATEWAY_CONTRACT_ROOT") ?: File(root, "gateway-contract").path)
                .directory(pluginRoot).redirectError(log)
            // 明确解析 SDK 来源，Gradle 工作目录与夹具工作目录变化不能改变宿主选择。
            builder.environment()["HERMES_SDK_ROOT"] = sdkRoot.path
            val process = builder.start()
            try {
                val baseUrl = process.inputStream.bufferedReader().readLine()
                check(baseUrl?.startsWith("http://127.0.0.1:") == true) { "HERMES_HISTORY_FIXTURE_START_FAILED" }
                val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                val auth = GatewayAuthClient(GatewayTransport(GatewayProfile("pre", "pre", "pre", baseUrl!!)),
                    "install_history_interop", "test", 35)
                val negotiated = auth.negotiate("neg_history_interop")
                val session = auth.loginWithPassword(negotiated.negotiationId, "alice", "android-fixture-only".toCharArray(),
                    "历史互通", ed25519WirePublicKey(keyPair.public))
                val profile = GatewayProfile(session.accountId, session.deviceId, session.sessionId, baseUrl, accessToken = session.accessToken)
                val health = EventStreamStatusSink()
                val http = GatewayHttpClient(profile, GatewayTransport(profile), { bytes ->
                    Signature.getInstance("Ed25519").run { initSign(keyPair.private); update(bytes); sign() }
                }, InMemoryEventCursorStore(), statusSink = health)
                val repository = GatewayConversationRepository(ConversationClient(http))
                val scope = ConversationScope("profile", baseUrl, session.accountId, "install_history_interop")
                val conversation = repository.createConversation(scope, "cc_history_interop")
                // 一像素合成 PNG，不使用用户照片，也不需要图像/模型依赖。
                val image = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
                val attachmentId = AttachmentUploader(HttpAttachmentTransport(http)).upload(
                    SelectedAttachment("synthetic.png", "image/png", GatewayRequestBody.fromBytes(image)),
                )
                val clientId = ClientMessageId("cm_history_interop")
                val original = "请说明这张合成图片\n保留这个原始文本。"
                val events = Channel<VerifiedConversationEvent>(Channel.UNLIMITED)
                val subscription = launch(start = CoroutineStart.UNDISPATCHED) {
                    repository.observeEvents(scope).collect { events.send(it) }
                }
                val published = mutableListOf<TimelineMessage>()
                val receipt = try {
                    http.execute(SignedGatewayRequest("GET", "/open-android-intelligence/v2/sync/snapshot")).requireData("SYNC_SNAPSHOT")
                    withTimeout(10_000) { health.status.first { it == EventStreamStatus.LIVE } }
                    val accepted = repository.submitMessage(conversation.id.value, OutgoingMessage(clientId, original, listOf(attachmentId)))
                    withTimeout(10_000) {
                        var completed = false
                        while (!completed || published.size < 2) {
                            when (val event = events.receive()) {
                                is VerifiedConversationEvent.TimelineUpsert -> if (event.message.sender == "assistant") published += event.message
                                is VerifiedConversationEvent.MessageStatus -> if (event.clientMessageId == clientId) {
                                    assertNotEquals(AgentMessageStatus.FAILED, event.status)
                                    completed = event.status == AgentMessageStatus.COMPLETED
                                }
                                else -> Unit
                            }
                        }
                    }
                    accepted
                } finally {
                    subscription.cancelAndJoin()
                    events.close()
                }
                assertEquals(listOf("正在查询合成测试图片信息", "这是一张合成测试图片，原始附件已经收到。"),
                    published.map { (it.parts.single() as MessagePart.Text).value })
                val other = repository.createConversation(scope, "cc_history_other")
                assertTrue(repository.timeline(other.id.value, PageRequest()).messages.isEmpty())
                var originalTimestamp: Long? = null
                repeat(2) {
                    val rows = repository.timeline(conversation.id.value, PageRequest()).messages
                    val user = rows.single { it.clientMessageId == clientId }
                    assertEquals(receipt.messageId, user.id)
                    assertEquals(listOf(MessagePart.Text(original),
                        MessagePart.Attachment(com.openandroidintelligence.conversation.model.AttachmentDraftId(attachmentId), "synthetic.png", "image/png",
                            sizeBytes = image.size.toLong(), sha256 = "sha256:" + MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) })), user.parts)
                    assertTrue(user.timestamp > 0)
                    originalTimestamp?.let { assertEquals(it, user.timestamp) }
                    originalTimestamp = user.timestamp
                    for (live in published) {
                        val restored = rows.single { it.id == live.id }
                        assertEquals(live.parts, restored.parts)
                        assertEquals(live.timestamp, restored.timestamp)
                    }
                    assertTrue(rows.filter { it.sender == "assistant" }.all { message ->
                        message.parts.any { it !is MessagePart.Text || it.value.isNotBlank() }
                    })
                }
                val mediaTarget = "/open-android-intelligence/v2/conversations/${conversation.id.value}/attachments/$attachmentId"
                val metadataData = http.execute(SignedGatewayRequest("GET", "$mediaTarget/metadata")).requireData("HISTORY_MEDIA_METADATA")
                val metadata = JsonFields.obj(JsonFields.field(metadataData, "metadata"))
                val digest = MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }
                assertEquals(attachmentId, JsonFields.string(metadata, "attachmentId"))
                assertEquals(conversation.id.value, JsonFields.string(metadata, "conversationId"))
                assertEquals(image.size.toLong(), JsonFields.long(metadata, "sizeBytes"))
                assertEquals("sha256:$digest", JsonFields.string(metadata, "sha256"))
                assertEquals(true, JsonFields.bool(metadata, "remoteAvailable"))
                val grant = http.execute(SignedGatewayRequest("POST", "$mediaTarget/cache-grant",
                    headers = listOf(RawHeader("Content-Type", "application/json")), body = "{}".toByteArray())).requireData("HISTORY_MEDIA_GRANT")
                val grantId = JsonFields.string(grant, "grantId") ?: error("HISTORY_MEDIA_GRANT_INVALID")
                val contentTarget = "$mediaTarget/content?grantId=$grantId"
                val content = http.execute(SignedGatewayRequest("GET", contentTarget)).requireData("HISTORY_MEDIA_CONTENT")
                assertEquals("image/png", JsonFields.string(content, "mediaType"))
                assertArrayEquals(image, Base64.getDecoder().decode(JsonFields.string(content, "contentBase64") ?: error("HISTORY_MEDIA_CONTENT_INVALID")))
                val reused = http.execute(SignedGatewayRequest("GET", contentTarget))
                assertEquals("媒体读取授权只能使用一次", 401, reused.status)
                val reusedError = JsonFields.obj(JsonFields.field(JsonFields.obj(com.openandroidintelligence.gateway.schema.Json.parse(reused.body.decodeToString())), "error"))
                assertEquals("AUTHENTICATION_FAILED", JsonFields.string(reusedError, "code"))
                val foreignTarget = "/open-android-intelligence/v2/conversations/${other.id.value}/attachments/$attachmentId/metadata"
                val foreign = http.execute(SignedGatewayRequest("GET", foreignTarget))
                assertEquals("其它会话不能读取原会话图片", 400, foreign.status)
                val foreignError = JsonFields.obj(JsonFields.field(JsonFields.obj(com.openandroidintelligence.gateway.schema.Json.parse(foreign.body.decodeToString())), "error"))
                assertEquals("ATTACHMENT_NOT_FOUND", JsonFields.string(foreignError, "code"))
                val diagnostics = log.readLines().filter { it.startsWith("{") }.mapNotNull {
                    JsonFields.obj(com.openandroidintelligence.gateway.schema.Json.parse(it))
                }
                assertEquals("native", JsonFields.string(diagnostics.single { JsonFields.string(it, "fixtureMode") == "history-agent" }, "host"))
                val persisted = diagnostics.single { JsonFields.string(it, "fixtureEvent") == "history-persisted" }
                assertEquals(true, JsonFields.bool(persisted, "nativeSessionDb"))
                assertEquals(true, JsonFields.bool(persisted, "modelInputPersisted"))
                assertEquals(digest, JsonFields.string(persisted, "imageSha256"))
                val released = diagnostics.single { JsonFields.string(it, "fixtureEvent") == "history-input-released" }
                assertEquals("处理临时原件已经释放，历史图片仍能通过既有安全入口读取", true, JsonFields.bool(released, "released"))
            } catch (failure: Throwable) {
                System.err.println("Hermes 历史互通诊断：${log.readText()}")
                throw failure
            } finally {
                process.destroy()
                check(process.waitFor(10, TimeUnit.SECONDS)) { "HERMES_HISTORY_FIXTURE_STOP_FAILED" }
            }
        }
    }

    @Test fun streamedReplyIsObservableBeforeCompletionOverTheDefaultWebSocket() = runBlocking {
        assertStreamingReply(defaultWebSocket = true)
    }

    @Test fun streamedReplyIsObservableBeforeCompletionOverSse() = runBlocking {
        assertStreamingReply(defaultWebSocket = false)
    }

    private suspend fun assertStreamingReply(defaultWebSocket: Boolean) = coroutineScope {
        val root = File("../../..").canonicalFile
        val pluginRoot = (System.getenv("HERMES_PLUGIN_ROOT")?.let(::File) ?: File(root, ".hermes-gateway-plugin")).canonicalFile
        val fixture = File(pluginRoot, "tests/android_gateway_fixture.py")
        check(fixture.isFile) { "HERMES_FIXTURE_MISSING" }
        val channelName = if (defaultWebSocket) "websocket" else "sse"
        val log = File(root, "tmp/conversation-bugfix/hermes-stream-$channelName.txt").also { it.parentFile!!.mkdirs() }
        val process = ProcessBuilder("python3", fixture.path, "--stream-agent", "--contract-root",
            System.getenv("OPEN_ANDROID_GATEWAY_CONTRACT_ROOT") ?: File(root, "gateway-contract").path)
            .directory(pluginRoot).redirectError(log).start()
        val control = process.outputStream.bufferedWriter()
        try {
            val baseUrl = process.inputStream.bufferedReader().readLine()
            check(baseUrl?.startsWith("http://127.0.0.1:") == true) { "HERMES_STREAM_FIXTURE_START_FAILED" }
            val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val auth = GatewayAuthClient(GatewayTransport(GatewayProfile("pre", "pre", "pre", baseUrl!!)), "install_stream_interop", "test", 35)
            val negotiated = auth.negotiate("neg_stream_interop")
            val session = auth.loginWithPassword(negotiated.negotiationId, "alice", "android-fixture-only".toCharArray(), "流式互通", ed25519WirePublicKey(keyPair.public))
            val profile = GatewayProfile(session.accountId, session.deviceId, session.sessionId, baseUrl, accessToken = session.accessToken)
            val health = EventStreamStatusSink()
            val signer: (ByteArray) -> ByteArray = { bytes ->
                Signature.getInstance("Ed25519").run { initSign(keyPair.private); update(bytes); sign() }
            }
            val http = if (defaultWebSocket) {
                // 使用手机生产构造方式，保留默认 WebSocket 优先行为。
                GatewayHttpClient(profile, GatewayTransport(profile), signer, InMemoryEventCursorStore(), statusSink = health)
            } else {
                GatewayHttpClient(profile, GatewayTransport(profile), signer, InMemoryEventCursorStore(), webSocketTransport = null, statusSink = health)
            }
            val repository = GatewayConversationRepository(ConversationClient(http))
            val scope = ConversationScope("profile", baseUrl, session.accountId, "install_stream_interop")
            val conversation = repository.createConversation(scope, "cc_stream_interop")
            val events = Channel<VerifiedConversationEvent>(Channel.UNLIMITED)
            val subscription = launch(start = CoroutineStart.UNDISPATCHED) {
                repository.observeEvents(scope).collect { events.send(it) }
            }
            suspend fun nextTimelineEvent(): VerifiedConversationEvent.TimelineUpsert = withTimeout(10_000) {
                var event = events.receive()
                while (event !is VerifiedConversationEvent.TimelineUpsert) event = events.receive()
                event
            }
            try {
                http.execute(SignedGatewayRequest("GET", "/open-android-intelligence/v2/sync/snapshot")).requireData("SYNC_SNAPSHOT")
                withTimeout(10_000) { health.status.first { it == EventStreamStatus.LIVE } }
                val clientId = ClientMessageId("cm_stream_interop")
                repository.submitMessage(conversation.id.value, OutgoingMessage(clientId, "流式测试"))
                val replyIds = mutableSetOf<String>()
                for (expected in listOf("fixture-agent-", "fixture-agent-reply:\n", "fixture-agent-reply:\n流式测试")) {
                    val event = nextTimelineEvent()
                    assertEquals("STREAMING", event.message.state)
                    assertEquals("assistant", event.message.sender)
                    assertEquals(conversation.id, event.message.conversationId)
                    assertEquals(expected, (event.message.parts.single() as com.openandroidintelligence.conversation.model.MessagePart.Text).value)
                    assertTrue(event.message.timestamp > 0)
                    replyIds += event.message.id
                    // 只有 Android 已观察到当前非空增量，夹具才继续下一段生成。
                    control.write("continue\n")
                    control.flush()
                }
                val completed = nextTimelineEvent()
                assertEquals("CONFIRMED", completed.message.state)
                assertEquals("fixture-agent-reply:\n流式测试", (completed.message.parts.single() as com.openandroidintelligence.conversation.model.MessagePart.Text).value)
                replyIds += completed.message.id
                assertEquals("三段增量与最终回复必须属于同一消息", 1, replyIds.size)
                val terminal = withTimeout(10_000) {
                    var event = events.receive()
                    while (event !is VerifiedConversationEvent.MessageStatus || event.clientMessageId != clientId ||
                        event.status !in setOf(AgentMessageStatus.COMPLETED, AgentMessageStatus.FAILED)) {
                        event = events.receive()
                    }
                    event
                }
                assertEquals(AgentMessageStatus.COMPLETED, terminal.status)
            } finally {
                subscription.cancelAndJoin()
                events.close()
            }
        } catch (failure: Throwable) {
            runCatching { log.readText() }.getOrNull()?.let { System.err.println("Hermes 隔离流式互通夹具日志：\n$it") }
            throw failure
        } finally {
            control.close()
            process.destroy()
            check(process.waitFor(10, TimeUnit.SECONDS)) { "HERMES_STREAM_FIXTURE_STOP_FAILED" }
        }
        val transports = log.readLines().mapNotNull { line ->
            runCatching {
                val entry = JsonFields.obj(com.openandroidintelligence.gateway.schema.Json.parse(line))
                if (JsonFields.string(entry, "fixtureEvent") == "stream-transport") JsonFields.string(entry, "transport") else null
            }.getOrNull()
        }
        assertEquals("应实际使用被验证的事件通道", listOf(channelName), transports)
    }

    @Test fun batchAcceptanceDispatchReplyAndReplayUseTheRealHttpAndSseBoundary() = runBlocking {
        val root = File("../../..").canonicalFile
        val pluginRoot = (System.getenv("HERMES_PLUGIN_ROOT")?.let(::File) ?: File(root, ".hermes-gateway-plugin")).canonicalFile
        val fixture = File(pluginRoot, "tests/android_gateway_fixture.py")
        check(fixture.isFile) { "HERMES_FIXTURE_MISSING" }
        val log = File(root, "tmp/conversation-bugfix/hermes-batch-interop.txt").also { it.parentFile!!.mkdirs() }
        val process = ProcessBuilder("python3", fixture.path, "--echo-agent", "--contract-root",
            System.getenv("OPEN_ANDROID_GATEWAY_CONTRACT_ROOT") ?: File(root, "gateway-contract").path)
            .directory(pluginRoot).redirectError(log).start()
        try {
            val baseUrl = process.inputStream.bufferedReader().readLine()
            check(baseUrl?.startsWith("http://127.0.0.1:") == true) { "HERMES_FIXTURE_START_FAILED" }
            val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val auth = GatewayAuthClient(GatewayTransport(GatewayProfile("pre", "pre", "pre", baseUrl!!)), "install_batch_interop", "test", 35)
            val negotiated = auth.negotiate("neg_batch_interop")
            assertTrue("message-batches-v1" in negotiated.conversationUi)
            val session = auth.loginWithPassword(negotiated.negotiationId, "alice", "android-fixture-only".toCharArray(), "批次互通", ed25519WirePublicKey(keyPair.public))
            val profile = GatewayProfile(session.accountId, session.deviceId, session.sessionId, baseUrl, accessToken = session.accessToken)
            val health = EventStreamStatusSink()
            val http = GatewayHttpClient(profile, GatewayTransport(profile), { bytes ->
                Signature.getInstance("Ed25519").run { initSign(keyPair.private); update(bytes); sign() }
            }, InMemoryEventCursorStore(), webSocketTransport = null, statusSink = health)
            val repository = GatewayConversationRepository(ConversationClient(http))
            val scope = ConversationScope("profile", baseUrl, session.accountId, "install_batch_interop")
            val conversation = repository.createConversation(scope, "cconv_batch_interop")
            val replies = Channel<GatewayEvent>(Channel.BUFFERED)
            val subscription = launch(start = CoroutineStart.UNDISPATCHED) {
                http.events().collect { event -> if (event.event == "conversation.message.completed") replies.send(event) }
            }
            try {
                // 先建立真实订阅，再提交；回复必须即时投递，不能只在重连后读取。
                http.execute(SignedGatewayRequest("GET", "/open-android-intelligence/v2/sync/snapshot")).requireData("SYNC_SNAPSHOT")
                withTimeout(10_000) { health.status.first { it == EventStreamStatus.LIVE } }
                val batch = MessageBatch("cb_batch_interop", listOf(
                    OutgoingMessage(ClientMessageId("cm_batch_one"), "第一条，保留空格 "),
                    OutgoingMessage(ClientMessageId("cm_batch_two"), "第二条消息"),
                ), conversation.id.value)
                val accepted = repository.submitBatch(conversation.id.value, batch)
                assertEquals(2, accepted.memberIds.size)
                assertEquals(2, accepted.memberIds.values.toSet().size)
                val reply = withTimeout(10_000) { replies.receive() }
                val message = (GatewayEventDecoder.decode(reply) as VerifiedConversationEvent.TimelineUpsert).message
                assertEquals(conversation.id, message.conversationId)
                assertEquals("fixture-agent-reply:\n第一条，保留空格 \n第二条消息", (message.parts.single() as com.openandroidintelligence.conversation.model.MessagePart.Text).value)
                assertEquals(accepted, repository.submitBatch(conversation.id.value, batch))
                val ordinary = repository.submitMessage(conversation.id.value, OutgoingMessage(ClientMessageId("cm_after_batch"), "下一回合"))
                assertTrue(ordinary.messageId.isNotBlank())
                val next = withTimeout(10_000) { replies.receive() }
                val nextMessage = (GatewayEventDecoder.decode(next) as VerifiedConversationEvent.TimelineUpsert).message
                assertEquals("fixture-agent-reply:\n下一回合", (nextMessage.parts.single() as com.openandroidintelligence.conversation.model.MessagePart.Text).value)
            } finally {
                subscription.cancelAndJoin()
                replies.close()
            }
        } catch (failure: Throwable) {
            // 隔离夹具只记录阶段及测试身份，启动失败时输出实际原因供 CI 定位。
            runCatching { log.readText() }.getOrNull()?.let { diagnostics ->
                System.err.println("Hermes 隔离批次互通夹具日志：")
                System.err.println(diagnostics)
            }
            throw failure
        } finally {
            process.destroy()
            check(process.waitFor(10, TimeUnit.SECONDS)) { "HERMES_FIXTURE_STOP_FAILED" }
        }
        val turns = log.readLines().count { line ->
            runCatching { JsonFields.string(JsonFields.obj(com.openandroidintelligence.gateway.schema.Json.parse(line)), "fixtureEvent") == "agent-turn" }.getOrDefault(false)
        }
        assertEquals("批次重放不能再次执行宿主回合", 2, turns)
    }

    @Test fun passwordLoginCreateUploadVerifyAndSendAgainstShippedHermes() = runBlocking {
        val root = File("../../..").canonicalFile
        val log = File(root, "tmp/bugfix-20260915/hermes-interop.log").also { it.parentFile!!.mkdirs() }
        // The plugin checkout is located by convention rather than only by
        // environment: CI checks it out at .hermes-gateway-plugin, and a reused
        // Gradle daemon can carry the environment of an earlier run, which would
        // fail this test for reasons unrelated to it. An explicit override still
        // wins for local development.
        val pluginRoot = (
            System.getenv("HERMES_PLUGIN_ROOT")?.let { File(it) }
                ?: File(root, ".hermes-gateway-plugin")
            ).canonicalFile
        val fixture = File(pluginRoot, "tests/android_gateway_fixture.py")
        check(fixture.isFile) {
            "未找到 Hermes 网关插件的 fixture: " + fixture +
                "（HERMES_PLUGIN_ROOT=" + System.getenv("HERMES_PLUGIN_ROOT") + "）"
        }
        // This repository owns the contract, so it is passed explicitly rather
        // than letting the plugin fetch its own pinned copy.
        val contractRoot = System.getenv("OPEN_ANDROID_GATEWAY_CONTRACT_ROOT")
            ?: File(root, "gateway-contract").path
        val command = if (contractRoot != null) {
            listOf("python3", fixture.absolutePath, "--contract-root", contractRoot)
        } else {
            listOf("python3", fixture.absolutePath)
        }
        val process = ProcessBuilder(command)
            .directory(pluginRoot).redirectError(log).start()
        try {
            val baseUrl = process.inputStream.bufferedReader().readLine()
            check(baseUrl != null && baseUrl.startsWith("http://127.0.0.1:")) { "Fixture failed: ${log.readText()}" }
            val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val auth = GatewayAuthClient(GatewayTransport(GatewayProfile("pre", "pre", "pre", baseUrl)),
                "install_interop", "test", 35)
            val negotiated = auth.negotiate("neg_interop")
            assertTrue("Hermes serves batches", "message-batches-v1" in negotiated.conversationUi)
            val session = auth.loginWithPassword(negotiated.negotiationId, "alice", "android-fixture-only".toCharArray(),
                "Android interop test", ed25519WirePublicKey(keyPair.public))
            val profile = GatewayProfile(session.accountId, session.deviceId, session.sessionId, baseUrl, accessToken = session.accessToken)
            val http = GatewayHttpClient(profile, GatewayTransport(profile), { bytes ->
                Signature.getInstance("Ed25519").run { initSign(keyPair.private); update(bytes); sign() }
            }, InMemoryEventCursorStore())
            // This HTTP-only fixture has no Agent registry; discovery may be empty.
            val catalog = CommandCatalogClient(http).get("zh-CN")
            assertEquals("agent-command-catalog-1.0", catalog.format)
            assertEquals("zh-CN", catalog.languageCode)
            assertTrue(catalog.catalogVersion.isNotBlank())
            val repository = GatewayConversationRepository(ConversationClient(http))
            val scope = ConversationScope("profile", baseUrl, session.accountId, "install_interop")
            val conversation = repository.createConversation(scope, "cconv_interop")
            assertEquals(conversation.id, repository.listConversations(scope, PageRequest()).conversations.single().id)
            val bytes = "真实 HTTP 附件内容\n+%\u0000".toByteArray()
            val id = AttachmentUploader(HttpAttachmentTransport(http)).upload(
                SelectedAttachment("interop.txt", "text/plain", com.openandroidintelligence.gateway.http.GatewayRequestBody.fromBytes(bytes)),
            )
            val metadata = http.execute(SignedGatewayRequest("GET", "/open-android-intelligence/v2/attachments/$id"))
                .requireData("READ_ATTACHMENT")
            val record = JsonFields.obj(JsonFields.field(metadata, "attachment"))
            assertEquals("uploaded", JsonFields.string(record, "status"))
            assertEquals(bytes.size.toLong(), JsonFields.long(record, "sizeBytes"))
            val accepted = repository.submitMessage(conversation.id.value, OutgoingMessage(ClientMessageId("cmsg_interop"), "请读取附件", listOf(id)))
            assertTrue(accepted.messageId.startsWith("msg_"))

            // 验证切换会话/拉取时间线时，附件元数据完整保留
            val timeline = repository.timeline(conversation.id.value, PageRequest())
            assertEquals(1, timeline.messages.size)
            val msg = timeline.messages.single()
            assertEquals("user", msg.sender)
            assertEquals("请读取附件", (msg.parts.first() as com.openandroidintelligence.conversation.model.MessagePart.Text).value)
            val attPart = msg.parts[1] as com.openandroidintelligence.conversation.model.MessagePart.Attachment
            assertEquals(id, attPart.draftId.value)
            assertEquals("interop.txt", attPart.filename)
            assertEquals("text/plain", attPart.mediaType)
        } catch (failure: Throwable) {
            // The fixture records every request it served; without them a
            // rejection is indistinguishable from a bad signature, an expired
            // session or a contract mismatch.
            runCatching { log.readText() }.getOrNull()?.let {
                System.err.println("=== Hermes fixture 日志 ===")
                System.err.println(it)
            }
            throw failure
        } finally {
            process.destroy()
            check(process.waitFor(10, TimeUnit.SECONDS)) { "Fixture did not stop" }
        }
    }
}
