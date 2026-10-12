package com.openandroidintelligence.mobile

import com.openandroidintelligence.encrypted.store.EncryptedAttachmentStagingStore
import com.openandroidintelligence.gateway.attachments.AttachmentCreateRequest
import com.openandroidintelligence.gateway.attachments.AttachmentRemoteStatus
import com.openandroidintelligence.gateway.attachments.AttachmentUploader
import com.openandroidintelligence.gateway.attachments.HttpAttachmentTransport
import com.openandroidintelligence.gateway.attachments.SelectedAttachment
import com.openandroidintelligence.gateway.auth.GatewayAuthClient
import com.openandroidintelligence.gateway.auth.ed25519WirePublicKey
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.GatewayConnectionFactory
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.GatewayRequestBody
import com.openandroidintelligence.gateway.http.GatewayTransport
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.SignedGatewayRequest
import com.openandroidintelligence.gateway.http.requireData
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 使用 Android 平台 HTTP 实现和独立 Hermes 的生产入口，锁定默认媒体类型造成的拒绝。 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AndroidAttachmentContentTypeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun androidDefaultContentTypeIsRejectedEvenWhenPlainLengthAndDigestAreCorrect() = runBlocking {
        withGateway { http ->
            val transport = HttpAttachmentTransport(http)
            val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
            val body = GatewayRequestBody.fromBytes(bytes)
            val attachmentId = transport.create(
                AttachmentCreateRequest("att_default_media_type", "sample.jpg", "image/jpeg", body.contentLength, body.sha256Hex),
            )
            val headers = listOf(
                RawHeader("Content-Length", body.contentLength.toString()),
                RawHeader("Digest", "sha-256=" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))),
            )

            // 不设置 Content-Type，让 Android 的原生连接生成真实默认头。
            val rejected = http.execute(
                SignedGatewayRequest("PUT", "$ATTACHMENTS/$attachmentId/content", headers = headers, streamBody = body),
            )
            val failure = runCatching { rejected.requireData("ATTACHMENT_UPLOAD_FAILED") }.exceptionOrNull()
            assertEquals(400, rejected.status)
            assertEquals("ATTACHMENT_UPLOAD_FAILED:ATTACHMENT_DIGEST_MISMATCH", failure?.message)
            assertEquals(AttachmentRemoteStatus.STAGED, transport.getStatus(attachmentId).status)

            // 长度、摘要和正文都不变，只补充创建时声明的媒体类型。
            http.execute(
                SignedGatewayRequest(
                    "PUT", "$ATTACHMENTS/$attachmentId/content",
                    headers = headers + RawHeader("Content-Type", "image/jpeg"), streamBody = body,
                ),
            ).requireData("ATTACHMENT_UPLOAD_FAILED")
            transport.commit(attachmentId)
            assertEquals(AttachmentRemoteStatus.UPLOADED, transport.getStatus(attachmentId).status)
        }
    }

    @Test
    fun selectedJpegUploadsThroughAndroidWithItsDeclaredMediaType() = runBlocking {
        verifySelection("photo.jpg", "image/jpeg", ByteArray(EncryptedAttachmentStagingStore.DEFAULT_CHUNK_BYTES + 7) { (it * 17).toByte() })
    }

    @Test
    fun selectedPdfUploadsThroughAndroidWithItsDeclaredMediaType() = runBlocking {
        verifySelection("document.pdf", "application/pdf", "%PDF-1.7\n附件边界样本\n%%EOF".encodeToByteArray())
    }

    @Test
    fun selectedEmptyFileUploadsThroughAndroidWithItsDeclaredMediaType() = runBlocking {
        verifySelection("empty.bin", "application/octet-stream", ByteArray(0))
    }

    private suspend fun verifySelection(filename: String, mediaType: String, bytes: ByteArray) {
        withGateway { http ->
            val staging = EncryptedAttachmentStagingStore(
                temporaryFolder.newFolder("staging"), SecretKeySpec(ByteArray(32) { (it + 13).toByte() }, "AES"),
                "isolated-gateway/alice/android-platform-test",
            )
            val staged = staging.stage(ByteArrayInputStream(bytes))
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(bytes.size.toLong(), staged.sizeBytes)
            assertEquals(digest, staged.sha256Hex)
            val transport = HttpAttachmentTransport(http)
            val attachmentId = AttachmentUploader(transport).upload(
                SelectedAttachment(
                    filename, mediaType,
                    GatewayRequestBody(staged.sizeBytes, staged.sha256Hex, { staging.openStream(staged.id) }),
                    clientAttachmentId = "att_selected_media_type", declaredSha256 = digest,
                ),
            )

            val status = transport.getStatus(attachmentId)
            assertEquals(AttachmentRemoteStatus.UPLOADED, status.status)
            assertEquals(bytes.size.toLong(), status.sizeBytes)
            assertEquals(digest, status.sha256)
        }
    }

    private suspend fun withGateway(block: suspend (GatewayHttpClient) -> Unit) {
        val root = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "gateway-contract").isDirectory }
        val pluginRoot = (System.getenv("HERMES_PLUGIN_ROOT")?.let(::File)
            ?: File(root, ".hermes-gateway-plugin")).canonicalFile
        val fixture = File(pluginRoot, "tests/android_gateway_fixture.py")
        check(fixture.isFile) { "HERMES_FIXTURE_MISSING" }
        val log = temporaryFolder.newFile("hermes-platform-attachment.txt")
        val process = ProcessBuilder(
            "python3", fixture.path, "--echo-agent", "--contract-root",
            System.getenv("OPEN_ANDROID_GATEWAY_CONTRACT_ROOT") ?: File(root, "gateway-contract").path,
        ).directory(pluginRoot).redirectError(log).start()
        try {
            val firstLine = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readLine() }
            val baseUrl = firstLine.get(20, TimeUnit.SECONDS) ?: error("HERMES_FIXTURE_START_FAILED")
            check(baseUrl.startsWith("http://127.0.0.1:")) { "HERMES_FIXTURE_START_FAILED" }
            val factory = GatewayConnectionFactory(::androidConnection)
            val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val auth = GatewayAuthClient(
                GatewayTransport(GatewayProfile("pre", "pre", "pre", baseUrl), factory),
                "install_android_attachment", "test", 35,
            )
            val negotiated = auth.negotiate("neg_android_attachment")
            val session = auth.loginWithPassword(
                negotiated.negotiationId, "alice", "android-fixture-only".toCharArray(),
                "Android 附件隔离回归", ed25519WirePublicKey(keyPair.public),
            )
            val profile = GatewayProfile(session.accountId, session.deviceId, session.sessionId, baseUrl, accessToken = session.accessToken)
            block(GatewayHttpClient(
                profile, GatewayTransport(profile, factory), { content ->
                    Signature.getInstance("Ed25519").run { initSign(keyPair.private); update(content); sign() }
                }, InMemoryEventCursorStore(), webSocketTransport = null,
            ))
        } finally {
            process.destroy()
            assertTrue("隔离 Gateway 必须结束", process.waitFor(10, TimeUnit.SECONDS))
        }
    }

    // 反射只用于平台隐藏的连接类；请求生成、签名和附件状态均调用生产公开入口。
    private fun androidConnection(url: URL): HttpURLConnection {
        val clientClass = Class.forName("com.android.okhttp.OkHttpClient")
        val client = clientClass.getConstructor().newInstance()
        val defaults = clientClass.getDeclaredMethod("copyWithDefaults").apply { isAccessible = true }
        val readyClient = defaults.invoke(client)
        val connectionClass = Class.forName("com.android.okhttp.internal.huc.HttpURLConnectionImpl")
        return connectionClass.getConstructor(URL::class.java, clientClass).newInstance(url, readyClient) as HttpURLConnection
    }

    private companion object {
        const val ATTACHMENTS = "/open-android-intelligence/v2/attachments"
    }
}
