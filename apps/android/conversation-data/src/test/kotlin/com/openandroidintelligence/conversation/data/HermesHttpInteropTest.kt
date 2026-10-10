package com.openandroidintelligence.conversation.data

import com.openandroidintelligence.conversation.model.ClientMessageId
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
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
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
            assertTrue(CommandCatalogClient(http).get("zh-CN").commands.any { it.invocation == "/new" })
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
