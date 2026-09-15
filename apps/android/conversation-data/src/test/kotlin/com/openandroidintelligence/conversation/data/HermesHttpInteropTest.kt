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

/** Real HTTP, real password session, real signatures, real Python Gateway Core. */
class HermesHttpInteropTest {
    @Test fun passwordLoginCreateUploadVerifyAndSendAgainstShippedHermes() = runBlocking {
        val root = File("../../..").canonicalFile
        val log = File(root, "tmp/bugfix-20260915/hermes-interop.log").also { it.parentFile!!.mkdirs() }
        val process = ProcessBuilder("python3", "integrations/hermes/tests/android_gateway_fixture.py")
            .directory(root).redirectError(log).start()
        try {
            val baseUrl = process.inputStream.bufferedReader().readLine()
            check(baseUrl != null && baseUrl.startsWith("http://127.0.0.1:")) { "Fixture failed: ${log.readText()}" }
            val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val auth = GatewayAuthClient(GatewayTransport(GatewayProfile("pre", "pre", "pre", baseUrl)),
                "install_interop", "test", 35)
            val negotiated = auth.negotiate("neg_interop")
            assertFalse("Hermes does not advertise batches", "message-batches-v1" in negotiated.conversationUi)
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
            val id = AttachmentUploader(HttpAttachmentTransport(http)).upload(SelectedAttachment("interop.txt", "text/plain", bytes))
            val metadata = http.execute(SignedGatewayRequest("GET", "/open-android-intelligence/v2/attachments/$id"))
                .requireData("READ_ATTACHMENT")
            val record = JsonFields.obj(JsonFields.field(metadata, "attachment"))
            assertEquals("verified", JsonFields.string(record, "state"))
            assertEquals(bytes.size.toLong(), JsonFields.long(record, "sizeBytes"))
            val accepted = repository.submitMessage(conversation.id.value, OutgoingMessage(ClientMessageId("cmsg_interop"), "请读取附件", listOf(id)))
            assertTrue(accepted.messageId.startsWith("msg_"))
        } finally {
            process.destroy()
            check(process.waitFor(10, TimeUnit.SECONDS)) { "Fixture did not stop" }
        }
    }
}
