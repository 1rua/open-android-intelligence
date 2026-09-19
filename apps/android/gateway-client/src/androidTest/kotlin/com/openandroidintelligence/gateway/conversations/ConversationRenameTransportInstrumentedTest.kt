package com.openandroidintelligence.gateway.conversations

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.GatewayTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 设备侧证据：会话重命名确实以一条已签名的 `PATCH` 发出。
 *
 * JDK 的 `HttpURLConnection` 会用 “Invalid HTTP method: PATCH” 拒绝这个方法，
 * 而 App 实际运行的平台实现（OkHttp）允许它，所以“手机端真的发出了 PATCH”只能在
 * 真机/模拟器上观察：这里对一个回环网关断言客户端写出的原始请求行、请求体与签名头。
 *
 * 网关侧“接受并落库”的证据由 `integrations/hermes/tests/test_conversation_rename.py`
 * 承担（同一个线协议，真实 HTTP 边界 + 真实签名）。
 */
@RunWith(AndroidJUnit4::class)
class ConversationRenameTransportInstrumentedTest {

    @Test
    fun renameIsSentAsASignedPatchWithTheClosedTitleBody() = runBlocking {
        val gateway = LoopbackGateway()
        try {
            val baseUrl = "http://127.0.0.1:${gateway.port}"
            val profile = GatewayProfile(
                accountId = "acc_rename",
                deviceId = "dev_rename",
                sessionId = "sess_rename",
                gatewayBaseUrl = baseUrl,
                accessToken = "token_rename",
            )
            val http = GatewayHttpClient(
                profile = profile,
                transport = GatewayTransport(profile),
                signer = { ByteArray(64) },
                cursorStore = InMemoryEventCursorStore(),
            )

            val saved = ConversationClient(http).updateConversationTitle("conv_rename", TITLE)

            assertTrue("重命名必须被网关接受（2xx）", saved)
            val request = gateway.await()
            assertTrue(
                "重命名必须是一条 PATCH，而不是被降级成别的方法：${request.requestLine}",
                request.requestLine.startsWith(
                    "PATCH /open-android-intelligence/v2/conversations/conv_rename HTTP/1.1",
                ),
            )
            assertEquals("请求体必须是封闭的 title 对象", """{"title":"$TITLE"}""", request.body)
            assertNotNull(
                "PATCH 也必须带签名头",
                request.header("X-Open-Android-Intelligence-Signature"),
            )
            assertEquals(
                "变更请求的 Idempotency-Key 必须绑定同一个 request id",
                request.header("X-Open-Android-Intelligence-Request-Id"),
                request.header("Idempotency-Key"),
            )
        } finally {
            gateway.close()
        }
    }

    private companion object {
        const val TITLE = "量子计算与经典物理的核心区别"
    }
}

/** 一条已经写出的原始请求。 */
private class RecordedRequest(
    val requestLine: String,
    val headers: List<String>,
    val body: String,
) {
    fun header(name: String): String? = headers
        .firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
}

/**
 * 只服务一条请求的回环网关。
 *
 * 它记录客户端真正写出的字节，并回一个成功信封：会话重命名的成功判定只看状态码，
 * 响应体只用于确认这条链路上没有任何自动降级。
 */
private class LoopbackGateway {
    private val server = ServerSocket(0)
    private val received = CompletableFuture<RecordedRequest>()
    private val worker = thread(name = "rename-loopback-gateway") {
        try {
            server.accept().use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val lines = readHead(input).split("\r\n").filter { it.isNotEmpty() }
                val contentLength = lines.drop(1)
                    .firstOrNull { it.substringBefore(':').trim().equals("Content-Length", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                val body = String(input.readExactly(contentLength), Charsets.UTF_8)
                received.complete(RecordedRequest(lines.first(), lines.drop(1), body))
                respond(socket.getOutputStream())
            }
        } catch (error: Throwable) {
            received.completeExceptionally(error)
        }
    }

    val port: Int = server.localPort

    fun await(): RecordedRequest = received.get(10, TimeUnit.SECONDS)

    fun close() {
        runCatching { server.close() }
        worker.join(1_000)
    }

    private fun respond(output: OutputStream) {
        val payload = """
            {"protocol":"2.0","data":{"conversation":{"conversationId":"conv_rename","title":"量子计算与经典物理的核心区别"}}}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        output.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${payload.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.ISO_8859_1),
        )
        output.write(payload)
        output.flush()
    }

    private fun readHead(input: InputStream): String {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next < 0) break
            head.append(next.toChar())
        }
        return head.toString()
    }

    private fun InputStream.readExactly(length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(bytes, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == length) bytes else bytes.copyOf(offset)
    }
}
