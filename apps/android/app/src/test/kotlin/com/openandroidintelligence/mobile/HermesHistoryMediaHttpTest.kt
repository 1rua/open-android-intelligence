package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.attachments.*
import com.openandroidintelligence.gateway.auth.GatewayAuthClient
import com.openandroidintelligence.gateway.auth.ed25519WirePublicKey
import com.openandroidintelligence.gateway.conversations.ConversationClient
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 真实 Python 宿主、登录、签名 HTTP 与生产原件库；仅本地文档密钥由 JVM 夹具提供。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HermesHistoryMediaHttpTest {
    @Test fun signedNativeHistoryMediaIsRetainedFromTheRawStreamAndReopensOffline() = runBlocking {
        val root = File("../../..").canonicalFile
        val plugin = resolve(root, System.getenv("HERMES_PLUGIN_ROOT")?.takeIf { it.isNotBlank() } ?: ".hermes-gateway-plugin")
        val sdk = resolve(root, System.getenv("HERMES_SDK_ROOT")?.takeIf { it.isNotBlank() } ?: error("HERMES_SDK_ROOT_REQUIRED"))
        val contract = resolve(root, System.getenv("OPEN_ANDROID_GATEWAY_CONTRACT_ROOT")?.takeIf { it.isNotBlank() } ?: "gateway-contract")
        val fixture = File(plugin, "tests/android_gateway_fixture.py")
        check(fixture.isFile && File(sdk, "hermes_state.py").isFile) { "HERMES_MEDIA_SOURCE_MISSING" }
        val pluginRevision = revision(plugin)
        val sdkRevision = revision(sdk)
        check(sdkRevision == SDK_REVISION) { "HERMES_NATIVE_REVISION_MISMATCH" }
        val output = File("build/test-evidence/hermes-history-media-http").also { it.mkdirs() }
        File(output, "source-revisions.json").writeText(Json.canonical(Json.of(mapOf(
            "pluginRevision" to pluginRevision, "sdkRevision" to sdkRevision,
            "fixtureSha256" to hash(fixture.readBytes()),
            "pluginSourcesSha256" to listOf("core.py", "history_media.py", "host_history.py", "adapter.py")
                .associateWith { hash(File(plugin, "open_android_intelligence_gateway/$it").readBytes()) },
            "mediaClientSha256" to hash(File(root, "apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/EncryptedHistoryMediaCache.kt").readBytes())))))
        val log = File(output, "fixture-stages.log")
        val python = System.getenv("OAI_HERMES_PYTHON")?.takeIf { it.isNotBlank() } ?: "python3"
        val builder = ProcessBuilder(python, fixture.path, "--history-agent", "--contract-root", contract.path)
            .directory(plugin).redirectError(log)
        builder.environment()["HERMES_SDK_ROOT"] = sdk.path
        val process = builder.start()
        try {
            // 冷启动真实 Python 宿主并导入加密依赖；保留启动超时是为了给出可读失败，不是业务时限。
            val base = withTimeout(60_000) { withContext(Dispatchers.IO) { process.inputStream.bufferedReader().readLine() } }
                ?.takeIf { it.startsWith("http://127.0.0.1:") } ?: error("HERMES_MEDIA_FIXTURE_START_FAILED")
            stage("fixture-started")
            val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val auth = GatewayAuthClient(GatewayTransport(GatewayProfile("pre", "pre", "pre", base)), "install_media_http", "test", 35)
            val negotiation = auth.negotiate("neg_media_http")
            val session = auth.loginWithPassword(negotiation.negotiationId, "alice", "android-fixture-only".toCharArray(),
                "媒体 HTTP 互通", ed25519WirePublicKey(pair.public))
            stage("authenticated")
            val profile = GatewayProfile(session.accountId, session.deviceId, session.sessionId, base, accessToken = session.accessToken)
            val production = GatewayTransport(profile)
            val targets = mutableListOf<String>()
            var observedRawStream = false
            val observedTransport = object : GatewayByteTransport by production {
                override suspend fun execute(request: WireRequest): WireResponse {
                    if (request.target.contains("/cache-grant")) {
                        assertTrue(request.target.endsWith("/cache-grant?format=stream-v1"))
                        targets += "cache-grant"
                    }
                    if (request.target.contains("/metadata")) assertTrue(request.target.endsWith("/metadata?format=stream-v1"))
                    return production.execute(request)
                }
                override suspend fun <T> consumeResponse(request: WireRequest, consume: suspend (Int, List<RawHeader>, java.io.InputStream) -> T): T {
                    assertTrue(request.target.contains("/content?format=stream-v1&grantId="))
                    return production.consumeResponse(request) { status, headers, stream ->
                        assertEquals(200, status)
                        assertEquals("stream-v1", headers.single { it.name.equals("X-Open-Android-Intelligence-Media-Format", true) }.value)
                        observedRawStream = true
                        consume(status, headers, stream)
                    }
                }
            }
            val http = GatewayHttpClient(profile, observedTransport, { bytes -> Signature.getInstance("Ed25519").run {
                initSign(pair.private); update(bytes); sign()
            } }, InMemoryEventCursorStore())
            val conversation = ConversationClient(http).createConversation("cc_media_http")
            val image = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
            val attachment = AttachmentUploader(HttpAttachmentTransport(http)).upload(SelectedAttachment("synthetic.png", "image/png", GatewayRequestBody.fromBytes(image)))
            ConversationClient(http).sendMessage(conversation.conversationId, "cm_media_http", "请检查合成图片。", listOf(attachment))
            val metadataTarget = "/open-android-intelligence/v2/conversations/${conversation.conversationId}/attachments/$attachment/metadata?format=stream-v1"
            withTimeout(15_000) {
                while (true) {
                    val response = http.execute(SignedGatewayRequest("GET", metadataTarget))
                    if (response.status == 200) {
                        val data = response.requireData("MEDIA_METADATA_FAILED")
                        val metadata = JsonFields.obj(JsonFields.field(data, "metadata"))
                        if (JsonFields.bool(metadata, "remoteAvailable") == true) break
                    } else {
                        val envelope = JsonFields.obj(Json.parse(response.body.decodeToString()))
                        val code = JsonFields.string(JsonFields.obj(JsonFields.field(envelope, "error")), "code")
                        check(response.status == 400 && code == "ATTACHMENT_NOT_FOUND") { "MEDIA_METADATA_FAILED" }
                    }
                    delay(50)
                }
            }
            stage("host-original-available")
            val context = ApplicationProvider.getApplicationContext<Application>()
            val keys = object : AesGcmKeyProvider { override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 19 }, "AES"); override fun delete() = Unit }
            val scope = ConversationScope("media_http", base, session.accountId, "install_media_http")
            val media = EncryptedHistoryMediaCache(context, scope, http, keys)
            val metadata = media.ensureLocal(conversation.conversationId, attachment)
            assertTrue(observedRawStream)
            assertEquals(listOf("cache-grant"), targets)
            assertEquals(image.size.toLong(), metadata.sizeBytes)
            assertEquals("sha256:${hash(image)}", metadata.sha256)
            stage("signed-stream-retained")
            val offline = EncryptedHistoryMediaCache(context, scope, keyProvider = keys)
            assertEquals(metadata.sha256, offline.ensureLocal(conversation.conversationId, attachment).sha256)
            val restored = offline.openOriginal(conversation.conversationId, attachment).use { it.readBytes() }
            assertArrayEquals(image, restored)
            assertEquals(hash(image), hash(restored))
            stage("offline-reopened")
            val diagnostics = log.readLines().mapNotNull { line -> runCatching { JsonFields.obj(Json.parse(line)) }.getOrNull() }
            assertEquals("native", JsonFields.string(diagnostics.single { JsonFields.string(it, "fixtureMode") == "history-agent" }, "host"))
            offline.clearMedia()
        } finally {
            process.destroy()
            check(process.waitFor(10, TimeUnit.SECONDS)) { "HERMES_MEDIA_FIXTURE_STOP_FAILED" }
        }
    }
    private fun resolve(root: File, value: String) = File(value).let { if (it.isAbsolute) it else File(root, value) }.canonicalFile
    private fun revision(root: File): String {
        val process = ProcessBuilder("git", "-C", root.path, "rev-parse", "HEAD").redirectErrorStream(true).start()
        val value = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0 && value.matches(Regex("[a-f0-9]{40}"))) { "SOURCE_REVISION_REQUIRED" }
        return value
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun stage(value: String) { println(Json.canonical(Json.of(mapOf("mediaHttpStage" to value)))) }
    companion object { private const val SDK_REVISION = "75b083e9399115b12513385252811f09c3503c90" }
}
